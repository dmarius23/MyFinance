package ro.myfinance.access.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.UserStatus;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.audit.AuditRepository;
import ro.myfinance.common.web.NotFoundException;

/**
 * GDPR subject-rights operations for a platform user (staff or representative). Everything is tenant-scoped
 * by RLS, so a tenant admin can only export/erase users of their own tenant. Erasure <b>anonymizes</b>
 * rather than hard-deletes: the row and its foreign keys (audit trail, links) stay for referential
 * integrity and the statutory accounting-record retention, but the personal data (name, email, phone) is
 * scrubbed and the account is deactivated.
 */
@Service
@Transactional
public class GdprService {

    private final AppUserRepository users;
    private final RepresentativeLinkRepository links;
    private final AuditRepository audit;
    private final AuditRecorder recorder;

    public GdprService(AppUserRepository users, RepresentativeLinkRepository links,
                       AuditRepository audit, AuditRecorder recorder) {
        this.users = users;
        this.links = links;
        this.audit = audit;
        this.recorder = recorder;
    }

    /** Everything the system holds about a user, for a Subject Access Request. */
    public record UserDataExport(Profile user, List<CompanyLink> companyLinks, List<AuditRecord> auditTrail) {
    }

    public record Profile(UUID id, String email, String name, String phone, String role, String status,
                          Instant lastLogin, Instant createdAt) {
    }

    public record CompanyLink(UUID companyId) {
    }

    public record AuditRecord(String action, String entity, UUID entityId, Instant at) {
    }

    @Transactional(readOnly = true)
    public UserDataExport export(UUID userId) {
        AppUser u = requireUser(userId);
        Profile profile = new Profile(u.getId(), u.getEmail(), u.getName(), u.getPhone(),
                u.getRole() == null ? null : u.getRole().name(),
                u.getStatus() == null ? null : u.getStatus().name(),
                u.getLastLogin(), u.getCreatedAt());
        List<CompanyLink> companyLinks = links.findByUserId(userId).stream()
                .map(l -> new CompanyLink(l.getCompanyId())).toList();
        List<AuditRecord> auditTrail = audit.findByActorIdOrderByAtDesc(userId).stream()
                .map(a -> new AuditRecord(a.getAction(), a.getEntity(), a.getEntityId(), a.getAt())).toList();
        return new UserDataExport(profile, companyLinks, auditTrail);
    }

    /** Scrub the user's personal data and deactivate the account (right to erasure, retention-aware). */
    public void anonymize(UUID userId) {
        AppUser u = requireUser(userId);
        u.setName("[erased]");
        // Keep it unique (uq_app_user_email_per_tenant) and non-routable, and don't collide across erasures.
        u.setEmail("erased-" + userId + "@erased.invalid");
        u.setPhone(null);
        u.setStatus(UserStatus.INACTIVE);
        recorder.record("USER_ANONYMIZED", "app_user", userId);
    }

    private AppUser requireUser(UUID userId) {
        // findById is RLS-scoped, so a user in another tenant is simply not found.
        return users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
    }
}
