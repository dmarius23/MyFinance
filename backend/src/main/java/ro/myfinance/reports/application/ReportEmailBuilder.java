package ro.myfinance.reports.application;

import java.time.LocalDate;
import ro.myfinance.common.i18n.RomanianMonths;
import ro.myfinance.reports.domain.ReportData;

/** Builds the standard Romanian report email body. Editable before sending; figures come from the report. */
public final class ReportEmailBuilder {

    private ReportEmailBuilder() {
    }

    public static String monthYear(LocalDate period) {
        return RomanianMonths.monthYear(period);
    }

    public static String subject(LocalDate period) {
        return "Raport financiar — " + monthYear(period);
    }

    public static String body(LocalDate period, ReportData r, String accountantName) {
        String name = accountantName == null || accountantName.isBlank() ? "[Numele contabilului]" : accountantName;
        var pl = r.profitLoss();
        return String.join("\n",
                "Bună ziua,",
                "",
                "Vă transmitem atașat raportul financiar pentru luna " + monthYear(period.withDayOfMonth(1)) + ".",
                "",
                "Pe scurt:",
                "• Venituri: " + ReportPdfGenerator.money(pl.revenue()) + " RON",
                "• Cheltuieli: " + ReportPdfGenerator.money(pl.operatingExpenses().add(pl.incomeTax())) + " RON",
                "• Profit net: " + ReportPdfGenerator.money(pl.netProfit()) + " RON",
                "",
                "Detaliile complete și graficele se găsesc în documentele atașate.",
                "",
                "O zi bună,",
                name);
    }
}
