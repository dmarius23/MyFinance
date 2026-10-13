package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.ingestion.adapter.persistence.EmailConnectionRepository;
import ro.myfinance.ingestion.domain.EmailConnection;
import ro.myfinance.support.AbstractPostgresIT;

/** RLS regression test for Gmail connection data. */
class EmailConnectionRlsIT extends AbstractPostgresIT {

    @Autowired EmailConnectionRepository connections;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void tenantBCannotReadTenantAGmailConnection() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        asTenant(tenantA);
        EmailConnection connection = connections.save(new EmailConnection(tenantA, "A inbox",
                "a-" + UUID.randomUUID() + "@example.com"));

        asTenant(tenantB);

        assertThat(connections.findById(connection.getId())).isEmpty();
        assertThat(connections.findAll()).isEmpty();
    }

    @Test
    void tenantBCannotReadOrWriteMessagesAndAttachmentsBelongingToTenantA() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        asTenant(tenantA);
        EmailConnection connection = connections.save(new EmailConnection(tenantA, "A inbox",
                "a-" + UUID.randomUUID() + "@example.com"));
        UUID messageId = UUID.randomUUID();
        UUID attachmentId = UUID.randomUUID();
        jdbc.update("""
                insert into email_message(id, tenant_id, connection_id, gmail_message_id,
                    sender_email, status)
                values (?, ?, ?, ?, ?, 'RECEIVED')
                """, messageId, tenantA, connection.getId(), "message-" + messageId,
                "sender@example.com");
        jdbc.update("""
                insert into email_attachment(id, tenant_id, message_id, gmail_attachment_id,
                    filename, status)
                values (?, ?, ?, ?, ?, 'RECEIVED')
                """, attachmentId, tenantA, messageId, "attachment-" + attachmentId,
                "statement.pdf");

        asTenant(tenantB);

        assertThat(jdbc.queryForObject("select count(*) from email_message", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from email_attachment", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("""
                insert into email_message(id, tenant_id, connection_id, gmail_message_id,
                    sender_email, status)
                values (?, ?, ?, ?, ?, 'RECEIVED')
                """, UUID.randomUUID(), tenantA, connection.getId(), "cross-tenant",
                "sender@example.com"))
                .hasMessageContaining("row-level security");
        assertThatThrownBy(() -> jdbc.update("""
                insert into email_attachment(id, tenant_id, message_id, gmail_attachment_id,
                    filename, status)
                values (?, ?, ?, ?, ?, 'RECEIVED')
                """, UUID.randomUUID(), tenantA, messageId, "cross-tenant",
                "statement.pdf"))
                .hasMessageContaining("row-level security");
    }

    private void asTenant(UUID tenantId) {
        TenantContext.set(new TenantContext.Identity(tenantId, UUID.randomUUID(),
                Role.TENANT_ADMIN, null));
        jdbc.update("insert into tenant(id, name, status, plan) values (?, ?, 'ACTIVE', 'STANDARD')"
                        + " on conflict do nothing",
                tenantId, "gmail-test-" + tenantId);
    }
}
