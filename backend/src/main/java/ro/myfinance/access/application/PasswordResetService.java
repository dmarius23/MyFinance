package ro.myfinance.access.application;

import java.util.List;
import org.springframework.stereotype.Service;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.email.EmailSender;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;

/**
 * Self-service password reset, delivered by US rather than by the identity provider.
 *
 * <p>Why not {@code supabase.auth.resetPasswordForEmail()} from the browser: that sends the provider's
 * generic template from a generic address. Every other message this product sends to a client comes from
 * the accounting firm's own sender through the firm's own provider, and a password reset — one of the few
 * emails a user is actively waiting for — should not be the exception.
 *
 * <p>Flow, mirroring the public Gmail webhook: resolve the email to its tenant through
 * {@link AuthUserDirectory} (the one deliberate RLS exception), bind {@code TenantContext}, then do
 * everything else under normal RLS. Binding the tenant is what makes {@code TenantAwareEmailSender} pick
 * the firm's SMTP — without it the mail would silently fall back to the platform sender.
 *
 * <p><b>This is reached from a PUBLIC endpoint, so it never throws and never reports what it found.</b>
 * An unknown address, a tenant with no provider configured, a dead SMTP host — all look identical from
 * outside. That is deliberate: a reset endpoint that distinguishes them is an account-enumeration oracle.
 * Failures are logged (address masked) for operators instead.
 */
@Service
public class PasswordResetService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PasswordResetService.class);

    private final AuthUserDirectory directory;
    private final UserInviter inviter;
    private final EmailEnvelopeService envelope;
    private final EmailSender emailSender;
    private final AuditRecorder audit;

    public PasswordResetService(AuthUserDirectory directory, UserInviter inviter,
                                EmailEnvelopeService envelope, EmailSender emailSender,
                                AuditRecorder audit) {
        this.directory = directory;
        this.inviter = inviter;
        this.envelope = envelope;
        this.emailSender = emailSender;
        this.audit = audit;
    }

    /** Mail a fresh set-password link, if that address belongs to an active user. Never throws. */
    public void requestReset(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            return;
        }
        String email = rawEmail.trim();

        List<AuthUserDirectory.TenantUser> matches;
        try {
            matches = directory.findByEmailAcrossTenants(email);
        } catch (RuntimeException e) {
            log.warn("Password-reset lookup failed for {}: {}", mask(email), e.getMessage());
            return;
        }
        if (matches.isEmpty()) {
            // Not an error, and not reported: the caller gets the same 204 either way.
            log.info("Password-reset requested for an unknown address {}", mask(email));
            return;
        }

        for (AuthUserDirectory.TenantUser match : matches) {
            try {
                // TENANT_ADMIN is the identity used to act within the resolved tenant (same choice the
                // Gmail webhook makes); this service only ever reads settings and sends one email.
                TenantContext.set(new TenantContext.Identity(
                        match.tenantId(), match.userId(), Role.TENANT_ADMIN, null));
                send(email);
                audit.record("PASSWORD_RESET_REQUESTED", "user", match.userId());
            } catch (RuntimeException e) {
                log.warn("Password-reset email failed for {} in tenant {}: {}",
                        mask(email), match.tenantId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /** Mint the recovery link and deliver it from the firm's sender — must run with a tenant bound. */
    private void send(String email) {
        String firmName = envelope.firmName();
        String link = inviter.generateSetPasswordLink(email);
        EmailEnvelopeService.Envelope env = envelope.system(email);
        String fromName = (firmName != null && !firmName.isBlank()) ? firmName : "MyFinance";
        emailSender.send(new EmailSender.Message(
                fromName, env.fromEmail(), email,
                PasswordResetMessageFactory.subject(firmName),
                PasswordResetMessageFactory.body(firmName, null, link),
                List.of()));
    }

    /** Never log a full address (PII): keep the first character and the domain. */
    private static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
