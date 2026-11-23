package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.whatsapp.MetaWhatsAppTemplateCatalog;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplate;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplateRepository;
import ro.myfinance.settings.application.WhatsAppTemplateService;

@ExtendWith(MockitoExtension.class)
class WhatsAppTemplateServiceTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Mock TenantWhatsAppTemplateRepository templates;
    @Mock MetaWhatsAppTemplateCatalog meta;

    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void savingWithoutVariablesKeepsTheStoredSchemaForTheSameTemplate() {
        TenantWhatsAppTemplate existing = mapping("tax_notice", "companyName,totalToPay");
        stubLookup(existing);

        var saved = service().upsert(EmailKind.TAX, MessageTemplateKind.TAX, "ro_RO", "tax_notice",
                null, "1", null, TenantWhatsAppTemplate.Status.APPROVED);

        assertThat(saved.getVariableSchema()).isEqualTo("companyName,totalToPay");
        assertThat(saved.getStatus()).isEqualTo(TenantWhatsAppTemplate.Status.APPROVED);
    }

    @Test
    void pointingAtAnotherTemplateDropsTheOldSchemaUnlessOneIsGiven() {
        stubLookup(mapping("tax_notice", "companyName,totalToPay"));

        var saved = service().upsert(EmailKind.TAX, MessageTemplateKind.TAX, "ro_RO", "tax_notice_v2",
                null, "1", null, TenantWhatsAppTemplate.Status.APPROVED);

        assertThat(saved.getVariableSchema()).isNull();
    }

    @Test
    void anEmptySchemaClearsIt() {
        stubLookup(mapping("tax_notice", "companyName"));

        var saved = service().upsert(EmailKind.TAX, MessageTemplateKind.TAX, "ro_RO", "tax_notice",
                null, "1", "", TenantWhatsAppTemplate.Status.APPROVED);

        assertThat(saved.getVariableSchema()).isNull();
    }

    @Test
    void rejectsVariableNamesTheMessageKindCannotFill() {
        assertThatThrownBy(() -> service().upsert(EmailKind.PAYROLL, MessageTemplateKind.PAYROLL, "ro_RO",
                "payroll_ready", null, "1", "companyName, totalToPay", TenantWhatsAppTemplate.Status.APPROVED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalToPay");
    }

    @Test
    void deletingAMetaTemplateDisablesTheMappingsThatUsedIt() {
        TenantWhatsAppTemplate mapping = mapping("tax_notice", null);
        when(templates.findByMetaTemplateName("tax_notice")).thenReturn(List.of(mapping));

        service().deleteMetaTemplate("tax_notice");

        var order = inOrder(meta, templates);
        order.verify(meta).delete("tax_notice");
        order.verify(templates).findByMetaTemplateName("tax_notice");
        assertThat(mapping.getStatus()).isEqualTo(TenantWhatsAppTemplate.Status.DISABLED);
    }

    private WhatsAppTemplateService service() {
        return new WhatsAppTemplateService(templates, meta);
    }

    private void stubLookup(TenantWhatsAppTemplate existing) {
        when(templates.findByKindAndSourceKindAndLanguage(EmailKind.TAX, MessageTemplateKind.TAX, "ro_RO"))
                .thenReturn(Optional.of(existing));
        when(templates.save(any(TenantWhatsAppTemplate.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static TenantWhatsAppTemplate mapping(String name, String schema) {
        TenantWhatsAppTemplate t = new TenantWhatsAppTemplate(TENANT, EmailKind.TAX, MessageTemplateKind.TAX,
                "ro_RO", name, TenantWhatsAppTemplate.Category.UTILITY, "1", schema);
        t.update(MessageTemplateKind.TAX, name, TenantWhatsAppTemplate.Category.UTILITY, "1", schema,
                TenantWhatsAppTemplate.Status.APPROVED);
        return t;
    }
}
