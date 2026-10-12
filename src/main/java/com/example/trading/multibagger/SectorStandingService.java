package com.example.trading.multibagger;

import com.example.trading.holdings.SymbolVariants;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import com.example.trading.portfolio.SectorMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single DB-backed reader for the sector-relative lens (SPEC §54).
 *
 * <p>The rules live in the pure {@link SectorStanding}; this class only loads the latest screening
 * run, turns its rows into the lens's inputs and keeps the result. One reader for every surface -
 * screener, discovery, portfolio, watchlist and the stock page - because two copies of a lookup is
 * how one stock reads "top quarter" on one screen and "not measured" on the next (Gotcha 85).
 *
 * <p><b>Symbol resolution is not repeated here.</b> Callers that already hold a compounding reading
 * pass {@code CompoundingLensService.Reading.symbolAnswered()} - the B-088 "first spelling that can
 * answer" winner - and this class looks that spelling up. Only the stock page, which has nothing
 * resolved yet, falls back to walking {@link SymbolVariants#candidates}.
 *
 * <p>DB-only: one {@code DISTINCT} date query and one count per call, and the full run (~300 rows)
 * only when the latest run has changed. Safe on a page load (Gotcha 17, 39). Contributes zero
 * points to any score.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SectorStandingService {

    private final MultibaggerScoreRepository repository;

    /** How far back to look for a run that actually has rows - the same bound as the screener. */
    private static final int MAX_WALK_BACK = 10;

    /** The latest run, its cross-section, and each row's lens input, keyed by screening symbol. */
    public record Snapshot(LocalDate screeningDate, SectorStanding.CrossSection crossSection,
                           Map<String, SectorStanding.Row> rows) {

        /** The reading for a screening-row symbol, or null when that symbol is not in this run. */
        public SectorStanding.Result read(String symbol, CompoundingQuality.Result compounding) {
            SectorStanding.Row row = symbol == null ? null : rows.get(symbol);
            return row == null ? null : SectorStanding.evaluate(row, crossSection, compounding);
        }

        /**
         * The reading for a symbol as a surface holds it. Takes the spelling the compounding lens
         * already chose (B-088's "first spelling that can answer") so a holding, a watchlist row
         * and the stock page all describe the same screening row as the compounding cell beside
         * them - one resolution rule, not two (Gotcha 85, 107). Falls back to the first spelling
         * present in this run only when that one is absent. Null when the stock is not in it.
         */
        public SectorStanding.Result readResolved(String symbol, CompoundingLensService.Reading lens) {
            String answered = lens == null ? null : lens.symbolAnswered();
            if (answered == null || !rows.containsKey(answered)) answered = resolve(symbol);
            return answered == null ? null : read(answered, lens == null ? null : lens.result());
        }

        /**
         * The first exchange spelling present in this run. Used only where no compounding reading
         * has resolved the symbol already.
         */
        public String resolve(String symbol) {
            if (symbol == null) return null;
            for (String candidate : SymbolVariants.candidates(symbol)) {
                if (rows.containsKey(candidate)) return candidate;
            }
            return null;
        }
    }

    private volatile Snapshot cached;
    /**
     * The cache key besides the date: row count AND the highest row id. A same-day re-screen
     * upserts by delete-then-save, which keeps the count but issues a new id - keying on the count
     * alone served the old cross-section until the next restart (B-141).
     */
    private volatile String cachedKey;

    /**
     * The cross-section for the latest run that has rows, rebuilt only when that run's date or row
     * count has moved. Null when nothing has been screened at all.
     */
    public Snapshot latest() {
        try {
            List<LocalDate> dates = repository.findScreeningDates();
            for (LocalDate date : dates.stream().limit(MAX_WALK_BACK).toList()) {
                long count = repository.countByScreeningDate(date);
                if (count == 0) continue;
                String key = date + "|" + count + "|" + repository.findMaxIdByScreeningDate(date);
                Snapshot s = cached;
                if (s != null && key.equals(cachedKey)) return s;
                return rebuild(date, repository.findByScreeningDateOrderByCompositeScoreDesc(date), key);
            }
        } catch (Exception e) {
            // Degrades to "not measured" on every surface, never to a zero (Gotcha 21).
            log.warn("Sector standing unavailable - every surface will read 'not measured': {}", e.getMessage());
        }
        return null;
    }

    /**
     * The cross-section built from rows the caller has already loaded - the screener holds the
     * whole run anyway. Always rebuilt from exactly those rows (a few milliseconds for ~300), so
     * the standing beside a row can never be computed from older figures than the row itself
     * (B-141). Also refreshes the cache for the other surfaces.
     */
    public Snapshot fromRun(LocalDate date, Collection<MultibaggerScoreEntity> run) {
        if (date == null || run == null || run.isEmpty()) return null;
        long maxId = run.stream().map(MultibaggerScoreEntity::getId).filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue).max().orElse(-1);
        return rebuild(date, run, date + "|" + run.size() + "|" + maxId);
    }

    private synchronized Snapshot rebuild(LocalDate date, Collection<MultibaggerScoreEntity> run, String key) {
        Map<String, SectorStanding.Row> rows = new LinkedHashMap<>();
        List<SectorStanding.Row> list = new ArrayList<>(run.size());
        for (MultibaggerScoreEntity e : run) {
            SectorStanding.Row row = toRow(e);
            if (row == null) continue;
            rows.putIfAbsent(row.symbol(), row);
            list.add(row);
        }
        Snapshot s = new Snapshot(date, SectorStanding.crossSection(list), Map.copyOf(rows));
        cachedKey = key;
        cached = s;
        return s;
    }

    /**
     * One screening row as the lens reads it. The bucket is {@code SectorMapping.resolve} - the
     * same call {@code DashboardService.sectorOf} and the holdings decorator make, so the lens
     * ranks a stock inside the sector every screen names for it (B-096, B-098).
     */
    static SectorStanding.Row toRow(MultibaggerScoreEntity e) {
        if (e == null || e.getSymbol() == null) return null;
        String sector = SectorMapping.resolve(e.getSymbol(), e.getIndustry());
        return new SectorStanding.Row(
                e.getSymbol(),
                SectorStanding.peerGroup(SectorMapping.isUnknown(sector) ? null : sector, e.getIndustry()),
                // A fee-earning financial filed under Financial Services is not a lender (B-136),
                // read from the row's own returns so it holds before the next screening rewrites
                // the capex verdict.
                "NA_FINANCIAL".equals(e.getCapexVerdict())
                        && !com.example.trading.ai.NseDataService.isFeeFinancialByReturns(
                                e.getRoaPercent(), e.getRoePercent()),
                e.getRocePercent(),
                e.getRoaPercent(),
                e.getRoePercent(),
                e.getCashConversionRatio(),
                e.getDebtToEquity(),
                e.getEarningsConsistencyScore());
    }
}
