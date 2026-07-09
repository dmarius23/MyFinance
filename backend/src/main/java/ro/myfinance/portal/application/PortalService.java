package ro.myfinance.portal.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.extraction.application.ReconciliationService;
import ro.myfinance.intake.application.DocumentService;
import ro.myfinance.intake.application.DocumentService.DocumentContent;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentSource;
import ro.myfinance.notifications.application.NotificationService;
import ro.myfinance.payroll.application.PayrollService;
import ro.myfinance.reports.application.ReportPdfGenerator;
import ro.myfinance.reports.application.ReportService;
import ro.myfinance.reports.domain.ReportData;

/**
 * Backend-for-frontend for the representative PWA. Every operation is scoped to the rep's single company
 * (taken from the JWT, never the client), so a rep can only ever see/act on their own company's data.
 */
@Service
@Transactional
public class PortalService {

    /** Header the PWA sends to pick which of the rep's companies a request operates on. */
    public static final String COMPANY_HEADER = "X-Company-Id";

    private final CompanyRepository companies;
    private final DocumentService documents;
    private final NotificationService notifications;
    private final ReconciliationService reconciliation;
    private final ReportService reports;
    private final ReportPdfGenerator reportPdf;
    private final PayrollService payroll;
    private final ro.myfinance.taxpayments.application.TaxPaymentService taxes;
    private final ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository repLinks;
    private final ro.myfinance.access.adapter.persistence.AppUserRepository users;
    private final jakarta.servlet.http.HttpServletRequest request;

    public PortalService(CompanyRepository companies, DocumentService documents, NotificationService notifications,
                         ReconciliationService reconciliation, ReportService reports, ReportPdfGenerator reportPdf,
                         PayrollService payroll, ro.myfinance.taxpayments.application.TaxPaymentService taxes,
                         ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository repLinks,
                         ro.myfinance.access.adapter.persistence.AppUserRepository users,
                         jakarta.servlet.http.HttpServletRequest request) {
        this.companies = companies;
        this.documents = documents;
        this.notifications = notifications;
        this.reconciliation = reconciliation;
        this.reports = reports;
        this.reportPdf = reportPdf;
        this.payroll = payroll;
        this.taxes = taxes;
        this.repLinks = repLinks;
        this.users = users;
        this.request = request;
    }

    public record CompanyInfo(UUID companyId, String name, String cui, List<CompanyOption> companies) {
    }

    /** One company the representative can switch to in the PWA. */
    public record CompanyOption(UUID companyId, String name, String cui) {
    }

    public record MissingItem(LocalDate txnDate, String partnerName, BigDecimal amount, String description,
                              boolean credit) {
    }

    /**
     * A document in the rep's view. For invoices/receipts the reconciliation-derived fields are filled:
     * {@code issuer} (who issued it), {@code paymentStatus} (PAID/PARTIAL/UNPAID), {@code duplicate}, and
     * {@code outsidePeriod} (its date falls outside the selected month). Null/false for other types.
     */
    public record DocView(UUID id, String filename, String type, String status, Instant uploadedAt,
                          String issuer, String paymentStatus, boolean duplicate, boolean outsidePeriod,
                          String issuerCif, java.math.BigDecimal total, java.time.LocalDate invoiceDate) {
    }

    public record PayrollFile(UUID id, String filename) {
    }

    /** What the company owes the state for the period: per-account lines + total. */
    public record PaymentView(BigDecimal total, List<PaymentLineView> lines, List<UnconfiguredView> unconfigured) {
    }

    public record PaymentLineView(BigDecimal amount, String explanation, String iban, LocalDate scadenta,
                                  List<String> categories) {
    }

    public record UnconfiguredView(String category, BigDecimal amount) {
    }

