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
}
