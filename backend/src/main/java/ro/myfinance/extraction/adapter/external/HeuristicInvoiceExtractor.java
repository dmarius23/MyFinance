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
        String iban = null;
        BigDecimal total = null;
        LocalDate date = null;
        try {
            iban = firstMatch(IBAN, text);
            total = totalAmount(text);
            date = invoiceDate(text);
        } catch (RuntimeException e) {
            log.debug("Invoice field extraction failed", e); // best-effort; never throws to the caller
        }
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

    /**
     * Invoice issue date. Romanian invoices print both an issue date ("Data facturii") and a payment
     * due date ("Termen de plată" / "Scadență") — and the due date often appears first in the layout.
     * Matching keys off the issue date (payment happens on/after issuance), so we skip due-date lines
     * and take the earliest remaining date; only if every date sits on a due-date line do we fall back
     * to the earliest date overall.
     */
    private LocalDate invoiceDate(String text) {
        LocalDate earliestIssue = null;
        LocalDate earliestAny = null;
        for (String line : text.split("\\R")) {
            String norm = java.text.Normalizer.normalize(line, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase();
            boolean dueLine = norm.contains("scaden") || norm.contains("termen de plata");
            for (Matcher m = DATE.matcher(line); m.find(); ) {
                LocalDate d = parseDate(m.group());
                if (d == null) {
                    continue;
                }
                if (earliestAny == null || d.isBefore(earliestAny)) {
                    earliestAny = d;
                }
                if (!dueLine && (earliestIssue == null || d.isBefore(earliestIssue))) {
                    earliestIssue = d;
                }
            }
        }
        return earliestIssue != null ? earliestIssue : earliestAny;
    }

    private LocalDate parseDate(String token) {
        for (String pat : new String[] {"dd.MM.uuuu", "dd/MM/uuuu"}) {
            try {
                return LocalDate.parse(token, DateTimeFormatter.ofPattern(pat));
            } catch (RuntimeException ignored) {
                // try next pattern
            }
        }
        return null;
    }

    private String firstMatch(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    /**
     * Parse a money token in RO ("1.234,56") or EN ("1,234.56") format — robust to multiple grouping
     * separators (e.g. "1.234.567,89"). The decimal point is the last '.'/',' followed by exactly two
     * digits; every other separator is a thousands grouping and is stripped.
     */
    private BigDecimal parseMoney(String token) {
        int lastSep = Math.max(token.lastIndexOf('.'), token.lastIndexOf(','));
        if (lastSep >= 0 && token.length() - lastSep - 1 == 2) {
            String intPart = token.substring(0, lastSep).replaceAll("[.,]", "");
            return new BigDecimal((intPart.isEmpty() ? "0" : intPart) + "." + token.substring(lastSep + 1));
        }
        String digits = token.replaceAll("[.,]", "");
        return new BigDecimal(digits.isEmpty() ? "0" : digits);
    }
}
