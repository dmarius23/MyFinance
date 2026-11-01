package ro.myfinance.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ro.myfinance.settings.adapter.persistence.PlatformTaxDeadlineRepository;
import ro.myfinance.settings.application.PlatformTaxDeadlineService;
import ro.myfinance.settings.domain.PlatformTaxDeadline;

/**
 * The salary-tax deadline is the configured day of the month AFTER the period, resolved by the rule in
 * force during that PERIOD — not the rule in force today. That distinction is what lets an old month's
 * email be re-sent without silently restating a newer statute.
 */
class PlatformTaxDeadlineServiceTest {

    private final PlatformTaxDeadlineRepository repo = mock(PlatformTaxDeadlineRepository.class);
    private final PlatformTaxDeadlineService service = new PlatformTaxDeadlineService(repo);

    private void givenRule(short day, String validFrom) {
        when(repo.findTopByValidFromLessThanEqualOrderByValidFromDesc(any()))
                .thenReturn(Optional.of(new PlatformTaxDeadline(day, LocalDate.parse(validFrom))));
    }

    @Test
    void resolvesTheConfiguredDayInTheMonthAfterThePeriod() {
        givenRule((short) 25, "2020-01-01");
        assertThat(service.dayFor(YearMonth.of(2026, 4))).isEqualTo(25);
        assertThat(service.deadlineFor(YearMonth.of(2026, 4))).isEqualTo(LocalDate.of(2026, 5, 25));
    }

    @Test
    void aChangedDayMovesTheDeadlineWithinTheSameFollowingMonth() {
        givenRule((short) 20, "2026-01-01");
        assertThat(service.deadlineFor(YearMonth.of(2026, 4))).isEqualTo(LocalDate.of(2026, 5, 20));
    }

    @Test
    void decemberRollsIntoTheFollowingYear() {
        givenRule((short) 25, "2020-01-01");
        assertThat(service.deadlineFor(YearMonth.of(2026, 12))).isEqualTo(LocalDate.of(2027, 1, 25));
    }

    /**
     * Review Focus 2: the admin API can delete the seeded row, leaving the table empty. Resolution must
     * fall back rather than throw — a missing row must never fail a payroll send mid-flight.
     */
    @Test
    void anEmptyTableFallsBackToDayTwentyFiveRatherThanThrowing() {
        when(repo.findTopByValidFromLessThanEqualOrderByValidFromDesc(any()))
                .thenReturn(Optional.empty());
        assertThat(service.dayFor(YearMonth.of(2026, 4))).isEqualTo(25);
        assertThat(service.deadlineFor(YearMonth.of(2026, 4))).isEqualTo(LocalDate.of(2026, 5, 25));
    }

    @Test
    void aNullPeriodFallsBackInsteadOfThrowing() {
        assertThat(service.dayFor(null)).isEqualTo(25);
    }

    /**
     * Review Focus 1 + 3: the rule is chosen by the PERIOD, not by today. A rule effective 2027 must not
     * touch a 2026 period, and re-resolving an old period must still give the old answer. This is the
     * test that fails if someone resolves with YearMonth.now().
     */
    @Test
    void theRuleIsSelectedByThePeriodNotByToday() {
        // Two rules: 25 from 2020, 20 from 2027. The repository is asked with the PERIOD's first day,
        // so a 2026 period must be matched against the 2020 rule.
        when(repo.findTopByValidFromLessThanEqualOrderByValidFromDesc(LocalDate.of(2026, 4, 1)))
                .thenReturn(Optional.of(new PlatformTaxDeadline((short) 25, LocalDate.of(2020, 1, 1))));
        when(repo.findTopByValidFromLessThanEqualOrderByValidFromDesc(LocalDate.of(2027, 4, 1)))
                .thenReturn(Optional.of(new PlatformTaxDeadline((short) 20, LocalDate.of(2027, 1, 1))));

        assertThat(service.deadlineFor(YearMonth.of(2026, 4))).isEqualTo(LocalDate.of(2026, 5, 25));
        assertThat(service.deadlineFor(YearMonth.of(2027, 4))).isEqualTo(LocalDate.of(2027, 5, 20));
    }

    @Test
    void queriesTheRepositoryWithThePeriodsFirstDay() {
        givenRule((short) 25, "2020-01-01");
        service.dayFor(YearMonth.of(2026, 4));
        org.mockito.Mockito.verify(repo)
                .findTopByValidFromLessThanEqualOrderByValidFromDesc(LocalDate.of(2026, 4, 1));
    }

    /**
     * Review Focus 4: day 28 is the highest the CHECK permits, precisely so February can never shorten
     * a deadline. A January period therefore resolves into February without clamping.
     */
    @Test
    void dayTwentyEightWithAJanuaryPeriodResolvesIntoFebruary() {
        givenRule((short) 28, "2020-01-01");
        assertThat(service.deadlineFor(YearMonth.of(2026, 1))).isEqualTo(LocalDate.of(2026, 2, 28));
        // And in a leap year, still the 28th — the rule is a fixed day, not "end of month".
        assertThat(service.deadlineFor(YearMonth.of(2028, 1))).isEqualTo(LocalDate.of(2028, 2, 28));
    }
}
