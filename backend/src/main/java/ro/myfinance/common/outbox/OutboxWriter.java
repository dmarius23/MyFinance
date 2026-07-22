package ro.myfinance.common.outbox;

import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import ro.myfinance.common.security.TenantContext;

/**
 * Writes {@link OutboxMessage} rows. Call this <b>inside the business transaction</b> whose commit the
 * dispatch must be tied to — the row is then persisted atomically with the business change, so a crash
 * after commit still leaves the message for the relay to deliver (transactional outbox pattern).
 */
@Service
public class OutboxWriter {

    private final OutboxMessageRepository outbox;
    private final ApplicationEventPublisher events;

    public OutboxWriter(OutboxMessageRepository outbox, ApplicationEventPublisher events) {
        this.outbox = outbox;
        this.events = events;
    }

    /**
     * Enqueue one message for the current tenant. {@code type} selects the relay handler; {@code payload}
     * is its JSON arguments; {@code aggregateType}/{@code aggregateId} identify the source for audit.
     */
    public OutboxMessage enqueue(String aggregateType, String aggregateId, String type, String payload) {
        UUID tenantId = TenantContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("No tenant bound — cannot enqueue outbox message"));
        OutboxMessage saved = outbox.save(new OutboxMessage(tenantId, aggregateType, aggregateId, type, payload));
        events.publishEvent(new OutboxEnqueuedEvent(saved.getId())); // drives the inline drainer (dev/tests)
        return saved;
    }
}
