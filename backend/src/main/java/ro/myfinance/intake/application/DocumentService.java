package ro.myfinance.intake.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentSource;
import ro.myfinance.intake.domain.DocumentStatus;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Document intake: classify, store, and manage uploaded documents. Tenant-scoped via RLS; type is
 * system-assigned. Staff-facing (authorization enforced at the controller).
 */
@Service
@Transactional
public class DocumentService {

    static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;
    static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("application/pdf", "image/png", "image/jpeg", "image/webp");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final CompanyRepository companies;
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final DocumentClassifier classifier;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;

    public DocumentService(CompanyRepository companies, DocumentRepository documents,
                           DocumentStorage storage, DocumentClassifier classifier, AuditRecorder audit,
                           ApplicationEventPublisher events) {
        this.companies = companies;
        this.documents = documents;
        this.storage = storage;
        this.classifier = classifier;
        this.audit = audit;
        this.events = events;
    }

    public Document upload(UUID companyId, LocalDate periodMonth, String filename,
                           String contentType, byte[] bytes) {
        return upload(companyId, periodMonth, filename, contentType, bytes, null);
    }

    /**
     * Upload a document. When {@code forcedType} is non-null the classifier is bypassed and the document
     * is stored as that type — used by module-specific intakes (e.g. payroll uploads as PAYROLL) where
     * the type is known from context and content-based classification would be unreliable.
     */
    public Document upload(UUID companyId, LocalDate periodMonth, String filename,
                           String contentType, byte[] bytes, DocumentType forcedType) {
        validate(contentType, bytes);
        UUID tenantId = currentTenant();
        var company = companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        if (forcedType == DocumentType.PAYROLL || forcedType == DocumentType.TRIAL_BALANCE) {
            verifyBelongsToCompany(company.getLegalName(), company.getCui(), contentType, bytes);
        }

        LocalDate period = periodMonth.withDayOfMonth(1);
        DocumentType type = forcedType != null ? forcedType : classifier.classify(filename, contentType, bytes);
        String safeName = sanitize(filename);
        UUID id = UUID.randomUUID();
        String key = "%s/%s/%s/%s-%s".formatted(tenantId, companyId, period.format(MONTH), id, safeName);

        storage.store(key, bytes, contentType);
        UUID uploadedBy = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        Document doc = new Document(tenantId, companyId, period, type, DocumentSource.EMPLOYEE,
                DocumentStatus.UPLOADED, filename, contentType, bytes.length, key, uploadedBy);
        Document saved = documents.save(doc);
        audit.record("DOCUMENT_UPLOADED", "document", saved.getId());
        events.publishEvent(new DocumentUploadedEvent(saved.getId(), companyId, period, type, filename, bytes));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Document> list(UUID companyId, LocalDate periodMonth) {
        return periodMonth == null
                ? documents.findByCompanyIdOrderByUploadedAtDesc(companyId)
                : documents.findByCompanyIdAndPeriodMonthOrderByUploadedAtDesc(companyId, periodMonth.withDayOfMonth(1));
    }

    @Transactional(readOnly = true)
    public java.util.List<CompanyDocSummary> summary(java.time.LocalDate periodMonth) {
        java.util.Map<java.util.UUID, int[]> acc = new java.util.HashMap<>();
        // int[]{fileCount, bankStatementCount, invoiceReceiptCount}
        for (Document d : documents.findByPeriodMonth(periodMonth.withDayOfMonth(1))) {
            int[] a = acc.computeIfAbsent(d.getCompanyId(), k -> new int[3]);
            a[0]++;
            if (d.getType() == ro.myfinance.intake.domain.DocumentType.BANK_STATEMENT) a[1]++;
            if (d.getType() == ro.myfinance.intake.domain.DocumentType.INVOICE
                    || d.getType() == ro.myfinance.intake.domain.DocumentType.RECEIPT) a[2]++;
        }
        return acc.entrySet().stream()
                .map(e -> new CompanyDocSummary(e.getKey(), e.getValue()[1] > 0, e.getValue()[2] > 0,
                        e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .toList();
    }

    public record CompanyDocSummary(java.util.UUID companyId, boolean hasBankStatement,
                                    boolean hasInvoiceOrReceipt, int fileCount,
                                    int bankStatementCount, int invoiceReceiptCount) {
    }

    /** All documents of a given type for a company + period (e.g. payroll files). */
    @Transactional(readOnly = true)
    public List<Document> listByCompanyPeriodType(UUID companyId, LocalDate periodMonth, DocumentType type) {
        return documents.findByCompanyIdAndPeriodMonthOrderByUploadedAtDesc(companyId, periodMonth.withDayOfMonth(1))
                .stream().filter(d -> d.getType() == type).toList();
    }

    /** All documents of a given type across all companies for a period. */
    @Transactional(readOnly = true)
    public List<Document> listByPeriodAndType(LocalDate periodMonth, DocumentType type) {
        return documents.findByPeriodMonth(periodMonth.withDayOfMonth(1))
                .stream().filter(d -> d.getType() == type).toList();
    }

    @Transactional(readOnly = true)
    public DocumentContent getContent(UUID id) {
        Document doc = require(id);
        return new DocumentContent(doc, storage.retrieve(doc.getStorageKey()));
    }

    public void delete(UUID id) {
        Document doc = require(id);
        storage.delete(doc.getStorageKey());
        documents.delete(doc);
        audit.record("DOCUMENT_DELETED", "document", id);
    }

    /** Manually set a document's type and re-run extraction/matching for it (purge-then-reprocess). */
    public Document changeType(UUID companyId, UUID id, DocumentType newType) {
        Document doc = require(id);
        if (!doc.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Document not found: " + id);
        }
        doc.setType(newType);
        audit.record("DOCUMENT_TYPE_CHANGED", "document", id);
        byte[] bytes = storage.retrieve(doc.getStorageKey());
        events.publishEvent(new DocumentUploadedEvent(id, companyId, doc.getPeriodMonth(), newType,
                doc.getOriginalFilename(), bytes));
        return doc;
    }

    /**
     * Re-run the classifier on every document in the period and re-extract all of them. Reclassifies
     * where the type changed, but re-publishes for every document regardless so re-extraction picks up
     * the latest parser logic (e.g. newly-extracted supplier names) without needing a re-upload.
     * Returns the number of documents whose type changed.
     */
    public int reclassify(UUID companyId, LocalDate periodMonth) {
        int changed = 0;
        for (Document doc : list(companyId, periodMonth)) {
            byte[] bytes = storage.retrieve(doc.getStorageKey());
            DocumentType newType = classifier.classify(doc.getOriginalFilename(), doc.getContentType(), bytes);
            if (newType != doc.getType()) {
                doc.setType(newType);
                changed++;
            }
            events.publishEvent(new DocumentUploadedEvent(doc.getId(), companyId, doc.getPeriodMonth(),
                    doc.getType(), doc.getOriginalFilename(), bytes));
        }
        audit.record("DOCUMENTS_RECLASSIFIED", "company", companyId);
        return changed;
    }

    private Document require(UUID id) {
        return documents.findById(id)
                .orElseThrow(() -> new NotFoundException("Document not found: " + id));
    }

    /**
     * Payroll guard: a payroll document must belong to the company it's uploaded for. We read the PDF
     * text and require the company's fiscal code (CUI, digits only) to appear in it; otherwise the file
     * is rejected so one company's payroll can't be attached to another. Conservative: if the file isn't
     * a readable text PDF (e.g. a scan) or the company has no CUI, we can't verify and allow the upload.
     */
    private void verifyBelongsToCompany(String companyName, String companyCui, String contentType, byte[] bytes) {
        if (contentType == null || !contentType.toLowerCase().contains("pdf")) {
            return;
        }
        String text;
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
        } catch (java.io.IOException | RuntimeException e) {
            return; // unreadable / not a real PDF → can't verify, allow
        }
        // Match on CUI or company name (payslips print only the name). null = can't verify → allow.
        if (Boolean.FALSE.equals(CompanyMatcher.present(text, companyCui, companyName))) {
            throw new ro.myfinance.common.web.ConflictException(
                    "Documentul nu pare emis pentru această firmă (" + companyName + " / CUI "
                            + companyCui + " negăsit în document).");
        }
    }

    private void validate(String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("Empty file");
        }
        if (bytes.length > MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("File exceeds 20 MB limit");
        }
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException("Unsupported file type: " + contentType);
        }
    }

    private String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "file";
        }
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private UUID currentTenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to request"));
    }

    public record DocumentContent(Document document, byte[] bytes) {
    }
}
