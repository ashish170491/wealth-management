package com.example.trading.multibagger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterisation tests pinning the verdict / grade / market-cap bands.
 *
 * <p>These are <b>boundary</b> tests on purpose. The bands are the contract between a raw
 * composite and the label the user actually reads in an email, and every one of them is an
 * off-by-one risk: {@code score >= 65} vs {@code > 65} moves a whole cohort of stocks
 * between "watchlist" and "potential multibagger".
 *
 * <p>They also serve as the safety net for the pending band recalibration. The bands were
 * designed for a distribution centred near 50; the live distribution centres near 70 (41%
 * of the universe scoring STRONG_MULTIBAGGER). When those thresholds are retuned, these
 * tests must be updated deliberately in the same commit — a failure here means the label
 * boundaries moved, which is exactly the change that needs to be conscious.
 */
class MultibaggerScoreBandsTest {

    @Nested
    @DisplayName("Verdict bands")
    class VerdictBands {

        @Test
        @DisplayName("Each band is inclusive at its lower bound")
        void lowerBoundsAreInclusive() {
            assertThat(MultibaggerScore.calculateVerdict(80)).isEqualTo("STRONG_MULTIBAGGER");
            assertThat(MultibaggerScore.calculateVerdict(65)).isEqualTo("POTENTIAL_MULTIBAGGER");
            assertThat(MultibaggerScore.calculateVerdict(50)).isEqualTo("WATCHLIST");
            assertThat(MultibaggerScore.calculateVerdict(35)).isEqualTo("MONITOR");
            assertThat(MultibaggerScore.calculateVerdict(34)).isEqualTo("AVOID");
        }

        @Test
        @DisplayName("One point below each boundary drops to the band beneath")
        void justBelowEachBoundary() {
            assertThat(MultibaggerScore.calculateVerdict(79)).isEqualTo("POTENTIAL_MULTIBAGGER");
            assertThat(MultibaggerScore.calculateVerdict(64)).isEqualTo("WATCHLIST");
            assertThat(MultibaggerScore.calculateVerdict(49)).isEqualTo("MONITOR");
        }

        @Test
        @DisplayName("Extremes are handled without falling through")
        void extremes() {
            assertThat(MultibaggerScore.calculateVerdict(100)).isEqualTo("STRONG_MULTIBAGGER");
            assertThat(MultibaggerScore.calculateVerdict(0)).isEqualTo("AVOID");
        }

        @Test
        @DisplayName("65 is the §23 recommendation-capture threshold and must be POTENTIAL_MULTIBAGGER")
        void recommendationCaptureThreshold() {
            // SPEC §23: MULTIBAGGER picks are captured into `recommendations` at composite >= 65.
            // If this band moves, the accuracy-tracking pick set changes with it.
            assertThat(MultibaggerScore.calculateVerdict(65)).isEqualTo("POTENTIAL_MULTIBAGGER");
        }
    }

    @Nested
    @DisplayName("Grade bands")
    class GradeBands {

        @Test
        @DisplayName("Each grade is inclusive at its lower bound")
        void lowerBoundsAreInclusive() {
            assertThat(MultibaggerScore.calculateGrade(85)).isEqualTo("A+");
            assertThat(MultibaggerScore.calculateGrade(75)).isEqualTo("A");
            assertThat(MultibaggerScore.calculateGrade(65)).isEqualTo("B+");
            assertThat(MultibaggerScore.calculateGrade(55)).isEqualTo("B");
            assertThat(MultibaggerScore.calculateGrade(45)).isEqualTo("C+");
            assertThat(MultibaggerScore.calculateGrade(35)).isEqualTo("C");
            assertThat(MultibaggerScore.calculateGrade(34)).isEqualTo("D");
        }

        @Test
        @DisplayName("Grades are monotonic — a higher score never yields a worse grade")
        void gradesAreMonotonic() {
            String[] order = {"D", "C", "C+", "B", "B+", "A", "A+"};
            int previousRank = -1;
            for (int score = 0; score <= 100; score++) {
                int rank = java.util.Arrays.asList(order).indexOf(MultibaggerScore.calculateGrade(score));
                assertThat(rank)
                        .as("grade rank must never decrease as score rises (score=%d)", score)
                        .isGreaterThanOrEqualTo(previousRank);
                previousRank = rank;
            }
        }
    }

    @Nested
    @DisplayName("Market-cap classification")
    class MarketCapClassification {

        private static final double SMALL_MAX = 5000;
        private static final double MID_MAX = 20000;

        @Test
        @DisplayName("Null or non-positive market cap is UNKNOWN, never a cap bucket")
        void nullIsUnknown() {
            // This mattered acutely under B-018: market cap was null for the entire universe.
            // UNKNOWN must not silently behave like SMALL_CAP, which carries a +5 bonus.
            assertThat(MultibaggerScore.classifyMarketCap(null, SMALL_MAX, MID_MAX)).isEqualTo("UNKNOWN");
            assertThat(MultibaggerScore.classifyMarketCap(0.0, SMALL_MAX, MID_MAX)).isEqualTo("UNKNOWN");
            assertThat(MultibaggerScore.classifyMarketCap(-100.0, SMALL_MAX, MID_MAX)).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("Boundaries are inclusive at the top of each bucket")
        void boundariesAreInclusive() {
            assertThat(MultibaggerScore.classifyMarketCap(SMALL_MAX, SMALL_MAX, MID_MAX)).isEqualTo("SMALL_CAP");
            assertThat(MultibaggerScore.classifyMarketCap(SMALL_MAX + 1, SMALL_MAX, MID_MAX)).isEqualTo("MID_CAP");
            assertThat(MultibaggerScore.classifyMarketCap(MID_MAX, SMALL_MAX, MID_MAX)).isEqualTo("MID_CAP");
            assertThat(MultibaggerScore.classifyMarketCap(MID_MAX + 1, SMALL_MAX, MID_MAX)).isEqualTo("LARGE_CAP");
        }

        @Test
        @DisplayName("Real computed market caps land in the expected buckets")
        void realWorldValues() {
            // Values produced by the post-B-018 computed-valuation path, verified live.
            assertThat(MultibaggerScore.classifyMarketCap(1_780_943.0, SMALL_MAX, MID_MAX))
                    .as("RELIANCE ~₹17.8 lakh cr").isEqualTo("LARGE_CAP");
            assertThat(MultibaggerScore.classifyMarketCap(102_796.0, SMALL_MAX, MID_MAX))
                    .as("MAZDOCK ~₹1.03 lakh cr").isEqualTo("LARGE_CAP");
        }
    }
}
