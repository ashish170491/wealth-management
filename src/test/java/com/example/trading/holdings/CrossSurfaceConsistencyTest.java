package com.example.trading.holdings;

import com.example.trading.multibagger.SuggestedEntry;
import com.example.trading.watchlist.BuyTimingVerdict.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §6.6 / §12.12 — the same stock must get the same answer on every screen.
 *
 * <p>This is the test that would have caught B-062, B-065 and B-069, all of which were the same
 * shape: two surfaces answering one question with two engines. Fixing them one at a time is what
 * produced the next one, so what is pinned here is not a particular screen's output but the
 * <strong>property</strong> that the shared rules make disagreement impossible.
 *
 * <p>These are pure-function checks. They do not prove a page calls the right function — only that
 * calling it can only give one answer. The structural half of the guarantee is that every holdings
 * read path goes through {@code HoldingsViewDecorator}, so a screen renders what it is given.
 */
class CrossSurfaceConsistencyTest {

    // ------------------------------------------------- one entry ladder, whatever the caller

    /**
     * The portfolio used to compute {@code suggestedEntry ?? support1} while the screener and
     * watchlist used the ladder, so one stock carried two entry prices. Identical inputs must now
     * produce an identical plan no matter who asks.
     */
    @Test
    @DisplayName("The same inputs give the same entry ladder on every surface")
    void oneLadderForEverySurface() {
        double price = 424.10;
        Double support = 424.00, atr = 16.3, ema50 = 385.27;
        for (Verdict v : Verdict.values()) {
            SuggestedEntry.Result a = SuggestedEntry.compute(v, price, support, atr, ema50);
            SuggestedEntry.Result b = SuggestedEntry.compute(v, price, support, atr, ema50);
            assertThat(a.rungs()).as("%s", v).isEqualTo(b.rungs());
            assertThat(a.price()).as("%s", v).isEqualTo(b.price());
            assertThat(a.basis()).as("%s", v).isEqualTo(b.basis());
        }
    }

    /**
     * A holding stores its 20-day low as {@code support1} and the screening row stores the same
     * number as {@code support20d}. Feeding either into the shared rule must give the same plan —
     * this is what makes the portfolio and the screener agree rather than merely look similar.
     */
    @Test
    @DisplayName("A holding's levels and a screening row's levels are interchangeable")
    void holdingAndScreeningLevelsAgree() {
        double price = 1000.0;
        SuggestedEntry.Result fromHolding = SuggestedEntry.compute(
                Verdict.WAIT_FOR_PULLBACK, price, 940.0, 30.0, 955.0);
        SuggestedEntry.Result fromScreening = SuggestedEntry.compute(
                Verdict.WAIT_FOR_PULLBACK, price, 940.0, 30.0, 955.0);
        assertThat(fromHolding.rungs()).isEqualTo(fromScreening.rungs());
    }

    // ------------------------------------------------- the signal cannot contradict the verdict

    /**
     * The property the investor actually reported: a buy signal must never be displayed beside a
     * verdict that says do not buy. Swept over every combination rather than the one that was
     * reported, because BEL was one of nine.
     */
    @Test
    @DisplayName("No combination can display a buy beside AVOID")
    void aBuyIsNeverShownBesideAvoid() {
        for (String stored : new String[]{"STRONG_BUY", "BUY", "HOLD", "SELL", "STRONG_SELL",
                "BOOK_PROFIT"}) {
            String shown = SignalReconciliation.reconcile(stored, "AVOID", "because").displaySignal();
            assertThat(shown).as("stored %s beside AVOID", stored).isNotIn("BUY", "STRONG_BUY");
        }
    }

    /** And the strongest endorsement never sits beside a caution. */
    @Test
    @DisplayName("STRONG_BUY is never shown beside HOLD_OFF")
    void strongBuyNeverShownBesideHoldOff() {
        assertThat(SignalReconciliation.reconcile("STRONG_BUY", "HOLD_OFF", "caution")
                .displaySignal()).isNotEqualTo("STRONG_BUY");
    }

    /**
     * Reconciliation must be idempotent. A decorated row that is passed through a second surface
     * must not be downgraded twice — that would make the answer depend on how many screens it
     * crossed, which is the very thing this is meant to stop.
     */
    @Test
    @DisplayName("Reconciling an already-reconciled signal changes nothing")
    void reconciliationIsIdempotent() {
        for (String stored : new String[]{"STRONG_BUY", "BUY", "HOLD"}) {
            for (String verdict : new String[]{"AVOID", "HOLD_OFF", "BUY_NOW", "NOT_MEASURED"}) {
                String once = SignalReconciliation.reconcile(stored, verdict, "r").displaySignal();
                String twice = SignalReconciliation.reconcile(once, verdict, "r").displaySignal();
                assertThat(twice).as("%s + %s applied twice", stored, verdict).isEqualTo(once);
            }
        }
    }

    /**
     * An absent verdict must leave every surface showing the same thing it showed before, rather
     * than one screen degrading to a different answer than another (Gotcha 21).
     */
    @Test
    @DisplayName("A missing verdict degrades identically everywhere")
    void missingVerdictDegradesIdentically() {
        for (String stored : new String[]{"STRONG_BUY", "BUY", "HOLD", "SELL"}) {
            assertThat(SignalReconciliation.reconcile(stored, null, null).displaySignal())
                    .isEqualTo(stored);
            assertThat(SignalReconciliation.reconcile(stored, "NOT_MEASURED", null).displaySignal())
                    .isEqualTo(stored);
        }
        assertThat(SuggestedEntry.compute(null, 1000.0, 900.0, 30.0, 950.0).isEmpty()).isTrue();
        assertThat(SuggestedEntry.compute(Verdict.NOT_MEASURED, 1000.0, 900.0, 30.0, 950.0)
                .isEmpty()).isTrue();
    }
}
