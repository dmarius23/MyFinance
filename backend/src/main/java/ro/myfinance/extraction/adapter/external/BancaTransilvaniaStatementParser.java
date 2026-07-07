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
 * Parser for Banca Transilvania ("EXTRAS CONT") PDF statements. Read from the position-sorted text
 * (see {@code BankStatementParserRegistry}), each operation is:
 *
 * <pre>
 *   03/10/2022 Plata OP intra - canal electronic 300.00        &lt;- date + type + amount (one column)
 *   SERVICII CONTABILE 09.2022;5ANCOR EXPERT
 *   CONSULT;RO42BTRLRONCRT0314443801;BTRLRO22                  &lt;- details: ;partner;IBAN;SWIFT
 *   REF. 833EINT222760373                                      &lt;- bank reference
 *   03/10/2022 RULAJ ZI 300.00 0.00                            &lt;- daily turnover totals (skipped)
 *   SOLD FINAL ZI 104,519.66                                   &lt;- end-of-day balance (skipped)
 * </pre>
 *
 * The RULAJ ZI / SOLD FINAL / SOLD ANTERIOR lines are summaries, not operations — the generic parser
 * mistook them for transactions. Direction comes from the operation type (Plată/Comision → debit,
 * Încasare/Alimentare → credit); the whole parse is cross-checked against opening + Σ = closing.
 */
@Component
@Order(15)
public class BancaTransilvaniaStatementParser implements BankStatementParser {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    /** An operation line: an OPTIONAL leading date + description + a single trailing money amount. */
    private static final Pattern TXN = Pattern.compile("^\\s*(?:(\\d{2}/\\d{2}/\\d{4})\\s+)?(.*\\S)\\s+(\\d[\\d.,]*[.,]\\d{2})\\s*$");
    private static final Pattern DATE_START = Pattern.compile("^\\s*(\\d{2}/\\d{2}/\\d{4})\\b");
    // A same-day follow-up operation prints no date; recognise it by its BT operation-type prefix.
    private static final Pattern OP_START = Pattern.compile("(?i)^(plata|incasare|comision|retragere|alimentare"
            + "|depunere|cumparare|transfer|impozit|rambursare|restituire|dobanda|taxa|poprire|storno|virament"
            + "|ridicare|schimb valutar)\\b");
    private static final Pattern IBAN_LABEL = Pattern.compile("Cod IBAN:\\s*(RO\\d{2}[A-Z0-9]+)");
    private static final Pattern SOLD_ANTERIOR = Pattern.compile("SOLD ANTERIOR\\s+([\\d.,]+)");
    private static final Pattern SOLD_FINAL = Pattern.compile("SOLD FINAL(?: ZI)?\\s+([\\d.,]+)");
    private static final Pattern REF = Pattern.compile("^REF\\.\\s*(\\S+)");
    private static final Pattern IBAN = Pattern.compile("\\bRO\\d{2}[A-Z0-9]{14,}\\b");
    private static final Pattern LEADING_DIGITS = Pattern.compile("^\\d+");

    @Override
    public boolean supports(String text) {
        return (text.contains("Banca Transilvania") || text.contains("BANCA TRANSILVANIA") || text.contains("BTRLRO22"))
                && (text.contains("EXTRAS CONT") || text.contains("SOLD ANTERIOR"));
    }

    @Override
    public ParsedStatement parse(String text) {
        String[] lines = text.split("\\R");
        String iban = firstGroup(lines, IBAN_LABEL);
        BigDecimal opening = firstMoney(lines, SOLD_ANTERIOR);
        BigDecimal closing = lastMoney(lines, SOLD_FINAL);

        // Locate every operation line, carrying the current date forward (BT omits the date on a
        // same-day follow-up operation), so all transactions of a multi-operation day are captured.
        List<Integer> opLines = new ArrayList<>();
        LocalDate[] dateAt = new LocalDate[lines.length];
        LocalDate current = null;
        for (int i = 0; i < lines.length; i++) {
            Matcher d = DATE_START.matcher(lines[i]);
            if (d.find()) {
                LocalDate parsed = parseDate(d.group(1));
                if (parsed != null) {
                    current = parsed;
                }
            }
            dateAt[i] = current;
            if (isOperationLine(lines[i]) && current != null) {
                opLines.add(i);
            }
        }

        List<ParsedTransaction> txns = new ArrayList<>();
        for (int k = 0; k < opLines.size(); k++) {
            int i = opLines.get(k);
            int next = k + 1 < opLines.size() ? opLines.get(k + 1) : lines.length;
            Matcher m = TXN.matcher(lines[i]);
            m.matches();
            String type = m.group(2).trim();
            BigDecimal magnitude = magnitude(m.group(3));
            BigDecimal amount = isCredit(type) ? magnitude : magnitude.negate();

            // Detail lines up to the next operation (or the day's RULAJ/SOLD summary, whichever first).
            String partnerIban = null;
            String ref = null;
            StringBuilder details = new StringBuilder(type);
            for (int j = i + 1; j < next; j++) {
                String ln = lines[j].trim();
                if (ln.isEmpty() || ln.startsWith("SOLD") || ln.contains("RULAJ") || DATE_START.matcher(lines[j]).find()) {
                    continue;
                }
                Matcher rm = REF.matcher(ln);
                if (rm.find()) {
                    ref = rm.group(1);
                    continue;
                }
                Matcher ib = IBAN.matcher(ln.replaceAll("\\s", ""));
                if (ib.find() && partnerIban == null) {
                    partnerIban = ib.group();
                }
                if (details.length() < 220) {
                    details.append(' ').append(ln);
                }
            }
            String detail = details.toString();
            txns.add(new ParsedTransaction(dateAt[i], amount, partnerName(detail), partnerIban, detail, ref,
                    nextBalance(lines, i, next)));
        }
        return new ParsedStatement("BTRL", iban, opening, closing, txns);
    }

