package ro.myfinance.extraction.adapter.external;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.myfinance.extraction.application.InvoiceExtractor;
import ro.myfinance.extraction.application.ParsedInvoice;

/** Best-effort invoice field extraction: supplier IBAN, total, date. Never throws. */
@Component
public class HeuristicInvoiceExtractor implements InvoiceExtractor {

    private static final Logger log = LoggerFactory.getLogger(HeuristicInvoiceExtractor.class);
    private static final Pattern IBAN = Pattern.compile("\\bRO\\d{2}[A-Z0-9]{10,}\\b");
    // Money token. The look-behind/ahead stop it from matching INSIDE a date or a longer number — e.g.
    // "30.12.2025" must not yield "30.12.20" (which parseMoney would read as 3012.20).
    private static final Pattern MONEY = Pattern.compile("(?<![\\d./,])\\d[\\d.,]*[.,]\\d{2}(?![./]?\\d)");
    private static final Pattern DATE = Pattern.compile("(\\d{2})[/.](\\d{2})[/.](\\d{4})");

    @Override
    public ParsedInvoice extract(byte[] pdf, String ownCompanyName) {
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        } catch (IOException | RuntimeException e) {
            log.debug("Invoice text extraction failed", e);
            return new ParsedInvoice(null, null, null, null, null);
        }
        String iban = null;
        BigDecimal total = null;
        LocalDate date = null;
        String supplier = null;
        String clientCif = null;
        try {
            iban = firstMatch(IBAN, text);
            total = totalAmount(text);
            date = invoiceDate(text);
            supplier = supplierName(text, ownCompanyName);
            clientCif = clientCif(text);
        } catch (RuntimeException e) {
            log.debug("Invoice field extraction failed", e); // best-effort; never throws to the caller
        }
        return new ParsedInvoice(supplier, iban, total, date, clientCif);
    }

    private static final Pattern CIF = Pattern.compile("(?i)\\bRO\\s?\\d{2,10}\\b|\\b\\d{2,10}\\b");

    /**
     * The buyer's fiscal code (CIF/CUI) from the "Cumpărător"/"Client"/"Beneficiar" block — used to
     * verify the invoice is addressed to this company. Looks for a CIF-labelled line within the buyer
     * block; returns null if the block or a labelled code can't be found (don't guess the supplier's).
     */
    private String clientCif(String text) {
        String[] lines = text.split("\\R");
        int buyer = -1;
        for (int i = 0; i < lines.length; i++) {
            String n = norm(lines[i]);
            if (n.contains("cumparator") || n.contains("client") || n.contains("beneficiar")) {
                buyer = i;
                break;
            }
        }
        if (buyer < 0) {
            return null;
        }
        for (int i = buyer; i < lines.length; i++) {
            String n = norm(lines[i]);
            if (n.contains("cif") || n.contains("cui") || n.contains("cod fiscal")
                    || n.contains("cod de identificare") || n.contains("c.i.f")) {
                for (Matcher m = CIF.matcher(lines[i]); m.find(); ) {
                    String cand = m.group().trim();
                    if (ro.myfinance.extraction.application.RoFiscalCode.isValidCui(cand)) {
                        return cand; // a real CUI (control-digit valid), not a stray number
                    }
                }
            }
        }
        return null;
    }

    /** Diacritics-insensitive lowercase of a line (for label/marker detection). */
    private static String norm(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase();
    }

    private static final java.util.Set<String> COMPANY_SUFFIX = java.util.Set.of(
            "srl", "srld", "sa", "sca", "snc", "scs", "ifn", "pfa", "ii");

    /** A short line whose last tokens carry a Romanian company-form suffix (SRL, SA, IFN, PFA, …). */
    private boolean looksLikeCompany(String rawLine) {
        String n = norm(rawLine).trim();
        if (n.isEmpty() || n.length() > 70) {
            return false;
        }
        String[] tokens = n.replace(".", "").trim().split("\\s+");
        if (tokens.length < 2) {
            return false; // a bare "SRL" isn't a name
        }
        // Suffix sits at (or near) the end of a company name.
        for (int i = Math.max(0, tokens.length - 2); i < tokens.length; i++) {
            if (COMPANY_SUFFIX.contains(tokens[i])) {
                return true;
            }
        }
        return false;
    }

    private String cleanName(String rawLine) {
        String s = rawLine.trim().replaceAll("\\s{2,}", " ");
        int colon = s.indexOf(':');
        if (colon >= 0 && colon < s.length() - 1 && norm(s.substring(0, colon)).contains("furnizor")) {
            s = s.substring(colon + 1).trim();
        }
        return s.isEmpty() ? null : s;
    }

    /** Comparison key for a company name: lowercase, no diacritics/dots, company-form suffix dropped. */
    private static String companyKey(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String tk : norm(s).replace(".", "").trim().split("\\s+")) {
            if (!tk.isBlank() && !COMPANY_SUFFIX.contains(tk)) {
                sb.append(sb.length() > 0 ? " " : "").append(tk);
            }
        }
        return sb.toString();
    }

    /** True when a candidate line names the company the document belongs to (never the supplier). */
    private boolean isOwnParty(String rawLine, String ownKey) {
        if (ownKey.isEmpty()) {
            return false;
        }
        String key = companyKey(rawLine);
        return !key.isEmpty() && (key.contains(ownKey) || ownKey.contains(key));
    }

    private String firstCounterparty(String[] lines, int from, int to, String ownKey) {
        for (int i = Math.max(0, from); i < Math.min(lines.length, to); i++) {
            if (looksLikeCompany(lines[i]) && !isOwnParty(lines[i], ownKey)) {
                return cleanName(lines[i]);
            }
        }
        return null;
    }

    /**
     * Issuing party (supplier). Romanian invoices put the supplier under a "Furnizor" block and the
     * buyer under "Cumpărător"/"Client"/"Beneficiar". We look for a company-form line within the
     * supplier block (or on the Furnizor label line itself); failing that, the first company-form line
     * before the buyer block (typically the letterhead). The company the document belongs to is the
     * buyer, never the supplier, so any line naming it is skipped (handles two-column Furnizor|Client
     * layouts where the own company is listed first). Returns null if nothing convincing is found.
     */
    private String supplierName(String text, String ownCompanyName) {
        String ownKey = companyKey(ownCompanyName);
        String[] lines = text.split("\\R");
        int furnizor = -1;
        int buyer = -1;
        for (int i = 0; i < lines.length; i++) {
            String n = norm(lines[i]);
            if (furnizor < 0 && n.contains("furnizor")) {
                furnizor = i;
            }
            if (buyer < 0 && (n.contains("cumparator") || n.contains("client") || n.contains("beneficiar"))) {
                buyer = i;
            }
        }
        if (furnizor >= 0) {
            if (looksLikeCompany(lines[furnizor]) && !isOwnParty(lines[furnizor], ownKey)) {
                return cleanName(lines[furnizor]);
            }
            int end = buyer > furnizor ? buyer : lines.length;
            String hit = firstCounterparty(lines, furnizor + 1, end, ownKey);
            // Two-column layouts put "Furnizor" and "Client" on the same line, so the bounded scan can
            // come up empty — widen to the whole document, still skipping the own company.
            if (hit == null && end != lines.length) {
                hit = firstCounterparty(lines, furnizor + 1, lines.length, ownKey);
            }
            if (hit != null) {
                return hit;
            }
        }
        String hit = firstCounterparty(lines, 0, buyer >= 0 ? buyer : lines.length, ownKey);
        return hit != null ? hit : firstCounterparty(lines, 0, lines.length, ownKey);
    }

    private BigDecimal totalAmount(String text) {
        String[] lines = text.split("\\R");
        // 1) The authoritative pay total: the amount on (or just after) a "total de plată" /
        //    "total general" line. The label and its value(s) are often on separate lines, and the
        //    grand total sits below a value+VAT breakdown — so take the LARGEST money in a short
        //    window after the label (the grand total is ≥ its components).
        for (int i = 0; i < lines.length; i++) {
            String n = norm(lines[i]);
            boolean payTotal = (n.contains("total") && n.contains("plata")) || n.contains("total general");
            if (!payTotal) {
                continue;
            }
            BigDecimal best = null;
            for (int j = i; j < Math.min(lines.length, i + 4); j++) {
                for (Matcher m = MONEY.matcher(lines[j]); m.find(); ) {
                    BigDecimal v = parseMoney(m.group());
                    if (best == null || v.compareTo(best) > 0) {
                        best = v;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        // 2) Else the largest money on any "total" line; 3) else the largest money overall.
        BigDecimal labelled = null;
        BigDecimal largest = null;
        for (String line : lines) {
            boolean totalLine = norm(line).contains("total");
            for (Matcher m = MONEY.matcher(line); m.find(); ) {
                BigDecimal v = parseMoney(m.group());
                if (largest == null || v.compareTo(largest) > 0) {
                    largest = v;
                }
                if (totalLine && (labelled == null || v.compareTo(labelled) > 0)) {
                    labelled = v;
                }
            }
        }
        return labelled != null ? labelled : largest;
    }

    /**
     * Invoice issue date. Romanian invoices print both an issue date ("Data facturii" / "Data (ziua,
     * luna, an)") and a payment due date ("Termen de plată" / "Scadență"); they may also carry stray
     * dates (a print/footer timestamp, a contract date). So we:
     *   1) prefer a date on (or just after) a line labelled "Data…" that isn't a due-date line — the
     *      label and its value are often on separate lines in form layouts;
     *   2) otherwise fall back to the earliest date not sitting on a due-date line;
     *   3) finally, the earliest date overall.
     */
    private LocalDate invoiceDate(String text) {
        String[] lines = text.split("\\R");

        // 1) Date near an issue-date label.
        for (int i = 0; i < lines.length; i++) {
            String n = norm(lines[i]);
            boolean issueLabel = n.contains("data") && !n.contains("scaden") && !n.contains("termen");
            if (!issueLabel) {
                continue;
            }
            for (int j = i; j < Math.min(lines.length, i + 4); j++) {
                String nj = norm(lines[j]);
                if (nj.contains("scaden") || nj.contains("termen")) {
                    continue; // don't drift into a due-date line
                }
                LocalDate d = firstDateIn(lines[j]);
                if (d != null) {
                    return d;
                }
            }
        }

        // 2) + 3) earliest non-due date, else earliest overall.
        LocalDate earliestIssue = null;
        LocalDate earliestAny = null;
        for (String line : lines) {
            String n = norm(line);
            boolean dueLine = n.contains("scaden") || n.contains("termen de plata");
            for (Matcher m = DATE.matcher(line); m.find(); ) {
                LocalDate d = parseDate(m.group());
                if (d == null) {
                    continue;
                }
                if (earliestAny == null || d.isBefore(earliestAny)) {
                    earliestAny = d;
                }
                if (!dueLine && (earliestIssue == null || d.isBefore(earliestIssue))) {
                    earliestIssue = d;
                }
            }
        }
        return earliestIssue != null ? earliestIssue : earliestAny;
    }

    private LocalDate firstDateIn(String line) {
        for (Matcher m = DATE.matcher(line); m.find(); ) {
            LocalDate d = parseDate(m.group());
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    private LocalDate parseDate(String token) {
        for (String pat : new String[] {"dd.MM.uuuu", "dd/MM/uuuu"}) {
            try {
                return LocalDate.parse(token, DateTimeFormatter.ofPattern(pat));
            } catch (RuntimeException ignored) {
                // try next pattern
            }
        }
        return null;
    }

    private String firstMatch(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    /**
     * Parse a money token in RO ("1.234,56") or EN ("1,234.56") format — robust to multiple grouping
     * separators (e.g. "1.234.567,89"). The decimal point is the last '.'/',' followed by exactly two
     * digits; every other separator is a thousands grouping and is stripped.
     */
    private BigDecimal parseMoney(String token) {
        int lastSep = Math.max(token.lastIndexOf('.'), token.lastIndexOf(','));
        if (lastSep >= 0 && token.length() - lastSep - 1 == 2) {
            String intPart = token.substring(0, lastSep).replaceAll("[.,]", "");
            return new BigDecimal((intPart.isEmpty() ? "0" : intPart) + "." + token.substring(lastSep + 1));
        }
        String digits = token.replaceAll("[.,]", "");
        return new BigDecimal(digits.isEmpty() ? "0" : digits);
    }
}
