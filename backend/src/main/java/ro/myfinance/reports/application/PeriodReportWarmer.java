package ro.myfinance.reports.application;

import java.time.LocalDate;
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
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;

/**
 * MOD-06 — pre-warms the {@code period_report} cache so a rep's first view of a just-closed
 * quarter/half/year is instant instead of paying the aggregation on demand. Enabled by
 * {@code myfinance.reports.warm.enabled=true}.
 *
 * <p><b>Optimization only — never a correctness dependency.</b> It calls the very same
 * {@link PeriodReportService#report} path a user request would; the source-fingerprint cache means a
 * cold cache (job disabled, or a period nobody warmed yet) is still served correctly and freshly on
 * demand. Each run enumerates active tenants via the admin (RLS-bypassing) datasource, then materializes
 * the most-recently-closed period of each grain per company under that tenant's RLS context.
 */
@Component
@ConditionalOnProperty(prefix = "myfinance.reports.warm", name = "enabled", havingValue = "true")
public class PeriodReportWarmer {

    private static final Logger log = LoggerFactory.getLogger(PeriodReportWarmer.class);
    /** MONTH is served straight from the monthly snapshot — only the aggregated grains are cached. */
    private static final List<Granularity> GRAINS = List.of(Granularity.QUARTER, Granularity.HALF, Granularity.YEAR);

    private final JdbcTemplate admin;
    private final CompanyRepository companies;
    private final PeriodReportService periodReports;

    public PeriodReportWarmer(@Qualifier("adminJdbcTemplate") JdbcTemplate admin,
                              CompanyRepository companies, PeriodReportService periodReports) {
        this.admin = admin;
        this.companies = companies;
        this.periodReports = periodReports;
    }

    /** Nightly (03:30 by default). Warms whatever calendar period most recently closed. */
    @Scheduled(cron = "${myfinance.reports.warm.cron:0 30 3 * * *}")
    void warm() {
        warmAt(LocalDate.now());
    }

    /** Package-private seam so the period math can be driven from a fixed date in tests. */
    void warmAt(LocalDate today) {
        List<UUID> tenants = admin.query("select id from tenant where status = 'ACTIVE'",
                (rs, i) -> rs.getObject("id", UUID.class));
        log.info("Period-report warm — {} active tenant(s), closed periods as of {}", tenants.size(), today);
        int warmed = 0;
        for (UUID tenantId : tenants) {
            try {
                TenantContext.set(new TenantContext.Identity(tenantId, null, Role.TENANT_ADMIN, null));
                warmed += warmTenant(today);
            } catch (RuntimeException e) {
                log.warn("Period-report warm failed for tenant {}", tenantId, e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Period-report warm done — {} period report(s) materialized", warmed);
    }

    /** Materialize the just-closed quarter/half/year for every company of the current tenant. */
    private int warmTenant(LocalDate today) {
        int warmed = 0;
        for (Company c : companies.findAll()) {
            for (Granularity grain : GRAINS) {
                LocalDate periodStart = closedPeriodStart(grain, today);
                try {
                    PeriodReportService.PeriodReportResult res = periodReports.report(c.getId(), grain, periodStart);
                    warmed++;
                    if (!res.complete()) {
                        log.debug("Warmed incomplete {} {} for company {} ({}/{} months)",
                                grain, periodStart, c.getId(), res.monthsPresent(), res.monthsExpected());
                    }
                } catch (NotFoundException e) {
                    // No monthly data in this period for this company — nothing to warm.
                }
            }
        }
        return warmed;
    }

    /** First day of the calendar period of {@code grain} that most recently closed relative to {@code today}. */
    static LocalDate closedPeriodStart(Granularity grain, LocalDate today) {
        return grain.periodStart(grain.periodStart(today).minusDays(1));
    }
}
