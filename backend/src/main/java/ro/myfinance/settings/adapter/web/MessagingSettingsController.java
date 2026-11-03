package ro.myfinance.settings.adapter.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import ro.myfinance.settings.adapter.web.MessagingSettingsDtos.EmailProviderResponse;
import ro.myfinance.settings.adapter.web.MessagingSettingsDtos.TestEmailRequest;
import ro.myfinance.settings.adapter.web.MessagingSettingsDtos.UpdateEmailRequest;
import ro.myfinance.settings.adapter.web.MessagingSettingsDtos.UpdateWhatsAppRequest;
import ro.myfinance.settings.adapter.web.MessagingSettingsDtos.WhatsAppProviderResponse;
import ro.myfinance.settings.application.MessagingSettingsService;
import ro.myfinance.settings.application.MetaWhatsAppOnboardingService;
import ro.myfinance.settings.application.MetaWhatsAppTemplateDraftService;
import ro.myfinance.settings.application.WhatsAppTemplateService;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.whatsapp.MetaWhatsAppTemplateCatalog;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplate;
import ro.myfinance.common.messaging.MessageTemplateKind;

/**
 * Per-tenant messaging provider settings (email SMTP + WhatsApp), managed by the firm admin. TENANT_ADMIN
 * only. Provider secrets are write-only — responses expose only whether a secret is stored, never its value.
 */
@RestController
@RequestMapping("/api/v1/settings/messaging")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class MessagingSettingsController {

    private final MessagingSettingsService service;
    private final MetaWhatsAppOnboardingService metaOnboarding;
    private final WhatsAppTemplateService templates;
    private final MetaWhatsAppTemplateCatalog metaTemplates;
    private final MetaWhatsAppTemplateDraftService metaTemplateDrafts;

    public MessagingSettingsController(MessagingSettingsService service,
                                       MetaWhatsAppOnboardingService metaOnboarding,
                                       WhatsAppTemplateService templates,
                                       MetaWhatsAppTemplateCatalog metaTemplates,
                                       MetaWhatsAppTemplateDraftService metaTemplateDrafts) {
        this.service = service;
        this.metaOnboarding = metaOnboarding;
        this.templates = templates;
        this.metaTemplates = metaTemplates;
        this.metaTemplateDrafts = metaTemplateDrafts;
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

    /** Validates the saved provider configuration without sending a client message. */
    @PostMapping("/whatsapp/test")
    public ConfigurationTestResponse testWhatsApp() {
        service.validateWhatsAppConfiguration();
        return new ConfigurationTestResponse("VALID");
    }

    /** Starts Meta Embedded Signup; the callback/token exchange is completed server-side in a later step. */
    @PostMapping("/whatsapp/meta/onboarding")
    public MetaOnboardingStart startMetaOnboarding() {
        var start = metaOnboarding.start();
        return new MetaOnboardingStart(start.authorizationUrl(), start.expiresAt());
    }

    @GetMapping("/whatsapp/templates")
    public java.util.List<WhatsAppTemplateResponse> listWhatsAppTemplates() {
        return templates.list().stream().map(WhatsAppTemplateResponse::from).toList();
    }

    /** The tenant's templates as they exist in WhatsApp Manager (live from Meta, read-only). */
    @GetMapping("/whatsapp/templates/meta")
    public java.util.List<MetaWhatsAppTemplateCatalog.MetaTemplate> listMetaWhatsAppTemplates() {
        return metaTemplates.list();
    }

    @PostMapping("/whatsapp/templates/meta")
    public MetaWhatsAppTemplateCatalog.MetaTemplateMutation createMetaWhatsAppTemplate(
            @Valid @RequestBody CreateMetaWhatsAppTemplate request) {
        return metaTemplates.create(request.name(), request.language(), request.body(), request.exampleValues());
    }

    /** Deletes the Meta template name and all of its translations. */
    @DeleteMapping("/whatsapp/templates/meta")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMetaWhatsAppTemplate(@org.springframework.web.bind.annotation.RequestParam String name) {
        metaTemplates.delete(name);
    }

    @GetMapping("/whatsapp/templates/meta/draft")
    public MetaWhatsAppTemplateDraftService.Draft draftMetaWhatsAppTemplate(
            @org.springframework.web.bind.annotation.RequestParam EmailKind kind,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
                    MessageTemplateKind reminderKind) {
        return metaTemplateDrafts.create(kind, reminderKind);
    }

    @PutMapping("/whatsapp/templates")
    public WhatsAppTemplateResponse updateWhatsAppTemplate(
            @Valid @RequestBody UpdateWhatsAppTemplate request) {
        return WhatsAppTemplateResponse.from(templates.upsert(request.kind(), request.language(),
                request.metaTemplateName(), request.category(), request.version(),
                request.variableSchema(), request.status()));
    }

    @DeleteMapping("/whatsapp/templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWhatsAppTemplate(@PathVariable java.util.UUID id) {
        templates.delete(id);
    }

    @PostMapping("/whatsapp/meta/onboarding/complete")
    public WhatsAppProviderResponse completeMetaOnboarding(
            @Valid @RequestBody MetaOnboardingComplete request) {
        metaOnboarding.complete(request.state(), request.authorizationCode(), request.wabaId(),
                request.phoneNumberId(), request.businessPortfolioId());
        return WhatsAppProviderResponse.from(service.getWhatsApp());
    }

    public record MetaOnboardingStart(String authorizationUrl, java.time.Instant expiresAt) {
    }

    public record ConfigurationTestResponse(String status) {
    }

    public record MetaOnboardingComplete(
            @jakarta.validation.constraints.NotBlank String state,
            @jakarta.validation.constraints.NotBlank String authorizationCode,
            String wabaId,
            String phoneNumberId,
            String businessPortfolioId) {
    }

    public record UpdateWhatsAppTemplate(
            @jakarta.validation.constraints.NotNull EmailKind kind,
            @jakarta.validation.constraints.NotBlank String language,
            @jakarta.validation.constraints.NotBlank String metaTemplateName,
            TenantWhatsAppTemplate.Category category,
            String version,
            String variableSchema,
            TenantWhatsAppTemplate.Status status) {
    }

    public record WhatsAppTemplateResponse(java.util.UUID id, EmailKind kind, String language,
                                           String metaTemplateName, TenantWhatsAppTemplate.Category category,
                                           TenantWhatsAppTemplate.Status status, String version,
                                           String variableSchema, java.time.Instant approvedAt) {
        static WhatsAppTemplateResponse from(TenantWhatsAppTemplate template) {
            return new WhatsAppTemplateResponse(template.getId(), template.getKind(), template.getLanguage(),
                    template.getMetaTemplateName(), template.getCategory(), template.getStatus(),
                    template.getVersion(), template.getVariableSchema(), template.getApprovedAt());
        }
    }

    public record CreateMetaWhatsAppTemplate(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "[a-z][a-z0-9_]{1,511}") String name,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "ro_RO|en_US") String language,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1024) String body,
            java.util.List<@jakarta.validation.constraints.NotBlank String> exampleValues) {
    }
}
