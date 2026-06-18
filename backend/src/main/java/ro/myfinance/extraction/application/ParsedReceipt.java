package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Fields extracted from a photographed fiscal receipt (bon fiscal). {@code confidence} is 0..1 and
 * {@code source} records which tier produced it (e.g. LLM, OCR, NONE). Amounts are non-authoritative
 * (golden rule) — low confidence routes the receipt to NEEDS_REVIEW.
 */
public record ParsedReceipt(String issuerName, String issuerCif, String clientCif, BigDecimal total,
                            String currency, LocalDate issueDate, String receiptNumber,
                            double confidence, String source) {

    public static ParsedReceipt empty() {
        return new ParsedReceipt(null, null, null, null, null, null, null, 0.0, "NONE");
    }
}
