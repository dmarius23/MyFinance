package ro.myfinance.extraction.adapter.external;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ro.myfinance.extraction.application.RoFiscalCode;

/**
 * Parser for the standard ANAF / SPV <b>e-Factura</b> PDF visualisation (the {@code F_<id>_….PDF}
 * files downloaded from the Spațiul Privat Virtual). These carry a fixed two-party layout: the seller
 * under a <i>VÂNZĂTOR</i> header and the buyer under a <i>CUMPĂRĂTOR</i> header.
 *
 * <p>The generic line heuristics fail here because the parties' fiscal codes are labelled
 * {@code Identificator} / {@code Identificatorul TVA} (not "CIF"/"CUI"), and — worse — PDFBox glues the
 * cell <i>value</i> to its <i>label</i> with no separator (e.g. {@code RO20464846Identificator},
 * {@code LEROY MERLIN S.R.LNume}). We split the flat text into the VÂNZĂTOR and CUMPĂRĂTOR blocks and
 * read each party's code from its own block, tolerating the glued labels — so the buyer's CIF is
 * recovered and a purchase invoice is identified instead of flagged "Parte neidentificată".
 */
final class EFacturaPdfParser {

    /** Extracted parties + issue date + invoice number; any field may be null when not found. */
    record EFacturaFields(String sellerName, String sellerCif, String buyerName, String buyerCif,
                          LocalDate issueDate, String invoiceNumber) {
    }

