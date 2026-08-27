package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.ingestion.application.IngestionScheduler;
import ro.myfinance.ingestion.application.IngestionScheduler.Conn;
import ro.myfinance.ingestion.application.IngestionService;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Per-tenant auto-sync scheduling (V56): the hourly tick must pick up only the tenants whose
 * {@code general_settings.auto_sync_hour} matches the current hour AND whose {@code auto_sync_enabled}
 * is true — while a tenant with no settings row yet inherits the defaults (enabled, hour 2).
 */
class IngestionScheduleSelectionIT extends AbstractPostgresIT {

    // Hour 2 (explicit), hour 5, disabled at hour 2, and no settings row (defaults → hour 2).
    private static final UUID T_HOUR2 = UUID.fromString("dddddddd-0000-0000-0000-0000000005a1");
    private static final UUID T_HOUR5 = UUID.fromString("dddddddd-0000-0000-0000-0000000005a2");
    private static final UUID T_OFF = UUID.fromString("dddddddd-0000-0000-0000-0000000005a3");
    private static final UUID T_DEFAULT = UUID.fromString("dddddddd-0000-0000-0000-0000000005a4");

    @Autowired IngestionScheduler scheduler;
    @Autowired IngestionService ingestion;
    @Autowired @Qualifier("adminJdbcTemplate") JdbcTemplate admin;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private UUID connFor(UUID tenant) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        admin.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", tenant);
        UUID id = ingestion.create("GOOGLE_DRIVE", "Drive", "root-" + tenant, "PAYROLL", false, "DECLARATIONS", false, null).getId();
        TenantContext.clear();
        return id;
    }

    private void settings(UUID tenant, boolean enabled, int hour) {
        admin.update("insert into general_settings(tenant_id, auto_sync_enabled, auto_sync_hour) values (?, ?, ?)"
                + " on conflict (tenant_id) do update set auto_sync_enabled = excluded.auto_sync_enabled,"
                + " auto_sync_hour = excluded.auto_sync_hour", tenant, enabled, hour);
    }

    @Test
    void selectsOnlyTenantsScheduledForTheGivenHour() {
        UUID c2 = connFor(T_HOUR2);
        UUID c5 = connFor(T_HOUR5);
        UUID cOff = connFor(T_OFF);
        UUID cDefault = connFor(T_DEFAULT);
        settings(T_HOUR2, true, 2);
        settings(T_HOUR5, true, 5);
        settings(T_OFF, false, 2);
        // T_DEFAULT: no general_settings row → inherits defaults (enabled, hour 2).

        assertThat(scheduler.dueConnections(2)).extracting(Conn::id)
                .contains(c2, cDefault)
                .doesNotContain(c5, cOff);

        assertThat(scheduler.dueConnections(5)).extracting(Conn::id)
                .contains(c5)
                .doesNotContain(c2, cOff, cDefault);
    }
}
