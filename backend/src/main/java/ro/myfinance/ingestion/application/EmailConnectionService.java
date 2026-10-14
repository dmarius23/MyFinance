package ro.myfinance.ingestion.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.ingestion.adapter.persistence.EmailConnectionRepository;
import ro.myfinance.ingestion.domain.EmailConnection;

/** Tenant-scoped management of Gmail mailbox connections. OAuth callback wiring is added separately. */
@Service
@Transactional
public class EmailConnectionService {

    private final EmailConnectionRepository connections;

    public EmailConnectionService(EmailConnectionRepository connections) {
        this.connections = connections;
    }

    @Transactional(readOnly = true)
    public List<EmailConnection> list() {
        return connections.findByOrderByCreatedAtDesc();
    }

    public EmailConnection create(String displayName, String mailboxAddress) {
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        if (displayName == null || displayName.isBlank() || mailboxAddress == null
                || mailboxAddress.isBlank()) {
            throw new IllegalArgumentException("Display name and mailbox address are required");
        }
        return connections.save(new EmailConnection(tenantId, displayName.trim(),
                mailboxAddress.trim().toLowerCase()));
    }

    public EmailConnection createPending() {
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        EmailConnection connection = new EmailConnection(tenantId, "Gmail",
                "pending+" + UUID.randomUUID() + "@invalid");
        connection.setStatus("PENDING");
        return connections.save(connection);
    }

    public void disconnect(UUID connectionId, GmailMailboxClient gmail) {
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        EmailConnection connection = connections.findById(connectionId)
                .filter(candidate -> tenantId.equals(candidate.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Email connection not found"));
        try {
            gmail.revoke(connection);
        } finally {
            // Always wipe the secret locally, including when Google's revoke endpoint is unavailable.
            connection.setRefreshTokenEncrypted(null);
            connection.setOauthStateHash(null);
            connection.setOauthStateExpiresAt(null);
            connection.setGmailHistoryId(null);
            connection.setWatchExpiresAt(null);
            connection.setStatus("DISCONNECTED");
            connection.setLastResult("Gmail disconnected");
            connections.save(connection);
        }
    }
}
