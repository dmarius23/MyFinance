package ro.myfinance.ingestion.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ro.myfinance.common.outbox.OutboxHandler;
import ro.myfinance.common.outbox.OutboxMessage;

/** Worker-side handler for durable Gmail sync requests. */
@Component
public class GmailSyncOutboxHandler implements OutboxHandler {

    public static final String TYPE = "SYNC_GMAIL";
    public static final String AGGREGATE = "email_connection";

    private final GmailIngestionService ingestion;
    private final ObjectMapper json;

    public GmailSyncOutboxHandler(GmailIngestionService ingestion, ObjectMapper json) {
        this.ingestion = ingestion;
        this.json = json;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(OutboxMessage message) {
        try {
            Payload payload = json.readValue(message.getPayload(), Payload.class);
            ingestion.sync(payload.connectionId(), payload.historyId());
        } catch (Exception e) {
            throw new IllegalStateException("Gmail sync payload could not be processed", e);
        }
    }

    public String serialize(Payload payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Gmail sync payload could not be serialized", e);
        }
    }

    public record Payload(UUID connectionId, String historyId) {
    }
}
