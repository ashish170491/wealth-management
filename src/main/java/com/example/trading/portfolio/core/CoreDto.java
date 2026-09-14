package com.example.trading.portfolio.core;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Value types for the Core Holding Classifier (SPEC §35).
 *
 * <p>Null discipline throughout: a value that could not be measured is {@code null} and is named
 * in {@code missingInputs}, never defaulted to a neutral number. A holding the classifier cannot
 * judge is {@link CoreTier#UNCLASSIFIED} — never SATELLITE, because SATELLITE is a finding and
 * UNCLASSIFIED is the absence of one.
 */
public final class CoreDto {

    private CoreDto() {}

    /** Tier. Ordered worst-to-best for display convenience only; nothing depends on the ordinal. */
    public enum CoreTier {
        UNCLASSIFIED,
        SATELLITE,
        CORE_WATCH,
        CORE;

        /** True for the two tiers the behavioural overlay protects. */
        public boolean isProtected() {
            return this == CORE || this == CORE_WATCH;
        }
    }

    /**
     * Per-gate outcome.
     *
     * <p>{@link #PASS_NO_DATA} is the load-bearing distinction: it passes the fail/pass decision
     * (absence of evidence is not a flag) but carries no evidence, so it does not count toward the
     * CORE quorum. Collapsing it into {@code PASS} is how "no forensic history" becomes "clean
     * books" — the exact confusion CLAUDE.md Gotcha 44 warns about.
     */
    public enum GateStatus {
        PASS,
        PASS_NO_DATA,
        FAIL,
        UNMEASURED;

        /** Only a real pass or a real failure counts toward {@code min-measured-gates}. */
        public boolean countsAsEvidence() {
            return this == PASS || this == FAIL;
        }
    }

    /** One gate's verdict plus the sentence a beginner-facing report can print verbatim. */
    public record Gate(String code, String name, GateStatus status, String reason) {}

    /**
     * Everything the classifier reads for one holding, assembled once. Every field nullable —
     * the classifier's job is partly to report what it could not see.
     */
    public record HoldingEvidence(
            String symbol,
            String tradingSymbol,
            String isin,
            String industry,

            // multibagger_scores (latest row, any screening date)
            LocalDate scoreDate,
            Integer compositeScore,
            String capitalEfficiencyVerdict,
            Double rocePercent,
            Double roePercent,
            Double roaPercent,
            String financialQualityVerdict,
            Double interestCoverage,
            Integer earningsConsistencyScore,
            String insiderPulseVerdict,
            String turnaroundVerdict,
            String capexVerdict,

            // forensic screen (DB-only, announcements excluded)
            Boolean forensicMeasured,
            Integer forensicFlagCount,
            String forensicSummary,

            // thesis
            String decayVerdict,
            Double purchaseDriftPoints,
            Boolean convictionRecordExists,
            Integer holdingHorizonMonths,
            Boolean horizonStated,
            String coreOverride,

            // multi-year history
            List<AnnualYear> annualHistory,

            // price
            List<PricePoint> dailyCloses,

            List<String> missingInputs
    ) {}

    /** One financial year, reduced to the figures the durability components need. */
    public record AnnualYear(
            int fiscalYear,
            Double sales,
            Double netProfit,
            Double interestCost,
            Double borrowings,
            Double equity,
            Double totalAssets,
            Double operatingCashFlow,
            Double shareCount
    ) {}

    /** A daily close. Kite's candles are split/bonus adjusted, so no corporate-action maths here. */
    public record PricePoint(LocalDate date, double close) {}

    /** One durability component's contribution, or its absence. */
    public record DurabilityComponent(String code, String name, Integer points, String note) {

        public boolean measured() { return points != null; }
    }

    /**
     * Durability, 0–100, renormalised over measured components.
     *
     * @param score null when fewer than {@code durability-min-components} were measured — a
     *              number computed from one component is a worse answer than no number.
     */
    public record Durability(Integer score, List<DurabilityComponent> components, String coverage) {}

    /** The classification of one holding on one day, before hysteresis is applied. */
    public record CoreClassification(
            String symbol,
            String tradingSymbol,
            String isin,
            CoreTier provisionalTier,
            List<Gate> gates,
            List<String> softSignals,
            List<String> reasons,
            List<String> missingInputs,
            Durability durability,
            boolean criticalTrigger,
            List<String> criticalTriggerReasons
    ) {

        public long evidenceGateCount() {
            return gates.stream().filter(g -> g.status().countsAsEvidence()).count();
        }

        public Map<String, GateStatus> gateStatuses() {
            java.util.LinkedHashMap<String, GateStatus> m = new java.util.LinkedHashMap<>();
            for (Gate g : gates) m.put(g.code(), g.status());
            return m;
        }
    }

    /**
     * What the reader and the overlay actually see: today's reading plus the tier that survived
     * hysteresis.
     *
     * @param pendingChange non-null while a change is accumulating confirmations
     *                      ("would become SATELLITE — 1 of 2 confirmations")
     */
    public record CoreHoldingView(
            String symbol,
            String tradingSymbol,
            LocalDate classifiedOn,
            CoreTier tier,
            CoreTier effectiveTier,
            Integer durabilityScore,
            String durabilityCoverage,
            List<Gate> gates,
            List<String> softSignals,
            List<String> reasons,
            List<String> missingInputs,
            List<String> observedAlerts,
            String pendingChange,
            String overrideApplied
    ) {}
}
