package ro.myfinance.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.email.EmailStatus;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.payroll.application.PayrollService;
import ro.myfinance.payroll.application.PayrollService.PayrollEmailView;
import ro.myfinance.payroll.application.PayrollService.PayrollRow;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Testcontainers coverage for the thin {@code payroll} module (S18). Drives the real send pipeline —
 * a payroll email is recorded ({@code email_history}) and durably queued, then relayed inline to SENT —
 * and reads it back via {@code history}/{@code summary}. Includes the mandatory cross-tenant isolation
 * assertion: the recorded email is invisible from another tenant (RLS on {@code email_history}).
 */
class PayrollServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("33333333-0000-0000-0000-000000000003");
    private static final UUID OTHER_TENANT = UUID.fromString("44444444-0000-0000-0000-000000000004");
    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

    @Autowired PayrollService service;
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

    private UUID seedCompany(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, 'Acme SRL', ?, 'ACTIVE')",
                id, tenant, "RO-" + id);
        return id;
    }

    @Test
    void recordsAndRelaysAPayrollEmail() {
        asTenant(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN);
        UUID company = seedCompany(TENANT);

        PayrollEmailView sent = service.send(company, PERIOD, "client@firma.ro",
                "Bună ziua, atașat regăsiți statul de plată.", List.of());
        assertThat(sent.recipient()).isEqualTo("client@firma.ro");

        // history() re-reads the persisted row after the inline outbox relay flipped it to SENT.
        List<PayrollEmailView> history = service.history(company, PERIOD);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).id()).isEqualTo(sent.id());
        assertThat(history.get(0).recipient()).isEqualTo("client@firma.ro");
        assertThat(history.get(0).status()).isEqualTo(EmailStatus.SENT);

        // summary() rolls the send into the per-company monthly view.
        List<PayrollRow> summary = service.summary(PERIOD);
        assertThat(summary).anySatisfy(row -> {
            assertThat(row.companyId()).isEqualTo(company);
            assertThat(row.sentCount()).isEqualTo(1);
        });
    }

    @Test
    void payrollHistoryIsInvisibleFromAnotherTenant() {
        asTenant(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN);
        UUID company = seedCompany(TENANT);
        service.send(company, PERIOD, "client@firma.ro", "corp", List.of());

        // Rebind the same company id under a different tenant → RLS returns nothing.
        TenantContext.set(new TenantContext.Identity(OTHER_TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        assertThat(service.history(company, PERIOD)).isEmpty();
        assertThat(service.summary(PERIOD)).isEmpty();
    }
}
