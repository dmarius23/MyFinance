package ro.myfinance.support;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.YearMonth;
import ro.myfinance.settings.application.PlatformTaxDeadlineService;

/**
 * A {@link PlatformTaxDeadlineService} stub matching the rule seeded by {@code V63}: day 25 of the month
 * following the period.
 *
 * <p>Exists so the several tests that construct {@code AnafDeclarationExtractor} directly keep the
 * expectations they had when the deadline was a hardcoded constant, without each repeating the same
 * five-line mock. Tests that care about a DIFFERENT configured day should stub the service themselves
 * rather than extend this — the point of this helper is "behaves like production's seed".
 */
public final class TestTaxDeadlines {

    private TestTaxDeadlines() {
    }

    /** A stub resolving every period to the 25th of the following month. */
    public static PlatformTaxDeadlineService seeded() {
        PlatformTaxDeadlineService stub = mock(PlatformTaxDeadlineService.class);
        when(stub.deadlineFor(any(YearMonth.class)))
                .thenAnswer(i -> ((YearMonth) i.getArgument(0)).plusMonths(1).atDay(25));
        when(stub.dayFor(any(YearMonth.class))).thenReturn(25);
        return stub;
    }
}
