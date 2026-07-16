package ro.myfinance.ingestion.adapter.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Mints short-lived Google access tokens from the service-account key (JWT-bearer grant). Shared by the
 * read-only ingestion connector and the Drive document writer — the caller passes the OAuth scope
 * ({@code drive.readonly} for reads, {@code drive} for writes).
 */
@Component
public class GoogleDriveTokens {

    private final GoogleIngestionProperties props;
    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();

    public GoogleDriveTokens(GoogleIngestionProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.build();
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    /** Mint an access token for {@code scope} (e.g. {@code https://www.googleapis.com/auth/drive}). */
    public String accessToken(String scope) {
        if (!props.isConfigured()) {
            throw new IllegalStateException("Google service account not configured");
        }
        long now = Instant.now().getEpochSecond();
        String header = b64(bytes("{\"alg\":\"RS256\",\"typ\":\"JWT\"}"));
        String claims = b64(bytes("{\"iss\":\"" + props.clientEmail() + "\",\"scope\":\"" + scope
                + "\",\"aud\":\"https://oauth2.googleapis.com/token\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}"));
        String signingInput = header + "." + claims;
        String jwt = signingInput + "." + b64(sign(signingInput.getBytes(StandardCharsets.UTF_8)));
        String resp = http.post().uri("https://oauth2.googleapis.com/token")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=" + jwt)
                .retrieve().body(String.class);
        try {
            return json.readTree(resp).path("access_token").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Google token exchange failed", e);
        }
    }

    private byte[] sign(byte[] data) {
        try {
            String pem = props.privateKey().replace("\\n", "\n")
                    .replaceAll("-----BEGIN (.*)-----", "").replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s", "");
            var key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign Google JWT", e);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
