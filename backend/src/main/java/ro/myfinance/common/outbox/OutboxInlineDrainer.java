package ro.myfinance.common.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drains the outbox <b>inline</b>, right after the enqueuing transaction commits — so dev (single web
 * process) and integration tests deliver without a separate worker. Active only when
 * {@code myfinance.outbox.inline=true}; production leaves this off and relies on the worker
 * {@link OutboxRelayScheduler}.
 */
@Component
@ConditionalOnProperty(name = "myfinance.outbox.inline", havingValue = "true")
public class OutboxInlineDrainer {

    private static final int BATCH_SIZE = 50;

    private final OutboxRelay relay;

    public OutboxInlineDrainer(OutboxRelay relay) {
        this.relay = relay;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEnqueued(OutboxEnqueuedEvent event) {
        relay.relayDue(BATCH_SIZE);
    }
}
