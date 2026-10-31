package ro.myfinance.access.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.email.EmailSender;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AccessService.class);

    private final AppUserRepository users;
    private final RepresentativeLinkRepository repLinks;
    private final UserInviter inviter;
    private final AuthUserCleanup authCleanup;
    private final AuditRecorder audit;
    private final EmailEnvelopeService envelope;
    private final EmailSender emailSender;

    public AccessService(AppUserRepository users, RepresentativeLinkRepository repLinks, UserInviter inviter,
                         AuthUserCleanup authCleanup, AuditRecorder audit,
                         EmailEnvelopeService envelope, EmailSender emailSender) {
        this.users = users;
        this.repLinks = repLinks;
        this.inviter = inviter;
        this.authCleanup = authCleanup;
        this.audit = audit;
        this.envelope = envelope;
        this.emailSender = emailSender;
    }

    @Transactional(readOnly = true)
    public List<AppUser> listUsers() {
        return users.findAll();
    }

    /** Firm-staff directory (admins + accountants) for assignee pickers — readable by any staff member. */
    @Transactional(readOnly = true)
    public List<AppUser> listStaff() {
        return users.findByRoleIn(List.of(Role.TENANT_ADMIN, Role.EMPLOYEE));
    }

    /**
     * Invite a firm-staff user (admin or accountant). Mirrors the representative flow exactly: PROVISION
     * the Supabase auth user with the tenant + role claims (no identity-provider email), then send OUR
     * branded invitation carrying a <b>recovery</b> ("set your password") link. The returned id becomes the
     * {@code app_user} primary key, so the invitee is recognized on first login.
     *
     * <p>The link type matters: the identity provider's own {@code /auth/v1/invite} email produces a
     * {@code type=invite} link, and supabase-js raises PASSWORD_RECOVERY only for {@code type=recovery} —
     * an invite link would sign the user straight into the app, leaving them with no password they know and
     * locked out at the next sign-in. Only ever mint staff links via
     * {@link UserInviter#generateSetPasswordLink}.
     *
     * <p>Atomicity is by compensation, as for representatives: if the local write fails we durably schedule
     * deletion of the auth user we just created, so none is orphaned.
     */
    public AppUser inviteUser(String email, String name, Role role) {
        if (role != Role.TENANT_ADMIN && role != Role.EMPLOYEE) {
            throw new IllegalArgumentException("Staff invites must be administrator or accountant");
        }
        UUID tenantId = currentTenant();
        if (users.existsByEmail(email)) {
            throw new ConflictException("A user with email " + email + " already exists in this tenant");
        }
        var invited = inviter.provision(email, new UserInviter.InviteClaims(tenantId, role, null));
        AppUser user;
        try {
            user = users.save(new AppUser(invited.externalUserId(), tenantId, email, name, role));
            // Start as PENDING_INVITE and promote to INVITED only once an email has actually gone out, so
            // the status never claims we contacted someone we didn't.
            user.setStatus(UserStatus.PENDING_INVITE);
        } catch (RuntimeException e) {
            // Only compensate for an auth user WE created — never delete a pre-existing one we reused.
            if (invited.created()) {
                try {
                    authCleanup.scheduleDelete(invited.externalUserId());
                } catch (RuntimeException cleanupEx) {
                    log.error("Persist failed AND could not schedule cleanup of orphaned auth user {}",
                            invited.externalUserId(), cleanupEx);
                }
            }
            throw e;
        }

        // Best-effort: a delivery problem must not undo the staff member we just created — it's
        // recoverable with the manual "resend invite" action, and they stay PENDING_INVITE so they still
        // show up as needing one.
        try {
            sendBrandedInvite(user.getEmail(), user.getName());
            user.setStatus(UserStatus.INVITED);
        } catch (RuntimeException mailEx) {
            log.warn("Staff user {} created but the invitation email failed to send: {}",
                    user.getId(), mailEx.getMessage());
        }
        return user;
    }

    /**
     * On-demand "Resend invite": email a staff member a fresh set-password link. This is the only way back
     * in for someone whose link expired, who never received it, or who predates the branded-link fix — the
     * login screen's self-service reset being the other. Sent synchronously so the admin sees
     * success/failure immediately; audited only after a successful send.
     */
    public void sendInvite(UUID userId) {
        AppUser user = requireStaff(userId);
        sendBrandedInvite(user.getEmail(), user.getName());
        // The deferred invite has now been delivered, so clear the "pending" marker. Only PENDING_INVITE is
        // promoted — re-sending a link to someone who already signed in must not knock them back to INVITED.
        if (user.getStatus() == UserStatus.PENDING_INVITE) {
            user.setStatus(UserStatus.INVITED);
        }
        audit.record("STAFF_INVITE_SENT", "user", userId);
    }

    /**
     * Send the MyFinance- and firm-branded invitation email (a "set your password" link) to a staff member,
     * through the tenant's own email provider. Throws on a delivery failure so the caller can decide
     * whether to surface it (manual resend) or swallow it (best-effort during creation).
     */
    private void sendBrandedInvite(String email, String name) {
        String firmName = envelope.firmName();
        String link = inviter.generateSetPasswordLink(email);

        // From: firm name (display) + the firm's configured sender address; recipient = the staff member.
        // Staff belong to the firm itself, so there is no client company to name.
        EmailEnvelopeService.Envelope env = envelope.system(email);
        String fromName = (firmName != null && !firmName.isBlank()) ? firmName : "MyFinance";
        emailSender.send(new EmailSender.Message(
                fromName, env.fromEmail(), email,
                InviteMessageFactory.subject(firmName),
                InviteMessageFactory.body(firmName, null, name, link),
                List.of()));
    }

    /** Resolve a firm-staff user in the caller's tenant; representatives are handled per-company. */
    private AppUser requireStaff(UUID userId) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        if (user.getRole() != Role.TENANT_ADMIN && user.getRole() != Role.EMPLOYEE) {
            throw new IllegalArgumentException("Not a firm-staff user: " + userId);
        }
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
