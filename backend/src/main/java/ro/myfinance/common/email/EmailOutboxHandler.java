package ro.myfinance.common.email;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ro.myfinance.common.outbox.OutboxHandler;
import ro.myfinance.common.outbox.OutboxMessage;

/**
 * Delivers a queued email when the outbox relay drains a {@code SEND_EMAIL} message: deserializes the
 * stored {@link EmailSender.Message} and hands it to the {@link EmailSender} transport. When the payload
 * carries a {@code historyId} (the client-facing module emails), delivery also flips that
 * {@link EmailHistory} row to SENT — or to FAILED on permanent failure (DLQ) so the module's notification
 * log reflects it. Emails with no history row (e.g. the internal upload notification) carry a null
 * {@code historyId} and are simply delivered. Idempotent — the relay only delivers claimed rows.
 */
@Component
public class EmailOutboxHandler implements OutboxHandler {

    /** Outbox message type + the aggregate type for email sends. */
    public static final String TYPE = "SEND_EMAIL";
    public static final String AGGREGATE = "email";

    private final EmailSender sender;
    private final EmailHistoryRepository history;
    private final ObjectMapper mapper;

    public EmailOutboxHandler(EmailSender sender, EmailHistoryRepository history, ObjectMapper mapper) {
        this.sender = sender;
        this.history = history;
        this.mapper = mapper;
    }

    /**
     * The serialized outbox payload: the fully-addressed message to send, plus an optional
     * {@code historyId}. Present → delivery flips that {@link EmailHistory} row to SENT/FAILED; null (e.g.
     * an internal notification email with no per-module history) → the message is just delivered.
     */
    public record Payload(UUID historyId, EmailSender.Message message) {
    }

    public String serialize(Payload payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize email outbox payload", e);
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(OutboxMessage message) {
        Payload payload = parse(message);
        sender.send(payload.message()); // throws on transport failure → relay retries
        if (payload.historyId() != null) {
            history.findById(payload.historyId()).ifPresent(EmailHistory::markSent);
        }
    }

    @Override
    public void onExhausted(OutboxMessage message) {
        Payload payload = parse(message);
        if (payload.historyId() != null) {
            history.findById(payload.historyId())
                    .ifPresent(h -> h.markFailed("Delivery failed after retries: " + message.getError()));
        }
    }

    private Payload parse(OutboxMessage message) {
        try {
            return mapper.readValue(message.getPayload(), Payload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed SEND_EMAIL payload for message " + message.getId(), e);
        }
    }
}
