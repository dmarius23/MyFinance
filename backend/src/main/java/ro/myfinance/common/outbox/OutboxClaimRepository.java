package ro.myfinance.common.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The single-flight claim + visibility-timeout reaper for the outbox, in raw SQL (JPA has no portable
 * {@code FOR UPDATE SKIP LOCKED}). Both statements must run under a system {@code SUPER_ADMIN} identity so
 * the cross-tenant scan is permitted by the outbox RLS policy.
 */
@Repository
public class OutboxClaimRepository {

    private final JdbcTemplate jdbc;

    public OutboxClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A claimed message and the tenant it must be processed under. */
    public record Claim(UUID id, UUID tenantId) {
    }

    /**
     * Atomically claim up to {@code limit} due PENDING messages — flipping them to PROCESSING in one
     * statement while {@code FOR UPDATE SKIP LOCKED} makes concurrent workers skip rows another worker is
     * already claiming (multi-worker single-flight). Returns the claimed ids + their tenants.
     */
    public List<Claim> claimDue(int limit) {
        return jdbc.query(
                """
                UPDATE outbox_message SET status = 'PROCESSING', claimed_at = now()
                WHERE id IN (
                    SELECT id FROM outbox_message
                    WHERE status = 'PENDING' AND next_attempt_at <= now()
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                RETURNING id, tenant_id
                """,
                (rs, i) -> new Claim(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)),
                limit);
    }

    /**
     * Return messages stuck in PROCESSING past the visibility window (a worker claimed them then died) to
     * PENDING so they are redelivered. Returns how many were reclaimed.
     */
    public int reapStale(long visibilityTimeoutSeconds) {
        return jdbc.update(
                """
                UPDATE outbox_message SET status = 'PENDING', claimed_at = NULL
                WHERE status = 'PROCESSING' AND claimed_at < now() - (? * interval '1 second')
                """,
                visibilityTimeoutSeconds);
    }
}
