package com.example.trading.fiidii;

import com.example.trading.fiidii.FiiDiiDTO.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for FII/DII data and analysis.
 */
@RestController
@RequestMapping("/api/fiidii")
@RequiredArgsConstructor
@Slf4j
public class FiiDiiController {

    private final FiiDiiDataService dataService;
    private final FiiDiiSectorAnalysisService sectorAnalysisService;
    private final FiiDiiReportService reportService;
    private final FiiDiiScheduler scheduler;
    private final FiiDiiConfig config;

    /**
     * Get latest FII/DII report.
     * GET /api/fiidii/report
     */
    @GetMapping("/report")
    public ResponseEntity<FiiDiiReport> getLatestReport() {
        FiiDiiReport report = scheduler.getLatestReport();
        if (report == null) {
            // Generate on-demand
            LocalDate previousDay = dataService.getPreviousTradingDay(LocalDate.now().minusDays(1));
            report = reportService.generateReport(previousDay);
        }
        return ResponseEntity.ok(report);
    }

    /**
     * Get FII/DII daily activity.
     * GET /api/fiidii/daily?date=2026-01-28
     */
    @GetMapping("/daily")
    public ResponseEntity<DailyActivity> getDailyActivity(
            @RequestParam(required = false) String date) {
        LocalDate targetDate = date != null ? LocalDate.parse(date) 
                : dataService.getPreviousTradingDay(LocalDate.now().minusDays(1));
        DailyActivity activity = dataService.fetchDailyActivity(targetDate);
        return ResponseEntity.ok(activity);
    }

    /**
     * Get historical FII/DII trend.
     * GET /api/fiidii/trend?days=5
     */
    @GetMapping("/trend")
    public ResponseEntity<Map<String, Object>> getHistoricalTrend(
            @RequestParam(defaultValue = "5") int days) {
        List<DailyActivity> history = dataService.fetchHistoricalData(days);
        HistoricalTrend trend = sectorAnalysisService.analyzeHistoricalTrend(days);
        
        Map<String, Object> response = new HashMap<>();
        response.put("trend", trend);
        response.put("dailyData", history);
        return ResponseEntity.ok(response);
    }

    /**
     * Get sector-wise FII/DII flows.
     * GET /api/fiidii/sectors?date=2026-01-28
     */
    @GetMapping("/sectors")
    public ResponseEntity<List<SectorFlow>> getSectorFlows(
            @RequestParam(required = false) String date) {
        LocalDate targetDate = date != null ? LocalDate.parse(date)
                : dataService.getPreviousTradingDay(LocalDate.now().minusDays(1));
        List<SectorFlow> flows = sectorAnalysisService.analyzeSectorFlows(targetDate);
        return ResponseEntity.ok(flows);
    }

    /**
     * Get institutional deals (bulk + block).
     * GET /api/fiidii/deals?date=2026-01-28
     */
    @GetMapping("/deals")
    public ResponseEntity<List<InstitutionalDeal>> getDeals(
            @RequestParam(required = false) String date) {
        LocalDate targetDate = date != null ? LocalDate.parse(date)
                : dataService.getPreviousTradingDay(LocalDate.now().minusDays(1));
        List<InstitutionalDeal> deals = dataService.fetchAllDeals(targetDate);
        return ResponseEntity.ok(deals);
    }

    /**
     * Get stock-wise institutional activity.
     * GET /api/fiidii/stocks?date=2026-01-28
     */
    @GetMapping("/stocks")
    public ResponseEntity<List<StockInstitutionalActivity>> getStockActivity(
            @RequestParam(required = false) String date) {
        LocalDate targetDate = date != null ? LocalDate.parse(date)
                : dataService.getPreviousTradingDay(LocalDate.now().minusDays(1));
        List<StockInstitutionalActivity> activities = sectorAnalysisService.analyzeStockActivity(targetDate);
        return ResponseEntity.ok(activities);
    }