    // A fiscal code "RO" + 2–10 digits. No trailing \b: in this layout the next char is the glued label
    // (a letter), so the digit run ends naturally; the leading guard keeps it from matching mid-token.
    private static final Pattern RO_CIF = Pattern.compile("(?<![A-Za-z0-9])RO\\s?(\\d{2,10})");
    // A bare 2–10 digit code that is a standalone token (not glued to letters). The letter guards skip a
    // postal code glued to its label ("407280Cod") and the numeric part of a J-register code.
    private static final Pattern BARE_CIF = Pattern.compile("(?<![A-Za-z0-9])(\\d{2,10})(?![A-Za-z0-9])");
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    // An invoice-number token: alphanumeric with the usual separators, containing at least one digit
    // (e.g. "S1186624", "MPTS/2026/100549", "EFI2601410462"). Length ≥ 4 keeps out stray fragments.
    private static final Pattern NUMBER_TOKEN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9/._-]*\\d[A-Za-z0-9/._-]*");

    private static final java.util.Set<String> COMPANY_SUFFIX = java.util.Set.of(
            "srl", "srld", "sa", "sca", "snc", "scs", "ifn", "pfa", "ii");
    // e-Factura cell labels that PDFBox glues onto a value; stripped when cleaning a party name.
    private static final Pattern TRAILING_LABEL =
            Pattern.compile("(?i)\\s*(nume|denumire|identificator|identificatorul tva)\\s*$");

    private EFacturaPdfParser() {
    }

    /** True when the document looks like the SPV e-Factura visualisation (both party headers present). */
    static boolean looksLikeEFactura(String fullText) {
        String n = norm(fullText);
        return n.contains("vanzator") && n.contains("cumparator");
    }

    /**
     * Parse the seller/buyer blocks from the flat text. Returns empty when the layout can't be located
     * (so the caller falls back to the generic heuristics). Never throws.
     */
    static Optional<EFacturaFields> parse(String fullText) {
        try {
            if (!looksLikeEFactura(fullText)) {
                return Optional.empty();
            }
            String[] lines = fullText.split("\\R");
            int seller = indexOfMarker(lines, "vanzator");
            int buyer = indexOfMarker(lines, "cumparator");
            if (buyer < 0) {
                return Optional.empty();
            }
            // Seller block: VÂNZĂTOR → CUMPĂRĂTOR. Buyer block: CUMPĂRĂTOR → next section (bounded), so a
            // downstream RO-code (a later party ref, a line item) can't be mistaken for the buyer's.
            int sellerFrom = seller >= 0 ? seller : 0;
            int sellerTo = buyer;
            int buyerTo = Math.min(lines.length, buyer + 14);

            String sellerCif = firstCif(lines, sellerFrom, sellerTo);
            String buyerCif = firstCif(lines, buyer, buyerTo);
            String sellerName = firstCompanyName(lines, sellerFrom, sellerTo);
            String buyerName = firstCompanyName(lines, buyer, buyerTo);
            LocalDate issueDate = issueDate(lines);
            String invoiceNumber = invoiceNumber(lines);

            if (buyerCif == null && sellerCif == null) {
                return Optional.empty();
            }
            return Optional.of(new EFacturaFields(sellerName, sellerCif, buyerName, buyerCif,
                    issueDate, invoiceNumber));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static int indexOfMarker(String[] lines, String marker) {
        for (int i = 0; i < lines.length; i++) {
            if (norm(lines[i]).contains(marker)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The party's fiscal code within a line range. Two passes: an explicit "RO…" code wins (a
     * VAT-registered party); failing that, a bare control-valid code on a fiscal-identifier line
     * (a non-VAT party prints its CUI bare under "Nr. înregistrare", e.g. "Nr. inregistrare 20464846").
     * The second pass is label-scoped so a postal code or phone number can't be mistaken for a CIF.
     */
    private static String firstCif(String[] lines, int from, int to) {
        int lo = Math.max(0, from);
        int hi = Math.min(lines.length, to);
        for (int i = lo; i < hi; i++) {
            for (Matcher m = RO_CIF.matcher(lines[i]); m.find(); ) {
                String code = "RO" + m.group(1);
                if (RoFiscalCode.isValidCui(code)) {
                    return code;
                }
            }
        }
        for (int i = lo; i < hi; i++) {
            if (!fiscalIdLabel(norm(lines[i]))) {
                continue;
            }
            for (Matcher m = BARE_CIF.matcher(lines[i]); m.find(); ) {
                if (RoFiscalCode.isValidCui(m.group(1))) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    /** A line label that introduces a fiscal code (registration/identifier), not an address field. */
    private static boolean fiscalIdLabel(String normLine) {
        return normLine.contains("inregistrare") || normLine.contains("identificator")
                || normLine.contains("cod de identificare") || normLine.contains("cod fiscal")
                || normLine.contains("cif") || normLine.contains("cui");
    }

    /** First line in a range that carries a Romanian company-form suffix, with glued labels stripped. */
    private static String firstCompanyName(String[] lines, int from, int to) {
        for (int i = Math.max(0, from); i < Math.min(lines.length, to); i++) {
            String name = cleanName(lines[i]);
            if (name == null) {
                continue;
            }
            String n = norm(name).replace(".", "").trim();
            String[] tokens = n.split("\\s+");
            if (tokens.length < 2) {
                continue;
            }
            for (int k = Math.max(0, tokens.length - 2); k < tokens.length; k++) {
                if (COMPANY_SUFFIX.contains(tokens[k])) {
                    return name;
                }
            }
        }
        return null;
    }

    /** Strip a glued/leading e-Factura cell label from a candidate name line; null when nothing useful. */
    private static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().replaceAll("\\s{2,}", " ");
        s = TRAILING_LABEL.matcher(s).replaceAll("");             // trailing "…S.R.LNume" → "…S.R.L"
        s = s.replaceFirst("(?i)^(nume|denumire)\\s+", "");       // leading "Denumire ACME SRL" → "ACME SRL"
        s = s.trim();
        return s.isEmpty() || s.length() > 70 ? null : s;
    }

    /**
     * Issue date from the e-Factura "Data emitere" (ISO {@code yyyy-MM-dd}) label; falls back to
     * "Data facturii". Returns null rather than guess (avoids picking the due date or a contract date).
     */
    private static LocalDate issueDate(String[] lines) {
        for (String label : new String[] {"data emitere", "data facturii", "data factura"}) {
            for (int i = 0; i < lines.length; i++) {
                if (!norm(lines[i]).contains(label)) {
                    continue;
                }
                for (int j = i; j < Math.min(lines.length, i + 3); j++) {
                    LocalDate d = parseIso(lines[j]);
                    if (d != null) {
                        return d;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The invoice series/number from the "Nr. factură" cell. The value may sit before the glued label
     * ("S1186624Nr. factura") or after it ("Nr. factura MPTS/2026/100549"); either way we strip the
     * label words and take the first alphanumeric token that carries a digit. This is the invoice's true
     * unique key — used downstream so two distinct invoices (same supplier/amount, days apart) aren't
     * mistaken for one duplicate.
     */
    private static String invoiceNumber(String[] lines) {
        for (String raw : lines) {
            String n = norm(raw);
            boolean isNumberLine = n.contains("factura")
                    && (n.contains("nr") || n.contains("numar") || n.contains("seria"));
            if (!isNumberLine || n.contains("data") || n.contains("scadenta")
                    || n.contains("moneda") || n.contains("proforma")) {
                continue;
            }
            String s = raw.replaceAll("(?i)nr\\.?\\s*factura", " ")
                    .replaceAll("(?i)numar(ul)?\\s*factura", " ")
                    .replaceAll("(?i)seria|serie", " ")
                    .replaceAll("(?i)factura", " ")
                    .trim();
            for (Matcher m = NUMBER_TOKEN.matcher(s); m.find(); ) {
                if (m.group().length() >= 4) {
                    return m.group();
                }
            }
        }
        return null;
    }

    private static LocalDate parseIso(String line) {
        for (Matcher m = ISO_DATE.matcher(line); m.find(); ) {
            try {
                return LocalDate.parse(m.group(), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (RuntimeException ignored) {
                // try next
            }
        }
        return null;
    }

    /** Diacritics-insensitive lowercase (so "CUMPĂRĂTOR"/"Vânzător" match plain ASCII markers). */
    private static String norm(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase();
    }
}
