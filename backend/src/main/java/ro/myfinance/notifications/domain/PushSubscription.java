package ro.myfinance.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A single browser's Web Push subscription for a user. {@code endpoint} is the push service URL and
 * {@code p256dh}/{@code auth} are the client's public encryption keys (VAPID/RFC-8291). One user may
 * have several rows (multiple devices/browsers). Unique per (tenant, endpoint) so re-subscribing the
 * same browser refreshes rather than duplicates.
 */
@Entity
@Table(name = "push_subscription")
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String endpoint;

    @Column(nullable = false)
    private String p256dh;

    @Column(nullable = false)
    private String auth;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PushSubscription() {
    }

    public PushSubscription(UUID tenantId, UUID userId, String endpoint, String p256dh, String auth) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
    }

    /** Refresh the client keys when the same browser re-subscribes (endpoint is the identity). */
    public void refreshKeys(String p256dh, String auth) {
        this.p256dh = p256dh;
        this.auth = auth;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getEndpoint() { return endpoint; }
    public String getP256dh() { return p256dh; }
    public String getAuth() { return auth; }
    public Instant getCreatedAt() { return createdAt; }
}
