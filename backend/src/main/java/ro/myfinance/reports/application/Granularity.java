package ro.myfinance.reports.application;

import java.time.LocalDate;
import java.util.List;

/**
 * The reporting period a request asks for. {@code MONTH} is the raw trial-balance slot; the wider
 * grains are <b>calendar</b> periods aggregated from the monthly snapshots they enclose — calendar
 * quarters (Q1 Jan–Mar … Q4 Oct–Dec), calendar halves (H1 Jan–Jun, H2 Jul–Dec) and the calendar year.
 * Given any day inside a period ("anchor"), it yields the period's first day and the first-of-month
 * dates it spans. No off-calendar fiscal year is supported.
 */
public enum Granularity {

    MONTH(1),
    QUARTER(3),
    HALF(6),
    YEAR(12);

    private final int monthsPerPeriod;

    Granularity(int monthsPerPeriod) {
        this.monthsPerPeriod = monthsPerPeriod;
    }

    /** How many months a complete period of this grain contains. */
    public int monthsExpected() {
        return monthsPerPeriod;
    }

    /** First day of the calendar period enclosing {@code anchor}. */
    public LocalDate periodStart(LocalDate anchor) {
        int startMonth = ((anchor.getMonthValue() - 1) / monthsPerPeriod) * monthsPerPeriod + 1;
        return LocalDate.of(anchor.getYear(), startMonth, 1);
    }

    /** Last day of the calendar period enclosing {@code anchor}. */
    public LocalDate periodEnd(LocalDate anchor) {
        return periodStart(anchor).plusMonths(monthsPerPeriod).minusDays(1);
    }

    /**
     * A short filename/label form of the period enclosing {@code anchor}:
     * {@code 2026-03} (month), {@code 2026-Q2}, {@code 2026-H1}, {@code 2026} (year).
     */
    public String label(LocalDate anchor) {
        LocalDate start = periodStart(anchor);
        return switch (this) {
            case MONTH -> start.toString().substring(0, 7);
            case QUARTER -> start.getYear() + "-Q" + ((start.getMonthValue() - 1) / 3 + 1);
            case HALF -> start.getYear() + "-H" + ((start.getMonthValue() - 1) / 6 + 1);
            case YEAR -> Integer.toString(start.getYear());
        };
    }

    /** The first-of-month dates the enclosing period spans, in chronological order. */
    public List<LocalDate> months(LocalDate anchor) {
        LocalDate start = periodStart(anchor);
        List<LocalDate> out = new java.util.ArrayList<>(monthsPerPeriod);
        for (int i = 0; i < monthsPerPeriod; i++) {
            out.add(start.plusMonths(i));
        }
        return out;
    }
}
