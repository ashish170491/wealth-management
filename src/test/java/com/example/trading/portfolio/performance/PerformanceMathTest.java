package com.example.trading.portfolio.performance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** SPEC 46.1: the return must not move when money is added, and must refuse when it cannot know. */
class PerformanceMathTest {

    private static final LocalDate D0 = LocalDate.of(2026, 1, 1);

    private static PerformanceMath.Day day(int offset, double invested, double value) {
        return new PerformanceMath.Day(D0.plusDays(offset), invested, value);
    }

    @Test @DisplayName("a deposit does not count as a return, which is the whole point")
    void depositIsNotReturn() {
        // Day 1: 100 invested, worth 100. Day 2: another 100 deposited, nothing moved.
        var r = PerformanceMath.timeWeighted(List.of(day(0, 100, 100), day(1, 200, 200)), Map.of());
        assertThat(r.twrPercent()).isEqualTo(0.0);
        // The headline "gain on cost" would also read 0 here; the case that separates them:
        var r2 = PerformanceMath.timeWeighted(List.of(day(0, 100, 120), day(1, 200, 220)), Map.of());
        assertThat(r2.twrPercent()).isEqualTo(0.0);   // still flat after the deposit
    }

    @Test @DisplayName("market moves chain-link")
    void chainLinks() {
        var r = PerformanceMath.timeWeighted(List.of(day(0, 100, 100), day(1, 100, 110), day(2, 100, 99)), Map.of());
        // +10% then -10% = -1%
        assertThat(r.twrPercent()).isCloseTo(-1.0, within(0.01));
        assertThat(r.indexed()).hasSize(3);
        assertThat(r.indexed().get(1).index()).isEqualTo(110.0);
    }

    @Test @DisplayName("a sale at a gain is a flow of cost plus the recorded gain, not a loss")
    void saleWithRecordedGain() {
        // Hold 100 cost worth 150; sell it all at 150. Value 0, invested 0. Realised gain 50 recorded.
        var withGain = PerformanceMath.timeWeighted(
                List.of(day(0, 100, 150), day(1, 0, 0)), Map.of(D0.plusDays(1), 50.0));
        assertThat(withGain.twrPercent()).isEqualTo(0.0);
        assertThat(withGain.flowsCorrected()).isEqualTo(1);
        // Without the sale on record the day reads as a 33% loss, and the result says so.
        var without = PerformanceMath.timeWeighted(List.of(day(0, 100, 150), day(1, 0, 0)), Map.of());
        assertThat(without.twrPercent()).isCloseTo(-33.33, within(0.01));
        assertThat(without.flowsUncorrected()).isEqualTo(1);
    }

    @Test @DisplayName("fewer than two snapshots is no return, and a short span is not annualised")
    void refusals() {
        assertThat(PerformanceMath.timeWeighted(List.of(day(0, 100, 100)), Map.of()).twrPercent()).isNull();
        assertThat(PerformanceMath.timeWeighted(List.of(), Map.of()).twrPercent()).isNull();
        assertThat(PerformanceMath.annualise(10.0, 30)).isNull();
        assertThat(PerformanceMath.annualise(10.0, 365)).isEqualTo(10.0);
        assertThat(PerformanceMath.annualise(21.0, 730)).isCloseTo(10.0, within(0.01));
        assertThat(PerformanceMath.annualise(null, 365)).isNull();
    }

    @Test @DisplayName("a zero opening value is skipped, never divided by")
    void zeroOpeningValueSkipped() {
        var r = PerformanceMath.timeWeighted(List.of(day(0, 0, 0), day(1, 100, 100), day(2, 100, 110)), Map.of());
        assertThat(r.twrPercent()).isEqualTo(10.0);
    }

    @Test @DisplayName("drawdown is on the flow-free index, and a withdrawal is not a fall")
    void drawdown() {
        var r = PerformanceMath.timeWeighted(
                List.of(day(0, 100, 100), day(1, 100, 120), day(2, 100, 96), day(3, 50, 54)), Map.of());
        var dd = PerformanceMath.drawdown(r.indexed());
        assertThat(dd.maxDrawdownPercent()).isEqualTo(-20.0);
        assertThat(dd.peakDate()).isEqualTo(D0.plusDays(1));
        assertThat(dd.maxDrawdownTrough()).isEqualTo(D0.plusDays(2));
        // Day 3: half withdrawn at cost (flow -50), value 54: return on 96 base = (54-96+50)/96 = +8.3%
        assertThat(dd.currentDrawdownPercent()).isCloseTo(-13.33, within(0.01));
        assertThat(PerformanceMath.drawdown(List.of())).isNull();
    }

    @Test @DisplayName("benchmark point-to-point refuses a missing leg")
    void pctChange() {
        assertThat(PerformanceMath.pctChange(null, 110.0)).isNull();
        assertThat(PerformanceMath.pctChange(100.0, null)).isNull();
        assertThat(PerformanceMath.pctChange(0.0, 110.0)).isNull();
        assertThat(PerformanceMath.pctChange(100.0, 112.5)).isEqualTo(12.5);
    }
}
