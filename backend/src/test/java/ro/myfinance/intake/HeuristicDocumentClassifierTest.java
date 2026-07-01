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
    void copyOrDuplicateInvoiceIsInvoice() throws Exception {
        assertThat(classifier.classify("x.pdf", "application/pdf", pdfWithText("COPIE FACTURA fiscala nr 456")))
                .isEqualTo(DocumentType.INVOICE);
        assertThat(classifier.classify("x.pdf", "application/pdf", pdfWithText("FACTURA DUPLICAT nr 789 total de plata")))
                .isEqualTo(DocumentType.INVOICE);
    }

    @Test
    void proformaInvoiceStaysUnclassified() throws Exception {
        assertThat(classifier.classify("x.pdf", "application/pdf", pdfWithText("FACTURA PROFORMA nr 123 total")))
                .isEqualTo(DocumentType.UNCLASSIFIED);
    }

    @Test
    void proformaMentioningABankIsNotABankStatement() throws Exception {
        // A proforma that prints its IBAN at Banca Transilvania must not fall to the weak bank fallback.
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("FACTURA PROFORMA nr 9 cont la Banca Transilvania total de plata")))
                .isEqualTo(DocumentType.UNCLASSIFIED);
    }

    @Test
    void trialBalanceWithStatementColumnWordsIsTrialBalanceNotStatement() throws Exception {
        // A "balanta de verificare" has columns like "Sold final" / "Rulaje" that also appear in bank
        // statements — the unambiguous "balanta" title must win over those markers.
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("BALANTA DE VERIFICARE Solduri initiale Rulaje perioada Sold final")))
                .isEqualTo(DocumentType.TRIAL_BALANCE);
    }

    @Test
    void unmarkedPdfIsUnclassified() throws Exception {
        assertThat(classifier.classify("x.pdf", "application/pdf", pdfWithText("Lorem ipsum dolor")))
                .isEqualTo(DocumentType.UNCLASSIFIED);
    }

    @Test
    void camt053XmlIsBankStatement() {
        String camt = "<?xml version=\"1.0\"?><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.02\">"
                + "<BkToCstmrStmt><Stmt><Id>1</Id></Stmt></BkToCstmrStmt></Document>";
        assertThat(classifier.classify("stmt.xml", "application/xml", camt.getBytes()))
                .isEqualTo(DocumentType.BANK_STATEMENT);
    }

    @Test
    void mt940TextIsBankStatement() {
        String mt940 = ":20:REF\n:25:RO98INGB0000999905473924\n:60F:C260101RON1000,00\n"
                + ":61:2601010101D50,00NTRFNONREF\n:86:Plata\n:62F:C260101RON950,00\n";
        assertThat(classifier.classify("stmt.sta", "text/plain", mt940.getBytes()))
                .isEqualTo(DocumentType.BANK_STATEMENT);
    }

    @Test
    void garbageBytesAreUnclassified() {
        assertThat(classifier.classify("x.pdf", "application/pdf", new byte[]{9, 9, 9}))
                .isEqualTo(DocumentType.UNCLASSIFIED);
    }

    @Test
    void invoiceMentioningABankIsInvoiceNotStatement() throws Exception {
        // A BT Leasing FACTURA contains "Banca Transilvania" but is an invoice, not a statement.
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("BT LEASING TRANSILVANIA IFN SA FACTURA Banca Transilvania Total de plata 4070.97")))
                .isEqualTo(DocumentType.INVOICE);
    }

    @Test
    void englishStatementByTransactionsListMarker() throws Exception {
        // The BRD statement is English ("Transactions List"), no "extras de cont".
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("BRD Transactions List Settlement date Debit Credit Balance")))
                .isEqualTo(DocumentType.BANK_STATEMENT);
    }
}
