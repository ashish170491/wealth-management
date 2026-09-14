package com.example.trading.holdings;

import com.example.trading.multibagger.CompoundingLensService;
import com.example.trading.multibagger.CompoundingQuality;
import com.example.trading.persistence.HoldingsEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how the compounding lens (SPEC 41) reaches a portfolio row.
 *
 * <p>The property that matters is the one this codebase keeps re-learning: <b>a stock that was
 * never screened must arrive with the column empty, not with a failing verdict</b>. "We could not
 * look" and "we looked and it is weak" are different findings, and collapsing them is how a
 * clean business ends up wearing a red badge on the screen the investor makes decisions from
 * (Gotcha 21, 44).
 *
 * <p>The decoration itself is exercised through the private hook rather than through Spring,
 * because the interesting behaviour is the null handling and not the wiring.
 */
class HoldingsCompoundingDecorationTest {

    private static HoldingsEntity holding(String symbol) {
        HoldingsEntity h = new HoldingsEntity();
        h.setSymbol(symbol);
        h.setQuantity(10);
        return h;
    }

    /** Calls the decorator's private {@code applyCompounding}. */
    private static void apply(HoldingsEntity h, CompoundingLensService.Reading reading) {
        try {
            Method m = HoldingsViewDecorator.class.getDeclaredMethod(
                    "applyCompounding", HoldingsEntity.class, CompoundingLensService.Reading.class);
            m.setAccessible(true);
            m.invoke(null, h, reading);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("applyCompounding is the seam this test exists to pin", e);
        }
    }

    private static CompoundingLensService.Reading reading(CompoundingQuality.Result r,
                                                          String answered, String asked) {
        return new CompoundingLensService.Reading(r, answered, asked, LocalDate.of(2026, 9, 5));
    }

    /** A business that clears every applicable gate. */
    private static CompoundingQuality.Result strong() {
        return CompoundingQuality.evaluate(new CompoundingQuality.Input(
                28.0, 12.0, 1.1, 0.15, 85, 1.5, "STEADY", null, "HIGH_QUALITY", 6));
    }

    @Test
    @DisplayName("A never-screened holding leaves the column empty, not failing")
    void neverScreenedIsNotAFailure() {
        HoldingsEntity h = holding("NSE:NOTINUNIVERSE");
        apply(h, null);

        assertThat(h.getCompounding()).isNull();
        assertThat(h.getCompoundingReason()).isNull();
        assertThat(h.getCompoundingPassed()).isNull();
        assertThat(h.getCompoundingApplicable()).isNull();
        // The UI renders a null verdict as the striped "not measured" marker. If this ever became
        // the string "NO" the investor would read a red badge on a business nobody looked at.
        assertThat(h.getCompounding()).isNotEqualTo("NO");
    }

    @Test
    @DisplayName("A reading with no result is treated as never screened")
    void emptyResultIsAlsoNotAFailure() {
        HoldingsEntity h = holding("NSE:X");
        apply(h, reading(null, "NSE:X", "NSE:X"));
        assertThat(h.getCompounding()).isNull();
    }

    @Test
    @DisplayName("A measured holding carries the verdict, its counts and its depth")
    void measuredHoldingCarriesEverythingTheCellNeeds() {
        HoldingsEntity h = holding("NSE:GOOD");
        apply(h, reading(strong(), "NSE:GOOD", "NSE:GOOD"));

        // These four field names are what compounding.js reads. They are shared with the screener
        // column deliberately: one renderer, three surfaces, nothing to drift.
        assertThat(h.getCompounding()).isEqualTo("COMPOUNDER");
        assertThat(h.getCompoundingReason()).isNotBlank();
        assertThat(h.getCompoundingPassed()).isNotNull();
        assertThat(h.getCompoundingApplicable()).isNotNull();
        assertThat(h.getCompoundingPassed()).isEqualTo(h.getCompoundingApplicable());
        assertThat(h.getCompoundingYearsOfAccounts()).isEqualTo(6);
    }

    @Test
    @DisplayName("A BSE holding records the NSE symbol its history came from")
    void crossExchangeReadingIsTraceable() {
        // 22 of 33 holdings are BSE-prefixed while screening history is keyed on NSE. Reusing the
        // NSE history is correct - the composite describes the company, not the listing venue -
        // but the reading has to say so, or it cannot be traced later (Gotcha 84).
        HoldingsEntity h = holding("BSE:INFY");
        apply(h, reading(strong(), "NSE:INFY", "BSE:INFY"));

        assertThat(h.getCompounding()).isEqualTo("COMPOUNDER");
        assertThat(h.getCompoundingFrom()).isEqualTo("NSE:INFY");
    }

    @Test
    @DisplayName("Reading.resolvedAcrossExchange distinguishes a borrowed history from an own one")
    void resolvedFlagIsHonest() {
        assertThat(reading(strong(), "NSE:INFY", "BSE:INFY").resolvedAcrossExchange()).isTrue();
        assertThat(reading(strong(), "NSE:INFY", "NSE:INFY").resolvedAcrossExchange()).isFalse();
    }

    @Test
    @DisplayName("The lens never writes anything a screen could mistake for a score")
    void noScoreIsAttached() {
        // SPEC 41 is a lens: it contributes zero to the composite, and the portfolio row must not
        // gain a number that looks like one (Gotcha 30).
        HoldingsEntity h = holding("NSE:GOOD");
        h.setOverallScore(71);
        apply(h, reading(strong(), "NSE:GOOD", "NSE:GOOD"));
        assertThat(h.getOverallScore()).isEqualTo(71);
    }

    @Test
    @DisplayName("Decorating a list without a lens service still yields usable rows")
    void decorationIsBestEffort() {
        // A portfolio that will not load is far worse than one missing a derived column, so the
        // absence of a reading must never blank the row itself.
        List<HoldingsEntity> rows = List.of(holding("NSE:A"), holding("NSE:B"));
        rows.forEach(h -> apply(h, null));
        assertThat(rows).allSatisfy(h -> {
            assertThat(h.getSymbol()).isNotBlank();
            assertThat(h.getQuantity()).isEqualTo(10);
        });
    }
}
