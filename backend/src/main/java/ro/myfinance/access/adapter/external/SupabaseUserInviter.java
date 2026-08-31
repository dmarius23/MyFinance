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
 * Invites a representative through Supabase Auth (GoTrue) admin REST:
 *   1. POST /auth/v1/invite  -> creates the user, sends the invite email, returns the id
 *   2. PUT  /auth/v1/admin/users/{id} -> sets app_metadata {tenant_id, role, company_id}, which the
 *      custom access-token hook lifts into top-level JWT claims read by the backend.
 *
 * If the email is already a Supabase auth user (e.g. this person already represents another company, or
 * a leftover from a reused project), GoTrue rejects the invite with "already registered". Rather than
 * failing, we look the existing user up by email and reuse it (step 2 still (re)applies the claims). The
 * returned {@code created=false} then tells the caller NOT to compensate-delete a user it did not create.
 */
public class SupabaseUserInviter implements UserInviter {

    private final RestClient client;
    /** The app's public URL, used as {@code redirect_to} so invite / set-password links open the app
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
    public InvitedUser invite(String email, InviteClaims claims) {
        boolean created = true;
        UUID userId;
        try {
            GoTrueUser invited = client.post()
                    .uri(b -> withRedirect(b.path("/auth/v1/invite")).build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email", email))
                    .retrieve()
                    .body(GoTrueUser.class);
            if (invited == null || invited.id() == null) {
                throw new IllegalStateException("Supabase invite returned no user id");
            }
            userId = invited.id();
        } catch (HttpClientErrorException e) {
            if (!isAlreadyRegistered(e)) {
                throw e;
            }
            userId = findUserIdByEmail(email);   // reuse the existing auth user
            created = false;
        }

        // Staff (admin/employee) have no company; only representatives carry a company_id claim.
        client.put()
                .uri("/auth/v1/admin/users/{id}", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("app_metadata", appMetadata(claims)))
                .retrieve()
                .toBodilessEntity();

        return new InvitedUser(userId, created);
    }

    @Override
    public InvitedUser provision(String email, InviteClaims claims) {
        Map<String, Object> appMetadata = appMetadata(claims);
        try {
            // Admin create-user does NOT send an email (unlike /invite), so it isn't email-rate-limited.
            // email_confirm=true marks the address confirmed; the rep gets a password via reset/magic-link later.
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

    /** Add {@code redirect_to=<appUrl>} so the email link opens the app; no-op when appUrl is blank. */
    private org.springframework.web.util.UriBuilder withRedirect(org.springframework.web.util.UriBuilder b) {
        return appUrl.isBlank() ? b : b.queryParam("redirect_to", appUrl);
    }

    @Override
    public void sendInvite(String email) {
        // A password-recovery ("set your password") email — works for a user that already exists (unlike
        // /invite, which rejects an already-registered address). The redirect opens the app.
        client.post()
                .uri(b -> withRedirect(b.path("/auth/v1/recover")).build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email))
                .retrieve()
                .toBodilessEntity();
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
}
