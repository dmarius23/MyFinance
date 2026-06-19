package ro.myfinance.company;

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
import ro.myfinance.company.application.CompanyService;
import ro.myfinance.support.AbstractPostgresIT;

class CompanyServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @Autowired CompanyService companies;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void asTenant(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') on conflict do nothing",
                tenantId, "T-" + tenantId);
    }

    @Test
    void createsAndListsCompaniesScopedToTenant() {
        asTenant(TENANT_A);
        companies.create("Alpha SRL", "RO-A-1", "SRL", "Cluj", "VAT_PAYER", "MONTHLY", null, null, null);

        asTenant(TENANT_B);
        companies.create("Beta SRL", "RO-B-1", "SRL", "București", "NON_VAT_PAYER", "QUARTERLY", null, null, null);

        assertThat(companies.list()).hasSize(1);
        assertThat(companies.list().get(0).getLegalName()).isEqualTo("Beta SRL");
    }

    @Test
    void rejectsDuplicateCuiWithinTenant() {
        asTenant(TENANT_A);
        companies.create("Alpha SRL", "RO-DUP", "SRL", "Cluj", "VAT_PAYER", "MONTHLY", null, null, null);
        assertThatThrownBy(() -> companies.create("Alpha2 SRL", "RO-DUP", "SRL", "Cluj", null, null, null, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updatesCui() {
        asTenant(TENANT_A);
        UUID id = companies.create("Alpha SRL", "RO-OLD", "SRL", "Cluj", null, null, null, null, null).getId();

        companies.update(id, "RO-NEW", "Alpha SRL", null, null, null, null, null, null, null);

        assertThat(companies.get(id).getCui()).isEqualTo("RO-NEW");
    }

    @Test
    void rejectsUpdateToAnotherCompanysCui() {
        asTenant(TENANT_A);
        companies.create("Alpha SRL", "RO-A", "SRL", "Cluj", null, null, null, null, null);
        UUID id = companies.create("Beta SRL", "RO-B", "SRL", "Cluj", null, null, null, null, null).getId();

        assertThatThrownBy(() -> companies.update(id, "RO-A", "Beta SRL", null, null, null, null, null, null, null))
                .isInstanceOf(ConflictException.class);
        assertThat(companies.get(id).getCui()).isEqualTo("RO-B"); // unchanged
    }

    @Test
    void keepsCuiWhenUpdateOmitsIt() {
        asTenant(TENANT_A);
        UUID id = companies.create("Alpha SRL", "RO-KEEP", "SRL", "Cluj", null, null, null, null, null).getId();

        companies.update(id, null, "Alpha Renamed SRL", null, null, null, null, null, null, null);

        assertThat(companies.get(id).getCui()).isEqualTo("RO-KEEP");
        assertThat(companies.get(id).getLegalName()).isEqualTo("Alpha Renamed SRL");
    }
}
