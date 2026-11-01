package ro.myfinance.payroll.application;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import ro.myfinance.common.i18n.RomanianMonths;

/**
 * Assembles the variable map the template renderer consumes for a {@code PAYROLL} email. The body
 * carries no money figures — amounts live in the attached pay statement.
 *
 * <p>The salary-tax deadline is the <b>25th of the month following</b> the payroll period: taxes on
 * April salaries are due 25 May. This matches the rest of the codebase —
 * {@code AnafDeclarationExtractor.defaultDeadline} ("the 25th of the next month") and
 * {@code DashboardService}'s {@code month.plusMonths(1).withDayOfMonth(25)} — which this email
 * previously contradicted by naming the period's own month.
 */
public final class PayrollEmailBuilder {

    private PayrollEmailBuilder() {
    }

    /** Romanian month name + year for a period month, e.g. "Aprilie 2026". */
    public static String monthYear(LocalDate period) {
        return RomanianMonths.monthYear(period);
    }

    /**
     * Everything a {@code PAYROLL} template may reference. {@code taxDeadline} is the 25th of the month
     * AFTER the period — {@code plusMonths(1)} also carries the year, so December's payroll correctly
     * reads "25 Ianuarie" of the next year.
     */
    public static Map<String, String> values(LocalDate period, String companyName,
                                             String senderName, String firmName) {
        LocalDate p = period.withDayOfMonth(1);
        String my = monthYear(p);
        Map<String, String> v = new HashMap<>();
        v.put("companyName", companyName == null ? "" : companyName);
        v.put("month", my);
        v.put("taxDeadline", "25 " + monthYear(p.plusMonths(1)));
        v.put("signOff", ro.myfinance.common.email.SignOff.names(senderName, firmName));
        return v;
    }
}
