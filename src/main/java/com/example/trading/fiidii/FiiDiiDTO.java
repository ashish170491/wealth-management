package com.example.trading.fiidii;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTOs for FII/DII activity tracking and analysis.
 */
public class FiiDiiDTO {

    /**
     * Daily FII/DII cash market activity from NSE.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyActivity {
        private LocalDate date;
        
        // FII (Foreign Institutional Investors) data in Crores
        private double fiiBuyValue;
        private double fiiSellValue;
        private double fiiNetValue;  // Buy - Sell
        
        // DII (Domestic Institutional Investors) data in Crores
        private double diiBuyValue;
        private double diiSellValue;
        private double diiNetValue;  // Buy - Sell
        
        // Combined metrics
        private double totalInstitutionalNet;  // FII + DII net
        private String overallSentiment;       // BULLISH, BEARISH, NEUTRAL
        
        public String getOverallSentiment() {
            if (fiiNetValue > 0 && diiNetValue > 0) return "STRONG_BULLISH";
            if (fiiNetValue < 0 && diiNetValue < 0) return "STRONG_BEARISH";
            if (fiiNetValue > 0 && diiNetValue < 0) return "FII_BULLISH_DII_BEARISH";
            if (fiiNetValue < 0 && diiNetValue > 0) return "FII_BEARISH_DII_BULLISH";
            return "NEUTRAL";
        }
        
        public double getTotalInstitutionalNet() {
            return fiiNetValue + diiNetValue;
        }
    }

    /**
     * Bulk/Block deal information.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InstitutionalDeal {
        private LocalDate date;
        private String symbol;
        private String clientName;
        private String dealType;        // BULK or BLOCK
        private String transactionType; // BUY or SELL
        private long quantity;
        private double price;
        private double value;           // In Crores
        private String sector;          // Mapped sector
        private boolean isFII;          // True if foreign institution
        private boolean isDII;          // True if domestic institution
    }

    /**
     * Sector-wise FII/DII flow analysis.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SectorFlow {
        private String sector;
        private double fiiBuyValue;
        private double fiiSellValue;
        private double fiiNetValue;
        private double diiBuyValue;
        private double diiSellValue;
        private double diiNetValue;
        private double totalNetFlow;
        private int dealCount;
        private List<String> topBoughtStocks;
        private List<String> topSoldStocks;
        private String flowDirection;   // INFLOW, OUTFLOW, NEUTRAL
        
        public String getFlowDirection() {
            if (totalNetFlow > 50) return "STRONG_INFLOW";
            if (totalNetFlow > 0) return "INFLOW";
            if (totalNetFlow < -50) return "STRONG_OUTFLOW";
            if (totalNetFlow < 0) return "OUTFLOW";
            return "NEUTRAL";
        }
    }

    /**
     * Complete FII/DII analysis report.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FiiDiiReport {
        private LocalDate reportDate;
        private LocalDate dataDate;     // Previous trading day
        
        // Overall market activity
        private DailyActivity dailyActivity;
        
        // Trend analysis (last 5 days)
        private List<DailyActivity> recentTrend;
        private double fii5DayNetFlow;
        private double dii5DayNetFlow;
        private String trendDirection;  // FII accumulating/distributing
        
        // Sector-wise breakdown
        private List<SectorFlow> sectorFlows;
        private List<String> sectorsWithInflow;
        private List<String> sectorsWithOutflow;
        
        // Notable deals
        private List<InstitutionalDeal> significantDeals;
        private List<InstitutionalDeal> fiiDeals;
        private List<InstitutionalDeal> diiDeals;
        
        // Alerts
        private List<String> alerts;
        private boolean heavyFiiSelling;
        private boolean heavyFiiBuying;
        
        // Actionable insights
        private List<String> tradingInsights;
    }

    /**
     * Stock with recent institutional activity.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockInstitutionalActivity {
        private String symbol;
        private String companyName;
        private String sector;
        private double totalFiiBuy;
        private double totalFiiSell;
        private double totalDiiBuy;
        private double totalDiiSell;
        private double netInstitutionalFlow;
        private int dealCount;
        private String activityType;    // ACCUMULATION, DISTRIBUTION, MIXED
        private LocalDate lastDealDate;
    }

    /**
     * Historical FII/DII trend data.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoricalTrend {
        private int days;
        private double totalFiiNet;
        private double totalDiiNet;
        private double avgDailyFiiNet;
        private double avgDailyDiiNet;
        private int fiiBuyingDays;
        private int fiiSellingDays;
        private int diiBuyingDays;
        private int diiSellingDays;
        private String fiiTrend;        // ACCUMULATING, DISTRIBUTING, NEUTRAL
        private String diiTrend;        // ACCUMULATING, DISTRIBUTING, NEUTRAL
    }
}
