package com.example.trading.watchlist;

import com.example.trading.holdings.HoldingsDecayService;
import com.example.trading.marketdata.MarketDataService;
import com.example.trading.multibagger.MultibaggerScreenerService;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.multibagger.SuggestedEntry;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Watchlist tracking (SPEC §37): membership writes, the DB-only read model behind the
 * dashboard, and the daily snapshot.
 *
 * <p>{@link #buildView} is the page-load path and touches nothing but Postgres. The three
 * broker-calling operations — {@link #add}, {@link #refresh}, and {@link #writeDailySnapshots}
 * — are reached only from a button or the 15:00 scheduler run.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WatchlistTrackingService {

    static final String NIFTY = "NSE:NIFTY 50";
    private static final int SERIES_DAYS = 90;
    private static final int BACKFILL_DAYS = 90;

    private final WatchlistRepository watchlistRepository;
    private final WatchlistSnapshotRepository snapshotRepository;
    private final MultibaggerScoreRepository scoreRepository;
    private final HoldingsRepository holdingsRepository;
    private final HoldingsDecayService decayService;
    private final WatchlistAnalysisService analysisService;
    private final MarketDataService marketDataService;
    private final MultibaggerScreenerService screenerService;
    /** SPEC 49.15. DB-only: the ledger the 13:20 pass already wrote, never a live fetch. */
    private final com.example.trading.analyst.AnalystTargetViewService analystTargetViewService;
    /** SPEC 41.5 - the one DB-backed reader of the compounding lens, shared with three other screens. */
    private final com.example.trading.multibagger.CompoundingLensService compoundingLensService;

    /** SPEC 48.8 - the one DB-backed reader of the exposure map, shared with every other screen. */
    private final com.example.trading.macro.MacroExposureService macroExposureService;
    private final WatchlistConfig config;

    // ---------------------------------------------------------------- errors

    /** Refusals that carry their reason to the caller (B-049 pattern). */
    public static class WatchlistException extends RuntimeException {
        private final int status;
        public WatchlistException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }

    // ------------------------------------------------------------- read model

    /** DB-only. Active rows oldest-first; removed rows appended when asked. */
    public List<WatchlistItemView> buildView(boolean includeRemoved) {
        List<WatchlistEntity> rows = new ArrayList<>(watchlistRepository.findActiveOrderByAddedOn());
        if (includeRemoved) rows.addAll(watchlistRepository.findRemoved());
        if (rows.isEmpty()) return List.of();

        LocalDate from = LocalDate.now().minusDays(SERIES_DAYS);
        Map<String, List<WatchlistSnapshotEntity>> seriesBySymbol = snapshotRepository
                .findBySnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(from).stream()
                .collect(Collectors.groupingBy(WatchlistSnapshotEntity::getSymbol));

        WatchlistSnapshotEntity niftyRow = snapshotRepository.findWithNiftyDesc().stream().findFirst().orElse(null);
        Double niftyNow = niftyRow != null ? niftyRow.getNiftyClose() : null;
        LocalDate niftyAsOf = niftyRow != null ? niftyRow.getSnapshotDate() : null;

        Set<String> held = holdingsRepository.findActive().stream()
                .map(h -> h.getSymbol()).collect(Collectors.toSet());

        // One query for the whole table (SPEC 41.5). Per-row lookups would cost one query per
        // symbol spelling on a page that is contractually one DB round of reads.
        Map<String, com.example.trading.multibagger.CompoundingLensService.Reading> compounding;
        try {
            compounding = compoundingLensService.forSymbols(
                    rows.stream().map(WatchlistEntity::getSymbol).toList());
        } catch (Exception e) {
            // WARN and name what the emptiness will be mistaken for (B-054's rule): every row
            // reads "not measured", which must not be read as a poor verdict.
            log.warn("Compounding lens failed - every watchlist row will read 'not measured' in "
                    + "the quality column rather than carrying a verdict: {}", e.getMessage());
            compounding = Map.of();
        }

        // Same shape, same reason: one query for the table, never one per row.
        Map<String, com.example.trading.macro.MacroExposureService.Reading> macro;
        try {
            macro = macroExposureService.forSymbols(
                    rows.stream().map(WatchlistEntity::getSymbol).toList());
        } catch (Exception e) {
            log.warn("Macro exposure failed - every watchlist row will read 'not measured' in the "
                    + "macro column rather than carrying a reading: {}", e.getMessage());
            macro = Map.of();
        }

        // Same shape again: who else is quoting a target on these stocks (SPEC 49.15). Measured
        // on the live watchlist, 9 of 23 carry a live target, 6 have been covered and gone quiet
        // and 8 have nothing on file - all three states, which is why the column is worth drawing.
        Map<String, com.example.trading.analyst.AnalystTargetViewService.Coverage> analyst;
        try {
            analyst = analystTargetViewService.forSymbols(
                    rows.stream().map(WatchlistEntity::getSymbol).toList());
        } catch (Exception e) {
            // Name what the emptiness will be mistaken for (B-054). Every row then reads "not
            // measured", which must never be read as "no brokerage covers this" (SPEC 49.7).
            log.warn("Analyst coverage failed - every watchlist row will read 'not measured' in "
                    + "the analyst column, which must not be read as 'nobody covers it': {}",
                    e.getMessage());
            analyst = Map.of();
        }

        List<WatchlistItemView> out = new ArrayList<>(rows.size());
        for (WatchlistEntity w : rows) {
            try {
                out.add(toView(w, seriesBySymbol.getOrDefault(w.getSymbol(), List.of()), niftyNow, niftyAsOf,
                        held.contains(w.getSymbol()), compounding.get(w.getSymbol()),
                        macro.get(w.getSymbol()), analyst.get(w.getSymbol())));
            } catch (Exception e) {
                log.warn("Watchlist view failed for {} — row omitted, not defaulted: {}", w.getSymbol(), e.getMessage());
            }
        }
        return out;
    }

    public Optional<WatchlistItemView> viewFor(String symbol) {
        return buildView(true).stream().filter(v -> v.symbol().equals(symbol)).findFirst();
    }

    private WatchlistItemView toView(WatchlistEntity w, List<WatchlistSnapshotEntity> snaps,
                                     Double niftyNow, LocalDate niftyAsOf, boolean heldNow,
                                     com.example.trading.multibagger.CompoundingLensService.Reading compounding,
                                     com.example.trading.macro.MacroExposureService.Reading macro,
                                     com.example.trading.analyst.AnalystTargetViewService.Coverage analyst) {
        MultibaggerScoreEntity score = latestScore(w.getSymbol());
        HoldingsDecayService.DecayAlert decay = quietDecay(w.getSymbol());

        boolean active = w.isActiveRow();
        Double currentPrice = w.getCurrentPrice();
        Double sinceAdd = active
                ? WatchlistReturnMath.pctReturn(w.getPriceAtAdd(), currentPrice)
                : null;
        Double whileWatched = !active
                ? WatchlistReturnMath.pctReturn(w.getPriceAtAdd(), w.getPriceAtRemoval())
                : null;
        Double niftyRet = active ? WatchlistReturnMath.pctReturn(w.getNiftyAtAdd(), niftyNow) : null;
        Double excess = WatchlistReturnMath.excess(sinceAdd, niftyRet);

        Integer quality = score != null ? score.getCompositeScore() : null;
        String sector = score != null && notBlank(score.getIndustry()) ? score.getIndustry()
                : notBlank(w.getIndustry()) ? w.getIndustry() : null;

        BuyTimingVerdict.Result verdict = BuyTimingVerdict.evaluate(new BuyTimingVerdict.Input(
                quality, w.getEntrySignal(), w.getSignalReason(), w.getRsi14(), currentPrice, w.getEma50(),
                w.getTrendDirection(),
                decay != null && decay.getVerdict() != null ? decay.getVerdict().name() : null,
                decay != null ? relativeOrRaw(decay) : null,
                // A null forensic column means the screen never ran (needs ≥3 FYs, Gotcha 44) — keep it null;
                // only a screened-and-clean row carries an empty string, and the entity stores exactly that.
                score != null ? score.getForensicFlags() : null,
                score != null ? score.getFinancialQualityVerdict() : null,
                score != null ? score.getLiquidityTier() : null,
                score != null ? score.getDcfVerdict() : null,
                sinceAdd), BuyTimingVerdict.Thresholds.from(config.getVerdict()));

        List<WatchlistItemView.SeriesPoint> series = snaps.stream()
                .filter(s -> s.getClose() != null)
                .map(s -> new WatchlistItemView.SeriesPoint(s.getSnapshotDate(), s.getClose()))
                .toList();

        Long days = w.getAddedOn() != null
                ? ChronoUnit.DAYS.between(w.getAddedOn(), active ? LocalDate.now()
                        : (w.getRemovedOn() != null ? w.getRemovedOn() : LocalDate.now()))
                : null;

        // One entry rule across the app (SPEC §12.12). The 50-day average is passed because it is
        // the level this row's own verdict reason cites ("stretched above its average") - without
        // it the ladder would quote a level answering a different question than the words beside
        // it, which was the second half of B-068.
        SuggestedEntry.Result entry = SuggestedEntry.compute(
                verdict.verdict(), w.getCurrentPrice(), w.getNearestSupport(),
                w.getAtr14(), w.getEma50());

        var themeTags = com.example.trading.universe.theme.UniverseThemes.tagsFor(w.getSymbol());
        return new WatchlistItemView(
                w.getSymbol(), w.getTradingSymbol(), active, w.getSource(), w.getAddedNote(), w.getAddedOn(), days,
                heldNow || Boolean.TRUE.equals(w.getInHoldings()),
                screenerService.isInScreeningUniverse(w.getSymbol()),
                w.getPriceAtAdd(), currentPrice, w.getDayChangePercent(), sinceAdd,
                w.getNiftyAtAdd(), niftyNow, niftyAsOf, niftyRet, excess,
                sector,
                quality, score != null ? score.getGrade() : null, score != null ? score.getVerdict() : null,
                score != null ? score.getScreeningDate() : null,
                // Null throughout when the stock was never screened. The UI renders that as the
                // "not measured" marker; it must never become a NO verdict (Gotcha 21, 44).
                compoundingVerdict(compounding),
                compounding != null && compounding.result() != null ? compounding.result().reason() : null,
                compounding != null && compounding.result() != null ? compounding.result().passed() : null,
                compounding != null && compounding.result() != null ? compounding.result().applicable() : null,
                compounding != null && compounding.result() != null
                        ? compounding.result().yearsOfAccounts() : null,
                compounding != null ? compounding.screeningDate() : null,
                w.getAdhocQualityScore(), w.getAdhocQualityAt(),
                w.getOverallScore(), w.getEntrySignal(), w.getSignalReason(), w.getSignalConfidence(), w.getRsi14(),
                w.getTrendDirection(), w.getEma20(), w.getEma50(),
                w.getSuggestedEntry(), w.getSuggestedStopLoss(), w.getSuggestedTarget1(),
                w.getNearestSupport(), w.getNearestResistance(),
                decay != null && decay.getVerdict() != null ? decay.getVerdict().name() : null,
                decay != null ? relativeOrRaw(decay) : null,
                score != null ? score.getFinancialQualityVerdict() : null,
                score != null ? score.getForensicFlags() : null,
                score != null ? score.getLiquidityTier() : null,
                score != null ? score.getDcfVerdict() : null,
                verdict.verdict(), verdict.reason(), verdict.qualityMeasured(), verdict.timingMeasured(),
                verdict.notMeasured(),
                w.getLastAnalyzedAt(), series,
                w.getRemovedOn(), w.getPriceAtRemoval(), whileWatched,
                entry.price(), entry.basis(), entry.reason(),
                entry.rungs(), entry.fallback(),
                macroVerdict(macro),
                macro != null && macro.result().strength() != null
                        ? macro.result().strength().name() : null,
                macro != null
                        ? macro.result().reasons().stream()
                                .map(com.example.trading.macro.MacroExposureRead.Reason::text).toList()
                        : java.util.List.of(),
                macro != null && !macro.result().reasons().isEmpty()
                        ? macro.result().reasons().size() : null,
                macro != null ? macro.symbolAnswered() : null,
                // Null throughout when the lookup did not run, so the cell draws the unmeasured
                // marker. A Coverage that IS present carries a counted zero, which reads as
                // "None on file" - a different fact, and the distinction B-117 was filed for.
                analyst != null ? analyst.houses() : null,
                analyst != null ? analyst.houseNames() : null,
                analyst != null ? analyst.openTargets() : null,
                analyst != null ? analyst.medianTarget() : null,
                analyst != null ? analyst.highestTarget() : null,
                analyst != null ? analyst.lowestTarget() : null,
                analyst != null ? analyst.impliedUpsidePct() : null,
                analyst != null ? analyst.priceAsStored() : null,
                analyst != null ? analyst.priceAsOf() : null,
                analyst != null ? analyst.housesEver() : null,
                analyst != null ? analyst.houseNamesEver() : null,
                analyst != null ? analyst.targetsEver() : null,
                analyst != null ? analyst.lastCallOn() : null,
                analyst != null ? analyst.symbolAnswered() : null,
                analyst != null ? analyst.note() : null,
                analyst != null ? analyst.overtakenTargets() : null,
                // SPEC 51.5. The map is a static table that cannot fail to answer, so these are
                // always written and an untagged stock gets an empty list rather than a null one -
                // "no tracked theme names this business" is a finding, not a gap (Gotcha 121).
                themeTags.stream().map(t -> t.theme().name()).distinct().toList(),
                themeTags.stream().map(t -> t.theme().label()).distinct().toList(),
                themeTags.stream().map(
                        com.example.trading.universe.theme.UniverseThemes.Tag::policy).distinct().toList(),
                themeTags.stream().map(
                        com.example.trading.universe.theme.UniverseThemes.Tag::role).toList());
    }

    /**
     * The reading's own word, or null when there is no reading at all.
     *
     * <p>Deliberately not collapsed to NOT_MEASURED here: the read already distinguishes "no rule
     * in the map" from "measured, nothing applies", and flattening that on the way to the screen
     * would let a blind spot render as an all-clear.
     */
    private static String macroVerdict(com.example.trading.macro.MacroExposureService.Reading r) {
        return r != null && r.result() != null ? r.result().verdict().name() : null;
    }

    /** The verdict name, or null when there is no reading at all - never a failing verdict. */
    private static String compoundingVerdict(
            com.example.trading.multibagger.CompoundingLensService.Reading r) {
        return r != null && r.result() != null ? r.result().verdict().name() : null;
    }

    private MultibaggerScoreEntity latestScore(String symbol) {
        try {
            List<MultibaggerScoreEntity> history = scoreRepository.findHistoryBySymbol(symbol);
            return history.isEmpty() ? null : history.get(0);
        } catch (Exception e) {
            log.debug("No score history for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /** B-064: the move net of the universe shift where measured, else the raw move. */
    private static Integer relativeOrRaw(HoldingsDecayService.DecayAlert d) {
        return d.getRelativeDelta30d() != null ? d.getRelativeDelta30d() : d.getDelta30d();
    }

    private HoldingsDecayService.DecayAlert quietDecay(String symbol) {
        try {
            return decayService.detectDecayForSymbol(symbol, null);
        } catch (Exception e) {
            log.debug("Decay read failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- writes

    /**
     * Add a symbol. Prices it live (two Kite quotes) and runs the technical analysis so the row
     * has a timing verdict immediately. Quality is deliberately <b>not</b> computed here — it
     * arrives with the next 14:00 screening, or never if the stock is outside the universe.
     */
    public WatchlistItemView add(String rawSymbol, String note) {
        String symbol;
        try {
            symbol = WatchlistSymbols.normalise(rawSymbol);
        } catch (IllegalArgumentException e) {
            throw new WatchlistException(400, e.getMessage());
        }

        WatchlistEntity row = watchlistRepository.findBySymbol(symbol).orElse(null);
        if (row != null && row.isActiveRow() && row.getAddedOn() != null) {
            throw new WatchlistException(409, symbol + " is already on the watchlist since " + row.getAddedOn() + ".");
        }

        Double price = marketDataService.getCurrentPrice(symbol);
        if (price == null || price <= 0) {
            // Gotcha 22: 0.0 is "no price". Refuse rather than store a row whose return can never be measured.
            throw new WatchlistException(422, "Kite returned no price for " + symbol
                    + " — check the symbol (renamed or delisted tickers return nothing). "
                    + "If the market is closed the last close still prices; an empty answer means the symbol is wrong.");
        }
        Double nifty = quietNifty();

        if (row == null) {
            row = new WatchlistEntity();
            row.setSymbol(symbol);
            row.setTradingSymbol(WatchlistSymbols.tradingSymbol(symbol));
            row.setExchange(symbol.substring(0, symbol.indexOf(':')));
        }
        row.setActive(true);
        row.setSource("MANUAL");
        row.setAddedOn(LocalDate.now());
        row.setPriceAtAdd(price);
        row.setNiftyAtAdd(nifty);
        row.setAddedNote(trimNote(note));
        row.setRemovedOn(null);
        row.setPriceAtRemoval(null);
        row.setCurrentPrice(price);
        watchlistRepository.save(row);
        log.info("Watchlist: added {} at {} (Nifty {}), note='{}'", symbol, price, nifty, row.getAddedNote());

        // Analysis failure must not roll back the add — the row exists and the 11:00 run fills it.
        try {
            analyseAndBackfill(symbol, nifty);
        } catch (Exception e) {
            log.warn("Watchlist: {} added but the first analysis failed ({}). It will read NOT_MEASURED until the next run.",
                    symbol, e.getMessage());
        }
        return viewFor(symbol).orElseThrow();
    }

    /** Re-analyse one row live; optionally compute an ad-hoc quality score (never persisted as a screening row). */
    public WatchlistItemView refresh(String symbol, boolean withQuality) {
        WatchlistEntity row = requireRow(symbol);
        Double nifty = quietNifty();
        analyseAndBackfill(row.getSymbol(), nifty);

        if (withQuality) {
            try {
                var score = screenerService.evaluateSingleStock(row.getSymbol()); // Gotcha 50: evaluate, never screen
                WatchlistEntity fresh = requireRow(row.getSymbol());
                fresh.setAdhocQualityScore(score != null ? score.getCompositeScore() : null);
                fresh.setAdhocQualityAt(score != null ? LocalDateTime.now() : null);
                watchlistRepository.save(fresh);
            } catch (Exception e) {
                log.warn("Watchlist: ad-hoc quality for {} failed — left unmeasured: {}", symbol, e.getMessage());
            }
        }
        return viewFor(row.getSymbol()).orElseThrow();
    }

    /** Soft-delete. No broker call — the removal price is the row's last analysed price. */
    public WatchlistItemView remove(String symbol) {
        WatchlistEntity row = requireRow(symbol);
        if (!row.isActiveRow()) {
            throw new WatchlistException(409, row.getSymbol() + " was already removed on " + row.getRemovedOn() + ".");
        }
        row.setActive(false);
        row.setRemovedOn(LocalDate.now());
        row.setPriceAtRemoval(row.getCurrentPrice() != null && row.getCurrentPrice() > 0 ? row.getCurrentPrice() : null);
        watchlistRepository.save(row);
        log.info("Watchlist: removed {} at {}", row.getSymbol(), row.getPriceAtRemoval());
        return viewFor(row.getSymbol()).orElseThrow();
    }

    public WatchlistItemView updateNote(String symbol, String note) {
        WatchlistEntity row = requireRow(symbol);
        row.setAddedNote(trimNote(note));
        watchlistRepository.save(row);
        return viewFor(row.getSymbol()).orElseThrow();
    }

    /**
     * One snapshot row per active symbol for today. Idempotent. One Nifty quote per run, not per
     * symbol. Called by the 15:00 scheduler run.
     */
    public int writeDailySnapshots() {
        LocalDate today = LocalDate.now();
        Double nifty = quietNifty();
        int written = 0;
        for (WatchlistEntity w : watchlistRepository.findActiveOrderByAddedOn()) {
            try {
                if (upsertSnapshot(w, today, w.getCurrentPrice(), nifty)) written++;
            } catch (Exception e) {
                log.warn("Watchlist snapshot for {} failed: {}", w.getSymbol(), e.getMessage());
            }
        }
        return written;
    }

    // --------------------------------------------------------------- helpers

    private void analyseAndBackfill(String symbol, Double nifty) {
        WatchlistAnalysisService.AnalysisOutcome outcome = analysisService.analyzeSymbol(symbol);
        backfillFromCandles(symbol, outcome.history());
        // Today's point carries the live close and Nifty so the page's "vs Nifty" is current.
        upsertSnapshot(outcome.entity(), LocalDate.now(), outcome.entity().getCurrentPrice(), nifty);
    }

    /** Writes the last {@value #BACKFILL_DAYS} closes from candles already fetched — no extra Kite call. */
    private void backfillFromCandles(String symbol, List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) return;
        LocalDate cutoff = LocalDate.now().minusDays(BACKFILL_DAYS);
        Map<LocalDate, Double> closes = new HashMap<>();
        for (Map<String, Object> candle : history) {
            LocalDate d = candleDate(candle.get("timestamp"));
            Object c = candle.get("close");
            if (d == null || d.isBefore(cutoff) || !(c instanceof Number n) || n.doubleValue() <= 0) continue;
            closes.put(d, n.doubleValue());
        }
        int added = 0;
        for (Map.Entry<LocalDate, Double> e : closes.entrySet()) {
            if (e.getKey().equals(LocalDate.now())) continue; // today is written with the live close + Nifty
            if (snapshotRepository.existsBySymbolAndSnapshotDate(symbol, e.getKey())) continue;
            snapshotRepository.save(WatchlistSnapshotEntity.builder()
                    .symbol(symbol).snapshotDate(e.getKey()).close(e.getValue()).build());
            added++;
        }
        if (added > 0) log.info("Watchlist: backfilled {} daily closes for {}", added, symbol);
    }

    /** True when a row was created; an existing row for the day is updated in place. */
    private boolean upsertSnapshot(WatchlistEntity w, LocalDate day, Double close, Double nifty) {
        if (close == null || close <= 0) return false;
        MultibaggerScoreEntity score = latestScore(w.getSymbol());
        Integer quality = score != null ? score.getCompositeScore() : null;
        Double sinceAdd = WatchlistReturnMath.pctReturn(w.getPriceAtAdd(), close);
        BuyTimingVerdict.Result v = BuyTimingVerdict.evaluate(new BuyTimingVerdict.Input(
                quality, w.getEntrySignal(), w.getSignalReason(), w.getRsi14(), close, w.getEma50(),
                w.getTrendDirection(), null, null,
                score != null ? score.getForensicFlags() : null,
                score != null ? score.getFinancialQualityVerdict() : null,
                score != null ? score.getLiquidityTier() : null,
                score != null ? score.getDcfVerdict() : null,
                sinceAdd), BuyTimingVerdict.Thresholds.from(config.getVerdict()));

        List<WatchlistSnapshotEntity> existing = snapshotRepository
                .findBySymbolAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(w.getSymbol(), day);
        WatchlistSnapshotEntity row = existing.stream().filter(s -> s.getSnapshotDate().equals(day)).findFirst()
                .orElseGet(() -> WatchlistSnapshotEntity.builder().symbol(w.getSymbol()).snapshotDate(day).build());
        boolean created = row.getId() == null;
        row.setClose(close);
        if (nifty != null) row.setNiftyClose(nifty);
        row.setTimingScore(w.getOverallScore());
        row.setQualityScore(quality);
        row.setTrendDirection(w.getTrendDirection());
        row.setVerdict(v.verdict().name());
        row.setReturnSinceAddPct(sinceAdd);
        snapshotRepository.save(row);
        return created;
    }

    private static LocalDate candleDate(Object ts) {
        if (ts == null) return null;
        String s = ts.toString();
        if (s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private WatchlistEntity requireRow(String rawSymbol) {
        String symbol;
        try {
            symbol = WatchlistSymbols.normalise(rawSymbol);
        } catch (IllegalArgumentException e) {
            throw new WatchlistException(400, e.getMessage());
        }
        return watchlistRepository.findBySymbol(symbol)
                .orElseThrow(() -> new WatchlistException(404, symbol + " is not on the watchlist."));
    }

    private Double quietNifty() {
        try {
            Double p = marketDataService.getCurrentPrice(NIFTY);
            return p != null && p > 0 ? p : null;
        } catch (Exception e) {
            log.warn("Nifty quote failed — excess return will read 'not measured': {}", e.getMessage());
            return null;
        }
    }

    private static String trimNote(String note) {
        if (note == null) return null;
        String t = note.trim();
        if (t.isEmpty()) return null;
        return t.length() > 500 ? t.substring(0, 500) : t;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
