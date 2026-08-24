package ro.myfinance.intake.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ro.myfinance.common.async.AsyncConfig;
import ro.myfinance.company.application.CompanyDirectory;
import ro.myfinance.company.domain.Company;
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentSource;
import ro.myfinance.intake.domain.DrivePurpose;
import ro.myfinance.intake.domain.DriveFilingRouter;

/**
 * Files uploaded documents into the tenant's write-enabled Google Drive connection(s) using the firm's real
 * structure (Drive Filing v2 — see {@code docs/MyFinance-drive-filing-v2-design-v1.md}), so the firm keeps a
 * browsable copy in its own Drive. Best-effort: any failure is logged and the document stays fully usable
 * from Supabase (the canonical store). Runs after the upload commits and off the request thread
 * ({@code AFTER_COMMIT} + {@code @Async}), in its own transaction. Cleans up the mirror copy on delete.
 *
 * <p>Skips documents synced FROM Drive (the accounting drive is bidirectional) and any document a
 * validation check blocked (duplicate / wrong company / wrong month) — those stay in Supabase, unmirrored.
 * The declaration routing metadata (form + dominant obligation) is captured at upload by
 * {@code DocumentValidator}, so declarations file here directly; only a declaration whose kind could not be
 * parsed is left for a later pass. No-op when the tenant has no write-enabled Drive connection for the
 * resolved purpose or the service account is not configured.
 */
@Component
public class DocumentMirrorListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentMirrorListener.class);

    private final DriveStorageTarget storageTarget;
    private final DriveDocumentWriter driveWriter;
    private final CompanyDirectory companies;
    private final DocumentRepository documents;

    public DocumentMirrorListener(DriveStorageTarget storageTarget, DriveDocumentWriter driveWriter,
                                  CompanyDirectory companies, DocumentRepository documents) {
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
        try {
            Document doc = documents.findById(e.documentId()).orElse(null);
            Company company = companies.findById(e.companyId()).orElse(null);
            if (doc == null || company == null) {
                return;
            }
            if (doc.getSource() == DocumentSource.DRIVE) {
                return; // synced FROM the drive — don't mirror it back (the accounting drive is bidirectional)
            }
            if (doc.getDriveBlockReason() != null) {
                // Blocked by validation (duplicate / wrong company / wrong month): kept in Supabase, not filed.
                log.info("Drive mirror skipped for document {} — {} ({})", e.documentId(),
                        doc.getDriveBlockReason(), doc.getDriveBlockDetail());
                return;
            }
            // Resolve the target first (it carries the connection's rootIsYearFolder), then route with it.
            DrivePurpose purpose = DriveFilingRouter.purposeFor(doc.getType()).orElse(null);
            if (purpose == null) {
                return; // receipts / unclassified — never mirrored
            }
            DriveStorageTarget.Target target = storageTarget.writeTargetFor(purpose).orElse(null);
            if (target == null) {
                return; // no write-enabled Drive connection for this purpose
            }
            DriveFilingRouter.FilingRequest request = new DriveFilingRouter.FilingRequest(
                    doc.getType(), doc.getPeriodMonth(), companyFolder(company),
                    doc.getDeclKind(), doc.getDominantObligationCod(), doc.getInvoiceDirection(),
                    target.rootIsYearFolder());
            DriveFilingRouter.Filing filing = DriveFilingRouter.route(request).orElse(null);
            if (filing == null) {
                return; // a declaration/invoice whose metadata (obligation/direction) is resolved later
            }
            String fileId = driveWriter.put(target.sharedDriveId(), target.rootFolderId(),
                    filing.segments(), doc.getOriginalFilename(), doc.getContentType(), e.bytes(), e.documentId());
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
