package com.example.trading.fiidii;

import com.example.trading.fiidii.FiiDiiDTO.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Advanced sector-wise FII/DII flow analysis.
 * 
 * Analyzes institutional deals to determine:
 * - Which sectors are seeing institutional inflow/outflow
 * - Which stocks are being accumulated/distributed
 * - Generates actionable trading insights
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FiiDiiSectorAnalysisService {

    private final FiiDiiDataService dataService;
    private final FiiDiiConfig config;

    /**
     * Perform comprehensive sector-wise analysis of FII/DII flows.
     */
    public List<SectorFlow> analyzeSectorFlows(LocalDate date) {
        log.info("Analyzing sector-wise FII/DII flows for {}", date);
        
        List<InstitutionalDeal> deals = dataService.fetchAllDeals(date);
        if (deals.isEmpty()) {
            log.warn("No institutional deals found for {}", date);
            return Collections.emptyList();
        }

        // Group deals by sector
        Map<String, List<InstitutionalDeal>> dealsBySector = deals.stream()
                .collect(Collectors.groupingBy(InstitutionalDeal::getSector));

        List<SectorFlow> sectorFlows = new ArrayList<>();

        for (Map.Entry<String, List<InstitutionalDeal>> entry : dealsBySector.entrySet()) {
            String sector = entry.getKey();
            List<InstitutionalDeal> sectorDeals = entry.getValue();

            SectorFlow flow = calculateSectorFlow(sector, sectorDeals);
            sectorFlows.add(flow);
        }

        // Sort by total net flow (strongest inflow first)
        sectorFlows.sort((a, b) -> Double.compare(b.getTotalNetFlow(), a.getTotalNetFlow()));

        log.info("Analyzed {} sectors with institutional activity", sectorFlows.size());
        return sectorFlows;
    }

    /**
     * Calculate flow metrics for a single sector.
     */
    private SectorFlow calculateSectorFlow(String sector, List<InstitutionalDeal> deals) {
        double fiiBuy = 0, fiiSell = 0, diiBuy = 0, diiSell = 0;
        Map<String, Double> stockNetFlow = new HashMap<>();

        for (InstitutionalDeal deal : deals) {
            double value = deal.getValue();
            boolean isBuy = "BUY".equals(deal.getTransactionType());

            if (deal.isFII()) {
                if (isBuy) fiiBuy += value;
                else fiiSell += value;
            }
            if (deal.isDII()) {
                if (isBuy) diiBuy += value;
                else diiSell += value;
            }

            // Track per-stock flow
            stockNetFlow.merge(deal.getSymbol(), 
                    isBuy ? value : -value, 
                    Double::sum);
        }

        // Find top bought and sold stocks
        List<String> topBought = stockNetFlow.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        List<String> topSold = stockNetFlow.entrySet().stream()
                .filter(e -> e.getValue() < 0)
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        return SectorFlow.builder()
                .sector(sector)
                .fiiBuyValue(round(fiiBuy))
                .fiiSellValue(round(fiiSell))
                .fiiNetValue(round(fiiBuy - fiiSell))
                .diiBuyValue(round(diiBuy))
                .diiSellValue(round(diiSell))
                .diiNetValue(round(diiBuy - diiSell))
                .totalNetFlow(round(fiiBuy - fiiSell + diiBuy - diiSell))
                .dealCount(deals.size())
                .topBoughtStocks(topBought)
                .topSoldStocks(topSold)
                .build();
    }

    /**
     * Analyze individual stock institutional activity.
     */
    public List<StockInstitutionalActivity> analyzeStockActivity(LocalDate date) {
        List<InstitutionalDeal> deals = dataService.fetchAllDeals(date);
        Map<String, String> sectorMap = config.getSectorMapping().isEmpty() 
                ? config.getDefaultSectorMapping() 
                : config.getSectorMapping();

        // Group by symbol
        Map<String, List<InstitutionalDeal>> dealsBySymbol = deals.stream()
                .collect(Collectors.groupingBy(InstitutionalDeal::getSymbol));

        List<StockInstitutionalActivity> activities = new ArrayList<>();

        for (Map.Entry<String, List<InstitutionalDeal>> entry : dealsBySymbol.entrySet()) {
            String symbol = entry.getKey();
            List<InstitutionalDeal> stockDeals = entry.getValue();

            double fiiBuy = 0, fiiSell = 0, diiBuy = 0, diiSell = 0;

            for (InstitutionalDeal deal : stockDeals) {
                double value = deal.getValue();
                boolean isBuy = "BUY".equals(deal.getTransactionType());

                if (deal.isFII()) {
                    if (isBuy) fiiBuy += value;
                    else fiiSell += value;
                }
                if (deal.isDII()) {
                    if (isBuy) diiBuy += value;
                    else diiSell += value;
                }
            }

            double netFlow = (fiiBuy - fiiSell) + (diiBuy - diiSell);
            String activityType = determineActivityType(fiiBuy, fiiSell, diiBuy, diiSell);

            activities.add(StockInstitutionalActivity.builder()
                    .symbol(symbol)
                    .sector(sectorMap.getOrDefault(symbol, "Others"))
                    .totalFiiBuy(round(fiiBuy))
                    .totalFiiSell(round(fiiSell))
                    .totalDiiBuy(round(diiBuy))
                    .totalDiiSell(round(diiSell))
                    .netInstitutionalFlow(round(netFlow))
                    .dealCount(stockDeals.size())
                    .activityType(activityType)
                    .lastDealDate(date)
                    .build());
        }

        // Sort by absolute net flow
        activities.sort((a, b) -> Double.compare(
                Math.abs(b.getNetInstitutionalFlow()), 
                Math.abs(a.getNetInstitutionalFlow())));

        return activities;
    }

    /**
     * Calculate historical trend analysis.
     */
    public HistoricalTrend analyzeHistoricalTrend(int days) {
        List<DailyActivity> history = dataService.fetchHistoricalData(days);
        
        if (history.isEmpty()) {
            return HistoricalTrend.builder()
                    .days(0)
                    .fiiTrend("UNKNOWN")
                    .diiTrend("UNKNOWN")
                    .build();
        }

        double totalFiiNet = 0, totalDiiNet = 0;
        int fiiBuyDays = 0, fiiSellDays = 0, diiBuyDays = 0, diiSellDays = 0;

        for (DailyActivity activity : history) {
            totalFiiNet += activity.getFiiNetValue();
            totalDiiNet += activity.getDiiNetValue();

            if (activity.getFiiNetValue() > 0) fiiBuyDays++;
            else if (activity.getFiiNetValue() < 0) fiiSellDays++;

            if (activity.getDiiNetValue() > 0) diiBuyDays++;
            else if (activity.getDiiNetValue() < 0) diiSellDays++;
        }

        int actualDays = history.size();
        String fiiTrend = determineTrend(totalFiiNet, fiiBuyDays, fiiSellDays, actualDays);
        String diiTrend = determineTrend(totalDiiNet, diiBuyDays, diiSellDays, actualDays);

        return HistoricalTrend.builder()
                .days(actualDays)
                .totalFiiNet(round(totalFiiNet))
                .totalDiiNet(round(totalDiiNet))
                .avgDailyFiiNet(round(totalFiiNet / actualDays))
                .avgDailyDiiNet(round(totalDiiNet / actualDays))
                .fiiBuyingDays(fiiBuyDays)
                .fiiSellingDays(fiiSellDays)
                .diiBuyingDays(diiBuyDays)
                .diiSellingDays(diiSellDays)
                .fiiTrend(fiiTrend)
                .diiTrend(diiTrend)
                .build();
    }

    /**
     * Generate trading insights based on FII/DII analysis.
     */
    public List<String> generateTradingInsights(FiiDiiReport report) {
        List<String> insights = new ArrayList<>();
        DailyActivity daily = report.getDailyActivity();

        // 1. Overall market sentiment
        String sentiment = daily.getOverallSentiment();
        switch (sentiment) {
            case "STRONG_BULLISH":
                insights.add("🟢 STRONG BULLISH: Both FII and DII are net buyers. Favorable for long positions.");
                break;
            case "STRONG_BEARISH":
                insights.add("🔴 STRONG BEARISH: Both FII and DII are net sellers. Consider reducing exposure.");
                break;
            case "FII_BULLISH_DII_BEARISH":
                insights.add("🟡 MIXED: FII buying but DII selling. Watch for volatile swings.");
                break;
            case "FII_BEARISH_DII_BULLISH":
                insights.add("🟡 MIXED: FII selling but DII supporting. DIIs cushioning the fall.");
                break;
        }

        // 2. Heavy activity alerts
        if (Math.abs(daily.getFiiNetValue()) > config.getHeavySellingThreshold()) {
            if (daily.getFiiNetValue() < 0) {
                insights.add(String.format("⚠️ HEAVY FII SELLING: ₹%.0f Cr net outflow. Expect selling pressure.", 
                        Math.abs(daily.getFiiNetValue())));
            } else {
                insights.add(String.format("💹 HEAVY FII BUYING: ₹%.0f Cr net inflow. Bullish momentum expected.", 
                        daily.getFiiNetValue()));
            }
        }

        // 3. Sector-specific insights
        if (report.getSectorFlows() != null && !report.getSectorFlows().isEmpty()) {
            // Top inflow sector
            SectorFlow topInflow = report.getSectorFlows().get(0);
            if (topInflow.getTotalNetFlow() > 50) {
                insights.add(String.format("📈 SECTOR INFLOW: %s seeing ₹%.0f Cr institutional buying. Stocks: %s",
                        topInflow.getSector(), topInflow.getTotalNetFlow(),
                        String.join(", ", topInflow.getTopBoughtStocks())));
            }

            // Top outflow sector
            Optional<SectorFlow> topOutflow = report.getSectorFlows().stream()
                    .filter(f -> f.getTotalNetFlow() < -50)
                    .min(Comparator.comparingDouble(SectorFlow::getTotalNetFlow));
            
            topOutflow.ifPresent(flow -> 
                insights.add(String.format("📉 SECTOR OUTFLOW: %s seeing ₹%.0f Cr selling. Avoid: %s",
                        flow.getSector(), Math.abs(flow.getTotalNetFlow()),
                        String.join(", ", flow.getTopSoldStocks()))));
        }

        // 4. Trend-based insights
        if (report.getFii5DayNetFlow() < -10000) {
            insights.add("📊 5-DAY TREND: Persistent FII selling (>₹10,000 Cr). Market may remain under pressure.");
        } else if (report.getFii5DayNetFlow() > 10000) {
            insights.add("📊 5-DAY TREND: Strong FII accumulation (>₹10,000 Cr). Bullish trend intact.");
        }

        // 5. Actionable recommendations
        if (sentiment.equals("STRONG_BULLISH") && report.getFii5DayNetFlow() > 5000) {
            insights.add("💡 ACTION: Consider increasing equity exposure. Both short-term and medium-term bullish.");
        } else if (sentiment.equals("STRONG_BEARISH") && report.getFii5DayNetFlow() < -5000) {
            insights.add("💡 ACTION: Consider reducing positions and keeping higher cash. Risk-off mode advised.");
        }

        return insights;
    }

    /**
     * Generate alerts based on FII/DII activity.
     */
    public List<String> generateAlerts(DailyActivity daily, HistoricalTrend trend) {
        List<String> alerts = new ArrayList<>();

        // Heavy selling alert
        if (daily.getFiiNetValue() < -config.getHeavySellingThreshold()) {
            alerts.add(String.format("🚨 ALERT: Heavy FII selling of ₹%.0f Cr. Market may see downside pressure.", 
                    Math.abs(daily.getFiiNetValue())));
        }

        // Heavy buying alert
        if (daily.getFiiNetValue() > config.getHeavyBuyingThreshold()) {
            alerts.add(String.format("🎯 ALERT: Strong FII buying of ₹%.0f Cr. Bullish signal for market.", 
                    daily.getFiiNetValue()));
        }

        // Both selling alert (rare but significant)
        if (daily.getFiiNetValue() < -1000 && daily.getDiiNetValue() < -500) {
            alerts.add("⚠️ CRITICAL: Both FII and DII selling heavily. Risk-off sentiment in market.");
        }

        // Trend reversal alert
        if (trend != null) {
            if (trend.getFiiTrend().equals("DISTRIBUTING") && daily.getFiiNetValue() > 2000) {
                alerts.add("🔄 REVERSAL: FII trend was selling but today showed strong buying. Watch for trend change.");
            }
            if (trend.getFiiTrend().equals("ACCUMULATING") && daily.getFiiNetValue() < -2000) {
                alerts.add("🔄 REVERSAL: FII trend was buying but today showed selling. Caution advised.");
            }
        }

        return alerts;
    }

    private String determineActivityType(double fiiBuy, double fiiSell, double diiBuy, double diiSell) {
        double totalBuy = fiiBuy + diiBuy;
        double totalSell = fiiSell + diiSell;
        
        if (totalBuy > totalSell * 1.5) return "ACCUMULATION";
        if (totalSell > totalBuy * 1.5) return "DISTRIBUTION";
        return "MIXED";
    }

    private String determineTrend(double totalNet, int buyDays, int sellDays, int totalDays) {
        if (totalNet > 2000 && buyDays > sellDays) return "ACCUMULATING";
        if (totalNet < -2000 && sellDays > buyDays) return "DISTRIBUTING";
        return "NEUTRAL";
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
