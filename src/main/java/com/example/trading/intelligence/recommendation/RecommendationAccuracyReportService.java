package com.example.trading.intelligence.recommendation;

import com.example.trading.intelligence.recommendation.RecommendationAccuracyService.SourceHorizonStats;
import com.example.trading.persistence.RecommendationEntity;
import com.example.trading.notification.EmailNotificationService;
import com.example.trading.notification.EmailTemplateService;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Weekly email report summarizing how accurate past recommendations have been.
 * Sent at 15:25 IST every Friday — inside the market-hours window (SPEC.md
 * §3.4) and right after the 15:22 outcome updater so numbers are fresh.
 *
 * Follows SPEC.md §21 communication rules: each section opens with a plain-
 * English "what this means" box, and technical terms are glossed on first use.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecommendationAccuracyReportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final RecommendationAccuracyService accuracyService;
    private final EmailNotificationService emailNotificationService;
    private final EmailTemplateService templateService;
    private final MarketHoursService marketHoursService;

    @Scheduled(cron = "0 25 15 * * FRI", zone = "Asia/Kolkata")
    public void sendWeeklyReport() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        try {
            sendReport();
        } catch (Exception e) {
            log.error("Recommendation accuracy report: send failed: {}", e.getMessage(), e);
        }
    }

    /** Public entry point for on-demand triggering via REST. */
    public void sendReport() throws Exception {
        List<SourceHorizonStats> stats = accuracyService.summarize();

        StringBuilder html = new StringBuilder();
        html.append(buildHtml(stats));
        html.append(buildDimensionIcSection());

        String subject = "Recommendation Accuracy — Week of " + LocalDate.now().format(DATE_FMT);
        String wrapped = templateService.buildEmailTemplate(
                "Recommendation Accuracy Report",
                "How well have our picks performed?",
                html.toString());
        emailNotificationService.sendHtmlEmail(subject, wrapped);
        log.info("Recommendation accuracy report: sent with {} source×horizon stats", stats.size());
    }

    private List<RecommendationAccuracyService.DimensionIcStats> safeDimensionIC(
            RecommendationEntity.Source source, int horizon) {
        try {
            return accuracyService.computeDimensionIC(source, horizon);
        } catch (Exception e) {
            log.warn("Per-dimension IC ({} {}d) failed; section will be empty: {}", source, horizon, e.getMessage());
            return List.of();
        }
    }

    private String buildHtml(List<SourceHorizonStats> stats) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                <div style="background:#eef7ff;border-left:4px solid #2b7cff;padding:12px 14px;margin-bottom:16px;border-radius:4px;">
                  <strong>💡 What this report shows</strong><br/>
                  This is a report card on the stock picks the app has issued. For each "source"
                  (a scoring engine, e.g., <em>Multibagger</em>) and each <em>horizon</em> (how long ago the pick was
                  issued — 30 / 90 / 180 / 365 days), we show what those picks have actually done in the market.
                  Use it to see which engines you should trust more.
                </div>
                """);

        if (stats.isEmpty()) {
            sb.append("""
                    <p style="color:#555;">
                      No completed measurements yet. Outcomes are only recorded once a recommendation
                      has had time to mature (minimum 30 days after it was issued). Check back in a few weeks.
                    </p>
                    """);
            return sb.toString();
        }

        sb.append("""
                <h3 style="margin-top:18px;margin-bottom:8px;">Accuracy by engine and horizon</h3>
                <div style="background:#fff6e0;border-left:4px solid #f5a623;padding:12px 14px;margin-bottom:12px;border-radius:4px;">
                  <strong>Glossary (first mention only)</strong><br/>
                  • <strong>Hit rate</strong>: percentage of picks that ended up positive at the horizon (higher = better).<br/>
                  • <strong>Mean return</strong>: average price change of picks at the horizon (your raw return, in %).<br/>
                  • <strong>Excess vs Nifty</strong>: mean return minus the Nifty 50's return over the same period — how much the pick actually beat the index (positive = outperformed).<br/>
                  • <strong>IC (Information Coefficient)</strong>: correlation between the engine's score and the actual return (−1 to +1). <em>0 = random</em>, <em>+0.10 and above = meaningful predictive signal</em>, negative = the score is misleading you.<br/>
                  • <strong>Sample</strong>: number of picks the number is based on — smaller samples are less reliable.<br/>
                  • <strong>Target hit</strong>: percentage of picks that reached their proposed target at the horizon — <em>computed only over picks that actually had a target proposed</em>; the "coverage" column shows what fraction of the sample that was. A dash means no picks in the cell carried a target (e.g., the Multibagger engine doesn't propose per-pick targets).<br/>
                  • <strong>SL hit</strong>: same idea for stop-loss.
                </div>
                """);

        sb.append("""
                <table style="width:100%;border-collapse:collapse;font-size:13px;">
                  <thead>
                    <tr style="background:#f2f4f8;text-align:left;">
                      <th style="padding:8px;border:1px solid #ddd;">Engine</th>
                      <th style="padding:8px;border:1px solid #ddd;">Horizon</th>
                      <th style="padding:8px;border:1px solid #ddd;">Sample</th>
                      <th style="padding:8px;border:1px solid #ddd;">Hit rate</th>
                      <th style="padding:8px;border:1px solid #ddd;">Mean return</th>
                      <th style="padding:8px;border:1px solid #ddd;">Excess vs Nifty</th>
                      <th style="padding:8px;border:1px solid #ddd;">IC</th>
                      <th style="padding:8px;border:1px solid #ddd;">Target hit (coverage)</th>
                      <th style="padding:8px;border:1px solid #ddd;">SL hit (coverage)</th>
                    </tr>
                  </thead>
                  <tbody>
                """);

        for (SourceHorizonStats s : stats) {
            sb.append("<tr>")
              .append(cell(humanizeSource(s.getSource())))
              .append(cell(s.getHorizonDays() + "d"))
              .append(cell(Integer.toString(s.getSampleSize())))
              .append(cell(pct(s.getHitRatePercent())))
              .append(cell(signedPct(s.getMeanReturnPercent())))
              .append(cell(signedPct(s.getMeanExcessReturnPercent())))
              .append(cell(ic(s.getInformationCoefficient())))
              .append(cell(levelCell(s.getTargetHitRatePercent(), s.getTargetCoveragePercent(), s.getPicksWithTarget())))
              .append(cell(levelCell(s.getStopLossHitRatePercent(), s.getStopLossCoveragePercent(), s.getPicksWithStopLoss())))
              .append("</tr>");
        }

        sb.append("</tbody></table>");

        sb.append("""
                <div style="background:#f8f9fa;border-left:4px solid #6c757d;padding:12px 14px;margin-top:18px;border-radius:4px;font-size:13px;color:#555;">
                  <strong>How to read this</strong>: Prefer engines with (a) a hit rate well above 50%, (b) positive excess
                  return, and (c) IC of +0.10 or higher on <em>large samples</em>. Anything with fewer than ~20 picks
                  is too early to draw conclusions from. Numbers shown for the first time will grow more reliable over the coming months.
                </div>
                """);

        return sb.toString();
    }

    /**
     * Per-dimension IC section across all three scoring engines. Each engine's
     * score is a weighted blend of sub-scores; this section shows which sub-score
     * actually predicts returns, per engine, at 30d and 90d horizons.
     *
     * SPEC.md §23.2: MULTIBAGGER reads multibagger_scores, SECTOR_REVERSAL reads
     * sector_reversal_signals, QUANT_DISCOVERY reads the recommendation_dimensions
     * sidecar (so its rows stay empty until picks captured after 2026-05-24 mature).
     */
    private String buildDimensionIcSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("<h3 style=\"margin-top:24px;margin-bottom:8px;\">Which scoring dimensions are working?</h3>");
        sb.append("""
                <div style="background:#eef7ff;border-left:4px solid #2b7cff;padding:12px 14px;margin-bottom:12px;border-radius:4px;">
                  <strong>💡 What this means</strong><br/>
                  Each scoring engine builds its headline score from several <strong>sub-scores</strong> (e.g., the Multibagger
                  engine blends technical momentum, volume, valuation, financial quality and more). Each row below shows
                  whether that <em>specific</em> sub-score predicts future returns on its own. If a dimension's IC is near zero it's
                  noise; if it's +0.10 or higher it carries real signal; if it's negative the dimension is actively misleading.
                  The <em>Composite (reference)</em> row is the full weighted score — use it as a baseline to judge which dimensions beat it.
                </div>
                <div style="background:#fff6e0;border-left:4px solid #f5a623;padding:10px 14px;margin-bottom:12px;border-radius:4px;font-size:12.5px;">
                  <strong>How to read:</strong> the two IC columns show the same data at different holding periods. A high 30d IC that
                  collapses at 90d = a short-lived signal; an IC that holds up or strengthens at 90d = an enduring signal.
                  Sample size matters — treat rows with fewer than 20 samples as indicative, not conclusive.
                </div>
                """);

        for (RecommendationEntity.Source source : RecommendationEntity.Source.values()) {
            sb.append(buildDimensionIcTable(
                    humanizeSource(source.name()),
                    safeDimensionIC(source, 30),
                    safeDimensionIC(source, 90)));
        }

        sb.append("""
                <div style="background:#f8f9fa;border-left:4px solid #6c757d;padding:10px 14px;margin-top:10px;border-radius:4px;font-size:12.5px;color:#555;">
                  <strong>What to do with this:</strong> dimensions with high IC (≥ +0.10) and solid sample size are candidates for
                  <em>higher</em> weight next revision. Dimensions with IC near zero or negative are candidates for lower weight or removal —
                  but only after the sample has built to at least ~60 outcomes, so we don't over-fit to noise. Quantitative Discovery's
                  dimension rows stay empty until picks captured from 2026-05-24 onward reach their 30/90-day horizon.
                </div>
                """);
        return sb.toString();
    }

    /**
     * One engine's per-dimension IC table (30d + 90d side by side). Renders a
     * short "no matured picks yet" note instead of an empty grid when every row
     * has a zero sample — keeps the email honest about thin data.
     */
    private String buildDimensionIcTable(String sourceLabel,
                                         List<RecommendationAccuracyService.DimensionIcStats> dim30,
                                         List<RecommendationAccuracyService.DimensionIcStats> dim90) {
        boolean anyData = hasSamples(dim30) || hasSamples(dim90);
        StringBuilder sb = new StringBuilder();
        sb.append("<h4 style=\"margin-top:18px;margin-bottom:6px;color:#333;\">").append(sourceLabel).append("</h4>");
        if (!anyData) {
            sb.append("<p style=\"color:#888;font-size:12.5px;margin:0 0 8px;\">"
                    + "No matured picks yet — check back once this engine's picks reach their 30/90-day horizon.</p>");
            return sb.toString();
        }

        // Merge by dimension name
        Map<String, RecommendationAccuracyService.DimensionIcStats> by30 = new LinkedHashMap<>();
        Map<String, RecommendationAccuracyService.DimensionIcStats> by90 = new LinkedHashMap<>();
        if (dim30 != null) for (var s : dim30) by30.put(s.getDimension(), s);
        if (dim90 != null) for (var s : dim90) by90.put(s.getDimension(), s);

        // Union of keys, preserving the canonical order from the 90d list (falls back to 30d)
        Set<String> order = new LinkedHashSet<>();
        for (var s : (dim90 != null && !dim90.isEmpty() ? dim90 : dim30)) order.add(s.getDimension());

        sb.append("<table style=\"width:100%;border-collapse:collapse;font-size:13px;\">");
        sb.append("<thead><tr style=\"background:#f2f4f8;text-align:left;\">");
        sb.append("<th style=\"padding:8px;border:1px solid #ddd;\">Dimension</th>");
        sb.append("<th style=\"padding:8px;border:1px solid #ddd;\">IC (30d)</th>");
        sb.append("<th style=\"padding:8px;border:1px solid #ddd;\">Sample (30d)</th>");
        sb.append("<th style=\"padding:8px;border:1px solid #ddd;\">IC (90d)</th>");
        sb.append("<th style=\"padding:8px;border:1px solid #ddd;\">Sample (90d)</th>");
        sb.append("<th style=\"padding:8px;border:1px solid #ddd;\">Mean return @ 90d</th>");
        sb.append("</tr></thead><tbody>");

        for (String dim : order) {
            var s30 = by30.get(dim);
            var s90 = by90.get(dim);
            boolean isComposite = dim.toLowerCase().contains("composite");
            sb.append(isComposite ? "<tr style=\"background:#f9f9f9;\">" : "<tr>");
            sb.append(cell(dim));
            sb.append(cell(s30 != null ? ic(s30.getInformationCoefficient()) : "—"));
            sb.append(cell(s30 != null ? Integer.toString(s30.getSampleSize()) : "—"));
            sb.append(cell(s90 != null ? icColored(s90.getInformationCoefficient()) : "—"));
            sb.append(cell(s90 != null ? Integer.toString(s90.getSampleSize()) : "—"));
            sb.append(cell(s90 != null ? signedPct(s90.getMeanReturnPercent()) : "—"));
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    /** True if any row in the list was computed over a non-zero sample. */
    private static boolean hasSamples(List<RecommendationAccuracyService.DimensionIcStats> list) {
        if (list == null) return false;
        return list.stream().anyMatch(s -> s.getSampleSize() > 0);
    }

    /** IC with green/red coloring for the 90d column — the signal that matters most. */
    private static String icColored(Double v) {
        if (v == null) return "—";
        String color = v >= 0.10 ? "#2e7d32" : v <= -0.05 ? "#c0392b" : "#555";
        return String.format("<span style=\"color:%s;font-weight:600;\">%+.2f</span>", color, v);
    }

    private static String humanizeSource(String source) {
        return switch (source) {
            case "MULTIBAGGER" -> "Multibagger Screener";
            case "QUANT_DISCOVERY" -> "Quantitative Discovery";
            case "SECTOR_REVERSAL" -> "Sector Reversal";
            default -> source;
        };
    }

    private static String cell(String text) {
        return "<td style=\"padding:8px;border:1px solid #eee;\">" + (text == null ? "—" : text) + "</td>";
    }

    private static String pct(Double v) {
        return v == null ? "—" : String.format("%.1f%%", v);
    }

    private static String signedPct(Double v) {
        if (v == null) return "—";
        return String.format("%+.1f%%", v);
    }

    private static String ic(Double v) {
        return v == null ? "—" : String.format("%+.2f", v);
    }

    /**
     * Render a target-or-SL level-calibration cell: "hit% (n of total coverage%)".
     * Returns em-dash when no picks in the cell proposed a level — distinguishes
     * "0% hit rate on a real sample" from "no levels to measure" (e.g., Multibagger
     * doesn't propose per-pick targets).
     */
    private static String levelCell(Double hitRatePercent, Double coveragePercent, int picksWithLevel) {
        if (picksWithLevel == 0 || hitRatePercent == null) return "—";
        String coverage = coveragePercent == null
                ? String.format("%d picks", picksWithLevel)
                : String.format("%d of %.0f%%", picksWithLevel, coveragePercent);
        return String.format("%s <span style='color:#888;'>(%s)</span>", pct(hitRatePercent), coverage);
    }
}
