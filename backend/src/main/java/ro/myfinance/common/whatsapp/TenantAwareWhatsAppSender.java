package ro.myfinance.common.whatsapp;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.crypto.SecretCipher;
import ro.myfinance.common.security.TenantContext;

/**
 * The primary {@link WhatsAppSender}: routes each send through the CURRENT tenant's own WhatsApp provider.
 * {@code TWILIO} → send via the firm's Twilio account (their SID/token/from); {@code OFF} or
 * {@code CLICK_TO_CHAT} (a UI-only manual flow) or no config → delegate to the platform's global sender
 * (logging by default). The tenant comes from {@link TenantContext}; the auth token is decrypted in-memory
 * only at send time.
 */
@Component
@Primary
public class TenantAwareWhatsAppSender implements WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareWhatsAppSender.class);

    private final TenantWhatsAppProviderRepository providers;
    private final SecretCipher cipher;
    private final ObjectProvider<WhatsAppSender> allSenders; // includes self; filtered out for fallback
    private final String defaultCountryCode;

    public TenantAwareWhatsAppSender(TenantWhatsAppProviderRepository providers, SecretCipher cipher,
                                     ObjectProvider<WhatsAppSender> allSenders,
                                     @Value("${myfinance.whatsapp.default-country-code:+40}") String defaultCountryCode) {
        this.providers = providers;
        this.cipher = cipher;
        this.allSenders = allSenders;
        this.defaultCountryCode = defaultCountryCode;
    }

    @Override
    @Transactional(readOnly = true)
    public void send(Message message) {
        UUID tenant = TenantContext.tenantId().orElse(null);
        TenantWhatsAppProvider p = tenant == null ? null : providers.findById(tenant).orElse(null);
        if (p != null && p.getMode() == TenantWhatsAppProvider.Mode.TWILIO) {
            TwilioDelivery.send(p.getAccountSid(), cipher.decrypt(p.getAuthTokenEnc()), p.getFromNumber(),
                    defaultCountryCode, message);
            log.info("[whatsapp:tenant] tenant={} delivered via twilio", tenant);
            return;
        }
        // OFF / CLICK_TO_CHAT (manual wa.me from the UI) / unconfigured → platform default (logging).
        fallback().send(message);
    }

    private WhatsAppSender fallback() {
        return allSenders.stream().filter(s -> s != this).findFirst()
                .orElseThrow(() -> new IllegalStateException("No fallback WhatsAppSender configured"));
    }
}
