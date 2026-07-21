package ro.myfinance.payroll.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.application.EmailDispatchService;
import ro.myfinance.access.application.EmailEnvelopeService;
import ro.myfinance.common.email.EmailHistory;
import ro.myfinance.common.email.EmailHistoryRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.email.EmailSender;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentType;

/**
 * MOD-08 Payroll. Manual upload of payroll files per company/month (reusing the document store as
 * type=PAYROLL), then an explicit, user-initiated email to the client with the standard Romanian body
 * and the payroll documents attached. Every send is recorded (SENT/FAILED) so the list shows "last
 * sent" and the log keeps history. No money figures are computed here — amounts live in the attachments.
 * The resolve → send → record mechanics live in the shared {@link EmailDispatchService}.
 */
@Service
@Transactional
public class PayrollService {

    private static final java.time.format.DateTimeFormatter MONTH_RO =
            java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale.forLanguageTag("ro"));

    private final DocumentService documents;
    private final EmailHistoryRepository history;
    private final EmailDispatchService dispatch;
    private final EmailEnvelopeService envelopes;
    private final ro.myfinance.notifications.application.NotificationService notifications;

    public PayrollService(DocumentService documents, EmailHistoryRepository history,
                          EmailDispatchService dispatch, EmailEnvelopeService envelopes,
                          ro.myfinance.notifications.application.NotificationService notifications) {
        this.documents = documents;
        this.history = history;
        this.dispatch = dispatch;
        this.envelopes = envelopes;
        this.notifications = notifications;
    }

    /** One payroll document (for the list chips and the attach set). */
    public record PayrollDoc(UUID id, String filename) {
    }

    /** Per-company payroll status for the monthly list. */
    public record PayrollRow(UUID companyId, List<PayrollDoc> documents, Instant lastSentAt, int sentCount) {
    }

    /** One payroll email send (notification log + resend). */
    public record PayrollEmailView(UUID id, String recipient, EmailStatus status, Instant sentAt,
                                   List<UUID> documentIds, String body) {
        public static PayrollEmailView from(EmailHistory e) {
            return new PayrollEmailView(e.getId(), e.getRecipient(), e.getStatus(), e.getSentAt(),
                    e.getRelatedIds(), e.getBody());
        }
    }

    /** Per-company rows for the period: payroll docs uploaded + last-sent summary. */
    @Transactional(readOnly = true)
    public List<PayrollRow> summary(LocalDate period) {
        LocalDate month = period.withDayOfMonth(1);
        Map<UUID, List<PayrollDoc>> docsByCompany = new LinkedHashMap<>();
        for (Document d : documents.listByPeriodAndType(month, DocumentType.PAYROLL)) {
            docsByCompany.computeIfAbsent(d.getCompanyId(), k -> new ArrayList<>())
                    .add(new PayrollDoc(d.getId(), d.getOriginalFilename()));
        }
        Map<UUID, List<EmailHistory>> emailsByCompany = new LinkedHashMap<>();
        for (EmailHistory e : history.findByKindAndPeriodMonthOrderBySentAtDesc(EmailKind.PAYROLL, month)) {
            emailsByCompany.computeIfAbsent(e.getCompanyId(), k -> new ArrayList<>()).add(e);
        }
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        ids.addAll(docsByCompany.keySet());
        ids.addAll(emailsByCompany.keySet());

        List<PayrollRow> out = new ArrayList<>();
        for (UUID companyId : ids) {
            List<PayrollDoc> docs = docsByCompany.getOrDefault(companyId, List.of());
            List<EmailHistory> es = emailsByCompany.getOrDefault(companyId, List.of());
            Instant last = es.isEmpty() ? null : es.get(0).getSentAt(); // sorted desc
            out.add(new PayrollRow(companyId, docs, last, es.size()));
        }
        return out;
    }

    /** Payroll documents uploaded for a company/period. */
    @Transactional(readOnly = true)
    public List<PayrollDoc> documentsFor(UUID companyId, LocalDate period) {
        return documents.listByCompanyPeriodType(companyId, period.withDayOfMonth(1), DocumentType.PAYROLL)
                .stream().map(d -> new PayrollDoc(d.getId(), d.getOriginalFilename())).toList();
    }

    /** Default editable email body for a company/period — signed with the logged-in user's name. */
    @Transactional(readOnly = true)
    public String composeBody(UUID companyId, LocalDate period) {
        return PayrollEmailBuilder.body(period, envelopes.currentUserName());
    }

    /** Full send history for a company + period (newest first). */
    @Transactional(readOnly = true)
    public List<PayrollEmailView> history(UUID companyId, LocalDate period) {
        return history.findByKindAndCompanyIdAndPeriodMonthOrderBySentAtDesc(
                        EmailKind.PAYROLL, companyId, period.withDayOfMonth(1))
                .stream().map(PayrollEmailView::from).toList();
    }

    /**
     * Record + dispatch one payroll email with the (possibly edited) body, attaching the company's
     * payroll documents for the period. Always persists a row: SENT on success, FAILED otherwise.
     */
    public PayrollEmailView send(UUID companyId, LocalDate period, String recipient, String body,
                                 List<UUID> documentIds) {
        LocalDate month = period.withDayOfMonth(1);

        // Attach the company's payroll documents for the period. When the caller passes an explicit set
        // (the user unchecked some in the compose modal) only those are attached; null = attach all.
        List<Document> docs = documents.listByCompanyPeriodType(companyId, month, DocumentType.PAYROLL);
        if (documentIds != null) {
            java.util.Set<UUID> wanted = new java.util.HashSet<>(documentIds);
            docs = docs.stream().filter(d -> wanted.contains(d.getId())).toList();
        }
        List<UUID> docIds = docs.stream().map(Document::getId).toList();
        List<EmailSender.Attachment> attachments = new ArrayList<>();
        for (Document d : docs) {
            byte[] bytes = documents.getContent(d.getId()).bytes();
            attachments.add(new EmailSender.Attachment(d.getOriginalFilename(), d.getContentType(), bytes));
        }

        EmailHistory row = dispatch.dispatch(EmailKind.PAYROLL, companyId, period, recipient,
                PayrollEmailBuilder.subject(month), body, attachments, docIds,
                () -> notifications.notifyCompanyReps(companyId, "PAYROLL_READY", "State de plată disponibile",
                        "Statul de plată, fluturașul de salariu și pontajul pentru luna " + MONTH_RO.format(month)
                                + " sunt disponibile în aplicație și un email a fost trimis."));
        return PayrollEmailView.from(row);
    }
}
