package com.example.trading.macro;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins event-level deduplication (SPEC §48.2).
 *
 * <p>The property that matters: four outlets reporting one rate cut, and two more following it up
 * the next day, are one event. Counting six would let the loudest story in the news cycle dominate
 * a portfolio reading purely by being repeated, which is the failure mode of every naive news
 * feature.
 */
class MacroEventDedupTest {

    private static final LocalDate WED = LocalDate.of(2026, 9, 9);

    @Test
    @DisplayName("The same factor moving the same way two days apart is one event")
    void followUpCoverageIsOneEvent() {
        assertThat(MacroEventDedup.sameEvent(MacroFactor.INTEREST_RATES, MacroDirection.DOWN, WED,
                MacroFactor.INTEREST_RATES, MacroDirection.DOWN, WED.plusDays(2), 3)).isTrue();
    }

    @Test
    @DisplayName("The same factor moving the same way ten days apart is two events")
    void separateMovesStaySeparate() {
        assertThat(MacroEventDedup.sameEvent(MacroFactor.CRUDE_OIL, MacroDirection.UP, WED,
                MacroFactor.CRUDE_OIL, MacroDirection.UP, WED.plusDays(10), 3)).isFalse();
    }

    @Test
    @DisplayName("Opposite directions on the same day are two events, not one")
    void oppositeDirectionsNeverMerge() {
        assertThat(MacroEventDedup.sameEvent(MacroFactor.USDINR, MacroDirection.UP, WED,
                MacroFactor.USDINR, MacroDirection.DOWN, WED, 3)).isFalse();
    }

    @Test
    @DisplayName("Different factors never merge however close in time")
    void differentFactorsNeverMerge() {
        assertThat(MacroEventDedup.sameEvent(MacroFactor.GOLD, MacroDirection.UP, WED,
                MacroFactor.METALS_PRICES, MacroDirection.UP, WED, 3)).isFalse();
    }

    @Test
    @DisplayName("The key is factor, direction and day - so an exact re-run cannot insert twice")
    void keyIdentifiesTheEvent() {
        String a = MacroEventDedup.key(MacroFactor.INTEREST_RATES, MacroDirection.DOWN, WED);
        String b = MacroEventDedup.key(MacroFactor.INTEREST_RATES, MacroDirection.DOWN, WED);
        String c = MacroEventDedup.key(MacroFactor.INTEREST_RATES, MacroDirection.UP, WED);

        assertThat(a).isEqualTo(b).isNotEqualTo(c).contains("2026-09-09");
    }

    @Test
    @DisplayName("A merge keeps the earliest date: follow-up coverage is not a second occurrence")
    void mergeKeepsEarliestDate() {
        MacroEventDedup.Merged m = MacroEventDedup.merge(
                WED, MacroMagnitude.MODERATE, 0.7, List.of(1L), List.of("a"), "Rate cut",
                WED.plusDays(2), MacroMagnitude.MODERATE, 0.6, List.of(2L), List.of("b"), "Rate cut analysis");

        assertThat(m.occurredAt()).isEqualTo(WED);
        assertThat(m.headlineIds()).containsExactly(1L, 2L);
        assertThat(m.sourceUrls()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("A merge keeps the largest magnitude and the highest confidence")
    void mergeKeepsTheStrongestClaim() {
        MacroEventDedup.Merged m = MacroEventDedup.merge(
                WED, MacroMagnitude.SMALL, 0.4, List.of(1L), List.of(), "Crude edges up",
                WED, MacroMagnitude.LARGE, 0.9, List.of(2L), List.of(), "Crude surges on supply cut");

        assertThat(m.magnitude()).isEqualTo(MacroMagnitude.LARGE);
        assertThat(m.confidence()).isEqualTo(0.9);
        assertThat(m.summary()).isEqualTo("Crude surges on supply cut");
    }

    @Test
    @DisplayName("A merge that adds nothing leaves the wording alone, so re-running does not churn")
    void mergeIsStableOnATie() {
        MacroEventDedup.Merged m = MacroEventDedup.merge(
                WED, MacroMagnitude.MODERATE, 0.7, List.of(1L), List.of(), "The first account",
                WED, MacroMagnitude.MODERATE, 0.7, List.of(1L), List.of(), "A later account");

        assertThat(m.summary()).isEqualTo("The first account");
        assertThat(m.headlineIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("An unmeasured confidence on one side never lowers the other to null")
    void nullConfidenceDoesNotErase() {
        MacroEventDedup.Merged withKeyword = MacroEventDedup.merge(
                WED, MacroMagnitude.MODERATE, 0.8, List.of(1L), List.of(), "x",
                WED, MacroMagnitude.MODERATE, null, List.of(2L), List.of(), "y");
        MacroEventDedup.Merged bothUnknown = MacroEventDedup.merge(
                WED, MacroMagnitude.MODERATE, null, List.of(1L), List.of(), "x",
                WED, MacroMagnitude.MODERATE, null, List.of(2L), List.of(), "y");

        assertThat(withKeyword.confidence()).isEqualTo(0.8);
        assertThat(bothUnknown.confidence()).isNull();
    }
}
