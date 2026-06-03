package ro.myfinance.mod02_access.adapter.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.mod02_access.adapter.web.RepresentativeDtos.InviteRepresentativeRequest;
import ro.myfinance.mod02_access.adapter.web.RepresentativeDtos.RepresentativeResponse;
import ro.myfinance.mod02_access.application.RepresentativeService;

/** MOD-02 — representatives of a company. Firm staff only. */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/representatives")
@PreAuthorize("hasAnyRole('TENANT_ADMIN', 'EMPLOYEE')")
public class RepresentativeController {

    private final RepresentativeService service;

    public RepresentativeController(RepresentativeService service) {
        this.service = service;
    }

    @GetMapping
    public List<RepresentativeResponse> list(@PathVariable UUID companyId) {
        return service.listRepresentatives(companyId).stream().map(RepresentativeResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RepresentativeResponse invite(@PathVariable UUID companyId,
                                         @Valid @RequestBody InviteRepresentativeRequest request) {
        return RepresentativeResponse.from(
                service.inviteRepresentative(companyId, request.email(), request.name()));
    }
}
