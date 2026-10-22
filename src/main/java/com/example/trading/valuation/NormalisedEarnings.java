package com.example.trading.valuation;

import java.util.ArrayList;
import java.util.List;

/**
 * The earnings record's own trend, and the earnings figure the grid starts from (SPEC §57.2, rules
 * VG-R1, VG-N1, VG-T1). Pure.
 *
 * <p>A log-linear least-squares fit of {@code ln(EPS)} on the fiscal year, over every contiguous
 * year on one reporting basis (five to ten of them). The slope gives the trend growth
 * {@code c = exp(slope) - 1}; its <b>standard error</b>, the <b>observation count</b> and R-squared
 * are always reported beside it, because a growth rate without them reads as firmer than five or
 * six noisy points can make it.
 *
 * <p>The fit is anchored at the latest year: {@code x = fiscalYear - latestFiscalYear}, so the
 * intercept is the fitted {@code ln(EPS)} <i>at the latest year</i> and {@code fit0 = exp(intercept)}.
 * That same intercept and slope are what the validation snapshot stores (rule VG-V1), so any later
 * grading can be recomputed from the stored parameters alone.
 *
 * <p><b>Normalised EPS {@code E0}</b> is the latest year's EPS unless it is more than
 * {@link ValuationGapRules#PEAK_FACTOR} times the fitted value (a clear peak), in which case the
 * fitted value is used. One-sided: a trough is never lifted, because lifting a bad year toward the
 * trend is a forecast of recovery in all but name.
 *
 * <p>Both reliability gates are provisional [P] and are described as exactly that wherever they are
 * printed: they decide whether the FY2031 historical-trend comparison is <i>shown</i>, and they are
 * not evidence that a trend carries on.
 */
public final class NormalisedEarnings {

    private NormalisedEarnings() {
    }

    /**
     * @param slope           slope of ln(EPS) per year
     * @param intercept       fitted ln(EPS) at the latest year
     * @param trendGrowthPct  exp(slope) - 1, in percent
     * @param stdErrPp        standard error of the slope, in percentage points of annual log-growth
     * @param rSquared        null when the series is constant (nothing to explain)
     * @param endpointCagrPct first-to-last compound rate, shown beside the fit
     */
    public record Fit(int observations, int firstFiscalYear, int lastFiscalYear, double slope, double intercept,
                      double trendGrowthPct, double stdErrPp, Double rSquared, double endpointCagrPct,
                      double fittedLatestEps, double latestEps, double normalisedEps, List<String> flags) {

        public boolean stdErrGatePasses() {
            return stdErrPp <= ValuationGapRules.MAX_STD_ERR_PP;
        }

        public boolean rSquaredGatePasses() {
            return rSquared != null && rSquared >= ValuationGapRules.MIN_R_SQUARED;
        }

        /** Both reliability gates: the FY2031 historical-trend comparison is shown only if true. */
        public boolean reliable() {
            return stdErrGatePasses() && rSquaredGatePasses();
        }

        /** The trend growth as a fraction, for the arithmetic. */
        public double trendGrowth() {
            return trendGrowthPct / 100.0;
        }
    }

    /**
     * @param years contiguous, oldest first, every EPS strictly positive (the caller has refused a loss)
     * @return null when fewer than the minimum observations are supplied
     */
    public static Fit fit(List<EpsSeries.Year> years) {
        int n = years.size();
        if (n < ValuationGapRules.MIN_OBSERVATIONS) return null;
        int last = years.get(n - 1).fiscalYear();
        double[] x = new double[n];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = years.get(i).fiscalYear() - last;
            y[i] = Math.log(years.get(i).eps());
        }
        double mx = mean(x);
        double my = mean(y);
        double sxx = 0, sxy = 0, syy = 0;
        for (int i = 0; i < n; i++) {
            sxx += (x[i] - mx) * (x[i] - mx);
            sxy += (x[i] - mx) * (y[i] - my);
            syy += (y[i] - my) * (y[i] - my);
        }
        double slope = sxy / sxx;
        double intercept = my - slope * mx;     // fitted ln(EPS) at x = 0, the latest year

        double sse = 0;
        for (int i = 0; i < n; i++) {
            double resid = y[i] - (intercept + slope * x[i]);
            sse += resid * resid;
        }
        double seSlope = Math.sqrt((sse / (n - 2)) / sxx);
        // Zero variance in the data is "nothing to explain", not a perfect fit: R-squared is null.
        Double r2 = syy < 1e-12 ? null : Math.max(0.0, 1.0 - sse / syy);

        double firstEps = years.get(0).eps();
        double latestEps = years.get(n - 1).eps();
        double cagr = (Math.pow(latestEps / firstEps, 1.0 / (n - 1)) - 1.0) * 100.0;
        double fit0 = Math.exp(intercept);

        List<String> flags = new ArrayList<>();
        double e0 = latestEps;
        if (latestEps > ValuationGapRules.PEAK_FACTOR * fit0) {
            e0 = fit0;
            flags.add("PEAK_ADJUSTED");
        }
        return new Fit(n, years.get(0).fiscalYear(), last, slope, intercept,
                (Math.exp(slope) - 1.0) * 100.0, seSlope * 100.0, r2, cagr, fit0, latestEps, e0, flags);
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double d : v) s += d;
        return s / v.length;
    }
}
