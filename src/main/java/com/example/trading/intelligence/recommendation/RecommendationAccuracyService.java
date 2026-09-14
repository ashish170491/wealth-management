package com.example.trading.intelligence.recommendation;

import com.example.trading.marketdata.MarketDataService;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import com.example.trading.persistence.RecommendationDimensionEntity;
import com.example.trading.persistence.RecommendationDimensionRepository;
import com.example.trading.persistence.RecommendationEntity;
import com.example.trading.persistence.RecommendationEntity.Source;
import com.example.trading.persistence.RecommendationOutcomeEntity;
import com.example.trading.persistence.RecommendationOutcomeRepository;
import com.example.trading.persistence.RecommendationRepository;
import com.example.trading.persistence.SectorReversalEntity;
import com.example.trading.persistence.SectorReversalRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Computes calibration metrics for past recommendations — hit-rate, mean
 * return, mean excess return vs Nifty, and Information Coefficient (score →
 * realized return correlation) — sliced by source and horizon.
 *
 * See SPEC.md §23 Recommendation Accuracy Tracking.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecommendationAccuracyService {

    private final RecommendationRepository recommendationRepository;
    private final RecommendationOutcomeRepository outcomeRepository;
    private final MultibaggerScoreRepository multibaggerScoreRepository;
    private final RecommendationDimensionRepository dimensionRepository;
    private final SectorReversalRepository sectorReversalRepository;
    private final MarketDataService marketDataService;
    private final com.example.trading.learning.validation.DailyCandleCache candleCache;

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_DATE;

    /** Full accuracy summary grouped by source × horizon. */
    public List<SourceHorizonStats> summarize() {
        List<SourceHorizonStats> out = new ArrayList<>();
        for (RecommendationEntity.Source source : RecommendationEntity.Source.values()) {
            for (int horizon : RecommendationOutcomeEntity.DEFAULT_HORIZONS) {
                SourceHorizonStats stats = computeFor(source, horizon);
                if (stats.getSampleSize() > 0) out.add(stats);
            }
        }
        return out;
    }

    public SourceHorizonStats computeFor(RecommendationEntity.Source source, int horizonDays) {
        List<RecommendationOutcomeEntity> outcomes = outcomeRepository.findBySourceAndHorizon(
                source.name(), horizonDays,
                horizonDays + RecommendationOutcomeScheduler.MEASUREMENT_GRACE_DAYS);
        if (outcomes.isEmpty()) {
            return SourceHorizonStats.builder()
                    .source(source.name())
                    .horizonDays(horizonDays)
                    .sampleSize(0)
                    .build();
        }

        // Build lookups off the parent recommendation rows — score (for IC),
        // target/SL presence (for level-calibration denominators).
        Map<Long, Integer> scoreById = new HashMap<>();
        Map<Long, Boolean> hasTargetById = new HashMap<>();
        Map<Long, Boolean> hasSlById = new HashMap<>();
        Map<Long, String> verdictById = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        for (RecommendationOutcomeEntity o : outcomes) ids.add(o.getRecommendationId());
        for (RecommendationEntity r : recommendationRepository.findAllById(ids)) {
            if (r.getScore() != null) scoreById.put(r.getId(), r.getScore());
            hasTargetById.put(r.getId(), r.getTargetPrice() != null && r.getTargetPrice() > 0);
            hasSlById.put(r.getId(), r.getStopLossPrice() != null && r.getStopLossPrice() > 0);
            verdictById.put(r.getId(), r.getVerdict());
        }

        // A "hit" means the reading was RIGHT, and for a directional reading that is not the same
        // as the price going up. Every other engine here says "this is worth owning", so a positive
        // return is a hit. Macro exposure says which way the ground is tilting, so a HEADWIND that
        // is followed by a fall is a correct reading, not a failed one. Scoring it the usual way
        // would report a feature that called every headwind perfectly as having a hit rate near
        // zero - and would make the whole panel unreadable.
        final boolean directional = source == RecommendationEntity.Source.MACRO_EVENT;

        double sumReturn = 0;
        double sumExcess = 0;
        int positive = 0;
        int targetHit = 0;
        int slHit = 0;
        int picksWithTarget = 0;
        int picksWithSl = 0;
        int excessCount = 0;
        List<double[]> xy = new ArrayList<>();

        for (RecommendationOutcomeEntity o : outcomes) {
            sumReturn += o.getReturnPercent();
            if (directional) {
                Double basis = o.getExcessReturnPercent() != null
                        ? o.getExcessReturnPercent() : o.getReturnPercent();
                boolean headwind = "HEADWIND".equalsIgnoreCase(verdictById.get(o.getRecommendationId()));
                if (headwind ? basis < 0 : basis > 0) positive++;
            } else if (o.getReturnPercent() > 0) {
                positive++;
            }
            // Level-calibration: only count outcomes whose recommendation actually proposed a level.
            boolean hadTarget = Boolean.TRUE.equals(hasTargetById.get(o.getRecommendationId()));
            boolean hadSl = Boolean.TRUE.equals(hasSlById.get(o.getRecommendationId()));
            if (hadTarget) {
                picksWithTarget++;
                if (Boolean.TRUE.equals(o.getTargetHit())) targetHit++;
            }
            if (hadSl) {
                picksWithSl++;
                if (Boolean.TRUE.equals(o.getStopLossHit())) slHit++;
            }
            if (o.getExcessReturnPercent() != null) {
                sumExcess += o.getExcessReturnPercent();
                excessCount++;
            }
            Integer score = scoreById.get(o.getRecommendationId());
            if (score != null) xy.add(new double[]{score, o.getReturnPercent()});
        }

        int n = outcomes.size();
        return SourceHorizonStats.builder()
                .source(source.name())
                .horizonDays(horizonDays)
                .sampleSize(n)
                .hitRatePercent(100.0 * positive / n)
                .meanReturnPercent(sumReturn / n)
                .meanExcessReturnPercent(excessCount > 0 ? sumExcess / excessCount : null)
                // Target/SL rates: denominator = picks that carried a target/SL.
                // When no picks in the cell proposed levels, rate is null (distinct from "zero hits").
                .targetHitRatePercent(picksWithTarget > 0 ? 100.0 * targetHit / picksWithTarget : null)
                .stopLossHitRatePercent(picksWithSl > 0 ? 100.0 * slHit / picksWithSl : null)
                .picksWithTarget(picksWithTarget)
                .picksWithStopLoss(picksWithSl)
                .targetCoveragePercent(100.0 * picksWithTarget / n)
                .stopLossCoveragePercent(100.0 * picksWithSl / n)
                .informationCoefficient(pearson(xy))
                .build();
    }

    /**
     * Why an IC could not be computed. A bare {@code null} conflates three very different
     * situations, and that ambiguity is not academic: the Valuation dimension sat pinned at
     * a constant 50 for months under B-018 and reported {@code IC = null} with a perfectly
     * healthy sampleSize — the exact signature of "not enough data yet". The Institutional
     * Interest dimension hid the same way for three months before it (SPEC §12.5). The one
     * mechanism that could have caught either was blind to the difference.
     */
    public enum IcUnavailableReason {
        /** Fewer than 3 paired observations. Genuinely wait for more data. */
        INSUFFICIENT_SAMPLES,
        /** The dimension returned the same score for every stock — it is broken, not immature. */
        CONSTANT_SCORE,
        /** Every stock realised an identical return — implausible; suspect the price feed. */
        CONSTANT_RETURN
    }

    /** Pearson correlation between two columns of {@code xy}. Null if n<3 or variance is zero. */
    private Double pearson(List<double[]> xy) {
        return pearsonWithReason(xy).ic();
    }

    /** Correlation plus, when it is null, the reason why. */
    private IcResult pearsonWithReason(List<double[]> xy) {
        int n = xy.size();
        if (n < 3) return new IcResult(null, IcUnavailableReason.INSUFFICIENT_SAMPLES);
        double sx = 0, sy = 0;
        for (double[] p : xy) { sx += p[0]; sy += p[1]; }
        double mx = sx / n, my = sy / n;
        double cov = 0, vx = 0, vy = 0;
        for (double[] p : xy) {
            double dx = p[0] - mx, dy = p[1] - my;
            cov += dx * dy;
            vx += dx * dx;
            vy += dy * dy;
        }
        if (vx <= 0) return new IcResult(null, IcUnavailableReason.CONSTANT_SCORE);
        if (vy <= 0) return new IcResult(null, IcUnavailableReason.CONSTANT_RETURN);
        return new IcResult(cov / Math.sqrt(vx * vy), null);
    }

    /** Correlation result carrying the reason it is absent, when it is. */
    public record IcResult(Double ic, IcUnavailableReason reason) {}

    @Data
    @Builder
    public static class SourceHorizonStats {
        private String source;
        private int horizonDays;
        private int sampleSize;
        private Double hitRatePercent;
        private Double meanReturnPercent;
        private Double meanExcessReturnPercent;
        /** Hit rate among picks that actually carried a target (denominator = {@link #picksWithTarget}, not total). Null when no picks in the cell proposed a target. */
        private Double targetHitRatePercent;
        /** Hit rate among picks that actually carried a stop-loss (denominator = {@link #picksWithStopLoss}). Null when no picks in the cell proposed an SL. */
        private Double stopLossHitRatePercent;
        /** Count of picks in this cell that proposed a target price — the denominator for {@link #targetHitRatePercent}. */
        private int picksWithTarget;
        /** Count of picks in this cell that proposed a stop-loss — the denominator for {@link #stopLossHitRatePercent}. */
        private int picksWithStopLoss;
        /** % of picks in this cell that carried a target — tells the reader how much of the sample the target-hit rate is even computed over. */
        private Double targetCoveragePercent;
        /** % of picks in this cell that carried a stop-loss. */
        private Double stopLossCoveragePercent;
        /** Pearson correlation between score and realized return; null if insufficient data. */
        private Double informationCoefficient;
    }

    // ================================================================
    // Per-dimension IC (SPEC.md §23)
    // Reads multibagger_scores directly (3+ months of history already
    // accumulated) and fetches realized prices via Kite. Independent of the
    // recommendation_outcomes table, so it produces usable numbers today.
    // ================================================================

    /** One row per scoring dimension + composite, for a given source and horizon. */
    @Data
    @Builder
    public static class DimensionIcStats {
        private String source;
        private String dimension;
        private int horizonDays;
        private int sampleSize;
        private Double informationCoefficient;   // Pearson correlation score→return
        /**
         * Why {@code informationCoefficient} is null, when it is: INSUFFICIENT_SAMPLES /
         * CONSTANT_SCORE / CONSTANT_RETURN. CONSTANT_SCORE means the dimension is broken —
         * it ranks nothing — as opposed to merely lacking data. Null when an IC was computed.
         */
        private String icUnavailableReason;
        private Double meanScore;
        private Double meanReturnPercent;
    }

    /**
     * Compute per-dimension IC for MULTIBAGGER scoring at the given horizon.
     * Back-compat overload — equivalent to {@code computeDimensionIC(MULTIBAGGER, horizonDays)}.
     */
    public List<DimensionIcStats> computeDimensionIC(int horizonDays) {
        return computeDimensionIC(Source.MULTIBAGGER, horizonDays);
    }

    /**
     * Compute per-dimension IC for the given source and horizon. Returns one row
     * per sub-score plus a "Composite (reference)" row. Rows whose sample has
     * n &lt; 3 return null IC — the caller should display sample size prominently
     * so thin cells are not over-interpreted.
     *
     * <p>Each engine's sub-scores come from wherever they are already persisted:
     * MULTIBAGGER from {@code multibagger_scores}, SECTOR_REVERSAL from
     * {@code sector_reversal_signals}, and QUANT_DISCOVERY from the
     * {@code recommendation_dimensions} sidecar (it has no score table of its own).
     * The realized-return half is identical across sources — Kite daily candles,
     * (date + horizon) → close, vs the price at issue. See SPEC.md §23.2.
     */
    public List<DimensionIcStats> computeDimensionIC(Source source, int horizonDays) {
        LocalDate cutoff = LocalDate.now().minusDays(horizonDays);
        DimensionDataset ds = switch (source) {
            case MULTIBAGGER -> multibaggerDataset(cutoff);
            case QUANT_DISCOVERY -> quantDataset(cutoff);
            case SECTOR_REVERSAL -> sectorDataset(cutoff);
            // Macro exposure keeps its per-factor sub-scores in the same sidecar QUANT uses, so
            // "which macro factor actually predicted anything" is answerable by the existing IC
            // machinery rather than a private scoreboard (SPEC 39.3 rule 1).
            case MACRO_EVENT -> sidecarDataset(Source.MACRO_EVENT, cutoff, macroFactorLabels());
        };
        return computeIcFromSamples(source, horizonDays, ds.orderedDims(), ds.samples());
    }

    /** One scored pick with the inputs needed to measure its realized return. */
    private record DimensionSample(String symbol, LocalDate date, double price,
                                   Map<String, Integer> dims, Integer composite) {}

    /** A source's ordered dimension labels plus its scored picks. */
    private record DimensionDataset(List<String> orderedDims, List<DimensionSample> samples) {}

    // ---- Per-source dataset builders ----

    /**
     * MULTIBAGGER sub-scores come from {@code multibagger_scores} columns, NOT from the
     * {@code recommendation_dimensions} sidecar — the sidecar exists only for
     * QUANT_DISCOVERY, which has no score table of its own.
     *
     * <p><b>Adding a new MULTIBAGGER signal therefore requires two things</b>: a nullable
     * {@code Integer} column on {@code MultibaggerScoreEntity}, and an entry in the list
     * below. Writing sidecar rows for a multibagger pick does nothing — they are silently
     * ignored here, and the signal would sit unmeasured indefinitely while appearing to be
     * tracked. That is exactly how Institutional Interest stayed constant for three months.
     *
     * <p>Under-Discovery and Insider Pulse are included even though neither currently moves
     * the composite (one is a lens, the other is in shadow mode). Measuring them <i>before</i>
     * they can act is the point: SPEC §25.5's promotion gate needs IC history to rule on.
     */
    private DimensionDataset multibaggerDataset(LocalDate cutoff) {
        List<String> dims = List.of("Technical Momentum", "Volume Accumulation", "Relative Strength",
                "Price Structure", "Valuation", "Institutional Interest", "Sector Tailwind", "Financial Quality",
                "Under-Discovery", "Insider Pulse", "Capex Cycle");
        List<DimensionSample> samples = new ArrayList<>();
        for (MultibaggerScoreEntity s : multibaggerScoreRepository.findAll()) {
            if (s.getScreeningDate() == null || s.getScreeningDate().isAfter(cutoff) || s.getCurrentPrice() <= 0) continue;
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("Technical Momentum", s.getTechnicalMomentumScore());
            m.put("Volume Accumulation", s.getVolumeAccumulationScore());
            m.put("Relative Strength", s.getRelativeStrengthScore());
            m.put("Price Structure", s.getPriceStructureScore());
            m.put("Valuation", s.getValuationScore());
            m.put("Institutional Interest", s.getInstitutionalInterestScore());
            m.put("Sector Tailwind", s.getSectorTailwindScore());
            m.put("Financial Quality", s.getFinancialQualityScore());
            m.put("Under-Discovery", s.getUnderDiscoveryScore());
            m.put("Insider Pulse", s.getInsiderPulseScore());
            m.put("Capex Cycle", s.getCapexScore());
            samples.add(new DimensionSample(s.getSymbol(), s.getScreeningDate(), s.getCurrentPrice(), m, s.getCompositeScore()));
        }
        return new DimensionDataset(dims, samples);
    }

    private DimensionDataset sectorDataset(LocalDate cutoff) {
        List<String> dims = List.of("MACD", "RSI", "Volume", "Price Structure");
        List<DimensionSample> samples = new ArrayList<>();
        for (SectorReversalEntity s : sectorReversalRepository.findAll()) {
            if (s.getScanDate() == null || s.getScanDate().isAfter(cutoff)
                    || s.getCurrentPrice() == null || s.getCurrentPrice() <= 0) continue;
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("MACD", round(s.getMacdScore()));
            m.put("RSI", round(s.getRsiScore()));
            m.put("Volume", round(s.getVolumeScore()));
            m.put("Price Structure", round(s.getPriceScore()));
            samples.add(new DimensionSample(s.getSymbol(), s.getScanDate(), s.getCurrentPrice(), m, round(s.getUpsideScore())));
        }
        return new DimensionDataset(dims, samples);
    }

    private DimensionDataset quantDataset(LocalDate cutoff) {
        return sidecarDataset(Source.QUANT_DISCOVERY, cutoff,
                List.of("Earnings Growth", "Insider Activity", "Valuation", "Price Momentum", "Volume"));
    }

    /** Every macro factor, so a factor with no picks yet still shows as an empty row rather than vanishing. */
    private static List<String> macroFactorLabels() {
        List<String> out = new ArrayList<>();
        for (com.example.trading.macro.MacroFactor f : com.example.trading.macro.MacroFactor.values()) {
            out.add(f.name());
        }
        return out;
    }

    /**
     * Sub-scores for an engine that has no score table of its own, read from the
     * {@code recommendation_dimensions} sidecar.
     *
     * <p>Generalised from the QUANT-only version on 2026-09-10 so macro exposure could reuse it.
     * Note what this does <b>not</b> do: it never falls back to the multibagger score columns.
     * Writing sidecar rows for an engine whose IC is read from hardcoded columns does nothing at
     * all - no error, no IC row - which is B-030, and the way to avoid repeating it is for each
     * engine to have exactly one place its sub-scores live.
     */
    private DimensionDataset sidecarDataset(Source source, LocalDate cutoff, List<String> dims) {
        List<RecommendationEntity> recos = recommendationRepository
                .findBySourceOrderByIssuedDateDesc(source.name());
        List<RecommendationEntity> eligible = new ArrayList<>();
        for (RecommendationEntity r : recos) {
            if (r.getIssuedDate() != null && !r.getIssuedDate().isAfter(cutoff) && r.getIssuedPrice() > 0) {
                eligible.add(r);
            }
        }
        if (eligible.isEmpty()) return new DimensionDataset(dims, List.of());

        // Bulk-load this engine's sub-scores from the sidecar, grouped by recommendation.
        Set<Long> ids = new HashSet<>();
        for (RecommendationEntity r : eligible) ids.add(r.getId());
        Map<Long, Map<String, Integer>> dimsById = new HashMap<>();
        for (RecommendationDimensionEntity d : dimensionRepository.findByRecommendationIdIn(ids)) {
            dimsById.computeIfAbsent(d.getRecommendationId(), k -> new LinkedHashMap<>())
                    .put(d.getDimension(), d.getScore());
        }

        List<DimensionSample> samples = new ArrayList<>();
        for (RecommendationEntity r : eligible) {
            Map<String, Integer> m = dimsById.get(r.getId());
            if (m == null || m.isEmpty()) continue; // picks issued before sub-scores were persisted
            samples.add(new DimensionSample(r.getSymbol(), r.getIssuedDate(), r.getIssuedPrice(), m, r.getScore()));
        }
        return new DimensionDataset(dims, samples);
    }

    private static Integer round(Double d) {
        return d == null ? null : (int) Math.round(d);
    }

    // ---- Shared IC core (source-independent) ----

    private List<DimensionIcStats> computeIcFromSamples(Source source, int horizonDays,
                                                        List<String> orderedDims, List<DimensionSample> samples) {
        log.info("Per-dimension IC ({} {}d): {} scored picks eligible", source, horizonDays, samples.size());
        if (samples.isEmpty()) return emptyDimensionStats(source, horizonDays, orderedDims);

        // Pre-warm the per-symbol candle cache so the loop below is all memory hits.
        Set<String> symbols = new LinkedHashSet<>();
        for (DimensionSample s : samples) symbols.add(s.symbol());
        warmCandleCache(symbols, horizonDays);

        // Realized return for each pick (from cache — zero Kite calls here).
        List<SampleOutcome> outcomes = new ArrayList<>();
        int skipped = 0;
        for (DimensionSample s : samples) {
            Double priceAtAnniv = closePriceFromCache(s.symbol(), s.date().plusDays(horizonDays));
            if (priceAtAnniv == null || priceAtAnniv <= 0) {
                skipped++;
                continue;
            }
            double retPct = (priceAtAnniv - s.price()) / s.price() * 100.0;
            outcomes.add(new SampleOutcome(s.dims(), s.composite(), retPct));
        }
        log.info("Per-dimension IC ({} {}d): {} outcomes collected, {} skipped (price unavailable)",
                source, horizonDays, outcomes.size(), skipped);
        if (outcomes.isEmpty()) return emptyDimensionStats(source, horizonDays, orderedDims);

        List<DimensionIcStats> out = new ArrayList<>();
        for (String dim : orderedDims) {
            out.add(ic(source, dim, horizonDays, outcomes, so -> so.dims.get(dim)));
        }
        out.add(ic(source, "Composite (reference)", horizonDays, outcomes, so -> so.composite));
        return out;
    }

    /** Realized-return view of a sample, decoupled from the source entity type. */
    private static final class SampleOutcome {
        final Map<String, Integer> dims;
        final Integer composite;
        final double returnPct;
        SampleOutcome(Map<String, Integer> dims, Integer composite, double returnPct) {
            this.dims = dims;
            this.composite = composite;
            this.returnPct = returnPct;
        }
    }

    /**
     * IC helper. {@code scoreFn} returns the dimension sub-score (nullable — e.g.
     * Financial Quality is null on rows scored before that dimension existed).
     * Rows with null scores are skipped, so {@code sampleSize} varies per
     * dimension even within the same horizon.
     */
    private DimensionIcStats ic(Source source, String dimension, int horizonDays, List<SampleOutcome> outcomes,
                                 java.util.function.Function<SampleOutcome, Integer> scoreFn) {
        double sumScore = 0;
        double sumRet = 0;
        List<double[]> xy = new ArrayList<>();
        int n = 0;
        for (SampleOutcome so : outcomes) {
            Integer score = scoreFn.apply(so);
            if (score == null) continue;
            xy.add(new double[]{score, so.returnPct});
            sumScore += score;
            sumRet += so.returnPct;
            n++;
        }
        IcResult ic = pearsonWithReason(xy);

        // A dimension that scored every stock identically is broken, not immature — say so
        // loudly rather than emitting a null that reads as "not enough data yet" (B-018).
        if (ic.reason() == IcUnavailableReason.CONSTANT_SCORE) {
            log.warn("Per-dimension IC: {} dimension '{}' returned a CONSTANT score ({}) across all {} "
                    + "picks at {}d — it contributes zero ranking information and its weight is "
                    + "effectively a fixed offset. Check its data source.",
                    source.name(), dimension, n > 0 ? sumScore / n : null, n, horizonDays);
        }

        return DimensionIcStats.builder()
                .source(source.name())
                .dimension(dimension)
                .horizonDays(horizonDays)
                .sampleSize(n)
                .informationCoefficient(ic.ic())
                .icUnavailableReason(ic.reason() == null ? null : ic.reason().name())
                .meanScore(n > 0 ? sumScore / n : null)
                .meanReturnPercent(n > 0 ? sumRet / n : null)
                .build();
    }

    private List<DimensionIcStats> emptyDimensionStats(Source source, int horizonDays, List<String> orderedDims) {
        List<DimensionIcStats> out = new ArrayList<>();
        for (String d : orderedDims) {
            out.add(DimensionIcStats.builder().source(source.name()).dimension(d)
                    .horizonDays(horizonDays).sampleSize(0)
                    .icUnavailableReason(IcUnavailableReason.INSUFFICIENT_SAMPLES.name()).build());
        }
        out.add(DimensionIcStats.builder().source(source.name()).dimension("Composite (reference)")
                .horizonDays(horizonDays).sampleSize(0).build());
        return out;
    }

    /**
     * Ensure the shared candle cache reaches back far enough for this horizon.
     *
     * <p>Delegates to {@code DailyCandleCache} rather than keeping a second cache of its own.
     * Every Kite request in this process shares one ~2.9 requests-per-second gate (B-027), and
     * exhausting it has twice starved the afternoon jobs (B-014, B-049), so two caches over the
     * same symbols would double the cost of identical information. The window formula is
     * unchanged, so the sample this method produces is the same as before the extraction.
     */
    private void warmCandleCache(Set<String> symbols, int horizonDays) {
        LocalDate from = LocalDate.now().minusDays(Math.max(horizonDays + 30, 400));
        candleCache.warm(symbols, from);
    }

    /**
     * Close on or before {@code targetDate} from the shared cache, walking back over weekends
     * and holidays. Null when unavailable — the caller drops the observation rather than
     * substituting a price from a different period.
     */
    private Double closePriceFromCache(String symbol, LocalDate targetDate) {
        return candleCache.closeOnOrBefore(symbol, targetDate);
    }
}
