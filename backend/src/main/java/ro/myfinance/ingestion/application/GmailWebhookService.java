package ro.myfinance.ingestion.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.outbox.OutboxWriter;
import ro.myfinance.ingestion.adapter.external.GmailPubSubTokenVerifier;

/** Handles Google Pub/Sub push envelopes without trusting the browser or a tenant-supplied id. */
@Service
public class GmailWebhookService {

    private final JdbcTemplate admin;
    private final GmailPubSubTokenVerifier tokenVerifier;
    private final GmailSyncOutboxHandler syncHandler;
    private final OutboxWriter outbox;
    private final ObjectMapper json;

    public GmailWebhookService(@Qualifier("adminJdbcTemplate") JdbcTemplate admin,
                               GmailPubSubTokenVerifier tokenVerifier, GmailSyncOutboxHandler syncHandler,
                               OutboxWriter outbox,
                               ObjectMapper json) {
        this.admin = admin;
        this.tokenVerifier = tokenVerifier;
        this.syncHandler = syncHandler;
        this.outbox = outbox;
        this.json = json;
    }

    public int handle(String suppliedToken, JsonNode envelope) {
        tokenVerifier.verify(suppliedToken);
        String encoded = envelope.path("message").path("data").asText(null);
        if (encoded == null || encoded.isBlank()) {
            return 0;
        }
        try {
            JsonNode notification = json.readTree(Base64.getDecoder().decode(encoded));
            String mailbox = notification.path("emailAddress").asText(null);
            String historyId = notification.path("historyId").asText(null);
            if (mailbox == null || mailbox.isBlank() || historyId == null || historyId.isBlank()) {
                return 0;
            }
            List<ConnectionRef> targets = admin.query(
                    "select id, tenant_id from email_connection"
                            + " where lower(mailbox_address) = lower(?) and status = 'ACTIVE'",
                    (rs, row) -> new ConnectionRef(
                            rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)),
                    mailbox);
            int processed = 0;
            for (ConnectionRef target : targets) {
                try {
                    TenantContext.set(new TenantContext.Identity(target.tenantId(), null,
                            Role.TENANT_ADMIN, null));
                    outbox.enqueue(GmailSyncOutboxHandler.AGGREGATE, target.id().toString(),
                            GmailSyncOutboxHandler.TYPE,
                            syncHandler.serialize(new GmailSyncOutboxHandler.Payload(
                                    target.id(), historyId)));
                    processed++;
                } finally {
                    TenantContext.clear();
                }
            }
            return processed;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Gmail Pub/Sub payload", e);
        }
    }

    public record ConnectionRef(UUID id, UUID tenantId) {
    }
}
