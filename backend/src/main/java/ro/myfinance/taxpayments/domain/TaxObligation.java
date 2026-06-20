package ro.myfinance.taxpayments.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One obligation extracted from a declaration: the ANAF obligation code, its category, the itemized
 * amount (the authoritative figure), and its due date. A positive amount is "de plată" (to pay); a
 * negative amount is "de restituit/de recuperat" (a refund — recorded for visibility, never paid).
 */
public record TaxObligation(TaxCategory category, String codOblig, BigDecimal amount, LocalDate scadenta) {

    public boolean payable() {
        return amount.signum() > 0;
    }

    public boolean refund() {
        return amount.signum() < 0;
    }
}
