package ro.myfinance.common.whatsapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Per-tenant WhatsApp provider — one row per accounting firm. {@code mode} selects how the firm sends:
 * {@code OFF} (disabled), {@code TWILIO} (send via the firm's own Twilio account), or {@code CLICK_TO_CHAT}
 * (no API — the app opens a pre-filled wa.me link for manual send). {@code authTokenEnc} is AES-GCM
 * ciphertext (see {@code SecretCipher}); the plaintext token is never stored or returned. RLS isolates rows.
 */
@Entity
@Table(name = "tenant_whatsapp_provider")
public class TenantWhatsAppProvider {

    /** How the firm sends WhatsApp messages. */
    public enum Mode { OFF, TWILIO, CLICK_TO_CHAT }

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(nullable = false)
    private String mode = Mode.OFF.name();

    @Column(name = "account_sid")
    private String accountSid;

    @Column(name = "auth_token_enc")
    private String authTokenEnc;

    @Column(name = "from_number")
    private String fromNumber;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantWhatsAppProvider() {
    }

    public TenantWhatsAppProvider(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getTenantId() { return tenantId; }
    public Mode getMode() {
        try {
            return Mode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            return Mode.OFF;
        }
    }
    public void setMode(Mode mode) { this.mode = (mode == null ? Mode.OFF : mode).name(); }
    public String getAccountSid() { return accountSid; }
    public void setAccountSid(String accountSid) { this.accountSid = accountSid; }
    public String getAuthTokenEnc() { return authTokenEnc; }
    public void setAuthTokenEnc(String authTokenEnc) { this.authTokenEnc = authTokenEnc; }
    public String getFromNumber() { return fromNumber; }
    public void setFromNumber(String fromNumber) { this.fromNumber = fromNumber; }
    public Instant getUpdatedAt() { return updatedAt; }
}
