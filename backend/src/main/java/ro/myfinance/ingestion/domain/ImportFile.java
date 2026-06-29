package ro.myfinance.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * MOD-15 — one ledger row per file seen in a source connection. Gives idempotency (unique per
 * connection + provider file id), provenance (where it came from), and backs the review queue for
 * files that couldn't be mapped to a company/period automatically.
 */
@Entity
@Table(name = "import_file")
public class ImportFile {

    public enum Status { IMPORTED, NEEDS_REVIEW, REJECTED, DUPLICATE }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    @Column(name = "source_etag")
    private String sourceEtag;

    @Column(name = "content_sha256")
    private String contentSha256;

    private String filename;

    @Column(name = "source_path")
    private String sourcePath;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(nullable = false)
    private String status;

    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ImportFile() {
    }

    public ImportFile(UUID tenantId, UUID connectionId, String sourceRef, String sourceEtag,
                      String contentSha256, String filename, String sourcePath, UUID documentId,
                      Status status, String detail) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.connectionId = connectionId;
        this.sourceRef = sourceRef;
        this.sourceEtag = sourceEtag;
        this.contentSha256 = contentSha256;
        this.filename = filename;
        this.sourcePath = sourcePath;
        this.documentId = documentId;
        this.status = status.name();
        this.detail = detail;
    }

    public UUID getId() { return id; }
    public UUID getConnectionId() { return connectionId; }
    public String getSourceRef() { return sourceRef; }
    public String getSourceEtag() { return sourceEtag; }
    public void setSourceEtag(String v) { this.sourceEtag = v; }
    public String getContentSha256() { return contentSha256; }
    public String getFilename() { return filename; }
    public String getSourcePath() { return sourcePath; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public String getStatus() { return status; }
    public void setStatus(Status v) { this.status = v.name(); }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
    public Instant getCreatedAt() { return createdAt; }
}
