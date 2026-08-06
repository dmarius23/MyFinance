package ro.myfinance.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.application.CompanyService;
import ro.myfinance.company.domain.Company;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Covers the paged companies directory (S16): deterministic sort, page boundaries, the max-size clamp,
 * and tenant isolation on both the content and the count.
 *
 * <p>Each test uses a fresh tenant UUID: the IT fork shares one DB across methods (no per-method
 * rollback under RLS), and these assertions are on exact tenant-wide counts, so a clean RLS scope per
 * method is required to avoid cross-method bleed.
 */
class CompanyPageIT extends AbstractPostgresIT {

    @Autowired CompanyService service;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void asTenant(UUID tenant) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STD') on conflict do nothing",
                tenant, "T-" + tenant);
    }

    private void seedCompany(UUID tenant, String legalName) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, ?, ?, 'ACTIVE')",
                id, tenant, legalName, "RO-" + id);
    }

    @Test
    void pagesAreSortedByNameAndBounded() {
        UUID TENANT = UUID.randomUUID();
        asTenant(TENANT);
        seedCompany(TENANT, "Zeta SRL");
        seedCompany(TENANT, "Alpha SRL");
        seedCompany(TENANT, "Mid SRL");

        Page<Company> first = service.listPage(0, 2);
        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getContent()).extracting(Company::getLegalName).containsExactly("Alpha SRL", "Mid SRL");
        assertThat(first.isLast()).isFalse();

        Page<Company> second = service.listPage(1, 2);
        assertThat(second.getContent()).extracting(Company::getLegalName).containsExactly("Zeta SRL");
        assertThat(second.isLast()).isTrue();
    }

    @Test
    void pageSizeIsClampedToTheMaximum() {
        UUID TENANT = UUID.randomUUID();
        asTenant(TENANT);
        seedCompany(TENANT, "Only SRL");

        Page<Company> page = service.listPage(0, 5000);
        assertThat(page.getSize()).isEqualTo(100); // clamped, not 5000
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void pagingIsTenantScoped() {
        UUID TENANT = UUID.randomUUID();
        UUID OTHER_TENANT = UUID.randomUUID();
        asTenant(TENANT);
        seedCompany(TENANT, "Acme SRL");

        TenantContext.set(new TenantContext.Identity(OTHER_TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        Page<Company> page = service.listPage(0, 25);
        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();
    }
}
