package ro.myfinance.intake.domain;

import java.text.Normalizer;
import java.util.Optional;
import java.util.Set;

/**
 * Single source of truth for the Google Drive document layout, shared by the mirror writer (write) and
 * the ingestion mapper (read) so one connection round-trips: the app writes an uploaded declaration into
 * {@code …/declarations/} and reads it back as a declaration. Layout is
 * {@code <company> / <YYYY> / <MM> / <type-folder>}; this class owns the type ↔ folder mapping.
 */
public final class DriveDocLayout {

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
