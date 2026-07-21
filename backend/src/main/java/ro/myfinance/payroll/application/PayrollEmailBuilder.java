package ro.myfinance.payroll.application;

import java.time.LocalDate;
import ro.myfinance.common.i18n.RomanianMonths;

/**
 * Builds the standard Romanian payroll email body. The body is a default the accountant edits before
 * sending — it carries no money figures (the amounts live in the attached pay statement / payslip), so
 * nothing here needs reconciliation. The tax-payment deadline is the 25th of the period month.
 */
public final class PayrollEmailBuilder {

    private PayrollEmailBuilder() {
    }

    /** Romanian month name + year for a period month, e.g. "Aprilie 2026". */
    public static String monthYear(LocalDate period) {
        return RomanianMonths.monthYear(period);
    }

    public static String subject(LocalDate period) {
        return "State de plată — " + monthYear(period);
    }

    /** The standard body for a company/period. {@code accountantName} may be blank (left as a placeholder). */
    public static String body(LocalDate period, String accountantName) {
        LocalDate p = period.withDayOfMonth(1);
        String my = monthYear(p);
        String deadline = "25 " + my;
        String name = accountantName == null || accountantName.isBlank() ? "[Numele contabilului]" : accountantName;
        return String.join("\n",
                "Bună ziua,",
                "",
                "Vă rog frumos să regăsiți atașate statul de plată, fluturașul de salariu și pontajul "
                        + "pentru luna " + my + ".",
                "",
                "Restul de plată de pe statul de plată și fluturaș trebuie virat din contul firmei în "
                        + "contul personal al salariatului.",
                "",
                "Diferența dintre restul de plată și salariul brut reprezintă taxele salariale. Acestea "
                        + "se vor plăti până în data de " + deadline + ".",
                "",
                "În ceea ce privește sumele care se plătesc către Trezorerie, precum și conturile "
                        + "aferente, vom reveni cu un email separat.",
                "",
                "O zi bună,",
                name);
    }
}
