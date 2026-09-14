package com.example.trading.watchlist;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for Watchlist feature.
 * Stocks are configured in application.yml under 'watchlist' section.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "watchlist")
public class WatchlistConfig {

    /**
     * Enable/disable watchlist feature.
     */
    private boolean enabled = true;

    /**
     * <b>Seed list, read once.</b> Since SPEC §37 the {@code watchlist} table is the source of
     * truth: on every boot {@code WatchlistSeedRunner} inserts any symbol here that has no row,
     * stamps legacy rows, and never resurrects a row the investor removed. Editing this list
     * after first boot only adds; removing a symbol here does nothing.
     * Format: NSE:RELIANCE, NSE:TCS, etc.
     */
    private List<String> symbols = new ArrayList<>();

    /**
     * Scheduler configuration.
     */
    private SchedulerConfig scheduler = new SchedulerConfig();

    /**
     * Analysis thresholds.
     */
    private AnalysisConfig analysis = new AnalysisConfig();

    /**
     * "Still a good time to buy?" verdict thresholds (SPEC §37.3).
     */
    private VerdictConfig verdict = new VerdictConfig();

    /** Daily snapshot rows older than this are pruned by DataCleanupScheduler. */
    private int snapshotRetentionDays = 730;

    @Data
    public static class VerdictConfig {
        /** Quality composite at or above this, with a technical entry, is BUY_NOW. */
        private int qualityMinBuy = 65;
        /** Quality composite below this is AVOID regardless of the chart. */
        private int qualityMinHold = 50;
        /** RSI above this is WAIT_FOR_PULLBACK. */
        private double rsiOverbought = 70.0;
        /** Return since added above this, together with runawayRsi, is WAIT_FOR_PULLBACK. */
        private double runawayReturnPct = 15.0;
        private double runawayRsi = 60.0;
        /** Price this far above EMA50 is stretched. */
        private double stretchAboveEma50Pct = 10.0;
    }

    @Data
    public static class SchedulerConfig {
        /**
         * Enable/disable scheduler.
         */
        private boolean enabled = true;

        /**
         * Cron expression for analysis runs.
         * Default: 11 AM, 1 PM, 3 PM on weekdays.
         */
        private String analysisCron = "0 0 11,13,15 * * MON-FRI";

        /**
         * Timezone for cron expressions.
         */
        private String timezone = "Asia/Kolkata";
    }

    @Data
    public static class AnalysisConfig {
        /**
         * Minimum historical days required for analysis.
         */
        private int minHistoryDays = 100;

        /**
         * RSI oversold threshold - below this generates BUY signal.
         */
        private double rsiOversold = 30.0;

        /**
         * RSI overbought threshold - above this generates SELL signal.
         */
        private double rsiOverbought = 70.0;

        /**
         * Score threshold for strong buy signal.
         */
        private int strongBuyThreshold = 75;

        /**
         * Score threshold for buy signal.
         */
        private int buyThreshold = 60;

        /**
         * Score threshold for sell consideration.
         */
        private int sellThreshold = 40;
    }
}
