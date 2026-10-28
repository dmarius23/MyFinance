package ro.myfinance.ingestion.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import ro.myfinance.common.crypto.SecretCipher;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.ConflictException;
import ro.myfinance.common.web.MisconfiguredException;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.ingestion.adapter.external.GmailOAuthProperties;
import ro.myfinance.ingestion.adapter.persistence.EmailConnectionRepository;
import ro.myfinance.ingestion.domain.EmailConnection;

/** Server-side Gmail OAuth authorization-code exchange with one-time, tenant-bound state. */
@Service
public class GmailOAuthService {

    private static final String AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailConnectionRepository connections;
    private final GmailOAuthProperties properties;
    private final SecretCipher cipher;
    private final RestClient http;
    private final ObjectMapper json;

    public GmailOAuthService(EmailConnectionRepository connections, GmailOAuthProperties properties,
                             SecretCipher cipher, RestClient.Builder builder, ObjectMapper json) {
        this.connections = connections;
        this.properties = properties;
        this.cipher = cipher;
        this.http = builder.build();
        this.json = json;
    }

    public AuthorizationStart start(UUID connectionId) {
        ensureConfigured();
        EmailConnection connection = ownedConnection(connectionId);
        String state = randomState();
        connection.setOauthStateHash(hash(state));
        connection.setOauthStateExpiresAt(Instant.now().plus(Duration.ofMinutes(10)));
        connections.save(connection);

        String url = UriComponentsBuilder.fromUriString(AUTHORIZATION_ENDPOINT)
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("response_type", "code")
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("scope", GMAIL_READONLY)
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
        return new AuthorizationStart(connectionId, url, Instant.now().plus(Duration.ofMinutes(10)));
    }

