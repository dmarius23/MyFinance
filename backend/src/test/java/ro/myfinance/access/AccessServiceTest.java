package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.application.AccessService;
import ro.myfinance.access.application.AuthUserCleanup;
import ro.myfinance.access.application.EmailEnvelopeService;
import ro.myfinance.access.application.UserInviter;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.UserStatus;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.email.EmailSender;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.NotFoundException;

/** Activate re-enables a user; the last active admin can't be deactivated. */
class AccessServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final RepresentativeLinkRepository links = mock(RepresentativeLinkRepository.class);
    private final UserInviter inviter = mock(UserInviter.class);
    private final AuthUserCleanup authCleanup = mock(AuthUserCleanup.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final EmailEnvelopeService envelope = mock(EmailEnvelopeService.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final AccessService service =
            new AccessService(users, links, inviter, authCleanup, audit, envelope, emailSender);

    @Test
    void activateSetsStatusActive() {
        UUID id = UUID.randomUUID();
        AppUser u = new AppUser(id, UUID.randomUUID(), "x@firma.ro", "Ana", Role.EMPLOYEE);
        u.setStatus(UserStatus.INACTIVE);
        when(users.findById(id)).thenReturn(Optional.of(u));

        assertThat(service.activate(id).getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void inviteUsesSupabaseIdAndMarksInvited() {
        withTenant(() -> {
            UUID externalId = UUID.randomUUID();
            when(users.existsByEmail("nou@firma.ro")).thenReturn(false);
            when(inviter.provision(eq("nou@firma.ro"), any()))
                    .thenReturn(new UserInviter.InvitedUser(externalId, true));
            when(users.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));
            when(inviter.generateSetPasswordLink("nou@firma.ro")).thenReturn("https://app.example/#type=recovery");
            when(envelope.system("nou@firma.ro"))
                    .thenReturn(new EmailEnvelopeService.Envelope("MyFinance", "firma@contabil.ro", "nou@firma.ro"));

            AppUser u = service.inviteUser("nou@firma.ro", "Nou Contabil", Role.EMPLOYEE);
            assertThat(u.getId()).isEqualTo(externalId); // app_user id == Supabase subject → recognized on login
            assertThat(u.getStatus()).isEqualTo(UserStatus.INVITED); // the email went out
        });
    }

    /**
     * The regression this guards: staff used to be invited with the identity provider's own
     * {@code /auth/v1/invite} email, whose link carries {@code type=invite}. supabase-js only raises
     * PASSWORD_RECOVERY for {@code type=recovery}, so the invitee was signed straight into the app and
     * never asked for a password — then locked out for good at the next sign-in. Staff must therefore get
     * the same branded <em>recovery</em> ("set your password") link representatives get.
     */
    @Test
    void inviteSendsBrandedSetPasswordLinkAndNoProviderEmail() {
        withTenant(() -> {
            when(users.existsByEmail("nou@firma.ro")).thenReturn(false);
            when(inviter.provision(eq("nou@firma.ro"), any()))
                    .thenReturn(new UserInviter.InvitedUser(UUID.randomUUID(), true));
            when(users.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));
            when(inviter.generateSetPasswordLink("nou@firma.ro"))
                    .thenReturn("https://app.example/#access_token=x&type=recovery");
            when(envelope.system("nou@firma.ro"))
                    .thenReturn(new EmailEnvelopeService.Envelope("MyFinance", "firma@contabil.ro", "nou@firma.ro"));
            when(envelope.firmName()).thenReturn("Contabil SRL");

            service.inviteUser("nou@firma.ro", "Nou Contabil", Role.EMPLOYEE);

            ArgumentCaptor<EmailSender.Message> sent = ArgumentCaptor.forClass(EmailSender.Message.class);
            verify(emailSender).send(sent.capture());
            assertThat(sent.getValue().to()).isEqualTo("nou@firma.ro");
            assertThat(sent.getValue().fromName()).isEqualTo("Contabil SRL");
            assertThat(sent.getValue().body()).contains("type=recovery");
            verify(inviter).generateSetPasswordLink("nou@firma.ro");
        });
    }

    /** A delivery failure must not undo the user we just created — it's recoverable via "resend". */
    @Test
    void inviteSurvivesAnEmailDeliveryFailure() {
        withTenant(() -> {
            UUID externalId = UUID.randomUUID();
            when(users.existsByEmail("nou@firma.ro")).thenReturn(false);
            when(inviter.provision(eq("nou@firma.ro"), any()))
                    .thenReturn(new UserInviter.InvitedUser(externalId, true));
            when(users.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));
            when(inviter.generateSetPasswordLink("nou@firma.ro"))
                    .thenThrow(new IllegalStateException("SMTP down"));

            AppUser u = service.inviteUser("nou@firma.ro", "Nou Contabil", Role.EMPLOYEE);

            assertThat(u.getId()).isEqualTo(externalId);
            verify(authCleanup, never()).scheduleDelete(any()); // the user is fine; only the email failed
            // Nothing reached them, so they stay in the "still needs an invite" bucket.
            assertThat(u.getStatus()).isEqualTo(UserStatus.PENDING_INVITE);
        });
    }

    /** A failed local save must not leave an orphaned auth user behind. */
    @Test
    void inviteSchedulesCleanupWhenPersistFails() {
        withTenant(() -> {
            UUID externalId = UUID.randomUUID();
            when(users.existsByEmail("nou@firma.ro")).thenReturn(false);
            when(inviter.provision(eq("nou@firma.ro"), any()))
                    .thenReturn(new UserInviter.InvitedUser(externalId, true));
            when(users.save(any(AppUser.class))).thenThrow(new IllegalStateException("constraint"));

            assertThatThrownBy(() -> service.inviteUser("nou@firma.ro", "Nou", Role.EMPLOYEE))
                    .isInstanceOf(IllegalStateException.class);

            verify(authCleanup).scheduleDelete(externalId);
            verify(emailSender, never()).send(any());
        });
    }

    /** Never compensate-delete an auth user we reused rather than created. */
    @Test
    void inviteDoesNotDeleteAReusedAuthUserWhenPersistFails() {
        withTenant(() -> {
            UUID externalId = UUID.randomUUID();
            when(users.existsByEmail("nou@firma.ro")).thenReturn(false);
            when(inviter.provision(eq("nou@firma.ro"), any()))
                    .thenReturn(new UserInviter.InvitedUser(externalId, false));
            when(users.save(any(AppUser.class))).thenThrow(new IllegalStateException("constraint"));

            assertThatThrownBy(() -> service.inviteUser("nou@firma.ro", "Nou", Role.EMPLOYEE))
                    .isInstanceOf(IllegalStateException.class);

            verify(authCleanup, never()).scheduleDelete(any());
        });
    }

    @Test
    void inviteRejectsNonStaffRole() {
        assertThatThrownBy(() -> service.inviteUser("x@y.ro", "X", Role.REPRESENTATIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** "Resend invite" — the only way back in for staff whose link expired or was never usable. */
    @Test
    void sendInviteMailsAFreshSetPasswordLinkAndAudits() {
        UUID id = UUID.randomUUID();
        AppUser staff = new AppUser(id, UUID.randomUUID(), "ana@firma.ro", "Ana", Role.EMPLOYEE);
        staff.setStatus(UserStatus.PENDING_INVITE);
        when(users.findById(id)).thenReturn(Optional.of(staff));
        when(inviter.generateSetPasswordLink("ana@firma.ro"))
                .thenReturn("https://app.example/#access_token=y&type=recovery");
        when(envelope.system("ana@firma.ro"))
                .thenReturn(new EmailEnvelopeService.Envelope("MyFinance", "firma@contabil.ro", "ana@firma.ro"));
        when(envelope.firmName()).thenReturn("Contabil SRL");

        service.sendInvite(id);

        ArgumentCaptor<EmailSender.Message> sent = ArgumentCaptor.forClass(EmailSender.Message.class);
        verify(emailSender).send(sent.capture());
        assertThat(sent.getValue().to()).isEqualTo("ana@firma.ro");
        assertThat(sent.getValue().body()).contains("type=recovery");
        assertThat(staff.getStatus()).isEqualTo(UserStatus.INVITED); // delivered → no longer pending
        verify(audit).record("STAFF_INVITE_SENT", "user", id);
    }

    /** Re-sending a link to someone who already signed in must not knock them back to INVITED. */
    @Test
    void sendInviteLeavesAnActiveUserActive() {
        UUID id = UUID.randomUUID();
        AppUser staff = new AppUser(id, UUID.randomUUID(), "ana@firma.ro", "Ana", Role.EMPLOYEE);
        staff.setStatus(UserStatus.ACTIVE);
        when(users.findById(id)).thenReturn(Optional.of(staff));
        when(inviter.generateSetPasswordLink("ana@firma.ro")).thenReturn("https://app.example/#type=recovery");
        when(envelope.system("ana@firma.ro"))
                .thenReturn(new EmailEnvelopeService.Envelope("MyFinance", "firma@contabil.ro", "ana@firma.ro"));

        service.sendInvite(id);

        assertThat(staff.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    /** Representatives are re-invited through RepresentativeService, which scopes them to a company. */
    @Test
    void sendInviteRejectsARepresentative() {
        UUID id = UUID.randomUUID();
        AppUser rep = new AppUser(id, UUID.randomUUID(), "rep@client.ro", "Rep", Role.REPRESENTATIVE);
        when(users.findById(id)).thenReturn(Optional.of(rep));

        assertThatThrownBy(() -> service.sendInvite(id)).isInstanceOf(IllegalArgumentException.class);
        verify(emailSender, never()).send(any());
    }

    @Test
    void sendInviteRejectsAnUnknownUser() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sendInvite(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void cannotDeactivateLastActiveAdmin() {
        UUID id = UUID.randomUUID();
        AppUser admin = new AppUser(id, UUID.randomUUID(), "admin@firma.ro", "Admin", Role.TENANT_ADMIN);
        when(users.findById(id)).thenReturn(Optional.of(admin));
        when(users.countByRoleAndStatus(Role.TENANT_ADMIN, UserStatus.ACTIVE)).thenReturn(1L);

        assertThatThrownBy(() -> service.deactivate(id)).isInstanceOf(ConflictException.class);
    }

    /** Invites read the tenant from the request context — never from the client. */
    private static void withTenant(Runnable body) {
        TenantContext.set(new TenantContext.Identity(UUID.randomUUID(), UUID.randomUUID(), Role.TENANT_ADMIN, null));
        try {
            body.run();
        } finally {
            TenantContext.clear();
        }
    }
}
