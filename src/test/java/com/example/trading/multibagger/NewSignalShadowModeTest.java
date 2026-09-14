package com.example.trading.multibagger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shadow-mode gate on newly-added scoring signals (Gotcha 30).
 *
 * <p>The rule this defends: a signal that claims a stock will go up must be computed,
 * persisted and IC-measured for a while before it is allowed to move a recommendation. The
 * composite's own edge is +1.54pp/month at t≈1.24 (p≈0.28) over roughly five independent
 * periods, so every unvalidated free parameter added to it is an opportunity to fit one
 * risk-on quarter as skill.
 *
 * <p>The deliberate exception is risk controls, which ship armed. If someone flips a
 * default here, a test should fail and make them say why.
 */
class NewSignalShadowModeTest {

    private final MultibaggerConfig config = new MultibaggerConfig();

    @Test
    @DisplayName("Capex Cycle ships in shadow mode")
    void capexIsShadowed() {
        // The most tempting signal in the system: it leads the P&L instead of following it.
        // That is a reason to measure it carefully, not a reason to trust it early.
        assertThat(config.isCapexActionable()).isFalse();
        // ...but the points are configured and ready, so flipping the flag is the only step.
        assertThat(config.getCapexExpansionBonus()).isPositive();
        assertThat(config.getCapexInvestingBonus()).isPositive();
        assertThat(config.getCapexHarvestingPenalty()).isPositive();
    }

    @Test
    @DisplayName("Turnaround detection ships in shadow mode")
    void turnaroundIsShadowed() {
        assertThat(config.isTurnaroundActionable()).isFalse();
        assertThat(config.getTurnaroundBonus()).isPositive();
    }

    @Test
    @DisplayName("Insider Pulse is still shadowed — nobody has flipped it without evidence")
    void insiderPulseStillShadowed() {
        assertThat(config.isInsiderPulseActionable()).isFalse();
    }

    @Test
    @DisplayName("Macro exposure ships in shadow mode, and has no bonus figure to flip on")
    void macroExposureIsShadowed() {
        MultibaggerConfig config = new MultibaggerConfig();

        assertThat(config.isMacroExposureActionable()).isFalse();

        // The absence is as load-bearing as the flag. Every other shadowed signal carries a
        // companion bonus waiting to be switched on; this one deliberately does not, because
        // choosing how many points a headwind is worth before the measurement has established
        // that it even has a sign is the fitting-a-quarter-as-skill failure in miniature
        // (Gotcha 30). A reviewer adding one has to justify the number.
        assertThat(java.util.Arrays.stream(MultibaggerConfig.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList())
                .as("a macro bonus must not be added without argument")
                .doesNotContain("macroExposureBonus");
    }

    @Test
    @DisplayName("Forensic risk flags ship ARMED — a guard that is off protects nothing")
    void forensicFlagsAreArmed() {
        // The asymmetry is the point. A wrong bonus costs a missed opportunity; a missed
        // auditor resignation costs capital. The existing HIGH_RISK composite cap ships
        // armed for the same reason.
        assertThat(config.isForensicActionable()).isTrue();
    }

    @Test
    @DisplayName("Capex expansion is worth more than merely investing, which is worth more than nothing")
    void capexBonusOrdering() {
        // EXPANSION_UNDERWAY is the signal the feature exists for and must outrank the
        // weaker INVESTING reading, or the ordering silently stops meaning anything.
        assertThat(config.getCapexExpansionBonus()).isGreaterThan(config.getCapexInvestingBonus());
    }
}
