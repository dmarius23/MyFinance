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
 * Parser for ING Bank ("Extras de cont") statements. Unlike single-line-row statements, ING prints each
 * transaction as a multi-line block:
 *
 * <pre>
 *   02.01.2026                     &lt;- processing date (own line)
 *   8055                           &lt;- bank reference (own line)
 *   SILMARIL SOFTWARE S.R.L.       &lt;- beneficiary / ordonator
 *   RO42INGB0000999911202782       &lt;- counterparty IBAN (optional)
 *   CUI:43756149                   &lt;- counterparty fiscal code (optional)
 *   Incasare                       &lt;- transaction type / description (one or more lines)
 *   MERIC1039
 *   181.50 17,989.15               &lt;- signed amount + running balance (closes the block)
 * </pre>
 *
 * The generic running-balance parser can't read this (it expects the amount on the date line), so it
 * yields zero transactions. Here a block runs from a stand-alone {@code dd.MM.yyyy} date line to its
 * amount+balance line (the first line carrying exactly two money tokens); debits are printed negative.
 */
@Component
@Order(20)
public class IngStatementParser implements BankStatementParser {

    private static final Pattern DATE_LINE = Pattern.compile("^\\s*(\\d{2}\\.\\d{2}\\.\\d{4})\\s*$");
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    // Signed money token (RO "1.234,56" / EN "1,234.56"); the sign carries the debit/credit direction.
    private static final Pattern SIGNED_MONEY = Pattern.compile("(-?)(\\d[\\d.,]*[.,]\\d{2})");
    // A counterparty IBAN is printed without spaces; the account's own IBAN is spaced, so it won't match.
    private static final Pattern IBAN = Pattern.compile("\\bRO\\d{2}[A-Z0-9]{10,}\\b");
    private static final Pattern ALL_DIGITS = Pattern.compile("\\d+");

    @Override
    public boolean supports(String text) {
        return text.contains("INGBROBU") || text.contains("ING Bank N.V.");
    }

    @Override
    public ParsedStatement parse(String text) {
        String[] lines = text.split("\\R");
        String accountIban = accountIban(lines);
        BigDecimal[] summary = openingClosing(lines);

        // Every stand-alone date line starts a transaction block; add a sentinel to bound the last one.
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (DATE_LINE.matcher(lines[i]).matches()) {
                starts.add(i);
            }
        }
        starts.add(lines.length);

        List<ParsedTransaction> txns = new ArrayList<>();
        for (int b = 0; b + 1 < starts.size(); b++) {
            ParsedTransaction t = parseBlock(lines, starts.get(b), starts.get(b + 1));
            if (t != null) {
                txns.add(t);
            }
        }
        return new ParsedStatement("INGB", accountIban, summary[0], summary[1], txns);
    }

    /** Parse one block [from, to); returns null when no amount+balance line is present. */
    private ParsedTransaction parseBlock(String[] lines, int from, int to) {
        LocalDate date = parseDate(lines[from].trim());
        if (date == null) {
            return null;
        }
        // The amount+balance line is the first block line with exactly two money tokens (the summary
        // line has four; "Suma …"/"Rata …" FX lines have one), so it can't be confused with them.
        int balIdx = -1;
        List<String> amounts = null;
        for (int i = from + 1; i < to; i++) {
            List<String> m = signedTokens(lines[i]);
            if (m.size() == 2) {
                balIdx = i;
                amounts = m;
                break;
            }
        }
        if (balIdx < 0) {
            return null;
        }
        BigDecimal amount = signedAmount(amounts.get(0));
        BigDecimal balance = magnitude(amounts.get(1));

        String ref = from + 1 < to && ALL_DIGITS.matcher(lines[from + 1].trim()).matches()
                ? lines[from + 1].trim() : null;

        String partnerName = null;
        String partnerIban = null;
        StringBuilder desc = new StringBuilder();
        for (int i = from + 1; i < balIdx; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.equals(ref)) {
                continue;
            }
            Matcher ib = IBAN.matcher(line);
            if (ib.find()) {
                if (partnerIban == null) {
                    partnerIban = ib.group();
                }
                continue; // an IBAN line isn't the name or description
            }
            if (!line.matches(".*[A-Za-z].*")) {
                continue; // skip lines with no letters (card mask, codes)
            }
            if (partnerName == null) {
                partnerName = line;
            }
            if (desc.length() < 180) {
                desc.append(desc.length() > 0 ? " " : "").append(line);
            }
        }
        String description = desc.length() == 0 ? null : desc.toString();
        return new ParsedTransaction(date, amount, partnerName, partnerIban, description, ref, balance);
    }

    /** The account's own IBAN, printed with spaces (e.g. "RO98 INGB 0000 …"); null if not found. */
    private String accountIban(String[] lines) {
        for (String line : lines) {
            String nospace = line.replaceAll("\\s", "");
            Matcher m = IBAN.matcher(nospace);
            if (m.find() && m.group().contains("INGB")) {
                return m.group();
            }
        }
        return null;
    }

    /**
     * Opening + closing balances from the summary row. ING prints the labels
     * ("Sold initial: … Sold final:") on one line and the four values on the next, in fixed order:
     * initial, total credit, total debit, final — followed by the period ("01 - 31.01.2026"). Opening is
     * the first value and closing the fourth; we take the fourth explicitly (not the last token) because
     * the trailing period date would otherwise be misread as an extra money value.
     */
    private BigDecimal[] openingClosing(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            String n = lines[i].toLowerCase();
            if (n.contains("sold initial") && n.contains("sold final")) {
                for (int j = i + 1; j < Math.min(lines.length, i + 3); j++) {
                    List<String> vals = signedTokens(lines[j]);
                    if (vals.size() >= 4) {
                        return new BigDecimal[] {magnitude(vals.get(0)), magnitude(vals.get(3))};
                    }
                }
            }
        }
        return new BigDecimal[] {null, null};
    }

    private List<String> signedTokens(String line) {
        List<String> out = new ArrayList<>();
        for (Matcher m = SIGNED_MONEY.matcher(line); m.find(); ) {
            out.add(m.group());
        }
        return out;
    }

    private BigDecimal signedAmount(String token) {
        boolean neg = token.startsWith("-");
        BigDecimal v = magnitude(token);
        return neg ? v.negate() : v;
    }

    /** Parse a money token (RO/EN) to its magnitude: the last '.'/',' + 2 digits is the decimal point. */
    private BigDecimal magnitude(String token) {
        String t = token.replaceFirst("^-", "");
        int lastSep = Math.max(t.lastIndexOf('.'), t.lastIndexOf(','));
        if (lastSep >= 0 && t.length() - lastSep - 1 == 2) {
            String intPart = t.substring(0, lastSep).replaceAll("[.,]", "");
            return new BigDecimal((intPart.isEmpty() ? "0" : intPart) + "." + t.substring(lastSep + 1));
        }
        String digits = t.replaceAll("[.,]", "");
        return new BigDecimal(digits.isEmpty() ? "0" : digits);
    }

    private LocalDate parseDate(String token) {
        try {
            return LocalDate.parse(token, DMY);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
