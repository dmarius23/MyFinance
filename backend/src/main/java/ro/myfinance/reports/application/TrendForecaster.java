package ro.myfinance.reports.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import ro.myfinance.reports.application.ReportService.TrendPoint;

/**
 * A deliberately simple, explainable trend forecast: an ordinary-least-squares line fitted over the
 * historical monthly series and extended forward. Each projected point carries a confidence band on the
 * charted lines (revenue, net profit) derived from the fit's residual spread — {@code ±2σ}, null when
 * too few points exist to estimate it.
 *
 * <p>Pure and deterministic — no I/O. A forecast is a <b>non-authoritative estimate</b>: the caller must
 * present it as such (dashed continuation, "estimate") and never persist it or treat it as a real figure.
 */
public final class TrendForecaster {

    private static final BigDecimal TWO = new BigDecimal("2");

    private TrendForecaster() {
    }

    /**
     * Project {@code count} monthly points after the last historical point. Returns an empty list when
     * {@code count <= 0} or there is no history. Projected points have {@code projected == true}.
     */
    public static List<TrendPoint> forecast(List<TrendPoint> history, int count) {
        if (count <= 0 || history == null || history.isEmpty()) {
            return List.of();
        }
        int n = history.size();
        Fit revenue = fit(history, TrendPoint::revenue);
        Fit expenses = fit(history, TrendPoint::expenses);
        Fit netProfit = fit(history, TrendPoint::netProfit);
        LocalDate lastMonth = history.get(n - 1).periodMonth();

        List<TrendPoint> out = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            double x = (n - 1) + i;
            out.add(new TrendPoint(
                    lastMonth.plusMonths(i),
                    revenue.at(x), expenses.at(x), netProfit.at(x),
                    true,
                    revenue.low(x), revenue.high(x),
                    netProfit.low(x), netProfit.high(x)));
        }
        return out;
    }

    /** OLS fit of a single series (x = 0..n-1) plus the residual standard deviation for the band. */
    private static Fit fit(List<TrendPoint> history, java.util.function.Function<TrendPoint, BigDecimal> field) {
        int n = history.size();
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            BigDecimal v = field.apply(history.get(i));
            y[i] = v == null ? 0.0 : v.doubleValue();
            sx += i;
            sy += y[i];
            sxx += (double) i * i;
            sxy += i * y[i];
        }
        double denom = n * sxx - sx * sx;
        double slope = denom == 0 ? 0 : (n * sxy - sx * sy) / denom;
        double intercept = (sy - slope * sx) / n;

        double sse = 0;
        for (int i = 0; i < n; i++) {
            double e = y[i] - (slope * i + intercept);
            sse += e * e;
        }
        // Residual σ needs ≥3 points (n-2 degrees of freedom); otherwise no meaningful band.
        double sigma = n > 2 ? Math.sqrt(sse / (n - 2)) : 0.0;
        return new Fit(slope, intercept, sigma);
    }

    private record Fit(double slope, double intercept, double sigma) {

        BigDecimal at(double x) {
            return round(slope * x + intercept);
        }

        BigDecimal low(double x) {
            return sigma == 0 ? null : round(slope * x + intercept).subtract(band());
        }

        BigDecimal high(double x) {
            return sigma == 0 ? null : round(slope * x + intercept).add(band());
        }

        private BigDecimal band() {
            return round(sigma).multiply(TWO);
        }

        private static BigDecimal round(double v) {
            return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
        }
    }
}
