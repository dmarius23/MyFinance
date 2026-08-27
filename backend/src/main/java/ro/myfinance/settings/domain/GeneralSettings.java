package ro.myfinance.settings.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Tenant-level configuration — one row per tenant, created lazily on first read.
 * {@code tenant_id} is both the PK and the tenant ownership identifier; RLS enforces isolation.
 *
 * <p>Tax rates used to live here but were moved to the global, effective-dated
 * {@code platform_tax_rate} table (V35/V36) — the only genuinely per-tenant setting left is the
 * outbound sender email. See {@code MyFinance-global-reference-settings-design-v1.md}.
 */
@Entity
@Table(name = "general_settings")
public class GeneralSettings {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** The accounting firm's outbound "From" email for all client emails. */
    @Column(name = "sender_email")
    private String senderEmail;

    /** Whether the nightly automatic Drive sync runs for this tenant. */
    @Column(name = "auto_sync_enabled", nullable = false)
    private boolean autoSyncEnabled = true;

    /** Local hour (0–23, Europe/Bucharest) at which the automatic sync runs. */
    @Column(name = "auto_sync_hour", nullable = false)
    private int autoSyncHour = 2;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GeneralSettings() {
    }

    public GeneralSettings(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getTenantId() { return tenantId; }
    public String getSenderEmail() { return senderEmail; }
    public void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }
    public boolean isAutoSyncEnabled() { return autoSyncEnabled; }
    public void setAutoSyncEnabled(boolean autoSyncEnabled) { this.autoSyncEnabled = autoSyncEnabled; }
    public int getAutoSyncHour() { return autoSyncHour; }
    public void setAutoSyncHour(int autoSyncHour) { this.autoSyncHour = autoSyncHour; }
    public Instant getUpdatedAt() { return updatedAt; }
}
