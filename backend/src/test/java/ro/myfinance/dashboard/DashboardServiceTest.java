package ro.myfinance.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.access.domain.AppUser;
import ro.myfinance.common.security.Role;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.dashboard.application.DashboardService;
import ro.myfinance.dashboard.application.DashboardService.StatusFilter;
import ro.myfinance.dashboard.domain.DashboardView;
import ro.myfinance.dashboard.domain.DashboardView.Status;
import ro.myfinance.extraction.application.ReconciliationService;
import ro.myfinance.extraction.application.ReconciliationService.CompanyCompleteness;
import ro.myfinance.payroll.application.PayrollService;
import ro.myfinance.reports.application.ReportService;
import ro.myfinance.taxpayments.application.TaxPaymentService;
import ro.myfinance.taxpayments.domain.DeclarationType;
import ro.myfinance.taxpayments.domain.TaxPaymentRow;

/** Status rules, tile tallies, open-requests / overdue / NA, and the status filters. */
class DashboardServiceTest {

    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ReconciliationService reconciliation = mock(ReconciliationService.class);
    private final TaxPaymentService taxes = mock(TaxPaymentService.class);
    private final PayrollService payroll = mock(PayrollService.class);
    private final ReportService reports = mock(ReportService.class);
    private final DashboardService service =
            new DashboardService(companies, users, reconciliation, taxes, payroll, reports);

    // Period far in the past → the tax deadline (25th of the next month) is always passed → overdue is testable.
    private static final LocalDate PERIOD = LocalDate.of(2020, 1, 1);
    private final UUID a = UUID.randomUUID(); // all done, has employees, created this month
    private final UUID b = UUID.randomUUID(); // nothing done, no employees, older
    private final UUID resp = UUID.randomUUID();

    private Company company(UUID id, boolean employees, UUID responsible, LocalDate created) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(id);
        lenient().when(c.getLegalName()).thenReturn("Firma " + id.toString().substring(0, 4));
        lenient().when(c.getCui()).thenReturn("12345678");
        lenient().when(c.getHasEmployees()).thenReturn(employees);
        lenient().when(c.getResponsibleUserId()).thenReturn(responsible);
        lenient().when(c.getCreatedAt()).thenReturn(created.atStartOfDay(ZoneId.systemDefault()).toInstant());
        return c;
    }

    @BeforeEach
    void setup() {
        // Build the company mocks first — stubbing them inside when(findAll()) would nest stubbings.
        Company ca = company(a, true, resp, LocalDate.of(2020, 1, 15));
        Company cb = company(b, false, null, LocalDate.of(2019, 6, 1));
        when(companies.findAll()).thenReturn(List.of(ca, cb));
        when(users.findAllById(any())).thenReturn(List.of(
                new AppUser(resp, UUID.randomUUID(), "maria@firma.ro", "Maria Pop", Role.EMPLOYEE)));

        when(reconciliation.completenessSummary(any())).thenReturn(List.of(
                new CompanyCompleteness(a, ReconciliationService.Completeness.COMPLETE, ReconciliationService.Payment.COMPLETE, 0, 0),
                new CompanyCompleteness(b, ReconciliationService.Completeness.PARTIAL, ReconciliationService.Payment.NONE, 2, 1)));
        when(taxes.list(any())).thenReturn(List.of(
                new TaxPaymentRow(a, "A", "1", null,
                        List.of(new TaxPaymentRow.DeclarationCell(UUID.randomUUID(), DeclarationType.D100, BigDecimal.TEN, false)),
                        Instant.now(), 1)));
        when(payroll.summary(any())).thenReturn(List.of(
                new PayrollService.PayrollRow(a, List.of(new PayrollService.PayrollDoc(UUID.randomUUID(), "stat.pdf")), Instant.now(), 1)));
        when(reports.summary(any())).thenReturn(List.of(
                new ReportService.ReportRow(a, Instant.now(), 1, true, Instant.now(), 1)));
    }

    @Test
    void computesPerCompanyStatusesAndExtras() {
        DashboardView v = service.build(PERIOD, null, StatusFilter.ALL);
        assertThat(v.rows()).hasSize(2);

        DashboardView.CompanyRow rowA = v.rows().stream().filter(r -> r.companyId().equals(a)).findFirst().orElseThrow();
        assertThat(rowA.statements()).isEqualTo(Status.DONE);
        assertThat(rowA.taxes()).isEqualTo(Status.DONE);
        assertThat(rowA.payroll()).isEqualTo(Status.DONE);
        assertThat(rowA.reports()).isEqualTo(Status.DONE);
        assertThat(rowA.openRequests()).isZero();
        assertThat(rowA.overdue()).isZero();
        assertThat(rowA.responsibleName()).isEqualTo("Maria Pop");

        DashboardView.CompanyRow rowB = v.rows().stream().filter(r -> r.companyId().equals(b)).findFirst().orElseThrow();
        assertThat(rowB.statements()).isEqualTo(Status.PARTIAL);
        assertThat(rowB.taxes()).isEqualTo(Status.NOTHING);
        assertThat(rowB.payroll()).isEqualTo(Status.NA); // no employees
        assertThat(rowB.reports()).isEqualTo(Status.NOTHING);
        assertThat(rowB.openRequests()).isEqualTo(3); // 2 missing txns + 1 unmatched invoice
        assertThat(rowB.overdue()).isEqualTo(1);       // tax deadline passed, taxes not done
        assertThat(rowB.responsibleName()).isNull();
    }

    @Test
    void tilesTallyAndExcludeNaFromPayroll() {
        var tiles = service.build(PERIOD, null, StatusFilter.ALL).tiles();
        assertThat(tiles.statements().done()).isEqualTo(1);
        assertThat(tiles.statements().partial()).isEqualTo(1);
        assertThat(tiles.statements().nothing()).isZero();
        assertThat(tiles.payroll().applicable()).isEqualTo(1); // B is NA → excluded
        assertThat(tiles.payroll().done()).isEqualTo(1);
        assertThat(tiles.reports().nothing()).isEqualTo(1);
        assertThat(tiles.newCompanies()).isEqualTo(1);         // only A created in 2020-01
        assertThat(tiles.totalCompanies()).isEqualTo(2);
    }

    @Test
    void statusFilterNarrowsRows() {
        assertThat(service.build(PERIOD, null, StatusFilter.ATTENTION).rows())
                .extracting(DashboardView.CompanyRow::companyId).containsExactly(b);
        assertThat(service.build(PERIOD, null, StatusFilter.COMPLETE).rows())
                .extracting(DashboardView.CompanyRow::companyId).containsExactly(a);
    }

    @Test
    void responsibleFilterLimitsCompanies() {
        DashboardView v = service.build(PERIOD, resp, StatusFilter.ALL);
        assertThat(v.rows()).extracting(DashboardView.CompanyRow::companyId).containsExactly(a);
        assertThat(v.tiles().totalCompanies()).isEqualTo(1);
    }
}
