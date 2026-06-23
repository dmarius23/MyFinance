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

    public AccessService(AppUserRepository users, RepresentativeLinkRepository repLinks) {
        this.users = users;
        this.repLinks = repLinks;
    }

    @Transactional(readOnly = true)
    public List<AppUser> listUsers() {
        return users.findAll();
    }

    /** Invite a user (placeholder — Supabase invite + email is wired in alongside Auth). */
    public AppUser inviteUser(String email, String name, Role role) {
        UUID tenantId = currentTenant();
        // TODO(MOD-02): staff invites should also go through Supabase; until then mint a local id.
        return users.save(new AppUser(UUID.randomUUID(), tenantId, email, name, role));
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
