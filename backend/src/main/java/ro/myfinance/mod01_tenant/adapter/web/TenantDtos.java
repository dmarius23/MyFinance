package ro.myfinance.mod01_tenant.adapter.web;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import ro.myfinance.mod01_tenant.domain.Tenant;
import ro.myfinance.mod01_tenant.domain.TenantStatus;

/** Request/response payloads for the tenant-admin API. */
public final class TenantDtos {

    private TenantDtos() {
    }

    public record CreateTenantRequest(@NotBlank String name, String cui, String plan) {
    }

    public record ChangeStatusRequest(@jakarta.validation.constraints.NotNull TenantStatus status) {
    }

    public record TenantResponse(UUID id, String name, String cui, TenantStatus status,
                                 String plan, Instant createdAt) {
        public static TenantResponse from(Tenant t) {
            return new TenantResponse(t.getId(), t.getName(), t.getCui(), t.getStatus(),
                    t.getPlan(), t.getCreatedAt());
        }
    }
}
