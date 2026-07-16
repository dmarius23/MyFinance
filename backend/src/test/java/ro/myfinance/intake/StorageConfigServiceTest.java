package ro.myfinance.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.intake.adapter.persistence.TenantStorageConfigRepository;
import ro.myfinance.intake.application.StorageConfigService;
import ro.myfinance.intake.domain.StorageMode;
import ro.myfinance.intake.domain.TenantStorageConfig;

/** Default mode, Drive-target validation, and the Phase-1 rejection of DRIVE_PRIMARY. */
class StorageConfigServiceTest {

    private final TenantStorageConfigRepository repo = mock(TenantStorageConfigRepository.class);
    private final StorageConfigService service = new StorageConfigService(repo);
    private final UUID tenant = UUID.randomUUID();

    @BeforeEach
    void bind() {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void defaultsToSupabaseOnlyWhenNoConfig() {
        when(repo.findByTenantId(tenant)).thenReturn(Optional.empty());
        assertThat(service.current().mode()).isEqualTo(StorageMode.SUPABASE_ONLY);
        assertThat(service.currentMode()).isEqualTo(StorageMode.SUPABASE_ONLY);
        assertThat(service.currentDriveTarget()).isEmpty();
    }

    @Test
    void updateToMirrorStoresDriveTarget() {
        when(repo.findByTenantId(tenant)).thenReturn(Optional.empty());
        var view = service.update(StorageMode.DRIVE_MIRROR, "  drive-123 ", " root-abc ");
        assertThat(view.mode()).isEqualTo(StorageMode.DRIVE_MIRROR);
        assertThat(view.sharedDriveId()).isEqualTo("drive-123");   // trimmed
        assertThat(view.writeRootFolderId()).isEqualTo("root-abc");
    }

    @Test
    void updateToSupabaseClearsDriveFields() {
        when(repo.findByTenantId(tenant)).thenReturn(Optional.empty());
        var view = service.update(StorageMode.SUPABASE_ONLY, "drive-123", "root-abc");
        assertThat(view.sharedDriveId()).isNull();
        assertThat(view.writeRootFolderId()).isNull();
    }

    @Test
    void mirrorRequiresDriveIdAndRootFolder() {
        when(repo.findByTenantId(tenant)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(StorageMode.DRIVE_MIRROR, "  ", "root"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(StorageMode.DRIVE_MIRROR, "drive", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void drivePrimaryIsRejectedInPhase1() {
        assertThatThrownBy(() -> service.update(StorageMode.DRIVE_PRIMARY, "drive", "root"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currentDriveTargetOnlyWhenConfigured() {
        TenantStorageConfig cfg = new TenantStorageConfig(tenant);
        cfg.update(StorageMode.DRIVE_MIRROR, "drive-123", "root-abc");
        when(repo.findByTenantId(tenant)).thenReturn(Optional.of(cfg));
        assertThat(service.currentDriveTarget()).isPresent();

        TenantStorageConfig incomplete = new TenantStorageConfig(tenant);
        incomplete.update(StorageMode.DRIVE_MIRROR, null, null);
        when(repo.findByTenantId(tenant)).thenReturn(Optional.of(incomplete));
        assertThat(service.currentDriveTarget()).isEmpty(); // mode writes to Drive but not configured
    }
}
