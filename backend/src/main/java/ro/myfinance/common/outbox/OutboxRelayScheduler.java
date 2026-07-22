package ro.myfinance.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link OutboxRelay} on a fixed delay in the {@code worker} process only (the web app produces
 * outbox rows; the worker drains them). Each tick delivers a bounded batch; failures back off per message.
 */
@Component
@Profile("worker")
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${myfinance.outbox.relay-delay-ms:5000}")
    public void tick() {
        try {
            int attempted = relay.relayDue(BATCH_SIZE);
            if (attempted > 0) {
                log.debug("Outbox relay delivered/attempted {} message(s)", attempted);
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay tick failed", e);
        }
    }
}
