package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.intake.domain.DrivePurpose;
import ro.myfinance.intake.domain.DriveFilingRouter;
import ro.myfinance.intake.domain.DriveFilingRouter.Filing;
import ro.myfinance.intake.domain.DriveFilingRouter.FilingRequest;
import ro.myfinance.intake.domain.InvoiceDirection;

/** Unit tests for the pure Drive Filing v2 routing rules. */
class DriveFilingRouterTest {

    private static final LocalDate JUNE = LocalDate.of(2026, 6, 1);

    private static Filing route(DocumentType type, LocalDate period, String company,
                                String declKind, String cod, InvoiceDirection dir) {
        Optional<Filing> f = DriveFilingRouter.route(new FilingRequest(type, period, company, declKind, cod, dir, false));
        assertThat(f).isPresent();
        return f.get();
    }

    @Test
    void payroll_goes_to_state_de_plata_under_month() {
        Filing f = route(DocumentType.PAYROLL, JUNE, "ACME SRL", null, null, null);
        assertThat(f.purpose()).isEqualTo(DrivePurpose.DECLARATIONS);
        assertThat(f.segments()).containsExactly("Declaratii 2026", "6. Iunie 2026", "State de plata");
    }

    @Test
    void declaration_d112_and_d300_use_their_code_folder() {
        assertThat(route(DocumentType.DECLARATION, JUNE, "ACME", "D112", null, null).segments())
                .containsExactly("Declaratii 2026", "6. Iunie 2026", "D112");
        assertThat(route(DocumentType.DECLARATION, JUNE, "ACME", "D300", null, null).segments())
                .containsExactly("Declaratii 2026", "6. Iunie 2026", "D300");
    }

    @Test
    void declaration_d100_routes_by_dominant_obligation() {
        assertThat(last(route(DocumentType.DECLARATION, JUNE, "ACME", "D100", "628", null))).isEqualTo("D100 Chirii");
        assertThat(last(route(DocumentType.DECLARATION, JUNE, "ACME", "D100", "604", null))).isEqualTo("D100 Dividende");
        assertThat(last(route(DocumentType.DECLARATION, JUNE, "ACME", "D100", "103", null))).isEqualTo("D100 Profit sau Micro");
        assertThat(last(route(DocumentType.DECLARATION, JUNE, "ACME", "D100", "121", null))).isEqualTo("D100 Profit sau Micro");
    }

    @Test
    void declaration_d100_unknown_obligation_falls_back_to_generic() {
        assertThat(last(route(DocumentType.DECLARATION, JUNE, "ACME", "D100", "999", null))).isEqualTo("D100");
        assertThat(last(route(DocumentType.DECLARATION, JUNE, "ACME", "D100", null, null))).isEqualTo("D100");
    }

    @Test
    void declaration_unknown_kind_is_not_mirrored() {
        assertThat(DriveFilingRouter.route(
                new FilingRequest(DocumentType.DECLARATION, JUNE, "ACME", null, null, null, false))).isEmpty();
    }

    @Test
    void trial_balance_goes_to_trimester_then_company() {
        Filing q2 = route(DocumentType.TRIAL_BALANCE, JUNE, "ACME SRL", null, null, null);
        assertThat(q2.segments()).containsExactly("Declaratii 2026", "Bilant Interimar T2 an 2026", "ACME SRL");
        // Trimester boundaries: Jan→T1, Mar→T1, Apr→T2, Sep→T3, Oct→T4, Dec→T4.
        assertThat(trimester(1)).isEqualTo("Bilant Interimar T1 an 2026");
        assertThat(trimester(3)).isEqualTo("Bilant Interimar T1 an 2026");
        assertThat(trimester(4)).isEqualTo("Bilant Interimar T2 an 2026");
        assertThat(trimester(9)).isEqualTo("Bilant Interimar T3 an 2026");
        assertThat(trimester(10)).isEqualTo("Bilant Interimar T4 an 2026");
        assertThat(trimester(12)).isEqualTo("Bilant Interimar T4 an 2026");
    }

