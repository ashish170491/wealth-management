package com.example.trading.watchlist;

import com.example.trading.watchlist.BuyTimingVerdict.Input;
import com.example.trading.watchlist.BuyTimingVerdict.Result;
import com.example.trading.watchlist.BuyTimingVerdict.Thresholds;
import com.example.trading.watchlist.BuyTimingVerdict.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §37.3 — the rule table, its precedence, and the null discipline. Change a rule → a case
 * here must fail.
 */
class BuyTimingVerdictTest {

    private static final Thresholds T = Thresholds.defaults();

    /** A clean, measured, "everything fine" stock: quality 80, BUY signal, RSI 55, price near EMA50. */
    private static Input good() {
        return new Input(80, "BUY", "Good technical setup.", 55.0, 100.0, 97.0, "BULLISH",
                "INTACT", 1, "", "DECENT", "LIQUID", "FAIRLY_VALUED", 4.0);
    }

    private static Input with(Input b, UnaryOperator<Builder> f) {
        return f.apply(new Builder(b)).build();
    }

    private static Result eval(Input in) {
        return BuyTimingVerdict.evaluate(in, T);
    }

    @Test @DisplayName("rule 12: quality >= 65 with a BUY signal is BUY_NOW")
    void buyNow() {
        Result r = eval(good());
        assertThat(r.verdict()).isEqualTo(Verdict.BUY_NOW);
        assertThat(r.qualityMeasured()).isTrue();
        assertThat(r.timingMeasured()).isTrue();
        assertThat(r.reason()).doesNotContain("null");
    }

