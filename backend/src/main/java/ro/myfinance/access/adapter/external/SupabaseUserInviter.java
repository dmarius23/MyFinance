package ro.myfinance.access.adapter.external;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.access.application.UserInviter;

/**
 * Invites a representative through Supabase Auth (GoTrue) admin REST:
 *   1. POST /auth/v1/invite  -> creates the user, sends the invite email, returns the id
 *   2. PUT  /auth/v1/admin/users/{id} -> sets app_metadata {tenant_id, role, company_id}, which the
 *      custom access-token hook lifts into top-level JWT claims read by the backend.
 */
public class SupabaseUserInviter implements UserInviter {

    private final RestClient client;

    public SupabaseUserInviter(SupabaseProperties props, RestClient.Builder builder) {
        this.client = builder
                .baseUrl(props.url())
                .defaultHeader("apikey", props.serviceRoleKey())
                .defaultHeader("Authorization", "Bearer " + props.serviceRoleKey())
                .build();
    }

    @Override
    public InvitedUser invite(String email, InviteClaims claims) {
        GoTrueUser created = client.post()
                .uri("/auth/v1/invite")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email))
                .retrieve()
                .body(GoTrueUser.class);

        if (created == null || created.id() == null) {
            throw new IllegalStateException("Supabase invite returned no user id");
        }

        // Staff (admin/employee) have no company; only representatives carry a company_id claim.
        Map<String, Object> appMetadata = new java.util.HashMap<>();
        appMetadata.put("tenant_id", claims.tenantId().toString());
        appMetadata.put("role", claims.role().name());
        if (claims.companyId() != null) {
            appMetadata.put("company_id", claims.companyId().toString());
        }
        client.put()
                .uri("/auth/v1/admin/users/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("app_metadata", appMetadata))
                .retrieve()
                .toBodilessEntity();

        return new InvitedUser(created.id());
    }

    record GoTrueUser(UUID id) {}
}
