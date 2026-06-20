package ro.myfinance.taxpayments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A record of one state-payment email send. Append-only: a resend is a new row, preserving history. */
@Entity
@Table(name = "tax_email")
public class TaxEmail {

    public enum Status { SENT, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "period_month", nullable = false)
    private java.time.LocalDate periodMonth;

    private String recipient;

    @Column(nullable = false)
    private String body;

    @Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    private String error;

    @Convert(converter = UuidListConverter.class)
    @Column(name = "declaration_ids", nullable = false)
    private List<UUID> declarationIds;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @Column(name = "sent_by")
    private UUID sentBy;

    protected TaxEmail() {
    }

    public TaxEmail(UUID tenantId, UUID companyId, java.time.LocalDate periodMonth, List<UUID> declarationIds,
                    String recipient, String body, Status status, String error, UUID sentBy) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.periodMonth = periodMonth;
        this.declarationIds = declarationIds;
        this.recipient = recipient;
        this.body = body;
        this.status = status;
        this.error = error;
        this.sentBy = sentBy;
    }

    public UUID getId() { return id; }
    public UUID getCompanyId() { return companyId; }
    public java.time.LocalDate getPeriodMonth() { return periodMonth; }
    public String getRecipient() { return recipient; }
    public String getBody() { return body; }
    public Status getStatus() { return status; }
    public String getError() { return error; }
    public List<UUID> getDeclarationIds() { return declarationIds; }
    public Instant getSentAt() { return sentAt; }
    public UUID getSentBy() { return sentBy; }
}
