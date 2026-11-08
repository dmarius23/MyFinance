package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import ro.myfinance.extraction.adapter.external.HeuristicInvoiceExtractor;
import ro.myfinance.extraction.application.ParsedInvoice;

class HeuristicInvoiceExtractorTest {

    private final HeuristicInvoiceExtractor extractor = new HeuristicInvoiceExtractor();

    private byte[] pdf(String... lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                cs.setLeading(14);
                cs.newLineAtOffset(50, 720);
                for (String l : lines) {
                    cs.showText(l);
                    cs.newLine();
                }
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void extractsIbanTotalAndDate() throws Exception {
        ParsedInvoice inv = extractor.extract(pdf(
                "Factura fiscala nr 123 din 15/03/2026",
                "Furnizor ACME SRL  IBAN RO49AAAA1B31007593840000",
                "Subtotal 1000,00",
                "Total de plata 1.190,00"));
        assertThat(inv.supplierIban()).isEqualTo("RO49AAAA1B31007593840000");
        assertThat(inv.totalAmount()).isEqualByComparingTo("1190.00");
        assertThat(inv.invoiceDate()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void garbageReturnsNulls() {
        ParsedInvoice inv = extractor.extract(new byte[]{1, 2, 3});
        assertThat(inv.supplierIban()).isNull();
        assertThat(inv.totalAmount()).isNull();
    }

    // ---- Foreign-supplier invoices (these clients buy abroad) -------------------------------------
    // Real documents from the firm's Drive. The company name and its CUI ARE printed on them, yet the
    // extractor read NOTHING off either: it looked only for Romanian labels ("CUI"/"CIF") and for dates
    // separated by "." or "/". An imported invoice therefore carried no buyer code and no date, leaving
    // reconciliation nothing to match on.

    @Test
    void readsBuyerCuiAndDashedDateFromAGermanInvoice() throws Exception {
        // "Ride invoice from Bolt 32.pdf": German, the buyer block is "Empfänger", the CUI is labelled
        // "VAT no.:", and the date uses DASHES. Two VAT ids sit on one line — only the Romanian one is a
        // control-digit-valid CUI, so the German one must not be picked.
        ParsedInvoice inv = extractor.extract(pdf(
                "Rechnung",
                "Rechnungsnummer 2759309-DE1126-197583",
                "Datum: 24-09-2026",
                "Empfaenger: Rechnung ausgestellt von Bolt Operations OU im Auftrag von:",
                "Agile transformation trailblazers SRL   Edrive Driver GmbH",
                "VAT no.: 50109730     VAT no.: DE461084306",
                "Fahrpreis 49.13 9.34 58.47",
                "Nettobetrag (EUR): 50.33 MwSt. 19%: 9.57 Bruttobetrag (EUR): 59.90"),
                "AGILE TRANSFORMATION TRAILBLAZERS SRL");
        assertThat(inv.clientCif()).isNotNull();
        assertThat(inv.clientCif().replaceAll("\\D", "")).isEqualTo("50109730");
        assertThat(inv.invoiceDate()).isEqualTo(LocalDate.of(2026, 9, 24));
    }

    @Test
    void readsSpacedRomanianCuiWhenThereIsNoBuyerBlock() throws Exception {
        // "Invoice-GINNBP-GBP-260923075.pdf": a German hotel bill with no Romanian party structure at
        // all — neither "Cumpărător" nor "Furnizor". The CUI reads "USt.-ID.: RO 50109730", with a space
        // after RO. A foreign supplier holds no Romanian CUI, so the one valid CUI here is the client's.
        ParsedInvoice inv = extractor.extract(pdf(
                "Rechnungsnr.: Agile Transformation Trailblazers SRL GBP-260923075",
                "Avram Iancu street, nd 410E  Datum: 23.09.2026",
                "Floresti Rumaenien  Anreise: 21.09.2026 Abreise: 24.09.2026",
                "Gast: Mihaela Stavila  USt.-ID.: RO 50109730",
                "21.09.2026 Komfort Zimmer 1 137.92 EUR 7 % 9.65 EUR 147.57 EUR",
                "Zwischensumme (inkl. MwSt.) 1247.40 EUR",
                "GINN Hotelmanagement GmbH",
                "IBAN: DE62 1009 0000 2794 2910 15  USt.-ID.: DE327014931"),
                "AGILE TRANSFORMATION TRAILBLAZERS SRL");
        assertThat(inv.clientCif()).isNotNull();
        assertThat(inv.clientCif().replaceAll("\\D", "")).isEqualTo("50109730");
        // "Datum" names the issue date; the stay's arrival date (21.09) must not win.
        assertThat(inv.invoiceDate()).isEqualTo(LocalDate.of(2026, 9, 23));
    }

    @Test
    void recognisesAForeignCompanyFormAsASupplierName() throws Exception {
        // A foreign supplier's name was never even recognised as a company name, because only Romanian
        // company forms counted. NOTE the limitation this does NOT fix: on the two real documents above
        // PDFBox merges the name into a long address line, and looksLikeCompany ignores lines over 70
        // characters, so their supplier still comes back null. Pulling a company-form phrase out of the
        // middle of a long line is a separate change.
        ParsedInvoice inv = extractor.extract(pdf(
                "Rechnung 55",
                "Edrive Driver GmbH",
                "Empfaenger: AGILE TRANSFORMATION TRAILBLAZERS SRL",
                "VAT no.: 50109730",
                "Gesamt 100,00"),
                "AGILE TRANSFORMATION TRAILBLAZERS SRL");
        assertThat(inv.supplierName()).contains("Edrive Driver GmbH");
        assertThat(inv.clientCif().replaceAll("\\D", "")).isEqualTo("50109730");
    }

    @Test
    void stillPrefersTheBuyerBlockCuiOnARomanianInvoice() throws Exception {
        // The guard that must survive the above: with a proper Romanian buyer block, only the code inside
        // it counts. The supplier's CUI appears FIRST in the document and must not be returned.
        ParsedInvoice inv = extractor.extract(pdf(
                "Factura fiscala nr 77 din 12.09.2026",
                "Furnizor: ACME DISTRIBUTIE SRL",
                "CUI 14399840",
                "Cumparator: AGILE TRANSFORMATION TRAILBLAZERS SRL",
                "CUI 50109730",
                "Total de plata 500,00"),
                "AGILE TRANSFORMATION TRAILBLAZERS SRL");
        assertThat(inv.clientCif().replaceAll("\\D", "")).isEqualTo("50109730");
    }

    @Test
    void extractsEnFormatTotal() throws Exception {
        ParsedInvoice inv = extractor.extract(pdf(
                "Invoice no 77 dated 10/03/2026",
                "Supplier IBAN RO49AAAA1B31007593840000",
                "Total 1,234.56"));
        assertThat(inv.totalAmount()).isEqualByComparingTo("1234.56");
        assertThat(inv.supplierIban()).isEqualTo("RO49AAAA1B31007593840000");
        assertThat(inv.invoiceDate()).isEqualTo(java.time.LocalDate.of(2026, 3, 10));
    }

    @Test
    void readsTheCurrencyFromTheTotalLine() throws Exception {
        // Real text from "Ride invoice from Bolt 32.pdf" — the currency sits on the total's own line.
        ParsedInvoice inv = extractor.extract(pdf(
                "Rechnung",
                "Datum: 24-09-2026",
                "Fahrpreis 49.13 9.34 58.47",
                "Nettobetrag (EUR): 50.33 MwSt. 19%: 9.57 Bruttobetrag (EUR): 59.90"),
                "AGILE TRANSFORMATION TRAILBLAZERS SRL");
        assertThat(inv.currency()).isEqualTo("EUR");
    }

    @Test
    void readsTheCurrencyWhenOnlyNamedElsewhereInTheDocument() throws Exception {
        // Real shape from the GINN hotel bill: the grand total's line carries no code, but the document
        // names exactly one currency, so it is unambiguous.
        ParsedInvoice inv = extractor.extract(pdf(
                "Rechnungsnr.: GBP-260923075  Datum: 23.09.2026",
                "21.09.2026 Komfort Zimmer 1 137.92 EUR 7 % 9.65 EUR 147.57 EUR",
                "Zwischensumme (inkl. MwSt.) 1247.40 EUR"),
                "AGILE TRANSFORMATION TRAILBLAZERS SRL");
        assertThat(inv.currency()).isEqualTo("EUR");
    }

    @Test
    void treatsLeiAsRon() throws Exception {
        ParsedInvoice inv = extractor.extract(pdf(
                "Factura fiscala nr 5 din 12.09.2026",
                "Total de plata 1.190,00 LEI"),
                "ACME SRL");
        assertThat(inv.currency()).isEqualTo("RON");
    }

    @Test
    void defaultsToRonWhenNoCurrencyIsNamed() throws Exception {
        // Romanian invoices frequently name no currency at all.
        ParsedInvoice inv = extractor.extract(pdf(
                "Factura fiscala nr 6 din 12.09.2026",
                "Total de plata 1.190,00"),
                "ACME SRL");
        assertThat(inv.currency()).isEqualTo("RON");
    }

    @Test
    void theTotalsLineWinsWhenTwoCurrenciesAppear() throws Exception {
        // An invoice that quotes an EUR reference price but is payable in RON.
        ParsedInvoice inv = extractor.extract(pdf(
                "Factura fiscala nr 7 din 12.09.2026",
                "Valoare informativa 240,00 EUR",
                "Total de plata 1.190,00 RON"),
                "ACME SRL");
        assertThat(inv.currency()).isEqualTo("RON");
    }

    @Test
    void readsACurrencySymbol() throws Exception {
        ParsedInvoice inv = extractor.extract(pdf(
                "Invoice 9 dated 12.09.2026",
                "Total due 1,190.00 EUR"),
                "ACME SRL");
        assertThat(inv.currency()).isEqualTo("EUR");
    }
}
