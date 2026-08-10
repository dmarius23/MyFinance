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
 * The Tax &amp; Payments list shows one line per fiscal obligation (creanță) with its ANAF code + short
 * label, collapsing only exact duplicates (same code + same amount). A company that files several
 * declarations of the same type (separate D100s for chirii / dividende) sees each obligation; a D112
 * with several obligations expands into several lines; an accidental re-upload of the same creanță
 * collapses.
 */
class TaxMultiDeclarationIT extends AbstractPostgresIT {

    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

    @Autowired TaxPaymentService taxService;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** Seed a declaration with a JSON obligations array, e.g. {@code [{"cod":"628","amount":1000}]}. */
    private void seedDeclaration(UUID tenant, UUID company, String type, String total, String obligationsJson) {
        jdbc.update("""
                insert into tax_declaration
                  (id, tenant_id, company_id, period_month, document_id, type, cui,
                   declared_total, computed_total, mismatch, decl_period, obligations)
                values (?, ?, ?, ?, ?, ?, 'RO1', ?, ?, false, ?, cast(? as jsonb))
                """,
                UUID.randomUUID(), tenant, company, PERIOD, UUID.randomUUID(), type,
                new java.math.BigDecimal(total), new java.math.BigDecimal(total), PERIOD, obligationsJson);
    }

    @Test
    void listShowsOneLinePerObligationWithLabelsAndDedup() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", tenant);
        UUID company = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, 'Acme SRL', 'RO1', 'ACTIVE')",
                company, tenant);

        // Two separate D100s (chirii 628 / dividende 604) + an accidental re-upload of chirii (same code+amount).
        seedDeclaration(tenant, company, "D100", "1000", "[{\"cod\":\"628\",\"amount\":1000}]");
        seedDeclaration(tenant, company, "D100", "2000", "[{\"cod\":\"604\",\"amount\":2000}]");
        seedDeclaration(tenant, company, "D100", "1000", "[{\"cod\":\"628\",\"amount\":1000}]"); // duplicate
        // One D112 carrying several obligations in a single document.
        seedDeclaration(tenant, company, "D112", "800", "[{\"cod\":\"412\",\"amount\":500},{\"cod\":\"432\",\"amount\":300}]");

        TaxPaymentRow row = taxService.list(PERIOD).stream()
                .filter(r -> r.companyId().equals(company)).findFirst().orElseThrow();

        List<TaxPaymentRow.DeclarationCell> d100 = row.declarations().stream()
                .filter(c -> c.type() == DeclarationType.D100).toList();
        assertThat(d100).hasSize(2); // the duplicate chirii collapsed
        assertThat(d100).extracting(TaxPaymentRow.DeclarationCell::cod).containsExactlyInAnyOrder("628", "604");
        assertThat(d100).extracting(TaxPaymentRow.DeclarationCell::label).containsExactlyInAnyOrder("Chirii", "Dividende");

        List<TaxPaymentRow.DeclarationCell> d112 = row.declarations().stream()
                .filter(c -> c.type() == DeclarationType.D112).toList();
        assertThat(d112).extracting(TaxPaymentRow.DeclarationCell::cod).containsExactlyInAnyOrder("412", "432");
        assertThat(d112).extracting(TaxPaymentRow.DeclarationCell::label).containsExactlyInAnyOrder("CAS", "CASS");
    }
}
