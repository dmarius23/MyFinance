package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.access.application.AccessService;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.UserStatus;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.support.AbstractPostgresIT;

/** MOD-02 staff invites against a real Postgres with RLS active. */
class AccessServiceIT extends AbstractPostgresIT {

    // Per-method (non-static) random tenants — JUnit builds a fresh instance per test, so each method
    // gets its own tenant namespace and no cross-method row pollution.
    private final UUID TENANT_A = UUID.randomUUID();
    private final UUID TENANT_B = UUID.randomUUID();

    @Autowired AccessService access;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private void asTenant(UUID tenant) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing",
                tenant);
    }

    @Test
    void invitesStaffAsInvited() {
        asTenant(TENANT_A);

        AppUser staff = access.inviteUser("contabil@firma.ro", "Ana Contabil", Role.EMPLOYEE);

        assertThat(staff.getRole()).isEqualTo(Role.EMPLOYEE);
        // The dev inviter + logging email sender deliver successfully, so the invite is marked as sent.
        assertThat(staff.getStatus()).isEqualTo(UserStatus.INVITED);
        assertThat(access.listStaff()).extracting(AppUser::getEmail).contains("contabil@firma.ro");
    }

    @Test
    void rejectsADuplicateEmailInTheSameTenant() {
        asTenant(TENANT_A);
        access.inviteUser("dup@firma.ro", "Ana", Role.EMPLOYEE);

        assertThatThrownBy(() -> access.inviteUser("dup@firma.ro", "Ana again", Role.TENANT_ADMIN))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void resendsASetPasswordLinkToOwnStaff() {
        asTenant(TENANT_A);
        AppUser staff = access.inviteUser("resend@firma.ro", "Ana", Role.EMPLOYEE);

        access.sendInvite(staff.getId());   // mails a fresh recovery link; no exception = delivered
    }

    /**
     * Golden rule #1 — cross-tenant isolation for the new "resend invite" path. Resending mails a
     * credential-bearing set-password link, so a tenant admin must not be able to aim it at a user in
     * another tenant: RLS hides the row entirely, so the lookup fails before any link is minted.
     */
    @Test
    void cannotResendAnInviteToAnotherTenantsUser() {
        asTenant(TENANT_A);
        AppUser foreign = access.inviteUser("victim@tenant-a.ro", "Victim", Role.EMPLOYEE);

        asTenant(TENANT_B);

        assertThatThrownBy(() -> access.sendInvite(foreign.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThat(access.listUsers()).extracting(AppUser::getEmail).doesNotContain("victim@tenant-a.ro");
    }

    /** Representatives are re-invited per company via RepresentativeService, never through this path. */
    @Test
    void cannotResendAnInviteToARepresentative() {
        asTenant(TENANT_A);
        UUID repId = UUID.randomUUID();
        jdbc.update("insert into app_user(id, tenant_id, email, name, role, status) "
                        + "values (?, ?, 'rep@client.ro', 'Rep', 'REPRESENTATIVE', 'INVITED')",
                repId, TENANT_A);

        assertThatThrownBy(() -> access.sendInvite(repId)).isInstanceOf(IllegalArgumentException.class);
    }
}
