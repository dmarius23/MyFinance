package ro.myfinance.mod02_access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.mod02_access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.mod02_access.domain.RepresentativeLink;
import ro.myfinance.support.AbstractPostgresIT;

class RepresentativeLinkIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000aa01");

    @Autowired RepresentativeLinkRepository repLinks;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void aRepIsLinkedToExactlyOneCompany() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", TENANT);

        UUID userId = UUID.randomUUID();
        UUID company1 = UUID.randomUUID();
        UUID company2 = UUID.randomUUID();

        repLinks.save(new RepresentativeLink(TENANT, userId, company1));
        // Re-linking the same rep replaces the company (PK is user_id) rather than adding a row.
        repLinks.save(new RepresentativeLink(TENANT, userId, company2));

        assertThat(repLinks.findById(userId)).isPresent();
        assertThat(repLinks.findById(userId).get().getCompanyId()).isEqualTo(company2);
        assertThat(repLinks.count()).isEqualTo(1);
    }
}
