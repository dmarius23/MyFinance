package ro.myfinance.access;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.application.AuthUserCleanup;
import ro.myfinance.access.application.EmailEnvelopeService;
import ro.myfinance.access.application.RepresentativeService;
import ro.myfinance.access.application.UserInviter;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.common.audit.AuditRecorder;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.application.CompanyDirectory;
import ro.myfinance.company.domain.Company;

/**
 * Invite atomicity (S5): the external auth user is created before the local persistence, so if a local
 * write fails the created auth user must be compensated (durably scheduled for deletion) — never orphaned.
 */
class RepresentativeServiceTest {

    private final CompanyDirectory companies = mock(CompanyDirectory.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final RepresentativeLinkRepository links = mock(RepresentativeLinkRepository.class);
    private final UserInviter inviter = mock(UserInviter.class);
    private final AuthUserCleanup authCleanup = mock(AuthUserCleanup.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final ro.myfinance.tenant.application.TenantDirectory tenants =
            mock(ro.myfinance.tenant.application.TenantDirectory.class);
    private final EmailEnvelopeService envelope = mock(EmailEnvelopeService.class);
    private final ro.myfinance.common.email.EmailSender emailSender =
            mock(ro.myfinance.common.email.EmailSender.class);
    private final RepresentativeService service =
            new RepresentativeService(companies, users, links, inviter, authCleanup, audit,
                    tenants, envelope, emailSender);

    private final UUID tenant = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID externalId = UUID.randomUUID();

    @BeforeEach
    void bind() {
        TenantContext.set(new TenantContext.Identity(tenant, UUID.randomUUID(), Role.TENANT_ADMIN, null));
        when(companies.findById(companyId)).thenReturn(Optional.of(mock(Company.class)));
        when(users.findByEmail("rep@client.ro")).thenReturn(Optional.empty()); // new invite path
        // New reps are always PROVISIONED (no identity-provider email); the branded invite is sent by us.
        when(inviter.provision(any(), any())).thenReturn(new UserInviter.InvitedUser(externalId, true));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void happyPathPersistsAndNeverCompensates() {
        when(users.saveAndFlush(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));

        AppUser rep = service.inviteRepresentative(companyId, "Rep One", "rep@client.ro", "0712345678");

        org.assertj.core.api.Assertions.assertThat(rep.getId()).isEqualTo(externalId);
        verify(authCleanup, never()).scheduleDelete(any());
    }

    @Test
    void addingRepInAppAutoSendsTheBrandedInvite() {
        when(users.saveAndFlush(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));
        when(tenants.current()).thenReturn(Optional.of(
                new ro.myfinance.tenant.application.TenantDirectory.CurrentTenant("Contabil Expert SRL", "RO1")));
        when(inviter.generateSetPasswordLink("rep@client.ro")).thenReturn("https://app.example/set#token");
        when(envelope.system("rep@client.ro"))
                .thenReturn(new EmailEnvelopeService.Envelope("MyFinance", "noreply@firma.ro", "rep@client.ro"));

        service.inviteRepresentative(companyId, "Rep One", "rep@client.ro", "0712345678"); // in-app add → sendEmail=true

        verify(inviter).provision(any(), any());
        verify(inviter, never()).invite(any(), any()); // no identity-provider email
        var captor = org.mockito.ArgumentCaptor.forClass(ro.myfinance.common.email.EmailSender.Message.class);
        verify(emailSender).send(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().body())
                .contains("https://app.example/set#token");
    }

    @Test
    void addingRepSucceedsEvenIfTheInviteEmailFails() {
        when(users.saveAndFlush(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));
        when(inviter.generateSetPasswordLink("rep@client.ro"))
                .thenThrow(new IllegalStateException("SMTP down"));

        // Best-effort delivery: the rep is still created; the accountant can re-send via "Send invite".
        AppUser rep = service.inviteRepresentative(companyId, "Rep One", "rep@client.ro", "0712345678");

        org.assertj.core.api.Assertions.assertThat(rep.getId()).isEqualTo(externalId);
        verify(authCleanup, never()).scheduleDelete(any()); // email failure must not compensate the rep
    }

    @Test
    void persistFailureSchedulesDeletionOfTheOrphanedAuthUser() {
        when(users.saveAndFlush(any(AppUser.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

        assertThatThrownBy(() ->
                service.inviteRepresentative(companyId, "Rep One", "rep@client.ro", "0712345678"))
                .isInstanceOf(RuntimeException.class);

        // The just-created auth user must be scheduled for deletion — no orphan.
        verify(authCleanup).scheduleDelete(externalId);
    }

    /** A representative assigned to the company, resolvable by requireRepOfCompany. */
    private AppUser assignedRep(String email) {
        AppUser rep = new AppUser(externalId, tenant, email, "Rep One", Role.REPRESENTATIVE);
        when(links.existsByUserIdAndCompanyId(externalId, companyId)).thenReturn(true);
        when(users.findById(externalId)).thenReturn(Optional.of(rep));
        return rep;
    }

    @Test
    void updateChangesEmailAndSyncsTheAuthIdentity() {
        AppUser rep = assignedRep("old@client.ro");
        when(users.findByEmail("new@client.ro")).thenReturn(Optional.empty());

        service.updateRepresentative(companyId, externalId, "Rep One", "0712345678", "new@client.ro");

        org.assertj.core.api.Assertions.assertThat(rep.getEmail()).isEqualTo("new@client.ro");
        verify(inviter).updateEmail(externalId, "new@client.ro"); // login identity synced
    }

    @Test
    void updateRejectsAnEmailAlreadyUsedByAnotherUser() {
        AppUser rep = assignedRep("old@client.ro");
        AppUser other = new AppUser(UUID.randomUUID(), tenant, "taken@client.ro", "Someone", Role.REPRESENTATIVE);
        when(users.findByEmail("taken@client.ro")).thenReturn(Optional.of(other));

        assertThatThrownBy(() ->
                service.updateRepresentative(companyId, externalId, "Rep One", null, "taken@client.ro"))
                .isInstanceOf(ro.myfinance.common.web.ConflictException.class);

        org.assertj.core.api.Assertions.assertThat(rep.getEmail()).isEqualTo("old@client.ro"); // unchanged
        verify(inviter, never()).updateEmail(any(), any());
    }

    @Test
    void updateWithTheSameEmailDoesNotTouchAuth() {
        assignedRep("rep@client.ro");

        service.updateRepresentative(companyId, externalId, "Rep One", "0712345678", "rep@client.ro");

        verify(inviter, never()).updateEmail(any(), any());
    }

    @Test
    void sendInviteDeliversABrandedEmailWithTheSetPasswordLink() {
        assignedRep("rep@client.ro");
        Company company = mock(Company.class);
        when(company.getLegalName()).thenReturn("ACME SRL");
        when(companies.findById(companyId)).thenReturn(Optional.of(company));
        when(tenants.current()).thenReturn(Optional.of(
                new ro.myfinance.tenant.application.TenantDirectory.CurrentTenant("Contabil Expert SRL", "RO123")));
        when(inviter.generateSetPasswordLink("rep@client.ro"))
                .thenReturn("https://app.example/set#token");
        when(envelope.system("rep@client.ro"))
                .thenReturn(new EmailEnvelopeService.Envelope("MyFinance", "noreply@firma.ro", "rep@client.ro"));

        service.sendInvite(companyId, externalId);

        // No generic provider email; instead the app sends its own branded message with the link.
        verify(inviter).generateSetPasswordLink("rep@client.ro");
        var captor = org.mockito.ArgumentCaptor.forClass(ro.myfinance.common.email.EmailSender.Message.class);
        verify(emailSender).send(captor.capture());
        var msg = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(msg.to()).isEqualTo("rep@client.ro");
        org.assertj.core.api.Assertions.assertThat(msg.fromName()).isEqualTo("Contabil Expert SRL");
        org.assertj.core.api.Assertions.assertThat(msg.fromEmail()).isEqualTo("noreply@firma.ro");
        org.assertj.core.api.Assertions.assertThat(msg.subject()).contains("MyFinance", "Contabil Expert SRL");
        org.assertj.core.api.Assertions.assertThat(msg.body())
                .contains("https://app.example/set#token", "Contabil Expert SRL", "ACME SRL", "MyFinance");
    }
}
