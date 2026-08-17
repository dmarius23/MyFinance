package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.ingestion.application.ModuleSyncStatusService;
import ro.myfinance.support.AbstractPostgresIT;

/** Per (module + month) sync state on the real schema: running → last-synced, and tenant isolation (RLS). */
class ModuleSyncStatusIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000f1");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000f1");
    private static final LocalDate PERIOD = LocalDate.of(2026, 6, 1);

    @Autowired ModuleSyncStatusService status;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.Identity(t, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", t);
    }

    @Test
    void tracksRunningThenLastSynced() {
        asTenant(TENANT_A);
        status.markStart("DECLARATION", PERIOD, null);
        var running = status.get("DECLARATION", PERIOD);
        assertThat(running.running()).isTrue();
        assertThat(running.startedAt()).isNotNull();
        assertThat(running.lastSyncedAt()).isNull();

        status.markFinish("DECLARATION", PERIOD, "26 imported, 2275 to review");
        var done = status.get("DECLARATION", PERIOD);
        assertThat(done.running()).isFalse();
        assertThat(done.lastSyncedAt()).isNotNull();
        assertThat(done.lastResult()).isEqualTo("26 imported, 2275 to review");
    }

    @Test
    void statusIsTenantIsolated() {
        asTenant(TENANT_A);
        status.markStart("PAYROLL", PERIOD, null);
        status.markFinish("PAYROLL", PERIOD, "9 imported");
        TenantContext.clear();

        asTenant(TENANT_B); // a different tenant sees nothing for the same module + month
        var other = status.get("PAYROLL", PERIOD);
        assertThat(other.running()).isFalse();
        assertThat(other.lastSyncedAt()).isNull();
        assertThat(other.lastResult()).isNull();
    }

    @Test
    void rejectsASecondConcurrentSyncThenAllowsAfterFinish() {
        asTenant(TENANT_A);
        assertThat(status.tryStart("DECLARATION", PERIOD, null)).isTrue();  // first claims the slot
        assertThat(status.tryStart("DECLARATION", PERIOD, null)).isFalse(); // second rejected while running
        status.markFinish("DECLARATION", PERIOD, "done");
        assertThat(status.tryStart("DECLARATION", PERIOD, null)).isTrue();  // released → can run again
    }

    @Test
    void aStaleRunningFlagFromACrashedSyncIsReclaimed() {
        asTenant(TENANT_A);
        assertThat(status.tryStart("TRIAL_BALANCE", PERIOD, null)).isTrue();
        assertThat(status.tryStart("TRIAL_BALANCE", PERIOD, null)).isFalse(); // fresh → rejected
        // Simulate a crashed sync that never released the flag (started well beyond the stale window).
        jdbc.update("update module_sync_status set started_at = now() - interval '30 minutes' where module = 'TRIAL_BALANCE'");
        assertThat(status.tryStart("TRIAL_BALANCE", PERIOD, null)).isTrue(); // stale → reclaimed
    }
}
