package ro.myfinance.taxpayments.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The computed Tax &amp; Payments view for one company and period: each declaration's extracted totals
 * (with mismatch flags), the grouped payment lines for the configured treasury IBANs, any categories
 * still missing an IBAN, the total to pay, and the ready-to-send email body.
 */
public record TaxPaymentSummary(UUID companyId, String companyName, String cui, LocalDate period,
                                String beneficiary, List<DeclarationSummary> declarations,
                                List<PaymentLine> paymentLines, List<Unconfigured> unconfigured,
                                BigDecimal totalToPay, String emailBody) {

    /** One extracted declaration: itemized total vs the form header, and whether they disagree. */
    public record DeclarationSummary(UUID documentId, String filename, DeclarationType type,
                                     BigDecimal computedTotal, BigDecimal declaredTotal, boolean mismatch) {
    }

    /** A payable category whose treasury IBAN isn't configured yet (so it can't be put on a payment line). */
    public record Unconfigured(TaxCategory category, BigDecimal amount) {
    }
}
