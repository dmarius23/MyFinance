package ro.myfinance.intake.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Pure routing rules for Drive Filing v2 (see {@code docs/MyFinance-drive-filing-v2-design-v1.md}): given a
 * document and the metadata needed to place it, return the target drive (purpose) and the folder path under
 * that drive's root. No I/O — fully unit-testable. The Drive writer ensures the path exists and uploads.
 *
 * <p>Returns {@link Optional#empty()} when the document should NOT be mirrored (receipts, unclassified, or a
 * declaration whose kind is unknown) — those stay in Supabase. Bank statements and invoices file into the
 * ACCOUNTING drive's simplified <b>{@code <Company>/<yyyy-MM>}</b> layout (the same folders the client drops
 * files into), so an invoice files regardless of its resolved direction.
 */
public final class DriveFilingRouter {

    /** A resolved filing decision: which drive, and the folder segments beneath its root. */
    public record Filing(DrivePurpose purpose, List<String> segments) {
    }

    /**
     * Inputs needed to route a document. {@code companyName} is the sanitized company folder name (legal
     * name or CUI). {@code declKind} is the declaration code ("D100"/"D112"/"D300", DECLARATION only).
     * {@code dominantObligationCod} is the largest-amount obligation budget code (D100 sub-routing).
     * {@code invoiceDirection} places an INVOICE (null/UNKNOWN → not mirrored). {@code rootIsYearFolder}
     * (DECLARATIONS drive) → the target root already IS the "Declaratii {year}" folder, so skip that level.
     */
    public record FilingRequest(DocumentType type, LocalDate periodMonth, String companyName,
                                String declKind, String dominantObligationCod,
                                InvoiceDirection invoiceDirection, boolean rootIsYearFolder) {
    }

    /** Title-case Romanian month names, index 1..12. */
    private static final String[] RO_MONTHS = {
            "", "Ianuarie", "Februarie", "Martie", "Aprilie", "Mai", "Iunie",
            "Iulie", "August", "Septembrie", "Octombrie", "Noiembrie", "Decembrie"};

    private DriveFilingRouter() {
    }

    /** The target drive a document type files into, or empty when the type is never mirrored. */
    public static Optional<DrivePurpose> purposeFor(DocumentType type) {
        return switch (type) {
            case PAYROLL, DECLARATION, TRIAL_BALANCE -> Optional.of(DrivePurpose.DECLARATIONS);
            case BANK_STATEMENT, INVOICE -> Optional.of(DrivePurpose.ACCOUNTING);
            case RECEIPT, UNCLASSIFIED -> Optional.empty();
        };
    }

    public static Optional<Filing> route(FilingRequest r) {
        return switch (r.type()) {
            case PAYROLL -> Optional.of(new Filing(DrivePurpose.DECLARATIONS,
                    declSegments(r, monthFolder(r.periodMonth()), "State de plata")));
            case DECLARATION -> declarationFolder(r.declKind(), r.dominantObligationCod())
                    .map(leaf -> new Filing(DrivePurpose.DECLARATIONS,
                            declSegments(r, monthFolder(r.periodMonth()), leaf)));
            case TRIAL_BALANCE -> Optional.of(new Filing(DrivePurpose.DECLARATIONS,
                    declSegments(r, trimesterFolder(r.periodMonth()), r.companyName())));
            case BANK_STATEMENT, INVOICE -> Optional.of(new Filing(DrivePurpose.ACCOUNTING,
                    List.of(r.companyName(), accountingMonthFolder(r.periodMonth()))));
            case RECEIPT, UNCLASSIFIED -> Optional.empty();
        };
    }

    /** DECLARATIONS-drive segments under the root, prepending "Declaratii {year}" unless the root already is it. */
    private static List<String> declSegments(FilingRequest r, String... rest) {
        List<String> segs = new java.util.ArrayList<>();
        if (!r.rootIsYearFolder()) {
            segs.add(yearFolder(r.periodMonth()));
        }
        segs.addAll(List.of(rest));
        return List.copyOf(segs);
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

    /** "{year}-{month}" — the per-month folder under a company in the accounting drive (e.g. "2026-06"). */
    private static String accountingMonthFolder(LocalDate period) {
        return "%04d-%02d".formatted(period.getYear(), period.getMonthValue());
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
}