    /** The rep's active company plus every company they can switch to. */
    @Transactional(readOnly = true)
    public CompanyInfo me() {
        UUID companyId = companyId();
        Company c = companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found"));
        List<CompanyOption> options = linkedCompanyIds().stream()
                .map(companies::findById).flatMap(java.util.Optional::stream)
                .map(co -> new CompanyOption(co.getId(), co.getLegalName(), co.getCui()))
                .sorted(java.util.Comparator.comparing(o -> o.name() == null ? "" : o.name(),
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new CompanyInfo(companyId, c.getLegalName(), c.getCui(), options);
    }

    /** Store a document the rep uploaded (source=REP) and notify the firm. */
    public DocView upload(String filename, String contentType, byte[] bytes, LocalDate period) {
        UUID companyId = companyId();
        LocalDate month = (period != null ? period : LocalDate.now()).withDayOfMonth(1);
        Document doc = documents.upload(companyId, month, filename, contentType, bytes, null, DocumentSource.REP);
        notifications.documentUploadedByRep(companyId, doc.getId(), filename);
        return view(doc);
    }

    /** What the accountant still needs: transactions that require a document and have none matched. */
    @Transactional(readOnly = true)
    public List<MissingItem> missing(LocalDate period) {
        return reconciliation.transactionsWithMatches(companyId(), period.withDayOfMonth(1)).stream()
                .filter(tw -> tw.txn().isRequiresDocument() && tw.invoices().isEmpty())
                .map(tw -> new MissingItem(tw.txn().getTxnDate(), tw.txn().getPartnerName(),
                        tw.txn().getAmount().abs(), tw.txn().getDescription(),
                        tw.txn().getAmount().signum() > 0)) // credit = money in (income), debit = supplier
                .toList();
    }

    /** Documents the rep themselves uploaded for the period (source=REP). */
    @Transactional(readOnly = true)
    public List<DocView> myDocuments(LocalDate period) {
        return documents.list(companyId(), period.withDayOfMonth(1)).stream()
                .filter(d -> d.getSource() == DocumentSource.REP)
                .map(this::view).toList();
    }

    /**
     * Bank statement, invoices and receipts for the period — uploaded by the rep OR the accountant. Each
     * invoice/receipt is annotated with its issuer, payment status, and duplicate / outside-period flags.
     */
    @Transactional(readOnly = true)
    public List<DocView> companyDocuments(LocalDate period) {
        UUID companyId = companyId();
        LocalDate month = period.withDayOfMonth(1);
        java.util.Set<ro.myfinance.intake.domain.DocumentType> types = java.util.EnumSet.of(
                ro.myfinance.intake.domain.DocumentType.BANK_STATEMENT,
                ro.myfinance.intake.domain.DocumentType.INVOICE,
                ro.myfinance.intake.domain.DocumentType.RECEIPT,
                ro.myfinance.intake.domain.DocumentType.UNCLASSIFIED);
        java.util.Map<UUID, ReconciliationService.DocumentStatus> status =
                reconciliation.documentStatuses(companyId, month).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                ReconciliationService.DocumentStatus::documentId, s -> s, (a, b) -> a));
        return documents.list(companyId, month).stream()
                .filter(d -> types.contains(d.getType()))
                .map(d -> {
                    boolean isInvoice = d.getType() == ro.myfinance.intake.domain.DocumentType.INVOICE
                            || d.getType() == ro.myfinance.intake.domain.DocumentType.RECEIPT;
                    ReconciliationService.DocumentStatus s = isInvoice ? status.get(d.getId()) : null;
                    return new DocView(d.getId(), d.getOriginalFilename(), d.getType().name(),
                            d.getStatus().name(), d.getUploadedAt(),
                            s == null ? null : s.issuer(),
                            s == null ? null : s.paymentStatus(),
                            s != null && s.duplicate(),
                            s != null && "date_outside_period".equals(s.dateReason()),
                            s == null ? null : s.issuerCif(),
                            s == null ? null : s.total(),
                            s == null ? null : s.invoiceDate());
                })
                .toList();
    }

    /** The uploaded balance sheet (trial balance) for the period — for download alongside the report. */
    @Transactional(readOnly = true)
    public List<DocView> balanceSheet(LocalDate period) {
        return documents.listByCompanyPeriodType(companyId(), period.withDayOfMonth(1),
                        ro.myfinance.intake.domain.DocumentType.TRIAL_BALANCE).stream()
                .map(this::view).toList();
    }

