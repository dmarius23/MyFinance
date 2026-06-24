package ro.myfinance.extraction.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.extraction.adapter.persistence.DocumentReminderRepository;
import ro.myfinance.extraction.domain.DocumentReminder;
import ro.myfinance.taxpayments.application.EmailSender;

/**
 * Compose, send and record missing-document reminder emails for the bank-statements &amp; invoices hub.
 * Every send is recorded (SENT or FAILED), so the Statements list can show "last sent" per company and
 * the notification log keeps full history. Sending is always an explicit, user-initiated action with an
 * editable body — never automatic.
 */
@Service
@Transactional
public class DocumentReminderService {

    private static final Logger log = LoggerFactory.getLogger(DocumentReminderService.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM.yyyy");

    private final DocumentReminderRepository reminders;
    private final EmailSender sender;
    private final ro.myfinance.access.application.EmailEnvelopeService envelopes;
    private final ro.myfinance.notifications.application.NotificationService notifications;

    public DocumentReminderService(DocumentReminderRepository reminders, EmailSender sender,
                                   ro.myfinance.access.application.EmailEnvelopeService envelopes,
                                   ro.myfinance.notifications.application.NotificationService notifications) {
        this.reminders = reminders;
        this.sender = sender;
        this.envelopes = envelopes;
        this.notifications = notifications;
    }

    /** One reminder send, for the notification log / list. */
    public record ReminderView(UUID id, String recipient, DocumentReminder.Status status, Instant sentAt, String body) {
        public static ReminderView from(DocumentReminder r) {
            return new ReminderView(r.getId(), r.getRecipient(), r.getStatus(), r.getSentAt(), r.getBody());
        }
    }

    /** Per-company "last sent" summary for the Statements list. */
    public record ReminderRow(UUID companyId, Instant lastSentAt, int count) {
    }

    /** Full send history for a company + period (newest first). */
    @Transactional(readOnly = true)
    public List<ReminderView> history(UUID companyId, LocalDate period) {
        return reminders.findByCompanyIdAndPeriodMonthOrderBySentAtDesc(companyId, period.withDayOfMonth(1))
                .stream().map(ReminderView::from).toList();
    }

    /** Last-sent + count per company for a period (one row per company that has at least one send). */
    @Transactional(readOnly = true)
    public List<ReminderRow> listByPeriod(LocalDate period) {
        java.util.Map<UUID, java.util.List<DocumentReminder>> byCompany = new java.util.LinkedHashMap<>();
        for (DocumentReminder r : reminders.findByPeriodMonthOrderBySentAtDesc(period.withDayOfMonth(1))) {
            byCompany.computeIfAbsent(r.getCompanyId(), k -> new java.util.ArrayList<>()).add(r);
        }
        List<ReminderRow> out = new java.util.ArrayList<>();
        for (var e : byCompany.entrySet()) {
            // List is sorted desc by sentAt, so the first element is the most recent.
            out.add(new ReminderRow(e.getKey(), e.getValue().get(0).getSentAt(), e.getValue().size()));
        }
        return out;
    }

    /**
     * Record and dispatch one reminder with the (possibly edited) body. Always persists a row:
     * SENT on success, FAILED with the error otherwise.
     */
    public ReminderView send(UUID companyId, LocalDate period, String recipient, String body) {
        UUID tenantId = TenantContext.tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound"));
        UUID userId = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        LocalDate month = period.withDayOfMonth(1);
        String subject = "Documente lipsă — " + MONTH.format(month);
        // From = logged-in user (name) + accounting firm (address); recipient defaults to the rep.
        var env = envelopes.resolve(companyId, recipient);
        String to = env.recipient();

        DocumentReminder.Status status = DocumentReminder.Status.SENT;
        String error = null;
        try {
            sender.send(new EmailSender.Message(env.fromName(), env.fromEmail(), to, subject, body, List.of()));
        } catch (RuntimeException e) {
            status = DocumentReminder.Status.FAILED;
            error = e.getMessage();
            log.warn("Reminder send failed for company {} period {}", companyId, month, e);
        }
        if (status == DocumentReminder.Status.SENT) {
            notifications.notifyCompanyReps(companyId, "DOC_REQUEST", "Documente solicitate",
                    "Contabilul a solicitat documente pentru luna " + MONTH.format(month) + ".");
        }
        return ReminderView.from(reminders.save(new DocumentReminder(
                tenantId, companyId, month, to, body, status, error, userId)));
    }
}
