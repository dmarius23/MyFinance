package ro.myfinance.common.whatsapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Short-lived, tenant-bound state for the Meta Embedded Signup authorization flow. */
@Entity
@Table(name = "whatsapp_meta_oauth_state")
public class MetaWhatsAppOAuthState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "state_hash", nullable = false, unique = true, updatable = false)
    private String stateHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected MetaWhatsAppOAuthState() {
    }

    public MetaWhatsAppOAuthState(UUID tenantId, UUID userId, String stateHash, Instant expiresAt) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.stateHash = stateHash;
        this.expiresAt = expiresAt;
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public String getStateHash() { return stateHash; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }

    public boolean isUsableAt(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }

    public void consume(Instant now) {
        this.consumedAt = now;
    }
}
