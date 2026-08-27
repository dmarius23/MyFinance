package ro.myfinance.ingestion.application;

import java.time.ZoneId;
import java.time.ZonedDateTime;
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
 * The tick fires ONCE per hour (Europe/Bucharest); each firing syncs only the tenants whose per-tenant
 * schedule ({@code general_settings.auto_sync_enabled} + {@code auto_sync_hour}) matches the current local
 * hour. Tenants without a settings row yet inherit the defaults (enabled, 02:00). For each matching Drive
 * connection it syncs the current + previous month — every module type: payroll, declarations, trial
 * balance, and (via the accounting connection) bank statements + invoices — under that tenant's RLS
 * context. New previous-month payroll notifies the company's representatives.
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

    /**
     * Hourly tick (top of each hour, Europe/Bucharest). Syncs only the tenants scheduled for this hour.
     * One instance per tick via the distributed lock.
     */
    @Scheduled(cron = "${myfinance.ingestion.poll.cron:0 0 * * * *}", zone = "Europe/Bucharest")
    @SchedulerLock(name = "ingestionPollNightly", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void pollNightly() {
        int hour = ZonedDateTime.now(ZoneId.of("Europe/Bucharest")).getHour();
        run("hour=" + hour, hour);
    }

    void run(String which, int hour) {
        List<Conn> conns = dueConnections(hour);
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

    /**
     * Drive connections whose tenant is scheduled to auto-sync at {@code hour} (Europe/Bucharest).
     * LEFT JOIN so tenants without a {@code general_settings} row yet inherit the defaults (enabled, hour 2).
     */
    public List<Conn> dueConnections(int hour) {
        return admin.query(
                "select sc.id, sc.tenant_id from source_connection sc"
                        + " left join general_settings gs on gs.tenant_id = sc.tenant_id"
                        + " where sc.provider = 'GOOGLE_DRIVE' and sc.status <> 'DISABLED'"
                        + " and coalesce(gs.auto_sync_enabled, true) = true"
                        + " and coalesce(gs.auto_sync_hour, 2) = ?",
                (rs, i) -> new Conn(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class)),
                hour);
    }

    public record Conn(UUID id, UUID tenantId) {
    }
}
