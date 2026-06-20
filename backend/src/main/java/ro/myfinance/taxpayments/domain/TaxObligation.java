package ro.myfinance.taxpayments.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One payable obligation extracted from a declaration: the ANAF obligation code, its category, the
 * amount to pay (itemized — the authoritative figure), and its due date.
 */
public record TaxObligation(TaxCategory category, String codOblig, BigDecimal amount, LocalDate scadenta) {
}
