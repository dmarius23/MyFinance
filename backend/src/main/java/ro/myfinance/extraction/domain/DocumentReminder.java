package ro.myfinance.extraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A record of one missing-document reminder email sent to a client for a company/month. Append-only:
 * a resend is a new row, preserving the full history shown in the Statements notification log.
 */
@Entity
@Table(name = "document_reminder")
public class DocumentReminder {

    public enum Status { SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    private String recipient;

    @Column(nullable = false)
    private String body;

    @Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    private String error;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @Column(name = "sent_by")
    private UUID sentBy;

    protected DocumentReminder() {
    }

    public DocumentReminder(UUID tenantId, UUID companyId, LocalDate periodMonth, String recipient,
                            String body, Status status, String error, UUID sentBy) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.recipient = recipient;
        this.body = body;
        this.status = status;
        this.error = error;
        this.sentBy = sentBy;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public LocalDate getPeriodMonth() { return periodMonth; }
    public String getRecipient() { return recipient; }
    public String getBody() { return body; }
    public Status getStatus() { return status; }
    public String getError() { return error; }
    public Instant getSentAt() { return sentAt; }
    public UUID getSentBy() { return sentBy; }
}
