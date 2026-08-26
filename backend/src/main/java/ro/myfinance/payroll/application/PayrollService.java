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
    private final ro.myfinance.company.application.CompanyDirectory companies;
    private final ro.myfinance.company.application.ExpectedDocuments expected;

    public PayrollService(DocumentService documents, EmailHistoryRepository history,
                          EmailDispatchService dispatch, EmailEnvelopeService envelopes,
                          ro.myfinance.notifications.application.NotificationService notifications,
                          ro.myfinance.company.application.CompanyDirectory companies,
                          ro.myfinance.company.application.ExpectedDocuments expected) {
        this.documents = documents;
        this.history = history;
        this.dispatch = dispatch;
        this.envelopes = envelopes;
        this.notifications = notifications;
        this.companies = companies;
        this.expected = expected;
    }

    /** One payroll document (for the list chips and the attach set). */
    public record PayrollDoc(UUID id, String filename) {
    }

    /** Per-company payroll status for the monthly list. */
    public record PayrollRow(UUID companyId, List<PayrollDoc> documents, Instant lastSentAt, int sentCount,
                             Instant lastWhatsappAt, int whatsappCount) {
    }

    /**
     * A row for the paginated Payroll list — self-contained (embeds the company identity) so the page can be
     * server-filtered ("needs attention") without a separate company query.
     */
    public record PayrollListRow(UUID companyId, String companyName, String cui, String locality,
                                 List<PayrollDoc> documents, Instant lastSentAt, int sentCount,
                                 Instant lastWhatsappAt, int whatsappCount) {
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
        Map<UUID, List<EmailHistory>> whatsappByCompany = new LinkedHashMap<>();
        for (EmailHistory e : history.findByChannelAndKindAndPeriodMonthOrderBySentAtDesc(
                ro.myfinance.common.email.MessageChannel.WHATSAPP, EmailKind.PAYROLL, month)) {
            whatsappByCompany.computeIfAbsent(e.getCompanyId(), k -> new ArrayList<>()).add(e);
        }
        java.util.Set<UUID> ids = new java.util.LinkedHashSet<>();
        ids.addAll(docsByCompany.keySet());
        ids.addAll(emailsByCompany.keySet());
        ids.addAll(whatsappByCompany.keySet());

        List<PayrollRow> out = new ArrayList<>();
        for (UUID companyId : ids) {
            List<PayrollDoc> docs = docsByCompany.getOrDefault(companyId, List.of());
            List<EmailHistory> es = emailsByCompany.getOrDefault(companyId, List.of());
            List<EmailHistory> ws = whatsappByCompany.getOrDefault(companyId, List.of());
            Instant last = es.isEmpty() ? null : es.get(0).getSentAt(); // sorted desc
            Instant lastWa = ws.isEmpty() ? null : ws.get(0).getSentAt();
            out.add(new PayrollRow(companyId, docs, last, es.size(), lastWa, ws.size()));
        }
        return out;
    }

    /**
     * A page of the monthly payroll list, fuzzy-searched by company (name or CUI). With {@code onlyMissing},
     * keeps only companies that OWE payroll ({@link ro.myfinance.company.application.ExpectedDocuments#owesPayroll})
     * yet uploaded nothing this month — the "needs attention" worklist. Small tenants ⇒ the filtered page is
     * computed in-memory over the tenant's companies (RLS-scoped).
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<PayrollListRow> listPage(
            LocalDate period, String q, boolean onlyMissing, int page, int size) {
        LocalDate month = period.withDayOfMonth(1);
        MonthPayroll ctx = monthContext(month);
        var pageable = org.springframework.data.domain.PageRequest.of(page, size);
        if (!onlyMissing) {
            return companies.search(q, pageable).map(c -> buildListRow(c, ctx));
        }
        List<PayrollListRow> missing = companies.findAllById(companies.searchIds(q)).stream()
                .filter(expected::owesPayroll)
                .filter(c -> ctx.docs().getOrDefault(c.getId(), List.of()).isEmpty())
                .map(c -> buildListRow(c, ctx))
                .sorted(java.util.Comparator.comparing(r -> r.companyName() == null ? "" : r.companyName().toLowerCase()))
                .toList();
        int from = Math.min(page * size, missing.size());
        int to = Math.min(from + size, missing.size());
        return new org.springframework.data.domain.PageImpl<>(missing.subList(from, to), pageable, missing.size());
    }

    /** The month's payroll docs / emails / WhatsApp grouped by company (loaded once, shared across rows). */
    private record MonthPayroll(Map<UUID, List<PayrollDoc>> docs, Map<UUID, List<EmailHistory>> emails,
                                Map<UUID, List<EmailHistory>> whatsapp) {
    }

    private MonthPayroll monthContext(LocalDate month) {
        Map<UUID, List<PayrollDoc>> docsByCompany = new LinkedHashMap<>();
        for (Document d : documents.listByPeriodAndType(month, DocumentType.PAYROLL)) {
            docsByCompany.computeIfAbsent(d.getCompanyId(), k -> new ArrayList<>())
                    .add(new PayrollDoc(d.getId(), d.getOriginalFilename()));
        }
        Map<UUID, List<EmailHistory>> emailsByCompany = new LinkedHashMap<>();
        for (EmailHistory e : history.findByKindAndPeriodMonthOrderBySentAtDesc(EmailKind.PAYROLL, month)) {
            emailsByCompany.computeIfAbsent(e.getCompanyId(), k -> new ArrayList<>()).add(e);
        }
        Map<UUID, List<EmailHistory>> whatsappByCompany = new LinkedHashMap<>();
        for (EmailHistory e : history.findByChannelAndKindAndPeriodMonthOrderBySentAtDesc(
                ro.myfinance.common.email.MessageChannel.WHATSAPP, EmailKind.PAYROLL, month)) {
            whatsappByCompany.computeIfAbsent(e.getCompanyId(), k -> new ArrayList<>()).add(e);
        }
        return new MonthPayroll(docsByCompany, emailsByCompany, whatsappByCompany);
    }

    private PayrollListRow buildListRow(ro.myfinance.company.domain.Company c, MonthPayroll ctx) {
        List<PayrollDoc> docs = ctx.docs().getOrDefault(c.getId(), List.of());
        List<EmailHistory> es = ctx.emails().getOrDefault(c.getId(), List.of());
        List<EmailHistory> ws = ctx.whatsapp().getOrDefault(c.getId(), List.of());
        Instant last = es.isEmpty() ? null : es.get(0).getSentAt(); // sorted desc
        Instant lastWa = ws.isEmpty() ? null : ws.get(0).getSentAt();
        return new PayrollListRow(c.getId(), c.getLegalName(), c.getCui(), c.getLocality(),
                docs, last, es.size(), lastWa, ws.size());
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
