package com.example.trading.multibagger;

import com.example.trading.watchlist.BuyTimingVerdict.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.example.trading.multibagger.ScreenerTimingVerdict.Input;
import static com.example.trading.multibagger.ScreenerTimingVerdict.Result;
import static com.example.trading.multibagger.ScreenerTimingVerdict.evaluate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §12.11 — the screener/discovery "Still good time to buy?" column.
 *
 * <p>Pins every rule row and, more importantly, the two properties that make the column safe to
 * put in front of a beginner: an unmeasured input never fires a rule, and a business-quality stop
 * always outranks a timing signal.
 */
class ScreenerTimingVerdictTest {

    /** A clean, unremarkable row: good quality, mid momentum, no flags. */
    private static Input base() {
        return new Input(75, 55.0, 12.0, 40.0, 2.0, null, "HIGH_QUALITY", "LIQUID", "FAIRLY_VALUED");
    }

    private static Input with(Input b, Double rsi, Double fromHigh, Double slope) {
        return new Input(b.compositeScore(), rsi, fromHigh, b.priceVs52WeekLow(), slope,
                b.forensicFlags(), b.financialQualityVerdict(), b.liquidityTier(), b.dcfVerdict());
    }

    // ----------------------------------------------------------------- not measured

    @Test
    @DisplayName("No quality and no timing is NOT_MEASURED, never a weak verdict")
    void nothingToStandOn() {
        Result r = evaluate(new Input(null, null, null, null, null, null, null, null, null));
        assertThat(r.verdict()).isEqualTo(Verdict.NOT_MEASURED);
        assertThat(r.notMeasured()).isNotEmpty();
        assertThat(r.reason()).contains("not been screened");
    }

    @Test
    @DisplayName("Quality known but timing unreadable says so, rather than guessing an entry")
    void qualityWithoutTiming() {
        Result r = evaluate(new Input(80, null, null, null, null, null, "HIGH_QUALITY", "LIQUID", null));
        assertThat(r.verdict()).isEqualTo(Verdict.NOT_MEASURED);
        assertThat(r.reason()).contains("80/100").contains("cannot be judged");
    }

    /**
     * The property that keeps this column honest. A missing RSI is not "not overbought" — if it
     * were, an unmeasured stock would collect the same verdict as a calm one (Gotcha 21).
     */
    @Test
    @DisplayName("A null input never triggers the rule it would have triggered")
    void nullNeverFiresARule() {
        // fromHigh alone, deeply below the high, but no slope: must NOT be called a falling knife.
        Result r = evaluate(with(base(), null, 40.0, null));
        assertThat(r.verdict()).isNotEqualTo(Verdict.HOLD_OFF);
    }

    // ----------------------------------------------------------------- hard stops

    @Test
    @DisplayName("HIGH_RISK balance sheet is AVOID regardless of a perfect entry")
    void highRiskOutranksTiming() {
        Input i = new Input(90, 45.0, 30.0, 20.0, 3.0, null, "HIGH_RISK", "LIQUID", "UNDERVALUED");
        Result r = evaluate(i);
        assertThat(r.verdict()).isEqualTo(Verdict.AVOID);
        assertThat(r.reason()).contains("high risk");
    }

    private static Input withFlags(String flags, int score) {
        return new Input(score, 50.0, 20.0, 30.0, 2.0, flags, "HIGH_QUALITY", "LIQUID", "UNDERVALUED");
    }

    @Test
    @DisplayName("A HIGH-severity flag is AVOID and the reason names it")
    void highSeverityFlagOutranksTiming() {
        Result r = evaluate(withFlags("DILUTION:HIGH", 88));
        assertThat(r.verdict()).isEqualTo(Verdict.AVOID);
        assertThat(r.reason()).contains("dilution");
    }

