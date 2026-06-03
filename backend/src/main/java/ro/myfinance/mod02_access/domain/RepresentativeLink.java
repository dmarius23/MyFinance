package ro.myfinance.mod02_access.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * MOD-02 — links a representative user to the single company they may access. A company has many
 * representatives; each representative belongs to exactly one company (FR-011), so the primary key
 * is the user id. Representative requests are additionally constrained to this company_id server-side.
 */
@Entity
@Table(name = "representative_link")
public class RepresentativeLink {

    @Id
    @Column(name = "user_id")
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
        this.tenantId = tenantId;
        this.userId = userId;
        this.companyId = companyId;
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
