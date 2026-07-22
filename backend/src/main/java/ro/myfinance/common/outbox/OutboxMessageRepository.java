package ro.myfinance.common.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Store for {@link OutboxMessage}. The relay reads across tenants (its connection carries
 * {@code app.role=SUPER_ADMIN}, honored by the outbox RLS policy); the writer inserts under the ordinary
 * tenant scope.
 */
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    /** The next batch of due PENDING messages, oldest first (a message is due once {@code nextAttemptAt} passes). */
    List<OutboxMessage> findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            OutboxMessage.Status status, Instant now, Limit limit);
}
