package com.example.trading.intelligence.recommendation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for the information-coefficient calculation and, crucially, its failure reporting.
 *
 * <p>A bare {@code null} IC conflated three different situations, and that ambiguity had a
 * real cost twice. The Institutional Interest dimension scored every stock 40 for three
 * months, and the Valuation dimension scored every stock 50 for months more (B-018) — both
 * reported {@code IC = null} alongside a perfectly healthy sample size, which is the exact
 * signature of "not enough data yet". The one mechanism that could have caught either was
 * blind to the difference.
 *
 * <p>The reason codes below are what make a broken dimension distinguishable from an
 * immature one.
 */
class RecommendationAccuracyIcTest {

    /** pearsonWithReason is private; it is the whole point of the change, so test it directly. */
    private Object pearson(List<double[]> xy) throws Exception {
        Method m = RecommendationAccuracyService.class
                .getDeclaredMethod("pearsonWithReason", List.class);
        m.setAccessible(true);
        // No collaborator is touched by this method, so a null-wired instance is safe.
        RecommendationAccuracyService svc =
                new RecommendationAccuracyService(null, null, null, null, null, null, null);
        return m.invoke(svc, xy);
    }

    private Double ic(Object result) throws Exception {
        return (Double) result.getClass().getMethod("ic").invoke(result);
    }

    private String reason(Object result) throws Exception {
        Object r = result.getClass().getMethod("reason").invoke(result);
        return r == null ? null : r.toString();
    }

    private static List<double[]> pairs(double[][] rows) {
        List<double[]> out = new ArrayList<>();
        for (double[] r : rows) out.add(r);
        return out;
    }

    @Test
    @DisplayName("A genuine correlation is computed and carries no reason code")
    void computesRealCorrelation() throws Exception {
        // Perfectly positive: score and return move together.
        Object r = pearson(pairs(new double[][]{{1, 2}, {2, 4}, {3, 6}, {4, 8}}));

        assertThat(ic(r)).isCloseTo(1.0, within(1e-9));
        assertThat(reason(r)).isNull();
    }

    @Test
    @DisplayName("A negative relationship is reported as negative, not swallowed")
    void computesNegativeCorrelation() throws Exception {
        // This matters: SECTOR_REVERSAL's real IC is negative (-0.061 @30d). A sign error
        // here would present an actively unhelpful engine as merely weak.
        Object r = pearson(pairs(new double[][]{{1, 8}, {2, 6}, {3, 4}, {4, 2}}));

        assertThat(ic(r)).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    @DisplayName("Fewer than 3 samples reports INSUFFICIENT_SAMPLES")
    void tooFewSamples() throws Exception {
        Object r = pearson(pairs(new double[][]{{1, 2}, {2, 4}}));

        assertThat(ic(r)).isNull();
        assertThat(reason(r)).isEqualTo("INSUFFICIENT_SAMPLES");
    }

    @Test
    @DisplayName("A CONSTANT dimension score reports CONSTANT_SCORE, not INSUFFICIENT_SAMPLES")
    void constantScoreIsDistinguishable() throws Exception {
        // The B-018 signature: plenty of samples, but the dimension scored every stock 50.
        // Reporting this as "insufficient samples" is what let it hide for months.
        Object r = pearson(pairs(new double[][]{{50, 2}, {50, -3}, {50, 7}, {50, 1}, {50, -4}}));

        assertThat(ic(r)).isNull();
        assertThat(reason(r))
                .as("a broken dimension must not look like an immature one")
                .isEqualTo("CONSTANT_SCORE");
    }

    @Test
    @DisplayName("Identical returns across all picks reports CONSTANT_RETURN")
    void constantReturnIsDistinguishable() throws Exception {
        // Implausible in real markets — points at the price feed, not the scoring engine.
        Object r = pearson(pairs(new double[][]{{10, 5}, {20, 5}, {30, 5}, {40, 5}}));

        assertThat(ic(r)).isNull();
        assertThat(reason(r)).isEqualTo("CONSTANT_RETURN");
    }

    @Test
    @DisplayName("Exactly 3 samples is enough — the boundary is inclusive")
    void threeSamplesIsEnough() throws Exception {
        Object r = pearson(pairs(new double[][]{{1, 1}, {2, 3}, {3, 2}}));

        assertThat(ic(r)).isNotNull();
        assertThat(reason(r)).isNull();
    }

    @Test
    @DisplayName("A weak-but-real signal is reported, not rounded away to null")
    void weakSignalStillReported() throws Exception {
        // MULTIBAGGER's measured IC is ~0.075 — below the 0.10 useful-signal convention but
        // meaningfully different from "no signal". It must survive as a number.
        Object r = pearson(pairs(new double[][]{{10, 1}, {20, -2}, {30, 3}, {40, 0}, {50, 2}}));

        assertThat(ic(r)).isNotNull();
        assertThat(Math.abs(ic(r))).isLessThan(1.0);
    }
}
