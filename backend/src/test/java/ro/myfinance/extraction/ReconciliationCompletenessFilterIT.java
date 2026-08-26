package ro.myfinance.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.extraction.application.ReconciliationService;
import ro.myfinance.extraction.application.ReconciliationView.CompanyRef;
import ro.myfinance.support.AbstractPostgresIT;

/** The Bank-statements "needs attention" filter: active companies whose reconciliation isn't COMPLETE
 *  (no statement, or transactions still missing documents); tenant-isolated. */
class ReconciliationCompletenessFilterIT extends AbstractPostgresIT {

    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

    @Autowired ReconciliationService service;
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

    private UUID company(UUID tenant, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, ?, ?, ?)",
                id, tenant, name, "RO-" + UUID.randomUUID(), status);
        return id;
    }

    /** Give the company a fully-reconciled month: one statement + one transaction that needs no document. */
    private void completeReconciliation(UUID tenant, UUID companyId) {
        UUID docId = UUID.randomUUID();
        jdbc.update("insert into document(id, tenant_id, company_id, period_month, type, source, status, "
                        + "original_filename, content_type, size_bytes, storage_key) "
                        + "values (?, ?, ?, ?, 'BANK_STATEMENT', 'EMPLOYEE', 'UPLOADED', 'e.pdf', 'application/pdf', 10, ?)",
                docId, tenant, companyId, PERIOD, "key/" + UUID.randomUUID());
        UUID stmtId = UUID.randomUUID();
        jdbc.update("insert into bank_statement(id, tenant_id, company_id, document_id, period_month, status, "
                        + "cross_check_ok, txn_count, created_at) values (?, ?, ?, ?, ?, 'EXTRACTED', true, 1, now())",
                stmtId, tenant, companyId, docId, PERIOD);
        jdbc.update("insert into bank_transaction(id, tenant_id, company_id, statement_id, txn_date, amount, "
                        + "direction, requires_document) values (?, ?, ?, ?, ?, 100.0, 'CREDIT', false)",
                UUID.randomUUID(), tenant, companyId, stmtId, PERIOD.plusDays(5));
    }

    private java.util.List<UUID> needy() {
        return service.companiesNeedingAttention(PERIOD, "", 0, 25).getContent().stream()
                .map(CompanyRef::id).toList();
    }

    @Test
    void keepsActiveCompaniesWhoseReconciliationIsNotComplete() {
        asTenant(tenantA);
        UUID noStatement = company(tenantA, "Alpha SRL", "ACTIVE");   // active, nothing uploaded → needs attention
        UUID complete = company(tenantA, "Bravo SRL", "ACTIVE");      // active, fully reconciled → excluded
        completeReconciliation(tenantA, complete);
        company(tenantA, "Charlie SRL", "INACTIVE");                  // inactive → not expected

        assertThat(needy()).containsExactly(noStatement);
    }

    @Test
    void needyFilterIsTenantScoped() {
        asTenant(tenantB);
        company(tenantB, "Bravo-B SRL", "ACTIVE"); // tenant B needs attention — must NOT leak into tenant A

        asTenant(tenantA);
        UUID a = company(tenantA, "Alpha-A SRL", "ACTIVE");
        assertThat(needy()).containsExactly(a);
    }
}
