package ro.myfinance.settings.application;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.security.TenantContext;
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

    public WhatsAppTemplateService(TenantWhatsAppTemplateRepository templates) {
        this.templates = templates;
    }

    @Transactional(readOnly = true)
    public List<TenantWhatsAppTemplate> list() {
        return templates.findAllByOrderByKindAscLanguageAsc();
    }

    public TenantWhatsAppTemplate upsert(EmailKind kind, String language, String name,
                                         TenantWhatsAppTemplate.Category category, String version,
                                         String variableSchema, TenantWhatsAppTemplate.Status status) {
        validate(kind, language, name);
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        TenantWhatsAppTemplate template = templates.findByKindAndLanguage(kind, language)
                .orElseGet(() -> new TenantWhatsAppTemplate(tenantId, kind, language, name, category,
                        version, variableSchema));
        template.update(name, category, version, variableSchema, status);
        return templates.save(template);
    }

    public void delete(UUID id) {
        TenantWhatsAppTemplate template = templates.findById(id)
                .orElseThrow(() -> new NotFoundException("WhatsApp template not found"));
        templates.delete(template);
    }

    private static void validate(EmailKind kind, String language, String name) {
        if (kind == null) {
            throw new IllegalArgumentException("Template kind is required");
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
