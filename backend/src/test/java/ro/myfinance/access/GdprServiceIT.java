package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.application.GdprService;
import ro.myfinance.access.domain.RepresentativeLink;
import ro.myfinance.access.domain.UserStatus;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.support.AbstractPostgresIT;

class GdprServiceIT extends AbstractPostgresIT {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000d1");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000d2");

    @Autowired GdprService gdpr;
    @Autowired AppUserRepository users;
    @Autowired RepresentativeLinkRepository repLinks;
    @Autowired AuditRecorder auditRecorder;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private void asTenant(UUID tenant, UUID actor, Role role) {
        TenantContext.set(new TenantContext.Identity(tenant, actor, role, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", tenant);
    }

    private UUID seedRep(UUID tenant, String email, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app_user(id, tenant_id, email, name, role, status) values (?,?,?,?, 'REPRESENTATIVE','ACTIVE')",
                id, tenant, email, name);
        return id;
    }

    private UUID seedCompany(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, 'C', ?, 'ACTIVE')",
                id, tenant, "RO-" + id);
        return id;
    }

    @Test
    void exportsProfileCompanyLinksAndAuditTrail() {
        asTenant(TENANT_A, UUID.randomUUID(), Role.TENANT_ADMIN);
        UUID subject = seedRep(TENANT_A, "alex@client.ro", "Alex Pop");
        UUID company = seedCompany(TENANT_A);
        repLinks.save(new RepresentativeLink(TENANT_A, subject, company));
        // An audit entry attributed to the subject (actor = subject).
        TenantContext.set(new TenantContext.Identity(TENANT_A, subject, Role.REPRESENTATIVE, company));
        auditRecorder.record("DOCUMENT_UPLOADED", "document", UUID.randomUUID());
        TenantContext.set(new TenantContext.Identity(TENANT_A, UUID.randomUUID(), Role.TENANT_ADMIN, null));

        GdprService.UserDataExport export = gdpr.export(subject);

        assertThat(export.user().email()).isEqualTo("alex@client.ro");
        assertThat(export.user().name()).isEqualTo("Alex Pop");
        assertThat(export.companyLinks()).extracting(GdprService.CompanyLink::companyId).containsExactly(company);
        assertThat(export.auditTrail()).extracting(GdprService.AuditRecord::action).contains("DOCUMENT_UPLOADED");
    }

    @Test
    void anonymizeScrubsPersonalDataAndDeactivates() {
        asTenant(TENANT_A, UUID.randomUUID(), Role.TENANT_ADMIN);
        UUID subject = seedRep(TENANT_A, "erase.me@client.ro", "Erase Me");

        gdpr.anonymize(subject);

        var after = users.findById(subject).orElseThrow();
        assertThat(after.getName()).isEqualTo("[erased]");
        assertThat(after.getEmail()).startsWith("erased-").doesNotContain("erase.me");
        assertThat(after.getStatus()).isEqualTo(UserStatus.INACTIVE);
    }

    @Test
    void cannotExportAUserOfAnotherTenant() {
        asTenant(TENANT_B, UUID.randomUUID(), Role.TENANT_ADMIN);
        UUID userInB = seedRep(TENANT_B, "b@other.ro", "Other Tenant");
        // Switch to tenant A — RLS must hide tenant B's user entirely.
        asTenant(TENANT_A, UUID.randomUUID(), Role.TENANT_ADMIN);

        assertThatThrownBy(() -> gdpr.export(userInB)).isInstanceOf(NotFoundException.class);
    }
}
