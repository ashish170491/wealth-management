package com.example.trading.holdings;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import com.example.trading.watchlist.BuyTimingVerdict;
import com.example.trading.watchlist.BuyTimingVerdict.Verdict;
import com.example.trading.watchlist.WatchlistConfig;
import com.example.trading.watchlist.WatchlistTrackingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Is it still a good time to buy more?" on a holding (SPEC §6.5).
 *
 * <p>The three rules worth pinning are the ones that would fail silently: a SELL must never come
 * back out as ACCUMULATE, a BSE-prefixed holding must find its NSE screening history (B-061), and
 * a stock also on the watchlist must be given the watchlist's answer rather than a second opinion
 * in the same six words (Gotcha 81).
 */
class HoldingsBuyTimingTest {

    private final HoldingsRepository holdings = mock(HoldingsRepository.class);
    private final MultibaggerScoreRepository scores = mock(MultibaggerScoreRepository.class);
    private final HoldingsDecayService decay = mock(HoldingsDecayService.class);
    private final WatchlistTrackingService watchlist = mock(WatchlistTrackingService.class);

    private final HoldingsBuyTimingService service =
            new HoldingsBuyTimingService(holdings, scores, decay, watchlist, config());

    @Test
    @DisplayName("a SELL recommendation is HOLD_OFF and says so — never laundered into ACCUMULATE")
    void sellIsNeverAnAdd() {
        // Everything else about the stock is attractive: a good composite and a calm chart. Only
        // the recommendation says no, and that alone must decide it.
        stubScore("NSE:X", 82);
        HoldingsEntity h = holding("NSE:X", "SELL");

        var t = service.evaluate(h, Map.of());

        assertThat(t.verdict()).isEqualTo(Verdict.HOLD_OFF.name());
        assertThat(t.reason()).contains("not a time to add");
        assertThat(t.source()).isEqualTo(HoldingsBuyTimingService.Source.HOLDING);
    }

    @Test
    @DisplayName("STRONG_SELL likewise, and a plain BUY on a good business is BUY_NOW")
    void signalVocabularyIsUnderstoodBothWays() {
        stubScore("NSE:X", 82);
        assertThat(service.evaluate(holding("NSE:X", "STRONG_SELL"), Map.of()).verdict())
                .isEqualTo(Verdict.HOLD_OFF.name());
        assertThat(service.evaluate(holding("NSE:X", "BUY"), Map.of()).verdict())
                .isEqualTo(Verdict.BUY_NOW.name());
    }

    @Test
    @DisplayName("a BSE-held position is scored off its NSE screening history, and says where from")
    void resolvesQualityAcrossExchangePrefixes() {
        when(scores.findHistoryBySymbol("BSE:X")).thenReturn(List.of());
        stubScore("NSE:X", 82);

        var t = service.evaluate(holding("BSE:X", "BUY"), Map.of());

        assertThat(t.qualityScore()).isEqualTo(82);
        assertThat(t.qualityFrom()).isEqualTo("NSE:X");
        assertThat(t.verdict()).isEqualTo(Verdict.BUY_NOW.name());
    }

    @Test
    @DisplayName("never screened under any prefix: quality is null, not zero, and is listed as unmeasured")
    void unscreenedIsUnmeasuredNotZero() {
        when(scores.findHistoryBySymbol(anyString())).thenReturn(List.of());

        var t = service.evaluate(holding("BSE:GHOST", "BUY"), Map.of());

        assertThat(t.qualityScore()).isNull();
        assertThat(t.qualityFrom()).isNull();
        // Rule 11: timing known, quality never measured -> small tranches, and it must NOT be
        // scored as if the business had been measured and found average.
        assertThat(t.verdict()).isEqualTo(Verdict.ACCUMULATE.name());
        assertThat(t.notMeasured()).anyMatch(m -> m.contains("quality score"));
    }

