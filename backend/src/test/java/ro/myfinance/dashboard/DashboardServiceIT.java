package ro.myfinance.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.dashboard.application.DashboardService;
import ro.myfinance.dashboard.application.DashboardService.StatusFilter;
import ro.myfinance.dashboard.domain.DashboardView;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Testcontainers coverage for the thin, read-only {@code dashboard} module (S18). Proves the aggregate
 * builds against a real DB — a seeded company shows up as a row with its tiles — and that the view is
 * tenant-scoped: bound to a different tenant it contains no companies (RLS via the underlying services).
 */
class DashboardServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("55555555-0000-0000-0000-000000000005");
    private static final UUID OTHER_TENANT = UUID.fromString("66666666-0000-0000-0000-000000000006");
    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

    @Autowired DashboardService service;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void asTenant(UUID tenant, UUID user, Role role) {
        TenantContext.set(new TenantContext.Identity(tenant, user, role, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STD') on conflict do nothing",
                tenant, "T-" + tenant);
    }

    private UUID seedCompany(UUID tenant, String legalName) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, ?, ?, 'ACTIVE')",
                id, tenant, legalName, "RO-" + id);
        return id;
    }

    @Test
    void buildsATenantScopedOverview() {
        asTenant(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN);
        UUID company = seedCompany(TENANT, "Acme SRL");

        DashboardView view = service.build(PERIOD, null, StatusFilter.ALL);

        assertThat(view.tiles().totalCompanies()).isGreaterThanOrEqualTo(1);
        assertThat(view.rows()).anySatisfy(row -> {
            assertThat(row.companyId()).isEqualTo(company);
            assertThat(row.legalName()).isEqualTo("Acme SRL");
        });
    }

    @Test
    void overviewExcludesOtherTenantsCompanies() {
        asTenant(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN);
        UUID company = seedCompany(TENANT, "Acme SRL");

        // A different tenant with no companies sees an empty overview — the seeded company stays hidden.
        TenantContext.set(new TenantContext.Identity(OTHER_TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        DashboardView view = service.build(PERIOD, null, StatusFilter.ALL);
        assertThat(view.rows()).noneMatch(row -> row.companyId().equals(company));
        assertThat(view.tiles().totalCompanies()).isZero();
    }
}
