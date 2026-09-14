package com.example.trading.multibagger;

import com.example.trading.ai.AiService;
import com.example.trading.ai.StockResearchService;
import com.example.trading.notification.EmailNotificationService;
import com.example.trading.notification.EmailTemplateService;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates and sends weekly Multibagger Screening email reports.
 * Also provides section builders for integration with other reports
 * (Morning Briefing, Holdings Report).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MultibaggerReportService {

    private final MultibaggerScreenerService screenerService;
    private final MultibaggerScoreRepository scoreRepository;
    private final EmailNotificationService emailNotificationService;
    private final EmailTemplateService templateService;
    private final MultibaggerConfig config;
    private final AiService aiService;
    private final StockResearchService stockResearchService;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /**
     * Send the full weekly multibagger screening report.
     */
    public void sendWeeklyReport() {
        try {
            List<MultibaggerScore> scores = screenerService.getLatestScores();
            if (scores.isEmpty()) {
                log.info("Multibagger Report: No screening data available, running screening first...");
                scores = screenerService.runFullScreening();
            }

            if (scores.isEmpty()) {
                log.warn("Multibagger Report: Screening returned no results");
                return;
            }

            String htmlContent = buildFullReport(scores);
            String today = LocalDate.now().format(DATE_FMT);
            String subject = "Multibagger Screening Report - " + today + " | " +
                    scores.stream().filter(s -> s.getCompositeScore() >= config.getMinScoreForCandidate()).count() + " Candidates Found";

            String html = templateService.buildEmailTemplate(
                    "Multibagger Screening Report",
                    "Potential Multi-bagger Stock Analysis",
                    htmlContent
            );

            emailNotificationService.sendHtmlEmail(subject, html);
            log.info("Multibagger Report: Weekly email sent successfully with {} stocks analyzed", scores.size());

        } catch (Exception e) {
            log.error("Multibagger Report: Failed to send weekly report: {}", e.getMessage(), e);
        }
    }

    /**
     * Build the weekly report as HTML for on-screen preview, without emailing it
     * (SPEC §27.6, same contract as the holdings previews).
     *
     * <p>Reads whatever the latest screening produced — it never triggers a screening of its
     * own, unlike {@link #sendWeeklyReport()}. A preview that silently kicked off a 12-minute
     * full scan would be a page-load trap of exactly the kind SPEC §27.4 forbids.
     */
    public String previewWeeklyReport() {
        List<MultibaggerScore> scores = screenerService.getLatestScores();
        if (scores.isEmpty()) {
            // In-memory cache is empty after every restart, so fall back to the most recent
            // screening date that actually has rows (Gotcha 20) rather than reporting nothing.
            List<LocalDate> dates = scoreRepository.findScreeningDates();
            if (dates != null && !dates.isEmpty()) {
                scores = scoreRepository.findByScreeningDateOrderByCompositeScoreDesc(dates.get(0))
                        .stream().map(this::toScore).collect(Collectors.toList());
            }
        }
        if (scores.isEmpty()) {
            return "<p>No screening data available yet. Run <code>POST /api/multibagger/screen</code> first.</p>";
        }
        return templateService.buildEmailTemplate(
                "Multibagger Screening Report",
                "Potential Multi-bagger Stock Analysis",
                buildFullReport(scores));
    }

    /** Map a persisted row back to the scoring DTO for rendering. */
    private MultibaggerScore toScore(MultibaggerScoreEntity e) {
        return MultibaggerScore.builder()
                .symbol(e.getSymbol())
                .tradingSymbol(e.getTradingSymbol())
                .industry(e.getIndustry())
                .currentPrice(e.getCurrentPrice())
                .marketCapCrores(e.getMarketCapCrores())
                .marketCapCategory(e.getMarketCapCategory())
                .technicalMomentumScore(e.getTechnicalMomentumScore())
                .volumeAccumulationScore(e.getVolumeAccumulationScore())
                .relativeStrengthScore(e.getRelativeStrengthScore())
                .priceStructureScore(e.getPriceStructureScore())
                .valuationScore(e.getValuationScore())
                .institutionalInterestScore(e.getInstitutionalInterestScore())
                .sectorTailwindScore(e.getSectorTailwindScore())
                .financialQualityScore(e.getFinancialQualityScore())
                .compositeScore(e.getCompositeScore())
                .percentileRank(e.getPercentileRank())
                .grade(e.getGrade())
                .verdict(e.getVerdict())
                .liquidityAdv20d(e.getLiquidityAdv20d())
                .liquidityTier(e.getLiquidityTier())
                .circuitDaysLast60(e.getCircuitDaysLast60())
                .underDiscoveryScore(e.getUnderDiscoveryScore())
                .insiderPulseVerdict(e.getInsiderPulseVerdict())
                .insiderNetBuy90dPct(e.getInsiderNetBuy90dPct())
                .insiderPulseScore(e.getInsiderPulseScore())
                .inHoldings(e.isInHoldings())
                .holdingsPnlPercent(e.getHoldingsPnlPercent())
                .bullishFactors(splitFactors(e.getBullishFactors()))
                .bearishFactors(splitFactors(e.getBearishFactors()))
                .build();
    }

    private static List<String> splitFactors(String packed) {
        if (packed == null || packed.isBlank()) return new java.util.ArrayList<>();
        return new java.util.ArrayList<>(List.of(packed.split("\\|")));
    }

    /**
     * Build the full standalone report HTML content.
     */
    private String buildFullReport(List<MultibaggerScore> scores) {
        StringBuilder content = new StringBuilder();

        // Summary cards
        long strongCandidates = scores.stream().filter(s -> "STRONG_MULTIBAGGER".equals(s.getVerdict())).count();
        long potentialCandidates = scores.stream().filter(s -> "POTENTIAL_MULTIBAGGER".equals(s.getVerdict())).count();
        long watchlistCount = scores.stream().filter(s -> "WATCHLIST".equals(s.getVerdict())).count();
        long inHoldings = scores.stream().filter(MultibaggerScore::isInHoldings).count();

        content.append("<div class=\"summary-grid\">");
        content.append(templateService.summaryCard("Total Screened", String.valueOf(scores.size()), ""));
        content.append(templateService.summaryCard("Strong Candidates", String.valueOf(strongCandidates), strongCandidates > 0 ? "profit" : ""));
        content.append(templateService.summaryCard("Potential Candidates", String.valueOf(potentialCandidates), potentialCandidates > 0 ? "profit" : ""));
        content.append(templateService.summaryCard("Watchlist", String.valueOf(watchlistCount), ""));
        content.append(templateService.summaryCard("In Your Holdings", String.valueOf(inHoldings), "neutral"));
        content.append("</div>");

        // Top candidates section
        List<MultibaggerScore> topCandidates = scores.stream()
                .filter(s -> s.getCompositeScore() >= config.getMinScoreForCandidate())
                .limit(config.getTopCandidatesInReport())
                .collect(Collectors.toList());

        if (!topCandidates.isEmpty()) {
            content.append(buildCandidatesTable("Top Multibagger Candidates", topCandidates));
        }

        // Under-the-radar section — the whole point of the early-discovery work
        content.append(buildUnderRadarSection(scores));

        // Per-cap-tier leaderboards — multibaggers usually emerge from small/micro tier
        content.append(buildCapTierLeaderboard(scores));

        // Holdings multibagger assessment
        List<MultibaggerScore> holdingsScores = scores.stream()
                .filter(MultibaggerScore::isInHoldings)
                .collect(Collectors.toList());

        if (!holdingsScores.isEmpty()) {
            content.append(buildHoldingsAssessment(holdingsScores));
        }

        // Sector-wise breakdown
        content.append(buildSectorBreakdown(scores));

        // Market cap breakdown
        content.append(buildMarketCapBreakdown(scores));

        // AI Analysis
        if (!topCandidates.isEmpty()) {
            StringBuilder ctx = new StringBuilder("Multibagger screening results for Indian stocks:\n");
            for (MultibaggerScore s : topCandidates) {
                ctx.append(String.format("%s: Score=%d, Verdict=%s, Grade=%s, Momentum=%d, Volume=%d, RelStrength=%d\n",
                        s.getSymbol(), s.getCompositeScore(), s.getVerdict(), s.getGrade(),
                        s.getTechnicalMomentumScore(), s.getVolumeAccumulationScore(), s.getRelativeStrengthScore()));
            }
            ctx.append("\nAnalyze these candidates. Which look most promising and why? Any red flags?");
            content.append(aiService.buildAiHtmlSection("AI Multibagger Analysis",
                    "You are an expert long-term Indian equity analyst specializing in identifying multi-bagger stocks. "
                    + "Provide concise insights in 4-6 bullet points. Focus on conviction picks and risks. Use plain text, no markdown.",
                    ctx.toString()));
        }

        // Deep AI Research for Top 3 Candidates
        if (!topCandidates.isEmpty() && aiService.isAvailable()) {
            content.append(templateService.section("&#128269;", "Deep AI Research — Top Candidates", ""));
            for (MultibaggerScore candidate : topCandidates.stream().limit(3).toList()) {
                try {
                    String research = stockResearchService.researchStockAsHtml(candidate.getSymbol());
                    if (!research.isEmpty()) {
                        content.append(research);
                    }
                } catch (Exception e) {
                    log.debug("Deep research failed for {}: {}", candidate.getSymbol(), e.getMessage());
                }
            }
        }

        // Methodology info box
        content.append(templateService.infoBox(
                "Scoring Methodology",
                "Technical Momentum (20%): Weekly/monthly EMA trend, RSI positioning, trend strength",
                "Volume Accumulation (15%): Smart money patterns, volume surge, up-day volume bias",
                "Relative Strength (15%): 3M/6M/1Y outperformance vs Nifty 50 index",
                "Price Structure (15%): 52W high proximity, higher lows, base building patterns",
                "Valuation (15%): PE vs sector PE, market cap growth potential",
                "Institutional Interest (10%): FII/DII sector flow direction",
                "Sector Tailwind (10%): Sector rotation, thematic momentum",
                "Grades: A+ (85+), A (75+), B+ (65+), B (55+), C+ (45+), C (35+), D (<35)"
        ));

        return content.toString();
    }

    /**
     * Per-cap-tier "top 5" leaderboards (Small-Cap / Mid-Cap / Large-Cap). This is where
     * the multibagger signal lives — most winners start in small/micro tier. See SPEC §12.5.
     */
    private String buildCapTierLeaderboard(List<MultibaggerScore> scores) {
        StringBuilder b = new StringBuilder();

        java.util.Map<String, String> tierLabels = new java.util.LinkedHashMap<>();
        tierLabels.put("SMALL_CAP", "&#128293; Top 5 Small-Caps (where most multibaggers emerge)");
        tierLabels.put("MID_CAP", "&#9889; Top 5 Mid-Caps");
        tierLabels.put("LARGE_CAP", "&#127774; Top 5 Large-Caps (stability, lower upside)");

        for (java.util.Map.Entry<String, String> entry : tierLabels.entrySet()) {
            List<MultibaggerScore> tierTop = scores.stream()
                    .filter(s -> entry.getKey().equals(s.getMarketCapCategory()))
                    .sorted(java.util.Comparator.comparingInt(MultibaggerScore::getCompositeScore).reversed())
                    .limit(5)
                    .toList();
            if (tierTop.isEmpty()) continue;
            b.append(buildCandidatesTable(entry.getValue(), tierTop));
        }
        return b.toString();
    }

    /**
     * Build a candidates table section.
     */
    /**
     * "Under the radar" — candidates that are also small, under-owned by institutions, and
     * quietly accumulating. This is the section the early-discovery work exists to produce:
     * the rest of the report tells you which businesses are good, this one tells you which
     * good businesses nobody has noticed yet.
     */
    private String buildUnderRadarSection(List<MultibaggerScore> scores) {
        List<MultibaggerScore> underRadar = scores.stream()
                .filter(s -> s.getUnderDiscoveryScore() != null)
                .filter(s -> s.getCompositeScore() >= 65)
                .filter(s -> s.getUnderDiscoveryScore() >= config.getUnderRadarMinScore())
                .sorted(Comparator.comparing(MultibaggerScore::getUnderDiscoveryScore).reversed())
                .limit(10)
                .collect(Collectors.toList());

        if (underRadar.isEmpty()) return "";

        StringBuilder b = new StringBuilder();
        b.append("<div style=\"background:#f0f7ff;border-left:4px solid #2b6cb0;padding:12px 14px;")
         .append("margin-bottom:14px;border-radius:4px;font-size:13px;line-height:1.6\">")
         .append("<strong>What this means.</strong> These stocks clear the quality bar <em>and</em> ")
         .append("look undiscovered: small, barely owned by big institutions (mutual funds and foreign ")
         .append("investors), and showing quiet buying. The idea is that when large investors do arrive, ")
         .append("their buying re-rates a small company much further than a large one. ")
         .append("<br><br><strong>The honest caveat:</strong> under-the-radar also means less liquid and ")
         .append("less scrutinised. Check the <em>Buyability</em> column before acting &mdash; a stock you ")
         .append("cannot accumulate without moving the price is not an opportunity for you, whatever its ")
         .append("score says. This ranking is new and has no measured track record yet.")
         .append("</div>");

        b.append(templateService.tableStart(
                "Stock", "Score", "Under-Radar", "Institutions", "Buyability", "Why it looks early"));

        for (MultibaggerScore s : underRadar) {
            String held = s.isInHoldings() ? " " + templateService.badge("HELD", "hold") : "";
            String why = s.getBullishFactors().stream()
                    .filter(f -> f.contains("institution") || f.contains("Institution")
                            || f.contains("discovered") || f.contains("Micro-cap")
                            || f.contains("delivery") || f.contains("coverage"))
                    .limit(3)
                    .collect(Collectors.joining("<br>"));
            if (why.isEmpty()) {
                why = s.getBullishFactors().stream().limit(2).collect(Collectors.joining("<br>"));
            }

            b.append(templateService.tableRow(
                    "<strong>" + s.getTradingSymbol() + "</strong>" + held
                            + "<br><small style=\"color:#666\">" + (s.getIndustry() != null ? s.getIndustry() : "") + "</small>",
                    "<strong>" + s.getCompositeScore() + "</strong>",
                    "<strong>" + s.getUnderDiscoveryScore() + "</strong>"
                            + templateService.scoreBar(s.getUnderDiscoveryScore()),
                    s.getMarketCapCategory() != null ? s.getMarketCapCategory().replace("_", " ") : "&mdash;",
                    describeBuyability(s),
                    "<small>" + why + "</small>"
            ));
        }
        b.append(templateService.tableEnd());
        return templateService.section("&#128302;", "Under the Radar", b.toString());
    }

    /**
     * Render buyability as the number that actually matters to a retail investor: how many
     * trading days it would take to build a Rs 1 lakh position without being more than 10%
     * of the day's volume.
     */
    static String describeBuyability(MultibaggerScore s) {
        String tier = s.getLiquidityTier();
        if (tier == null || "UNKNOWN".equals(tier)) {
            return "<span style=\"color:#888\">not measured</span>";
        }
        Integer days = MultibaggerScore.daysToBuild(s.getLiquidityAdv20d(), 1_00_000d, 0.10);
        String colour = switch (tier) {
            case "LIQUID" -> "#2f855a";
            case "MODERATE" -> "#b7791f";
            default -> "#c53030";
        };
        StringBuilder out = new StringBuilder();
        out.append("<span style=\"color:").append(colour).append(";font-weight:600\">").append(tier).append("</span>");
        if (days != null) {
            out.append("<br><small>~").append(days).append(days == 1 ? " day" : " days")
               .append(" to build Rs 1L</small>");
        }
        if (s.getCircuitDaysLast60() != null && s.getCircuitDaysLast60() >= 3) {
            out.append("<br><small style=\"color:#c53030\">circuit risk (")
               .append(s.getCircuitDaysLast60()).append("/60 d)</small>");
        }
        return out.toString();
    }

    private String buildCandidatesTable(String title, List<MultibaggerScore> candidates) {
        StringBuilder table = new StringBuilder();
        table.append(templateService.tableStart(
                "Stock", "Score", "Grade", "Verdict", "Price",
                "Tech", "Vol", "RS", "Struct", "Val",
                "Key Factors"
        ));

        for (MultibaggerScore s : candidates) {
            String verdictBadge = getVerdictBadge(s.getVerdict());
            String holdingsBadge = s.isInHoldings() ?
                    " " + templateService.badge("HELD", "hold") : "";

            String keyFactors = s.getBullishFactors().stream()
                    .limit(2)
                    .collect(Collectors.joining("<br>"));

            table.append(templateService.tableRow(
                    "<strong>" + s.getTradingSymbol() + "</strong>" + holdingsBadge +
                            "<br><small style=\"color:#666\">" + (s.getIndustry() != null ? s.getIndustry() : "") + "</small>",
                    "<strong>" + s.getCompositeScore() + "</strong>" + templateService.scoreBar(s.getCompositeScore()),
                    templateService.badge(s.getGrade(), getGradeBadgeType(s.getGrade())),
                    verdictBadge,
                    String.format("%.2f", s.getCurrentPrice()),
                    String.valueOf(s.getTechnicalMomentumScore()),
                    String.valueOf(s.getVolumeAccumulationScore()),
                    String.valueOf(s.getRelativeStrengthScore()),
                    String.valueOf(s.getPriceStructureScore()),
                    scoreOrNa(s.getValuationScore()),
                    "<small>" + keyFactors + "</small>"
            ));
        }

        table.append(templateService.tableEnd());
        return templateService.section("&#127775;", title, table.toString());
    }

    /**
     * Build holdings multibagger assessment section.
     */
    private String buildHoldingsAssessment(List<MultibaggerScore> holdingsScores) {
        StringBuilder body = new StringBuilder();

        // Alert for strong multibagger holdings
        long strongInHoldings = holdingsScores.stream()
                .filter(s -> s.getCompositeScore() >= config.getMinScoreForStrongCandidate())
                .count();

        if (strongInHoldings > 0) {
            body.append(templateService.alert("success", "&#127942;",
                    strongInHoldings + " of your holdings have strong multibagger potential!",
                    "These stocks score 75+ on our multibagger screening. Consider holding for long-term wealth creation."));
        }

        long weakInHoldings = holdingsScores.stream()
                .filter(s -> s.getCompositeScore() < 40)
                .count();

        if (weakInHoldings > 0) {
            body.append(templateService.alert("warning", "&#9888;",
                    weakInHoldings + " holdings show weak multibagger characteristics",
                    "These stocks score below 40. Review if they align with your long-term investment thesis."));
        }

        // Holdings table with multibagger scores
        body.append(templateService.tableStart(
                "Stock", "Score", "Grade", "P&L %", "Weekly RSI",
                "52W High", "Volume", "Verdict"
        ));

        for (MultibaggerScore s : holdingsScores) {
            String pnlStr = s.getHoldingsPnlPercent() != null ?
                    templateService.formatPercent(s.getHoldingsPnlPercent()) : "N/A";

            body.append(templateService.tableRow(
                    "<strong>" + s.getTradingSymbol() + "</strong>",
                    "<strong>" + s.getCompositeScore() + "</strong>" + templateService.scoreBar(s.getCompositeScore()),
                    templateService.badge(s.getGrade(), getGradeBadgeType(s.getGrade())),
                    pnlStr,
                    s.getWeeklyRsi() != null ? String.format("%.1f", s.getWeeklyRsi()) : "N/A",
                    s.getPriceVs52WeekHigh() != null ? String.format("%.1f%% below", s.getPriceVs52WeekHigh()) : "N/A",
                    s.getAvgVolumeRatio() != null ? String.format("%.1fx", s.getAvgVolumeRatio()) : "N/A",
                    getVerdictBadge(s.getVerdict())
            ));
        }

        body.append(templateService.tableEnd());
        return templateService.section("&#128188;", "Your Holdings - Multibagger Assessment", body.toString());
    }

    /**
     * Build sector-wise breakdown section.
     */
    private String buildSectorBreakdown(List<MultibaggerScore> scores) {
        Map<String, List<MultibaggerScore>> bySector = scores.stream()
                .filter(s -> s.getIndustry() != null)
                // One sector source (B-098): the screener's own table, backed by NSE's index
                // classification. A stock nobody has classified is "Unclassified", named as such,
                // rather than a sector called "Other" that happens to hold most of the universe.
                .collect(Collectors.groupingBy(s -> java.util.Optional
                        .ofNullable(MultibaggerScreenerService.sectorFor(s.getSymbol()))
                        .orElse("Unclassified")));

        StringBuilder body = new StringBuilder();
        body.append(templateService.tableStart("Sector", "Stocks", "Avg Score", "Best Candidate", "Best Score"));

        bySector.entrySet().stream()
                .sorted((a, b) -> {
                    double avgA = a.getValue().stream().mapToInt(MultibaggerScore::getCompositeScore).average().orElse(0);
                    double avgB = b.getValue().stream().mapToInt(MultibaggerScore::getCompositeScore).average().orElse(0);
                    return Double.compare(avgB, avgA);
                })
                .forEach(entry -> {
                    String sector = entry.getKey();
                    List<MultibaggerScore> sectorScores = entry.getValue();
                    double avgScore = sectorScores.stream().mapToInt(MultibaggerScore::getCompositeScore).average().orElse(0);
                    MultibaggerScore best = sectorScores.stream()
                            .max(java.util.Comparator.comparingInt(MultibaggerScore::getCompositeScore))
                            .orElse(null);

                    body.append(templateService.tableRow(
                            "<strong>" + sector + "</strong>",
                            String.valueOf(sectorScores.size()),
                            String.format("%.0f", avgScore),
                            best != null ? best.getTradingSymbol() : "N/A",
                            best != null ? String.valueOf(best.getCompositeScore()) : "N/A"
                    ));
                });

        body.append(templateService.tableEnd());
        return templateService.section("&#128202;", "Sector-wise Multibagger Potential", body.toString());
    }

    /**
     * Build market cap breakdown section.
     */
    private String buildMarketCapBreakdown(List<MultibaggerScore> scores) {
        StringBuilder body = new StringBuilder();
        body.append("<div class=\"summary-grid\">");

        long smallCap = scores.stream().filter(s -> "SMALL_CAP".equals(s.getMarketCapCategory())).count();
        long midCap = scores.stream().filter(s -> "MID_CAP".equals(s.getMarketCapCategory())).count();
        long largeCap = scores.stream().filter(s -> "LARGE_CAP".equals(s.getMarketCapCategory())).count();

        double smallAvg = scores.stream().filter(s -> "SMALL_CAP".equals(s.getMarketCapCategory()))
                .mapToInt(MultibaggerScore::getCompositeScore).average().orElse(0);
        double midAvg = scores.stream().filter(s -> "MID_CAP".equals(s.getMarketCapCategory()))
                .mapToInt(MultibaggerScore::getCompositeScore).average().orElse(0);
        double largeAvg = scores.stream().filter(s -> "LARGE_CAP".equals(s.getMarketCapCategory()))
                .mapToInt(MultibaggerScore::getCompositeScore).average().orElse(0);

        body.append(templateService.summaryCard("Small Cap", smallCap + " stocks (Avg: " + String.format("%.0f", smallAvg) + ")", "profit"));
        body.append(templateService.summaryCard("Mid Cap", midCap + " stocks (Avg: " + String.format("%.0f", midAvg) + ")", "neutral"));
        body.append(templateService.summaryCard("Large Cap", largeCap + " stocks (Avg: " + String.format("%.0f", largeAvg) + ")", ""));
        body.append("</div>");

        return templateService.section("&#128176;", "Market Cap Breakdown", body.toString());
    }

    // ============================================================
    // Section builders for integration with other reports
    // ============================================================

    /**
     * Build a compact multibagger section for Morning Briefing.
     * Shows top 5 candidates only.
     */
    public String buildMorningBriefingSection() {
        try {
            List<MultibaggerScore> candidates = screenerService.getTopCandidates();
            if (candidates.isEmpty()) {
                return templateService.section("&#127775;", "Multibagger Radar",
                        templateService.alert("info", "&#128269;",
                                "No Strong Candidates Today",
                                "Run screening to identify potential multibagger stocks."));
            }

            StringBuilder body = new StringBuilder();

            // Lead with high-conviction names. Realised 4-month median return by band was
            // 8.4% (80+) / 7.2% (70-79) / 4.7% (65-69) / -0.2% (<50), so the top band is where
            // attention is worth spending. Ordering only — nothing is filtered out.
            long highConviction = candidates.stream().filter(screenerService::isHighConviction).count();
            if (highConviction > 0) {
                body.append(templateService.alert("success", "&#127919;",
                        highConviction + " high-conviction pick" + (highConviction == 1 ? "" : "s")
                                + " today (score 70+)",
                        "Stocks scoring 70+ have historically been the band worth concentrating on: "
                        + "a median 4-month return of about 7-8% against 4.7% for 65-69 and roughly "
                        + "flat below 50. <em>Measured over one 4-month window, so treat it as a "
                        + "ranking guide rather than a promise.</em>"));
            }

            // Top 5 in compact format, high-conviction first
            List<MultibaggerScore> top5 = candidates.stream()
                    .sorted(java.util.Comparator
                            .comparing((MultibaggerScore s) -> !screenerService.isHighConviction(s))
                            .thenComparing(MultibaggerScore::getCompositeScore,
                                    java.util.Comparator.reverseOrder()))
                    .limit(5).collect(Collectors.toList());

            body.append(templateService.tableStart("Stock", "Score", "Grade", "Verdict", "Key Factor"));
            for (MultibaggerScore s : top5) {
                String topFactor = s.getBullishFactors().isEmpty() ? "N/A" : s.getBullishFactors().get(0);
                body.append(templateService.tableRow(
                        "<strong>" + s.getTradingSymbol() + "</strong>" +
                                (screenerService.isHighConviction(s)
                                        ? " " + templateService.badge("HIGH CONVICTION", "success") : "") +
                                (s.isInHoldings() ? " " + templateService.badge("HELD", "hold") : ""),
                        "<strong>" + s.getCompositeScore() + "</strong>",
                        templateService.badge(s.getGrade(), getGradeBadgeType(s.getGrade())),
                        getVerdictBadge(s.getVerdict()),
                        "<small>" + topFactor + "</small>"
                ));
            }
            body.append(templateService.tableEnd());

            if (candidates.size() > 5) {
                body.append("<p style=\"font-size:12px; color:#666; margin-top:10px;\">" +
                        "+" + (candidates.size() - 5) + " more candidates. View full report at /api/multibagger/report</p>");
            }

            return templateService.section("&#127775;", "Multibagger Radar - Top Candidates", body.toString());

        } catch (Exception e) {
            log.warn("Failed to build multibagger morning briefing section: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Build multibagger assessment for holdings report.
     * Shows multibagger scores for all current holdings.
     */
    public String buildHoldingsReportSection() {
        try {
            List<MultibaggerScore> holdingsScores = screenerService.getHoldingsScores();
            if (holdingsScores.isEmpty()) {
                return "";
            }

            StringBuilder body = new StringBuilder();

            long strong = holdingsScores.stream().filter(s -> s.getCompositeScore() >= config.getMinScoreForStrongCandidate()).count();
            if (strong > 0) {
                body.append(templateService.alert("success", "&#127775;",
                        strong + " holding(s) with strong multibagger potential!",
                        "These stocks show characteristics of potential multi-baggers. Consider long-term holding."));
            }

            body.append(templateService.tableStart("Stock", "MB Score", "Grade", "Verdict", "Tech", "RS", "Val", "Key Insight"));
            for (MultibaggerScore s : holdingsScores) {
                String insight = s.getBullishFactors().isEmpty() ?
                        (s.getBearishFactors().isEmpty() ? "N/A" : s.getBearishFactors().get(0)) :
                        s.getBullishFactors().get(0);

                body.append(templateService.tableRow(
                        "<strong>" + s.getTradingSymbol() + "</strong>",
                        "<strong>" + s.getCompositeScore() + "</strong>" + templateService.scoreBar(s.getCompositeScore()),
                        templateService.badge(s.getGrade(), getGradeBadgeType(s.getGrade())),
                        getVerdictBadge(s.getVerdict()),
                        String.valueOf(s.getTechnicalMomentumScore()),
                        String.valueOf(s.getRelativeStrengthScore()),
                        scoreOrNa(s.getValuationScore()),
                        "<small>" + insight + "</small>"
                ));
            }
            body.append(templateService.tableEnd());

            return templateService.section("&#127775;", "Holdings - Multibagger Assessment", body.toString());

        } catch (Exception e) {
            log.warn("Failed to build multibagger holdings section: {}", e.getMessage());
            return "";
        }
    }

    // ============================================================
    // Score trend analysis (for tracking improvement over time)
    // ============================================================

    /**
     * Get score trend for a symbol (for REST API).
     */
    public List<MultibaggerScoreEntity> getScoreTrend(String symbol, int days) {
        LocalDate fromDate = LocalDate.now().minusDays(days);
        return scoreRepository.findTrend(symbol, fromDate);
    }

    // ============================================================
    // Helper methods
    // ============================================================

    private String getVerdictBadge(String verdict) {
        if (verdict == null) return templateService.badge("N/A", "hold");
        return switch (verdict) {
            case "STRONG_MULTIBAGGER" -> templateService.badge("STRONG MB", "buy");
            case "POTENTIAL_MULTIBAGGER" -> templateService.badge("POTENTIAL MB", "bullish");
            case "WATCHLIST" -> templateService.badge("WATCHLIST", "hold");
            case "MONITOR" -> templateService.badge("MONITOR", "sideways");
            case "AVOID" -> templateService.badge("AVOID", "sell");
            default -> templateService.badge(verdict, "hold");
        };
    }

    private String getGradeBadgeType(String grade) {
        if (grade == null) return "hold";
        return switch (grade) {
            case "A+", "A" -> "buy";
            case "B+", "B" -> "bullish";
            case "C+", "C" -> "hold";
            default -> "sell";
        };
    }

    /**
     * Render a nullable dimension score. Null means the dimension could not be measured for
     * this stock — show a dash rather than the literal text "null", and never a 0, which a
     * reader would fairly interpret as "scored worst possible".
     */
    private static String scoreOrNa(Integer score) {
        return score == null ? "&mdash;" : String.valueOf(score);
    }
}
