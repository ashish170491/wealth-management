package com.example.trading.universe;

import com.example.trading.ai.NseDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the universe-expansion funnel (SPEC §30, F3).
 *
 * <p>The properties defended here are the ones that keep an automatically-widening universe
 * from quietly becoming unreviewable or dishonest:
 * <ul>
 *   <li>BE/BZ trade-to-trade series never enter (Gotcha 14)</li>
 *   <li>THIN liquidity never gets promoted (SPEC §12.9)</li>
 *   <li>"no usable data" stays distinct from "measured and rejected"</li>
 *   <li>a base of higher lows means genuinely ascending lows, not a falling knife</li>
 * </ul>
 */
class UniverseExpansionTest {

    private static Map<String, Object> candle(double close, double high, double low, double vol) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("close", close);
        c.put("high", high);
        c.put("low", low);
        c.put("volume", vol);
        return c;
    }

    private static List<Map<String, Object>> flat(int n, double price, double vol) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(candle(price, price * 1.01, price * 0.99, vol));
        return out;
    }

    // ---- Series filtering ----

    @Test
    @DisplayName("Only the plain EQ series counts as mainboard equity")
    void onlyEqSeriesIsMainboard() {
        assertThat(listing("ACME", "EQ").isMainboardEquity()).isTrue();
        // BE/BZ are trade-to-trade / surveillance names: illiquid, and Kite tradingsymbols
        // never carry the suffix anyway (B-013).
        assertThat(listing("ACME", "BE").isMainboardEquity()).isFalse();
        assertThat(listing("ACME", "BZ").isMainboardEquity()).isFalse();
        assertThat(listing("ACME", "").isMainboardEquity()).isFalse();
    }

    @Test
    @DisplayName("Qualified symbol carries the NSE prefix the rest of the system expects")
    void qualifiedSymbol() {
        assertThat(listing("ACME", "EQ").qualifiedSymbol()).isEqualTo("NSE:ACME");
    }

    private static NseDataService.EquityListing listing(String sym, String series) {
        return new NseDataService.EquityListing(sym, "Acme Ltd", series,
                LocalDate.now().minusYears(5), "INE000A01001");
    }

    // ---- Traded value ----

    @Test
    @DisplayName("Average traded value is close x volume, and null when unmeasurable")
    void averageTradedValue() {
        assertThat(UniverseExpansionService.averageTradedValue(flat(30, 100, 10_000), 20))
                .isEqualTo(1_000_000.0);
        // Too little history to judge — null, never 0. A 0 would read as "illiquid" and
        // permanently exclude a stock for having a short listing history.
        assertThat(UniverseExpansionService.averageTradedValue(flat(10, 100, 10_000), 20)).isNull();
        assertThat(UniverseExpansionService.averageTradedValue(null, 20)).isNull();
        assertThat(UniverseExpansionService.averageTradedValue(flat(30, 100, 0), 20)).isNull();
    }

    // ---- Relative strength ----

    @Test
    @DisplayName("Relative strength is the stock's return minus the index's")
    void relativeStrength() {
        List<Map<String, Object>> stock = new ArrayList<>(flat(200, 100, 1000));
        stock.set(stock.size() - 1, candle(120, 121, 119, 1000));   // +20% on the last bar
        List<Map<String, Object>> nifty = new ArrayList<>(flat(200, 100, 1000));
        nifty.set(nifty.size() - 1, candle(105, 106, 104, 1000));   // +5%

        Double rs = UniverseExpansionService.relativeStrength(stock, nifty, 126);
        assertThat(rs).isNotNull();
        assertThat(rs).isCloseTo(15.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("Relative strength is null when the stock has too little history to compare")
    void relativeStrengthNullOnShortHistory() {
        assertThat(UniverseExpansionService.relativeStrength(flat(50, 100, 1000), flat(200, 100, 1000), 126))
                .isNull();
    }

    @Test
    @DisplayName("B-049: a missing index series is unmeasured, NOT the stock's absolute return")
    void relativeStrengthIsNullWithoutTheBenchmark() {
        // This used to return the raw +10%, which quietly redefines "relative strength" as
        // "went up". In a rising market that passes for nearly every stock, so the promotion
        // filter would have stopped being relative at the exact moment the Nifty fetch failed
        // — and nothing in the output would have said so.
        List<Map<String, Object>> stock = new ArrayList<>(flat(200, 100, 1000));
        stock.set(stock.size() - 1, candle(110, 111, 109, 1000));

        assertThat(UniverseExpansionService.relativeStrength(stock, null, 126)).isNull();

        // With the benchmark present it still measures the difference: stock +10%, index +4%.
        List<Map<String, Object>> nifty = new ArrayList<>(flat(200, 100, 1000));
        nifty.set(nifty.size() - 1, candle(104, 105, 103, 1000));
        assertThat(UniverseExpansionService.relativeStrength(stock, nifty, 126))
                .isCloseTo(6.0, org.assertj.core.data.Offset.offset(0.001));
    }

    // ---- Base detection ----

    @Test
    @DisplayName("Higher lows require each third's low to exceed the previous third's")
    void higherLowsDetectsAscendingBase() {
        List<Map<String, Object>> rising = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            double base = 100 + i * 0.5;            // steadily ascending lows
            rising.add(candle(base + 2, base + 3, base, 1000));
        }
        assertThat(UniverseExpansionService.hasHigherLows(rising)).isTrue();
    }

    @Test
    @DisplayName("A falling knife is not a base")
    void higherLowsRejectsDowntrend() {
        List<Map<String, Object>> falling = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            double base = 200 - i * 0.5;
            falling.add(candle(base + 2, base + 3, base, 1000));
        }
        assertThat(UniverseExpansionService.hasHigherLows(falling)).isFalse();
    }

    @Test
    @DisplayName("Higher lows is false — not an exception — when history is too short")
    void higherLowsShortHistory() {
        assertThat(UniverseExpansionService.hasHigherLows(flat(50, 100, 1000))).isFalse();
        assertThat(UniverseExpansionService.hasHigherLows(null)).isFalse();
    }

    // ---- Config defaults ----

    @Test
    @DisplayName("Expansion ships OFF: discovery runs, but nothing reaches the screening universe")
    void expansionDefaultsToObservationMode() {
        UniverseConfig c = new UniverseConfig();
        // The funnel must be observable before it is allowed to change what gets screened.
        assertThat(c.isEnabled()).isFalse();
        // ...but the scan itself still runs, otherwise there would be nothing to observe.
        assertThat(c.isScanEnabled()).isTrue();
    }

    @Test
    @DisplayName("Promotion bar sits above the retirement floor, so a symbol cannot be both")
    void thresholdsDoNotOverlap() {
        UniverseConfig c = new UniverseConfig();
        assertThat(c.getPromotionMinComposite()).isGreaterThan(c.getRetirementComposite());
        assertThat(c.getRetirementConsecutiveRuns()).isGreaterThan(1);
        assertThat(c.getMaxActiveSymbols()).isPositive();
        // The liquidity floor must not be looser than the MODERATE tier, or the funnel
        // could promote stocks the buyability guard calls THIN.
        assertThat(c.getMinAdv20d())
                .isGreaterThanOrEqualTo(com.example.trading.multibagger.MultibaggerScore.MODERATE_MIN);
    }

    @Test
    @DisplayName("Recent-IPO window is wider than the maturity gate, or the tracker sees nothing")
    void ipoWindowsAreConsistent() {
        UniverseConfig c = new UniverseConfig();
        assertThat(c.getRecentIpoMonths()).isGreaterThan(c.getMinMonthsSinceListing());
    }
}
