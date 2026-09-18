package com.example.trading.multibagger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for cross-sectional percentile ranking and the candidate gate.
 *
 * <p>Percentile ranking is the structural answer to every distortion the 2026-08 audit
 * found, because all of them were <b>uniform</b> shifts: weights summing to 1.15 scaled
 * every score by 15% (B-019), a constant Valuation dimension added a fixed offset to every
 * stock (B-018), and the bonus stack added a median +9.8 to 85% of the universe. Each
 * sailed through an absolute {@code >= 60} gate — and none of them move a rank.
 *
 * <p>The scale-invariance test below is the important one: it is the property that makes
 * the gate robust to the <i>next</i> bug of this shape, not just the three already found.
 */
class MultibaggerPercentileRankTest {

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

    private static MultibaggerScore score(String symbol, int composite) {
        return MultibaggerScore.builder().symbol(symbol).compositeScore(composite).build();
    }

    /** Mirror of the production call: sort best-first, then rank. */
    private List<MultibaggerScore> rank(List<MultibaggerScore> scores) throws Exception {
        scores.sort(Comparator.comparingInt(MultibaggerScore::getCompositeScore).reversed());
        var m = MultibaggerScreenerService.class.getDeclaredMethod("assignPercentileRanks", List.class);
        m.setAccessible(true);
        m.invoke(screener, scores);
        return scores;
    }

    @Test
    @DisplayName("Best scores 100, worst scores 0")
    void endpointsAreAnchored() throws Exception {
        List<MultibaggerScore> ranked = rank(new ArrayList<>(List.of(
                score("A", 90), score("B", 70), score("C", 50), score("D", 30), score("E", 10))));

        assertThat(ranked.get(0).getPercentileRank()).isEqualTo(100.0);
        assertThat(ranked.get(ranked.size() - 1).getPercentileRank()).isZero();
    }

    @Test
    @DisplayName("Ranks decrease monotonically with score")
    void ranksAreMonotonic() throws Exception {
        List<MultibaggerScore> ranked = rank(new ArrayList<>(List.of(
                score("A", 95), score("B", 80), score("C", 65), score("D", 40))));

        for (int i = 1; i < ranked.size(); i++) {
            assertThat(ranked.get(i).getPercentileRank())
                    .isLessThanOrEqualTo(ranked.get(i - 1).getPercentileRank());
        }
    }

    @Test
    @DisplayName("Tied scores share a rank instead of being ordered arbitrarily")
    void tiesShareRank() throws Exception {
        // The live run had 18 stocks tied at composite 100, ordered by whatever the sort
        // happened to do. They must all read as equally ranked.
        List<MultibaggerScore> ranked = rank(new ArrayList<>(List.of(
                score("A", 100), score("B", 100), score("C", 100), score("D", 50), score("E", 20))));

        assertThat(ranked.get(0).getPercentileRank()).isEqualTo(100.0);
        assertThat(ranked.get(1).getPercentileRank()).isEqualTo(100.0);
        assertThat(ranked.get(2).getPercentileRank()).isEqualTo(100.0);
        assertThat(ranked.get(3).getPercentileRank()).isLessThan(100.0);
    }

    @Test
    @DisplayName("SCALE INVARIANCE: a uniform 15% inflation does not change any rank")
    void uniformInflationDoesNotChangeRanks() throws Exception {
        List<Integer> raw = List.of(80, 70, 60, 50, 40, 30);

        List<MultibaggerScore> normal = new ArrayList<>();
        List<MultibaggerScore> inflated = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            normal.add(score("S" + i, raw.get(i)));
            // Exactly the B-019 distortion: weights summing to 1.15.
            inflated.add(score("S" + i, (int) Math.round(raw.get(i) * 1.15)));
        }

        rank(normal);
        rank(inflated);

        for (int i = 0; i < raw.size(); i++) {
            assertThat(inflated.get(i).getPercentileRank())
                    .as("rank must be identical under uniform scaling (index %d)", i)
                    .isEqualTo(normal.get(i).getPercentileRank());
        }
    }

    @Test
    @DisplayName("SHIFT INVARIANCE: a constant additive bonus does not change any rank")
    void uniformBonusDoesNotChangeRanks() throws Exception {
        // The bonus stack added a median +9.8 to 85% of stocks; a constant Valuation
        // dimension added a fixed offset to 100% of them.
        List<Integer> raw = List.of(75, 65, 55, 45, 35);

        List<MultibaggerScore> normal = new ArrayList<>();
        List<MultibaggerScore> shifted = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            normal.add(score("S" + i, raw.get(i)));
            shifted.add(score("S" + i, raw.get(i) + 10));
        }

        rank(normal);
        rank(shifted);

        for (int i = 0; i < raw.size(); i++) {
            assertThat(shifted.get(i).getPercentileRank()).isEqualTo(normal.get(i).getPercentileRank());
        }
    }

    @Test
    @DisplayName("Candidate gate requires BOTH the top slice and the absolute floor")
    void candidateGateRequiresBoth() {
        config.setCandidateTopPercentile(20.0);
        config.setMinScoreForCandidate(60);

        MultibaggerScore topButWeak = score("A", 55);
        topButWeak.setPercentileRank(95.0);       // top slice, below the floor

        MultibaggerScore strongButMidPack = score("B", 75);
        strongButMidPack.setPercentileRank(50.0); // above the floor, not top slice

        MultibaggerScore both = score("C", 75);
        both.setPercentileRank(95.0);

        assertThat(screener.isCandidate(topButWeak)).as("best of a bad market is not a buy").isFalse();
        assertThat(screener.isCandidate(strongButMidPack)).as("mid-pack is not a shortlist").isFalse();
        assertThat(screener.isCandidate(both)).isTrue();
    }

    @Test
    @DisplayName("A null percentile still passes the floor — ad-hoc single-stock screens work")
    void nullPercentileFallsBackToAbsoluteFloor() {
        MultibaggerScore adHoc = score("A", 75);
        adHoc.setPercentileRank(null);

        assertThat(screener.isCandidate(adHoc)).isTrue();
    }

    @Test
    @DisplayName("The gate keeps roughly the configured slice of a realistic universe")
    void gateSelectivityIsRoughlyTheConfiguredSlice() throws Exception {
        config.setCandidateTopPercentile(20.0);
        config.setMinScoreForCandidate(60);

        // 290 stocks centred near 70 — the live post-fix distribution shape.
        List<MultibaggerScore> universe = new ArrayList<>();
        for (int i = 0; i < 290; i++) {
            universe.add(score("S" + i, 40 + (i % 55)));
        }
        rank(universe);

        long candidates = universe.stream().filter(screener::isCandidate).count();

        assertThat(candidates)
                .as("should be far below the 69%% the absolute-only gate produced")
                .isLessThan(90);
    }
}
