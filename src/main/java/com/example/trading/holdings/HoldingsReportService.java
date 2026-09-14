package com.example.trading.holdings;

import com.example.trading.ai.AiService;
import com.example.trading.ai.NseDataService;
import com.example.trading.notification.EmailTemplateService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.example.trading.fiidii.FiiDiiDataService;
import com.example.trading.fiidii.FiiDiiDTO.DailyActivity;
import com.example.trading.fiidii.FiiDiiDTO.SectorFlow;
import com.example.trading.fiidii.FiiDiiSectorAnalysisService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.trading.portfolio.tax.TaxConfig;
import com.example.trading.portfolio.tax.TaxLotDto.TaxAwareExitClassification;
import com.example.trading.portfolio.tax.TaxLotService;

@Service
@Slf4j
@RequiredArgsConstructor
public class HoldingsReportService {

    private final HoldingsRepository holdingsRepository;
    private final JavaMailSender mailSender;
    private final EmailTemplateService templateService;
    private final StockValuationService valuationService;
    private final FiiDiiDataService fiiDiiDataService;
    private final FiiDiiSectorAnalysisService fiiDiiSectorAnalysisService;
    private final com.example.trading.multibagger.MultibaggerReportService multibaggerReportService;
    private final AiService aiService;
    private final NseDataService nseDataService;
    private final HoldingsDecayService holdingsDecayService;
    private final com.example.trading.macro.MacroExposureService macroExposureService;
    private final com.example.trading.macro.MacroReportRenderer macroReportRenderer;
    private final TaxLotService taxLotService;
    private final TaxConfig taxConfig;
    private final com.example.trading.fundamentals.FundamentalsHistoryService fundamentalsHistoryService;
    private final com.example.trading.fundamentals.ForensicScreenService forensicScreenService;
    private final com.example.trading.fundamentals.CapexCycleService capexCycleService;
    private final com.example.trading.portfolio.core.CoreClassificationService coreClassificationService;
    private final com.example.trading.portfolio.core.CoreOverlayService coreOverlayService;
    private final com.example.trading.portfolio.core.CoreHoldingConfig coreConfig;

    @Value("${spring.mail.username}")
    private String senderEmail;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /**
     * Send daily holdings report with analysis and recommendations.
     */
    public void sendDailyHoldingsReport() {
        try {
            if (senderEmail == null || senderEmail.contains("placeholder") || senderEmail.equals("your-email@gmail.com")) {
                log.warn("Holdings report skipped: Email not configured");
                return;
            }

            List<HoldingsEntity> holdings = holdingsRepository.findActiveOrderByScoreDesc();
            if (holdings.isEmpty()) {
                log.info("No holdings to report");
                return;
            }

            // Remove duplicates (same stock appearing multiple times)
            holdings = deduplicateHoldings(holdings);

            // Drop fully-exited rows (qty == 0) so they don't clutter the report
            holdings = filterActiveHoldings(holdings);
            if (holdings.isEmpty()) {
                log.info("No active holdings to report (all rows had zero quantity)");
                return;
            }

            Double totalPnL = holdingsRepository.getTotalPnL();
            String pnlText = totalPnL != null ? String.format("%.2f", totalPnL) : "0.00";
            String dateText = LocalDate.now().format(DATE_FORMATTER);

            // Email 1 of 2: Action Items (decisions to make today)
            try {
                sendHtmlEmail(
                    String.format("Holdings 1/2: Action Items - %s | P&L: Rs.%s", dateText, pnlText),
                    buildDailyActionItemsHtml(holdings)
                );
                log.info("Daily holdings report (Action Items) sent successfully");
            } catch (Exception e) {
                log.error("Failed to send Action Items email: {}", e.getMessage(), e);
            }

            // Email 2 of 2: Portfolio Analysis (deep-dive context — read when not busy)
            try {
                sendHtmlEmail(
                    String.format("Holdings 2/2: Analysis - %s | P&L: Rs.%s", dateText, pnlText),
                    buildDailyAnalysisHtml(holdings)
                );
                log.info("Daily holdings report (Analysis) sent successfully");
            } catch (Exception e) {
                log.error("Failed to send Analysis email: {}", e.getMessage(), e);
            }

        } catch (Exception e) {
            log.error("Failed to send daily holdings report: {}", e.getMessage(), e);
        }
    }

