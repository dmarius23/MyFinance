package ro.myfinance.access.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.whatsapp.MetaTemplateText;
import ro.myfinance.common.whatsapp.MetaWhatsAppTemplateCatalog;
import ro.myfinance.common.whatsapp.TenantWhatsAppProvider;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplate;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplateRepository;
import ro.myfinance.common.whatsapp.TenantWhatsAppProviderRepository;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;

/**
 * Resolves the Meta template that will actually be sent, filled with this company's values, for the
 * pre-send staff preview. The preview is what the client will read; {@code blockedReason} says why it
 * cannot be sent yet (not approved by Meta, a value is missing, the mapping disagrees with Meta…).
 */
@org.springframework.stereotype.Service
// Deliberately not @Transactional: a module refusing to fill values throws from its own transactional
// service, which would mark an enclosing transaction rollback-only even though the preview handles it.
public class WhatsAppPreviewService {

    private final TenantWhatsAppTemplateRepository templates;
    private final TenantWhatsAppProviderRepository providers;
    private final MetaWhatsAppTemplateCatalog meta;
    private final WhatsAppTemplateVariables variables;
    private final CompanyRepository companies;

    public WhatsAppPreviewService(TenantWhatsAppTemplateRepository templates,
                                  TenantWhatsAppProviderRepository providers,
                                  MetaWhatsAppTemplateCatalog meta,
                                  WhatsAppTemplateVariables variables,
                                  CompanyRepository companies) {
        this.templates = templates;
        this.providers = providers;
        this.meta = meta;
        this.variables = variables;
        this.companies = companies;
    }

    public Preview preview(EmailKind kind, MessageTemplateKind sourceKind, String language,
                           UUID companyId, LocalDate period) {
        var tenant = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        providers.findById(tenant)
                .filter(p -> p.getMode() == TenantWhatsAppProvider.Mode.META_CLOUD_API)
                .orElseThrow(() -> new ConflictException("Meta WhatsApp is not configured"));
        if (companyId != null) {
            companies.findById(companyId)
                    .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        }
        String requested = language == null || language.isBlank() ? "ro_RO" : language;
        String fallback = "ro_RO".equals(requested) ? "en_US" : "ro_RO";
        MessageTemplateKind selectedSource = sourceKind == null ? defaultSource(kind) : sourceKind;

        List<TenantWhatsAppTemplate> candidates = new ArrayList<>();
        approved(kind, selectedSource, requested).ifPresent(candidates::add);
        approved(kind, selectedSource, fallback).ifPresent(candidates::add);
        if (candidates.isEmpty()) {
            throw new ConflictException("No approved WhatsApp template is configured for "
                    + kind + "/" + requested);
        }
        // Fall back to the other language when the preferred mapping has no matching translation in Meta
        // (e.g. ro_RO → hello_world, which Meta only has in en_US) instead of failing the preview.
        for (TenantWhatsAppTemplate local : candidates) {
            var live = meta.find(local.getMetaTemplateName()).stream()
                    .filter(t -> local.getLanguage().equals(t.appLanguage()))
                    .findFirst();
            if (live.isPresent()) {
                return build(local, live.get(), kind, selectedSource, companyId, period);
            }
        }
        throw new ConflictException("The configured WhatsApp template is not available in Meta for "
                + candidates.stream().map(TenantWhatsAppTemplate::getLanguage).toList());
    }

    private Preview build(TenantWhatsAppTemplate local, MetaWhatsAppTemplateCatalog.MetaTemplate live,
                          EmailKind kind, MessageTemplateKind source, UUID companyId, LocalDate period) {
        List<String> names = WhatsAppTemplateVariables.names(local.getVariableSchema());
        String blocked = null;
        WhatsAppTemplateVariables.Resolved resolved = WhatsAppTemplateVariables.Resolved.NONE;
        int metaVariables = MetaTemplateText.variableCount(live.body());
        if (!"APPROVED".equalsIgnoreCase(live.status())) {
            blocked = "Meta has not approved this template yet (status " + live.status() + ")";
        } else if (metaVariables != names.size()) {
            blocked = "The Meta template has " + metaVariables + " variable(s) but its mapping declares "
                    + names.size() + " — fix the mapping's variables in Settings";
        } else if (!names.isEmpty()) {
            if (companyId == null || period == null) {
                blocked = "Choose a company and month to fill the template's variables";
            } else {
                try {
                    resolved = variables.resolve(kind, source, companyId, period, names);
                    if (!resolved.complete()) {
                        blocked = "Values not available for: " + String.join(", ", resolved.missing());
                    }
                } catch (ConflictException e) {
                    blocked = e.getMessage();
                }
            }
        }
        String body = resolved.variables().isEmpty() ? live.body()
                : MetaTemplateText.fill(live.body(), resolved.values());
        return new Preview(local.getMetaTemplateName(), local.getLanguage(), live.language(), live.status(),
                body, names, resolved.variables(), blocked);
    }

    private java.util.Optional<TenantWhatsAppTemplate> approved(EmailKind kind, MessageTemplateKind source,
                                                                String language) {
        return templates.findByKindAndSourceKindAndLanguage(kind, source, language)
                .filter(t -> t.getStatus() == TenantWhatsAppTemplate.Status.APPROVED);
    }

    private static MessageTemplateKind defaultSource(EmailKind kind) {
        return kind == EmailKind.DOCUMENT_REMINDER ? MessageTemplateKind.REMINDER_DOCS_MISSING
                : MessageTemplateKind.valueOf(kind.name());
    }

    /**
     * {@code appLanguage} is the mapping that matched — the send must pass it back so dispatch picks the same
     * template. {@code body} is the Meta text with this company's values filled in.
     */
    public record Preview(String name, String appLanguage, String metaLanguage, String status, String body,
                          List<String> variableNames, List<WhatsAppTemplateVariables.Variable> variables,
                          String blockedReason) {
    }
}
