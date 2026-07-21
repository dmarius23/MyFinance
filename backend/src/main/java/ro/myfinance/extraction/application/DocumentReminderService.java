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
import ro.myfinance.access.application.EmailDispatchService;
import ro.myfinance.common.email.EmailHistory;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailStatus;

/**
 * Compose, send and record missing-document reminder emails for the bank-statements &amp; invoices hub.
 * Every send is recorded (SENT or FAILED), so the Statements list can show "last sent" per company and
 * the notification log keeps full history. Sending is always an explicit, user-initiated action with an
 * editable body — never automatic. The resolve → send → record mechanics live in the shared
 * {@link EmailDispatchService}; this service owns the reminder subject and the missing-document notification.
 */
@Service
@Transactional
public class DocumentReminderService {

    private static final Logger log = LoggerFactory.getLogger(DocumentReminderService.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM.yyyy");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");

    private final EmailHistoryRepository history;
    private final EmailDispatchService dispatch;
    private final ro.myfinance.notifications.application.NotificationService notifications;
    private final ReconciliationService reconciliation;

    public DocumentReminderService(EmailHistoryRepository history, EmailDispatchService dispatch,
                                   ro.myfinance.notifications.application.NotificationService notifications,
                                   ReconciliationService reconciliation) {
        this.history = history;
        this.dispatch = dispatch;
        this.notifications = notifications;
        this.reconciliation = reconciliation;
    }

    /** One reminder send, for the notification log / list. */
    public record ReminderView(UUID id, String recipient, EmailStatus status, Instant sentAt, String body) {
        public static ReminderView from(EmailHistory e) {
            return new ReminderView(e.getId(), e.getRecipient(), e.getStatus(), e.getSentAt(), e.getBody());
        }
    }

    /** Per-company "last sent" summary for the Statements list. */
    public record ReminderRow(UUID companyId, Instant lastSentAt, int count) {
    }

    /** Full send history for a company + period (newest first). */
    @Transactional(readOnly = true)
    public List<ReminderView> history(UUID companyId, LocalDate period) {
        return history.findByKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(
                        EmailKind.DOCUMENT_REMINDER, companyId, period.withDayOfMonth(1))
                .stream().map(ReminderView::from).toList();
    }

    /** Last-sent + count per company for a period (one row per company that has at least one send). */
    @Transactional(readOnly = true)
    public List<ReminderRow> listByPeriod(LocalDate period) {
        java.util.Map<UUID, java.util.List<EmailHistory>> byCompany = new java.util.LinkedHashMap<>();
        for (EmailHistory r : history.findByKindAndPeriodMonthOrderBySentAtDesc(
                EmailKind.DOCUMENT_REMINDER, period.withDayOfMonth(1))) {
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
        LocalDate month = period.withDayOfMonth(1);
        String subject = "Documente lipsă — " + MONTH.format(month);
        EmailHistory row = dispatch.dispatch(EmailKind.DOCUMENT_REMINDER, companyId, period, recipient,
                subject, body, null, null,
                () -> notifications.notifyCompanyReps(companyId, "DOC_REQUEST", "Documente solicitate",
                        docRequestBody(companyId, month)));
        return ReminderView.from(row);
    }

    /**
     * The in-app notification body: the intro line plus the concrete list of transactions still missing a
     * document (a bank transaction that requires an invoice/receipt and has none matched), so the rep sees
     * exactly what to upload. Falls back to the plain intro when nothing is missing (or the lookup fails).
     */
    private String docRequestBody(UUID companyId, LocalDate month) {
        String intro = "Contabilul a solicitat documente pentru luna " + MONTH.format(month) + ".";
        try {
            List<String> lines = reconciliation.transactionsWithMatches(companyId, month).stream()
                    .filter(tw -> tw.txn().isRequiresDocument() && tw.invoices().isEmpty())
                    .map(tw -> {
                        var t = tw.txn();
                        String who = t.getPartnerName() != null && !t.getPartnerName().isBlank()
                                ? t.getPartnerName() : t.getDescription();
                        String kind = t.getAmount().signum() > 0 ? "încasare" : "furnizor";
                        String amount = t.getAmount().abs().stripTrailingZeros().toPlainString();
                        return "• " + DAY.format(t.getTxnDate()) + " · " + (who == null ? "—" : who)
                                + " · " + amount + " lei (" + kind + ")";
                    })
                    .toList();
            if (lines.isEmpty()) {
                return intro;
            }
            return intro + "\nDocumente necesare:\n" + String.join("\n", lines);
        } catch (RuntimeException e) {
            log.warn("Could not build missing-document list for company {} period {}", companyId, month, e);
            return intro;
        }
    }
}
