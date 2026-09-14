package com.example.trading.scheduler;

import com.example.trading.persistence.*;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Automated data cleanup service that removes old records to manage storage.
 * Runs daily at 2:00 AM IST to clean up records older than configured retention periods.
 * 
 * Cleanup targets:
 * - Completed trades (old historical data)
 * - Strategy execution logs
 * - Sector reversal signals
 * - Holdings history
 * - Historical candles
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "trading.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DataCleanupScheduler {

    private final DataCleanupConfig config;
    private final TradeRepository tradeRepository;
    private final StrategyExecutionLogRepository strategyLogRepository;
    private final SectorReversalRepository sectorReversalRepository;
    private final HoldingsHistoryRepository holdingsHistoryRepository;
    private final com.example.trading.watchlist.WatchlistSnapshotRepository watchlistSnapshotRepository;
    private final com.example.trading.watchlist.WatchlistConfig watchlistConfig;
    private final com.example.trading.intelligence.MarketImpactNewsRepository marketImpactNewsRepository;
    private final com.example.trading.macro.MacroEventRepository macroEventRepository;
    private final MarketHoursService marketHoursService;

    /**
     * Run cleanup at end of every trading day (15:28 IST MON-FRI), just before the
     * app shuts down. App only runs during market hours so 2 AM batch windows don't work.
     */
    @Scheduled(cron = "0 28 15 * * MON-FRI", zone = "Asia/Kolkata")
    @Transactional
    public void performDailyCleanup() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        log.info("=== Starting Daily Data Cleanup ===");
        long startTime = System.currentTimeMillis();
        Map<String, Integer> deletionStats = new HashMap<>();

        try {
            // Cleanup completed trades
            int tradesDeleted = cleanupTrades();
            deletionStats.put("Trades", tradesDeleted);

            // Cleanup strategy execution logs
            int strategyLogsDeleted = cleanupStrategyLogs();
            deletionStats.put("Strategy Logs", strategyLogsDeleted);

            // Cleanup sector reversal signals
            int sectorReversalDeleted = cleanupSectorReversalSignals();
            deletionStats.put("Sector Reversal", sectorReversalDeleted);

            // Cleanup holdings history
            int holdingsHistoryDeleted = cleanupHoldingsHistory();
            deletionStats.put("Holdings History", holdingsHistoryDeleted);

            // Cleanup watchlist daily snapshots (SPEC §37.4)
            int watchlistSnapshotsDeleted = cleanupWatchlistSnapshots();
            deletionStats.put("Watchlist Snapshots", watchlistSnapshotsDeleted);

            // Cleanup captured headlines and extracted macro events (SPEC §48.2, B-103)
            deletionStats.put("News Headlines", cleanupMarketImpactNews());
            deletionStats.put("Macro Events", cleanupMacroEvents());

            long duration = System.currentTimeMillis() - startTime;
            int totalDeleted = deletionStats.values().stream().mapToInt(Integer::intValue).sum();

            log.info("=== Data Cleanup Complete ===");
            log.info("Total records deleted: {}", totalDeleted);
            log.info("Duration: {} ms", duration);

            if (config.isLogDetailedStats()) {
                log.info("Deletion breakdown:");
                deletionStats.forEach((category, count) -> 
                    log.info("  {} deleted: {}", category, count));
            }

        } catch (Exception e) {
            log.error("Error during data cleanup", e);
        }
    }

    private int cleanupWatchlistSnapshots() {
        java.time.LocalDate cutoff = java.time.LocalDate.now().minusDays(watchlistConfig.getSnapshotRetentionDays());
        int deleted = watchlistSnapshotRepository.deleteBySnapshotDateBefore(cutoff);
        log.debug("Deleted {} watchlist snapshots older than {} days", deleted, watchlistConfig.getSnapshotRetentionDays());
        return deleted;
    }

    /**
     * B-103: this arm did not exist. {@code trading.cleanup.market-impact-news-retention-days} was
     * declared in the configuration file, the repository already had a delete method with zero
     * callers, and the table simply grew.
     */
    private int cleanupMarketImpactNews() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(config.getMarketImpactNewsRetentionDays());
        int deleted = marketImpactNewsRepository.deleteByCreatedAtBefore(cutoff);
        log.debug("Deleted {} news headlines older than {} days",
                deleted, config.getMarketImpactNewsRetentionDays());
        return deleted;
    }

    private int cleanupMacroEvents() {
        LocalDate cutoff = LocalDate.now().minusDays(config.getMacroEventsRetentionDays());
        int deleted = macroEventRepository.deleteByOccurredAtBefore(cutoff);
        log.debug("Deleted {} macro events older than {} days",
                deleted, config.getMacroEventsRetentionDays());
        return deleted;
    }

    private int cleanupTrades() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(config.getTradesRetentionDays());
        int deleted = tradeRepository.deleteByCompletedAtBeforeAndStatusIn(
                cutoffDate, 
                java.util.List.of("COMPLETED", "CANCELLED", "FAILED"));
        log.debug("Deleted {} trades older than {} days", deleted, config.getTradesRetentionDays());
        return deleted;
    }

    private int cleanupStrategyLogs() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(config.getStrategyLogsRetentionDays());
        int deleted = strategyLogRepository.deleteByTimestampBefore(cutoffDate);
        log.debug("Deleted {} strategy logs older than {} days", 
                deleted, config.getStrategyLogsRetentionDays());
        return deleted;
    }

    private int cleanupSectorReversalSignals() {
        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(config.getSectorReversalRetentionDays());
        int deleted = sectorReversalRepository.deleteByCreatedAtBefore(cutoffDate);
        log.debug("Deleted {} sector reversal signals older than {} days", 
                deleted, config.getSectorReversalRetentionDays());
        return deleted;
    }

    private int cleanupHoldingsHistory() {
        LocalDate cutoffDate = LocalDate.now().minusDays(config.getHoldingsHistoryRetentionDays());
        int deleted = holdingsHistoryRepository.deleteBySnapshotDateBefore(cutoffDate);
        log.debug("Deleted {} holdings history records older than {} days", 
                deleted, config.getHoldingsHistoryRetentionDays());
        return deleted;
    }

    /**
     * Manual trigger for cleanup (for testing/admin purposes).
     * Can be called via REST API or admin interface.
     */
    public Map<String, Integer> performManualCleanup() {
        log.info("Manual cleanup triggered");
        performDailyCleanup();
        return new HashMap<>(); // Return stats if needed
    }
}
