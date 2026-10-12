package com.example.trading.jobs;

import java.util.Map;

/**
 * Human names for scheduled jobs (SPEC §56.5). Pure.
 *
 * <p>Keyed by {@code SimpleClass.method}, which is what a reader recognises from SPEC §15 and the
 * logs. A job missing from this table still appears on the page - under a name built from its
 * method - so adding a scheduler never makes it invisible here; the table only makes it readable.
 * The list of jobs itself comes from the running scheduler, never from this map.
 */
public final class JobLabels {

    private JobLabels() {
    }

    /** Name, then what the run touches - the second half is what tells you whether it competes for the broker. */
    public record Label(String name, String touches) {
    }

    private static final Map<String, Label> LABELS = Map.ofEntries(
            e("HoldingsScheduler.syncHoldingsFromBroker", "Holdings sync from broker", "Kite"),
            e("HoldingsScheduler.analyzeHoldings", "Holdings analysis + core-holding classification", "Kite, NSE"),
            e("HoldingsScheduler.sendHoldingsReport", "Holdings report emails", "Kite, NSE, email"),
            e("HoldingsScheduler.refreshHoldingsPrices", "Holdings price refresh", "Kite"),
            e("MorningBriefingService.scheduledMorningBriefing", "Morning briefing email", "DB, email"),
            e("PortfolioReportScheduler.dailySnapshot", "Portfolio daily snapshot", "DB"),
            e("PortfolioReportScheduler.monthlyReview", "Monthly portfolio review", "DB, email"),
            e("PortfolioReportScheduler.quarterlyRebalance", "Quarterly rebalance review", "DB, email"),
            e("TokenManagementService.dailyTokenRefresh", "Kite login recovery", "Kite login"),
            e("FiiDiiScheduler.fetchFiiDiiData", "FII/DII fetch", "NSE"),
            e("FiiDiiScheduler.sendFiiDiiReport", "FII/DII report email", "NSE, email"),
            e("QuantitativeDiscoveryScheduler.dailyDiscoveryScan", "Quantitative discovery scan", "Kite, NSE, email"),
            e("AccumulationReminderService.dailyCheck", "Accumulation plan reminders", "DB, email"),
            e("ExitTimingAlertService.scheduledCheck", "Exit-timing alerts", "DB, email"),
            e("WatchlistScheduler.analyzeAndReportWatchlist", "Watchlist analysis + snapshot", "Kite, email"),
            e("FundamentalsBackfillScheduler.backfillBatch", "Annual-accounts backfill", "NSE"),
            e("IpoCaptureScheduler.captureDaily", "IPO pipeline capture", "NSE, Kite"),
            e("MutualFundNavScheduler.pullDailyNav", "Mutual fund NAV pull", "AMFI"),
            e("MacroIngestScheduler.dailyIngest", "Macro news read", "RSS, Kite"),
            e("MarketImpactNewsService.scheduledNewsCheck", "Headline capture", "RSS"),
            e("AnalystTargetScheduler.captureAndMeasure", "Analyst target capture + measurement", "Broker research feed, Kite"),
            e("MultibaggerScheduler.dailyQuickScreening", "Daily multibagger screening", "Kite, NSE"),
            e("MultibaggerScheduler.weeklyFullScreening", "Weekly full screening (Saturday)", "Kite, NSE"),
            e("MultibaggerScheduler.sendWeeklyReport", "Weekly screening email (Saturday)", "DB, email"),
            e("InsiderCaptureScheduler.captureDaily", "Insider disclosure capture", "NSE"),
            e("TargetHitAlertService.scheduledScan", "Target-hit scan + email", "Kite, email"),
            e("RecommendationOutcomeScheduler.updateOutcomesScheduled", "Pick outcome measurement", "Kite"),
            e("RecommendationAccuracyReportService.sendWeeklyReport", "Weekly accuracy email", "DB, email"),
            e("TaxLotAutoCaptureScheduler.captureTodayTrades", "Tax-lot capture of today's trades", "Kite"),
            e("DataCleanupScheduler.performDailyCleanup", "Data retention cleanup", "DB"));

    private static Map.Entry<String, Label> e(String key, String name, String touches) {
        return Map.entry(key, new Label(name, touches));
    }

    /** {@code com.x.HoldingsScheduler.analyzeHoldings} -> {@code HoldingsScheduler.analyzeHoldings}. */
    public static String shortKey(String jobKey) {
        if (jobKey == null) return null;
        int method = jobKey.lastIndexOf('.');
        if (method <= 0) return jobKey;
        int cls = jobKey.lastIndexOf('.', method - 1);
        return jobKey.substring(cls + 1);
    }

    /** The label for a job, or one built from its method name when the table has none. Never null. */
    public static Label of(String jobKey) {
        String shortKey = shortKey(jobKey);
        Label known = shortKey == null ? null : LABELS.get(shortKey);
        if (known != null) return known;
        return new Label(humanize(shortKey), null);
    }

    /** {@code HoldingsScheduler.analyzeHoldings} -> {@code "Analyze holdings (HoldingsScheduler)"}. */
    static String humanize(String shortKey) {
        if (shortKey == null || shortKey.isBlank()) return "Unnamed job";
        int dot = shortKey.indexOf('.');
        String cls = dot > 0 ? shortKey.substring(0, dot) : "";
        String method = dot > 0 ? shortKey.substring(dot + 1) : shortKey;
        String words = method.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase();
        if (words.isEmpty()) return shortKey;
        words = Character.toUpperCase(words.charAt(0)) + words.substring(1);
        return cls.isEmpty() ? words : words + " (" + cls + ")";
    }

    static int size() {
        return LABELS.size();
    }
}
