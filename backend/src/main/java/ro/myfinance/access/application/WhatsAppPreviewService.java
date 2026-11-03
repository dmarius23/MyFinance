package ro.myfinance.access.application;

import java.util.List;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.whatsapp.MetaWhatsAppTemplateCatalog;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplate;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplateRepository;
import ro.myfinance.common.whatsapp.TenantWhatsAppProviderRepository;
import ro.myfinance.common.web.ConflictException;

/** Resolves the actual Meta template shown before a staff member sends a WhatsApp message. */
@org.springframework.stereotype.Service
public class WhatsAppPreviewService {

    private final TenantWhatsAppTemplateRepository templates;
    private final TenantWhatsAppProviderRepository providers;
    private final MetaWhatsAppTemplateCatalog meta;

    public WhatsAppPreviewService(TenantWhatsAppTemplateRepository templates,
                                  TenantWhatsAppProviderRepository providers,
                                  MetaWhatsAppTemplateCatalog meta) {
        this.templates = templates;
        this.providers = providers;
        this.meta = meta;
    }

    public Preview preview(EmailKind kind, String language) {
        var tenant = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        var provider = providers.findById(tenant)
                .filter(p -> p.getMode() == ro.myfinance.common.whatsapp.TenantWhatsAppProvider.Mode.META_CLOUD_API)
                .orElseThrow(() -> new ConflictException("Meta WhatsApp is not configured"));
        String requested = language == null || language.isBlank() ? "ro_RO" : language;
        String fallback = "ro_RO".equals(requested) ? "en_US" : "ro_RO";
        TenantWhatsAppTemplate local = templates.findByKindAndLanguage(kind, requested)
                .filter(t -> t.getStatus() == TenantWhatsAppTemplate.Status.APPROVED)
                .or(() -> templates.findByKindAndLanguage(kind, fallback)
                        .filter(t -> t.getStatus() == TenantWhatsAppTemplate.Status.APPROVED))
                .orElseThrow(() -> new ConflictException("No approved WhatsApp template is configured for "
                        + kind + "/" + requested));
        MetaWhatsAppTemplateCatalog.MetaTemplate live = meta.list().stream()
                .filter(t -> local.getMetaTemplateName().equals(t.name())
                        && local.getLanguage().equals(t.appLanguage()))
                .findFirst()
                .orElseThrow(() -> new ConflictException("The configured WhatsApp template is not available "
                        + "in Meta for " + local.getLanguage()));
        return new Preview(local.getMetaTemplateName(), local.getLanguage(), live.language(), live.status(),
                live.body(), variableNames(local.getVariableSchema()));
    }

    private static List<String> variableNames(String schema) {
        if (schema == null || schema.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(schema.split(",")).map(String::trim)
                .filter(s -> !s.isBlank()).toList();
    }

    public record Preview(String name, String appLanguage, String metaLanguage, String status,
                          String body, List<String> variableNames) {
    }
}
