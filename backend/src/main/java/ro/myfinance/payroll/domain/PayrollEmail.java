package ro.myfinance.payroll.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ro.myfinance.taxpayments.domain.UuidListConverter;

/**
 * A record of one payroll email send for a company/month, with the document ids it attached. Append-only:
 * a resend is a new row, preserving the history shown in the payroll notification log.
 */
@Entity
@Table(name = "payroll_email")
public class PayrollEmail {

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

    @Convert(converter = UuidListConverter.class)
    @Column(name = "document_ids", nullable = false)
    private List<UUID> documentIds;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @Column(name = "sent_by")
    private UUID sentBy;

    protected PayrollEmail() {
    }

    public PayrollEmail(UUID tenantId, UUID companyId, LocalDate periodMonth, List<UUID> documentIds,
                        String recipient, String body, Status status, String error, UUID sentBy) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.documentIds = documentIds;
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
    public List<UUID> getDocumentIds() { return documentIds; }
    public Instant getSentAt() { return sentAt; }
    public UUID getSentBy() { return sentBy; }
}
