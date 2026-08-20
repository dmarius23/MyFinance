package ro.myfinance.access.application;

import java.util.UUID;
import ro.myfinance.common.security.Role;

/**
 * Port for inviting a user (staff or representative) through the identity provider (Supabase Auth).
 * Implementations create the auth user, attach the tenant/role claims, and trigger the invite email.
 * The returned id becomes the app_user primary key (the future JWT subject), so an invited user who
 * accepts is recognized on first login.
 */
public interface UserInviter {

    InvitedUser invite(String email, InviteClaims claims);

    /**
     * Delete an auth user by its external id — the compensating action when the local persistence that
     * should have followed an {@link #invite} fails, so no orphaned auth user is left behind. Must be
     * <b>idempotent</b>: deleting an already-absent user is a success (the outbox retries this).
     */
    void delete(UUID externalUserId);

    /** {@code companyId} is null for firm staff (admin/employee); set only for representatives. */
    record InviteClaims(UUID tenantId, Role role, UUID companyId) {}

    /** {@code created} is true when this invite created a NEW auth user, false when it reused an
     *  already-registered one — the caller must only compensate-delete users it actually created. */
    record InvitedUser(UUID externalUserId, boolean created) {}
}
