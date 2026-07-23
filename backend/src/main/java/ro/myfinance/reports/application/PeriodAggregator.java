package ro.myfinance.reports.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ro.myfinance.reports.domain.ReportData;

/**
 * Folds the monthly {@link ReportData} snapshots enclosed by a calendar period into one aggregated
 * {@link ReportData}. Pure, deterministic — no I/O — like {@link ReportCalculator}.
 *
 * <p>Aggregation rules:
 * <ul>
 *   <li><b>P&amp;L flows are summed</b> across the months (revenue, expenses, tax, profit; line items
 *       merged by account code). Flows are additive.</li>
 *   <li><b>The balance sheet is the period-end position</b> — the latest present month's closing
 *       balances verbatim, never summed and never averaged.</li>
 *   <li><b>KPIs are recomputed</b>: margins from the summed P&amp;L; the balance-sheet ratios are taken
 *       from the period-end month (they already describe the balance sheet we adopted).</li>
 *   <li><b>balanced</b> is true only when every present month is balanced.</li>
 * </ul>
 *
 * <p>A period may be <b>incomplete</b> (some months not yet uploaded). The result reports
 * {@code monthsPresent}/{@code monthsExpected} so callers never present a partial total as if it were
 * whole. {@code MONTH} passes its single snapshot through unchanged.
 */
public final class PeriodAggregator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private PeriodAggregator() {
    }

    /** One monthly snapshot feeding an aggregation. */
    public record MonthlyReport(LocalDate periodMonth, boolean balanced, ReportData data) {
    }

    /** The aggregated report plus how much of the period it actually covers. */
    public record AggregatedReport(ReportData data, boolean complete, int monthsPresent, int monthsExpected) {
    }

    /**
     * Aggregate the {@code present} monthly snapshots of the calendar period enclosing {@code anchor}.
     * {@code present} must be non-empty (the caller returns 404 when nothing exists for the period);
     * missing months are allowed and surface as an incomplete result.
     */
    public static AggregatedReport aggregate(Granularity granularity, LocalDate anchor, List<MonthlyReport> present) {
        if (present == null || present.isEmpty()) {
            throw new IllegalArgumentException("Cannot aggregate an empty period");
        }
        LocalDate periodStart = granularity.periodStart(anchor);
        LocalDate periodEnd = granularity.periodEnd(anchor);
        int monthsExpected = granularity.monthsExpected();

        // Chronological, so the last element is the period-end month.
        List<MonthlyReport> months = new ArrayList<>(present);
        months.sort((a, b) -> a.periodMonth().compareTo(b.periodMonth()));
        MonthlyReport last = months.get(months.size() - 1);
        ReportData end = last.data();

        // ---- P&L: sum the flows, merge line items by account code ----
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal opEx = BigDecimal.ZERO;
        BigDecimal incomeTax = BigDecimal.ZERO;
        Map<String, ReportData.Item> revenueItems = new LinkedHashMap<>();
        Map<String, ReportData.Item> expenseItems = new LinkedHashMap<>();
        boolean allBalanced = true;
        for (MonthlyReport m : months) {
            ReportData.ProfitLoss pl = m.data().profitLoss();
            revenue = revenue.add(nz(pl.revenue()));
            opEx = opEx.add(nz(pl.operatingExpenses()));
            incomeTax = incomeTax.add(nz(pl.incomeTax()));
            mergeItems(revenueItems, pl.revenueItems());
            mergeItems(expenseItems, pl.expenseItems());
            allBalanced &= m.balanced();
        }
        BigDecimal grossProfit = revenue.subtract(opEx);
        BigDecimal netProfit = grossProfit.subtract(incomeTax);
        ReportData.ProfitLoss pl = new ReportData.ProfitLoss(
                revenue, sortedByAmountDesc(revenueItems), opEx, sortedByAmountDesc(expenseItems),
                grossProfit, incomeTax, netProfit);

        // ---- Balance sheet: the period-end month's position, verbatim ----
        ReportData.BalanceSheet bs = end.balanceSheet();

        // ---- KPIs: margins from the summed P&L; balance-sheet ratios from the period-end month ----
        ReportData.Kpis endKpis = end.kpis();
        ReportData.Kpis kpis = new ReportData.Kpis(
                pct(grossProfit, revenue), pct(netProfit, revenue),
                endKpis.currentAssets(), endKpis.currentLiabilities(),
                endKpis.currentRatio(), endKpis.debtToEquity());

        ReportData aggregated = new ReportData(
                end.companyName(), end.cui(), periodStart, periodEnd, allBalanced, pl, bs, kpis);

        int monthsPresent = months.size();
        return new AggregatedReport(aggregated, monthsPresent == monthsExpected, monthsPresent, monthsExpected);
    }

    /** Merge line items into the running map, summing amounts of the same account code (label kept). */
    private static void mergeItems(Map<String, ReportData.Item> into, List<ReportData.Item> items) {
        if (items == null) {
            return;
        }
        for (ReportData.Item it : items) {
            String key = it.code() != null ? it.code() : "@" + it.label();
            into.merge(key, it, (a, b) -> new ReportData.Item(a.code(), a.label(), a.amount().add(b.amount())));
        }
    }

    private static List<ReportData.Item> sortedByAmountDesc(Map<String, ReportData.Item> m) {
        List<ReportData.Item> out = new ArrayList<>(m.values());
        out.sort((a, b) -> b.amount().compareTo(a.amount()));
        return out;
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? null
                : part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
