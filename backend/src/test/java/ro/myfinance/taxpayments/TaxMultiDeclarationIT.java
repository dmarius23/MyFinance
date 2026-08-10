package ro.myfinance.taxpayments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.support.AbstractPostgresIT;
import ro.myfinance.taxpayments.application.TaxPaymentService;
import ro.myfinance.taxpayments.domain.DeclarationType;
import ro.myfinance.taxpayments.domain.TaxPaymentRow;

/**
 * Proves the "show all, never auto-dedup" rule: a company that files several declarations of the same
 * type in one month (e.g. separate D100s for chirii, dividende and impozit) sees ALL of them in the
 * monthly list — the pipeline no longer collapses same-type copies into one canonical cell.
 */
class TaxMultiDeclarationIT extends AbstractPostgresIT {

    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

    @Autowired TaxPaymentService taxService;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void seedDeclaration(UUID tenant, UUID company, String type, String amount) {
        java.math.BigDecimal total = new java.math.BigDecimal(amount);
        jdbc.update("""
                insert into tax_declaration
                  (id, tenant_id, company_id, period_month, document_id, type, cui,
                   declared_total, computed_total, mismatch, decl_period)
                values (?, ?, ?, ?, ?, ?, 'RO1', ?, ?, false, ?)
                """,
                UUID.randomUUID(), tenant, company, PERIOD, UUID.randomUUID(), type, total, total, PERIOD);
    }

    @Test
    void listShowsEveryDeclarationOfTheSameType() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", tenant);
        UUID company = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, 'Acme SRL', 'RO1', 'ACTIVE')",
                company, tenant);

        // Three same-period D100s (chirii / dividende / impozit) plus one D300.
        seedDeclaration(tenant, company, "D100", "1000.00");
        seedDeclaration(tenant, company, "D100", "2000.00");
        seedDeclaration(tenant, company, "D100", "3000.00");
        seedDeclaration(tenant, company, "D300", "500.00");

        TaxPaymentRow row = taxService.list(PERIOD).stream()
                .filter(r -> r.companyId().equals(company)).findFirst().orElseThrow();

        List<TaxPaymentRow.DeclarationCell> d100 = row.declarations().stream()
                .filter(c -> c.type() == DeclarationType.D100).toList();
        assertThat(d100).hasSize(3);
        assertThat(d100).extracting(c -> c.amount().intValueExact())
                .containsExactlyInAnyOrder(1000, 2000, 3000);
        assertThat(row.declarations()).filteredOn(c -> c.type() == DeclarationType.D300).hasSize(1);
    }
}
