package com.example.trading.multibagger;

import com.example.trading.broker.kite.TokenManagementService;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Scheduler for Multibagger screening and reporting tasks.
 *
 * Schedule:
 * - Weekly screening: Saturday 8:00 AM IST (full universe scan with fresh weekly data)
 * - Daily quick screen: 3:45 PM IST weekdays (after market close, uses latest daily data)
 * - Weekly email report: Saturday 9:00 AM IST (after screening completes)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MultibaggerScheduler {

    private final MultibaggerScreenerService screenerService;
    private final MultibaggerReportService reportService;
    private final MultibaggerConfig config;
    private final MarketHoursService marketHoursService;
    private final TokenManagementService tokenManagementService;
    private final com.example.trading.universe.UniverseExpansionService universeExpansionService;

    /**
     * Run full weekly screening on Saturday 08:00 IST. The Windows scheduled tasks start
     * the app 07:50 and stop it 10:35 on Saturday specifically for this window (SPEC §3.4
     * carve-out, 2026-08-25 — previously Friday 15:17, which saturated the packed
     * 15:15–15:30 close ramp). Guarded by the Saturday window, not isMarketOpen().
     */
    @Scheduled(cron = "0 0 8 * * SAT", zone = "Asia/Kolkata")
    public void weeklyFullScreening() {
        if (!marketHoursService.isSaturdayScreeningWindow()) return; // SPEC §3.4 Saturday carve-out.
        if (!config.isEnabled()) {
            log.debug("Multibagger Scheduler: Screening disabled in config");
            return;
        }
        // Saturday has no token safety net: TokenManagementService's recovery cron is
        // MON-FRI, so the only login attempt is the fire-and-forget one at app startup
        // (07:50). If its 3 retries all failed, every Kite call below returns TokenException
        // and we would persist a full screening run of garbage that looks completed.
        // A missing weekly run is recoverable; a fabricated one silently poisons the
        // score history and the accuracy tracker (B-026 lesson).
        if (!tokenManagementService.hasValidToken()) {
            log.error("Multibagger Scheduler: ABORTING weekly screening — no valid broker token. "
                    + "The 07:50 startup login must have failed and Saturday has no refresh cron. "
                    + "Re-run manually via POST /api/multibagger/screen once a token is available.");
            return;
        }

        log.info("Multibagger Scheduler: Starting weekly full screening...");
        try {
            screenerService.runFullScreening();
            log.info("Multibagger Scheduler: Weekly screening completed successfully");

            // Age the promoted set against the run that just finished (B-036). This is the
            // only caller of retireWeakSymbols: without it the "8 weak weeks -> retire" rule
            // in SPEC §30.3 never ran at all, so a promoted symbol could only ever be
            // evicted by the 100-symbol cap, and then on a score frozen at promotion time.
            Map<String, Integer> latest = screenerService.getLatestScores().stream()
                    .filter(s -> s.getSymbol() != null && s.getCompositeScore() > 0)
                    .collect(java.util.stream.Collectors.toMap(
                            MultibaggerScore::getSymbol,
                            MultibaggerScore::getCompositeScore,
                            (a, b) -> b));
            if (latest.isEmpty()) {
                log.warn("Multibagger Scheduler: no scores from this run — skipping retirement pass "
                        + "rather than ageing the promoted set against no evidence");
            } else {
                universeExpansionService.retireWeakSymbols(latest);
            }
        } catch (Exception e) {
            log.error("Multibagger Scheduler: Weekly screening failed: {}", e.getMessage(), e);
        }

        // Universe expansion Stage A (SPEC §30.2) runs here rather than in a scheduler of its
        // own: isSaturdayScreeningWindow() is documented as having exactly two authorised
        // callers, and a third would turn a sanctioned exception into a convention (Gotcha 28).
        // It is also the only slot with room — ~1,900 symbols at the paced Kite rate is ~11
        // minutes, which fits the 150-minute Saturday window but not a weekday one.
        try {
            universeExpansionService.runCoarseScan();
        } catch (Exception e) {
            log.error("Multibagger Scheduler: Universe coarse scan failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Run daily screening at 2:00 PM IST (weekdays).
     * Updates scores with latest daily price action.
     */
    @Scheduled(cron = "0 0 14 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyQuickScreening() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        if (!config.isEnabled()) {
            log.debug("Multibagger Scheduler: Screening disabled in config");
            return;
        }

        log.info("Multibagger Scheduler: Starting daily post-market screening...");
        try {
            screenerService.runFullScreening();
            log.info("Multibagger Scheduler: Daily screening completed successfully");
        } catch (Exception e) {
            log.error("Multibagger Scheduler: Daily screening failed: {}", e.getMessage(), e);
        }

        // Universe expansion Stage B (SPEC §30.2): deep-score a handful of queued symbols.
        // Deliberately after the screening, and budgeted per day — running the full
        // 8-dimension workup on a 40-name queue would add ~40 NSE/XBRL round trips to a run
        // that already takes ~12 minutes.
        try {
            universeExpansionService.processQueue();
        } catch (Exception e) {
            log.error("Multibagger Scheduler: Universe queue processing failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Send weekly multibagger report email on Saturday 09:00 IST, an hour after the
     * 08:00 weekly screen starts (comfortably after it completes). Same SPEC §3.4
     * Saturday carve-out as {@link #weeklyFullScreening()}.
     */
    @Scheduled(cron = "0 0 9 * * SAT", zone = "Asia/Kolkata")
    public void sendWeeklyReport() {
        if (!marketHoursService.isSaturdayScreeningWindow()) return; // SPEC §3.4 Saturday carve-out.
        if (!config.isEnabled() || !config.isEmailEnabled()) {
            log.debug("Multibagger Scheduler: Report disabled in config");
            return;
        }

        log.info("Multibagger Scheduler: Sending weekly multibagger report...");
        try {
            reportService.sendWeeklyReport();
            log.info("Multibagger Scheduler: Weekly report sent successfully");
        } catch (Exception e) {
            log.error("Multibagger Scheduler: Failed to send weekly report: {}", e.getMessage(), e);
        }
    }
}
