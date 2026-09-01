package ro.myfinance.settings.adapter.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ro.myfinance.common.email.TenantEmailProvider;
import ro.myfinance.common.whatsapp.TenantWhatsAppProvider;

/**
 * DTOs for per-tenant messaging provider settings. Secrets are write-only: the responses expose only a
 * {@code hasPassword}/{@code hasToken} boolean (never the ciphertext), and on update a {@code null} secret
 * means "keep the stored value".
 */
final class MessagingSettingsDtos {

    private MessagingSettingsDtos() {
    }

    record EmailProviderResponse(boolean enabled, String fromEmail, String fromName, String smtpHost,
                                 Integer smtpPort, String smtpUsername, boolean hasPassword) {
        static EmailProviderResponse from(TenantEmailProvider p) {
            return new EmailProviderResponse(p.isEnabled(), p.getFromEmail(), p.getFromName(),
                    p.getSmtpHost(), p.getSmtpPort(), p.getSmtpUsername(),
                    p.getSmtpPasswordEnc() != null && !p.getSmtpPasswordEnc().isBlank());
        }
    }

    record UpdateEmailRequest(boolean enabled,
                              @Email String fromEmail,
                              String fromName,
                              String smtpHost,
                              @Min(1) @Max(65535) Integer smtpPort,
                              String smtpUsername,
                              String smtpPassword /* null = keep, "" = clear, else replace */) {
    }

    record WhatsAppProviderResponse(String mode, String accountSid, String fromNumber, boolean hasToken) {
        static WhatsAppProviderResponse from(TenantWhatsAppProvider p) {
            return new WhatsAppProviderResponse(p.getMode().name(), p.getAccountSid(), p.getFromNumber(),
                    p.getAuthTokenEnc() != null && !p.getAuthTokenEnc().isBlank());
        }
    }

    record UpdateWhatsAppRequest(@NotNull TenantWhatsAppProvider.Mode mode,
                                 String accountSid,
                                 String authToken /* null = keep, "" = clear, else replace */,
                                 String fromNumber) {
    }

    record TestEmailRequest(@NotBlank @Email String to) {
    }
}
