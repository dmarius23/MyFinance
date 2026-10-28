package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import ro.myfinance.ingestion.adapter.external.GmailOAuthProperties;
import ro.myfinance.ingestion.adapter.external.GmailPubSubTokenVerifier;

class GmailPubSubTokenVerifierTest {

    @Test
    void rejectsMissingBearerTokenBeforeDecoding() {
        GmailOAuthProperties properties = new GmailOAuthProperties(
                "client", "secret", "https://localhost/callback", "topic", null,
                "audience", "pubsub@example.com");
        GmailPubSubTokenVerifier verifier = new GmailPubSubTokenVerifier(properties,
                mock(org.springframework.security.oauth2.jwt.JwtDecoder.class));

        assertThatThrownBy(() -> verifier.verify(null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Missing Pub/Sub bearer token");
    }

    @Test
    void rejectsConfigurationWithoutAudience() {
        GmailOAuthProperties properties = new GmailOAuthProperties(
                "client", "secret", "https://localhost/callback", "topic", null,
                "", "pubsub@example.com");
        GmailPubSubTokenVerifier verifier = new GmailPubSubTokenVerifier(properties,
                mock(org.springframework.security.oauth2.jwt.JwtDecoder.class));

        assertThatThrownBy(() -> verifier.verify("Bearer token"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("audience is not configured");
    }

    /**
     * The audience is not an authenticator: any Google service account can mint an OIDC token for
     * an arbitrary audience, so without pinning the caller's identity this endpoint would accept
     * forged Gmail notifications from anyone with a GCP account. Must fail closed, and must do so
     * before the token is decoded.
     */
    @Test
    void rejectsConfigurationWithoutServiceAccountEvenWhenAudienceIsSet() {
        GmailOAuthProperties properties = new GmailOAuthProperties(
                "client", "secret", "https://localhost/callback", "topic", null,
                "https://api.example.ro/api/v1/ingestion/gmail/pubsub", "");
        GmailPubSubTokenVerifier verifier = new GmailPubSubTokenVerifier(properties,
                mock(org.springframework.security.oauth2.jwt.JwtDecoder.class));

        assertThatThrownBy(() -> verifier.verify("Bearer token"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("GMAIL_PUBSUB_SERVICE_ACCOUNT");
    }
}
