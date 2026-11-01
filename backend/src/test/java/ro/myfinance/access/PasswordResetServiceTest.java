package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.myfinance.access.application.AuthUserDirectory;
import ro.myfinance.access.application.EmailEnvelopeService;
import ro.myfinance.access.application.PasswordResetService;
import ro.myfinance.access.application.UserInviter;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.email.EmailSender;
import ro.myfinance.common.security.TenantContext;

/**
 * Self-service password reset, delivered by US through the firm's own email provider — not by the
 * identity provider's generic template. The endpoint behind this is PUBLIC, so the service must never
 * throw and never reveal whether an address exists.
 */
class PasswordResetServiceTest {

    private final AuthUserDirectory directory = mock(AuthUserDirectory.class);
    private final UserInviter inviter = mock(UserInviter.class);
    private final EmailEnvelopeService envelope = mock(EmailEnvelopeService.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final PasswordResetService service =
            new PasswordResetService(directory, inviter, envelope, emailSender, audit);

    private final UUID TENANT = UUID.randomUUID();
    private final UUID USER = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void knownUser(String email) {
        when(directory.findByEmailAcrossTenants(email))
                .thenReturn(List.of(new AuthUserDirectory.TenantUser(USER, TENANT)));
        when(inviter.generateSetPasswordLink(email))
                .thenReturn("https://app.example/#access_token=t&type=recovery");
        when(envelope.system(email))
                .thenReturn(new EmailEnvelopeService.Envelope("MyFinance", "firma@contabil.ro", email));
        when(envelope.firmName()).thenReturn("Contabil SRL");
    }

    /** The whole point: the mail goes out from the FIRM's sender, carrying a type=recovery link. */
    @Test
    void sendsABrandedRecoveryLinkFromTheFirmsSender() {
        knownUser("ana@firma.ro");

        service.requestReset("ana@firma.ro");

        ArgumentCaptor<EmailSender.Message> sent = ArgumentCaptor.forClass(EmailSender.Message.class);
        verify(emailSender).send(sent.capture());
        assertThat(sent.getValue().to()).isEqualTo("ana@firma.ro");
        assertThat(sent.getValue().fromEmail()).isEqualTo("firma@contabil.ro");
        assertThat(sent.getValue().fromName()).isEqualTo("Contabil SRL");
        assertThat(sent.getValue().body()).contains("type=recovery");
        verify(audit).record("PASSWORD_RESET_REQUESTED", "user", USER);
    }

    /**
     * TenantAwareEmailSender picks the SMTP provider from TenantContext, so the tenant MUST be bound at
     * send time — otherwise the mail silently falls back to the platform sender, defeating the feature.
     */
    @Test
    void bindsTheResolvedTenantWhileSending() {
        knownUser("ana@firma.ro");
        AtomicReference<UUID> boundAtSendTime = new AtomicReference<>();
        doAnswer(inv -> {
            boundAtSendTime.set(TenantContext.tenantId().orElse(null));
            return null;
        }).when(emailSender).send(any());

        service.requestReset("ana@firma.ro");

        assertThat(boundAtSendTime.get()).isEqualTo(TENANT);
        assertThat(TenantContext.current()).isEmpty();   // and cleared afterwards
    }

    /** Anti-enumeration: an unknown address must produce no mail and no error. */
    @Test
    void unknownAddressSendsNothingAndDoesNotThrow() {
        when(directory.findByEmailAcrossTenants("nobody@nowhere.ro")).thenReturn(List.of());

        assertThatCode(() -> service.requestReset("nobody@nowhere.ro")).doesNotThrowAnyException();

        verify(emailSender, never()).send(any());
        verify(inviter, never()).generateSetPasswordLink(any());
    }

    /** A delivery failure must not surface — the response is 204 either way. */
    @Test
    void swallowsDeliveryFailures() {
        knownUser("ana@firma.ro");
        doThrow(new IllegalStateException("SMTP down")).when(emailSender).send(any());

        assertThatCode(() -> service.requestReset("ana@firma.ro")).doesNotThrowAnyException();

        assertThat(TenantContext.current()).isEmpty();   // still cleaned up
        verify(audit, never()).record(any(), any(), any());   // nothing was delivered
    }

    /** A link-minting failure is equally invisible to the caller. */
    @Test
    void swallowsLinkGenerationFailures() {
        when(directory.findByEmailAcrossTenants("ana@firma.ro"))
                .thenReturn(List.of(new AuthUserDirectory.TenantUser(USER, TENANT)));
        when(inviter.generateSetPasswordLink("ana@firma.ro"))
                .thenThrow(new IllegalStateException("generate_link failed"));

        assertThatCode(() -> service.requestReset("ana@firma.ro")).doesNotThrowAnyException();

        verify(emailSender, never()).send(any());
    }

    @Test
    void ignoresABlankEmailWithoutTouchingTheDirectory() {
        assertThatCode(() -> service.requestReset("   ")).doesNotThrowAnyException();
        verify(directory, never()).findByEmailAcrossTenants(any());
    }
}
