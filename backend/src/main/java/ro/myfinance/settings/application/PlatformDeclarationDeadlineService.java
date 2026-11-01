package ro.myfinance.settings.application;

import java.time.LocalDate;
import java.time.YearMonth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.myfinance.settings.adapter.persistence.PlatformDeclarationDeadlineRepository;
import ro.myfinance.settings.domain.PlatformDeclarationDeadline;

/**
 * Period-aware resolver for the monthly ANAF declaration deadline. GLOBAL, not tenant-scoped (see V69).
 *
 * <p>The rule is chosen by the PERIOD, not by today's date: re-sending March's payroll email in December
 * must restate March's deadline, not whatever statute is current. Callers therefore pass the reported
 * period — passing {@code YearMonth.now()} would defeat the effective dating.
 *
 * <p>Consumed by payroll (D112), the dashboard's overdue flag, and {@code AnafDeclarationExtractor} for
 * D300/D100. One day covers all three because Romanian law currently sets them all on the 25th.
 */
@Service
@Transactional(readOnly = true)
public class PlatformDeclarationDeadlineService {

    /** Used when no row is configured at or before the period. The statute in force as of 2026. */
    public static final int DEFAULT_DAY = 25;

    private static final Logger log = LoggerFactory.getLogger(PlatformDeclarationDeadlineService.class);

    private final PlatformDeclarationDeadlineRepository deadlines;

    public PlatformDeclarationDeadlineService(PlatformDeclarationDeadlineRepository deadlines) {
        this.deadlines = deadlines;
    }

    /**
     * The statutory day-of-month in force for {@code period}. Falls back to {@link #DEFAULT_DAY} rather
     * than throwing or returning empty: a missing row must not fail a payroll send mid-flight, and three
     * consumers each inventing their own fallback is how the drift this table exists to remove restarts.
     */
    public int dayFor(YearMonth period) {
        if (period == null) {
            // Logged as loudly as the missing-row case: both mean "we guessed", and a silent null was
            // how a caller could lose the effective dating without anything showing up in the logs.
            log.warn("Declaration deadline requested with no period — using day {}", DEFAULT_DAY);
            return DEFAULT_DAY;
        }
        return deadlines.findTopByValidFromLessThanEqualOrderByValidFromDesc(period.atDay(1))
                .map(PlatformDeclarationDeadline::getDayOfMonth)
                .map(Short::intValue)
                .orElseGet(() -> {
                    log.warn("No declaration deadline configured at or before {} — using day {}",
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
