package ro.myfinance.intake.adapter.external;

import java.io.IOException;
import java.text.Normalizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (hasEmbeddedXml(pdf)) {
                return DocumentType.DECLARATION;
            }
            String text = normalize(extractText(pdf));
            if (containsAny(text, "a.n.a.f", "anaf", "declarat", "d212", "d300", "d301", "d112")) {
                return DocumentType.DECLARATION;
            }
            if (containsAny(text, "extras de cont", "brd", "banca transilvania", "bcr", "ing bank", "raiffeisen")) {
                return DocumentType.BANK_STATEMENT;
            }
            if (containsAny(text, "factur", "invoice")) {
                return DocumentType.INVOICE;
            }
            if (containsAny(text, "balanta")) {
                return DocumentType.TRIAL_BALANCE;
            }
            return DocumentType.UNCLASSIFIED;
        } catch (IOException | RuntimeException e) {
            log.debug("Classification failed for {}, defaulting to UNCLASSIFIED", filename, e);
            return DocumentType.UNCLASSIFIED;
        }
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

    /** Lower-cased, diacritics-stripped, for accent-insensitive matching. */
    private String normalize(String s) {
        if (s == null) {
            return "";
        }
        String stripped = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return stripped.toLowerCase();
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
