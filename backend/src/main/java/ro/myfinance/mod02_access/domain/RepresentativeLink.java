package ro.myfinance.mod02_access.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * MOD-02 — links a representative user to the single company they may access (MVP: one rep → one
 * company). Representative requests are additionally constrained to this {@code company_id},
 * enforced server-side.
 */
@Entity
@Table(name = "representative_link")
@IdClass(RepresentativeLink.Key.class)
public class RepresentativeLink {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "company_id")
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

    /** Composite key. */
    public static class Key implements Serializable {
        private UUID userId;
        private UUID companyId;

        public Key() {
        }

        public Key(UUID userId, UUID companyId) {
            this.userId = userId;
            this.companyId = companyId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(userId, key.userId) && Objects.equals(companyId, key.companyId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, companyId);
        }
    }
}