    /**
     * Severity is respected, not flattened. The forensic screen grades its own flags and scores
     * INFO at zero (SPEC §32.4); collapsing the three tiers sent a 90-score business to AVOID on a
     * medium receivables note — measured on the live 2026-08-27 run, which is how this was caught.
     */
    @Test
    @DisplayName("A MEDIUM flag cautions (HOLD_OFF), it does not disqualify")
    void mediumSeverityIsACaution() {
        Result r = evaluate(withFlags("RECEIVABLES:MEDIUM", 90));
        assertThat(r.verdict()).isEqualTo(Verdict.HOLD_OFF);
        assertThat(r.reason()).contains("receivables").contains("not disqualifying");
    }

    @Test
    @DisplayName("An INFO flag is not a stop at all")
    void infoSeverityIsNotAStop() {
        Result r = evaluate(withFlags("AUDITOR_NOTE:INFO", 90));
        assertThat(r.verdict()).isIn(Verdict.BUY_NOW, Verdict.ACCUMULATE);
    }

    @Test
    @DisplayName("The worst severity present wins, whatever the order")
    void worstSeverityWins() {
        assertThat(evaluate(withFlags("RECEIVABLES:MEDIUM;DILUTION:HIGH", 88)).verdict())
                .isEqualTo(Verdict.AVOID);
        assertThat(evaluate(withFlags("DILUTION:HIGH;RECEIVABLES:MEDIUM", 88)).verdict())
                .isEqualTo(Verdict.AVOID);
    }

    @Test
    @DisplayName("An empty flag string is not a flag")
    void blankFlagsAreNotAFlag() {
        Input b = base();
        Input i = new Input(b.compositeScore(), b.weeklyRsi(), b.priceVs52WeekHigh(),
                b.priceVs52WeekLow(), b.weeklyEmaSlope(), "   ", "HIGH_QUALITY", "LIQUID", null);
        assertThat(evaluate(i).verdict()).isNotEqualTo(Verdict.AVOID);
    }

    @Test
    @DisplayName("THIN liquidity is AVOID — you cannot act on the verdict anyway")
    void thinLiquidityIsAvoid() {
        Input b = base();
        Input i = new Input(b.compositeScore(), b.weeklyRsi(), b.priceVs52WeekHigh(),
                b.priceVs52WeekLow(), b.weeklyEmaSlope(), null, "HIGH_QUALITY", "THIN", null);
        assertThat(evaluate(i).verdict()).isEqualTo(Verdict.AVOID);
    }

    /** UNKNOWN is not THIN — SPEC §12.9 / Gotcha 33, restated at the presentation layer. */
    @Test
    @DisplayName("UNKNOWN liquidity does not become AVOID")
    void unknownLiquidityIsNotThin() {
        Input b = base();
        Input i = new Input(b.compositeScore(), b.weeklyRsi(), b.priceVs52WeekHigh(),
                b.priceVs52WeekLow(), b.weeklyEmaSlope(), null, "HIGH_QUALITY", "UNKNOWN", null);
        assertThat(evaluate(i).verdict()).isNotEqualTo(Verdict.AVOID);
    }

    @Test
    @DisplayName("Quality below the floor is AVOID at any price")
    void lowQualityIsAvoid() {
        Input b = base();
        Input i = new Input(35, b.weeklyRsi(), b.priceVs52WeekHigh(), b.priceVs52WeekLow(),
                b.weeklyEmaSlope(), null, "AVERAGE", "LIQUID", null);
        Result r = evaluate(i);
        assertThat(r.verdict()).isEqualTo(Verdict.AVOID);
        assertThat(r.reason()).contains("35/100");
    }

    // ----------------------------------------------------------------- timing rules

    @Test
    @DisplayName("Hot weekly RSI is WAIT_FOR_PULLBACK")
    void stretchedIsWait() {
        Result r = evaluate(with(base(), 78.0, 2.0, 5.0));
        assertThat(r.verdict()).isEqualTo(Verdict.WAIT_FOR_PULLBACK);
        assertThat(r.reason()).contains("78").contains("12-month high");
    }

