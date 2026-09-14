package com.example.trading.portfolio.report;

import com.example.trading.notification.EmailNotificationService;
import com.example.trading.notification.EmailTemplateService;
import com.example.trading.portfolio.AllocationDto;
import com.example.trading.portfolio.AllocationService;
import com.example.trading.portfolio.accumulation.AccumulationDto;
import com.example.trading.portfolio.accumulation.AccumulationService;
import com.example.trading.portfolio.conviction.ConvictionDto;
import com.example.trading.portfolio.conviction.ConvictionService;
import com.example.trading.portfolio.dividend.DividendDto;
import com.example.trading.portfolio.dividend.DividendService;
import com.example.trading.portfolio.rebalance.RebalanceDto;
import com.example.trading.portfolio.rebalance.RebalanceService;
import com.example.trading.portfolio.risk.DiversificationDto;
import com.example.trading.portfolio.risk.DiversificationService;
import com.example.trading.portfolio.tax.TaxLotDto;
import com.example.trading.portfolio.tax.TaxLotService;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.Month;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Consolidated portfolio snapshot email (SPEC §5–§11). Written for a beginner investor
 * per SPEC §21 Communication Style — every section has a plain-English explanation box,
 * technical terms are glossed on first mention, and enum strings are rendered as
 * human-friendly labels.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PortfolioSnapshotReportService {

    private static final Locale INDIA = Locale.of("en", "IN");
    private static final NumberFormat INR = NumberFormat.getCurrencyInstance(INDIA);

    private final AllocationService allocationService;
    private final DiversificationService diversificationService;
    private final ConvictionService convictionService;
    private final RebalanceService rebalanceService;
    private final AccumulationService accumulationService;
    private final DividendService dividendService;
    private final TaxLotService taxLotService;
    private final EmailTemplateService tpl;
    private final EmailNotificationService mail;

    public String sendSnapshot() throws MessagingException {
        StringBuilder body = new StringBuilder();

        body.append(buildIntro());
        body.append(buildHeaderSummary());
        body.append(buildAllocationSection());
        body.append(buildRiskSection());
        body.append(buildConvictionSection());
        body.append(buildRebalanceSection());
        body.append(buildAccumulationSection());
        body.append(buildTaxHarvestSection());
        body.append(buildDividendSection());
        body.append(buildFooter());

        String html = tpl.buildEmailTemplate(
                "Your Portfolio Snapshot",
                "A plain-English overview of where your money is and what to watch",
                body.toString());

        String subject = "Portfolio Snapshot — " + LocalDate.now();
        mail.sendHtmlEmail(subject, html);
        log.info("PortfolioSnapshot: email sent ({} chars)", html.length());
        return subject;
    }

    // ============================================================
    // Plain-English helpers
    // ============================================================

    /** Renders a light-blue "What this means" info box above a section. */
    private String explain(String plainEnglish) {
        return "<div style=\"background:#e7f3fe;border-left:4px solid #2196f3;"
                + "padding:10px 14px;margin:0 0 12px 0;border-radius:4px;color:#1a1a1a;font-size:14px;\">"
                + "<strong>&#128161; What this means:</strong> "
                + plainEnglish
                + "</div>";
    }

    /** Formats a value as Indian-rupee currency (e.g. ₹1,25,000). */
    private String inr(double value) {
        return INR.format(value).replace("INR", "\u20B9").replace("\u00A0", "");
    }

    /** Human-friendly label for a drift-alert enum. */
    private String humanAlert(String code) {
        return switch (code) {
            case "IN_TOLERANCE" -> "On Track";
            case "OVER_TOLERANCE" -> "Needs Action";
            case "NO_TARGET" -> "Not in Your Plan";
            default -> code;
        };
    }

    /** Human-friendly label for a conviction-drift status. */
    private String humanStatus(String code) {
        return switch (code) {
            case "INTACT" -> "Thesis Intact";
            case "UNDER_REVIEW" -> "Needs Review";
            case "BROKEN" -> "Thesis Broken";
            case "NO_CURRENT_SCORE" -> "No Fresh Score";
            default -> code;
        };
    }

    /** Human-friendly label for accumulation modes. */
    private String humanMode(String code) {
        return switch (code) {
            case "SIP" -> "Fixed-date SIP";
            case "PRICE_LADDER" -> "Buy-on-dips";
            case "SIGNAL_GATED" -> "Signal-triggered";
            default -> code;
        };
    }

    private String humanPlanStatus(String code) {
        return switch (code) {
            case "ACTIVE" -> "In progress";
            case "COMPLETED" -> "Done";
            case "CANCELLED" -> "Cancelled";
            case "PAUSED" -> "Paused";
            default -> code;
        };
    }

    // ============================================================
    // Sections
    // ============================================================

    private String buildIntro() {
        return "<div style=\"background:#f9f9f9;padding:15px;border-radius:6px;"
                + "margin-bottom:20px;font-size:14px;line-height:1.6;\">"
                + "<strong>Welcome.</strong> This email is a one-page health check for your investment "
                + "portfolio. Each section below starts with a blue box explaining what it means in "
                + "plain language, so you never need to guess what a number is telling you. "
                + "Nothing in this email places any trade automatically — it is just information and "
                + "suggestions for you to act on."
                + "</div>";
    }

    private String buildHeaderSummary() {
        AllocationDto.DriftResponse drift = safeDrift();
        DiversificationDto.RiskResponse risk = diversificationService.computeRisk();
        ConvictionDto.ConvictionReport conv = convictionService.getReport();

        StringBuilder b = new StringBuilder();
        b.append(explain(
                "The three most important numbers about your portfolio today: how many stocks you own, "
                + "how much your portfolio is worth right now, and whether anything is flashing red."));

        b.append("<div class=\"summary-grid\">");
        b.append(tpl.summaryCard("Stocks you own", String.valueOf(drift != null ? drift.holdingsCount() : 0), ""));
        b.append(tpl.summaryCard("Total value today",
                drift != null ? inr(drift.totalPortfolioValue()) : inr(0.0), ""));
        b.append(tpl.summaryCard("Concentration",
                humanHhi(risk.hhi().classification()),
                "DANGEROUS".equals(risk.hhi().classification()) ? "loss"
                        : "CONCENTRATED".equals(risk.hhi().classification()) ? "hold" : "profit"));
        b.append(tpl.summaryCard("Investment ideas in trouble", String.valueOf(conv.brokenCount()),
                conv.brokenCount() > 0 ? "loss" : "profit"));
        b.append("</div>");
        return tpl.section("&#128188;", "At a Glance", b.toString());
    }

    private String humanHhi(String cls) {
        return switch (cls) {
            case "LOW" -> "Well spread";
            case "MODERATE" -> "OK";
            case "CONCENTRATED" -> "A bit concentrated";
            case "DANGEROUS" -> "Very concentrated";
            default -> cls;
        };
    }

    private String buildAllocationSection() {
        AllocationDto.DriftResponse drift = safeDrift();
        StringBuilder b = new StringBuilder();
        b.append(explain(
                "You decided (or accepted the default) what percent of your portfolio should sit in each "
                + "sector, market-cap bucket, or stock — that is your <em>target</em>. This table shows how "
                + "far today's actual weights have drifted from that plan. <strong>Needs Action</strong> means "
                + "the gap is big enough that you should consider rebalancing (see Module 5 below)."));

        if (drift == null) {
            b.append(tpl.alert("warning", "&#9888;", "No plan yet",
                    "You haven't set target percentages yet. Use <code>PUT /api/portfolio/profile</code> "
                    + "to tell the system what mix you want — for example '20% IT, 25% Banking, 10% Metals…'"));
            return tpl.section("&#127919;", "How your money is actually spread vs your plan", b.toString());
        }

        b.append("<p><strong>Your plan:</strong> ").append(drift.profileName()).append("</p>");

        List<AllocationDto.DriftBucket> overTol = drift.buckets().stream()
                .filter(x -> "OVER_TOLERANCE".equals(x.alertLevel()))
                .sorted(Comparator.comparingDouble(x -> -Math.abs(x.driftPp())))
                .toList();
        List<AllocationDto.DriftBucket> noTarget = drift.buckets().stream()
                .filter(x -> "NO_TARGET".equals(x.alertLevel()))
                .sorted(Comparator.comparingDouble(x -> -x.actualWeight()))
                .limit(10)
                .toList();

        if (overTol.isEmpty()) {
            b.append(tpl.alert("success", "&#10004;", "On track",
                    "Every bucket you set a target for is within the tolerance band."));
        } else {
            b.append(tpl.tableStart("Bucket", "Your target", "Actual now", "Gap", "Status"));
            for (AllocationDto.DriftBucket d : overTol) {
                b.append(tpl.tableRow(
                        d.bucketType() + ": <strong>" + d.bucketKey() + "</strong>",
                        String.format("%.1f%%", d.targetWeight()),
                        String.format("%.1f%%", d.actualWeight()),
                        String.format("%+.1f pp", d.driftPp()),
                        tpl.badge(humanAlert(d.alertLevel()), "sell")));
            }
            b.append(tpl.tableEnd());
            b.append("<p style=\"font-size:12px;color:#666;margin-top:6px;\">"
                    + "<em>pp = percentage points, the simple difference between target and actual "
                    + "(e.g. target 20%, actual 15% → gap of −5 pp).</em></p>");
        }

        if (!noTarget.isEmpty()) {
            b.append("<h4 style=\"margin-top:20px;\">Top 10 holdings you haven't planned for</h4>");
            b.append("<p style=\"font-size:13px;color:#555;\">These buckets hold real money but aren't in "
                    + "your plan. Consider whether to add them as targets or trim them.</p>");
            b.append(tpl.tableStart("Bucket", "How much of your portfolio"));
            for (AllocationDto.DriftBucket d : noTarget) {
                b.append(tpl.tableRow(
                        d.bucketType() + ": " + d.bucketKey(),
                        String.format("%.2f%%", d.actualWeight())));
            }
            b.append(tpl.tableEnd());
        }
        return tpl.section("&#127919;", "How your money is actually spread vs your plan", b.toString());
    }

    private String buildRiskSection() {
        DiversificationDto.RiskResponse r = diversificationService.computeRisk();
        StringBuilder b = new StringBuilder();

        b.append(explain(
                "Diversification means not putting all your eggs in one basket. "
                + "<strong>HHI (Herfindahl Index)</strong> is a single number that measures concentration "
                + "— lower is better (below 1,500 is well spread, above 2,500 is getting risky, above "
                + "4,000 is dangerous). If any one sector takes more than 30% of your portfolio, or any one "
                + "stock takes more than 15%, that's a red flag worth acting on."));

        b.append("<div class=\"summary-grid\">");
        b.append(tpl.summaryCard("HHI score",
                String.format("%.0f", r.hhi().value()),
                "DANGEROUS".equals(r.hhi().classification()) ? "loss" : "profit"));
        b.append(tpl.summaryCard("Biggest sector",
                r.sectorConcentration().topBucket() != null ? r.sectorConcentration().topBucket() : "—",
                r.sectorConcentration().exceeds() ? "loss" : "profit"));
        b.append(tpl.summaryCard("Biggest sector %",
                String.format("%.1f%%", r.sectorConcentration().topWeight()),
                r.sectorConcentration().exceeds() ? "loss" : "profit"));
        b.append(tpl.summaryCard("Biggest single stock %",
                String.format("%.1f%%", r.stockConcentration().topWeight()),
                r.stockConcentration().exceeds() ? "loss" : "profit"));
        b.append("</div>");

        if (!r.alerts().isEmpty()) {
            b.append("<h4 style=\"margin-top:15px;\">Red flags</h4>");
            for (DiversificationDto.Alert a : r.alerts()) {
                b.append(tpl.alert(
                        "RED".equals(a.severity()) ? "danger" : "warning",
                        "RED".equals(a.severity()) ? "&#10060;" : "&#9888;",
                        a.category(), a.message()));
            }
        } else {
            b.append(tpl.alert("success", "&#10004;", "You're diversified",
                    "No single sector or stock is taking a dangerous slice of your portfolio right now."));
        }

        b.append("<h4 style=\"margin-top:20px;\">Your top 5 sectors</h4>");
        b.append(tpl.tableStart("Sector", "How much of your money"));
        r.sectorConcentration().all().stream().limit(5).forEach(bk ->
                b.append(tpl.tableRow(bk.key(), String.format("%.2f%%", bk.weight()))));
        b.append(tpl.tableEnd());

        return tpl.section("&#128737;", "Are you spread out enough? (Diversification)", b.toString());
    }

    private String buildConvictionSection() {
        ConvictionDto.ConvictionReport r = convictionService.getReport();
        StringBuilder b = new StringBuilder();

        b.append(explain(
                "When you bought each stock, you had a reason (your <em>thesis</em> — e.g. 'PSU banks will "
                + "re-rate because of lower interest rates'). This section tracks whether that reason "
                + "still holds. It compares today's quality score with the score when you bought. "
                + "A big drop (≥25 points) means your thesis may be broken — time to re-check before "
                + "the loss shows up in P&L."));

        b.append("<div class=\"summary-grid\">");
        b.append(tpl.summaryCard("Holdings with a thesis", String.valueOf(r.totalHoldings()), ""));
        b.append(tpl.summaryCard("Thesis intact", String.valueOf(r.intactCount()), "profit"));
        b.append(tpl.summaryCard("Needs review", String.valueOf(r.underReviewCount()), "hold"));
        b.append(tpl.summaryCard("Thesis broken", String.valueOf(r.brokenCount()), "loss"));
        b.append("</div>");

        if (!r.drifts().isEmpty()) {
            b.append("<h4 style=\"margin-top:15px;\">Stocks where you've recorded your reason</h4>");
            b.append(tpl.tableStart("Stock", "How sure were you? (1–10)",
                    "Quality score when you bought", "Quality score now", "Change", "Status"));
            for (ConvictionDto.ConvictionDrift d : r.drifts()) {
                b.append(tpl.tableRow(
                        "<strong>" + d.symbol() + "</strong>",
                        d.convictionScore() + " / 10",
                        d.purchaseMultibaggerScore() != null
                                ? String.format("%.0f", d.purchaseMultibaggerScore()) : "—",
                        d.currentMultibaggerScore() != null
                                ? String.format("%.0f", d.currentMultibaggerScore()) : "—",
                        d.driftPoints() != null ? String.format("%+.1f pts", d.driftPoints()) : "—",
                        tpl.badge(humanStatus(d.status()), badgeFor(d.status()))));
            }
            b.append(tpl.tableEnd());
        }

        if (!r.holdingsMissingThesis().isEmpty()) {
            b.append("<h4 style=\"margin-top:20px;color:#b45309;\">Stocks still waiting for a thesis ("
                    + r.holdingsMissingThesis().size() + ")</h4>");
            b.append("<p style=\"font-size:13px;color:#555;\">Thesis tracking only works for stocks you've "
                    + "told the system about. For each stock below, think about "
                    + "<em>why</em> you bought it (e.g. 'bet on PSU bank re-rating', 'aluminium supercycle', "
                    + "'5-year FMCG compounder') and what would break that reason, then record it with "
                    + "<code>PUT /api/portfolio/conviction</code> (one at a time) or "
                    + "<code>PUT /api/portfolio/conviction/bulk</code> (all at once). "
                    + "Until you do, the app can't warn you when a thesis breaks.</p>");

            StringBuilder chips = new StringBuilder("<div style=\"margin-top:8px;line-height:2;\">");
            for (String sym : r.holdingsMissingThesis()) {
                chips.append("<span style=\"display:inline-block;background:#fff3cd;border:1px solid #ffe69c;"
                        + "border-radius:4px;padding:3px 10px;margin:2px;font-size:13px;font-weight:600;"
                        + "color:#664d03;\">").append(sym).append("</span>");
            }
            chips.append("</div>");
            b.append(chips);
        }

        if (r.drifts().isEmpty() && r.holdingsMissingThesis().isEmpty()) {
            b.append(tpl.alert("info", "&#8505;", "No holdings found",
                    "Once your broker sync runs, holdings will appear here for thesis tracking."));
        }

        return tpl.section("&#128170;", "Is your reason to hold each stock still valid?", b.toString());
    }

    private String buildRebalanceSection() {
        RebalanceDto.RebalanceProposal p = rebalanceService.generateProposal();
        StringBuilder b = new StringBuilder();

        b.append(explain(
                "Rebalancing means selling a bit of what has grown too big in your portfolio and buying "
                + "more of what has shrunk too small, so your mix stays close to your plan. The suggestions "
                + "below are <strong>not placed automatically</strong> — you review them, decide, and place "
                + "the trades yourself with your broker."));

        b.append("<p><em>").append(p.notes()).append("</em></p>");
        b.append("<div class=\"summary-grid\">");
        b.append(tpl.summaryCard("Suggested trades", String.valueOf(p.trades().size()), ""));
        b.append(tpl.summaryCard("Tips for sector/cap adjustments", String.valueOf(p.bucketHints().size()), ""));
        b.append(tpl.summaryCard("Total to buy", inr(p.totalBuyValue()), "profit"));
        b.append(tpl.summaryCard("Total to sell", inr(p.totalSellValue()), "loss"));
        b.append("</div>");

        if (!p.trades().isEmpty()) {
            b.append("<h4 style=\"margin-top:15px;\">Suggested trades</h4>");
            b.append(tpl.tableStart("Stock", "What to do", "How many shares", "Price now", "₹ amount", "Why"));
            for (RebalanceDto.Trade t : p.trades()) {
                b.append(tpl.tableRow(
                        "<strong>" + t.symbol() + "</strong>",
                        tpl.badge(t.action(), "BUY".equals(t.action()) ? "buy" : "sell"),
                        String.valueOf(t.quantity()),
                        String.format("%.2f", t.currentPrice()),
                        inr(t.amount()),
                        t.reason()));
            }
            b.append(tpl.tableEnd());
        }

        if (!p.bucketHints().isEmpty()) {
            b.append("<h4 style=\"margin-top:15px;\">Sector / market-cap nudges</h4>");
            b.append("<p style=\"font-size:13px;color:#555;\">For these, the system can't suggest exact "
                    + "trades — you pick which stocks inside the sector to add or trim.</p>");
            b.append(tpl.tableStart("Bucket", "Gap", "What to consider"));
            for (RebalanceDto.BucketHint h : p.bucketHints()) {
                b.append(tpl.tableRow(
                        h.bucketType() + ": <strong>" + h.bucketKey() + "</strong>",
                        String.format("%+.1f pp", h.driftPp()),
                        h.recommendation()));
            }
            b.append(tpl.tableEnd());
        }
        return tpl.section("&#128260;", "Suggestions to bring you back to your plan", b.toString());
    }

    private String buildAccumulationSection() {
        List<AccumulationDto.PlanView> plans = accumulationService.listPlans();
        StringBuilder b = new StringBuilder();

        b.append(explain(
                "Instead of buying a stock all in one go (and risking that you picked a bad day), an "
                + "<strong>accumulation plan</strong> splits the purchase into smaller chunks (we call them "
                + "<em>tranches</em>) spread over time or over price levels. Three modes: "
                + "<strong>Fixed-date SIP</strong> (buy on set dates), <strong>Buy-on-dips</strong> "
                + "(buy only when price falls to targets), and <strong>Signal-triggered</strong> "
                + "(buy when a technical signal confirms)."));

        if (plans.isEmpty()) {
            b.append(tpl.alert("info", "&#8505;", "No plans yet",
                    "To start a staged purchase, call <code>POST /api/portfolio/accumulate</code>."));
        } else {
            b.append(tpl.tableStart("Stock", "Mode", "Target amount",
                    "Chunks", "Bought so far", "% done", "Status"));
            for (AccumulationDto.PlanView p : plans) {
                b.append(tpl.tableRow(
                        "<strong>" + p.symbol() + "</strong>",
                        tpl.badge(humanMode(p.mode()), "hold"),
                        inr(p.targetAmount()),
                        String.valueOf(p.tranchesCount()),
                        inr(p.filledAmount()) + " (" + p.filledQuantity() + " shares)",
                        String.format("%.0f%%", p.progressPercent()),
                        tpl.badge(humanPlanStatus(p.status()), "ACTIVE".equals(p.status()) ? "buy" : "hold")));
            }
            b.append(tpl.tableEnd());
        }
        return tpl.section("&#128200;", "Your planned stock purchases over time", b.toString());
    }

    private String buildTaxHarvestSection() {
        TaxLotDto.HarvestResponse h = taxLotService.computeHarvestSuggestions();
        StringBuilder b = new StringBuilder();

        b.append(explain(
                "Indian tax rules for equity: if you sell within 365 days of buying, profits are taxed at "
                + "<strong>20% (Short-Term Capital Gains)</strong>; sell after 365 days, the tax drops to "
                + "<strong>12.5% (Long-Term)</strong> and the first ₹1,25,000 of long-term profit every "
                + "financial year is tax-free. This section helps you do three things: (1) find shares in "
                + "loss that you can sell to offset taxable gains, (2) spot shares close to the 365-day "
                + "mark so you don't sell too early, and (3) see which shares are already tax-efficient to sell."));

        b.append("<div class=\"summary-grid\">");
        b.append(tpl.summaryCard("Short-term gains realised this year",
                inr(h.fiscalYearRealizedStcg()),
                h.fiscalYearRealizedStcg() > 0 ? "profit" : h.fiscalYearRealizedStcg() < 0 ? "loss" : ""));
        b.append(tpl.summaryCard("Long-term gains realised this year",
                inr(h.fiscalYearRealizedLtcg()),
                h.fiscalYearRealizedLtcg() > 0 ? "profit" : h.fiscalYearRealizedLtcg() < 0 ? "loss" : ""));
        b.append(tpl.summaryCard("Tax-free LTCG left this year", inr(h.fiscalYearExemptionRemaining()), ""));
        b.append(tpl.summaryCard("Shares to harvest at loss",
                String.valueOf(h.lossHarvestCandidates().size()),
                h.lossHarvestCandidates().isEmpty() ? "" : "loss"));
        b.append("</div>");

        if (!h.lossHarvestCandidates().isEmpty()) {
            b.append("<h4 style=\"margin-top:15px;\">Shares sitting at a loss — sell to offset gains</h4>");
            b.append(renderHarvestTable(h.lossHarvestCandidates()));
        }
        if (!h.approachingLtcgCutoff().isEmpty()) {
            b.append("<h4 style=\"margin-top:15px;\">Close to the 365-day mark — wait a few more days to save tax</h4>");
            b.append(renderHarvestTable(h.approachingLtcgCutoff()));
        }
        if (!h.ltcgEligible().isEmpty()) {
            b.append("<h4 style=\"margin-top:15px;\">Held &gt; 365 days — lowest tax if you sell</h4>");
            b.append(renderHarvestTable(h.ltcgEligible()));
        }
        if (h.lossHarvestCandidates().isEmpty()
                && h.approachingLtcgCutoff().isEmpty()
                && h.ltcgEligible().isEmpty()) {
            b.append(tpl.alert("info", "&#8505;", "Nothing to harvest right now",
                    "Either no tax lots are recorded yet, or none of your lots match the three conditions. "
                    + "Record purchases via <code>POST /api/portfolio/tax-lots</code> to start tracking."));
        }
        return tpl.section("&#128181;", "Saving tax when you sell (Tax-Lot Harvest)", b.toString());
    }

    private String renderHarvestTable(List<TaxLotDto.HarvestItem> items) {
        StringBuilder b = new StringBuilder();
        b.append(tpl.tableStart("Stock", "Shares", "Bought at", "Price now",
                "Days you've held", "Days to long-term", "Unrealised P/L"));
        for (TaxLotDto.HarvestItem it : items) {
            b.append(tpl.tableRow(
                    "<strong>" + it.symbol() + "</strong>",
                    String.valueOf(it.remainingQuantity()),
                    inr(it.buyPrice()),
                    inr(it.currentPrice()),
                    String.valueOf(it.daysHeld()),
                    String.valueOf(it.daysToLtcgCutoff()),
                    (it.unrealizedGain() >= 0 ? "+" : "") + inr(it.unrealizedGain())));
        }
        b.append(tpl.tableEnd());
        return b.toString();
    }

    private String buildDividendSection() {
        LocalDate today = LocalDate.now();
        int fy = today.getMonth().getValue() >= Month.APRIL.getValue() ? today.getYear() : today.getYear() - 1;
        DividendDto.AnnualSummary s = dividendService.annualSummary(fy);
        List<DividendDto.ReinvestmentSuggestion> reinvest = dividendService.reinvestmentSuggestions();

        StringBuilder b = new StringBuilder();
        b.append(explain(
                "Dividends are cash payments companies make to shareholders out of their profits. This "
                + "section shows how much dividend money you've received (or are expecting) in this "
                + "financial year, and suggests which under-weighted stocks in your plan you could "
                + "reinvest the dividends into for the biggest impact."));

        b.append("<div class=\"summary-grid\">");
        b.append(tpl.summaryCard("Received FY " + fy + "–" + String.valueOf(fy + 1).substring(2),
                inr(s.totalReceived()), "profit"));
        b.append(tpl.summaryCard("Announced / expected FY " + fy + "–" + String.valueOf(fy + 1).substring(2),
                inr(s.totalProjected()), ""));
        b.append(tpl.summaryCard("Events this year", String.valueOf(s.eventCount()), ""));
        b.append("</div>");

        if (!reinvest.isEmpty()) {
            b.append("<h4 style=\"margin-top:15px;\">Where to put the dividend cash</h4>");
            b.append(tpl.tableStart("Stock", "How under-weight", "Dividend accrued", "Why"));
            reinvest.stream().limit(10).forEach(r -> b.append(tpl.tableRow(
                    "<strong>" + r.symbol() + "</strong>",
                    String.format("%+.1f pp", r.driftPp()),
                    inr(r.accruedDividend()),
                    r.reason())));
            b.append(tpl.tableEnd());
        }
        return tpl.section("&#128176;", "Dividend income & reinvestment ideas", b.toString());
    }

    private String buildFooter() {
        return "<div style=\"background:#f0f4ff;border-radius:6px;padding:15px;margin-top:25px;"
                + "font-size:13px;color:#333;line-height:1.6;\">"
                + "<strong>&#128221; How to use this report</strong><br>"
                + "1. Skim the <em>At a Glance</em> box first — if everything is green, you can stop there.<br>"
                + "2. If anything flags red, jump to that section for the details.<br>"
                + "3. Any trade the system suggests is just a suggestion — you review and place it "
                + "yourself with your broker. The app never places trades on its own.<br>"
                + "4. Terms you saw: <strong>pp</strong> = percentage points, <strong>STCG</strong> = Short-Term "
                + "Capital Gains tax (&lt; 365 days, 20%), <strong>LTCG</strong> = Long-Term (&gt; 365 days, 12.5% "
                + "with ₹1.25L/year free), <strong>HHI</strong> = concentration score (lower = safer), "
                + "<strong>tranche</strong> = one chunk of a staged purchase, <strong>thesis</strong> = your "
                + "reason for owning a stock.</div>";
    }

    private AllocationDto.DriftResponse safeDrift() {
        try {
            return allocationService.computeDrift();
        } catch (Exception e) {
            log.warn("PortfolioSnapshot: drift unavailable: {}", e.getMessage());
            return null;
        }
    }

    private String badgeFor(String convictionStatus) {
        return switch (convictionStatus) {
            case ConvictionService.STATUS_INTACT -> "buy";
            case ConvictionService.STATUS_UNDER_REVIEW -> "hold";
            case ConvictionService.STATUS_BROKEN -> "sell";
            default -> "hold";
        };
    }
}
