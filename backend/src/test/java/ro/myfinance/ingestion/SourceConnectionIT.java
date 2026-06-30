package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.ingestion.application.IngestionService;
import ro.myfinance.ingestion.domain.SourceConnection;
import ro.myfinance.support.AbstractPostgresIT;

/** Persists a connection through the real schema — guards the jsonb/text config column binding. */
class SourceConnectionIT extends AbstractPostgresIT {

    private static final UUID TENANT = UUID.fromString("dddddddd-0000-0000-0000-0000000000d1");

    @Autowired IngestionService ingestion;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() { TenantContext.clear(); }

    private void asTenant() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, 't', 'ACTIVE', 'STD') on conflict do nothing", TENANT);
    }

    @Test
    void createsAndPersistsAConnection() {
        asTenant();
        SourceConnection c = ingestion.create("GOOGLE_DRIVE", "Payroll Drive", "folder-123", "PAYROLL", null);
        assertThat(c.getId()).isNotNull();
        assertThat(ingestion.list()).extracting(SourceConnection::getRootFolderId).contains("folder-123");
    }
}
