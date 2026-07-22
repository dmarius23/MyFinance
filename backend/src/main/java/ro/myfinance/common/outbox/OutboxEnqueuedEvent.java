package ro.myfinance.common.outbox;

import java.util.UUID;

/**
 * Published after an {@link OutboxMessage} is written. In the worker the scheduler drains on a timer; this
 * event lets an inline drainer (dev / tests, {@code myfinance.outbox.inline=true}) deliver immediately
 * after the enqueuing transaction commits, so no separate worker process is needed there.
 */
public record OutboxEnqueuedEvent(UUID messageId) {
}
