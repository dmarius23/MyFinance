package ro.myfinance.common.outbox;

/**
 * Handles one {@link OutboxMessage} type when the relay delivers it. Registered by Spring (one bean per
 * type). {@link #handle} runs inside the message's own transaction, bound to the message's tenant, and
 * must be <b>idempotent</b> — the relay is at-least-once, so a message may be redelivered. Throw to signal
 * a delivery failure (the relay backs off and retries, then moves to DLQ after the attempts cap).
 */
public interface OutboxHandler {

    /** The {@link OutboxMessage#getType()} this handler serves. */
    String type();

    /** Deliver the message. Throw on failure. */
    void handle(OutboxMessage message);

    /**
     * Called when a message has exhausted its retries and moved to the DLQ, so the source can reflect the
     * permanent failure (e.g. flip its email-history row to FAILED). Runs in the same transaction as the
     * DLQ transition, bound to the message's tenant. Default: no-op.
     */
    default void onExhausted(OutboxMessage message) {
        // no-op by default
    }
}
