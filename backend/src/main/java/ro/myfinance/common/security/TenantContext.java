package ro.myfinance.common.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Request-scoped tenant/identity context, bound to the current thread.
 *
 * <p>Populated by {@link TenantContextFilter} from the validated Supabase JWT and read by
 * {@link RlsConnectionProvider} when handing out DB connections, so PostgreSQL row-level
 * security is enforced even if application code forgets a {@code tenant_id} filter.
 *
 * <p>Golden rule: when no tenant is bound, RLS sees an empty {@code app.tenant_id} and returns
 * zero rows — fail closed, never fail open.
 */
public final class TenantContext {

    /** Identity resolved for the current request/transaction. */
    public record Identity(UUID tenantId, UUID userId, Role role, UUID companyId) {}

    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Identity identity) {
        CURRENT.set(identity);
    }

    public static Optional<Identity> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static Optional<UUID> tenantId() {
        return current().map(Identity::tenantId);
    }

    public static Optional<UUID> companyId() {
        return current().map(Identity::companyId);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
