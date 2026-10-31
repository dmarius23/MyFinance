package ro.myfinance.payroll.application;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import ro.myfinance.common.i18n.RomanianMonths;

/**
 * Assembles the variable map the template renderer consumes for a {@code PAYROLL} email. The body
 * carries no money figures — amounts live in the attached pay statement. The tax-payment deadline is
 * always the 25th of the period month.
 */
public final class PayrollEmailBuilder {

    private PayrollEmailBuilder() {
    }

    /** Romanian month name + year for a period month, e.g. "Aprilie 2026". */
    public static String monthYear(LocalDate period) {
        return RomanianMonths.monthYear(period);
    }

    /** Everything a {@code PAYROLL} template may reference. The tax deadline is the 25th of the period. */
    public static Map<String, String> values(LocalDate period, String companyName,
                                             String senderName, String firmName) {
        LocalDate p = period.withDayOfMonth(1);
        String my = monthYear(p);
        Map<String, String> v = new HashMap<>();
        v.put("companyName", companyName == null ? "" : companyName);
        v.put("month", my);
        v.put("taxDeadline", "25 " + my);
        v.put("signOff", ro.myfinance.common.email.SignOff.names(senderName, firmName));
        return v;
    }
}
