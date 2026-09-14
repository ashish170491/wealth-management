package com.example.trading.portfolio.core;

import com.example.trading.portfolio.core.CoreDto.CoreTier;
import com.example.trading.portfolio.core.CoreHysteresis.AnchorReading;
import com.example.trading.portfolio.core.CoreHysteresis.TierDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier hysteresis (SPEC §35.6), including the asymmetry R-6 asked for: a critical demotion is
 * immediate while everything else waits for weekly confirmation.
 */
class CoreHysteresisTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 8, 25);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 8, 28);
    private static final int RUNS = 2;

    private static AnchorReading anchor(LocalDate d, CoreTier t) {
        return new AnchorReading(d, t);
    }

    private static TierDecision resolve(CoreTier provisional, CoreTier previous,
                                        List<AnchorReading> anchors, boolean critical, LocalDate day) {
        return CoreHysteresis.resolve(provisional, previous, anchors, critical, day, RUNS);
    }

    @Test
    @DisplayName("The first ever classification takes effect immediately — there is nothing to confirm")
    void firstClassificationIsImmediate() {
        TierDecision d = resolve(CoreTier.CORE, null, List.of(), false, TUESDAY);
        assertThat(d.effectiveTier()).isEqualTo(CoreTier.CORE);
        assertThat(d.pendingChange()).isNull();
    }

    @Test
    @DisplayName("An unchanged reading changes nothing and reports nothing pending")
    void unchangedIsQuiet() {
        TierDecision d = resolve(CoreTier.CORE, CoreTier.CORE, List.of(), false, TUESDAY);
        assertThat(d.effectiveTier()).isEqualTo(CoreTier.CORE);
        assertThat(d.pendingChange()).isNull();
    }

    @Test
    @DisplayName("A single day's reading never promotes into a protected tier")
    void promotionWaitsForAnchors() {
        TierDecision d = resolve(CoreTier.CORE, CoreTier.SATELLITE, List.of(), false, TUESDAY);
        assertThat(d.effectiveTier()).isEqualTo(CoreTier.SATELLITE);
        assertThat(d.pendingChange()).contains("would become CORE").contains("of 2");
    }

    @Test
    @DisplayName("Two consecutive Friday anchors promote")
    void twoAnchorsPromote() {
        List<AnchorReading> anchors = List.of(
                anchor(LocalDate.of(2026, 8, 21), CoreTier.CORE),
                anchor(LocalDate.of(2026, 8, 14), CoreTier.CORE));
        TierDecision d = resolve(CoreTier.CORE, CoreTier.SATELLITE, anchors, false, TUESDAY);
        assertThat(d.effectiveTier()).isEqualTo(CoreTier.CORE);
        assertThat(d.pendingChange()).isNull();
    }

    @Test
    @DisplayName("Today counts as an anchor when today is a Friday")
    void todayCountsOnFriday() {
        List<AnchorReading> one = List.of(anchor(LocalDate.of(2026, 8, 21), CoreTier.CORE));
        assertThat(resolve(CoreTier.CORE, CoreTier.SATELLITE, one, false, FRIDAY).effectiveTier())
                .isEqualTo(CoreTier.CORE);
        // The same single anchor on a Tuesday is only one confirmation.
        assertThat(resolve(CoreTier.CORE, CoreTier.SATELLITE, one, false, TUESDAY).effectiveTier())
                .isEqualTo(CoreTier.SATELLITE);
    }

    @Test
    @DisplayName("Confirmations must be consecutive — one disagreeing Friday resets the run")
    void nonConsecutiveAnchorsDoNotCount() {
        List<AnchorReading> broken = List.of(
                anchor(LocalDate.of(2026, 8, 21), CoreTier.CORE),
                anchor(LocalDate.of(2026, 8, 14), CoreTier.SATELLITE),
                anchor(LocalDate.of(2026, 8, 7), CoreTier.CORE));
        TierDecision d = resolve(CoreTier.CORE, CoreTier.SATELLITE, broken, false, TUESDAY);
        assertThat(d.effectiveTier()).isEqualTo(CoreTier.SATELLITE);
        assertThat(d.pendingChange()).contains("1 of 2");
    }

    @Test
    @DisplayName("A soft demotion also waits — one bad data day must not disarm the tier")
    void softDemotionWaits() {
        TierDecision d = resolve(CoreTier.SATELLITE, CoreTier.CORE, List.of(), false, TUESDAY);
        assertThat(d.effectiveTier()).isEqualTo(CoreTier.CORE);
        assertThat(d.pendingChange()).contains("would become SATELLITE");
    }

    /**
     * R-6. Hysteresis on a demotion means a company that has just tripped a forensic flag would
     * otherwise keep its exit alerts suppressed for up to a fortnight. An auditor problem
     * escalates rather than deducts (Gotcha 45), and the same logic applies here.
     */
    /**
     * B-059. A holding can pass every gate and still not be core yet, because promotion needs its
     * weekly confirmations. When that happens the decision must carry a non-null pendingChange:
     * it is the only channel through which anything downstream can learn a promotion is under way.
     * Without it the holdings email reported "no holding clears every core gate" while one did —
     * a false statement about the portfolio, made from a correct classification.
     */
    @Test
    @DisplayName("B-059: a first-time CORE reading is not yet effective, but says so")
    void pendingPromotionIsAnnounced() {
        TierDecision d = resolve(CoreTier.CORE, CoreTier.UNCLASSIFIED, List.of(), false, TUESDAY);
        assertThat(d.effectiveTier())
                .as("promotion must still wait for its confirmations")
                .isEqualTo(CoreTier.UNCLASSIFIED);
        assertThat(d.pendingChange())
                .as("the pending promotion must be reportable, not silent")
                .isNotNull()
                .contains("CORE");
    }

    @Test
    @DisplayName("R-6: a critical trigger demotes the same day, with no anchors at all")
    void criticalDemotionIsImmediate() {
        TierDecision d = resolve(CoreTier.SATELLITE, CoreTier.CORE, List.of(), true, TUESDAY);
        assertThat(d.effectiveTier()).isEqualTo(CoreTier.SATELLITE);
        assertThat(d.pendingChange()).isNull();
    }

    @Test
    @DisplayName("A critical trigger never accelerates a promotion — it only ever demotes")
    void criticalDoesNotAcceleratePromotion() {
        TierDecision d = resolve(CoreTier.CORE, CoreTier.SATELLITE, List.of(), true, TUESDAY);
        assertThat(d.effectiveTier()).isEqualTo(CoreTier.SATELLITE);
        assertThat(d.pendingChange()).isNotNull();
    }

    @Test
    @DisplayName("Re-promotion after a critical demotion goes through the normal two-anchor path")
    void rePromotionIsNotFastTracked() {
        List<AnchorReading> one = List.of(anchor(LocalDate.of(2026, 8, 21), CoreTier.CORE));
        assertThat(resolve(CoreTier.CORE, CoreTier.SATELLITE, one, false, TUESDAY).effectiveTier())
                .isEqualTo(CoreTier.SATELLITE);
    }

    /**
     * The observation trail is the whole evidence base for arming suppression, so a write to it
     * must never be an overwrite. The in-memory buffer is lost on a restart, and this app restarts
     * daily: a 12:00 alert written straight over a row already carrying the 10:00 one would delete
     * the morning's observation without a trace.
     */
    @Test
    @DisplayName("Observed alerts are merged into the row, never written over it")
    void observedAlertsAreUnioned() {
        assertThat(CoreClassificationService.unionAlerts("RSI_OVERBOUGHT",
                java.util.List.of("NEAR_RESISTANCE")))
                .containsExactly("RSI_OVERBOUGHT", "NEAR_RESISTANCE");

        assertThat(CoreClassificationService.unionAlerts("RSI_OVERBOUGHT",
                java.util.List.of("RSI_OVERBOUGHT")))
                .as("the same alert seen twice in a day is one observation")
                .containsExactly("RSI_OVERBOUGHT");

        assertThat(CoreClassificationService.unionAlerts(null, java.util.List.of("BROKE_SUPPORT")))
                .containsExactly("BROKE_SUPPORT");

        assertThat(CoreClassificationService.unionAlerts("RSI_OVERBOUGHT", java.util.List.of()))
                .as("an empty batch must not blank what is already recorded")
                .containsExactly("RSI_OVERBOUGHT");
    }

    @Test
    @DisplayName("Moves that do not cross the protected boundary take effect at once")
    void nonBoundaryMovesAreImmediate() {
        // Both protected: the overlay behaves identically, only the label differs.
        assertThat(resolve(CoreTier.CORE_WATCH, CoreTier.CORE, List.of(), false, TUESDAY)
                .effectiveTier()).isEqualTo(CoreTier.CORE_WATCH);
        // Neither protected: SATELLITE and UNCLASSIFIED both get today's exit behaviour.
        assertThat(resolve(CoreTier.UNCLASSIFIED, CoreTier.SATELLITE, List.of(), false, TUESDAY)
                .effectiveTier()).isEqualTo(CoreTier.UNCLASSIFIED);
    }
}
