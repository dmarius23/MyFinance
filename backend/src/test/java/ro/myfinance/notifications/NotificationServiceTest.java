package ro.myfinance.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.notifications.adapter.persistence.NotificationRepository;
import ro.myfinance.notifications.application.NotificationService;
import ro.myfinance.notifications.domain.Notification;
import ro.myfinance.access.application.EmailEnvelopeService;
import ro.myfinance.common.email.EmailSender;

/** Rep-upload notification routing: accountant gets in-app + email; unassigned → admins in-app only. */
class NotificationServiceTest {

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final EmailEnvelopeService envelopes = mock(EmailEnvelopeService.class);
    private final EmailSender sender = mock(EmailSender.class);
    private final ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository repLinks =
            mock(ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository.class);
    private final ro.myfinance.notifications.application.PushNotificationService push =
            mock(ro.myfinance.notifications.application.PushNotificationService.class);
    private final NotificationService service =
            new NotificationService(notifications, companies, users, envelopes, sender, repLinks, push);

    private final UUID tenant = UUID.randomUUID();
    private final UUID repId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID accId = UUID.randomUUID();

    @BeforeEach
    void bind() {
        // The rep is the current user during their upload.
        TenantContext.set(new TenantContext.Identity(tenant, repId, Role.REPRESENTATIVE, companyId));
        lenient().when(notifications.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(envelopes.system(any())).thenAnswer(i ->
                new EmailEnvelopeService.Envelope("MyFinance", "firma@contabil.ro", i.getArgument(0)));
        lenient().when(users.findById(repId)).thenReturn(Optional.of(
                new AppUser(repId, tenant, "ion@client.ro", "Ion Rep", Role.REPRESENTATIVE)));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private Company company(UUID responsible) {
        Company c = mock(Company.class);
        when(c.getLegalName()).thenReturn("Client SRL");
        when(c.getResponsibleUserId()).thenReturn(responsible);
        return c;
    }

    @Test
    void notifiesAndEmailsResponsibleAccountant() {
        Company c = company(accId);
        when(companies.findById(companyId)).thenReturn(Optional.of(c));
        when(users.findById(accId)).thenReturn(Optional.of(
                new AppUser(accId, tenant, "maria@contabil.ro", "Maria Acc", Role.EMPLOYEE)));

        service.documentUploadedByRep(companyId, UUID.randomUUID(), "bon.pdf");

        ArgumentCaptor<Notification> n = ArgumentCaptor.forClass(Notification.class);
        verify(notifications).save(n.capture());
        assertThat(n.getValue().getRecipientUserId()).isEqualTo(accId);
        assertThat(n.getValue().getBody()).contains("Ion Rep").contains("Client SRL").contains("bon.pdf");

        ArgumentCaptor<EmailSender.Message> m = ArgumentCaptor.forClass(EmailSender.Message.class);
        verify(sender).send(m.capture());
        assertThat(m.getValue().to()).isEqualTo("maria@contabil.ro");
        assertThat(m.getValue().subject()).contains("Client SRL");
    }

    @Test
    void unassignedNotifiesAdminsInAppNoEmail() {
        Company c = company(null);
        when(companies.findById(companyId)).thenReturn(Optional.of(c));
        when(users.findByRoleIn(List.of(Role.TENANT_ADMIN))).thenReturn(List.of(
                new AppUser(UUID.randomUUID(), tenant, "a1@firma.ro", "Admin 1", Role.TENANT_ADMIN),
                new AppUser(UUID.randomUUID(), tenant, "a2@firma.ro", "Admin 2", Role.TENANT_ADMIN)));

        service.documentUploadedByRep(companyId, UUID.randomUUID(), "factura.pdf");

        verify(notifications, times(2)).save(any(Notification.class));
        verify(sender, never()).send(any(EmailSender.Message.class));
    }

    @Test
    void unreadCountAndMarkReadAreScopedToCurrentUser() {
        TenantContext.set(new TenantContext.Identity(tenant, accId, Role.EMPLOYEE, null));
        when(notifications.countByRecipientUserIdAndReadAtIsNull(accId)).thenReturn(4L);
        assertThat(service.unreadCount()).isEqualTo(4L);

        Notification own = new Notification(tenant, accId, "DOCUMENT_UPLOADED", "t", "b", companyId, "Client", null);
        UUID nid = UUID.randomUUID();
        when(notifications.findById(nid)).thenReturn(Optional.of(own));
        service.markRead(nid);
        assertThat(own.getReadAt()).isNotNull();
    }
}
