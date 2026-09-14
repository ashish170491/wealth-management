package com.example.trading.learning;

import com.example.trading.learning.validation.Statistics;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the statistics the promotion gate reasons with (SPEC §38.7).
 *
 * <p>The recurring theme is that an absent answer must stay absent. A correlation of 0.0 and
 * "there was not enough data to compute a correlation" are indistinguishable once they reach a
 * table, and this system has twice spent months reading the second as the first — Institutional
 * Interest scoring a constant 40 for three months, and monthly RSI returning a constant 50.0 for
 * the whole universe.
 */
class StatisticsTest {

    private static List<double[]> pairs(double[] x, double[] y) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < x.length; i++) out.add(new double[]{x[i], y[i]});
        return out;
    }

    @Test
    @DisplayName("Perfectly ordered data gives a rank correlation of 1 even when curved")
    void spearmanMeasuresOrderNotLinearity() {
        // A composite is an ordering device: what matters is that a higher score goes with a
        // higher return, not that the relationship is a straight line.
        List<double[]> xy = pairs(new double[]{1, 2, 3, 4, 5},
                new double[]{1, 4, 9, 16, 25});

        assertThat(Statistics.spearman(xy).value()).isCloseTo(1.0, Offset.offset(1e-9));
        assertThat(Statistics.pearson(xy).value()).isLessThan(1.0);
    }

    @Test
    @DisplayName("One extreme return cannot carry a rank correlation the way it carries Pearson")
    void rankCorrelationBoundsOutlierInfluence() {
        // Scores ascending, returns descending except for one stock that trebled. Pearson is
        // dragged positive by that single observation; the rank measure is not.
        List<double[]> xy = pairs(new double[]{10, 20, 30, 40, 50},
                new double[]{5, 4, 3, 2, 300});

        assertThat(Statistics.pearson(xy).value()).isGreaterThan(0.5);
        assertThat(Statistics.spearman(xy).value()).isLessThan(0.5);
    }

    @Test
    @DisplayName("A constant score reports why it has no correlation, not a zero")
    void constantScoreIsNamedNotZeroed() {
        List<double[]> xy = pairs(new double[]{50, 50, 50, 50},
                new double[]{1, 2, 3, 4});

        Statistics.Correlation c = Statistics.spearman(xy);

        assertThat(c.value()).isNull();
        // This is the exact signature of the two bugs above: a dimension pinned at one value is
        // broken, not immature, and the two must not read alike.
        assertThat(c.reason()).isEqualTo(Statistics.Unavailable.CONSTANT_SCORE);
    }

    @Test
    @DisplayName("Too few observations is reported as immaturity, distinct from breakage")
    void tooFewSamplesIsItsOwnReason() {
        Statistics.Correlation c = Statistics.pearson(pairs(new double[]{1, 2}, new double[]{3, 4}));

        assertThat(c.value()).isNull();
        assertThat(c.reason()).isEqualTo(Statistics.Unavailable.INSUFFICIENT_SAMPLES);
    }

    @Test
    @DisplayName("Tied ranks share their average position")
    void tiesAverage() {
        double[] r = Statistics.ranks(new double[]{10, 20, 20, 30});
        assertThat(r[0]).isEqualTo(1.0);
        assertThat(r[1]).isEqualTo(2.5);
        assertThat(r[2]).isEqualTo(2.5);
        assertThat(r[3]).isEqualTo(4.0);
    }

    @Test
    @DisplayName("Standard deviation uses the sample denominator, which matters at n=5")
    void sampleStandardDeviationNotPopulation() {
        List<Double> xs = List.of(2.0, 4.0, 4.0, 4.0, 6.0);
        // Population sd here is 1.265; the sample formula gives 1.414. At the sample sizes this
        // class sees, using the population form would understate the spread and therefore
        // overstate every t-statistic built on it.
        assertThat(Statistics.stdDev(xs)).isCloseTo(1.414, Offset.offset(0.001));
    }

    @Test
    @DisplayName("A zero spread yields no t-statistic rather than an infinite one")
    void zeroSpreadHasNoTStatistic() {
        assertThat(Statistics.tStatistic(List.of(0.05, 0.05, 0.05))).isNull();
        assertThat(Statistics.tStatistic(List.of(0.05))).isNull();
    }

    @Test
    @DisplayName("A real mean over a real spread produces the textbook t-statistic")
    void tStatisticIsTheTextbookFormula() {
        // mean 4, sd 1.414, n 5 -> 4 / (1.414/sqrt(5)) = 6.32
        assertThat(Statistics.tStatistic(List.of(2.0, 4.0, 4.0, 4.0, 6.0)))
                .isCloseTo(6.325, Offset.offset(0.01));
    }

    @Test
    @DisplayName("Critical values tighten as the sample shrinks and as alpha tightens")
    void criticalValuesBehaveCorrectly() {
        assertThat(Statistics.criticalT(4, 0.05)).isCloseTo(2.776, Offset.offset(0.001));
        assertThat(Statistics.criticalT(15, 0.05)).isCloseTo(2.131, Offset.offset(0.001));

        // Smaller sample demands a larger t for the same confidence.
        assertThat(Statistics.criticalT(4, 0.05)).isGreaterThan(Statistics.criticalT(30, 0.05));
        // Tighter alpha demands a larger t at the same sample size.
        assertThat(Statistics.criticalT(15, 0.01)).isGreaterThan(Statistics.criticalT(15, 0.05));
        assertThat(Statistics.criticalT(0, 0.05)).isNull();
    }
}
