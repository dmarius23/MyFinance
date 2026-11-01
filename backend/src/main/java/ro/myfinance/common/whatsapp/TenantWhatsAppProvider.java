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
 * {@code OFF} (disabled), {@code TWILIO} (send via the firm's own Twilio account), {@code META_CLOUD_API}
 * (tenant-owned Meta Business account), or {@code CLICK_TO_CHAT} (no API — the app opens a pre-filled
 * wa.me link for manual send). Provider credentials are AES-GCM ciphertext (see {@code SecretCipher}); the
 * plaintext tokens are never stored or returned. RLS isolates rows.
 */
@Entity
@Table(name = "tenant_whatsapp_provider")
public class TenantWhatsAppProvider {

    /** How the firm sends WhatsApp messages. */
    public enum Mode { OFF, TWILIO, META_CLOUD_API, CLICK_TO_CHAT }

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

    @Column(name = "meta_business_portfolio_id")
    private String metaBusinessPortfolioId;

    @Column(name = "meta_waba_id")
    private String metaWabaId;

    @Column(name = "meta_phone_number_id")
    private String metaPhoneNumberId;

    @Column(name = "meta_access_token_enc")
    private String metaAccessTokenEnc;

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
    public String getMetaBusinessPortfolioId() { return metaBusinessPortfolioId; }
    public void setMetaBusinessPortfolioId(String id) { this.metaBusinessPortfolioId = id; }
    public String getMetaWabaId() { return metaWabaId; }
    public void setMetaWabaId(String id) { this.metaWabaId = id; }
    public String getMetaPhoneNumberId() { return metaPhoneNumberId; }
    public void setMetaPhoneNumberId(String id) { this.metaPhoneNumberId = id; }
    public String getMetaAccessTokenEnc() { return metaAccessTokenEnc; }
    public void setMetaAccessTokenEnc(String token) { this.metaAccessTokenEnc = token; }
    public Instant getUpdatedAt() { return updatedAt; }

    /**
     * Whether this tenant can send WhatsApp: TWILIO needs account SID + auth token + sender number;
     * CLICK_TO_CHAT is always usable (a manual wa.me link needs no credentials); OFF cannot send.
     * Drives the UI gate on WhatsApp-send actions, mirroring {@code TenantEmailProvider.isSendable()}.
     */
    public boolean isSendable() {
        return switch (getMode()) {
            case TWILIO -> notBlank(accountSid) && notBlank(authTokenEnc) && notBlank(fromNumber);
            case META_CLOUD_API -> notBlank(metaWabaId) && notBlank(metaPhoneNumberId)
                    && notBlank(metaAccessTokenEnc);
            case CLICK_TO_CHAT -> true;
            case OFF -> false;
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
