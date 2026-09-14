package com.example.trading.multibagger;

import com.example.trading.watchlist.BuyTimingVerdict.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.example.trading.multibagger.SuggestedEntry.Result;
import static com.example.trading.multibagger.SuggestedEntry.Rung;
import static com.example.trading.multibagger.SuggestedEntry.compute;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §12.12 — the entry ladder.
 *
 * <p>The property that matters most is agreement: this plan is displayed next to the entry verdict,
 * so it must never contradict it. B-062 was that failure across two screens and B-068 was the same
 * failure inside one cell ("wait for a dip" beside a level 0.0% below today's price). The ladder
 * removes it <em>structurally</em> — a waiting verdict's rungs all start below today's price — so
 * the tests below assert the structure rather than a guard.
 */
class SuggestedEntryTest {

    private static final double PRICE = 1000.0;
    private static final double ATR = 30.0;

    // ------------------------------------------------------------ refusals

    /** Naming a price for a stock the system says to avoid reads as "buy it cheaper". */
    @Test
    @DisplayName("AVOID gets no plan at all, and says why")
    void avoidGetsNoPlan() {
        Result r = compute(Verdict.AVOID, PRICE, 900.0, ATR, 950.0);
        assertThat(r.isEmpty()).isTrue();
        assertThat(r.price()).isNull();
        assertThat(r.reason()).contains("Not a buy at any price");
    }

    @Test
    @DisplayName("An unjudged stock, or one with no price, gets no plan")
    void unjudgedOrPricelessGetsNoPlan() {
        assertThat(compute(Verdict.NOT_MEASURED, PRICE, 900.0, ATR, null).isEmpty()).isTrue();
        assertThat(compute(null, PRICE, 900.0, ATR, null).isEmpty()).isTrue();
        assertThat(compute(Verdict.BUY_NOW, null, 900.0, ATR, null).isEmpty()).isTrue();
        assertThat(compute(Verdict.BUY_NOW, 0.0, 900.0, ATR, null).isEmpty()).isTrue();
    }

    // ------------------------------------------------------------ the ladder's shape

    @Test
    @DisplayName("A buy verdict starts at today's price and steps down")
    void buyStartsAtMarket() {
        for (Verdict v : new Verdict[]{Verdict.BUY_NOW, Verdict.ACCUMULATE}) {
            Result r = compute(v, PRICE, null, ATR, null);
            assertThat(r.rungs()).as("%s", v).hasSize(3);
            assertThat(r.rungs().get(0).price()).as("%s first rung", v).isEqualTo(PRICE);
            assertThat(r.rungs().get(0).label()).isEqualTo("now");
            assertThat(r.fallback()).as("a buy-now plan needs no fallback").isNull();
        }
    }

    /**
     * The B-068 fix, now structural: a waiting verdict cannot quote today's price because its
     * ladder begins one step below it.
     */
    @Test
    @DisplayName("A waiting verdict puts every rung strictly below today's price")
    void waitingStartsBelowMarket() {
        for (Verdict v : new Verdict[]{Verdict.WAIT_FOR_PULLBACK, Verdict.HOLD_OFF}) {
            Result r = compute(v, PRICE, null, ATR, null);
            assertThat(r.rungs()).as("%s", v).hasSize(3);
            for (Rung rung : r.rungs()) {
                assertThat(rung.price()).as("%s rung %s", v, rung.label()).isLessThan(PRICE);
            }
            assertThat(r.fallback()).as("%s must say what to do if the dip never comes", v)
                    .isNotBlank();
        }
    }

    /** The exact figures the investor found: KRBL at Rs 424.10 with a 20-day low of Rs 424.00. */
    @Test
    @DisplayName("KRBL: the waiting plan never quotes today's price")
    void krblRegression() {
        Result r = compute(Verdict.WAIT_FOR_PULLBACK, 424.10, 424.00, null, 385.27);
        assertThat(r.rungs()).isNotEmpty();
        for (Rung rung : r.rungs()) {
            assertThat(rung.price()).isLessThan(424.10);
        }
        assertThat(r.price()).isNotEqualTo(424.00);
    }

    @Test
    @DisplayName("Rungs descend, and their shares total 100%")
    void rungsDescendAndSharesTotal100() {
        for (Verdict v : new Verdict[]{Verdict.BUY_NOW, Verdict.WAIT_FOR_PULLBACK}) {
            Result r = compute(v, PRICE, null, ATR, null);
            int total = 0;
            for (int i = 0; i < r.rungs().size(); i++) {
                total += r.rungs().get(i).sharePercent();
                if (i > 0) {
                    assertThat(r.rungs().get(i).price()).as("%s descending", v)
                            .isLessThan(r.rungs().get(i - 1).price());
                }
            }
            assertThat(total).as("%s shares must total 100", v).isEqualTo(100);
        }
    }

    // ------------------------------------------------------------ volatility normalisation

    /**
     * The step is the stock's own normal daily range, so the same ladder means the same thing for
     * a placid large-cap and a volatile small-cap. A fixed percentage could not do this.
     */
    @Test
    @DisplayName("ATR sets the spacing, so a volatile stock gets a wider ladder")
    void atrSetsTheSpacing() {
        Result calm = compute(Verdict.BUY_NOW, PRICE, null, 10.0, null);
        Result wild = compute(Verdict.BUY_NOW, PRICE, null, 60.0, null);
        double calmSpan = PRICE - calm.rungs().get(2).price();
        double wildSpan = PRICE - wild.rungs().get(2).price();
        assertThat(wildSpan).isGreaterThan(calmSpan);
    }

    @Test
    @DisplayName("With no ATR the ladder still forms, on a percentage step")
    void fallsBackToAPercentageStep() {
        Result r = compute(Verdict.BUY_NOW, PRICE, null, null, null);
        assertThat(r.rungs()).hasSize(3);
        assertThat(r.rungs().get(2).price()).isLessThan(PRICE);
    }

    // ------------------------------------------------------------ the 50-day anchor

    /**
     * The verdict's own reason cites the 50-day average, so when the average is nearer than the
     * volatility ladder reaches, the deepest rung is snapped to it — the number now answers the
     * question the words asked (the second half of B-068).
     */
    @Test
    @DisplayName("A nearby 50-day average anchors the deepest rung and is named")
    void nearbyAverageAnchorsDeepestRung() {
        Result r = compute(Verdict.BUY_NOW, PRICE, null, ATR, 955.0);
        Rung deepest = r.rungs().get(r.rungs().size() - 1);
        assertThat(deepest.price()).isEqualTo(955.0);
        assertThat(deepest.label()).isEqualTo("at the 50-day average");
    }

    /**
     * A level the stock may not revisit for a year is not a plan. When the average is further away
     * than the ladder reaches it is left out entirely rather than quoted.
     */
    @Test
    @DisplayName("A distant 50-day average is not quoted")
    void distantAverageIsIgnored() {
        Result r = compute(Verdict.BUY_NOW, PRICE, null, ATR, 600.0);
        for (Rung rung : r.rungs()) {
            assertThat(rung.price()).as("600 is far below the ladder").isNotEqualTo(600.0);
            assertThat(rung.label()).isNotEqualTo("at the 50-day average");
        }
    }

    /**
     * Snapping the deepest rung to an average that sits just under its neighbour would print two
     * tranches a fraction apart - a distinction the investor cannot act on.
     */
    @Test
    @DisplayName("An average too close to the rung above is left alone")
    void averageTooCloseToNeighbourIsIgnored() {
        // Natural ladder for a 30 ATR buy plan: 1000 / 970 / 940. An average at 968 sits inside
        // the last gap but only 2 below the rung above, so it must not become a rung.
        Result r = compute(Verdict.BUY_NOW, PRICE, null, ATR, 968.0);
        java.util.List<Rung> rungs = r.rungs();
        for (int i = 1; i < rungs.size(); i++) {
            assertThat(rungs.get(i - 1).price() - rungs.get(i).price())
                    .as("rungs must be far enough apart to be different instructions")
                    .isGreaterThanOrEqualTo(ATR / 2.0);
        }
    }

    /**
     * The other side of that threshold, and the case that matters more: KRBL's real figures. Its
     * average sat 0.41 of a step under the rung above and must be kept - discarding it would throw
     * away the anchor this feature exists to quote.
     */
    @Test
    @DisplayName("KRBL: a moderately close average is still quoted as the deepest rung")
    void moderatelyCloseAverageIsKept() {
        Result r = compute(Verdict.WAIT_FOR_PULLBACK, 424.10, 424.00, 16.3, 385.27);
        Rung deepest = r.rungs().get(r.rungs().size() - 1);
        assertThat(deepest.label()).isEqualTo("at the 50-day average");
        assertThat(deepest.price()).isEqualTo(385.0);
    }

    @Test
    @DisplayName("An average above today's price is never used")
    void averageAbovePriceIgnored() {
        Result r = compute(Verdict.BUY_NOW, PRICE, null, ATR, 1200.0);
        for (Rung rung : r.rungs()) {
            assertThat(rung.price()).isLessThanOrEqualTo(PRICE);
        }
    }

    // ------------------------------------------------------------ invariants

    /**
     * "Buy higher than it currently trades" is not a fresh entry. Rounding must not be able to
     * produce one either, which is why levels round <em>down</em> rather than to nearest.
     */
    @Test
    @DisplayName("No rung is ever above today's price, for any verdict or input")
    void neverAboveTodaysPrice() {
        double[] prices = {37.4, 424.10, 999.99, 2503.0, 12130.0};
        Double[] atrs = {null, 0.5, 30.0, 400.0};
        Double[] emas = {null, 10.0, 900.0, 5000.0};
        for (Verdict v : Verdict.values()) {
            for (double p : prices) {
                for (Double a : atrs) {
                    for (Double e : emas) {
                        for (Rung rung : compute(v, p, null, a, e).rungs()) {
                            assertThat(rung.price()).as("%s p=%s atr=%s ema=%s", v, p, a, e)
                                    .isLessThanOrEqualTo(p);
                            assertThat(rung.price()).as("a rung is never free").isGreaterThan(0.0);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Every result explains itself, with or without a ladder")
    void everyResultExplainsItself() {
        for (Verdict v : Verdict.values()) {
            assertThat(compute(v, PRICE, 900.0, ATR, 950.0).reason()).as("%s", v).isNotBlank();
            assertThat(compute(v, PRICE, null, null, null).reason()).as("%s bare", v).isNotBlank();
        }
    }

    /** Rounding exists to avoid implying a precision nobody has. */
    @Test
    @DisplayName("Levels round down to a sensible tick")
    void levelsRoundDown() {
        assertThat(SuggestedEntry.roundDown(424.37)).isEqualTo(424.0);
        assertThat(SuggestedEntry.roundDown(2503.9)).isEqualTo(2500.0);
        assertThat(SuggestedEntry.roundDown(37.49)).isEqualTo(37.45);
    }
}
