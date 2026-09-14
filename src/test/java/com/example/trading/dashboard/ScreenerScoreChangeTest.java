package com.example.trading.dashboard;

import com.example.trading.holdings.HoldingsDecayService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The screener's "vs peers, 30d" figure reads a stock's score change against the universe's own
 * median move (B-064), through the same pure function the holdings decay verdict uses. These pin
 * the two properties that make it safe to print: a market-wide move reads as zero relative
 * change, and too few paired symbols yields null rather than a zero shift.
 */
class ScreenerScoreChangeTest {

    private static Map<String, Integer> run(int n, int base, int offset) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < n; i++) m.put("NSE:S" + i, base + (i % 7) + offset);
        return m;
    }

    @Test
    @DisplayName("a universe-wide fall is the shift, so a stock that fell with it reads flat relative to peers")
    void universeWideFallIsTheShift() {
        Map<String, Integer> then = run(40, 70, 0);
        Map<String, Integer> now = run(40, 70, -15);
        Integer shift = HoldingsDecayService.medianShift(then, now);
        assertThat(shift).isEqualTo(-15);
        int stockDelta = now.get("NSE:S3") - then.get("NSE:S3");
        assertThat(stockDelta - shift).isZero();
    }

    @Test
    @DisplayName("below 30 paired symbols the shift is null — an unmeasured shift is never zero")
    void tooFewPairsIsNull() {
        assertThat(HoldingsDecayService.medianShift(run(29, 70, 0), run(29, 70, -5))).isNull();
        assertThat(HoldingsDecayService.medianShift(run(40, 70, 0), run(10, 70, -5))).isNull();
        assertThat(HoldingsDecayService.medianShift(null, run(40, 70, 0))).isNull();
    }

    @Test
    @DisplayName("only symbols present on both runs pair up; a null score is skipped, not read as zero")
    void pairsOnlyOnBothRuns() {
        Map<String, Integer> then = run(40, 70, 0);
        Map<String, Integer> now = run(40, 70, 4);
        now.put("NSE:NEWCOMER", 95);          // not on the earlier run
        then.put("NSE:GONE", 60);             // not on the later run
        then.put("NSE:S1", null);             // unmeasured then
        assertThat(HoldingsDecayService.medianShift(then, now)).isEqualTo(4);
    }
}
