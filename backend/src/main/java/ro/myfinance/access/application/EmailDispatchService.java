package ro.myfinance.access.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.email.EmailHistory;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailSender;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.common.security.TenantContext;

/**
 * The one place a client email is resolved, sent and recorded — shared by every module (tax, reports,
 * payroll, document reminders), which previously each had a copy-pasted send/record flow. Given a
 * {@link EmailKind} plus the composed subject/body/attachments, it: resolves the From/To via
 * {@link EmailEnvelopeService}, hands the message to the {@link EmailSender} port, and appends one
 * {@link EmailHistory} row (SENT on success, FAILED with the error otherwise). The row is always written,
 * so the send is never silently lost.
 *
 * <p>The caller supplies an optional {@code onSent} action (e.g. a client notification) run only when the
 * send succeeds, before the row is saved — preserving each module's original ordering and rollback
 * behaviour. Extracted amounts / totals are computed by the caller, never here.
 */
@Service
@Transactional
public class EmailDispatchService {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchService.class);

    private final EmailEnvelopeService envelopes;
    private final EmailSender sender;
    private final EmailHistoryRepository history;

    public EmailDispatchService(EmailEnvelopeService envelopes, EmailSender sender,
                                EmailHistoryRepository history) {
        this.envelopes = envelopes;
        this.sender = sender;
        this.history = history;
    }

    /**
     * Resolve → send → record one email. {@code recipientOverride} wins over the company representative;
     * {@code attachments}/{@code relatedIds} may be null (treated as empty); {@code onSent} runs only on a
     * successful send.
     */
    public EmailHistory dispatch(EmailKind kind, UUID companyId, LocalDate period, String recipientOverride,
                                 String subject, String body, List<EmailSender.Attachment> attachments,
                                 List<UUID> relatedIds, Runnable onSent) {
        UUID tenantId = TenantContext.tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound"));
        UUID userId = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        LocalDate month = period.withDayOfMonth(1);

        // From = logged-in user (name) + accounting firm (address); recipient defaults to the rep.
        var env = envelopes.resolve(companyId, recipientOverride);
        String to = env.recipient();

        EmailStatus status = EmailStatus.SENT;
        String error = null;
        try {
            sender.send(new EmailSender.Message(env.fromName(), env.fromEmail(), to, subject, body,
                    attachments == null ? List.of() : attachments));
        } catch (RuntimeException e) {
            status = EmailStatus.FAILED;
            error = e.getMessage();
            log.warn("Email send failed (kind={} company={} period={})", kind, companyId, month, e);
        }
        if (status == EmailStatus.SENT && onSent != null) {
            onSent.run();
        }
        return history.save(new EmailHistory(tenantId, kind, companyId, month,
                relatedIds == null ? List.of() : relatedIds, to, body, status, error, userId));
    }
}
