package ro.myfinance.access.adapter.external;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import ro.myfinance.common.config.SupabaseProperties;
import ro.myfinance.access.application.UserInviter;

/**
 * Creates users in Supabase Auth (GoTrue) via admin REST: POST /auth/v1/admin/users mints the user with
 * app_metadata {tenant_id, role, company_id}, which the custom access-token hook lifts into top-level JWT
 * claims read by the backend. No email is ever sent by GoTrue — the application delivers its own branded
 * invitation carrying a recovery link from {@link #generateSetPasswordLink}.
 *
 * If the email is already a Supabase auth user (e.g. this person already represents another company, or
 * a leftover from a reused project), GoTrue rejects the create with "already registered". Rather than
 * failing, we look the existing user up by email and (re)apply the claims with a PUT. The returned
 * {@code created=false} then tells the caller NOT to compensate-delete a user it did not create.
 */
public class SupabaseUserInviter implements UserInviter {

    private final RestClient client;
    /** The app's public URL, used as {@code redirect_to} so set-password links open the app
     *  instead of Supabase's default Site URL. Blank = fall back to the project's Site URL. */
    private final String appUrl;

    public SupabaseUserInviter(SupabaseProperties props, RestClient.Builder builder, String appUrl) {
        this.appUrl = appUrl == null ? "" : appUrl.trim();
        this.client = builder
                .baseUrl(props.url())
                .defaultHeader("apikey", props.serviceRoleKey())
                .defaultHeader("Authorization", "Bearer " + props.serviceRoleKey())
                .build();
    }

    @Override
    public InvitedUser provision(String email, InviteClaims claims) {
        // Staff (admin/employee) have no company; only representatives carry a company_id claim.
        Map<String, Object> appMetadata = appMetadata(claims);
        try {
            // Admin create-user does NOT send an email, so it isn't email-rate-limited. email_confirm=true
            // marks the address confirmed; the user sets a password via the branded recovery link.
            GoTrueUser created = client.post()
                    .uri("/auth/v1/admin/users")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email, "email_confirm", true, "app_metadata", appMetadata))
                    .retrieve()
                    .body(GoTrueUser.class);
            if (created == null || created.id() == null) {
                throw new IllegalStateException("Supabase admin create-user returned no user id");
            }
            return new InvitedUser(created.id(), true);
        } catch (HttpClientErrorException e) {
            if (!isAlreadyRegistered(e)) {
                throw e;
            }
            UUID userId = findUserIdByEmail(email);   // reuse the existing auth user and (re)apply claims
            client.put()
                    .uri("/auth/v1/admin/users/{id}", userId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("app_metadata", appMetadata))
                    .retrieve()
                    .toBodilessEntity();
            return new InvitedUser(userId, false);
        }
    }

    private static Map<String, Object> appMetadata(InviteClaims claims) {
        Map<String, Object> m = new HashMap<>();
        m.put("tenant_id", claims.tenantId().toString());
        m.put("role", claims.role().name());
        if (claims.companyId() != null) {
            m.put("company_id", claims.companyId().toString());
        }
        return m;
    }

    @Override
    public String generateSetPasswordLink(String email) {
        // Admin generate_link mints a recovery ("set your password") action link WITHOUT sending an email,
        // so the app can deliver its own branded invitation. type=recovery is what makes supabase-js raise
        // PASSWORD_RECOVERY in the browser and force the set-password screen. redirect_to opens the app.
        Map<String, Object> body = new HashMap<>();
        body.put("type", "recovery");
        body.put("email", email);
        if (!appUrl.isBlank()) {
            body.put("redirect_to", appUrl);
        }
        GenerateLinkResponse resp = client.post()
                .uri("/auth/v1/admin/generate_link")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(GenerateLinkResponse.class);
        if (resp == null || resp.actionLink() == null || resp.actionLink().isBlank()) {
            throw new IllegalStateException("Supabase generate_link returned no action_link for " + email);
        }
        return resp.actionLink();
    }

    @Override
    public void updateEmail(UUID externalUserId, String newEmail) {
        // Admin PUT with email_confirm=true sets the address without a re-confirmation round-trip.
        client.put()
                .uri("/auth/v1/admin/users/{id}", externalUserId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", newEmail, "email_confirm", true))
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public void delete(UUID externalUserId) {
        try {
            client.delete()
                    .uri("/auth/v1/admin/users/{id}", externalUserId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound alreadyGone) {
            // Idempotent: the auth user is already absent — nothing to compensate.
        }
    }

    /** GoTrue signals a duplicate email with a 4xx whose body carries an "already registered" message. */
    private static boolean isAlreadyRegistered(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString().toLowerCase(Locale.ROOT);
        return body.contains("already been registered") || body.contains("already registered")
                || body.contains("user_already_exists") || body.contains("email_exists");
    }

    /** Resolve an email to its auth-user id by paging the GoTrue admin user list. */
    private UUID findUserIdByEmail(String email) {
        for (int page = 1; page <= 20; page++) {
            final int pg = page;
            UsersPage resp = client.get()
                    .uri(b -> b.path("/auth/v1/admin/users")
                            .queryParam("page", pg).queryParam("per_page", 200).build())
                    .retrieve()
                    .body(UsersPage.class);
            if (resp == null || resp.users() == null || resp.users().isEmpty()) {
                break;
            }
            for (AdminUser u : resp.users()) {
                if (u.id() != null && email.equalsIgnoreCase(u.email())) {
                    return u.id();
                }
            }
            if (resp.users().size() < 200) {
                break;   // last page
            }
        }
        throw new IllegalStateException("Email already registered but no matching auth user found: " + email);
    }

    record GoTrueUser(UUID id) {}
    record UsersPage(List<AdminUser> users) {}
    record AdminUser(UUID id, String email) {}
    record GenerateLinkResponse(@com.fasterxml.jackson.annotation.JsonProperty("action_link") String actionLink) {}
}
