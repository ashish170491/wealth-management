package com.example.trading.multibagger;

import com.example.trading.holdings.StockValuationService.ValuationData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterisation tests for the eight multibagger scoring dimensions.
 *
 * <p><b>These pin current behaviour, including behaviour that is arguably wrong.</b> That is
 * the point: SPEC §25.6 records that "any scoring-engine change is currently untested;
 * regression risk is the main blocker on weight-tuning". Before changing how a dimension
 * scores, we need a test that fails loudly and shows exactly which inputs moved.
 *
 * <p>Where a test documents a known defect it says so explicitly and names the finding, so
 * that a future change which "breaks" the test is understood as the intended fix rather
 * than a regression.
 *
 * <p>Only {@code config} is needed to exercise the pure scorers, so the remaining
 * collaborators are left null rather than mocked — these methods are deliberately
 * side-effect free and depend on nothing else.
 */
class MultibaggerDimensionScoringTest {

    private MultibaggerScreenerService screener;
    private MultibaggerConfig config;
    private List<String> bullish;
    private List<String> bearish;

    @BeforeEach
    void setUp() {
        config = new MultibaggerConfig();
        screener = new MultibaggerScreenerService(
                null, null, null, null, null, null, null, null, null, null, config, null, null, null, null, null, null, null, null, null, null, null);
        bullish = new ArrayList<>();
        bearish = new ArrayList<>();
    }

    // ==================== fixtures ====================

    /** One daily candle. Kite returns mixed Integer/Double, so values stay as Object. */
    private static Map<String, Object> candle(double close, double volume) {
        Map<String, Object> c = new HashMap<>();
        c.put("open", close);
        c.put("high", close * 1.01);
        c.put("low", close * 0.99);
        c.put("close", close);
        c.put("volume", volume);
        return c;
    }

    /** A steadily rising series — the "routine uptrend" case, oldest first. */
    private static List<Map<String, Object>> risingSeries(int days, double start, double dailyGrowthPct) {
        List<Map<String, Object>> out = new ArrayList<>();
        double price = start;
        for (int i = 0; i < days; i++) {
            out.add(candle(price, 100_000));
            price *= (1 + dailyGrowthPct / 100.0);
        }
        return out;
    }

    /** A steadily falling series. */
    private static List<Map<String, Object>> fallingSeries(int days, double start, double dailyDeclinePct) {
        return risingSeries(days, start, -dailyDeclinePct);
    }

    /** An uptrend at a caller-chosen daily rate, with the same every-third-week pullback. */
    private static List<Map<String, Object>> uptrendAt(int days, double start, double dailyPct) {
        List<Map<String, Object>> out = new ArrayList<>();
        double price = start;
        for (int i = 0; i < days; i++) {
            int week = i / 5;
            double step = (week % 3 == 2) ? -(dailyPct * 1.15) : dailyPct;
            price *= (1 + step / 100.0);
            out.add(candle(price, 100_000));
        }
        return out;
    }

