package ro.myfinance.extraction.adapter.external;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ro.myfinance.extraction.application.BankStatementParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;

/**
 * Parser for Libra Internet Bank ("EXTRAS CONT") statements. Rows carry separate Debit and Credit
 * columns, exactly one of which is non-zero:
 *
 * <pre>
 *   SOLD INITIAL 2,509.28
 *   Data Descriere operatiune Data Debit Credit Sold
 *   20/04/2026 Tranzactie Platitor:  STRIPE TECHNOLOGY 20/04/2026 0.00 2,533.13
 *   EUROPE LIMITED                       &lt;- description wraps onto following lines
 *   RO49BREL... Detalii:
 *   20/04/2026 Comision tranzactie (FT26110DSVSV) 20/04/2026 3.00 0.00
 *   20/04/2026 Sold nou/New balance 5,035.41      &lt;- daily closing, NOT a transaction
 * </pre>
 *
 * Without this, Libra statements fall through to {@link GenericRunningBalanceParser}, which expects a
 * running balance on the row and mis-reads the two-column layout.
 *
 * <p>Three things about the extracted text drive the implementation:
 *
 * <ul>
 *   <li><b>Rows are matched on their two trailing amounts, not on the "Data Operare" column.</b>
 *       PDFBox runs with {@code setSortByPosition(true)}, which interleaves the overlapping header
 *       word "Operare" into the first data row — the operare date arrives as
 *       {@code O01p/e0r4a/2re026}. Requiring a well-formed date there silently drops that row.</li>
 *   <li><b>Parsing stops at {@code MISCARI PE CONT}.</b> The per-day summary that follows has the same
 *       "date then amounts" shape ({@code 01/04/2026 2,509.28 4.00 0.00 2,505.28}) and would otherwise
 *       be read as transactions, double-counting the whole statement.</li>
 *   <li><b>{@code Sold nou/New balance} rows are skipped.</b> They are daily closing balances; they
 *       carry a single amount so they cannot match a transaction row, but they must also not be
 *       appended to the preceding description.</li>
 * </ul>
 *
 * <p>The statement's own {@code TOTAL} row (opening, total debit, total credit, closing) is parsed and
 * used as a self-check: a parse whose sums disagree with the bank's own totals is reported as having no
 * usable transactions rather than quietly producing wrong figures, since extracted amounts feed
 * reconciliation.
 */
@Component
@Order(25)
public class LibraStatementParser implements BankStatementParser {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/uuuu");

    /**
     * Money token. The integer part is spelled out unambiguously (grouped thousands OR a plain digit
     * run) rather than a "[\d,]*" soup, so the engine cannot re-split a long "1,1,1,…" run — the lazy
     * form is quadratic on semi-trusted statement text (CodeQL java/polynomial-redos).
     */
    private static final String AMOUNT = "(?:\\d{1,3}(?:,\\d{3}){0,6}|\\d{1,15})\\.\\d{2}";

    /**
     * Rows are recognised by TOKENISING, not by one regex over the whole line. The natural spelling,
     * {@code ^(date)\s+(.+?)\s+(AMOUNT)\s+(AMOUNT)\s*$}, is quadratic: the lazy {@code (.+?)} advances
     * one character at a time and at each position {@code \s+} backtracks across the whitespace run,
     * so a line padded with ~40k spaces takes seconds. Splitting on whitespace is linear, and each
     * token is then matched against an anchored, bounded pattern.
     */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern AMOUNT_TOKEN = Pattern.compile(AMOUNT);
    private static final int MIN_ROW_TOKENS = 4; // date + ≥1 description token + debit + credit
    /**
     * A bare {@code dd/MM/yyyy}, matched against a fixed 10-character slice — never scanned across a
     * whole line. The obvious spelling, {@code replaceAll("\\s*\\d{2}/\\d{2}/\\d{4}\\s*$")}, is
     * quadratic: replaceAll retries at every index and the leading {@code \s*} can consume a long run
     * of spaces before failing at each one (CodeQL java/polynomial-redos, high). Statement text is
     * only semi-trusted, so that is a worker-hanging DoS vector, not a theoretical one.
     */
    private static final Pattern BARE_DATE = Pattern.compile("\\d{2}/\\d{2}/\\d{4}");
    private static final int DATE_LENGTH = 10;
    private static final Pattern IBAN = Pattern.compile("\\bRO\\d{2}[A-Z0-9]{10,}\\b");
    /** Libra's own transaction reference, e.g. {@code (FT26110DSVSV)}. */
    private static final Pattern REF = Pattern.compile("\\((FT[A-Z0-9]+)\\)");
    /** Page furniture repeated on every page; never part of a description. */
    private static final Pattern FOOTER = Pattern.compile(
            "LIBRA INTERNET BANK|Pagina \\d+ din|Swift:|Phoenix Tower|Aut\\. BNR|Capital social"
                    + "|Depozitele bancare|Acest extras de cont");

    @Override
    public boolean supports(String text) {
        if (text == null) {
            return false;
        }
        // BREL is Libra's BIC; the footer prints it spaced ("BREL RO BU"), IBANs print it joined.
        return (text.contains("LIBRA INTERNET BANK") || text.contains("librabank.ro")
                || text.contains("BREL RO BU") || text.contains("BREL"))
                && (text.contains("EXTRAS CONT") || text.contains("SOLD INITIAL"));
    }

