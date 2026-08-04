package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.domain.RepresentativeLink;
import ro.myfinance.support.AbstractPostgresIT;

class RepresentativeLinkIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000aa01");

    @Autowired RepresentativeLinkRepository repLinks;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void aRepCanBeLinkedToMultipleCompanies() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", TENANT);

        // Seed the FK targets: a representative user and two companies (representative_link references both).
        UUID userId = UUID.randomUUID();
        UUID company1 = UUID.randomUUID();
        UUID company2 = UUID.randomUUID();
        jdbc.update("insert into app_user(id, tenant_id, email, name, role, status) values (?,?,?, 'Rep', 'REPRESENTATIVE','ACTIVE')",
                userId, TENANT, "rep-" + userId + "@client.ro");
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, 'C1', ?, 'ACTIVE')",
                company1, TENANT, "RO-" + company1);
        jdbc.update("insert into company(id, tenant_id, legal_name, cui, status) values (?, ?, 'C2', ?, 'ACTIVE')",
                company2, TENANT, "RO-" + company2);

        // Since V26, a representative may serve several companies (was one-company before).
        repLinks.save(new RepresentativeLink(TENANT, userId, company1));
        repLinks.save(new RepresentativeLink(TENANT, userId, company2));

        assertThat(repLinks.findByUserId(userId))
                .extracting(RepresentativeLink::getCompanyId)
                .containsExactlyInAnyOrder(company1, company2);
    }
}
