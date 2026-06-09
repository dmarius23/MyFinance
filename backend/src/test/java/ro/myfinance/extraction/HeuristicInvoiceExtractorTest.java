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
}
