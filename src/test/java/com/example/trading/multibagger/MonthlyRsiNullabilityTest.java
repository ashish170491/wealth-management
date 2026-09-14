package com.example.trading.multibagger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-060 — an RSI that cannot be computed must be null, never the 50.0 neutral default.
 *
 * <p>Monthly RSI read exactly {@code 50.0} on all 294 rows of the 2026-08-27 screening. Not a
 * coincidence and not thin data for some stocks: RSI-14 on monthly bars needs 15 bars ≈ 330
 * trading days, while {@code history-days: 365} is 365 <em>calendar</em> days ≈ 276 candles ≈ 12
 * bars. It was uncomputable for every stock in the universe, and the primitive
 * {@code calculateRSI} returned its neutral default each time — a constant wearing the shape of a
 * measurement, the same zero-variance signature as the Institutional-Interest bug that ran three
 * months undetected.
 */
class MonthlyRsiNullabilityTest {

    /** A rising series — enough shape that a computable RSI would not land on 50 by accident. */
    private static List<Map<String, Object>> bars(int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        double close = 100.0;
        for (int i = 0; i < n; i++) {
            close *= (i % 3 == 0) ? 0.98 : 1.03;
            Map<String, Object> b = new HashMap<>();
            b.put("close", close);
            out.add(b);
        }
        return out;
    }

    @Test
    @DisplayName("Too few bars returns null, not 50.0")
    void insufficientBarsIsNull() {
        assertThat(MultibaggerScreenerService.calculateRsiOrNull(bars(12), 14)).isNull();
    }

    @Test
    @DisplayName("Exactly period bars is still not enough — RSI-14 needs 15")
    void boundaryIsPeriodPlusOne() {
        assertThat(MultibaggerScreenerService.calculateRsiOrNull(bars(14), 14)).isNull();
        assertThat(MultibaggerScreenerService.calculateRsiOrNull(bars(15), 14)).isNotNull();
    }

    @Test
    @DisplayName("A null or empty series is null, not a crash and not 50.0")
    void nullSeriesIsNull() {
        assertThat(MultibaggerScreenerService.calculateRsiOrNull(null, 14)).isNull();
        assertThat(MultibaggerScreenerService.calculateRsiOrNull(List.of(), 14)).isNull();
    }

    @Test
    @DisplayName("With enough bars it computes a real value, and agrees with the primitive")
    void sufficientBarsComputes() {
        Double v = MultibaggerScreenerService.calculateRsiOrNull(bars(40), 14);
        assertThat(v).isNotNull();
        assertThat(v).isBetween(0.0, 100.0);
        assertThat(v).isEqualTo(MultibaggerScreenerService.calculateRSI(bars(40), 14));
    }

    /**
     * The arithmetic that made this structural. If the history window is ever widened, this test
     * documents what it would have to reach for monthly RSI to become measurable at all.
     */
    @Test
    @DisplayName("365 calendar days cannot yield 15 monthly bars — the window is the constraint")
    void theWindowIsTheConstraint() {
        int tradingDaysIn365Calendar = (int) Math.round(365 * 252.0 / 365.0);
        int monthlyBars = tradingDaysIn365Calendar / 22;
        assertThat(monthlyBars).isLessThan(15);
        assertThat(MultibaggerScreenerService.calculateRsiOrNull(bars(monthlyBars), 14)).isNull();
    }
}
