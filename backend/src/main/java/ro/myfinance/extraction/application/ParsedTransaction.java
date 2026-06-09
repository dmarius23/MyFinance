package ro.myfinance.extraction.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A transaction line produced by a bank-statement parser (amount signed: negative = debit). */
public record ParsedTransaction(LocalDate date, BigDecimal amount, String partnerName,
                                String partnerIban, String description, String ref,
                                BigDecimal balanceAfter) {
}
