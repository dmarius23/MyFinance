package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ro.myfinance.common.outbox.OutboxMessage;
import ro.myfinance.ingestion.application.GmailIngestionService;
import ro.myfinance.ingestion.application.GmailSyncOutboxHandler;

class GmailSyncOutboxHandlerTest {

    private final GmailIngestionService ingestion = mock(GmailIngestionService.class);
    private final GmailSyncOutboxHandler handler =
            new GmailSyncOutboxHandler(ingestion, new ObjectMapper());

    @Test
    void dispatchesConnectionAndHistoryCursorToSyncService() {
        UUID connectionId = UUID.randomUUID();
        String payload = handler.serialize(new GmailSyncOutboxHandler.Payload(connectionId, "history-42"));
        OutboxMessage message = new OutboxMessage(UUID.randomUUID(), "email_connection",
                connectionId.toString(), GmailSyncOutboxHandler.TYPE, payload);

        handler.handle(message);

        verify(ingestion).sync(connectionId, "history-42");
    }

    @Test
    void propagatesSyncFailureSoRelayCanRetry() {
        UUID connectionId = UUID.randomUUID();
        doThrow(new IllegalStateException("Gmail unavailable"))
                .when(ingestion).sync(connectionId, "history-42");
        String payload = handler.serialize(new GmailSyncOutboxHandler.Payload(connectionId, "history-42"));
        OutboxMessage message = new OutboxMessage(UUID.randomUUID(), "email_connection",
                connectionId.toString(), GmailSyncOutboxHandler.TYPE, payload);

        assertThatThrownBy(() -> handler.handle(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payload could not be processed");
    }
}
