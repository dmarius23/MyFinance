package ro.myfinance.intake.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.application.CompanyDirectory;
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
            Set.of("application/pdf", "image/png", "image/jpeg", "image/webp",
                    // Structured bank-statement exports: CAMT.053 (XML) and MT940 (text).
                    "application/xml", "text/xml", "text/plain");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final CompanyDirectory companies;
    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final DocumentClassifier classifier;
    private final DocumentReclassifier ocr;
    private final DocumentValidator validator;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final ro.myfinance.tenant.application.TenantDirectory tenants;

    public DocumentService(CompanyDirectory companies, DocumentRepository documents,
                           DocumentStorage storage, DocumentClassifier classifier,
                           DocumentReclassifier ocr, DocumentValidator validator, AuditRecorder audit,
                           ApplicationEventPublisher events,
                           ro.myfinance.tenant.application.TenantDirectory tenants) {
        this.companies = companies;
        this.documents = documents;
        this.storage = storage;
        this.classifier = classifier;
        this.ocr = ocr;
        this.validator = validator;
        this.audit = audit;
        this.tenants = tenants;
        this.events = events;
    }

    /** Classify by text, then — if unclassified — try the OCR fallback for non-extractable PDFs. */
    private DocumentType classifyWithOcr(String filename, String contentType, byte[] bytes) {
        DocumentType type = classifier.classify(filename, contentType, bytes);
        return type == DocumentType.UNCLASSIFIED
                ? ocr.tryClassify(contentType, bytes).orElse(DocumentType.UNCLASSIFIED)
                : type;
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
        return upload(companyId, periodMonth, filename, contentType, bytes, forcedType, DocumentSource.EMPLOYEE);
    }

    /**
     * As {@link #upload(UUID, LocalDate, String, String, byte[], DocumentType)}, recording who supplied it.
     * Runs in its OWN transaction ({@code REQUIRES_NEW}) so that when a bulk sync imports many files, one
     * file's rejection (wrong-party payslip, quota, an unreadable PDF) rolls back only that file and cannot
     * poison the whole sync's transaction. (The shorter overloads self-invoke this one, so a single manual
     * upload keeps the caller's transaction.)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Document upload(UUID companyId, LocalDate periodMonth, String filename,
                           String contentType, byte[] bytes, DocumentType forcedType, DocumentSource source) {
        validate(contentType, bytes);
        UUID tenantId = currentTenant();
        var company = companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        LocalDate period = periodMonth.withDayOfMonth(1);
        enforceDocumentQuota(companyId, period);
        DocumentType type = forcedType != null ? forcedType : classifyWithOcr(filename, contentType, bytes);
        String safeName = sanitize(filename);
        UUID id = UUID.randomUUID();
        String key = "%s/%s/%s/%s-%s".formatted(tenantId, companyId, period.format(MONTH), id, safeName);

        storage.store(key, bytes, contentType);
        UUID uploadedBy = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        Document doc = new Document(tenantId, companyId, period, type, source,
                DocumentStatus.UPLOADED, filename, contentType, bytes.length, key, uploadedBy);
        // Common validation (duplicate / wrong company / wrong month) gates the Drive mirror — the file is
        // always stored here, but a blocked one is not written to Drive. Files ingested FROM Drive skip the
        // heavy per-type checks (ingestion already deduped/flagged them and they are never mirrored back);
        // we still hash them so a later manual upload of the same bytes is detected as a duplicate.
        if (source == DocumentSource.DRIVE) {
            doc.setContentSha256(ro.myfinance.common.hash.ContentHash.sha256(bytes));
        } else {
            applyValidation(doc, validator.validate(company, period, type, filename, contentType, bytes, null));
        }
        Document saved = documents.save(doc);
        audit.record("DOCUMENT_UPLOADED", "document", saved.getId());
        events.publishEvent(new DocumentUploadedEvent(saved.getId(), companyId, period, type, filename, bytes));
        return saved;
    }

    /**
     * Enforce the tenant plan limit on documents per company/period, if one is configured. A missing or
     * non-positive {@code maxDocumentsPerCompanyMonth} means no limit, so existing tenants are unaffected.
     */
    private void enforceDocumentQuota(UUID companyId, LocalDate period) {
        long limit = tenants.maxDocumentsPerCompanyMonth();
        if (limit >= 0 && documents.countByCompanyIdAndPeriodMonth(companyId, period) >= limit) {
            throw new ConflictException(
                    "Document limit reached for this company and period (max " + limit + "). "
                            + "Remove a document or upgrade the plan to add more.");
        }
    }

    @Transactional(readOnly = true)
    public List<Document> list(UUID companyId, LocalDate periodMonth) {
        return periodMonth == null
                ? documents.findByCompanyIdOrderByUploadedAtDesc(companyId)
                : documents.findByCompanyIdAndPeriodMonthOrderByUploadedAtDesc(companyId, periodMonth.withDayOfMonth(1));
    }

    @Transactional(readOnly = true)
    public java.util.List<CompanyDocSummary> summary(java.time.LocalDate periodMonth) {
        java.util.Map<java.util.UUID, DocAcc> acc = new java.util.HashMap<>();
        for (Document d : documents.findByPeriodMonth(periodMonth.withDayOfMonth(1))) {
            DocAcc a = acc.computeIfAbsent(d.getCompanyId(), k -> new DocAcc());
            a.fileCount++;
            if (d.getType() == ro.myfinance.intake.domain.DocumentType.BANK_STATEMENT) {
                a.bankStatements.add(d.getOriginalFilename());
            }
            if (d.getType() == ro.myfinance.intake.domain.DocumentType.INVOICE
                    || d.getType() == ro.myfinance.intake.domain.DocumentType.RECEIPT) {
                a.invoiceReceipts.add(d.getOriginalFilename());
            }
        }
        return acc.entrySet().stream()
                .map(e -> {
                    DocAcc a = e.getValue();
                    return new CompanyDocSummary(e.getKey(), !a.bankStatements.isEmpty(),
                            !a.invoiceReceipts.isEmpty(), a.fileCount, a.bankStatements.size(),
                            a.invoiceReceipts.size(), a.bankStatements, a.invoiceReceipts);
                })
                .toList();
    }

    /** Per-company accumulator for {@link #summary}: counts plus the filenames (for tooltips). */
    private static final class DocAcc {
        int fileCount;
        final List<String> bankStatements = new java.util.ArrayList<>();
        final List<String> invoiceReceipts = new java.util.ArrayList<>();
    }

    public record CompanyDocSummary(java.util.UUID companyId, boolean hasBankStatement,
                                    boolean hasInvoiceOrReceipt, int fileCount,
                                    int bankStatementCount, int invoiceReceiptCount,
                                    List<String> bankStatementFiles, List<String> invoiceReceiptFiles) {
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

    /** Own transaction ({@code REQUIRES_NEW}) so replacing a superseded document during a bulk sync can't
     *  poison the sync's transaction (and a manual delete is independent too). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(UUID id) {
        Document doc = require(id);
        storage.delete(doc.getStorageKey());
        String driveFileId = doc.getDriveFileId();
        documents.delete(doc);
        audit.record("DOCUMENT_DELETED", "document", id);
        events.publishEvent(new DocumentDeletedEvent(id, doc.getCompanyId(), doc.getType(), driveFileId));
    }

    /**
     * Re-fire the document pipeline for the stored documents of a type in a month — a backfill that
     * (re)builds reports/extractions from documents that are already imported (e.g. after ingest logic
     * changed), without re-importing. Publishes the same DocumentUploadedEvent a fresh upload would, so the
     * downstream listeners re-run in their own isolated transactions. Returns how many documents were fired.
     */
    public int reprocess(LocalDate periodMonth, DocumentType type) {
        LocalDate month = periodMonth.withDayOfMonth(1);
        int n = 0;
        for (Document d : documents.findByPeriodMonth(month)) {
            if (type != null && d.getType() != type) {
                continue;
            }
            events.publishEvent(new DocumentUploadedEvent(d.getId(), d.getCompanyId(), d.getPeriodMonth(),
                    d.getType(), d.getOriginalFilename(), storage.retrieve(d.getStorageKey())));
            n++;
        }
        return n;
    }

    /**
     * Move a document to a different period slot (e.g. a trial balance uploaded to the wrong month).
     * Updates {@code periodMonth} in the database and re-publishes the event so the report /
     * declaration index is rebuilt for the correct period. The old period's snapshot/declaration is
     * cleaned up by the listeners (they always purge by documentId before re-ingesting).
     */
    public Document movePeriod(UUID companyId, UUID id, LocalDate newPeriod) {
        Document doc = require(id);
        if (!doc.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Document not found: " + id);
        }
        LocalDate oldPeriod = doc.getPeriodMonth();
        LocalDate target = newPeriod.withDayOfMonth(1);
        doc.setPeriodMonth(target);
        audit.record("DOCUMENT_PERIOD_MOVED", "document", id,
                java.util.Map.of("periodMonth", String.valueOf(oldPeriod)),
                java.util.Map.of("periodMonth", target.toString()));
        byte[] bytes = storage.retrieve(doc.getStorageKey());
        revalidate(doc, bytes); // moving to the correct month can clear a WRONG_PERIOD Drive block
        events.publishEvent(new DocumentUploadedEvent(id, companyId, target, doc.getType(),
                doc.getOriginalFilename(), bytes));
        return doc;
    }

    /** Manually set a document's type and re-run extraction/matching for it (purge-then-reprocess). */
    public Document changeType(UUID companyId, UUID id, DocumentType newType) {
        Document doc = require(id);
        if (!doc.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Document not found: " + id);
        }
        DocumentType oldType = doc.getType();
        doc.setType(newType);
        audit.record("DOCUMENT_TYPE_CHANGED", "document", id,
                java.util.Map.of("type", String.valueOf(oldType)),
                java.util.Map.of("type", newType.name()));
        byte[] bytes = storage.retrieve(doc.getStorageKey());
        revalidate(doc, bytes); // the new type may change the duplicate/wrong-party/period verdict
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
            // Cheap text classify first; only spend an OCR call when the document is still UNCLASSIFIED
            // (an already-typed doc with unreadable text keeps its type — no need to re-OCR to classify).
            DocumentType cheap = classifier.classify(doc.getOriginalFilename(), doc.getContentType(), bytes);
            DocumentType newType = cheap != DocumentType.UNCLASSIFIED ? cheap
                    : doc.getType() != DocumentType.UNCLASSIFIED ? doc.getType()
                    : ocr.tryClassify(doc.getContentType(), bytes).orElse(DocumentType.UNCLASSIFIED);
            if (newType != doc.getType()) {
                doc.setType(newType);
                changed++;
            }
            // Re-publish every document so re-extraction picks up newer parser/OCR logic (e.g. vision
            // field recovery for non-extractable PDFs). process() upserts by document, so a single run is
            // idempotent.
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

    /** Persist the validator's verdict + Drive-routing metadata onto the document. */
    private static void applyValidation(Document doc, DocumentValidator.Result v) {
        doc.setContentSha256(v.contentSha256());
        doc.setDriveBlockReason(v.blockReason());
        doc.setDriveBlockDetail(v.blockDetail());
        doc.setDeclKind(v.declKind());
        doc.setDominantObligationCod(v.dominantObligationCod());
    }

    /** Re-run validation for an existing document (after a period move / type change) and re-persist it. */
    private void revalidate(Document doc, byte[] bytes) {
        var company = companies.findById(doc.getCompanyId()).orElse(null);
        if (company == null) {
            return;
        }
        applyValidation(doc, validator.validate(company, doc.getPeriodMonth(), doc.getType(),
                doc.getOriginalFilename(), doc.getContentType(), bytes, doc.getId()));
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
        if (!contentMatchesMagic(contentType.toLowerCase(), bytes)) {
            throw new IllegalArgumentException("File content does not match its declared type: " + contentType);
        }
    }

    /**
     * Defence against a spoofed content-type header: the leading magic bytes must match the declared
     * binary type. Text/XML bank-statement exports (CAMT.053, MT940) have no reliable binary signature,
     * so they are accepted on the content-type allowlist + size guard alone.
     */
    private static boolean contentMatchesMagic(String contentType, byte[] b) {
        return switch (contentType) {
            case "application/pdf" -> b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
            case "image/png" -> b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
            case "image/jpeg" -> b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
            case "image/webp" -> b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
            default -> true; // text/xml/plain — no signature to check
        };
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
