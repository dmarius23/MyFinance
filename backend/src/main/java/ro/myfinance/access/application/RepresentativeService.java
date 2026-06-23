package ro.myfinance.access.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.application.UserInviter.InviteClaims;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.RepresentativeLink;
import ro.myfinance.access.domain.UserStatus;
import ro.myfinance.company.adapter.persistence.CompanyRepository;

/**
 * MOD-02 — managing a company's representatives. Invites go through the {@link UserInviter}
 * port (Supabase or logging fallback). All reads/writes are RLS-scoped to the caller's tenant.
 */
@Service
@Transactional
public class RepresentativeService {

    private final CompanyRepository companies;
    private final AppUserRepository users;
    private final RepresentativeLinkRepository links;
    private final UserInviter inviter;
    private final AuditRecorder audit;

    public RepresentativeService(CompanyRepository companies, AppUserRepository users,
                                 RepresentativeLinkRepository links, UserInviter inviter,
                                 AuditRecorder audit) {
        this.companies = companies;
        this.users = users;
        this.links = links;
        this.inviter = inviter;
        this.audit = audit;
    }

    public AppUser inviteRepresentative(UUID companyId, String email, String name) {
        UUID tenantId = currentTenant();
        companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        if (users.existsByEmail(email)) {
            throw new ConflictException("A user with email " + email + " already exists in this tenant");
        }

        // NOTE: the external invite happens before the local persistence. If a save below fails or
        // the tx rolls back, the Supabase auth user + invite email are NOT rolled back, leaving an
        // orphaned auth user. The existsByEmail pre-check narrows this but doesn't eliminate it.
        // TODO(MOD-02): when the real Supabase adapter is enabled, add a compensating delete (or move
        // to persist-then-invite via the outbox) so a failed persistence can't orphan an auth user.
        var invited = inviter.invite(email, new InviteClaims(tenantId, Role.REPRESENTATIVE, companyId));
        AppUser rep = users.save(new AppUser(invited.externalUserId(), tenantId, email, name, Role.REPRESENTATIVE));
        rep.setStatus(UserStatus.INVITED);
        links.save(new RepresentativeLink(tenantId, rep.getId(), companyId));
        audit.record("REPRESENTATIVE_INVITED", "company", companyId);
        return rep;
    }

    @Transactional(readOnly = true)
    public List<AppUser> listRepresentatives(UUID companyId) {
        companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        List<UUID> userIds = links.findByCompanyId(companyId).stream()
                .map(RepresentativeLink::getUserId).toList();
        return userIds.isEmpty() ? List.of() : users.findAllById(userIds);
    }

    private UUID currentTenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to request"));
    }
}