    @Override
    public ParsedStatement parse(String text) {
        List<ParsedTransaction> txns = new ArrayList<>();
        BigDecimal opening = null;
        BigDecimal[] totals = null;
        Row current = null;

        // Form feeds are stripped defensively: PDFBox does not emit them, but other extractors prefix
        // the first row of each page with \f, which would unanchor ^<date> and drop one row per page.
        String[] lines = text.replace("\f", "").split("\\R");
        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("MISCARI PE CONT")) {
                break; // per-day summary follows; same shape as rows, must not be parsed as such
            }
            String[] tokens = line.isEmpty() ? new String[0] : WHITESPACE.split(line);
            if (opening == null && tokens.length == 3
                    && "SOLD".equals(tokens[0]) && "INITIAL".equals(tokens[1])
                    && AMOUNT_TOKEN.matcher(tokens[2]).matches()) {
                opening = money(tokens[2]);
                continue;
            }
            if (totals == null && tokens.length == 5 && "TOTAL".equals(tokens[0])
                    && allAmounts(tokens, 1)) {
                totals = new BigDecimal[] {money(tokens[1]), money(tokens[2]),
                        money(tokens[3]), money(tokens[4])};
                current = null; // a totals row never continues a description
                continue;
            }
            if (line.isEmpty() || line.contains("Sold nou/New balance") || FOOTER.matcher(line).find()) {
                current = null; // never continue a description across page furniture
                continue;
            }
            Row row = row(tokens);
            if (row != null) {
                current = row;
                txns.add(null); // placeholder; replaced on flush so wrapped lines can still be appended
                current.index = txns.size() - 1;
                flush(txns, current);
                continue;
            }
            if (current != null) {
                current.description = (current.description + " " + line).strip();
                flush(txns, current);
            }
        }

        // The TOTAL row lives inside the summary section, i.e. past the break above, so it needs its
        // own pass. Tokenised for the same linear-cost reason as the transaction rows.
        for (String raw : lines) {
            String line = raw.strip();
            if (!line.startsWith("TOTAL")) {
                continue;
            }
            String[] tokens = WHITESPACE.split(line);
            if (tokens.length == 5 && allAmounts(tokens, 1)) {
                totals = new BigDecimal[] {money(tokens[1]), money(tokens[2]),
                        money(tokens[3]), money(tokens[4])};
            }
        }

        if (opening == null || !reconciles(txns, totals, opening)) {
            // Amounts feed reconciliation, so a parse that disagrees with the bank's own totals must
            // not be published. Returning no transactions marks the statement NEEDS_REVIEW upstream.
            return new ParsedStatement("LIBRA", iban(text), opening,
                    totals == null ? null : totals[3], List.of());
        }
        return new ParsedStatement("LIBRA", iban(text), opening, totals[3], List.copyOf(txns));
    }

    /**
     * A transaction row, or {@code null} if these tokens are not one: first token a date, last two
     * tokens amounts. Everything between is the description — which is why the mangled operare column
     * ({@code O01p/e0r4a/2re026}, produced when PDFBox merges the overlapping "Operare" header into
     * the first row) costs nothing here: it is simply carried as a description token.
     */
    private static Row row(String[] tokens) {
        int n = tokens.length;
        if (n < MIN_ROW_TOKENS
                || !isDate(tokens[0])
                || !AMOUNT_TOKEN.matcher(tokens[n - 2]).matches()
                || !AMOUNT_TOKEN.matcher(tokens[n - 1]).matches()) {
            return null;
        }
        int end = n - 2;
        if (isDate(tokens[end - 1])) {
            end--; // drop the intact "Data Operare" column
        }
        StringBuilder description = new StringBuilder();
        for (int i = 1; i < end; i++) {
            description.append(i > 1 ? " " : "").append(tokens[i]);
        }
        return new Row(LocalDate.parse(tokens[0], DMY), money(tokens[n - 2]), money(tokens[n - 1]),
                description.toString());
    }

    private static boolean isDate(String token) {
        return token.length() == DATE_LENGTH && BARE_DATE.matcher(token).matches();
    }

    private static boolean allAmounts(String[] tokens, int from) {
        for (int i = from; i < tokens.length; i++) {
            if (!AMOUNT_TOKEN.matcher(tokens[i]).matches()) {
                return false;
            }
        }
        return true;
    }

    /** Rewrites the placeholder for {@code row} now its description may have grown. */
    private static void flush(List<ParsedTransaction> txns, Row row) {
        BigDecimal signed = row.credit.signum() != 0 ? row.credit : row.debit.negate();
        Matcher ref = REF.matcher(row.description);
        Matcher iban = IBAN.matcher(row.description);
        txns.set(row.index, new ParsedTransaction(row.date, signed, null,
                iban.find() ? iban.group() : null, row.description,
                ref.find() ? ref.group(1) : null, null));
    }

    /** Sums must equal the statement's own TOTAL row, and opening − debits + credits the closing. */
    private static boolean reconciles(List<ParsedTransaction> txns, BigDecimal[] totals,
                                      BigDecimal opening) {
        if (totals == null || txns.isEmpty()) {
            return false;
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (ParsedTransaction t : txns) {
            if (t.amount().signum() < 0) {
                debits = debits.add(t.amount().negate());
            } else {
                credits = credits.add(t.amount());
            }
        }
        return debits.compareTo(totals[1]) == 0
                && credits.compareTo(totals[2]) == 0
                && opening.subtract(debits).add(credits).compareTo(totals[3]) == 0;
    }

    private static BigDecimal money(String token) {
        return new BigDecimal(token.replace(",", ""));
    }

    private static String iban(String text) {
        Matcher m = IBAN.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** Mutable while its wrapped description lines are still arriving. */
    private static final class Row {
        private final LocalDate date;
        private final BigDecimal debit;
        private final BigDecimal credit;
        private String description;
        private int index;

        private Row(LocalDate date, BigDecimal debit, BigDecimal credit, String description) {
            this.date = date;
            this.debit = debit;
            this.credit = credit;
            this.description = description;
        }
    }
}
