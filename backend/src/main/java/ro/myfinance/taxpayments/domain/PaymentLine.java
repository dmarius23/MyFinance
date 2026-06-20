package ro.myfinance.taxpayments.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One line of a state-payment email: a single amount to a single treasury IBAN, with the categories it
 * aggregates, a human explanation, and the due date. Obligations sharing an IBAN are summed into one line.
 */
public record PaymentLine(String iban, BigDecimal amount, List<TaxCategory> categories,
                          String explanation, LocalDate scadenta) {
}