    @Test
    void bank_statement_goes_to_accounting_drive() {
        Filing f = route(DocumentType.BANK_STATEMENT, JUNE, "ACME SRL", null, null, null);
        assertThat(f.purpose()).isEqualTo(DrivePurpose.ACCOUNTING);
        assertThat(f.segments()).containsExactly("Contabilitate ACME SRL", "Extrase de cont");
    }

    @Test
    void invoice_direction_selects_the_leaf_folder() {
        assertThat(route(DocumentType.INVOICE, JUNE, "ACME", null, null, InvoiceDirection.RECEIVED).segments())
                .containsExactly("Contabilitate ACME", "Facturi achizitii");
        assertThat(route(DocumentType.INVOICE, JUNE, "ACME", null, null, InvoiceDirection.ISSUED).segments())
                .containsExactly("Contabilitate ACME", "Facturi emise");
    }

    @Test
    void invoice_without_resolved_direction_is_not_mirrored() {
        assertThat(DriveFilingRouter.route(
                new FilingRequest(DocumentType.INVOICE, JUNE, "ACME", null, null, null, false))).isEmpty();
        assertThat(DriveFilingRouter.route(
                new FilingRequest(DocumentType.INVOICE, JUNE, "ACME", null, null, InvoiceDirection.UNKNOWN, false))).isEmpty();
    }

    @Test
    void rootIsYearFolder_omits_the_declaratii_year_level() {
        // Connection root already points at "Declaratii 2026" → the year level must not be re-created.
        Filing f = DriveFilingRouter.route(
                new FilingRequest(DocumentType.PAYROLL, JUNE, "ACME", null, null, null, true)).orElseThrow();
        assertThat(f.segments()).containsExactly("6. Iunie 2026", "State de plata");
        Filing r = DriveFilingRouter.route(
                new FilingRequest(DocumentType.TRIAL_BALANCE, JUNE, "ACME SRL", null, null, null, true)).orElseThrow();
        assertThat(r.segments()).containsExactly("Bilant Interimar T2 an 2026", "ACME SRL");
    }

    @Test
    void purposeFor_maps_types_to_drives() {
        assertThat(DriveFilingRouter.purposeFor(DocumentType.PAYROLL)).contains(DrivePurpose.DECLARATIONS);
        assertThat(DriveFilingRouter.purposeFor(DocumentType.DECLARATION)).contains(DrivePurpose.DECLARATIONS);
        assertThat(DriveFilingRouter.purposeFor(DocumentType.TRIAL_BALANCE)).contains(DrivePurpose.DECLARATIONS);
        assertThat(DriveFilingRouter.purposeFor(DocumentType.BANK_STATEMENT)).contains(DrivePurpose.ACCOUNTING);
        assertThat(DriveFilingRouter.purposeFor(DocumentType.INVOICE)).contains(DrivePurpose.ACCOUNTING);
        assertThat(DriveFilingRouter.purposeFor(DocumentType.RECEIPT)).isEmpty();
        assertThat(DriveFilingRouter.purposeFor(DocumentType.UNCLASSIFIED)).isEmpty();
    }

    @Test
    void receipts_and_unclassified_are_not_mirrored() {
        assertThat(DriveFilingRouter.route(
                new FilingRequest(DocumentType.RECEIPT, JUNE, "ACME", null, null, null, false))).isEmpty();
        assertThat(DriveFilingRouter.route(
                new FilingRequest(DocumentType.UNCLASSIFIED, JUNE, "ACME", null, null, null, false))).isEmpty();
    }

    private static String last(Filing f) {
        List<String> s = f.segments();
        return s.get(s.size() - 1);
    }

    private static String trimester(int month) {
        return route(DocumentType.TRIAL_BALANCE, LocalDate.of(2026, month, 1), "ACME", null, null, null)
                .segments().get(1);
    }
}
