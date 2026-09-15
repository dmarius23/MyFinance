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
}
