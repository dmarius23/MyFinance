package ro.myfinance.common.email;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;

/**
 * Whether the current tenant can actually send email — i.e. it has configured and enabled its own SMTP
 * provider ({@link TenantEmailProvider#isSendable()}). Exposed to the app shell (via {@code /api/v1/me})
 * so the UI can disable every email-send action until the firm sets up SMTP. Product rule: each tenant
 * must configure its own SMTP before sending, regardless of any platform-level fallback sender.
 */
@Service
public class EmailDeliveryStatus {

    private final TenantEmailProviderRepository providers;

    public EmailDeliveryStatus(TenantEmailProviderRepository providers) {
        this.providers = providers;
    }

    @Transactional(readOnly = true)
    public boolean configuredForCurrentTenant() {
        return TenantContext.tenantId()
                .flatMap(providers::findById)
                .map(TenantEmailProvider::isSendable)
                .orElse(false);
    }
}
