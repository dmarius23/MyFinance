package ro.myfinance.extraction.application;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** Picks the first parser whose supports() matches the statement text. */
@Component
public class BankStatementParserRegistry {

    private final List<BankStatementParser> parsers;

    public BankStatementParserRegistry(List<BankStatementParser> parsers) {
        this.parsers = parsers;
    }

    public Optional<BankStatementParser> find(byte[] pdf) {
        String text = extractText(pdf);
        return parsers.stream().filter(p -> p.supports(text)).findFirst();
    }

    private String extractText(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
