package ro.myfinance.common.outbox;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Store for {@link OutboxMessage}: per-message loads/saves for the relay. Claiming due rows and reaping
 * stale claims is done in raw SQL by {@link OutboxClaimRepository} ({@code FOR UPDATE SKIP LOCKED}). The
 * relay operates across tenants under a {@code SUPER_ADMIN} identity honored by the outbox RLS policy; the
 * writer inserts under the ordinary tenant scope.
 */
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {
}
