package ro.myfinance.extraction.adapter.external;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ro.myfinance.extraction.application.BankStatementParser;
import ro.myfinance.extraction.application.ParsedStatement;
import ro.myfinance.extraction.application.ParsedTransaction;

/**
 * Generic, best-effort fallback parser (lowest priority — tried only when no bank-specific parser
 * matches). Handles statements with single-line transaction rows of the shape
 * "{date} {description} {amount} {running-balance}", in either RO ("1.234,56") or EN ("1,234.56")
 * number format, listed oldest- or newest-first. Signed amounts are derived from the balance chain,
 * so the per-statement cross-check (opening + Σ == closing) validates the parse; anything it can't
 * resolve confidently leads to NEEDS_REVIEW upstream.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class GenericRunningBalanceParser implements BankStatementParser {

    // Monetary token: ends with a decimal separator + exactly 2 digits (excludes IBANs/account nos).
    private static final Pattern MONEY = Pattern.compile("\\d[\\d.,]*[.,]\\d{2}");
    private static final Pattern DATE = Pattern.compile("(\\d{2})[/.](\\d{2})[/.](\\d{2,4})");
    private static final Pattern IBAN = Pattern.compile("\\bRO\\d{2}[A-Z0-9]{10,}\\b");
    private static final String[] OPEN_KW =
            {"sold initial", "sold anterior", "sold precedent", "opening balance", "start balance"};
    private static final String[] CLOSE_KW =
            {"sold final", "closing balance", "end balance"};

    @Override
    public boolean supports(String text) {
        return true; // universal fallback; selected only after specific parsers decline
    }

    @Override
    public ParsedStatement parse(String text) {
        String[] lines = text.split("\\R");
        BigDecimal opening = balanceForKeyword(lines, OPEN_KW);
        BigDecimal closing = balanceForKeyword(lines, CLOSE_KW);

        // Primary: single-line rows ("{date} {desc} {amount} {balance}"). If that finds nothing, the
        // statement is likely a multi-line block layout (date, description and amount+balance on
        // separate lines) — fall back to a block scan that carries the date forward across lines.
        List<Row> rows = parseSingleLineRows(lines);
        if (rows.size() < 2) {
            List<Row> blocks = parseBlockRows(lines);
            if (blocks.size() > rows.size()) {
                rows = blocks;
            }
        }

        boolean newestFirst = isNewestFirst(rows, opening, closing);
        List<ParsedTransaction> txns = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            BigDecimal prev = newestFirst
                    ? (i + 1 < rows.size() ? rows.get(i + 1).balance : opening)
                    : (i > 0 ? rows.get(i - 1).balance : opening);
            BigDecimal signed = (prev != null) ? r.balance.subtract(prev) : null;
            txns.add(new ParsedTransaction(r.date, signed, null, r.iban, r.desc, null, r.balance));
        }
        return new ParsedStatement(null, firstIban(lines), opening, closing, txns);
    }

    /** Rows where the date, amount and running balance all sit on one line (the common statement shape). */
    private List<Row> parseSingleLineRows(String[] lines) {
        List<Row> rows = new ArrayList<>();
        for (String line : lines) {
            if (containsAny(norm(line), OPEN_KW) || containsAny(norm(line), CLOSE_KW)) {
                continue; // balance summary lines, not transactions
            }
            Matcher d = DATE.matcher(line);
            if (!d.find() || d.start() > 3) {
                continue; // a transaction row starts (near) the line with a date
            }
            LocalDate date = parseDate(d.group());
            if (date == null) {
                continue;
            }
            String rest = line.substring(d.end());
            List<BigDecimal> nums = money(rest);
            if (nums.size() < 2) {
                continue; // need at least an amount + a running balance
            }
            BigDecimal balance = nums.get(nums.size() - 1);
            String desc = rest.replaceAll(MONEY.pattern(), "").replaceAll("\\s+", " ").strip();
            Matcher ib = IBAN.matcher(rest);
            String iban = ib.find() ? ib.group() : null;
            rows.add(new Row(date, balance, desc.isBlank() ? null : desc, iban));
        }
        return rows;
    }

    /**
     * Rows for a multi-line block layout: the date is on its own line (carried forward), description
     * lines accumulate, and the block closes on the line that carries the amount + running balance
     * (exactly two money tokens, so a 4-value summary row can't be mistaken for it). Signed amounts are
     * still derived from the balance chain downstream, so the parse self-validates against the totals.
     */
    private List<Row> parseBlockRows(String[] lines) {
        List<Row> rows = new ArrayList<>();
        LocalDate currentDate = null;
        StringBuilder desc = new StringBuilder();
        String iban = null;
        for (String line : lines) {
            String n = norm(line);
            if (containsAny(n, OPEN_KW) || containsAny(n, CLOSE_KW)) {
                continue;
            }
            String rest = line;
            Matcher d = DATE.matcher(line);
            if (d.find() && d.start() <= 3) {
                LocalDate parsed = parseDate(d.group());
                if (parsed != null) {
                    currentDate = parsed;
                    rest = line.substring(d.end());
                }
            }
            Matcher ib = IBAN.matcher(line);
            if (ib.find() && iban == null) {
                iban = ib.group();
            }
            List<BigDecimal> nums = money(rest);
            if (nums.size() == 2 && currentDate != null) {
                String tail = rest.replaceAll(MONEY.pattern(), "").replaceAll("\\s+", " ").strip();
                appendDesc(desc, tail);
                rows.add(new Row(currentDate, nums.get(1), desc.length() == 0 ? null : desc.toString(), iban));
                desc.setLength(0);
                iban = null;
            } else if (currentDate != null && nums.isEmpty()) {
                String tail = rest.replaceAll("\\s+", " ").strip();
                if (!tail.matches("\\d+")) { // skip a bare reference number
                    appendDesc(desc, tail);
                }
            }
        }
        return rows;
    }

    private void appendDesc(StringBuilder desc, String text) {
        if (!text.isBlank() && desc.length() < 180) {
            desc.append(desc.length() > 0 ? " " : "").append(text);
        }
    }

    private boolean isNewestFirst(List<Row> rows, BigDecimal opening, BigDecimal closing) {
        if (rows.isEmpty() || opening == null || closing == null) {
            return false;
        }
        BigDecimal firstBal = rows.get(0).balance;
        BigDecimal toClosing = firstBal.subtract(closing).abs();
        BigDecimal toOpening = firstBal.subtract(opening).abs();
        return toClosing.compareTo(toOpening) < 0; // first row balance closer to closing → newest-first
    }

    private BigDecimal balanceForKeyword(String[] lines, String[] keywords) {
        for (String line : lines) {
            if (containsAny(norm(line), keywords)) {
                List<BigDecimal> nums = money(line);
                if (!nums.isEmpty()) {
                    return nums.get(nums.size() - 1);
                }
            }
        }
        return null;
    }

    private List<BigDecimal> money(String s) {
        List<BigDecimal> out = new ArrayList<>();
        Matcher m = MONEY.matcher(s);
        while (m.find()) {
            out.add(parseAmount(m.group()));
        }
        return out;
    }

    /**
     * Parse a monetary token in RO ("1.234,56") or EN ("1,234.56") format — robust to multiple grouping
     * separators (e.g. "1.234.567,89"). The decimal point is the last '.'/',' followed by exactly two
     * digits; every other separator is a thousands grouping and is stripped.
     */
    private BigDecimal parseAmount(String token) {
        int lastSep = Math.max(token.lastIndexOf('.'), token.lastIndexOf(','));
        if (lastSep >= 0 && token.length() - lastSep - 1 == 2) {
            String intPart = token.substring(0, lastSep).replaceAll("[.,]", "");
            return new BigDecimal((intPart.isEmpty() ? "0" : intPart) + "." + token.substring(lastSep + 1));
        }
        String digits = token.replaceAll("[.,]", "");
        return new BigDecimal(digits.isEmpty() ? "0" : digits);
    }

    private LocalDate parseDate(String token) {
        for (String pat : new String[] {"dd/MM/uuuu", "dd/MM/uu", "dd.MM.uuuu", "dd.MM.uu"}) {
            try {
                return LocalDate.parse(token, DateTimeFormatter.ofPattern(pat));
            } catch (RuntimeException ignored) {
                // try next pattern
            }
        }
        return null;
    }

    private String firstIban(String[] lines) {
        for (String l : lines) {
            Matcher m = IBAN.matcher(l);
            if (m.find()) {
                return m.group();
            }
        }
        return null;
    }

    private String norm(String s) {
        return s.toLowerCase();
    }

    private boolean containsAny(String haystack, String[] needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private record Row(LocalDate date, BigDecimal balance, String desc, String iban) {
    }
}
