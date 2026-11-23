package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ro.myfinance.access.application.WhatsAppPreviewService;
import ro.myfinance.access.application.WhatsAppTemplateVariables;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.whatsapp.MetaWhatsAppTemplateCatalog;
import ro.myfinance.common.whatsapp.MetaWhatsAppTemplateCatalog.MetaTemplate;
import ro.myfinance.common.whatsapp.TenantWhatsAppProvider;
import ro.myfinance.common.whatsapp.TenantWhatsAppProviderRepository;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplate;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplateRepository;
import ro.myfinance.common.whatsapp.WhatsAppTemplateValues;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WhatsAppPreviewServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID COMPANY = UUID.randomUUID();
    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

    @Mock TenantWhatsAppTemplateRepository templates;
    @Mock TenantWhatsAppProviderRepository providers;
    @Mock MetaWhatsAppTemplateCatalog meta;
    @Mock CompanyRepository companies;

    private Map<String, String> payrollValues = Map.of("companyName", "Acme SRL", "month", "aprilie 2026");

    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        TenantWhatsAppProvider provider = new TenantWhatsAppProvider(TENANT);
        provider.setMode(TenantWhatsAppProvider.Mode.META_CLOUD_API);
        when(providers.findById(TENANT)).thenReturn(Optional.of(provider));
        when(companies.findById(COMPANY)).thenReturn(Optional.of(mock(Company.class)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void fallsBackToTheOtherLanguageWhenThePreferredMappingIsMissingInMeta() {
        mapping("ro_RO", "hello_world", null);
        mapping("en_US", "hello_world", null);
        when(meta.find("hello_world")).thenReturn(List.of(
                new MetaTemplate("hello_world", "en_US", "en_US", "APPROVED", "UTILITY", "Hello World")));

        var preview = service().preview(EmailKind.PAYROLL, null, "ro_RO", COMPANY, PERIOD);

        assertThat(preview.appLanguage()).isEqualTo("en_US");
        assertThat(preview.body()).isEqualTo("Hello World");
        assertThat(preview.blockedReason()).isNull();
    }

    @Test
    void fillsVariablesWithServerValuesInSchemaOrder() {
        mapping("ro_RO", "payroll_ready", "companyName,month");
        when(meta.find("payroll_ready")).thenReturn(List.of(new MetaTemplate("payroll_ready", "ro", "ro_RO",
                "APPROVED", "UTILITY", "Buna ziua, {{1}}! Statele pentru {{2}} sunt gata.")));

        var preview = service().preview(EmailKind.PAYROLL, null, "ro_RO", COMPANY, PERIOD);

        assertThat(preview.body()).isEqualTo("Buna ziua, Acme SRL! Statele pentru aprilie 2026 sunt gata.");
        assertThat(preview.variables()).extracting(WhatsAppTemplateVariables.Variable::value)
                .containsExactly("Acme SRL", "aprilie 2026");
        assertThat(preview.blockedReason()).isNull();
    }

    @Test
    void blocksWhenAValueIsMissing() {
        payrollValues = Map.of("companyName", "Acme SRL");
        mapping("ro_RO", "payroll_ready", "companyName,month");
        when(meta.find("payroll_ready")).thenReturn(List.of(new MetaTemplate("payroll_ready", "ro", "ro_RO",
                "APPROVED", "UTILITY", "Buna {{1}}, luna {{2}}.")));

        var preview = service().preview(EmailKind.PAYROLL, null, "ro_RO", COMPANY, PERIOD);

        assertThat(preview.blockedReason()).contains("month");
    }

    @Test
    void blocksWhenTheMappingDisagreesWithMetaOnTheVariableCount() {
        mapping("ro_RO", "payroll_ready", null);
        when(meta.find("payroll_ready")).thenReturn(List.of(new MetaTemplate("payroll_ready", "ro", "ro_RO",
                "APPROVED", "UTILITY", "Buna {{1}}.")));

        var preview = service().preview(EmailKind.PAYROLL, null, "ro_RO", COMPANY, PERIOD);

        assertThat(preview.blockedReason()).contains("1 variable(s)", "declares 0");
    }

    @Test
    void blocksWhenMetaHasNotApprovedTheTemplate() {
        mapping("ro_RO", "payroll_ready", null);
        when(meta.find("payroll_ready")).thenReturn(List.of(new MetaTemplate("payroll_ready", "ro", "ro_RO",
                "PENDING", "UTILITY", "Statele sunt gata.")));

        var preview = service().preview(EmailKind.PAYROLL, null, "ro_RO", COMPANY, PERIOD);

        assertThat(preview.blockedReason()).contains("PENDING");
    }

    @Test
    void reportsAModuleRefusalAsTheBlockedReason() {
        mapping("ro_RO", "tax_notice", "totalToPay");
        when(templates.findByKindAndSourceKindAndLanguage(EmailKind.TAX, MessageTemplateKind.TAX, "ro_RO"))
                .thenReturn(Optional.of(approved(EmailKind.TAX, MessageTemplateKind.TAX, "ro_RO", "tax_notice",
                        "totalToPay")));
        when(meta.find("tax_notice")).thenReturn(List.of(new MetaTemplate("tax_notice", "ro", "ro_RO",
                "APPROVED", "UTILITY", "Total de plata: {{1}} RON.")));

        var preview = service().preview(EmailKind.TAX, null, "ro_RO", COMPANY, PERIOD);

        assertThat(preview.blockedReason()).contains("do not reconcile");
    }

    private WhatsAppPreviewService service() {
        WhatsAppTemplateValues payroll = new WhatsAppTemplateValues() {
            @Override public EmailKind kind() { return EmailKind.PAYROLL; }
            @Override public Map<String, String> values(MessageTemplateKind s, UUID c, LocalDate p) {
                return payrollValues;
            }
        };
        WhatsAppTemplateValues tax = new WhatsAppTemplateValues() {
            @Override public EmailKind kind() { return EmailKind.TAX; }
            @Override public Map<String, String> values(MessageTemplateKind s, UUID c, LocalDate p) {
                throw new ConflictException("A declaration's totals do not reconcile");
            }
        };
        return new WhatsAppPreviewService(templates, providers, meta,
                new WhatsAppTemplateVariables(List.of(payroll, tax)), companies);
    }

    private void mapping(String language, String name, String schema) {
        when(templates.findByKindAndSourceKindAndLanguage(any(), any(), org.mockito.ArgumentMatchers.eq(language)))
                .thenReturn(Optional.of(approved(EmailKind.PAYROLL, MessageTemplateKind.PAYROLL, language, name,
                        schema)));
    }

    private static TenantWhatsAppTemplate approved(EmailKind kind, MessageTemplateKind source, String language,
                                                   String name, String schema) {
        TenantWhatsAppTemplate t = new TenantWhatsAppTemplate(TENANT, kind, source, language, name,
                TenantWhatsAppTemplate.Category.UTILITY, "1", schema);
        t.update(source, name, TenantWhatsAppTemplate.Category.UTILITY, "1", schema,
                TenantWhatsAppTemplate.Status.APPROVED);
        return t;
    }
}
