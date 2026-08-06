package ro.myfinance.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.notifications.application.NotificationService;
import ro.myfinance.notifications.application.NotificationService.NotificationView;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Testcontainers coverage for the thin {@code notifications} module (S18). Exercises the real
 * persistence path — a staff notification is written under one tenant, read back by its recipient, and
 * marked read — plus the mandatory cross-tenant isolation assertion: the same recipient bound to a
 * different tenant sees nothing (RLS on {@code notification.tenant_id}).
 */
class NotificationServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-0000-0000-0000-000000000002");

    @Autowired NotificationService service;
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

    private UUID seedUser(UUID tenant, Role role, String label) {
        UUID id = UUID.randomUUID();
        String email = label + "+" + id + "@firma.ro"; // unique per seed (DB is shared across methods in a class)
        jdbc.update("insert into app_user(id, tenant_id, email, name, role, status) values (?,?,?,?,?, 'ACTIVE')",
                id, tenant, email, label, role.name());
        return id;
    }

    @Test
    void writesReadsAndMarksNotificationsForTheirRecipient() {
        UUID admin = UUID.randomUUID();
        asTenant(TENANT, admin, Role.TENANT_ADMIN);
        UUID company = seedCompany(TENANT);
        UUID employee = seedUser(TENANT, Role.EMPLOYEE, "angajat");

        // The admin assigns a task to the employee → one in-app notification for the employee.
        service.taskAssigned(employee, "Verifică documentele lunii", company, "Acme SRL");

        // Read back as the employee (its recipient).
        TenantContext.set(new TenantContext.Identity(TENANT, employee, Role.EMPLOYEE, null));
        List<NotificationView> inbox = service.list();
        assertThat(inbox).hasSize(1);
        assertThat(inbox.get(0).type()).isEqualTo("TASK_ASSIGNED");
        assertThat(inbox.get(0).companyId()).isEqualTo(company);
        assertThat(service.unreadCount()).isEqualTo(1);

        service.markRead(inbox.get(0).id());
        assertThat(service.unreadCount()).isZero();
    }

    @Test
    void notificationsAreInvisibleFromAnotherTenant() {
        UUID admin = UUID.randomUUID();
        asTenant(TENANT, admin, Role.TENANT_ADMIN);
        UUID company = seedCompany(TENANT);
        UUID employee = seedUser(TENANT, Role.EMPLOYEE, "angajat");
        service.taskAssigned(employee, "Sarcină internă", company, "Acme SRL");

        // Same recipient user id, but bound to a different tenant → RLS hides the row (fail-closed).
        TenantContext.set(new TenantContext.Identity(OTHER_TENANT, employee, Role.EMPLOYEE, null));
        assertThat(service.list()).isEmpty();
        assertThat(service.unreadCount()).isZero();
    }
}
