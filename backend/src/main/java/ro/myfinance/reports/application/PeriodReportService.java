package ro.myfinance.reports.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.reports.adapter.persistence.PeriodReportRepository;
import ro.myfinance.reports.adapter.persistence.ReportSnapshotRepository;
import ro.myfinance.reports.application.PeriodAggregator.MonthlyReport;
import ro.myfinance.reports.domain.PeriodReport;
import ro.myfinance.reports.domain.ReportData;
import ro.myfinance.reports.domain.ReportSnapshot;

/**
 * Serves the report for any {@link Granularity}. {@code MONTH} delegates to the existing single-snapshot
 * path ({@link ReportService#report}); the wider calendar periods are aggregated from the monthly
 * snapshots they enclose and <b>cached</b> in {@code period_report}.
 *
 * <p>The cache is keyed by a <b>source fingerprint</b> — a hash of the constituent months and their
 * versions. On each request the current fingerprint is recomputed from the live snapshots: a match
 * serves the stored row; a mismatch (a month was re-uploaded, or a previously-missing month arrived)
 * regenerates it. This is what keeps a quarter/half/year honest after a monthly re-upload, without any
 * coupling to the ingest path. Tenant-scoped via RLS.
 */
@Service
@Transactional
public class PeriodReportService {

    private final ReportService reports;
    private final ReportSnapshotRepository snapshots;
    private final PeriodReportRepository periodReports;
    private final ObjectMapper json;

    public PeriodReportService(ReportService reports, ReportSnapshotRepository snapshots,
                               PeriodReportRepository periodReports, ObjectMapper json) {
        this.reports = reports;
        this.snapshots = snapshots;
        this.periodReports = periodReports;
        this.json = json;
    }

    /** The report for a period, plus how much of that period is actually covered. */
    public record PeriodReportResult(ReportData data, boolean complete, int monthsPresent, int monthsExpected) {
    }

    /**
     * The report for the calendar period (of {@code granularity}) enclosing {@code anchor}.
     * Throws {@link NotFoundException} when no monthly data exists for the period.
     */
    public PeriodReportResult report(UUID companyId, Granularity granularity, LocalDate anchor) {
        if (granularity == Granularity.MONTH) {
            // Reuse the single-snapshot path — it already validates content_period and 404s.
            return new PeriodReportResult(reports.report(companyId, anchor), true, 1, 1);
        }

        LocalDate start = granularity.periodStart(anchor);
        LocalDate end = granularity.periodEnd(anchor);

        List<ReportSnapshot> present = snapshots
                .findByCompanyIdAndPeriodMonthBetweenOrderByPeriodMonthAsc(companyId, start, end).stream()
                .filter(PeriodReportService::validForItsSlot)
                .toList();
        if (present.isEmpty()) {
            throw new NotFoundException("No report for company " + companyId);
        }

        String fingerprint = fingerprint(present);
        Optional<PeriodReport> cached =
                periodReports.findByCompanyIdAndGranularityAndPeriodStart(companyId, granularity, start);
        if (cached.isPresent() && cached.get().getSourceFingerprint().equals(fingerprint)) {
            PeriodReport pr = cached.get();
            return new PeriodReportResult(read(pr.getReportJson()), pr.isComplete(),
                    pr.getMonthsPresent(), pr.getMonthsExpected());
        }

        // Cache miss or stale — aggregate and (re)store.
        List<MonthlyReport> months = new ArrayList<>(present.size());
        for (ReportSnapshot s : present) {
            months.add(new MonthlyReport(s.getPeriodMonth(), s.isBalanced(), read(s.getReportJson())));
        }
        PeriodAggregator.AggregatedReport agg = PeriodAggregator.aggregate(granularity, anchor, months);
        String body = write(agg.data());
        cached.ifPresentOrElse(
                pr -> pr.refresh(body, agg.complete(), agg.monthsPresent(), agg.monthsExpected(), fingerprint),
                () -> periodReports.save(new PeriodReport(TenantContext.tenantId().orElseThrow(), companyId,
                        granularity, start, body, agg.complete(), agg.monthsPresent(), agg.monthsExpected(),
                        fingerprint)));
        return new PeriodReportResult(agg.data(), agg.complete(), agg.monthsPresent(), agg.monthsExpected());
    }

    /**
     * A snapshot counts toward its period only when its own content period matches the slot it sits in.
     * Legacy snapshots (content_period unknown) are trusted to their slot — the monthly path detects those.
     */
    private static boolean validForItsSlot(ReportSnapshot s) {
        LocalDate cp = s.getContentPeriod();
        return cp == null || cp.equals(s.getPeriodMonth());
    }

    /** Stable hash of the constituent (month, version, content_period) tuples — the cache key. */
    private static String fingerprint(List<ReportSnapshot> present) {
        String canonical = present.stream()
                .sorted(Comparator.comparing(ReportSnapshot::getPeriodMonth))
                .map(s -> s.getPeriodMonth() + ":" + s.getVersion() + ":" + s.getContentPeriod())
                .reduce((a, b) -> a + "|" + b).orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
        }
    }

    private String write(ReportData data) {
        try {
            return json.writeValueAsString(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize period report", e);
        }
    }

    private ReportData read(String body) {
        try {
            return json.readValue(body, ReportData.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to read stored report", e);
        }
    }
}
