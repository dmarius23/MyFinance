package ro.myfinance.reports.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.reports.application.PeriodReportService.PeriodReportResult;

/** The pre-warm scheduler: closed-period math, per-tenant context, and resilient per-company warming. */
class PeriodReportWarmerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID COMPANY = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 15); // in Q3 / H2 / 2026

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void closedPeriodStartIsTheMostRecentlyClosedCalendarPeriod() {
        assertThat(PeriodReportWarmer.closedPeriodStart(Granularity.QUARTER, TODAY)).isEqualTo(LocalDate.of(2026, 4, 1)); // Q2
        assertThat(PeriodReportWarmer.closedPeriodStart(Granularity.HALF, TODAY)).isEqualTo(LocalDate.of(2026, 1, 1));    // H1
        assertThat(PeriodReportWarmer.closedPeriodStart(Granularity.YEAR, TODAY)).isEqualTo(LocalDate.of(2025, 1, 1));    // 2025
        // year boundary: in January the closed year/quarter belong to the previous year
        LocalDate jan = LocalDate.of(2026, 1, 10);
        assertThat(PeriodReportWarmer.closedPeriodStart(Granularity.QUARTER, jan)).isEqualTo(LocalDate.of(2025, 10, 1)); // Q4 2025
        assertThat(PeriodReportWarmer.closedPeriodStart(Granularity.YEAR, jan)).isEqualTo(LocalDate.of(2025, 1, 1));
    }

    @Test
    void warmsTheClosedQuarterHalfAndYearForEachCompanyThenClearsContext() {
        JdbcTemplate admin = mock(JdbcTemplate.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        PeriodReportService periodReports = mock(PeriodReportService.class);
        when(admin.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(TENANT));
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(COMPANY);
        when(companies.findAll()).thenReturn(List.of(c));
        when(periodReports.report(eq(COMPANY), any(), any())).thenReturn(new PeriodReportResult(null, true, 3, 3));

        new PeriodReportWarmer(admin, companies, periodReports).warmAt(TODAY);

        verify(periodReports).report(COMPANY, Granularity.QUARTER, LocalDate.of(2026, 4, 1));
        verify(periodReports).report(COMPANY, Granularity.HALF, LocalDate.of(2026, 1, 1));
        verify(periodReports).report(COMPANY, Granularity.YEAR, LocalDate.of(2025, 1, 1));
        assertThat(TenantContext.current()).as("tenant context cleared after the run").isEmpty();
    }

    @Test
    void aCompanyPeriodWithNoDataIsSkippedWithoutAbortingTheRest() {
        JdbcTemplate admin = mock(JdbcTemplate.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        PeriodReportService periodReports = mock(PeriodReportService.class);
        when(admin.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(TENANT));
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(COMPANY);
        when(companies.findAll()).thenReturn(List.of(c));
        // The half-year has no data (404); quarter and year still get warmed.
        when(periodReports.report(eq(COMPANY), eq(Granularity.HALF), any())).thenThrow(new NotFoundException("none"));
        when(periodReports.report(eq(COMPANY), eq(Granularity.QUARTER), any())).thenReturn(new PeriodReportResult(null, true, 3, 3));
        when(periodReports.report(eq(COMPANY), eq(Granularity.YEAR), any())).thenReturn(new PeriodReportResult(null, false, 8, 12));

        new PeriodReportWarmer(admin, companies, periodReports).warmAt(TODAY);

        verify(periodReports).report(COMPANY, Granularity.QUARTER, LocalDate.of(2026, 4, 1));
        verify(periodReports).report(COMPANY, Granularity.YEAR, LocalDate.of(2025, 1, 1));
        assertThat(TenantContext.current()).isEmpty();
    }
}
