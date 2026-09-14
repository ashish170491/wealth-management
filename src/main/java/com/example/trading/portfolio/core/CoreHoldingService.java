package com.example.trading.portfolio.core;

import com.example.trading.portfolio.core.CoreDto.CoreClassification;
import com.example.trading.portfolio.core.CoreDto.CoreTier;
import com.example.trading.portfolio.core.CoreDto.Durability;
import com.example.trading.portfolio.core.CoreDto.Gate;
import com.example.trading.portfolio.core.CoreDto.GateStatus;
import com.example.trading.portfolio.core.CoreDto.HoldingEvidence;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The classifier itself (SPEC §35.2) — seven hard gates, five soft signals, one tier.
 *
 * <p><b>Pure by construction.</b> It takes a {@link HoldingEvidence} and a date and returns a
 * {@link CoreClassification}; it reads no repository and calls no broker. Evidence assembly lives
 * in {@link CoreClassificationService}. That split is what lets every rule below be pinned by
 * {@code CoreHoldingGatesTest} without a Spring context, and it is why changing a gate makes a
 * test fail rather than quietly changing what the daily email says.
 *
 * <h2>The three-state gate</h2>
 * A gate is not a boolean. {@code PASS} means evidence was found and it was good; {@code FAIL}
 * means evidence was found and it was bad; {@code PASS_NO_DATA} means there was nothing to look
 * at, which is not a reason to fail a holding but is also not evidence in its favour; and
 * {@code UNMEASURED} means the input needed to judge is simply absent. Only PASS and FAIL count
 * toward the CORE quorum. Without that distinction, a stock with no forensic history, no insider
 * filings and no conviction record collects three free passes and reaches CORE on two gates.
 */
@Component
public class CoreHoldingService {

    private final CoreHoldingConfig config;
    private final DurabilityScorer durabilityScorer;

    public CoreHoldingService(CoreHoldingConfig config, DurabilityScorer durabilityScorer) {
        this.config = config;
        this.durabilityScorer = durabilityScorer;
    }

    /** Purchase-drift floor: a thesis that has lost this many composite points is not intact. */
    static final double PURCHASE_DRIFT_FLOOR = -25.0;

    /** Financial-quality verdict below which a DECENT stock is only CORE_WATCH. */
    static final double SOFT_INTEREST_COVER = 4.0;

    public CoreClassification classify(HoldingEvidence e, LocalDate today) {
        List<Gate> gates = List.of(
                g1CapitalEfficiency(e),
                g2FinancialQuality(e),
                g3EarningsConsistency(e),
                g4Forensic(e),
                g5ThesisIntact(e),
                g6OwnerAlignment(e),
                g7Horizon(e));

        List<String> softSignals = softSignals(e);
        List<String> criticalReasons = criticalTriggers(e);
        List<String> reasons = new ArrayList<>();
        List<String> missing = new ArrayList<>(
                e.missingInputs() == null ? List.of() : e.missingInputs());

        Durability durability = durabilityScorer.score(e, today);

        long evidenceGates = gates.stream().filter(g -> g.status().countsAsEvidence()).count();
        List<Gate> failed = gates.stream().filter(g -> g.status() == GateStatus.FAIL).toList();

        CoreTier tier;
        if (!failed.isEmpty()) {
            tier = CoreTier.SATELLITE;
            for (Gate g : failed) reasons.add(g.name() + ": " + g.reason());
        } else if (evidenceGates >= config.getMinMeasuredGates()) {
            tier = softSignals.isEmpty() ? CoreTier.CORE : CoreTier.CORE_WATCH;
            reasons.add(String.format("All measurable gates passed (%d of %d carrying evidence)",
                    evidenceGates, gates.size()));
            reasons.addAll(softSignals);
        } else {
            tier = CoreTier.UNCLASSIFIED;
            reasons.add(String.format(
                    "Only %d of the %d gates could be judged on real evidence (%d needed). "
                            + "No gate failed - this is a data gap, not a verdict.",
                    evidenceGates, gates.size(), config.getMinMeasuredGates()));
            for (Gate g : gates) {
                if (!g.status().countsAsEvidence()) missing.add(g.name() + ": " + g.reason());
            }
        }

        return new CoreClassification(e.symbol(), e.tradingSymbol(), e.isin(), tier, gates,
                softSignals, reasons, missing, durability,
                !criticalReasons.isEmpty(), criticalReasons);
    }

