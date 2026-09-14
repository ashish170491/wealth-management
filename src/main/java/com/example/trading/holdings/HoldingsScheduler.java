package com.example.trading.holdings;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class HoldingsScheduler {

    private final HoldingsAnalysisService holdingsAnalysisService;
    private final HoldingsReportService holdingsReportService;
    private final MarketHoursService marketHoursService;
    private final com.example.trading.portfolio.core.CoreClassificationService coreClassificationService;
    private final com.example.trading.portfolio.core.CoreOverlayService coreOverlayService;

    @Value("${holdings.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    /**
     * Sync holdings from broker at 9:20 AM on weekdays.
     * Runs shortly after market opens to get fresh data.
     */
    @Scheduled(cron = "${holdings.scheduler.sync-cron:0 20 9 * * MON-FRI}", zone = "Asia/Kolkata")
    public void syncHoldingsFromBroker() {
        if (!schedulerEnabled || !marketHoursService.isMarketOpen()) {
            return; // SPEC §3.4: market-hours only.
        }

        log.info("=== SCHEDULED TASK: Holdings Sync from Broker ===");
        try {
            holdingsAnalysisService.syncHoldingsFromBroker();
            log.info("Scheduled holdings sync completed successfully");
        } catch (Exception e) {
            log.error("Scheduled holdings sync failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Run technical analysis on all holdings at 10:30 AM (mid-morning check).
     * This is an additional analysis run separate from the report times.
     * Note: Analysis also runs before each report at 11 AM, 1 PM, and 3 PM.
     */
    @Scheduled(cron = "${holdings.scheduler.analysis-cron:0 30 10 * * MON-FRI}", zone = "Asia/Kolkata")
    public void analyzeHoldings() {
        if (!schedulerEnabled || !marketHoursService.isMarketOpen()) {
            return; // SPEC §3.4: market-hours only.
        }

        log.info("=== SCHEDULED TASK: Holdings Technical Analysis (Mid-Morning) ===");
        try {
            // First sync latest prices
            holdingsAnalysisService.syncHoldingsFromBroker();

            // Then run analysis
            holdingsAnalysisService.analyzeAllHoldings();

            // Core-holding classification rides on this job rather than getting a cron of its own
            // (SPEC §3.4: no new scheduler). It needs this morning's refreshed prices and the
            // previous day's screening scores, which is exactly what has just been prepared.
            try {
                coreClassificationService.classifyAll();
                coreOverlayService.invalidate();
            } catch (Exception e) {
                // A classification failure must not take the holdings analysis down with it. The
                // overlay then reads yesterday's tiers, which is stale but never wrong-by-default.
                log.warn("Core-holding classification failed (holdings analysis itself succeeded): {}",
                        e.getMessage(), e);
            }

            log.info("Scheduled holdings analysis completed successfully");
        } catch (Exception e) {
            log.error("Scheduled holdings analysis failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Send holdings report via email 3 times daily at 11 AM, 1 PM, and 3 PM on weekdays.
     * Each report includes fresh analysis with synced prices from broker.
     */
    @Scheduled(cron = "${holdings.scheduler.report-cron:0 0 11,13,15 * * MON-FRI}", zone = "Asia/Kolkata")
    public void sendHoldingsReport() {
        if (!schedulerEnabled || !marketHoursService.isMarketOpen()) {
            return; // SPEC §3.4: market-hours only.
        }

        LocalTime now = LocalTime.now(marketHoursService.getMarketZone());
        log.info("=== SCHEDULED TASK: Holdings Report ({}) ===", now.getHour() + ":00");
        try {
            // Sync latest prices from broker
            log.info("Syncing holdings prices before report...");
            holdingsAnalysisService.syncHoldingsFromBroker();

            // Run full technical analysis
            log.info("Running technical analysis before report...");
            holdingsAnalysisService.analyzeAllHoldings();

            // Record daily snapshot only at 3 PM (end of day)
            if (now.getHour() == 15) {
                log.info("Recording daily snapshot (3 PM report)...");
                holdingsAnalysisService.recordDailySnapshot();
            }

            // Send the report
            holdingsReportService.sendDailyHoldingsReport();
            log.info("Scheduled holdings report sent successfully at {}", now);
        } catch (Exception e) {
            log.error("Scheduled holdings report failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Refresh holdings data every hour during market hours.
     * Updates current prices and day change values.
     */
    @Scheduled(cron = "${holdings.scheduler.hourly-refresh-cron:0 0 10,11,12,13,14 * * MON-FRI}", zone = "Asia/Kolkata")
    public void refreshHoldingsPrices() {
        if (!schedulerEnabled || !marketHoursService.isMarketOpen()) {
            return; // SPEC §3.4: market-hours only.
        }

        log.debug("Refreshing holdings prices (hourly during market hours)");
        try {
            holdingsAnalysisService.syncHoldingsFromBroker();
        } catch (Exception e) {
            log.warn("Hourly holdings refresh failed: {}", e.getMessage());
        }
    }
}
