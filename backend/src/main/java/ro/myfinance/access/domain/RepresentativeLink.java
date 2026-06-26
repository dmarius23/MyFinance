package ro.myfinance.access.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * MOD-02 — links a representative user to a company they may access. A representative can be assigned to
 * several companies (within their tenant); each (user, company) pair is one row, unique together. Which
 * company a representative request operates on is resolved and validated against these links server-side.
 */
@Entity
@Table(name = "representative_link")
public class RepresentativeLink {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RepresentativeLink() {
    }

    public RepresentativeLink(UUID tenantId, UUID userId, UUID companyId) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.userId = userId;
        this.companyId = companyId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getTenantId() {
        return tenantId;
    }
}
