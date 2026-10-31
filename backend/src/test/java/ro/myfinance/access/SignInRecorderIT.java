package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.access.application.SignInRecorder;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * The promotion must survive the database, not just the entity: it runs while a REPRESENTATIVE identity is
 * bound, so it has to pass {@code tenant_isolation} (USING + WITH CHECK on tenant_id) on {@code app_user}.
 * {@code app_user} has no {@code company_id}, so V49's {@code rep_company_scope} does not apply to it —
 * this test pins that, because if RLS ever did block the UPDATE the promotion would fail silently.
 */
class SignInRecorderIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000fa");
    private static final UUID OTHER_TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000fb");

    @Autowired SignInRecorder recorder;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private UUID seedRep(UUID tenant, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') "
                + "on conflict do nothing", tenant);
        jdbc.update("insert into app_user(id, tenant_id, email, name, role, status) "
                        + "values (?, ?, ?, 'Rep', 'REPRESENTATIVE', ?)",
                id, tenant, "rep-" + id + "@client.ro", status);
        return id;
    }

    private String storedStatus(UUID userId) {
        return jdbc.queryForObject("select status from app_user where id = ?", String.class, userId);
    }

    @Test
    void promotesAPendingInviteRepOnTheirFirstRequest() {
        UUID rep = seedRep(TENANT, "PENDING_INVITE");
        TenantContext.set(new TenantContext.Identity(TENANT, rep, Role.REPRESENTATIVE, null));

        recorder.recordSignIn(rep);

        assertThat(storedStatus(rep)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select last_login from app_user where id = ?",
                java.sql.Timestamp.class, rep)).isNotNull();
    }

    @Test
    void promotesAnInvitedRepOnTheirFirstRequest() {
        UUID rep = seedRep(TENANT, "INVITED");
        TenantContext.set(new TenantContext.Identity(TENANT, rep, Role.REPRESENTATIVE, null));

        recorder.recordSignIn(rep);

        assertThat(storedStatus(rep)).isEqualTo("ACTIVE");
    }

    @Test
    void cannotPromoteAUserInAnotherTenant() {
        UUID outsider = seedRep(OTHER_TENANT, "PENDING_INVITE");
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.REPRESENTATIVE, null));

        recorder.recordSignIn(outsider);

        // tenant_isolation must hide the row entirely — no cross-tenant write.
        TenantContext.set(new TenantContext.Identity(OTHER_TENANT, outsider, Role.REPRESENTATIVE, null));
        assertThat(storedStatus(outsider)).isEqualTo("PENDING_INVITE");
    }
}
