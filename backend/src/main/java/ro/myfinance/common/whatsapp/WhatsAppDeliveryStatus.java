package ro.myfinance.common.whatsapp;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;

/**
 * Whether the current tenant can send WhatsApp — i.e. it has configured a usable provider
 * ({@link TenantWhatsAppProvider#isSendable()}). Exposed via {@code /api/v1/me} so the UI can disable
 * WhatsApp-send actions until the firm sets it up, mirroring {@code EmailDeliveryStatus}.
 */
@Service
public class WhatsAppDeliveryStatus {

    private final TenantWhatsAppProviderRepository providers;

    public WhatsAppDeliveryStatus(TenantWhatsAppProviderRepository providers) {
        this.providers = providers;
    }

    @Transactional(readOnly = true)
    public boolean configuredForCurrentTenant() {
        return TenantContext.tenantId()
                .flatMap(providers::findById)
                .map(TenantWhatsAppProvider::isSendable)
                .orElse(false);
    }

    /**
     * The current tenant's WhatsApp mode ({@code OFF} / {@code TWILIO} / {@code CLICK_TO_CHAT}) — the UI
     * branches on it: CLICK_TO_CHAT opens a wa.me deep link instead of sending through the backend.
     */
    @Transactional(readOnly = true)
    public TenantWhatsAppProvider.Mode modeForCurrentTenant() {
        return TenantContext.tenantId()
                .flatMap(providers::findById)
                .map(TenantWhatsAppProvider::getMode)
                .orElse(TenantWhatsAppProvider.Mode.OFF);
    }
}
