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
    // dd.MM.yyyy / dd/MM/yyyy / dd-MM-yyyy. The dash form is what Bolt prints ("Datum: 24-09-2026");
    // without it such an invoice carried no date at all. A longer number cannot match, because the
    // pattern is anchored on two-digit day and month groups ("2759309-DE1126-197583" yields nothing).
    private static final Pattern DATE = Pattern.compile("(\\d{2})[-/.](\\d{2})[-/.](\\d{4})");

    @Override
    public ParsedInvoice extract(byte[] pdf, String ownCompanyName) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            String iban = null;
            BigDecimal total = null;
            LocalDate date = null;
            String supplier = null;
            String clientCif = null;
            String invoiceNumber = null;
            String issuerCif = null;
            String currency = "RON";
            try {
                iban = firstMatch(IBAN, text);
                Total tot = total(text);
                total = tot.amount();
                currency = currency(text, tot.line());
                date = invoiceDate(text);
                supplier = supplierName(text, ownCompanyName);
                clientCif = clientCif(text);
                // The ANAF/SPV e-Factura visualisation has a fixed two-column layout the generic
                // line-based heuristics can't read (party codes are labelled "Identificator", and the
                // columns interleave). Parse it positionally and let its results win where present.
                var ef = EFacturaPdfParser.parse(text);
                if (ef.isPresent()) {
                    EFacturaPdfParser.EFacturaFields f = ef.get();
                    if (f.buyerCif() != null) {
                        clientCif = f.buyerCif();
                    }
                    if (f.sellerName() != null) {
                        supplier = f.sellerName();
                    }
                    if (f.issueDate() != null) {
                        date = f.issueDate();
                    }
                    if (f.total() != null) {
                        total = f.total();
                    }
                    invoiceNumber = f.invoiceNumber();
                    issuerCif = f.sellerCif();
                }
            } catch (RuntimeException e) {
                log.debug("Invoice field extraction failed", e); // best-effort; never throws to the caller
            }
            return new ParsedInvoice(supplier, iban, total, date, clientCif, invoiceNumber, issuerCif, currency);
        } catch (IOException | RuntimeException e) {
            log.debug("Invoice text extraction failed", e);
            return new ParsedInvoice(null, null, null, null, null, null, null, "RON");
        }
    }

    private static final Pattern CIF = Pattern.compile("(?i)\\bRO\\s?\\d{2,10}\\b|\\b\\d{2,10}\\b");

    /** Buyer-block headings: Romanian first, then the German/English ones on foreign invoices.
     *  Both "empfanger" and "empfaenger" are listed — PDFBox yields "Empfänger", which diacritic
     *  folding turns into "empfanger", while a document transliterating ä as "ae" gives "empfaenger". */
    private static final String[] BUYER_MARKERS = {
        "cumparator", "client", "beneficiar",
        "empfanger", "empfaenger", "rechnungsempfanger", "rechnungsempfaenger", "kunde",
        "bill to", "billed to", "buyer", "customer", "invoiced to", "recipient",
    };
    /** Supplier-block headings — used only to tell a Romanian invoice apart from a foreign one. */
    private static final String[] SUPPLIER_MARKERS = {"furnizor", "prestator", "vanzator"};
    /** Fiscal-code labels. A foreign invoice prints the Romanian CUI under a VAT heading instead. */
    private static final String[] CIF_LABELS = {
        "cif", "cui", "cod fiscal", "cod de identificare", "c.i.f",
        "vat no", "vat id", "vat number", "vat reg",
        "ust-id", "ust.-id", "ustid", "ust id", "ust.id",
    };

    /**
     * The buyer's fiscal code (CIF/CUI), used to verify the invoice is addressed to this company.
     * Prefers a code on a fiscal-code-labelled line at or after the buyer heading, so the supplier's own
     * code is never returned.
     *
     * <p>Foreign invoices have no Romanian party structure at all — a Bolt ride invoice heads the block
     * "Empfänger" and labels the code "VAT no.:", and a German hotel bill has neither heading and writes
     * "USt.-ID.: RO 50109730". Both used to yield nothing, so an imported invoice carried no buyer code.
     */
    private String clientCif(String text) {
        String[] lines = text.split("\\R");
        int buyer = firstLineContaining(lines, BUYER_MARKERS);
        if (buyer >= 0) {
            String hit = labelledCui(lines, buyer);
            if (hit != null) {
                return hit;
            }
        }
        // Neither a buyer nor a supplier heading: a foreign document with no Romanian party structure.
        // Any control-digit-valid Romanian CUI on such a page is the client's, because the foreign
        // supplier does not hold one — its DE/EE/LT VAT id fails isValidCui. Requiring the supplier
        // heading to be absent is what keeps a Romanian invoice out of here, so this can never pick up
        // the supplier's CUI on the layouts the buyer-block rule above is meant for.
        if (buyer < 0 && firstLineContaining(lines, SUPPLIER_MARKERS) < 0) {
            return labelledCui(lines, 0);
        }
        return null;
    }

    /** Index of the first line containing any of {@code markers}, or -1. */
    private static int firstLineContaining(String[] lines, String[] markers) {
        for (int i = 0; i < lines.length; i++) {
            String n = norm(lines[i]);
            for (String marker : markers) {
                if (n.contains(marker)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** First control-digit-valid Romanian CUI on a fiscal-code-labelled line at or after {@code from}. */
    private String labelledCui(String[] lines, int from) {
        for (int i = Math.max(0, from); i < lines.length; i++) {
            String n = norm(lines[i]);
            boolean labelled = false;
            for (String label : CIF_LABELS) {
                if (n.contains(label)) {
                    labelled = true;
                    break;
                }
            }
            if (!labelled) {
                continue;
            }
            for (Matcher m = CIF.matcher(lines[i]); m.find(); ) {
                String cand = m.group().trim();
                if (ro.myfinance.extraction.application.RoFiscalCode.isValidCui(cand)) {
                    return cand; // a real CUI (control-digit valid), not a stray number
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

    // Romanian company forms, then the foreign ones the clients actually buy from — a German GmbH, an
    // Estonian OÜ, a Lithuanian UAB. Without these a foreign supplier's name was never recognised as a
    // company name at all, so the supplier came back null.
    private static final java.util.Set<String> COMPANY_SUFFIX = java.util.Set.of(
            "srl", "srld", "sa", "sca", "snc", "scs", "ifn", "pfa", "ii",
            "gmbh", "ag", "kg", "ou", "uab", "ltd", "limited", "bv", "nv", "sarl", "sas", "spa", "oy");

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

    /** The chosen total and the line it was read from — the currency is taken from that same line. */
    private record Total(BigDecimal amount, String line) {
    }

    private Total total(String text) {
        String[] lines = text.split("\\R");
        // 1) The authoritative pay total (unchanged selection logic).
        for (int i = 0; i < lines.length; i++) {
            String n = norm(lines[i]);
            boolean payTotal = (n.contains("total") && n.contains("plata")) || n.contains("total general");
            if (!payTotal) {
                continue;
            }
            BigDecimal best = null;
            String bestLine = null;
            for (int j = i; j < Math.min(lines.length, i + 4); j++) {
                for (Matcher m = MONEY.matcher(lines[j]); m.find(); ) {
                    BigDecimal v = parseMoney(m.group());
                    if (best == null || v.compareTo(best) > 0) {
                        best = v;
                        bestLine = lines[j];
                    }
                }
            }
            if (best != null) {
                return new Total(best, bestLine);
            }
        }
        // 2) Else the largest money on any "total" line; 3) else the largest money overall.
        BigDecimal labelled = null, largest = null;
        String labelledLine = null, largestLine = null;
        for (String line : lines) {
            boolean totalLine = norm(line).contains("total");
            for (Matcher m = MONEY.matcher(line); m.find(); ) {
                BigDecimal v = parseMoney(m.group());
                if (largest == null || v.compareTo(largest) > 0) {
                    largest = v;
                    largestLine = line;
                }
                if (totalLine && (labelled == null || v.compareTo(labelled) > 0)) {
                    labelled = v;
                    labelledLine = line;
                }
            }
        }
        return labelled != null ? new Total(labelled, labelledLine) : new Total(largest, largestLine);
    }

    /** Recognised currency tokens. "LEI" is how Romanian invoices often write RON.
     * "$" requires numeric adjacency to avoid matching shell/template variables: a leading "$" must be
     * followed by a digit, and a trailing "$" must be preceded by a digit. */
    private static final Pattern CURRENCY_TOKEN = Pattern.compile(
            "(?i)(?<![a-z])(RON|LEI|EUR|USD|GBP|CHF|HUF)(?![a-z])|([€£])|\\$(?=[\\d])|(?<=[\\d])\\$");

    private static String normaliseCurrency(String token) {
        String t = token.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (t) {
            case "LEI" -> "RON";
            case "€" -> "EUR";
            case "£" -> "GBP";
            case "$" -> "USD";
            default -> t;
        };
    }

    private static String firstCurrencyIn(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = CURRENCY_TOKEN.matcher(line);
        return m.find() ? normaliseCurrency(m.group()) : null;
    }

    /**
     * The invoice's currency: the code on the total's own line first, else the only code named anywhere
     * in the document, else RON.
     *
     * <p>RON is the right default rather than "unknown": a Romanian invoice frequently names no currency,
     * and the column is NOT NULL. Being wrong here degrades to today's behaviour, and the accountant can
     * see the value and correct it.
     */
    private String currency(String text, String totalLine) {
        String onTotalLine = firstCurrencyIn(totalLine);
        if (onTotalLine != null) {
            return onTotalLine;
        }
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        for (Matcher m = CURRENCY_TOKEN.matcher(text); m.find(); ) {
            all.add(normaliseCurrency(m.group()));
        }
        return all.size() == 1 ? all.iterator().next() : "RON";
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
            // "datum" is the German issue-date label; without it a German invoice fell through to the
            // earliest-date-anywhere fallback, which on a hotel bill is the ARRIVAL date, not the issue date.
            boolean issueLabel = (n.contains("data") || n.contains("datum"))
                    && !n.contains("scaden") && !n.contains("termen");
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
        for (String pat : new String[] {"dd.MM.uuuu", "dd/MM/uuuu", "dd-MM-uuuu"}) {
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
