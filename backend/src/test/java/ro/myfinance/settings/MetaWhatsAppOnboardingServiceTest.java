package ro.myfinance.settings;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
import ro.myfinance.settings.application.MetaWhatsAppOnboardingClient;
import ro.myfinance.settings.application.MetaWhatsAppOnboardingService;

@ExtendWith(MockitoExtension.class)
class MetaWhatsAppOnboardingServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Mock MetaWhatsAppOAuthStateRepository states;
    @Mock TenantWhatsAppProviderRepository providers;
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
        when(meta.exchangeCode("code", "https://app.test/callback")).thenReturn("access-token");
        when(providers.findById(TENANT)).thenReturn(Optional.empty());
        when(providers.save(any(TenantWhatsAppProvider.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MetaWhatsAppOnboardingService service = new MetaWhatsAppOnboardingService(
                states, providers, meta, new SecretCipher(testKey()),
                "app-id", "config-id", "https://app.test/callback",
                "https://www.facebook.com/v24.0/dialog/oauth",
                "whatsapp_business_management,whatsapp_business_messaging");

        service.complete("state", "code", "waba-id", "phone-id", "business-id");

        verify(meta).validateWaba("access-token", "waba-id");
        verify(meta).validatePhoneNumber("access-token", "phone-id");
        verify(meta).subscribeToWaba("access-token", "waba-id");
        verify(providers).save(any(TenantWhatsAppProvider.class));
    }

    private static String testKey() {
        return java.util.Base64.getEncoder().encodeToString(new byte[32]);
    }
}
