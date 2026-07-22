package ro.myfinance.common.email;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One record of an email send, shared by every module (tax, reports, payroll, document reminders). The
 * {@link EmailKind kind} discriminator keeps each module's history separate. Append-only: a resend is a
 * new row, preserving the full history each module's notification log shows. Written through
 * {@code access.application.EmailDispatchService}.
 *
 * <p>{@code relatedIds} carries the ids a send referenced — the tax declarations covered (TAX) or the
 * documents attached (PAYROLL); empty for REPORT / DOCUMENT_REMINDER.
 */
@Entity
@Table(name = "email_history")
public class EmailHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private EmailKind kind;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "period_month", nullable = false)
    private LocalDate periodMonth;

    private String recipient;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EmailStatus status;

    private String error;

    @Convert(converter = UuidListConverter.class)
    @Column(name = "related_ids", nullable = false)
    private List<UUID> relatedIds;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @Column(name = "sent_by")
    private UUID sentBy;

    protected EmailHistory() {
    }

    public EmailHistory(UUID tenantId, EmailKind kind, UUID companyId, LocalDate periodMonth,
                        List<UUID> relatedIds, String recipient, String body, EmailStatus status,
                        String error, UUID sentBy) {
        this.tenantId = tenantId;
        this.kind = kind;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.relatedIds = relatedIds == null ? List.of() : relatedIds;
        this.recipient = recipient;
        this.body = body;
        this.status = status;
        this.error = error;
        this.sentBy = sentBy;
    }

    /** Relay callback: the queued email was delivered. */
    public void markSent() {
        this.status = EmailStatus.SENT;
        this.error = null;
    }

    /** Relay callback: delivery permanently failed (outbox message reached the DLQ). */
    public void markFailed(String errorMessage) {
        this.status = EmailStatus.FAILED;
        this.error = errorMessage;
    }

    public UUID getId() { return id; }
    public EmailKind getKind() { return kind; }
    public UUID getCompanyId() { return companyId; }
    public LocalDate getPeriodMonth() { return periodMonth; }
    public String getRecipient() { return recipient; }
    public String getBody() { return body; }
    public EmailStatus getStatus() { return status; }
    public String getError() { return error; }
    public List<UUID> getRelatedIds() { return relatedIds; }
    public Instant getSentAt() { return sentAt; }
    public UUID getSentBy() { return sentBy; }
}
