package com.example.trading.api;

import com.example.trading.intelligence.recommendation.RecommendationAccuracyReportService;
import com.example.trading.intelligence.recommendation.RecommendationAccuracyService;
import com.example.trading.intelligence.recommendation.RecommendationAccuracyService.SourceHorizonStats;
import com.example.trading.intelligence.recommendation.RecommendationOutcomeScheduler;
import com.example.trading.learning.ScreeningCoverageService;
import com.example.trading.persistence.RecommendationEntity;
import com.example.trading.persistence.RecommendationRepository;
import com.example.trading.persistence.ScreeningCoverageEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the recommendation accuracy tracker.
 * See SPEC.md §23.
 */
@RestController
@RequestMapping("/api/accuracy")
@Slf4j
@RequiredArgsConstructor
public class RecommendationAccuracyController {

    private final RecommendationAccuracyService accuracyService;
    private final RecommendationAccuracyReportService reportService;
    private final RecommendationOutcomeScheduler outcomeScheduler;
    private final RecommendationRepository recommendationRepository;
    private final com.example.trading.intelligence.recommendation.RetroBacktestService retroBacktestService;
    private final ScreeningCoverageService coverageService;

    @GetMapping("/summary")
    public List<SourceHorizonStats> summary() {
        return accuracyService.summarize();
    }

    @GetMapping("/by-source/{source}")
    public List<SourceHorizonStats> bySource(@PathVariable String source) {
        RecommendationEntity.Source src = RecommendationEntity.Source.valueOf(source.toUpperCase());
        return List.of(
                accuracyService.computeFor(src, 30),
                accuracyService.computeFor(src, 90),
                accuracyService.computeFor(src, 180),
                accuracyService.computeFor(src, 365));
    }

    /**
     * Every pick this app has made for one stock.
     *
     * <p>MACRO_EVENT rows are excluded by default and that is deliberate. They are measurements,
     * not picks: a row saying "this business faced a headwind on 9 September" is a risk note filed
     * so it can be checked later, and listing it under "the app's picks for this stock" would turn
     * a note about the weather into a recommendation to own something. Pass
     * {@code includeMacro=true} to see them when auditing the accuracy record itself.
     */
    @GetMapping("/by-symbol/{symbol}")
    public List<RecommendationEntity> bySymbol(@PathVariable String symbol,
                                               @RequestParam(defaultValue = "false") boolean includeMacro) {
        List<RecommendationEntity> all = recommendationRepository.findBySymbolOrderByIssuedDateDesc(symbol);
        if (includeMacro) return all;
        return all.stream()
                .filter(r -> !RecommendationEntity.Source.MACRO_EVENT.name().equals(r.getSource()))
                .toList();
    }

    /**
     * Per-dimension Information Coefficient for a scoring engine at the given horizon.
     * Fetches realized prices via Kite daily candles; reads each engine's sub-scores
     * from wherever they are persisted (multibagger_scores / sector_reversal_signals /
     * recommendation_dimensions). Independent of the recommendation_outcomes table so
     * numbers are usable as soon as enough picks have matured.
     *
     * Examples:
     *   GET /api/accuracy/dimension-ic?horizon=90
     *   GET /api/accuracy/dimension-ic?horizon=90&source=SECTOR_REVERSAL
     */
    @GetMapping("/dimension-ic")
    public List<RecommendationAccuracyService.DimensionIcStats> dimensionIc(
            @RequestParam(name = "horizon", defaultValue = "90") int horizonDays,
            @RequestParam(name = "source", defaultValue = "MULTIBAGGER") String source) {
        RecommendationEntity.Source src = RecommendationEntity.Source.valueOf(source.toUpperCase());
        log.info("API: Per-dimension IC requested for {} {}d horizon", src, horizonDays);
        return accuracyService.computeDimensionIC(src, horizonDays);
    }

    /**
     * Retrospective test of the technical dimensions against known 2018-2023 winners and
     * matched controls (SPEC §33). Manual only — no scheduler; it fetches years of daily
     * candles for ~40 symbols and is far too expensive for a page load (SPEC §27.4).
     *
     * <p>Deliberately NOT mounted under {@code /api/backtest}: that namespace belonged to
     * the intraday BacktestController deleted 2026-05-24, and reviving the path would
     * suggest the intraday engine is back. This is measurement, so it lives with the rest
     * of the measurement API.
     *
     * POST /api/accuracy/retro
     */
    @PostMapping("/retro")
    public com.example.trading.intelligence.recommendation.RetroBacktestService.RetroBacktestResult retro() {
        log.info("API: Retro-backtest requested");
        return retroBacktestService.run(null);
    }

    /**
     * Per-signal coverage for the most recent screening run that has rows (SPEC §38.2).
     *
     * <p>Answers the question an IC table cannot: was this signal weak, or was it simply
     * never measured on most of the universe? Reports MEASURED / NOT_APPLICABLE /
     * NOT_MEASURED separately — a bank with no ROCE is not a data gap, and neither count may
     * be folded into the other.
     *
     * <p>DB-only (one table, one date), so it is safe on a dashboard page load. Walks back to
     * the latest date that actually has rows rather than assuming today, which is empty until
     * the 14:00 screening (Gotcha 20).
     *
     * GET /api/accuracy/coverage
     * GET /api/accuracy/coverage?date=2026-08-29
     */
    @GetMapping("/coverage")
    public List<ScreeningCoverageEntity> coverage(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return date != null ? coverageService.forDate(date) : coverageService.latest();
    }

    /**
     * One signal's coverage over time, oldest first — the view that shows a signal degrading
     * before its IC has enough data to say anything. DB-only.
     *
     * GET /api/accuracy/coverage/trend?signal=Valuation&days=90
     */
    @GetMapping("/coverage/trend")
    public List<ScreeningCoverageEntity> coverageTrend(
            @RequestParam(name = "signal") String signal,
            @RequestParam(name = "days", defaultValue = "90") int days) {
        return coverageService.trend(signal, days);
    }

    @PostMapping("/refresh-outcomes")
    public Map<String, String> refreshOutcomes() {
        outcomeScheduler.updateOutcomes();
        return Map.of("status", "ok");
    }

    @PostMapping("/report")
    public Map<String, String> sendReport() {
        try {
            reportService.sendReport();
            return Map.of("status", "sent");
        } catch (Exception e) {
            log.error("Accuracy report send failed", e);
            return Map.of("status", "error", "message", e.getMessage());
        }
    }
}
