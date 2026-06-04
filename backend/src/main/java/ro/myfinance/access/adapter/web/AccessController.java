package ro.myfinance.access.adapter.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ro.myfinance.access.adapter.web.AccessDtos.InviteUserRequest;
import ro.myfinance.access.adapter.web.AccessDtos.LinkRepRequest;
import ro.myfinance.access.adapter.web.AccessDtos.SetRoleRequest;
import ro.myfinance.access.adapter.web.AccessDtos.UserResponse;
import ro.myfinance.access.application.AccessService;

/** MOD-02 — user & access management within the caller's tenant. Tenant-admin only. */
@RestController
@RequestMapping("/api/v1/users")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class AccessController {

    private final AccessService service;

    public AccessController(AccessService service) {
        this.service = service;
    }

    @GetMapping
    public List<UserResponse> list() {
        return service.listUsers().stream().map(UserResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse invite(@Valid @RequestBody InviteUserRequest request) {
        return UserResponse.from(service.inviteUser(request.email(), request.name(), request.role()));
    }

    @PutMapping("/{id}/role")
    public UserResponse setRole(@PathVariable UUID id, @Valid @RequestBody SetRoleRequest request) {
        return UserResponse.from(service.setRole(id, request.role()));
    }

    @PostMapping("/{id}/deactivate")
    public UserResponse deactivate(@PathVariable UUID id) {
        return UserResponse.from(service.deactivate(id));
    }

    @PostMapping("/representative-links")
    @ResponseStatus(HttpStatus.CREATED)
    public void linkRepresentative(@Valid @RequestBody LinkRepRequest request) {
        service.linkRepresentative(request.userId(), request.companyId());
    }
}
