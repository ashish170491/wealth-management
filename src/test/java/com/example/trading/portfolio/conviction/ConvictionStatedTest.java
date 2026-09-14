package com.example.trading.portfolio.conviction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** B-097: a thesis the app generated is not a thesis the investor can be held to. */
class ConvictionStatedTest {

    private static HoldingConvictionEntity row(String thesis, Boolean thesisStated, Boolean horizonStated) {
        return HoldingConvictionEntity.builder()
                .symbol("NSE:X").thesis(thesis).convictionScore(5)
                .thesisStated(thesisStated).horizonStated(horizonStated).build();
    }

    @Test @DisplayName("the stored flag wins in both directions")
    void storedFlagWins() {
        assertThat(ConvictionService.isStated(row("Selected via app's discovery reports.", true, false))).isTrue();
        assertThat(ConvictionService.isStated(row("Aluminium rally + China reopening", false, true))).isFalse();
    }

    @Test @DisplayName("a legacy row is read from what the seeder writes")
    void legacyRowsDerived() {
        assertThat(ConvictionService.isStated(row("Selected via app's multibagger screening. Current grade A.", null, null))).isFalse();
        assertThat(ConvictionService.isStated(row("Aluminium rally + China reopening", null, null))).isTrue();
        // The seeded-horizon marker is the other fingerprint of a generated record.
        assertThat(ConvictionService.isStated(row("Anything at all", null, false))).isFalse();
    }

    @Test @DisplayName("an empty thesis is not a statement")
    void emptyIsNotStated() {
        assertThat(ConvictionService.isStated(row("", null, null))).isFalse();
        assertThat(ConvictionService.isStated(row(null, null, null))).isFalse();
    }
}
