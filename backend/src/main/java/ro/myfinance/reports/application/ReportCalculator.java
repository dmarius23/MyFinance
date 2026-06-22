package ro.myfinance.reports.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ro.myfinance.reports.domain.ReportData;
import ro.myfinance.reports.domain.TrialBalanceData;

/**
 * Turns a parsed {@link TrialBalanceData} into a {@link ReportData}: the period P&L (class 6/7 movements)
 * and a condensed balance sheet (closing balances grouped by Romanian chart-of-accounts class). Pure,
 * deterministic — no I/O. The income-tax account 698 is split out of operating expenses so gross profit
 * matches SAGA's situație profit.
 */
public final class ReportCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private ReportCalculator() {
    }

    public static ReportData compute(TrialBalanceData tb) {
        List<TrialBalanceData.Line> lines = tb.lines();

        // ---- Profit & loss (period movements) ----
        List<ReportData.Item> revenueItems = new ArrayList<>();
        List<ReportData.Item> expenseItems = new ArrayList<>();
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal opEx = BigDecimal.ZERO;
        BigDecimal incomeTax = BigDecimal.ZERO;
        for (TrialBalanceData.Line l : lines) {
            if (l.accountClass() == 7) {
                BigDecimal v = nz(l.periodCredit());
                if (v.signum() != 0) {
                    revenue = revenue.add(v);
                    revenueItems.add(new ReportData.Item(l.account(), l.name(), v));
                }
            } else if (l.accountClass() == 6) {
                BigDecimal v = nz(l.periodDebit());
                if (l.account().startsWith("698")) {
                    incomeTax = incomeTax.add(v);
                } else if (v.signum() != 0) {
                    opEx = opEx.add(v);
                    expenseItems.add(new ReportData.Item(l.account(), l.name(), v));
                }
            }
        }
        expenseItems.sort((a, b) -> b.amount().compareTo(a.amount()));
        revenueItems.sort((a, b) -> b.amount().compareTo(a.amount()));
        BigDecimal grossProfit = revenue.subtract(opEx);
        BigDecimal netProfit = grossProfit.subtract(incomeTax);
        ReportData.ProfitLoss pl = new ReportData.ProfitLoss(
                revenue, revenueItems, opEx, expenseItems, grossProfit, incomeTax, netProfit);

        // ---- Condensed balance sheet (closing balances; net = debit - credit) ----
        Map<String, BigDecimal> assets = new LinkedHashMap<>();
        Map<String, BigDecimal> liabilities = new LinkedHashMap<>();
        Map<String, BigDecimal> equity = new LinkedHashMap<>();
        for (TrialBalanceData.Line l : lines) {
            BigDecimal net = nz(l.closingDebit()).subtract(nz(l.closingCredit()));
            if (net.signum() == 0) {
                continue;
            }
            String acc = l.account();
            switch (l.accountClass()) {
                case 2 -> add(assets, "Imobilizări nete", net);
                case 3 -> add(assets, "Stocuri", net);
                case 5 -> {
                    if (net.signum() > 0) {
                        add(assets, "Disponibilități", net);
                    } else {
                        add(liabilities, "Credite bancare", net.negate());
                    }
                }
                case 4 -> {
                    if (net.signum() > 0) {
                        add(assets, "Creanțe", net);
                    } else {
                        add(liabilities, payableLabel(acc), net.negate());
                    }
                }
                case 1 -> {
                    if (acc.startsWith("15") || acc.startsWith("16")) {
                        add(liabilities, "Împrumuturi și datorii asimilate", net.negate());
                    } else {
                        add(equity, equityLabel(acc), net.negate());
                    }
                }
                default -> {
                    // classes 6/7 net to zero at close — ignored here
                }
            }
        }
        ReportData.BalanceSheet bs = new ReportData.BalanceSheet(
                items(assets), sum(assets), items(liabilities), sum(liabilities), items(equity), sum(equity));

        // ---- KPIs ----
        BigDecimal currentAssets = nz(assets.get("Creanțe"))
                .add(nz(assets.get("Disponibilități"))).add(nz(assets.get("Stocuri")));
        BigDecimal currentLiab = liabilities.entrySet().stream()
                .filter(e -> !e.getKey().startsWith("Împrumuturi"))
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        ReportData.Kpis kpis = new ReportData.Kpis(
                pct(grossProfit, revenue), pct(netProfit, revenue),
                currentAssets, currentLiab, ratio(currentAssets, currentLiab),
                ratio(sum(liabilities), sum(equity)));

        return new ReportData(tb.companyName(), tb.cui(), tb.periodStart(), tb.periodEnd(), tb.balanced(),
                pl, bs, kpis);
    }

    private static String payableLabel(String acc) {
        if (acc.startsWith("40")) return "Furnizori";
        if (acc.startsWith("42") || acc.startsWith("43") || acc.startsWith("44")) return "Salarii și taxe";
        if (acc.startsWith("45")) return "Asociați / dividende";
        return "Alte datorii";
    }

    private static String equityLabel(String acc) {
        if (acc.startsWith("101") || acc.startsWith("104") || acc.startsWith("105")) return "Capital";
        if (acc.startsWith("106")) return "Rezerve";
        if (acc.startsWith("117")) return "Rezultat reportat";
        if (acc.startsWith("121") || acc.startsWith("129")) return "Rezultatul exercițiului";
        return "Alte capitaluri";
    }

    private static void add(Map<String, BigDecimal> m, String k, BigDecimal v) {
        m.merge(k, v, BigDecimal::add);
    }

    private static List<ReportData.Item> items(Map<String, BigDecimal> m) {
        List<ReportData.Item> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new ReportData.Item(null, k, v)));
        out.sort((a, b) -> b.amount().abs().compareTo(a.amount().abs()));
        return out;
    }

    private static BigDecimal sum(Map<String, BigDecimal> m) {
        return m.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? null
                : part.multiply(HUNDRED).divide(whole, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal a, BigDecimal b) {
        return b == null || b.signum() == 0 ? null : a.divide(b, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
