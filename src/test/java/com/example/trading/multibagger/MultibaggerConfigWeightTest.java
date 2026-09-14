package com.example.trading.multibagger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression guard for B-019 — the scoring weights summing to 1.15 instead of 1.0.
 *
 * <p>That bug inflated every multibagger composite by 15% for roughly four months. It was
 * invisible because the weights live in application.yml as {@code @ConfigurationProperties}:
 * the yml listed the seven pre-2026-04 dimensions, which <i>do</i> sum to exactly 1.00, and
 * the eighth (Financial Quality) silently kept its 0.15 Java default. A "60" candidate
 * threshold therefore meant 52, and ~70% of the screened universe passed it.
 *
 * <p>These tests pin both halves of the fix: the Java defaults are self-consistent, and the
 * validator actually rejects a drifting sum rather than logging and carrying on.
 */
class MultibaggerConfigWeightTest {

    @Test
    @DisplayName("Java default weights sum to exactly 1.0 across all seven dimensions")
    void defaultWeightsSumToOne() {
        MultibaggerConfig config = new MultibaggerConfig();

        double sum = config.getTechnicalMomentumWeight()
                + config.getVolumeAccumulationWeight()
                + config.getRelativeStrengthWeight()
                + config.getPriceStructureWeight()
                + config.getValuationWeight()
                + config.getInstitutionalInterestWeight()
                + config.getFinancialQualityWeight();

        assertThat(sum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    @DisplayName("Defaults match the SPEC §12.5 rebalance exactly")
    void defaultWeightsMatchSpec() {
        MultibaggerConfig config = new MultibaggerConfig();

        // If any of these change, SPEC §12.5's weight table must change in the same commit.
        assertThat(config.getTechnicalMomentumWeight()).isEqualTo(0.20);
        assertThat(config.getVolumeAccumulationWeight()).isEqualTo(0.13);
        assertThat(config.getRelativeStrengthWeight()).isEqualTo(0.13);
        assertThat(config.getPriceStructureWeight()).isEqualTo(0.13);
        assertThat(config.getValuationWeight()).isEqualTo(0.14);
        assertThat(config.getInstitutionalInterestWeight()).isEqualTo(0.11);
        assertThat(config.getFinancialQualityWeight()).isEqualTo(0.16);
    }

    @Test
    @DisplayName("validateWeights accepts a sum of exactly 1.0")
    void validatorAcceptsCorrectSum() {
        assertThatCode(() -> new MultibaggerConfig().validateWeights()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateWeights rejects the exact B-019 configuration (sum 1.15)")
    void validatorRejectsTheHistoricalBug() {
        MultibaggerConfig config = new MultibaggerConfig();
        // Reproduce the pre-2026-08-22 application.yml: the pre-financial-quality weights
        // summing to 1.00 on their own, with financial-quality left at its default on top.
        // (Sector Tailwind carried 0.10 of that 1.00 before it was removed on 2026-09-03;
        // its share is folded into technical-momentum here so the arithmetic of the bug —
        // a 1.00 block plus an unaccounted default — is reproduced exactly.)
        config.setTechnicalMomentumWeight(0.30);
        config.setVolumeAccumulationWeight(0.15);
        config.setRelativeStrengthWeight(0.15);
        config.setPriceStructureWeight(0.15);
        config.setValuationWeight(0.15);
        config.setInstitutionalInterestWeight(0.10);
        // financialQualityWeight deliberately left at 0.16 — this is the bug.

        assertThatThrownBy(config::validateWeights)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1.16");
    }

    @Test
    @DisplayName("validateWeights rejects an under-sum too, not just an over-sum")
    void validatorRejectsUnderSum() {
        MultibaggerConfig config = new MultibaggerConfig();
        config.setFinancialQualityWeight(0.06); // sum = 0.90

        assertThatThrownBy(config::validateWeights).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Tolerance is tight enough to catch a single mistyped weight")
    void toleranceIsTight() {
        MultibaggerConfig config = new MultibaggerConfig();
        config.setInstitutionalInterestWeight(0.12); // sum = 1.01 — a plausible typo

        assertThatThrownBy(config::validateWeights).isInstanceOf(IllegalStateException.class);
    }
}
