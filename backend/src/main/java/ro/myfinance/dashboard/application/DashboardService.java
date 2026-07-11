package ro.myfinance.dashboard.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.access.adapter.persistence.AppUserRepository;
import ro.myfinance.company.adapter.persistence.CompanyRepository;
import ro.myfinance.company.domain.Company;
import ro.myfinance.dashboard.domain.DashboardView;
import ro.myfinance.dashboard.domain.DashboardView.Status;
import ro.myfinance.extraction.application.ReconciliationService;
import ro.myfinance.payroll.application.PayrollService;
import ro.myfinance.reports.application.ReportService;
import ro.myfinance.taxpayments.application.TaxPaymentService;
import ro.myfinance.taxpayments.domain.TaxPaymentRow;

/**
 * MOD-11 Dashboards — aggregates the per-module monthly summaries (statements/taxes/payroll/reports) into
 * the companies overview: section tiles + a per-company status row. "Done" rules (agreed): statements =
 * reconciled; taxes/payroll/reports = data ready AND emailed. Payroll is NA when the company has no
 * employees. Read-only; tenant-scoped via the underlying RLS-bound services.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final CompanyRepository companies;
    private final AppUserRepository users;
    private final ReconciliationService reconciliation;
    private final TaxPaymentService taxes;
    private final PayrollService payroll;
    private final ReportService reports;
    private final ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository repLinks;

    public DashboardService(CompanyRepository companies, AppUserRepository users,
                            ReconciliationService reconciliation, TaxPaymentService taxes,
                            PayrollService payroll, ReportService reports,
                            ro.myfinance.access.adapter.persistence.RepresentativeLinkRepository repLinks) {
        this.companies = companies;
        this.users = users;
        this.reconciliation = reconciliation;
        this.taxes = taxes;
        this.payroll = payroll;
        this.reports = reports;
        this.repLinks = repLinks;
    }

    /** Filter: which companies to include in the table. Tiles always reflect the representative filter only. */
    public enum StatusFilter { ALL, ATTENTION, COMPLETE }

    public DashboardView build(LocalDate periodMonth, UUID representative, StatusFilter statusFilter) {
        LocalDate month = periodMonth.withDayOfMonth(1);
        StatusFilter sf = statusFilter == null ? StatusFilter.ALL : statusFilter;

        Map<UUID, ReconciliationService.CompanyCompleteness> recon = reconciliation.completenessSummary(month)
                .stream().collect(Collectors.toMap(ReconciliationService.CompanyCompleteness::companyId, Function.identity(), (a, b) -> a));
        Map<UUID, TaxPaymentRow> tax = taxes.list(month).stream()
                .collect(Collectors.toMap(TaxPaymentRow::companyId, Function.identity(), (a, b) -> a));
        Map<UUID, PayrollService.PayrollRow> pay = payroll.summary(month).stream()
                .collect(Collectors.toMap(PayrollService.PayrollRow::companyId, Function.identity(), (a, b) -> a));
        Map<UUID, ReportService.ReportRow> rep = reports.summary(month).stream()
                .collect(Collectors.toMap(ReportService.ReportRow::companyId, Function.identity(), (a, b) -> a));

        // Tax obligations are overdue past the 25th of the following month.
        boolean taxDeadlinePassed = LocalDate.now().isAfter(month.plusMonths(1).withDayOfMonth(25));

        // Representatives (client-side contacts) per company, resolved in bulk. The people column and the
        // filter are representative-based: a company may have several, and a rep may serve several companies.
        List<Company> companiesAll = companies.findAll();
        Map<UUID, List<UUID>> repIdsByCompany = repLinks.findByCompanyIdIn(
                        companiesAll.stream().map(Company::getId).toList()).stream()
                .collect(Collectors.groupingBy(l -> l.getCompanyId(),
                        Collectors.mapping(l -> l.getUserId(), Collectors.toList())));
        Map<UUID, String> userNames = users.findAllById(repIdsByCompany.values().stream()
                        .flatMap(List::stream).distinct().toList())
                .stream().collect(Collectors.toMap(u -> u.getId(), u -> u.getName()));

        List<Company> all = companiesAll.stream()
                .filter(c -> representative == null
                        || repIdsByCompany.getOrDefault(c.getId(), List.of()).contains(representative))
                .toList();

        int[] st = new int[3];
        int[] tx = new int[3];
        int[] py = new int[3];
        int[] rp = new int[3];
        int newCompanies = 0;
        List<DashboardView.CompanyRow> rows = new ArrayList<>();

        for (Company c : all) {
            UUID id = c.getId();
            Status statements = statementsStatus(recon.get(id));
            Status taxStatus = taxStatus(tax.get(id));
            Status payrollStatus = payrollStatus(Boolean.TRUE.equals(c.getHasEmployees()), pay.get(id));
            Status reportStatus = reportStatus(rep.get(id));

            tally(st, statements);
            tally(tx, taxStatus);
            tally(py, payrollStatus);
            tally(rp, reportStatus);

            var comp = recon.get(id);
            int openRequests = comp == null ? 0 : comp.missingTxnCount() + comp.unmatchedInvoiceCount();
            int overdue = taxDeadlinePassed && taxStatus != Status.DONE ? 1 : 0;

            if (createdIn(c, month)) {
                newCompanies++;
            }

            boolean attention = overdue > 0 || openRequests > 0
                    || anyPending(statements, taxStatus, payrollStatus, reportStatus);
            if (sf == StatusFilter.ATTENTION && !attention) {
                continue;
            }
            if (sf == StatusFilter.COMPLETE && attention) {
                continue;
            }
            List<DashboardView.Person> reps = repIdsByCompany.getOrDefault(id, List.of()).stream()
                    .map(uid -> new DashboardView.Person(uid, userNames.get(uid)))
                    .filter(p -> p.name() != null)
                    .toList();
            rows.add(new DashboardView.CompanyRow(id, c.getLegalName(), c.getCui(),
                    reps, statements, taxStatus, payrollStatus, reportStatus, openRequests, overdue));
        }

        DashboardView.Tiles tiles = new DashboardView.Tiles(
                new DashboardView.SectionTile(st[0], st[1], st[2]),
                new DashboardView.SectionTile(tx[0], tx[1], tx[2]),
                new DashboardView.SectionTile(py[0], py[1], py[2]),
                new DashboardView.SectionTile(rp[0], rp[1], rp[2]),
                newCompanies, all.size());
        return new DashboardView(tiles, rows);
    }

    private static Status statementsStatus(ReconciliationService.CompanyCompleteness c) {
        if (c == null) {
            return Status.NOTHING;
        }
        return switch (c.completeness()) {
            case COMPLETE -> Status.DONE;
            case PARTIAL -> Status.PARTIAL;
            case NOT_STARTED -> Status.NOTHING;
        };
    }

    private static Status taxStatus(TaxPaymentRow r) {
        if (r == null || r.declarations().isEmpty()) {
            return Status.NOTHING;
        }
        return r.lastEmailAt() != null ? Status.DONE : Status.PARTIAL;
    }

    private static Status payrollStatus(boolean hasEmployees, PayrollService.PayrollRow r) {
        if (!hasEmployees) {
            return Status.NA;
        }
        if (r == null || r.documents().isEmpty()) {
            return Status.NOTHING;
        }
        return r.lastSentAt() != null ? Status.DONE : Status.PARTIAL;
    }

    private static Status reportStatus(ReportService.ReportRow r) {
        if (r == null || r.uploadedAt() == null) {
            return Status.NOTHING;
        }
        return r.balanced() && r.lastSentAt() != null ? Status.DONE : Status.PARTIAL;
    }

    private static void tally(int[] bucket, Status s) {
        switch (s) {
            case DONE -> bucket[0]++;
            case PARTIAL -> bucket[1]++;
            case NOTHING -> bucket[2]++;
            case NA -> { }
        }
    }

    private static boolean anyPending(Status... statuses) {
        for (Status s : statuses) {
            if (s == Status.PARTIAL || s == Status.NOTHING) {
                return true;
            }
        }
        return false;
    }

    private static boolean createdIn(Company c, LocalDate month) {
        if (c.getCreatedAt() == null) {
            return false;
        }
        return c.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate().withDayOfMonth(1).equals(month);
    }
}
