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

    @Test
    void mergeSupplierFillsOnlyTheSupplierFromOcrKeepingTextMoneyFields() {
        // Hybrid: readable text gave total/date/buyer but no supplier (logo-only); OCR of page 1 supplies
        // the name + CIF. Money/date/party must stay the text values, not the OCR's.
        Fields text = new Fields(null, null, new BigDecimal("731.02"), LocalDate.of(2026, 5, 29),
                "EXTRACTED", null, OWN_CUI, "F123", Boolean.FALSE);
        Fields ocr = new Fields("S.C. COMPEXIT TRADING SRL", null, new BigDecimal("999.99"),
                LocalDate.of(2000, 1, 1), "EXTRACTED", "RO8008913", OWN_CUI, "OTHER", Boolean.FALSE);

        Fields out = InvoiceExtractionService.mergeSupplier(text, ocr);

        assertThat(out.supplierName()).isEqualTo("S.C. COMPEXIT TRADING SRL"); // from OCR
        assertThat(out.issuerCif()).isEqualTo("RO8008913");                    // from OCR
        assertThat(out.total()).isEqualByComparingTo("731.02");                // kept from text
        assertThat(out.date()).isEqualTo(LocalDate.of(2026, 5, 29));           // kept from text
        assertThat(out.receiptNumber()).isEqualTo("F123");                     // kept from text
    }

    @Test
    void mergeSupplierKeepsTextSupplierWhenAlreadyPresent() {
        Fields text = fields("REAL SUPPLIER SRL", "RO111", OWN_CUI);
        Fields ocr = fields("WRONG FROM OCR", "RO999", OWN_CUI);

        Fields out = InvoiceExtractionService.mergeSupplier(text, ocr);

        assertThat(out.supplierName()).isEqualTo("REAL SUPPLIER SRL");
        assertThat(out.issuerCif()).isEqualTo("RO111");
    }
}
