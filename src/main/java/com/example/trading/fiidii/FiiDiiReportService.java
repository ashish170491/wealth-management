package com.example.trading.fiidii;

import com.example.trading.ai.AiService;
import com.example.trading.fiidii.FiiDiiDTO.*;
import com.example.trading.notification.EmailNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to generate and send FII/DII analysis reports.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FiiDiiReportService {

    private final FiiDiiDataService dataService;
    private final FiiDiiSectorAnalysisService sectorAnalysisService;
    private final FiiDiiConfig config;
    private final EmailNotificationService emailService;
    private final AiService aiService;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm");

    /**
     * Generate comprehensive FII/DII report.
     */
    public FiiDiiReport generateReport(LocalDate dataDate) {
        log.info("Generating FII/DII report for data date: {}", dataDate);

        // Fetch daily activity
        DailyActivity dailyActivity = dataService.fetchDailyActivity(dataDate);

        // Fetch historical trend
        List<DailyActivity> recentTrend = dataService.fetchHistoricalData(config.getTrendAnalysisDays());
        HistoricalTrend trend = sectorAnalysisService.analyzeHistoricalTrend(config.getTrendAnalysisDays());

        // Analyze sector flows
        List<SectorFlow> sectorFlows = sectorAnalysisService.analyzeSectorFlows(dataDate);

        // Fetch significant deals
        List<InstitutionalDeal> allDeals = dataService.fetchAllDeals(dataDate);
        List<InstitutionalDeal> significantDeals = allDeals.stream()
                .filter(d -> d.getValue() >= config.getSignificantDealThreshold())
                .collect(Collectors.toList());

        // Separate FII and DII deals
        List<InstitutionalDeal> fiiDeals = allDeals.stream()
                .filter(InstitutionalDeal::isFII)
                .collect(Collectors.toList());
        List<InstitutionalDeal> diiDeals = allDeals.stream()
                .filter(InstitutionalDeal::isDII)
                .collect(Collectors.toList());

        // Build report
        FiiDiiReport report = FiiDiiReport.builder()
                .reportDate(LocalDate.now())
                .dataDate(dataDate)
                .dailyActivity(dailyActivity)
                .recentTrend(recentTrend)
                .fii5DayNetFlow(trend.getTotalFiiNet())
                .dii5DayNetFlow(trend.getTotalDiiNet())
                .trendDirection(trend.getFiiTrend())
                .sectorFlows(sectorFlows)
                .sectorsWithInflow(sectorFlows.stream()
                        .filter(f -> f.getTotalNetFlow() > 0)
                        .map(SectorFlow::getSector)
                        .collect(Collectors.toList()))
                .sectorsWithOutflow(sectorFlows.stream()
                        .filter(f -> f.getTotalNetFlow() < 0)
                        .map(SectorFlow::getSector)
                        .collect(Collectors.toList()))
                .significantDeals(significantDeals)
                .fiiDeals(fiiDeals)
                .diiDeals(diiDeals)
                .heavyFiiSelling(dailyActivity.getFiiNetValue() < -config.getHeavySellingThreshold())
                .heavyFiiBuying(dailyActivity.getFiiNetValue() > config.getHeavyBuyingThreshold())
                .build();

        // Generate alerts and insights
        report.setAlerts(sectorAnalysisService.generateAlerts(dailyActivity, trend));
        report.setTradingInsights(sectorAnalysisService.generateTradingInsights(report));

        // Enrich with AI analysis
        if (aiService.isAvailable()) {
            try {
                String context = String.format(
                    "FII/DII Activity Report for Indian Markets:\n" +
                    "FII Net: %.0f Cr (%s), DII Net: %.0f Cr (%s)\n" +
                    "5-day FII trend: %s, 5-day FII net flow: %.0f Cr, 5-day DII net flow: %.0f Cr\n" +
                    "Sectors with institutional inflow: %s\n" +
                    "Sectors with institutional outflow: %s\n" +
                    "Significant deals count: %d\n\n" +
                    "Analyze the institutional flow pattern and provide actionable insights for today's trading.",
                    dailyActivity.getFiiNetValue(), dailyActivity.getFiiNetValue() >= 0 ? "buying" : "selling",
                    dailyActivity.getDiiNetValue(), dailyActivity.getDiiNetValue() >= 0 ? "buying" : "selling",
                    report.getTrendDirection(), report.getFii5DayNetFlow(), report.getDii5DayNetFlow(),
                    report.getSectorsWithInflow(),
                    report.getSectorsWithOutflow(),
                    report.getSignificantDeals() != null ? report.getSignificantDeals().size() : 0
                );
                String aiInsight = aiService.analyzeForReport(context);
                if (!aiInsight.isEmpty()) {
                    List<String> insights = report.getTradingInsights() != null
                            ? new ArrayList<>(report.getTradingInsights()) : new ArrayList<>();
                    insights.add("AI Analysis: " + aiInsight);
                    report.setTradingInsights(insights);
                }
            } catch (Exception e) {
                log.warn("FII/DII report: AI enrichment failed: {}", e.getMessage());
            }
        }

        log.info("FII/DII report generated: FII Net={}, DII Net={}, Sectors analyzed={}",
                dailyActivity.getFiiNetValue(), dailyActivity.getDiiNetValue(), sectorFlows.size());

        return report;
    }

    /**
     * Send FII/DII report via email.
     */
    public void sendReport(FiiDiiReport report) {
        if (!config.isSendAlerts()) {
            log.info("FII/DII email alerts disabled");
            return;
        }

        try {
            String subject = buildEmailSubject(report);
            String body = buildEmailBody(report);
            
            emailService.sendHtmlEmail(subject, body);
            log.info("FII/DII report email sent successfully");
        } catch (Exception e) {
            log.error("Failed to send FII/DII report email: {}", e.getMessage(), e);
        }
    }

    /**
     * Build email subject based on market sentiment.
     */
    private String buildEmailSubject(FiiDiiReport report) {
        DailyActivity daily = report.getDailyActivity();
        String sentiment = daily.getOverallSentiment();
        
        String emoji;
        switch (sentiment) {
            case "STRONG_BULLISH": emoji = "🟢"; break;
            case "STRONG_BEARISH": emoji = "🔴"; break;
            default: emoji = "🟡";
        }

        return String.format("%s FII/DII Report %s | FII: %s₹%.0f Cr | DII: %s₹%.0f Cr",
                emoji,
                report.getDataDate().format(DATE_FORMAT),
                daily.getFiiNetValue() >= 0 ? "+" : "",
                daily.getFiiNetValue(),
                daily.getDiiNetValue() >= 0 ? "+" : "",
                daily.getDiiNetValue());
    }

    /**
     * Build comprehensive email body.
     */
    private String buildEmailBody(FiiDiiReport report) {
        StringBuilder sb = new StringBuilder();
        DailyActivity daily = report.getDailyActivity();

        // Header
        sb.append("<html><body style='font-family: Arial, sans-serif; max-width: 800px; margin: 0 auto;'>");
        sb.append("<h1 style='color: #333; border-bottom: 2px solid #4CAF50;'>📊 FII/DII Activity Report</h1>");
        sb.append("<p style='color: #666;'>Data Date: <strong>").append(report.getDataDate().format(DATE_FORMAT)).append("</strong>");
        sb.append(" | Generated: ").append(LocalDateTime.now().format(DATETIME_FORMAT)).append("</p>");

        // Alerts Section (if any)
        if (report.getAlerts() != null && !report.getAlerts().isEmpty()) {
            sb.append("<div style='background-color: #FFF3CD; border: 1px solid #FFC107; padding: 15px; margin: 15px 0; border-radius: 5px;'>");
            sb.append("<h3 style='color: #856404; margin-top: 0;'>⚠️ Important Alerts</h3>");
            sb.append("<ul style='margin-bottom: 0;'>");
            for (String alert : report.getAlerts()) {
                sb.append("<li>").append(alert).append("</li>");
            }
            sb.append("</ul></div>");
        }

        // Daily Summary
        sb.append("<h2 style='color: #2196F3;'>📈 Daily Summary</h2>");
        sb.append("<table style='width: 100%; border-collapse: collapse; margin-bottom: 20px;'>");
        sb.append("<tr style='background-color: #f5f5f5;'>");
        sb.append("<th style='padding: 10px; border: 1px solid #ddd; text-align: left;'>Category</th>");
        sb.append("<th style='padding: 10px; border: 1px solid #ddd; text-align: right;'>Buy (₹ Cr)</th>");
        sb.append("<th style='padding: 10px; border: 1px solid #ddd; text-align: right;'>Sell (₹ Cr)</th>");
        sb.append("<th style='padding: 10px; border: 1px solid #ddd; text-align: right;'>Net (₹ Cr)</th>");
        sb.append("</tr>");

        // FII Row
        String fiiColor = daily.getFiiNetValue() >= 0 ? "#4CAF50" : "#F44336";
        sb.append("<tr>");
        sb.append("<td style='padding: 10px; border: 1px solid #ddd;'><strong>FII (Foreign)</strong></td>");
        sb.append("<td style='padding: 10px; border: 1px solid #ddd; text-align: right;'>").append(formatNumber(daily.getFiiBuyValue())).append("</td>");
        sb.append("<td style='padding: 10px; border: 1px solid #ddd; text-align: right;'>").append(formatNumber(daily.getFiiSellValue())).append("</td>");
        sb.append("<td style='padding: 10px; border: 1px solid #ddd; text-align: right; color: ").append(fiiColor).append("; font-weight: bold;'>");
        sb.append(formatNetValue(daily.getFiiNetValue())).append("</td>");
        sb.append("</tr>");

        // DII Row
        String diiColor = daily.getDiiNetValue() >= 0 ? "#4CAF50" : "#F44336";
        sb.append("<tr style='background-color: #f9f9f9;'>");
        sb.append("<td style='padding: 10px; border: 1px solid #ddd;'><strong>DII (Domestic)</strong></td>");
        sb.append("<td style='padding: 10px; border: 1px solid #ddd; text-align: right;'>").append(formatNumber(daily.getDiiBuyValue())).append("</td>");
        sb.append("<td style='padding: 10px; border: 1px solid #ddd; text-align: right;'>").append(formatNumber(daily.getDiiSellValue())).append("</td>");
        sb.append("<td style='padding: 10px; border: 1px solid #ddd; text-align: right; color: ").append(diiColor).append("; font-weight: bold;'>");
        sb.append(formatNetValue(daily.getDiiNetValue())).append("</td>");
        sb.append("</tr>");

        // Total Row
        double totalNet = daily.getFiiNetValue() + daily.getDiiNetValue();
        String totalColor = totalNet >= 0 ? "#4CAF50" : "#F44336";
        sb.append("<tr style='background-color: #e3f2fd;'>");
        sb.append("<td style='padding: 10px; border: 1px solid #ddd;'><strong>TOTAL</strong></td>");
        sb.append("<td style='padding: 10px; border: 1px solid #ddd; text-align: right;'>").append(formatNumber(daily.getFiiBuyValue() + daily.getDiiBuyValue())).append("</td>");
        sb.append("<td style='padding: 10px; border: 1px solid #ddd; text-align: right;'>").append(formatNumber(daily.getFiiSellValue() + daily.getDiiSellValue())).append("</td>");
        sb.append("<td style='padding: 10px; border: 1px solid #ddd; text-align: right; color: ").append(totalColor).append("; font-weight: bold;'>");
        sb.append(formatNetValue(totalNet)).append("</td>");
        sb.append("</tr>");
        sb.append("</table>");

        // Market Sentiment
        sb.append("<div style='background-color: ").append(getSentimentBgColor(daily.getOverallSentiment()));
        sb.append("; padding: 15px; border-radius: 5px; margin-bottom: 20px;'>");
        sb.append("<strong>Market Sentiment: </strong>").append(formatSentiment(daily.getOverallSentiment()));
        sb.append("</div>");

        // 5-Day Trend
        sb.append("<h2 style='color: #2196F3;'>📊 5-Day Trend</h2>");
        sb.append("<table style='width: 100%; border-collapse: collapse; margin-bottom: 20px;'>");
        sb.append("<tr style='background-color: #f5f5f5;'>");
        sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>Metric</th>");
        sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>FII</th>");
        sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>DII</th>");
        sb.append("</tr>");
        sb.append("<tr>");
        sb.append("<td style='padding: 8px; border: 1px solid #ddd;'>5-Day Net Flow</td>");
        sb.append("<td style='padding: 8px; border: 1px solid #ddd; text-align: center; color: ").append(report.getFii5DayNetFlow() >= 0 ? "#4CAF50" : "#F44336").append(";'>");
        sb.append(formatNetValue(report.getFii5DayNetFlow())).append("</td>");
        sb.append("<td style='padding: 8px; border: 1px solid #ddd; text-align: center; color: ").append(report.getDii5DayNetFlow() >= 0 ? "#4CAF50" : "#F44336").append(";'>");
        sb.append(formatNetValue(report.getDii5DayNetFlow())).append("</td>");
        sb.append("</tr>");
        sb.append("<tr style='background-color: #f9f9f9;'>");
        sb.append("<td style='padding: 8px; border: 1px solid #ddd;'>Trend</td>");
        sb.append("<td style='padding: 8px; border: 1px solid #ddd; text-align: center;'>").append(report.getTrendDirection()).append("</td>");
        sb.append("<td style='padding: 8px; border: 1px solid #ddd; text-align: center;'>-</td>");
        sb.append("</tr>");
        sb.append("</table>");

        // Sector-wise Flows
        if (report.getSectorFlows() != null && !report.getSectorFlows().isEmpty()) {
            sb.append("<h2 style='color: #2196F3;'>🏭 Sector-wise Institutional Flows</h2>");
            sb.append("<table style='width: 100%; border-collapse: collapse; margin-bottom: 20px;'>");
            sb.append("<tr style='background-color: #f5f5f5;'>");
            sb.append("<th style='padding: 8px; border: 1px solid #ddd; text-align: left;'>Sector</th>");
            sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>FII Net</th>");
            sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>DII Net</th>");
            sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>Total Net</th>");
            sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>Flow</th>");
            sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>Top Stocks</th>");
            sb.append("</tr>");

            int count = 0;
            for (SectorFlow flow : report.getSectorFlows()) {
                if (count++ >= 10) break; // Limit to top 10 sectors
                
                String bgColor = count % 2 == 0 ? "#f9f9f9" : "#ffffff";
                String flowColor = flow.getTotalNetFlow() >= 0 ? "#4CAF50" : "#F44336";
                
                sb.append("<tr style='background-color: ").append(bgColor).append(";'>");
                sb.append("<td style='padding: 8px; border: 1px solid #ddd;'><strong>").append(flow.getSector()).append("</strong></td>");
                sb.append("<td style='padding: 8px; border: 1px solid #ddd; text-align: center;'>").append(formatNetValue(flow.getFiiNetValue())).append("</td>");
                sb.append("<td style='padding: 8px; border: 1px solid #ddd; text-align: center;'>").append(formatNetValue(flow.getDiiNetValue())).append("</td>");
                sb.append("<td style='padding: 8px; border: 1px solid #ddd; text-align: center; color: ").append(flowColor).append("; font-weight: bold;'>");
                sb.append(formatNetValue(flow.getTotalNetFlow())).append("</td>");
                sb.append("<td style='padding: 8px; border: 1px solid #ddd; text-align: center;'>").append(formatFlowDirection(flow.getFlowDirection())).append("</td>");
                
                // Top stocks
                List<String> topStocks = flow.getTotalNetFlow() >= 0 ? flow.getTopBoughtStocks() : flow.getTopSoldStocks();
                sb.append("<td style='padding: 8px; border: 1px solid #ddd; font-size: 12px;'>");
                sb.append(topStocks != null ? String.join(", ", topStocks) : "-");
                sb.append("</td>");
                sb.append("</tr>");
            }
            sb.append("</table>");
        }

        // Significant Deals
        if (report.getSignificantDeals() != null && !report.getSignificantDeals().isEmpty()) {
            sb.append("<h2 style='color: #2196F3;'>💼 Significant Institutional Deals (>₹").append(formatNumber(config.getSignificantDealThreshold())).append(" Cr)</h2>");
            sb.append("<table style='width: 100%; border-collapse: collapse; margin-bottom: 20px;'>");
            sb.append("<tr style='background-color: #f5f5f5;'>");
            sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>Stock</th>");
            sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>Sector</th>");
            sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>Type</th>");
            sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>Action</th>");
            sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>Value (Cr)</th>");
            sb.append("<th style='padding: 8px; border: 1px solid #ddd;'>Client</th>");
            sb.append("</tr>");

            int count = 0;
            for (InstitutionalDeal deal : report.getSignificantDeals()) {
                if (count++ >= 15) break;
                
                String bgColor = count % 2 == 0 ? "#f9f9f9" : "#ffffff";
                String actionColor = "BUY".equals(deal.getTransactionType()) ? "#4CAF50" : "#F44336";
                String investorType = deal.isFII() ? "FII" : (deal.isDII() ? "DII" : "Other");
                
                sb.append("<tr style='background-color: ").append(bgColor).append(";'>");
                sb.append("<td style='padding: 8px; border: 1px solid #ddd;'><strong>").append(deal.getSymbol()).append("</strong></td>");
                sb.append("<td style='padding: 8px; border: 1px solid #ddd;'>").append(deal.getSector()).append("</td>");
                sb.append("<td style='padding: 8px; border: 1px solid #ddd; text-align: center;'>").append(investorType).append("</td>");
                sb.append("<td style='padding: 8px; border: 1px solid #ddd; text-align: center; color: ").append(actionColor).append("; font-weight: bold;'>");
                sb.append(deal.getTransactionType()).append("</td>");
                sb.append("<td style='padding: 8px; border: 1px solid #ddd; text-align: right;'>₹").append(formatNumber(deal.getValue())).append("</td>");
                sb.append("<td style='padding: 8px; border: 1px solid #ddd; font-size: 11px;'>").append(truncateClient(deal.getClientName())).append("</td>");
                sb.append("</tr>");
            }
            sb.append("</table>");
        }

        // Trading Insights
        if (report.getTradingInsights() != null && !report.getTradingInsights().isEmpty()) {
            sb.append("<h2 style='color: #2196F3;'>💡 Trading Insights</h2>");
            sb.append("<div style='background-color: #E3F2FD; padding: 15px; border-radius: 5px; margin-bottom: 20px;'>");
            sb.append("<ul style='margin: 0; padding-left: 20px;'>");
            for (String insight : report.getTradingInsights()) {
                sb.append("<li style='margin-bottom: 8px;'>").append(insight).append("</li>");
            }
            sb.append("</ul></div>");
        }

        // Footer
        sb.append("<hr style='border: none; border-top: 1px solid #ddd; margin: 20px 0;'>");
        sb.append("<p style='color: #999; font-size: 12px;'>");
        sb.append("Data Source: NSE India | ");
        sb.append("Note: FII/DII data is from previous trading day. Sector flows are based on bulk/block deals.<br>");
        sb.append("This report is for informational purposes only. Always do your own research before making investment decisions.");
        sb.append("</p>");
        sb.append("</body></html>");

        return sb.toString();
    }

    private String formatNumber(double value) {
        return String.format("%,.2f", value);
    }

    private String formatNetValue(double value) {
        String sign = value >= 0 ? "+" : "";
        return sign + String.format("%.0f", value);
    }

    private String formatSentiment(String sentiment) {
        switch (sentiment) {
            case "STRONG_BULLISH": return "🟢 Strong Bullish (FII + DII both buying)";
            case "STRONG_BEARISH": return "🔴 Strong Bearish (FII + DII both selling)";
            case "FII_BULLISH_DII_BEARISH": return "🟡 Mixed (FII buying, DII selling)";
            case "FII_BEARISH_DII_BULLISH": return "🟡 Mixed (FII selling, DII buying)";
            default: return "⚪ Neutral";
        }
    }

    private String getSentimentBgColor(String sentiment) {
        switch (sentiment) {
            case "STRONG_BULLISH": return "#C8E6C9";
            case "STRONG_BEARISH": return "#FFCDD2";
            default: return "#FFF9C4";
        }
    }

    private String formatFlowDirection(String direction) {
        switch (direction) {
            case "STRONG_INFLOW": return "⬆️⬆️";
            case "INFLOW": return "⬆️";
            case "STRONG_OUTFLOW": return "⬇️⬇️";
            case "OUTFLOW": return "⬇️";
            default: return "➡️";
        }
    }

    private String truncateClient(String clientName) {
        if (clientName == null) return "-";
        return clientName.length() > 30 ? clientName.substring(0, 27) + "..." : clientName;
    }
}
