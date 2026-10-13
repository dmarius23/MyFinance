package ro.myfinance.ingestion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** Tenant-owned Gmail mailbox configuration. The refresh token is encrypted before persistence. */
@Entity
@Table(name = "email_connection")
public class EmailConnection {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "mailbox_address", nullable = false)
    private String mailboxAddress;

    @Column(name = "refresh_token_enc")
    private String refreshTokenEncrypted;

    @Column(name = "oauth_state_hash")
    private String oauthStateHash;

    @Column(name = "oauth_state_expires_at")
    private Instant oauthStateExpiresAt;

    @Column(name = "gmail_history_id")
    private String gmailHistoryId;

    @Column(name = "watch_expires_at")
    private Instant watchExpiresAt;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_result")
    private String lastResult;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EmailConnection() {
    }

    public EmailConnection(UUID tenantId, String displayName, String mailboxAddress) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.displayName = displayName;
        this.mailboxAddress = mailboxAddress;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { this.displayName = value; }
    public String getMailboxAddress() { return mailboxAddress; }
    public void setMailboxAddress(String value) { this.mailboxAddress = value; }
    public String getRefreshTokenEncrypted() { return refreshTokenEncrypted; }
    public void setRefreshTokenEncrypted(String value) { this.refreshTokenEncrypted = value; }
    public String getOauthStateHash() { return oauthStateHash; }
    public void setOauthStateHash(String value) { this.oauthStateHash = value; }
    public Instant getOauthStateExpiresAt() { return oauthStateExpiresAt; }
    public void setOauthStateExpiresAt(Instant value) { this.oauthStateExpiresAt = value; }
    public String getGmailHistoryId() { return gmailHistoryId; }
    public void setGmailHistoryId(String value) { this.gmailHistoryId = value; }
    public Instant getWatchExpiresAt() { return watchExpiresAt; }
    public void setWatchExpiresAt(Instant value) { this.watchExpiresAt = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant value) { this.lastSyncedAt = value; }
    public String getLastResult() { return lastResult; }
    public void setLastResult(String value) { this.lastResult = value; }
    public Instant getCreatedAt() { return createdAt; }
}
