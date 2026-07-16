package ro.myfinance.intake.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A tenant's document storage strategy. One row per tenant (absent → {@link StorageMode#SUPABASE_ONLY}).
 * When a Drive mode is selected, {@code sharedDriveId} + {@code writeRootFolderId} point at the firm-owned
 * Google Shared Drive our service account is a member of. Tenant-scoped via RLS.
 */
@Entity
@Table(name = "tenant_storage_config")
public class TenantStorageConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_mode", nullable = false)
    private StorageMode storageMode = StorageMode.SUPABASE_ONLY;

    @Column(name = "shared_drive_id")
    private String sharedDriveId;

    @Column(name = "write_root_folder_id")
    private String writeRootFolderId;

    @Column(name = "cache_ttl_seconds")
    private Integer cacheTtlSeconds;

    @Column(name = "cache_max_bytes")
    private Long cacheMaxBytes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected TenantStorageConfig() {
    }

    public TenantStorageConfig(UUID tenantId) {
        this.tenantId = tenantId;
    }

    /** Apply an update; drive fields are only meaningful when the mode writes to Drive. */
    public void update(StorageMode mode, String sharedDriveId, String writeRootFolderId) {
        this.storageMode = mode;
        this.sharedDriveId = sharedDriveId;
        this.writeRootFolderId = writeRootFolderId;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public StorageMode getStorageMode() { return storageMode; }
    public String getSharedDriveId() { return sharedDriveId; }
    public String getWriteRootFolderId() { return writeRootFolderId; }
    public Integer getCacheTtlSeconds() { return cacheTtlSeconds; }
    public Long getCacheMaxBytes() { return cacheMaxBytes; }
}
