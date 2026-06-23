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

    /** {@code companyId} is null for firm staff (admin/employee); set only for representatives. */
    record InviteClaims(UUID tenantId, Role role, UUID companyId) {}

    record InvitedUser(UUID externalUserId) {}
}
