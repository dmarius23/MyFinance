package ro.myfinance.intake.application;

import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.intake.domain.Document;
import ro.myfinance.intake.domain.DocumentType;

/**
 * Mirrors uploaded documents into the tenant's write-enabled Google Drive source connection (MOD-15), so
 * the firm keeps a browsable copy in its own Drive. Best-effort: any failure is logged and the document
 * stays fully usable from Supabase (the canonical store). Runs in the upload transaction (like the
 * extraction listeners); moving it to a post-commit worker job is a later optimisation. Cleans up the
 * mirror copy on delete. No-op when the tenant has no write-enabled Drive connection or the service
 * account is not configured.
 */
@Component
public class DocumentMirrorListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentMirrorListener.class);
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

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

    @EventListener
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
                    e.periodMonth().format(YM),
                    typeFolder(doc.getType()));
            String fileId = driveWriter.put(target.sharedDriveId(), target.rootFolderId(),
                    segments, doc.getOriginalFilename(), doc.getContentType(), e.bytes(), e.documentId());
            doc.setDriveFileId(fileId); // managed within this transaction → flushed on commit
        } catch (RuntimeException ex) {
            log.warn("Drive mirror failed for document {} — kept in Supabase", e.documentId(), ex);
        }
    }

    @EventListener
    public void onDeleted(DocumentDeletedEvent e) {
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

    private static String typeFolder(DocumentType type) {
        return switch (type) {
            case BANK_STATEMENT -> "Extrase de cont";
            case INVOICE -> "Facturi";
            case RECEIPT -> "Chitante";
            case PAYROLL -> "Salarizare";
            case DECLARATION -> "Declaratii";
            case TRIAL_BALANCE -> "Balante";
            case UNCLASSIFIED -> "Neclasificate";
        };
    }
}