    // ------------------------------------------------------------------ gates

    /**
     * G1 — a business that does not earn well above its cost of capital cannot compound, whatever
     * its chart says. Banks and NBFCs are judged on ROE and ROA instead: ROCE computed on an
     * equity-only base is misleading for a balance-sheet business (SPEC §12.8).
     */
    Gate g1CapitalEfficiency(HoldingEvidence e) {
        String v = e.capitalEfficiencyVerdict();
        if (v == null || "NO_DATA".equals(v)) {
            return gate("G1", "Capital efficiency", GateStatus.UNMEASURED,
                    "no capital-efficiency verdict on the latest screening row");
        }
        if ("NA_FINANCIAL".equals(v)) {
            if (e.roePercent() == null || e.roaPercent() == null) {
                return gate("G1", "Capital efficiency", GateStatus.UNMEASURED,
                        "financial company with no ROE/ROA on file");
            }
            boolean ok = e.roePercent() >= config.getFinancialRoeThresholdPercent()
                    && e.roaPercent() >= config.getFinancialRoaThresholdPercent();
            return gate("G1", "Capital efficiency", ok ? GateStatus.PASS : GateStatus.FAIL,
                    String.format("financial company: ROE %.1f%% (need %.1f), ROA %.2f%% (need %.2f)",
                            e.roePercent(), config.getFinancialRoeThresholdPercent(),
                            e.roaPercent(), config.getFinancialRoaThresholdPercent()));
        }
        boolean ok = "HIGH_QUALITY_COMPOUNDER".equals(v) || "SOLID".equals(v);
        return gate("G1", "Capital efficiency", ok ? GateStatus.PASS : GateStatus.FAIL,
                "capital efficiency is " + human(v)
                        + (e.rocePercent() != null ? String.format(" (ROCE %.1f%%)", e.rocePercent()) : ""));
    }

    /** G2 — a fragile balance sheet is not a "never sell". */
    Gate g2FinancialQuality(HoldingEvidence e) {
        String v = e.financialQualityVerdict();
        if (v == null) {
            return gate("G2", "Financial quality", GateStatus.UNMEASURED,
                    "no financial-quality verdict on the latest screening row");
        }
        boolean ok = "HIGH_QUALITY".equals(v) || "DECENT".equals(v);
        return gate("G2", "Financial quality", ok ? GateStatus.PASS : GateStatus.FAIL,
                "balance sheet and cash flow read " + human(v));
    }

    /** G3 — steady beats lumpy when the intended holding period is measured in years. */
    Gate g3EarningsConsistency(HoldingEvidence e) {
        Integer s = e.earningsConsistencyScore();
        if (s == null) {
            return gate("G3", "Earnings consistency", GateStatus.UNMEASURED,
                    "no earnings-consistency score on the latest screening row");
        }
        boolean ok = s >= config.getConsistencyMin();
        return gate("G3", "Earnings consistency", ok ? GateStatus.PASS : GateStatus.FAIL,
                String.format("earnings consistency %d/100 (need %d)", s, config.getConsistencyMin()));
    }

