package com.example.trading.learning;

import com.example.trading.learning.WeightVariant.Dimension;
import com.example.trading.multibagger.MultibaggerScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the arithmetic every shadow composite rests on (SPEC §38.7).
 *
 * <p>The cases below are not hypothetical. Each one is a defect this codebase has already
 * shipped at least once: an unmeasured dimension treated as a neutral 50 (B-019, which made the
 * engine rank stocks it could not analyse above stocks it had measured and found weak), a null
 * rendered as a zero (Gotcha 21), and a weighted average that silently changes meaning when its
 * weights do not sum to one.
 */
class WeightVariantTest {

    private static WeightVariant variant(String name, double tech, double vol, double rs,
                                         double structure, double val, double inst, double qual) {
        Map<Dimension, Double> w = new LinkedHashMap<>();
        w.put(Dimension.TECHNICAL_MOMENTUM, tech);
        w.put(Dimension.VOLUME_ACCUMULATION, vol);
        w.put(Dimension.RELATIVE_STRENGTH, rs);
        w.put(Dimension.PRICE_STRUCTURE, structure);
        w.put(Dimension.VALUATION, val);
        w.put(Dimension.INSTITUTIONAL_INTEREST, inst);
        w.put(Dimension.FINANCIAL_QUALITY, qual);
        return new WeightVariant(name, "test", w);
    }

    private static Map<Dimension, Integer> scores(Integer tech, Integer vol, Integer rs,
                                                  Integer structure, Integer val, Integer inst,
                                                  Integer qual) {
        Map<Dimension, Integer> m = new LinkedHashMap<>();
        m.put(Dimension.TECHNICAL_MOMENTUM, tech);
        m.put(Dimension.VOLUME_ACCUMULATION, vol);
        m.put(Dimension.RELATIVE_STRENGTH, rs);
        m.put(Dimension.PRICE_STRUCTURE, structure);
        m.put(Dimension.VALUATION, val);
        m.put(Dimension.INSTITUTIONAL_INTEREST, inst);
        m.put(Dimension.FINANCIAL_QUALITY, qual);
        return m;
    }

    @Test
    @DisplayName("An equal-weight variant is the plain mean of the measured dimensions")
    void equalWeightIsTheMean() {
        WeightVariant equal = variant("equal", 1, 1, 1, 1, 1, 1, 1);
        assertThat(equal.weightedBase(scores(70, 60, 50, 40, 30, 20, 10))).isEqualTo(40);
    }

    @Test
    @DisplayName("An unmeasured dimension is dropped and the remaining weight renormalised, "
            + "never replaced with a neutral score")
    void nullDimensionRenormalisesRatherThanDefaulting() {
        WeightVariant equal = variant("equal", 1, 1, 1, 1, 1, 1, 1);

        // Four measured at 80, three unmeasured. The answer is 80 — the score of what was
        // actually measured — not 80 pulled toward 50 by three imagined neutral readings.
        Integer base = equal.weightedBase(scores(80, 80, 80, 80, null, null, null));

        assertThat(base).isEqualTo(80);
        assertThat(base).as("substituting 50 for the three nulls would give 67").isNotEqualTo(67);
    }

    @Test
    @DisplayName("Weights need not sum to 1.0 — renormalisation makes the scale irrelevant")
    void weightScaleDoesNotChangeTheAnswer() {
        Map<Dimension, Integer> s = scores(90, 10, 50, 50, 50, 50, 50);
        WeightVariant unit = variant("unit", 0.4, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1);
        WeightVariant scaled = variant("scaled", 40, 10, 10, 10, 10, 10, 10);

        assertThat(scaled.weightedBase(s)).isEqualTo(unit.weightedBase(s));
    }

    @Test
    @DisplayName("A zero weight is a choice and a null score is a gap — only the gap renormalises")
    void zeroWeightAndNullScoreAreDifferentThings() {
        // price-only: the three fundamental dimensions carry no weight.
        WeightVariant priceOnly = variant("price-only", 1, 1, 1, 1, 0, 0, 0);

        // Whether the fundamentals are measured or not cannot matter to this variant.
        Integer withFundamentals = priceOnly.weightedBase(scores(60, 60, 60, 60, 99, 99, 99));
        Integer withoutFundamentals = priceOnly.weightedBase(scores(60, 60, 60, 60, null, null, null));

        assertThat(withFundamentals).isEqualTo(60);
        assertThat(withoutFundamentals).isEqualTo(60);
    }

    @Test
    @DisplayName("A variant that can score nothing returns null, not zero")
    void unscoreableStockIsAbsentNotBottom() {
        WeightVariant fundamentalsOnly = variant("fundamentals-only", 0, 0, 0, 0, 1, 1, 1);

        // A stock with price data but no fundamental coverage. This variant does not rank it.
        // Returning 0 would place it below every measured stock, which is a claim the data
        // does not support (Gotcha 21).
        assertThat(fundamentalsOnly.weightedBase(scores(80, 80, 80, 80, null, null, null))).isNull();
    }

    @Test
    @DisplayName("compose clamps to 0-100 and leaves an unscoreable stock unscored")
    void composeClampsAndPropagatesNull() {
        assertThat(WeightVariant.compose(90, 30)).isEqualTo(100);
        assertThat(WeightVariant.compose(10, -40)).isEqualTo(0);
        assertThat(WeightVariant.compose(60, -6)).isEqualTo(54);
        assertThat(WeightVariant.compose(null, 10)).isNull();
    }

    @Test
    @DisplayName("dimensionsMeasured counts only dimensions this variant both uses and has")
    void dimensionsMeasuredIgnoresUnusedAndUnmeasured() {
        WeightVariant priceOnly = variant("price-only", 1, 1, 1, 1, 0, 0, 0);
        assertThat(priceOnly.dimensionsMeasured(scores(1, 1, null, 1, 50, 50, 50))).isEqualTo(3);
    }

    @Test
    @DisplayName("The score-object adapter carries nulls through instead of boxing them to zero")
    void scoreAdapterPreservesUnmeasuredDimensions() {
        MultibaggerScore score = MultibaggerScore.builder()
                .symbol("NSE:TEST")
                .technicalMomentumScore(70)
                .volumeAccumulationScore(60)
                .relativeStrengthScore(50)
                .priceStructureScore(40)
                .valuationScore(null)
                .institutionalInterestScore(null)
                .financialQualityScore(30)
                .build();

        Map<Dimension, Integer> m = WeightVariant.subScores(score);

        assertThat(m.get(Dimension.TECHNICAL_MOMENTUM)).isEqualTo(70);
        assertThat(m.get(Dimension.VALUATION)).isNull();
        assertThat(m.get(Dimension.INSTITUTIONAL_INTEREST)).isNull();
        assertThat(m.get(Dimension.FINANCIAL_QUALITY)).isEqualTo(30);
    }

    @Test
    @DisplayName("A variant must be named — the name is its persisted identity")
    void unnamedVariantIsRejected() {
        Map<Dimension, Double> w = Map.of(Dimension.VALUATION, 1.0);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new WeightVariant("  ", "test", w));
    }
}
