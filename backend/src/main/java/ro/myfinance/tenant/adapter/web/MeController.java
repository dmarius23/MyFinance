package ro.myfinance.tenant.adapter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.common.email.EmailDeliveryStatus;
import ro.myfinance.tenant.application.TenantDirectory;

/**
 * The signed-in user's own tenant (accounting firm) identity + capabilities, for the app shell. Any
 * authenticated tenant user may read it — RLS on {@code tenant} scopes the row to their own tenant
 * ({@code id = app.tenant_id}), so no extra authorization is needed. Returns nulls for a SUPER_ADMIN
 * (no tenant bound); the frontend shows the platform-admin label in that case.
 *
 * <p>{@code emailConfigured} tells the UI whether the tenant has set up its own SMTP provider, so it can
 * disable every email-send action until it has (the firm must configure SMTP before sending).
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final TenantDirectory tenants;
    private final EmailDeliveryStatus emailStatus;

    public MeController(TenantDirectory tenants, EmailDeliveryStatus emailStatus) {
        this.tenants = tenants;
        this.emailStatus = emailStatus;
    }

    public record MeResponse(String tenantName, String cui, boolean emailConfigured) {}

    @GetMapping
    public MeResponse me() {
        boolean emailConfigured = emailStatus.configuredForCurrentTenant();
        return tenants.current()
                .map(t -> new MeResponse(t.name(), t.cui(), emailConfigured))
                .orElse(new MeResponse(null, null, emailConfigured));
    }
}