    /**
     * B-056's lesson at the entry side: a stock sitting near its high on ordinary momentum is a
     * base, not a blow-off. Calling that "wait" is how a compounder gets talked out of.
     */
    @Test
    @DisplayName("Near the high on calm momentum is NOT a wait")
    void nearHighAloneIsNotStretched() {
        Result r = evaluate(with(base(), 55.0, 2.0, 3.0));
        assertThat(r.verdict()).isNotEqualTo(Verdict.WAIT_FOR_PULLBACK);
        assertThat(r.verdict()).isEqualTo(Verdict.ACCUMULATE);
        assertThat(r.reason()).contains("tranches");
    }

    @Test
    @DisplayName("Deep below the high AND still falling is HOLD_OFF")
    void fallingKnifeIsHoldOff() {
        Result r = evaluate(with(base(), 45.0, 40.0, -3.0));
        assertThat(r.verdict()).isEqualTo(Verdict.HOLD_OFF);
        assertThat(r.reason()).contains("still falling");
    }

    @Test
    @DisplayName("Deep below the high but no longer falling is the pullback case, BUY_NOW")
    void pullbackWithTrendStabilisedIsBuy() {
        Result r = evaluate(with(base(), 45.0, 40.0, 1.0));
        assertThat(r.verdict()).isEqualTo(Verdict.BUY_NOW);
        assertThat(r.reason()).contains("pullback");
    }

    // ------------------------------------------------- range position (the GALAXYSURF case)

    /**
     * The defect the user caught. GALAXYSURF read "good entry" on the screener and "wait for a
     * dip" on the watchlist: 13% below its 12-month high (a pullback) and 11% above its 50-day
     * average (stretched) — both true. Distance-from-high alone cannot tell a quiet base from a
     * round-trip. Measured on the live 2026-08-27 run, 50 of 56 BUY_NOW rows sat in the top half
     * of their 52-week range.
     */
    @Test
    @DisplayName("A stock that already bounced back up its range is not a pullback")
    void roundTripIsNotAPullback() {
        // 13% below the high but 53% above the low = 80% of the way up the range: GALAXYSURF.
        Input i = new Input(100, 64.0, 13.0, 52.6, 9.1, null, "HIGH_QUALITY", "LIQUID", null);
        Result r = evaluate(i);
        assertThat(r.verdict()).isEqualTo(Verdict.ACCUMULATE);
        assertThat(r.reason()).contains("recovered most of the way");
    }

    @Test
    @DisplayName("The same distance from the high IS a pullback when the stock is low in its range")
    void samePullbackLowInRangeIsBuyNow() {
        // 13% below the high and only 8% above the low = 38% of the range.
        Input i = new Input(100, 45.0, 13.0, 8.0, 1.0, null, "HIGH_QUALITY", "LIQUID", null);
        assertThat(evaluate(i).verdict()).isEqualTo(Verdict.BUY_NOW);
    }

    @Test
    @DisplayName("Range position is null when either leg is unmeasured, and never fires the gate")
    void rangePositionNeedsBothLegs() {
        assertThat(ScreenerTimingVerdict.rangePosition(null, 13.0)).isNull();
        assertThat(ScreenerTimingVerdict.rangePosition(52.6, null)).isNull();
        assertThat(ScreenerTimingVerdict.rangePosition(0.0, 0.0)).isNull();
        // Missing low leg must not block a BUY_NOW - an unmeasured input never fires a rule.
        Input i = new Input(100, 45.0, 13.0, null, 1.0, null, "HIGH_QUALITY", "LIQUID", null);
        assertThat(evaluate(i).verdict()).isEqualTo(Verdict.BUY_NOW);
    }

