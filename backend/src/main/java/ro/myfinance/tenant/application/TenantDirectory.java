package ro.myfinance.tenant.application;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.tenant.adapter.persistence.TenantRepository;
import ro.myfinance.tenant.domain.Tenant;

/**
 * Read-only access to the current tenant's plan limits, exposed to other modules (e.g. {@code intake})
 * so they can enforce quotas without reaching into the {@code tenant} persistence adapter. Limits live in
 * the tenant's {@code limits} jsonb map; a missing key means "no limit".
 */
@Service
@Transactional(readOnly = true)
public class TenantDirectory {

    private final TenantRepository tenants;

    public TenantDirectory(TenantRepository tenants) {
        this.tenants = tenants;
    }

    /**
     * Max documents allowed per company and period-month for the current tenant, or {@code -1} (no limit)
     * when the {@code maxDocumentsPerCompanyMonth} key is absent, blank or non-positive.
     */
    public long maxDocumentsPerCompanyMonth() {
        return limitValue("maxDocumentsPerCompanyMonth");
    }

    /** The current tenant's display identity (name + CUI) for the app shell, or empty for a SUPER_ADMIN. */
    public java.util.Optional<CurrentTenant> current() {
        return TenantContext.tenantId()
                .flatMap(tenants::findById)
                .map(t -> new CurrentTenant(t.getName(), t.getCui()));
    }

    /** The signed-in user's own accounting firm identity. */
    public record CurrentTenant(String name, String cui) {
    }

    private long limitValue(String key) {
        return TenantContext.tenantId()
                .flatMap(tenants::findById)
                .map(Tenant::getLimits)
                .map(limits -> asPositiveLong(limits.get(key)))
                .orElse(-1L);
    }

    /** A limits value coerced to a positive long, or -1 when absent/blank/zero/negative/non-numeric. */
    private static long asPositiveLong(Object value) {
        if (value instanceof Number n) {
            long l = n.longValue();
            return l > 0 ? l : -1L;
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                long l = Long.parseLong(s.trim());
                return l > 0 ? l : -1L;
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
        return -1L;
    }

    // Visible for testing: the coercion rules without a database.
    static long coerce(Map<String, Object> limits, String key) {
        return asPositiveLong(limits == null ? null : limits.get(key));
    }
}
