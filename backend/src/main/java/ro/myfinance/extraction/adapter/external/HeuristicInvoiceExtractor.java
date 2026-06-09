package ro.myfinance.extraction.adapter.external;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.myfinance.extraction.application.InvoiceExtractor;
import ro.myfinance.extraction.application.ParsedInvoice;

/** Best-effort invoice field extraction: supplier IBAN, total, date. Never throws. */
@Component
public class HeuristicInvoiceExtractor implements InvoiceExtractor {

    private static final Logger log = LoggerFactory.getLogger(HeuristicInvoiceExtractor.class);
    private static final Pattern IBAN = Pattern.compile("\\bRO\\d{2}[A-Z0-9]{10,}\\b");
    private static final Pattern MONEY = Pattern.compile("\\d[\\d.,]*[.,]\\d{2}");
    private static final Pattern DATE = Pattern.compile("(\\d{2})[/.](\\d{2})[/.](\\d{4})");

    @Override
    public ParsedInvoice extract(byte[] pdf) {
        String text;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            text = new PDFTextStripper().getText(doc);
        } catch (IOException | RuntimeException e) {
            log.debug("Invoice text extraction failed", e);
            return new ParsedInvoice(null, null, null, null);
        }
        String iban = firstMatch(IBAN, text);
        BigDecimal total = totalAmount(text);
        LocalDate date = invoiceDate(text);
        return new ParsedInvoice(null, iban, total, date);
    }

    private BigDecimal totalAmount(String text) {
        // Prefer the money token on a line mentioning "total"; else the largest money token.
        BigDecimal labelled = null;
        BigDecimal largest = null;
        for (String line : text.split("\\R")) {
            for (Matcher m = MONEY.matcher(line); m.find(); ) {
                BigDecimal v = parseMoney(m.group());
                if (largest == null || v.compareTo(largest) > 0) {
                    largest = v;
                }
                String norm = java.text.Normalizer.normalize(line, java.text.Normalizer.Form.NFD)
                        .replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase();
                if (norm.contains("total")) {
                    if (labelled == null || v.compareTo(labelled) > 0) {
                        labelled = v;
                    }
                }
            }
        }
        return labelled != null ? labelled : largest;
    }

    private LocalDate invoiceDate(String text) {
        Matcher m = DATE.matcher(text);
        while (m.find()) {
            for (String pat : new String[] {"dd.MM.uuuu", "dd/MM/uuuu"}) {
                try {
                    return LocalDate.parse(m.group(), DateTimeFormatter.ofPattern(pat));
                } catch (RuntimeException ignored) {
                    // try next
                }
            }
        }
        return null;
    }

    private String firstMatch(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** RO ("1.234,56") or EN ("1,234.56") money token. */
    private BigDecimal parseMoney(String token) {
        int lastDot = token.lastIndexOf('.');
        int lastComma = token.lastIndexOf(',');
        String n;
        if (lastDot >= 0 && lastComma >= 0) {
            n = lastDot > lastComma ? token.replace(",", "") : token.replace(".", "").replace(",", ".");
        } else if (lastComma >= 0) {
            n = (token.length() - lastComma - 1 == 2) ? token.replace(",", ".") : token.replace(",", "");
        } else {
            n = token;
        }
        return new BigDecimal(n);
    }
}
