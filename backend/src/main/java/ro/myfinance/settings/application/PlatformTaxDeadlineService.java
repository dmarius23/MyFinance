package ro.myfinance.settings.application;

import java.time.LocalDate;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.settings.adapter.persistence.PlatformTaxDeadlineRepository;
import ro.myfinance.settings.domain.PlatformTaxDeadline;

/**
 * Period-aware resolver for the salary-tax deadline. GLOBAL, not tenant-scoped (see V63).
 *
 * <p>The rule is chosen by the PERIOD, not by today's date: re-sending March's payroll email in December
 * must restate March's deadline, not whatever statute is current. Callers therefore pass the payroll
 * period — passing {@code YearMonth.now()} would defeat the effective dating.
 */
@Service
@Transactional(readOnly = true)
public class PlatformTaxDeadlineService {

    /** Used when no row is configured at or before the period. The statute in force as of 2026. */
    public static final int DEFAULT_DAY = 25;

    private static final Logger log = LoggerFactory.getLogger(PlatformTaxDeadlineService.class);

    private final PlatformTaxDeadlineRepository deadlines;

    public PlatformTaxDeadlineService(PlatformTaxDeadlineRepository deadlines) {
        this.deadlines = deadlines;
    }

    /**
     * The statutory day-of-month in force for {@code period}. Falls back to {@link #DEFAULT_DAY} rather
     * than throwing or returning empty: a missing row must not fail a payroll send mid-flight, and three
     * consumers each inventing their own fallback is how the drift this table exists to remove restarts.
     */
    public int dayFor(YearMonth period) {
        if (period == null) {
            return DEFAULT_DAY;
        }
        return deadlines.findTopByValidFromLessThanEqualOrderByValidFromDesc(period.atDay(1))
                .map(PlatformTaxDeadline::getDayOfMonth)
                .map(Short::intValue)
                .orElseGet(() -> {
                    log.warn("No salary-tax deadline configured at or before {} — using day {}",
                            period, DEFAULT_DAY);
                    return DEFAULT_DAY;
                });
    }

    /** The deadline date: the configured day of the month AFTER {@code period}. */
    public LocalDate deadlineFor(YearMonth period) {
        YearMonth due = (period == null ? YearMonth.now() : period).plusMonths(1);
        return due.atDay(dayFor(period));
    }
}
