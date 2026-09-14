package com.example.trading.scanner;

import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for quantitative stock discovery scan.
 *
 * Schedule:
 * - Daily discovery scan: 10:00 AM IST weekdays (after market data is flowing)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class QuantitativeDiscoveryScheduler {

    private final QuantitativeDiscoveryReportService reportService;

    private final MarketHoursService marketHoursService;

    /**
     * Run quantitative discovery scan at 10:00 AM IST.
     * NSE data and market prices are available by this time.
     */
    @Scheduled(cron = "0 0 10 * * MON-FRI", zone = "Asia/Kolkata")
    public void dailyDiscoveryScan() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        log.info("Discovery Scheduler: Running daily quantitative scan...");
        try {
            reportService.runAndSendReport();
            log.info("Discovery Scheduler: Daily scan report sent");
        } catch (Exception e) {
            log.error("Discovery Scheduler: Daily scan failed: {}", e.getMessage(), e);
        }
    }
}
