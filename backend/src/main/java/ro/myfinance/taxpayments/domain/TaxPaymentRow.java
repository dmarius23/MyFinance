package ro.myfinance.taxpayments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the monthly Tax &amp; Payments list: a company (name + CUI + fiscal residence), the
 * declarations it has uploaded for the period (one cell per declaration), and whether/when an email
 * was last sent.
 */
public record TaxPaymentRow(UUID companyId, String companyName, String cui, String residence,
                            List<DeclarationCell> declarations, Instant lastEmailAt, int emailCount) {

    /** A single uploaded declaration shown in its own column. */
    public record DeclarationCell(UUID id, DeclarationType type, BigDecimal amount, boolean mismatch) {
    }
}
