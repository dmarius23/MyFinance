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

    /** Extract the plain text of a statement PDF (empty string if unreadable). */
    public String extractText(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /** The first parser that recognizes this text, if any. */
    public Optional<BankStatementParser> find(String text) {
        return parsers.stream().filter(p -> p.supports(text)).findFirst();
    }
}