    @Test @DisplayName("rule 1: nothing measured at all is NOT_MEASURED, never a default")
    void bothNull() {
        Input in = new Input(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        Result r = eval(in);
        assertThat(r.verdict()).isEqualTo(Verdict.NOT_MEASURED);
        assertThat(r.notMeasured()).contains("quality score (not yet screened)", "technical analysis", "RSI");
    }

    @Test @DisplayName("rule 2: a HIGH forensic flag beats a perfect chart and a 90 composite")
    void forensicBeatsEverything() {
        Input in = with(good(), b -> b.quality(90).signal("STRONG_BUY").flags("DILUTION:HIGH"));
        assertThat(eval(in).verdict()).isEqualTo(Verdict.AVOID);
        assertThat(eval(in).reason()).contains("dilution");
    }

    /**
     * B-065. Severity is read, not just presence — the same rule the screener already applied
     * (Gotcha 77). Flattening the tiers told the investor to AVOID three holdings on a medium
     * receivables note while the screener called those same stocks a caution.
     */
    @Test @DisplayName("rule 5b: a MEDIUM flag cautions (HOLD_OFF) and names itself, it does not disqualify")
    void mediumFlagIsACaution() {
        Result r = eval(with(good(), b -> b.quality(90).signal("STRONG_BUY").flags("RECEIVABLES:MEDIUM")));
        assertThat(r.verdict()).isEqualTo(Verdict.HOLD_OFF);
        assertThat(r.reason()).contains("receivables").contains("not disqualifying");
    }

    @Test @DisplayName("rule 5b: an unrecognised flag is a caution, never silently ignored")
    void unknownSeverityIsNotAFreePass() {
        Result r = eval(with(good(), b -> b.quality(90).signal("STRONG_BUY").flags("SOMETHING_ODD")));
        assertThat(r.verdict()).isEqualTo(Verdict.HOLD_OFF);
        assertThat(r.reason()).contains("something odd");
    }

    @Test @DisplayName("the worst severity present wins, whatever the order")
    void worstSeverityWins() {
        assertThat(eval(with(good(), b -> b.flags("RECEIVABLES:MEDIUM;DILUTION:HIGH"))).verdict())
                .isEqualTo(Verdict.AVOID);
        assertThat(eval(with(good(), b -> b.flags("DILUTION:HIGH;RECEIVABLES:MEDIUM"))).verdict())
                .isEqualTo(Verdict.AVOID);
    }

    @Test @DisplayName("rule 2: HIGH_RISK financial quality is AVOID")
    void highRisk() {
        assertThat(eval(with(good(), b -> b.fq("HIGH_RISK"))).verdict()).isEqualTo(Verdict.AVOID);
    }

    @Test @DisplayName("INFO-only forensic tokens are not red flags")
    void infoFlagsIgnored() {
        assertThat(eval(with(good(), b -> b.flags("INFO_SHORT_HISTORY, INFO_NEW_AUDITOR"))).verdict())
                .isEqualTo(Verdict.BUY_NOW);
        assertThat(BuyTimingVerdict.firstRedFlag("INFO_X,RECEIVABLES_FLAG")).isEqualTo("RECEIVABLES_FLAG");
        assertThat(BuyTimingVerdict.firstRedFlag(null)).isNull();
        assertThat(BuyTimingVerdict.firstRedFlag("NONE")).isNull();
    }

    @Test @DisplayName("rule 3: THIN liquidity is AVOID; UNKNOWN is not")
    void thin() {
        assertThat(eval(with(good(), b -> b.liq("THIN"))).verdict()).isEqualTo(Verdict.AVOID);
        assertThat(eval(with(good(), b -> b.liq("UNKNOWN"))).verdict()).isEqualTo(Verdict.BUY_NOW);
    }

    @Test @DisplayName("rule 4: quality 49 is AVOID, 50 is not — boundary")
    void qualityFloor() {
        assertThat(eval(with(good(), b -> b.quality(49))).verdict()).isEqualTo(Verdict.AVOID);
        assertThat(eval(with(good(), b -> b.quality(50))).verdict()).isNotEqualTo(Verdict.AVOID);
    }

    @Test @DisplayName("rule 5: DECAYING / BROKEN is HOLD_OFF; STALE and NO_DATA never block")
    void decay() {
        assertThat(eval(with(good(), b -> b.decay("DECAYING", -12))).verdict()).isEqualTo(Verdict.HOLD_OFF);
        assertThat(eval(with(good(), b -> b.decay("BROKEN", -25))).verdict()).isEqualTo(Verdict.HOLD_OFF);
        assertThat(eval(with(good(), b -> b.decay("STALE", null))).verdict()).isEqualTo(Verdict.BUY_NOW);
        assertThat(eval(with(good(), b -> b.decay("NO_DATA", null))).verdict()).isEqualTo(Verdict.BUY_NOW);
    }

    @Test @DisplayName("WATCH decay keeps the verdict but appends a note")
    void watchAppends() {
        Result r = eval(with(good(), b -> b.decay("WATCH", -6)));
        assertThat(r.verdict()).isEqualTo(Verdict.BUY_NOW);
        assertThat(r.reason()).contains("slipped -6");
    }

    @Test @DisplayName("rule 6: EXTREMELY_EXPENSIVE is HOLD_OFF")
    void dcf() {
        assertThat(eval(with(good(), b -> b.dcf("EXTREMELY_EXPENSIVE"))).verdict()).isEqualTo(Verdict.HOLD_OFF);
    }

    @Test @DisplayName("rule 7: BEARISH trend or AVOID signal is HOLD_OFF")
    void bearish() {
        assertThat(eval(with(good(), b -> b.trend("BEARISH"))).verdict()).isEqualTo(Verdict.HOLD_OFF);
        assertThat(eval(with(good(), b -> b.signal("AVOID"))).verdict()).isEqualTo(Verdict.HOLD_OFF);
    }

    @Test @DisplayName("rule 8: ran away since added (>15% and RSI>60) is WAIT")
    void runaway() {
        Result r = eval(with(good(), b -> b.ret(22.0).rsi(64.0)));
        assertThat(r.verdict()).isEqualTo(Verdict.WAIT_FOR_PULLBACK);
        assertThat(r.reason()).contains("22.0%");
        // Same return with a cool RSI is not a runaway.
        assertThat(eval(with(good(), b -> b.ret(22.0).rsi(50.0))).verdict()).isEqualTo(Verdict.BUY_NOW);
    }

    @Test @DisplayName("rule 8 never fires on an unmeasured return (seeded rows)")
    void runawayNeedsMeasuredReturn() {
        assertThat(eval(with(good(), b -> b.ret(null).rsi(64.0))).verdict()).isEqualTo(Verdict.BUY_NOW);
    }

    @Test @DisplayName("rule 9: RSI above 70 is WAIT; a null RSI is not 'not overbought'")
    void rsi() {
        assertThat(eval(with(good(), b -> b.rsi(74.0))).verdict()).isEqualTo(Verdict.WAIT_FOR_PULLBACK);
        Result r = eval(with(good(), b -> b.rsi(null)));
        assertThat(r.verdict()).isEqualTo(Verdict.BUY_NOW);
        assertThat(r.notMeasured()).contains("RSI");
    }

    @Test @DisplayName("rule 10: >10% above EMA50 is WAIT")
    void stretched() {
        assertThat(eval(with(good(), b -> b.price(120.0).ema50(100.0))).verdict()).isEqualTo(Verdict.WAIT_FOR_PULLBACK);
        assertThat(eval(with(good(), b -> b.price(105.0).ema50(100.0))).verdict()).isEqualTo(Verdict.BUY_NOW);
    }

    @Test @DisplayName("rule 11: quality null with a BUY signal is ACCUMULATE (unproven); with HOLD it is HOLD_OFF")
    void qualityNull() {
        Result r = eval(with(good(), b -> b.quality(null)));
        assertThat(r.verdict()).isEqualTo(Verdict.ACCUMULATE);
        assertThat(r.qualityMeasured()).isFalse();
        assertThat(r.reason()).contains("not in the screening universe");
        assertThat(eval(with(good(), b -> b.quality(null).signal("HOLD"))).verdict()).isEqualTo(Verdict.HOLD_OFF);
    }

    @Test @DisplayName("rule 13: good business, HOLD signal is ACCUMULATE")
    void goodBusinessNoTrigger() {
        assertThat(eval(with(good(), b -> b.signal("HOLD"))).verdict()).isEqualTo(Verdict.ACCUMULATE);
        assertThat(eval(with(good(), b -> b.signal(null))).verdict()).isEqualTo(Verdict.ACCUMULATE);
    }

    @Test @DisplayName("rule 14/15: middling quality — BUY signal ACCUMULATE, HOLD signal HOLD_OFF; 64/65 boundary")
    void middling() {
        assertThat(eval(with(good(), b -> b.quality(58))).verdict()).isEqualTo(Verdict.ACCUMULATE);
        assertThat(eval(with(good(), b -> b.quality(58).signal("HOLD"))).verdict()).isEqualTo(Verdict.HOLD_OFF);
        assertThat(eval(with(good(), b -> b.quality(64))).verdict()).isEqualTo(Verdict.ACCUMULATE);
        assertThat(eval(with(good(), b -> b.quality(65))).verdict()).isEqualTo(Verdict.BUY_NOW);
    }

    @Test @DisplayName("thresholds come from config, not constants")
    void thresholdsFromConfig() {
        Thresholds strict = new Thresholds(90, 50, 70, 15, 60, 10);
        assertThat(BuyTimingVerdict.evaluate(good(), strict).verdict()).isEqualTo(Verdict.ACCUMULATE);
    }

    /** Small builder so each case names only what it changes. */
    private static final class Builder {
        private Integer quality; private String signal; private String reason; private Double rsi;
        private Double price; private Double ema50; private String trend; private String decay;
        private Integer delta; private String flags; private String fq; private String liq; private String dcf; private Double ret;

        Builder(Input b) {
            quality = b.qualityScore(); signal = b.entrySignal(); reason = b.signalReason(); rsi = b.rsi14();
            price = b.currentPrice(); ema50 = b.ema50(); trend = b.trendDirection(); decay = b.decayVerdict();
            delta = b.decayDelta30(); flags = b.forensicFlags(); fq = b.financialQualityVerdict();
            liq = b.liquidityTier(); dcf = b.dcfVerdict(); ret = b.returnSinceAddPct();
        }
        Builder quality(Integer v) { quality = v; return this; }
        Builder signal(String v) { signal = v; return this; }
        Builder rsi(Double v) { rsi = v; return this; }
        Builder price(Double v) { price = v; return this; }
        Builder ema50(Double v) { ema50 = v; return this; }
        Builder trend(String v) { trend = v; return this; }
        Builder decay(String v, Integer d) { decay = v; delta = d; return this; }
        Builder flags(String v) { flags = v; return this; }
        Builder fq(String v) { fq = v; return this; }
        Builder liq(String v) { liq = v; return this; }
        Builder dcf(String v) { dcf = v; return this; }
        Builder ret(Double v) { ret = v; return this; }
        Input build() {
            return new Input(quality, signal, reason, rsi, price, ema50, trend, decay, delta, flags, fq, liq, dcf, ret);
        }
    }
}
