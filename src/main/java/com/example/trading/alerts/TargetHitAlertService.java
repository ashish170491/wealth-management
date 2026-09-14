package com.example.trading.alerts;

import com.example.trading.marketdata.MarketDataService;
import com.example.trading.notification.EmailNotificationService;
import com.example.trading.notification.EmailTemplateService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.persistence.RecommendationEntity;
import com.example.trading.persistence.RecommendationEntity.Source;
import com.example.trading.persistence.RecommendationRepository;
import com.example.trading.persistence.TargetHitEventEntity;
import com.example.trading.persistence.TargetHitEventRepository;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily "target hit" alert (SPEC.md §26). Once per session it checks whether any
 * recommended pick (QUANT_DISCOVERY / SECTOR_REVERSAL, which carry a target
 * price) or any owned holding (vs its suggested target) has reached its target,
 * and emails the <em>newly</em>-hit ones. Each target is alerted only once —
 * dedup is persisted in {@code target_hit_events} so re-scans and daily restarts
 * never re-send the same hit.
 *
 * <p><b>Snapshot semantics:</b> the check uses the live price at scan time
 * (run late in the session, 15:05 IST), i.e. "at-or-above target as of today's
 * session". A target that was touched intraday but pulled back below by scan
 * time is not caught — true intraday-touch detection is the SPEC §25.1 Phase-2
 * item ("intra-period target/SL hit detection").
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TargetHitAlertService {

    /** Recommendations older than this are treated as no longer open. */
    private static final int RECOMMENDATION_LOOKBACK_DAYS = 365;
    private static final String SOURCE_HOLDING = "HOLDING";

    private final RecommendationRepository recommendationRepository;
    private final HoldingsRepository holdingsRepository;
    private final TargetHitEventRepository targetHitRepository;
    private final MarketDataService marketDataService;
    private final EmailNotificationService emailNotificationService;
    private final EmailTemplateService templateService;
    private final MarketHoursService marketHoursService;

    /** One target row in the email. */
    public record TargetHit(String source, String symbol, double targetPrice,
                            double currentPrice, Double gainPercent) {}

    @Scheduled(cron = "0 5 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledScan() {
        if (!marketHoursService.isMarketOpen()) return; // SPEC §3.4: market-hours only.
        try {
            int n = scanAndAlert();
            log.info("Target-hit scan complete: {} newly-hit stock(s) emailed", n);
        } catch (Exception e) {
            log.error("Target-hit scan failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Run the scan, persist newly-hit events, and email them if any.
     * @return number of newly-hit stocks (0 means no email sent)
     */
    public int scanAndAlert() {
        List<TargetHit> newHits = new ArrayList<>();
        newHits.addAll(scanRecommendations(Source.QUANT_DISCOVERY));
        newHits.addAll(scanRecommendations(Source.SECTOR_REVERSAL));
        // MACRO_EVENT is deliberately absent. Those rows are measurements of a risk reading, not
        // picks with a target to reach, and alerting on one would present "the ground tilted under
        // this business" as a price objective that had been met (SPEC 48.7).
        newHits.addAll(scanHoldings());

        if (newHits.isEmpty()) {
            log.info("Target-hit scan: nothing newly hit today");
            return 0;
        }

        try {
            String html = buildEmailHtml(newHits);
            String subject = String.format("🎯 Target Hit — %d stock(s) reached target (%s)",
                    newHits.size(), LocalDate.now());
            String wrapped = templateService.buildEmailTemplate(
                    "Target Hit Alert",
                    "Stocks that reached their target price today",
                    html);
            emailNotificationService.sendHtmlEmail(subject, wrapped);
        } catch (Exception e) {
            // Don't lose the dedup rows already persisted; just report the send failure.
            log.error("Target-hit email send failed: {}", e.getMessage(), e);
        }
        return newHits.size();
    }

    /**
     * Send a sample target-hit email with synthetic rows to verify the rendering
     * + delivery path. Exercises the real {@link #buildEmailHtml} and email sender
     * but writes nothing to the DB and records no dedup rows. The subject is
     * prefixed [TEST] so it can't be mistaken for a live alert.
     */
    public void sendSampleEmail() {
        List<TargetHit> sample = List.of(
                new TargetHit(SOURCE_HOLDING, "NSE:RELIANCE (SAMPLE)", 1500.0, 1512.40, 18.6),
                new TargetHit(Source.QUANT_DISCOVERY.name(), "NSE:BEL (SAMPLE)", 320.0, 327.10, 24.3),
                new TargetHit(Source.SECTOR_REVERSAL.name(), "NSE:TATAPOWER (SAMPLE)", 460.0, 461.85, 12.0));
        try {
            String html = "<div style=\"background:#fff3cd;border-left:4px solid #f0ad4e;padding:10px 14px;"
                    + "margin-bottom:14px;border-radius:4px;\"><strong>⚠️ TEST EMAIL</strong> — synthetic data, "
                    + "sent to verify the target-hit report renders and delivers. The stocks/prices below are not real.</div>"
                    + buildEmailHtml(sample);
            String subject = "[TEST] 🎯 Target Hit — sample email (" + LocalDate.now() + ")";
            String wrapped = templateService.buildEmailTemplate(
                    "Target Hit Alert (TEST)",
                    "Sample data — verifying email rendering and delivery",
                    html);
            emailNotificationService.sendHtmlEmail(subject, wrapped);
            log.info("Target-hit SAMPLE email sent ({} synthetic rows)", sample.size());
        } catch (Exception e) {
            log.error("Target-hit sample email failed: {}", e.getMessage(), e);
            throw new RuntimeException("Sample email send failed: " + e.getMessage(), e);
        }
    }

    /** Latest open recommendation per symbol for one engine, checked against live price. */
    private List<TargetHit> scanRecommendations(Source source) {
        LocalDate earliest = LocalDate.now().minusDays(RECOMMENDATION_LOOKBACK_DAYS);
        // findBySource...Desc returns newest first, so putIfAbsent keeps the most recent per symbol.
        Map<String, RecommendationEntity> latestPerSymbol = new LinkedHashMap<>();
        for (RecommendationEntity r : recommendationRepository.findBySourceOrderByIssuedDateDesc(source.name())) {
            if (r.getTargetPrice() == null || r.getTargetPrice() <= 0) continue;
            if (r.getIssuedDate() == null || r.getIssuedDate().isBefore(earliest)) continue;
            latestPerSymbol.putIfAbsent(r.getSymbol(), r);
        }

        List<TargetHit> hits = new ArrayList<>();
        for (RecommendationEntity r : latestPerSymbol.values()) {
            Double price = currentPriceQuietly(r.getSymbol());
            if (price == null || price <= 0) continue;
            double target = r.getTargetPrice();
            if (price >= target && recordIfNew(source.name(), r.getSymbol(), target, price)) {
                Double gain = r.getIssuedPrice() > 0
                        ? (price - r.getIssuedPrice()) / r.getIssuedPrice() * 100.0 : null;
                hits.add(new TargetHit(source.name(), r.getSymbol(), target, price, gain));
            }
        }
        return hits;
    }

    /** Owned holdings vs their suggested target (price gain measured from average cost). */
    private List<TargetHit> scanHoldings() {
        List<TargetHit> hits = new ArrayList<>();
        for (HoldingsEntity h : holdingsRepository.findActive()) {
            Double target = h.getSuggestedTarget1();
            double price = h.getCurrentPrice();
            if (target == null || target <= 0 || price <= 0) continue;
            if (price >= target && recordIfNew(SOURCE_HOLDING, h.getSymbol(), target, price)) {
                hits.add(new TargetHit(SOURCE_HOLDING, h.getSymbol(), target, price, h.getPnlPercent()));
            }
        }
        return hits;
    }

    /**
     * Persist a hit if its dedup key is new. Returns true when newly recorded
     * (so the caller should alert), false when already alerted before.
     */
    private boolean recordIfNew(String source, String symbol, double target, double price) {
        String key = String.format("%s|%s|%.2f", source, symbol, target);
        try {
            if (targetHitRepository.existsByDedupKey(key)) return false;
            targetHitRepository.save(TargetHitEventEntity.builder()
                    .source(source).symbol(symbol)
                    .targetPrice(target).hitPrice(price)
                    .hitDate(LocalDate.now())
                    .dedupKey(key)
                    .build());
            return true;
        } catch (Exception e) {
            // Unique-constraint race (concurrent scan) or DB hiccup — treat as already-handled.
            log.debug("Target-hit dedup skip for {}: {}", key, e.getMessage());
            return false;
        }
    }

    private Double currentPriceQuietly(String symbol) {
        try {
            return marketDataService.getCurrentPrice(symbol);
        } catch (Exception e) {
            log.debug("Target-hit: price unavailable for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ---- Email rendering (beginner-friendly per SPEC §21) ----

    private String buildEmailHtml(List<TargetHit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <div style="background:#e8f5e9;border-left:4px solid #2e7d32;padding:12px 14px;margin-bottom:16px;border-radius:4px;">
                  <strong>💡 What this means</strong><br/>
                  Each stock below has reached the <em>target price</em> it was given — either a price the app's
                  scoring engines projected (a "recommended pick") or the target on a stock you own (a "holding").
                  Reaching a target is a prompt to <strong>review</strong>: consider booking some profit, raising your
                  stop-loss, or re-checking the thesis. It is <em>not</em> automatic advice to sell — you decide.
                  This is a one-time alert per target; you won't be re-notified for the same one.
                </div>
                """);

        List<TargetHit> picks = hits.stream().filter(h -> !SOURCE_HOLDING.equals(h.source())).toList();
        List<TargetHit> holdings = hits.stream().filter(h -> SOURCE_HOLDING.equals(h.source())).toList();

        if (!holdings.isEmpty()) {
            sb.append("<h3 style=\"margin:18px 0 6px;\">📈 Holdings you own — target reached</h3>");
            sb.append(table(holdings, "Gain (P&L)"));
        }
        if (!picks.isEmpty()) {
            sb.append("<h3 style=\"margin:18px 0 6px;\">⭐ Recommended picks — target reached</h3>");
            sb.append(table(picks, "Gain since pick"));
        }
        return sb.toString();
    }

    private String table(List<TargetHit> rows, String gainHeader) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table style=\"width:100%;border-collapse:collapse;font-size:13px;\">");
        sb.append("<thead><tr style=\"background:#f2f4f8;text-align:left;\">");
        for (String h : new String[]{"Stock", "Source", "Target ₹", "Current ₹", gainHeader}) {
            sb.append("<th style=\"padding:8px;border:1px solid #ddd;\">").append(h).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (TargetHit r : rows) {
            sb.append("<tr>")
              .append(td(r.symbol()))
              .append(td(humanizeSource(r.source())))
              .append(td(String.format("%.2f", r.targetPrice())))
              .append(td(String.format("%.2f", r.currentPrice())))
              .append(td(r.gainPercent() == null ? "—" : String.format("%+.1f%%", r.gainPercent())))
              .append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private static String td(String s) {
        return "<td style=\"padding:8px;border:1px solid #eee;\">" + (s == null ? "—" : s) + "</td>";
    }

    private static String humanizeSource(String source) {
        return switch (source) {
            case "QUANT_DISCOVERY" -> "Quant Discovery";
            case "SECTOR_REVERSAL" -> "Sector Reversal";
            case SOURCE_HOLDING -> "Your Holding";
            default -> source;
        };
    }
}
