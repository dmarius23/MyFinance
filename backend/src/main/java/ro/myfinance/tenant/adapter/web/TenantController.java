package ro.myfinance.tenant.adapter.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.tenant.adapter.web.TenantDtos.ChangeStatusRequest;
import ro.myfinance.tenant.adapter.web.TenantDtos.CreateTenantRequest;
import ro.myfinance.tenant.adapter.web.TenantDtos.TenantResponse;
import ro.myfinance.tenant.application.TenantService;

/** MOD-01 — platform tenant administration. SUPER_ADMIN only. */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class TenantController {

    private final TenantService service;

    public TenantController(TenantService service) {
        this.service = service;
    }

    @GetMapping
    public List<TenantResponse> list() {
        return service.list().stream().map(TenantResponse::from).toList();
    }

    @GetMapping("/{id}")
    public TenantResponse get(@PathVariable UUID id) {
        return TenantResponse.from(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantResponse create(@Valid @RequestBody CreateTenantRequest request) {
        return TenantResponse.from(service.create(request.name(), request.cui(), request.plan()));
    }

    @PatchMapping("/{id}/status")
    public TenantResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest request) {
        return TenantResponse.from(service.changeStatus(id, request.status()));
    }
}
