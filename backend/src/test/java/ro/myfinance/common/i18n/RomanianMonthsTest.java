package ro.myfinance.common.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** The one source of Romanian month names, shared by the email builders and the tax explanations. */
class RomanianMonthsTest {

    @Test
    void namesEveryMonth() {
        assertThat(RomanianMonths.name(1)).isEqualTo("Ianuarie");
        assertThat(RomanianMonths.name(4)).isEqualTo("Aprilie");
        assertThat(RomanianMonths.name(12)).isEqualTo("Decembrie");
    }

    @Test
    void monthYearIsSpaceJoinedForBothTypes() {
        assertThat(RomanianMonths.monthYear(LocalDate.of(2026, 4, 15))).isEqualTo("Aprilie 2026");
        assertThat(RomanianMonths.monthYear(YearMonth.of(2026, 3))).isEqualTo("Martie 2026");
    }
}
