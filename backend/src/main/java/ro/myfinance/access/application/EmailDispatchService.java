package ro.myfinance.access.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.email.EmailHistory;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailOutboxHandler;
import ro.myfinance.common.email.EmailSender;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.common.outbox.OutboxWriter;
import ro.myfinance.common.security.TenantContext;

/**
 * The one place a client email is resolved, recorded and durably queued — shared by every module (tax,
 * reports, payroll, document reminders), which previously each had a copy-pasted send/record flow. Given
 * a {@link EmailKind} plus the composed subject/body/attachments, it: resolves the From/To via
 * {@link EmailEnvelopeService}, appends one {@link EmailHistory} row as {@code QUEUED}, and writes a
 * {@code SEND_EMAIL} {@link OutboxWriter outbox} message in the <b>same transaction</b> — so the send is
 * committed atomically with the history and never lost on a crash. The worker relay then delivers it
 * (with retries) and flips the history row to SENT, or FAILED if it exhausts its retries.
 *
 * <p>The caller supplies an optional {@code onSent} action (e.g. a client notification) run once the email
 * is durably queued (before the transaction commits) — preserving each module's original ordering and
 * rollback behaviour: if enqueue fails, the history row, the outbox row and the notification all roll back
 * together. Extracted amounts / totals are computed by the caller, never here.
 */
@Service
@Transactional
public class EmailDispatchService {

    private final EmailEnvelopeService envelopes;
    private final EmailHistoryRepository history;
    private final OutboxWriter outbox;
    private final EmailOutboxHandler emailOutbox;

    public EmailDispatchService(EmailEnvelopeService envelopes, EmailHistoryRepository history,
                                OutboxWriter outbox, EmailOutboxHandler emailOutbox) {
        this.envelopes = envelopes;
        this.history = history;
        this.outbox = outbox;
        this.emailOutbox = emailOutbox;
    }

    /**
     * Resolve → record (QUEUED) → enqueue one email. {@code recipientOverride} wins over the company
     * representative; {@code attachments}/{@code relatedIds} may be null (treated as empty); {@code onSent}
     * runs once the email is durably queued.
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

        EmailHistory row = history.save(new EmailHistory(tenantId, kind, companyId, month,
                relatedIds == null ? List.of() : relatedIds, to, body, EmailStatus.QUEUED, null, userId));

        var message = new EmailSender.Message(env.fromName(), env.fromEmail(), to, subject, body,
                attachments == null ? List.of() : attachments);
        outbox.enqueue(EmailOutboxHandler.AGGREGATE, row.getId().toString(), EmailOutboxHandler.TYPE,
                emailOutbox.serialize(new EmailOutboxHandler.Payload(row.getId(), message)));

        if (onSent != null) {
            onSent.run();
        }
        return row;
    }
}