    /** Invoke the private continuous RSI scorer directly. */
    private int invokeRsiScore(double rsi) {
        try {
            var m = MultibaggerScreenerService.class.getDeclaredMethod(
                    "scoreRsiPosition", double.class, List.class, List.class);
            m.setAccessible(true);
            return (int) m.invoke(screener, rsi, new ArrayList<String>(), new ArrayList<String>());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A realistic uptrend: rising overall, but with a pullback week in every third week so
     * RSI lands in the 50-75 "sweet spot" rather than pinning at 100. A perfectly smooth
     * rise has no down periods at all, which pushes RSI to 100 and paradoxically scores
     * <i>lower</i> (the overbought branch gives +10 instead of +30). Deterministic — no RNG,
     * so the pinned expectations stay stable.
     */
    private static List<Map<String, Object>> realisticUptrend(int days, double start) {
        List<Map<String, Object>> out = new ArrayList<>();
        double price = start;
        for (int i = 0; i < days; i++) {
            int week = i / 5;
            double dailyPct = (week % 3 == 2) ? -0.35 : 0.30; // pull back every third week
            price *= (1 + dailyPct / 100.0);
            out.add(candle(price, 100_000));
        }
        return out;
    }

    // ==================== Dimension 1: Technical Momentum ====================

    @Nested
    @DisplayName("Technical Momentum (weight 0.18 — the heaviest dimension)")
    class TechnicalMomentum {

        @Test
        @DisplayName("Insufficient weekly history returns the neutral default of 30")
        void insufficientHistoryReturns30() {
            // Fewer than 26 weekly bars. Note this path is unreachable in production: the
            // screener requires >=50 daily candles and fetches 365 days.
            List<Map<String, Object>> shortHistory = risingSeries(30, 100, 0.1);

            int score = screener.scoreTechnicalMomentum(shortHistory, 110, bullish, bearish);

            assertThat(score).isEqualTo(30);
        }

        @Test
        @DisplayName("A routine uptrend scores well but does NOT hit the ceiling")
        void routineUptrendDoesNotSaturate() {
            // FIXED. The five components used to sum to exactly 25+15+15+15+30 = 100, so
            // any stock above both weekly EMAs with a positive crossover, >1% slope and RSI
            // anywhere in 50-75 scored a perfect 100 — 122 of 291 stocks (42%) on the
            // 2026-08-20 run, with 18 tied at composite 100. Slope and RSI are now
            // continuous, so an ordinary uptrend leaves headroom for a stronger one.
            List<Map<String, Object>> history = realisticUptrend(400, 100);
            double currentPrice = closeOf(history, history.size() - 1);

            int score = screener.scoreTechnicalMomentum(history, currentPrice, bullish, bearish);

            assertThat(score)
                    .as("strong but not maximal — headroom must remain above a routine trend")
                    .isBetween(70, 90);
        }

        @Test
        @DisplayName("Different uptrends produce different scores — no mass tie at the ceiling")
        void uptrendsAreSeparated() {
            // The point of the saturation fix: healthy-but-different uptrends must be
            // separable. All of these would have scored an identical 100 before, because
            // slope >1% and RSI anywhere in 50-75 both collapsed to flat maxima.
            //
            // Note the ordering is deliberately NOT asserted: a steeper trend can score
            // lower once it pushes RSI past the overbought line, which is correct for a
            // long-term screen — a parabolic move is a worse entry, not a better one.
            List<Integer> scores = new ArrayList<>();
            for (double dailyPct : List.of(0.15, 0.30, 0.55, 0.85)) {
                List<Map<String, Object>> h = uptrendAt(400, 100, dailyPct);
                scores.add(screener.scoreTechnicalMomentum(
                        h, closeOf(h, h.size() - 1), new ArrayList<>(), new ArrayList<>()));
            }

            assertThat(scores).doesNotContain(100);
            assertThat(new java.util.HashSet<>(scores))
                    .as("scores %s must not collapse to a single value", scores)
                    .hasSizeGreaterThan(1);
        }

        @Test
        @DisplayName("RSI positioning is continuous, not a four-step cliff")
        void rsiScoringIsContinuous() {
            // Sweeping RSI across the sweet spot used to yield a flat +30 everywhere in
            // 50-75. It should now vary smoothly, peaking mid-band.
            java.util.Set<Integer> distinct = new java.util.HashSet<>();
            for (double rsi = 45; rsi <= 85; rsi += 2.5) {
                distinct.add(invokeRsiScore(rsi));
            }
            assertThat(distinct)
                    .as("RSI score must take many values across the range, not 4 buckets")
                    .hasSizeGreaterThan(8);
        }

        @Test
        @DisplayName("RSI score peaks inside the sweet spot and decays when overbought")
        void rsiPeaksMidBandAndDecays() {
            int midBand = invokeRsiScore(62.5);   // middle of 50-75
            int edge = invokeRsiScore(74.0);      // still in band, near the top
            int overbought = invokeRsiScore(85.0);

            assertThat(midBand).isGreaterThan(edge);
            assertThat(edge).isGreaterThan(overbought);
        }

        @Test
        @DisplayName("A downtrend scores far below the ceiling")
        void downtrendScoresLow() {
            List<Map<String, Object>> history = fallingSeries(400, 500, 0.2);
            double currentPrice = closeOf(history, history.size() - 1);

            int score = screener.scoreTechnicalMomentum(history, currentPrice, bullish, bearish);

            assertThat(score).isLessThan(40);
            assertThat(bearish).isNotEmpty();
        }

        @Test
        @DisplayName("Score is always clamped to 0..100")
        void scoreIsBounded() {
            List<Map<String, Object>> history = risingSeries(400, 100, 0.5);
            int score = screener.scoreTechnicalMomentum(history, closeOf(history, history.size() - 1), bullish, bearish);
            assertThat(score).isBetween(0, 100);
        }
    }

    // ==================== Dimension 5: Valuation ====================

    @Nested
    @DisplayName("Valuation (weight 0.13)")
    class Valuation {

        @Test
        @DisplayName("Null valuation is unmeasured (null), not a neutral 50")
        void nullValuationIsUnmeasured() {
            // FIXED: previously returned 50. A neutral score claims knowledge we don't have
            // and still consumes 13% of the composite weight; null drops the dimension and
            // renormalises the rest.
            assertThat(screener.scoreValuation(null, 100, bullish, bearish)).isNull();
        }

        @Test
        @DisplayName("A non-null object with no measurable fields is also unmeasured")
        void allNullFieldsAreUnmeasured() {
            // This is precisely how B-018 hid. StockValuationService's fallback returns a
            // NON-null ValuationData whose stockPe / peDeviation / marketCap are all null,
            // so the early `valuation == null` return was skipped, every sub-check was
            // skipped, and the method yielded a constant 50 — 13% of the composite weight
            // contributing exactly zero cross-sectional variance while adding 6.5 points to
            // every stock, and reporting IC=null indistinguishably from "no data yet".
            ValuationData empty = new ValuationData();
            empty.setSymbol("TESTCO");

            assertThat(screener.scoreValuation(empty, 100, bullish, bearish))
                    .as("an object carrying no measurable field must not score as average")
                    .isNull();
            assertThat(bullish).isEmpty();
            assertThat(bearish).isEmpty();
        }

        @Test
        @DisplayName("A partially-populated object IS measured — only total absence is null")
        void partialDataStillScores() {
            // Guards against over-correcting: a stock with a market cap but no PE is still
            // partially measurable and must not be discarded.
            ValuationData partial = new ValuationData();
            partial.setMarketCap(3000.0);

            assertThat(screener.scoreValuation(partial, 100, bullish, bearish)).isNotNull();
        }

        @Test
        @DisplayName("A cheap stock scores above neutral and a rich one below")
        void deviationMovesScoreInTheRightDirection() {
            ValuationData cheap = new ValuationData();
            cheap.setStockPe(10.0);
            cheap.setIndustryPe(25.0);
            cheap.setPeDeviation(-60.0);

            ValuationData rich = new ValuationData();
            rich.setStockPe(80.0);
            rich.setIndustryPe(25.0);
            rich.setPeDeviation(220.0);

            int cheapScore = screener.scoreValuation(cheap, 100, new ArrayList<>(), new ArrayList<>());
            int richScore = screener.scoreValuation(rich, 100, new ArrayList<>(), new ArrayList<>());

            assertThat(cheapScore).isGreaterThan(50);
            assertThat(richScore).isLessThan(50);
            assertThat(cheapScore).isGreaterThan(richScore);
        }

        @Test
        @DisplayName("Score stays within 0..100 for extreme deviations")
        void extremeDeviationsStayBounded() {
            ValuationData absurd = new ValuationData();
            absurd.setStockPe(5000.0);
            absurd.setIndustryPe(10.0);
            absurd.setPeDeviation(49_900.0);

            int score = screener.scoreValuation(absurd, 100, bullish, bearish);

            assertThat(score).isBetween(0, 100);
        }
    }

    // ==================== Cross-cutting: the missing-data asymmetry ====================

    @Nested
    @DisplayName("Cross-cutting missing-data semantics")
    class MissingDataSemantics {

        @Test
        @DisplayName("Absent data no longer outranks measured weakness")
        void absentDataNoLongerBeatsMeasuredWeakness() {
            // FIXED. Previously a stock we knew nothing about scored 50 on Valuation while a
            // stock we successfully measured and found expensive scored well below that —
            // so the engine rewarded data absence over measured weakness, and under B-018
            // that reward applied to the entire universe at once.
            //
            // Now the unknown stock yields null (dropped from the composite and the weight
            // renormalised) while the measured-expensive stock keeps its low score.
            ValuationData unknown = new ValuationData();

            ValuationData measuredBad = new ValuationData();
            measuredBad.setStockPe(90.0);
            measuredBad.setIndustryPe(20.0);
            measuredBad.setPeDeviation(350.0);

            Integer unknownScore = screener.scoreValuation(unknown, 100, new ArrayList<>(), new ArrayList<>());
            Integer measuredBadScore = screener.scoreValuation(measuredBad, 100, new ArrayList<>(), new ArrayList<>());

            assertThat(unknownScore).as("unknown must be excluded, not scored").isNull();
            assertThat(measuredBadScore).as("measured-expensive keeps a real, low score").isLessThan(50);
        }
    }

    @Nested
    @DisplayName("Weighted composite with renormalisation")
    class WeightedComposite {

        /** Reflection: weightedComposite is private and varargs, but its arithmetic is the
         *  core of the missing-data fix and deserves direct coverage. */
        private int composite(Object... pairs) throws Exception {
            var m = MultibaggerScreenerService.class
                    .getDeclaredMethod("weightedComposite", Object[].class);
            m.setAccessible(true);
            return (int) m.invoke(screener, (Object) pairs);
        }

        @Test
        @DisplayName("All dimensions present is a plain weighted mean")
        void allPresent() throws Exception {
            assertThat(composite(80, 0.5, 60, 0.5)).isEqualTo(70);
        }

        @Test
        @DisplayName("A null dimension drops from BOTH numerator and denominator")
        void nullRenormalises() throws Exception {
            // 80 at weight 0.5 with the other half unmeasured must be 80, not 40. Treating
            // the missing half as zero would rank a well-measured stock as mediocre; giving
            // it a neutral 50 would invent information.
            assertThat(composite(80, 0.5, null, 0.5)).isEqualTo(80);
        }

        @Test
        @DisplayName("Renormalisation is correct for uneven weights")
        void unevenWeights() throws Exception {
            // Measured: 90@0.18 and 30@0.12 => (16.2 + 3.6) / 0.30 = 66
            assertThat(composite(90, 0.18, 30, 0.12, null, 0.13, null, 0.15)).isEqualTo(66);
        }

        @Test
        @DisplayName("A fully unmeasurable stock scores 0, so it ranks last rather than mid")
        void nothingMeasurableScoresZero() throws Exception {
            assertThat(composite(null, 0.5, null, 0.5)).isZero();
        }

        @Test
        @DisplayName("Renormalisation never pushes a score outside 0..100")
        void staysInRange() throws Exception {
            assertThat(composite(100, 0.13, null, 0.87)).isEqualTo(100);
            assertThat(composite(0, 0.13, null, 0.87)).isZero();
        }
    }

    // ==================== helpers ====================

    private static double closeOf(List<Map<String, Object>> history, int idx) {
        return ((Number) history.get(idx).get("close")).doubleValue();
    }
}
