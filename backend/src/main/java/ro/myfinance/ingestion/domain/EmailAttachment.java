package ro.myfinance.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** Idempotency and result ledger for one Gmail attachment. */
@Entity
@Table(name = "email_attachment")
public class EmailAttachment {

    public enum Status { IMPORTED, DUPLICATE, NEEDS_REVIEW, REJECTED, FAILED }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "gmail_attachment_id", nullable = false)
    private String gmailAttachmentId;

    @Column(nullable = false)
    private String filename;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "content_sha256")
    private String contentSha256;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(nullable = false)
    private String status;

    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EmailAttachment() {
    }

    public EmailAttachment(UUID tenantId, UUID messageId, String gmailAttachmentId, String filename,
                           String contentType) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.messageId = messageId;
        this.gmailAttachmentId = gmailAttachmentId;
        this.filename = filename;
        this.contentType = contentType;
        this.status = Status.NEEDS_REVIEW.name();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getMessageId() { return messageId; }
    public String getGmailAttachmentId() { return gmailAttachmentId; }
    public String getFilename() { return filename; }
    public String getContentType() { return contentType; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String value) { this.contentSha256 = value; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID value) { this.documentId = value; }
    public Status getStatus() { return Status.valueOf(status); }
    public void setStatus(Status value) { this.status = value.name(); }
    public String getDetail() { return detail; }
    public void setDetail(String value) { this.detail = value; }
    public Instant getCreatedAt() { return createdAt; }
}
