package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.access.application.RepresentativeService;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.UserStatus;
import ro.myfinance.company.application.CompanyService;
import ro.myfinance.support.AbstractPostgresIT;

class RepresentativeServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("cccccccc-0000-0000-0000-0000000000c1");

    @Autowired RepresentativeService representatives;
    @Autowired CompanyService companies;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private UUID asTenantWithCompany() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", TENANT);
        return companies.create("Client SRL", "RO-REP-" + UUID.randomUUID(), "SRL", "Cluj", "VAT_PAYER", "MONTHLY", null).getId();
    }

    @Test
    void invitesARepresentativeAndLinksToCompany() {
        UUID companyId = asTenantWithCompany();

        AppUser rep = representatives.inviteRepresentative(companyId, "rep@client.ro", "Rep One");

        assertThat(rep.getRole()).isEqualTo(Role.REPRESENTATIVE);
        assertThat(rep.getStatus()).isEqualTo(UserStatus.INVITED);
        assertThat(representatives.listRepresentatives(companyId)).extracting(AppUser::getEmail)
                .containsExactly("rep@client.ro");
    }

    @Test
    void invitingToAMissingCompanyFails() {
        asTenantWithCompany();
        assertThatThrownBy(() -> representatives.inviteRepresentative(UUID.randomUUID(), "x@y.ro", "X"))
                .isInstanceOf(ro.myfinance.common.web.NotFoundException.class);
    }

    @Test
    void reinvitingSameEmailIsRejected() {
        UUID company1 = asTenantWithCompany();
        UUID company2 = companies.create("Second SRL", "RO-REP2-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null).getId();
        AppUser rep = representatives.inviteRepresentative(company1, "dup@client.ro", "Dup");

        // A different invite produces a different external id (new auth user) for the same email →
        // attaching a second app_user with the same email in the tenant must be rejected.
        assertThatThrownBy(() -> representatives.inviteRepresentative(company2, "dup@client.ro", "Dup"))
                .isInstanceOf(ConflictException.class);
        assertThat(rep.getEmail()).isEqualTo("dup@client.ro");
    }
}