    /** What the company owes the state this period: amounts grouped per treasury IBAN, with the total. */
    @Transactional(readOnly = true)
    public PaymentView payments(LocalDate period) {
        var s = taxes.summary(companyId(), period.withDayOfMonth(1));
        List<PaymentLineView> lines = s.paymentLines().stream()
                .map(l -> new PaymentLineView(l.amount(), l.explanation(), l.iban(), l.scadenta(),
                        l.categories().stream().map(Enum::name).toList()))
                .toList();
        List<UnconfiguredView> unconfigured = s.unconfigured().stream()
                .map(u -> new UnconfiguredView(u.category().name(), u.amount()))
                .toList();
        return new PaymentView(s.totalToPay(), lines, unconfigured);
    }

    /** The computed monthly report for the rep's company, or null if none is available yet. */
    @Transactional(readOnly = true)
    public ReportData report(LocalDate period) {
        try {
            return reports.report(companyId(), period.withDayOfMonth(1));
        } catch (NotFoundException e) {
            return null;
        }
    }

    /** Revenue/expenses/profit trend across the last months for the rep's company (charts). */
    @Transactional(readOnly = true)
    public List<ReportService.TrendPoint> trend(LocalDate period, int months) {
        return reports.trend(companyId(), period.withDayOfMonth(1), months);
    }

    @Transactional(readOnly = true)
    public byte[] reportPdf(LocalDate period) {
        ReportData r = reports.report(companyId(), period.withDayOfMonth(1));
        return reportPdf.generate(r);
    }

    @Transactional(readOnly = true)
    public List<PayrollFile> payroll(LocalDate period) {
        return payroll.documentsFor(companyId(), period.withDayOfMonth(1)).stream()
                .map(d -> new PayrollFile(d.id(), d.filename())).toList();
    }

    /** The rep's in-app notifications (document requests, new reports). Scoped to the current user. */
    @Transactional(readOnly = true)
    public List<NotificationService.NotificationView> notifications() {
        return notifications.list();
    }

    @Transactional(readOnly = true)
    public long unreadNotifications() {
        return notifications.unreadCount();
    }

    public void markNotificationRead(UUID id) {
        notifications.markRead(id);
    }

    /** Download a document — only if it belongs to the rep's own company. */
    @Transactional(readOnly = true)
    public DocumentContent download(UUID documentId) {
        DocumentContent content = documents.getContent(documentId);
        if (!content.document().getCompanyId().equals(companyId())) {
            throw new NotFoundException("Document not found");
        }
        return content;
    }

    private DocView view(Document d) {
        return new DocView(d.getId(), d.getOriginalFilename(), d.getType().name(),
                d.getStatus().name(), d.getUploadedAt(), null, null, false, false, null, null, null);
    }

    /**
     * The company this request operates on, resolved and validated server-side. A representative may be
     * assigned to several companies; the PWA names the active one via the {@code X-Company-Id} header.
     * The chosen company MUST be one the current user is actually linked to — a requested id that isn't
     * among their links is rejected (never trust the client). When no header is sent we fall back to the
     * JWT company (if still linked) and otherwise to the first linked company.
     */
    private UUID companyId() {
        List<UUID> linked = linkedCompanyIds();
        if (linked.isEmpty()) {
            throw new NotFoundException("No company bound to this representative");
        }
        UUID requested = parseUuid(request.getHeader(COMPANY_HEADER));
        if (requested != null) {
            if (!linked.contains(requested)) {
                throw new NotFoundException("Company not available to this representative");
            }
            return requested;
        }
        UUID fromJwt = TenantContext.companyId().orElse(null);
        return (fromJwt != null && linked.contains(fromJwt)) ? fromJwt : linked.get(0);
    }

    /** Every company the current representative is assigned to. Also fails closed if deactivated. */
    private List<UUID> linkedCompanyIds() {
        UUID userId = TenantContext.current().map(TenantContext.Identity::userId)
                .orElseThrow(() -> new NotFoundException("No user bound to this request"));
        users.findById(userId).ifPresent(u -> {
            if (u.getStatus() == ro.myfinance.access.domain.UserStatus.INACTIVE) {
                throw new NotFoundException("This representative account is deactivated");
            }
        });
        return repLinks.findByUserId(userId).stream()
                .map(ro.myfinance.access.domain.RepresentativeLink::getCompanyId).toList();
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
