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

    /**
     * Assign a representative to a company. If a representative with this email already exists in the
     * tenant they are simply linked to the company (so one person can represent several companies);
     * otherwise a new representative is invited. Re-assigning an existing link is rejected.
     */
    public AppUser inviteRepresentative(UUID companyId, String name, String email, String phone) {
        UUID tenantId = currentTenant();
        companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));

        AppUser existing = users.findByEmail(email).orElse(null);
        if (existing != null) {
            if (existing.getRole() != Role.REPRESENTATIVE) {
                throw new ConflictException("A non-representative user with email " + email + " already exists");
            }
            if (links.existsByUserIdAndCompanyId(existing.getId(), companyId)) {
                throw new ConflictException("This representative is already assigned to the company");
            }
            links.save(new RepresentativeLink(tenantId, existing.getId(), companyId));
            audit.record("REPRESENTATIVE_ASSIGNED", "company", companyId);
            return existing;
        }

        // NOTE: the external invite happens before the local persistence. If a save below fails or
        // the tx rolls back, the Supabase auth user + invite email are NOT rolled back, leaving an
        // orphaned auth user. The findByEmail pre-check narrows this but doesn't eliminate it.
        // TODO(MOD-02): when the real Supabase adapter is enabled, add a compensating delete (or move
        // to persist-then-invite via the outbox) so a failed persistence can't orphan an auth user.
        var invited = inviter.invite(email, new InviteClaims(tenantId, Role.REPRESENTATIVE, companyId));
        AppUser rep = new AppUser(invited.externalUserId(), tenantId, email, name, Role.REPRESENTATIVE);
        rep.setPhone(phone);
        rep.setStatus(UserStatus.INVITED);
        users.save(rep);
        links.save(new RepresentativeLink(tenantId, rep.getId(), companyId));
        audit.record("REPRESENTATIVE_INVITED", "company", companyId);
        return rep;
    }

    /** Update a representative's contact details (must be assigned to the given company). */
    public AppUser updateRepresentative(UUID companyId, UUID userId, String name, String phone) {
        AppUser rep = requireRepOfCompany(companyId, userId);
        rep.setName(name);
        rep.setPhone(phone);
        audit.record("REPRESENTATIVE_UPDATED", "company", companyId);
        return rep;
    }

    /** Activate or deactivate a representative (tenant-wide; a deactivated rep can't use the portal). */
    public AppUser setRepresentativeActive(UUID companyId, UUID userId, boolean active) {
        AppUser rep = requireRepOfCompany(companyId, userId);
        rep.setStatus(active ? UserStatus.ACTIVE : UserStatus.INACTIVE);
        audit.record(active ? "REPRESENTATIVE_ACTIVATED" : "REPRESENTATIVE_DEACTIVATED", "company", companyId);
        return rep;
    }

    /** Remove a representative's assignment to one company (the user and other assignments remain). */
    public void unassignRepresentative(UUID companyId, UUID userId) {
        RepresentativeLink link = links.findByUserIdAndCompanyId(userId, companyId)
                .orElseThrow(() -> new NotFoundException("Representative is not assigned to this company"));
        links.delete(link);
        audit.record("REPRESENTATIVE_UNASSIGNED", "company", companyId);
    }

    /** All representatives for every company in the tenant — one query pair, no N+1. */
    @Transactional(readOnly = true)
    public List<ro.myfinance.access.adapter.web.AllRepresentativesController.CompanyRepEntry> listAllRepresentatives() {
        List<RepresentativeLink> allLinks = links.findAll();
        if (allLinks.isEmpty()) return List.of();
        List<java.util.UUID> userIds = allLinks.stream().map(RepresentativeLink::getUserId).distinct().toList();
        java.util.Map<java.util.UUID, AppUser> byId = users.findAllById(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(AppUser::getId, u -> u));
        return allLinks.stream()
                .filter(l -> byId.containsKey(l.getUserId()))
                .map(l -> {
                    AppUser u = byId.get(l.getUserId());
                    return new ro.myfinance.access.adapter.web.AllRepresentativesController.CompanyRepEntry(
                            l.getCompanyId(), u.getId(), u.getName(), u.getEmail(), u.getStatus().name());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AppUser> listRepresentatives(UUID companyId) {
        companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        List<UUID> userIds = links.findByCompanyId(companyId).stream()
                .map(RepresentativeLink::getUserId).toList();
        return userIds.isEmpty() ? List.of() : users.findAllById(userIds);
    }

    private AppUser requireRepOfCompany(UUID companyId, UUID userId) {
        if (!links.existsByUserIdAndCompanyId(userId, companyId)) {
            throw new NotFoundException("Representative is not assigned to this company");
        }
        return users.findById(userId)
                .orElseThrow(() -> new NotFoundException("Representative not found: " + userId));
    }

    private UUID currentTenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to request"));
    }
}
