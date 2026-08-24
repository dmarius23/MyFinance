package ro.myfinance.intake.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** An uploaded document for a company + period. Type is system-assigned by the classifier. */
@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    /** Which backend holds the canonical bytes: SUPABASE (default) or DRIVE (Phase 2 primary). */
    @Column(name = "storage_backend", nullable = false)
    private String storageBackend = "SUPABASE";

    /** The Google Drive file id of the mirror copy (DRIVE_MIRROR), for refresh/cleanup. Null otherwise. */
    @Column(name = "drive_file_id")
    private String driveFileId;

    /** Invoice filing direction (INVOICE only), resolved from the e-Factura XML. Null for non-invoices. */
    @Column(name = "invoice_direction")
    @Enumerated(EnumType.STRING)
    private InvoiceDirection invoiceDirection;

    /** SHA-256 of the stored bytes — the key for cross-document duplicate detection. */
    @Column(name = "content_sha256")
    private String contentSha256;

    /** Why this document is NOT mirrored to Drive (duplicate / wrong company / wrong period). Null = eligible. */
    @Column(name = "drive_block_reason")
    @Enumerated(EnumType.STRING)
    private DriveBlockReason driveBlockReason;

    /** Human-readable explanation of {@link #driveBlockReason}, surfaced in the UI. */
    @Column(name = "drive_block_detail")
    private String driveBlockDetail;

    /** Declaration form (D100/D112/D300) captured at upload — routes DECLARATION mirrors. Null otherwise. */
    @Column(name = "decl_kind")
    private String declKind;

    /** Largest-amount obligation code (D100 sub-routing: 628 Chirii / 604 Dividende / 103 Profit …). */
    @Column(name = "dominant_obligation_cod")
    private String dominantObligationCod;

    @CreationTimestamp
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    protected Document() {
    }

    public Document(UUID tenantId, UUID companyId, LocalDate periodMonth, DocumentType type,
                    DocumentSource source, DocumentStatus status, String originalFilename,
                    String contentType, long sizeBytes, String storageKey, UUID uploadedBy) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.type = type;
        this.source = source;
        this.status = status;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.uploadedBy = uploadedBy;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public LocalDate getPeriodMonth() { return periodMonth; }
    public DocumentType getType() { return type; }
    public void setType(DocumentType type) { this.type = type; }
    public void setPeriodMonth(LocalDate periodMonth) { this.periodMonth = periodMonth; }
    public DocumentSource getSource() { return source; }
    public DocumentStatus getStatus() { return status; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getStorageKey() { return storageKey; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public String getStorageBackend() { return storageBackend; }
    public void setStorageBackend(String storageBackend) { this.storageBackend = storageBackend; }
    public String getDriveFileId() { return driveFileId; }
    public void setDriveFileId(String driveFileId) { this.driveFileId = driveFileId; }
    public InvoiceDirection getInvoiceDirection() { return invoiceDirection; }
    public void setInvoiceDirection(InvoiceDirection invoiceDirection) { this.invoiceDirection = invoiceDirection; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public DriveBlockReason getDriveBlockReason() { return driveBlockReason; }
    public void setDriveBlockReason(DriveBlockReason driveBlockReason) { this.driveBlockReason = driveBlockReason; }
    public String getDriveBlockDetail() { return driveBlockDetail; }
    public void setDriveBlockDetail(String driveBlockDetail) { this.driveBlockDetail = driveBlockDetail; }
    public String getDeclKind() { return declKind; }
    public void setDeclKind(String declKind) { this.declKind = declKind; }
    public String getDominantObligationCod() { return dominantObligationCod; }
    public void setDominantObligationCod(String dominantObligationCod) { this.dominantObligationCod = dominantObligationCod; }
}

