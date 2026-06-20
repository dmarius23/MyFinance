package ro.myfinance.taxpayments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The computed Tax &amp; Payments view for one company and period: each stored declaration (with mismatch
 * flags and how many times it's been emailed), the grouped payment lines for the configured treasury
 * IBANs, any categories still missing an IBAN, the total to pay, a default email body, and the full
 * history of emails sent for the period.
 */
public record TaxPaymentSummary(UUID companyId, String companyName, String cui, LocalDate period,
                                String beneficiary, List<DeclarationSummary> declarations,
                                List<PaymentLine> paymentLines, List<Unconfigured> unconfigured,
                                BigDecimal totalToPay, String emailBody, List<EmailView> emails) {

    /** One stored declaration: itemized vs header total, mismatch flag, and its email send count/last. */
    public record DeclarationSummary(UUID id, UUID documentId, DeclarationType type, BigDecimal computedTotal,
                                     BigDecimal declaredTotal, boolean mismatch, int sentCount, Instant lastSentAt) {
    }

    /** A payable category whose treasury IBAN isn't configured yet (so it can't be put on a payment line). */
    public record Unconfigured(TaxCategory category, BigDecimal amount) {
    }

    /** One past send, for the history list (and to repopulate the editor on resend). */
    public record EmailView(UUID id, String recipient, TaxEmail.Status status, Instant sentAt,
                            List<UUID> declarationIds, String body) {
        public static EmailView from(TaxEmail e) {
            return new EmailView(e.getId(), e.getRecipient(), e.getStatus(), e.getSentAt(),
                    e.getDeclarationIds(), e.getBody());
        }
    }
}
