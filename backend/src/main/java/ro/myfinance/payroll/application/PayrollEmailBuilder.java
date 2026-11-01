package ro.myfinance.payroll.application;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import ro.myfinance.common.i18n.RomanianMonths;

/**
 * Assembles the variable map the template renderer consumes for a {@code PAYROLL} email. The body
 * carries no money figures — amounts live in the attached pay statement.
 *
 * <p>The salary-tax deadline falls in the <b>month following</b> the payroll period: taxes on April
 * salaries are due in May. The <em>day</em> is statutory and comes from effective-dated reference data
 * ({@code PlatformTaxDeadlineService}), resolved by the caller and merely formatted here — so a change in
 * statute needs no deploy, and this email cannot drift away from
 * {@code AnafDeclarationExtractor}'s D112 fallback or {@code DashboardService}'s overdue gate, which read
 * the same source. This email once contradicted both by naming the period's own month.
 */
public final class PayrollEmailBuilder {

    private PayrollEmailBuilder() {
    }

    /** Romanian month name + year for a period month, e.g. "Aprilie 2026". */
    public static String monthYear(LocalDate period) {
        return RomanianMonths.monthYear(period);
    }

    /**
     * Everything a {@code PAYROLL} template may reference. {@code taxDeadline} is resolved by the caller
     * from effective-dated reference data and merely formatted here — the builder stays a pure
     * variable-map assembler with no injected dependency, which is also what keeps the golden wording
     * test deterministic without a database.
     *
     * <p>There is deliberately no four-argument overload: a call site that forgot the deadline would
     * silently fall back to a hardcoded day and rebuild the drift this indirection exists to remove.
     */
    public static Map<String, String> values(LocalDate period, String companyName,
                                             String senderName, String firmName,
                                             LocalDate taxDeadline) {
        LocalDate p = period.withDayOfMonth(1);
        Map<String, String> v = new HashMap<>();
        v.put("companyName", companyName == null ? "" : companyName);
        v.put("month", monthYear(p));
        v.put("taxDeadline", taxDeadline == null ? ""
                : taxDeadline.getDayOfMonth() + " " + monthYear(taxDeadline));
        v.put("signOff", ro.myfinance.common.email.SignOff.names(senderName, firmName));
        return v;
    }
}
