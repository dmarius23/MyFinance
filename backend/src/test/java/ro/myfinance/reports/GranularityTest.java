package ro.myfinance.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ro.myfinance.reports.application.Granularity;

/** Calendar-period arithmetic: bounds and the months a period spans, from any day inside it. */
class GranularityTest {

    @Test
    void monthIsTheAnchorsOwnMonth() {
        LocalDate anchor = LocalDate.of(2026, 3, 20);
        assertThat(Granularity.MONTH.periodStart(anchor)).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(Granularity.MONTH.periodEnd(anchor)).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(Granularity.MONTH.monthsExpected()).isEqualTo(1);
        assertThat(Granularity.MONTH.months(anchor)).containsExactly(LocalDate.of(2026, 3, 1));
    }

    @Test
    void quarterIsCalendarQuarter() {
        LocalDate anchor = LocalDate.of(2026, 5, 15); // Q2
        assertThat(Granularity.QUARTER.periodStart(anchor)).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(Granularity.QUARTER.periodEnd(anchor)).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(Granularity.QUARTER.monthsExpected()).isEqualTo(3);
        assertThat(Granularity.QUARTER.months(anchor))
                .containsExactly(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1));
    }

    @Test
    void quarterBoundaries() {
        assertThat(Granularity.QUARTER.periodStart(LocalDate.of(2026, 1, 1))).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(Granularity.QUARTER.periodStart(LocalDate.of(2026, 12, 31))).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(Granularity.QUARTER.periodEnd(LocalDate.of(2026, 12, 31))).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void halfIsCalendarHalf() {
        assertThat(Granularity.HALF.periodStart(LocalDate.of(2026, 6, 30))).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(Granularity.HALF.periodEnd(LocalDate.of(2026, 6, 30))).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(Granularity.HALF.periodStart(LocalDate.of(2026, 8, 1))).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(Granularity.HALF.periodEnd(LocalDate.of(2026, 8, 1))).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(Granularity.HALF.monthsExpected()).isEqualTo(6);
        assertThat(Granularity.HALF.months(LocalDate.of(2026, 8, 1))).hasSize(6)
                .startsWith(LocalDate.of(2026, 7, 1)).endsWith(LocalDate.of(2026, 12, 1));
    }

    @Test
    void yearIsCalendarYear() {
        LocalDate anchor = LocalDate.of(2026, 3, 10);
        assertThat(Granularity.YEAR.periodStart(anchor)).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(Granularity.YEAR.periodEnd(anchor)).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(Granularity.YEAR.monthsExpected()).isEqualTo(12);
        assertThat(Granularity.YEAR.months(anchor)).hasSize(12)
                .startsWith(LocalDate.of(2026, 1, 1)).endsWith(LocalDate.of(2026, 12, 1));
    }
}
