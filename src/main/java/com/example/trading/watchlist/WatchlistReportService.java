package com.example.trading.watchlist;

import com.example.trading.ai.AiService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Service to send watchlist analysis reports via email.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WatchlistReportService {

    private final WatchlistRepository watchlistRepository;
    private final JavaMailSender mailSender;
    private final AiService aiService;

    @Value("${spring.mail.username}")
    private String senderEmail;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("hh:mm a");

    /**
     * Send watchlist analysis report via email.
     */
    public void sendWatchlistReport() {
        try {
            if (senderEmail == null || senderEmail.contains("placeholder") || senderEmail.equals("your-email@gmail.com")) {
                log.warn("Watchlist report skipped: Email not configured");
                return;
            }

            // Active rows only — a symbol the investor removed must not keep appearing in the
            // email (same defect class as Gotcha 16 for holdings).
            List<WatchlistEntity> watchlist = watchlistRepository.findActiveOrderByScoreDesc();
            if (watchlist.isEmpty()) {
                log.info("No watchlist stocks to report");
                return;
            }

            String htmlContent = buildWatchlistReportHtml(watchlist);

            // Count signals for subject
            long buySignals = watchlist.stream()
                    .filter(w -> "STRONG_BUY".equals(w.getEntrySignal()) || "BUY".equals(w.getEntrySignal()))
                    .count();

            String subject = String.format("Watchlist Report - %s %s | %d Buy Signals",
                    LocalDate.now().format(DATE_FORMATTER),
                    LocalTime.now().format(TIME_FORMATTER),
                    buySignals);

            sendHtmlEmail(subject, htmlContent);
            log.info("Watchlist report sent successfully with {} stocks, {} buy signals", watchlist.size(), buySignals);

        } catch (Exception e) {
            log.error("Failed to send watchlist report: {}", e.getMessage(), e);
        }
    }

    private void sendHtmlEmail(String subject, String htmlContent) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(senderEmail);
        helper.setTo(senderEmail);
        helper.setSubject(subject);
        helper.setText(htmlContent, true);

        mailSender.send(message);
    }

    private String buildWatchlistReportHtml(List<WatchlistEntity> watchlist) {
        StringBuilder html = new StringBuilder();

        // CSS Styles
        html.append("""
            <!DOCTYPE html>
            <html>
            <head>
            <style>
                body { font-family: 'Segoe UI', Arial, sans-serif; background: #f5f5f5; margin: 0; padding: 20px; }
                .container { max-width: 900px; margin: 0 auto; background: white; border-radius: 8px; padding: 20px; }
                .header { background: linear-gradient(135deg, #1a237e 0%, #3949ab 100%); color: white; padding: 15px 20px; border-radius: 8px 8px 0 0; margin: -20px -20px 20px -20px; }
                .header h1 { margin: 0; font-size: 22px; }
                .header .subtitle { opacity: 0.9; font-size: 14px; margin-top: 5px; }
                
                .summary-cards { display: flex; gap: 15px; margin-bottom: 20px; flex-wrap: wrap; }
                .card { flex: 1; min-width: 120px; padding: 15px; border-radius: 8px; text-align: center; }
                .card.total { background: #e3f2fd; border: 1px solid #90caf9; }
                .card.buy { background: #e8f5e9; border: 1px solid #81c784; }
                .card.strong-buy { background: #c8e6c9; border: 1px solid #4caf50; }
                .card.hold { background: #fff3e0; border: 1px solid #ffb74d; }
                .card.avoid { background: #ffebee; border: 1px solid #ef9a9a; }

                .valuation-undervalued { color: #4caf50; font-weight: bold; }
                .valuation-fair { color: #2196f3; font-weight: bold; }
                .valuation-overvalued { color: #f44336; font-weight: bold; }
                .valuation-badge { display: inline-block; padding: 2px 6px; border-radius: 3px; font-size: 10px; font-weight: bold; color: white; }
                .fund-score-high { background: #4caf50; }
                .fund-score-medium { background: #ff9800; }
                .fund-score-low { background: #f44336; }
                .card .value { font-size: 28px; font-weight: bold; }
                .card .label { font-size: 12px; color: #666; margin-top: 5px; }
                
                .section { margin-bottom: 25px; }
                .section-title { font-size: 16px; font-weight: bold; color: #333; padding-bottom: 8px; border-bottom: 2px solid #1a237e; margin-bottom: 15px; }
                
                table { width: 100%; border-collapse: collapse; font-size: 13px; }
                th { background: #f5f5f5; padding: 10px; text-align: left; font-weight: 600; color: #333; border-bottom: 2px solid #ddd; }
                td { padding: 10px; border-bottom: 1px solid #eee; }
                tr:hover { background: #f9f9f9; }
                
                .signal-badge { display: inline-block; padding: 4px 8px; border-radius: 4px; font-weight: bold; font-size: 11px; }
                .signal-strong-buy { background: #4caf50; color: white; }
                .signal-buy { background: #8bc34a; color: white; }
                .signal-hold { background: #ffc107; color: #333; }
                .signal-avoid { background: #f44336; color: white; }
                
                .trend-bullish { color: #4caf50; font-weight: bold; }
                .trend-bearish { color: #f44336; font-weight: bold; }
                .trend-sideways { color: #ff9800; font-weight: bold; }
                
                .score { font-weight: bold; }
                .score-high { color: #4caf50; }
                .score-medium { color: #ff9800; }
                .score-low { color: #f44336; }
                
                .positive { color: #4caf50; }
                .negative { color: #f44336; }
                
                .levels-table td { font-size: 12px; }
                .price-level { font-family: monospace; }
                
                .footer { margin-top: 20px; padding-top: 15px; border-top: 1px solid #eee; font-size: 12px; color: #666; text-align: center; }
            </style>
            </head>
            <body>
            <div class="container">
            """);

        // Header
        html.append("<div class=\"header\">");
        html.append("<h1>📊 Watchlist Analysis Report</h1>");
        html.append("<div class=\"subtitle\">")
                .append(LocalDate.now().format(DATE_FORMATTER))
                .append(" at ")
                .append(LocalTime.now().format(TIME_FORMATTER))
                .append("</div>");
        html.append("</div>");

        // Summary Cards
        buildSummaryCards(html, watchlist);

        // Action Required Section
        buildActionSection(html, watchlist);

        // All Watchlist Stocks Table
        buildWatchlistTable(html, watchlist);

        // Fundamental Health Section
        buildFundamentalSection(html, watchlist);

        // Entry Levels for Buy Signals
        buildEntryLevelsTable(html, watchlist);

        // AI Watchlist Analysis
        StringBuilder ctx = new StringBuilder("Watchlist analysis for Indian stocks:\n");
        for (WatchlistEntity w : watchlist) {
            ctx.append(String.format("%s: Signal=%s, Score=%s, Trend=%s, Price=%.2f, Change=%.1f%%\n",
                    w.getSymbol(), w.getEntrySignal(), w.getOverallScore(),
                    w.getTrendDirection(), w.getCurrentPrice(), w.getDayChangePercent()));
        }
        ctx.append("\nWhich stocks have the best entry setups today? Any traps to avoid?");
        html.append(aiService.buildAiHtmlSection("AI Watchlist Insights",
                "You are an Indian stock market swing trader reviewing a watchlist. "
                + "Identify top 3 actionable setups and any to avoid in 3-5 bullet points. Use plain text, no markdown.",
                ctx.toString()));

        // Footer
        html.append("<div class=\"footer\">");
        html.append("Generated by Intraday Trading System | Watchlist Analysis Module");
        html.append("</div>");

        html.append("</div></body></html>");
        return html.toString();
    }

    private void buildSummaryCards(StringBuilder html, List<WatchlistEntity> watchlist) {
        long total = watchlist.size();
        long strongBuy = watchlist.stream().filter(w -> "STRONG_BUY".equals(w.getEntrySignal())).count();
        long buy = watchlist.stream().filter(w -> "BUY".equals(w.getEntrySignal())).count();
        long hold = watchlist.stream().filter(w -> "HOLD".equals(w.getEntrySignal())).count();
        long avoid = watchlist.stream().filter(w -> "AVOID".equals(w.getEntrySignal())).count();

        html.append("<div class=\"summary-cards\">");

        html.append("<div class=\"card total\"><div class=\"value\">").append(total).append("</div><div class=\"label\">Total Stocks</div></div>");
        html.append("<div class=\"card strong-buy\"><div class=\"value\">").append(strongBuy).append("</div><div class=\"label\">Strong Buy</div></div>");
        html.append("<div class=\"card buy\"><div class=\"value\">").append(buy).append("</div><div class=\"label\">Buy</div></div>");
        html.append("<div class=\"card hold\"><div class=\"value\">").append(hold).append("</div><div class=\"label\">Hold</div></div>");
        html.append("<div class=\"card avoid\"><div class=\"value\">").append(avoid).append("</div><div class=\"label\">Avoid</div></div>");

        html.append("</div>");
    }

    private void buildActionSection(StringBuilder html, List<WatchlistEntity> watchlist) {
        List<WatchlistEntity> actionable = watchlist.stream()
                .filter(w -> "STRONG_BUY".equals(w.getEntrySignal()) || "BUY".equals(w.getEntrySignal()))
                .toList();

        if (actionable.isEmpty()) {
            html.append("<div class=\"section\">");
            html.append("<div class=\"section-title\">⚡ Action Required</div>");
            html.append("<p style=\"color: #666;\">No actionable buy signals at this time. All stocks are either on HOLD or AVOID.</p>");
            html.append("</div>");
            return;
        }

        html.append("<div class=\"section\">");
        html.append("<div class=\"section-title\">⚡ Action Required - Buy Signals</div>");
        html.append("<table>");
        html.append("<tr><th>Symbol</th><th>Signal</th><th>Price</th><th>Score</th><th>RSI</th><th>Reason</th></tr>");

        for (WatchlistEntity stock : actionable) {
            html.append("<tr>");
            html.append("<td><strong>").append(stock.getTradingSymbol()).append("</strong></td>");
            html.append("<td>").append(getSignalBadge(stock.getEntrySignal())).append("</td>");
            html.append("<td>₹").append(format(stock.getCurrentPrice())).append("</td>");
            html.append("<td>").append(getScoreHtml(stock.getOverallScore())).append("</td>");
            html.append("<td>").append(format(stock.getRsi14())).append("</td>");
            html.append("<td style=\"font-size:11px;\">").append(stock.getSignalReason() != null ? stock.getSignalReason() : "-").append("</td>");
            html.append("</tr>");
        }

        html.append("</table></div>");
    }

    private void buildWatchlistTable(StringBuilder html, List<WatchlistEntity> watchlist) {
        html.append("<div class=\"section\">");
        html.append("<div class=\"section-title\">📋 Complete Watchlist</div>");
        html.append("<p style=\"font-size:12px;color:#555;background:#f7f7f7;padding:8px;border-left:3px solid #999;\">")
            .append("<b>What this means:</b> \"Added\" is the day you put the stock on your list and \"Since added\" is how far ")
            .append("the price has moved since then. A dash means the price was not recorded when it was added ")
            .append("(the original list came from the config file), so no return can be shown — it is not zero. ")
            .append("\"Score\" here is the <i>timing</i> score (trend, RSI, momentum); the business-quality score ")
            .append("lives on the Watchlist page of the dashboard next to a \"still a good time to buy?\" verdict.")
            .append("</p>");
        html.append("<table>");
        html.append("<tr><th>Symbol</th><th>Added</th><th>Since added</th><th>Price</th><th>Day %</th><th>Trend</th><th>RSI</th><th>Tech</th><th>Fund</th><th>Score</th><th>Signal</th></tr>");

        for (WatchlistEntity stock : watchlist) {
            Double sinceAdded = WatchlistReturnMath.pctReturn(stock.getPriceAtAdd(), stock.getCurrentPrice());
            html.append("<tr>");
            html.append("<td><strong>").append(stock.getTradingSymbol()).append("</strong></td>");
            html.append("<td>").append(stock.getAddedOn() != null ? stock.getAddedOn().toString() : "-").append("</td>");
            html.append("<td>").append(getChangeHtml(sinceAdded)).append("</td>");
            html.append("<td>₹").append(format(stock.getCurrentPrice())).append("</td>");
            html.append("<td>").append(getChangeHtml(stock.getDayChangePercent())).append("</td>");
            html.append("<td>").append(getTrendHtml(stock.getTrendDirection())).append("</td>");
            html.append("<td>").append(format(stock.getRsi14())).append("</td>");
            html.append("<td>").append(getScoreHtml(stock.getTechnicalScore())).append("</td>");
            html.append("<td>").append(getFundScoreHtml(stock.getFundamentalScore())).append("</td>");
            html.append("<td>").append(getScoreHtml(stock.getOverallScore())).append("</td>");
            html.append("<td>").append(getSignalBadge(stock.getEntrySignal())).append("</td>");
            html.append("</tr>");
        }

        html.append("</table></div>");
    }

    private void buildEntryLevelsTable(StringBuilder html, List<WatchlistEntity> watchlist) {
        List<WatchlistEntity> buySignals = watchlist.stream()
                .filter(w -> "STRONG_BUY".equals(w.getEntrySignal()) || "BUY".equals(w.getEntrySignal()))
                .filter(w -> w.getSuggestedEntry() != null)
                .toList();

        if (buySignals.isEmpty()) {
            return;
        }

        html.append("<div class=\"section\">");
        html.append("<div class=\"section-title\">🎯 Entry Levels (Buy Signals Only)</div>");
        html.append("<table class=\"levels-table\">");
        html.append("<tr><th>Symbol</th><th>Entry</th><th>Stop Loss</th><th>Target 1</th><th>Target 2</th><th>R:R</th><th>Support</th><th>Resistance</th></tr>");

        for (WatchlistEntity stock : buySignals) {
            html.append("<tr>");
            html.append("<td><strong>").append(stock.getTradingSymbol()).append("</strong></td>");
            html.append("<td class=\"price-level\">₹").append(format(stock.getSuggestedEntry())).append("</td>");
            html.append("<td class=\"price-level negative\">₹").append(format(stock.getSuggestedStopLoss())).append("</td>");
            html.append("<td class=\"price-level positive\">₹").append(format(stock.getSuggestedTarget1())).append("</td>");
            html.append("<td class=\"price-level positive\">₹").append(format(stock.getSuggestedTarget2())).append("</td>");
            html.append("<td>").append(stock.getRiskRewardRatio() != null ? String.format("%.1f:1", stock.getRiskRewardRatio()) : "-").append("</td>");
            html.append("<td class=\"price-level\">").append(stock.getNearestSupport() != null ? "₹" + format(stock.getNearestSupport()) : "-").append("</td>");
            html.append("<td class=\"price-level\">").append(stock.getNearestResistance() != null ? "₹" + format(stock.getNearestResistance()) : "-").append("</td>");
            html.append("</tr>");
        }

        html.append("</table></div>");
    }

    private String getSignalBadge(String signal) {
        if (signal == null) return "-";
        return switch (signal) {
            case "STRONG_BUY" -> "<span class=\"signal-badge signal-strong-buy\">STRONG BUY</span>";
            case "BUY" -> "<span class=\"signal-badge signal-buy\">BUY</span>";
            case "HOLD" -> "<span class=\"signal-badge signal-hold\">HOLD</span>";
            case "AVOID" -> "<span class=\"signal-badge signal-avoid\">AVOID</span>";
            default -> signal;
        };
    }

    private String getTrendHtml(String trend) {
        if (trend == null) return "-";
        return switch (trend) {
            case "BULLISH" -> "<span class=\"trend-bullish\">▲ BULLISH</span>";
            case "BEARISH" -> "<span class=\"trend-bearish\">▼ BEARISH</span>";
            case "SIDEWAYS" -> "<span class=\"trend-sideways\">◆ SIDEWAYS</span>";
            default -> trend;
        };
    }

    private String getScoreHtml(Integer score) {
        if (score == null) return "-";
        String cssClass = score >= 70 ? "score-high" : (score >= 50 ? "score-medium" : "score-low");
        return String.format("<span class=\"score %s\">%d</span>", cssClass, score);
    }

    private String getChangeHtml(Double change) {
        if (change == null) return "-";
        String cssClass = change >= 0 ? "positive" : "negative";
        String prefix = change >= 0 ? "+" : "";
        return String.format("<span class=\"%s\">%s%.2f%%</span>", cssClass, prefix, change);
    }

    private void buildFundamentalSection(StringBuilder html, List<WatchlistEntity> watchlist) {
        // Only show if any stock has fundamental data
        boolean hasFundamentals = watchlist.stream().anyMatch(w -> w.getStockPe() != null);
        if (!hasFundamentals) return;

        html.append("<div class=\"section\">");
        html.append("<div class=\"section-title\">📊 Fundamental Health</div>");
        html.append("<table>");
        html.append("<tr><th>Symbol</th><th>Industry</th><th>PE</th><th>Sector PE</th><th>Valuation</th><th>Mkt Cap (Cr)</th><th>P/B</th><th>EPS</th><th>Div %</th><th>Fund Score</th></tr>");

        for (WatchlistEntity stock : watchlist) {
            html.append("<tr>");
            html.append("<td><strong>").append(stock.getTradingSymbol()).append("</strong></td>");
            html.append("<td style=\"font-size:11px;\">").append(stock.getIndustry() != null ? stock.getIndustry() : "-").append("</td>");
            html.append("<td>").append(stock.getStockPe() != null ? String.format("%.1f", stock.getStockPe()) : "-").append("</td>");
            html.append("<td>").append(stock.getIndustryPe() != null ? String.format("%.1f", stock.getIndustryPe()) : "-").append("</td>");
            html.append("<td>").append(getValuationHtml(stock.getValuationRating(), stock.getPeDeviation())).append("</td>");
            html.append("<td>").append(stock.getMarketCap() != null ? formatMarketCap(stock.getMarketCap()) : "-").append("</td>");
            html.append("<td>").append(stock.getPriceToBook() != null ? String.format("%.1f", stock.getPriceToBook()) : "-").append("</td>");
            html.append("<td>").append(stock.getEps() != null ? String.format("%.1f", stock.getEps()) : "-").append("</td>");
            html.append("<td>").append(stock.getDividendYield() != null ? String.format("%.1f%%", stock.getDividendYield()) : "-").append("</td>");
            html.append("<td>").append(getFundScoreHtml(stock.getFundamentalScore())).append("</td>");
            html.append("</tr>");
        }

        html.append("</table>");

        // Score legend
        html.append("<div style=\"margin-top:10px; font-size:11px; color:#666;\">");
        html.append("<strong>Score Breakdown:</strong> Overall = 40% Technical + 30% Fundamental + 30% Momentum | ");
        html.append("<strong>Valuation:</strong> PE vs Sector average PE");
        html.append("</div>");

        html.append("</div>");
    }

    private String getValuationHtml(String rating, Double peDeviation) {
        if (rating == null) return "-";
        String cssClass;
        if (rating.contains("Undervalued")) {
            cssClass = "valuation-undervalued";
        } else if (rating.contains("Fairly")) {
            cssClass = "valuation-fair";
        } else if (rating.contains("Overvalued")) {
            cssClass = "valuation-overvalued";
        } else {
            cssClass = "";
        }
        String devStr = peDeviation != null ? String.format(" (%+.0f%%)", peDeviation) : "";
        return String.format("<span class=\"%s\">%s%s</span>", cssClass, rating, devStr);
    }

    private String getFundScoreHtml(Integer score) {
        if (score == null) return "-";
        String cssClass = score >= 65 ? "fund-score-high" : (score >= 45 ? "fund-score-medium" : "fund-score-low");
        return String.format("<span class=\"valuation-badge %s\">%d</span>", cssClass, score);
    }

    private String formatMarketCap(Double marketCapCr) {
        if (marketCapCr == null) return "-";
        if (marketCapCr >= 100000) {
            return String.format("%.1fL Cr", marketCapCr / 100000);
        } else if (marketCapCr >= 1000) {
            return String.format("%,.0f", marketCapCr);
        }
        return String.format("%.0f", marketCapCr);
    }

    private String format(Double value) {
        if (value == null) return "-";
        return String.format("%.2f", value);
    }
}
