package ro.myfinance.extraction.adapter.external;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ro.myfinance.extraction.application.BankStatementParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;

/**
 * Parser for Revolut monthly statement PDFs (business accounts, RON).
 *
 * <p>Revolut exports a combined PDF that may contain multiple sub-statements — one per currency
 * (AUD, EUR, RON…). Each sub-statement is a full-page section with its own Balance summary and
 * transaction table. Only RON-currency sections are extracted; AUD and other foreign-currency
 * sections are ignored (they show the other side of FX exchanges which would double-count).
 *
 * <p>Transaction table layout (multi-line blocks, newest-first within each section):
 * <pre>
 *   4 Dec 2024   MOS   To Codesio Software SRL • Transfer   44 491.82 RON        0.00 RON
 *                      Reference: 674ff577-...
 *                      To account: RO68BTRLRONCRT0CH3184101
 * </pre>
 * Columns: Date | Type code | Description | Money out | Money in | Balance (right-aligned).
 * Sign is derived from the running balance chain (prev_balance - curr_balance for debits, vice
 * versa for credits) since the wide multi-column layout collapses on position extraction.
 */
@Component
@Order(5) // before BRD (10), BT (15), ING (20), and the generic fallback
public class RevolutStatementParser implements BankStatementParser {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    // e.g. "4 Dec 2024", "18 December 2024"
    private static final Pattern DATE_PAT = Pattern.compile(
            "\\b(\\d{1,2})\\s+(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|" +
            "Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    // Amount in RON: "44 491.82 RON" or "0.00 RON" (space is thousands separator in Revolut)
    private static final Pattern RON_AMOUNT = Pattern.compile(
            "([\\d ]+\\.\\d{2})\\s+RON");

    // RO IBAN or LT IBAN (international)
    private static final Pattern IBAN_PAT = Pattern.compile(
            "\\b(RO\\d{2}[A-Z0-9]{4}[A-Za-z0-9]{16}|LT\\d{2}[A-Z0-9]{4}[0-9]{11})\\b");

    // "To account: RO68BTRL..." on a description continuation line
    private static final Pattern TO_ACCOUNT = Pattern.compile(
            "To account:\\s*([A-Z0-9 ]+)", Pattern.CASE_INSENSITIVE);

    // Opening/closing balance in the Balance summary block
    private static final Pattern OPEN_BAL = Pattern.compile(
            "Opening balance[\\s\\S]{0,40}?([\\d ]+\\.\\d{2})\\s+RON");
    private static final Pattern CLOSE_BAL = Pattern.compile(
            "Closing balance[\\s\\S]{0,40}?([\\d ]+\\.\\d{2})\\s+RON");

    @Override
    public boolean supports(String text) {
        String lo = text.toLowerCase(Locale.ROOT);
        return lo.contains("revolut") && lo.contains("money out") && lo.contains("money in");
    }

    @Override
    public ParsedStatement parse(String text) {
        List<RonSection> sections = splitIntoRonSections(text);

        BigDecimal opening = sections.stream()
                .map(s -> s.opening).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal closing = sections.stream()
                .map(s -> s.closing).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Primary IBAN: the first local (RO) IBAN found in any RON section.
        String iban = sections.stream()
                .map(s -> s.iban).filter(Objects::nonNull)
                .findFirst().orElse(null);

        // Merge all RON transactions, sorted by date (oldest first).
        List<ParsedTransaction> txns = sections.stream()
                .flatMap(s -> s.transactions.stream())
                .sorted(Comparator.comparing(t -> t.date()))
                .toList();

        return new ParsedStatement("REVOLUT", iban, opening, closing, txns);
    }

    // ---- section splitting -----------------------------------------------------------------------

    /**
     * Splits the raw PDF text into per-sub-statement chunks at each "Monthly statement" page header,
     * then keeps only those whose currency is RON.
     */
    private List<RonSection> splitIntoRonSections(String text) {
        // Sub-statements are separated by the "Monthly statement" page header.
        String[] chunks = text.split("(?=Monthly statement)");
        List<RonSection> out = new ArrayList<>();
        for (String chunk : chunks) {
            if (!isRonSection(chunk)) continue;
            RonSection s = parseSection(chunk);
            if (s != null) out.add(s);
        }
        return out;
    }

    /** A section is RON if it explicitly declares "Currency RON" (not AUD, EUR, etc.). */
    private boolean isRonSection(String chunk) {
        // Currency line appears right-aligned: "Currency                                          RON"
        return chunk.contains("Currency") && chunk.matches("(?s).*Currency\\s+RON.*");
    }

    private RonSection parseSection(String chunk) {
        BigDecimal opening = extractBalanceLine(OPEN_BAL, chunk);
        BigDecimal closing = extractBalanceLine(CLOSE_BAL, chunk);
        String iban = extractAccountIban(chunk);
        List<ParsedTransaction> txns = parseTransactionTable(chunk, opening);
        return new RonSection(opening, closing, iban, txns);
    }

    // ---- balance extraction ---------------------------------------------------------------------

    private BigDecimal extractBalanceLine(Pattern p, String chunk) {
        Matcher m = p.matcher(chunk);
        if (m.find()) {
            return parseRonAmount(m.group(1));
        }
        return null;
    }

    // ---- IBAN extraction ------------------------------------------------------------------------

    private String extractAccountIban(String chunk) {
        // Prefer "Type ... Local" + subsequent "IBAN" line (the local RO IBAN).
        // Revolut layout: "Type    Local\n  IBAN    RO04 BREL..."
        Pattern localIban = Pattern.compile(
                "Type\\s+Local[\\s\\S]{0,200}?IBAN\\s+([A-Z]{2}[\\d A-Z]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = localIban.matcher(chunk);
        if (m.find()) {
            return normalizeIban(m.group(1));
        }
        // Fallback: first RO IBAN found in the section header area (before the transaction table).
        String header = chunk.length() > 3000 ? chunk.substring(0, 3000) : chunk;
        Matcher im = IBAN_PAT.matcher(header);
        while (im.find()) {
            String candidate = im.group(1).replaceAll("\\s", "");
            if (candidate.startsWith("RO")) return candidate;
        }
        return null;
    }

    private String normalizeIban(String raw) {
        return raw.replaceAll("\\s", "");
    }

    // ---- transaction table parsing --------------------------------------------------------------

    /**
     * Parses the transaction table section (between "Transactions from" and "Transaction types").
     * Each transaction is a multi-line block: the first line carries the date, type code, description
     * and amounts; continuation lines carry the reference, "To account:" etc.
     *
     * <p>Sign is derived from the running balance: amount = balance[i] - balance[i-1]. The table
     * lists transactions newest-first (the first row's balance is closest to the closing balance),
     * so we reverse the list before computing deltas.
     */
    private List<ParsedTransaction> parseTransactionTable(String chunk, BigDecimal opening) {
        // Isolate the transaction table.
        int start = chunk.indexOf("Transactions from");
        int end = chunk.indexOf("Transaction types");
        if (start < 0 || end < 0 || end <= start) return List.of();
        String table = chunk.substring(start, end);

        // Each block starts with a line that begins with a date.
        String[] lines = table.split("\\R");
        List<Block> blocks = new ArrayList<>();
        Block current = null;
        for (String line : lines) {
            Matcher dm = DATE_PAT.matcher(line);
            if (dm.find() && dm.start() < 12) {
                // New transaction block starts here.
                if (current != null) blocks.add(current);
                current = new Block(parseDate(dm.group()), line);
            } else if (current != null) {
                current.lines.add(line);
            }
        }
        if (current != null) blocks.add(current);

        // Convert blocks to ParsedTransaction (balance only for now, no sign yet).
        List<ParsedTransaction> unsorted = new ArrayList<>();
        for (Block b : blocks) {
            ParsedTransaction t = buildTransaction(b);
            if (t != null) unsorted.add(t);
        }
        if (unsorted.isEmpty()) return List.of();

        // Derive signed amounts from the balance chain. Revolut tables list newest-first
        // (confirmed from the Dec statement: opening=0, EXI balance=44491.82, MOS balance=0.00
        // but MOS row appears before EXI). We reverse the block list so index 0 = oldest.
        // sign(amount) = balance[i] - balance[i-1]
        List<ParsedTransaction> chronological = new ArrayList<>(unsorted);
        java.util.Collections.reverse(chronological);
        List<ParsedTransaction> result = new ArrayList<>();
        BigDecimal prev = opening != null ? opening : BigDecimal.ZERO;
        for (ParsedTransaction t : chronological) {
            BigDecimal balance = t.balanceAfter();
            BigDecimal signed = balance != null ? balance.subtract(prev) : null;
            result.add(new ParsedTransaction(t.date(), signed, t.partnerName(),
                    t.partnerIban(), t.description(), t.ref(), balance));
            if (balance != null) prev = balance;
        }
        return result;
    }

    private ParsedTransaction buildTransaction(Block b) {
        if (b.date == null) return null;
        String firstLine = b.firstLine;

        // Extract all RON amounts from the first line; the last one is the running balance.
        List<BigDecimal> amounts = extractRonAmounts(firstLine);
        BigDecimal balance = amounts.isEmpty() ? null : amounts.get(amounts.size() - 1);

        // Description: everything after the type code (3 uppercase letters), stripped of amounts.
        String desc = extractDescription(firstLine);

        // Continuation lines: grab "To account:" IBAN and the reference.
        String partnerIban = null;
        String ref = null;
        StringBuilder extraDesc = new StringBuilder();
        for (String line : b.lines) {
            String stripped = line.strip();
            if (stripped.isBlank()) continue;
            Matcher ta = TO_ACCOUNT.matcher(stripped);
            if (ta.find()) {
                String candidate = ta.group(1).trim().replaceAll("\\s", "");
                if (IBAN_PAT.matcher(candidate).matches()) {
                    partnerIban = candidate;
                }
                continue;
            }
            if (stripped.startsWith("Reference:")) {
                ref = stripped.substring("Reference:".length()).strip();
                continue;
            }
            // FX rate and other notes — append to description.
            if (!stripped.isEmpty()) {
                extraDesc.append(" ").append(stripped);
            }
        }
        String fullDesc = (desc + extraDesc).strip();

        // Partner name: for MOS/MOR lines the description starts with the counterparty name.
        String partnerName = extractPartnerName(fullDesc);

        return new ParsedTransaction(b.date, null, partnerName, partnerIban,
                fullDesc.isEmpty() ? null : fullDesc, ref, balance);
    }

    /** Extract description text from the first line of a transaction block. */
    private String extractDescription(String line) {
        // Pattern: "  4 Dec 2024  <spaces> MOS  <spaces> Description text   amount   amount"
        // Strip the date token first, then the 3-letter type code, then trim amounts from the right.
        Matcher dm = DATE_PAT.matcher(line);
        String afterDate = dm.find() ? line.substring(dm.end()) : line;
        // Strip leading type code (3 uppercase letters like MOS, EXI, EXO, MOA, etc.)
        afterDate = afterDate.replaceFirst("^\\s+[A-Z]{2,3}\\s+", " ");
        // Remove RON amounts from the end of the description.
        afterDate = RON_AMOUNT.matcher(afterDate).replaceAll(" ").strip();
        // Remove AU$ amounts (inline FX notes).
        afterDate = afterDate.replaceAll("AU\\$[\\d ,]+\\.\\d{2}", "").strip();
        return afterDate.replaceAll("\\s{2,}", " ").strip();
    }

    /** Try to extract a partner name (the first sentence of a description, e.g. "To Codesio Software SRL"). */
    private String extractPartnerName(String desc) {
        if (desc == null || desc.isBlank()) return null;
        // Common patterns: "To {name} • {details}", "{name} • {details}", "Money added from {name}"
        String clean = desc.replaceAll("•.*", "").replaceAll("\\bTransfer\\b.*", "").strip();
        if (clean.toLowerCase().startsWith("to ")) clean = clean.substring(3).strip();
        if (clean.toLowerCase().startsWith("money added from ")) clean = clean.substring("money added from ".length()).strip();
        return clean.length() > 1 && clean.length() < 80 ? clean : null;
    }

    // ---- amount parsing -------------------------------------------------------------------------

    private List<BigDecimal> extractRonAmounts(String line) {
        List<BigDecimal> out = new ArrayList<>();
        Matcher m = RON_AMOUNT.matcher(line);
        while (m.find()) {
            out.add(parseRonAmount(m.group(1)));
        }
        return out;
    }

    /** Parse a Revolut RON amount string like "44 491.82" (space = thousands separator). */
    private BigDecimal parseRonAmount(String raw) {
        String digits = raw.replaceAll("\\s", ""); // remove space thousands separator
        try {
            return new BigDecimal(digits);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    // ---- date parsing ---------------------------------------------------------------------------

    private LocalDate parseDate(String token) {
        // Normalize abbreviated months to full (pattern already accepts both via regex alternation).
        try {
            return LocalDate.parse(token.trim(), DATE_FMT);
        } catch (Exception e) {
            // Try with full month name via MMMM pattern.
            try {
                return LocalDate.parse(token.trim(),
                        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH));
            } catch (Exception ex) {
                return null;
            }
        }
    }

    // ---- inner types ----------------------------------------------------------------------------

    private record RonSection(BigDecimal opening, BigDecimal closing, String iban,
                               List<ParsedTransaction> transactions) {
    }

    private static class Block {
        final LocalDate date;
        final String firstLine;
        final List<String> lines = new ArrayList<>();

        Block(LocalDate date, String firstLine) {
            this.date = date;
            this.firstLine = firstLine;
        }
    }
}
