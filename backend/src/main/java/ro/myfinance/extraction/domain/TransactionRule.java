package ro.myfinance.extraction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** A learned document-requirement rule, created from an accountant's override. */
@Entity
@Table(name = "transaction_rule")
public class TransactionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "match_iban")
    private String matchIban;

    @Column(name = "match_desc_norm", nullable = false)
    private String matchDescNorm;

    @Column(name = "requires_document", nullable = false)
    private boolean requiresDocument;

    @Column(name = "created_by")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TransactionRule() {
    }

    public TransactionRule(UUID tenantId, UUID companyId, String matchIban, String matchDescNorm,
                           boolean requiresDocument, UUID createdBy) {
        this.tenantId = tenantId;
        this.companyId = companyId;
        this.matchIban = matchIban;
        this.matchDescNorm = matchDescNorm;
        this.requiresDocument = requiresDocument;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public String getMatchIban() { return matchIban; }
    public String getMatchDescNorm() { return matchDescNorm; }
    public boolean isRequiresDocument() { return requiresDocument; }
    public void setRequiresDocument(boolean requiresDocument) { this.requiresDocument = requiresDocument; }
}
