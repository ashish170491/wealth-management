package com.example.trading.portfolio.core;

import com.example.trading.fundamentals.AnnualFundamentalsEntity;
import com.example.trading.fundamentals.AnnualFundamentalsRepository;
import com.example.trading.fundamentals.ForensicScreenService;
import com.example.trading.holdings.HoldingsDecayService;
import com.example.trading.marketdata.MarketDataService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import com.example.trading.portfolio.conviction.HoldingConvictionEntity;
import com.example.trading.portfolio.conviction.HoldingConvictionRepository;
import com.example.trading.portfolio.core.CoreDto.AnnualYear;
import com.example.trading.portfolio.core.CoreDto.CoreClassification;
import com.example.trading.portfolio.core.CoreDto.CoreHoldingView;
import com.example.trading.portfolio.core.CoreDto.CoreTier;
import com.example.trading.portfolio.core.CoreDto.Gate;
import com.example.trading.portfolio.core.CoreDto.GateStatus;
import com.example.trading.portfolio.core.CoreDto.HoldingEvidence;
import com.example.trading.portfolio.core.CoreDto.PricePoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assembles the evidence, runs {@link CoreHoldingService}, applies hysteresis and the manual
 * override, and persists one row per holding per day (SPEC §35).
 *
 * <p>This is the only class in the package that touches a repository or the broker. It runs at the
 * end of the existing 10:30 holdings-analysis job — <b>no new {@code @Scheduled} method</b>, per
 * SPEC §3.4 — so classification sees that morning's refreshed prices and the previous day's
 * screening scores.
 *
 * <h2>Broker cost</h2>
 * Durability component D5 needs daily closes, which costs <b>two</b> paced Kite calls per holding
 * (instrument-token resolution, then candles — SPEC §30.6 measured two, not one). At ~33 holdings
 * and ~2.9 req/s process-wide that is roughly 25 seconds. Candles are cached for the day inside
 * this service; the old global candle cache was deleted for being write-only (B-049), and this one
 * is bounded by the number of holdings and cleared when the date rolls.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CoreClassificationService {

    private final HoldingsRepository holdingsRepository;
    private final MultibaggerScoreRepository scoreRepository;
    private final AnnualFundamentalsRepository fundamentalsRepository;
    private final HoldingConvictionRepository convictionRepository;
    private final CoreHoldingSnapshotRepository snapshotRepository;
    private final HoldingsDecayService decayService;
    private final ForensicScreenService forensicScreenService;
    private final MarketDataService marketDataService;
    private final CoreHoldingConfig config;
    private final CoreHoldingService classifier;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_DATE;

    /** Day-scoped candle cache. Key = symbol; cleared whenever {@link #cacheDate} rolls. */
    private final Map<String, List<PricePoint>> candleCache = new ConcurrentHashMap<>();
    private volatile LocalDate cacheDate = LocalDate.now();

    /**
     * Observation-mode evidence collected before today's row exists. The exit-alert job runs at
     * 10:00 and classification at 10:30, so the first batch of the day arrives before there is a
     * row to write it on.
     */
    private final Map<String, List<String>> pendingObservedAlerts = new ConcurrentHashMap<>();
    private volatile LocalDate observedDate = LocalDate.now();

    // ------------------------------------------------------------------ entry points

    /** Classify every active holding and persist today's rows. Returns what it wrote. */
    public List<CoreHoldingView> classifyAll() {
        if (!config.isEnabled()) {
            log.debug("Core holding classifier disabled (portfolio.core.enabled=false)");
            return List.of();
        }
        LocalDate today = LocalDate.now();
        rollCachesIfNewDay(today);

        List<HoldingsEntity> holdings = holdingsRepository.findActive();
        if (holdings.isEmpty()) {
            log.info("Core classification: no active holdings");
            return List.of();
        }

        Map<String, HoldingsDecayService.DecayAlert> decay = new LinkedHashMap<>();
        try {
            for (HoldingsDecayService.DecayAlert a : decayService.detectDecay(holdings)) {
                decay.put(a.getSymbol(), a);
            }
        } catch (Exception e) {
            // A decay failure must not lose the whole classification — G5 goes UNMEASURED instead,
            // which narrows the quorum rather than inventing a pass.
            log.warn("Core classification: thesis-drift detection failed, G5 will be unmeasured "
                    + "for every holding today: {}", e.getMessage());
        }

        List<CoreHoldingView> out = new ArrayList<>();
        int failed = 0;
        for (HoldingsEntity h : holdings) {
            try {
                out.add(classifyAndPersist(h, decay.get(h.getSymbol()), today));
            } catch (Exception e) {
                failed++;
                log.warn("Core classification failed for {}: {}", h.getSymbol(), e.getMessage());
            }
        }
        // Persisted-vs-attempted, together, always. A bare success count cannot reveal a partial
        // failure — that is how 210 of 288 screening rows went missing unnoticed (B-026).
        log.info("Core classification: {} of {} holdings classified ({} failed). Tiers: {}",
                out.size(), holdings.size(), failed, tierCounts(out));
        return out;
    }

    private String tierCounts(List<CoreHoldingView> views) {
        Map<CoreTier, Integer> counts = new LinkedHashMap<>();
        for (CoreTier t : CoreTier.values()) counts.put(t, 0);
        for (CoreHoldingView v : views) counts.merge(v.effectiveTier(), 1, Integer::sum);
        return counts.toString();
    }

    private CoreHoldingView classifyAndPersist(HoldingsEntity h,
                                               HoldingsDecayService.DecayAlert decay,
                                               LocalDate today) {
        HoldingEvidence evidence = evidenceFor(h, decay);
        CoreClassification c = classifier.classify(evidence, today);

        // One load, two readings. Both hysteresis inputs walk the same newest-first history, and
        // it grows by a row per holding per weekday.
        List<CoreHoldingSnapshotEntity> history = snapshotRepository.findHistory(h.getSymbol());
        CoreTier previousEffective = previousEffectiveTier(history, today);
        List<CoreHysteresis.AnchorReading> anchors = weeklyAnchors(history, today);
        CoreHysteresis.TierDecision decision = CoreHysteresis.resolve(
                c.provisionalTier(), previousEffective, anchors,
                c.criticalTrigger(), today, config.getHysteresisRuns());

        CoreTier effective = decision.effectiveTier();
        String pending = decision.pendingChange();

        // The investor's own judgement wins, and is recorded so nothing is hidden (SPEC §6.2).
        String override = evidence.coreOverride();
        if ("FORCE_CORE".equals(override)) {
            effective = CoreTier.CORE;
            pending = null;
        } else if ("FORCE_SATELLITE".equals(override)) {
            effective = CoreTier.SATELLITE;
            pending = null;
        } else {
            override = null;
        }

        CoreHoldingSnapshotEntity row = snapshotRepository
                .findBySymbolAndClassifiedOn(h.getSymbol(), today)
                .orElseGet(() -> CoreHoldingSnapshotEntity.builder()
                        .symbol(h.getSymbol())
                        .classifiedOn(today)
                        .build());

        row.setTradingSymbol(h.getTradingSymbol());
        row.setIsin(h.getIsin());
        row.setTier(c.provisionalTier().name());
        row.setEffectiveTier(effective.name());
        row.setDurabilityScore(c.durability().score());
        row.setDurabilityCoverage(c.durability().coverage());
        row.setGatesJson(encodeGates(c.gates()));
        row.setSoftSignals(join(c.softSignals()));
        row.setMissingInputs(join(c.missingInputs()));
        row.setReasons(join(c.reasons()));
        row.setPendingChange(pending);
        row.setOverrideApplied(override);

        // Same union rule as recordObservedAlerts: the 10:00 alert job runs before this one, and
        // re-running classification mid-day must not discard what it recorded.
        List<String> buffered = pendingObservedAlerts.get(h.getSymbol());
        if (buffered != null && !buffered.isEmpty()) {
            row.setObservedAlerts(join(unionAlerts(row.getObservedAlerts(), buffered)));
        }
        snapshotRepository.save(row);

        return toView(row, c.gates());
    }

    // ------------------------------------------------------------------ evidence

    HoldingEvidence evidenceFor(HoldingsEntity h, HoldingsDecayService.DecayAlert decay) {
        List<String> missing = new ArrayList<>();

        MultibaggerScoreEntity score = latestScore(h.getSymbol());
        String scoreSymbol = h.getSymbol();
        if (score == null && h.getTradingSymbol() != null && !h.getSymbol().startsWith("NSE:")) {
            // A BSE holding has no NSE score row under its own symbol (SPEC §6.4). The company is
            // the same one, so its NSE screening history is the right evidence — matched on
            // trading symbol, because multibagger_scores carries no ISIN to join on.
            String nse = "NSE:" + h.getTradingSymbol();
            score = latestScore(nse);
            if (score != null) {
                scoreSymbol = nse;
                missing.add("Quality evidence comes from " + nse + ", the same company's NSE line "
                        + "(this holding sits on BSE, which has no screening history of its own)");
            }
        }
        if (score == null) {
            missing.add("Not in the screening universe - no composite-score row exists, so the "
                    + "quality gates have nothing to read. Add it via universe expansion.");
        }

        ForensicScreenService.ForensicResult forensic = null;
        try {
            forensic = forensicScreenService.screen(scoreSymbol, false);
        } catch (Exception e) {
            log.debug("Forensic screen unavailable for {}: {}", h.getSymbol(), e.getMessage());
        }
        Boolean forensicMeasured = null;
        Integer forensicFlags = null;
        String forensicSummary = null;
        if (forensic != null) {
            // "Measured" means at least the balance-sheet checks had something to work with:
            // receivables and cash conversion need three years. The auditor check is always
            // unmeasured here because announcements are deliberately not fetched (page-load rule).
            long scoring = forensic.getFlags().stream()
                    .filter(f -> !"INFO".equals(f.getSeverity())).count();
            forensicMeasured = scoring > 0 || forensic.getYearsAvailable() >= 3;
            forensicFlags = (int) scoring;
            forensicSummary = forensic.toStorageString();
            if (!forensicMeasured) {
                missing.add("Forensic screen could not run - needs about 3 years of annual history "
                        + "(have " + forensic.getYearsAvailable() + ")");
            }
        }

        Optional<HoldingConvictionEntity> conviction = convictionRepository.findBySymbol(h.getSymbol());
        Double drift = null;
        if (conviction.isPresent() && conviction.get().getPurchaseMultibaggerScore() != null
                && score != null) {
            drift = score.getCompositeScore() - conviction.get().getPurchaseMultibaggerScore();
        }

        List<AnnualYear> history = annualHistory(scoreSymbol);
        if (history.size() < 3) {
            missing.add("Annual history has " + history.size()
                    + " year(s) - multi-year durability needs 3 to 5");
        }

        List<PricePoint> closes = dailyCloses(h.getSymbol());

        return new HoldingEvidence(
                h.getSymbol(), h.getTradingSymbol(), h.getIsin(),
                h.getIndustry() != null ? h.getIndustry()
                        : (score != null ? score.getIndustry() : null),
                score != null ? score.getScreeningDate() : null,
                score != null ? score.getCompositeScore() : null,
                score != null ? score.getCapitalEfficiencyVerdict() : null,
                score != null ? score.getRocePercent() : null,
                score != null ? score.getRoePercent() : null,
                score != null ? score.getRoaPercent() : null,
                score != null ? score.getFinancialQualityVerdict() : null,
                score != null ? score.getInterestCoverage() : null,
                score != null ? score.getEarningsConsistencyScore() : null,
                score != null ? score.getInsiderPulseVerdict() : null,
                score != null ? score.getTurnaroundVerdict() : null,
                score != null ? score.getCapexVerdict() : null,
                forensicMeasured, forensicFlags, forensicSummary,
                decay != null && decay.getVerdict() != null ? decay.getVerdict().name() : null,
                drift,
                conviction.isPresent(),
                conviction.map(HoldingConvictionEntity::getHoldingHorizonMonths).orElse(null),
                conviction.map(HoldingConvictionEntity::getHorizonStated).orElse(null),
                conviction.map(HoldingConvictionEntity::getCoreOverride).orElse(null),
                history,
                closes,
                missing);
    }

    /**
     * The most recent screening row for a symbol, whatever its date.
     *
     * <p>Not "today's": today has no rows until the 14:00 screening, and this app restarts every
     * morning, so anything reading a single date sees an empty table for most of the day
     * (CLAUDE.md Gotcha 20).
     */
    private MultibaggerScoreEntity latestScore(String symbol) {
        List<MultibaggerScoreEntity> history = scoreRepository.findHistoryBySymbol(symbol);
        return history.isEmpty() ? null : history.get(0);
    }

    private List<AnnualYear> annualHistory(String symbol) {
        List<AnnualFundamentalsEntity> rows = fundamentalsRepository.findHistory(symbol);
        List<AnnualYear> out = new ArrayList<>(rows.size());
        for (AnnualFundamentalsEntity r : rows) {
            out.add(new AnnualYear(r.getFiscalYear() == null ? 0 : r.getFiscalYear(),
                    r.getSales(), r.getNetProfit(), r.getInterestCost(), r.getBorrowings(),
                    r.getEquity(), r.getTotalAssets(), r.getOperatingCashFlow(), r.getShareCount()));
        }
        return out;
    }

    /**
     * Daily closes for D5, cached for the day.
     *
     * <p>Returns an empty list on any broker failure, and <b>caches that emptiness for the rest of
     * the day</b>. A delisted or renamed ticker would otherwise be retried on every call, burning
     * the shared Kite rate limit that the 15:05-15:28 report jobs depend on (B-027). The cost is
     * that a transient failure leaves D5 unmeasured until tomorrow — which the coverage text says
     * out loud, rather than scoring it zero.
     */
    List<PricePoint> dailyCloses(String symbol) {
        List<PricePoint> cached = candleCache.get(symbol);
        if (cached != null) return cached;

        List<PricePoint> points = new ArrayList<>();
        try {
            String from = LocalDate.now().minusYears(config.getPriceHistoryYears()).format(ISO);
            String to = LocalDate.now().format(ISO);
            List<Map<String, Object>> candles = marketDataService.getRecentCandles(symbol, "day", from, to);
            if (candles != null) {
                for (Map<String, Object> c : candles) {
                    LocalDate d = parseCandleDate(c.get("timestamp"));
                    Object close = c.get("close");
                    if (d != null && close instanceof Number n && n.doubleValue() > 0) {
                        points.add(new PricePoint(d, n.doubleValue()));
                    }
                }
            }
            points.sort(java.util.Comparator.comparing(PricePoint::date));
        } catch (Exception e) {
            log.debug("Daily closes unavailable for {} (D5 will be unmeasured): {}",
                    symbol, e.getMessage());
        }
        candleCache.put(symbol, points);
        return points;
    }

    static LocalDate parseCandleDate(Object raw) {
        if (raw == null) return null;
        if (raw instanceof LocalDate ld) return ld;
        String s = raw.toString();
        int t = s.indexOf('T');
        if (t > 0) s = s.substring(0, t);
        if (s.length() > 10) s = s.substring(0, 10);
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ hysteresis inputs

    private CoreTier previousEffectiveTier(List<CoreHoldingSnapshotEntity> history, LocalDate today) {
        for (CoreHoldingSnapshotEntity row : history) {
            if (row.getClassifiedOn().isBefore(today) && row.getEffectiveTier() != null) {
                return parseTier(row.getEffectiveTier());
            }
        }
        return null;
    }

    /** Friday rows before today, newest first — the weekly anchors hysteresis counts. */
    private List<CoreHysteresis.AnchorReading> weeklyAnchors(List<CoreHoldingSnapshotEntity> history,
                                                             LocalDate today) {
        List<CoreHysteresis.AnchorReading> out = new ArrayList<>();
        for (CoreHoldingSnapshotEntity row : history) {
            if (!row.getClassifiedOn().isBefore(today)) continue;
            if (row.getClassifiedOn().getDayOfWeek() != DayOfWeek.FRIDAY) continue;
            out.add(new CoreHysteresis.AnchorReading(row.getClassifiedOn(), parseTier(row.getTier())));
            if (out.size() >= config.getHysteresisRuns() + 2) break;
        }
        return out;
    }

    static CoreTier parseTier(String raw) {
        if (raw == null) return CoreTier.UNCLASSIFIED;
        try {
            return CoreTier.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return CoreTier.UNCLASSIFIED;
        }
    }

    // ------------------------------------------------------------------ reads

    /** Latest classification per holding, from the newest date that actually has rows. DB-only. */
    public List<CoreHoldingView> latestClassifications() {
        List<LocalDate> dates = snapshotRepository.findClassificationDates();
        if (dates.isEmpty()) return List.of();
        List<CoreHoldingSnapshotEntity> rows = snapshotRepository.findByClassifiedOn(dates.get(0));

        // Only rows for stocks currently held (B-061). A snapshot is keyed (symbol, date), but a
        // holding's symbol is NOT stable within a day: the 15:18 broker sync can re-prefix
        // BSE:INFY as NSE:INFY, and a classification run either side of it writes both under the
        // same date. Without this filter the dashboard showed 49 rows against 33 holdings, listing
        // each re-prefixed stock twice. Gotcha 16's rule ("reports use findActive(), never
        // findAll()") applied to the read side: a row for something not held must never surface,
        // and must never be able to grant core protection through effectiveTiers().
        Set<String> active = new HashSet<>();
        for (HoldingsEntity h : holdingsRepository.findActive()) active.add(h.getSymbol());

        List<CoreHoldingView> out = new ArrayList<>();
        for (CoreHoldingSnapshotEntity r : retainActive(rows, active)) {
            out.add(toView(r, decodeGates(r.getGatesJson())));
        }
        out.sort((a, b) -> {
            int t = Integer.compare(b.effectiveTier().ordinal(), a.effectiveTier().ordinal());
            if (t != 0) return t;
            int da = a.durabilityScore() == null ? -1 : a.durabilityScore();
            int db = b.durabilityScore() == null ? -1 : b.durabilityScore();
            return Integer.compare(db, da);
        });
        return out;
    }

    /**
     * Drop snapshot rows for anything not currently held. Pure and package-private so the rule is
     * testable without a database — see {@code CoreStaleRowTest}.
     *
     * <p>An empty active set drops everything, deliberately: "we hold nothing" and "we could not
     * read the holdings" must be distinguished by the caller before it gets here, not silently
     * resolved into showing stale rows.
     */
    static List<CoreHoldingSnapshotEntity> retainActive(List<CoreHoldingSnapshotEntity> rows,
                                                        Set<String> activeSymbols) {
        List<CoreHoldingSnapshotEntity> out = new ArrayList<>();
        if (rows == null) return out;
        for (CoreHoldingSnapshotEntity r : rows) {
            if (r != null && r.getSymbol() != null && activeSymbols.contains(r.getSymbol())) out.add(r);
        }
        return out;
    }

    /** Map of symbol to effective tier for the overlay. Empty when nothing has been classified. */
    public Map<String, CoreTier> effectiveTiers() {
        Map<String, CoreTier> map = new ConcurrentHashMap<>();
        for (CoreHoldingView v : latestClassifications()) map.put(v.symbol(), v.effectiveTier());
        return map;
    }

    public List<CoreHoldingView> history(String symbol, int days) {
        List<CoreHoldingView> out = new ArrayList<>();
        for (CoreHoldingSnapshotEntity r :
                snapshotRepository.findSeries(symbol, LocalDate.now().minusDays(days))) {
            out.add(toView(r, decodeGates(r.getGatesJson())));
        }
        return out;
    }

    /** Tier changes between the newest classification date and the one closest to a week earlier. */
    public List<String> tierChangesSince(int days) {
        List<LocalDate> dates = snapshotRepository.findClassificationDates();
        if (dates.size() < 2) return List.of();
        LocalDate newest = dates.get(0);
        LocalDate target = newest.minusDays(days);
        LocalDate comparison = null;
        for (LocalDate d : dates) {
            if (d.isAfter(target) || d.equals(newest)) continue;
            comparison = d;
            break;
        }
        if (comparison == null) return List.of();

        Map<String, String> before = new LinkedHashMap<>();
        for (CoreHoldingSnapshotEntity r : snapshotRepository.findByClassifiedOn(comparison)) {
            before.put(r.getSymbol(), r.getEffectiveTier());
        }
        List<String> changes = new ArrayList<>();
        for (CoreHoldingSnapshotEntity r : snapshotRepository.findByClassifiedOn(newest)) {
            String was = before.get(r.getSymbol());
            if (was != null && !was.equals(r.getEffectiveTier())) {
                changes.add(String.format("%s: %s -> %s", displayName(r), was, r.getEffectiveTier()));
            }
        }
        return changes;
    }

    private String displayName(CoreHoldingSnapshotEntity r) {
        return r.getTradingSymbol() != null ? r.getTradingSymbol() : r.getSymbol();
    }

    // ------------------------------------------------------------------ observation evidence

    /**
     * Record the technical exit alerts that fired on a core holding today (SPEC §35.5). Written on
     * today's row when it exists, buffered when it does not — the 10:00 alert job runs half an
     * hour before the 10:30 classification.
     */
    public void recordObservedAlerts(String symbol, List<String> alertTypes) {
        if (alertTypes == null || alertTypes.isEmpty()) return;
        LocalDate today = LocalDate.now();
        rollCachesIfNewDay(today);

        List<String> merged = pendingObservedAlerts
                .computeIfAbsent(symbol, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (merged) {
            for (String t : alertTypes) {
                if (!merged.contains(t)) merged.add(t);
            }
        }
        snapshotRepository.findBySymbolAndClassifiedOn(symbol, today).ifPresent(row -> {
            // Union with what the row already holds, never a straight overwrite. The in-memory
            // buffer is lost on a restart, so a 12:00 alert written over a row that already carried
            // the 10:00 one would silently delete the earlier observation - and the observation
            // trail is the entire evidence base for deciding whether to arm suppression.
            row.setObservedAlerts(join(unionAlerts(row.getObservedAlerts(), merged)));
            snapshotRepository.save(row);
        });
    }

    private void rollCachesIfNewDay(LocalDate today) {
        if (!today.equals(cacheDate)) {
            candleCache.clear();
            cacheDate = today;
        }
        if (!today.equals(observedDate)) {
            pendingObservedAlerts.clear();
            observedDate = today;
        }
    }

    // ------------------------------------------------------------------ encoding

    static String encodeGates(List<Gate> gates) {
        StringBuilder sb = new StringBuilder();
        for (Gate g : gates) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(g.code()).append('|').append(g.name()).append('|')
              .append(g.status()).append('|').append(g.reason() == null ? "" : g.reason());
        }
        return sb.toString();
    }

    static List<Gate> decodeGates(String raw) {
        List<Gate> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String line : raw.split("\n")) {
            String[] parts = line.split("\\|", 4);
            if (parts.length < 3) continue;
            GateStatus status;
            try {
                status = GateStatus.valueOf(parts[2]);
            } catch (IllegalArgumentException e) {
                continue;
            }
            out.add(new Gate(parts[0], parts[1], status, parts.length > 3 ? parts[3] : ""));
        }
        return out;
    }

    /**
     * Merge newly-observed alert types into what a row already holds, preserving order and
     * dropping duplicates.
     *
     * <p>Never an overwrite. The in-memory buffer is lost on a restart, so writing it straight over
     * a row that already carried the morning's observations would silently delete them — and those
     * observations are the entire evidence base for the decision to arm suppression.
     */
    static List<String> unionAlerts(String existing, List<String> incoming) {
        List<String> out = new ArrayList<>(split(existing));
        if (incoming == null) return out;
        synchronized (incoming) {
            for (String t : incoming) {
                if (t != null && !t.isBlank() && !out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    static String join(List<String> items) {
        return items == null || items.isEmpty() ? null : String.join("\n", items);
    }

    static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return List.of(raw.split("\n"));
    }

    private CoreHoldingView toView(CoreHoldingSnapshotEntity r, List<Gate> gates) {
        return new CoreHoldingView(
                r.getSymbol(), r.getTradingSymbol(), r.getClassifiedOn(),
                parseTier(r.getTier()), parseTier(r.getEffectiveTier()),
                r.getDurabilityScore(), r.getDurabilityCoverage(), gates,
                split(r.getSoftSignals()), split(r.getReasons()), split(r.getMissingInputs()),
                split(r.getObservedAlerts()), r.getPendingChange(), r.getOverrideApplied());
    }
}
