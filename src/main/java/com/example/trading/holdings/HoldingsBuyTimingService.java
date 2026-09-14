package com.example.trading.holdings;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import com.example.trading.watchlist.BuyTimingVerdict;
import com.example.trading.watchlist.WatchlistConfig;
import com.example.trading.watchlist.WatchlistItemView;
import com.example.trading.watchlist.WatchlistTrackingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Is it still a good time to buy <em>more</em>?" for stocks already owned (SPEC §6.5).
 *
 * <p>Same question the watchlist answers, same rule table ({@link BuyTimingVerdict}), same six
 * words — because two engines answering one question in one vocabulary is a defect regardless of
 * which is right (Gotcha 81, B-062). What differs is only the row the inputs are read from: a
 * holding carries its own daily RSI-14, EMA-50 and trend from the 10:30/15:15 holdings analysis,
 * and its "return since you added it" is the actual P&amp;L on the position rather than the move
 * since a watch date.
 *
 * <h2>Three things worth not getting wrong</h2>
 * <ol>
 *   <li><b>A tracked stock defers to the watchlist.</b> If the investor also watches it, the
 *       watchlist verdict wins here exactly as it does on the screener — one stock, one answer,
 *       on whichever surface it is read.</li>
 *   <li><b>Quality and score history are resolved across exchange prefixes</b>
 *       ({@link SymbolVariants}). Screening runs on NSE symbols; most holdings are BSE-prefixed.
 *       Without this the column would read "never screened" for two-thirds of the portfolio —
 *       an unmeasured marker standing in for data that exists.</li>
 *   <li><b>A SELL recommendation is not a buy signal with a minus sign.</b> The holdings
 *       vocabulary (STRONG_BUY…STRONG_SELL) is passed through untranslated and the rule table
 *       understands it; mapping SELL onto some nearest buy-signal would launder a sell into a
 *       hold.</li>
 * </ol>
 *
 * <p>DB-only: repository reads plus pure arithmetic, no Kite or NSE call, so it is safe on a page
 * load (Gotcha 17/39). The verdict is computed on read and never persisted — a stored copy can
 * disagree with the row it describes after the next analysis (same rule as the screener column).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HoldingsBuyTimingService {

    private final HoldingsRepository holdingsRepository;
    private final MultibaggerScoreRepository multibaggerScoreRepository;
    private final HoldingsDecayService decayService;
    private final WatchlistTrackingService watchlistTrackingService;
    private final WatchlistConfig watchlistConfig;

    /** Where the verdict on a holding came from. */
    public enum Source { WATCHLIST, HOLDING }

    public record HoldingBuyTiming(
            String symbol,
            String verdict,
            String reason,
            Source source,
            Integer qualityScore,
            /** The screening symbol the quality score and score trend were read from (B-061). */
            String qualityFrom,
            List<String> notMeasured,
            /**
             * What the Signal column should display, after a quality problem has been allowed to
             * veto a buy (SPEC §6.6). Display-only: {@code holdings.recommendation} is untouched.
             */
            String displaySignal,
            /** Why the displayed signal differs from the stored one; null when it does not. */
            String signalNote) {}

    /** Verdict per active holding, keyed by the holding's own symbol. DB-only. */
    public Map<String, HoldingBuyTiming> buildAll() {
        Map<String, WatchlistItemView> tracked = trackedVerdicts();
        Map<String, HoldingBuyTiming> out = new LinkedHashMap<>();
        for (HoldingsEntity h : holdingsRepository.findActive()) {
            if (h.getSymbol() == null) continue;
            try {
                out.put(h.getSymbol(), evaluate(h, tracked));
            } catch (Exception e) {
                log.warn("Buy-timing failed for {} — the column will read 'not measured' for it, "
                        + "which must not be mistaken for a considered 'no': {}", h.getSymbol(), e.getMessage());
            }
        }
        return out;
    }

    /** Verdict for one holding. */
    public HoldingBuyTiming evaluate(HoldingsEntity h, Map<String, WatchlistItemView> tracked) {
        // 1. Already on the watchlist? That surface has the investor's own add-price context and
        //    a live technical read; deferring keeps one answer per stock (Gotcha 81).
        WatchlistItemView watched = findTracked(tracked, h.getSymbol());
        if (watched != null && watched.verdict() != null) {
            return withSignal(h, h.getSymbol(), watched.verdict().name(), watched.verdictReason(),
                    Source.WATCHLIST, watched.qualityScore(), watched.symbol(), List.of());
        }

        // 2. Quality + red flags from the latest screening row, found under any exchange prefix.
        MultibaggerScoreEntity score = latestScore(h.getSymbol());
        String qualityFrom = score != null ? score.getSymbol() : null;

        // 3. Thesis drift, read off the same resolved symbol so the column and the drift table
        //    can never disagree about the same stock.
        HoldingsDecayService.DecayAlert decay = quietDecay(h.getSymbol(), h.getPnlPercent());

        BuyTimingVerdict.Result r = BuyTimingVerdict.evaluate(new BuyTimingVerdict.Input(
                score != null ? score.getCompositeScore() : null,
                h.getRecommendation(),
                h.getAnalysisNotes(),
                h.getRsi14(),
                h.getCurrentPrice() > 0 ? h.getCurrentPrice() : null,   // 0.0 is "no price", never a quote (Gotcha 22)
                h.getEma50(),
                h.getTrendDirection(),
                decay != null && decay.getVerdict() != null ? decay.getVerdict().name() : null,
                decay != null ? relativeOrRaw(decay) : null,
                score != null ? score.getForensicFlags() : null,
                score != null ? score.getFinancialQualityVerdict() : null,
                score != null ? score.getLiquidityTier() : null,
                score != null ? score.getDcfVerdict() : null,
                // For a holding, "return since you added it" is the P&L on the position itself.
                h.getPnlPercent()),
                BuyTimingVerdict.Thresholds.from(watchlistConfig.getVerdict()));

        return withSignal(h, h.getSymbol(), r.verdict().name(), r.reason(), Source.HOLDING,
                score != null ? score.getCompositeScore() : null, qualityFrom, r.notMeasured());
    }

    /**
     * Builds the row, letting a quality problem veto a buy signal (SPEC §6.6).
     *
     * <p>Every construction goes through here so the two columns cannot drift apart again: the
     * portfolio table shows Signal and this verdict side by side, and on 2026-09-02 they disagreed
     * on 9 of 32 holdings because nothing reconciled them. The stored recommendation is only read
     * — never rewritten (Gotcha 69).
     */
    private HoldingBuyTiming withSignal(HoldingsEntity h, String symbol, String verdict,
                                        String reason, Source source, Integer quality,
                                        String qualityFrom, List<String> notMeasured) {
        SignalReconciliation.Result signal =
                SignalReconciliation.reconcile(h.getRecommendation(), verdict, reason);
        return new HoldingBuyTiming(symbol, verdict, reason, source, quality, qualityFrom,
                notMeasured, signal.displaySignal(), signal.note());
    }

    /** B-064: the move net of the universe shift where measured, else the raw move. */
    private static Integer relativeOrRaw(HoldingsDecayService.DecayAlert d) {
        return d.getRelativeDelta30d() != null ? d.getRelativeDelta30d() : d.getDelta30d();
    }

    /** Latest screening row for the stock under any exchange prefix, or null if never screened. */
    private MultibaggerScoreEntity latestScore(String symbol) {
        for (String candidate : SymbolVariants.candidates(symbol)) {
            List<MultibaggerScoreEntity> history =
                    multibaggerScoreRepository.findHistoryBySymbol(candidate); // DESC by date
            if (!history.isEmpty()) return history.get(0);
        }
        return null;
    }

    private HoldingsDecayService.DecayAlert quietDecay(String symbol, Double pnlPercent) {
        try {
            return decayService.detectDecayForSymbol(symbol, pnlPercent);
        } catch (Exception e) {
            log.debug("Decay read failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /** Watchlist rows by symbol. Empty on any failure — the holdings page must still render. */
    private Map<String, WatchlistItemView> trackedVerdicts() {
        Map<String, WatchlistItemView> map = new LinkedHashMap<>();
        try {
            for (WatchlistItemView v : watchlistTrackingService.buildView(false)) {
                if (v.symbol() != null && v.verdict() != null) {
                    map.put(v.symbol().toUpperCase(Locale.ROOT), v);
                }
            }
        } catch (Exception ex) {
            log.warn("Holdings buy-timing: watchlist verdicts unavailable, falling back to the "
                    + "holding's own row — a tracked stock may read differently here than on the "
                    + "watchlist page: {}", ex.getMessage());
        }
        return map;
    }

    /** Match a holding to a watched row across exchange prefixes. */
    private WatchlistItemView findTracked(Map<String, WatchlistItemView> tracked, String symbol) {
        for (String candidate : SymbolVariants.candidates(symbol)) {
            WatchlistItemView v = tracked.get(candidate.toUpperCase(Locale.ROOT));
            if (v != null) return v;
        }
        return null;
    }
}
