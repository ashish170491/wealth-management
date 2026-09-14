package com.example.trading.scanner;

import com.example.trading.notification.EmailNotificationService;
import com.example.trading.notification.EmailTemplateService;
import com.example.trading.scanner.QuantitativeDiscoveryService.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class QuantitativeDiscoveryReportService {

    private final QuantitativeDiscoveryService discoveryService;
    private final EmailNotificationService emailNotificationService;
    private final EmailTemplateService templateService;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /**
     * Run discovery scan and send results via email.
     */
    public DiscoveryReport runAndSendReport() {
        DiscoveryReport report = discoveryService.runDiscoveryScan();

        if (report.getOpportunities().isEmpty()) {
            log.info("Discovery Report: No opportunities found, skipping email");
            return report;
        }

        try {
            String htmlContent = buildReportHtml(report);
            String html = templateService.buildEmailTemplate(
                    "Quantitative Stock Discovery",
                    "Data-Driven Investment Opportunities",
                    htmlContent
            );

            String subject = String.format("Stock Discovery — %s | %d Opportunities Found (Score-Ranked)",
                    LocalDate.now().format(DATE_FMT), report.getOpportunities().size());

            emailNotificationService.sendHtmlEmail(subject, html);
            log.info("Discovery Report: Email sent with {} opportunities", report.getOpportunities().size());
        } catch (Exception e) {
            log.error("Discovery Report: Email failed: {}", e.getMessage(), e);
        }

        return report;
    }

    private String buildReportHtml(DiscoveryReport report) {
        StringBuilder html = new StringBuilder();

        // Summary header
        html.append("<div style='background:#1a1a2e;color:#e0e0e0;padding:20px;border-radius:8px;margin-bottom:20px'>");
        html.append("<h2 style='color:#4fc3f7;margin:0'>Quantitative Discovery Scan</h2>");
        html.append(String.format("<p style='margin:5px 0'>Scanned: <b>%d stocks</b> | Passed filters: <b>%d</b> | Scan time: <b>%.1fs</b></p>",
                report.getTotalScanned(), report.getTotalPassed(), report.getElapsedMs() / 1000.0));
        html.append("<p style='margin:5px 0;color:#aaa;font-size:12px'>Scoring: Earnings (25) + Insider (20) + Valuation (20) + Momentum (20) + Volume (15) = 100</p>");
        html.append("</div>");

        // Top opportunities section
        List<DiscoveredOpportunity> opps = report.getOpportunities();

        // Top 20 Table
        html.append("<h3 style='color:#4fc3f7;border-bottom:2px solid #4fc3f7;padding-bottom:5px'>Top Opportunities (Ranked by Score)</h3>");
        html.append("<table style='width:100%;border-collapse:collapse;font-size:13px'>");
        html.append("<tr style='background:#2a2a4a;color:#fff'>");
        html.append("<th style='padding:8px;text-align:left'>Stock</th>");
        html.append("<th style='padding:8px'>Score</th>");
        html.append("<th style='padding:8px'>Price</th>");
        html.append("<th style='padding:8px'>PE</th>");
        html.append("<th style='padding:8px'>YoY Rev</th>");
        html.append("<th style='padding:8px'>Margin</th>");
        html.append("<th style='padding:8px'>Insider</th>");
        html.append("<th style='padding:8px'>Entry Signal</th>");
        html.append("</tr>");

        int rank = 0;
        for (DiscoveredOpportunity opp : opps) {
            rank++;
            if (rank > 20) break;

            String bgColor = rank % 2 == 0 ? "#1e1e3a" : "#16162e";
            String scoreColor = opp.getDiscoveryScore() >= 70 ? "#4caf50" :
                    opp.getDiscoveryScore() >= 50 ? "#ffb74d" : "#ef5350";
            String holdingBadge = opp.isInHoldings() ? " [H]" : "";

            html.append(String.format("<tr style='background:%s;color:#e0e0e0'>", bgColor));
            html.append(String.format("<td style='padding:8px;font-weight:bold'>%s%s</td>", cleanSymbol(opp.getSymbol()), holdingBadge));
            html.append(String.format("<td style='padding:8px;text-align:center;color:%s;font-weight:bold'>%d</td>", scoreColor, opp.getDiscoveryScore()));
            html.append(String.format("<td style='padding:8px;text-align:right'>%.2f</td>", opp.getCurrentPrice()));
            html.append(String.format("<td style='padding:8px;text-align:center'>%s</td>", fmtDouble(opp.getStockPE())));
            html.append(String.format("<td style='padding:8px;text-align:center;color:%s'>%s</td>",
                    opp.getYoyRevenueGrowth() != null && opp.getYoyRevenueGrowth() > 0 ? "#4caf50" : "#ef5350",
                    fmtPct(opp.getYoyRevenueGrowth())));
            html.append(String.format("<td style='padding:8px;text-align:center'>%s</td>", fmtPct(opp.getNetMargin())));
            html.append(String.format("<td style='padding:8px;text-align:center'>%s</td>",
                    formatInsiderSignal(opp.getInsiderSignal())));
            html.append(String.format("<td style='padding:8px;font-size:11px'>%s</td>",
                    opp.getLevels() != null ? opp.getLevels().getEntrySignal() : "N/A"));
            html.append("</tr>");
        }
        html.append("</table>");

        // Detailed cards for top 10
        html.append("<h3 style='color:#4fc3f7;border-bottom:2px solid #4fc3f7;padding-bottom:5px;margin-top:30px'>Detailed Analysis - Top 10</h3>");

        rank = 0;
        for (DiscoveredOpportunity opp : opps) {
            rank++;
            if (rank > 10) break;
            html.append(buildDetailCard(opp, rank));
        }

        // Methodology
        html.append("<div style='background:#1a1a2e;color:#aaa;padding:15px;border-radius:8px;margin-top:20px;font-size:12px'>");
        html.append("<b>Methodology:</b> Pure quantitative screening - no AI opinions. ");
        html.append("Earnings data from NSE quarterly results. Shareholding from NSE corporate filings. ");
        html.append("Valuation from NSE API. Price data from Kite API (6-month daily candles). ");
        html.append("Entry/exit levels calculated from swing highs/lows, EMAs, RSI, and ATR.");
        html.append("</div>");

        return html.toString();
    }

    private String buildDetailCard(DiscoveredOpportunity opp, int rank) {
        StringBuilder card = new StringBuilder();
        EntryExitLevels levels = opp.getLevels();

        String scoreColor = opp.getDiscoveryScore() >= 70 ? "#4caf50" :
                opp.getDiscoveryScore() >= 50 ? "#ffb74d" : "#ef5350";

        card.append("<div style='background:#1e1e3a;border:1px solid #333;border-left:4px solid ")
            .append(scoreColor).append(";border-radius:8px;padding:15px;margin:10px 0'>");

        // Header
        card.append(String.format("<div style='display:flex;justify-content:space-between;align-items:center'>"
                + "<h4 style='color:#4fc3f7;margin:0'>#%d %s</h4>"
                + "<span style='background:%s;color:#fff;padding:4px 12px;border-radius:12px;font-weight:bold'>Score: %d/100</span>"
                + "</div>",
                rank, cleanSymbol(opp.getSymbol()), scoreColor, opp.getDiscoveryScore()));

        // Quick stats row
        card.append("<div style='display:flex;gap:15px;margin:10px 0;font-size:13px;color:#ccc'>");
        card.append(String.format("<span>Price: <b>%.2f</b></span>", opp.getCurrentPrice()));
        if (opp.getIndustry() != null) card.append(String.format("<span>Sector: <b>%s</b></span>", opp.getIndustry()));
        if (opp.getMarketCapCr() != null) card.append(String.format("<span>MCap: <b>%.0f Cr</b></span>", opp.getMarketCapCr()));
        if (opp.getStockPE() != null) card.append(String.format("<span>PE: <b>%.1f</b></span>", opp.getStockPE()));
        if (opp.isInHoldings()) card.append("<span style='color:#4fc3f7'>[In Holdings]</span>");
        card.append("</div>");

        // Score breakdown
        card.append("<div style='margin:10px 0'>");
        card.append("<table style='width:100%;font-size:12px;color:#ccc'><tr>");
        card.append(String.format("<td>Earnings: <b>%d</b>/25</td>", opp.getEarningsScore()));
        card.append(String.format("<td>Insider: <b>%d</b>/20</td>", opp.getInsiderScore()));
        card.append(String.format("<td>Valuation: <b>%d</b>/20</td>", opp.getValuationScore()));
        card.append(String.format("<td>Momentum: <b>%d</b>/20</td>", opp.getMomentumScore()));
        card.append(String.format("<td>Volume: <b>%d</b>/15</td>", opp.getVolumeScore()));
        card.append("</tr></table>");
        card.append("</div>");

        // Triggers (green)
        if (opp.getTriggers() != null && !opp.getTriggers().isEmpty()) {
            card.append("<div style='margin:8px 0'>");
            for (String trigger : opp.getTriggers()) {
                card.append(String.format("<span style='color:#4caf50;font-size:12px'>+ %s</span><br>", trigger));
            }
            card.append("</div>");
        }

        // Risks (red)
        if (opp.getRisks() != null && !opp.getRisks().isEmpty()) {
            card.append("<div style='margin:8px 0'>");
            for (String risk : opp.getRisks()) {
                card.append(String.format("<span style='color:#ef5350;font-size:12px'>! %s</span><br>", risk));
            }
            card.append("</div>");
        }

        // Entry/Exit Levels Table
        if (levels != null) {
            card.append("<div style='margin-top:10px'>");
            card.append("<table style='width:100%;border-collapse:collapse;font-size:12px'>");
            card.append("<tr style='background:#2a2a4a;color:#aaa'>");
            card.append("<th style='padding:5px' colspan='4'>ENTRY / EXIT LEVELS</th>");
            card.append("</tr>");

            // Entry row
            card.append("<tr style='color:#ccc'>");
            card.append(String.format("<td style='padding:4px'>Entry Zone: <b style='color:#4caf50'>%.2f - %.2f</b></td>",
                    levels.getIdealEntryZoneLow() != null ? levels.getIdealEntryZoneLow() : 0,
                    levels.getIdealEntryZoneHigh() != null ? levels.getIdealEntryZoneHigh() : 0));
            card.append(String.format("<td style='padding:4px'>Stop Loss: <b style='color:#ef5350'>%.2f</b></td>",
                    levels.getStopLoss() != null ? levels.getStopLoss() : 0));
            card.append(String.format("<td style='padding:4px'>R:R: <b>1:%.1f</b></td>",
                    levels.getRiskRewardRatio() != null ? levels.getRiskRewardRatio() : 0));
            card.append(String.format("<td style='padding:4px'>RSI: <b>%.1f</b></td>",
                    levels.getRsi14() != null ? levels.getRsi14() : 0));
            card.append("</tr>");

            // Targets row
            card.append("<tr style='color:#ccc'>");
            card.append(String.format("<td style='padding:4px'>Target 1 (1.5R): <b>%.2f</b></td>",
                    levels.getTarget1() != null ? levels.getTarget1() : 0));
            card.append(String.format("<td style='padding:4px'>Target 2 (2.5R): <b>%.2f</b></td>",
                    levels.getTarget2() != null ? levels.getTarget2() : 0));
            card.append(String.format("<td style='padding:4px'>Target 3 (4R): <b>%.2f</b></td>",
                    levels.getTarget3() != null ? levels.getTarget3() : 0));
            card.append(String.format("<td style='padding:4px'>ATR: <b>%.2f</b></td>",
                    levels.getAtr14() != null ? levels.getAtr14() : 0));
            card.append("</tr>");

            // Support/Resistance row
            card.append("<tr style='color:#ccc'>");
            card.append(String.format("<td style='padding:4px'>Support 1: <b>%s</b></td>", fmtPrice(levels.getSupport1())));
            card.append(String.format("<td style='padding:4px'>Support 2: <b>%s</b></td>", fmtPrice(levels.getSupport2())));
            card.append(String.format("<td style='padding:4px'>Resist 1: <b>%s</b></td>", fmtPrice(levels.getResistance1())));
            card.append(String.format("<td style='padding:4px'>Resist 2: <b>%s</b></td>", fmtPrice(levels.getResistance2())));
            card.append("</tr>");

            // EMA row
            card.append("<tr style='color:#ccc'>");
            card.append(String.format("<td style='padding:4px'>EMA20: <b>%s</b></td>", fmtPrice(levels.getEma20())));
            card.append(String.format("<td style='padding:4px'>EMA50: <b>%s</b></td>", fmtPrice(levels.getEma50())));
            card.append(String.format("<td style='padding:4px'>EMA200: <b>%s</b></td>", fmtPrice(levels.getEma200())));
            card.append(String.format("<td style='padding:4px'>52W: %s - %s</td>",
                    fmtPrice(levels.getLow52Week()), fmtPrice(levels.getHigh52Week())));
            card.append("</tr>");

            card.append("</table>");

            // Signal assessment
            card.append("<div style='margin-top:8px;padding:6px;background:#2a2a4a;border-radius:4px;font-size:12px'>");
            card.append(String.format("<span style='color:#4caf50'>ENTRY: %s</span> | ", levels.getEntrySignal()));
            card.append(String.format("<span style='color:#ffb74d'>EXIT: %s</span>", levels.getExitSignal()));
            card.append("</div>");

            card.append("</div>");
        }

        card.append("</div>");
        return card.toString();
    }

    // Helpers
    private String cleanSymbol(String symbol) {
        return symbol != null && symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
    }

    private String fmtDouble(Double val) {
        return val != null ? String.format("%.1f", val) : "-";
    }

    private String fmtPct(Double val) {
        if (val == null) return "-";
        return String.format("%+.1f%%", val);
    }

    private String fmtPrice(Double val) {
        return val != null ? String.format("%.2f", val) : "-";
    }

    private String formatInsiderSignal(String signal) {
        if (signal == null) return "-";
        switch (signal) {
            case "STRONG_BUY": return "<span style='color:#4caf50'>Strong Buy</span>";
            case "BUY": return "<span style='color:#81c784'>Buy</span>";
            case "NEUTRAL": return "<span style='color:#aaa'>Neutral</span>";
            case "SELL": return "<span style='color:#ef9a9a'>Sell</span>";
            case "STRONG_SELL": return "<span style='color:#ef5350'>Strong Sell</span>";
            default: return signal;
        }
    }
}
