package ro.myfinance.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** Idempotency and provenance ledger for a Gmail message. */
@Entity
@Table(name = "email_message")
public class EmailMessage {

    public enum Status { RECEIVED, PROCESSED, NEEDS_REVIEW, FAILED }

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "gmail_message_id", nullable = false)
    private String gmailMessageId;

    @Column(name = "thread_id")
    private String threadId;

    @Column(name = "sender_email", nullable = false)
    private String senderEmail;

    private String subject;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(nullable = false)
    private String status;

    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EmailMessage() {
    }

    public EmailMessage(UUID tenantId, UUID connectionId, String gmailMessageId, String threadId,
                        String senderEmail, String subject, Instant receivedAt) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.connectionId = connectionId;
        this.gmailMessageId = gmailMessageId;
        this.threadId = threadId;
        this.senderEmail = senderEmail;
        this.subject = subject;
        this.receivedAt = receivedAt;
        this.status = Status.RECEIVED.name();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getConnectionId() { return connectionId; }
    public String getGmailMessageId() { return gmailMessageId; }
    public String getThreadId() { return threadId; }
    public String getSenderEmail() { return senderEmail; }
    public String getSubject() { return subject; }
    public Instant getReceivedAt() { return receivedAt; }
    public Status getStatus() { return Status.valueOf(status); }
    public void setStatus(Status value) { this.status = value.name(); }
    public String getDetail() { return detail; }
    public void setDetail(String value) { this.detail = value; }
    public Instant getCreatedAt() { return createdAt; }
}
