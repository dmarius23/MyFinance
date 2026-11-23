package ro.myfinance.intake.adapter.external;

import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.myfinance.common.text.StringNormalizer;
import ro.myfinance.intake.application.DocumentClassifier;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Deterministic, best-effort document classifier. Images → RECEIPT; PDFs classified by embedded XML
 * (declarations) and Romanian text markers. Anything unrecognized → UNCLASSIFIED. Never throws.
 */
@Component
public class HeuristicDocumentClassifier implements DocumentClassifier {

    private static final Logger log = LoggerFactory.getLogger(HeuristicDocumentClassifier.class);

    @Override
    public DocumentType classify(String filename, String contentType, byte[] bytes) {
        if (contentType != null && contentType.toLowerCase().startsWith("image/")) {
            return DocumentType.RECEIPT;
        }
        if (!isPdf(bytes)) {
            // Non-PDF upload — e.g. a structured bank-statement export (CAMT.053 XML or MT940). Detect
            // those directly, else fall back to text-marker classification on the decoded content.
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (looksLikeCamt(text) || looksLikeMt940(text)) {
                return DocumentType.BANK_STATEMENT;
            }
            return classifyMarkers(StringNormalizer.foldLower(text));
        }
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (hasEmbeddedXml(pdf)) {
                return DocumentType.DECLARATION;
            }
            return classifyMarkers(StringNormalizer.foldLower(extractText(pdf)));
        } catch (IOException | RuntimeException e) {
            log.debug("Classification failed for {}, defaulting to UNCLASSIFIED", filename, e);
            return DocumentType.UNCLASSIFIED;
        }
    }

    @Override
    public DocumentType classifyText(String text) {
        return classifyMarkers(StringNormalizer.foldLower(text));
    }

    /** The deterministic text-marker rules, applied to already-normalised text. */
    private DocumentType classifyMarkers(String text) {
        // A payment proof — an "ordin de plata", or the bank's own "Confirmare tranzactie" printout — is
        // neither a statement nor a declaration. Tested FIRST because one settling an ANAF debt names ANAF
        // (→ would read as a declaration) and prints the bank's name without ever saying "factura" (→ would
        // fall to the bank-name rule below and read as a statement). Guarded by the statement markers so a
        // genuine statement that happens to use the phrase still wins. (Text is diacritic-folded already.)
        boolean statementLike = containsAny(text, "extras de cont", "sold anterior", "sold final", "rulaj");
        if (!statementLike && containsAny(text, "ordin de plata", "ordinul de plata", "payment order",
                "confirmare tranzactie", "transaction confirmation", "dispozitie de plata")) {
            return DocumentType.RECEIPT;
        }
        if (containsAny(text, "a.n.a.f", "anaf", "declarat", "d212", "d300", "d301", "d112")) {
            return DocumentType.DECLARATION;
        }
        // A trial balance ("balanta de verificare") is unambiguous from its title and shares column
        // words with statements ("sold final", "rulaj"), so it must be checked BEFORE the bank markers.
        if (containsAny(text, "balanta")) {
            return DocumentType.TRIAL_BALANCE;
        }
        // Strong, statement-specific markers next (so a statement is never mistaken for anything else).
        // A Revolut statement has no "extras de cont" / "Transactions List" title, so it is matched on
        // its transaction-table header — the same signature RevolutStatementParser.supports() uses. The
        // bare brand name is NOT a statement marker: a company banking with Revolut prints "Revolut Bank
        // UAB" and its IBAN in the payment details of every invoice it issues, and those are invoices.
        boolean revolutStatement = text.contains("revolut")
                && text.contains("money out") && text.contains("money in");
        // "kontoauszug" is the German statement title. It is listed here, ABOVE the invoice rule, because
        // that rule now matches "rechnung" — and a German statement's transaction lines routinely name an
        // invoice ("Zahlung Rechnung 456"), which would otherwise flip the whole statement to INVOICE.
        if (revolutStatement || containsAny(text, "extras de cont", "transactions list",
                "sold anterior", "sold final", "rulaj zi", "rulaj total cont", "kontoauszug")) {
            return DocumentType.BANK_STATEMENT;
        }
        // An invoice ("factura"), including a copy or duplicate, must win over a mere bank-name mention
        // (e.g. a BT Leasing invoice contains "Banca Transilvania" but is NOT a statement). A PROFORMA
        // invoice is not a fiscal document, so it stays unclassified.
        //
        // The word list is multilingual because the clients buy abroad: a Bolt ride in Berlin or a German
        // hotel bill says "Rechnung" and never "factura"/"invoice", and such a document used to fall through
        // to UNCLASSIFIED and be refused at the Drive gate. "factur" also covers the French "facture" and
        // the Spanish "factura"; Dutch "factuur" and Hungarian "szamla" (diacritics already folded) do not
        // share that stem and are listed separately. A German proforma reads "Proformarechnung", so the
        // proforma guard below still catches it.
        boolean invoiceLike = containsAny(text, "factur", "invoice", "rechnung", "fattura", "factuur", "szamla");
        if (invoiceLike && !containsAny(text, "proforma", "pro forma")) {
            return DocumentType.INVOICE;
        }
        // A plain receipt — the word itself, in the languages the clients actually receive. Until now
        // RECEIPT was only reachable through the payment-order phrasing tested at the top, so an ordinary
        // "Here's your receipt …" (Uber Eats, Stripe) matched nothing at all and was refused even though
        // the Drive gate accepts receipts. Tested AFTER the invoice rule on purpose: invoices routinely
        // say "receipt" ("please retain this receipt", "receipt of goods") and must stay invoices.
        if (containsAny(text, "receipt", "chitanta", "bon fiscal", "quittung", "ricevuta", "recibo")) {
            return DocumentType.RECEIPT;
        }
        // Weak fallback: a bank name with NO invoice signal → most likely a statement. An invoice-like
        // document (incl. a proforma) that merely prints its bank/IBAN must not be caught here.
        if (!invoiceLike && containsAny(text, "brd", "banca transilvania", "bcr", "ing bank",
                "raiffeisen", "revolut")) {
            return DocumentType.BANK_STATEMENT;
        }
        return DocumentType.UNCLASSIFIED;
    }

    private static boolean isPdf(byte[] b) {
        return b != null && b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
    }

    /** ISO 20022 CAMT.053 bank statement XML. */
    private static boolean looksLikeCamt(String text) {
        return text.contains("camt.053") || text.contains("BkToCstmrStmt");
    }

    /** SWIFT MT940 customer statement (tag-delimited). */
    private static boolean looksLikeMt940(String text) {
        return text.contains(":61:") && (text.contains(":60F:") || text.contains(":60M:"));
    }

    private String extractText(PDDocument pdf) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(1);
        stripper.setEndPage(Math.min(3, Math.max(1, pdf.getNumberOfPages())));
        return stripper.getText(pdf);
    }

    private boolean hasEmbeddedXml(PDDocument pdf) {
        try {
            PDDocumentCatalog catalog = pdf.getDocumentCatalog();
            PDDocumentNameDictionary names = catalog.getNames();
            if (names == null) {
                return false;
            }
            PDEmbeddedFilesNameTreeNode tree = names.getEmbeddedFiles();
            if (tree == null || tree.getNames() == null) {
                return false;
            }
            return tree.getNames().keySet().stream()
                    .anyMatch(n -> n != null && n.toLowerCase().endsWith(".xml"));
        } catch (IOException e) {
            return false;
        }
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
