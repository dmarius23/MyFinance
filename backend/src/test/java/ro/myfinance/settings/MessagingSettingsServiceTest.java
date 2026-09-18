package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.myfinance.common.crypto.SecretCipher;
import ro.myfinance.common.email.TenantAwareEmailSender;
import ro.myfinance.common.email.TenantEmailProvider;
import ro.myfinance.common.email.TenantEmailProviderRepository;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.MisconfiguredException;
import ro.myfinance.common.whatsapp.TenantWhatsAppProviderRepository;
import ro.myfinance.settings.application.MessagingSettingsService;

/**
 * Saving SMTP settings used to answer an opaque 500 when the server had no MYFINANCE_SECRET_KEY, because
 * the IllegalStateException it raised had no handler. These cases pin the two failure modes to statuses a
 * caller can act on: a deployment gap is 503, a tenant's own missing config is 409.
 */
@ExtendWith(MockitoExtension.class)
class MessagingSettingsServiceTest {

    private static final UUID TENANT = UUID.randomUUID();

    @Mock TenantEmailProviderRepository emailRepo;
    @Mock TenantWhatsAppProviderRepository whatsappRepo;
    @Mock TenantAwareEmailSender emailSender;

    @BeforeEach
    void bindTenant() {
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), Role.TENANT_ADMIN, null));
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private MessagingSettingsService service(String base64Key) {
        return new MessagingSettingsService(emailRepo, whatsappRepo, new SecretCipher(base64Key), emailSender);
    }

    /** No master key + a password to store → 503 naming the setting, and nothing is persisted. */
    @Test
    void savingAPasswordWithoutAMasterKeyFailsAsMisconfigured() {
        assertThatThrownBy(() -> service("").updateEmail(true, "firm@example.ro", "Firm",
                "smtp.example.ro", 587, "user", "hunter2"))
                .isInstanceOf(MisconfiguredException.class)
                .hasMessageContaining("MYFINANCE_SECRET_KEY");

        verify(emailRepo, never()).save(any());
    }

    /** The guard is about storing a secret — settings without a password still save on a keyless server. */
    @Test
    void savingWithoutAPasswordStillWorksWithoutAMasterKey() {
        when(emailRepo.findById(TENANT)).thenReturn(Optional.of(new TenantEmailProvider(TENANT)));

        service("").updateEmail(true, "firm@example.ro", "Firm", "smtp.example.ro", 587, "user", null);

        verify(emailRepo).save(any(TenantEmailProvider.class));
    }

    /** With a key configured the password is stored encrypted — never in plaintext. */
    @Test
    void storesThePasswordEncryptedWhenAKeyIsConfigured() {
        when(emailRepo.findById(TENANT)).thenReturn(Optional.of(new TenantEmailProvider(TENANT)));
        String key = java.util.Base64.getEncoder().encodeToString(new byte[32]);

        service(key).updateEmail(true, "firm@example.ro", "Firm", "smtp.example.ro", 587, "user", "hunter2");

        org.mockito.ArgumentCaptor<TenantEmailProvider> saved =
                org.mockito.ArgumentCaptor.forClass(TenantEmailProvider.class);
        verify(emailRepo).save(saved.capture());
        assertThat(saved.getValue().getSmtpPasswordEnc())
                .isNotNull()
                .doesNotContain("hunter2");
    }

    /** Test-send before configuring a provider is the tenant's own gap → 409, not a 500. */
    @Test
    void testEmailBeforeConfiguringAProviderIsAConflict() {
        when(emailRepo.findById(TENANT)).thenReturn(Optional.of(new TenantEmailProvider(TENANT)));

        assertThatThrownBy(() -> service("").sendTestEmail("someone@example.ro"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Configure and enable an email provider");
    }
}
