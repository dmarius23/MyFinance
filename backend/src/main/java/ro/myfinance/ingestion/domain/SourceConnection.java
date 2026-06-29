package ro.myfinance.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * MOD-15 — a tenant's configured document source (a cloud folder the app watches). The administrator
 * sets which folder; no secrets are stored here (the app authenticates with one service account whose
 * key is in env, and the admin shares the folder with it). Tenant-scoped via RLS.
 */
@Entity
@Table(name = "source_connection")
public class SourceConnection {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String provider;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "root_folder_id", nullable = false)
    private String rootFolderId;

    @Column(name = "forced_type")
    private String forcedType;

    @Column(nullable = false, columnDefinition = "jsonb")
    private String config = "{}";

    private String cursor;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_result")
    private String lastResult;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SourceConnection() {
    }

    public SourceConnection(UUID tenantId, String provider, String displayName, String rootFolderId,
                            String forcedType) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.provider = provider;
        this.displayName = displayName;
        this.rootFolderId = rootFolderId;
        this.forcedType = forcedType;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getProvider() { return provider; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getRootFolderId() { return rootFolderId; }
    public void setRootFolderId(String v) { this.rootFolderId = v; }
    public String getForcedType() { return forcedType; }
    public void setForcedType(String v) { this.forcedType = v; }
    public String getConfig() { return config; }
    public void setConfig(String v) { this.config = v == null ? "{}" : v; }
    public String getCursor() { return cursor; }
    public void setCursor(String v) { this.cursor = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant v) { this.lastSyncedAt = v; }
    public String getLastResult() { return lastResult; }
    public void setLastResult(String v) { this.lastResult = v; }
    public Instant getCreatedAt() { return createdAt; }
}
