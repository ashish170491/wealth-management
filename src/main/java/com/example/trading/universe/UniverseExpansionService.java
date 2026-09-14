package com.example.trading.universe;

import com.example.trading.ai.NseDataService;
import com.example.trading.config.StockFilterConfig;
import com.example.trading.marketdata.MarketDataService;
import com.example.trading.multibagger.MultibaggerScore;
import com.example.trading.multibagger.MultibaggerScreenerService;
import com.example.trading.scanner.Nifty200WatchlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Dynamic universe expansion (SPEC §30, plan feature F3).
 *
 * <p>The screener works off ~361 curated names. Multibaggers emerge from the ~2,300-stock
 * NSE mainboard while nobody is watching, so a curated list can only ever re-rank stocks
 * somebody already thought were interesting. This is the funnel that lets the system find
 * names for itself.
 *
 * <h2>Two stages, because deep scoring is expensive</h2>
 * <b>Stage A</b> is price/volume only — one Kite call per symbol, no NSE, no XBRL — and runs
 * weekly inside the Saturday screening window. It reduces ~1,900 unseen symbols to a few
 * dozen worth a closer look. <b>Stage B</b> takes that queue a few names a day during the
 * weekday screening and runs the full 8-dimension composite, promoting only what clears the
 * bar. Doing Stage B for every mainboard symbol would be thousands of NSE/XBRL fetches a day.
 *
 * <h2>What it will not do</h2>
 * It never removes a curated name, never promotes a THIN stock (SPEC §12.9 — a
 * recommendation you cannot accumulate is noise), and never deletes a retired row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UniverseExpansionService {

    private final NseDataService nseDataService;
    private final MarketDataService marketDataService;
    private final MultibaggerScreenerService screenerService;
    private final Nifty200WatchlistService curatedWatchlist;
    private final DynamicUniverseRepository repository;
    private final UniverseConfig config;
    private final StockFilterConfig stockFilter;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_PROMOTED = "PROMOTED";
    private static final String STATUS_RETIRED = "RETIRED";

    // ================================================================
    // Stage A — coarse technical scan
    // ================================================================

    /**
     * Scan every mainboard symbol the system has never looked at and queue the strongest.
     *
     * <p>Called from inside the Saturday weekly screening rather than from a scheduler of its
     * own: {@code isSaturdayScreeningWindow()} is documented as having exactly two authorised
     * callers, and a third would turn a sanctioned exception into a convention (Gotcha 28).
     */
    public ScanResult runCoarseScan() {
        long start = System.currentTimeMillis();
        if (!config.isScanEnabled()) {
            log.info("Universe expansion: coarse scan disabled by config");
            return new ScanResult(0, 0, 0, 0, 0, List.of());
        }

        List<NseDataService.EquityListing> listings = nseDataService.fetchEquityList();
        if (listings.isEmpty()) {
            log.warn("Universe expansion: equity list unavailable — coarse scan skipped this week");
            return new ScanResult(0, 0, 0, 0, 0, List.of());
        }

        Set<String> known = knownSymbols();
        LocalDate listedBefore = LocalDate.now().minusMonths(config.getMinMonthsSinceListing());

        int blacklisted = 0;
        List<NseDataService.EquityListing> targets = new ArrayList<>();
        for (NseDataService.EquityListing l : listings) {
            if (!l.isMainboardEquity()) continue;                       // BE/BZ excluded (Gotcha 14)
            if (known.contains(l.qualifiedSymbol())) continue;          // already curated or tracked
            if (l.listingDate() != null && l.listingDate().isAfter(listedBefore)) continue; // IPO tracker's job
            // Blacklisted names were excluded downstream, at the merge — but only after a
            // Kite call, a queue row and a Stage B deep score had already been spent on
            // them, and after they had appeared in the funnel view as live candidates
            // (B-049). A stock we have decided not to buy is not a discovery.
            if (stockFilter.isBlacklisted(l.qualifiedSymbol())) { blacklisted++; continue; }
            targets.add(l);
        }

        log.info("Universe expansion: coarse scan over {} unseen mainboard symbols "
                        + "({} listed, {} already known, {} blacklisted)",
                targets.size(), listings.size(), known.size(), blacklisted);

        // Relative strength is scored against the Nifty, so without the index series every
        // symbol would score null and the whole run would report ~1,600 "no data" rows —
        // a total failure dressed up as a scan that found nothing (B-049). Fail loudly here
        // instead, and keep the queue as it was.
        List<Map<String, Object>> nifty = safeCandles("NSE:NIFTY 50");
        if (nifty == null || nifty.size() < 130) {
            log.warn("Universe expansion: Nifty 50 history unavailable ({} candles) — coarse scan "
                            + "aborted rather than scoring {} symbols with no benchmark",
                    nifty == null ? 0 : nifty.size(), targets.size());
            return new ScanResult(0, 0, 0, 0, 0, List.of());
        }

        List<Candidate> survivors = new ArrayList<>();
        int noData = 0;
        int rejected = 0;

        for (NseDataService.EquityListing l : targets) {
            try {
                Candidate c = scoreCoarse(l, nifty);
                if (c == null) {
                    noData++;
                } else if (c.score() <= 0) {
                    rejected++;
                } else {
                    survivors.add(c);
                }
            } catch (Exception e) {
                noData++;
                log.debug("Coarse scan failed for {}: {}", l.symbol(), e.getMessage());
            }
        }

        survivors.sort(Comparator.comparingInt(Candidate::score).reversed());
        int cap = Math.min(config.getCoarseScanTopN(), survivors.size());
        List<Candidate> kept = survivors.subList(0, cap);

        // Each queue write is isolated (B-049). These calls sat outside the per-symbol try,
        // so a single unique-constraint collision — a manual scan overlapping the Saturday
        // one — threw out of the whole method and discarded 20 minutes of completed work
        // along with every other symbol's queue row.
        int queued = 0;
        int queueFailures = 0;
        for (Candidate c : kept) {
            try {
                if (queue(c)) queued++;
            } catch (Exception e) {
                queueFailures++;
                log.warn("Universe expansion: could not queue {} — {}. The scan continues; this "
                        + "symbol stays eligible next week.", c.symbol(), e.toString());
            }
        }

        // No silent caps: say what was dropped, so "40 queued" is never mistaken for
        // "40 were all that passed".
        if (survivors.size() > cap) {
            log.info("Universe expansion: {} symbols passed the coarse filters, keeping top {} — "
                            + "{} not queued this week (they remain eligible next scan)",
                    survivors.size(), cap, survivors.size() - cap);
        }

        long ms = System.currentTimeMillis() - start;
        log.info("Universe expansion: coarse scan done in {} ms — {} scanned, {} passed, {} newly queued, "
                        + "{} rejected, {} without usable data{}",
                ms, targets.size(), survivors.size(), queued, rejected, noData,
                queueFailures > 0 ? ", " + queueFailures + " queue writes failed" : "");

        return new ScanResult(targets.size(), survivors.size(), queued, rejected, noData,
                kept.stream().map(Candidate::symbol).toList());
    }

    /**
     * Price/volume-only score. Returns null when the symbol has no usable history, and 0
     * when it fails a hard filter — the caller keeps those apart so "we could not look" is
     * never reported as "we looked and it was bad".
     */
    Candidate scoreCoarse(NseDataService.EquityListing listing, List<Map<String, Object>> nifty) {
        String symbol = listing.qualifiedSymbol();
        List<Map<String, Object>> history = safeCandles(symbol);
        if (history == null || history.size() < 130) return null;   // ~6 months of trading days

        double price = closeOf(history.get(history.size() - 1));
        if (price <= 0) return null;

        // Hard gate 1: buyability. A stock we cannot accumulate is not an opportunity,
        // whatever its chart looks like (SPEC §12.9).
        Double adv20 = averageTradedValue(history, 20);
        if (adv20 == null || adv20 < config.getMinAdv20d()) return zero(listing, adv20);

        int score = 0;

        // Relative strength over ~6 months vs Nifty.
        Double rs = relativeStrength(history, nifty, 126);
        if (rs == null) return null;
        if (rs <= 0) return zero(listing, adv20);
        score += (int) Math.min(40, 20 + rs);

        // Proximity to the 52-week high — strength, not a bounce off the floor.
        double high52 = history.stream().skip(Math.max(0, history.size() - 252))
                .mapToDouble(UniverseExpansionService::highOf).max().orElse(price);
        double belowHigh = high52 > 0 ? 100.0 * (high52 - price) / high52 : 100;
        boolean nearHigh = belowHigh <= 25;
        if (nearHigh) score += (int) Math.max(0, 30 - belowHigh);

        // Or a base of higher lows, which is the same thesis earlier in its life.
        boolean higherLows = hasHigherLows(history);
        if (higherLows) score += 15;
        if (!nearHigh && !higherLows) return zero(listing, adv20);

        // Volume expanding against its own longer average.
        Double adv60 = averageTradedValue(history, 60);
        if (adv60 != null && adv60 > 0 && adv20 / adv60 > 1.1) score += 15;

        return new Candidate(symbol, listing.companyName(), Math.min(100, score), adv20,
                listing.listingDate());
    }

    private Candidate zero(NseDataService.EquityListing l, Double adv20) {
        return new Candidate(l.qualifiedSymbol(), l.companyName(), 0, adv20, l.listingDate());
    }

    private boolean queue(Candidate c) {
        Optional<DynamicUniverseEntity> existing = repository.findBySymbol(c.symbol());
        if (existing.isPresent()) {
            DynamicUniverseEntity e = existing.get();
            // Re-surfacing a retired name is meaningful, but it should not silently re-enter
            // the universe — it goes back to the queue and has to earn promotion again.
            if (STATUS_RETIRED.equals(e.getStatus())) {
                e.setStatus(STATUS_QUEUED);
                e.setActive(true);
                e.setConsecutiveWeakRuns(0);
                e.setCoarseScore(c.score());
                e.setNote("Re-discovered by coarse scan after retirement");
                repository.save(e);
                return true;
            }
            e.setCoarseScore(c.score());
            repository.save(e);
            return false;
        }

        repository.save(DynamicUniverseEntity.builder()
                .symbol(c.symbol())
                .companyName(truncate(c.companyName(), 128))
                .sourceReason("COARSE_SCAN")
                .status(STATUS_QUEUED)
                .discoveredDate(LocalDate.now())
                .listingDate(c.listingDate())
                .coarseScore(c.score())
                .liquidityAdv20d(c.adv20())
                .consecutiveWeakRuns(0)
                .active(true)
                .build());
        return true;
    }

    // ================================================================
    // Stage B — deep fundamental scoring of the queue
    // ================================================================

    /**
     * Deep-score a few queued symbols and promote the ones that clear the bar.
     *
     * <p>Called from the weekday screening. The per-day budget is what keeps NSE/XBRL cost
     * predictable — without it, a 40-name queue would add 40 full fundamental workups to a
     * run that already takes ~12 minutes.
     */
    public PromotionResult processQueue() {
        List<DynamicUniverseEntity> queued = repository.findActiveByStatus(STATUS_QUEUED);
        if (queued.isEmpty()) return new PromotionResult(0, 0, 0, List.of());

        int budget = Math.min(config.getDeepScorePerDay(), queued.size());
        List<DynamicUniverseEntity> batch = queued.subList(0, budget);

        int promoted = 0;
        int rejected = 0;
        int failed = 0;
        List<String> promotedSymbols = new ArrayList<>();

        for (DynamicUniverseEntity e : batch) {
            try {
                // Evaluate, do not publish (B-035). A queued symbol is a candidate the
                // funnel is deciding about; it has no business in `multibagger_scores` or
                // the recommendation ledger until it is actually promoted AND the feature
                // is enabled. Its score lives on `dynamic_universe.lastCompositeScore`.
                MultibaggerScore score = screenerService.evaluateSingleStock(e.getSymbol());
                if (score == null) {
                    failed++;
                    e.setNote("Deep scoring returned no result");
                    repository.save(e);
                    continue;
                }
                e.setLastCompositeScore(score.getCompositeScore());
                e.setLastScreenedDate(LocalDate.now());

                boolean qualityOk = !"WEAK".equals(score.getFinancialQualityVerdict())
                        && !"HIGH_RISK".equals(score.getFinancialQualityVerdict());
                boolean liquidityOk = !"THIN".equals(score.getLiquidityTier());

                if (score.getCompositeScore() >= config.getPromotionMinComposite() && qualityOk && liquidityOk) {
                    e.setStatus(STATUS_PROMOTED);
                    e.setPromotedDate(LocalDate.now());
                    e.setNote(String.format("Promoted at composite %d (%s)",
                            score.getCompositeScore(), score.getVerdict()));
                    promoted++;
                    promotedSymbols.add(e.getSymbol());
                } else {
                    e.setStatus(STATUS_RETIRED);
                    e.setRetiredDate(LocalDate.now());
                    e.setActive(false);
                    e.setNote(rejectionReason(score, qualityOk, liquidityOk));
                    rejected++;
                }
                repository.save(e);
            } catch (Exception ex) {
                failed++;
                log.debug("Deep scoring failed for {}: {}", e.getSymbol(), ex.getMessage());
            }
        }

        log.info("Universe expansion: deep-scored {} of {} queued — {} promoted, {} rejected, {} failed. "
                        + "{} still queued.",
                batch.size(), queued.size(), promoted, rejected, failed,
                Math.max(0, queued.size() - batch.size()));

        enforceCap();
        return new PromotionResult(promoted, rejected, failed, promotedSymbols);
    }

    private String rejectionReason(MultibaggerScore s, boolean qualityOk, boolean liquidityOk) {
        if (!qualityOk) return "Rejected: financial quality " + s.getFinancialQualityVerdict();
        if (!liquidityOk) return "Rejected: liquidity THIN — cannot be accumulated";
        return String.format("Rejected: composite %d below %d", s.getCompositeScore(),
                config.getPromotionMinComposite());
    }

    // ================================================================
    // Retirement
    // ================================================================

    /**
     * Age the promoted set against the latest weekly scores.
     *
     * <p>A symbol has to be below the floor for {@code retirementConsecutiveRuns} consecutive
     * weekly runs, so one bad week never drops a name. Retirement deactivates the row; it
     * never deletes it.
     */
    public int retireWeakSymbols(Map<String, Integer> latestComposites) {
        int retired = 0;
        for (DynamicUniverseEntity e : repository.findPromoted()) {
            Integer composite = latestComposites.get(e.getSymbol());
            if (composite == null) continue;    // not screened this run — no evidence either way
            e.setLastCompositeScore(composite);
            e.setLastScreenedDate(LocalDate.now());

            if (composite < config.getRetirementComposite()) {
                int weak = (e.getConsecutiveWeakRuns() == null ? 0 : e.getConsecutiveWeakRuns()) + 1;
                e.setConsecutiveWeakRuns(weak);
                if (weak >= config.getRetirementConsecutiveRuns()) {
                    e.setStatus(STATUS_RETIRED);
                    e.setActive(false);
                    e.setRetiredDate(LocalDate.now());
                    e.setNote(String.format("Retired after %d consecutive runs below %d",
                            weak, config.getRetirementComposite()));
                    retired++;
                }
            } else {
                e.setConsecutiveWeakRuns(0);
            }
            repository.save(e);
        }
        if (retired > 0) {
            log.info("Universe expansion: retired {} persistently weak symbol(s)", retired);
        }
        return retired;
    }

    /** Keep the promoted set within its cap, dropping the weakest first. */
    private void enforceCap() {
        List<DynamicUniverseEntity> promoted = repository.findPromoted();
        int excess = promoted.size() - config.getMaxActiveSymbols();
        if (excess <= 0) return;

        promoted.sort(Comparator.comparing(
                e -> e.getLastCompositeScore() == null ? 0 : e.getLastCompositeScore()));
        for (int i = 0; i < excess; i++) {
            DynamicUniverseEntity e = promoted.get(i);
            e.setStatus(STATUS_RETIRED);
            e.setActive(false);
            e.setRetiredDate(LocalDate.now());
            e.setNote("Retired to stay within the " + config.getMaxActiveSymbols() + "-symbol cap");
            repository.save(e);
        }
        log.info("Universe expansion: cap of {} exceeded — retired {} lowest-scoring symbol(s)",
                config.getMaxActiveSymbols(), excess);
    }

    // ================================================================
    // Universe resolution
    // ================================================================

    /**
     * Promoted symbols to merge into the screening universe, or empty when the feature is
     * switched off. Curated names are never affected either way.
     */
    public List<String> activeDynamicSymbols() {
        if (!config.isEnabled()) return List.of();
        return repository.findPromoted().stream().map(DynamicUniverseEntity::getSymbol).toList();
    }

    /**
     * Everything the scan must skip: stocks already screened, plus dynamic rows that are
     * live or still cooling off after retirement.
     *
     * <p><b>Both universe lists (B-053).</b> The screener screens the tier universe
     * <i>merged with</i> its own hardcoded {@code SCREENING_UNIVERSE} — see
     * {@code MultibaggerScreenerService.resolveUniverse()}. Reading only the tier list let
     * the funnel "discover" stocks it was already screening daily: PAYTM was deep-scored and
     * promoted on 2026-08-26 despite having 91 rows of screening history going back to March.
     *
     * <p><b>Retired rows cool off rather than vanish (B-036).</b> A symbol retired for eight
     * consecutive weak weeks is excluded for {@link UniverseConfig#getRetirementCooloffMonths()}
     * months, then becomes eligible again. Excluding retired rows forever makes the scannable
     * pool shrink monotonically; excluding them not at all makes retirement cosmetic, because
     * the next Saturday scan re-discovers and re-promotes what was just dropped.
     */
    private Set<String> knownSymbols() {
        Set<String> known = new HashSet<>(curatedWatchlist.getFullUniverseSymbols());
        known.addAll(screenerService.getScreeningUniverse());

        LocalDate coolOffBefore = LocalDate.now().minusMonths(config.getRetirementCooloffMonths());
        for (DynamicUniverseEntity d : repository.findAll()) {
            boolean stillCoolingOff = d.getRetiredDate() == null || !d.getRetiredDate().isBefore(coolOffBefore);
            if (d.isActive() || stillCoolingOff) {
                known.add(d.getSymbol());
            }
        }
        return known;
    }

    // ================================================================
    // Helpers
    // ================================================================

    private List<Map<String, Object>> safeCandles(String symbol) {
        try {
            String to = LocalDate.now().atTime(15, 30).format(FMT);
            String from = LocalDate.now().minusDays(400).atTime(9, 15).format(FMT);
            return marketDataService.getRecentCandles(symbol, "day", from, to);
        } catch (Exception e) {
            return null;
        }
    }

    /** Average close x volume over the last {@code days} candles; null when unmeasurable. */
    static Double averageTradedValue(List<Map<String, Object>> history, int days) {
        if (history == null || history.size() < days) return null;
        double sum = 0;
        int n = 0;
        for (Map<String, Object> c : history.subList(history.size() - days, history.size())) {
            double close = closeOf(c);
            double vol = numberOf(c.get("volume"));
            if (close > 0 && vol > 0) {
                sum += close * vol;
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    /**
     * Stock return minus Nifty return over the window, in percentage points.
     *
     * <p>Null when either leg is unavailable (B-049). Falling back to the raw stock return
     * turned "relative strength" into "went up", which passes the filter for every stock in
     * a rising market — the promotion gate would have silently stopped being relative at the
     * exact moment the benchmark fetch failed.
     */
    static Double relativeStrength(List<Map<String, Object>> stock, List<Map<String, Object>> nifty, int days) {
        Double stockRet = periodReturn(stock, days);
        if (stockRet == null) return null;
        Double niftyRet = periodReturn(nifty, days);
        return niftyRet == null ? null : stockRet - niftyRet;
    }

    private static Double periodReturn(List<Map<String, Object>> h, int days) {
        if (h == null || h.size() < days + 1) return null;
        double then = closeOf(h.get(h.size() - 1 - days));
        double now = closeOf(h.get(h.size() - 1));
        if (then <= 0) return null;
        return 100.0 * (now - then) / then;
    }

    /** Three ascending swing lows over the last ~6 months: a base, not a falling knife. */
    public static boolean hasHigherLows(List<Map<String, Object>> history) {
        if (history == null || history.size() < 130) return false;
        List<Map<String, Object>> window = history.subList(history.size() - 130, history.size());
        int third = window.size() / 3;
        double l1 = minLow(window.subList(0, third));
        double l2 = minLow(window.subList(third, 2 * third));
        double l3 = minLow(window.subList(2 * third, window.size()));
        return l1 > 0 && l2 > l1 && l3 > l2;
    }

    private static double minLow(List<Map<String, Object>> part) {
        return part.stream().mapToDouble(c -> numberOf(c.get("low"))).filter(v -> v > 0).min().orElse(0);
    }

    private static double closeOf(Map<String, Object> candle) {
        return numberOf(candle.get("close"));
    }

    private static double highOf(Map<String, Object> candle) {
        return numberOf(candle.get("high"));
    }

    private static double numberOf(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ---- Result types ----

    record Candidate(String symbol, String companyName, int score, Double adv20, LocalDate listingDate) {}

    public record ScanResult(int scanned, int passed, int newlyQueued, int rejected, int noData,
                             List<String> queuedSymbols) {}

    public record PromotionResult(int promoted, int rejected, int failed, List<String> promotedSymbols) {}
}
