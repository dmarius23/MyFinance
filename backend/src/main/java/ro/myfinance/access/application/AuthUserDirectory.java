package ro.myfinance.access.application;

import java.util.List;
import java.util.UUID;

/**
 * Resolves an email address to the tenant(s) that own it, for the ONE flow that has no tenant to start
 * from: self-service password reset. The caller is unauthenticated and locked out, so there is no JWT and
 * therefore no {@code TenantContext} — and with no tenant bound, RLS correctly returns zero rows for every
 * tenant table. Something has to cross that boundary once, deliberately and in a single named place.
 *
 * <p><b>This is a deliberate, audited exception to golden rule #1</b> (RLS is the tenant boundary), modelled
 * on how the public Gmail Pub/Sub webhook resolves a mailbox to its tenant. Keep it minimal:
 * <ul>
 *   <li>It returns <b>identifiers only</b> — never names, roles, or any other business data.</li>
 *   <li>Callers must immediately bind {@code TenantContext} and do all remaining work under normal RLS.</li>
 *   <li>Its result must never reach an HTTP response: that would leak whether an address exists.</li>
 * </ul>
 * Do not add methods here for convenience. Anything that has a tenant already belongs in
 * {@code AppUserRepository}, under RLS.
 */
public interface AuthUserDirectory {

    /**
     * Active (non-deactivated) users with this email, with the tenant that owns each. Normally empty or a
     * single element — an email maps to one Supabase auth user, and {@code app_user.id} IS that auth user
     * id, so two rows sharing an email would collide on the primary key. The list shape is defensive.
     *
     * <p>Deactivated users are excluded: someone who has been switched off must not be able to reset their
     * way back in.
     */
    List<TenantUser> findByEmailAcrossTenants(String email);

    /** Identifiers only — deliberately carries no business data across the tenant boundary. */
    record TenantUser(UUID userId, UUID tenantId) {
    }
}
