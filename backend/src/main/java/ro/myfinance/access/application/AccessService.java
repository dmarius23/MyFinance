package ro.myfinance.access.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.RepresentativeLink;
import ro.myfinance.access.domain.UserStatus;

/**
 * MOD-02 — users, roles, representative links. All reads/writes are RLS-scoped to the caller's
 * tenant; this service never accepts a tenant id from the client.
 */
@Service
@Transactional
public class AccessService {

    private final AppUserRepository users;
    private final RepresentativeLinkRepository repLinks;
    private final UserInviter inviter;

    public AccessService(AppUserRepository users, RepresentativeLinkRepository repLinks, UserInviter inviter) {
        this.users = users;
        this.repLinks = repLinks;
        this.inviter = inviter;
    }

    @Transactional(readOnly = true)
    public List<AppUser> listUsers() {
        return users.findAll();
    }

    /**
     * Invite a firm-staff user (admin or accountant). Creates the Supabase auth user with tenant + role
     * claims and triggers the invite email; the returned id becomes the {@code app_user} primary key, so
     * the invitee is recognized on first login. Representatives are invited via {@link RepresentativeService}.
     */
    public AppUser inviteUser(String email, String name, Role role) {
        if (role != Role.TENANT_ADMIN && role != Role.EMPLOYEE) {
            throw new IllegalArgumentException("Staff invites must be administrator or accountant");
        }
        UUID tenantId = currentTenant();
        if (users.existsByEmail(email)) {
            throw new ConflictException("A user with email " + email + " already exists in this tenant");
        }
        // NOTE: same caveat as representative invites — the external auth user is created before local
        // persistence, so a failed save can orphan a Supabase user (narrowed by the existsByEmail check).
        var invited = inviter.invite(email, new UserInviter.InviteClaims(tenantId, role, null));
        AppUser user = users.save(new AppUser(invited.externalUserId(), tenantId, email, name, role));
        user.setStatus(UserStatus.INVITED);
        return user;
    }

    public AppUser setRole(UUID userId, Role role) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        user.setRole(role);
        return user;
    }

    public AppUser deactivate(UUID userId) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        if (user.getRole() == Role.TENANT_ADMIN
                && users.countByRoleAndStatus(Role.TENANT_ADMIN, UserStatus.ACTIVE) <= 1) {
            throw new ConflictException("Cannot deactivate the last active tenant admin");
        }
        user.setStatus(UserStatus.INACTIVE);
        return user;
    }

    public AppUser activate(UUID userId) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    public RepresentativeLink linkRepresentative(UUID userId, UUID companyId) {
        return repLinks.save(new RepresentativeLink(currentTenant(), userId, companyId));
    }

    private UUID currentTenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to request"));
    }
}
