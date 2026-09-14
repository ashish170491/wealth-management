package com.example.trading.portfolio.report;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled cadences for the consolidated Portfolio Snapshot email. Covers SPEC §14:
 * <ul>
 *   <li>Daily — 09:30 MON-FRI, baseline health check</li>
 *   <li>Monthly review — first Friday of each month at 15:25, richer tone</li>
 *   <li>Quarterly rebalance — first Friday of Jan/Apr/Jul/Oct at 15:26</li>
 * </ul>
 *
 * <p>All crons fire within market hours (09:15–15:30 MON-FRI) per SPEC §15 — the app
 * only runs in that window. Opt out per cadence via {@code portfolio.reports.*} config.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PortfolioReportScheduler {

    private final PortfolioSnapshotReportService service;

    private final MarketHoursService marketHoursService;

    @Value("${portfolio.reports.daily-enabled:true}")
    private boolean dailyEnabled;

    @Value("${portfolio.reports.monthly-enabled:true}")
    private boolean monthlyEnabled;

    @Value("${portfolio.reports.quarterly-enabled:true}")
    private boolean quarterlyEnabled;

    /** Daily morning snapshot at 09:30 IST — the baseline "health check" email. */
    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailySnapshot() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        if (!dailyEnabled) return;
        run("daily");
    }

    /**
     * Monthly portfolio review — first Friday of every month at 15:25 IST.
     * Cron: day-of-month 1-7 AND day-of-week FRI narrows to exactly the first Friday.
     */
    @Scheduled(cron = "0 25 15 1-7 * FRI", zone = "Asia/Kolkata")
    public void monthlyReview() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        if (!monthlyEnabled) return;
        run("monthly");
    }

    /** Quarterly rebalance prompt — first Friday of Jan/Apr/Jul/Oct at 15:26 IST. */
    @Scheduled(cron = "0 26 15 1-7 1,4,7,10 FRI", zone = "Asia/Kolkata")
    public void quarterlyRebalance() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        if (!quarterlyEnabled) return;
        run("quarterly");
    }

    private void run(String cadence) {
        try {
            log.info("PortfolioReportScheduler: firing {} snapshot", cadence);
            service.sendSnapshot();
        } catch (Exception e) {
            log.error("PortfolioReportScheduler: {} snapshot failed: {}", cadence, e.getMessage(), e);
        }
    }
}
