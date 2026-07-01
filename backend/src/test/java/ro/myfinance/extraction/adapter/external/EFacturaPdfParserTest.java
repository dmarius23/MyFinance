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
}
