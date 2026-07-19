package ro.myfinance.settings.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.settings.adapter.persistence.GeneralSettingsRepository;
import ro.myfinance.settings.domain.GeneralSettings;

/**
 * Tenant-level general settings. Since tax rates and treasury accounts moved to the global,
 * effective-dated reference tables (V35/V36, managed by SUPER_ADMIN), the only per-tenant setting
 * left here is the outbound sender email. All reads/writes are RLS-scoped; {@code tenant_id} always
 * comes from {@link TenantContext}.
 */
@Service
@Transactional
public class SettingsService {

    private final GeneralSettingsRepository settings;

    public SettingsService(GeneralSettingsRepository settings) {
        this.settings = settings;
    }

    /** Returns the tenant's settings, creating an empty row if none exists yet. */
    public GeneralSettings getSettings() {
        UUID tenantId = currentTenant();
        return settings.findById(tenantId)
                .orElseGet(() -> settings.save(new GeneralSettings(tenantId)));
    }

    /** Update the accounting firm's outbound "From" address (blank clears it). */
    public GeneralSettings updateSenderEmail(String senderEmail) {
        GeneralSettings s = getSettings();
        s.setSenderEmail(senderEmail == null || senderEmail.isBlank() ? null : senderEmail.trim());
        return s;
    }

    /** The accounting firm's outbound From address (null if not configured yet). */
    @Transactional(readOnly = true)
    public String senderEmail() {
        return TenantContext.tenantId().flatMap(settings::findById)
                .map(GeneralSettings::getSenderEmail).orElse(null);
    }

    private UUID currentTenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to request"));
    }
}
