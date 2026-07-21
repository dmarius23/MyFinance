package ro.myfinance.taxpayments.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.application.EmailDispatchService;
import ro.myfinance.common.email.EmailHistory;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.taxpayments.application.TaxPaymentService.Computation;

/**
 * Compose, send and record state-payment emails. Every send is recorded (SENT or FAILED) and linked to
 * the declarations it covered, so the history is complete and an email can be resent any time. Sending a
 * client email is an explicit, user-initiated action with an editable body — never automatic. The
 * resolve → send → record mechanics live in the shared {@link EmailDispatchService}; this service owns
 * only the tax-specific subject, the covered-declaration ids, and the tax-due client notification.
 */
@Service
@Transactional
public class TaxEmailService {

    private static final Logger log = LoggerFactory.getLogger(TaxEmailService.class);

    private final TaxPaymentService payments;
    private final EmailHistoryRepository history;
    private final EmailDispatchService dispatch;
    private final ro.myfinance.notifications.application.NotificationService notifications;

    public TaxEmailService(TaxPaymentService payments, EmailHistoryRepository history,
                           EmailDispatchService dispatch,
                           ro.myfinance.notifications.application.NotificationService notifications) {
        this.payments = payments;
        this.history = history;
        this.dispatch = dispatch;
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
        return history.findByKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(
                        EmailKind.TAX, companyId, period.withDayOfMonth(1))
                .stream().map(ro.myfinance.taxpayments.domain.TaxPaymentSummary.EmailView::from).toList();
    }

    /**
     * Record and dispatch one email for the chosen declarations with the (possibly edited) body. Always
     * persists a row: SENT on success, FAILED with the error otherwise.
     */
    public EmailHistory send(UUID companyId, LocalDate period, List<UUID> declarationIds, String recipient, String body) {
        LocalDate month = period.withDayOfMonth(1);
        String subject = subject(companyId, month);
        return dispatch.dispatch(EmailKind.TAX, companyId, period, recipient, subject, body, null, declarationIds,
                () -> notifyTaxDue(companyId, month, declarationIds));
    }

    /** The tax-due client notification — best-effort: building it must never fail the recorded send. */
    private void notifyTaxDue(UUID companyId, LocalDate month, List<UUID> declarationIds) {
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

    private String subject(UUID companyId, LocalDate month) {
        YearMonth ym = YearMonth.from(month);
        return "Sume de plată — " + PaymentCalculator.monthYear(ym).replace('-', ' ');
    }
}
