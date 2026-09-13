package ro.myfinance.common.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Per-tenant outbound email (SMTP) provider — one row per accounting firm, so each firm's client emails go
 * out through their own mailbox/provider. {@code smtpPasswordEnc} is AES-GCM ciphertext (see
 * {@code SecretCipher}); the plaintext password is never stored or returned to the client. RLS isolates
 * rows by tenant.
 */
@Entity
@Table(name = "tenant_email_provider")
public class TenantEmailProvider {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(name = "from_email")
    private String fromEmail;

    @Column(name = "from_name")
    private String fromName;

    @Column(name = "smtp_host")
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort;

    @Column(name = "smtp_username")
    private String smtpUsername;

    @Column(name = "smtp_password_enc")
    private String smtpPasswordEnc;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantEmailProvider() {
    }

    public TenantEmailProvider(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getTenantId() { return tenantId; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getFromEmail() { return fromEmail; }
    public void setFromEmail(String fromEmail) { this.fromEmail = fromEmail; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }
    public Integer getSmtpPort() { return smtpPort; }
    public void setSmtpPort(Integer smtpPort) { this.smtpPort = smtpPort; }
    public String getSmtpUsername() { return smtpUsername; }
    public void setSmtpUsername(String smtpUsername) { this.smtpUsername = smtpUsername; }
    public String getSmtpPasswordEnc() { return smtpPasswordEnc; }
    public void setSmtpPasswordEnc(String smtpPasswordEnc) { this.smtpPasswordEnc = smtpPasswordEnc; }
    public Instant getUpdatedAt() { return updatedAt; }

    /** True when this config has enough to actually send (enabled + host + from address). */
    public boolean isSendable() {
        return enabled && smtpHost != null && !smtpHost.isBlank()
                && fromEmail != null && !fromEmail.isBlank();
    }
}