    /**
     * G4 — clean books are a precondition, not a bonus.
     *
     * <p>The forensic screen needs about three years of annual history. When none of its checks
     * could run, this is {@code PASS_NO_DATA}: absence of evidence is not a flag, but neither is
     * it a clean bill of health (CLAUDE.md Gotcha 44), so it earns the holding nothing toward the
     * CORE quorum.
     */
    Gate g4Forensic(HoldingEvidence e) {
        if (e.forensicMeasured() == null || !e.forensicMeasured()) {
            return gate("G4", "Forensic screen", GateStatus.PASS_NO_DATA,
                    "no checks could run - needs about 3 years of annual history. "
                            + "This is 'nothing was checked', not 'nothing was found'");
        }
        int flags = e.forensicFlagCount() == null ? 0 : e.forensicFlagCount();
        if (flags == 0) {
            return gate("G4", "Forensic screen", GateStatus.PASS, "no accounting red flags found");
        }
        return gate("G4", "Forensic screen", GateStatus.FAIL,
                flags + " accounting red flag(s): "
                        + (e.forensicSummary() == null ? "see the forensic section" : e.forensicSummary()));
    }

    /** G5 — a CORE holding with a broken thesis is a SATELLITE carrying sunk cost. */
    Gate g5ThesisIntact(HoldingEvidence e) {
        String v = e.decayVerdict();
        if (v == null || "NO_DATA".equals(v) || "STALE".equals(v)) {
            return gate("G5", "Thesis intact", GateStatus.UNMEASURED,
                    "no usable score history - the stock is outside the screening universe or "
                            + "was added too recently to measure drift");
        }
        if ("BROKEN".equals(v) || "DECAYING".equals(v)) {
            return gate("G5", "Thesis intact", GateStatus.FAIL,
                    "composite score is " + human(v) + " over the last 30/60 days");
        }
        if (Boolean.TRUE.equals(e.convictionRecordExists())
                && e.purchaseDriftPoints() != null
                && e.purchaseDriftPoints() <= PURCHASE_DRIFT_FLOOR) {
            return gate("G5", "Thesis intact", GateStatus.FAIL,
                    String.format("score has fallen %.0f points since purchase", -e.purchaseDriftPoints()));
        }
        return gate("G5", "Thesis intact", GateStatus.PASS,
                "score trend is " + human(v) + " and the purchase thesis still holds");
    }

    /** G6 — promoters selling in size is a fundamental fact, not price noise. */
    Gate g6OwnerAlignment(HoldingEvidence e) {
        String v = e.insiderPulseVerdict();
        if (v == null || "NO_DATA".equals(v) || "NOT_MEASURED".equals(v)) {
            return gate("G6", "Owner alignment", GateStatus.PASS_NO_DATA,
                    "no insider disclosures on file for this stock");
        }
        if ("STRONG_DISTRIBUTION".equals(v)) {
            return gate("G6", "Owner alignment", GateStatus.FAIL,
                    "promoters and insiders have been selling heavily in the open market");
        }
        return gate("G6", "Owner alignment", GateStatus.PASS, "insider activity reads " + human(v));
    }

    /** G7 — the investor's own stated intent, from the conviction record they wrote. */
    Gate g7Horizon(HoldingEvidence e) {
        if (!Boolean.TRUE.equals(e.convictionRecordExists())) {
            return gate("G7", "Stated horizon", GateStatus.PASS_NO_DATA,
                    "no conviction record - add one (Portfolio -> Conviction) to make this gate count");
        }
        if (e.holdingHorizonMonths() == null) {
            return gate("G7", "Stated horizon", GateStatus.PASS_NO_DATA,
                    "conviction record exists but names no holding horizon");
        }
        if (!Boolean.TRUE.equals(e.horizonStated())) {
            // The 24-month horizon on an auto-generated conviction record is a seed value, not
            // something the investor chose. Failing this gate on it would demote a holding on a
            // number nobody decided - a default masquerading as a statement.
            return gate("G7", "Stated horizon", GateStatus.PASS_NO_DATA,
                    "the " + e.holdingHorizonMonths() + "-month horizon on this record is the "
                            + "system default, not something you set. Edit the conviction record "
                            + "(Portfolio -> Conviction) to make this gate count either way");
        }
        boolean ok = e.holdingHorizonMonths() >= 36;
        return gate("G7", "Stated horizon", ok ? GateStatus.PASS : GateStatus.FAIL,
                "you recorded a " + e.holdingHorizonMonths() + "-month horizon"
                        + (ok ? "" : " - under 3 years, so this is not a hold-forever position"));
    }

