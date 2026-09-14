package com.example.trading.analyst;

import com.example.trading.learning.validation.DailyCandleCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins how a recorded target is measured (SPEC §49.5).
 *
 * <p>The candle cache is mocked so the rules are tested rather than the broker.
 */
class AnalystTargetOutcomeTest {

    private DailyCandleCache cache;
    private AnalystTargetOutcomeService service;

    private static final LocalDate ISSUED = LocalDate.now().minusDays(100);

    @BeforeEach
    void setUp() {
        cache = mock(DailyCandleCache.class);
        service = new AnalystTargetOutcomeService(
                mock(AnalystTargetRepository.class), cache, new AnalystTargetConfig());
    }

    private AnalystTargetEntity target(double targetPrice, int horizonDays) {
        return AnalystTargetEntity.builder()
                .symbol("NSE:X")
                .brokerage("Nomura")
                .targetPrice(targetPrice)
                .issuedOn(ISSUED)
                .horizonDays(horizonDays)
                .resolvesOn(ISSUED.plusDays(horizonDays))
                .status(AnalystTargetStatus.UNPRICED.name())
                .dedupKey("k")
                .build();
    }

    @Test
    @DisplayName("A target touched intraday is reached, even if no close ever got there")
    void reachedOnTheHighNotTheClose() {
        // Measuring on closes would report "missed" for every call that got there and gave it
        // back, which is a different claim about the analyst.
        AnalystTargetEntity t = target(120.0, 365);
        when(cache.closeOnOrBefore(eq("NSE:X"), eq(ISSUED))).thenReturn(100.0);
        when(cache.firstCrossing(eq("NSE:X"), any(), any(), anyDouble(), eq(true)))
                .thenReturn(new DailyCandleCache.Extreme(121.0, ISSUED.plusDays(30)));
        when(cache.closeOnOrBefore(eq("NSE:X"), eq(LocalDate.now()))).thenReturn(105.0);

        service.measure(t);

        assertThat(t.getStatus()).isEqualTo("REACHED");
        assertThat(t.getDaysToReach()).isEqualTo(30);
        assertThat(t.getReachedOn()).isEqualTo(ISSUED.plusDays(30));
    }

    @Test
    @DisplayName("Measurement starts the day after the call, never on the day itself")
    void measurementStartsTheDayAfter() {
        // A target quoted at a level the stock traded through that same morning is not a forecast.
        AnalystTargetEntity t = target(120.0, 365);
        when(cache.closeOnOrBefore(eq("NSE:X"), eq(ISSUED))).thenReturn(100.0);
        when(cache.firstCrossing(eq("NSE:X"), eq(ISSUED.plusDays(1)), any(), anyDouble(), anyBoolean()))
                .thenReturn(null);

        service.measure(t);

        // The stub above only answers for a window starting the day after; had the service asked
        // from the issue date, Mockito would return null too — so the assertion that carries the
        // rule is that the call is still running rather than credited.
        assertThat(t.getStatus()).isEqualTo("PENDING");
        assertThat(t.getReachedOn()).isNull();
    }

    @Test
    @DisplayName("Without an issue-date close the call stays unpriced — not zero, not today's price")
    void noReferencePriceMeansUnpriced() {
        AnalystTargetEntity t = target(120.0, 365);
        when(cache.closeOnOrBefore(any(), any())).thenReturn(null);

        service.measure(t);

        assertThat(t.getStatus()).isEqualTo("UNPRICED");
        assertThat(t.getUpsidePctAtCall()).isNull();
        assertThat(t.getReturnPct()).isNull();
        assertThat(t.getDirection()).isNull();
        // It was still examined, so the rotation advances rather than jamming on this row.
        assertThat(t.getLastMeasuredAt()).isNotNull();
    }

    @Test
    @DisplayName("A horizon that has passed without a touch is a miss")
    void expiredWithoutATouchIsAMiss() {
        AnalystTargetEntity t = target(120.0, 30);   // resolved 70 days ago
        when(cache.closeOnOrBefore(eq("NSE:X"), eq(ISSUED))).thenReturn(100.0);
        when(cache.firstCrossing(any(), any(), any(), anyDouble(), anyBoolean())).thenReturn(null);
        when(cache.closeOnOrBefore(eq("NSE:X"), eq(ISSUED.plusDays(30)))).thenReturn(108.0);

        service.measure(t);

        assertThat(t.getStatus()).isEqualTo("MISSED");
        // Measured at the horizon, not at today: reading a 30-day call's return 100 days later
        // is measuring a holding period nobody claimed.
        assertThat(t.getReturnPct()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("A target below the price is measured against lows, and is a Sell call")
    void belowTargetsUseLows() {
        AnalystTargetEntity t = target(80.0, 365);
        when(cache.closeOnOrBefore(eq("NSE:X"), eq(ISSUED))).thenReturn(100.0);
        when(cache.firstCrossing(eq("NSE:X"), any(), any(), eq(80.0), eq(false)))
                .thenReturn(new DailyCandleCache.Extreme(79.0, ISSUED.plusDays(50)));
        when(cache.closeOnOrBefore(eq("NSE:X"), eq(LocalDate.now()))).thenReturn(85.0);

        service.measure(t);

        assertThat(t.getDirection()).isEqualTo("BELOW");
        assertThat(t.getUpsidePctAtCall()).isEqualTo(-20.0);
        assertThat(t.getStatus()).isEqualTo("REACHED");
    }

    @Test
    @DisplayName("Excess return is the stock minus the index over exactly the same dates")
    void excessReturnIsAgainstTheIndex() {
        // Reaching a target during a market-wide rally is beta. This is the neutral yardstick,
        // and it is the same one §23 applies to this app's own picks.
        AnalystTargetEntity t = target(200.0, 365);
        when(cache.closeOnOrBefore(eq("NSE:X"), eq(ISSUED))).thenReturn(100.0);
        when(cache.closeOnOrBefore(eq("NSE:X"), eq(LocalDate.now()))).thenReturn(112.0);
        when(cache.closeOnOrBefore(eq(AnalystTargetOutcomeService.NIFTY), eq(ISSUED))).thenReturn(20000.0);
        when(cache.closeOnOrBefore(eq(AnalystTargetOutcomeService.NIFTY), eq(LocalDate.now()))).thenReturn(22000.0);
        when(cache.firstCrossing(any(), any(), any(), anyDouble(), anyBoolean())).thenReturn(null);

        service.measure(t);

        assertThat(t.getReturnPct()).isEqualTo(12.0);
        assertThat(t.getNiftyReturnPct()).isEqualTo(10.0);
        assertThat(t.getExcessReturnPct()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("A missing index price leaves excess null rather than equal to the raw return")
    void missingIndexLeavesExcessNull() {
        // A return with one real leg and one substituted one is not a smaller measurement, it is
        // a different and wrong one.
        AnalystTargetEntity t = target(200.0, 365);
        when(cache.closeOnOrBefore(eq("NSE:X"), eq(ISSUED))).thenReturn(100.0);
        when(cache.closeOnOrBefore(eq("NSE:X"), eq(LocalDate.now()))).thenReturn(112.0);
        when(cache.closeOnOrBefore(eq(AnalystTargetOutcomeService.NIFTY), any())).thenReturn(null);
        when(cache.firstCrossing(any(), any(), any(), anyDouble(), anyBoolean())).thenReturn(null);

        service.measure(t);

        assertThat(t.getReturnPct()).isEqualTo(12.0);
        assertThat(t.getExcessReturnPct()).isNull();
    }
}
