package com.example.trading.fiidii;

import com.example.trading.fiidii.FiiDiiDTO.*;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Scheduler for FII/DII data fetching and report generation.
 * 
 * Schedule:
 * - 9:45 AM: Fetch previous day's FII/DII data from NSE
 * - 10:00 AM: Generate and send FII/DII report with sector analysis
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FiiDiiScheduler {

    private final FiiDiiDataService dataService;
    private final FiiDiiReportService reportService;
    private final FiiDiiConfig config;
    private final MarketHoursService marketHoursService;

    // Store the fetched data for report generation
    private volatile FiiDiiReport latestReport;

    /**
     * Fetch previous day's FII/DII data at 9:45 AM on weekdays.
     * NSE publishes data after market hours (~6:30 PM), so we fetch previous day's data.
     */
    @Scheduled(cron = "${trading.fiidii.fetch-cron:0 45 9 * * MON-FRI}", zone = "Asia/Kolkata")
    public void fetchFiiDiiData() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        if (!config.isEnabled() || !isWeekday()) {
            log.debug("FII/DII fetch skipped - disabled or weekend");
            return;
        }

        log.info("=== SCHEDULED TASK: Fetching FII/DII Data ===");
        
        try {
            // Get previous trading day
            LocalDate previousTradingDay = dataService.getPreviousTradingDay(LocalDate.now().minusDays(1));
            log.info("Fetching FII/DII data for previous trading day: {}", previousTradingDay);

            // Generate full report (this fetches all data internally)
            latestReport = reportService.generateReport(previousTradingDay);

            if (latestReport != null) {
                DailyActivity daily = latestReport.getDailyActivity();
                log.info("FII/DII data fetched successfully:");
                log.info("  - FII: Buy=₹{} Cr, Sell=₹{} Cr, Net=₹{} Cr",
                        formatNumber(daily.getFiiBuyValue()),
                        formatNumber(daily.getFiiSellValue()),
                        formatNumber(daily.getFiiNetValue()));
                log.info("  - DII: Buy=₹{} Cr, Sell=₹{} Cr, Net=₹{} Cr",
                        formatNumber(daily.getDiiBuyValue()),
                        formatNumber(daily.getDiiSellValue()),
                        formatNumber(daily.getDiiNetValue()));
                log.info("  - Sentiment: {}", daily.getOverallSentiment());
                log.info("  - Sectors analyzed: {}", latestReport.getSectorFlows().size());
                log.info("  - Significant deals: {}", latestReport.getSignificantDeals().size());
                
                // Log any alerts
                if (latestReport.getAlerts() != null && !latestReport.getAlerts().isEmpty()) {
                    log.warn("FII/DII Alerts:");
                    for (String alert : latestReport.getAlerts()) {
                        log.warn("  {}", alert);
                    }
                }
            }

            log.info("FII/DII data fetch completed successfully");

        } catch (Exception e) {
            log.error("Failed to fetch FII/DII data: {}", e.getMessage(), e);
        }
    }

    /**
     * Send FII/DII report at 10:00 AM on weekdays.
     */
    @Scheduled(cron = "${trading.fiidii.report-cron:0 0 10 * * MON-FRI}", zone = "Asia/Kolkata")
    public void sendFiiDiiReport() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        if (!config.isEnabled() || !config.isSendAlerts() || !isWeekday()) {
            log.debug("FII/DII report skipped - disabled or weekend");
            return;
        }

        log.info("=== SCHEDULED TASK: Sending FII/DII Report ===");

        try {
            // Use the report fetched at 9:45 AM, or generate fresh if not available
            FiiDiiReport report = latestReport;
            
            if (report == null || !report.getReportDate().equals(LocalDate.now())) {
                log.info("Generating fresh FII/DII report...");
                LocalDate previousTradingDay = dataService.getPreviousTradingDay(LocalDate.now().minusDays(1));
                report = reportService.generateReport(previousTradingDay);
                latestReport = report;
            }

            if (report == null) {
                log.error("Failed to generate FII/DII report - no data available");
                return;
            }

            // Send the report
            reportService.sendReport(report);
            
            // Log summary
            DailyActivity daily = report.getDailyActivity();
            log.info("FII/DII report sent: FII Net={}, DII Net={}, Sentiment={}",
                    formatNumber(daily.getFiiNetValue()),
                    formatNumber(daily.getDiiNetValue()),
                    daily.getOverallSentiment());

            // Log top sector flows
            if (!report.getSectorFlows().isEmpty()) {
                log.info("Top sector flows:");
                report.getSectorFlows().stream()
                        .limit(5)
                        .forEach(flow -> log.info("  - {}: ₹{} Cr ({})",
                                flow.getSector(),
                                formatNumber(flow.getTotalNetFlow()),
                                flow.getFlowDirection()));
            }

            log.info("FII/DII report sent successfully");

        } catch (Exception e) {
            log.error("Failed to send FII/DII report: {}", e.getMessage(), e);
        }
    }

    /**
     * Get the latest FII/DII report (for API access).
     */
    public FiiDiiReport getLatestReport() {
        return latestReport;
    }

    /**
     * Manually trigger report generation and sending.
     */
    public FiiDiiReport triggerManualReport() {
        log.info("Manual FII/DII report triggered");
        LocalDate previousTradingDay = dataService.getPreviousTradingDay(LocalDate.now().minusDays(1));
        FiiDiiReport report = reportService.generateReport(previousTradingDay);
        latestReport = report;
        reportService.sendReport(report);
        return report;
    }

    /**
     * Get FII/DII summary for integration with other services.
     */
    public String getFiiDiiSummary() {
        if (latestReport == null) {
            return "FII/DII data not available";
        }

        DailyActivity daily = latestReport.getDailyActivity();
        return String.format("FII: %s₹%.0f Cr | DII: %s₹%.0f Cr | %s",
                daily.getFiiNetValue() >= 0 ? "+" : "",
                daily.getFiiNetValue(),
                daily.getDiiNetValue() >= 0 ? "+" : "",
                daily.getDiiNetValue(),
                daily.getOverallSentiment());
    }

    /**
     * Check if FII is heavily selling (for risk management integration).
     */
    public boolean isHeavyFiiSelling() {
        if (latestReport == null) return false;
        return latestReport.isHeavyFiiSelling();
    }

    /**
     * Check if FII is heavily buying (for trading opportunities).
     */
    public boolean isHeavyFiiBuying() {
        if (latestReport == null) return false;
        return latestReport.isHeavyFiiBuying();
    }

    private boolean isWeekday() {
        DayOfWeek day = LocalDate.now().getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    private String formatNumber(double value) {
        return String.format("%.0f", value);
    }
}
