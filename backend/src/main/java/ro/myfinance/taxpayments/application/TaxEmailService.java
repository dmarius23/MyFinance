package ro.myfinance.taxpayments.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.email.EmailSender;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.taxpayments.adapter.persistence.TaxEmailRepository;
import ro.myfinance.taxpayments.application.TaxPaymentService.Computation;
import ro.myfinance.taxpayments.domain.TaxEmail;

/**
 * Compose, send and record state-payment emails. Every send is recorded (SENT or FAILED) and linked to
 * the declarations it covered, so the history is complete and an email can be resent any time. Sending a
 * client email is an explicit, user-initiated action with an editable body — never automatic.
 */
@Service
@Transactional
public class TaxEmailService {

    private static final Logger log = LoggerFactory.getLogger(TaxEmailService.class);

    private final TaxPaymentService payments;
    private final TaxEmailRepository emails;
    private final EmailSender sender;
    private final ro.myfinance.access.application.EmailEnvelopeService envelopes;
    private final ro.myfinance.notifications.application.NotificationService notifications;

    public TaxEmailService(TaxPaymentService payments, TaxEmailRepository emails, EmailSender sender,
                           ro.myfinance.access.application.EmailEnvelopeService envelopes,
                           ro.myfinance.notifications.application.NotificationService notifications) {
        this.payments = payments;
        this.emails = emails;
        this.sender = sender;
        this.envelopes = envelopes;
        this.notifications = notifications;
    }

    /** Default editable body + totals for the chosen declarations. */
    @Transactional(readOnly = true)
    public Computation preview(UUID companyId, List<UUID> declarationIds) {
        return payments.composeFor(companyId, declarationIds);
    }

    /** Full send history for a company + period (newest first), for the notification log. */
    @Transactional(readOnly = true)
    public List<ro.myfinance.taxpayments.domain.TaxPaymentSummary.EmailView> history(UUID companyId, LocalDate period) {
        return emails.findByCompanyIdAndPeriodMonthOrderBySentAtDesc(companyId, period.withDayOfMonth(1))
                .stream().map(ro.myfinance.taxpayments.domain.TaxPaymentSummary.EmailView::from).toList();
    }

    /**
     * Record and dispatch one email for the chosen declarations with the (possibly edited) body. Always
     * persists a row: SENT on success, FAILED with the error otherwise.
     */
    public TaxEmail send(UUID companyId, LocalDate period, List<UUID> declarationIds, String recipient, String body) {
        UUID tenantId = TenantContext.tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound"));
        UUID userId = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        LocalDate month = period.withDayOfMonth(1);
        String subject = subject(companyId, month);
        // From = logged-in user (name) + accounting firm (address); recipient defaults to the rep.
        var env = envelopes.resolve(companyId, recipient);
        String to = env.recipient();

        TaxEmail.Status status = TaxEmail.Status.SENT;
        String error = null;
        try {
            sender.send(new EmailSender.Message(env.fromName(), env.fromEmail(), to, subject, body, List.of()));
        } catch (RuntimeException e) {
            status = TaxEmail.Status.FAILED;
            error = e.getMessage();
            log.warn("Email send failed for company {} period {}", companyId, month, e);
        }
        if (status == TaxEmail.Status.SENT) {
            // Building the client notification must never fail the recorded send.
            try {
                java.math.BigDecimal total = payments.composeFor(companyId, declarationIds).total();
                String amount = total.stripTrailingZeros().toPlainString();
                notifications.notifyCompanyReps(companyId, "TAX_DUE", "Sume de plată la stat",
                        "Contabilul a trimis situația impozitelor pentru luna " + PaymentCalculator.monthYear(YearMonth.from(month))
                                + ". Total de plată: " + amount + " lei.");
            } catch (RuntimeException e) {
                log.warn("Failed to build tax-due notification for company {} period {}", companyId, month, e);
            }
        }
        return emails.save(new TaxEmail(tenantId, companyId, month, declarationIds, to, body,
                status, error, userId));
    }

    private String subject(UUID companyId, LocalDate month) {
        YearMonth ym = YearMonth.from(month);
        return "Sume de plată — " + PaymentCalculator.monthYear(ym).replace('-', ' ');
    }
}
