package ro.myfinance.ingestion.application;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;

/**
 * MOD-15 — automatic Google Drive polling. Enabled by {@code myfinance.ingestion.poll.enabled=true}.
 * Payroll lands early in the month, so it runs hourly during the first week and once a day afterwards
 * (both cron expressions configurable). Each run enumerates Drive connections across tenants (via the
 * admin/RLS-bypassing datasource), then syncs the current + previous month for each, under that tenant's
 * RLS context. New previous-month payroll notifies the company's representatives.
 */
@Component
@ConditionalOnProperty(prefix = "myfinance.ingestion.poll", name = "enabled", havingValue = "true")
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final JdbcTemplate admin;
    private final IngestionService ingestion;

    public IngestionScheduler(@Qualifier("adminJdbcTemplate") JdbcTemplate admin, IngestionService ingestion) {
        this.admin = admin;
        this.ingestion = ingestion;
    }

    /** Hourly during the first week of the month (days 1–7). */
    @Scheduled(cron = "${myfinance.ingestion.poll.cron-frequent:0 0 * 1-7 * *}")
    void pollFrequent() {
        run("frequent");
    }

    /** Once a day for the rest of the month (days 8–31, 09:00). */
    @Scheduled(cron = "${myfinance.ingestion.poll.cron-daily:0 0 9 8-31 * *}")
    void pollDaily() {
        run("daily");
    }

    void run(String which) {
        List<Conn> conns = admin.query(
                "select id, tenant_id from source_connection where provider = 'GOOGLE_DRIVE' and status <> 'DISABLED'",
                (rs, i) -> new Conn(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)));
        log.info("Ingestion auto-sync ({}) — {} connection(s)", which, conns.size());
        for (Conn c : conns) {
            try {
                TenantContext.set(new TenantContext.Identity(c.tenantId(), null, Role.TENANT_ADMIN, null));
                var r = ingestion.syncRecent(c.id());
                log.info("Auto-sync connection {} (tenant {}): {} imported, {} to review",
                        c.id(), c.tenantId(), r.imported(), r.needsReview());
            } catch (RuntimeException e) {
                log.warn("Auto-sync failed for connection {} (tenant {})", c.id(), c.tenantId(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }

    private record Conn(UUID id, UUID tenantId) {
    }
}