    // ------------------------------------------------------------------ soft signals

    /**
     * Soft signals turn CORE into CORE_WATCH. They never produce SATELLITE — a watch item is
     * something to keep an eye on, and demoting on one would make the tier flicker.
     *
     * <p>The design also listed a NEGATIVE earnings trend-break. That is deliberately absent in
     * Phase A: the trend-break verdict is not persisted on {@code multibagger_scores} and can only
     * be had from a live NSE call per holding, which this classifier cannot afford inside the
     * 10:30 job. The daily email already carries its own trend-break section, so the signal
     * reaches the reader either way.
     */
    List<String> softSignals(HoldingEvidence e) {
        List<String> out = new ArrayList<>();
        if ("WATCH".equals(e.decayVerdict())) {
            out.add("Score has slipped recently (thesis drift: WATCH)");
        }
        if ("DISTRIBUTION".equals(e.insiderPulseVerdict())) {
            out.add("Insiders have been net sellers over the last 90 days");
        }
        if ("SOLID".equals(e.capitalEfficiencyVerdict()) && roceFallingTwoYears(e)) {
            out.add("Return on capital has fallen two years running");
        }
        if ("DECENT".equals(e.financialQualityVerdict()) && e.interestCoverage() != null
                && e.interestCoverage() < SOFT_INTEREST_COVER) {
            out.add(String.format("Interest cover is thin (%.1fx profit covers interest)",
                    e.interestCoverage()));
        }
        return out;
    }

    private boolean roceFallingTwoYears(HoldingEvidence e) {
        List<CoreDto.AnnualYear> h = e.annualHistory();
        if (h == null || h.size() < 3) return false;
        List<Double> r = new ArrayList<>();
        for (CoreDto.AnnualYear y : h.subList(h.size() - 3, h.size())) {
            Double v = DurabilityScorer.roce(y);
            if (v == null) return false;
            r.add(v);
        }
        return r.get(2) < r.get(1) && r.get(1) < r.get(0);
    }

    // ------------------------------------------------------------------ critical triggers

    /**
     * Facts that demote a core holding <em>the same day</em>, bypassing hysteresis (SPEC §35.6).
     *
     * <p>Hysteresis exists so a single bad XBRL parse cannot flip exit behaviour. It must not also
     * mean that a company which just tripped a forensic flag keeps its exit alerts suppressed for
     * a fortnight. An auditor problem in particular escalates rather than deducts (Gotcha 45):
     * every quality metric here is computed from the audited numbers, so a compromised audit makes
     * a clean reading meaningless rather than reassuring.
     */
    List<String> criticalTriggers(HoldingEvidence e) {
        List<String> out = new ArrayList<>();
        if (e.forensicFlagCount() != null && e.forensicFlagCount() > 0) {
            out.add("Forensic red flag: "
                    + (e.forensicSummary() == null ? "see the forensic section" : e.forensicSummary()));
        }
        if ("HIGH_RISK".equals(e.financialQualityVerdict())) {
            out.add("Financial quality is HIGH_RISK");
        }
        if ("BROKEN".equals(e.decayVerdict())) {
            out.add("Thesis drift is BROKEN");
        }
        if ("STRONG_DISTRIBUTION".equals(e.insiderPulseVerdict())) {
            out.add("Insiders are selling heavily (STRONG_DISTRIBUTION)");
        }
        if ("WEAK".equals(e.capitalEfficiencyVerdict()) || "POOR".equals(e.capitalEfficiencyVerdict())) {
            out.add("Capital efficiency has fallen to " + human(e.capitalEfficiencyVerdict()));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static Gate gate(String code, String name, GateStatus status, String reason) {
        return new Gate(code, name, status, reason);
    }

    /** Turn SCREAMING_SNAKE verdicts into something a non-specialist reader can parse (SPEC §21). */
    static String human(String verdict) {
        if (verdict == null) return "not measured";
        return verdict.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
