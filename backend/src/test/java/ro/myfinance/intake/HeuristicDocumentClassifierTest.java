package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import ro.myfinance.intake.adapter.external.HeuristicDocumentClassifier;
import ro.myfinance.intake.domain.DocumentType;

class HeuristicDocumentClassifierTest {

    private final HeuristicDocumentClassifier classifier = new HeuristicDocumentClassifier();

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
    void imageContentTypeIsReceipt() {
        assertThat(classifier.classify("photo.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                .isEqualTo(DocumentType.RECEIPT);
    }

    @Test
    void bankStatementByText() throws Exception {
        assertThat(classifier.classify("x.pdf", "application/pdf", pdfWithText("Extras de cont BRD")))
                .isEqualTo(DocumentType.BANK_STATEMENT);
    }

    @Test
    void invoiceByText() throws Exception {
        assertThat(classifier.classify("x.pdf", "application/pdf", pdfWithText("FACTURA fiscala nr 123")))
                .isEqualTo(DocumentType.INVOICE);
    }

    @Test
    void trialBalanceByText() throws Exception {
        assertThat(classifier.classify("x.pdf", "application/pdf", pdfWithText("Balanta de verificare")))
                .isEqualTo(DocumentType.TRIAL_BALANCE);
    }

    @Test
    void unmarkedPdfIsUnclassified() throws Exception {
        assertThat(classifier.classify("x.pdf", "application/pdf", pdfWithText("Lorem ipsum dolor")))
                .isEqualTo(DocumentType.UNCLASSIFIED);
    }

    @Test
    void garbageBytesAreUnclassified() {
        assertThat(classifier.classify("x.pdf", "application/pdf", new byte[]{9, 9, 9}))
                .isEqualTo(DocumentType.UNCLASSIFIED);
    }
}
