package ro.myfinance.access.adapter.web;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.access.application.AccessService;

/**
 * Firm-staff directory (id + name), readable by any staff member — used to populate assignee pickers
 * (e.g. internal tasks). Unlike the admin-only user management endpoint, this is available to employees.
 */
@RestController
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class StaffController {

    private final AccessService access;

    public StaffController(AccessService access) {
        this.access = access;
    }

    @GetMapping("/api/v1/staff")
    public List<StaffMember> list() {
        return access.listStaff().stream()
                .map(u -> new StaffMember(u.getId(), u.getName(), u.getRole().name()))
                .toList();
    }

    public record StaffMember(UUID id, String name, String role) {
    }
}