    /**
     * Send weekly comprehensive holdings report.
     */
    public void sendWeeklyHoldingsReport() {
        try {
            if (senderEmail == null || senderEmail.contains("placeholder")) {
                log.warn("Weekly holdings report skipped: Email not configured");
                return;
            }

            List<HoldingsEntity> holdings = holdingsRepository.findActiveOrderByScoreDesc();
            if (holdings.isEmpty()) {
                log.info("No holdings for weekly report");
                return;
            }

            // Remove duplicates (same stock appearing multiple times)
            holdings = deduplicateHoldings(holdings);

            // Drop fully-exited rows (qty == 0)
            holdings = filterActiveHoldings(holdings);
            if (holdings.isEmpty()) {
                log.info("No active holdings for weekly report (all rows had zero quantity)");
                return;
            }

            String htmlContent = buildWeeklyReportHtml(holdings);
            Double totalPnL = holdingsRepository.getTotalPnL();
            String pnlText = totalPnL != null ? String.format("%.2f", totalPnL) : "0.00";

            sendHtmlEmail(
                String.format("WEEKLY Holdings Report - %s | Total P&L: Rs.%s", LocalDate.now().format(DATE_FORMATTER), pnlText),
                htmlContent
            );

            log.info("Weekly holdings report sent successfully");

        } catch (Exception e) {
            log.error("Failed to send weekly holdings report: {}", e.getMessage(), e);
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

    /**
     * Email 1 of 2: Action Items.
     * Short, decision-focused — what to do today, what needs attention.
     * Sections: portfolio summary, executive next-steps, exits/profit-booking,
     * tax-aware horizon, balance-sheet/trend-break/thesis-drift attention items.
     */
    // ---------------------------------------------------------------- previews
    //
    // The dashboard shows these reports as HTML rather than re-deriving their numbers as
    // JSON (SPEC section 27.6). The analysis in this class is computed inside String.format
    // argument lists, so extracting ~25 sections into DTOs would be a multi-week refactor
    // with real regression risk to reports read daily. Serving the same HTML the email
    // sends is exact by construction and cannot drift from it.
    //
    // These methods deliberately reuse the identical holdings pipeline as the send path
    // (findActiveOrderByScoreDesc -> deduplicateHoldings -> filterActiveHoldings). If that
    // pipeline changes, change it in both places or the preview stops matching the email.

    /** Prepared holdings list, identical to what the scheduled emails render. */
    private List<HoldingsEntity> reportHoldings() {
        List<HoldingsEntity> holdings = holdingsRepository.findActiveOrderByScoreDesc();
        if (holdings.isEmpty()) {
            return List.of();
        }
        holdings = deduplicateHoldings(holdings);
        return filterActiveHoldings(holdings);
    }

    /** The "Holdings 1/2: Action Items" email body, for on-screen display. Sends nothing. */
    public String previewDailyActionItems() {
        List<HoldingsEntity> holdings = reportHoldings();
        return holdings.isEmpty() ? null : buildDailyActionItemsHtml(holdings);
    }

    /** The "Holdings 2/2: Analysis" email body, for on-screen display. Sends nothing. */
    public String previewDailyAnalysis() {
        List<HoldingsEntity> holdings = reportHoldings();
        return holdings.isEmpty() ? null : buildDailyAnalysisHtml(holdings);
    }

    /** The weekly review email body, for on-screen display. Sends nothing. */
    public String previewWeeklyReport() {
        List<HoldingsEntity> holdings = reportHoldings();
        return holdings.isEmpty() ? null : buildWeeklyReportHtml(holdings);
    }

    private String buildDailyActionItemsHtml(List<HoldingsEntity> holdings) {
        StringBuilder content = new StringBuilder();

        // What this email is — orient the reader (companion email follows)
        content.append("<div style='background:#fff8e1;border-left:4px solid #f9a825;padding:12px 16px;margin-bottom:16px;border-radius:4px;'>")
               .append("<strong>Email 1 of 2 &mdash; Action Items.</strong> ")
               .append("Decisions to make today: exits, profit-booking, and holdings flagged for attention. ")
               .append("Email 2 (<em>Analysis</em>) follows shortly with valuation, support/resistance, ML/AI insights, and the full holdings table.")
               .append("</div>");

        // Portfolio Summary Section
        content.append(buildPortfolioSummary(holdings));

        // Portfolio Health & Next Steps (executive summary with prioritized actions)
        content.append(buildPortfolioHealthAndNextSteps(holdings));

        // Action Required Section (book profit / hold-for-LTCG / exit required — SPEC §9.4)
        content.append(buildActionRequiredSection(holdings));

        // Core Holdings — which stocks are held through the noise (SPEC §35)
        content.append(buildCoreHoldingsSection(holdings));

        // Thesis Drift Alerts — score-decay on owned holdings (SPEC §6)
        content.append(buildThesisDriftSection(holdings));
        content.append(buildMacroExposureSection(holdings));

        // Financial-Quality Attention Items (SPEC §6)
        content.append(buildFinancialQualityAttention(holdings));

        // Forensic Red Flags — multi-year dilution / receivables / cash / auditor (SPEC §32.4)
        content.append(buildForensicFlagsSection(holdings));

        // Earnings Trend-Break Attention Items (SPEC §24)
        content.append(buildTrendBreakAttention(holdings));

        return templateService.buildEmailTemplate(
            "Holdings Report 1/2: Action Items",
            "What needs your attention today",
            content.toString()
        );
    }

    /**
     * Email 2 of 2: Portfolio Analysis.
     * Deep-dive context — valuation, support/resistance, ML/AI views, multibagger
     * assessment, full holdings table. Read when you have time, not the moment it arrives.
     */
    private String buildDailyAnalysisHtml(List<HoldingsEntity> holdings) {
        StringBuilder content = new StringBuilder();

        // What this email is — orient the reader
        content.append("<div style='background:#e8f5e9;border-left:4px solid #2e7d32;padding:12px 16px;margin-bottom:16px;border-radius:4px;'>")
               .append("<strong>Email 2 of 2 &mdash; Portfolio Analysis.</strong> ")
               .append("Deep-dive context: valuation, support/resistance, ML/AI views, multibagger assessment, full holdings table. ")
               .append("If anything in <em>Email 1 (Action Items)</em> needed immediate action, you've already seen it.")
               .append("</div>");

        // Portfolio Summary (orientation when this email is opened standalone)
        content.append(buildPortfolioSummary(holdings));

        // Market Intelligence Overlay (FII/DII + Options correlation)
        content.append(buildMarketIntelligenceOverlay(holdings));


        // Valuation Analysis Section
        content.append(buildValuationSection(holdings));

        // Support & Resistance Levels
        content.append(buildSupportResistanceSection(holdings));

        // All Holdings Table
        content.append(buildHoldingsTable(holdings));

        // Multibagger Assessment for Holdings
        try {
            String multibaggerSection = multibaggerReportService.buildHoldingsReportSection();
            if (multibaggerSection != null && !multibaggerSection.isEmpty()) {
                content.append(multibaggerSection);
            }
        } catch (Exception e) {
            log.debug("Multibagger section skipped in holdings report: {}", e.getMessage());
        }

        // Technical Summary
        content.append(buildTechnicalSummary(holdings));

        // AI Portfolio Analysis (if available)
        content.append(buildAiPortfolioAnalysis(holdings));

        // Price Level Guide
        content.append(buildPriceLevelGuide());

        return templateService.buildEmailTemplate(
            "Holdings Report 2/2: Analysis",
            "Deep-dive portfolio context",
            content.toString()
        );
    }

    private String buildWeeklyReportHtml(List<HoldingsEntity> holdings) {
        StringBuilder content = new StringBuilder();

        // Portfolio Summary
        content.append(buildPortfolioSummary(holdings));

        // Portfolio Health & Next Steps (executive summary)
        content.append(buildPortfolioHealthAndNextSteps(holdings));

        // Market Intelligence Overlay (FII/DII + Options correlation)
        content.append(buildMarketIntelligenceOverlay(holdings));


        // Valuation Analysis Section
        content.append(buildValuationSection(holdings));

        // Weekly Highlights
        content.append(buildWeeklyHighlights(holdings));

        // Week-over-Week Portfolio Changes
        content.append(buildWeekOverWeekChanges(holdings));

        // Top Movers of the Week
        content.append(buildTopMovers(holdings));

        // Sector Rotation Analysis
        content.append(buildSectorRotation(holdings));

        // Score Distribution
        content.append(buildScoreDistribution(holdings));

        // Action Required Section
        content.append(buildActionRequiredSection(holdings));

        // Support & Resistance Levels
        content.append(buildSupportResistanceSection(holdings));

        // All Holdings Table
        content.append(buildHoldingsTable(holdings));

        // Financial-Quality Attention Items (SPEC §6)
        content.append(buildFinancialQualityAttention(holdings));

        // Forensic Red Flags — multi-year dilution / receivables / cash / auditor (SPEC §32.4)
        content.append(buildForensicFlagsSection(holdings));

        // Earnings Trend-Break Attention Items (SPEC §24)
        content.append(buildTrendBreakAttention(holdings));

        // Fundamental Wealth Signals — pricing power, earnings consistency, real accumulation (SPEC §12.7)
        content.append(buildWealthSignalsSection(holdings));

        // Capital Efficiency — ROCE / ROE / debt / cash conversion from annual balance sheet (SPEC §12.8)
        content.append(buildCapitalEfficiencySection(holdings));

        // Thesis Drift Alerts — score-decay on owned holdings (SPEC §6)
        content.append(buildCoreHoldingsSection(holdings));
        content.append(buildThesisDriftSection(holdings));

        // Recommendations Summary
        content.append(buildRecommendationsSummary(holdings));

        // Price Level Guide
        content.append(buildPriceLevelGuide());

        return templateService.buildEmailTemplate(
            "Weekly Holdings Report",
            "Comprehensive Portfolio Review",
            content.toString()
        );
    }

    private String buildPortfolioSummary(List<HoldingsEntity> holdings) {
        Double totalInvested = holdingsRepository.getTotalInvestedValue();
        Double totalCurrent = holdingsRepository.getTotalCurrentValue();
        Double totalPnL = holdingsRepository.getTotalPnL();
        Long profitable = holdingsRepository.countProfitableHoldings();
        Long losing = holdingsRepository.countLossHoldings();

        double pnlPercent = totalInvested != null && totalInvested > 0
                ? ((totalCurrent - totalInvested) / totalInvested) * 100 : 0;

        String pnlType = totalPnL != null && totalPnL >= 0 ? "profit" : "loss";

        StringBuilder cards = new StringBuilder("<div class=\"summary-grid\">");
        cards.append(templateService.summaryCard("Total Holdings", holdings.size() + " stocks", ""));
        cards.append(templateService.summaryCard("Invested Value", String.format("Rs.%.0f", totalInvested != null ? totalInvested : 0), ""));
        cards.append(templateService.summaryCard("Current Value", String.format("Rs.%.0f", totalCurrent != null ? totalCurrent : 0), ""));
        cards.append(templateService.summaryCard("Total P&L", String.format("Rs.%.2f (%.1f%%)", totalPnL != null ? totalPnL : 0, pnlPercent), pnlType));
        cards.append(templateService.summaryCard("Win/Loss", String.format("%d / %d", profitable != null ? profitable : 0, losing != null ? losing : 0), "neutral"));
        cards.append("</div>");

        return templateService.section("&#128202;", "Portfolio Summary", cards.toString());
    }

    /**
     * Build Portfolio Health & Next Steps - executive summary synthesizing all analysis
     * into a single actionable overview to help decide next steps.
     */
    private String buildPortfolioHealthAndNextSteps(List<HoldingsEntity> holdings) {
        StringBuilder content = new StringBuilder();

        int total = holdings.size();
        if (total == 0) {
            return "";
        }

        // --- Compute all metrics ---
        Double totalInvested = holdingsRepository.getTotalInvestedValue();
        Double totalPnL = holdingsRepository.getTotalPnL();
        double pnlPercent = totalInvested != null && totalInvested > 0
            ? (totalPnL != null ? (totalPnL / totalInvested) * 100 : 0) : 0;

        long bullish = holdings.stream().filter(h -> "BULLISH".equals(h.getTrendDirection())).count();
        long bearish = holdings.stream().filter(h -> "BEARISH".equals(h.getTrendDirection())).count();
        long sideways = holdings.stream().filter(h -> "SIDEWAYS".equals(h.getTrendDirection())).count();

        double avgScore = holdings.stream()
            .filter(h -> h.getOverallScore() != null)
            .mapToInt(HoldingsEntity::getOverallScore)
            .average().orElse(0);

        double avgRsi = holdings.stream()
            .filter(h -> h.getRsi14() != null)
            .mapToDouble(HoldingsEntity::getRsi14)
            .average().orElse(50);

        long aboveEma200 = holdings.stream()
            .filter(h -> h.getEma200() != null && h.getEma200() > 0 && h.getCurrentPrice() > h.getEma200())
            .count();
        long withEma200 = holdings.stream()
            .filter(h -> h.getEma200() != null && h.getEma200() > 0)
            .count();

        long profitable = holdings.stream().filter(h -> h.getPnl() > 0).count();
        long losing = holdings.stream().filter(h -> h.getPnl() < 0).count();

        // Stocks by recommendation
        long exitCount = holdings.stream().filter(h -> h.getRecommendation() != null &&
            (h.getRecommendation().equals("SELL") || h.getRecommendation().equals("STRONG_SELL"))).count();
        long bookProfitCount = holdings.stream().filter(h -> "BOOK_PROFIT".equals(h.getRecommendation())).count();
        long buyCount = holdings.stream().filter(h -> h.getRecommendation() != null &&
            (h.getRecommendation().equals("BUY") || h.getRecommendation().equals("STRONG_BUY"))).count();

        // Overbought / oversold
        long overbought = holdings.stream().filter(h -> h.getRsi14() != null && h.getRsi14() > 70).count();
        long oversold = holdings.stream().filter(h -> h.getRsi14() != null && h.getRsi14() < 30).count();

        // Near S/R levels
        long nearSupport = holdings.stream().filter(h -> {
            if (h.getSupport1() == null || h.getCurrentPrice() <= 0) return false;
            return ((h.getCurrentPrice() - h.getSupport1()) / h.getCurrentPrice()) < 0.02;
        }).count();
        long nearResistance = holdings.stream().filter(h -> {
            if (h.getResistance1() == null || h.getCurrentPrice() <= 0) return false;
            return ((h.getResistance1() - h.getCurrentPrice()) / h.getCurrentPrice()) < 0.02;
        }).count();

        // Deep loss stocks (P&L < -10%)
        List<HoldingsEntity> deepLoss = holdings.stream()
            .filter(h -> h.getPnlPercent() < -10)
            .sorted((a, b) -> Double.compare(a.getPnlPercent(), b.getPnlPercent()))
            .collect(Collectors.toList());

        // Big winners (P&L > 20%)
        List<HoldingsEntity> bigWinners = holdings.stream()
            .filter(h -> h.getPnlPercent() > 20)
            .sorted((a, b) -> Double.compare(b.getPnlPercent(), a.getPnlPercent()))
            .collect(Collectors.toList());

        // --- Portfolio Health Score (0-100) ---
        int healthScore = calculatePortfolioHealthScore(
            pnlPercent, avgScore, bullish, bearish, total, profitable, losing, avgRsi, exitCount);

        String healthLabel;
        String healthBadgeType;
        if (healthScore >= 75) { healthLabel = "STRONG"; healthBadgeType = "success"; }
        else if (healthScore >= 55) { healthLabel = "MODERATE"; healthBadgeType = "warning"; }
        else if (healthScore >= 35) { healthLabel = "WEAK"; healthBadgeType = "warning"; }
        else { healthLabel = "CRITICAL"; healthBadgeType = "danger"; }

        // Market stance
        String stance;
        String stanceBadge;
        if (bullish > bearish * 2) { stance = "BULLISH"; stanceBadge = templateService.badge(stance, "bullish"); }
        else if (bearish > bullish * 2) { stance = "BEARISH"; stanceBadge = templateService.badge(stance, "bearish"); }
        else if (bullish > bearish) { stance = "MILDLY BULLISH"; stanceBadge = templateService.badge(stance, "bullish"); }
        else if (bearish > bullish) { stance = "MILDLY BEARISH"; stanceBadge = templateService.badge(stance, "bearish"); }
        else { stance = "MIXED"; stanceBadge = templateService.badge(stance, "sideways"); }

        // --- Health Score & Key Metrics Cards ---
        content.append("<div class=\"summary-grid\">");
        content.append(templateService.summaryCard("Health Score",
            healthScore + "/100 " + templateService.badge(healthLabel, healthBadgeType), ""));
        content.append(templateService.summaryCard("Market Stance", stanceBadge, ""));
        content.append(templateService.summaryCard("Avg Score", String.format("%.0f/100", avgScore),
            avgScore >= 60 ? "profit" : avgScore >= 40 ? "neutral" : "loss"));
        content.append(templateService.summaryCard("Avg RSI", String.format("%.1f", avgRsi),
            avgRsi > 70 ? "loss" : avgRsi < 30 ? "loss" : ""));
        content.append(templateService.summaryCard("Above 200 EMA",
            String.format("%d/%d", aboveEma200, withEma200),
            aboveEma200 > withEma200 / 2 ? "profit" : "loss"));
        content.append("</div>");

        // --- Priority Next Steps ---
        content.append("<div style=\"margin-top: 20px;\">");
        content.append("<h4 style=\"color: #1a237e; margin-bottom: 12px;\">Priority Next Steps</h4>");

        int stepNum = 0;

        // CRITICAL: Immediate exits
        if (exitCount > 0) {
            stepNum++;
            List<String> exitSymbols = holdings.stream()
                .filter(h -> h.getRecommendation() != null &&
                    (h.getRecommendation().equals("SELL") || h.getRecommendation().equals("STRONG_SELL")))
                .map(h -> h.getTradingSymbol() + " (" + String.format("%.1f%%", h.getPnlPercent()) + ")")
                .collect(Collectors.toList());
            content.append(buildNextStepItem(stepNum, "danger",
                "EXIT " + exitCount + " weak position" + (exitCount > 1 ? "s" : ""),
                "Bearish trend, low scores — exit to free up capital: " + String.join(", ", exitSymbols)));
        }

        // HIGH: Deep loss review
        if (!deepLoss.isEmpty()) {
            stepNum++;
            List<String> deepLossSymbols = deepLoss.stream()
                .map(h -> h.getTradingSymbol() + " (" + String.format("%.1f%%", h.getPnlPercent()) + ")")
                .limit(5)
                .collect(Collectors.toList());
            content.append(buildNextStepItem(stepNum, "danger",
                "REVIEW " + deepLoss.size() + " deep loss holding" + (deepLoss.size() > 1 ? "s" : "") + " (>10% loss)",
                "Assess if thesis is intact or cut losses: " + String.join(", ", deepLossSymbols)));
        }

        // HIGH: Overbought stocks
        if (overbought > 0) {
            stepNum++;
            List<String> obSymbols = holdings.stream()
                .filter(h -> h.getRsi14() != null && h.getRsi14() > 70)
                .map(h -> h.getTradingSymbol() + " (RSI " + String.format("%.0f", h.getRsi14()) + ")")
                .collect(Collectors.toList());
            content.append(buildNextStepItem(stepNum, "warning",
                "WATCH " + overbought + " overbought stock" + (overbought > 1 ? "s" : "") + " (RSI > 70)",
                "Risk of pullback — tighten stop-loss or book partial profit: " + String.join(", ", obSymbols)));
        }

        // MEDIUM: Book profit opportunities — split by tax horizon (SPEC.md §9). Only flag
        // LTCG-eligible winners as "book now"; STCG-only winners get a separate "wait for LTCG"
        // step so we don't suggest realizing 20% short-term tax.
        Map<String, HoldingsEntity> nextStepProfitCandidates = new LinkedHashMap<>();
        holdings.stream()
                .filter(h -> "BOOK_PROFIT".equals(h.getRecommendation()))
                .forEach(h -> nextStepProfitCandidates.put(h.getSymbol(), h));
        for (HoldingsEntity h : bigWinners) nextStepProfitCandidates.putIfAbsent(h.getSymbol(), h);

        List<HoldingsEntity> nextStepLtcg = new ArrayList<>();
        List<HoldingsEntity> nextStepStcg = new ArrayList<>();
        Map<String, TaxAwareExitClassification> nextStepClassifications = new HashMap<>();
        for (HoldingsEntity h : nextStepProfitCandidates.values()) {
            TaxAwareExitClassification cls = classify(h);
            nextStepClassifications.put(h.getSymbol(), cls);
            if (cls.hasLtcgEligible() || "UNKNOWN".equals(cls.dataSource())) {
                nextStepLtcg.add(h);
            } else if (cls.fullyShortTerm()) {
                nextStepStcg.add(h);
            }
        }

        if (!nextStepLtcg.isEmpty()) {
            stepNum++;
            List<String> profitSymbols = nextStepLtcg.stream()
                .map(h -> h.getTradingSymbol() + " (+" + String.format("%.1f%%", h.getPnlPercent()) + ")")
                .limit(5)
                .collect(Collectors.toList());
            content.append(buildNextStepItem(stepNum, "warning",
                "BOOK PROFIT on " + nextStepLtcg.size() + " long-term winner" + (nextStepLtcg.size() > 1 ? "s" : ""),
                "LTCG-eligible (>365d held) — partial exit at 12.5% tax instead of 20% STCG: " + String.join(", ", profitSymbols)));
        }

        if (!nextStepStcg.isEmpty()) {
            stepNum++;
            List<String> waitSymbols = nextStepStcg.stream()
                .sorted(Comparator.comparingInt(h -> {
                    Integer d = nextStepClassifications.get(h.getSymbol()).daysUntilNextLtcg();
                    return d != null ? d : Integer.MAX_VALUE;
                }))
                .map(h -> {
                    Integer d = nextStepClassifications.get(h.getSymbol()).daysUntilNextLtcg();
                    return h.getTradingSymbol() + " (+" + String.format("%.1f%%", h.getPnlPercent()) + (d != null ? ", " + d + "d" : "") + ")";
                })
                .limit(5)
                .collect(Collectors.toList());
            content.append(buildNextStepItem(stepNum, "info",
                "HOLD " + nextStepStcg.size() + " short-term winner" + (nextStepStcg.size() > 1 ? "s" : "") + " for LTCG",
                "Booking now triggers 20% STCG; wait until past 365 days for 12.5% LTCG: " + String.join(", ", waitSymbols)));
        }

        // MEDIUM: Stocks near support
        if (nearSupport > 0) {
            stepNum++;
            List<String> supportSymbols = holdings.stream()
                .filter(h -> h.getSupport1() != null && h.getCurrentPrice() > 0 &&
                    ((h.getCurrentPrice() - h.getSupport1()) / h.getCurrentPrice()) < 0.02)
                .map(h -> h.getTradingSymbol() + " (S1: " + String.format("%.0f", h.getSupport1()) + ")")
                .collect(Collectors.toList());
            content.append(buildNextStepItem(stepNum, "success",
                "ACCUMULATE — " + nearSupport + " stock" + (nearSupport > 1 ? "s" : "") + " near support",
                "Near 20-day low, potential bounce zone — add if fundamentals intact: " + String.join(", ", supportSymbols)));
        }

        // MEDIUM: Stocks near resistance
        if (nearResistance > 0) {
            stepNum++;
            List<String> resistSymbols = holdings.stream()
                .filter(h -> h.getResistance1() != null && h.getCurrentPrice() > 0 &&
                    ((h.getResistance1() - h.getCurrentPrice()) / h.getCurrentPrice()) < 0.02)
                .map(h -> h.getTradingSymbol() + " (R1: " + String.format("%.0f", h.getResistance1()) + ")")
                .collect(Collectors.toList());
            content.append(buildNextStepItem(stepNum, "info",
                "WATCH — " + nearResistance + " stock" + (nearResistance > 1 ? "s" : "") + " near resistance",
                "Near 20-day high — watch for breakout or rejection: " + String.join(", ", resistSymbols)));
        }

        // LOW: Accumulate strong stocks
        if (buyCount > 0) {
            stepNum++;
            List<String> buySymbols = holdings.stream()
                .filter(h -> h.getRecommendation() != null &&
                    (h.getRecommendation().equals("BUY") || h.getRecommendation().equals("STRONG_BUY")))
                .map(h -> h.getTradingSymbol() + " (Score: " + h.getOverallScore() + ")")
                .collect(Collectors.toList());
            content.append(buildNextStepItem(stepNum, "success",
                "ACCUMULATE " + buyCount + " strong stock" + (buyCount > 1 ? "s" : "") + " on dips",
                "Bullish trend + high score — add on pullbacks: " + String.join(", ", buySymbols)));
        }

        // LOW: Oversold stocks
        if (oversold > 0) {
            stepNum++;
            List<String> osSymbols = holdings.stream()
                .filter(h -> h.getRsi14() != null && h.getRsi14() < 30)
                .map(h -> h.getTradingSymbol() + " (RSI " + String.format("%.0f", h.getRsi14()) + ")")
                .collect(Collectors.toList());
            content.append(buildNextStepItem(stepNum, "info",
                "MONITOR " + oversold + " oversold stock" + (oversold > 1 ? "s" : "") + " (RSI < 30)",
                "Could be bottoming out — watch for RSI reversal before adding: " + String.join(", ", osSymbols)));
        }

        // If nothing notable
        if (stepNum == 0) {
            content.append(buildNextStepItem(1, "success",
                "NO URGENT ACTION NEEDED",
                "Portfolio is stable. Continue monitoring your positions. Review again tomorrow."));
        }

        content.append("</div>");

        // --- Portfolio Composition Bar ---
        content.append("<div style=\"margin-top: 20px;\">");
        content.append("<h4 style=\"color: #1a237e; margin-bottom: 8px;\">Portfolio Composition</h4>");
        content.append(buildCompositionBar(bullish, bearish, sideways, total));

        // Win rate and concentration
        double winRate = total > 0 ? (profitable * 100.0 / total) : 0;
        content.append(String.format(
            "<div style=\"display: flex; gap: 20px; margin-top: 10px; font-size: 13px; color: #666;\">" +
            "<span>Win Rate: <strong style=\"color: %s;\">%.0f%%</strong> (%d of %d)</span>" +
            "<span>Bullish: <strong>%d</strong> | Bearish: <strong>%d</strong> | Sideways: <strong>%d</strong></span>" +
            "</div>",
            winRate >= 50 ? "#2e7d32" : "#c62828", winRate, profitable, total,
            bullish, bearish, sideways));
        content.append("</div>");

        return templateService.section("&#127919;", "Portfolio Health & Next Steps", content.toString());
    }

    /**
     * Calculate portfolio health score (0-100) from key metrics.
     */
    private int calculatePortfolioHealthScore(double pnlPercent, double avgScore,
            long bullish, long bearish, int total, long profitable, long losing,
            double avgRsi, long exitCount) {
        int score = 50; // base

        // P&L contribution (max +/- 20)
        if (pnlPercent > 10) score += 20;
        else if (pnlPercent > 5) score += 15;
        else if (pnlPercent > 0) score += 10;
        else if (pnlPercent > -5) score -= 5;
        else if (pnlPercent > -10) score -= 10;
        else score -= 20;

        // Average score (max +/- 15)
        if (avgScore >= 70) score += 15;
        else if (avgScore >= 55) score += 10;
        else if (avgScore >= 40) score += 0;
        else if (avgScore >= 25) score -= 10;
        else score -= 15;

        // Trend composition (max +/- 10)
        if (total > 0) {
            double bullishRatio = (double) bullish / total;
            if (bullishRatio > 0.6) score += 10;
            else if (bullishRatio > 0.4) score += 5;
            double bearishRatio = (double) bearish / total;
            if (bearishRatio > 0.5) score -= 10;
            else if (bearishRatio > 0.3) score -= 5;
        }

        // Win rate (max +/- 10)
        long totalPositions = profitable + losing;
        if (totalPositions > 0) {
            double winRate = (double) profitable / totalPositions;
            if (winRate >= 0.7) score += 10;
            else if (winRate >= 0.5) score += 5;
            else if (winRate < 0.3) score -= 10;
        }

        // RSI health (max +/- 5)
        if (avgRsi >= 45 && avgRsi <= 65) score += 5;
        else if (avgRsi > 75 || avgRsi < 25) score -= 5;

        // Penalty for exit candidates
        if (exitCount > 3) score -= 10;
        else if (exitCount > 0) score -= 5;

        return Math.max(0, Math.min(100, score));
    }

    /**
     * Build a single next-step action item with numbered priority.
     */
    private String buildNextStepItem(int stepNum, String type, String title, String description) {
        String borderColor = switch (type) {
            case "danger" -> "#c62828";
            case "warning" -> "#f57c00";
            case "success" -> "#2e7d32";
            default -> "#1976d2";
        };
        String bgColor = switch (type) {
            case "danger" -> "#fff5f5";
            case "warning" -> "#fffbf0";
            case "success" -> "#f0fff4";
            default -> "#f0f7ff";
        };
        return String.format(
            "<div style=\"display: flex; align-items: flex-start; gap: 12px; padding: 12px 15px; " +
            "margin-bottom: 8px; border-left: 4px solid %s; background: %s; border-radius: 4px;\">" +
            "<div style=\"min-width: 28px; height: 28px; background: %s; color: white; border-radius: 50%%; " +
            "display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 13px;\">%d</div>" +
            "<div><div style=\"font-weight: 600; font-size: 14px; color: %s;\">%s</div>" +
            "<div style=\"font-size: 12px; color: #555; margin-top: 3px;\">%s</div></div></div>",
            borderColor, bgColor, borderColor, stepNum, borderColor, title, description);
    }

    /**
     * Build a visual composition bar showing bullish/bearish/sideways ratio.
     */
    private String buildCompositionBar(long bullish, long bearish, long sideways, int total) {
        if (total == 0) return "";
        int bullPct = (int) Math.round(bullish * 100.0 / total);
        int bearPct = (int) Math.round(bearish * 100.0 / total);
        int sidePct = 100 - bullPct - bearPct;
        return String.format(
            "<div style=\"display: flex; height: 24px; border-radius: 12px; overflow: hidden; font-size: 11px; font-weight: 600;\">" +
            "<div style=\"width: %d%%; background: #4caf50; color: white; display: flex; align-items: center; justify-content: center;\">%s</div>" +
            "<div style=\"width: %d%%; background: #ff9800; color: white; display: flex; align-items: center; justify-content: center;\">%s</div>" +
            "<div style=\"width: %d%%; background: #f44336; color: white; display: flex; align-items: center; justify-content: center;\">%s</div>" +
            "</div>",
            bullPct, bullPct > 10 ? bullPct + "% Bull" : "",
            sidePct, sidePct > 10 ? sidePct + "% Side" : "",
            bearPct, bearPct > 10 ? bearPct + "% Bear" : "");
    }

    private String buildActionRequiredSection(List<HoldingsEntity> holdings) {
        StringBuilder content = new StringBuilder();

        // Exit Candidates. A core holding is held through price noise (SPEC §35.5), so its
        // technical SELL is moved to its own list rather than dropped - the reader still sees the
        // signal, framed as "this is the noise the tier exists to sit through".
        List<HoldingsEntity> exitCandidates = new ArrayList<>();
        List<HoldingsEntity> coreHeldThrough = new ArrayList<>();
        for (HoldingsEntity h : holdings) {
            String rec = h.getRecommendation();
            if (rec == null || !(rec.equals("SELL") || rec.equals("STRONG_SELL"))) continue;
            if (isCoreProtected(h)) coreHeldThrough.add(h);
            else exitCandidates.add(h);
        }

        // Book Profit. Excludes core holdings entirely: the rule behind this table fires on
        // P&L > 50% AND RSI > 70, i.e. it flags the best compounders precisely while they run
        // (B-056). Trimming a core holding is not something a momentum rule should propose.
        List<HoldingsEntity> bookProfit = holdings.stream()
                .filter(h -> "BOOK_PROFIT".equals(h.getRecommendation()))
                .filter(h -> !isCoreProtected(h))
                .collect(Collectors.toList());

        // Accumulate
        List<HoldingsEntity> accumulate = holdings.stream()
                .filter(h -> h.getRecommendation() != null &&
                        (h.getRecommendation().equals("BUY") || h.getRecommendation().equals("STRONG_BUY")))
                .collect(Collectors.toList());

        // Exit Alert — sell-signal exits (cut losses or trim weak names regardless of horizon).
        // Each row tagged with the tax horizon (LTCG = >365 days, STCG = ≤365 days) so the
        // user knows whether the realized loss/gain will be short- or long-term.
        if (!exitCandidates.isEmpty()) {
            content.append(templateService.alert("danger", "&#9888;",
                "Exit Required (" + exitCandidates.size() + " stocks)",
                "These positions show weak technicals. Cut losses to protect capital — even short-term losses are useful (they offset future STCG/LTCG in the same financial year)."));

            StringBuilder table = new StringBuilder();
            table.append(templateService.tableStart("Symbol", "CMP", "Sell Above", "Stop Loss", "P&L%", "Horizon", "Trend", "Reason"));

            for (HoldingsEntity h : exitCandidates) {
                double sellPrice = h.getCurrentPrice();
                Double ema20 = h.getEma20();
                double sellAbove = (ema20 != null && ema20 > sellPrice) ? ema20 : sellPrice;
                double stopLoss = h.getSuggestedStopLoss() != null ? h.getSuggestedStopLoss() : sellPrice * 0.95;
                TaxAwareExitClassification cls = classify(h);

                table.append(templateService.tableRow(
                    "<strong>" + truncate(h.getTradingSymbol(), 12) + "</strong>",
                    String.format("%.2f", h.getCurrentPrice()),
                    String.format("%.2f", sellAbove),
                    String.format("%.2f", stopLoss),
                    templateService.formatPercent(h.getPnlPercent()),
                    horizonBadge(cls),
                    templateService.badge(h.getTrendDirection() != null ? h.getTrendDirection() : "N/A",
                        templateService.getTrendBadgeType(h.getTrendDirection())),
                    getTrendReason(h)
                ));
            }
            table.append(templateService.tableEnd());
            content.append(table);
            content.append(buildTaxHorizonGloss());
        }

        // Profit-booking — gated on LTCG eligibility (SPEC.md §9). Combine the recommendation-driven
        // BOOK_PROFIT list with big winners (P&L > 20%), then split into LTCG-eligible and STCG-only
        // buckets. Only the LTCG bucket gets a partial-exit suggestion; STCG winners get a "wait
        // N more days" callout instead so we don't trigger a 20% short-term tax hit.
        Map<String, HoldingsEntity> profitCandidates = new LinkedHashMap<>();
        for (HoldingsEntity h : bookProfit) profitCandidates.put(h.getSymbol(), h);
        holdings.stream()
                .filter(h -> h.getPnlPercent() > 20)
                .filter(h -> !isCoreProtected(h))
                .forEach(h -> profitCandidates.putIfAbsent(h.getSymbol(), h));

        List<HoldingsEntity> ltcgWinners = new ArrayList<>();
        List<HoldingsEntity> stcgOnlyWinners = new ArrayList<>();
        Map<String, TaxAwareExitClassification> winnerClassifications = new HashMap<>();
        for (HoldingsEntity h : profitCandidates.values()) {
            TaxAwareExitClassification cls = classify(h);
            winnerClassifications.put(h.getSymbol(), cls);
            if (cls.hasLtcgEligible()) {
                ltcgWinners.add(h);
            } else if (cls.fullyShortTerm()) {
                stcgOnlyWinners.add(h);
            } else {
                // UNKNOWN horizon (no tax lots and no purchaseDate) — show as LTCG candidate
                // with a caveat rather than silently dropping it.
                ltcgWinners.add(h);
            }
        }

        if (!ltcgWinners.isEmpty()) {
            content.append("<div style=\"margin-top: 20px;\"></div>");
            content.append(templateService.alert("warning", "&#128176;",
                "Book Profit (" + ltcgWinners.size() + " stocks — long-term lots only)",
                "These have at least some quantity held > 365 days, so booking gains is taxed at the lower 12.5% LTCG rate (with a ₹1.25 L/year exemption). Short-term lots in the same stock are excluded — see the 'Hold for LTCG' section below. "
                + "<strong>Read this table as a prompt to check valuation, not as a sell instruction:</strong> the rule that populates it (big gain plus a hot RSI) is a momentum rule inherited from this app's intraday era, and a stock running hard is not by itself a reason for a long-term investor to trim. Core holdings are excluded from it entirely."));

            StringBuilder table = new StringBuilder();
            table.append(templateService.tableStart("Symbol", "CMP", "Qty to Book", "Target 1", "Target 2", "P&L%", "Tax Note"));

            for (HoldingsEntity h : ltcgWinners) {
                TaxAwareExitClassification cls = winnerClassifications.get(h.getSymbol());
                String qtyAction;
                String taxNote;
                if ("UNKNOWN".equals(cls.dataSource())) {
                    qtyAction = "Up to 50% (verify horizon)";
                    taxNote = "<span style=\"color:#666;\">Holding period unknown — confirm before booking</span>";
                } else if (cls.hasMixedHorizon()) {
                    qtyAction = String.format("%d (LTCG)", cls.ltcgEligibleQuantity());
                    taxNote = String.format("LTCG %d / STCG %d (hold %dd)",
                        cls.ltcgEligibleQuantity(), cls.stcgQuantity(), cls.daysUntilNextLtcg());
                } else {
                    qtyAction = String.format("Up to %d (LTCG)", cls.ltcgEligibleQuantity());
                    taxNote = "All long-term — 12.5% LTCG";
                }
                table.append(templateService.tableRow(
                    "<strong>" + truncate(h.getTradingSymbol(), 12) + "</strong>",
                    String.format("%.2f", h.getCurrentPrice()),
                    qtyAction,
                    String.format("%.2f", h.getSuggestedTarget1() != null ? h.getSuggestedTarget1() : h.getCurrentPrice() * 1.05),
                    String.format("%.2f", h.getSuggestedTarget2() != null ? h.getSuggestedTarget2() : h.getCurrentPrice() * 1.10),
                    templateService.formatPercent(h.getPnlPercent()),
                    taxNote
                ));
            }
            table.append(templateService.tableEnd());
            content.append(table);
        }

        if (!stcgOnlyWinners.isEmpty()) {
            content.append("<div style=\"margin-top: 20px;\"></div>");
            content.append(templateService.alert("info", "&#9201;",
                "Hold for LTCG (" + stcgOnlyWinners.size() + " short-term winners)",
                "These winners are entirely short-term lots. Booking now triggers 20% STCG; waiting until they cross 365 days drops the rate to 12.5% LTCG (with ₹1.25 L/year exempt). Skip booking unless thesis breaks."));

            StringBuilder table = new StringBuilder();
            table.append(templateService.tableStart("Symbol", "CMP", "P&L%", "Days to LTCG", "Recommended Action"));

            // Sort by days-until-LTCG ascending — closest to graduation first.
            stcgOnlyWinners.sort(Comparator.comparingInt(h -> {
                Integer d = winnerClassifications.get(h.getSymbol()).daysUntilNextLtcg();
                return d != null ? d : Integer.MAX_VALUE;
            }));

            for (HoldingsEntity h : stcgOnlyWinners) {
                TaxAwareExitClassification cls = winnerClassifications.get(h.getSymbol());
                Integer days = cls.daysUntilNextLtcg();
                String daysCell = days != null ? days + " days" : "—";
                String action = days != null && days <= 30
                    ? "<strong style=\"color:#2e7d32;\">Almost LTCG — hold</strong>"
                    : "Hold (avoid 20% STCG)";
                table.append(templateService.tableRow(
                    "<strong>" + truncate(h.getTradingSymbol(), 12) + "</strong>",
                    String.format("%.2f", h.getCurrentPrice()),
                    templateService.formatPercent(h.getPnlPercent()),
                    daysCell,
                    action
                ));
            }
            table.append(templateService.tableEnd());
            content.append(table);
        }

        // Core holdings carrying a technical sell signal — shown, not acted on.
        if (!coreHeldThrough.isEmpty()) {
            content.append("<div style=\"margin-top: 20px;\"></div>");
            content.append(templateService.alert("info", "&#127959;",
                "Core holdings — hold through the noise (" + coreHeldThrough.size() + ")",
                "These carry a technical sell signal today, but their business passed every quality "
                + "check we run: how well they earn on capital, balance-sheet strength, earnings "
                + "steadiness, clean accounts, and whether your original reason for buying still "
                + "holds. A falling price is not one of those checks. They are listed here instead "
                + "of under 'Exit Required' — only a fundamental change demotes them."));

            StringBuilder table = new StringBuilder();
            table.append(templateService.tableStart("Symbol", "CMP", "P&L%", "Signal", "Durability", "Why it stays"));
            for (HoldingsEntity h : coreHeldThrough) {
                com.example.trading.portfolio.core.CoreDto.CoreHoldingView v = coreViewOf(h);
                table.append(templateService.tableRow(
                    "<strong>" + truncate(h.getTradingSymbol(), 12) + "</strong>",
                    String.format("%.2f", h.getCurrentPrice()),
                    templateService.formatPercent(h.getPnlPercent()),
                    coreOverlayService.displayRecommendation(h.getSymbol(), h.getRecommendation()),
                    v == null ? "&mdash;" : durabilityCell(v),
                    v == null ? "&mdash;" : keyStrength(v)));
            }
            table.append(templateService.tableEnd());
            content.append(table);
        }

        // Accumulate Alert
        if (!accumulate.isEmpty()) {
            content.append("<div style=\"margin-top: 20px;\"></div>");
            content.append(templateService.alert("success", "&#10004;",
                "Accumulate (" + accumulate.size() + " stocks)",
                "These stocks show strong technicals. Consider adding on dips."));

            StringBuilder table = new StringBuilder();
            table.append(templateService.tableStart("Symbol", "CMP", "Buy Below", "Stop Loss", "Target", "Trend", "Score"));

            for (HoldingsEntity h : accumulate) {
                double cmp = h.getCurrentPrice();
                Double ema20 = h.getEma20();
                Double ema50 = h.getEma50();

                double buyBelow = cmp;
                if (ema20 != null && ema20 < cmp && ema20 > cmp * 0.95) {
                    buyBelow = ema20;
                } else if (ema50 != null && ema50 < cmp && ema50 > cmp * 0.90) {
                    buyBelow = ema50;
                } else {
                    buyBelow = cmp * 0.97;
                }

                double stopLoss = h.getSuggestedStopLoss() != null ? h.getSuggestedStopLoss() : buyBelow * 0.93;
                double target = h.getSuggestedTarget1() != null ? h.getSuggestedTarget1() : cmp * 1.10;
                int score = h.getOverallScore() != null ? h.getOverallScore() : 0;

                table.append(templateService.tableRow(
                    "<strong>" + truncate(h.getTradingSymbol(), 12) + "</strong>",
                    String.format("%.2f", cmp),
                    String.format("%.2f", buyBelow),
                    String.format("%.2f", stopLoss),
                    String.format("%.2f", target),
                    templateService.badge(h.getTrendDirection() != null ? h.getTrendDirection() : "N/A",
                        templateService.getTrendBadgeType(h.getTrendDirection())),
                    score + "/100"
                ));
            }
            table.append(templateService.tableEnd());
            content.append(table);
        }

        if (exitCandidates.isEmpty() && ltcgWinners.isEmpty() && stcgOnlyWinners.isEmpty() && accumulate.isEmpty()) {
            content.append(templateService.alert("info", "&#128712;",
                "No Immediate Action Required",
                "All holdings are in HOLD status. Continue monitoring."));
        }

        return templateService.section("&#9888;", "Action Required", content.toString());
    }

    /**
     * Look up the tax horizon split for a holding — LTCG-eligible vs STCG quantity.
     * Wraps {@link TaxLotService#classifyForExit(String, int, LocalDate)} with the
     * holding's purchaseDate as the fallback for symbols without persisted tax lots.
     */
    private TaxAwareExitClassification classify(HoldingsEntity h) {
        LocalDate purchaseDate = h.getPurchaseDate() != null ? h.getPurchaseDate().toLocalDate() : null;
        return taxLotService.classifyForExit(h.getSymbol(), h.getIsin(), h.getQuantity(), purchaseDate);
    }

    /**
     * Compact horizon badge for table cells — LTCG / STCG (Nd) / MIXED / —.
     */
    private String horizonBadge(TaxAwareExitClassification cls) {
        if ("UNKNOWN".equals(cls.dataSource())) {
            return templateService.badge("—", "info");
        }
        if (cls.hasMixedHorizon()) {
            return templateService.badge("MIXED", "warning");
        }
        if (cls.hasLtcgEligible()) {
            return templateService.badge("LTCG", "success");
        }
        Integer d = cls.daysUntilNextLtcg();
        return templateService.badge("STCG" + (d != null ? " (" + d + "d)" : ""), "danger");
    }

    /**
     * Plain-English explainer that follows the Exit Required and Book Profit tables.
     * Per CLAUDE.md user-feedback rule: every report section gets a "what this means" gloss.
     */
    /**
     * Is this holding held through price noise? Presentational only — the stored
     * {@code recommendation} column is never changed, so ML labels and the raw signal are intact.
     */
    private boolean isCoreProtected(HoldingsEntity h) {
        try {
            return coreOverlayService.isProtected(h.getSymbol());
        } catch (Exception e) {
            // Unknown means unprotected, which is exactly today's behaviour. A report must never
            // withhold an exit signal because a lookup failed.
            log.debug("Core tier lookup failed for {}: {}", h.getSymbol(), e.getMessage());
            return false;
        }
    }

    /**
     * Today's classification row for a holding, or null.
     *
     * <p>One query per call, deliberately not cached: the only caller iterates the
     * held-through list, which is a handful of rows at most. If a caller ever needs this
     * per-holding across the whole portfolio, build the map once instead of calling this in a loop.
     */
    private com.example.trading.portfolio.core.CoreDto.CoreHoldingView coreViewOf(HoldingsEntity h) {
        try {
            for (com.example.trading.portfolio.core.CoreDto.CoreHoldingView v
                    : coreClassificationService.latestClassifications()) {
                if (v.symbol().equals(h.getSymbol())) return v;
            }
        } catch (Exception e) {
            log.debug("Core view unavailable for {}: {}", h.getSymbol(), e.getMessage());
        }
        return null;
    }

    private String buildTaxHorizonGloss() {
        String body =
            "<div style=\"margin: 10px 0; padding: 12px 14px; background: #f5f9ff; border-left: 4px solid #1976d2; border-radius: 4px; font-size: 12px; color: #333;\">"
            + "<div style=\"font-weight:600; margin-bottom:6px;\">What \"Horizon\" means</div>"
            + "<div><strong>LTCG</strong> = Long-Term Capital Gains. Equity held &gt; 365 days. Taxed at 12.5%, with the first ₹1.25 L of gains each financial year exempt.</div>"
            + "<div style=\"margin-top:4px;\"><strong>STCG (Nd)</strong> = Short-Term Capital Gains. Equity held ≤ 365 days. Taxed at "
            + String.format("%.0f%%", taxConfig.getStcgRatePercent())
            + ". The number in parentheses is days remaining until this holding becomes LTCG-eligible.</div>"
            + "<div style=\"margin-top:4px;\"><strong>MIXED</strong> = This stock has lots on both sides of the 365-day cutoff — partial-exit suggestions only target the long-term portion.</div>"
            + "<div style=\"margin-top:4px;\"><strong>Why cut a short-term loser anyway:</strong> realized short-term losses (STCL) can offset both STCG and LTCG in the same financial year, so cutting a broken thesis early still has tax value.</div>"
            + "</div>";
        return body;
    }

    private String buildHoldingsTable(List<HoldingsEntity> holdings) {
        StringBuilder table = new StringBuilder();
        table.append(templateService.tableStart("Symbol", "Qty", "Avg Price", "CMP", "P&L%", "Stop Loss", "Target 1", "Target 2", "Score", "Action"));

        for (HoldingsEntity h : holdings) {
            String recommendation = h.getRecommendation() != null ? h.getRecommendation() : "HOLD";
            String action = templateService.getActionText(recommendation);
            String badgeType = templateService.getRecommendationBadgeType(recommendation);

            double stopLoss = h.getSuggestedStopLoss() != null ? h.getSuggestedStopLoss() : h.getCurrentPrice() * 0.93;
            double target1 = h.getSuggestedTarget1() != null ? h.getSuggestedTarget1() : h.getCurrentPrice() * 1.07;
            double target2 = h.getSuggestedTarget2() != null ? h.getSuggestedTarget2() : h.getCurrentPrice() * 1.15;
            int score = h.getOverallScore() != null ? h.getOverallScore() : 0;

            table.append(templateService.tableRow(
                "<strong>" + truncate(h.getTradingSymbol(), 12) + "</strong>",
                String.valueOf(h.getQuantity()),
                String.format("%.2f", h.getAveragePrice()),
                String.format("%.2f", h.getCurrentPrice()),
                templateService.formatPercent(h.getPnlPercent()),
                String.format("%.2f", stopLoss),
                String.format("%.2f", target1),
                String.format("%.2f", target2),
                score + "/100",
                templateService.badge(action, badgeType)
            ));
        }
        table.append(templateService.tableEnd());

        return templateService.section("&#128203;", "All Holdings", table.toString());
    }

    private String buildTechnicalSummary(List<HoldingsEntity> holdings) {
        long bullish = holdings.stream().filter(h -> "BULLISH".equals(h.getTrendDirection())).count();
        long bearish = holdings.stream().filter(h -> "BEARISH".equals(h.getTrendDirection())).count();
        long sideways = holdings.stream().filter(h -> "SIDEWAYS".equals(h.getTrendDirection())).count();

        double avgScore = holdings.stream()
                .filter(h -> h.getOverallScore() != null)
                .mapToInt(HoldingsEntity::getOverallScore)
                .average()
                .orElse(0);

        StringBuilder content = new StringBuilder();
        content.append("<div class=\"summary-grid\">");
        content.append(templateService.summaryCard("Bullish", bullish + " stocks", "profit"));
        content.append(templateService.summaryCard("Bearish", bearish + " stocks", "loss"));
        content.append(templateService.summaryCard("Sideways", sideways + " stocks", "neutral"));
        content.append(templateService.summaryCard("Avg Score", String.format("%.1f/100", avgScore), ""));
        content.append("</div>");

        return templateService.section("&#128200;", "Technical Summary", content.toString());
    }

    private String buildWeeklyHighlights(List<HoldingsEntity> holdings) {
        List<HoldingsEntity> topGainers = holdings.stream()
                .sorted((a, b) -> Double.compare(b.getPnlPercent(), a.getPnlPercent()))
                .limit(3)
                .collect(Collectors.toList());

        List<HoldingsEntity> topLosers = holdings.stream()
                .filter(h -> h.getPnlPercent() < 0)
                .sorted((a, b) -> Double.compare(a.getPnlPercent(), b.getPnlPercent()))
                .limit(3)
                .collect(Collectors.toList());

        StringBuilder content = new StringBuilder();
        content.append("<div style=\"display: flex; gap: 20px; flex-wrap: wrap;\">");

        // Top Gainers
        content.append("<div style=\"flex: 1; min-width: 200px;\">");
        content.append("<h4 style=\"color: #2e7d32; margin-bottom: 10px;\">&#127942; Top Performers</h4>");
        for (HoldingsEntity h : topGainers) {
            content.append(String.format("<div style=\"padding: 8px; background: #e8f5e9; border-radius: 4px; margin-bottom: 5px;\"><strong>%s</strong> <span class=\"positive\">+%.2f%%</span></div>",
                    h.getTradingSymbol(), h.getPnlPercent()));
        }
        content.append("</div>");

        // Top Losers
        content.append("<div style=\"flex: 1; min-width: 200px;\">");
        content.append("<h4 style=\"color: #c62828; margin-bottom: 10px;\">&#128308; Underperformers</h4>");
        for (HoldingsEntity h : topLosers) {
            content.append(String.format("<div style=\"padding: 8px; background: #ffebee; border-radius: 4px; margin-bottom: 5px;\"><strong>%s</strong> <span class=\"negative\">%.2f%%</span></div>",
                    h.getTradingSymbol(), h.getPnlPercent()));
        }
        content.append("</div>");

        content.append("</div>");

        return templateService.section("&#127775;", "Weekly Highlights", content.toString());
    }

    private String buildScoreDistribution(List<HoldingsEntity> holdings) {
        long highScore = holdings.stream().filter(h -> h.getOverallScore() != null && h.getOverallScore() >= 70).count();
        long mediumScore = holdings.stream().filter(h -> h.getOverallScore() != null && h.getOverallScore() >= 40 && h.getOverallScore() < 70).count();
        long lowScore = holdings.stream().filter(h -> h.getOverallScore() != null && h.getOverallScore() < 40).count();

        StringBuilder content = new StringBuilder();
        content.append("<div class=\"summary-grid\">");
        content.append(templateService.summaryCard("Strong (70-100)", highScore + " stocks", "profit"));
        content.append(templateService.summaryCard("Moderate (40-69)", mediumScore + " stocks", "neutral"));
        content.append(templateService.summaryCard("Weak (0-39)", lowScore + " stocks", "loss"));
        content.append("</div>");

        return templateService.section("&#128202;", "Score Distribution", content.toString());
    }

    private String buildRecommendationsSummary(List<HoldingsEntity> holdings) {
        long strongBuy = holdings.stream().filter(h -> "STRONG_BUY".equals(h.getRecommendation())).count();
        long buy = holdings.stream().filter(h -> "BUY".equals(h.getRecommendation())).count();
        long hold = holdings.stream().filter(h -> "HOLD".equals(h.getRecommendation())).count();
        long sell = holdings.stream().filter(h -> "SELL".equals(h.getRecommendation())).count();
        long strongSell = holdings.stream().filter(h -> "STRONG_SELL".equals(h.getRecommendation())).count();

        StringBuilder content = new StringBuilder();
        content.append("<div class=\"summary-grid\">");
        content.append(templateService.summaryCard("Strong Buy", String.valueOf(strongBuy), "profit"));
        content.append(templateService.summaryCard("Buy", String.valueOf(buy), "profit"));
        content.append(templateService.summaryCard("Hold", String.valueOf(hold), ""));
        content.append(templateService.summaryCard("Sell", String.valueOf(sell), "loss"));
        content.append(templateService.summaryCard("Strong Sell", String.valueOf(strongSell), "loss"));
        content.append("</div>");

        return templateService.section("&#128161;", "Recommendations Summary", content.toString());
    }

    private String buildAiPortfolioAnalysis(List<HoldingsEntity> holdings) {
        if (!aiService.isAvailable()) {
            return "";
        }

        try {
            StringBuilder context = new StringBuilder();
            context.append("Portfolio holdings analysis for Indian stock market:\n\n");

            // Summarize each holding
            for (HoldingsEntity h : holdings) {
                context.append(String.format("%s: Price=%.2f, P&L=%.1f%%, Trend=%s, Rec=%s, Score=%s\n",
                        h.getSymbol(),
                        h.getCurrentPrice(),
                        h.getPnlPercent(),
                        h.getTrendDirection() != null ? h.getTrendDirection() : "N/A",
                        h.getRecommendation() != null ? h.getRecommendation() : "HOLD",
                        h.getOverallScore() != null ? h.getOverallScore().toString() : "N/A"));
            }

            // Portfolio-level stats
            Double totalPnl = holdingsRepository.getTotalPnL();
            long bullish = holdings.stream().filter(h -> "BULLISH".equals(h.getTrendDirection())).count();
            long bearish = holdings.stream().filter(h -> "BEARISH".equals(h.getTrendDirection())).count();

            context.append(String.format("\nPortfolio: %d holdings, Total P&L: %.0f, Bullish: %d, Bearish: %d\n",
                    holdings.size(), totalPnl != null ? totalPnl : 0.0, bullish, bearish));
            context.append("\nAnalyze this portfolio. Which holdings need attention? Any rebalancing suggestions? What are the key risks?");

            String aiInsight = aiService.analyze(
                "You are a portfolio advisor for Indian equities. Analyze the holdings and provide: " +
                "1) Overall portfolio health assessment, 2) Top 3 holdings needing attention (with reasons), " +
                "3) Key risk to watch. Be concise, 5-7 bullet points max. Use plain text, no markdown.",
                context.toString()
            );

            if (aiInsight.isEmpty()) {
                return "";
            }

            StringBuilder body = new StringBuilder();
            body.append("<div style=\"background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 2px; border-radius: 10px; margin-bottom: 15px;\">");
            body.append("<div style=\"background: #fff; border-radius: 8px; padding: 15px;\">");
            body.append("<div style=\"font-size: 11px; color: #999; margin-bottom: 8px;\">Powered by AI Analysis</div>");

            String[] lines = aiInsight.split("\n");
            body.append("<ul style=\"margin: 0; padding-left: 20px; color: #333;\">");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                trimmed = trimmed.replaceFirst("^[-*•]\\s*", "").replaceFirst("^\\d+[.):]\\s*", "");
                if (!trimmed.isEmpty()) {
                    body.append("<li style=\"margin-bottom: 6px;\">").append(trimmed).append("</li>");
                }
            }
            body.append("</ul>");
            body.append("</div></div>");

            return templateService.section("&#129302;", "AI Portfolio Analysis", body.toString());
        } catch (Exception e) {
            log.warn("Holdings report: AI portfolio analysis failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Flag any holding whose Financial Quality verdict is HIGH_RISK or WEAK.
     * Beginner-friendly per SPEC §21 — opens with a plain-English "what this means" box
     * and glosses each metric on first mention.
     */
    private String buildFinancialQualityAttention(List<HoldingsEntity> holdings) {
        List<String> rows = new ArrayList<>();
        for (HoldingsEntity h : holdings) {
            if (h.getSymbol() == null) continue;
            try {
                String tradingSymbol = h.getSymbol().contains(":")
                        ? h.getSymbol().substring(h.getSymbol().indexOf(":") + 1)
                        : h.getSymbol();
                NseDataService.FinancialQualityData fq = nseDataService.analyzeFinancialQuality(tradingSymbol);
                if (fq == null || fq.getQualityVerdict() == null) continue;
                if (!"HIGH_RISK".equals(fq.getQualityVerdict()) && !"WEAK".equals(fq.getQualityVerdict())) continue;

                String flags = (fq.getRedFlags() != null && !fq.getRedFlags().isEmpty())
                        ? String.join("; ", fq.getRedFlags())
                        : "No specific red flag logged";
                String verdictColor = "HIGH_RISK".equals(fq.getQualityVerdict()) ? "#c0392b" : "#d68910";
                rows.add(String.format(
                        "<tr>" +
                        "<td style='padding:8px;border:1px solid #eee;'><strong>%s</strong></td>" +
                        "<td style='padding:8px;border:1px solid #eee;color:%s;'><strong>%s</strong></td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%d/100</td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "</tr>",
                        h.getSymbol(),
                        verdictColor,
                        humanizeVerdict(fq.getQualityVerdict()),
                        fq.getQualityScore() == null ? 0 : fq.getQualityScore(),
                        fq.getInterestCoverageLatest() != null
                                ? String.format("%.2fx", fq.getInterestCoverageLatest())
                                : "n/a",
                        flags));
            } catch (Exception e) {
                log.debug("Financial-quality attention: skipped {}: {}", h.getSymbol(), e.getMessage());
            }
        }

        if (rows.isEmpty()) {
            return ""; // no attention items — stay silent rather than clutter the report
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<h3 style='margin-top:20px;'>&#9888;&#65039; Balance-Sheet Attention Items</h3>");
        sb.append("""
                <div style='background:#fff6e0;border-left:4px solid #f5a623;padding:12px 14px;margin-bottom:10px;border-radius:4px;'>
                  <strong>💡 What this means</strong><br/>
                  These are stocks <em>you already own</em> where the balance sheet or cash flow shows warning signs
                  — rising debt, weak interest coverage (the ratio of operating profit to interest owed),
                  poor cash conversion, or high promoter pledge (promoters borrowing against their own shares).
                  A stock can still rise with fragile finances, but long-term holders should re-underwrite the thesis here.
                </div>
                """);
        sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
        sb.append("<thead><tr style='background:#f2f4f8;text-align:left;'>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Holding</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Verdict</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Quality score</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Interest coverage</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Red flags</th>");
        sb.append("</tr></thead><tbody>");
        for (String row : rows) sb.append(row);
        sb.append("</tbody></table>");
        return sb.toString();
    }

    /**
     * Forensic red flags on owned holdings (SPEC §32.4, F5).
     *
     * <p>Distinct from Balance-Sheet Attention Items above: that section reads one year's
     * ratios, this one reads multi-year patterns that a single year cannot show — steady
     * share-count dilution, receivables outrunning sales, profit that never becomes cash
     * across a cycle, and auditor problems.
     *
     * <p>Two deliberate choices. Announcements <b>are</b> scanned here (unlike in the
     * screening loop) because a portfolio is dozens of stocks, not hundreds, and an auditor
     * resignation on something you own is worth a network call. And the section prints the
     * <b>coverage line</b> even when nothing fires, because "no flags" on a stock with no
     * imported history means nothing was checked — silence would read as a clean bill.
     */
    private String buildForensicFlagsSection(List<HoldingsEntity> holdings) {
        List<String> rows = new ArrayList<>();
        int checked = 0;
        int noHistory = 0;

        for (HoldingsEntity h : holdings) {
            if (h.getSymbol() == null) continue;
            try {
                if (fundamentalsHistoryService.yearsAvailable(h.getSymbol()) < 3) {
                    noHistory++;
                    continue;
                }
                checked++;
                var fr = forensicScreenService.screen(h.getSymbol(), true);
                if (fr.isClean()) continue;

                for (var flag : fr.getFlags()) {
                    String colour = switch (flag.getSeverity()) {
                        case "HIGH" -> "#c0392b";
                        case "MEDIUM" -> "#d68910";
                        default -> "#5d6d7e";
                    };
                    rows.add(String.format(
                            "<tr>"
                            + "<td style='padding:8px;border:1px solid #eee;'><strong>%s</strong></td>"
                            + "<td style='padding:8px;border:1px solid #eee;color:%s;'><strong>%s</strong></td>"
                            + "<td style='padding:8px;border:1px solid #eee;'>%s</td>"
                            + "</tr>",
                            h.getSymbol(), colour, flag.getSeverity(), flag.getMessage()));
                }
            } catch (Exception e) {
                log.debug("Forensic screen: skipped {}: {}", h.getSymbol(), e.getMessage());
            }
        }

        if (rows.isEmpty() && noHistory == 0) {
            return ""; // everything was checked and everything was clean — stay silent
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<h3 style='margin-top:20px;'>&#128269; Forensic Red Flags</h3>");
        sb.append("""
                <div style='background:#fff6e0;border-left:4px solid #f5a623;padding:12px 14px;margin-bottom:10px;border-radius:4px;'>
                  <strong>💡 What this means</strong><br/>
                  These checks look for patterns that only show up over several years, which the usual
                  one-year ratios cannot see: the share count creeping up (your slice of the company
                  shrinking), money owed by customers growing faster than sales (revenue booked but not
                  collected), reported profit that never turns into actual cash, and problems with the
                  company's auditor. <em>None of these proves anything is wrong</em> — each is a reason
                  to read the annual report before adding more money.
                </div>
                """);

        if (!rows.isEmpty()) {
            sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
            sb.append("<thead><tr style='background:#f2f4f8;text-align:left;'>");
            sb.append("<th style='padding:8px;border:1px solid #ddd;'>Holding</th>");
            sb.append("<th style='padding:8px;border:1px solid #ddd;'>Severity</th>");
            sb.append("<th style='padding:8px;border:1px solid #ddd;'>What was found</th>");
            sb.append("</tr></thead><tbody>");
            for (String row : rows) sb.append(row);
            sb.append("</tbody></table>");
        } else {
            sb.append("<p style='font-size:13px;color:#27ae60;margin:6px 0;'>")
              .append("No red flags on the holdings that could be checked.</p>");
        }

        // The coverage line is the honest part: without it, a portfolio where nothing could
        // be checked looks identical to one that was checked and came back clean.
        sb.append(String.format(
                "<p style='font-size:12px;color:#7f8c8d;margin-top:8px;'>"
                + "Checked %d holding(s) with enough financial history. "
                + "<strong>%d holding(s) could not be checked at all</strong> because no multi-year history "
                + "has been imported for them — that is not a clean bill of health, it is a blank one. "
                + "Import history via <code>POST /api/fundamentals/import-history?symbol=…</code> to widen coverage."
                + "</p>", checked, noHistory));
        return sb.toString();
    }

    /**
     * Fundamental "wealth signals" for owned holdings (SPEC §12.7) — the quality
     * traits that actually drive long-term compounding: gross-margin trend (pricing
     * power), earnings-growth consistency (steady beats lumpy), and delivery %
     * (genuine accumulation vs. intraday churn). Informational, not an alert: shows
     * every holding where at least one signal is computable, strong signals in green,
     * weak ones in red, so the user can see which holdings are quietly getting better
     * or worse on fundamentals — independent of price.
     */
    private String buildWealthSignalsSection(List<HoldingsEntity> holdings) {
        List<String> rows = new ArrayList<>();
        for (HoldingsEntity h : holdings) {
            if (h.getSymbol() == null) continue;
            try {
                String tradingSymbol = h.getSymbol().contains(":")
                        ? h.getSymbol().substring(h.getSymbol().indexOf(":") + 1)
                        : h.getSymbol();
                NseDataService.WealthSignalsData ws = nseDataService.analyzeWealthSignals(tradingSymbol);
                if (ws == null) continue;
                boolean hasAny = !"NA".equals(ws.getGrossMarginVerdict())
                        || (ws.getConsistencyVerdict() != null && !"NA".equals(ws.getConsistencyVerdict()))
                        || (ws.getDeliveryVerdict() != null && !"NA".equals(ws.getDeliveryVerdict()));
                if (!hasAny) continue;

                rows.add(String.format(
                        "<tr>" +
                        "<td style='padding:8px;border:1px solid #eee;'><strong>%s</strong></td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "</tr>",
                        h.getSymbol(),
                        wealthBadge(ws.getGrossMarginVerdict(), ws.getGrossMarginTrend(), "pp"),
                        consistencyBadge(ws.getConsistencyVerdict(), ws.getEarningsConsistencyScore()),
                        wealthBadge(ws.getDeliveryVerdict(), ws.getDeliveryPercent(), "%")));
            } catch (Exception e) {
                log.debug("Wealth signals: skipped {}: {}", h.getSymbol(), e.getMessage());
            }
        }

        if (rows.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<h3 style='margin-top:20px;'>&#127793; Fundamental Wealth Signals</h3>");
        sb.append("""
                <div style='background:#eafaf1;border-left:4px solid #27ae60;padding:12px 14px;margin-bottom:10px;border-radius:4px;'>
                  <strong>💡 What this means</strong><br/>
                  These three signals tend to separate genuine long-term wealth creators from stocks that
                  just moved on price:
                  <ul style='margin:6px 0 0 18px;padding:0;'>
                    <li><strong>Gross margin trend</strong> — profit kept per ₹100 of sales, before overheads.
                        <em>Expanding</em> usually means pricing power (a moat); <em>contracting</em> means competition
                        or rising input costs.</li>
                    <li><strong>Earnings consistency</strong> — a company that grows steadily compounds far more
                        reliably than one that swings up and down to the same average.</li>
                    <li><strong>Delivery %</strong> — the share of traded volume actually taken into demat accounts
                        (real investors) vs. same-day trading churn. Higher = stronger hands accumulating.</li>
                  </ul>
                  Green = strong, red = weak, grey = not available (banks/financials have no gross-margin line).
                </div>
                """);
        sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
        sb.append("<thead><tr style='background:#f2f4f8;text-align:left;'>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Holding</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Gross margin trend</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Earnings consistency</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Delivery %</th>");
        sb.append("</tr></thead><tbody>");
        for (String row : rows) sb.append(row);
        sb.append("</tbody></table>");
        return sb.toString();
    }

    /** Colored badge for a wealth-signal verdict + its numeric value. */
    private String wealthBadge(String verdict, Double value, String unit) {
        if (verdict == null || "NA".equals(verdict)) {
            return "<span style='color:#999;'>n/a</span>";
        }
        String color;
        String label;
        switch (verdict) {
            case "EXPANDING"    -> { color = "#27ae60"; label = "Expanding"; }
            case "CONTRACTING"  -> { color = "#c0392b"; label = "Contracting"; }
            case "STABLE"       -> { color = "#7f8c8d"; label = "Stable"; }
            case "STRONG_HANDS" -> { color = "#27ae60"; label = "Strong hands"; }
            case "MODERATE"     -> { color = "#7f8c8d"; label = "Moderate"; }
            case "SPECULATIVE"  -> { color = "#c0392b"; label = "Speculative"; }
            default             -> { color = "#7f8c8d"; label = verdict; }
        }
        String valStr = value != null
                ? String.format(" (%s%.1f%s)", value > 0 && "pp".equals(unit) ? "+" : "", value, "pp".equals(unit) ? " pp" : unit)
                : "";
        return String.format("<span style='color:%s;font-weight:600;'>%s</span>%s", color, label, valStr);
    }

    /** Colored badge for the earnings-consistency verdict + 0-100 score. */
    private String consistencyBadge(String verdict, Integer score) {
        if (verdict == null || "NA".equals(verdict)) {
            return "<span style='color:#999;'>n/a</span>";
        }
        String color = switch (verdict) {
            case "VERY_CONSISTENT" -> "#27ae60";
            case "CONSISTENT"      -> "#2ecc71";
            case "VARIABLE"        -> "#d68910";
            case "ERRATIC"         -> "#c0392b";
            default                -> "#7f8c8d";
        };
        String label = verdict.charAt(0) + verdict.substring(1).toLowerCase().replace('_', ' ');
        String valStr = score != null ? String.format(" (%d/100)", score) : "";
        return String.format("<span style='color:%s;font-weight:600;'>%s</span>%s", color, label, valStr);
    }

    /**
     * Capital-efficiency table for holdings (SPEC §12.8) — the balance-sheet ratios that
     * identify genuine long-term wealth creators: ROCE, ROE, Debt-to-Equity, and real
     * cash conversion (operating cash flow ÷ profit). Sourced from each company's latest
     * annual Ind-AS filing on NSE. Shows every holding where the annual statement parsed.
     */
    private String buildCapitalEfficiencySection(List<HoldingsEntity> holdings) {
        List<String> rows = new ArrayList<>();
        String fyShown = null;
        for (HoldingsEntity h : holdings) {
            if (h.getSymbol() == null) continue;
            try {
                String tradingSymbol = h.getSymbol().contains(":")
                        ? h.getSymbol().substring(h.getSymbol().indexOf(":") + 1)
                        : h.getSymbol();
                NseDataService.CapitalEfficiencyData ce =
                        nseDataService.analyzeCapitalEfficiency(tradingSymbol, h.getIndustry());
                if (ce == null || !ce.isApplicable()) continue;
                if (fyShown == null) fyShown = ce.getFinancialYear();

                rows.add(String.format(
                        "<tr>" +
                        "<td style='padding:8px;border:1px solid #eee;'><strong>%s</strong></td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "</tr>",
                        h.getSymbol(),
                        capEffMetric(ce.getRocePercent(), "NA_FINANCIAL".equals(ce.getRoceVerdict()), "%", 15, 12),
                        capEffMetric(ce.getRoePercent(), false, "%", 15, 10),
                        capEffMetric(ce.getRoaPercent(), false, "%", 1.5, 1.0),
                        capEffLeverage(ce.getDebtToEquity(), "NA_FINANCIAL".equals(ce.getLeverageVerdict())),
                        capEffMetric(ce.getCashConversionRatio(), false, "x", 0.8, 0.5),
                        capEffOverallBadge(ce.getOverallVerdict()),
                        capexBadge(capexCycleService.analyze(h.getSymbol(), h.getIndustry()))));
            } catch (Exception e) {
                log.debug("Capital efficiency: skipped {}: {}", h.getSymbol(), e.getMessage());
            }
        }

        if (rows.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<h3 style='margin-top:20px;'>&#128176; Capital Efficiency (Annual Balance Sheet)</h3>");
        sb.append("""
                <div style='background:#eef3fb;border-left:4px solid #2c5fb3;padding:12px 14px;margin-bottom:10px;border-radius:4px;'>
                  <strong>💡 What this means</strong><br/>
                  These four numbers, from each company's latest <em>annual</em> audited accounts, are the strongest
                  long-term wealth signals — they show whether the business actually makes good money on the capital
                  it uses:
                  <ul style='margin:6px 0 0 18px;padding:0;'>
                    <li><strong>ROCE</strong> (Return on Capital Employed) — profit earned per ₹100 of total capital
                        in the business. Above ~15% is good; this is what lets a company reinvest and compound.</li>
                    <li><strong>ROE</strong> (Return on Equity) — profit per ₹100 of shareholders' money. Above ~15% is good.</li>
                    <li><strong>ROA</strong> (Return on Assets) — profit per ₹100 of total assets. Most useful for
                        <em>banks</em>, where above ~1.5% is good (banks show "n/c" on ROCE/D-E instead).</li>
                    <li><strong>D/E</strong> (Debt-to-Equity) — how much the company owes vs. owns. Below 0.3 is very safe;
                        above 2 is risky.</li>
                    <li><strong>Cash conversion</strong> — how much of reported profit turns into actual cash (operating
                        cash flow ÷ profit). Above 0.8× means the profits are real cash, not just accounting entries.</li>
                    <li><strong>Building?</strong> — whether the company is spending on new plants and capacity right
                        now. "Expanding" means a large build is under way and still growing; that capacity usually
                        starts earning 1–2 years later, so it is one of the few signs that shows up <em>before</em>
                        the profits do. "Harvesting" means it is taking cash out of what it already owns rather than
                        building — fine for a mature business, a warning if growth has already stalled.</li>
                  </ul>
                  Green = strong, red = weak. "n/c" on ROCE/D-E/Building means the stock is a bank/financial where these aren't comparable
                  (ROE and ROA are used instead). "n/a" means the company's filing did not report the figure — that is
                  a gap in the data, not a zero.
                </div>
                """);
        sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
        sb.append("<thead><tr style='background:#f2f4f8;text-align:left;'>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Holding</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>ROCE</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>ROE</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>ROA</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>D/E</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Cash conv.</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Verdict</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Building?</th>");
        sb.append("</tr></thead><tbody>");
        for (String row : rows) sb.append(row);
        sb.append("</tbody></table>");
        if (fyShown != null) {
            sb.append(String.format("<p style='font-size:11px;color:#888;margin-top:6px;'>Source: latest annual Ind-AS filing (%s) on NSE. Updated yearly.</p>", fyShown));
        }
        return sb.toString();
    }

    /**
     * Capex-cycle cell (SPEC §31) — is this company building new capacity right now?
     *
     * <p>Sits in the capital-efficiency table because it answers the natural next question
     * after "does this business earn well on its capital": is it putting more capital in.
     * Renders "n/c" for banks (plant tells you nothing about a lender) and "n/a" when the
     * filing does not tag it — neither is a zero.
     */
    private String capexBadge(NseDataService.CapexCycleData cx) {
        if (cx == null) return "<span style='color:#999;'>n/a</span>";
        if ("NA_FINANCIAL".equals(cx.getVerdict())) return "<span style='color:#999;'>n/c</span>";
        if (!cx.isApplicable()) return "<span style='color:#999;'>n/a</span>";
        return switch (cx.getVerdict()) {
            case "EXPANSION_UNDERWAY" -> "<span style='color:#27ae60;font-weight:600;'>Expanding</span>";
            case "INVESTING" -> "<span style='color:#27ae60;'>Investing</span>";
            case "HARVESTING" -> "<span style='color:#d68910;'>Harvesting</span>";
            default -> "<span style='color:#7f8c8d;'>Steady</span>";
        };
    }

    /** Green/red value cell for a ratio where higher is better. */
    private String capEffMetric(Double value, boolean notComparable, String unit, double good, double weak) {
        if (notComparable) return "<span style='color:#999;'>n/c</span>";
        if (value == null) return "<span style='color:#999;'>n/a</span>";
        String color = value >= good ? "#27ae60" : value < weak ? "#c0392b" : "#7f8c8d";
        String v = "x".equals(unit) ? String.format("%.2fx", value) : String.format("%.1f%%", value);
        return String.format("<span style='color:%s;font-weight:600;'>%s</span>", color, v);
    }

    /** Debt-to-Equity cell — lower is better. */
    private String capEffLeverage(Double de, boolean notComparable) {
        if (notComparable) return "<span style='color:#999;'>n/c</span>";
        if (de == null) return "<span style='color:#999;'>n/a</span>";
        String color = de <= 0.3 ? "#27ae60" : de > 2.0 ? "#c0392b" : "#7f8c8d";
        return String.format("<span style='color:%s;font-weight:600;'>%.2f</span>", color, de);
    }

    private String capEffOverallBadge(String verdict) {
        if (verdict == null || "NA".equals(verdict)) return "<span style='color:#999;'>n/a</span>";
        String color = switch (verdict) {
            case "HIGH_QUALITY_COMPOUNDER" -> "#1e8449";
            case "SOLID"   -> "#27ae60";
            case "AVERAGE" -> "#7f8c8d";
            case "WEAK"    -> "#d68910";
            case "POOR"    -> "#c0392b";
            default        -> "#7f8c8d";
        };
        String label = switch (verdict) {
            case "HIGH_QUALITY_COMPOUNDER" -> "High-quality compounder";
            default -> verdict.charAt(0) + verdict.substring(1).toLowerCase().replace('_', ' ');
        };
        return String.format("<span style='color:%s;font-weight:600;'>%s</span>", color, label);
    }

    /**
     * Flag any holding whose latest-quarter earnings broke the trend downward.
     * Uses {@link NseDataService#analyzeEarningsTrendBreak} — a proxy for analyst
     * surprise computed from the 3-quarter linear trend (SPEC §24). Not a
     * substitute for real consensus data; explain that in the intro box.
     */
    private String buildTrendBreakAttention(List<HoldingsEntity> holdings) {
        List<String> rows = new ArrayList<>();
        for (HoldingsEntity h : holdings) {
            if (h.getSymbol() == null) continue;
            try {
                String tradingSymbol = h.getSymbol().contains(":")
                        ? h.getSymbol().substring(h.getSymbol().indexOf(":") + 1)
                        : h.getSymbol();
                NseDataService.EarningsTrendBreakData tb = nseDataService.analyzeEarningsTrendBreak(tradingSymbol);
                if (tb == null || tb.getVerdict() == null) continue;
                if (!"NEGATIVE_BREAK".equals(tb.getVerdict()) && !"BIG_NEGATIVE_BREAK".equals(tb.getVerdict())) continue;

                String verdictColor = "BIG_NEGATIVE_BREAK".equals(tb.getVerdict()) ? "#c0392b" : "#d68910";
                String profitDelta = tb.getProfitSurprisePercent() != null
                        ? String.format("%+.1f%%", tb.getProfitSurprisePercent())
                        : "n/a";
                String revenueDelta = tb.getRevenueSurprisePercent() != null
                        ? String.format("%+.1f%%", tb.getRevenueSurprisePercent())
                        : "n/a";
                rows.add(String.format(
                        "<tr>" +
                        "<td style='padding:8px;border:1px solid #eee;'><strong>%s</strong></td>" +
                        "<td style='padding:8px;border:1px solid #eee;color:%s;'><strong>%s</strong></td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "<td style='padding:8px;border:1px solid #eee;'>%s</td>" +
                        "</tr>",
                        h.getSymbol(),
                        verdictColor,
                        humanizeTrendBreak(tb.getVerdict()),
                        tb.getLatestPeriod() != null ? tb.getLatestPeriod() : "n/a",
                        revenueDelta,
                        profitDelta));
            } catch (Exception e) {
                log.debug("Trend-break attention: skipped {}: {}", h.getSymbol(), e.getMessage());
            }
        }

        if (rows.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<h3 style='margin-top:20px;'>&#128200; Earnings Trend-Break Alerts</h3>");
        sb.append("""
                <div style='background:#fff6e0;border-left:4px solid #f5a623;padding:12px 14px;margin-bottom:10px;border-radius:4px;'>
                  <strong>💡 What this means</strong><br/>
                  These holdings reported quarterly numbers <em>meaningfully below</em> what their own recent 3-quarter trend
                  would have projected. The "surprise %" shows how far actual revenue/profit missed a simple linear extrapolation.
                  This is <strong>not</strong> a comparison to paid analyst consensus (which we don't have access to) —
                  it's a trend-deviation proxy. A negative break can be a one-off (lumpy business, raw-material spike)
                  or an early sign of thesis breakdown. Re-check the story before adding.
                </div>
                """);
        sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
        sb.append("<thead><tr style='background:#f2f4f8;text-align:left;'>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Holding</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Verdict</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Quarter</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Revenue vs trend</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Profit vs trend</th>");
        sb.append("</tr></thead><tbody>");
        for (String row : rows) sb.append(row);
        sb.append("</tbody></table>");
        return sb.toString();
    }

    /**
     * "Thesis Drift Alerts" — score-decay on owned holdings (SPEC §6). Shows
     * every holding whose Multibagger composite has dropped over the last 30
     * or 60 days, worst-first. Holdings whose score is still stable are
     * summarised in a single line at the bottom so the section is always
     * shown (it's an affirmative signal, not just an alert).
     */

    /**
     * Core Holdings (SPEC §35) — the answer to "which stock should I never sell".
     *
     * <p>Reads persisted classification rows only; it never classifies. Classification costs Kite
     * calls and happens once, in the 10:30 job. A report that recomputed would make sending an
     * email expensive and could show a different answer than the one on record.
     */
    private String buildCoreHoldingsSection(List<HoldingsEntity> holdings) {
        if (!coreConfig.isEnabled()) return "";

        List<com.example.trading.portfolio.core.CoreDto.CoreHoldingView> views;
        try {
            views = coreClassificationService.latestClassifications();
        } catch (Exception e) {
            log.warn("Core holdings section skipped - classification could not be read: {}", e.getMessage());
            return "";
        }
        if (views.isEmpty()) return "";

        Map<String, HoldingsEntity> bySymbol = new HashMap<>();
        for (HoldingsEntity h : holdings) bySymbol.put(h.getSymbol(), h);

        List<com.example.trading.portfolio.core.CoreDto.CoreHoldingView> core = new ArrayList<>();
        List<com.example.trading.portfolio.core.CoreDto.CoreHoldingView> unclassified = new ArrayList<>();
        // Holdings whose gates all passed today but whose promotion is still accumulating its
        // weekly confirmations. They are NOT core yet - the overlay must not protect them - but
        // saying "nothing clears the gates" while one of them does is a false statement about the
        // portfolio, and the reader has no other way to learn a promotion is under way (B-059).
        List<com.example.trading.portfolio.core.CoreDto.CoreHoldingView> pendingPromotion = new ArrayList<>();
        for (com.example.trading.portfolio.core.CoreDto.CoreHoldingView v : views) {
            if (!bySymbol.containsKey(v.symbol())) continue;   // no longer held
            if (v.effectiveTier().isProtected()) {
                core.add(v);
            } else if (v.tier().isProtected()) {
                // Reported as awaiting confirmation, and NOT also as unmeasured: a stock that
                // passed every gate today is the opposite of one we could not judge, and listing
                // it under both headings contradicts itself in the same section.
                pendingPromotion.add(v);
            } else if (v.effectiveTier() == com.example.trading.portfolio.core.CoreDto.CoreTier.UNCLASSIFIED) {
                unclassified.add(v);
            }
        }

        StringBuilder content = new StringBuilder();
        content.append(templateService.section("&#127959;", "Core Holdings", ""));

        content.append("<div style=\"background:#e8f5e9;border-left:4px solid #2e7d32;padding:12px 16px;")
               .append("margin-bottom:16px;border-radius:4px;font-size:13px;\">")
               .append("<strong>What this means.</strong> A <em>core holding</em> is one whose ")
               .append("<em>business</em> has passed every quality check we can run on it - how well it ")
               .append("earns on the money invested in it, how solid its balance sheet is, how steady ")
               .append("its earnings are, whether its accounts throw up red flags, and whether the ")
               .append("reason you bought it still holds. Price is deliberately not one of the checks: ")
               .append("a good business having a bad six months is the exact situation this tier is for. ")
               .append("<strong>Nothing here is executed and nothing is sold automatically.</strong> ")
               .append("A stock we could not measure is listed as <em>not measured</em> with the reason - ")
               .append("which is not the same as a poor verdict.")
               .append("</div>");

        if (core.isEmpty()) {
            content.append("<div style=\"padding:10px 14px;background:#fafafa;border-radius:4px;")
                   .append("font-size:13px;color:#555;\">")
                   .append("No holding is a core holding yet. ")
                   .append("That is a finding, not an error - see the not-measured list below for the ")
                   .append("ones we could not judge.</div>");
        } else {
            content.append(templateService.tableStart(
                    "Symbol", "Tier", "Durability", "Held", "Key strength", "Watch"));
            for (com.example.trading.portfolio.core.CoreDto.CoreHoldingView v : core) {
                HoldingsEntity h = bySymbol.get(v.symbol());
                content.append(templateService.tableRow(
                        "<strong>" + truncate(v.tradingSymbol() != null ? v.tradingSymbol() : v.symbol(), 12) + "</strong>",
                        coreTierBadge(v.effectiveTier()),
                        durabilityCell(v),
                        yearsHeld(h),
                        keyStrength(v),
                        watchNote(v)));
            }
            content.append(templateService.tableEnd());
        }

        // Tier movement leads the section when something was demoted - that is the news.
        try {
            List<String> changes = coreClassificationService.tierChangesSince(7);
            if (!changes.isEmpty()) {
                content.append("<div style=\"margin-top:14px;padding:10px 14px;background:#fff3e0;")
                       .append("border-left:4px solid #e65100;border-radius:4px;font-size:13px;\">")
                       .append("<strong>Tier changes in the last week:</strong> ")
                       .append(String.join("; ", changes))
                       .append(". A demotion means a <em>fundamental</em> fact changed - a red flag, a ")
                       .append("quality downgrade, or a broken thesis. Price moves never demote a stock.")
                       .append("</div>");
            }
        } catch (Exception e) {
            log.debug("Core tier changes unavailable: {}", e.getMessage());
        }

        if (!pendingPromotion.isEmpty()) {
            StringBuilder pend = new StringBuilder();
            for (com.example.trading.portfolio.core.CoreDto.CoreHoldingView v : pendingPromotion) {
                if (pend.length() > 0) pend.append("; ");
                pend.append("<strong>").append(v.tradingSymbol()).append("</strong>");
                if (v.pendingChange() != null && !v.pendingChange().isBlank()) {
                    pend.append(" (").append(v.pendingChange()).append(")");
                }
            }
            content.append("<div style=\"padding:10px 14px;background:#fff8e1;")
                   .append("border-left:4px solid #f9a825;border-radius:4px;font-size:13px;")
                   .append("margin-bottom:12px;\">")
                   .append("<strong>Passing every gate, awaiting confirmation (")
                   .append(pendingPromotion.size()).append("):</strong> ").append(pend)
                   .append(". These cleared every quality check we could run <em>today</em>. ")
                   .append("Before a stock is treated as core we require the same result on two ")
                   .append("separate weekly readings, so that one odd data day cannot change how ")
                   .append("its alerts behave. Until then it is handled exactly like any other ")
                   .append("holding.</div>");
        }

        if (!unclassified.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (com.example.trading.portfolio.core.CoreDto.CoreHoldingView v : unclassified) {
                if (names.length() > 0) names.append("; ");
                names.append(v.tradingSymbol() != null ? v.tradingSymbol() : v.symbol());
                List<String> missing = v.missingInputs();
                if (missing != null && !missing.isEmpty()) {
                    names.append(" (").append(truncate(missing.get(0), 90)).append(")");
                }
            }
            content.append("<div style=\"margin-top:14px;padding:10px 14px;background:#f5f5f5;")
                   .append("border-radius:4px;font-size:12px;color:#555;\">")
                   .append("<strong>Not enough data to judge (").append(unclassified.size())
                   .append("):</strong> ").append(names)
                   .append(".<br>These are absent from the core list, and will be absent from any ")
                   .append("future profit-booking list, on purpose - we never suggest selling ")
                   .append("something we could not measure. The number falls as annual history is ")
                   .append("imported and as these stocks enter the weekly screening.")
                   .append("</div>");
        }

        if (!coreConfig.isSuppressTechnicalExits()) {
            content.append("<div style=\"margin-top:14px;padding:10px 14px;background:#e8eaf6;")
                   .append("border-left:4px solid #3949ab;border-radius:4px;font-size:12px;color:#283593;\">")
                   .append("<strong>Observation mode.</strong> Being core currently changes only what ")
                   .append("this email <em>says</em>, never which alerts are sent. Every technical exit ")
                   .append("alert still fires and is still delivered. The exit-alert email records ")
                   .append("which of them landed on a core holding, and after a quarter of that record ")
                   .append("we decide whether withholding them would have helped or hurt. Switching a ")
                   .append("safety check off is not something to do on a hunch.")
                   .append("</div>");
        }
        return content.toString();
    }

    private String coreTierBadge(com.example.trading.portfolio.core.CoreDto.CoreTier tier) {
        return switch (tier) {
            case CORE -> templateService.badge("CORE", "success");
            case CORE_WATCH -> templateService.badge("CORE - WATCH", "warning");
            case SATELLITE -> templateService.badge("SATELLITE", "info");
            case UNCLASSIFIED -> templateService.badge("NOT MEASURED", "info");
        };
    }

    /**
     * Durability, or an explicit "not measured" marker. Never 0 and never blank: a missing score
     * rendered as a number is indistinguishable from a genuinely bad one (Gotcha 21).
     */
    private String durabilityCell(com.example.trading.portfolio.core.CoreDto.CoreHoldingView v) {
        if (v.durabilityScore() == null) {
            return "<span style=\"color:#999;font-style:italic;\">not measured</span>";
        }
        String coverage = v.durabilityCoverage() == null ? "" : v.durabilityCoverage();
        int measured = 0;
        for (String part : coverage.split(" ")) {
            try {
                measured = Integer.parseInt(part);
                break;
            } catch (NumberFormatException ignored) {
                // keep scanning: the coverage sentence opens with "N of 5 components measured"
            }
        }
        return "<strong>" + v.durabilityScore() + "</strong>"
                + (measured > 0 ? " <span style=\"color:#888;font-size:11px;\">" + measured + "/5</span>" : "");
    }

    private String yearsHeld(HoldingsEntity h) {
        if (h == null || h.getPurchaseDate() == null) return "&mdash;";
        long days = java.time.temporal.ChronoUnit.DAYS.between(
                h.getPurchaseDate().toLocalDate(), LocalDate.now());
        if (days < 365) return days + "d";
        return String.format("%.1fy", days / 365.25);
    }

    /** The gate doing the most work, in the reader's language. */
    private String keyStrength(com.example.trading.portfolio.core.CoreDto.CoreHoldingView v) {
        for (com.example.trading.portfolio.core.CoreDto.Gate g : v.gates()) {
            if (g.status() == com.example.trading.portfolio.core.CoreDto.GateStatus.PASS) {
                return truncate(g.reason(), 60);
            }
        }
        return "&mdash;";
    }

    private String watchNote(com.example.trading.portfolio.core.CoreDto.CoreHoldingView v) {
        if (v.pendingChange() != null && !v.pendingChange().isBlank()) {
            return "<span style=\"color:#e65100;\">" + truncate(v.pendingChange(), 60) + "</span>";
        }
        if (v.overrideApplied() != null) {
            return "<span style=\"color:#6a1b9a;\">your override: " + v.overrideApplied() + "</span>";
        }
        List<String> soft = v.softSignals();
        if (soft == null || soft.isEmpty()) return "&mdash;";
        return truncate(String.join("; ", soft), 60);
    }

    /**
     * What recent macro events mean for the businesses in this portfolio (SPEC 48.10).
     *
     * <p>Returns empty when no event was recorded in the window - an empty fortnight is not news.
     * When events exist and none of them touches a holding it says exactly that, which is the
     * output that makes the section trustworthy the rest of the time.
     */
    private String buildMacroExposureSection(List<HoldingsEntity> holdings) {
        try {
            java.util.List<String> symbols = holdings.stream()
                    .map(HoldingsEntity::getSymbol).filter(java.util.Objects::nonNull).toList();
            var readings = macroExposureService.forSymbols(symbols);
            long live = macroExposureService.recentEvents(0).stream()
                    .filter(e -> !e.isDismissed()).count();
            return macroReportRenderer.portfolioSection(holdings, readings, (int) live,
                    macroExposureService.windowDays());
        } catch (Exception e) {
            // WARN and name what the blank will be mistaken for (B-054's rule): a missing section
            // reads as "no event affects your portfolio", which is a claim, not an absence.
            log.warn("Macro exposure section omitted ({}). The email will look as though no event "
                    + "touches the portfolio, which is not what was measured.", e.getMessage());
            return "";
        }
    }

    private String buildThesisDriftSection(List<HoldingsEntity> holdings) {
        List<HoldingsDecayService.DecayAlert> alerts;
        try {
            alerts = holdingsDecayService.detectDecay(holdings);
        } catch (Exception e) {
            log.warn("Thesis-drift section failed: {}", e.getMessage());
            return "";
        }
        if (alerts.isEmpty()) return "";

        long brokenCount = alerts.stream().filter(a -> a.getVerdict() == HoldingsDecayService.Verdict.BROKEN).count();
        long decayingCount = alerts.stream().filter(a -> a.getVerdict() == HoldingsDecayService.Verdict.DECAYING).count();
        long watchCount = alerts.stream().filter(a -> a.getVerdict() == HoldingsDecayService.Verdict.WATCH).count();
        long intactCount = alerts.stream().filter(a -> a.getVerdict() == HoldingsDecayService.Verdict.INTACT).count();
        long noDataCount = alerts.stream().filter(a ->
                a.getVerdict() == HoldingsDecayService.Verdict.NO_DATA
                || a.getVerdict() == HoldingsDecayService.Verdict.STALE).count();

        StringBuilder sb = new StringBuilder();
        sb.append("<h3 style='margin-top:20px;'>&#128269; Thesis Drift Alerts</h3>");
        sb.append("""
                <div style='background:#eef7ff;border-left:4px solid #2b7cff;padding:12px 14px;margin-bottom:10px;border-radius:4px;'>
                  <strong>💡 What this means</strong><br/>
                  For every stock you own, the app runs a daily <strong>Multibagger composite score</strong> (0–100)
                  that captures technicals, fundamentals, ownership, and sector tailwinds in a single number.
                  This section flags holdings whose score has <em>dropped</em> meaningfully in the last 30 or 60 days —
                  an early warning the investment thesis may be weakening, often before P&amp;L shows it.
                  A drop of 10+ points in 30 days is "Decaying"; 20+ points is "Broken".
                </div>
                """);

        sb.append(String.format("""
                <p style="font-size:13px;color:#555;">
                  <strong>Portfolio health:</strong>
                  %d broken, %d decaying, %d watch, %d intact, %d no-data / stale.
                </p>
                """, brokenCount, decayingCount, watchCount, intactCount, noDataCount));

        // Only show a table if there are any alerts worth surfacing
        boolean hasAlerts = (brokenCount + decayingCount + watchCount) > 0;
        if (!hasAlerts) {
            sb.append("<p style='color:#2e7d32;font-weight:600;font-size:13px;'>"
                    + "&#10003; No thesis drift detected — all tracked holdings' composite scores are stable.</p>");
            return sb.toString();
        }

        sb.append("<table style='width:100%;border-collapse:collapse;font-size:13px;'>");
        sb.append("<thead><tr style='background:#f2f4f8;text-align:left;'>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Holding</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Verdict</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Score now</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>&#916; 30d</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>&#916; 30d vs peers</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>&#916; 60d</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Grade</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>P&amp;L</th>");
        sb.append("<th style='padding:8px;border:1px solid #ddd;'>Why</th>");
        sb.append("</tr></thead><tbody>");

        for (HoldingsDecayService.DecayAlert a : alerts) {
            if (a.getVerdict() == HoldingsDecayService.Verdict.INTACT
                    || a.getVerdict() == HoldingsDecayService.Verdict.NO_DATA
                    || a.getVerdict() == HoldingsDecayService.Verdict.STALE) {
                continue;
            }
            String color = verdictColor(a.getVerdict());
            sb.append("<tr>")
              .append(td("<strong>" + a.getSymbol() + "</strong>"))
              .append(td("<strong style='color:" + color + ";'>" + humanizeDecayVerdict(a.getVerdict()) + "</strong>"))
              .append(td(a.getCurrentScore() != null ? a.getCurrentScore().toString() : "—"))
              .append(td(a.getDelta30d() != null ? String.format("%+d", a.getDelta30d()) : "—"))
              .append(td(a.getRelativeDelta30d() != null ? String.format("%+d", a.getRelativeDelta30d()) : "not measured"))
              .append(td(a.getDelta60d() != null ? String.format("%+d", a.getDelta60d()) : "—"))
              .append(td(formatGradeChange(a.getGradeAt30d(), a.getCurrentGrade())))
              .append(td(a.getPnlPercent() != null ? String.format("%+.1f%%", a.getPnlPercent()) : "—"))
              .append(td(a.getReason() != null ? a.getReason() : "—"))
              .append("</tr>");
        }
        sb.append("</tbody></table>");
        sb.append("<p style='font-size:12px;color:#6c757d;margin-top:8px;'>"
                + "<em>How to act:</em> Broken &amp; Decaying items deserve a thesis re-read — check the AI Deep Research "
                + "endpoint (<code>/api/research/{symbol}</code>) before adding more. Watch items warrant monitoring, not immediate action.</p>");

        return sb.toString();
    }

    private static String td(String content) {
        return "<td style='padding:8px;border:1px solid #eee;'>" + (content == null ? "—" : content) + "</td>";
    }

    private static String verdictColor(HoldingsDecayService.Verdict v) {
        if (v == null) return "#555";
        return switch (v) {
            case BROKEN -> "#c0392b";
            case DECAYING -> "#d68910";
            case WATCH -> "#b7950b";
            default -> "#555";
        };
    }

    private static String humanizeDecayVerdict(HoldingsDecayService.Verdict v) {
        if (v == null) return "—";
        return switch (v) {
            case INTACT -> "Intact";
            case WATCH -> "Watch";
            case DECAYING -> "Decaying";
            case BROKEN -> "Thesis Broken";
            case STALE -> "Stale Data";
            case NO_DATA -> "No Data";
        };
    }

    private static String formatGradeChange(String oldGrade, String newGrade) {
        if (oldGrade == null && newGrade == null) return "—";
        if (oldGrade == null) return newGrade;
        if (newGrade == null) return oldGrade;
        if (oldGrade.equals(newGrade)) return newGrade;
        return oldGrade + " &rarr; " + newGrade;
    }

    private static String humanizeTrendBreak(String verdict) {
        if (verdict == null) return "—";
        return switch (verdict) {
            case "BIG_POSITIVE_BREAK" -> "Big Positive Break";
            case "POSITIVE_BREAK" -> "Positive Break";
            case "IN_LINE" -> "In Line";
            case "NEGATIVE_BREAK" -> "Negative Break";
            case "BIG_NEGATIVE_BREAK" -> "Big Negative Break";
            default -> verdict;
        };
    }

    private static String humanizeVerdict(String verdict) {
        return switch (verdict) {
            case "HIGH_QUALITY" -> "High Quality";
            case "DECENT" -> "Decent";
            case "AVERAGE" -> "Average";
            case "WEAK" -> "Weak";
            case "HIGH_RISK" -> "High Risk";
            default -> verdict;
        };
    }

    private String buildPriceLevelGuide() {
        return templateService.infoBox(
            "&#128204; Price Level Guide",
            "<strong>Stop Loss:</strong> Exit if price falls below this level (based on 2x ATR below current price)",
            "<strong>Target 1:</strong> Book 50% profit at this level (2x ATR above current price)",
            "<strong>Target 2:</strong> Book remaining profit at this level (4x ATR above current price)",
            "<strong>Buy Below:</strong> Ideal accumulation price based on EMA support levels",
            "<strong>Sell Above:</strong> Consider exit at this price if in loss (EMA resistance level)",
            "<strong>S1/S2:</strong> Support levels from 20-day and 50-day lows — buy near these levels",
            "<strong>R1/R2:</strong> Resistance levels from 20-day and 50-day highs — book profits near these levels"
        );
    }

    /**
     * Build Valuation Analysis section comparing Stock PE vs Industry PE.
     */
    private String buildValuationSection(List<HoldingsEntity> holdings) {
        StringBuilder content = new StringBuilder();

        // Filter holdings with PE data
        List<HoldingsEntity> withPeData = holdings.stream()
            .filter(h -> h.getStockPe() != null && h.getIndustryPe() != null)
            .collect(Collectors.toList());

        if (withPeData.isEmpty()) {
            content.append(templateService.alert("info", "&#128202;",
                "Valuation Data Unavailable",
                "PE ratios not yet fetched. Data will be available in the next report."));
            return templateService.section("&#128176;", "Valuation Analysis", content.toString());
        }

        // Categorize holdings by valuation
        List<HoldingsEntity> undervalued = withPeData.stream()
            .filter(h -> h.getPeDeviation() != null && h.getPeDeviation() < -15)
            .sorted((a, b) -> Double.compare(a.getPeDeviation(), b.getPeDeviation()))
            .collect(Collectors.toList());

        List<HoldingsEntity> overvalued = withPeData.stream()
            .filter(h -> h.getPeDeviation() != null && h.getPeDeviation() > 15)
            .sorted((a, b) -> Double.compare(b.getPeDeviation(), a.getPeDeviation()))
            .collect(Collectors.toList());

        List<HoldingsEntity> fairlyValued = withPeData.stream()
            .filter(h -> h.getPeDeviation() != null && h.getPeDeviation() >= -15 && h.getPeDeviation() <= 15)
            .collect(Collectors.toList());

        // Valuation Summary Cards
        content.append("<div class=\"summary-grid\">");
        content.append(templateService.summaryCard("Undervalued", undervalued.size() + " stocks", "profit"));
        content.append(templateService.summaryCard("Fairly Valued", fairlyValued.size() + " stocks", "neutral"));
        content.append(templateService.summaryCard("Overvalued", overvalued.size() + " stocks", "warning"));
        content.append(templateService.summaryCard("PE Data Available", withPeData.size() + "/" + holdings.size(), ""));
        content.append("</div>");

        // Undervalued stocks alert (good for accumulation)
        if (!undervalued.isEmpty()) {
            content.append("<div style=\"margin-top: 20px;\"></div>");
            content.append(templateService.alert("success", "&#128178;",
                "Undervalued (" + undervalued.size() + " stocks)",
                "Trading below industry average PE - potential accumulation opportunities."));

            StringBuilder table = new StringBuilder();
            table.append(templateService.tableStart("Symbol", "Industry", "Stock PE", "Industry PE", "Deviation", "P&L%", "Valuation"));

            for (HoldingsEntity h : undervalued) {
                String deviation = String.format("%.1f%%", h.getPeDeviation());
                String valuation = valuationService.getValuationInterpretation(h.getStockPe(), h.getIndustryPe());
                String badgeType = valuationService.getValuationBadgeType(h.getPeDeviation());

                table.append(templateService.tableRow(
                    "<strong>" + truncate(h.getTradingSymbol(), 12) + "</strong>",
                    truncate(h.getIndustry() != null ? h.getIndustry() : "N/A", 15),
                    String.format("%.1f", h.getStockPe()),
                    String.format("%.1f", h.getIndustryPe()),
                    templateService.badge(deviation, "success"),
                    templateService.formatPercent(h.getPnlPercent()),
                    templateService.badge(valuation, badgeType)
                ));
            }
            table.append(templateService.tableEnd());
            content.append(table);
        }

        // Overvalued stocks alert (consider booking profits)
        if (!overvalued.isEmpty()) {
            content.append("<div style=\"margin-top: 20px;\"></div>");
            content.append(templateService.alert("warning", "&#9888;",
                "Overvalued (" + overvalued.size() + " stocks)",
                "Trading above industry average PE - consider booking profits on rallies."));

            StringBuilder table = new StringBuilder();
            table.append(templateService.tableStart("Symbol", "Industry", "Stock PE", "Industry PE", "Deviation", "P&L%", "Valuation"));

            for (HoldingsEntity h : overvalued) {
                String deviation = String.format("+%.1f%%", h.getPeDeviation());
                String valuation = valuationService.getValuationInterpretation(h.getStockPe(), h.getIndustryPe());
                String badgeType = valuationService.getValuationBadgeType(h.getPeDeviation());

                table.append(templateService.tableRow(
                    "<strong>" + truncate(h.getTradingSymbol(), 12) + "</strong>",
                    truncate(h.getIndustry() != null ? h.getIndustry() : "N/A", 15),
                    String.format("%.1f", h.getStockPe()),
                    String.format("%.1f", h.getIndustryPe()),
                    templateService.badge(deviation, "warning"),
                    templateService.formatPercent(h.getPnlPercent()),
                    templateService.badge(valuation, badgeType)
                ));
            }
            table.append(templateService.tableEnd());
        }

        // Full PE Comparison Table
        content.append("<div style=\"margin-top: 20px;\"></div>");
        content.append("<h4 style=\"margin-bottom: 10px;\">Complete PE Comparison</h4>");

        StringBuilder fullTable = new StringBuilder();
        fullTable.append(templateService.tableStart("Symbol", "Industry", "Stock PE", "Industry PE", "Deviation", "Mkt Cap (Cr)", "P/B", "Valuation"));

        // Sort by deviation (most undervalued first)
        List<HoldingsEntity> sorted = withPeData.stream()
            .sorted((a, b) -> {
                Double devA = a.getPeDeviation() != null ? a.getPeDeviation() : 0;
                Double devB = b.getPeDeviation() != null ? b.getPeDeviation() : 0;
                return Double.compare(devA, devB);
            })
            .collect(Collectors.toList());

        for (HoldingsEntity h : sorted) {
            String deviation = h.getPeDeviation() != null
                ? String.format("%+.1f%%", h.getPeDeviation())
                : "N/A";
            String valuation = valuationService.getValuationInterpretation(h.getStockPe(), h.getIndustryPe());
            String badgeType = valuationService.getValuationBadgeType(h.getPeDeviation());

            String marketCap = h.getMarketCap() != null
                ? String.format("%.0f", h.getMarketCap())
                : "N/A";
            String priceToBook = h.getPriceToBook() != null
                ? String.format("%.2f", h.getPriceToBook())
                : "N/A";

            fullTable.append(templateService.tableRow(
                "<strong>" + truncate(h.getTradingSymbol(), 12) + "</strong>",
                truncate(h.getIndustry() != null ? h.getIndustry() : "N/A", 12),
                String.format("%.1f", h.getStockPe()),
                String.format("%.1f", h.getIndustryPe()),
                templateService.badge(deviation, badgeType),
                marketCap,
                priceToBook,
                templateService.badge(truncate(valuation, 12), badgeType)
            ));
        }
        fullTable.append(templateService.tableEnd());
        content.append(fullTable);

        // Valuation Guide
        content.append(templateService.infoBox(
            "&#128161; Valuation Guide",
            "<strong>PE Deviation:</strong> Compares stock PE with industry average PE",
            "<strong>Undervalued (< -15%):</strong> Stock trading at discount to peers - potential accumulation",
            "<strong>Fairly Valued (-15% to +15%):</strong> Trading in line with industry",
            "<strong>Overvalued (> +15%):</strong> Premium valuation - consider profit booking"
        ));

        return templateService.section("&#128176;", "Valuation Analysis (PE Comparison)", content.toString());
    }

    /**
     * Build Support & Resistance Levels section.
     * Shows pivot point, S1/S2, R1/R2 for each holding with proximity indicators.
     */
    private String buildSupportResistanceSection(List<HoldingsEntity> holdings) {
        StringBuilder content = new StringBuilder();

        // Filter holdings that have S/R data
        List<HoldingsEntity> withSR = holdings.stream()
            .filter(h -> h.getSupport1() != null && h.getResistance1() != null)
            .collect(Collectors.toList());

        if (withSR.isEmpty()) {
            content.append(templateService.alert("info", "&#128202;",
                "Support/Resistance Data Unavailable",
                "Levels will be calculated after the next analysis cycle."));
            return templateService.section("&#128205;", "Support & Resistance Levels", content.toString());
        }

        // Highlight stocks near key levels
        List<HoldingsEntity> nearSupport = withSR.stream()
            .filter(h -> {
                double cmp = h.getCurrentPrice();
                double s1 = h.getSupport1();
                return cmp > 0 && s1 > 0 && ((cmp - s1) / cmp) < 0.02; // Within 2% of support
            })
            .collect(Collectors.toList());

        List<HoldingsEntity> nearResistance = withSR.stream()
            .filter(h -> {
                double cmp = h.getCurrentPrice();
                double r1 = h.getResistance1();
                return cmp > 0 && r1 > 0 && ((r1 - cmp) / cmp) < 0.02; // Within 2% of resistance
            })
            .collect(Collectors.toList());

        // Alert cards for stocks near key levels
        if (!nearSupport.isEmpty()) {
            String symbols = nearSupport.stream()
                .map(HoldingsEntity::getTradingSymbol)
                .collect(Collectors.joining(", "));
            content.append(templateService.alert("success", "&#128994;",
                "Near Support (" + nearSupport.size() + " stocks)",
                "Within 2% of 20-day low — potential bounce zone: " + symbols));
        }

        if (!nearResistance.isEmpty()) {
            String symbols = nearResistance.stream()
                .map(HoldingsEntity::getTradingSymbol)
                .collect(Collectors.joining(", "));
            content.append(templateService.alert("warning", "&#128308;",
                "Near Resistance (" + nearResistance.size() + " stocks)",
                "Within 2% of 20-day high — watch for breakout or rejection: " + symbols));
        }

        // Full S/R table
        content.append("<div style=\"margin-top: 15px;\"></div>");
        StringBuilder table = new StringBuilder();
        table.append(templateService.tableStart("Symbol", "CMP", "S2 (50d)", "S1 (20d)", "Pivot", "R1 (20d)", "R2 (50d)", "Position"));

        for (HoldingsEntity h : withSR) {
            double cmp = h.getCurrentPrice();
            double s1 = h.getSupport1() != null ? h.getSupport1() : 0;
            double s2 = h.getSupport2() != null ? h.getSupport2() : 0;
            double r1 = h.getResistance1() != null ? h.getResistance1() : 0;
            double r2 = h.getResistance2() != null ? h.getResistance2() : 0;
            double pp = h.getPivotPoint() != null ? h.getPivotPoint() : 0;

            // Determine position relative to levels
            String position;
            String positionBadge;
            if (cmp > r1) {
                position = "Above R1";
                positionBadge = templateService.badge(position, "success");
            } else if (cmp > pp) {
                position = "Above Pivot";
                positionBadge = templateService.badge(position, "success");
            } else if (cmp > s1) {
                position = "Below Pivot";
                positionBadge = templateService.badge(position, "warning");
            } else {
                position = "Below S1";
                positionBadge = templateService.badge(position, "danger");
            }

            table.append(templateService.tableRow(
                "<strong>" + h.getTradingSymbol() + "</strong>",
                String.format("%.2f", cmp),
                String.format("%.2f", s2),
                String.format("%.2f", s1),
                String.format("%.2f", pp),
                String.format("%.2f", r1),
                String.format("%.2f", r2),
                positionBadge
            ));
        }
        table.append(templateService.tableEnd());
        content.append(table);

        // S/R Guide
        content.append(templateService.infoBox(
            "&#128204; Support & Resistance Guide",
            "<strong>S1 (20-day low):</strong> Immediate support — price bounced from here recently",
            "<strong>S2 (50-day low):</strong> Strong support — major floor from last 50 trading days",
            "<strong>Pivot:</strong> (20d High + 20d Low + Close) / 3 — key inflection point",
            "<strong>R1 (20-day high):</strong> Immediate resistance — price was rejected here recently",
            "<strong>R2 (50-day high):</strong> Strong resistance — breakout above this is very bullish"
        ));

        return templateService.section("&#128205;", "Support & Resistance Levels", content.toString());
    }

    private String getTrendReason(HoldingsEntity h) {
        StringBuilder reason = new StringBuilder();
        if ("BEARISH".equals(h.getTrendDirection())) {
            reason.append("Bearish trend");
        }
        if (h.getRsi14() != null) {
            if (h.getRsi14() > 70) {
                reason.append(reason.length() > 0 ? ", " : "").append("Overbought");
            } else if (h.getRsi14() < 30) {
                reason.append(reason.length() > 0 ? ", " : "").append("Oversold");
            }
        }
        if (h.getOverallScore() != null && h.getOverallScore() < 40) {
            reason.append(reason.length() > 0 ? ", " : "").append("Low score");
        }
        return reason.length() > 0 ? reason.toString() : "Technical weakness";
    }

    private String truncate(String str, int maxLen) {
        // Truncation disabled - show full text
        if (str == null) return "";
        return str;
    }

    /**
     * Get styled badge for recommendation.
     */
    private String getRecommendationBadge(String recommendation) {
        if (recommendation == null) return "N/A";
        return switch (recommendation) {
            case "STRONG_BUY" -> templateService.badge("STRONG BUY", "success");
            case "BUY" -> templateService.badge("BUY", "success");
            case "HOLD" -> templateService.badge("HOLD", "neutral");
            case "BOOK_PROFIT" -> templateService.badge("BOOK PROFIT", "warning");
            case "SELL" -> templateService.badge("SELL", "danger");
            case "STRONG_SELL" -> templateService.badge("STRONG SELL", "danger");
            default -> templateService.badge(recommendation, "neutral");
        };
    }

    /**
     * Build Market Intelligence Overlay section.
     * Correlates holdings with FII/DII sector flows.
     *
     * <p>The NIFTY option-chain arm was removed on 2026-09-03: PCR and max pain describe where
     * this week's option sellers sit, which is a trading horizon, not a 1-3 year one.
     */
    private String buildMarketIntelligenceOverlay(List<HoldingsEntity> holdings) {
        StringBuilder sb = new StringBuilder();

        try {
            sb.append(templateService.sectionStart("Market Intelligence Overlay", "satellite"));

            // --- FII/DII Sector Flow vs Holdings ---
            try {
                LocalDate dataDate = LocalDate.now();
                // Adjust for weekends
                if (dataDate.getDayOfWeek() == DayOfWeek.MONDAY) {
                    dataDate = dataDate.minusDays(3);
                } else if (dataDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    dataDate = dataDate.minusDays(2);
                } else {
                    dataDate = dataDate.minusDays(1);
                }

                DailyActivity fiiDii = fiiDiiDataService.fetchDailyActivity(dataDate);
                List<SectorFlow> sectorFlows = fiiDiiSectorAnalysisService.analyzeSectorFlows(dataDate);

                if (fiiDii != null) {
                    // FII/DII Quick Summary
                    String fiiColor = fiiDii.getFiiNetValue() >= 0 ? "#4CAF50" : "#F44336";
                    String diiColor = fiiDii.getDiiNetValue() >= 0 ? "#4CAF50" : "#F44336";
                    sb.append("<div style='display:flex; gap:15px; margin:15px 0; flex-wrap:wrap;'>");
                    sb.append(String.format("<div style='flex:1; min-width:150px; background:#f8f9fa; padding:12px; border-radius:6px; border-left:4px solid %s;'>" +
                        "<div style='font-size:11px; color:#666;'>FII Net (Yesterday)</div><div style='font-size:18px; font-weight:bold; color:%s;'>%s%.0f Cr</div></div>",
                        fiiColor, fiiColor, fiiDii.getFiiNetValue() >= 0 ? "+" : "", fiiDii.getFiiNetValue()));
                    sb.append(String.format("<div style='flex:1; min-width:150px; background:#f8f9fa; padding:12px; border-radius:6px; border-left:4px solid %s;'>" +
                        "<div style='font-size:11px; color:#666;'>DII Net (Yesterday)</div><div style='font-size:18px; font-weight:bold; color:%s;'>%s%.0f Cr</div></div>",
                        diiColor, diiColor, fiiDii.getDiiNetValue() >= 0 ? "+" : "", fiiDii.getDiiNetValue()));
                    sb.append("</div>");
                }

                // Match sector flows to holdings
                if (sectorFlows != null && !sectorFlows.isEmpty()) {
                    // Build sector -> flow map
                    Map<String, SectorFlow> sectorFlowMap = new HashMap<>();
                    for (SectorFlow flow : sectorFlows) {
                        sectorFlowMap.put(flow.getSector().toUpperCase(), flow);
                    }

                    // Find holdings in sectors with heavy selling
                    List<String> warnings = new ArrayList<>();
                    List<String> positives = new ArrayList<>();

                    for (HoldingsEntity h : holdings) {
                        if (h.getIndustry() == null) continue;
                        String sector = h.getIndustry().toUpperCase();

                        // Try to match sector (partial match)
                        SectorFlow matchedFlow = null;
                        for (Map.Entry<String, SectorFlow> entry : sectorFlowMap.entrySet()) {
                            if (sector.contains(entry.getKey()) || entry.getKey().contains(sector)) {
                                matchedFlow = entry.getValue();
                                break;
                            }
                        }

                        if (matchedFlow != null) {
                            String symbol = h.getSymbol().replace("NSE:", "").replace("BSE:", "");
                            if (matchedFlow.getFiiNetValue() < -50) {
                                warnings.add(String.format("<b>%s</b> (%s) — FII selling %.0f Cr in %s sector",
                                    symbol, h.getRecommendation(), Math.abs(matchedFlow.getFiiNetValue()), matchedFlow.getSector()));
                            } else if (matchedFlow.getFiiNetValue() > 50) {
                                positives.add(String.format("<b>%s</b> (%s) — FII buying %.0f Cr in %s sector",
                                    symbol, h.getRecommendation(), matchedFlow.getFiiNetValue(), matchedFlow.getSector()));
                            }
                        }
                    }

                    if (!warnings.isEmpty()) {
                        sb.append(templateService.alert("danger", "&#128308;", "FII Selling Alert",
                            "FII SELLING in your sectors:<br>" + String.join("<br>", warnings.subList(0, Math.min(5, warnings.size())))));
                    }
                    if (!positives.isEmpty()) {
                        sb.append(templateService.alert("success", "&#128994;", "FII Buying Signal",
                            "FII BUYING in your sectors:<br>" + String.join("<br>", positives.subList(0, Math.min(5, positives.size())))));
                    }
                }
            } catch (Exception e) {
                log.debug("Could not fetch FII/DII data for overlay: {}", e.getMessage());
            }

            sb.append(templateService.sectionEnd());

        } catch (Exception e) {
            log.debug("Market Intelligence Overlay failed: {}", e.getMessage());
        }

        return sb.toString();
    }

    /**
     * Build week-over-week portfolio changes section for weekly report.
     */
    private String buildWeekOverWeekChanges(List<HoldingsEntity> holdings) {
        StringBuilder sb = new StringBuilder();
        sb.append(templateService.sectionStart("Week-over-Week Portfolio Changes", "chart_with_upwards_trend"));

        Double totalInvested = holdingsRepository.getTotalInvestedValue();
        Double totalCurrent = holdingsRepository.getTotalCurrentValue();
        Double totalPnL = holdingsRepository.getTotalPnL();

        if (totalInvested == null || totalCurrent == null) {
            sb.append("<p>Insufficient data for week-over-week comparison.</p>");
            sb.append(templateService.sectionEnd());
            return sb.toString();
        }

        double portfolioReturn = totalInvested > 0 ? ((totalCurrent - totalInvested) / totalInvested) * 100 : 0;
        long profitable = holdings.stream().filter(h -> h.getPnl() > 0).count();
        long losing = holdings.stream().filter(h -> h.getPnl() < 0).count();

        // Portfolio value summary cards
        sb.append("<div style='display:flex; gap:12px; margin-bottom:15px; flex-wrap:wrap;'>");

        String retColor = portfolioReturn >= 0 ? "#4CAF50" : "#F44336";
        sb.append(String.format("<div style='flex:1; min-width:140px; background:#f8f9fa; padding:15px; border-radius:8px; text-align:center; border-top:3px solid %s;'>" +
            "<div style='font-size:11px; color:#888;'>Total Return</div>" +
            "<div style='font-size:22px; font-weight:bold; color:%s;'>%s%.2f%%</div>" +
            "<div style='font-size:11px; color:#666;'>P&L: Rs.%,.0f</div></div>",
            retColor, retColor, portfolioReturn >= 0 ? "+" : "", portfolioReturn, totalPnL != null ? totalPnL : 0));

        sb.append(String.format("<div style='flex:1; min-width:140px; background:#f8f9fa; padding:15px; border-radius:8px; text-align:center; border-top:3px solid #2196F3;'>" +
            "<div style='font-size:11px; color:#888;'>Portfolio Value</div>" +
            "<div style='font-size:22px; font-weight:bold; color:#1565C0;'>Rs.%,.0f</div>" +
            "<div style='font-size:11px; color:#666;'>Invested: Rs.%,.0f</div></div>",
            totalCurrent, totalInvested));

        double winRate = holdings.size() > 0 ? (double) profitable / holdings.size() * 100 : 0;
        String wrColor = winRate >= 50 ? "#4CAF50" : "#F44336";
        sb.append(String.format("<div style='flex:1; min-width:140px; background:#f8f9fa; padding:15px; border-radius:8px; text-align:center; border-top:3px solid %s;'>" +
            "<div style='font-size:11px; color:#888;'>Win Rate</div>" +
            "<div style='font-size:22px; font-weight:bold; color:%s;'>%.0f%%</div>" +
            "<div style='font-size:11px; color:#666;'>%d winners / %d losers</div></div>",
            wrColor, wrColor, winRate, profitable, losing));

        sb.append("</div>");

        // Average metrics
        double avgScore = holdings.stream().filter(h -> h.getOverallScore() != null).mapToInt(HoldingsEntity::getOverallScore).average().orElse(0);
        double avgRsi = holdings.stream().filter(h -> h.getRsi14() != null).mapToDouble(HoldingsEntity::getRsi14).average().orElse(50);
        long aboveEma200 = holdings.stream().filter(h -> "BULLISH".equals(h.getTrendDirection())).count();

        sb.append(String.format("<div style='background:#e8eaf6; padding:12px; border-radius:6px; margin-top:10px;'>" +
            "<span style='margin-right:20px;'>Avg Score: <b>%.0f</b>/100</span>" +
            "<span style='margin-right:20px;'>Avg RSI: <b>%.1f</b></span>" +
            "<span>Bullish Stocks: <b>%d</b> of %d</span></div>",
            avgScore, avgRsi, aboveEma200, holdings.size()));

        sb.append(templateService.sectionEnd());
        return sb.toString();
    }

    /**
     * Build top movers section showing biggest gainers and losers.
     */
    private String buildTopMovers(List<HoldingsEntity> holdings) {
        StringBuilder sb = new StringBuilder();
        sb.append(templateService.sectionStart("Top Movers This Week", "rocket"));

        // Top 5 gainers by P&L%
        List<HoldingsEntity> sortedByPnl = new ArrayList<>(holdings);
        sortedByPnl.sort(Comparator.comparingDouble((HoldingsEntity h) -> h.getPnlPercent()).reversed());

        List<HoldingsEntity> topGainers = sortedByPnl.stream().filter(h -> h.getPnlPercent() > 0).limit(5).collect(Collectors.toList());
        List<HoldingsEntity> topLosers = sortedByPnl.stream().filter(h -> h.getPnlPercent() < 0).collect(Collectors.toList());
        if (topLosers.size() > 5) topLosers = topLosers.subList(topLosers.size() - 5, topLosers.size());
        java.util.Collections.reverse(topLosers);

        // Gainers table
        if (!topGainers.isEmpty()) {
            sb.append("<h4 style='color:#4CAF50; margin:10px 0 5px;'>Top Gainers</h4>");
            sb.append("<table style='width:100%; border-collapse:collapse; margin-bottom:15px;'>");
            sb.append("<tr style='background:#E8F5E9;'><th style='padding:8px; border:1px solid #C8E6C9; text-align:left;'>Stock</th>" +
                "<th style='padding:8px; border:1px solid #C8E6C9;'>Price</th>" +
                "<th style='padding:8px; border:1px solid #C8E6C9;'>P&L %</th>" +
                "<th style='padding:8px; border:1px solid #C8E6C9;'>P&L Rs.</th>" +
                "<th style='padding:8px; border:1px solid #C8E6C9;'>Score</th>" +
                "<th style='padding:8px; border:1px solid #C8E6C9;'>Recommendation</th></tr>");

            for (HoldingsEntity h : topGainers) {
                String sym = h.getSymbol().replaceAll("(NSE:|BSE:)", "");
                sb.append(String.format("<tr><td style='padding:8px; border:1px solid #ddd;'><b>%s</b></td>" +
                    "<td style='padding:8px; border:1px solid #ddd; text-align:right;'>Rs.%.2f</td>" +
                    "<td style='padding:8px; border:1px solid #ddd; text-align:right; color:#4CAF50; font-weight:bold;'>+%.2f%%</td>" +
                    "<td style='padding:8px; border:1px solid #ddd; text-align:right; color:#4CAF50;'>+Rs.%,.0f</td>" +
                    "<td style='padding:8px; border:1px solid #ddd; text-align:center;'>%s</td>" +
                    "<td style='padding:8px; border:1px solid #ddd; text-align:center;'>%s</td></tr>",
                    sym, h.getCurrentPrice(), h.getPnlPercent(),
                    h.getPnl(),
                    h.getOverallScore() != null ? h.getOverallScore().toString() : "-",
                    h.getRecommendation() != null ? h.getRecommendation() : "-"));
            }
            sb.append("</table>");
        }

        // Losers table
        if (!topLosers.isEmpty()) {
            sb.append("<h4 style='color:#F44336; margin:10px 0 5px;'>Top Losers</h4>");
            sb.append("<table style='width:100%; border-collapse:collapse; margin-bottom:15px;'>");
            sb.append("<tr style='background:#FFEBEE;'><th style='padding:8px; border:1px solid #FFCDD2; text-align:left;'>Stock</th>" +
                "<th style='padding:8px; border:1px solid #FFCDD2;'>Price</th>" +
                "<th style='padding:8px; border:1px solid #FFCDD2;'>P&L %</th>" +
                "<th style='padding:8px; border:1px solid #FFCDD2;'>P&L Rs.</th>" +
                "<th style='padding:8px; border:1px solid #FFCDD2;'>Score</th>" +
                "<th style='padding:8px; border:1px solid #FFCDD2;'>Recommendation</th></tr>");

            for (HoldingsEntity h : topLosers) {
                String sym = h.getSymbol().replaceAll("(NSE:|BSE:)", "");
                sb.append(String.format("<tr><td style='padding:8px; border:1px solid #ddd;'><b>%s</b></td>" +
                    "<td style='padding:8px; border:1px solid #ddd; text-align:right;'>Rs.%.2f</td>" +
                    "<td style='padding:8px; border:1px solid #ddd; text-align:right; color:#F44336; font-weight:bold;'>%.2f%%</td>" +
                    "<td style='padding:8px; border:1px solid #ddd; text-align:right; color:#F44336;'>Rs.%,.0f</td>" +
                    "<td style='padding:8px; border:1px solid #ddd; text-align:center;'>%s</td>" +
                    "<td style='padding:8px; border:1px solid #ddd; text-align:center;'>%s</td></tr>",
                    sym, h.getCurrentPrice(), h.getPnlPercent(),
                    h.getPnl(),
                    h.getOverallScore() != null ? h.getOverallScore().toString() : "-",
                    h.getRecommendation() != null ? h.getRecommendation() : "-"));
            }
            sb.append("</table>");
        }

        sb.append(templateService.sectionEnd());
        return sb.toString();
    }

    /**
     * Build sector rotation analysis showing allocation and health by sector.
     */
    private String buildSectorRotation(List<HoldingsEntity> holdings) {
        StringBuilder sb = new StringBuilder();
        sb.append(templateService.sectionStart("Sector Allocation & Health", "pie_chart"));

        // Group holdings by industry/sector
        Map<String, List<HoldingsEntity>> bySector = holdings.stream()
            .filter(h -> h.getIndustry() != null && !h.getIndustry().isEmpty())
            .collect(Collectors.groupingBy(HoldingsEntity::getIndustry));

        if (bySector.isEmpty()) {
            sb.append("<p>Sector data not available for holdings.</p>");
            sb.append(templateService.sectionEnd());
            return sb.toString();
        }

        Double totalValue = holdingsRepository.getTotalCurrentValue();
        double portfolioTotal = totalValue != null ? totalValue : 1;

        // Build sector summary list sorted by allocation
        List<Map.Entry<String, List<HoldingsEntity>>> sortedSectors = new ArrayList<>(bySector.entrySet());
        sortedSectors.sort((a, b) -> {
            double valA = a.getValue().stream().mapToDouble(h -> h.getCurrentValue()).sum();
            double valB = b.getValue().stream().mapToDouble(h -> h.getCurrentValue()).sum();
            return Double.compare(valB, valA);
        });

        sb.append("<table style='width:100%; border-collapse:collapse;'>");
        sb.append("<tr style='background:#e8eaf6;'>" +
            "<th style='padding:8px; border:1px solid #C5CAE9; text-align:left;'>Sector</th>" +
            "<th style='padding:8px; border:1px solid #C5CAE9;'>Stocks</th>" +
            "<th style='padding:8px; border:1px solid #C5CAE9;'>Allocation</th>" +
            "<th style='padding:8px; border:1px solid #C5CAE9;'>Value (Rs.)</th>" +
            "<th style='padding:8px; border:1px solid #C5CAE9;'>Avg P&L%</th>" +
            "<th style='padding:8px; border:1px solid #C5CAE9;'>Avg Score</th>" +
            "<th style='padding:8px; border:1px solid #C5CAE9;'>Trend</th></tr>");

        int row = 0;
        for (Map.Entry<String, List<HoldingsEntity>> entry : sortedSectors) {
            String sector = entry.getKey();
            List<HoldingsEntity> sectorHoldings = entry.getValue();

            double sectorValue = sectorHoldings.stream().mapToDouble(h -> h.getCurrentValue()).sum();
            double allocation = portfolioTotal > 0 ? (sectorValue / portfolioTotal) * 100 : 0;
            double avgPnl = sectorHoldings.stream().mapToDouble(HoldingsEntity::getPnlPercent).average().orElse(0);
            double avgScore = sectorHoldings.stream().filter(h -> h.getOverallScore() != null).mapToInt(HoldingsEntity::getOverallScore).average().orElse(0);

            long bullish = sectorHoldings.stream().filter(h -> "BULLISH".equals(h.getTrendDirection())).count();
            long bearish = sectorHoldings.stream().filter(h -> "BEARISH".equals(h.getTrendDirection())).count();
            String trendLabel = bullish > bearish ? "BULLISH" : (bearish > bullish ? "BEARISH" : "MIXED");
            String trendColor = "BULLISH".equals(trendLabel) ? "#4CAF50" : ("BEARISH".equals(trendLabel) ? "#F44336" : "#FF9800");

            String pnlColor = avgPnl >= 0 ? "#4CAF50" : "#F44336";
            String bgColor = row % 2 == 0 ? "#ffffff" : "#f8f9fa";

            // Allocation bar
            String barColor = allocation > 20 ? "#FF9800" : (allocation > 10 ? "#2196F3" : "#4CAF50");

            sb.append(String.format("<tr style='background:%s;'>" +
                "<td style='padding:8px; border:1px solid #ddd;'><b>%s</b></td>" +
                "<td style='padding:8px; border:1px solid #ddd; text-align:center;'>%d</td>" +
                "<td style='padding:8px; border:1px solid #ddd;'>" +
                    "<div style='background:#eee; border-radius:4px; overflow:hidden;'>" +
                    "<div style='background:%s; height:18px; width:%.0f%%; min-width:20px; border-radius:4px; text-align:center; color:white; font-size:11px; line-height:18px;'>%.1f%%</div>" +
                    "</div></td>" +
                "<td style='padding:8px; border:1px solid #ddd; text-align:right;'>%,.0f</td>" +
                "<td style='padding:8px; border:1px solid #ddd; text-align:center; color:%s; font-weight:bold;'>%s%.1f%%</td>" +
                "<td style='padding:8px; border:1px solid #ddd; text-align:center;'>%.0f</td>" +
                "<td style='padding:8px; border:1px solid #ddd; text-align:center; color:%s; font-weight:bold;'>%s</td></tr>",
                bgColor, sector, sectorHoldings.size(),
                barColor, Math.min(allocation, 100), allocation,
                sectorValue,
                pnlColor, avgPnl >= 0 ? "+" : "", avgPnl,
                avgScore,
                trendColor, trendLabel));
            row++;
        }
        sb.append("</table>");

        // Concentration warning
        if (!sortedSectors.isEmpty()) {
            double topSectorAlloc = sortedSectors.get(0).getValue().stream()
                .mapToDouble(h -> h.getCurrentValue()).sum() / portfolioTotal * 100;
            if (topSectorAlloc > 30) {
                sb.append(String.format("<div style='margin-top:10px; padding:10px; background:#FFF3E0; border-left:4px solid #FF9800; border-radius:4px;'>" +
                    "<b>Concentration Warning:</b> %s accounts for %.0f%% of portfolio. Consider diversifying.</div>",
                    sortedSectors.get(0).getKey(), topSectorAlloc));
            }
        }

        sb.append(templateService.sectionEnd());
        return sb.toString();
    }

    /**
     * Remove duplicate holdings based on tradingSymbol.
     * Keeps the first occurrence (highest score since list is sorted by score desc).
     */
    /**
     * Drop fully-exited holdings (quantity &lt;= 0). Stale rows for sold-out stocks
     * still live in the holdings table; we don't want them showing up in any
     * email section, ranking, or table.
     */
    private List<HoldingsEntity> filterActiveHoldings(List<HoldingsEntity> holdings) {
        List<HoldingsEntity> active = holdings.stream()
            .filter(h -> h.getQuantity() > 0)
            .collect(Collectors.toList());
        int dropped = holdings.size() - active.size();
        if (dropped > 0) {
            log.info("Filtered out {} zero-quantity holdings from report", dropped);
        }
        return active;
    }

    private List<HoldingsEntity> deduplicateHoldings(List<HoldingsEntity> holdings) {
        Set<String> seenSymbols = new HashSet<>();
        List<HoldingsEntity> unique = new ArrayList<>();

        for (HoldingsEntity h : holdings) {
            String key = h.getTradingSymbol() != null ? h.getTradingSymbol().toUpperCase() : h.getSymbol();
            if (!seenSymbols.contains(key)) {
                seenSymbols.add(key);
                unique.add(h);
            } else {
                log.debug("Skipping duplicate holding: {} (symbol: {})", h.getTradingSymbol(), h.getSymbol());
            }
        }

        if (unique.size() < holdings.size()) {
            log.warn("Removed {} duplicate holdings from report", holdings.size() - unique.size());
        }

        return unique;
    }
}