    public void complete(UUID connectionId, String state, String authorizationCode) {
        ensureConfigured();
        if (state == null || state.isBlank() || authorizationCode == null || authorizationCode.isBlank()) {
            throw new IllegalArgumentException("OAuth state and authorization code are required");
        }
        EmailConnection connection = ownedConnection(connectionId);
        if (!hash(state).equals(connection.getOauthStateHash())
                || connection.getOauthStateExpiresAt() == null
                || connection.getOauthStateExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Invalid or expired Gmail OAuth state");
        }

        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", authorizationCode);
        form.add("grant_type", "authorization_code");
        form.add("redirect_uri", properties.redirectUri());
        String response;
        try {
            response = http.post()
                    .uri(TOKEN_ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw googleRejected("token exchange", e);
        }
        TokenResponse tokens = tokens(response);
        String refreshToken = tokens.refreshToken();
        String mailbox = mailboxAddress(tokens.accessToken());
        // (tenant_id, mailbox_address) is UNIQUE. Reconnecting the same mailbox through a fresh
        // pending row would otherwise fail as an opaque 500 at save time.
        connections.findByMailboxAddress(mailbox)
                .filter(existing -> !existing.getId().equals(connection.getId()))
                .ifPresent(existing -> {
                    throw new ConflictException(mailbox + " is already connected. Disconnect the "
                            + "existing connection before linking this mailbox again.");
                });
        connection.setRefreshTokenEncrypted(cipher.encrypt(refreshToken));
        connection.setMailboxAddress(mailbox);
        connection.setDisplayName(mailbox);
        connection.setOauthStateHash(null);
        connection.setOauthStateExpiresAt(null);
        connection.setStatus("ACTIVE");
        connection.setLastResult("Gmail OAuth connected");
        connections.save(connection);
    }

    private EmailConnection ownedConnection(UUID connectionId) {
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound"));
        return connections.findById(connectionId)
                .filter(connection -> tenantId.equals(connection.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Email connection not found"));
    }

    /**
     * A deployment that never provisioned the Google OAuth client is an operator problem, not a server
     * fault — surface it as 503 naming the missing settings, so "Connect Gmail" explains itself instead of
     * answering an opaque 500. Public so the caller can fail before it persists a pending connection.
     */
    public void ensureConfigured() {
        if (!properties.isConfigured()) {
            throw new MisconfiguredException("Gmail OAuth is not configured — set GMAIL_CLIENT_ID, "
                    + "GMAIL_CLIENT_SECRET and GMAIL_REDIRECT_URI");
        }
        if (!cipher.isConfigured()) {
            throw new MisconfiguredException("Server secret key (MYFINANCE_SECRET_KEY) is not configured "
                    + "— cannot store the Gmail refresh token");
        }
    }

    private TokenResponse tokens(String response) {
        try {
            JsonNode node = json.readTree(response);
            String refreshToken = node.path("refresh_token").asText(null);
            String accessToken = node.path("access_token").asText(null);
            if (refreshToken == null || refreshToken.isBlank()
                    || accessToken == null || accessToken.isBlank()) {
                // Google omits refresh_token when the mailbox already granted this client and the
                // request did not force a fresh consent. Without it we cannot sync unattended, so
                // this is a hard failure — but an actionable one, not a server fault.
                throw new ConflictException("Google did not return a refresh token. Remove MyFinance "
                        + "from the account's third-party access (myaccount.google.com/permissions) "
                        + "and connect again.");
            }
            return new TokenResponse(refreshToken, accessToken);
        } catch (RuntimeException e) {
            // Only JSON parsing is being guarded here. Our own mapped exceptions carry the actionable
            // message and must pass through untouched — re-wrapping them would undo the mapping and
            // put the caller back at an opaque 500.
            throw e;
        } catch (Exception e) {
            throw new MisconfiguredException("Google returned an unreadable token response");
        }
    }

    /**
     * Translates a Google error response into something an operator can act on. Google states the
     * problem precisely — {@code invalid_client} for a wrong client secret, {@code SERVICE_DISABLED}
     * when the Gmail API was never enabled on the project — and that text is the whole value here, so
     * it is passed through rather than collapsed into a generic failure. Never includes our request,
     * so the client secret cannot be echoed back.
     *
     * <p>An expired or replayed authorization code is the user's problem (click Connect again), so it
     * maps to 400; everything else is a deployment gap and maps to 503.
     */
    private RuntimeException googleRejected(String stage, RestClientResponseException e) {
        String code = "";
        String detail = "";
        try {
            JsonNode node = json.readTree(e.getResponseBodyAsString());
            // OAuth errors: {"error":"invalid_client","error_description":"..."}
            // API errors:   {"error":{"status":"PERMISSION_DENIED","message":"..."}}
            code = node.path("error").isTextual()
                    ? node.path("error").asText("")
                    : node.path("error").path("status").asText("");
            detail = node.path("error_description").asText(
                    node.path("error").path("message").asText(""));
        } catch (Exception ignored) {
            // Non-JSON body (e.g. an HTML error page) — fall back to the status line below.
        }
        String summary = (code.isBlank() ? e.getStatusText() : code)
                + (detail.isBlank() ? "" : " — " + detail);
        if ("invalid_grant".equals(code)) {
            return new IllegalArgumentException(
                    "Google rejected the authorization code (" + summary + "). It expires quickly and is "
                            + "single-use — start the Gmail connection again.");
        }
        return new MisconfiguredException("Google rejected the Gmail " + stage + ": " + summary);
    }

    private String mailboxAddress(String accessToken) {
        String response;
        try {
            response = http.get()
                    .uri("https://gmail.googleapis.com/gmail/v1/users/me/profile")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw googleRejected("profile lookup", e);
        }
        try {
            String email = json.readTree(response).path("emailAddress").asText(null);
            if (email == null || email.isBlank()) {
                throw new MisconfiguredException("Google did not return the Gmail address");
            }
            return email.trim().toLowerCase();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new MisconfiguredException("Google returned an unreadable Gmail profile response");
        }
    }

    private static String randomState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash OAuth state", e);
        }
    }

    public record AuthorizationStart(UUID connectionId, String authorizationUrl, Instant expiresAt) {
    }

    private record TokenResponse(String refreshToken, String accessToken) {
    }
}