    @Test
    @DisplayName("Range position arithmetic: at the low is 0, at the high is 100")
    void rangePositionEndpoints() {
        assertThat(ScreenerTimingVerdict.rangePosition(0.0, 50.0)).isEqualTo(0.0);
        assertThat(ScreenerTimingVerdict.rangePosition(50.0, 0.0)).isEqualTo(100.0);
        assertThat(ScreenerTimingVerdict.rangePosition(25.0, 25.0)).isEqualTo(50.0);
    }

    @Test
    @DisplayName("Weak momentum with no turn is HOLD_OFF")
    void weakMomentumIsHoldOff() {
        Result r = evaluate(with(base(), 30.0, 15.0, -0.5));
        assertThat(r.verdict()).isEqualTo(Verdict.HOLD_OFF);
    }

    @Test
    @DisplayName("Middling quality caps the verdict at ACCUMULATE and says quality is the limit")
    void midQualityNeverReachesBuyNow() {
        Input b = base();
        Input i = new Input(58, 45.0, 30.0, 40.0, 1.0, null, "DECENT", "LIQUID", null);
        Result r = evaluate(i);
        assertThat(r.verdict()).isEqualTo(Verdict.ACCUMULATE);
        assertThat(r.reason()).contains("58/100").contains("smaller");
    }

    // ----------------------------------------------------------------- ordering

    /**
     * Precedence is the whole design: a business fact settles the question before a price fact is
     * consulted. If this ever reverses, the column starts recommending entries into broken books.
     */
    @Test
    @DisplayName("A hard stop beats every timing rule, including the best one")
    void hardStopsOutrankEveryTimingRule() {
        for (Double rsi : new Double[]{30.0, 55.0, 85.0}) {
            for (Double fromHigh : new Double[]{1.0, 20.0, 45.0}) {
                Input i = new Input(90, rsi, fromHigh, 30.0, 2.0, null, "HIGH_RISK", "LIQUID", null);
                assertThat(evaluate(i).verdict())
                        .as("rsi=%s fromHigh=%s", rsi, fromHigh)
                        .isEqualTo(Verdict.AVOID);
            }
        }
    }

    @Test
    @DisplayName("Every verdict carries a non-blank reason a beginner can read")
    void everyVerdictExplainsItself() {
        Input[] cases = {
                base(),
                with(base(), 85.0, 1.0, 6.0),
                with(base(), 45.0, 40.0, -3.0),
                with(base(), 45.0, 40.0, 1.0),
                new Input(null, null, null, null, null, null, null, null, null),
                new Input(30, 50.0, 10.0, 20.0, 1.0, null, "WEAK", "LIQUID", null),
        };
        for (Input i : cases) {
            Result r = evaluate(i);
            assertThat(r.reason()).isNotBlank();
            assertThat(r.verdict()).isNotNull();
        }
    }

    // ------------------------------------------------------- 52-week range position

    @Test
    @DisplayName("The result carries where the price sits in its 52-week range, for the screener to show")
    void rangePositionIsCarriedOut() {
        // 40% above the low, 12% below the high: 40 / (40 + 12) of the way up.
        Result r = evaluate(base());
        assertThat(r.rangePosition52w()).isNotNull();
        assertThat(r.rangePosition52w()).isCloseTo(76.9, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    @DisplayName("Half a range is not a range: a missing leg leaves the position null, never 0 or 100")
    void rangePositionOnTheResultNeedsBothLegs() {
        Input noLow = new Input(75, 55.0, 12.0, null, 2.0, null, "HIGH_QUALITY", "LIQUID", "FAIRLY_VALUED");
        assertThat(evaluate(noLow).rangePosition52w()).isNull();
        Input noHigh = new Input(75, 55.0, null, 40.0, 2.0, null, "HIGH_QUALITY", "LIQUID", "FAIRLY_VALUED");
        assertThat(evaluate(noHigh).rangePosition52w()).isNull();
        // The verdict itself is unaffected by the extra component.
        assertThat(evaluate(base()).verdict()).isEqualTo(evaluate(base()).verdict());
    }
}
