package ro.myfinance.tenant.adapter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.access.application.SignInRecorder;
import ro.myfinance.common.email.EmailDeliveryStatus;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.whatsapp.WhatsAppDeliveryStatus;
import ro.myfinance.tenant.application.TenantDirectory;

/**
 * The signed-in user's own tenant (accounting firm) identity + capabilities, for the app shell. Any
 * authenticated tenant user may read it — RLS on {@code tenant} scopes the row to their own tenant
 * ({@code id = app.tenant_id}), so no extra authorization is needed. Returns nulls for a SUPER_ADMIN
 * (no tenant bound); the frontend shows the platform-admin label in that case.
 *
 * <p>{@code emailConfigured} / {@code whatsappConfigured} tell the UI whether the tenant has set up its
 * own SMTP / WhatsApp provider, so it can disable the matching send actions until it has.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final TenantDirectory tenants;
    private final EmailDeliveryStatus emailStatus;
    private final WhatsAppDeliveryStatus whatsappStatus;
    private final SignInRecorder signIns;

    public MeController(TenantDirectory tenants, EmailDeliveryStatus emailStatus,
                        WhatsAppDeliveryStatus whatsappStatus, SignInRecorder signIns) {
        this.tenants = tenants;
        this.emailStatus = emailStatus;
        this.whatsappStatus = whatsappStatus;
        this.signIns = signIns;
    }

    public record MeResponse(String tenantName, String cui, boolean emailConfigured,
                             boolean whatsappConfigured, String whatsappMode) {}

    @GetMapping
    public MeResponse me() {
        // The staff shell calls this once on bootstrap, which makes it the counterpart of
        // PortalService.me() for the rep PWA: the cheapest place to notice that a staff member is actually
        // using the app and promote them off PENDING_INVITE/INVITED. Without it, firm staff stayed
        // "Invitat" on the Team page forever, however long they had been working — only reps were ever
        // promoted, because the recorder was wired solely into the portal.
        TenantContext.current().map(TenantContext.Identity::userId).ifPresent(signIns::recordSignIn);
        boolean emailConfigured = emailStatus.configuredForCurrentTenant();
        boolean whatsappConfigured = whatsappStatus.configuredForCurrentTenant();
        String whatsappMode = whatsappStatus.modeForCurrentTenant().name();
        return tenants.current()
                .map(t -> new MeResponse(t.name(), t.cui(), emailConfigured, whatsappConfigured, whatsappMode))
                .orElse(new MeResponse(null, null, emailConfigured, whatsappConfigured, whatsappMode));
    }
}
