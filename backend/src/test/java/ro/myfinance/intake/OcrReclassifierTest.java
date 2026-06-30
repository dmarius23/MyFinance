package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import ro.myfinance.extraction.adapter.external.ReceiptProperties;
import ro.myfinance.intake.adapter.external.HeuristicDocumentClassifier;
import ro.myfinance.intake.adapter.external.OcrProperties;
import ro.myfinance.intake.adapter.external.OcrReclassifier;

class OcrReclassifierTest {

    private OcrReclassifier reclassifier(boolean enabled) {
        var props = new OcrProperties(enabled, "tesseract", "ron+eng", 150, 1);
        var receipt = new ReceiptProperties("anthropic", "", "", "", 0.6); // blank key → no vision calls
        return new OcrReclassifier(new HeuristicDocumentClassifier(), props, receipt, RestClient.builder());
    }

    private byte[] pdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void readabilityHeuristic() {
        assertThat(OcrReclassifier.isReadable("Factura fiscala numarul 123 catre client")).isTrue();
        assertThat(OcrReclassifier.isReadable(" \n")).isFalse();
        assertThat(OcrReclassifier.isReadable("")).isFalse();
        assertThat(OcrReclassifier.isReadable("12 34 56 78 90")).isFalse(); // digits, no letters
    }

    @Test
    void disabledReturnsEmpty() throws Exception {
        assertThat(reclassifier(false).tryClassify("application/pdf", pdfWithText("COPIE FACTURA"))).isEmpty();
    }

    @Test
    void readablePdfIsLeftToTheTextClassifierNoOcr() throws Exception {
        // Text is extractable, so the OCR fallback must NOT kick in (returns empty; no Tesseract/vision).
        assertThat(reclassifier(true).tryClassify("application/pdf", pdfWithText("COPIE FACTURA fiscala 123"))).isEmpty();
    }

    @Test
    void nonPdfReturnsEmpty() {
        assertThat(reclassifier(true).tryClassify("image/png", new byte[]{1, 2, 3})).isEmpty();
    }
}
