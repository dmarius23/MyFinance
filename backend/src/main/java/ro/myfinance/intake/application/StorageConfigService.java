package ro.myfinance.intake.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.intake.adapter.persistence.TenantStorageConfigRepository;
import ro.myfinance.intake.domain.StorageMode;
import ro.myfinance.intake.domain.TenantStorageConfig;

/**
 * Reads and updates the current tenant's document storage strategy. Absent config → SUPABASE_ONLY.
 * Phase 1 supports SUPABASE_ONLY + DRIVE_MIRROR; DRIVE_PRIMARY is rejected until Phase 2. Tenant-scoped
 * via RLS.
 */
@Service
@Transactional
public class StorageConfigService {

    private final TenantStorageConfigRepository configs;

    public StorageConfigService(TenantStorageConfigRepository configs) {
        this.configs = configs;
    }

    /** The tenant's storage strategy for the settings screen. */
    public record StorageConfigView(StorageMode mode, String sharedDriveId, String writeRootFolderId) {
    }

    @Transactional(readOnly = true)
    public StorageConfigView current() {
        return configs.findByTenantId(currentTenant())
                .map(c -> new StorageConfigView(c.getStorageMode(), c.getSharedDriveId(), c.getWriteRootFolderId()))
                .orElse(new StorageConfigView(StorageMode.SUPABASE_ONLY, null, null));
    }

    /** The active mode for the current tenant (default SUPABASE_ONLY). */
    @Transactional(readOnly = true)
    public StorageMode currentMode() {
        return configs.findByTenantId(currentTenant())
                .map(TenantStorageConfig::getStorageMode).orElse(StorageMode.SUPABASE_ONLY);
    }

    /** The tenant's Drive write target, present only when the mode writes to Drive and it is configured. */
    @Transactional(readOnly = true)
    public Optional<TenantStorageConfig> currentDriveTarget() {
        return configs.findByTenantId(currentTenant())
                .filter(c -> c.getStorageMode().writesToDrive())
                .filter(c -> notBlank(c.getSharedDriveId()) && notBlank(c.getWriteRootFolderId()));
    }

    public StorageConfigView update(StorageMode mode, String sharedDriveId, String writeRootFolderId) {
        if (mode == StorageMode.DRIVE_PRIMARY) {
            throw new IllegalArgumentException("DRIVE_PRIMARY is not available yet (Phase 2)");
        }
        String driveId = mode.writesToDrive() ? trimToNull(sharedDriveId) : null;
        String rootFolder = mode.writesToDrive() ? trimToNull(writeRootFolderId) : null;
        if (mode.writesToDrive() && (driveId == null || rootFolder == null)) {
            throw new IllegalArgumentException("Drive storage requires a Shared Drive id and a root folder id");
        }
        UUID tenantId = currentTenant();
        TenantStorageConfig cfg = configs.findByTenantId(tenantId)
                .orElseGet(() -> new TenantStorageConfig(tenantId));
        cfg.update(mode, driveId, rootFolder);
        configs.save(cfg);
        return new StorageConfigView(cfg.getStorageMode(), cfg.getSharedDriveId(), cfg.getWriteRootFolderId());
    }

    private static UUID currentTenant() {
        return TenantContext.tenantId().orElseThrow(() -> new IllegalStateException("No tenant bound"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
