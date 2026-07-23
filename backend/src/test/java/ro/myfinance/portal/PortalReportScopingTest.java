package ro.myfinance.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.access.domain.RepresentativeLink;
import ro.myfinance.access.domain.UserStatus;
import ro.myfinance.common.security.Role;
import ro.myfinance.common.security.TenantContext;
import ro.myfinance.common.web.NotFoundException;
import ro.myfinance.portal.application.PortalService;
import ro.myfinance.reports.application.Granularity;
import ro.myfinance.reports.application.PeriodReportService;
import ro.myfinance.reports.application.PeriodReportService.PeriodReportResult;
import ro.myfinance.reports.application.ReportService;

/**
 * Company-scoping for the rep report path (golden rule #2): a representative may only read <b>their own</b>
 * company's report/trend/PDF. The active company is resolved and validated server-side against the rep's
 * links — a client-supplied {@code X-Company-Id} outside their assignments never reaches the data layer.
 * (Cross-tenant isolation is covered separately at the RLS layer by {@code PeriodReportIsolationTest}.)
 */
class PortalReportScopingTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID COMPANY_A = UUID.randomUUID(); // the rep IS linked to this
    private static final UUID OUTSIDER = UUID.randomUUID();  // a company the rep is NOT linked to
    private static final LocalDate PERIOD = LocalDate.of(2026, 4, 1);

    private PeriodReportService periodReports;
    private ReportService reports;
    private RepresentativeLinkRepository repLinks;
    private AppUserRepository users;
    private HttpServletRequest request;

    @BeforeEach
    void setup() {
        periodReports = mock(PeriodReportService.class);
        reports = mock(ReportService.class);
        repLinks = mock(RepresentativeLinkRepository.class);
        users = mock(AppUserRepository.class);
        request = mock(HttpServletRequest.class);

        AppUser active = mock(AppUser.class);
        lenient().when(active.getStatus()).thenReturn(UserStatus.ACTIVE);
        lenient().when(users.findById(USER)).thenReturn(Optional.of(active));
        lenient().when(repLinks.findByUserId(USER))
                .thenReturn(List.of(new RepresentativeLink(TENANT, USER, COMPANY_A))); // only A

        TenantContext.set(new TenantContext.Identity(TENANT, USER, Role.REPRESENTATIVE, COMPANY_A));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private PortalService service() {
        return new PortalService(null, null, null, null, reports, periodReports, null, null, null,
                repLinks, users, request);
    }

    @Test
    void reportForANonLinkedCompanyNeverReachesTheDataLayer() {
        when(request.getHeader(PortalService.COMPANY_HEADER)).thenReturn(OUTSIDER.toString());

        // Requesting a company the rep isn't linked to yields no data (204) and never queries it.
        assertThat(service().report(PERIOD, Granularity.QUARTER)).isNull();
        verify(periodReports, never()).report(any(), any(), any());
    }

    @Test
    void pdfForANonLinkedCompanyIsRejected() {
        when(request.getHeader(PortalService.COMPANY_HEADER)).thenReturn(OUTSIDER.toString());

        assertThatThrownBy(() -> service().reportPdf(PERIOD, Granularity.QUARTER))
                .isInstanceOf(NotFoundException.class);
        verify(periodReports, never()).report(any(), any(), any());
    }

    @Test
    void trendForANonLinkedCompanyIsRejected() {
        when(request.getHeader(PortalService.COMPANY_HEADER)).thenReturn(OUTSIDER.toString());

        assertThatThrownBy(() -> service().trend(PERIOD, 12, 3))
                .isInstanceOf(NotFoundException.class);
        verify(reports, never()).trend(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void reportForTheRepsOwnCompanyIsServedForThatCompanyOnly() {
        when(request.getHeader(PortalService.COMPANY_HEADER)).thenReturn(COMPANY_A.toString());
        PeriodReportResult expected = new PeriodReportResult(null, true, 3, 3);
        when(periodReports.report(eq(COMPANY_A), eq(Granularity.QUARTER), eq(PERIOD))).thenReturn(expected);

        PeriodReportResult res = service().report(PERIOD, Granularity.QUARTER);

        assertThat(res).isSameAs(expected);
        verify(periodReports).report(eq(COMPANY_A), eq(Granularity.QUARTER), eq(PERIOD));
    }
}
