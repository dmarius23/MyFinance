package ro.myfinance.extraction.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.application.InvoiceExtractionService.Fields;

/**
 * Buyer-vs-issuer disambiguation for vision-OCR'd purchase invoices (e.g. the Compexit dealer invoice
 * whose supplier is only in the logo + footer). The company the document belongs to is always the
 * buyer, never the supplier.
 */
class InvoiceExtractionPartyTest {

    private static final String OWN_CUI = "49443957";           // INNOVATECODE (the buyer)
    private static final String OWN_NAME = "INNOVATECODE IT SRL";

    private static Fields fields(String supplierName, String issuerCif, String clientCif) {
        return new Fields(supplierName, null, new BigDecimal("884.53"), LocalDate.of(2026, 6, 5),
                "EXTRACTED", issuerCif, clientCif, "F1293999", null);
    }

    @Test
    void keepsSupplierWhenModelLabelsThePartiesCorrectly() {
        // Model returns supplier=Compexit, client=us — the common, correct case.
        Fields out = InvoiceExtractionService.identifyClientParty(
                fields("S.C. COMPEXIT TRADING SRL", "RO8008913", OWN_CUI), OWN_CUI, OWN_NAME);

        assertThat(out.supplierName()).isEqualTo("S.C. COMPEXIT TRADING SRL");
        assertThat(out.issuerCif()).isEqualTo("RO8008913");
        assertThat(out.clientCif()).isEqualTo(OWN_CUI);
        assertThat(out.wrongParty()).isFalse();
    }

    @Test
    void recoversSupplierCifWhenModelSwapsTheParties() {
        // Model swapped: it tagged us (the buyer) as the issuer and Compexit as the client.
        Fields out = InvoiceExtractionService.identifyClientParty(
                fields("INNOVATECODE IT S.R.L.", OWN_CUI, "RO8008913"), OWN_CUI, OWN_NAME);

        // The supplier's CIF is recovered from the "client" slot; the buyer name is dropped.
        assertThat(out.issuerCif()).isEqualTo("RO8008913");
        assertThat(out.supplierName()).isNull();
        assertThat(out.clientCif()).isEqualTo(OWN_CUI);
        assertThat(out.wrongParty()).isFalse();
    }

    @Test
    void dropsSupplierNameThatIsActuallyOurCompany() {
        // Buyer name leaked into supplierName but issuer CIF is ours → still our invoice, no supplier.
        Fields out = InvoiceExtractionService.identifyClientParty(
                fields("INNOVATECODE IT SRL", OWN_CUI, null), OWN_CUI, OWN_NAME);

        assertThat(out.supplierName()).isNull();
        assertThat(out.clientCif()).isEqualTo(OWN_CUI);
        assertThat(out.wrongParty()).isFalse();
    }

    @Test
    void leavesFieldsUntouchedWhenOurCompanyIsNotOnTheDocument() {
        // A genuinely different buyer (wrong-party invoice) — do not force our CUI onto it.
        Fields in = fields("S.C. COMPEXIT TRADING SRL", "RO8008913", "12345678");
        Fields out = InvoiceExtractionService.identifyClientParty(in, OWN_CUI, OWN_NAME);

        assertThat(out).isSameAs(in);
    }
}
