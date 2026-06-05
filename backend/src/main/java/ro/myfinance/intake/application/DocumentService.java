package ro.myfinance.intake.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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

    public DocumentService(CompanyRepository companies, DocumentRepository documents,
                           DocumentStorage storage, DocumentClassifier classifier, AuditRecorder audit) {
        this.companies = companies;
        this.documents = documents;
        this.storage = storage;
        this.classifier = classifier;
        this.audit = audit;
    }

    public Document upload(UUID companyId, LocalDate periodMonth, String filename,
                           String contentType, byte[] bytes) {
        validate(contentType, bytes);
        UUID tenantId = currentTenant();
        companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));

        LocalDate period = periodMonth.withDayOfMonth(1);
        DocumentType type = classifier.classify(filename, contentType, bytes);
        String safeName = sanitize(filename);
        UUID id = UUID.randomUUID();
        String key = "%s/%s/%s/%s-%s".formatted(tenantId, companyId, period.format(MONTH), id, safeName);

        storage.store(key, bytes, contentType);
        UUID uploadedBy = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        Document doc = new Document(tenantId, companyId, period, type, DocumentSource.EMPLOYEE,
                DocumentStatus.UPLOADED, filename, contentType, bytes.length, key, uploadedBy);
        Document saved = documents.save(doc);
        audit.record("DOCUMENT_UPLOADED", "document", saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Document> list(UUID companyId, LocalDate periodMonth) {
        return periodMonth == null
                ? documents.findByCompanyIdOrderByUploadedAtDesc(companyId)
                : documents.findByCompanyIdAndPeriodMonthOrderByUploadedAtDesc(companyId, periodMonth.withDayOfMonth(1));
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

    private Document require(UUID id) {
        return documents.findById(id)
                .orElseThrow(() -> new NotFoundException("Document not found: " + id));
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
