package ro.myfinance.common.whatsapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ro.myfinance.common.outbox.OutboxHandler;
import ro.myfinance.common.outbox.OutboxMessage;

/**
 * Delivers a queued WhatsApp message when the outbox relay drains a {@code SEND_WHATSAPP} message —
 * mirrors {@code EmailOutboxHandler}, reusing the same durable outbox (retries, DLQ, single-flight) and
 * the dedicated {@link WhatsAppMessage} store. On success the message row flips to SENT;
 * on permanent failure (DLQ) it flips to FAILED so the module's WhatsApp log reflects it.
 */
@Component
public class WhatsAppOutboxHandler implements OutboxHandler {

    public static final String TYPE = "SEND_WHATSAPP";
    public static final String AGGREGATE = "whatsapp";

    private final WhatsAppSender sender;
    private final WhatsAppMessageRepository messages;
    private final ObjectMapper mapper;

    public WhatsAppOutboxHandler(WhatsAppSender sender, WhatsAppMessageRepository messages, ObjectMapper mapper) {
        this.sender = sender;
        this.messages = messages;
        this.mapper = mapper;
    }

    /** The serialized outbox payload: the addressed message plus the history row to flip on delivery. */
    public record Payload(UUID historyId, WhatsAppSender.Message message) {
    }

    public String serialize(Payload payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize WhatsApp outbox payload", e);
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(OutboxMessage message) {
        Payload payload = parse(message);
        WhatsAppMessage row = payload.historyId() == null ? null
                : messages.findById(payload.historyId()).orElse(null);
        WhatsAppSender.DeliveryProgress progress = row == null
                ? WhatsAppSender.DeliveryProgress.none() : new RowProgress(row);
        try {
            sender.send(payload.message(), progress);
        } catch (RuntimeException e) {
            // Progress recorded on the row before the failure is committed with the attempt (the outbox
            // delivery transaction survives a handler failure), so the retry resumes after it.
            if (row != null) {
                row.recordDeliveryError(e.getMessage());
            }
            throw e;
        }
        if (row != null) {
            row.markSent();
        }
    }

    /** Delivery progress persisted on the history row, so it outlives a failed attempt. */
    private record RowProgress(WhatsAppMessage row) implements WhatsAppSender.DeliveryProgress {
        @Override
        public String templateMessageId() {
            return row.getProviderMessageId();
        }

        @Override
        public boolean isAttachmentDelivered(UUID documentId) {
            return row.getDeliveredAttachmentIds().contains(documentId);
        }

        @Override
        public void onTemplateDelivered(String providerMessageId) {
            row.recordProviderMessage(providerMessageId, java.time.Instant.now());
        }

        @Override
        public void onAttachmentDelivered(UUID documentId) {
            row.recordAttachmentDelivered(documentId);
        }
    }

    @Override
    public void onExhausted(OutboxMessage message) {
        Payload payload = parse(message);
        if (payload.historyId() != null) {
            messages.findById(payload.historyId())
                    .ifPresent(h -> h.markFailed(message.getError()));
        }
    }

    @Override
    public boolean isRetryable(RuntimeException failure) {
        return !(failure instanceof WhatsAppProviderException providerFailure)
                || providerFailure.isRetryable();
    }

    private Payload parse(OutboxMessage message) {
        try {
            return mapper.readValue(message.getPayload(), Payload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Malformed SEND_WHATSAPP payload for message " + message.getId(), e);
        }
    }
}
