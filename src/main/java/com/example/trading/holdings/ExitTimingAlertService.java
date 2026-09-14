package com.example.trading.holdings;

import com.example.trading.ai.AiService;
import com.example.trading.notification.EmailNotificationService;
import com.example.trading.notification.EmailTemplateService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ExitTimingAlertService {

    private final HoldingsRepository holdingsRepository;
    private final EmailNotificationService emailNotificationService;
    private final EmailTemplateService templateService;
    private final AiService aiService;
    private final com.example.trading.portfolio.core.CoreOverlayService coreOverlay;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** Tracks previous overall scores for momentum reversal detection. Key = symbol. */
    private final Map<String, Integer> previousScores = new ConcurrentHashMap<>();

    /** Tracks which alerts have already been sent today to avoid spam. Key = "SYMBOL|ALERT_TYPE". */
    private final Set<String> sentAlertsToday = ConcurrentHashMap.newKeySet();
    private final MarketHoursService marketHoursService;

    /** The date for which sentAlertsToday is valid; resets when the date changes. */
    private volatile LocalDate sentAlertsDate = LocalDate.now();

    // Alert type constants
    private static final String ALERT_NEAR_RESISTANCE = "NEAR_RESISTANCE";
    private static final String ALERT_RSI_OVERBOUGHT = "RSI_OVERBOUGHT";
    private static final String ALERT_BROKE_SUPPORT = "BROKE_SUPPORT";
    private static final String ALERT_DEEP_LOSS = "DEEP_LOSS_ACCELERATING";
    private static final String ALERT_MOMENTUM_REVERSAL = "MOMENTUM_REVERSAL";

    // Urgency levels
    private static final String URGENCY_URGENT = "URGENT";
    private static final String URGENCY_WARNING = "WARNING";
    private static final String URGENCY_OPPORTUNITY = "OPPORTUNITY";

    @Scheduled(cron = "0 0 10,12,14 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledCheck() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        log.info("Running scheduled exit timing alert check");
        checkExitConditions();
    }

    /**
     * Checks all holdings for exit conditions and sends an email alert if any are found.
     * Can be called manually from a controller.
     */
    public void checkExitConditions() {
        try {
            clearAlertsIfNewDay();

            List<HoldingsEntity> holdings = holdingsRepository.findActive();
            if (holdings.isEmpty()) {
                log.info("No holdings found, skipping exit timing check");
                return;
            }

            List<ExitAlert> alerts = new ArrayList<>();
            Map<String, List<String>> coreTechnicalAlerts = new LinkedHashMap<>();
            boolean suppression = coreOverlay.suppressionEnabled();

            for (HoldingsEntity holding : holdings) {
                boolean core = coreOverlay.isProtected(holding.getSymbol());
                // Suppression mode must evaluate without touching the dedup set: the footer has to
                // NAME the alerts it withheld, and naming them means evaluating them (Gotcha 19).
                boolean dedupFree = core && suppression;

                for (ExitAlert alert : evaluateHolding(holding, !dedupFree)) {
                    if (core && com.example.trading.portfolio.core.CoreOverlayService
                            .isTechnicalAlert(alert.alertType())) {
                        coreTechnicalAlerts
                                .computeIfAbsent(holding.getSymbol(), k -> new ArrayList<>())
                                .add(alert.alertType());
                        if (suppression) continue;   // withheld, and no dedup slot was consumed
                    }
                    // Anything still going out in dedup-free mode claims its own slot, so a
                    // retained alert (DEEP_LOSS) does not repeat at 12:00 and 14:00.
                    if (dedupFree && !markSent(holding.getSymbol(), alert.alertType())) continue;
                    alerts.add(core ? retitleForCore(alert) : alert);
                }
            }

            // The R-1 evidence trail: what fired on a core holding today, persisted on its
            // classification row so the flag flip is argued from observations, not intuition.
            coreTechnicalAlerts.forEach(coreOverlay::recordObservedAlerts);

            // Update previous scores for next run (after evaluation so current run uses old values)
            for (HoldingsEntity holding : holdings) {
                if (holding.getOverallScore() != null) {
                    previousScores.put(holding.getSymbol(), holding.getOverallScore());
                }
            }

            if (alerts.isEmpty()) {
                log.info("No exit timing alerts triggered for {} holdings", holdings.size());
                return;
            }

            log.info("Generated {} exit timing alerts for {} holdings ({} core-holding "
                    + "technical alerts {})", alerts.size(), holdings.size(),
                    coreTechnicalAlerts.values().stream().mapToInt(List::size).sum(),
                    suppression ? "withheld" : "observed");
            sendAlertEmail(alerts, coreTechnicalAlerts, suppression);

        } catch (Exception e) {
            log.error("Error during exit timing alert check: {}", e.getMessage(), e);
        }
    }

    private void clearAlertsIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(sentAlertsDate)) {
            sentAlertsToday.clear();
            sentAlertsDate = today;
            log.info("Cleared exit timing alert history for new day: {}", today);
        }
    }

    /**
     * Evaluate one holding, recording the day's dedup keys as usual.
     *
     * <p><b>Not safe for read-only callers.</b> This consumes the day's alert slots, so a dashboard
     * or API path that calls it silently empties the next 10:00 / 12:00 / 14:00 email (CLAUDE.md
     * Gotcha 19). Such callers want {@link #evaluateHolding(HoldingsEntity, boolean)} with
     * {@code recordDedup=false}.
     */
    List<ExitAlert> evaluateHolding(HoldingsEntity h) {
        return evaluateHolding(h, true);
    }

    /**
     * @param recordDedup {@code true} = today's behaviour: an alert already sent today is skipped,
     *                    and each alert returned claims its slot. {@code false} = report every
     *                    triggered condition and claim nothing, for callers that need to know what
     *                    <em>would</em> fire without disarming it. The caller is then responsible
     *                    for {@link #markSent} on anything it actually sends.
     */
    List<ExitAlert> evaluateHolding(HoldingsEntity h, boolean recordDedup) {
        List<ExitAlert> alerts = new ArrayList<>();

        String symbol = h.getSymbol();
        double currentPrice = h.getCurrentPrice();
        double pnlPercent = h.getPnlPercent();

        // 1. Near Resistance - Current price within 1.5% of resistance1 AND profitable (pnlPercent > 5%)
        if (h.getResistance1() != null && h.getResistance1() > 0) {
            double distanceToResistance = ((h.getResistance1() - currentPrice) / currentPrice) * 100.0;
            if (distanceToResistance >= 0 && distanceToResistance <= 1.5 && pnlPercent > 5.0) {
                String alertKey = symbol + "|" + ALERT_NEAR_RESISTANCE;
                if (claim(alertKey, recordDedup)) {
                    alerts.add(new ExitAlert(
                            symbol,
                            ALERT_NEAR_RESISTANCE,
                            URGENCY_WARNING,
                            "Near Resistance - Consider Booking Profit",
                            String.format("Price %.2f is within 1.5%% of resistance at %.2f. P&L: +%.1f%%. "
                                    + "Consider partial profit booking before resistance rejection.",
                                    currentPrice, h.getResistance1(), pnlPercent),
                            "Book 50-75% profits near resistance; trail stop-loss on remainder",
                            h
                    ));
                }
            }
        }

        // 2. RSI Overbought on Profitable Holding - RSI > 75 AND pnlPercent > 10%
        if (h.getRsi14() != null && h.getRsi14() > 75.0 && pnlPercent > 10.0) {
            String alertKey = symbol + "|" + ALERT_RSI_OVERBOUGHT;
            if (claim(alertKey, recordDedup)) {
                alerts.add(new ExitAlert(
                        symbol,
                        ALERT_RSI_OVERBOUGHT,
                        URGENCY_WARNING,
                        "RSI Overbought - Potential Reversal",
                        String.format("RSI at %.1f (overbought > 75) with P&L of +%.1f%%. "
                                + "Overbought conditions often precede pullbacks.",
                                h.getRsi14(), pnlPercent),
                        "Book profits in phases; set trailing stop-loss at recent swing low",
                        h
                ));
            }
        }

        // 3. Broke Below Support - Current price < support1 AND recommendation is SELL or STRONG_SELL
        if (h.getSupport1() != null && h.getSupport1() > 0 && currentPrice < h.getSupport1()) {
            String rec = h.getRecommendation();
            if ("SELL".equals(rec) || "STRONG_SELL".equals(rec)) {
                String alertKey = symbol + "|" + ALERT_BROKE_SUPPORT;
                if (claim(alertKey, recordDedup)) {
                    alerts.add(new ExitAlert(
                            symbol,
                            ALERT_BROKE_SUPPORT,
                            URGENCY_URGENT,
                            "Broke Below Support - Exit Recommended",
                            String.format("Price %.2f has broken below support at %.2f. "
                                    + "Recommendation: %s. Next support at %.2f.",
                                    currentPrice, h.getSupport1(), rec,
                                    h.getSupport2() != null ? h.getSupport2() : 0.0),
                            "Exit position immediately; do not average down on broken support",
                            h
                    ));
                }
            }
        }

        // 4. Deep Loss Accelerating - pnlPercent < -15% AND trendDirection is BEARISH
        if (pnlPercent < -15.0 && "BEARISH".equals(h.getTrendDirection())) {
            String alertKey = symbol + "|" + ALERT_DEEP_LOSS;
            if (claim(alertKey, recordDedup)) {
                alerts.add(new ExitAlert(
                        symbol,
                        ALERT_DEEP_LOSS,
                        URGENCY_URGENT,
                        "Deep Loss Accelerating - Stop the Bleeding",
                        String.format("Holding is down %.1f%% with a BEARISH trend. "
                                + "Loss amount: Rs.%.2f. Trend shows no reversal signs.",
                                pnlPercent, h.getPnl()),
                        "Exit to preserve capital; reassess only after trend reversal confirmation",
                        h
                ));
            }
        }

        // 5. Momentum Reversal - overallScore < 30 AND previous score was > 50
        if (h.getOverallScore() != null && h.getOverallScore() < 30) {
            Integer prevScore = previousScores.get(symbol);
            if (prevScore != null && prevScore > 50) {
                String alertKey = symbol + "|" + ALERT_MOMENTUM_REVERSAL;
                if (claim(alertKey, recordDedup)) {
                    alerts.add(new ExitAlert(
                            symbol,
                            ALERT_MOMENTUM_REVERSAL,
                            URGENCY_URGENT,
                            "Momentum Reversal Detected",
                            String.format("Overall score dropped from %d to %d (below 30 threshold). "
                                    + "Sharp momentum deterioration signals potential further decline.",
                                    prevScore, h.getOverallScore()),
                            "Reduce position size or exit; momentum collapse often precedes larger moves",
                            h
                    ));
                }
            }
        }

        return alerts;
    }

    /**
     * The core-holding line (SPEC §35.5). In observation mode it names the alerts that fired on a
     * core holding and says what suppression would have done with them — that sentence, repeated
     * daily for a quarter, is the entire evidence base for flipping the flag. In suppression mode
     * it names what was withheld, so nothing disappears silently.
     */
    private String buildCoreHoldingFooter(Map<String, List<String>> coreTechnicalAlerts,
                                          boolean suppression) {
        if (coreTechnicalAlerts == null || coreTechnicalAlerts.isEmpty()) return "";

        StringBuilder names = new StringBuilder();
        int total = 0;
        for (Map.Entry<String, List<String>> e : coreTechnicalAlerts.entrySet()) {
            if (names.length() > 0) names.append("; ");
            String plain = e.getKey().contains(":")
                    ? e.getKey().substring(e.getKey().indexOf(':') + 1) : e.getKey();
            names.append(plain).append(" (")
                 .append(String.join(", ", e.getValue()).toLowerCase(Locale.ROOT).replace('_', ' '))
                 .append(')');
            total += e.getValue().size();
        }

        String headline = suppression
                ? total + " technical exit alert(s) were WITHHELD on core holdings today"
                : total + " technical exit alert(s) fired on core holdings today";
        String gloss = suppression
                ? "Core holdings are held through price noise, so these were not sent. They are "
                  + "named here so nothing disappears silently."
                : "These were sent as usual. With core-holding suppression switched on they would "
                  + "have been withheld — this line is the record being kept to decide whether "
                  + "switching it on is a good idea.";

        return "<div style=\"margin-top:20px;padding:12px 16px;background:#e8eaf6;"
                + "border-left:4px solid #3949ab;border-radius:4px;font-size:12px;color:#283593;\">"
                + "<strong>Core holdings: " + headline + ".</strong> " + names + ".<br>"
                + "<span style=\"color:#555;\">" + gloss + "</span></div>";
    }

    /** Take the day's slot for this alert unless dedup is off, in which case report it freely. */
    private boolean claim(String alertKey, boolean recordDedup) {
        return !recordDedup || sentAlertsToday.add(alertKey);
    }

    /**
     * Claim the day's slot explicitly. Returns false when this alert has already gone out today.
     * Used by the suppression path, which evaluates with dedup off and must therefore dedup by
     * hand whatever it decides to send.
     */
    boolean markSent(String symbol, String alertType) {
        return sentAlertsToday.add(symbol + "|" + alertType);
    }

    /** Visible for tests: has this alert already been sent today? */
    boolean alreadySentToday(String symbol, String alertType) {
        return sentAlertsToday.contains(symbol + "|" + alertType);
    }

    /**
     * A deep drawdown on a core holding is not a price problem to react to, it is a thesis to
     * re-read. The alert is retained in both modes and re-titled to say so (SPEC §35.5).
     */
    private ExitAlert retitleForCore(ExitAlert a) {
        if (!ALERT_DEEP_LOSS.equals(a.alertType())) return a;
        return new ExitAlert(a.symbol(), a.alertType(), a.urgency(),
                "Deep drawdown on a core holding - review the thesis, not the price",
                a.description(),
                "Re-read your invalidation triggers for this stock. Exit only if one has actually "
                        + "been hit; a fall on its own is not one of them",
                a.holding());
    }

    private void sendAlertEmail(List<ExitAlert> alerts,
                                Map<String, List<String>> coreTechnicalAlerts,
                                boolean suppression) {
        try {
            // Sort: URGENT first, then WARNING, then OPPORTUNITY
            alerts.sort(Comparator.comparingInt(a -> urgencyOrder(a.urgency)));

            long urgentCount = alerts.stream().filter(a -> URGENCY_URGENT.equals(a.urgency)).count();
            long warningCount = alerts.stream().filter(a -> URGENCY_WARNING.equals(a.urgency)).count();
            long opportunityCount = alerts.stream().filter(a -> URGENCY_OPPORTUNITY.equals(a.urgency)).count();

            StringBuilder content = new StringBuilder();

            // Summary card at the top
            content.append("<div style=\"margin-bottom:24px;\">");
            content.append("<div style=\"display:flex;flex-wrap:wrap;gap:12px;\">");
            if (urgentCount > 0) {
                content.append(buildSummaryPill("URGENT", urgentCount, "#c62828", "#ffebee"));
            }
            if (warningCount > 0) {
                content.append(buildSummaryPill("WARNING", warningCount, "#e65100", "#fff3e0"));
            }
            if (opportunityCount > 0) {
                content.append(buildSummaryPill("OPPORTUNITY", opportunityCount, "#2e7d32", "#e8f5e9"));
            }
            content.append(buildSummaryPill("TOTAL", alerts.size(), "#1a237e", "#e8eaf6"));
            content.append("</div>");
            content.append("</div>");

            // Alert time info
            content.append(String.format(
                    "<div style=\"font-size:12px;color:#666;margin-bottom:20px;\">Checked at %s IST on %s</div>",
                    LocalDateTime.now().format(TIME_FORMATTER),
                    LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"))));

            // Group by urgency
            Map<String, List<ExitAlert>> grouped = alerts.stream()
                    .collect(Collectors.groupingBy(a -> a.urgency, LinkedHashMap::new, Collectors.toList()));

            for (Map.Entry<String, List<ExitAlert>> entry : grouped.entrySet()) {
                String urgency = entry.getKey();
                List<ExitAlert> groupAlerts = entry.getValue();

                String sectionIcon;
                switch (urgency) {
                    case URGENCY_URGENT -> sectionIcon = "&#128680;";
                    case URGENCY_WARNING -> sectionIcon = "&#9888;";
                    default -> sectionIcon = "&#128161;";
                }

                content.append(templateService.section(sectionIcon, urgency + " Alerts (" + groupAlerts.size() + ")", ""));

                for (ExitAlert alert : groupAlerts) {
                    content.append(buildAlertCard(alert));
                }
            }

            // AI Exit Guidance
            StringBuilder ctx = new StringBuilder("Exit timing alerts for my Indian stock holdings:\n");
            for (ExitAlert alert : alerts) {
                ctx.append(String.format("%s: %s (%s) - %s\n", alert.symbol, alert.alertType, alert.urgency, alert.description));
            }
            ctx.append("\nPrioritize these alerts. Which ones need immediate action? Suggest specific exit strategies.");
            content.append(aiService.buildAiHtmlSection("AI Exit Guidance",
                    "You are an expert Indian stock market trader advising on exit timing. "
                    + "Be decisive and actionable in 3-5 bullet points. Prioritize by urgency. Use plain text, no markdown.",
                    ctx.toString()));

            content.append(buildCoreHoldingFooter(coreTechnicalAlerts, suppression));

            // Disclaimer
            content.append("<div style=\"margin-top:24px;padding:12px;background:#f5f5f5;border-radius:8px;"
                    + "font-size:11px;color:#888;\">"
                    + "These alerts are generated automatically based on technical analysis. "
                    + "Always verify with your own research before making trading decisions. "
                    + "Past performance does not guarantee future results.</div>");

            String subject = String.format("EXIT ALERT - %d Holdings Need Attention", alerts.size());

            String html = templateService.buildEmailTemplate(
                    "&#128680; Exit Timing Alert",
                    String.format("%d holdings require attention", alerts.size()),
                    content.toString()
            );

            emailNotificationService.sendHtmlEmail(subject, html);
            log.info("Exit timing alert email sent with {} alerts ({} urgent, {} warning, {} opportunity)",
                    alerts.size(), urgentCount, warningCount, opportunityCount);

        } catch (Exception e) {
            log.error("Failed to send exit timing alert email: {}", e.getMessage(), e);
        }
    }

    private String buildSummaryPill(String label, long count, String textColor, String bgColor) {
        return String.format(
                "<div style=\"display:inline-block;padding:10px 18px;border-radius:8px;"
                + "background:%s;text-align:center;min-width:100px;\">"
                + "<div style=\"font-size:11px;color:%s;text-transform:uppercase;letter-spacing:0.5px;"
                + "font-weight:600;\">%s</div>"
                + "<div style=\"font-size:24px;font-weight:700;color:%s;margin-top:2px;\">%d</div>"
                + "</div>",
                bgColor, textColor, label, textColor, count);
    }

    private String buildAlertCard(ExitAlert alert) {
        String borderColor;
        String badgeBg;
        String badgeColor;
        switch (alert.urgency) {
            case URGENCY_URGENT -> {
                borderColor = "#c62828";
                badgeBg = "#ffebee";
                badgeColor = "#c62828";
            }
            case URGENCY_WARNING -> {
                borderColor = "#f57c00";
                badgeBg = "#fff3e0";
                badgeColor = "#e65100";
            }
            default -> {
                borderColor = "#2e7d32";
                badgeBg = "#e8f5e9";
                badgeColor = "#2e7d32";
            }
        }

        HoldingsEntity h = alert.holding;
        StringBuilder card = new StringBuilder();

        // Card container with colored left border
        card.append(String.format(
                "<div style=\"border:1px solid #e0e0e0;border-left:5px solid %s;border-radius:8px;"
                + "padding:16px;margin-bottom:16px;background:#fff;\">",
                borderColor));

        // Header row: symbol + badge
        card.append("<div style=\"display:flex;justify-content:space-between;align-items:center;"
                + "margin-bottom:12px;padding-bottom:10px;border-bottom:1px solid #f0f0f0;\">");
        card.append(String.format(
                "<span style=\"font-size:17px;font-weight:700;color:#1a237e;\">%s</span>",
                cleanSymbol(alert.symbol)));
        card.append(String.format(
                "<span style=\"display:inline-block;padding:4px 10px;border-radius:12px;font-size:11px;"
                + "font-weight:600;text-transform:uppercase;background:%s;color:%s;\">%s</span>",
                badgeBg, badgeColor, alert.alertType.replace("_", " ")));
        card.append("</div>");

        // Alert title
        card.append(String.format(
                "<div style=\"font-size:14px;font-weight:600;color:#333;margin-bottom:8px;\">%s</div>",
                alert.title));

        // Alert description
        card.append(String.format(
                "<div style=\"font-size:13px;color:#555;margin-bottom:14px;line-height:1.5;\">%s</div>",
                alert.description));

        // Key metrics grid
        card.append("<div style=\"display:flex;flex-wrap:wrap;gap:10px;margin-bottom:14px;\">");
        card.append(buildMetricCell("Current Price", String.format("Rs.%.2f", h.getCurrentPrice())));
        card.append(buildMetricCell("P&L", String.format("%s%.1f%%", h.getPnlPercent() >= 0 ? "+" : "", h.getPnlPercent()),
                h.getPnlPercent() >= 0 ? "#2e7d32" : "#c62828"));
        if (h.getRsi14() != null) {
            String rsiColor = h.getRsi14() > 70 ? "#c62828" : (h.getRsi14() < 30 ? "#2e7d32" : "#333");
            card.append(buildMetricCell("RSI (14)", String.format("%.1f", h.getRsi14()), rsiColor));
        }
        if (h.getSupport1() != null && h.getSupport1() > 0) {
            card.append(buildMetricCell("Support 1", String.format("Rs.%.2f", h.getSupport1())));
        }
        if (h.getResistance1() != null && h.getResistance1() > 0) {
            card.append(buildMetricCell("Resistance 1", String.format("Rs.%.2f", h.getResistance1())));
        }
        if (h.getOverallScore() != null) {
            String scoreColor = h.getOverallScore() >= 60 ? "#2e7d32" : (h.getOverallScore() >= 40 ? "#f57c00" : "#c62828");
            card.append(buildMetricCell("Score", String.valueOf(h.getOverallScore()) + "/100", scoreColor));
        }
        if (h.getTrendDirection() != null) {
            String trendColor = "BULLISH".equals(h.getTrendDirection()) ? "#2e7d32"
                    : ("BEARISH".equals(h.getTrendDirection()) ? "#c62828" : "#666");
            card.append(buildMetricCell("Trend", h.getTrendDirection(), trendColor));
        }
        card.append(buildMetricCell("Avg Price", String.format("Rs.%.2f", h.getAveragePrice())));
        card.append("</div>");

        // Suggested action box
        card.append(String.format(
                "<div style=\"padding:10px 14px;background:#f8f9fa;border-radius:6px;border-left:3px solid %s;\">"
                + "<div style=\"font-size:11px;color:#666;text-transform:uppercase;letter-spacing:0.5px;"
                + "margin-bottom:4px;font-weight:600;\">Suggested Action</div>"
                + "<div style=\"font-size:13px;color:#333;font-weight:500;\">%s</div>"
                + "</div>",
                borderColor, alert.suggestedAction));

        card.append("</div>");
        return card.toString();
    }

    private String buildMetricCell(String label, String value) {
        return buildMetricCell(label, value, "#333");
    }

    private String buildMetricCell(String label, String value, String valueColor) {
        return String.format(
                "<div style=\"flex:1;min-width:90px;padding:8px 10px;background:#f8f9fa;border-radius:6px;"
                + "text-align:center;\">"
                + "<div style=\"font-size:10px;color:#888;text-transform:uppercase;letter-spacing:0.3px;\">%s</div>"
                + "<div style=\"font-size:14px;font-weight:700;color:%s;margin-top:2px;\">%s</div>"
                + "</div>",
                label, valueColor, value);
    }

    private String cleanSymbol(String symbol) {
        if (symbol != null && symbol.contains(":")) {
            return symbol.substring(symbol.indexOf(':') + 1);
        }
        return symbol;
    }

    private int urgencyOrder(String urgency) {
        return switch (urgency) {
            case URGENCY_URGENT -> 0;
            case URGENCY_WARNING -> 1;
            case URGENCY_OPPORTUNITY -> 2;
            default -> 3;
        };
    }

    /**
     * Internal record representing a single exit alert.
     */
    record ExitAlert(
            String symbol,
            String alertType,
            String urgency,
            String title,
            String description,
            String suggestedAction,
            HoldingsEntity holding
    ) {}
}
