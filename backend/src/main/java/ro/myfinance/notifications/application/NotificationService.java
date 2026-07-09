package ro.myfinance.notifications.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.notifications.adapter.persistence.NotificationRepository;
import ro.myfinance.notifications.domain.Notification;
import ro.myfinance.settings.application.SettingsService;
import ro.myfinance.taxpayments.application.EmailSender;

/**
 * MOD-09 Notifications. Creates in-app notifications for firm staff and dispatches the matching email.
 * The headline trigger: a representative uploads a document → notify the responsible accountant (or all
 * admins if unassigned) in-app, and email the accountant. Tenant-scoped via RLS.
 */
@Service
@Transactional
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String DOCUMENT_UPLOADED = "DOCUMENT_UPLOADED";

    private final NotificationRepository notifications;
    private final CompanyRepository companies;
    private final AppUserRepository users;
    private final SettingsService settings;
    private final EmailSender sender;
    private final ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository repLinks;
    private final PushNotificationService push;

    public NotificationService(NotificationRepository notifications, CompanyRepository companies,
                               AppUserRepository users, SettingsService settings, EmailSender sender,
                               ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository repLinks,
                               PushNotificationService push) {
        this.notifications = notifications;
        this.companies = companies;
        this.users = users;
        this.settings = settings;
        this.sender = sender;
        this.repLinks = repLinks;
        this.push = push;
    }

    /**
     * Persist one in-app notification and fire a matching Web Push to the recipient's devices. The single
     * choke-point every trigger goes through, so adding push here covers all notification types at once.
     * Push is best-effort (see {@link PushNotificationService#dispatchToUser}) — the in-app row is the
     * source of truth.
     */
    private void persist(Notification n) {
        notifications.save(n);
        push.dispatchToUser(n.getRecipientUserId(), n.getTitle(), n.getBody());
    }

    /** In-app notification to every representative of a company (e.g. a document request or a new report). */
    public void notifyCompanyReps(UUID companyId, String type, String title, String body) {
        try {
            UUID tenantId = TenantContext.tenantId().orElseThrow();
            String companyName = companies.findById(companyId).map(Company::getLegalName).orElse(null);
            for (var link : repLinks.findByCompanyId(companyId)) {
                persist(new Notification(tenantId, link.getUserId(), type, title, body,
                        companyId, companyName, null));
            }
        } catch (RuntimeException e) {
            log.warn("Failed to notify reps of company {}", companyId, e);
        }
    }

    public record NotificationView(UUID id, String type, String title, String body, UUID companyId,
                                   String companyName, UUID documentId, java.time.Instant readAt,
                                   java.time.Instant createdAt) {
        static NotificationView from(Notification n) {
            return new NotificationView(n.getId(), n.getType(), n.getTitle(), n.getBody(), n.getCompanyId(),
                    n.getCompanyName(), n.getDocumentId(), n.getReadAt(), n.getCreatedAt());
        }
    }

    /**
     * A representative uploaded {@code documentId} for their company. Notify the responsible accountant
     * in-app + by email; if no accountant is assigned, notify all firm admins in-app. Never breaks the
     * upload — failures are logged.
     */
    public void documentUploadedByRep(UUID companyId, UUID documentId, String filename) {
        try {
            UUID tenantId = TenantContext.tenantId().orElseThrow();
            Company company = companies.findById(companyId).orElse(null);
            String companyName = company == null ? "—" : company.getLegalName();
            String repName = TenantContext.current().map(TenantContext.Identity::userId)
                    .flatMap(users::findById).map(AppUser::getName).orElse("Reprezentant");

            String title = "Document nou de la client";
            String body = repName + " (" + companyName + ") a încărcat: " + filename;

            UUID responsibleId = company == null ? null : company.getResponsibleUserId();
            AppUser accountant = responsibleId == null ? null : users.findById(responsibleId).orElse(null);

            List<AppUser> recipients = new ArrayList<>();
            if (accountant != null) {
                recipients.add(accountant);
            } else {
                recipients.addAll(users.findByRoleIn(List.of(Role.TENANT_ADMIN)));
            }
            for (AppUser r : recipients) {
                persist(new Notification(tenantId, r.getId(), DOCUMENT_UPLOADED, title, body,
                        companyId, companyName, documentId));
            }

            // Email the accountant (the PWA-triggered notification email).
            if (accountant != null && accountant.getEmail() != null) {
                String from = settings.senderEmail();
                sender.send(new EmailSender.Message("MyFinance", from, accountant.getEmail(),
                        title + " — " + companyName, body, List.of()));
            }
        } catch (RuntimeException e) {
            log.warn("Failed to create upload notification for document {}", documentId, e);
        }
    }

    /** In-app nudge when a task is assigned to a staff member (no email). No-op for self-assignment. */
    public void taskAssigned(UUID assigneeUserId, String taskTitle, UUID companyId, String companyName) {
        UUID self = TenantContext.current().map(TenantContext.Identity::userId).orElse(null);
        if (assigneeUserId == null || assigneeUserId.equals(self)) {
            return;
        }
        try {
            UUID tenantId = TenantContext.tenantId().orElseThrow();
            persist(new Notification(tenantId, assigneeUserId, "TASK_ASSIGNED",
                    "Sarcină nouă", taskTitle, companyId, companyName, null));
        } catch (RuntimeException e) {
            log.warn("Failed to create task-assigned notification for {}", assigneeUserId, e);
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationView> list() {
        return notifications.findTop50ByRecipientUserIdOrderByCreatedAtDesc(currentUser())
                .stream().map(NotificationView::from).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount() {
        return notifications.countByRecipientUserIdAndReadAtIsNull(currentUser());
    }

    public void markRead(UUID id) {
        Notification n = notifications.findById(id).orElseThrow(() -> new NotFoundException("Notification not found"));
        if (!n.getRecipientUserId().equals(currentUser())) {
            throw new NotFoundException("Notification not found");
        }
        n.markRead();
    }

    public void markAllRead() {
        notifications.markAllRead(currentUser());
    }

    private UUID currentUser() {
        return TenantContext.current().map(TenantContext.Identity::userId)
                .orElseThrow(() -> new IllegalStateException("No user bound"));
    }
}
