package ro.myfinance.access.adapter.external;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.access.application.RepresentativeInviter;

/**
 * Invites a representative through Supabase Auth (GoTrue) admin REST:
 *   1. POST /auth/v1/invite  -> creates the user, sends the invite email, returns the id
 *   2. PUT  /auth/v1/admin/users/{id} -> sets app_metadata {tenant_id, role, company_id}, which the
 *      custom access-token hook lifts into top-level JWT claims read by the backend.
 */
public class SupabaseRepresentativeInviter implements RepresentativeInviter {

    private final RestClient client;

    public SupabaseRepresentativeInviter(SupabaseProperties props, RestClient.Builder builder) {
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

        client.put()
                .uri("/auth/v1/admin/users/{id}", created.id())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("app_metadata", Map.of(
                        "tenant_id", claims.tenantId().toString(),
                        "role", claims.role().name(),
                        "company_id", claims.companyId().toString())))
                .retrieve()
                .toBodilessEntity();

        return new InvitedUser(created.id());
    }

    record GoTrueUser(UUID id) {}
}
