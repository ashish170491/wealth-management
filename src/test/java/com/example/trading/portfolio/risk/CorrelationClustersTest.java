package com.example.trading.portfolio.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** SPEC 7.2: holdings that move together are one bet, whatever their sectors say. */
class CorrelationClustersTest {

    private static final LocalDate D0 = LocalDate.of(2026, 6, 1);

    /** A price path driven by a shared factor plus its own noise. */
    private static NavigableMap<LocalDate, Double> path(double[] factor, long seed, double ownNoise) {
        Random r = new Random(seed);
        NavigableMap<LocalDate, Double> m = new TreeMap<>();
        double p = 100;
        m.put(D0, p);
        for (int i = 0; i < factor.length; i++) {
            p *= 1 + factor[i] + ownNoise * r.nextGaussian();
            m.put(D0.plusDays(i + 1), p);
        }
        return m;
    }

    private static double[] factor(long seed, int n) {
        Random r = new Random(seed);
        double[] f = new double[n];
        for (int i = 0; i < n; i++) f[i] = 0.01 * r.nextGaussian();
        return f;
    }

    @Test
    @DisplayName("Two holdings riding one factor form a cluster; an unrelated one does not join it")
    void linkedPairIsClustered() {
        double[] pharma = factor(1, 80);
        double[] other = factor(2, 80);
        var closes = Map.of(
                "SUNPHARMA", path(pharma, 10, 0.002),
                "CIPLA", path(pharma, 11, 0.002),
                "TCS", path(other, 12, 0.002));
        var weights = Map.of("SUNPHARMA", 15.0, "CIPLA", 10.0, "TCS", 30.0);
        var r = CorrelationClusters.compute(closes, weights, 0.7);
        assertThat(r.clusters()).hasSize(1);
        assertThat(r.clusters().get(0).members()).containsExactly("SUNPHARMA", "CIPLA");
        assertThat(r.clusters().get(0).weightPercent()).isEqualTo(25.0);
        assertThat(r.pairsMeasured()).isEqualTo(3);
    }

    @Test
    @DisplayName("A holding with a fortnight of prices is reported as unmeasured, never as uncorrelated")
    void shortHistoryIsUnmeasured() {
        double[] f = factor(3, 80);
        var closes = Map.of("A", path(f, 1, 0.002), "B", path(factor(4, 10), 2, 0.002));
        var r = CorrelationClusters.compute(closes, Map.of("A", 50.0, "B", 50.0), 0.7);
        assertThat(r.clusters()).isEmpty();
        assertThat(r.pairsUnmeasured()).isEqualTo(1);
        assertThat(r.symbolsWithoutHistory()).containsExactly("B");
    }
}
