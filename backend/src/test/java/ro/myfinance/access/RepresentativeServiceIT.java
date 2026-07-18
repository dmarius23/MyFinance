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
    // Dedicated tenant pair for the isolation test — kept apart from TENANT (which sibling tests
    // seed with reps) so the cross-tenant assertions aren't polluted by leftover rows.
    private static final UUID TENANT_ISO_A = UUID.fromString("cccccccc-0000-0000-0000-0000000000c2");
    private static final UUID TENANT_ISO_B = UUID.fromString("cccccccc-0000-0000-0000-0000000000c3");

    @Autowired RepresentativeService representatives;
    @Autowired CompanyService companies;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private UUID asTenantWithCompany() {
        return asTenantWithCompany(TENANT, "Client SRL", "RO-REP-");
    }

    private UUID asTenantWithCompany(UUID tenant, String name, String cuiPrefix) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", tenant);
        return companies.create(name, cuiPrefix + UUID.randomUUID(), "SRL", "Cluj", "VAT_PAYER", "MONTHLY", null, null, null).getId();
    }

    @Test
    void invitesARepresentativeAndLinksToCompany() {
        UUID companyId = asTenantWithCompany();

        AppUser rep = representatives.inviteRepresentative(companyId, "Rep One", "rep@client.ro", "0712345678");

        assertThat(rep.getRole()).isEqualTo(Role.REPRESENTATIVE);
        assertThat(rep.getStatus()).isEqualTo(UserStatus.INVITED);
        assertThat(rep.getPhone()).isEqualTo("0712345678");
        assertThat(representatives.listRepresentatives(companyId))
                .extracting(AppUser::getEmail, AppUser::getName, AppUser::getPhone)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("rep@client.ro", "Rep One", "0712345678"));
    }

    @Test
    void invitingToAMissingCompanyFails() {
        asTenantWithCompany();
        assertThatThrownBy(() -> representatives.inviteRepresentative(UUID.randomUUID(), "X", "x@y.ro", null))
                .isInstanceOf(ro.myfinance.common.web.NotFoundException.class);
    }

    @Test
    void sameRepCanBeAssignedToMultipleCompanies() {
        UUID company1 = asTenantWithCompany();
        UUID company2 = companies.create("Second SRL", "RO-REP2-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null, null, null).getId();
        AppUser rep = representatives.inviteRepresentative(company1, "Dup", "dup@client.ro", null);

        // Assigning the same email to a second company links the existing user (one person, many companies).
        AppUser again = representatives.inviteRepresentative(company2, "Dup", "dup@client.ro", null);
        assertThat(again.getId()).isEqualTo(rep.getId());
        assertThat(representatives.listRepresentatives(company1)).extracting(AppUser::getId).containsExactly(rep.getId());
        assertThat(representatives.listRepresentatives(company2)).extracting(AppUser::getId).containsExactly(rep.getId());

        // But assigning to a company they're already on is rejected.
        assertThatThrownBy(() -> representatives.inviteRepresentative(company1, "Dup", "dup@client.ro", null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void unassignRemovesOnlyTheCompanyLink() {
        UUID company1 = asTenantWithCompany();
        UUID company2 = companies.create("Second SRL", "RO-REP3-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null, null, null).getId();
        AppUser rep = representatives.inviteRepresentative(company1, "Dup", "dup@client.ro", null);
        representatives.inviteRepresentative(company2, "Dup", "dup@client.ro", null);

        representatives.unassignRepresentative(company1, rep.getId());

        assertThat(representatives.listRepresentatives(company1)).isEmpty();
        assertThat(representatives.listRepresentatives(company2)).extracting(AppUser::getId).containsExactly(rep.getId());
    }

    @Test
    void deactivateSetsInactiveStatus() {
        UUID companyId = asTenantWithCompany();
        AppUser rep = representatives.inviteRepresentative(companyId, "Dep", "dep@client.ro", null);

        representatives.setRepresentativeActive(companyId, rep.getId(), false);
        assertThat(representatives.listRepresentatives(companyId)).singleElement()
                .satisfies(u -> assertThat(u.getStatus()).isEqualTo(UserStatus.INACTIVE));

        representatives.setRepresentativeActive(companyId, rep.getId(), true);
        assertThat(representatives.listRepresentatives(companyId)).singleElement()
                .satisfies(u -> assertThat(u.getStatus()).isEqualTo(UserStatus.ACTIVE));
    }

    /**
     * Mandatory cross-tenant isolation test (golden rule #1) for the new {@code /api/v1/representatives}
     * data-access path: {@code listAllRepresentatives()} does an unfiltered {@code findAll()} and relies
     * entirely on RLS for scoping. Tenant B must never see tenant A's reps, and vice versa.
     */
    @Test
    void listAllRepresentativesIsScopedToTheCurrentTenant() {
        // Tenant A has its own representative.
        UUID companyA = asTenantWithCompany(TENANT_ISO_A, "Client A SRL", "RO-A-");
        representatives.inviteRepresentative(companyA, "Rep A", "rep-a@client.ro", null);

        // Tenant B has a different representative (this leaves us in tenant B's context).
        UUID companyB = asTenantWithCompany(TENANT_ISO_B, "Client B SRL", "RO-B-");
        representatives.inviteRepresentative(companyB, "Rep B", "rep-b@client.ro", null);

        // In tenant B's context, only tenant B's rep+company are visible — RLS strips tenant A's rows.
        assertThat(representatives.listAllRepresentatives())
                .extracting(e -> e.email(), e -> e.companyId())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("rep-b@client.ro", companyB));

        // Switching back to tenant A yields only tenant A's rep — no leakage across the boundary.
        TenantContext.set(new TenantContext.Identity(TENANT_ISO_A, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        assertThat(representatives.listAllRepresentatives())
                .extracting(e -> e.email(), e -> e.companyId())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("rep-a@client.ro", companyA));
    }
}
