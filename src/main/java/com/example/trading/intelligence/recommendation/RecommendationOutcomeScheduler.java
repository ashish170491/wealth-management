package com.example.trading.intelligence.recommendation;

import com.example.trading.marketdata.MarketDataService;
import com.example.trading.persistence.RecommendationEntity;
import com.example.trading.persistence.RecommendationOutcomeEntity;
import com.example.trading.persistence.RecommendationOutcomeRepository;
import com.example.trading.persistence.RecommendationRepository;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Measures realized returns for past recommendations at fixed horizons
 * (30 / 90 / 180 / 365 days) and persists them to
 * {@code recommendation_outcomes}.
 *
 * Runs at 15:22 IST MON–FRI — inside the market-hours window (SPEC.md §3.4),
 * after the holdings (15:18) and market-impact news (15:20) reports so any
 * shared work is primed in caches. Uses the latest quote available at that
 * moment; 1-minute precision is immaterial over multi-week horizons.
 *
 * See SPEC.md §23 Recommendation Accuracy Tracking.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecommendationOutcomeScheduler {

    /**
     * How far past its anniversary a pick may still be measured at a given horizon (B-028).
     *
     * <p>Generous enough to absorb weekends, market holidays and a few days of downtime;
     * tight enough that the stored return still means what {@code horizonDays} says. Picks
     * that drift beyond it are left unmeasured — an honest gap beats a mislabelled number,
     * and it also stops permanently unpriceable symbols being retried every day forever
     * (B-027: six delisted tickers accounted for 152 lookups per run).
     */
    static final int MEASUREMENT_GRACE_DAYS = 7;

    private final RecommendationRepository recommendationRepository;
    private final RecommendationOutcomeRepository outcomeRepository;
    private final MarketDataService marketDataService;
    private final MarketHoursService marketHoursService;

    @Scheduled(cron = "0 22 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void updateOutcomesScheduled() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        updateOutcomes();
    }

    /**
     * Recompute due outcomes. Deliberately NOT market-hours gated.
     *
     * <p>The guard belongs on the scheduled entry point, not here: this is also the
     * on-demand path behind {@code POST /api/accuracy/refresh-outcomes}, and a manual
     * trigger that silently no-ops outside 09:15-15:30 is worse than useless — it returns
     * {@code {"status":"ok"}} having done nothing, which is exactly what it did when
     * verifying the B-022 backfill on a Saturday.
     */
    public void updateOutcomes() {
        log.info("Recommendation outcomes: starting update");
        int persisted = 0;
        int skipped = 0;
        // Track per-source so a source that skips 100% of its picks is visible. The old
        // aggregate-only "skipped=1499" line hid SECTOR_REVERSAL failing every single pick
        // for four months (B-022) — the total looked plausible next to persisted=578.
        java.util.Map<String, int[]> bySource = new java.util.HashMap<>(); // [persisted, skipped]

        int drifted = 0;
        for (int horizon : RecommendationOutcomeEntity.DEFAULT_HORIZONS) {
            LocalDate today = LocalDate.now();
            LocalDate cutoff = today.minusDays(horizon);
            // Only picks whose anniversary fell inside the grace window are still measurable at
            // this horizon (B-028). Anything older would be measured with today's price and then
            // stored as an on-time result — a 200-day return filed as a "30-day" one.
            LocalDate floor = today.minusDays((long) horizon + MEASUREMENT_GRACE_DAYS);
            List<RecommendationEntity> due = recommendationRepository.findDueForOutcome(cutoff, horizon);
            int before = due.size();
            due = due.stream().filter(r -> !r.getIssuedDate().isBefore(floor)).toList();
            drifted += before - due.size();
            if (due.isEmpty()) continue;

            log.info("Recommendation outcomes: {} picks due at {}d horizon", due.size(), horizon);
            Double niftyNow = fetchPriceQuietly(RecommendationTracker.NIFTY_SYMBOL);

            for (RecommendationEntity reco : due) {
                int[] tally = bySource.computeIfAbsent(reco.getSource(), k -> new int[2]);
                Double priceNow = fetchPriceQuietly(reco.getSymbol());
                if (priceNow == null || priceNow <= 0) {
                    skipped++;
                    tally[1]++;
                    continue;
                }
                try {
                    double returnPct = ((priceNow - reco.getIssuedPrice()) / reco.getIssuedPrice()) * 100.0;
                    Double niftyReturnPct = null;
                    Double excessPct = null;
                    if (niftyNow != null && reco.getNiftyIndexAtIssue() != null && reco.getNiftyIndexAtIssue() > 0) {
                        niftyReturnPct = ((niftyNow - reco.getNiftyIndexAtIssue()) / reco.getNiftyIndexAtIssue()) * 100.0;
                        excessPct = returnPct - niftyReturnPct;
                    }

                    RecommendationOutcomeEntity outcome = RecommendationOutcomeEntity.builder()
                            .recommendationId(reco.getId())
                            .horizonDays(horizon)
                            .measuredDate(LocalDate.now())
                            .priceAtMeasurement(priceNow)
                            .returnPercent(returnPct)
                            .niftyReturnPercent(niftyReturnPct)
                            .excessReturnPercent(excessPct)
                            .targetHit(reco.getTargetPrice() != null && priceNow >= reco.getTargetPrice())
                            .stopLossHit(reco.getStopLossPrice() != null && priceNow <= reco.getStopLossPrice())
                            .daysElapsed((int) java.time.temporal.ChronoUnit.DAYS.between(
                                    reco.getIssuedDate(), LocalDate.now()))
                            .build();
                    outcomeRepository.save(outcome);
                    persisted++;
                    tally[0]++;
                } catch (Exception e) {
                    log.warn("Recommendation outcomes: failed for {} @ {}d: {}",
                            reco.getSymbol(), horizon, e.getMessage());
                    skipped++;
                    tally[1]++;
                }
            }
        }

        log.info("Recommendation outcomes: persisted={}, skipped={}", persisted, skipped);
        if (drifted > 0) {
            log.info("Recommendation outcomes: {} pick(s) past their horizon + {}d grace were left "
                    + "unmeasured rather than recorded as on-time results (B-028).",
                    drifted, MEASUREMENT_GRACE_DAYS);
        }
        bySource.forEach((source, t) -> {
            int total = t[0] + t[1];
            if (total == 0) return;
            if (t[0] == 0) {
                // A whole engine producing no measurable outcomes is a defect, not a lull.
                log.warn("Recommendation outcomes: source {} skipped ALL {} due picks — "
                        + "no outcomes recorded. Check symbol format and price availability (B-022).",
                        source, total);
            } else {
                log.info("Recommendation outcomes: {} -> persisted={}, skipped={}", source, t[0], t[1]);
            }
        });
    }

    private Double fetchPriceQuietly(String symbol) {
        try {
            Double price = marketDataService.getCurrentPrice(symbol);
            return (price != null && price > 0) ? price : null;
        } catch (Exception e) {
            log.debug("Recommendation outcomes: price unavailable for {}: {}", symbol, e.getMessage());
            return null;
        }
    }
}
