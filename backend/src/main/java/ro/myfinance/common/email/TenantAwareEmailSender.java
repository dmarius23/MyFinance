package ro.myfinance.common.email;

import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.crypto.SecretCipher;
import ro.myfinance.common.security.TenantContext;

/**
 * The primary {@link EmailSender}: routes every send through the CURRENT tenant's own SMTP provider when
 * they've configured one ({@link TenantEmailProvider#isSendable()}), otherwise delegates to the platform's
 * global sender (SES/SMTP/logging). The tenant is taken from {@link TenantContext} — the outbox relay binds
 * it before delivering, so per-tenant routing works from the async relay too. Each tenant's
 * {@link JavaMailSender} is cached and rebuilt when its config changes; the SMTP password is decrypted only
 * here, in-memory, at send time.
 */
@Component
@Primary
public class TenantAwareEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareEmailSender.class);

    private final TenantEmailProviderRepository providers;
    private final SecretCipher cipher;
    private final ObjectProvider<EmailSender> allSenders; // includes self; self is filtered out for fallback
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public TenantAwareEmailSender(TenantEmailProviderRepository providers, SecretCipher cipher,
                                  ObjectProvider<EmailSender> allSenders) {
        this.providers = providers;
        this.cipher = cipher;
        this.allSenders = allSenders;
    }

    @Override
    @Transactional(readOnly = true)
    public void send(Message message) {
        UUID tenant = TenantContext.tenantId().orElse(null);
        TenantEmailProvider p = tenant == null ? null : providers.findById(tenant).orElse(null);
        if (p == null || !p.isSendable()) {
            fallback().send(message); // firm hasn't configured a provider → platform default
            return;
        }
        // SMTP providers require the envelope From to be the authenticated identity → use the provider's
        // from address; keep the human display name from the message (the logged-in staff member).
        String fromName = (message.fromName() != null && !message.fromName().isBlank())
                ? message.fromName()
                : (p.getFromName() == null ? "" : p.getFromName());
        Message toSend = new Message(fromName, p.getFromEmail(), message.to(), message.subject(),
                message.body(), message.attachments());
        SmtpDelivery.send(mailFor(tenant, p), toSend);
        log.info("[email:tenant] tenant={} sent to={} attachments={}",
                tenant, EmailAddresses.mask(message.to()), message.attachments().size());
    }

    private JavaMailSender mailFor(UUID tenant, TenantEmailProvider p) {
        String sig = p.getSmtpHost() + "|" + p.getSmtpPort() + "|" + p.getSmtpUsername() + "|"
                + p.getSmtpPasswordEnc() + "|" + p.getUpdatedAt();
        Cached c = cache.get(tenant);
        if (c == null || !c.sig().equals(sig)) {
            c = new Cached(sig, build(p));
            cache.put(tenant, c);
        }
        return c.mail();
    }

    private JavaMailSender build(TenantEmailProvider p) {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(p.getSmtpHost());
        int port = p.getSmtpPort() == null ? 587 : p.getSmtpPort();
        s.setPort(port);
        if (p.getSmtpUsername() != null && !p.getSmtpUsername().isBlank()) {
            s.setUsername(p.getSmtpUsername());
        }
        if (p.getSmtpPasswordEnc() != null && !p.getSmtpPasswordEnc().isBlank()) {
            s.setPassword(cipher.decrypt(p.getSmtpPasswordEnc()));
        }
        Properties props = s.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        if (port == 465) {
            props.put("mail.smtp.ssl.enable", "true"); // implicit TLS
        } else {
            props.put("mail.smtp.starttls.enable", "true"); // 587 / others
        }
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        return s;
    }

    private EmailSender fallback() {
        return allSenders.stream().filter(s -> s != this).findFirst()
                .orElseThrow(() -> new IllegalStateException("No fallback EmailSender configured"));
    }

    /** Invalidate a tenant's cached mail sender (call after its config changes). */
    public void evict(UUID tenant) {
        cache.remove(tenant);
    }

    private record Cached(String sig, JavaMailSender mail) {
    }
}
