package ro.myfinance.settings.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.crypto.SecretCipher;
import ro.myfinance.common.email.EmailSender;
import ro.myfinance.common.email.TenantAwareEmailSender;
import ro.myfinance.common.email.TenantEmailProvider;
import ro.myfinance.common.email.TenantEmailProviderRepository;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.whatsapp.TenantWhatsAppProvider;
import ro.myfinance.common.whatsapp.TenantWhatsAppProviderRepository;

/**
 * Per-tenant messaging provider settings (email SMTP + WhatsApp), managed by the firm admin. Secrets (SMTP
 * password, WhatsApp auth token) are AES-GCM encrypted on save and never returned to the client. On a
 * password update, {@code null} means "keep the existing secret", blank means "clear it", any other value
 * replaces it. All reads/writes are RLS-scoped to the current tenant.
 */
@Service
@Transactional
public class MessagingSettingsService {

    private final TenantEmailProviderRepository emailRepo;
    private final TenantWhatsAppProviderRepository whatsappRepo;
    private final SecretCipher cipher;
    private final TenantAwareEmailSender emailSender;

    public MessagingSettingsService(TenantEmailProviderRepository emailRepo,
                                    TenantWhatsAppProviderRepository whatsappRepo,
                                    SecretCipher cipher, TenantAwareEmailSender emailSender) {
        this.emailRepo = emailRepo;
        this.whatsappRepo = whatsappRepo;
        this.cipher = cipher;
        this.emailSender = emailSender;
    }

    @Transactional(readOnly = true)
    public TenantEmailProvider getEmail() {
        return emailRepo.findById(tenant()).orElseGet(() -> new TenantEmailProvider(tenant()));
    }

    public void updateEmail(boolean enabled, String fromEmail, String fromName, String host, Integer port,
                            String username, String password) {
        requireCipherWhenSecret(password);
        TenantEmailProvider p = emailRepo.findById(tenant()).orElseGet(() -> new TenantEmailProvider(tenant()));
        p.setEnabled(enabled);
        p.setFromEmail(trimToNull(fromEmail));
        p.setFromName(trimToNull(fromName));
        p.setSmtpHost(trimToNull(host));
        p.setSmtpPort(port);
        p.setSmtpUsername(trimToNull(username));
        if (password != null) { // null = keep existing; blank = clear; else replace
            p.setSmtpPasswordEnc(password.isBlank() ? null : cipher.encrypt(password));
        }
        emailRepo.save(p);
        emailSender.evict(tenant()); // rebuild the cached JavaMailSender from the new config
    }

    @Transactional(readOnly = true)
    public TenantWhatsAppProvider getWhatsApp() {
        return whatsappRepo.findById(tenant()).orElseGet(() -> new TenantWhatsAppProvider(tenant()));
    }

    public void updateWhatsApp(TenantWhatsAppProvider.Mode mode, String accountSid, String authToken,
                               String fromNumber) {
        requireCipherWhenSecret(authToken);
        TenantWhatsAppProvider p = whatsappRepo.findById(tenant())
                .orElseGet(() -> new TenantWhatsAppProvider(tenant()));
        p.setMode(mode);
        p.setAccountSid(trimToNull(accountSid));
        p.setFromNumber(trimToNull(fromNumber));
        if (authToken != null) {
            p.setAuthTokenEnc(authToken.isBlank() ? null : cipher.encrypt(authToken));
        }
        whatsappRepo.save(p);
    }

    /** Send a test email to the given address using the tenant's configured provider — immediate (not queued). */
    public void sendTestEmail(String toEmail) {
        TenantEmailProvider p = getEmail();
        if (!p.isSendable()) {
            // The tenant's own config, fixable from Settings → 409, not a server fault.
            throw new ro.myfinance.common.web.ConflictException(
                    "Configure and enable an email provider (host + from address) first");
        }
        String fromName = (p.getFromName() == null || p.getFromName().isBlank()) ? "MyFinance" : p.getFromName();
        emailSender.send(EmailSender.Message.of(fromName, p.getFromEmail(), toEmail,
                "MyFinance — test email",
                "This is a test message from MyFinance confirming your email (SMTP) settings work."));
    }

    /**
     * Provider secrets are stored AES-GCM-encrypted, so a missing master key must fail before we persist
     * anything. This is a deployment gap, not user error — surface it as 503 naming the setting, so the
     * operator sees what to fix instead of an opaque 500.
     */
    private void requireCipherWhenSecret(String secret) {
        if (secret != null && !secret.isBlank() && !cipher.isConfigured()) {
            throw new ro.myfinance.common.web.MisconfiguredException(
                    "Server secret key (MYFINANCE_SECRET_KEY) is not configured — cannot store provider credentials");
        }
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private UUID tenant() {
        return TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound to request"));
    }
}
