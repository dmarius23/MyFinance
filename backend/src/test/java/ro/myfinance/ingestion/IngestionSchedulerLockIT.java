package ro.myfinance.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ro.myfinance.support.AbstractPostgresIT;

/**
 * S6 — the distributed lock that makes the ingestion poll run exactly once per tick. Proven at the lock
 * layer on a real Postgres: while one "instance" holds the named lock, no other can acquire it (so only one
 * runs the tick); once released it's available again. This is the guarantee behind the
 * {@code @SchedulerLock} on {@code IngestionScheduler.pollNightly}.
 */
class IngestionSchedulerLockIT extends AbstractPostgresIT {

    @Autowired LockProvider lockProvider;

    @Test
    void aHeldLockBlocksOtherInstancesThenReleases() {
        var config = new LockConfiguration(Instant.now(), "ingestionPollNightly",
                Duration.ofMinutes(5), Duration.ZERO);

        Optional<SimpleLock> first = lockProvider.lock(config);   // instance A grabs the tick
        Optional<SimpleLock> second = lockProvider.lock(config);  // instance B tries the same tick

        assertThat(first).as("first instance acquires the lock").isPresent();
        assertThat(second).as("a second instance must NOT run the same tick").isEmpty();

        first.get().unlock();

        Optional<SimpleLock> next = lockProvider.lock(config);    // next tick, after release
        assertThat(next).as("lock is available again once released").isPresent();
        next.get().unlock();
    }
}
