package ro.myfinance.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.reports.application.ReportService;
import ro.myfinance.reports.application.ReportService.ReportListRow;
import ro.myfinance.support.AbstractPostgresIT;

/** The Reports "needs attention" filter: only active companies with no trial balance (balanță) this month;
 *  tenant-isolated. */
class ReportsCompletenessFilterIT extends AbstractPostgresIT {

    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

    @Autowired ReportService service;
    @Autowired JdbcTemplate jdbc;

    // Fresh tenants per test (assertions are tenant-wide "contains exactly"; Testcontainers state persists).
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @AfterEach
    void clear() { TenantContext.clear(); }

    private void asTenant(UUID tenant) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STD') on conflict do nothing",
                tenant, "T-" + tenant);
    }

    private UUID company(UUID tenant, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, ?, ?, ?)",
                id, tenant, name, "RO-" + UUID.randomUUID(), status);
        return id;
    }

    private void balance(UUID tenant, UUID companyId) {
        jdbc.update("insert into document(id, tenant_id, company_id, period_month, type, source, status, "
                        + "original_filename, content_type, size_bytes, storage_key) "
                        + "values (?, ?, ?, ?, 'TRIAL_BALANCE', 'EMPLOYEE', 'UPLOADED', 'balanta.pdf', 'application/pdf', 10, ?)",
                UUID.randomUUID(), tenant, companyId, PERIOD, "key/" + UUID.randomUUID());
    }

    @Test
    void missingFilterKeepsOnlyActiveCompaniesWithNoBalance() {
        asTenant(tenantA);
        UUID activeEmpty = company(tenantA, "Alpha SRL", "ACTIVE");   // active, no balance → MISSING
        UUID hasBalance = company(tenantA, "Bravo SRL", "ACTIVE");    // active, has balance → not missing
        balance(tenantA, hasBalance);
        company(tenantA, "Charlie SRL", "INACTIVE");                 // inactive → not expected

        assertThat(service.listPage(PERIOD, "", true, 0, 25).getContent())
                .extracting(ReportListRow::companyId)
                .containsExactly(activeEmpty);

        // The "all" filter shows every ACTIVE company regardless of completeness, but inactive companies
        // are hidden from the module lists — so Charlie (INACTIVE) is excluded: Alpha + Bravo = 2.
        assertThat(service.listPage(PERIOD, "", false, 0, 25).getTotalElements()).isEqualTo(2);
    }

    @Test
    void missingFilterIsTenantScoped() {
        asTenant(tenantB);
        company(tenantB, "Bravo-B SRL", "ACTIVE"); // tenant B owes a balance, none uploaded — must NOT leak

        asTenant(tenantA);
        UUID a = company(tenantA, "Alpha-A SRL", "ACTIVE");
        assertThat(service.listPage(PERIOD, "", true, 0, 25).getContent())
                .extracting(ReportListRow::companyId)
                .containsExactly(a);
    }
}
