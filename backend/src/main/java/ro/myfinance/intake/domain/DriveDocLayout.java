package ro.myfinance.intake.domain;

import java.text.Normalizer;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Single source of truth for the Google Drive document layout, shared by the mirror writer (write) and
 * the ingestion mapper (read) so one connection round-trips: the app writes an uploaded declaration into
 * {@code …/declarations/} and reads it back as a declaration. Layout is
 * {@code <company> / <YYYY> / <MM> / <type-folder>}; this class owns the type ↔ folder mapping.
 */
public final class DriveDocLayout {

    /** A declaration form-code folder, e.g. {@code D700}, {@code D 112}, {@code D300}, {@code D100 Chirie},
     *  {@code D060}, {@code D406} — a "D" followed by 2–4 digits (spaces already stripped by normalize). */
    private static final Pattern DECLARATION_FOLDER = Pattern.compile("^d\\d{2,4}");
    /** A declaration filed straight to a month folder: {@code <FormCode>_<CUI>_<YYYY>_<MM> ….pdf},
     *  e.g. {@code D700_44570402_2026_07 - trecere impozit profit.pdf}. Matched on the raw filename. */
    private static final Pattern DECLARATION_FILE = Pattern.compile("(?i)^d\\s?\\d{2,4}[ _]");

    private DriveDocLayout() {
    }

    /** The folder name a document of {@code type} is written into. */
    public static String typeFolder(DocumentType type) {
        return switch (type) {
            case PAYROLL -> "payrolls";
            case DECLARATION -> "declarations";
            case TRIAL_BALANCE -> "reports";
            case BANK_STATEMENT -> "bank-statements";
            case INVOICE -> "invoices";
            case RECEIPT -> "receipts";
            case UNCLASSIFIED -> "other";
        };
    }

    /** Map a folder-path segment to a document type (RO/EN, singular/plural). Empty when it is not a type folder. */
    public static Optional<DocumentType> typeOf(String segment) {
        String n = normalize(segment);
        if (n.isEmpty()) {
            return Optional.empty();
        }
        for (DocumentType t : DocumentType.values()) {
            if (aliases(t).contains(n)) {
                return Optional.of(t);
            }
        }
        // Declaration form-code folders (D100/D112/D300/D394/D700/D060/D406 …) are named by the ANAF form,
        // not by the word "declaratii" — recognise any "D" + 2–4 digits.
        if (DECLARATION_FOLDER.matcher(n).find()) {
            return Optional.of(DocumentType.DECLARATION);
        }
        // A free-form trial-balance folder such as "Balanta de verificare 2026" (exact "balanta" is handled
        // by the alias set above; this catches the descriptive variants).
        if (n.startsWith("balanta") || n.startsWith("balante")) {
            return Optional.of(DocumentType.TRIAL_BALANCE);
        }
        return Optional.empty();
    }

    /**
     * Best-effort document type from a <em>filename</em> alone, for files that sit directly in a month
     * folder with no type sub-folder: payroll payslips/timesheets ({@code fluturas#…}, {@code Pontaj#…},
     * {@code Stat_salarii#…}) and declarations filed as {@code <FormCode>_<CUI>_<YYYY>_<MM> ….pdf}. Empty
     * when the name carries no type hint (the classifier then decides).
     */
    public static Optional<DocumentType> typeOfFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        String n = normalize(fileName);
        if (n.contains("pontaj") || n.contains("fluturas")
                || (n.contains("stat") && (n.contains("salar") || n.contains("plata")))) {
            return Optional.of(DocumentType.PAYROLL);
        }
        // Trial balance filed as a plain file in the company's balance folder: "balanta_de_verificare
        // martie 2026.pdf". The full statement ("Bilant …") starts with "bilant", so it is NOT matched.
        if (n.startsWith("balanta") || n.startsWith("balante")) {
            return Optional.of(DocumentType.TRIAL_BALANCE);
        }
        if (DECLARATION_FILE.matcher(fileName.trim()).find()) {
            return Optional.of(DocumentType.DECLARATION);
        }
        return Optional.empty();
    }

    private static Set<String> aliases(DocumentType type) {
        return switch (type) {
            case PAYROLL -> Set.of("payrolls", "payroll", "salarizare", "salarii", "statedeplata");
            case DECLARATION -> Set.of("declarations", "declaration", "declaratii", "declaratie");
            case TRIAL_BALANCE -> Set.of("reports", "report", "rapoarte", "raport", "balante", "balanta", "balance");
            case BANK_STATEMENT -> Set.of("bankstatements", "bankstatement", "statements", "extrase", "extrasedecont");
            case INVOICE -> Set.of("invoices", "invoice", "facturi", "factura");
            case RECEIPT -> Set.of("receipts", "receipt", "chitante", "chitanta", "bonuri", "bon");
            case UNCLASSIFIED -> Set.of("other", "altele", "neclasificate");
        };
    }

    /** Lowercase, drop diacritics and non-alphanumerics — exact-match against aliases avoids company-name clashes. */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('ș', 's').replace('ț', 't').replace('Ș', 's').replace('Ț', 't');
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return t.toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}
