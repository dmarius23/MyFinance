package ro.myfinance.access.adapter.web;

import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.access.application.RepresentativeService;

/**
 * Tenant-level representative summary — returns every company's representatives in one call,
 * used by the Companies list page to display reps alongside company data without N+1 requests.
 */
@RestController
@RequestMapping("/api/v1/representatives")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class AllRepresentativesController {

    private final RepresentativeService service;

    public AllRepresentativesController(RepresentativeService service) {
        this.service = service;
    }

    /** All representatives grouped by company: [{companyId, id, name, email, status}]. */
    @GetMapping
    public List<CompanyRepEntry> listAll() {
        return service.listAllRepresentatives();
    }

    public record CompanyRepEntry(UUID companyId, UUID id, String name, String email, String status) {
    }
}
