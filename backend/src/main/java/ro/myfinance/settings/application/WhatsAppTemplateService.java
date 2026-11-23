package ro.myfinance.settings.application;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.whatsapp.MetaWhatsAppTemplateCatalog;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplate;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplateRepository;
import ro.myfinance.common.web.NotFoundException;

/** Tenant-admin management of the templates approved for outbound WhatsApp messages. */
@Service
@Transactional
public class WhatsAppTemplateService {

    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2}_[A-Z]{2}");
    private static final Pattern TEMPLATE_NAME = Pattern.compile("[a-z][a-z0-9_]{1,511}");

    private final TenantWhatsAppTemplateRepository templates;
    private final MetaWhatsAppTemplateCatalog meta;

    public WhatsAppTemplateService(TenantWhatsAppTemplateRepository templates, MetaWhatsAppTemplateCatalog meta) {
        this.templates = templates;
        this.meta = meta;
    }

    @Transactional(readOnly = true)
    public List<TenantWhatsAppTemplate> list() {
        return templates.findAllByOrderByKindAscLanguageAsc();
    }

    /**
     * Creates or updates the mapping for (kind, source, language). {@code variableSchema} is the ordered list
     * of placeholder names filling {@code {{1}}, {{2}}, …}: {@code null} keeps the stored list (when the
     * Meta template is unchanged — a form that does not edit variables must not erase them), {@code ""}
     * clears it.
     */
    public TenantWhatsAppTemplate upsert(EmailKind kind, MessageTemplateKind sourceKind, String language, String name,
                                         TenantWhatsAppTemplate.Category category, String version,
                                         String variableSchema, TenantWhatsAppTemplate.Status status) {
        validate(kind, sourceKind, language, name);
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        var existing = templates.findByKindAndSourceKindAndLanguage(kind, sourceKind, language);
        String schema = variableSchema == null
                ? existing.filter(t -> name.equals(t.getMetaTemplateName()))
                        .map(TenantWhatsAppTemplate::getVariableSchema).orElse(null)
                : normalizeSchema(variableSchema, sourceKind);
        TenantWhatsAppTemplate template = existing
                .orElseGet(() -> new TenantWhatsAppTemplate(tenantId, kind, sourceKind, language, name, category,
                        version, schema));
        template.update(sourceKind, name, category, version, schema, status);
        return templates.save(template);
    }

    public void delete(UUID id) {
        TenantWhatsAppTemplate template = templates.findById(id)
                .orElseThrow(() -> new NotFoundException("WhatsApp template not found"));
        templates.delete(template);
    }

    /**
     * Deletes the template (all translations) in Meta, then disables every local mapping that pointed at it,
     * so sends fail fast with "no approved template" instead of being queued and rejected by Meta.
     */
    public void deleteMetaTemplate(String name) {
        meta.delete(name);
        templates.findByMetaTemplateName(name).forEach(TenantWhatsAppTemplate::disable);
    }

    /** Trimmed, de-duplicated check of the declared names against what this message kind can fill. */
    static String normalizeSchema(String schema, MessageTemplateKind sourceKind) {
        List<String> names = Arrays.stream(schema.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (names.isEmpty()) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        for (String n : names) {
            if (!sourceKind.allNames().contains(n)) {
                throw new IllegalArgumentException("Unknown WhatsApp template variable '" + n + "' for "
                        + sourceKind + " (allowed: " + String.join(", ", new java.util.TreeSet<>(sourceKind.allNames()))
                        + ")");
            }
            if (!seen.add(n)) {
                throw new IllegalArgumentException("WhatsApp template variable '" + n + "' is listed twice");
            }
        }
        return String.join(",", names);
    }

    private static void validate(EmailKind kind, MessageTemplateKind sourceKind, String language, String name) {
        if (kind == null) {
            throw new IllegalArgumentException("Template kind is required");
        }
        boolean matches = kind == EmailKind.DOCUMENT_REMINDER
                ? sourceKind != null && sourceKind.isReminder()
                : sourceKind != null && sourceKind.name().equals(kind.name());
        if (!matches) {
            throw new IllegalArgumentException("WhatsApp source message type does not match its module");
        }
        if (language == null || !LANGUAGE.matcher(language).matches()
                || (!"ro_RO".equals(language) && !"en_US".equals(language))) {
            throw new IllegalArgumentException("Only ro_RO and en_US WhatsApp templates are supported");
        }
        if (name == null || !TEMPLATE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid Meta WhatsApp template name");
        }
    }
}