    /**
     * Get quick FII/DII summary.
     * GET /api/fiidii/summary
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        FiiDiiReport report = scheduler.getLatestReport();
        if (report != null) {
            DailyActivity daily = report.getDailyActivity();
            summary.put("dataDate", report.getDataDate());
            summary.put("fiiNet", daily.getFiiNetValue());
            summary.put("diiNet", daily.getDiiNetValue());
            summary.put("totalNet", daily.getFiiNetValue() + daily.getDiiNetValue());
            summary.put("sentiment", daily.getOverallSentiment());
            summary.put("fii5DayNet", report.getFii5DayNetFlow());
            summary.put("dii5DayNet", report.getDii5DayNetFlow());
            summary.put("heavyFiiSelling", report.isHeavyFiiSelling());
            summary.put("heavyFiiBuying", report.isHeavyFiiBuying());
            summary.put("sectorsWithInflow", report.getSectorsWithInflow());
            summary.put("sectorsWithOutflow", report.getSectorsWithOutflow());
            summary.put("alerts", report.getAlerts());
        } else {
            summary.put("status", "No data available. Report will be generated at 10:00 AM.");
        }
        
        return ResponseEntity.ok(summary);
    }

    /**
     * Manually trigger report generation and email.
     * POST /api/fiidii/trigger-report
     */
    @PostMapping("/trigger-report")
    public ResponseEntity<Map<String, Object>> triggerReport() {
        log.info("Manual FII/DII report triggered via API");
        
        try {
            FiiDiiReport report = scheduler.triggerManualReport();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "FII/DII report generated and email sent");
            response.put("dataDate", report.getDataDate());
            response.put("fiiNet", report.getDailyActivity().getFiiNetValue());
            response.put("diiNet", report.getDailyActivity().getDiiNetValue());
            response.put("sentiment", report.getDailyActivity().getOverallSentiment());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to trigger FII/DII report: {}", e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get configuration status.
     * GET /api/fiidii/config
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("enabled", config.isEnabled());
        configInfo.put("sendAlerts", config.isSendAlerts());
        configInfo.put("heavySellingThreshold", config.getHeavySellingThreshold());
        configInfo.put("heavyBuyingThreshold", config.getHeavyBuyingThreshold());
        configInfo.put("significantDealThreshold", config.getSignificantDealThreshold());
        configInfo.put("trendAnalysisDays", config.getTrendAnalysisDays());
        configInfo.put("sectorMappingCount", config.getDefaultSectorMapping().size());
        return ResponseEntity.ok(configInfo);
    }

    /**
     * Clear caches and refresh data.
     * POST /api/fiidii/refresh
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refreshData() {
        log.info("Refreshing FII/DII data caches");
        dataService.clearCaches();
        return ResponseEntity.ok(Map.of("status", "Caches cleared. Fresh data will be fetched on next request."));
    }

    /**
     * Manually update FII/DII data for a specific date.
     * This is useful when NSE API is blocked and you want to input real data.
     * POST /api/fiidii/update-data
     * Body: { "date": "2026-01-28", "fiiBuy": 21044.50, "fiiSell": 20564.24, "diiBuy": 19578.39, "diiSell": 16217.80 }
     */
    @PostMapping("/update-data")
    public ResponseEntity<Map<String, Object>> updateRealData(@RequestBody Map<String, Object> data) {
        log.info("Manual FII/DII data update: {}", data);
        
        try {
            String dateStr = (String) data.get("date");
            LocalDate date = LocalDate.parse(dateStr);
            
            double fiiBuy = parseDouble(data.get("fiiBuy"));
            double fiiSell = parseDouble(data.get("fiiSell"));
            double diiBuy = parseDouble(data.get("diiBuy"));
            double diiSell = parseDouble(data.get("diiSell"));
            
            dataService.updateRealData(date, fiiBuy, fiiSell, diiBuy, diiSell);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "FII/DII data updated for " + date);
            response.put("fiiNet", fiiBuy - fiiSell);
            response.put("diiNet", diiBuy - diiSell);
            response.put("totalNet", (fiiBuy - fiiSell) + (diiBuy - diiSell));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to update FII/DII data: {}", e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    private double parseDouble(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).doubleValue();
        return Double.parseDouble(value.toString());
    }

    /**
     * Health check for FII/DII service.
     * GET /api/fiidii/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("service", "FII/DII Tracking");
        health.put("enabled", config.isEnabled());
        health.put("lastReportDate", scheduler.getLatestReport() != null
                ? scheduler.getLatestReport().getReportDate() : "None");
        health.put("status", config.isEnabled() ? "ACTIVE" : "DISABLED");
        return ResponseEntity.ok(health);
    }

    /**
     * Debug endpoint to fetch raw NSE API response.
     * GET /api/fiidii/debug-raw
     */
    @GetMapping("/debug-raw")
    public ResponseEntity<String> debugRawResponse() {
        log.info("Fetching raw NSE FII/DII API response for debugging");

        try {
            dataService.clearCaches(); // Clear cache to force fresh fetch
            LocalDate yesterday = dataService.getPreviousTradingDay(LocalDate.now().minusDays(1));

            // This will log the raw response
            DailyActivity activity = dataService.fetchDailyActivity(yesterday);

            return ResponseEntity.ok("Check logs for raw API response. Data fetched: " +
                    activity.toString());
        } catch (Exception e) {
            log.error("Failed to fetch raw data: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Error: " + e.getMessage());
        }
    }
}
