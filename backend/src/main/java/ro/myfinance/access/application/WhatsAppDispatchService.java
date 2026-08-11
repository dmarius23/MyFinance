package ro.myfinance.access.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.email.EmailHistory;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.common.email.MessageChannel;
import ro.myfinance.common.outbox.OutboxWriter;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.whatsapp.WhatsAppOutboxHandler;
import ro.myfinance.common.whatsapp.WhatsAppSender;

/**
 * The one place a client WhatsApp message is resolved, recorded and durably queued — the channel analogue
 * of {@link EmailDispatchService}. Given a {@link EmailKind} plus the composed body, it resolves the
 * recipient phone (the company's representative, unless overridden), appends one {@link EmailHistory} row
 * (channel = WHATSAPP, status QUEUED) and writes a {@code SEND_WHATSAPP} outbox message in the same
 * transaction — so the send is committed atomically with the history and never lost on a crash. The
 * worker relay then delivers it (with retries) and flips the row to SENT, or FAILED on DLQ.
 */
@Service
@Transactional
public class WhatsAppDispatchService {

    private final EmailEnvelopeService envelopes;
    private final EmailHistoryRepository history;
    private final OutboxWriter outbox;
    private final WhatsAppOutboxHandler whatsappOutbox;

    public WhatsAppDispatchService(EmailEnvelopeService envelopes, EmailHistoryRepository history,
                                   OutboxWriter outbox, WhatsAppOutboxHandler whatsappOutbox) {
        this.envelopes = envelopes;
        this.history = history;
        this.outbox = outbox;
        this.whatsappOutbox = whatsappOutbox;
    }

    /**
     * Resolve → record (QUEUED) → enqueue one WhatsApp message. {@code recipientOverride} (a phone) wins
     * over the representative; {@code relatedIds} may be null; {@code onSent} runs once durably queued.
     */
    public EmailHistory dispatch(EmailKind kind, UUID companyId, LocalDate period, String recipientOverride,
                                 String body, List<UUID> relatedIds, Runnable onSent) {
        UUID tenantId = TenantContext.tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound"));
        UUID userId = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        LocalDate month = period.withDayOfMonth(1);

        String toPhone = recipientOverride != null && !recipientOverride.isBlank()
                ? recipientOverride.trim()
                : envelopes.representativePhone(companyId);

        EmailHistory row = history.save(new EmailHistory(tenantId, kind, MessageChannel.WHATSAPP, companyId,
                month, relatedIds == null ? List.of() : relatedIds, toPhone, body, EmailStatus.QUEUED, null, userId));

        var message = new WhatsAppSender.Message(envelopes.currentUserName(), toPhone, body);
        outbox.enqueue(WhatsAppOutboxHandler.AGGREGATE, row.getId().toString(), WhatsAppOutboxHandler.TYPE,
                whatsappOutbox.serialize(new WhatsAppOutboxHandler.Payload(row.getId(), message)));

        if (onSent != null) {
            onSent.run();
        }
        return row;
    }

    /** WhatsApp send history for one company/period + kind (newest first) — the log modal. */
    @Transactional(readOnly = true)
    public List<EmailHistory> history(EmailKind kind, UUID companyId, LocalDate period) {
        return history.findByChannelAndKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(
                MessageChannel.WHATSAPP, kind, companyId, period.withDayOfMonth(1));
    }

    /** The phone WhatsApp would send to for this company (the representative's), for pre-filling the UI. */
    @Transactional(readOnly = true)
    public String resolveRecipient(UUID companyId) {
        return envelopes.representativePhone(companyId);
    }
}
