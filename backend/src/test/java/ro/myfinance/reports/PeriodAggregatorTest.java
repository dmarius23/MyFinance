package ro.myfinance.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ro.myfinance.reports.application.Granularity;
import ro.myfinance.reports.application.PeriodAggregator;
import ro.myfinance.reports.application.PeriodAggregator.AggregatedReport;
import ro.myfinance.reports.application.PeriodAggregator.MonthlyReport;
import ro.myfinance.reports.domain.ReportData;

/** Folding monthly snapshots into a calendar-period report: summed P&L, period-end balance sheet. */
class PeriodAggregatorTest {

    @Test
    void sumsProfitAndLossFlowsAndRecomputesMargins() {
        AggregatedReport agg = PeriodAggregator.aggregate(Granularity.QUARTER, LocalDate.of(2026, 1, 15), List.of(
                month(LocalDate.of(2026, 1, 1), true, report(100, 40, 10, 500)),
                month(LocalDate.of(2026, 2, 1), true, report(200, 60, 20, 900))));

        ReportData.ProfitLoss pl = agg.data().profitLoss();
        assertThat(pl.revenue()).isEqualByComparingTo("300");
        assertThat(pl.operatingExpenses()).isEqualByComparingTo("100");
        assertThat(pl.incomeTax()).isEqualByComparingTo("30");
        assertThat(pl.grossProfit()).isEqualByComparingTo("200");
        assertThat(pl.netProfit()).isEqualByComparingTo("170");
        // margins recomputed from the summed P&L, not averaged
        assertThat(agg.data().kpis().grossMargin()).isEqualByComparingTo("66.7"); // 200/300
        assertThat(agg.data().kpis().netMargin()).isEqualByComparingTo("56.7");   // 170/300
    }

    @Test
    void balanceSheetIsThePeriodEndMonthNotSummed() {
        AggregatedReport agg = PeriodAggregator.aggregate(Granularity.QUARTER, LocalDate.of(2026, 1, 15), List.of(
                month(LocalDate.of(2026, 1, 1), true, report(100, 40, 10, 500)),
                month(LocalDate.of(2026, 3, 1), true, report(200, 60, 20, 900)),
                month(LocalDate.of(2026, 2, 1), true, report(150, 50, 15, 700))));

        // March is the latest month → its closing position (900), regardless of input order
        assertThat(agg.data().balanceSheet().totalAssets()).isEqualByComparingTo("900");
        assertThat(agg.data().kpis().currentAssets()).isEqualByComparingTo("900");
        assertThat(agg.data().kpis().currentRatio()).isEqualByComparingTo("900");
    }

    @Test
    void mergesLineItemsByAccountCodeSortedDescending() {
        ReportData jan = reportWithItems(
                List.of(new ReportData.Item("707", "Vânzări", bd(100))),
                List.of(new ReportData.Item("601", "Materiale", bd(30))));
        ReportData feb = reportWithItems(
                List.of(new ReportData.Item("707", "Vânzări", bd(200)),
                        new ReportData.Item("704", "Servicii", bd(500))),
                List.of(new ReportData.Item("601", "Materiale", bd(70))));

        AggregatedReport agg = PeriodAggregator.aggregate(Granularity.QUARTER, LocalDate.of(2026, 1, 1), List.of(
                month(LocalDate.of(2026, 1, 1), true, jan),
                month(LocalDate.of(2026, 2, 1), true, feb)));

        List<ReportData.Item> rev = agg.data().profitLoss().revenueItems();
        assertThat(rev).extracting(ReportData.Item::code).containsExactly("704", "707"); // 500 before 300
        assertThat(rev).extracting(i -> i.amount().stripTrailingZeros())
                .containsExactly(bd(500).stripTrailingZeros(), bd(300).stripTrailingZeros());
        List<ReportData.Item> exp = agg.data().profitLoss().expenseItems();
        assertThat(exp).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo("601");
            assertThat(i.amount()).isEqualByComparingTo("100");
        });
    }

    @Test
    void balancedOnlyWhenEveryPresentMonthIsBalanced() {
        AggregatedReport agg = PeriodAggregator.aggregate(Granularity.QUARTER, LocalDate.of(2026, 1, 1), List.of(
                month(LocalDate.of(2026, 1, 1), true, report(100, 40, 10, 500)),
                month(LocalDate.of(2026, 2, 1), false, report(200, 60, 20, 900))));
        assertThat(agg.data().balanced()).isFalse();
    }

    @Test
    void reportsIncompletePeriodHonestly() {
        AggregatedReport agg = PeriodAggregator.aggregate(Granularity.QUARTER, LocalDate.of(2026, 4, 1), List.of(
                month(LocalDate.of(2026, 4, 1), true, report(100, 40, 10, 500)),
                month(LocalDate.of(2026, 6, 1), true, report(200, 60, 20, 900))));
        assertThat(agg.monthsPresent()).isEqualTo(2);
        assertThat(agg.monthsExpected()).isEqualTo(3);
        assertThat(agg.complete()).isFalse();
        // period bounds are the full calendar quarter even when a month is missing
        assertThat(agg.data().periodStart()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(agg.data().periodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void monthPassesSingleSnapshotThrough() {
        AggregatedReport agg = PeriodAggregator.aggregate(Granularity.MONTH, LocalDate.of(2026, 3, 20), List.of(
                month(LocalDate.of(2026, 3, 1), true, report(100, 40, 10, 500))));
        assertThat(agg.complete()).isTrue();
        assertThat(agg.monthsExpected()).isEqualTo(1);
        assertThat(agg.data().profitLoss().revenue()).isEqualByComparingTo("100");
        assertThat(agg.data().periodStart()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(agg.data().periodEnd()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void rejectsEmptyPeriod() {
        assertThatThrownBy(() ->
                PeriodAggregator.aggregate(Granularity.QUARTER, LocalDate.of(2026, 1, 1), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---

    private static MonthlyReport month(LocalDate periodMonth, boolean balanced, ReportData data) {
        return new MonthlyReport(periodMonth, balanced, data);
    }

    /** A report whose balance sheet / current-ratio KPIs are keyed to {@code totalAssets} for assertions. */
    private static ReportData report(double revenue, double opEx, double tax, double totalAssets) {
        return reportWith(revenue, opEx, tax, totalAssets, List.of(), List.of());
    }

    private static ReportData reportWithItems(List<ReportData.Item> revItems, List<ReportData.Item> expItems) {
        return reportWith(0, 0, 0, 0, revItems, expItems);
    }

    private static ReportData reportWith(double revenue, double opEx, double tax, double totalAssets,
                                         List<ReportData.Item> revItems, List<ReportData.Item> expItems) {
        BigDecimal rev = bd(revenue);
        BigDecimal ex = bd(opEx);
        BigDecimal t = bd(tax);
        BigDecimal gp = rev.subtract(ex);
        BigDecimal np = gp.subtract(t);
        ReportData.ProfitLoss pl = new ReportData.ProfitLoss(rev, revItems, ex, expItems, gp, t, np);
        ReportData.BalanceSheet bs = new ReportData.BalanceSheet(
                List.of(new ReportData.Item(null, "Active", bd(totalAssets))), bd(totalAssets),
                List.of(), BigDecimal.ZERO, List.of(), BigDecimal.ZERO);
        // currentAssets and currentRatio carry totalAssets so tests can prove period-end selection
        ReportData.Kpis kpis = new ReportData.Kpis(
                null, null, bd(totalAssets), BigDecimal.ZERO, bd(totalAssets), BigDecimal.ZERO);
        return new ReportData("ACME SRL", "RO123", null, null, true, pl, bs, kpis);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
