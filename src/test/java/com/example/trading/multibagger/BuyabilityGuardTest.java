package com.example.trading.multibagger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the buyability guard (SPEC §12.9, F7).
 *
 * <p>The property that matters most here is the <b>null discipline</b>: an unmeasurable
 * liquidity must classify as UNKNOWN, never as THIN. THIN excludes a stock from promotion,
 * so letting a measurement gap collapse into THIN would silently blacklist stocks for having
 * short price history rather than for being illiquid.
 */
class BuyabilityGuardTest {

    private final MultibaggerScreenerService service = new MultibaggerScreenerService(
            null, null, null, null, null, null, null, null, null, null,
            new MultibaggerConfig(), null, null, null, null, null, null, null, null, null, null, null,
            // SPEC 50: the quarterly-result ledger and its config. Null here because these tests
            // exercise pure scoring only, and capture is guarded by the config being consulted first.
            null, null);

    private static List<Map<String, Object>> candles(int n, double close, double volume) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("close", close);
            c.put("high", close * 1.01);
            c.put("low", close * 0.99);
            c.put("volume", volume);
            out.add(c);
        }
        return out;
    }

    @Test
    @DisplayName("ADV20 averages close x volume over the last 20 candles")
    void adv20IsAverageTradedValue() {
        Double adv = service.calculateAdv20(candles(60, 100.0, 10_000));
        assertThat(adv).isNotNull();
        assertThat(adv).isEqualTo(1_000_000.0);
    }

    @Test
    @DisplayName("ADV20 is null — not zero — when history is too short to measure")
    void adv20NullOnShortHistory() {
        assertThat(service.calculateAdv20(candles(19, 100.0, 10_000))).isNull();
        assertThat(service.calculateAdv20(null)).isNull();
    }

    @Test
    @DisplayName("ADV20 is null when every candle is missing volume, rather than 0")
    void adv20NullWhenNoVolumeData() {
        List<Map<String, Object>> h = candles(30, 100.0, 0);
        assertThat(service.calculateAdv20(h)).isNull();
    }

    @Test
    @DisplayName("B-044: untraded days count in the denominator, so a barely-traded stock reads THIN")
    void adv20DividesByTheWindowNotByTradedDays() {
        // A stock that traded on only 3 of the last 20 days, at Rs 20 lakh on each of them.
        // Real absorbable liquidity is Rs 3 lakh/day averaged over the window.
        List<Map<String, Object>> h = candles(20, 100.0, 0);
        for (int i = 0; i < 3; i++) h.get(i).put("volume", 20_000d);   // 100 x 20,000 = Rs 20 lakh

        Double adv = service.calculateAdv20(h);
        assertThat(adv).isEqualTo(3_00_000.0);   // 60 lakh / 20 days

        // Dividing by traded days instead gave Rs 20 lakh — a 6.7x overstatement that
        // lifted the stock out of THIN and past the F3 promotion filter that relies on it.
        assertThat(adv).isNotEqualTo(20_00_000.0);
        assertThat(MultibaggerScore.classifyLiquidity(adv)).isEqualTo("THIN");
    }

    @Test
    @DisplayName("B-044: a fully-traded window is unaffected by the fix")
    void adv20UnchangedWhenEveryDayTraded() {
        assertThat(service.calculateAdv20(candles(20, 100.0, 10_000))).isEqualTo(1_000_000.0);
    }

    @Test
    @DisplayName("Unmeasurable liquidity is UNKNOWN, never THIN")
    void unknownIsNotThin() {
        assertThat(MultibaggerScore.classifyLiquidity(null)).isEqualTo("UNKNOWN");
        assertThat(MultibaggerScore.classifyLiquidity(0.0)).isEqualTo("UNKNOWN");
        assertThat(MultibaggerScore.classifyLiquidity(-1.0)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("Liquidity tiers sit at Rs 50 lakh and Rs 5 crore")
    void liquidityTierBoundaries() {
        assertThat(MultibaggerScore.classifyLiquidity(4_99_99_999d)).isEqualTo("MODERATE");
        assertThat(MultibaggerScore.classifyLiquidity(5_00_00_000d)).isEqualTo("LIQUID");
        assertThat(MultibaggerScore.classifyLiquidity(50_00_000d)).isEqualTo("MODERATE");
        assertThat(MultibaggerScore.classifyLiquidity(49_99_999d)).isEqualTo("THIN");
    }

    @Test
    @DisplayName("Days-to-build makes thinness concrete and rounds up")
    void daysToBuild() {
        // Rs 20 lakh/day traded, 10% participation => Rs 2 lakh/day absorbable.
        assertThat(MultibaggerScore.daysToBuild(20_00_000d, 1_00_000d, 0.10)).isEqualTo(1);
        // Rs 2 lakh/day traded => Rs 20,000/day absorbable => 5 days for Rs 1 lakh.
        assertThat(MultibaggerScore.daysToBuild(2_00_000d, 1_00_000d, 0.10)).isEqualTo(5);
        // Partial days round UP — you cannot finish in 4.2 days.
        assertThat(MultibaggerScore.daysToBuild(2_40_000d, 1_00_000d, 0.10)).isEqualTo(5);
    }

    @Test
    @DisplayName("Days-to-build is null when liquidity is unknown, so it can never render as 0 days")
    void daysToBuildNullWhenUnknown() {
        assertThat(MultibaggerScore.daysToBuild(null, 1_00_000d, 0.10)).isNull();
        assertThat(MultibaggerScore.daysToBuild(0d, 1_00_000d, 0.10)).isNull();
    }

    @Test
    @DisplayName("Circuit days count candles with no intraday range at all")
    void circuitDaysDetectLockedCandles() {
        List<Map<String, Object>> h = candles(60, 100.0, 1000);
        for (int i = 0; i < 4; i++) {
            h.get(i).put("high", 100.0);
            h.get(i).put("low", 100.0);
        }
        assertThat(service.countCircuitDays(h)).isEqualTo(4);
    }

    @Test
    @DisplayName("Circuit days are null when there is less than 60 days of history")
    void circuitDaysNullOnShortHistory() {
        assertThat(service.countCircuitDays(candles(59, 100.0, 1000))).isNull();
    }
}
