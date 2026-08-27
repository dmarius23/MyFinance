package ro.myfinance.ingestion.application;

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

/**
 * MOD-15 — automatic Google Drive polling. Enabled by {@code myfinance.ingestion.poll.enabled=true}.
 * Runs ONCE nightly (02:00 Europe/Bucharest by default; the cron is configurable), enumerating Drive
 * connections across tenants (via the admin/RLS-bypassing datasource) and syncing the current + previous
 * month for each — every module type: payroll, declarations, trial balance, and (via the accounting
 * connection) bank statements + invoices — under that tenant's RLS context. New previous-month payroll
 * notifies the company's representatives.
 *
 * <p>Multi-instance safe (S6): the tick is guarded by a ShedLock {@code @SchedulerLock}, so with several
 * web/worker instances up exactly one runs the poll and the rest skip it — no duplicate Drive imports.
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

    /** Nightly auto-sync (02:00 local by default). One instance per tick via the distributed lock. */
    @Scheduled(cron = "${myfinance.ingestion.poll.cron:0 0 2 * * *}", zone = "Europe/Bucharest")
    @SchedulerLock(name = "ingestionPollNightly", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void pollNightly() {
        run("nightly");
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
