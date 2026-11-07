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
    void paymentOrderIsAReceiptNotAStatement() throws Exception {
        // An "ordin de plata" prints the bank's name and never says "factura", so the weak bank-name
        // fallback used to file it as a bank statement. It is proof of payment — a receipt.
        assertThat(classifier.classify("OP 13.08.2026 Somatie Anaf.pdf", "application/pdf",
                pdfWithText("ORDIN DE PLATA nr 4177 catre ANAF Banca Transilvania")))
                .isEqualTo(DocumentType.RECEIPT);
        // The real file from the firm's Drive is a BT "Confirmare tranzactie" — it says neither
        // "ordin de plata" nor "factura", which is why it kept reading as a statement.
        assertThat(classifier.classify("OP 13.08.2026.pdf", "application/pdf",
                pdfWithText("Banca Transilvania Confirmare tranzactie Platitor Beneficiar Trezoreria Brasov")))
                .isEqualTo(DocumentType.RECEIPT);
        // A real statement from the same bank is still a statement...
        assertThat(classifier.classify("extras.pdf", "application/pdf",
                pdfWithText("Extras de cont Banca Transilvania SOLD ANTERIOR")))
                .isEqualTo(DocumentType.BANK_STATEMENT);
        // ...and a genuine ANAF declaration is still a declaration (the OP rule must not swallow it).
        assertThat(classifier.classify("d112.pdf", "application/pdf",
                pdfWithText("Declaratie D112 ANAF privind obligatiile de plata")))
                .isEqualTo(DocumentType.DECLARATION);
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

    @Test
    void revolutStatementIsBankStatement() throws Exception {
        // A Revolut statement carries no Romanian ("extras de cont") or English ("Transactions List")
        // title marker, so it is recognised by its transaction-table header instead.
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("Revolut Bank UAB Monthly statement Money out Money in Balance")))
                .isEqualTo(DocumentType.BANK_STATEMENT);
    }

    @Test
    void invoiceMentioningRevolutIsInvoiceNotStatement() throws Exception {
        // A company that banks with Revolut prints "Revolut Bank UAB" and its IBAN in the payment
        // details of every invoice it issues. The brand name alone must not outrank "factura" —
        // otherwise every invoice of a Revolut-banked company is filed as a bank statement.
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("FACTURA nr 104 Cont Revolut Bank UAB RO04 BREL0000000000000001 Total de plata 1200.00")))
                .isEqualTo(DocumentType.INVOICE);
        // Same for an English-language invoice paid into a Revolut account.
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("INVOICE 2026-104 Beneficiary Revolut Bank UAB IBAN RO04 BREL0000000000000001")))
                .isEqualTo(DocumentType.INVOICE);
    }

    @Test
    void revolutStatementWithInvoiceWordInDescriptionIsStillStatement() throws Exception {
        // Statement transaction descriptions routinely mention an invoice ("Payment for invoice 104").
        // The statement-table signature must be checked BEFORE the invoice rule so this still files
        // as a statement.
        assertThat(classifier.classify("account-statement_01-Sep-2026_30-Sep-2026.pdf", "application/pdf",
                pdfWithText("Revolut Bank UAB Monthly statement Date Description Money out Money in Balance "
                        + "4 Sep 2026 MOS To Codesio Software SRL Payment for invoice 104 1200.00 RON")))
                .isEqualTo(DocumentType.BANK_STATEMENT);
    }

    @Test
    void foreignSupplierInvoicePayableToRevolutIsInvoice() throws Exception {
        // Real shape from the firm's Drive: "ATT_41_OC_EMEA_Limited.pdf" / "ATT_38_Next_Ventures_Limited.pdf"
        // — English invoices from UK suppliers that bank with Revolut, so the remittance block names
        // Revolut while the document itself never says "factura". Only the "invoice" marker identifies it.
        assertThat(classifier.classify("ATT_41_OC_EMEA_Limited.pdf", "application/pdf",
                pdfWithText("OC EMEA Limited INVOICE No 41 Date 15 Oct 2026 Services rendered "
                        + "Please remit to Revolut Bank UAB Beneficiary OC EMEA Limited Total due 2400.00 EUR")))
                .isEqualTo(DocumentType.INVOICE);
        assertThat(classifier.classify("ATT_38_Next_Ventures_Limited.pdf", "application/pdf",
                pdfWithText("Next Ventures Limited Invoice 38 Bank Revolut IBAN LT12 3250 0123 4567 8901 "
                        + "Amount payable 5100.00 EUR")))
                .isEqualTo(DocumentType.INVOICE);
    }

    @Test
    void germanInvoiceIsInvoice() throws Exception {
        // Real file from the firm's Drive: "Ride invoice from Bolt 32.pdf" is a GERMAN Bolt ride invoice —
        // it says "Rechnung", never "factura"/"invoice", so it used to end up UNCLASSIFIED and the Drive
        // gate refused it. The word "invoice" is only in the FILENAME, which the marker rules never see.
        assertThat(classifier.classify("Ride invoice from Bolt 32.pdf", "application/pdf",
                pdfWithText("Rechnung Rechnungsnummer 2759309-DE1126-197583 Datum: 24-09-2026 "
                        + "Bolt Operations OU im Auftrag von: Agile transformation trailblazers SRL "
                        + "Fahrpreis 49.13 MwSt. 19% Bruttobetrag (EUR): 59.90")))
                .isEqualTo(DocumentType.INVOICE);
        // Real file: "Invoice-GINNBP-GBP-260923075.pdf" — a German hotel bill for the same company.
        assertThat(classifier.classify("Invoice-GINNBP-GBP-260923075.pdf", "application/pdf",
                pdfWithText("Rechnungsnr.: GBP-260923075 Datum: 23.09.2026 Rechnung fur Ihren Aufenthalt "
                        + "Komfort Zimmer Fruhstuck Zwischensumme (inkl. MwSt.) 1247.40 EUR "
                        + "GINN Hotelmanagement GmbH Bankverbindung: Berliner Volksbank eG")))
                .isEqualTo(DocumentType.INVOICE);
    }

    @Test
    void germanStatementListingAnInvoiceIsStillStatement() throws Exception {
        // A German statement's transaction lines name invoices ("Zahlung Rechnung 456"). Because the
        // invoice rule now matches "rechnung", the German statement title must be checked before it or
        // the whole statement flips to INVOICE.
        assertThat(classifier.classify("kontoauszug.pdf", "application/pdf",
                pdfWithText("Kontoauszug Nr. 9 Commerzbank Buchung Zahlung Rechnung 456 "
                        + "Alter Saldo 1000.00 Neuer Saldo 940.00 EUR")))
                .isEqualTo(DocumentType.BANK_STATEMENT);
    }

    @Test
    void invoiceInOtherEuLanguagesIsInvoice() throws Exception {
        // The clients buy across the EU, so the invoice word list is multilingual.
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("FACTURE n 2026-14 Montant total 450.00 EUR")))      // French
                .isEqualTo(DocumentType.INVOICE);
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("FATTURA n. 77 Imponibile 300.00 EUR IVA 22%")))      // Italian
                .isEqualTo(DocumentType.INVOICE);
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("Factuur nummer 2026-9 Totaal 120.00 EUR BTW 21%")))  // Dutch
                .isEqualTo(DocumentType.INVOICE);
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("SZAMLA sorszam 2026/55 Osszesen 40000 HUF")))        // Hungarian
                .isEqualTo(DocumentType.INVOICE);
    }

    @Test
    void germanProformaIsStillNotAFiscalDocument() throws Exception {
        // "Proformarechnung" contains both "rechnung" and "proforma" — the proforma guard must still win.
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("Proformarechnung Nr 5 Gesamtbetrag 100.00 EUR")))
                .isEqualTo(DocumentType.UNCLASSIFIED);
    }

    @Test
    void plainReceiptIsReceipt() throws Exception {
        // Real file: "receipt_f1dc7032-….pdf" is an Uber Eats receipt in English. RECEIPT used to be
        // reachable only via the payment-order phrasing, so this matched nothing and was refused even
        // though the Drive gate accepts receipts.
        assertThat(classifier.classify("receipt_f1dc7032-edfd-51ad-a5db-1bbf5c1375fc 2.pdf", "application/pdf",
                pdfWithText("Thanks for ordering, Mihaela Here's your receipt for Sushi For You "
                        + "Kleinmachnow Total 21.82 Item Subtotal 20.20 Marketplace Fee 1.62 Uber Delivery")))
                .isEqualTo(DocumentType.RECEIPT);
        // Romanian and German equivalents.
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("CHITANTA nr 12 Suma 150.00 RON")))
                .isEqualTo(DocumentType.RECEIPT);
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("Quittung Betrag 25.00 EUR Vielen Dank")))
                .isEqualTo(DocumentType.RECEIPT);
    }

    @Test
    void invoiceMentioningAReceiptIsStillInvoice() throws Exception {
        // Invoices routinely use the word "receipt", so the receipt rule must rank BELOW the invoice rule.
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("INVOICE no 88 Please retain this receipt for your records Total 90.00 EUR")))
                .isEqualTo(DocumentType.INVOICE);
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("Rechnung Nr 4 Beleg bitte aufbewahren receipt Gesamt 60.00 EUR")))
                .isEqualTo(DocumentType.INVOICE);
    }

    @Test
    void statementMentioningAReceiptIsStillStatement() throws Exception {
        // The statement markers are tested before both, so a statement listing a receipt stays a statement.
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("Extras de cont SOLD ANTERIOR receipt payment 120.00 SOLD FINAL")))
                .isEqualTo(DocumentType.BANK_STATEMENT);
    }

    @Test
    void revolutBrandedDocumentWithNoInvoiceSignalIsStatement() throws Exception {
        // A Revolut-branded PDF with neither the money-in/out table nor any invoice signal still falls
        // to the weak bank-name fallback, exactly like the other banks.
        assertThat(classifier.classify("x.pdf", "application/pdf",
                pdfWithText("Revolut Bank UAB account activity 01 Sep 2026 - 30 Sep 2026")))
                .isEqualTo(DocumentType.BANK_STATEMENT);
    }
}
