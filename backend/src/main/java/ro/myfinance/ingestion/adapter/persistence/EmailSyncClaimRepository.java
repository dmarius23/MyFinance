package ro.myfinance.ingestion.adapter.persistence;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Atomic database claim for one Gmail connection, safe across worker instances. */
@Repository
public class EmailSyncClaimRepository {

    private final JdbcTemplate jdbc;

    public EmailSyncClaimRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryClaim(UUID connectionId, UUID tenantId, Duration lease) {
        List<UUID> claimed = jdbc.query(
                """
                insert into email_sync_claim(connection_id, tenant_id, claimed_at, lock_until)
                values (?, ?, now(), now() + (? * interval '1 second'))
                on conflict (connection_id) do update
                    set tenant_id = excluded.tenant_id,
                        claimed_at = now(),
                        lock_until = excluded.lock_until
                    where email_sync_claim.lock_until < now()
                returning connection_id
                """,
                (rs, row) -> rs.getObject("connection_id", UUID.class),
                connectionId, tenantId, lease.toSeconds());
        return !claimed.isEmpty();
    }

    public void release(UUID connectionId) {
        jdbc.update("update email_sync_claim set lock_until = now() where connection_id = ?",
                connectionId);
    }
}
