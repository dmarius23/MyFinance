package ro.myfinance.ingestion.adapter.external;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Provides short-lived Google access tokens from service-account credentials. Google Auth Library owns
 * JWT construction, signing, token exchange and expiry handling; callers pass only the required scope.
 */
@Component
public class GoogleDriveTokens {

    private final GoogleIngestionProperties props;
    private final ConcurrentMap<String, GoogleCredentials> credentials = new ConcurrentHashMap<>();

    public GoogleDriveTokens(GoogleIngestionProperties props) {
        this.props = props;
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    /** Get an access token for {@code scope} (e.g. {@code https://www.googleapis.com/auth/drive}). */
    public String accessToken(String scope) {
        if (!props.isConfigured()) {
            throw new IllegalStateException("Google service account not configured");
        }
        try {
            GoogleCredentials googleCredentials = credentials.computeIfAbsent(scope, this::loadCredentials);
            googleCredentials.refreshIfExpired();
            AccessToken token = googleCredentials.getAccessToken();
            if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
                googleCredentials.refresh();
                token = googleCredentials.getAccessToken();
            }
            if (token == null || token.getTokenValue() == null || token.getTokenValue().isBlank()) {
                throw new IllegalStateException("Google did not return an access token");
            }
            return token.getTokenValue();
        } catch (Exception e) {
            throw new IllegalStateException("Google service-account authentication failed", e);
        }
    }

    private GoogleCredentials loadCredentials(String scope) {
        try {
            String privateKey = props.privateKey().replace("\\n", "\n");
            Map<String, Object> serviceAccount = Map.of(
                    "type", "service_account",
                    "private_key_id", "managed-by-myfinance",
                    "private_key", privateKey,
                    "client_email", props.clientEmail(),
                    "client_id", "managed-by-myfinance",
                    "token_uri", "https://oauth2.googleapis.com/token");
            byte[] json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsBytes(serviceAccount);
            return GoogleCredentials.fromStream(new ByteArrayInputStream(json))
                    .createScoped(List.of(scope));
        } catch (IOException e) {
            throw new IllegalStateException("Google service-account credentials are invalid", e);
        }
    }
}
