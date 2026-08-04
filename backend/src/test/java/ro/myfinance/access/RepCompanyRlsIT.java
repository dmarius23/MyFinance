package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.domain.RepresentativeLink;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.application.CompanyDirectory;
import ro.myfinance.intake.adapter.persistence.DocumentRepository;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * Defense-in-depth RLS (V49): a REPRESENTATIVE only sees rows for companies they are linked to, even within
 * their own tenant. Staff see everything in the tenant. Verified at the database layer — the queries below
 * run through {@code RlsDataSource} with the bound identity, so the {@code rep_company_scope} RESTRICTIVE
 * policy applies.
 */
class RepCompanyRlsIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000f9");

    @Autowired DocumentRepository documents;
    @Autowired RepresentativeLinkRepository repLinks;
    @Autowired CompanyDirectory companies;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private void as(UUID user, Role role) {
        TenantContext.set(new TenantContext.Identity(TENANT, user, role, null));
    }

    private UUID seedCompany() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, 'C', ?, 'ACTIVE')",
                id, TENANT, "RO-" + id);
        return id;
    }

    private UUID seedDocument(UUID company) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into document(id, tenant_id, company_id, period_month, type, source, status, "
                        + "original_filename, content_type, size_bytes, storage_key) "
                        + "values (?,?,?,?, 'INVOICE','EMPLOYEE','UPLOADED','x.pdf','application/pdf',1,?)",
                id, TENANT, company, java.sql.Date.valueOf(LocalDate.of(2026, 6, 1)), "k/" + id);
        return id;
    }

    @Test
    void representativeSeesOnlyLinkedCompanysRows() {
        UUID admin = UUID.randomUUID();
        UUID rep = UUID.randomUUID();
        as(admin, Role.TENANT_ADMIN);
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", TENANT);
        jdbc.update("insert into app_user(id, tenant_id, email, name, role, status) values (?,?,?, 'Rep', 'REPRESENTATIVE','ACTIVE')",
                rep, TENANT, "rep-" + rep + "@client.ro");

        UUID companyA = seedCompany();
        UUID companyB = seedCompany();
        repLinks.save(new RepresentativeLink(TENANT, rep, companyA)); // linked to A only
        UUID docA = seedDocument(companyA);
        UUID docB = seedDocument(companyB);

        // Staff (TENANT_ADMIN): both companies and both documents are visible.
        assertThat(documents.findById(docA)).isPresent();
        assertThat(documents.findById(docB)).isPresent();
        assertThat(companies.findById(companyA)).isPresent();
        assertThat(companies.findById(companyB)).isPresent();

        // Representative of A: only A's rows — the RESTRICTIVE policy hides company B entirely.
        as(rep, Role.REPRESENTATIVE);
        assertThat(documents.findById(docA)).isPresent();
        assertThat(documents.findById(docB)).isEmpty();
        assertThat(companies.findById(companyA)).isPresent();
        assertThat(companies.findById(companyB)).isEmpty();
    }
}
