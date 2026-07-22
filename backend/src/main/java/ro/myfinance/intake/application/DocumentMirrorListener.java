package ro.myfinance.intake.application;

import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ro.myfinance.common.async.AsyncConfig;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DriveDocLayout;

/**
 * Mirrors uploaded documents into the tenant's write-enabled Google Drive source connection (MOD-15), so
 * the firm keeps a browsable copy in its own Drive. Best-effort: any failure is logged and the document
 * stays fully usable from Supabase (the canonical store). Runs after the upload commits and off the
 * request thread ({@code AFTER_COMMIT} + {@code @Async}), in its own transaction — the network round-trip
 * to Drive no longer blocks the HTTP response. Cleans up the mirror copy on delete. No-op when the tenant
 * has no write-enabled Drive connection or the service account is not configured.
 */
@Component
public class DocumentMirrorListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentMirrorListener.class);
    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM");

    private final DriveStorageTarget storageTarget;
    private final DriveDocumentWriter driveWriter;
    private final CompanyRepository companies;
    private final DocumentRepository documents;

    public DocumentMirrorListener(DriveStorageTarget storageTarget, DriveDocumentWriter driveWriter,
                                  CompanyRepository companies, DocumentRepository documents) {
        this.storageTarget = storageTarget;
        this.driveWriter = driveWriter;
        this.companies = companies;
        this.documents = documents;
    }

    @Async(AsyncConfig.DOCUMENT_PIPELINE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onUploaded(DocumentUploadedEvent e) {
        if (!driveWriter.isEnabled()) {
            return;
        }
        DriveStorageTarget.Target target = storageTarget.currentWriteTarget().orElse(null);
        if (target == null) {
            return; // no write-enabled Drive connection for this tenant
        }
        try {
            Document doc = documents.findById(e.documentId()).orElse(null);
            Company company = companies.findById(e.companyId()).orElse(null);
            if (doc == null || company == null) {
                return;
            }
            List<String> segments = List.of(
                    companyFolder(company),
                    e.periodMonth().format(YEAR),
                    e.periodMonth().format(MONTH),
                    DriveDocLayout.typeFolder(doc.getType()));
            String fileId = driveWriter.put(target.sharedDriveId(), target.rootFolderId(),
                    segments, doc.getOriginalFilename(), doc.getContentType(), e.bytes(), e.documentId());
            doc.setDriveFileId(fileId); // managed within this transaction → flushed on commit
        } catch (RuntimeException ex) {
            log.warn("Drive mirror failed for document {} — kept in Supabase", e.documentId(), ex);
        }
    }

    @Async(AsyncConfig.DOCUMENT_PIPELINE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeleted(DocumentDeletedEvent e) {
        // External Drive cleanup only — no DB write, so no transaction needed.
        if (e.driveFileId() == null || e.driveFileId().isBlank() || !driveWriter.isEnabled()) {
            return;
        }
        try {
            driveWriter.delete(e.driveFileId());
        } catch (RuntimeException ex) {
            log.warn("Drive mirror cleanup failed for document {}", e.documentId(), ex);
        }
    }

    /** Readable, filesystem-safe folder name for a company: legal name (or CUI fallback). */
    private static String companyFolder(Company company) {
        String name = company.getLegalName();
        if (name == null || name.isBlank()) {
            name = company.getCui();
        }
        String safe = (name == null ? "company" : name).replaceAll("[/\\\\]", "-").trim();
        return safe.isEmpty() ? "company" : safe;
    }
}
