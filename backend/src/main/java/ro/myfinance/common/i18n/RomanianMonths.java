package ro.myfinance.common.i18n;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The single source of Romanian month names, used wherever a period is rendered for a human (email
 * subjects/bodies across tax, reports and payroll, and the tax-payment explanations). Previously the same
 * twelve-name array was copy-pasted in three places; keep it here only.
 */
public final class RomanianMonths {

    private static final String[] MONTHS = {
            "Ianuarie", "Februarie", "Martie", "Aprilie", "Mai", "Iunie",
            "Iulie", "August", "Septembrie", "Octombrie", "Noiembrie", "Decembrie"
    };

    private RomanianMonths() {
    }

    /** Romanian month name for a 1-based month value (1 = Ianuarie … 12 = Decembrie). */
    public static String name(int monthValue) {
        return MONTHS[monthValue - 1];
    }

    /** e.g. {@code "Aprilie 2026"}. */
    public static String monthYear(LocalDate period) {
        return name(period.getMonthValue()) + " " + period.getYear();
    }

    /** e.g. {@code "Aprilie 2026"}. */
    public static String monthYear(YearMonth period) {
        return name(period.getMonthValue()) + " " + period.getYear();
    }
}
