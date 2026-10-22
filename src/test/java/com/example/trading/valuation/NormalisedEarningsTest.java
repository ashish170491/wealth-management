package com.example.trading.valuation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** SPEC §57.2 rules VG-N1, VG-R1, VG-T1: the record's own trend, its standard error and its gates. */
class NormalisedEarningsTest {

    private static List<EpsSeries.Year> series(int lastFy, double... eps) {
        List<EpsSeries.Year> out = new ArrayList<>();
        for (int i = 0; i < eps.length; i++) {
            out.add(new EpsSeries.Year(lastFy - (eps.length - 1) + i, eps[i] * 10, 10.0, 10.0, true, 1.0, eps[i],
                    null, null, "XBRL", null));
        }
        return out;
    }

    private static double[] growing(int n, double eps0, double g) {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = eps0 * Math.pow(1 + g, i);
        return v;
    }

    @Test
    @DisplayName("a perfectly compounding series recovers its growth rate with zero standard error and R-squared 1")
    void exactCompounding() {
        var f = NormalisedEarnings.fit(series(2026, growing(8, 10, 0.15)));
        assertThat(f.trendGrowthPct()).isCloseTo(15.0, within(1e-6));
        assertThat(f.stdErrPp()).isCloseTo(0.0, within(1e-6));
        assertThat(f.rSquared()).isCloseTo(1.0, within(1e-9));
        assertThat(f.observations()).isEqualTo(8);
        assertThat(f.firstFiscalYear()).isEqualTo(2019);
        assertThat(f.lastFiscalYear()).isEqualTo(2026);
        assertThat(f.reliable()).isTrue();
        assertThat(f.flags()).isEmpty();
        assertThat(f.normalisedEps()).isCloseTo(f.latestEps(), within(1e-9));
    }

    @Test
    @DisplayName("the intercept is the fitted ln(EPS) at the LATEST year, so exp(intercept) is the fitted latest EPS")
    void interceptIsAtTheLatestYear() {
        double[] eps = growing(6, 10, 0.2);
        var f = NormalisedEarnings.fit(series(2026, eps));
        assertThat(Math.exp(f.intercept())).isCloseTo(eps[5], within(1e-6));
        assertThat(f.fittedLatestEps()).isCloseTo(eps[5], within(1e-6));
    }

    @Test
    @DisplayName("fewer than the minimum observations gives no fit at all")
    void tooFew() {
        assertThat(NormalisedEarnings.fit(series(2026, growing(4, 10, 0.1)))).isNull();
    }

    @Test
    @DisplayName("a clear peak (latest > 1.5x the fit) is replaced by the fitted value")
    void peakIsLowered() {
        double[] eps = {10, 10.5, 11, 11.5, 12, 12.5, 13, 30};
        var f = NormalisedEarnings.fit(series(2026, eps));
        assertThat(f.flags()).containsExactly("PEAK_ADJUSTED");
        assertThat(f.normalisedEps()).isLessThan(f.latestEps());
        assertThat(f.normalisedEps()).isCloseTo(f.fittedLatestEps(), within(1e-9));
    }

    @Test
    @DisplayName("a trough is never lifted toward the trend: that would be a forecast of recovery")
    void troughIsNotLifted() {
        double[] eps = {20, 21, 22, 23, 24, 25, 26, 8};
        var f = NormalisedEarnings.fit(series(2026, eps));
        assertThat(f.flags()).isEmpty();
        assertThat(f.normalisedEps()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("an ordinary record keeps its latest year: always using the fitted value would change E0 here, this rule does not")
    void ordinaryRecordKeepsLatest() {
        double[] eps = {10, 12, 11, 13, 12.5, 14, 13.5, 15.5};
        var f = NormalisedEarnings.fit(series(2026, eps));
        assertThat(f.flags()).isEmpty();
        assertThat(f.normalisedEps()).isEqualTo(15.5);
        assertThat(f.fittedLatestEps()).isNotCloseTo(15.5, within(0.05));
    }

    @Test
    @DisplayName("a noisy record has a large standard error and fails the 5pp gate, with the numbers still reported")
    void noisyFailsTheStdErrGate() {
        double[] eps = {10, 25, 8, 30, 12, 28, 9, 26};
        var f = NormalisedEarnings.fit(series(2026, eps));
        assertThat(f.stdErrPp()).isGreaterThan(ValuationGapRules.MAX_STD_ERR_PP);
        assertThat(f.stdErrGatePasses()).isFalse();
        assertThat(f.reliable()).isFalse();
        assertThat(f.observations()).isEqualTo(8);
    }

    @Test
    @DisplayName("standard error is in percentage points of annual log-growth (hand-computed 9.2pp on a lumpy record)")
    void stdErrUnit() {
        var tight = NormalisedEarnings.fit(series(2026, growing(8, 10, 0.1)));
        assertThat(tight.stdErrPp()).isLessThanOrEqualTo(ValuationGapRules.MAX_STD_ERR_PP);
        var f = NormalisedEarnings.fit(series(2026, 10, 25, 8, 30, 12, 28, 9, 26));
        assertThat(f.stdErrPp()).isCloseTo(9.2, within(0.3));
    }

    @Test
    @DisplayName("R-squared below 0.5 fails the second gate even when the standard error is small")
    void rSquaredGate() {
        // A flat, slightly noisy record: tiny slope uncertainty in pp terms but nothing to explain.
        double[] eps = {10.0, 10.1, 9.9, 10.05, 9.95, 10.02, 9.98, 10.0};
        var f = NormalisedEarnings.fit(series(2026, eps));
        assertThat(f.stdErrGatePasses()).isTrue();
        assertThat(f.rSquared()).isLessThan(ValuationGapRules.MIN_R_SQUARED);
        assertThat(f.rSquaredGatePasses()).isFalse();
        assertThat(f.reliable()).isFalse();
    }

    @Test
    @DisplayName("identical earnings every year has no R-squared (null), which fails the gate rather than reading as a perfect fit")
    void constantSeries() {
        var f = NormalisedEarnings.fit(series(2026, 12, 12, 12, 12, 12, 12));
        assertThat(f.rSquared()).isNull();
        assertThat(f.rSquaredGatePasses()).isFalse();
        assertThat(f.trendGrowthPct()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("the endpoint CAGR is shown beside the fit and differs from it on a lumpy record")
    void endpointCagr() {
        double[] eps = {10, 30, 10, 30, 10, 20};
        var f = NormalisedEarnings.fit(series(2026, eps));
        assertThat(f.endpointCagrPct()).isCloseTo((Math.pow(2.0, 1.0 / 5) - 1) * 100, within(1e-9));
        assertThat(f.endpointCagrPct()).isNotCloseTo(f.trendGrowthPct(), within(0.5));
    }

    @Test
    @DisplayName("a covid-style trough inside the window widens the standard error rather than hiding")
    void troughInsideWindow() {
        double[] clean = growing(10, 10, 0.12);
        double[] dipped = clean.clone();
        dipped[3] = clean[3] * 0.45;
        var a = NormalisedEarnings.fit(series(2026, clean));
        var b = NormalisedEarnings.fit(series(2026, dipped));
        assertThat(b.stdErrPp()).isGreaterThan(a.stdErrPp());
        assertThat(b.rSquared()).isLessThan(a.rSquared());
    }
}
