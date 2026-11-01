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
 * {@code TWILIO} → send via the firm's Twilio account (their SID/token/from). Disabled, manual
 * click-to-chat, or unconfigured tenants are rejected by the application before delivery. A logging
 * fallback is available only when explicitly enabled for local/test environments. The tenant comes from
 * {@link TenantContext}; the auth token is decrypted in-memory only at send time.
 */
@Component
@Primary
public class TenantAwareWhatsAppSender implements WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareWhatsAppSender.class);

    private final TenantWhatsAppProviderRepository providers;
    private final SecretCipher cipher;
    private final MetaWhatsAppSender metaSender;
    private final ObjectProvider<WhatsAppSender> allSenders; // includes self; filtered out for fallback
    private final String defaultCountryCode;
    private final boolean loggingFallbackEnabled;

    public TenantAwareWhatsAppSender(TenantWhatsAppProviderRepository providers, SecretCipher cipher,
                                     MetaWhatsAppSender metaSender,
                                     ObjectProvider<WhatsAppSender> allSenders,
                                     @Value("${myfinance.whatsapp.default-country-code:+40}") String defaultCountryCode,
                                     @Value("${myfinance.whatsapp.logging-fallback-enabled:false}")
                                     boolean loggingFallbackEnabled) {
        this.providers = providers;
        this.cipher = cipher;
        this.metaSender = metaSender;
        this.allSenders = allSenders;
        this.defaultCountryCode = defaultCountryCode;
        this.loggingFallbackEnabled = loggingFallbackEnabled;
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryReceipt send(Message message) {
        UUID tenant = TenantContext.tenantId().orElse(null);
        TenantWhatsAppProvider p = tenant == null ? null : providers.findById(tenant).orElse(null);
        if (p != null && p.getMode() == TenantWhatsAppProvider.Mode.TWILIO) {
            DeliveryReceipt receipt = TwilioDelivery.send(p.getAccountSid(),
                    cipher.decrypt(p.getAuthTokenEnc()), p.getFromNumber(), defaultCountryCode, message);
            log.info("[whatsapp:tenant] tenant={} delivered via twilio", tenant);
            return receipt;
        }
        if (p != null && p.getMode() == TenantWhatsAppProvider.Mode.META_CLOUD_API) {
            return metaSender.send(message);
        }
        if (!loggingFallbackEnabled) {
            throw new IllegalStateException("WhatsApp provider is not configured for this tenant");
        }
        // Development/test-only logging fallback.
        return fallback().send(message);
    }

    private WhatsAppSender fallback() {
        return allSenders.stream().filter(s -> s instanceof LoggingWhatsAppSender).findFirst()
                .orElseThrow(() -> new IllegalStateException("No fallback WhatsAppSender configured"));
    }
}
