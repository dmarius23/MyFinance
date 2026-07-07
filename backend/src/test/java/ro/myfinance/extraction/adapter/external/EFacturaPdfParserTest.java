package ro.myfinance.extraction.adapter.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.adapter.external.EFacturaPdfParser.EFacturaFields;
import ro.myfinance.extraction.application.RoFiscalCode;

/**
 * Tests the SPV e-Factura parser against flat text that mirrors what PDFBox actually produces for these
 * documents: seller and buyer stacked under VÂNZĂTOR / CUMPĂRĂTOR headers, with each cell's value glued
 * to its label ("RO16702141Identificatorul TVA", "LEROY MERLIN ROMANIA S.R.LNume"). This is the shape
 * that made the generic heuristics return no buyer CIF → "Parte neidentificată".
 */
class EFacturaPdfParserTest {

    // Reproduces the real PDFBox output for one of Meric's January purchase invoices (LEROY MERLIN),
    // including the label-glued cells and ISO "Data emitere".
    private static final String LEROY = String.join("\n",
            "RO eFactura",
            "I26M0090092600000479751",
            "Data emitere 2026-01-04",
            "VANZATOR",
            "LEROY MERLIN ROMANIA S.R.LNume",
            "Nr. inregistrare RO",
            "Informatii juridice",
            "RO16702141Identificatorul TVA",
            "Strada Soseaua Pipera Nr.43",
            "CUMPARATOR",
            "Meric SRLNume",
            "Nr. inregistrare",
            "RO20464846Identificator",
            "Strada Str. FLORILOR",
            "FlorestiOras",
            "ROTaraData scadenta 2026-01-04",
            "Moneda facturii RON",
            "46.66TOTAL PLATA",
            "Nr. cont de plata RO84BTRLRONCRT0416666301");

    @Test
    void recoversBuyerAndSellerFromGluedLabelLayout() {
        EFacturaFields f = EFacturaPdfParser.parse(LEROY).orElseThrow();

        assertThat(RoFiscalCode.digits(f.buyerCif())).isEqualTo("20464846");   // Meric = the buyer
        assertThat(RoFiscalCode.digits(f.sellerCif())).isEqualTo("16702141");  // supplier
        assertThat(f.sellerName()).isEqualTo("LEROY MERLIN ROMANIA S.R.L");    // "Nume" label stripped
        assertThat(f.issueDate()).isEqualTo(LocalDate.of(2026, 1, 4));         // ISO emitere, not scadenta
    }

    @Test
    void buyerCifIsNotConfusedByTheSupplierIbanBelowIt() {
        // The payment IBAN "RO84BTRL…" sits after the buyer block and must never be read as a CIF.
        EFacturaFields f = EFacturaPdfParser.parse(LEROY).orElseThrow();
        assertThat(f.buyerCif()).isEqualTo("RO20464846");
    }

    @Test
    void extractsInvoiceNumberGluedBeforeLabel() {
        // SAGA invoices: the series/number is glued before the label ("S1186624Nr. factura").
        String t = String.join("\n",
                "RO eFactura",
                "S1186624Nr. factura",
                "Data emitere 2026-01-09",
                "VANZATOR",
                "SAGA Software S.R.L.Nume",
                "RO17602787Identificatorul TVA",
                "CUMPARATOR",
                "MERIC SRLNume",
                "RO20464846Identificator");
        EFacturaFields f = EFacturaPdfParser.parse(t).orElseThrow();
        assertThat(f.invoiceNumber()).isEqualTo("S1186624");
    }

    @Test
    void extractsInvoiceNumberAfterLabel() {
        // Other generators print the number after the label ("Nr. factura MPTS/2026/100549").
        String t = String.join("\n",
                "VANZATOR",
                "PYROSTOP TOTAL SECURITY GROUP SRLNume",
                "RO34609408Identificatorul TVA",
                "Nr. factura MPTS/2026/100549",
                "CUMPARATOR",
                "MERIC SRLNume",
                "RO20464846Identificator");
        EFacturaFields f = EFacturaPdfParser.parse(t).orElseThrow();
        assertThat(f.invoiceNumber()).isEqualTo("MPTS/2026/100549");
    }

    @Test
    void invoiceNumberIgnoresDueDateAndProformaLines() {
        // "Data scadenta"/"Data facturii" and a "factura proforma" mention must not be read as the number.
        String t = String.join("\n",
                "VANZATOR", "ACME SRLNume", "RO34609408Identificatorul TVA",
                "Data scadenta 2026-02-15",
                "CUMPARATOR", "MERIC SRLNume", "RO20464846Identificator");
        EFacturaFields f = EFacturaPdfParser.parse(t).orElseThrow();
        assertThat(f.invoiceNumber()).isNull();
    }

