package ro.myfinance.settings.adapter.web;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.settings.adapter.web.MessagingSettingsDtos.EmailProviderResponse;
import ro.myfinance.settings.adapter.web.MessagingSettingsDtos.TestEmailRequest;
import ro.myfinance.settings.adapter.web.MessagingSettingsDtos.UpdateEmailRequest;
import ro.myfinance.settings.adapter.web.MessagingSettingsDtos.UpdateWhatsAppRequest;
import ro.myfinance.settings.adapter.web.MessagingSettingsDtos.WhatsAppProviderResponse;
import ro.myfinance.settings.application.MessagingSettingsService;

/**
 * Per-tenant messaging provider settings (email SMTP + WhatsApp), managed by the firm admin. TENANT_ADMIN
 * only. Provider secrets are write-only — responses expose only whether a secret is stored, never its value.
 */
@RestController
@RequestMapping("/api/v1/settings/messaging")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class MessagingSettingsController {

    private final MessagingSettingsService service;

    public MessagingSettingsController(MessagingSettingsService service) {
        this.service = service;
    }

    @GetMapping("/email")
    public EmailProviderResponse getEmail() {
        return EmailProviderResponse.from(service.getEmail());
    }

    @PutMapping("/email")
    public EmailProviderResponse updateEmail(@Valid @RequestBody UpdateEmailRequest req) {
        service.updateEmail(req.enabled(), req.fromEmail(), req.fromName(), req.smtpHost(), req.smtpPort(),
                req.smtpUsername(), req.smtpPassword());
        return EmailProviderResponse.from(service.getEmail());
    }

    /** Send a test email to the given address using the tenant's just-saved SMTP settings (synchronous). */
    @PostMapping("/email/test")
    public void testEmail(@Valid @RequestBody TestEmailRequest req) {
        service.sendTestEmail(req.to());
    }

    @GetMapping("/whatsapp")
    public WhatsAppProviderResponse getWhatsApp() {
        return WhatsAppProviderResponse.from(service.getWhatsApp());
    }

    @PutMapping("/whatsapp")
    public WhatsAppProviderResponse updateWhatsApp(@Valid @RequestBody UpdateWhatsAppRequest req) {
        service.updateWhatsApp(req.mode(), req.accountSid(), req.authToken(), req.fromNumber());
        return WhatsAppProviderResponse.from(service.getWhatsApp());
    }
}
