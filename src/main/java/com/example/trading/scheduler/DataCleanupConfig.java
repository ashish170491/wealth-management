package com.example.trading.scheduler;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for automated data cleanup/retention policies.
 * Configures how long different types of records are retained before deletion.
 */
@Configuration
@ConfigurationProperties(prefix = "trading.cleanup")
@Data
public class DataCleanupConfig {

    /**
     * Enable/disable automated cleanup scheduler.
     */
    private boolean enabled = true;

    /**
     * Retention period for completed trades (days).
     * Default: 90 days (3 months)
     */
    private int tradesRetentionDays = 90;

    /**
     * Retention period for strategy execution logs (days).
     * Default: 30 days
     */
    private int strategyLogsRetentionDays = 30;

    /**
     * Retention period for sector reversal signals (days).
     * Default: 60 days
     */
    private int sectorReversalRetentionDays = 60;

    /**
     * Retention period for holdings history (days).
     * Default: 1095 days (3 years).
     *
     * <p>Raised from 180 on 2026-08-24. This table is the dashboard's portfolio-value
     * time series (SPEC §27) — at 180 days the cleanup job permanently deleted one more
     * chartable day every night, hard-capping every history chart at 6 months. One small
     * row per symbol per day, so the storage cost is negligible.
     */
    private int holdingsHistoryRetentionDays = 1095;

    /**
     * Retention period for historical candles (days).
     * Default: 365 days (1 year)
     */
    private int historicalCandlesRetentionDays = 365;

    /**
     * Retention period for captured news headlines (days).
     *
     * <p><b>B-103.</b> This key existed in {@code application.yml} for months and there was no
     * field to receive it and no cleanup arm to act on it, so {@code market_impact_news} grew
     * without limit while the configuration file said it was pruned at 30 days. A retention
     * setting with no deleter behind it is a comment, not a policy.
     */
    private int marketImpactNewsRetentionDays = 30;

    /**
     * Retention period for extracted macro events (days).
     *
     * <p>Two years, deliberately longer than the headlines that produced them. An event is the
     * record its own reading is measured against, and recommendation outcomes run to 365 days, so
     * pruning at one year would delete the evidence just as the last horizon matured.
     */
    private int macroEventsRetentionDays = 730;

    /**
     * Batch size for deletion queries (to avoid memory issues).
     */
    private int deletionBatchSize = 1000;

    /**
     * Whether to log detailed cleanup statistics.
     */
    private boolean logDetailedStats = true;
}
