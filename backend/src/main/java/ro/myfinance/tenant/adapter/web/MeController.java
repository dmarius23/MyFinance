package ro.myfinance.tenant.adapter.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.tenant.adapter.persistence.TenantRepository;

/**
 * The signed-in user's own tenant (accounting firm) identity, for the app shell to display. Any
 * authenticated tenant user may read it — RLS on {@code tenant} scopes the row to their own tenant
 * ({@code id = app.tenant_id}), so no extra authorization is needed. Returns nulls for a SUPER_ADMIN
 * (no tenant bound); the frontend shows the platform-admin label in that case.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final TenantRepository tenants;

    public MeController(TenantRepository tenants) {
        this.tenants = tenants;
    }

    public record MeResponse(String tenantName, String cui) {}

    @GetMapping
    public MeResponse me() {
        return TenantContext.tenantId()
                .flatMap(tenants::findById)
                .map(t -> new MeResponse(t.getName(), t.getCui()))
                .orElse(new MeResponse(null, null));
    }
}
