package com.example.trading.watchlist;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Scheduler for watchlist analysis and reports.
 * Runs 3 times daily (11 AM, 1 PM, 3 PM) on weekdays during market hours.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WatchlistScheduler {

    private final WatchlistAnalysisService watchlistAnalysisService;
    private final WatchlistReportService watchlistReportService;
    private final WatchlistTrackingService trackingService;
    private final WatchlistConfig watchlistConfig;
    private final MarketHoursService marketHoursService;

    /** The 15:00 fire of the 11/13/15 cron is the one that writes the daily snapshot. */
    private static final int SNAPSHOT_HOUR = 15;

    /**
     * Run watchlist analysis and send report at 11 AM, 1 PM, and 3 PM on weekdays.
     * This method combines analysis and report sending for efficiency.
     */
    @Scheduled(cron = "${watchlist.scheduler.analysis-cron:0 0 11,13,15 * * MON-FRI}", zone = "${watchlist.scheduler.timezone:Asia/Kolkata}")
    public void analyzeAndReportWatchlist() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        if (!isEnabled()) {
            log.debug("Watchlist scheduler is disabled");
            return;
        }

        if (!isWeekday()) {
            log.debug("Watchlist analysis skipped - weekend");
            return;
        }

        LocalTime now = LocalTime.now();
        log.info("=== SCHEDULED TASK: Watchlist Analysis & Report ({}) ===", now.getHour() + ":00");

        try {
            // Step 1: Analyze all watchlist stocks
            log.info("Starting watchlist analysis...");
            long startTime = System.currentTimeMillis();
            watchlistAnalysisService.analyzeAllWatchlistStocks();
            long analysisTime = System.currentTimeMillis() - startTime;
            log.info("Watchlist analysis completed in {} ms", analysisTime);

            // Step 1b (SPEC §37.4): the last run of the day records the daily snapshot that
            // makes returns and sparklines DB-only for stocks the investor does not own.
            if (now.getHour() >= SNAPSHOT_HOUR) {
                try {
                    int written = trackingService.writeDailySnapshots();
                    log.info("Watchlist daily snapshots written: {}", written);
                } catch (Exception e) {
                    log.warn("Watchlist daily snapshot failed — today's return/sparkline point is missing: {}",
                            e.getMessage());
                }
            }

            // Step 2: Send report
            log.info("Sending watchlist report...");
            watchlistReportService.sendWatchlistReport();
            log.info("Watchlist report sent successfully");

        } catch (Exception e) {
            log.error("Watchlist scheduled task failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Check if the scheduler is enabled.
     */
    private boolean isEnabled() {
        return watchlistConfig != null &&
                watchlistConfig.isEnabled() &&
                watchlistConfig.getScheduler() != null &&
                watchlistConfig.getScheduler().isEnabled();
    }

    /**
     * Check if today is a weekday.
     */
    private boolean isWeekday() {
        DayOfWeek day = LocalDate.now().getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    /**
     * Manual trigger for watchlist analysis (for testing or on-demand).
     */
    public void triggerManualAnalysis() {
        log.info("Manual watchlist analysis triggered");
        try {
            watchlistAnalysisService.analyzeAllWatchlistStocks();
            watchlistReportService.sendWatchlistReport();
            log.info("Manual watchlist analysis and report completed");
        } catch (Exception e) {
            log.error("Manual watchlist analysis failed: {}", e.getMessage(), e);
            throw e;
        }
    }
}
