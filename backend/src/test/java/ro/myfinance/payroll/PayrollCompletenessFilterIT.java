package ro.myfinance.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.payroll.application.PayrollService;
import ro.myfinance.payroll.application.PayrollService.PayrollListRow;
import ro.myfinance.support.AbstractPostgresIT;

/** The Payroll "needs attention" filter: only companies that owe payroll (have employees) and uploaded
 *  nothing this month; tenant-isolated. */
class PayrollCompletenessFilterIT extends AbstractPostgresIT {

    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

    @Autowired PayrollService service;
    @Autowired JdbcTemplate jdbc;

    // Fresh tenants per test: the assertions are tenant-wide "contains exactly", so each test needs a clean
    // tenant (Testcontainers state persists across tests in the class).
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @AfterEach
    void clear() { TenantContext.clear(); }

    private void asTenant(UUID tenant) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STD') on conflict do nothing",
                tenant, "T-" + tenant);
    }

    private UUID company(UUID tenant, String name, boolean hasEmployees) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status, has_employees) "
                        + "values (?, ?, ?, ?, 'ACTIVE', ?)",
                id, tenant, name, "RO-" + UUID.randomUUID(), hasEmployees);
        return id;
    }

    private void payrollDoc(UUID tenant, UUID companyId) {
        jdbc.update("insert into document(id, tenant_id, company_id, period_month, type, source, status, "
                        + "original_filename, content_type, size_bytes, storage_key) "
                        + "values (?, ?, ?, ?, 'PAYROLL', 'EMPLOYEE', 'UPLOADED', 'stat.pdf', 'application/pdf', 10, ?)",
                UUID.randomUUID(), tenant, companyId, PERIOD, "key/" + UUID.randomUUID());
    }

    @Test
    void missingFilterKeepsOnlyCompaniesThatOwePayrollAndUploadedNothing() {
        asTenant(tenantA);
        UUID owesAndEmpty = company(tenantA, "Alpha SRL", true);   // has employees, no doc → MISSING
        UUID hasDoc = company(tenantA, "Bravo SRL", true);         // has employees, has doc → not missing
        payrollDoc(tenantA, hasDoc);
        company(tenantA, "Charlie SRL", false);                    // no employees → not expected

        assertThat(service.listPage(PERIOD, "", true, 0, 25).getContent())
                .extracting(PayrollListRow::companyId)
                .containsExactly(owesAndEmpty);

        // The "all" filter is unchanged — every company, regardless of completeness.
        assertThat(service.listPage(PERIOD, "", false, 0, 25).getTotalElements()).isEqualTo(3);
    }

    @Test
    void missingFilterIsTenantScoped() {
        asTenant(tenantB);
        company(tenantB, "Bravo-B SRL", true); // tenant B owes payroll, no doc — must NOT leak into tenant A

        asTenant(tenantA);
        UUID a = company(tenantA, "Alpha-A SRL", true);
        assertThat(service.listPage(PERIOD, "", true, 0, 25).getContent())
                .extracting(PayrollListRow::companyId)
                .containsExactly(a);
    }
}
