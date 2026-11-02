package ro.myfinance.reports.application;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import ro.myfinance.common.i18n.RomanianMonths;
import ro.myfinance.reports.domain.ReportData;

/**
 * Assembles the variable map the template renderer consumes for a {@code REPORT} email. Money figures
 * come straight from the report; formatting stays here, never in a template — a tenant may reposition
 * figures but must never be able to reformat them.
 */
public final class ReportEmailBuilder {

    private ReportEmailBuilder() {
    }

    public static String monthYear(LocalDate period) {
        return RomanianMonths.monthYear(period);
    }

    /** Everything a {@code REPORT} template may reference. Figures come straight from the report. */
    public static Map<String, String> values(LocalDate period, ReportData r,
                                             String senderName, String firmName) {
        var pl = r.profitLoss();
        Map<String, String> v = new HashMap<>();
        v.put("companyName", r.companyName() == null ? "" : r.companyName());
        v.put("cui", r.cui() == null ? "" : r.cui());
        v.put("month", monthYear(period.withDayOfMonth(1)));
        v.put("revenue", ReportPdfGenerator.money(pl.revenue()));
        v.put("expenses", ReportPdfGenerator.money(pl.operatingExpenses().add(pl.incomeTax())));
        v.put("grossProfit", ReportPdfGenerator.money(pl.grossProfit()));
        v.put("incomeTax", ReportPdfGenerator.money(pl.incomeTax()));
        v.put("netProfit", ReportPdfGenerator.money(pl.netProfit()));
        v.put("signOff", ro.myfinance.common.email.SignOff.names(senderName, firmName));
        return v;
    }
}
