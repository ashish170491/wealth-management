package com.example.trading.notification;

import com.example.trading.ai.AiService;
import com.example.trading.fiidii.FiiDiiDTO.DailyActivity;
import com.example.trading.fiidii.FiiDiiDataService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends a consolidated pre-market morning briefing email at 9:30 AM IST on weekdays.
 * Aggregates FII/DII sentiment, holdings near key levels,
 * and the multibagger radar into a single email.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MorningBriefingService {

    private final FiiDiiDataService fiiDiiDataService;
    private final HoldingsRepository holdingsRepository;
    private final EmailNotificationService emailNotificationService;
    private final EmailTemplateService templateService;
    private final com.example.trading.multibagger.MultibaggerReportService multibaggerReportService;
    private final AiService aiService;
    private final MarketHoursService marketHoursService;
    /** SPEC 48.10. Both DB-only; the briefing already fetches from NSE, this adds nothing to that. */
    private final com.example.trading.macro.MacroExposureService macroExposureService;
    private final com.example.trading.macro.MacroReportRenderer macroReportRenderer;

    private static final DateTimeFormatter DATE_DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy");

    @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledMorningBriefing() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4.
        log.info("Morning Briefing: Scheduled trigger at 9:30 AM IST");
        sendMorningBriefing();
    }

    /**
     * Build and send the morning briefing email. Can be called manually from a controller.
     */
    public void sendMorningBriefing() {
        log.info("Morning Briefing: Building consolidated pre-market email...");

        String html = buildBriefingHtml();
        String today = LocalDate.now().format(DATE_DISPLAY);
        String subject = "Morning Briefing - " + today + " | Pre-Market Analysis";

        try {
            emailNotificationService.sendHtmlEmail(subject, html);
            log.info("Morning Briefing: Email sent successfully for {}", today);
        } catch (Exception e) {
            log.error("Morning Briefing: Failed to send email: {}", e.getMessage(), e);
        }
    }

    /**
     * Builds the briefing body without sending it, so the dashboard can display the very
     * same HTML the email contains (SPEC section 27.6).
     *
     * <p>Extracted from {@link #sendMorningBriefing()} by pure extract-method - each
     * {@code buildXxxSection()} still fetches its own data internally, which is why this
     * is served as HTML rather than JSON. It is not cheap: several sections call NSE and
     * the AI provider, so callers must cache and only invoke it on an explicit request.
     */
    public String buildBriefingHtml() {
        StringBuilder content = new StringBuilder();

        // 1. Market Pulse - FII/DII
        content.append(buildFiiDiiSection());

        // 2. Portfolio Quick Summary
        content.append(buildPortfolioSummarySection());

        // 3. Holdings Near Key Levels
        content.append(buildHoldingsNearLevelsSection());

        // 4. Multibagger Radar - Top candidates
        content.append(buildMultibaggerSection());

        // 5. AI Market Summary (if AI service is available)
        // Before the AI summary on purpose: that method is handed everything above it, so a new
        // section placed here is automatically visible to the summary.
        content.append(buildMacroEventsSection());

        content.append(buildAiSummarySection(content.toString()));

        return templateService.buildEmailTemplate(
                "Morning Briefing",
                "Pre-Market Analysis & Trading Plan",
                content.toString()
        );
    }

    // ============================================================
    // Section 1: FII/DII Market Pulse
    // ============================================================

    private String buildFiiDiiSection() {
        try {
            LocalDate yesterday = getPreviousTradingDay(LocalDate.now());
            DailyActivity activity = fiiDiiDataService.fetchDailyActivity(yesterday);

            if (activity == null) {
                return templateService.section("&#128200;", "Market Pulse - FII/DII Activity",
                        templateService.alert("warning", "&#9888;", "Data Unavailable",
                                "Could not fetch FII/DII data for " + yesterday.format(DATE_DISPLAY)));
            }

            String sentiment = activity.getOverallSentiment();
            String sentimentBadge = getSentimentBadge(sentiment);

            StringBuilder body = new StringBuilder();

            // Summary cards
            body.append("<div class=\"summary-grid\">");
            body.append(templateService.summaryCard("FII Net",
                    String.format("%.0f Cr", activity.getFiiNetValue()),
                    activity.getFiiNetValue() >= 0 ? "profit" : "loss"));
            body.append(templateService.summaryCard("DII Net",
                    String.format("%.0f Cr", activity.getDiiNetValue()),
                    activity.getDiiNetValue() >= 0 ? "profit" : "loss"));
            body.append(templateService.summaryCard("Total Institutional",
                    String.format("%.0f Cr", activity.getTotalInstitutionalNet()),
                    activity.getTotalInstitutionalNet() >= 0 ? "profit" : "loss"));
            body.append(templateService.summaryCard("Sentiment", sentimentBadge, ""));
            body.append("</div>");

            // Detail table
            body.append(templateService.tableStart("Category", "Buy (Cr)", "Sell (Cr)", "Net (Cr)"));
            body.append(templateService.tableRow(
                    "<strong>FII/FPI</strong>",
                    String.format("%.2f", activity.getFiiBuyValue()),
                    String.format("%.2f", activity.getFiiSellValue()),
                    templateService.formatPnL(activity.getFiiNetValue(), true)
            ));
            body.append(templateService.tableRow(
                    "<strong>DII</strong>",
                    String.format("%.2f", activity.getDiiBuyValue()),
                    String.format("%.2f", activity.getDiiSellValue()),
                    templateService.formatPnL(activity.getDiiNetValue(), true)
            ));
            body.append(templateService.tableEnd());

            // Sentiment alert
            String alertType = sentimentAlertType(sentiment);
            String alertIcon = sentimentAlertIcon(sentiment);
            body.append(templateService.alert(alertType, alertIcon, "Institutional Sentiment: " + formatSentiment(sentiment),
                    buildSentimentMessage(activity)));

            return templateService.section("&#128200;", "Market Pulse - FII/DII Activity (" + yesterday.format(DATE_DISPLAY) + ")", body.toString());
        } catch (Exception e) {
            log.warn("Morning Briefing: Failed to build FII/DII section: {}", e.getMessage());
            return templateService.section("&#128200;", "Market Pulse - FII/DII Activity",
                    templateService.alert("warning", "&#9888;", "Data Unavailable",
                            "Could not fetch FII/DII data: " + e.getMessage()));
        }
    }

    // ============================================================
    // Section 2: Portfolio Quick Summary
    // ============================================================

    private String buildPortfolioSummarySection() {
        try {
            List<HoldingsEntity> allHoldings = holdingsRepository.findActive();

            if (allHoldings.isEmpty()) {
                return templateService.section("&#128188;", "Portfolio Quick Summary",
                        templateService.alert("info", "&#8505;", "No Holdings",
                                "No holdings found in portfolio."));
            }

            long totalCount = allHoldings.size();
            Double totalPnl = holdingsRepository.getTotalPnL();
            Long profitableCount = holdingsRepository.countProfitableHoldings();
            Long losingCount = holdingsRepository.countLossHoldings();

            double pnl = totalPnl != null ? totalPnl : 0.0;
            long profitable = profitableCount != null ? profitableCount : 0;
            long losing = losingCount != null ? losingCount : 0;

            double avgScore = allHoldings.stream()
                    .filter(h -> h.getOverallScore() != null)
                    .mapToInt(HoldingsEntity::getOverallScore)
                    .average()
                    .orElse(0.0);

            Double totalInvested = holdingsRepository.getTotalInvestedValue();
            Double totalCurrent = holdingsRepository.getTotalCurrentValue();
            double invested = totalInvested != null ? totalInvested : 0.0;
            double current = totalCurrent != null ? totalCurrent : 0.0;
            double overallPnlPercent = invested > 0 ? ((current - invested) / invested) * 100 : 0.0;

            StringBuilder body = new StringBuilder();
            body.append("<div class=\"summary-grid\">");
            body.append(templateService.summaryCard("Holdings", String.valueOf(totalCount), ""));
            body.append(templateService.summaryCard("Total P&L",
                    String.format("Rs.%.0f", pnl),
                    pnl >= 0 ? "profit" : "loss"));
            body.append(templateService.summaryCard("P&L %",
                    String.format("%.2f%%", overallPnlPercent),
                    overallPnlPercent >= 0 ? "profit" : "loss"));
            body.append(templateService.summaryCard("Profitable", String.valueOf(profitable), "profit"));
            body.append(templateService.summaryCard("Losing", String.valueOf(losing), "loss"));
            body.append(templateService.summaryCard("Avg Score",
                    String.format("%.0f/100", avgScore),
                    avgScore >= 60 ? "profit" : (avgScore >= 40 ? "neutral" : "loss")));
            body.append("</div>");

            return templateService.section("&#128188;", "Portfolio Quick Summary", body.toString());
        } catch (Exception e) {
            log.warn("Morning Briefing: Failed to build portfolio summary: {}", e.getMessage());
            return templateService.section("&#128188;", "Portfolio Quick Summary",
                    templateService.alert("warning", "&#9888;", "Unavailable",
                            "Could not load portfolio data: " + e.getMessage()));
        }
    }

    // ============================================================
    // Section 3: Holdings Near Key Levels
    // ============================================================

    private String buildHoldingsNearLevelsSection() {
        try {
            List<HoldingsEntity> allHoldings = holdingsRepository.findActive();
            List<HoldingsEntity> nearLevels = new ArrayList<>();

            for (HoldingsEntity h : allHoldings) {
                if (h.getCurrentPrice() <= 0) continue;

                boolean nearSupport = false;
                boolean nearResistance = false;

                if (h.getSupport1() != null && h.getSupport1() > 0) {
                    double distToSupport = Math.abs(h.getCurrentPrice() - h.getSupport1()) / h.getCurrentPrice() * 100;
                    nearSupport = distToSupport <= 2.0;
                }
                if (h.getResistance1() != null && h.getResistance1() > 0) {
                    double distToResistance = Math.abs(h.getCurrentPrice() - h.getResistance1()) / h.getCurrentPrice() * 100;
                    nearResistance = distToResistance <= 2.0;
                }

                if (nearSupport || nearResistance) {
                    nearLevels.add(h);
                }
            }

            if (nearLevels.isEmpty()) {
                return templateService.section("&#127919;", "Holdings Near Key Levels",
                        templateService.alert("info", "&#8505;", "No Alerts",
                                "No holdings are currently within 2% of their support or resistance levels."));
            }

            StringBuilder body = new StringBuilder();
            body.append(templateService.tableStart("Symbol", "Price", "S1", "R1", "P&L%", "Trend", "Near Level", "Action"));

            for (HoldingsEntity h : nearLevels) {
                double support1 = h.getSupport1() != null ? h.getSupport1() : 0;
                double resistance1 = h.getResistance1() != null ? h.getResistance1() : 0;

                boolean nearSupport = support1 > 0 &&
                        (Math.abs(h.getCurrentPrice() - support1) / h.getCurrentPrice() * 100) <= 2.0;
                boolean nearResistance = resistance1 > 0 &&
                        (Math.abs(h.getCurrentPrice() - resistance1) / h.getCurrentPrice() * 100) <= 2.0;

                String nearLevel;
                String nearLevelBadge;
                String actionGuidance;

                if (nearSupport && nearResistance) {
                    nearLevel = "S1 & R1";
                    nearLevelBadge = templateService.badge("SQUEEZE", "warning");
                    actionGuidance = "Tight range - wait for breakout direction";
                } else if (nearSupport) {
                    nearLevel = "Support (S1)";
                    nearLevelBadge = templateService.badge("NEAR S1", "buy");
                    actionGuidance = h.getCurrentPrice() > support1
                            ? "Approaching support - watch for bounce or break"
                            : "Below support - consider adding SL or averaging";
                } else {
                    nearLevel = "Resistance (R1)";
                    nearLevelBadge = templateService.badge("NEAR R1", "sell");
                    actionGuidance = h.getCurrentPrice() < resistance1
                            ? "Testing resistance - watch for breakout"
                            : "Above resistance - consider booking partial profits";
                }

                String trend = h.getTrendDirection() != null ? h.getTrendDirection() : "N/A";
                String trendBadge = templateService.badge(trend, templateService.getTrendBadgeType(trend));

                String recommendation = h.getRecommendation() != null ? h.getRecommendation() : "HOLD";
                String recBadge = templateService.badge(recommendation, templateService.getRecommendationBadgeType(recommendation));

                body.append(templateService.tableRow(
                        "<strong>" + h.getSymbol() + "</strong>",
                        String.format("%.2f", h.getCurrentPrice()),
                        support1 > 0 ? String.format("%.2f", support1) : "-",
                        resistance1 > 0 ? String.format("%.2f", resistance1) : "-",
                        templateService.formatPercent(h.getPnlPercent()),
                        trendBadge,
                        nearLevelBadge,
                        recBadge
                ));
            }

            body.append(templateService.tableEnd());

            // Action guidance below the table
            body.append("<div style=\"margin-top: 12px;\">");
            for (HoldingsEntity h : nearLevels) {
                double support1 = h.getSupport1() != null ? h.getSupport1() : 0;
                double resistance1 = h.getResistance1() != null ? h.getResistance1() : 0;
                boolean nearSup = support1 > 0 &&
                        (Math.abs(h.getCurrentPrice() - support1) / h.getCurrentPrice() * 100) <= 2.0;
                boolean nearRes = resistance1 > 0 &&
                        (Math.abs(h.getCurrentPrice() - resistance1) / h.getCurrentPrice() * 100) <= 2.0;

                String guidance;
                if (nearSup && nearRes) {
                    guidance = "Tight range between S1 and R1 - wait for breakout direction before acting";
                } else if (nearSup) {
                    guidance = h.getCurrentPrice() > support1
                            ? "Approaching support at " + String.format("%.2f", support1) + " - watch for bounce or break below"
                            : "Trading below support " + String.format("%.2f", support1) + " - consider stop-loss or averaging down";
                } else {
                    guidance = h.getCurrentPrice() < resistance1
                            ? "Testing resistance at " + String.format("%.2f", resistance1) + " - breakout could trigger momentum"
                            : "Above resistance " + String.format("%.2f", resistance1) + " - consider booking partial profits";
                }

                body.append("<div style=\"font-size: 12px; color: #555; margin-bottom: 4px;\">");
                body.append("<strong>").append(h.getSymbol()).append("</strong>: ").append(guidance);
                body.append("</div>");
            }
            body.append("</div>");

            return templateService.section("&#127919;", "Holdings Near Key Levels (" + nearLevels.size() + " stocks)", body.toString());
        } catch (Exception e) {
            log.warn("Morning Briefing: Failed to build holdings near levels: {}", e.getMessage());
            return templateService.section("&#127919;", "Holdings Near Key Levels",
                    templateService.alert("warning", "&#9888;", "Unavailable",
                            "Could not analyze holdings levels: " + e.getMessage()));
        }
    }

    // ============================================================
    // Section 5: AI Market Summary
    // ============================================================

    /**
     * Overnight and recent macro events, plus what is dated in the coming week (SPEC 48.10).
     *
     * <p>Empty when there is neither. The calendar half exists because most of what reaches an
     * Indian portfolio from the wider world arrives while the market here is shut, and a date the
     * investor knew about is one that cannot arrive as a shock.
     */
    private String buildMacroEventsSection() {
        try {
            var recent = macroExposureService.recentEvents(7).stream()
                    .filter(e -> !e.isDismissed()).limit(8).toList();
            var upcoming = com.example.trading.macro.MacroCalendar.upcoming(java.time.LocalDate.now(), 7);
            var symbols = holdingsRepository.findActive().stream()
                    .map(com.example.trading.persistence.HoldingsEntity::getSymbol)
                    .filter(java.util.Objects::nonNull).toList();
            var readings = macroExposureService.forSymbols(symbols);
            return macroReportRenderer.overnightAndCalendarSection(recent, upcoming, readings);
        } catch (Exception e) {
            log.warn("Macro events section omitted from the briefing ({}). Its absence reads as "
                    + "'nothing happened', which is a claim rather than an absence.", e.getMessage());
            return "";
        }
    }

    private String buildAiSummarySection(String allSectionsHtml) {
        if (!aiService.isAvailable()) {
            return "";
        }

        try {
            // Build a concise data summary for the AI (strip HTML, key data points only)
            StringBuilder context = new StringBuilder();
            context.append("Today's pre-market data for Indian stock market:\n");

            // FII/DII data
            try {
                LocalDate yesterday = getPreviousTradingDay(LocalDate.now());
                DailyActivity activity = fiiDiiDataService.fetchDailyActivity(yesterday);
                if (activity != null) {
                    context.append(String.format("FII Net: %.0f Cr (%s), DII Net: %.0f Cr (%s), Sentiment: %s\n",
                            activity.getFiiNetValue(), activity.getFiiNetValue() >= 0 ? "buying" : "selling",
                            activity.getDiiNetValue(), activity.getDiiNetValue() >= 0 ? "buying" : "selling",
                            activity.getOverallSentiment()));
                }
            } catch (Exception e) {
                log.debug("AI Summary: Could not include FII/DII data: {}", e.getMessage());
            }

            // Portfolio summary
            try {
                List<HoldingsEntity> holdings = holdingsRepository.findActive();
                if (!holdings.isEmpty()) {
                    long profitable = holdings.stream().filter(h -> h.getPnlPercent() > 0).count();
                    long losing = holdings.size() - profitable;
                    Double totalPnl = holdingsRepository.getTotalPnL();
                    context.append(String.format("Portfolio: %d holdings, %d profitable, %d losing, Total P&L: %.0f\n",
                            holdings.size(), profitable, losing, totalPnl != null ? totalPnl : 0.0));
                }
            } catch (Exception e) {
                log.debug("AI Summary: Could not include portfolio data: {}", e.getMessage());
            }

            context.append("\nProvide a brief trading plan for today based on this data. What should I watch out for? Any key levels or themes?");

            String aiInsight = aiService.analyze(
                "You are a senior Indian stock market analyst preparing a morning briefing. " +
                "Be concise (5-7 bullet points max). Focus on actionable insights. " +
                "Consider FII/DII flows and portfolio positioning. Use plain text, no markdown formatting.",
                context.toString()
            );

            if (aiInsight.isEmpty()) {
                return "";
            }

            // Format AI response into HTML
            StringBuilder body = new StringBuilder();
            body.append("<div style=\"background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 2px; border-radius: 10px; margin-bottom: 15px;\">");
            body.append("<div style=\"background: #fff; border-radius: 8px; padding: 15px;\">");
            body.append("<div style=\"font-size: 11px; color: #999; margin-bottom: 8px;\">Powered by AI Analysis</div>");

            // Convert plain text bullets to HTML
            String[] lines = aiInsight.split("\n");
            body.append("<ul style=\"margin: 0; padding-left: 20px; color: #333;\">");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                // Strip leading bullet chars (-, *, •, numbered)
                trimmed = trimmed.replaceFirst("^[-*•]\\s*", "").replaceFirst("^\\d+[.):]\\s*", "");
                if (!trimmed.isEmpty()) {
                    body.append("<li style=\"margin-bottom: 6px;\">").append(trimmed).append("</li>");
                }
            }
            body.append("</ul>");
            body.append("</div></div>");

            return templateService.section("&#129302;", "AI Market Summary", body.toString());
        } catch (Exception e) {
            log.warn("Morning Briefing: AI summary generation failed: {}", e.getMessage());
            return "";
        }
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    /**
     * Get the previous trading day (skip weekends).
     */
    private LocalDate getPreviousTradingDay(LocalDate date) {
        LocalDate previous = date.minusDays(1);
        while (previous.getDayOfWeek() == DayOfWeek.SATURDAY || previous.getDayOfWeek() == DayOfWeek.SUNDAY) {
            previous = previous.minusDays(1);
        }
        return previous;
    }

    private String getSentimentBadge(String sentiment) {
        if (sentiment == null) return "NEUTRAL";
        return switch (sentiment) {
            case "STRONG_BULLISH" -> "STRONG BULLISH";
            case "STRONG_BEARISH" -> "STRONG BEARISH";
            case "FII_BULLISH_DII_BEARISH" -> "FII BULL / DII BEAR";
            case "FII_BEARISH_DII_BULLISH" -> "FII BEAR / DII BULL";
            default -> sentiment;
        };
    }

    private String formatSentiment(String sentiment) {
        if (sentiment == null) return "Neutral";
        return switch (sentiment) {
            case "STRONG_BULLISH" -> "Strong Bullish";
            case "STRONG_BEARISH" -> "Strong Bearish";
            case "FII_BULLISH_DII_BEARISH" -> "Mixed (FII Bullish, DII Bearish)";
            case "FII_BEARISH_DII_BULLISH" -> "Mixed (FII Bearish, DII Bullish)";
            case "NEUTRAL" -> "Neutral";
            default -> sentiment;
        };
    }

    private String sentimentAlertType(String sentiment) {
        if (sentiment == null) return "info";
        return switch (sentiment) {
            case "STRONG_BULLISH" -> "success";
            case "STRONG_BEARISH" -> "danger";
            case "FII_BULLISH_DII_BEARISH", "FII_BEARISH_DII_BULLISH" -> "warning";
            default -> "info";
        };
    }

    private String sentimentAlertIcon(String sentiment) {
        if (sentiment == null) return "&#8505;";
        return switch (sentiment) {
            case "STRONG_BULLISH" -> "&#128994;";
            case "STRONG_BEARISH" -> "&#128308;";
            case "FII_BULLISH_DII_BEARISH", "FII_BEARISH_DII_BULLISH" -> "&#128992;";
            default -> "&#8505;";
        };
    }

    private String buildSentimentMessage(DailyActivity activity) {
        StringBuilder msg = new StringBuilder();
        if (activity.getFiiNetValue() >= 0) {
            msg.append("FII were net buyers (").append(String.format("+%.0f Cr", activity.getFiiNetValue())).append("). ");
        } else {
            msg.append("FII were net sellers (").append(String.format("%.0f Cr", activity.getFiiNetValue())).append("). ");
        }
        if (activity.getDiiNetValue() >= 0) {
            msg.append("DII were net buyers (").append(String.format("+%.0f Cr", activity.getDiiNetValue())).append("). ");
        } else {
            msg.append("DII were net sellers (").append(String.format("%.0f Cr", activity.getDiiNetValue())).append("). ");
        }

        double totalNet = activity.getTotalInstitutionalNet();
        if (totalNet > 1000) {
            msg.append("Strong institutional inflow suggests bullish momentum.");
        } else if (totalNet < -1000) {
            msg.append("Significant institutional outflow suggests bearish pressure.");
        } else if (totalNet > 0) {
            msg.append("Mild net inflow - slightly positive bias.");
        } else if (totalNet < 0) {
            msg.append("Mild net outflow - slightly negative bias.");
        } else {
            msg.append("Balanced flow - no strong directional bias.");
        }

        return msg.toString();
    }

    // ============================================================
    // Section 4: Multibagger Radar
    // ============================================================

    private String buildMultibaggerSection() {
        try {
            return multibaggerReportService.buildMorningBriefingSection();
        } catch (Exception e) {
            log.warn("Morning Briefing: Failed to build multibagger section: {}", e.getMessage());
            return "";
        }
    }
}
