package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.ingestion.adapter.persistence.EmailSyncClaimRepository;
import ro.myfinance.support.AbstractPostgresIT;

class EmailSyncClaimIT extends AbstractPostgresIT {

    @Autowired EmailSyncClaimRepository claims;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void onlyOneWorkerOwnsAnUnexpiredConnectionClaim() {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        asTenant(tenantId);
        jdbc.update("insert into email_connection(id, tenant_id, display_name, mailbox_address) "
                        + "values (?, ?, 'Claim test', ?)",
                connectionId, tenantId, "claim-" + connectionId + "@example.com");

        assertThat(claims.tryClaim(connectionId, tenantId, Duration.ofMinutes(10))).isTrue();
        assertThat(claims.tryClaim(connectionId, tenantId, Duration.ofMinutes(10))).isFalse();

        claims.release(connectionId);
        assertThat(claims.tryClaim(connectionId, tenantId, Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void anotherTenantCannotClaimAConnection() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        asTenant(tenantA);
        jdbc.update("insert into email_connection(id, tenant_id, display_name, mailbox_address) "
                        + "values (?, ?, 'Claim test', ?)",
                connectionId, tenantA, "claim-" + connectionId + "@example.com");
        assertThat(claims.tryClaim(connectionId, tenantA, Duration.ofMinutes(10))).isTrue();

        asTenant(tenantB);
        assertThat(claims.tryClaim(connectionId, tenantB, Duration.ofMinutes(10))).isFalse();
    }

    private void asTenant(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(),
                Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD') "
                        + "on conflict do nothing",
                tenantId, "gmail-claim-test-" + tenantId);
    }
}
