package ro.myfinance.access.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.RepresentativeLink;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.settings.application.SettingsService;

/**
 * Resolves the participants for any client email, identically across every channel (tax payments,
 * document reminders, payroll):
 * <ul>
 *   <li><b>From name</b> — the logged-in user's name.</li>
 *   <li><b>From email</b> — the accounting firm's configured sender address (general settings).</li>
 *   <li><b>Recipient</b> — the company's representative, unless the caller overrides it.</li>
 * </ul>
 * All lookups are RLS-scoped to the caller's tenant.
 */
@Service
@Transactional(readOnly = true)
public class EmailEnvelopeService {

    private final AppUserRepository users;
    private final RepresentativeLinkRepository links;
    private final SettingsService settings;

    public EmailEnvelopeService(AppUserRepository users, RepresentativeLinkRepository links,
                                SettingsService settings) {
        this.users = users;
        this.links = links;
        this.settings = settings;
    }

    /** From name + email + recipient for a company. {@code recipientOverride} wins over the representative. */
    public record Envelope(String fromName, String fromEmail, String recipient) {
    }

    public Envelope resolve(UUID companyId, String recipientOverride) {
        String recipient = recipientOverride != null && !recipientOverride.isBlank()
                ? recipientOverride.trim()
                : representativeEmail(companyId);
        return new Envelope(currentUserName(), settings.senderEmail(), recipient);
    }

    /**
     * From/To for a <b>system-generated</b> email (e.g. the "new upload" notification to the accountant):
     * the From name is the product, not a logged-in user, but the From address stays the firm's configured
     * sender so every outbound email shares one identity/domain. The recipient is passed in (internal staff).
     */
    public Envelope system(String recipient) {
        return new Envelope("MyFinance", settings.senderEmail(), recipient);
    }

    /** The logged-in user's display name (falls back to their email, then null). */
    public String currentUserName() {
        return TenantContext.current().map(TenantContext.Identity::userId)
                .flatMap(users::findById)
                .map(u -> u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getEmail())
                .orElse(null);
    }

    /** The email of the company's representative (the first REPRESENTATIVE linked to it), or null. */
    public String representativeEmail(UUID companyId) {
        List<UUID> userIds = links.findByCompanyId(companyId).stream()
                .map(RepresentativeLink::getUserId).toList();
        if (userIds.isEmpty()) {
            return null;
        }
        return users.findAllById(userIds).stream()
                .filter(u -> u.getRole() == Role.REPRESENTATIVE)
                .map(AppUser::getEmail)
                .filter(e -> e != null && !e.isBlank())
                .findFirst()
                .orElse(null);
    }
}
