package ro.myfinance.common.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivers a single {@link OutboxMessage} in its own transaction (so one poison message never rolls back
 * the rest of a batch). Split from {@link OutboxRelay} so the {@code REQUIRES_NEW} boundary is crossed
 * through the Spring proxy. Must be called with the message's tenant already bound to the thread.
 */
@Service
public class OutboxDelivery {

    private static final Logger log = LoggerFactory.getLogger(OutboxDelivery.class);
    static final int MAX_ATTEMPTS = 8;
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofHours(1);

    private final OutboxMessageRepository outbox;
    private final Map<String, OutboxHandler> handlers;

    public OutboxDelivery(OutboxMessageRepository outbox, List<OutboxHandler> handlers) {
        this.outbox = outbox;
        this.handlers = handlers.stream().collect(Collectors.toMap(OutboxHandler::type, h -> h));
    }

    /**
     * Deliver one <b>claimed</b> (PROCESSING) message. Idempotency guard: only PROCESSING rows are
     * delivered, so a row already SENT/DLQ (or no longer claimed) is skipped — no double send. A handler
     * exception backs the row off (or DLQs it after the attempts cap) but is caught so the batch continues.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliverOne(UUID id) {
        OutboxMessage msg = outbox.findById(id).orElse(null);
        if (msg == null || msg.getStatus() != OutboxMessage.Status.PROCESSING) {
            return; // not a live claim — no double send
        }
        OutboxHandler handler = handlers.get(msg.getType());
        if (handler == null) {
            msg.recordFailure("No handler for type " + msg.getType(), backoff(msg.getAttempts()), MAX_ATTEMPTS);
            outbox.save(msg);
            log.warn("Outbox: no handler for type {} (message {})", msg.getType(), id);
            return;
        }
        try {
            handler.handle(msg);
            msg.markSent(Instant.now());
        } catch (RuntimeException e) {
            msg.recordFailure(e.getMessage(), backoff(msg.getAttempts()), MAX_ATTEMPTS);
            log.warn("Outbox delivery failed (type={} message={} attempt={}/{})",
                    msg.getType(), id, msg.getAttempts(), MAX_ATTEMPTS, e);
            if (msg.getStatus() == OutboxMessage.Status.DLQ) {
                handler.onExhausted(msg); // let the source reflect the permanent failure
            }
        }
        outbox.save(msg);
    }

    private static Instant backoff(int attemptsSoFar) {
        // attemptsSoFar is pre-increment (recordFailure bumps it): exponential from BASE, capped at MAX.
        long seconds = Math.min(MAX_BACKOFF.getSeconds(),
                BASE_BACKOFF.getSeconds() * (1L << Math.min(attemptsSoFar, 20)));
        return Instant.now().plusSeconds(seconds);
    }
}
