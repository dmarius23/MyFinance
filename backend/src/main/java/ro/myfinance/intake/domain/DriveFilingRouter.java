package ro.myfinance.intake.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Pure routing rules for Drive Filing v2 (see {@code docs/MyFinance-drive-filing-v2-design-v1.md}): given a
 * document and the metadata needed to place it, return the target drive (purpose) and the folder path under
 * that drive's root. No I/O — fully unit-testable. The Drive writer ensures the path exists and uploads.
 *
 * <p>Returns {@link Optional#empty()} when the document should NOT be mirrored (receipts, unclassified, a
 * declaration whose kind is unknown, or an invoice whose direction is unresolved) — those stay in Supabase.
 */
public final class DriveFilingRouter {

    /** A resolved filing decision: which drive, and the folder segments beneath its root. */
    public record Filing(DrivePurpose purpose, List<String> segments) {
    }

    /**
     * Inputs needed to route a document. {@code companyName} is the sanitized company folder name (legal
     * name or CUI). {@code declKind} is the declaration code ("D100"/"D112"/"D300", DECLARATION only).
     * {@code dominantObligationCod} is the largest-amount obligation budget code (D100 sub-routing).
     * {@code invoiceDirection} places an INVOICE (null/UNKNOWN → not mirrored).
     */
    public record FilingRequest(DocumentType type, LocalDate periodMonth, String companyName,
                                String declKind, String dominantObligationCod,
                                InvoiceDirection invoiceDirection) {
    }

    /** Title-case Romanian month names, index 1..12. */
    private static final String[] RO_MONTHS = {
            "", "Ianuarie", "Februarie", "Martie", "Aprilie", "Mai", "Iunie",
            "Iulie", "August", "Septembrie", "Octombrie", "Noiembrie", "Decembrie"};

    private DriveFilingRouter() {
    }

    public static Optional<Filing> route(FilingRequest r) {
        return switch (r.type()) {
            case PAYROLL -> Optional.of(new Filing(DrivePurpose.DECLARATIONS,
                    List.of(yearFolder(r.periodMonth()), monthFolder(r.periodMonth()), "State de plata")));
            case DECLARATION -> declarationFolder(r.declKind(), r.dominantObligationCod())
                    .map(leaf -> new Filing(DrivePurpose.DECLARATIONS,
                            List.of(yearFolder(r.periodMonth()), monthFolder(r.periodMonth()), leaf)));
            case TRIAL_BALANCE -> Optional.of(new Filing(DrivePurpose.DECLARATIONS,
                    List.of(yearFolder(r.periodMonth()), trimesterFolder(r.periodMonth()), r.companyName())));
            case BANK_STATEMENT -> Optional.of(new Filing(DrivePurpose.ACCOUNTING,
                    List.of(companyAccountingFolder(r.companyName()), "Extrase de cont")));
            case INVOICE -> invoiceFolder(r.invoiceDirection())
                    .map(leaf -> new Filing(DrivePurpose.ACCOUNTING,
                            List.of(companyAccountingFolder(r.companyName()), leaf)));
            case RECEIPT, UNCLASSIFIED -> Optional.empty();
        };
    }

    /** "Declaratii {year}" — the per-year subfolder under the all-years declarations root. */
    private static String yearFolder(LocalDate period) {
        return "Declaratii " + period.getYear();
    }

    /** "{monthNumber}. {LunăRo} {year}", month number not zero-padded, e.g. "6. Iunie 2026". */
    private static String monthFolder(LocalDate period) {
        return period.getMonthValue() + ". " + RO_MONTHS[period.getMonthValue()] + " " + period.getYear();
    }

    /** "Bilant Interimar T{q} an {year}" — q = trimester 1..4. */
    private static String trimesterFolder(LocalDate period) {
        int q = (period.getMonthValue() - 1) / 3 + 1;
        return "Bilant Interimar T" + q + " an " + period.getYear();
    }

    /** "Contabilitate {Company}" — the per-company folder in the accounting drive. */
    private static String companyAccountingFolder(String companyName) {
        return "Contabilitate " + companyName;
    }

    /** The declaration leaf folder, or empty when the declaration kind is unknown (skip mirroring). */
    private static Optional<String> declarationFolder(String declKind, String dominantObligationCod) {
        if (declKind == null || declKind.isBlank()) {
            return Optional.empty();
        }
        String kind = declKind.trim().toUpperCase();
        return switch (kind) {
            case "D112" -> Optional.of("D112");
            case "D300" -> Optional.of("D300");
            case "D100" -> Optional.of(d100Folder(dominantObligationCod));
            default -> Optional.of(kind); // future declaration kinds file under their own name
        };
    }

    /** "D100 {label}" by dominant obligation code; generic "D100" when the code is absent/unmapped. */
    private static String d100Folder(String dominantObligationCod) {
        String label = switch (dominantObligationCod == null ? "" : dominantObligationCod.trim()) {
            case "628" -> "Chirii";
            case "604" -> "Dividende";
            case "103", "121" -> "Profit sau Micro";
            default -> null;
        };
        return label == null ? "D100" : "D100 " + label;
    }

    /** The invoice leaf folder by direction, or empty when direction is unresolved (skip mirroring). */
    private static Optional<String> invoiceFolder(InvoiceDirection direction) {
        if (direction == null) {
            return Optional.empty();
        }
        return switch (direction) {
            case RECEIVED -> Optional.of("Facturi achizitii");
            case ISSUED -> Optional.of("Facturi emise");
            case UNKNOWN -> Optional.empty();
        };
    }
}
