package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.myfinance.common.crypto.SecretCipher;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.whatsapp.MetaWhatsAppOAuthState;
import ro.myfinance.common.whatsapp.MetaWhatsAppOAuthStateRepository;
import ro.myfinance.common.whatsapp.TenantWhatsAppProvider;
import ro.myfinance.common.whatsapp.TenantWhatsAppProviderRepository;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplate;
import ro.myfinance.common.whatsapp.TenantWhatsAppTemplateRepository;
import ro.myfinance.common.email.EmailKind;
import ro.myfinance.common.messaging.MessageTemplateKind;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.settings.application.MetaWhatsAppOnboardingClient;
import ro.myfinance.settings.application.MetaWhatsAppOnboardingService;

@ExtendWith(MockitoExtension.class)
class MetaWhatsAppOnboardingServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Mock MetaWhatsAppOAuthStateRepository states;
    @Mock TenantWhatsAppProviderRepository providers;
    @Mock TenantWhatsAppTemplateRepository templates;
    @Mock MetaWhatsAppOnboardingClient meta;

    @BeforeEach
    void setUp() {
        TenantContext.set(new TenantContext.Identity(TENANT, USER, Role.TENANT_ADMIN, null));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void completesOnboardingOnlyAfterRegisteringWabaWebhookSubscription() {
        MetaWhatsAppOAuthState pending = new MetaWhatsAppOAuthState(
                TENANT, USER, "state-hash", Instant.now().plusSeconds(600));
        when(states.findByStateHash(any())).thenReturn(Optional.of(pending));
        when(states.consumeIfUsable(any(), any())).thenReturn(1);
        when(meta.exchangeCode("code", "https://app.test/callback")).thenReturn("access-token");
        when(providers.findById(TENANT)).thenReturn(Optional.empty());
        when(providers.save(any(TenantWhatsAppProvider.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MetaWhatsAppOnboardingService service = new MetaWhatsAppOnboardingService(
                states, providers, templates, meta, new SecretCipher(testKey()),
                "app-id", "config-id", "https://app.test/callback",
                "https://www.facebook.com/v24.0/dialog/oauth",
                "whatsapp_business_management,whatsapp_business_messaging");

        service.complete("state", "code", "waba-id", "phone-id", "business-id");

        verify(meta).validateWaba("access-token", "waba-id");
        verify(meta).validatePhoneNumber("access-token", "phone-id");
        verify(meta).subscribeToWaba("access-token", "waba-id");
        verify(providers).save(any(TenantWhatsAppProvider.class));
    }

    @Test
    void resolvesWabaAndPhoneFromTokenWhenRedirectOmitsThem() {
        MetaWhatsAppOAuthState pending = new MetaWhatsAppOAuthState(
                TENANT, USER, "state-hash", Instant.now().plusSeconds(600));
        when(states.findByStateHash(any())).thenReturn(Optional.of(pending));
        when(states.consumeIfUsable(any(), any())).thenReturn(1);
        when(meta.exchangeCode("code", "https://app.test/callback")).thenReturn("access-token");
        when(meta.grantedWabaIds("access-token")).thenReturn(List.of("waba-id"));
        when(meta.phoneNumberIds("access-token", "waba-id")).thenReturn(List.of("phone-id"));
        when(meta.validateWaba("access-token", "waba-id")).thenReturn("owner-business");
        when(providers.findById(TENANT)).thenReturn(Optional.empty());
        when(providers.save(any(TenantWhatsAppProvider.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        TenantWhatsAppProvider saved = service().complete("state", "code", null, null, null);

        assertThat(saved.getMetaWabaId()).isEqualTo("waba-id");
        assertThat(saved.getMetaPhoneNumberId()).isEqualTo("phone-id");
        assertThat(saved.getMetaBusinessPortfolioId()).isEqualTo("owner-business");
        assertThat(saved.isSendable()).isTrue();
        verify(meta).validatePhoneNumber("access-token", "phone-id");
        verify(meta).subscribeToWaba("access-token", "waba-id");
    }

    @Test
    void rejectsAmbiguousWabaGrantWithoutStoringProvider() {
        MetaWhatsAppOAuthState pending = new MetaWhatsAppOAuthState(
                TENANT, USER, "state-hash", Instant.now().plusSeconds(600));
        when(states.findByStateHash(any())).thenReturn(Optional.of(pending));
        when(states.consumeIfUsable(any(), any())).thenReturn(1);
        when(meta.exchangeCode("code", "https://app.test/callback")).thenReturn("access-token");
        when(meta.grantedWabaIds("access-token")).thenReturn(List.of("waba-1", "waba-2"));

        assertThatThrownBy(() -> service().complete("state", "code", null, null, null))
                .isInstanceOf(ConflictException.class);
        verify(providers, never()).save(any());
    }

    @Test
    void concurrentCompletionLosesBeforeExchangingTheCode() {
        MetaWhatsAppOAuthState pending = new MetaWhatsAppOAuthState(
                TENANT, USER, "state-hash", Instant.now().plusSeconds(600));
        when(states.findByStateHash(any())).thenReturn(Optional.of(pending));
        when(states.consumeIfUsable(any(), any())).thenReturn(0); // another request already claimed it

        assertThatThrownBy(() -> service().complete("state", "code", "waba-id", "phone-id", null))
                .isInstanceOf(ConflictException.class);
        verify(meta, never()).exchangeCode(any(), any());
        verify(providers, never()).save(any());
    }

    @Test
    void connectingADifferentWabaDisablesMappingsMadeForTheOldOne() {
        MetaWhatsAppOAuthState pending = new MetaWhatsAppOAuthState(
                TENANT, USER, "state-hash", Instant.now().plusSeconds(600));
        when(states.findByStateHash(any())).thenReturn(Optional.of(pending));
        when(states.consumeIfUsable(any(), any())).thenReturn(1);
        when(meta.exchangeCode("code", "https://app.test/callback")).thenReturn("access-token");
        TenantWhatsAppProvider existing = new TenantWhatsAppProvider(TENANT);
        existing.setMetaWabaId("old-waba");
        when(providers.findById(TENANT)).thenReturn(Optional.of(existing));
        when(providers.save(any(TenantWhatsAppProvider.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        TenantWhatsAppTemplate mapping = approvedMapping();
        when(templates.findAll()).thenReturn(List.of(mapping));

        service().complete("state", "code", "new-waba", "phone-id", "business-id");

        assertThat(mapping.getStatus()).isEqualTo(TenantWhatsAppTemplate.Status.DISABLED);
    }

    @Test
    void reconnectingTheSameWabaKeepsMappings() {
        MetaWhatsAppOAuthState pending = new MetaWhatsAppOAuthState(
                TENANT, USER, "state-hash", Instant.now().plusSeconds(600));
        when(states.findByStateHash(any())).thenReturn(Optional.of(pending));
        when(states.consumeIfUsable(any(), any())).thenReturn(1);
        when(meta.exchangeCode("code", "https://app.test/callback")).thenReturn("access-token");
        TenantWhatsAppProvider existing = new TenantWhatsAppProvider(TENANT);
        existing.setMetaWabaId("waba-id");
        when(providers.findById(TENANT)).thenReturn(Optional.of(existing));
        when(providers.save(any(TenantWhatsAppProvider.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().complete("state", "code", "waba-id", "phone-id", "business-id");

        verify(templates, never()).findAll();
    }

    private static TenantWhatsAppTemplate approvedMapping() {
        TenantWhatsAppTemplate t = new TenantWhatsAppTemplate(TENANT, EmailKind.TAX, MessageTemplateKind.TAX,
                "ro_RO", "tax_notice", TenantWhatsAppTemplate.Category.UTILITY, "1", null);
        t.update(MessageTemplateKind.TAX, "tax_notice", TenantWhatsAppTemplate.Category.UTILITY, "1", null,
                TenantWhatsAppTemplate.Status.APPROVED);
        return t;
    }

    private MetaWhatsAppOnboardingService service() {
        return new MetaWhatsAppOnboardingService(
                states, providers, templates, meta, new SecretCipher(testKey()),
                "app-id", "config-id", "https://app.test/callback",
                "https://www.facebook.com/v24.0/dialog/oauth",
                "whatsapp_business_management,whatsapp_business_messaging");
    }

    private static String testKey() {
        return java.util.Base64.getEncoder().encodeToString(new byte[32]);
    }
}
