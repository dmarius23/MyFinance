package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ro.myfinance.common.crypto.SecretCipher;
import ro.myfinance.common.web.MisconfiguredException;
import ro.myfinance.ingestion.adapter.external.GmailOAuthProperties;
import ro.myfinance.ingestion.adapter.persistence.EmailConnectionRepository;
import ro.myfinance.ingestion.adapter.web.EmailConnectionController;
import ro.myfinance.ingestion.application.EmailConnectionService;
import ro.myfinance.ingestion.application.GmailIngestionService;
import ro.myfinance.ingestion.application.GmailMailboxClient;
import ro.myfinance.ingestion.application.GmailOAuthService;

/**
 * A deployment that never provisioned the Google OAuth client must say so, not answer an opaque 500 —
 * and must not leave a half-built connection behind when it does.
 */
class GmailOAuthConfigurationTest {

    private static final GmailOAuthProperties UNCONFIGURED =
            new GmailOAuthProperties("", "", "", null, null, null, null);
    private static final GmailOAuthProperties CONFIGURED = new GmailOAuthProperties(
            "client-id", "client-secret", "https://app.example.ro/callback", null, null, null, null);

    /** Any valid base64 32-byte AES key — only needs to make SecretCipher report "configured". */
    private static final String KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    private final EmailConnectionRepository connections = mock(EmailConnectionRepository.class);

    private GmailOAuthService oauth(GmailOAuthProperties properties, String secretKey) {
        return new GmailOAuthService(connections, properties, new SecretCipher(secretKey),
                RestClient.builder(), new ObjectMapper());
    }

    @Test
    void missingOAuthClientIsReportedAsMisconfiguredNotServerError() {
        assertThatThrownBy(() -> oauth(UNCONFIGURED, "").start(UUID.randomUUID()))
                .isInstanceOf(MisconfiguredException.class)
                .hasMessageContaining("GMAIL_CLIENT_ID");
    }

    @Test
    void missingSecretKeyIsReportedAsMisconfiguredNotServerError() {
        assertThatThrownBy(() -> oauth(CONFIGURED, "").start(UUID.randomUUID()))
                .isInstanceOf(MisconfiguredException.class)
                .hasMessageContaining("MYFINANCE_SECRET_KEY");
    }

    /**
     * The config check has to run before the pending row is written: {@code createPending} commits in its
     * own transaction, so checking afterwards leaks a {@code pending+<uuid>@invalid} connection on every
     * click — rows the UI filters out of the list and nobody ever sees again.
     */
    @Test
    void misconfiguredStartLeavesNoPendingConnectionBehind() {
        EmailConnectionService connectionService = mock(EmailConnectionService.class);
        EmailConnectionController controller = new EmailConnectionController(connectionService,
                oauth(UNCONFIGURED, ""), mock(GmailIngestionService.class), mock(GmailMailboxClient.class));

        assertThatThrownBy(controller::startNewOAuth).isInstanceOf(MisconfiguredException.class);

        verify(connectionService, never()).createPending();
    }

    /**
     * Every upstream failure in complete() used to surface as an opaque 500, which is what made the
     * production Gmail callback impossible to diagnose from the UI. Google states the cause precisely;
     * these pin that text to an actionable status instead of swallowing it.
     */
    @Test
    void googleErrorsAreMappedToActionableStatusesNotServerErrors() {
        GmailOAuthService svc = oauth(CONFIGURED, KEY);

        // Wrong client secret -> OAuth-style body {"error":"invalid_client",...}
        assertThatThrownBy(() -> reject(svc, 401,
                "{\"error\":\"invalid_client\",\"error_description\":\"Unauthorized\"}"))
                .isInstanceOf(MisconfiguredException.class)
                .hasMessageContaining("invalid_client");

        // Gmail API never enabled -> API-style body {"error":{"status":...,"message":...}}
        assertThatThrownBy(() -> reject(svc, 403,
                "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"Gmail API has not been used\"}}"))
                .isInstanceOf(MisconfiguredException.class)
                .hasMessageContaining("Gmail API has not been used");

        // Replayed/expired code is the user's problem, not the operator's -> 400, not 503.
        assertThatThrownBy(() -> reject(svc, 400, "{\"error\":\"invalid_grant\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(MisconfiguredException.class);

        // Non-JSON body (HTML error page) must still map, not blow up in the parser.
        assertThatThrownBy(() -> reject(svc, 502, "<html>bad gateway</html>"))
                .isInstanceOf(MisconfiguredException.class);
    }

    /** Invokes the private googleRejected(..) mapper with a synthetic Google error response. */
    private static void reject(GmailOAuthService svc, int status, String body) throws Exception {
        var m = GmailOAuthService.class.getDeclaredMethod(
                "googleRejected", String.class, RestClientResponseException.class);
        m.setAccessible(true);
        RestClientResponseException ex = new RestClientResponseException(
                "err", status, "Status " + status, null, body.getBytes(StandardCharsets.UTF_8), null);
        throw (RuntimeException) m.invoke(svc, "token exchange", ex);
    }
}
