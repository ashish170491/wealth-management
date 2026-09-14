package com.example.trading.universe.ipo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.example.trading.universe.ipo.IpoStructureRead.Verdict.FAVOURABLE;
import static com.example.trading.universe.ipo.IpoStructureRead.Verdict.MIXED;
import static com.example.trading.universe.ipo.IpoStructureRead.Verdict.NOT_MEASURED;
import static com.example.trading.universe.ipo.IpoStructureRead.Verdict.UNFAVOURABLE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the pre-listing structure read (SPEC §45.3).
 *
 * <p>The properties that matter: a mid-issue subscription never decides anything; an unparsed
 * issue-size sentence leaves the read unmeasured rather than defaulting a leg to zero; an
 * unfilled institutional book outranks everything; and the read never produces an "apply".
 */
class IpoStructureReadTest {

    private static IpoStructureRead.Result read(Double fresh, Double qib, Double retail, Boolean fin) {
        return IpoStructureRead.read(new IpoStructureRead.Input(fresh, qib, retail, fin));
    }

    @Test
    @DisplayName("Nothing parsed and bidding still open: not measured")
    void nothingKnown() {
        IpoStructureRead.Result r = read(null, 0.5, 2.0, false);
        assertThat(r.verdict()).isEqualTo(NOT_MEASURED);
        assertThat(r.structureMeasured()).isFalse();
        assertThat(r.subscriptionMeasured()).isFalse();
    }

    @Test
    @DisplayName("A mid-issue QIB of 0.3x is not read as institutions passing")
    void midIssueSubscriptionNeverDecides() {
        IpoStructureRead.Result r = read(60.0, 0.3, 0.2, false);
        assertThat(r.verdict()).isEqualTo(FAVOURABLE);
        assertThat(r.subscriptionMeasured()).isFalse();
        assertThat(r.reasons()).anyMatch(s -> s.contains("not final"));
    }

    @Test
    @DisplayName("Final QIB below 1x is unfavourable whatever the structure says")
    void unfilledInstitutionalBook() {
        IpoStructureRead.Result r = read(90.0, 0.8, 25.0, true);
        assertThat(r.verdict()).isEqualTo(UNFAVOURABLE);
        assertThat(r.reasons().get(0)).contains("passed");
    }

    @Test
    @DisplayName("Retail 10x with institutions under 2x is the hype pattern")
    void hypePattern() {
        IpoStructureRead.Result r = read(70.0, 1.5, 30.0, true);
        assertThat(r.verdict()).isEqualTo(UNFAVOURABLE);
        assertThat(r.reasons().get(0)).contains("chasing");
    }

    @Test
    @DisplayName("Retail 10x with institutions at 2x or more is not hype")
    void retailEnthusiasmWithInstitutionsIsFine() {
        assertThat(read(70.0, 2.0, 30.0, true).verdict()).isEqualTo(FAVOURABLE);
    }

    @Test
    @DisplayName("Mostly an exit is mixed even when institutions wanted it")
    void mostlyExitIsMixed() {
        IpoStructureRead.Result r = read(16.0, 50.0, 8.0, true);
        assertThat(r.verdict()).isEqualTo(MIXED);
        assertThat(r.reasons().get(0)).contains("Just 16%");
    }

    @Test
    @DisplayName("A pure offer for sale says so in words")
    void pureOfs() {
        IpoStructureRead.Result r = read(0.0, null, null, false);
        assertThat(r.verdict()).isEqualTo(MIXED);
        assertThat(r.reasons().get(0)).contains("Entirely an offer for sale");
    }

    @Test
    @DisplayName("Half fresh and institutions at 2x: favourable")
    void capitalRaiseInstitutionsWanted() {
        IpoStructureRead.Result r = read(50.0, 2.0, 5.0, true);
        assertThat(r.verdict()).isEqualTo(FAVOURABLE);
        assertThat(r.subscriptionMeasured()).isTrue();
    }

    @Test
    @DisplayName("Half fresh but institutions lukewarm (1-2x) is mixed")
    void capitalRaiseInstitutionsLukewarm() {
        IpoStructureRead.Result r = read(60.0, 1.4, 3.0, true);
        assertThat(r.verdict()).isEqualTo(MIXED);
        assertThat(r.reasons()).anyMatch(s -> s.contains("without enthusiasm"));
    }

    @Test
    @DisplayName("A middling fresh share is mixed before and after the book closes")
    void middlingFreshShare() {
        assertThat(read(35.0, null, null, false).verdict()).isEqualTo(MIXED);
        assertThat(read(35.0, 10.0, 4.0, true).verdict()).isEqualTo(MIXED);
    }

    @Test
    @DisplayName("Structure unparsed but the final book is strong: mixed with the gap named")
    void unparsedStructureFinalBook() {
        IpoStructureRead.Result r = read(null, 20.0, 4.0, true);
        assertThat(r.verdict()).isEqualTo(MIXED);
        assertThat(r.structureMeasured()).isFalse();
        assertThat(r.subscriptionMeasured()).isTrue();
        assertThat(r.reasons().get(0)).contains("could not be read");
    }

    @Test
    @DisplayName("Final flag set but no institutional figure published: not measured when structure is also unknown")
    void finalWithoutQibFigure() {
        assertThat(read(null, null, 5.0, true).verdict()).isEqualTo(NOT_MEASURED);
    }

    @Test
    @DisplayName("The vocabulary never contains an instruction to buy or apply")
    void noApplyVocabulary() {
        for (IpoStructureRead.Verdict v : IpoStructureRead.Verdict.values()) {
            assertThat(v.name()).doesNotContain("APPLY").doesNotContain("BUY").doesNotContain("SUBSCRIBE");
        }
    }
}
