package com.example.trading.notification;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Service for generating professional HTML email templates with CSS styling.
 */
@Service
public class EmailTemplateService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    /**
     * Common CSS styles for all email templates.
     */
    private String getCommonStyles() {
        return """
            <style>
                /* Reset and Base Styles */
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
                    line-height: 1.6;
                    color: #333333;
                    background-color: #f5f5f5;
                }
                .email-container {
                    max-width: 800px;
                    margin: 0 auto;
                    background-color: #ffffff;
                }

                /* Header Styles */
                .header {
                    background: linear-gradient(135deg, #1a237e 0%, #3949ab 100%);
                    color: #ffffff;
                    padding: 30px;
                    text-align: center;
                }
                .header h1 {
                    font-size: 24px;
                    font-weight: 600;
                    margin-bottom: 8px;
                }
                .header .subtitle {
                    font-size: 14px;
                    opacity: 0.9;
                }
                .header .date {
                    font-size: 13px;
                    opacity: 0.8;
                    margin-top: 10px;
                }

                /* Content Area */
                .content {
                    padding: 30px;
                }

                /* Section Styles */
                .section {
                    margin-bottom: 30px;
                }
                .section-title {
                    font-size: 16px;
                    font-weight: 600;
                    color: #1a237e;
                    padding-bottom: 10px;
                    border-bottom: 2px solid #e8eaf6;
                    margin-bottom: 15px;
                    display: flex;
                    align-items: center;
                    gap: 8px;
                }
                .section-icon {
                    font-size: 18px;
                }

                /* Summary Cards */
                .summary-grid {
                    display: flex;
                    flex-wrap: wrap;
                    gap: 15px;
                    margin-bottom: 20px;
                }
                .summary-card {
                    flex: 1;
                    min-width: 150px;
                    background: #f8f9fa;
                    border-radius: 8px;
                    padding: 15px;
                    text-align: center;
                    border-left: 4px solid #3949ab;
                }
                .summary-card.profit { border-left-color: #2e7d32; background: #e8f5e9; }
                .summary-card.loss { border-left-color: #c62828; background: #ffebee; }
                .summary-card.neutral { border-left-color: #f57c00; background: #fff3e0; }
                .summary-card .label {
                    font-size: 12px;
                    color: #666;
                    text-transform: uppercase;
                    letter-spacing: 0.5px;
                }
                .summary-card .value {
                    font-size: 22px;
                    font-weight: 700;
                    color: #1a237e;
                    margin-top: 5px;
                }
                .summary-card.profit .value { color: #2e7d32; }
                .summary-card.loss .value { color: #c62828; }

                /* Table Styles */
                .data-table {
                    width: 100%;
                    border-collapse: collapse;
                    font-size: 13px;
                    margin-top: 10px;
                }
                .data-table th {
                    background: #1a237e;
                    color: #ffffff;
                    padding: 12px 10px;
                    text-align: left;
                    font-weight: 600;
                    font-size: 11px;
                    text-transform: uppercase;
                    letter-spacing: 0.5px;
                }
                .data-table th:first-child { border-radius: 6px 0 0 0; }
                .data-table th:last-child { border-radius: 0 6px 0 0; }
                .data-table td {
                    padding: 10px;
                    border-bottom: 1px solid #e0e0e0;
                }
                .data-table tr:nth-child(even) { background-color: #f8f9fa; }
                .data-table tr:hover { background-color: #e8eaf6; }
                .data-table tr:last-child td:first-child { border-radius: 0 0 0 6px; }
                .data-table tr:last-child td:last-child { border-radius: 0 0 6px 0; }

                /* Number Formatting */
                .text-right { text-align: right; }
                .text-center { text-align: center; }
                .positive { color: #2e7d32; font-weight: 600; }
                .negative { color: #c62828; font-weight: 600; }
                .neutral { color: #f57c00; }

                /* Badges and Tags */
                .badge {
                    display: inline-block;
                    padding: 4px 10px;
                    border-radius: 12px;
                    font-size: 11px;
                    font-weight: 600;
                    text-transform: uppercase;
                }
                .badge-buy { background: #e8f5e9; color: #2e7d32; }
                .badge-sell { background: #ffebee; color: #c62828; }
                .badge-hold { background: #e3f2fd; color: #1565c0; }
                .badge-warning { background: #fff3e0; color: #e65100; }
                .badge-bullish { background: #e8f5e9; color: #2e7d32; }
                .badge-bearish { background: #ffebee; color: #c62828; }
                .badge-sideways { background: #f5f5f5; color: #616161; }

                /* Alert Boxes */
                .alert {
                    padding: 15px;
                    border-radius: 8px;
                    margin-bottom: 15px;
                    display: flex;
                    align-items: flex-start;
                    gap: 12px;
                }
                .alert-icon { font-size: 20px; }
                .alert-content { flex: 1; }
                .alert-title { font-weight: 600; margin-bottom: 4px; }
                .alert-danger {
                    background: #ffebee;
                    border-left: 4px solid #c62828;
                    color: #b71c1c;
                }
                .alert-success {
                    background: #e8f5e9;
                    border-left: 4px solid #2e7d32;
                    color: #1b5e20;
                }
                .alert-warning {
                    background: #fff3e0;
                    border-left: 4px solid #f57c00;
                    color: #e65100;
                }
                .alert-info {
                    background: #e3f2fd;
                    border-left: 4px solid #1976d2;
                    color: #0d47a1;
                }

                /* Info Box */
                .info-box {
                    background: #f5f5f5;
                    border-radius: 8px;
                    padding: 15px;
                    margin-top: 15px;
                }
                .info-box h4 {
                    font-size: 13px;
                    color: #1a237e;
                    margin-bottom: 10px;
                }
                .info-box ul {
                    margin: 0;
                    padding-left: 20px;
                    font-size: 12px;
                    color: #666;
                }
                .info-box li { margin-bottom: 5px; }

                /* Footer */
                .footer {
                    background: #f5f5f5;
                    padding: 20px 30px;
                    text-align: center;
                    font-size: 12px;
                    color: #666;
                    border-top: 1px solid #e0e0e0;
                }
                .footer p { margin-bottom: 5px; }
                .footer .brand {
                    font-weight: 600;
                    color: #1a237e;
                }

                /* Position Details Card */
                .position-card {
                    border: 1px solid #e0e0e0;
                    border-radius: 8px;
                    padding: 15px;
                    margin-bottom: 15px;
                    background: #fff;
                }
                .position-header {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    margin-bottom: 12px;
                    padding-bottom: 10px;
                    border-bottom: 1px solid #e0e0e0;
                }
                .position-symbol {
                    font-size: 16px;
                    font-weight: 700;
                    color: #1a237e;
                }
                .position-grid {
                    display: flex;
                    flex-wrap: wrap;
                    gap: 15px;
                }
                .position-item {
                    flex: 1;
                    min-width: 100px;
                }
                .position-label {
                    font-size: 11px;
                    color: #666;
                    text-transform: uppercase;
                }
                .position-value {
                    font-size: 14px;
                    font-weight: 600;
                    color: #333;
                }

                /* Trend Indicators */
                .trend-up::before { content: "▲ "; color: #2e7d32; }
                .trend-down::before { content: "▼ "; color: #c62828; }

                /* Score Bar */
                .score-bar {
                    height: 8px;
                    background: #e0e0e0;
                    border-radius: 4px;
                    overflow: hidden;
                    margin-top: 5px;
                }
                .score-fill {
                    height: 100%;
                    border-radius: 4px;
                }
                .score-high { background: linear-gradient(90deg, #4caf50, #2e7d32); }
                .score-medium { background: linear-gradient(90deg, #ff9800, #f57c00); }
                .score-low { background: linear-gradient(90deg, #f44336, #c62828); }
            </style>
        """;
    }

    /**
     * Build the email wrapper with header and footer.
     */
    public String buildEmailTemplate(String title, String subtitle, String content) {
        return String.format("""
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s</title>
                %s
            </head>
            <body>
                <div class="email-container">
                    <div class="header">
                        <h1>%s</h1>
                        <div class="subtitle">%s</div>
                        <div class="date">%s</div>
                    </div>
                    <div class="content">
                        %s
                    </div>
                    <div class="footer">
                        <p class="brand">Intraday Trading System</p>
                        <p>This is an automated report. Please verify before taking any action.</p>
                        <p>Generated at %s</p>
                    </div>
                </div>
            </body>
            </html>
            """,
            title,
            getCommonStyles(),
            title,
            subtitle,
            LocalDate.now().format(DATE_FORMATTER),
            content,
            LocalDateTime.now().format(DATETIME_FORMATTER)
        );
    }

    /**
     * Create a summary card.
     */
    public String summaryCard(String label, String value, String type) {
        return String.format("""
            <div class="summary-card %s">
                <div class="label">%s</div>
                <div class="value">%s</div>
            </div>
            """, type, label, value);
    }

    /**
     * Create a section with title.
     */
    public String section(String icon, String title, String content) {
        return String.format("""
            <div class="section">
                <div class="section-title">
                    <span class="section-icon">%s</span>
                    %s
                </div>
                %s
            </div>
            """, icon, title, content);
    }

    /**
     * Create an alert box.
     */
    public String alert(String type, String icon, String title, String message) {
        return String.format("""
            <div class="alert alert-%s">
                <div class="alert-icon">%s</div>
                <div class="alert-content">
                    <div class="alert-title">%s</div>
                    <div>%s</div>
                </div>
            </div>
            """, type, icon, title, message);
    }

    /**
     * Create a badge element.
     */
    public String badge(String text, String type) {
        return String.format("<span class=\"badge badge-%s\">%s</span>", type, text);
    }

    /**
     * Format number with color based on sign.
     */
    public String formatPnL(double value, boolean showSign) {
        String cssClass = value >= 0 ? "positive" : "negative";
        String sign = showSign ? (value >= 0 ? "+" : "") : "";
        return String.format("<span class=\"%s\">%s%.2f</span>", cssClass, sign, value);
    }

    /**
     * Format percentage with color.
     */
    public String formatPercent(double value) {
        String cssClass = value >= 0 ? "positive" : "negative";
        String sign = value >= 0 ? "+" : "";
        return String.format("<span class=\"%s\">%s%.2f%%</span>", cssClass, sign, value);
    }

    /**
     * Start a table.
     */
    public String tableStart(String... headers) {
        StringBuilder sb = new StringBuilder("<table class=\"data-table\"><thead><tr>");
        for (String header : headers) {
            sb.append("<th>").append(header).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        return sb.toString();
    }

    /**
     * Add a table row.
     */
    public String tableRow(String... cells) {
        StringBuilder sb = new StringBuilder("<tr>");
        for (String cell : cells) {
            sb.append("<td>").append(cell).append("</td>");
        }
        sb.append("</tr>");
        return sb.toString();
    }

    /**
     * End a table.
     */
    public String tableEnd() {
        return "</tbody></table>";
    }

    /**
     * Create an info box with bullet points.
     */
    public String infoBox(String title, String... items) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"info-box\"><h4>").append(title).append("</h4><ul>");
        for (String item : items) {
            sb.append("<li>").append(item).append("</li>");
        }
        sb.append("</ul></div>");
        return sb.toString();
    }

    /**
     * Get recommendation badge type.
     */
    public String getRecommendationBadgeType(String recommendation) {
        if (recommendation == null) return "hold";
        return switch (recommendation) {
            case "STRONG_BUY", "BUY" -> "buy";
            case "STRONG_SELL", "SELL" -> "sell";
            case "BOOK_PROFIT" -> "warning";
            default -> "hold";
        };
    }

    /**
     * Get trend badge type.
     */
    public String getTrendBadgeType(String trend) {
        if (trend == null) return "sideways";
        return switch (trend) {
            case "BULLISH" -> "bullish";
            case "BEARISH" -> "bearish";
            default -> "sideways";
        };
    }

    /**
     * Format action text for holdings.
     */
    public String getActionText(String recommendation) {
        if (recommendation == null) return "HOLD";
        return switch (recommendation) {
            case "STRONG_BUY" -> "BUY MORE";
            case "BUY" -> "ACCUMULATE";
            case "SELL" -> "PARTIAL EXIT";
            case "STRONG_SELL" -> "EXIT NOW";
            case "BOOK_PROFIT" -> "BOOK 50%";
            default -> "HOLD";
        };
    }

    /**
     * Start a section with title and icon (for building content incrementally).
     */
    public String sectionStart(String title, String icon) {
        return String.format("""
            <div class="section">
                <div class="section-title">
                    <span class="section-icon">%s</span>
                    %s
                </div>
            """, icon, title);
    }

    /**
     * End a section started with sectionStart.
     */
    public String sectionEnd() {
        return "</div>";
    }

    /**
     * Create a score bar visualization.
     */
    public String scoreBar(int score) {
        String fillClass = score >= 70 ? "score-high" : (score >= 40 ? "score-medium" : "score-low");
        return String.format("""
            <div class="score-bar">
                <div class="score-fill %s" style="width: %d%%;"></div>
            </div>
            """, fillClass, Math.min(score, 100));
    }

    /**
     * Create a position card for trade alerts.
     */
    public String positionCard(String symbol, String action, String... details) {
        StringBuilder grid = new StringBuilder("<div class=\"position-grid\">");
        for (int i = 0; i < details.length; i += 2) {
            if (i + 1 < details.length) {
                grid.append(String.format("""
                    <div class="position-item">
                        <div class="position-label">%s</div>
                        <div class="position-value">%s</div>
                    </div>
                    """, details[i], details[i + 1]));
            }
        }
        grid.append("</div>");

        String badgeType = action.contains("BUY") ? "buy" : "sell";

        return String.format("""
            <div class="position-card">
                <div class="position-header">
                    <span class="position-symbol">%s</span>
                    %s
                </div>
                %s
            </div>
            """, symbol, badge(action, badgeType), grid.toString());
    }
}
