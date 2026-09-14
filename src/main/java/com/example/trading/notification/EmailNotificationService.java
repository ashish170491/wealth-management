package com.example.trading.notification;

import com.example.trading.persistence.PositionEntity;
import com.example.trading.persistence.TradeEntity;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmailNotificationService {

    private final JavaMailSender mailSender;
    private final EmailTemplateService templateService;

    @Value("${spring.mail.username}")
    private String senderEmail;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /**
     * Sends an HTML email alert for a new trade.
     */
    public void sendTradeAlert(TradeEntity trade, String strategyName) {
        try {
            if (!isEmailConfigured()) {
                log.warn("Email Notification Skipped: Sender details not configured.");
                return;
            }

            String actionIcon = trade.getAction().contains("BUY") ? "&#128200;" : "&#128201;";

            StringBuilder content = new StringBuilder();

            // Trade Summary Card
            content.append(templateService.positionCard(
                trade.getSymbol(),
                trade.getAction(),
                "Strategy", strategyName,
                "Quantity", String.valueOf(trade.getQuantity()),
                "Entry Price", String.format("Rs.%.2f", trade.getEntryPrice()),
                "Stop Loss", String.format("Rs.%.2f", trade.getStopLoss()),
                "Target", String.format("Rs.%.2f", trade.getTarget()),
                "Time", LocalDateTime.now().format(TIME_FORMATTER)
            ));

            // Risk/Reward Info
            double riskAmount = Math.abs(trade.getEntryPrice() - trade.getStopLoss()) * trade.getQuantity();
            double rewardAmount = Math.abs(trade.getTarget() - trade.getEntryPrice()) * trade.getQuantity();
            double riskRewardRatio = riskAmount > 0 ? rewardAmount / riskAmount : 0;

            content.append("<div style=\"margin-top: 20px;\">");
            content.append("<div class=\"summary-grid\">");
            content.append(templateService.summaryCard("Risk Amount", String.format("Rs.%.2f", riskAmount), "loss"));
            content.append(templateService.summaryCard("Reward Amount", String.format("Rs.%.2f", rewardAmount), "profit"));
            content.append(templateService.summaryCard("R:R Ratio", String.format("1:%.1f", riskRewardRatio), ""));
            content.append("</div>");
            content.append("</div>");

            String subject = String.format("%s Trade Alert: %s %s (Qty: %d)",
                    trade.getAction(), trade.getSymbol(),
                    trade.getAction().contains("BUY") ? "BOUGHT" : "SOLD",
                    trade.getQuantity());

            String html = templateService.buildEmailTemplate(
                actionIcon + " Trade Executed",
                strategyName + " Strategy",
                content.toString()
            );

            sendHtmlEmail(subject, html);
            log.info("Email Alert sent for {} on {}", trade.getAction(), trade.getSymbol());

        } catch (Exception e) {
            log.error("Failed to send email alert: {}", e.getMessage());
        }
    }

    /**
     * Sends end-of-day trading summary with P&L details from closed positions.
     */
    public void sendEndOfDaySummary(List<PositionEntity> closedPositions, List<PositionEntity> openPositions) {
        try {
            if (!isEmailConfigured()) {
                log.warn("EOD Email Notification Skipped: Sender details not configured.");
                return;
            }

            if ((closedPositions == null || closedPositions.isEmpty()) &&
                (openPositions == null || openPositions.isEmpty())) {
                log.info("No positions today. Skipping EOD email summary.");
                return;
            }

            int totalPositions = (closedPositions != null ? closedPositions.size() : 0) +
                                 (openPositions != null ? openPositions.size() : 0);
            int closedCount = closedPositions != null ? closedPositions.size() : 0;
            int stillOpenCount = openPositions != null ? openPositions.size() : 0;

            // Calculate P&L from CLOSED positions
            double totalPnL = closedPositions != null ? closedPositions.stream()
                    .mapToDouble(PositionEntity::getRealizedPnL)
                    .sum() : 0;

            long winningTrades = closedPositions != null ? closedPositions.stream()
                    .filter(p -> p.getRealizedPnL() > 0)
                    .count() : 0;

            long losingTrades = closedPositions != null ? closedPositions.stream()
                    .filter(p -> p.getRealizedPnL() < 0)
                    .count() : 0;

            double grossProfit = closedPositions != null ? closedPositions.stream()
                    .filter(p -> p.getRealizedPnL() > 0)
                    .mapToDouble(PositionEntity::getRealizedPnL)
                    .sum() : 0;

            double grossLoss = closedPositions != null ? Math.abs(closedPositions.stream()
                    .filter(p -> p.getRealizedPnL() < 0)
                    .mapToDouble(PositionEntity::getRealizedPnL)
                    .sum()) : 0;

            double winRate = closedCount > 0 ? (winningTrades * 100.0 / closedCount) : 0.0;

            double unrealizedPnL = openPositions != null ? openPositions.stream()
                    .mapToDouble(PositionEntity::getUnrealizedPnL)
                    .sum() : 0;

            StringBuilder content = new StringBuilder();

            // Summary Cards
            content.append("<div class=\"summary-grid\">");
            content.append(templateService.summaryCard("Total Positions", String.valueOf(totalPositions), ""));
            content.append(templateService.summaryCard("Closed", String.valueOf(closedCount), ""));
            content.append(templateService.summaryCard("Still Open", String.valueOf(stillOpenCount), stillOpenCount > 0 ? "neutral" : ""));
            content.append(templateService.summaryCard("Net P&L", String.format("Rs.%.2f", totalPnL), totalPnL >= 0 ? "profit" : "loss"));
            content.append(templateService.summaryCard("Win Rate", String.format("%.1f%%", winRate), winRate >= 50 ? "profit" : "loss"));
            content.append("</div>");

            // P&L Breakdown Section
            StringBuilder pnlContent = new StringBuilder();
            pnlContent.append("<div class=\"summary-grid\">");
            pnlContent.append(templateService.summaryCard("Gross Profit", String.format("Rs.%.2f", grossProfit), "profit"));
            pnlContent.append(templateService.summaryCard("Gross Loss", String.format("Rs.%.2f", grossLoss), "loss"));
            pnlContent.append(templateService.summaryCard("Winners", String.valueOf(winningTrades), "profit"));
            pnlContent.append(templateService.summaryCard("Losers", String.valueOf(losingTrades), "loss"));
            pnlContent.append("</div>");
            content.append(templateService.section("&#128176;", "P&L Breakdown", pnlContent.toString()));

            // Closed Positions Table
            if (closedPositions != null && !closedPositions.isEmpty()) {
                StringBuilder table = new StringBuilder();
                table.append(templateService.tableStart("Symbol", "Action", "Qty", "Entry", "Exit", "P&L", "Result"));

                for (PositionEntity pos : closedPositions) {
                    String result = pos.getRealizedPnL() >= 0 ? "WIN" : "LOSS";
                    String resultBadge = templateService.badge(result, pos.getRealizedPnL() >= 0 ? "buy" : "sell");

                    table.append(templateService.tableRow(
                        "<strong>" + pos.getSymbol() + "</strong>",
                        pos.getAction(),
                        String.valueOf(pos.getQuantity()),
                        String.format("Rs.%.2f", pos.getAverageEntryPrice()),
                        String.format("Rs.%.2f", pos.getCurrentPrice()),
                        templateService.formatPnL(pos.getRealizedPnL(), true),
                        resultBadge
                    ));
                }
                table.append(templateService.tableEnd());
                content.append(templateService.section("&#9989;", "Closed Positions", table.toString()));
            }

            // Open Positions Warning
            if (openPositions != null && !openPositions.isEmpty()) {
                content.append(templateService.alert("warning", "&#9888;",
                    "Warning: " + stillOpenCount + " Position(s) Still Open",
                    String.format("Unrealized P&L: Rs.%.2f. These positions were not squared off.", unrealizedPnL)));

                StringBuilder table = new StringBuilder();
                table.append(templateService.tableStart("Symbol", "Action", "Qty", "Entry", "Current", "Unrealized P&L"));

                for (PositionEntity pos : openPositions) {
                    table.append(templateService.tableRow(
                        "<strong>" + pos.getSymbol() + "</strong>",
                        pos.getAction(),
                        String.valueOf(pos.getQuantity()),
                        String.format("Rs.%.2f", pos.getAverageEntryPrice()),
                        String.format("Rs.%.2f", pos.getCurrentPrice()),
                        templateService.formatPnL(pos.getUnrealizedPnL(), true)
                    ));
                }
                table.append(templateService.tableEnd());
                content.append(table);
            }

            // Next Session Info
            content.append(templateService.infoBox(
                "&#128197; Next Trading Session",
                "Market opens at 09:15 AM IST",
                "Pre-market analysis will be available",
                "Review your trading strategy before the session"
            ));

            String pnlText = totalPnL >= 0 ? "Profit" : "Loss";
            String subject = String.format("EOD Summary - %s | Net P&L: Rs.%.2f (%s)",
                LocalDateTime.now().format(DATE_FORMATTER),
                Math.abs(totalPnL), pnlText);

            String html = templateService.buildEmailTemplate(
                "End of Day Summary",
                LocalDateTime.now().format(DATE_FORMATTER),
                content.toString()
            );

            sendHtmlEmail(subject, html);
            log.info("End-of-day summary email sent successfully. Closed: {}, Open: {}, Net P&L: {}",
                closedCount, stillOpenCount, String.format("%.2f", totalPnL));

        } catch (Exception e) {
            log.error("Failed to send end-of-day summary email: {}", e.getMessage(), e);
        }
    }

    /**
     * Sends email alert when stop loss is hit.
     */
    public void sendStopLossAlert(PositionEntity position, double exitPrice) {
        try {
            if (!isEmailConfigured()) {
                log.warn("Stop Loss Email Notification Skipped: Sender details not configured.");
                return;
            }

            boolean isProfit = position.getRealizedPnL() >= 0;
            double pnlPercent = (position.getRealizedPnL() / (position.getAverageEntryPrice() * position.getQuantity())) * 100;

            StringBuilder content = new StringBuilder();

            // Alert Box
            String alertType = isProfit ? "success" : "danger";
            String alertIcon = isProfit ? "&#128176;" : "&#128680;";
            String alertTitle = isProfit ? "Stop Loss Hit - Profit Secured!" : "Stop Loss Hit - Loss Incurred";
            String alertMsg = String.format("Position exited at Rs.%.2f with %s of Rs.%.2f",
                    exitPrice, isProfit ? "profit" : "loss", Math.abs(position.getRealizedPnL()));

            content.append(templateService.alert(alertType, alertIcon, alertTitle, alertMsg));

            // Position Details Card
            content.append(templateService.positionCard(
                position.getSymbol(),
                "STOP LOSS",
                "Action", position.getAction(),
                "Quantity", String.valueOf(position.getQuantity()),
                "Entry Price", String.format("Rs.%.2f", position.getAverageEntryPrice()),
                "Exit Price", String.format("Rs.%.2f", exitPrice),
                "Stop Loss", String.format("Rs.%.2f", position.getStopLoss()),
                "Target", String.format("Rs.%.2f", position.getTarget())
            ));

            // P&L Summary
            content.append("<div style=\"margin-top: 20px;\">");
            content.append("<div class=\"summary-grid\">");
            content.append(templateService.summaryCard("Realized P&L", String.format("Rs.%.2f", position.getRealizedPnL()), isProfit ? "profit" : "loss"));
            content.append(templateService.summaryCard("Return", String.format("%.2f%%", pnlPercent), isProfit ? "profit" : "loss"));
            content.append(templateService.summaryCard("Exit Time", LocalDateTime.now().format(TIME_FORMATTER), ""));
            content.append("</div>");
            content.append("</div>");

            String subject = String.format("STOP LOSS HIT: %s | P&L: Rs.%.2f",
                    position.getSymbol(), position.getRealizedPnL());

            String html = templateService.buildEmailTemplate(
                alertIcon + " Stop Loss Triggered",
                position.getSymbol(),
                content.toString()
            );

            sendHtmlEmail(subject, html);
            log.info("Stop Loss email alert sent for {} at Rs.{}, P&L: Rs.{}",
                position.getSymbol(), exitPrice, position.getRealizedPnL());

        } catch (Exception e) {
            log.error("Failed to send stop loss email alert: {}", e.getMessage());
        }
    }

    /**
     * Sends email alert when target is achieved.
     */
    public void sendTargetAchievedAlert(PositionEntity position, double exitPrice) {
        try {
            if (!isEmailConfigured()) {
                log.warn("Target Achieved Email Notification Skipped: Sender details not configured.");
                return;
            }

            double pnlPercent = (position.getRealizedPnL() / (position.getAverageEntryPrice() * position.getQuantity())) * 100;

            StringBuilder content = new StringBuilder();

            // Success Alert
            content.append(templateService.alert("success", "&#127942;",
                "Target Achieved - Profit Booked!",
                String.format("Congratulations! Position exited at Rs.%.2f with profit of Rs.%.2f", exitPrice, position.getRealizedPnL())));

            // Position Details Card
            content.append(templateService.positionCard(
                position.getSymbol(),
                "TARGET HIT",
                "Action", position.getAction(),
                "Quantity", String.valueOf(position.getQuantity()),
                "Entry Price", String.format("Rs.%.2f", position.getAverageEntryPrice()),
                "Exit Price", String.format("Rs.%.2f", exitPrice),
                "Target", String.format("Rs.%.2f", position.getTarget()),
                "Stop Loss", String.format("Rs.%.2f", position.getStopLoss())
            ));

            // Profit Summary
            content.append("<div style=\"margin-top: 20px;\">");
            content.append("<div class=\"summary-grid\">");
            content.append(templateService.summaryCard("Profit", String.format("Rs.%.2f", position.getRealizedPnL()), "profit"));
            content.append(templateService.summaryCard("Return", String.format("+%.2f%%", pnlPercent), "profit"));
            content.append(templateService.summaryCard("Exit Time", LocalDateTime.now().format(TIME_FORMATTER), ""));
            content.append("</div>");
            content.append("</div>");

            String subject = String.format("TARGET ACHIEVED: %s | Profit: Rs.%.2f",
                    position.getSymbol(), position.getRealizedPnL());

            String html = templateService.buildEmailTemplate(
                "&#127942; Target Achieved!",
                position.getSymbol(),
                content.toString()
            );

            sendHtmlEmail(subject, html);
            log.info("Target Achieved email alert sent for {} at Rs.{}, Profit: Rs.{}",
                position.getSymbol(), exitPrice, position.getRealizedPnL());

        } catch (Exception e) {
            log.error("Failed to send target achieved email alert: {}", e.getMessage());
        }
    }

    public void sendHtmlEmail(String subject, String htmlContent) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(senderEmail);
        helper.setTo(senderEmail);
        helper.setSubject(subject);
        helper.setText(htmlContent, true);

        mailSender.send(message);
    }

    private boolean isEmailConfigured() {
        return senderEmail != null &&
               !senderEmail.contains("placeholder") &&
               !senderEmail.equals("your-email@gmail.com");
    }
}
