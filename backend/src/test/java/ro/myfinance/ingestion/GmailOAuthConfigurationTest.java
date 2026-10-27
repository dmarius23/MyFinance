package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
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
}
