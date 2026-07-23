package ro.myfinance.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ro.myfinance.reports.application.ReportService.TrendPoint;
import ro.myfinance.reports.application.TrendForecaster;

/** The simple OLS trend projection: slope extrapolation, residual band, and the guard rails around them. */
class TrendForecasterTest {

    @Test
    void emptyOrNonPositiveCountYieldsNoForecast() {
        assertThat(TrendForecaster.forecast(List.of(), 3)).isEmpty();
        assertThat(TrendForecaster.forecast(history(100, 200, 300), 0)).isEmpty();
    }

    @Test
    void projectsTheObviousSlopeAndContinuesMonthly() {
        List<TrendPoint> forecast = TrendForecaster.forecast(history(100, 200, 300), 2);

        assertThat(forecast).hasSize(2);
        TrendPoint first = forecast.get(0);
        assertThat(first.projected()).isTrue();
        assertThat(first.periodMonth()).isEqualTo(LocalDate.of(2026, 4, 1)); // month after the last (March)
        assertThat(first.revenue()).isEqualByComparingTo("400");
        assertThat(first.netProfit()).isEqualByComparingTo("400");
        assertThat(forecast.get(1).periodMonth()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(forecast.get(1).revenue()).isEqualByComparingTo("500");
    }

    @Test
    void perfectlyLinearSeriesHasNoBand() {
        // zero residuals → σ = 0 → no meaningful confidence band
        TrendPoint p = TrendForecaster.forecast(history(100, 200, 300), 1).get(0);
        assertThat(p.revenueLow()).isNull();
        assertThat(p.revenueHigh()).isNull();
        assertThat(p.netProfitLow()).isNull();
        assertThat(p.netProfitHigh()).isNull();
    }

    @Test
    void noisySeriesGetsABandAroundTheProjection() {
        TrendPoint p = TrendForecaster.forecast(history(100, 150, 300, 250), 1).get(0);
        assertThat(p.revenueLow()).isNotNull();
        assertThat(p.revenueHigh()).isNotNull();
        assertThat(p.revenueLow()).isLessThan(p.revenue());
        assertThat(p.revenueHigh()).isGreaterThan(p.revenue());
    }

    @Test
    void twoPointsStillProjectButWithoutABand() {
        TrendPoint p = TrendForecaster.forecast(history(100, 200), 1).get(0);
        assertThat(p.revenue()).isEqualByComparingTo("300"); // slope 100 extended
        assertThat(p.revenueLow()).isNull(); // σ needs ≥3 points
    }

    /** Monthly history starting Jan 2026; net profit mirrors revenue, expenses held flat. */
    private static List<TrendPoint> history(double... revenues) {
        List<TrendPoint> out = new ArrayList<>();
        LocalDate month = LocalDate.of(2026, 1, 1);
        for (double r : revenues) {
            out.add(TrendPoint.actual(month, BigDecimal.valueOf(r), BigDecimal.valueOf(50), BigDecimal.valueOf(r)));
            month = month.plusMonths(1);
        }
        return out;
    }
}
