package com.example.trading.learning;

import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.ShadowCompositeEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the reconstruction rule that makes shadow composites trustworthy (SPEC §38.7).
 *
 * <p>The whole design rests on one claim: a variant differs from the live vector <i>only</i> by
 * the reweighting, because every bonus and the hard cap are computed from data that does not
 * depend on the weights. These tests hold that claim to its consequences — most importantly that
 * the rows where it does not hold are marked, rather than being averaged in and quietly
 * corrupting the one panel built to be trustworthy.
 */
class ShadowReconstructionTest {

    private static MultibaggerScoreEntity row(int composite, String fqVerdict, String forensicFlags) {
        return MultibaggerScoreEntity.builder()
                .symbol("NSE:TEST")
                .compositeScore(composite)
                .technicalMomentumScore(60)
                .volumeAccumulationScore(60)
                .relativeStrengthScore(60)
                .priceStructureScore(60)
                .valuationScore(60)
                .institutionalInterestScore(60)
                .financialQualityScore(60)
                .financialQualityVerdict(fqVerdict)
                .forensicFlags(forensicFlags)
                .build();
    }

    @Test
    @DisplayName("An ordinary composite is an exact reconstruction")
    void ordinaryRowIsExact() {
        assertThat(ShadowCompositeService.isReconstructionExact(row(63, "DECENT", null))).isTrue();
    }

    @Test
    @DisplayName("A composite pinned at a clamp boundary is not an exact reconstruction")
    void clampBoundaryIsInexact() {
        // At 100 the bonus chain stopped adding; the recovered adjustment is the movement that
        // survived the clamp, not the movement that was intended, so re-applying it to a
        // different base is arithmetic about a number that was never computed.
        assertThat(ShadowCompositeService.isReconstructionExact(row(100, "DECENT", null))).isFalse();
        assertThat(ShadowCompositeService.isReconstructionExact(row(0, "DECENT", null))).isFalse();
    }

    @Test
    @DisplayName("A HIGH_RISK row sitting on the hard cap is not an exact reconstruction")
    void hardCapIsInexact() {
        // min(x, 54) for an unknown x is not of the form base + constant at all.
        assertThat(ShadowCompositeService.isReconstructionExact(row(54, "HIGH_RISK", null))).isFalse();
    }

    @Test
    @DisplayName("An auditor flag triggers the same cap and the same inexact marking")
    void auditorFlagIsInexact() {
        assertThat(ShadowCompositeService.isReconstructionExact(row(54, "DECENT", "AUDITOR:HIGH")))
                .isFalse();
    }

    @Test
    @DisplayName("54 reached honestly, without a cap, stays exact")
    void fiftyFourWithoutACapIsStillExact() {
        // The cap value is not itself suspicious — only a cap being applicable is. Flagging
        // every 54 would discard sound observations at the one score the universe clusters near.
        assertThat(ShadowCompositeService.isReconstructionExact(row(54, "DECENT", null))).isTrue();
        assertThat(ShadowCompositeService.isReconstructionExact(row(54, "DECENT", "RECEIVABLES:MEDIUM")))
                .isTrue();
    }

    @Test
    @DisplayName("Percentile ranks put the best at 100 and share the best rank across ties")
    void percentileRanksMatchTheScreenerConvention() {
        List<ShadowCompositeEntity> rows = List.of(
                shadow("A", 90), shadow("B", 80), shadow("C", 80), shadow("D", 50));

        ShadowCompositeService.assignPercentileRanks(rows);

        assertThat(rows.get(0).getPercentileRank()).isEqualTo(100.0);
        // Both 80s take the better of the two positions, so a tie is never broken arbitrarily.
        assertThat(rows.get(1).getPercentileRank()).isEqualTo(rows.get(2).getPercentileRank());
        assertThat(rows.get(1).getPercentileRank()).isCloseTo(66.67, org.assertj.core.data.Offset.offset(0.01));
        assertThat(rows.get(3).getPercentileRank()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("A single row gets no percentile rank rather than a flattering 100")
    void singleRowHasNoRank() {
        List<ShadowCompositeEntity> rows = new java.util.ArrayList<>(List.of(shadow("A", 90)));
        ShadowCompositeService.assignPercentileRanks(rows);
        // Rank is a cross-sectional statement. With one stock there is no cross-section, and
        // "best of one" is not a finding (the same reason a one-symbol run must never write the
        // day's coverage vector).
        assertThat(rows.get(0).getPercentileRank()).isNull();
    }

    private static ShadowCompositeEntity shadow(String symbol, int composite) {
        return ShadowCompositeEntity.builder()
                .symbol(symbol)
                .variantName("equal")
                .composite(composite)
                .reconstructionExact(true)
                .build();
    }
}
