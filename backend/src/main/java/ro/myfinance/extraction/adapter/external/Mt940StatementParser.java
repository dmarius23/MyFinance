package ro.myfinance.extraction.adapter.external;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * Parser for <b>SWIFT MT940</b> ("Customer Statement Message") — the classic structured statement
 * export. Like CAMT.053 it is bank-agnostic and carries explicit signed amounts and opening/closing
 * balances, so it's preferred over PDF scraping. Fields are tag-delimited:
 *
 * <pre>
 *   :25:  account identification (IBAN)
 *   :60F: opening balance   (C/D + YYMMDD + CCY + amount)
 *   :61:  statement line     (value date, D/C mark, amount, type, reference)
 *   :86:  information to account owner (counterparty / remittance)
 *   :62F: closing balance
 * </pre>
 *
 * Amounts use a comma decimal ("17977,65"). Second-highest precedence (after CAMT), before PDF parsers.
 */
@Component
@Order(2)
public class Mt940StatementParser implements BankStatementParser {

    // :61: content: value date (YYMMDD), optional entry date (MMDD), C/D/RC/RD mark, amount, then type.
    private static final Pattern LINE_61 =
            Pattern.compile("^(\\d{6})(\\d{4})?(RC|RD|EC|ED|C|D)([\\d.,]+)");
    // :60F:/:62F: content: C/D mark, date (YYMMDD), 3-letter currency, amount.
    private static final Pattern BALANCE = Pattern.compile("^([CD])(\\d{6})([A-Z]{3})([\\d.,]+)");
    private static final Pattern IBAN = Pattern.compile("\\bRO\\d{2}[A-Z0-9]{10,}\\b");
    private static final Pattern TAG = Pattern.compile("^:(\\d{2}[A-Z]?):(.*)$");

    @Override
    public boolean supports(String text) {
        if (text == null) {
            return false;
        }
        return text.contains(":61:") && (text.contains(":60F:") || text.contains(":60M:"));
    }

    @Override
    public ParsedStatement parse(String text) {
        // Collapse each tag with its continuation lines into one logical field.
        List<String[]> fields = fields(text);

        String accountIban = null;
        BigDecimal opening = null;
        BigDecimal closing = null;
        List<ParsedTransaction> txns = new ArrayList<>();
        ParsedTransaction pending = null; // a :61: line awaiting its :86: description

        for (String[] f : fields) {
            String tag = f[0];
            String value = f[1];
            switch (tag) {
                case "25" -> accountIban = ibanOf(value);
                case "60F", "60M" -> {
                    BigDecimal b = balance(value);
                    if (opening == null) {
                        opening = b; // opening = first opening balance (60M = intermediate on multi-page)
                    }
                }
                case "62F", "62M" -> closing = balance(value); // closing = last closing balance
                case "61" -> {
                    if (pending != null) {
                        txns.add(pending);
                    }
                    pending = line61(value);
                }
                case "86" -> {
                    if (pending != null) {
                        pending = withInfo(pending, value);
                    }
                }
                default -> { /* ignore other tags */ }
            }
        }
        if (pending != null) {
            txns.add(pending);
        }
        return new ParsedStatement("MT940", accountIban, opening, closing, txns);
    }

    /** Split into (tag, value) fields, appending continuation lines (no leading ":NN:") to the value. */
    private List<String[]> fields(String text) {
        List<String[]> out = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            Matcher m = TAG.matcher(raw);
            if (m.matches()) {
                out.add(new String[] {m.group(1), m.group(2)});
            } else if (!out.isEmpty() && !raw.isBlank()) {
                String[] last = out.get(out.size() - 1);
                last[1] = last[1] + "\n" + raw;
            }
        }
        return out;
    }

    private ParsedTransaction line61(String value) {
        Matcher m = LINE_61.matcher(value);
        if (!m.find()) {
            return new ParsedTransaction(null, null, null, null, null, null, null);
        }
        LocalDate date = valueDate(m.group(1));
        BigDecimal amount = money(m.group(4));
        if (amount != null && isDebit(m.group(3))) {
            amount = amount.negate();
        }
        // The customer reference follows the amount+type; e.g. "…NTRF NONREF//bankref".
        String ref = null;
        int sep = value.indexOf("//");
        if (sep >= 0) {
            ref = value.substring(sep + 2).split("\\R", 2)[0].trim();
        }
        String iban = ibanOf(value);
        return new ParsedTransaction(date, amount, null, iban, null, blankToNull(ref), null);
    }

    /** Attach the :86: information: description text and, where structured, the counterparty name/IBAN. */
    private ParsedTransaction withInfo(ParsedTransaction t, String info) {
        String flat = info.replace("\n", " ").trim();
        String name = null;
        String description = flat;
        if (flat.contains("?")) {
            // Structured :86: — subfields ?NN. Name is ?32/?33; remittance is ?20–?29.
            name = subfield(flat, "32") + subfield(flat, "33");
            name = name.isBlank() ? null : name.trim();
            StringBuilder rmt = new StringBuilder();
            for (int i = 20; i <= 29; i++) {
                rmt.append(subfield(flat, String.valueOf(i)));
            }
            description = rmt.length() > 0 ? rmt.toString().trim() : flat;
        }
        String iban = t.partnerIban() != null ? t.partnerIban() : ibanOf(flat);
        return new ParsedTransaction(t.date(), t.amount(), blankToNull(name), iban,
                blankToNull(description), t.ref(), t.balanceAfter());
    }

    private String subfield(String flat, String code) {
        Matcher m = Pattern.compile("\\?" + code + "([^?]*)").matcher(flat);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(m.group(1));
        }
        return sb.toString();
    }

    private BigDecimal balance(String value) {
        Matcher m = BALANCE.matcher(value.trim());
        if (!m.find()) {
            return null;
        }
        BigDecimal v = money(m.group(4));
        return v != null && "D".equals(m.group(1)) ? v.negate() : v;
    }

    private boolean isDebit(String mark) {
        return mark.equals("D") || mark.equals("RC") || mark.equals("ED"); // RC = reversed credit
    }

    private LocalDate valueDate(String yymmdd) {
        try {
            int y = 2000 + Integer.parseInt(yymmdd.substring(0, 2));
            int mo = Integer.parseInt(yymmdd.substring(2, 4));
            int d = Integer.parseInt(yymmdd.substring(4, 6));
            return LocalDate.of(y, mo, d);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String ibanOf(String s) {
        // Try the text as-is first (an IBAN token in free text is space-delimited, so its word boundary
        // is intact); only if that fails, collapse spaces (a spaced account IBAN like "RO98 INGB …").
        Matcher m = IBAN.matcher(s);
        if (m.find()) {
            return m.group();
        }
        m = IBAN.matcher(s.replaceAll("\\s", ""));
        return m.find() ? m.group() : null;
    }

    /** Parse an MT940 amount: comma is the decimal separator ("1234,56" → 1234.56). */
    private BigDecimal money(String token) {
        String t = token.trim();
        int lastSep = Math.max(t.lastIndexOf(','), t.lastIndexOf('.'));
        if (lastSep >= 0 && t.length() - lastSep - 1 <= 2 && t.length() - lastSep - 1 > 0) {
            String intPart = t.substring(0, lastSep).replaceAll("[.,]", "");
            String frac = t.substring(lastSep + 1);
            return new BigDecimal((intPart.isEmpty() ? "0" : intPart) + "." + frac);
        }
        String digits = t.replaceAll("[.,]", "");
        return digits.isEmpty() ? null : new BigDecimal(digits);
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
