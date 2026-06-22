package ro.myfinance.reports.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The computed monthly financial picture derived from a {@link TrialBalanceData}: a profit &amp; loss
 * statement (class 6/7 period movements), a condensed balance sheet (closing balances grouped by
 * nature), and key ratios. Drives the branded PDF, the charts, and the on-screen report. Figures are
 * trustworthy only when {@link #balanced()} is true.
 */
public record ReportData(
        String companyName,
        String cui,
        LocalDate periodStart,
        LocalDate periodEnd,
        boolean balanced,
        ProfitLoss profitLoss,
        BalanceSheet balanceSheet,
        Kpis kpis) {

    /** A named amount (P&L line item or balance-sheet group). */
    public record Item(String code, String label, BigDecimal amount) {
    }

    /** Period P&L. operatingExpenses excludes the profit-tax account (698), matching SAGA's situație profit. */
    public record ProfitLoss(
            BigDecimal revenue, List<Item> revenueItems,
            BigDecimal operatingExpenses, List<Item> expenseItems,
            BigDecimal grossProfit,
            BigDecimal incomeTax,
            BigDecimal netProfit) {
    }

    /** Condensed balance sheet from closing balances. assets == liabilities + equity for a clean balanță. */
    public record BalanceSheet(
            List<Item> assets, BigDecimal totalAssets,
            List<Item> liabilities, BigDecimal totalLiabilities,
            List<Item> equity, BigDecimal totalEquity) {
    }

    /** Headline ratios (percentages are 0..100). Null when undefined (e.g. divide-by-zero). */
    public record Kpis(
            BigDecimal grossMargin,
            BigDecimal netMargin,
            BigDecimal currentAssets,
            BigDecimal currentLiabilities,
            BigDecimal currentRatio,
            BigDecimal debtToEquity) {
    }
}