    @Test
    void recoversBareBuyerCuiForNonVatBuyer() {
        // A non-VAT buyer prints its CUI bare under "Nr. inregistrare" (no "RO"); the surrounding
        // address lines ("407280Cod", postal) must not be mistaken for the fiscal code.
        String t = String.join("\n",
                "VANZATOR",
                "ELECTRICA FURNIZARE S.A.Nume",
                "RO28909028Identificatorul TVA",
                "CUMPARATOR",
                "MERIC SRLNume",
                "Nr. inregistrare 20464846",
                "Strada Strada FLORILOR, Nr. 192C",
                "407280Cod",
                "RO-CJRegiune",
                "ROTaraData scadenta 2026-01-18");
        EFacturaFields f = EFacturaPdfParser.parse(t).orElseThrow();
        assertThat(RoFiscalCode.digits(f.buyerCif())).isEqualTo("20464846");
        assertThat(RoFiscalCode.digits(f.sellerCif())).isEqualTo("28909028");
    }

    @Test
    void payTotalIsTheWithVatGrandTotalNotTheVat() {
        // The value sits ABOVE the "TOTAL PLATA" label; a forward-only scan would instead grab the VAT
        // from the "TOTAL TVA" line (147.00). The grand total to pay is 847.00 (= 700 net + 147 VAT).
        String t = String.join("\n",
                "VANZATOR", "BREHAR PALER-GROSAN CATANA SCANume", "RO40513447Identificatorul TVA",
                "CUMPARATOR", "MERIC SRLNume", "Nr. inregistrare 20464846",
                "700.00 700.00 847.00 0.00 0 0",
                "847.00",
                "0.00",
                "TOTAL PLATA",
                "TOTAL NET VALOARE TOTALA  fara TVA SUMA PLATITATOTAL TAXE",
                "147.00 RONTOTAL TVA");
        EFacturaFields f = EFacturaPdfParser.parse(t).orElseThrow();
        assertThat(f.total()).isEqualByComparingTo("847.00");
    }

    @Test
    void payTotalHandlesValueGluedOnTheLabelLine() {
        EFacturaFields f = EFacturaPdfParser.parse(LEROY).orElseThrow();
        assertThat(f.total()).isEqualByComparingTo("46.66"); // "46.66TOTAL PLATA"
    }

    @Test
    void nonEFacturaTextYieldsEmpty() {
        assertThat(EFacturaPdfParser.parse("Just an ordinary invoice, Total de plata 100.00")).isEmpty();
    }

    @Test
    void diacriticHeadersAreRecognised() {
        String t = String.join("\n",
                "VÂNZĂTOR",
                "ACME FURNIZORI SRLNume",
                "RO34609408Identificatorul TVA",
                "CUMPĂRĂTOR",
                "BENEFICIAR TEST SRLNume",
                "RO14399840Identificator");
        EFacturaFields f = EFacturaPdfParser.parse(t).orElseThrow();
        assertThat(RoFiscalCode.digits(f.buyerCif())).isEqualTo("14399840");
        assertThat(RoFiscalCode.digits(f.sellerCif())).isEqualTo("34609408");
    }

    // Some suppliers' SPV layouts print the seller's CUI BARE (no "RO") and PDFBox emits it after the
    // buyer block, near the footer — outside VÂNZĂTOR→CUMPĂRĂTOR. The seller block itself carries only
    // the name + J-register, so the block-scoped search finds no seller CIF (previously → issuer blank).
    private static final String OXYGEN = String.join("\n",
            "RO eFactura",
            "VANZATOR",
            "OXYGEN CLEANING S.R.L.",       // seller name (in block)
            "j12/262/22.01.2021",           // seller registration — NOT a fiscal code
            "CUMPARATOR",
            "MERIC SRL",
            "Nr. inregistrare",
            "RO20464846Identificator",      // buyer CIF (RO-prefixed, in block)
            "Strada Str. FLORILOR",
            "OXYGEN CLEANING S.R.L.",       // seller repeated in the footer
            "43597655",                     // seller CUI, bare and out of block
            "Nr. factura OXY2335",
            "Data emitere 2026-01-31",
            "800.00TOTAL PLATA");

    @Test
    void recoversBareSellerCifPrintedOutsideItsBlock() {
        EFacturaFields f = EFacturaPdfParser.parse(OXYGEN).orElseThrow();

        assertThat(f.sellerName()).isEqualTo("OXYGEN CLEANING S.R.L.");
        assertThat(RoFiscalCode.digits(f.sellerCif())).isEqualTo("43597655"); // recovered via fallback
        assertThat(f.buyerCif()).isEqualTo("RO20464846");                     // buyer unaffected
    }
}
