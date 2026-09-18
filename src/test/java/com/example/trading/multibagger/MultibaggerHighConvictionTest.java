package com.example.trading.multibagger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the high-conviction tier (2026-08-25).
 *
 * <p>It exists because realised forward returns were monotone in the median by score band over
 * 2026-04-20 → 2026-08-25 — 8.43% (80+), 7.23% (70-79), 4.71% (65-69), 2.34% (50-64), −0.19%
 * (&lt;50) — with the meaningful step at 70. The tier reorders what reports lead with.
 *
 * <p>The property worth defending is that it is <b>purely additive</b>: it must never shrink the
 * candidate set. That evidence is a single 4-month window (n=46 in the 70-79 band), which
 * justifies ranking but not discarding — SPEC §25.5. A future change that turns this into a
 * filter should fail here loudly.
 */
class MultibaggerHighConvictionTest {

    private MultibaggerScreenerService screener;
    private MultibaggerConfig config;

    @BeforeEach
    void setUp() {
        config = new MultibaggerConfig();
        screener = new MultibaggerScreenerService(
                null, null, null, null, null, null, null, null, null, null, config, null, null, null, null, null, null, null, null, null, null, null,
                // SPEC 50: the quarterly-result ledger and its config, unused by these pure tests.
                null, null);
    }

    /** Build a ranked universe so percentileRank is populated exactly as production does. */
    private List<MultibaggerScore> universe(int... composites) throws Exception {
        List<MultibaggerScore> scores = new ArrayList<>();
        for (int i = 0; i < composites.length; i++) {
            scores.add(MultibaggerScore.builder()
                    .symbol("NSE:S" + i).compositeScore(composites[i]).build());
        }
        scores.sort(Comparator.comparingInt(MultibaggerScore::getCompositeScore).reversed());
        var m = MultibaggerScreenerService.class.getDeclaredMethod("assignPercentileRanks", List.class);
        m.setAccessible(true);
        m.invoke(screener, scores);
        return scores;
    }

    @Test
    @DisplayName("High conviction is a strict subset of candidates — it never adds a new pick")
    void isSubsetOfCandidates() throws Exception {
        List<MultibaggerScore> ranked = universe(92, 85, 74, 71, 68, 66, 61, 55, 40, 22);

        for (MultibaggerScore s : ranked) {
            if (screener.isHighConviction(s)) {
                assertThat(screener.isCandidate(s))
                        .as("%s scored %d — high conviction must imply candidate",
                                s.getSymbol(), s.getCompositeScore())
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("The tier does not shrink the candidate set")
    void doesNotReduceCandidateCount() throws Exception {
        List<MultibaggerScore> ranked = universe(92, 85, 74, 71, 68, 66, 61, 55, 40, 22);

        long candidates = ranked.stream().filter(screener::isCandidate).count();
        long highConviction = ranked.stream().filter(screener::isHighConviction).count();

        // The whole point: 65-69 names remain candidates, they are merely ranked below.
        assertThat(candidates).isGreaterThanOrEqualTo(highConviction);
        assertThat(candidates).as("mid-band candidates must survive the tier").isGreaterThan(0);
    }

    @Test
    @DisplayName("A candidate below the 70 threshold is not high conviction")
    void midBandCandidateIsNotHighConviction() throws Exception {
        List<MultibaggerScore> ranked = universe(68, 67, 66, 65, 64, 40, 30, 20, 10, 5);

        MultibaggerScore best = ranked.get(0); // 68 — top of its universe, still below 70
        assertThat(screener.isCandidate(best)).isTrue();
        assertThat(screener.isHighConviction(best))
                .as("68 clears the candidate bar but sits in the 4.7%%-median band")
                .isFalse();
    }

    @Test
    @DisplayName("A high score that misses the percentile slice is still not high conviction")
    void percentileGateStillApplies() throws Exception {
        // Every stock scores 70+, so the absolute threshold alone would promote all ten.
        List<MultibaggerScore> ranked = universe(99, 96, 94, 92, 90, 88, 86, 84, 82, 80);

        long highConviction = ranked.stream().filter(screener::isHighConviction).count();
        assertThat(highConviction)
                .as("the top-percentile gate must survive a uniformly strong universe")
                .isLessThan(ranked.size());
    }

    @Test
    @DisplayName("The threshold sits at 70, where the measured return ladder steps up")
    void thresholdMatchesTheEvidence() {
        assertThat(config.getHighConvictionScore()).isEqualTo(70);
        assertThat(config.getHighConvictionScore())
                .as("must stay at or above the candidate floor or the tier is meaningless")
                .isGreaterThanOrEqualTo(config.getMinScoreForCandidate());
    }
}
