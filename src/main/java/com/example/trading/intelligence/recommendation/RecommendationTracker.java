package com.example.trading.intelligence.recommendation;

import com.example.trading.marketdata.MarketDataService;
import com.example.trading.persistence.RecommendationDimensionEntity;
import com.example.trading.persistence.RecommendationDimensionRepository;
import com.example.trading.persistence.RecommendationEntity;
import com.example.trading.persistence.RecommendationEntity.Source;
import com.example.trading.persistence.RecommendationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Capture service invoked by scoring engines when a pick crosses the
 * recommendation threshold. Dedupes per (symbol, source, issuedDate) and never
 * throws — a tracking failure must not break the caller's primary workflow.
 *
 * See SPEC.md §23 Recommendation Accuracy Tracking.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecommendationTracker {

    static final String NIFTY_SYMBOL = "NSE:NIFTY 50";

    private final RecommendationRepository recommendationRepository;
    private final RecommendationDimensionRepository dimensionRepository;
    private final MarketDataService marketDataService;
    private final com.example.trading.learning.ScoringVersionRegistry scoringVersionRegistry;

    /**
     * Record a pick. Upserts on (symbol, source, issuedDate) so same-day
     * re-runs do not create duplicates — the latest scoring wins.
     *
     * @return the persisted entity, or {@code null} if recording failed
     */
    public RecommendationEntity record(Source source,
                                       String symbol,
                                       double issuedPrice,
                                       Integer score,
                                       String grade,
                                       String verdict,
                                       Double targetPrice,
                                       Double stopLossPrice,
                                       String sector,
                                       String marketCapCategory) {
        return record(source, symbol, issuedPrice, score, grade, verdict,
                targetPrice, stopLossPrice, sector, marketCapCategory, null);
    }

    /**
     * Record a pick along with its per-dimension sub-scores (SPEC.md §23.2).
     * The sub-scores are persisted to {@code recommendation_dimensions} so
     * per-dimension IC can be computed later for engines that have no standalone
     * score table (notably QUANT_DISCOVERY). Pass {@code null}/empty to skip.
     *
     * @param dimensions ordered map of dimension label → raw sub-score
     * @return the persisted entity, or {@code null} if recording failed
     */
    public RecommendationEntity record(Source source,
                                       String symbol,
                                       double issuedPrice,
                                       Integer score,
                                       String grade,
                                       String verdict,
                                       Double targetPrice,
                                       Double stopLossPrice,
                                       String sector,
                                       String marketCapCategory,
                                       Map<String, Integer> dimensions) {
        return record(source, symbol, issuedPrice, score, grade, verdict, targetPrice, stopLossPrice,
                sector, marketCapCategory, dimensions, null);
    }

    /**
     * As above, with the Nifty level supplied by the caller.
     *
     * <p><b>Why this overload exists.</b> Every other overload fetches the index level from the
     * broker per row, which is correct when an engine records a handful of picks at the end of a
     * long scan. A macro event, though, records one row per exposed stock in a single pass -
     * potentially three hundred of them - and three hundred quote calls against a process-wide
     * gate of roughly 2.9 requests a second is ninety seconds of broker budget spent fetching the
     * same number three hundred times. Worse, that budget has twice starved the afternoon
     * schedulers, which abort silently at 15:30 (B-014, B-049).
     *
     * <p>The caller fetches the index once and passes it in. Null falls back to the per-row fetch,
     * so existing behaviour is unchanged.
     */
    public RecommendationEntity record(Source source,
                                       String symbol,
                                       double issuedPrice,
                                       Integer score,
                                       String grade,
                                       String verdict,
                                       Double targetPrice,
                                       Double stopLossPrice,
                                       String sector,
                                       String marketCapCategory,
                                       Map<String, Integer> dimensions,
                                       Double niftyIndexAtIssue) {
        if (source == null || symbol == null || symbol.isBlank() || issuedPrice <= 0) {
            log.debug("RecommendationTracker: skipping invalid input (source={}, symbol={}, price={})",
                    source, symbol, issuedPrice);
            return null;
        }
        // Normalise to an exchange-prefixed symbol (B-022). SECTOR_REVERSAL passed bare
        // names ("AXISBANK") straight through from EarlyUpsideScanner, while MULTIBAGGER
        // and QUANT_DISCOVERY passed "NSE:AXISBANK". Kite's /quote returns an empty
        // payload for a bare symbol, so every one of the 1,247 SECTOR_REVERSAL picks was
        // silently skipped by RecommendationOutcomeScheduler and the engine had ZERO
        // measured outcomes for four months. Normalising here — the single choke point
        // every engine goes through — fixes it for all current and future sources without
        // touching the DTOs and email templates that still use bare names.
        final String normalizedSymbol = symbol.contains(":") ? symbol : "NSE:" + symbol;

        try {
            LocalDate today = LocalDate.now();
            RecommendationEntity entity = recommendationRepository
                    .findBySymbolAndSourceAndIssuedDate(normalizedSymbol, source.name(), today)
                    .orElseGet(() -> RecommendationEntity.builder()
                            .source(source.name())
                            .symbol(normalizedSymbol)
                            .issuedDate(today)
                            .build());

            entity.setIssuedPrice(issuedPrice);
            entity.setNiftyIndexAtIssue(niftyIndexAtIssue != null ? niftyIndexAtIssue : fetchNiftyPriceQuietly());
            entity.setScore(score);
            entity.setGrade(grade);
            entity.setVerdict(verdict);
            entity.setTargetPrice(targetPrice);
            entity.setStopLossPrice(stopLossPrice);
            entity.setSector(sector);
            entity.setMarketCapCategory(marketCapCategory);
            // Provenance (SPEC §38.1). Resolved here rather than passed in, so the stamp on a
            // pick can never disagree with the stamp on the score row that produced it.
            entity.setScoringVersion(scoringVersionRegistry.forSource(source));

            RecommendationEntity saved = recommendationRepository.save(entity);
            persistDimensions(saved.getId(), dimensions);
            return saved;
        } catch (Exception e) {
            log.warn("RecommendationTracker: failed to record {} pick for {}: {}",
                    source, symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Replace a recommendation's dimension rows. Delete-then-insert keeps the
     * same-day upsert correct (re-scoring overwrites). Never throws — a
     * dimension-persistence failure must not lose the recommendation itself.
     */
    private void persistDimensions(Long recommendationId, Map<String, Integer> dimensions) {
        if (recommendationId == null || dimensions == null || dimensions.isEmpty()) return;
        try {
            dimensionRepository.deleteByRecommendationId(recommendationId);
            List<RecommendationDimensionEntity> rows = dimensions.entrySet().stream()
                    .filter(e -> e.getKey() != null && e.getValue() != null)
                    .map(e -> RecommendationDimensionEntity.builder()
                            .recommendationId(recommendationId)
                            .dimension(e.getKey())
                            .score(e.getValue())
                            .build())
                    .toList();
            dimensionRepository.saveAll(rows);
        } catch (Exception e) {
            log.warn("RecommendationTracker: failed to persist {} dimension(s) for reco {}: {}",
                    dimensions.size(), recommendationId, e.getMessage());
        }
    }

    /**
     * The current Nifty level, or null. Public so a caller recording many picks in one pass can
     * fetch it once and hand it to the overload above instead of paying for it per row.
     */
    public Double currentNiftyLevel() {
        return fetchNiftyPriceQuietly();
    }

    private Double fetchNiftyPriceQuietly() {
        try {
            Double p = marketDataService.getCurrentPrice(NIFTY_SYMBOL);
            return (p != null && p > 0) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }
}
