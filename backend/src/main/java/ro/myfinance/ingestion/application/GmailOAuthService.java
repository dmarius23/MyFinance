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
import org.springframework.web.util.UriComponentsBuilder;
import ro.myfinance.common.crypto.SecretCipher;
import ro.myfinance.common.security.TenantContext;
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
        requireConfigured();
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
        requireConfigured();
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
        String response = http.post()
                .uri(TOKEN_ENDPOINT)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        TokenResponse tokens = tokens(response);
        String refreshToken = tokens.refreshToken();
        connection.setRefreshTokenEncrypted(cipher.encrypt(refreshToken));
        connection.setMailboxAddress(mailboxAddress(tokens.accessToken()));
        connection.setDisplayName(connection.getMailboxAddress());
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

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("Gmail OAuth is not configured");
        }
        if (!cipher.isConfigured()) {
            throw new IllegalStateException("Secret encryption is not configured");
        }
    }

    private TokenResponse tokens(String response) {
        try {
            JsonNode node = json.readTree(response);
            String refreshToken = node.path("refresh_token").asText(null);
            String accessToken = node.path("access_token").asText(null);
            if (refreshToken == null || refreshToken.isBlank()
                    || accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("Google did not return a refresh token");
            }
            return new TokenResponse(refreshToken, accessToken);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("Invalid Google token response", e);
        }
    }

    private String mailboxAddress(String accessToken) {
        String response = http.get()
                .uri("https://gmail.googleapis.com/gmail/v1/users/me/profile")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(String.class);
        try {
            String email = json.readTree(response).path("emailAddress").asText(null);
            if (email == null || email.isBlank()) {
                throw new IllegalStateException("Google did not return the Gmail address");
            }
            return email.trim().toLowerCase();
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("Invalid Gmail profile response", e);
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
