package ro.myfinance.access.application;

import java.util.UUID;
import ro.myfinance.common.security.Role;

/**
 * Port for inviting a representative through the identity provider (Supabase Auth). Implementations
 * create the auth user, attach the tenant claims, and trigger the invite email. The returned id
 * becomes the app_user primary key (the future JWT subject).
 */
public interface RepresentativeInviter {

    InvitedUser invite(String email, InviteClaims claims);

    record InviteClaims(UUID tenantId, Role role, UUID companyId) {}

    record InvitedUser(UUID externalUserId) {}
}
