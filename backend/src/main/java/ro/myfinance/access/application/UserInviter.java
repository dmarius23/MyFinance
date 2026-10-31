package ro.myfinance.access.application;

import java.util.UUID;
import ro.myfinance.common.security.Role;

/**
 * Port for inviting a user (staff or representative) through the identity provider (Supabase Auth).
 * Implementations create the auth user and attach the tenant/role claims. The returned id becomes the
 * app_user primary key (the future JWT subject), so an invited user who accepts is recognized on first login.
 *
 * <p><b>The identity provider never sends our emails.</b> There is deliberately no "invite" operation
 * here: the provider's own invite email carries a {@code type=invite} link, and supabase-js raises
 * PASSWORD_RECOVERY only for {@code type=recovery}, so such a link signs the invitee straight into the app
 * without ever asking for a password — locking them out at the next sign-in. Every invitation is therefore
 * {@link #provision} + {@link #generateSetPasswordLink}, delivered as our own branded email.
 */
public interface UserInviter {

    /**
     * Create the auth user with the tenant/role/company claims WITHOUT sending any email — the application
     * delivers its own branded invitation (see {@link #generateSetPasswordLink}). This also keeps bulk
     * onboarding (CSV import) clear of the provider's per-email rate limit, which would otherwise drop all
     * but the first representative. Reuses an already-registered auth user (returning {@code created=false}).
     */
    InvitedUser provision(String email, InviteClaims claims);

    /**
     * Generate a "set your password" (recovery) action link for an existing user <b>without sending any
     * email</b>, so the application can deliver its own MyFinance- and firm-branded invitation (through the
     * tenant's own email provider) instead of the identity provider's generic reset-password message. The
     * link opens the app ({@code redirect_to=<appUrl>}). Safe to call repeatedly; each call mints a fresh link.
     */
    String generateSetPasswordLink(String email);

    /**
     * Change an existing auth user's email (admin-set, already-confirmed). Used when an accountant corrects
     * a representative's email so their login identity matches the app.
     */
    void updateEmail(UUID externalUserId, String newEmail);

    /**
     * Delete an auth user by its external id — the compensating action when the local persistence that
     * should have followed a {@link #provision} fails, so no orphaned auth user is left behind. Must be
     * <b>idempotent</b>: deleting an already-absent user is a success (the outbox retries this).
     */
    void delete(UUID externalUserId);

    /** {@code companyId} is null for firm staff (admin/employee); set only for representatives. */
    record InviteClaims(UUID tenantId, Role role, UUID companyId) {}

    /** {@code created} is true when this call created a NEW auth user, false when it reused an
     *  already-registered one — the caller must only compensate-delete users it actually created. */
    record InvitedUser(UUID externalUserId, boolean created) {}
}
