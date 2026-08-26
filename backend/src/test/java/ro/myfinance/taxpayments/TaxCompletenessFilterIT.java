package ro.myfinance.taxpayments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.taxpayments.application.TaxPaymentService;
import ro.myfinance.taxpayments.domain.DeclarationType;
import ro.myfinance.taxpayments.domain.TaxPaymentRow;
import ro.myfinance.support.AbstractPostgresIT;

/** The Taxe "needs attention" filter (with per-type picker): companies that OWE a declaration but haven't
 *  filed it; expected-aware via the fiscal profile; tenant-isolated. */
class TaxCompletenessFilterIT extends AbstractPostgresIT {

    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

    @Autowired TaxPaymentService service;
    @Autowired JdbcTemplate jdbc;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    @AfterEach
    void clear() { TenantContext.clear(); }

    private void asTenant(UUID tenant) {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STD') on conflict do nothing",
                tenant, "T-" + tenant);
    }

    /** A company with the given fiscal profile (nulls ⇒ not owed). */
    private UUID company(UUID tenant, String name, Boolean employees, String vat, String regime) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status, has_employees, vat_status, tax_regime) "
                        + "values (?, ?, ?, ?, 'ACTIVE', ?, ?, ?)",
                id, tenant, name, "RO-" + UUID.randomUUID(), employees, vat, regime);
        return id;
    }

    /** File a declaration of {@code type} for the period (document + tax_declaration). */
    private void filedDeclaration(UUID tenant, UUID companyId, DeclarationType type) {
        UUID docId = UUID.randomUUID();
        jdbc.update("insert into document(id, tenant_id, company_id, period_month, type, source, status, "
                        + "original_filename, content_type, size_bytes, storage_key) "
                        + "values (?, ?, ?, ?, 'DECLARATION', 'EMPLOYEE', 'UPLOADED', 'd.xml', 'application/xml', 10, ?)",
                docId, tenant, companyId, PERIOD, "key/" + UUID.randomUUID());
        jdbc.update("insert into tax_declaration(id, tenant_id, company_id, period_month, document_id, type, "
                        + "computed_total, mismatch, wrong_party, duplicate, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, 0, false, false, false, now())",
                UUID.randomUUID(), tenant, companyId, PERIOD, docId, type.name());
    }

    private java.util.List<UUID> missing(DeclarationType declType) {
        return service.listPage(PERIOD, "", true, declType, 0, 25).getContent().stream()
                .map(TaxPaymentRow::companyId).toList();
    }

    @Test
    void missingByTypeRespectsTheFiscalProfileAndWhatIsFiled() {
        asTenant(tenantA);
        UUID emp = company(tenantA, "Emp SRL", true, null, null);          // owes D112 (employees) + D100 (all)
        UUID vat = company(tenantA, "Vat SRL", false, "VAT_PAYER", null);  // owes D300 (VAT) + D100 (all)
        UUID plain = company(tenantA, "Plain SRL", false, null, null);     // owes only D100 (all)
        UUID done = company(tenantA, "Done SRL", false, null, null);       // owes only D100 — and filed it
        filedDeclaration(tenantA, done, DeclarationType.D100);

        // D112 only from companies with employees that haven't filed it; D300 only from VAT payers.
        assertThat(missing(DeclarationType.D112)).containsExactly(emp);
        assertThat(missing(DeclarationType.D300)).containsExactly(vat);
        // D100 is owed by EVERY company — everyone who hasn't filed it (all but "done").
        assertThat(missing(DeclarationType.D100)).containsExactlyInAnyOrder(emp, vat, plain);
        // Any expected type missing (no declType): everyone still missing something ("done" filed its only owed type).
        assertThat(missing(null)).containsExactlyInAnyOrder(emp, vat, plain);
    }

    @Test
    void missingFilterIsTenantScoped() {
        asTenant(tenantB);
        company(tenantB, "Bravo-B SRL", true, null, null); // tenant B owes D112 — must NOT leak into tenant A

        asTenant(tenantA);
        UUID a = company(tenantA, "Alpha-A SRL", true, null, null);
        assertThat(missing(DeclarationType.D112)).containsExactly(a);
    }
}
