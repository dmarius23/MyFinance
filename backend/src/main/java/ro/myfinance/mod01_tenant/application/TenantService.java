package ro.myfinance.mod01_tenant.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.mod01_tenant.adapter.persistence.TenantRepository;
import ro.myfinance.mod01_tenant.domain.Tenant;
import ro.myfinance.mod01_tenant.domain.TenantStatus;

/**
 * MOD-01 tenant administration. SUPER_ADMIN only (enforced at the web layer). Suspending a tenant
 * must also pause its scheduled jobs + email agent — wired in MOD-09/worker (TODO below).
 */
@Service
@Transactional
public class TenantService {

    private final TenantRepository tenants;

    public TenantService(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Transactional(readOnly = true)
    public List<Tenant> list() {
        return tenants.findAll();
    }

    @Transactional(readOnly = true)
    public Tenant get(UUID id) {
        return tenants.findById(id)
                .orElseThrow(() -> new NotFoundException("Tenant not found: " + id));
    }

    public Tenant create(String name, String cui, String plan) {
        return tenants.save(new Tenant(name, cui, plan));
    }

    public Tenant changeStatus(UUID id, TenantStatus status) {
        Tenant tenant = get(id);
        tenant.setStatus(status);
        // TODO(MOD-09): when SUSPENDED/ARCHIVED, pause scheduled jobs + email ingestion for this tenant.
        return tenant;
    }
}