    @Test
    @DisplayName("a tracked stock gets the watchlist's verdict verbatim, under either exchange prefix")
    void defersToTheWatchlist() {
        stubScore("NSE:X", 82);   // would say BUY_NOW on its own
        var tracked = Map.of("NSE:X", view("NSE:X", Verdict.WAIT_FOR_PULLBACK, "RSI 78 is overbought", 82));

        var t = service.evaluate(holding("BSE:X", "BUY"), tracked);

        assertThat(t.verdict()).isEqualTo(Verdict.WAIT_FOR_PULLBACK.name());
        assertThat(t.reason()).isEqualTo("RSI 78 is overbought");
        assertThat(t.source()).isEqualTo(HoldingsBuyTimingService.Source.WATCHLIST);
    }

    @Test
    @DisplayName("a price of 0.0 is 'no price', so the stretch rule cannot fire on it")
    void zeroPriceIsNotAPrice() {
        stubScore("NSE:X", 82);
        HoldingsEntity h = holding("NSE:X", "HOLD");
        h.setCurrentPrice(0.0);         // Gotcha 22 — every failure path returns 0.0, never null
        h.setEma50(100.0);

        var t = service.evaluate(h, Map.of());

        // Rule 10 compares price against EMA-50; with no price it must not fire at all.
        assertThat(t.verdict()).isEqualTo(Verdict.ACCUMULATE.name());
        assertThat(t.reason()).doesNotContain("above its 50-day average");
    }

    // ---- fixtures ----

    private void stubScore(String symbol, int composite) {
        MultibaggerScoreEntity e = new MultibaggerScoreEntity();
        e.setSymbol(symbol);
        e.setCompositeScore(composite);
        when(scores.findHistoryBySymbol(symbol)).thenReturn(List.of(e));
    }

    private HoldingsEntity holding(String symbol, String recommendation) {
        HoldingsEntity h = new HoldingsEntity();
        h.setSymbol(symbol);
        h.setRecommendation(recommendation);
        h.setCurrentPrice(100.0);
        h.setEma50(98.0);
        h.setRsi14(52.0);
        h.setTrendDirection("SIDEWAYS");
        h.setPnlPercent(4.0);
        return h;
    }

    private com.example.trading.watchlist.WatchlistItemView view(
            String symbol, Verdict verdict, String reason, Integer quality) {
        com.example.trading.watchlist.WatchlistItemView v =
                mock(com.example.trading.watchlist.WatchlistItemView.class);
        when(v.symbol()).thenReturn(symbol);
        when(v.verdict()).thenReturn(verdict);
        when(v.verdictReason()).thenReturn(reason);
        when(v.qualityScore()).thenReturn(quality);
        return v;
    }

    private WatchlistConfig config() {
        return new WatchlistConfig();   // VerdictConfig defaults are the shared thresholds
    }

    /** Guards the assumption every case above rests on: thresholds match the shared defaults. */
    @Test
    @DisplayName("the holdings surface uses the same thresholds as the watchlist")
    void sharesTheWatchlistThresholds() {
        BuyTimingVerdict.Thresholds t = BuyTimingVerdict.Thresholds.from(config().getVerdict());
        assertThat(t).isEqualTo(BuyTimingVerdict.Thresholds.defaults());
    }

    @Test
    @DisplayName("decay is read for the holding and a DECAYING thesis holds off the add")
    void decayFeedsTheVerdict() {
        stubScore("NSE:X", 82);
        HoldingsDecayService.DecayAlert alert = new HoldingsDecayService.DecayAlert();
        alert.setVerdict(HoldingsDecayService.Verdict.DECAYING);
        alert.setDelta30d(-20);
        alert.setRelativeDelta30d(-12);      // B-064: the relative move is what the verdict quotes
        when(decay.detectDecayForSymbol(any(), any())).thenReturn(alert);

        var t = service.evaluate(holding("NSE:X", "BUY"), Map.of());

        assertThat(t.verdict()).isEqualTo(Verdict.HOLD_OFF.name());
        assertThat(t.reason()).contains("-12").contains("against the rest of the universe");
    }
}
