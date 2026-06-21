package ro.myfinance.payroll.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentType;
import ro.myfinance.payroll.adapter.persistence.PayrollEmailRepository;
import ro.myfinance.payroll.domain.PayrollEmail;
import ro.myfinance.taxpayments.application.EmailSender;

/**
 * MOD-08 Payroll. Manual upload of payroll files per company/month (reusing the document store as
 * type=PAYROLL), then an explicit, user-initiated email to the client with the standard Romanian body
 * and the payroll documents attached. Every send is recorded (SENT/FAILED) so the list shows "last
 * sent" and the log keeps history. No money figures are computed here — amounts live in the attachments.
 */
@Service
@Transactional
public class PayrollService {

    private static final Logger log = LoggerFactory.getLogger(PayrollService.class);

    private final DocumentService documents;
    private final PayrollEmailRepository emails;
    private final EmailSender sender;

    public PayrollService(DocumentService documents, PayrollEmailRepository emails, EmailSender sender) {
        this.documents = documents;
        this.emails = emails;
        this.sender = sender;
    }

    /** One payroll document (for the list chips and the attach set). */
    public record PayrollDoc(UUID id, String filename) {
    }

    /** Per-company payroll status for the monthly list. */
    public record PayrollRow(UUID companyId, List<PayrollDoc> documents, Instant lastSentAt, int sentCount) {
    }

    /** One payroll email send (notification log + resend). */
    public record PayrollEmailView(UUID id, String recipient, PayrollEmail.Status status, Instant sentAt,
                                   List<UUID> documentIds, String body) {
        public static PayrollEmailView from(PayrollEmail e) {
            return new PayrollEmailView(e.getId(), e.getRecipient(), e.getStatus(), e.getSentAt(),
                    e.getDocumentIds(), e.getBody());
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
        Map<UUID, List<PayrollEmail>> emailsByCompany = new LinkedHashMap<>();
        for (PayrollEmail e : emails.findByPeriodMonthOrderBySentAtDesc(month)) {
            emailsByCompany.computeIfAbsent(e.getCompanyId(), k -> new ArrayList<>()).add(e);
        }
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        ids.addAll(docsByCompany.keySet());
        ids.addAll(emailsByCompany.keySet());

        List<PayrollRow> out = new ArrayList<>();
        for (UUID companyId : ids) {
            List<PayrollDoc> docs = docsByCompany.getOrDefault(companyId, List.of());
            List<PayrollEmail> es = emailsByCompany.getOrDefault(companyId, List.of());
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

    /** Default editable email body for a company/period. */
    @Transactional(readOnly = true)
    public String composeBody(UUID companyId, LocalDate period) {
        return PayrollEmailBuilder.body(period, null);
    }

    /** Full send history for a company + period (newest first). */
    @Transactional(readOnly = true)
    public List<PayrollEmailView> history(UUID companyId, LocalDate period) {
        return emails.findByCompanyIdAndPeriodMonthOrderBySentAtDesc(companyId, period.withDayOfMonth(1))
                .stream().map(PayrollEmailView::from).toList();
    }

    /**
     * Record + dispatch one payroll email with the (possibly edited) body, attaching the company's
     * payroll documents for the period. Always persists a row: SENT on success, FAILED otherwise.
     */
    public PayrollEmailView send(UUID companyId, LocalDate period, String recipient, String body) {
        UUID tenantId = TenantContext.tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound"));
        UUID userId = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        LocalDate month = period.withDayOfMonth(1);

        List<Document> docs = documents.listByCompanyPeriodType(companyId, month, DocumentType.PAYROLL);
        List<UUID> docIds = docs.stream().map(Document::getId).toList();
        List<EmailSender.Attachment> attachments = new ArrayList<>();
        for (Document d : docs) {
            byte[] bytes = documents.getContent(d.getId()).bytes();
            attachments.add(new EmailSender.Attachment(d.getOriginalFilename(), d.getContentType(), bytes));
        }

        PayrollEmail.Status status = PayrollEmail.Status.SENT;
        String error = null;
        try {
            sender.send(recipient, PayrollEmailBuilder.subject(month), body, attachments);
        } catch (RuntimeException e) {
            status = PayrollEmail.Status.FAILED;
            error = e.getMessage();
            log.warn("Payroll email send failed for company {} period {}", companyId, month, e);
        }
        return PayrollEmailView.from(emails.save(new PayrollEmail(
                tenantId, companyId, month, docIds, recipient, body, status, error, userId)));
    }
}
