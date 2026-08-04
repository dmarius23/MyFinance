package ro.myfinance.access.adapter.web;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.access.application.GdprService;

/**
 * GDPR subject-rights endpoints for a tenant's users. TENANT_ADMIN only, and tenant-scoped by RLS (a user
 * of another tenant is not found). Kept under {@code /api/v1/users} — {@code /api/v1/admin/**} is the
 * SUPER_ADMIN (platform) surface at the security filter.
 */
@RestController
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class GdprController {

    private final GdprService gdpr;

    public GdprController(GdprService gdpr) {
        this.gdpr = gdpr;
    }

    /** Subject Access Request: everything held about the user, as JSON. */
    @GetMapping("/api/v1/users/{userId}/gdpr/export")
    public GdprService.UserDataExport export(@PathVariable UUID userId) {
        return gdpr.export(userId);
    }

    /** Right to erasure: anonymize the user's personal data and deactivate the account. */
    @DeleteMapping("/api/v1/users/{userId}/gdpr")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anonymize(@PathVariable UUID userId) {
        gdpr.anonymize(userId);
    }
}
