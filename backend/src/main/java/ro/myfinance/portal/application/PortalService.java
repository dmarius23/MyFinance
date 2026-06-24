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

    private final CompanyRepository companies;
    private final DocumentService documents;
    private final NotificationService notifications;
    private final ReconciliationService reconciliation;
    private final ReportService reports;
    private final ReportPdfGenerator reportPdf;
    private final PayrollService payroll;

    public PortalService(CompanyRepository companies, DocumentService documents, NotificationService notifications,
                         ReconciliationService reconciliation, ReportService reports, ReportPdfGenerator reportPdf,
                         PayrollService payroll) {
        this.companies = companies;
        this.documents = documents;
        this.notifications = notifications;
        this.reconciliation = reconciliation;
        this.reports = reports;
        this.reportPdf = reportPdf;
        this.payroll = payroll;
    }

    public record CompanyInfo(UUID companyId, String name, String cui) {
    }

    public record MissingItem(LocalDate txnDate, String partnerName, BigDecimal amount, String description) {
    }

    public record DocView(UUID id, String filename, String type, String status, Instant uploadedAt) {
    }

    public record PayrollFile(UUID id, String filename) {
    }

    /** The rep's company (from the JWT). */
    @Transactional(readOnly = true)
    public CompanyInfo me() {
        UUID companyId = companyId();
        Company c = companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found"));
        return new CompanyInfo(companyId, c.getLegalName(), c.getCui());
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
                        tw.txn().getAmount().abs(), tw.txn().getDescription()))
                .toList();
    }

    /** Documents the rep has uploaded for the period. */
    @Transactional(readOnly = true)
    public List<DocView> myDocuments(LocalDate period) {
        return documents.list(companyId(), period.withDayOfMonth(1)).stream().map(this::view).toList();
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
                d.getStatus().name(), d.getUploadedAt());
    }

    private static UUID companyId() {
        return TenantContext.companyId()
                .orElseThrow(() -> new NotFoundException("No company bound to this representative"));
    }
}
