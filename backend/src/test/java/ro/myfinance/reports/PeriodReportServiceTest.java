package ro.myfinance.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.reports.adapter.persistence.PeriodReportRepository;
import ro.myfinance.reports.adapter.persistence.ReportSnapshotRepository;
import ro.myfinance.reports.application.Granularity;
import ro.myfinance.reports.application.PeriodReportService;
import ro.myfinance.reports.application.PeriodReportService.PeriodReportResult;
import ro.myfinance.reports.application.ReportService;
import ro.myfinance.reports.domain.PeriodReport;
import ro.myfinance.reports.domain.ReportData;
import ro.myfinance.reports.domain.ReportSnapshot;

/** The fingerprint cache: MONTH delegation, cache miss → store, cache hit → serve, re-upload → regenerate. */
@ExtendWith(MockitoExtension.class)
class PeriodReportServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID COMPANY = UUID.randomUUID();

    @Mock ReportService reports;
    @Mock ReportSnapshotRepository snapshots;
    @Mock PeriodReportRepository periodReports;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private PeriodReportService service;

    @BeforeEach
    void init() {
        service = new PeriodReportService(reports, snapshots, periodReports, json);
        TenantContext.set(new TenantContext.Identity(TENANT, UUID.randomUUID(), null, null));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void monthDelegatesToTheSingleSnapshotPath() {
        LocalDate month = LocalDate.of(2026, 3, 10);
        ReportData monthly = reportData(500);
        when(reports.report(COMPANY, month)).thenReturn(monthly);

        PeriodReportResult res = service.report(COMPANY, Granularity.MONTH, month);

        assertThat(res.data()).isSameAs(monthly);
        assertThat(res.complete()).isTrue();
        assertThat(res.monthsExpected()).isEqualTo(1);
        verify(snapshots, never()).findByCompanyIdAndPeriodMonthBetweenOrderByPeriodMonthAsc(any(), any(), any());
    }

    @Test
    void missingPeriodDataThrowsNotFound() {
        when(snapshots.findByCompanyIdAndPeriodMonthBetweenOrderByPeriodMonthAsc(eq(COMPANY), any(), any()))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.report(COMPANY, Granularity.QUARTER, LocalDate.of(2026, 4, 1)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void cacheMissAggregatesStoresAndReportsCoverage() {
        // Q2 with only April and May present (June missing) → incomplete 2 of 3.
        when(snapshots.findByCompanyIdAndPeriodMonthBetweenOrderByPeriodMonthAsc(eq(COMPANY), any(), any()))
                .thenReturn(List.of(snap(LocalDate.of(2026, 4, 1), 100), snap(LocalDate.of(2026, 5, 1), 200)));
        when(periodReports.findByCompanyIdAndGranularityAndPeriodStart(any(), any(), any()))
                .thenReturn(Optional.empty());

        PeriodReportResult res = service.report(COMPANY, Granularity.QUARTER, LocalDate.of(2026, 5, 15));

        assertThat(res.data().profitLoss().revenue()).isEqualByComparingTo("300");
        assertThat(res.monthsPresent()).isEqualTo(2);
        assertThat(res.monthsExpected()).isEqualTo(3);
        assertThat(res.complete()).isFalse();

        ArgumentCaptor<PeriodReport> saved = ArgumentCaptor.forClass(PeriodReport.class);
        verify(periodReports).save(saved.capture());
        assertThat(saved.getValue().getGranularity()).isEqualTo(Granularity.QUARTER);
        assertThat(saved.getValue().getPeriodStart()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(saved.getValue().getSourceFingerprint()).isNotBlank();
    }

    @Test
    void cacheHitServesStoredWithoutRegenerating() {
        List<ReportSnapshot> present = List.of(
                snap(LocalDate.of(2026, 1, 1), 100),
                snap(LocalDate.of(2026, 2, 1), 200),
                snap(LocalDate.of(2026, 3, 1), 300));
        when(snapshots.findByCompanyIdAndPeriodMonthBetweenOrderByPeriodMonthAsc(eq(COMPANY), any(), any()))
                .thenReturn(present);
        when(periodReports.findByCompanyIdAndGranularityAndPeriodStart(any(), any(), any()))
                .thenReturn(Optional.empty());

        // First call: cache miss → stores the row (capture it, fingerprint and all).
        service.report(COMPANY, Granularity.QUARTER, LocalDate.of(2026, 2, 1));
        ArgumentCaptor<PeriodReport> saved = ArgumentCaptor.forClass(PeriodReport.class);
        verify(periodReports).save(saved.capture());
        PeriodReport stored = saved.getValue();

        // Second call: same snapshots → fingerprint matches → served from the stored row, no re-save.
        when(periodReports.findByCompanyIdAndGranularityAndPeriodStart(any(), any(), any()))
                .thenReturn(Optional.of(stored));

        PeriodReportResult res = service.report(COMPANY, Granularity.QUARTER, LocalDate.of(2026, 2, 1));

        assertThat(res.data().profitLoss().revenue()).isEqualByComparingTo("600");
        assertThat(res.complete()).isTrue();
        verify(periodReports, times(1)).save(any()); // only the first call stored
    }

    @Test
    void reuploadedMonthInvalidatesAndRegeneratesInPlace() {
        // A stored row built from a stale fingerprint must be refreshed, not saved anew.
        List<ReportSnapshot> present = List.of(
                snap(LocalDate.of(2026, 1, 1), 100),
                snap(LocalDate.of(2026, 2, 1), 200),
                snap(LocalDate.of(2026, 3, 1), 300));
        when(snapshots.findByCompanyIdAndPeriodMonthBetweenOrderByPeriodMonthAsc(eq(COMPANY), any(), any()))
                .thenReturn(present);

        PeriodReport stale = new PeriodReport(TENANT, COMPANY, Granularity.QUARTER, LocalDate.of(2026, 1, 1),
                json(reportData(1)), true, 3, 3, "stale-fingerprint");
        when(periodReports.findByCompanyIdAndGranularityAndPeriodStart(any(), any(), any()))
                .thenReturn(Optional.of(stale));

        PeriodReportResult res = service.report(COMPANY, Granularity.QUARTER, LocalDate.of(2026, 2, 1));

        assertThat(res.data().profitLoss().revenue()).isEqualByComparingTo("600"); // fresh aggregate, not the stale 1
        assertThat(stale.getSourceFingerprint()).isNotEqualTo("stale-fingerprint"); // refreshed in place
        verify(periodReports, never()).save(any()); // updated the existing row, no new insert
    }

    // --- helpers ---

    private ReportSnapshot snap(LocalDate month, double revenue) {
        return new ReportSnapshot(TENANT, COMPANY, month, UUID.randomUUID(), true, json(reportData(revenue)), month);
    }

    private String json(ReportData d) {
        try {
            return json.writeValueAsString(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ReportData reportData(double revenue) {
        BigDecimal rev = BigDecimal.valueOf(revenue);
        ReportData.ProfitLoss pl = new ReportData.ProfitLoss(
                rev, List.of(), BigDecimal.ZERO, List.of(), rev, BigDecimal.ZERO, rev);
        ReportData.BalanceSheet bs = new ReportData.BalanceSheet(
                List.of(), BigDecimal.ZERO, List.of(), BigDecimal.ZERO, List.of(), BigDecimal.ZERO);
        ReportData.Kpis kpis = new ReportData.Kpis(
                null, null, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        return new ReportData("ACME SRL", "RO123", null, null, true, pl, bs, kpis);
    }
}
