package ro.myfinance.ingestion.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.ingestion.adapter.persistence.EmailConnectionRepository;

/** Renews Gmail watches before their seven-day maximum lifetime expires. */
@Component
@ConditionalOnProperty(prefix = "myfinance.ingestion.gmail", name = "watch-renewal-enabled",
        havingValue = "true")
public class GmailWatchRenewalScheduler {

    private static final Logger log = LoggerFactory.getLogger(GmailWatchRenewalScheduler.class);

    private final JdbcTemplate admin;
    private final EmailConnectionRepository connections;
    private final GmailMailboxClient gmail;

    public GmailWatchRenewalScheduler(@Qualifier("adminJdbcTemplate") JdbcTemplate admin,
                                      EmailConnectionRepository connections, GmailMailboxClient gmail) {
        this.admin = admin;
        this.connections = connections;
        this.gmail = gmail;
    }

    @Scheduled(cron = "${myfinance.ingestion.gmail.watch-renewal-cron:0 15 * * * *}",
            zone = "Europe/Bucharest")
    @SchedulerLock(name = "gmailWatchRenewal", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public void renewDueWatches() {
        Instant threshold = Instant.now().plus(1, ChronoUnit.DAYS);
        List<ConnectionRef> due = admin.query(
                "select id, tenant_id from email_connection"
                        + " where status = 'ACTIVE'"
                        + " and (watch_expires_at is null or watch_expires_at < ?)",
                (rs, row) -> new ConnectionRef(rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class)),
                threshold);
        for (ConnectionRef target : due) {
            try {
                TenantContext.set(new TenantContext.Identity(target.tenantId(), null,
                        Role.TENANT_ADMIN, null));
                var connection = connections.findById(target.id()).orElse(null);
                if (connection == null) {
                    continue;
                }
                var registration = gmail.watch(connection);
                // A renewal response contains Gmail's current history id, not a processed cursor.
                // Replacing our cursor here would skip changes between the old cursor and this value.
                connection.setWatchExpiresAt(registration.expiresAt());
                connection.setLastResult("Gmail watch renewed");
                connections.save(connection);
            } catch (RuntimeException e) {
                log.warn("Gmail watch renewal failed for connection {}", target.id());
            } finally {
                TenantContext.clear();
            }
        }
    }

    public record ConnectionRef(UUID id, UUID tenantId) {
    }
}
