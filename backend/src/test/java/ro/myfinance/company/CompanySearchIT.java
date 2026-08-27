package ro.myfinance.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.access.application.RepresentativeService;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.application.CompanyService;
import ro.myfinance.company.domain.Company;
import ro.myfinance.company.domain.CompanyStatus;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * The module-list company search ({@link CompanyService#listPage}, backed by the shared repository
 * {@code search}): only ACTIVE companies surface, and a company matches when the query hits any of its
 * representatives' names — with the representative join staying strictly tenant-scoped (RLS).
 *
 * <p>Per-method random tenants: the IT fork shares one DB across methods with no per-method RLS rollback,
 * so a fresh tenant per test keeps the exact-count assertions clean.
 */
class CompanySearchIT extends AbstractPostgresIT {

    @Autowired CompanyService companies;
    @Autowired RepresentativeService representatives;
    @Autowired JdbcTemplate jdbc;

    private final UUID TENANT_A = UUID.randomUUID();
    private final UUID TENANT_B = UUID.randomUUID();

    @AfterEach
    void clear() { TenantContext.clear(); }

    private void asTenant(UUID tenant) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STD') on conflict do nothing",
                tenant, "T-" + tenant);
    }

    private UUID create(String name) {
        return companies.create(name, "RO-" + UUID.randomUUID(), "SRL", "Cluj", null, null, null, null, null).getId();
    }

    @Test
    void inactiveCompaniesAreHiddenFromTheModuleListSearch() {
        asTenant(TENANT_A);
        create("Active SRL");
        UUID inactive = create("Inactive SRL");
        companies.setStatus(inactive, CompanyStatus.INACTIVE);

        assertThat(companies.listPage("", 0, 50).getContent())
                .extracting(Company::getLegalName)
                .contains("Active SRL")
                .doesNotContain("Inactive SRL");
    }

    @Test
    void matchesACompanyByItsRepresentativeName() {
        asTenant(TENANT_A);
        UUID company = create("Widgets SRL");
        representatives.inviteRepresentative(company, "Ionel Popescu", "ionel@client.ro", "0712345678");

        // substring on the surname, case-insensitive — the company surfaces though its name/CUI don't match
        assertThat(companies.listPage("popescu", 0, 50).getContent())
                .extracting(Company::getLegalName).contains("Widgets SRL");
        assertThat(companies.listPage("Ionel", 0, 50).getContent())
                .extracting(Company::getLegalName).contains("Widgets SRL");
        // a rep name that doesn't exist matches nothing
        assertThat(companies.listPage("Nonexistent", 0, 50).getContent()).isEmpty();
    }

    @Test
    void representativeNameSearchIsTenantScoped() {
        asTenant(TENANT_A);
        UUID company = create("Alpha SRL");
        representatives.inviteRepresentative(company, "Zorbthur Uniquename", "z@client.ro", null);

        // Tenant B must not find tenant A's company by A's representative name.
        asTenant(TENANT_B);
        assertThat(companies.listPage("Zorbthur", 0, 50).getContent()).isEmpty();
    }
}