    /** An operation line: a trailing money amount, not a summary, and either dated or type-prefixed. */
    private static boolean isOperationLine(String line) {
        Matcher m = TXN.matcher(line);
        if (!m.matches() || isSummary(m.group(2).trim())) {
            return false;
        }
        return m.group(1) != null || OP_START.matcher(m.group(2).trim()).find();
    }

    /**
     * Balance after this operation: the "SOLD FINAL ZI" value below it, before the next operation. Only
     * the day's last operation has one (it's the end-of-day balance); earlier same-day operations return
     * null. The whole parse is still cross-checked via opening + Σamounts = closing.
     */
    private BigDecimal nextBalance(String[] lines, int from, int next) {
        for (int i = from + 1; i < next; i++) {
            Matcher m = SOLD_FINAL.matcher(lines[i]);
            if (m.find()) {
                return magnitude(m.group(1));
            }
        }
        return null;
    }

    private static boolean isSummary(String desc) {
        String d = desc.toLowerCase();
        return d.startsWith("rulaj") || d.startsWith("sold ") || d.contains("rulaj ") || d.contains("sold final");
    }

    /** Incoming operations (money in) vs the debit default (Plată, Comision, Retragere, Impozit…). */
    private static boolean isCredit(String desc) {
        String d = stripDiacritics(desc.toLowerCase());
        return d.contains("incasare") || d.contains("alimentare") || d.contains("depunere")
                || d.contains("dobanda") || d.contains("restituire") || d.contains("rambursare incasat");
    }

    /**
     * The counterparty name from BT's ";"-separated detail string, e.g.
     * "…;5ANCOR EXPERT CONSULT;RO42BTRLRONCRT0314443801;BTRLRO22" → "ANCOR EXPERT CONSULT". The name is
     * the segment just before the counterparty account, with its leading ordinal digits stripped.
     */
    private static String partnerName(String detail) {
        String[] parts = detail.split(";");
        String best = null;
        for (int i = 0; i < parts.length; i++) {
            // Strip BT's leading ordinal digits and "CHECK<n>" verification marker glued to the name.
            String seg = LEADING_DIGITS.matcher(parts[i].trim()).replaceFirst("")
                    .replaceFirst("(?i)^CHECK\\d+", "").trim();
            if (seg.length() < 3 || !seg.matches(".*[A-Za-zĂÂÎȘȚăâîșț].*")) {
                continue;
            }
            String u = seg.toUpperCase();
            if (u.equals("BTRLRO22") || u.startsWith("REF") || u.startsWith("C.I.F") || u.startsWith("CIF")
                    || u.startsWith("CV FACTURA") || seg.replaceAll("\\s", "").matches("RO\\d{2}[A-Z0-9]{14,}")) {
                continue; // codes, SWIFT, IBAN, our own labels — not a counterparty name
            }
            // Prefer the segment immediately before an account/IBAN-looking segment (the payer/payee).
            boolean nextIsAccount = i + 1 < parts.length
                    && parts[i + 1].trim().matches("(RO\\d{2})?[A-Z0-9]*RONCRT[A-Z0-9]*|RO\\d{2}[A-Z0-9]{14,}");
            if (nextIsAccount) {
                return seg;
            }
            if (best == null) {
                best = seg;
            }
        }
        return best;
    }

    private static String firstGroup(String[] lines, Pattern p) {
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static BigDecimal firstMoney(String[] lines, Pattern p) {
        for (String line : lines) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                return magnitude(m.group(1));
            }
        }
        return null;
    }

    private static BigDecimal lastMoney(String[] lines, Pattern p) {
        BigDecimal last = null;
        for (String line : lines) {
            Matcher m = p.matcher(line);
            while (m.find()) {
                last = magnitude(m.group(1));
            }
        }
        return last;
    }

    private static LocalDate parseDate(String token) {
        try {
            return LocalDate.parse(token, DMY);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Money magnitude, RO/EN aware: the last '.'/',' followed by exactly two digits is the decimal. */
    private static BigDecimal magnitude(String token) {
        String t = token.trim();
        int lastSep = Math.max(t.lastIndexOf('.'), t.lastIndexOf(','));
        if (lastSep >= 0 && t.length() - lastSep - 1 == 2) {
            String intPart = t.substring(0, lastSep).replaceAll("[.,]", "");
            return new BigDecimal((intPart.isEmpty() ? "0" : intPart) + "." + t.substring(lastSep + 1));
        }
        String digits = t.replaceAll("[.,]", "");
        return new BigDecimal(digits.isEmpty() ? "0" : digits);
    }

    private static String stripDiacritics(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
