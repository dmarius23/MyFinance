package ro.myfinance.ingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.myfinance.access.application.UserDirectory;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.ingestion.adapter.persistence.ModuleSyncStatusRepository;
import ro.myfinance.ingestion.domain.ModuleSyncStatus;

/**
 * The unattended nightly poll has no interactive "running" phase, so it uses {@link
 * ModuleSyncStatusService#markSyncedNow} to refresh the last-synced timestamp the module screens read —
 * upserting the row when that month was never synced before.
 */
class ModuleSyncStatusServiceTest {

    private final ModuleSyncStatusRepository repo = mock(ModuleSyncStatusRepository.class);
    private final UserDirectory users = mock(UserDirectory.class);
    private final ModuleSyncStatusService service = new ModuleSyncStatusService(repo, users);
    private final UUID tenant = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void createsRowWhenAbsentAndStampsSyncedNow() {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        when(repo.findByModuleAndPeriodMonth("PAYROLL", LocalDate.of(2026, 8, 1))).thenReturn(Optional.empty());
        when(repo.save(any(ModuleSyncStatus.class))).thenAnswer(i -> i.getArgument(0));

        service.markSyncedNow("PAYROLL", LocalDate.of(2026, 8, 1), "3 imported");

        ArgumentCaptor<ModuleSyncStatus> captor = ArgumentCaptor.forClass(ModuleSyncStatus.class);
        verify(repo).save(captor.capture());
        ModuleSyncStatus saved = captor.getValue();
        assertThat(saved.getLastSyncedAt()).isNotNull();
        assertThat(saved.isRunning()).isFalse();
        assertThat(saved.getLastResult()).isEqualTo("3 imported");
    }

    @Test
    void refreshesExistingRow() {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        ModuleSyncStatus existing = new ModuleSyncStatus(tenant, "PAYROLL", LocalDate.of(2026, 8, 1));
        when(repo.findByModuleAndPeriodMonth("PAYROLL", LocalDate.of(2026, 8, 1)))
                .thenReturn(Optional.of(existing));
        when(repo.save(any(ModuleSyncStatus.class))).thenAnswer(i -> i.getArgument(0));

        service.markSyncedNow("PAYROLL", LocalDate.of(2026, 8, 1), "auto");

        assertThat(existing.getLastSyncedAt()).isNotNull();
        assertThat(existing.getLastResult()).isEqualTo("auto");
    }
}
