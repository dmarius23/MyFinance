package ro.myfinance.extraction.application;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Extracts statement text once and picks the first parser whose supports() matches. Parsers are
 * injected in Spring @Order order — bank-specific parsers (low order) are tried before the generic
 * fallback (lowest precedence), so a specific parser always wins when it recognizes the statement.
 */
@Component
public class BankStatementParserRegistry {

    private final List<BankStatementParser> parsers;

    public BankStatementParserRegistry(List<BankStatementParser> parsers) {
        this.parsers = parsers;
    }

    /**
     * The parseable text of a statement. For a PDF, the extracted text (empty if unreadable). For a
     * non-PDF (e.g. a CAMT.053 XML or MT940 export), the raw UTF-8 content, so the structured parsers
     * can read it directly rather than through PDFBox.
     */
    public String extractText(byte[] content) {
        if (!isPdf(content)) {
            return new String(content, java.nio.charset.StandardCharsets.UTF_8);
        }
        try (PDDocument doc = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static boolean isPdf(byte[] b) {
        return b != null && b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
    }

    /** The first parser that recognizes this text, if any. */
    public Optional<BankStatementParser> find(String text) {
        return parsers.stream().filter(p -> p.supports(text)).findFirst();
    }
}
