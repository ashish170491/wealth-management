package com.example.trading.learning;

import com.example.trading.multibagger.MultibaggerScore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Computes the per-signal coverage vector for one screening run (SPEC §38.2).
 *
 * <p><b>The question this answers.</b> A dimension's Information Coefficient reads ~0 for
 * two completely different reasons: the signal genuinely does not predict returns, or it was
 * never measured on most of the universe and the few values it did produce carry no spread.
 * The system has already been bitten by the second: Institutional Interest scored every
 * stock exactly 40 for three months (bug #9), and monthly RSI returned a constant 50.0 for
 * every stock because the fetch window could not reach the indicator's period (B-060). Both
 * looked like weak signals. Neither was.
 *
 * <p><b>Three states, not two.</b> Every stock is classified per signal as:
 * <ul>
 *   <li>{@code MEASURED} — a real value was produced;</li>
 *   <li>{@code NOT_APPLICABLE} — the signal legitimately does not apply to this stock
 *       (ROCE and debt-to-equity on a bank, capex on a financial). Excluded from the
 *       coverage denominator, because a bank without a ROCE is not a data gap;</li>
 *   <li>{@code NOT_MEASURED} — a genuine gap.</li>
 * </ul>
 * Collapsing NOT_APPLICABLE into either of the others is the same error that
 * {@code GateStatus.PASS_NO_DATA} exists to prevent (Gotcha 68): a finding and the absence
 * of a finding are different things.
 *
 * <p><b>Deliberately excluded: {@code forensicFlags}.</b> A null there means "clean" or
 * "never checked" and the two cannot be told apart from this table (Gotcha 44) — the
 * distinction lives in {@code ForensicResult.notMeasured}, which is not persisted. Guessing
 * would produce a coverage number that is confidently wrong, which is worse than the absent
 * row. Recorded in SPEC §38.5 as a known gap rather than silently filled in.
 *
 * <p>Pure — no Spring, no I/O. {@code ScreeningCoverageService} persists what this returns.
 */
public final class ScreeningCoverage {

    /**
     * Cross-sectional standard deviation below which a numeric signal is treated as
     * collapsed — it is no longer separating the universe, whatever its weight says.
     * Shared with {@code MultibaggerScreenerService.logDimensionVariance}, which raises the
     * same condition as a live ERROR; this class is its persisted, trendable sibling.
     */
    public static final double MIN_HEALTHY_STDDEV = 5.0;

    /** Below this many measured values, spread is not reported at all rather than reported noisily. */
    static final int MIN_SAMPLE_FOR_SPREAD = 10;

    public enum Status { MEASURED, NOT_APPLICABLE, NOT_MEASURED }

    /**
     * One signal's coverage across one screening run.
     *
     * @param coveragePercent measured / (attempted − notApplicable), or null when every
     *                        stock was not-applicable — a percentage over an empty
     *                        denominator is not 0%, it is nothing
     * @param mean            cross-sectional mean of measured values, numeric signals only
     * @param stdDev          cross-sectional spread, null below {@link #MIN_SAMPLE_FOR_SPREAD}
     * @param collapsed       true when a 0-100 signal's spread is below
     *                        {@link #MIN_HEALTHY_STDDEV}; null when the spread could not be
     *                        computed, and null for signals that are not on the 0-100 scale
     *                        — never false in either case
     */
    public record SignalCoverage(String signal,
                                 int attempted,
                                 int measured,
                                 int notApplicable,
                                 int notMeasured,
                                 Double coveragePercent,
                                 Double mean,
                                 Double stdDev,
                                 Boolean collapsed) {
    }

    /**
     * @param scoreScaled whether {@code value} lives on the engine's 0-100 scale. Only these
     *                    get a {@code collapsed} verdict: {@link #MIN_HEALTHY_STDDEV} is a
     *                    threshold for 0-100 scores and means nothing on a ratio. A
     *                    debt-to-equity spread of 0.5 around a mean of 0.3 is wide, and the
     *                    first live run duly flagged it as collapsed — a false alarm on the
     *                    system's loudest channel is how a real one gets ignored.
     */
    private record Spec(String name,
                        Function<MultibaggerScore, Status> status,
                        Function<MultibaggerScore, Double> value,
                        boolean scoreScaled) {
    }

    private ScreeningCoverage() {
    }

    public static List<SignalCoverage> compute(List<MultibaggerScore> scores) {
        List<SignalCoverage> out = new ArrayList<>();
        if (scores == null || scores.isEmpty()) return out;

        for (Spec spec : SPECS) {
            int measured = 0;
            int notApplicable = 0;
            int notMeasured = 0;
            List<Double> values = new ArrayList<>();

            for (MultibaggerScore s : scores) {
                Status st;
                try {
                    st = spec.status().apply(s);
                } catch (Exception e) {
                    st = Status.NOT_MEASURED;
                }
                switch (st) {
                    case MEASURED -> {
                        measured++;
                        Double v = safeValue(spec, s);
                        if (v != null) values.add(v);
                    }
                    case NOT_APPLICABLE -> notApplicable++;
                    case NOT_MEASURED -> notMeasured++;
                }
            }

            int denominator = scores.size() - notApplicable;
            Double coverage = denominator > 0 ? round1(100.0 * measured / denominator) : null;

            Double mean = null;
            Double stdDev = null;
            Boolean collapsed = null;
            if (values.size() >= MIN_SAMPLE_FOR_SPREAD) {
                double m = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double var = values.stream().mapToDouble(v -> (v - m) * (v - m)).sum() / values.size();
                mean = round1(m);
                stdDev = round1(Math.sqrt(var));
                // Only 0-100 signals get a verdict; on a ratio the threshold is meaningless.
                collapsed = spec.scoreScaled() ? stdDev < MIN_HEALTHY_STDDEV : null;
            }

            out.add(new SignalCoverage(spec.name(), scores.size(), measured, notApplicable,
                    notMeasured, coverage, mean, stdDev, collapsed));
        }
        return out;
    }

    private static Double safeValue(Spec spec, MultibaggerScore s) {
        if (spec.value() == null) return null;
        try {
            return spec.value().apply(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    // ============================================================
    // Status helpers
    // ============================================================

    private static Status present(Object v) {
        return v == null ? Status.NOT_MEASURED : Status.MEASURED;
    }

    /**
     * Classify a verdict string. {@code na} lists the values meaning "does not apply here",
     * {@code missing} the values meaning "could not be determined". Anything else non-blank
     * is a real verdict.
     */
    private static Status verdict(String v, Set<String> na, Set<String> missing) {
        if (v == null || v.isBlank()) return Status.NOT_MEASURED;
        String u = v.trim().toUpperCase();
        if (na.contains(u)) return Status.NOT_APPLICABLE;
        if (missing.contains(u)) return Status.NOT_MEASURED;
        return Status.MEASURED;
    }

    /**
     * True when this row is a bank or other financial, for which several metrics are
     * suppressed by design (SPEC §12.8 — ROCE on an equity-only base misleads for them).
     *
     * <p><b>The marker is {@code capexVerdict}, not {@code capitalEfficiencyVerdict}.</b> The
     * first draft used the latter and the not-applicable branch never fired once: NSE's
     * capital-efficiency analysis puts {@code NA_FINANCIAL} on its <i>sub</i>-verdicts
     * ({@code roceVerdict}, {@code leverageVerdict}), which are not persisted, while the
     * composite verdict that is persisted still grades a bank on ROE/ROA and reads
     * {@code SOLID} or {@code AVERAGE}. Measured on the live run of 2026-08-29: 0 of 295 rows
     * carried {@code capitalEfficiencyVerdict = NA_FINANCIAL}, while 24 carried
     * {@code capexVerdict = NA_FINANCIAL} — and all 24 of those had a null ROCE, which is
     * exactly the not-applicable set.
     *
     * <p>Insurers return {@code NO_DATA} instead (NSE publishes nothing for them) and so count
     * as genuine gaps — the correct reading: that data is missing, not inapplicable.
     */
    private static boolean isFinancial(MultibaggerScore s) {
        return "NA_FINANCIAL".equalsIgnoreCase(s.getCapexVerdict());
    }

    private static Status naIfFinancial(MultibaggerScore s, Object v) {
        if (v != null) return Status.MEASURED;
        return isFinancial(s) ? Status.NOT_APPLICABLE : Status.NOT_MEASURED;
    }

    private static Double d(Number n) {
        return n == null ? null : n.doubleValue();
    }

    private static final Set<String> NONE = Set.of();

    private static final List<Spec> SPECS = buildSpecs();

    private static List<Spec> buildSpecs() {
        List<Spec> s = new ArrayList<>();

        // --- The seven weighted dimensions (SPEC §12.5) -------------------------------
        // The first four are primitives and therefore always "measured"; they are listed so
        // their spread is trended, which is the half of this that catches a collapse.
        s.add(new Spec("TechnicalMomentum", x -> Status.MEASURED, x -> (double) x.getTechnicalMomentumScore(), true));
        s.add(new Spec("VolumeAccumulation", x -> Status.MEASURED, x -> (double) x.getVolumeAccumulationScore(), true));
        s.add(new Spec("RelativeStrength", x -> Status.MEASURED, x -> (double) x.getRelativeStrengthScore(), true));
        s.add(new Spec("PriceStructure", x -> Status.MEASURED, x -> (double) x.getPriceStructureScore(), true));
        s.add(new Spec("Valuation", x -> present(x.getValuationScore()), x -> d(x.getValuationScore()), true));
        s.add(new Spec("InstitutionalInterest", x -> present(x.getInstitutionalInterestScore()),
                x -> d(x.getInstitutionalInterestScore()), true));
        s.add(new Spec("FinancialQuality", x -> present(x.getFinancialQualityScore()),
                x -> d(x.getFinancialQualityScore()), true));

        // --- Shadow-mode signals and lenses ------------------------------------------
        s.add(new Spec("UnderDiscovery", x -> present(x.getUnderDiscoveryScore()),
                x -> d(x.getUnderDiscoveryScore()), true));
        s.add(new Spec("InsiderPulseScore", x -> present(x.getInsiderPulseScore()),
                x -> d(x.getInsiderPulseScore()), true));
        s.add(new Spec("CapexScore", x -> naIfFinancial(x, x.getCapexScore()), x -> d(x.getCapexScore()), true));
        s.add(new Spec("EarningsConsistency", x -> present(x.getEarningsConsistencyScore()),
                x -> d(x.getEarningsConsistencyScore()), true));

        // --- Lenses that contribute nothing to the composite --------------------------
        // Macro exposure (SPEC 48.7). MEASURED means the exposure map has a rule for this business;
        // NOT_MEASURED means it does not, which is a gap in the map and never a finding about the
        // company. NOT_APPLICABLE is deliberately never emitted: every listed business is exposed
        // to something, so an absence here is always our blind spot rather than its exemption.
        // No value, so `collapsed` stays null - a coverage row without a 0-100 score gets no
        // collapse verdict (Gotcha 88), and a false alarm on this channel is how a real one gets
        // ignored.
        s.add(new Spec("MacroExposure",
                x -> com.example.trading.macro.MacroExposureMap.hasMapping(x.getSymbol(),
                        com.example.trading.portfolio.SectorMapping.resolve(x.getSymbol(), x.getIndustry()),
                        x.getIndustry())
                        ? Status.MEASURED : Status.NOT_MEASURED,
                null, false));

        // Analyst targets (SPEC 49.7). MEASURED means at least one brokerage price target has
        // been recorded for this stock; NOT_MEASURED means none has. NOT_APPLICABLE is never
        // emitted - every listed company can be covered by an analyst, so an absence is our feed's
        // blind spot and not an exemption the business earned. This row is the honest answer to
        // "is that hit rate worth reading": headline-derived coverage is thin and the vector is
        // where that shows up, rather than in a confident-looking percentage (B-074's lesson).
        // No 0-100 value, so `collapsed` stays null (Gotcha 88).
        s.add(new Spec("AnalystTarget",
                x -> com.example.trading.analyst.AnalystTargetCoverage.available()
                        && com.example.trading.analyst.AnalystTargetCoverage.hasTarget(x.getSymbol())
                        ? Status.MEASURED : Status.NOT_MEASURED,
                null, false));

        // --- Verdicts ------------------------------------------------------------------
        s.add(new Spec("DcfVerdict",
                x -> verdict(x.getDcfVerdict(), Set.of("NOT_APPLICABLE"), Set.of("INSUFFICIENT_DATA")), null, false));
        s.add(new Spec("CapitalEfficiencyVerdict",
                x -> verdict(x.getCapitalEfficiencyVerdict(), Set.of("NA", "NA_FINANCIAL"), Set.of("NO_DATA")), null, false));
        s.add(new Spec("CapexVerdict",
                x -> verdict(x.getCapexVerdict(), Set.of("NA_FINANCIAL"), Set.of("NO_DATA")), null, false));
        s.add(new Spec("TurnaroundVerdict",
                x -> verdict(x.getTurnaroundVerdict(), NONE, Set.of("INSUFFICIENT_HISTORY")), null, false));
        s.add(new Spec("FinancialQualityVerdict",
                x -> verdict(x.getFinancialQualityVerdict(), NONE, NONE), null, false));
        s.add(new Spec("InsiderPulseVerdict",
                x -> verdict(x.getInsiderPulseVerdict(), NONE, Set.of("NO_DATA")), null, false));
        // UNKNOWN is a measurement gap, never a THIN verdict (Gotcha 33).
        s.add(new Spec("LiquidityTier", x -> verdict(x.getLiquidityTier(), NONE, Set.of("UNKNOWN")), null, false));

        // --- Underlying numeric inputs -------------------------------------------------
        s.add(new Spec("Roce", x -> naIfFinancial(x, x.getRocePercent()), MultibaggerScore::getRocePercent, false));
        s.add(new Spec("Roe", x -> present(x.getRoePercent()), MultibaggerScore::getRoePercent, false));
        s.add(new Spec("Roa", x -> present(x.getRoaPercent()), MultibaggerScore::getRoaPercent, false));
        s.add(new Spec("DebtToEquity", x -> naIfFinancial(x, x.getDebtToEquity()), MultibaggerScore::getDebtToEquity, false));
        s.add(new Spec("CashConversion", x -> present(x.getCashConversionRatio()),
                MultibaggerScore::getCashConversionRatio, false));
        s.add(new Spec("InterestCoverage", x -> present(x.getInterestCoverage()),
                MultibaggerScore::getInterestCoverage, false));
        s.add(new Spec("OcfToProfit", x -> present(x.getOcfToProfitRatio()),
                MultibaggerScore::getOcfToProfitRatio, false));
        s.add(new Spec("PromoterPledge", x -> present(x.getPromoterPledgePercent()),
                MultibaggerScore::getPromoterPledgePercent, false));
        // Growth and ownership, persisted since 2026-09-09 (SPEC §12.5). Listed so the screener's
        // new columns carry a coverage row: a null there must read as "not measured", never as weak.
        s.add(new Spec("PromoterHolding", x -> present(x.getPromoterHoldingPct()),
                MultibaggerScore::getPromoterHoldingPct, false));
        s.add(new Spec("YoyProfitGrowth", x -> present(x.getYoyProfitGrowth()),
                MultibaggerScore::getYoyProfitGrowth, false));
        s.add(new Spec("EarningsGrowthVerdict",
                x -> verdict(x.getEarningsGrowthVerdict(), NONE, NONE), null, false));
        // Gross margin is null for banks by design (no cost-of-goods line), so the financial
        // marker applies here too.
        s.add(new Spec("GrossMargin", x -> naIfFinancial(x, x.getGrossMarginPercent()),
                MultibaggerScore::getGrossMarginPercent, false));
        s.add(new Spec("PegRatio", x -> present(x.getPegRatio()), MultibaggerScore::getPegRatio, false));
        s.add(new Spec("DeliveryPercent", x -> present(x.getDeliveryPercent()),
                MultibaggerScore::getDeliveryPercent, false));
        s.add(new Spec("MarketCap", x -> present(x.getMarketCapCrores()), MultibaggerScore::getMarketCapCrores, false));
        s.add(new Spec("PeDeviation", x -> present(x.getPeDeviation()), MultibaggerScore::getPeDeviation, false));
        // B-060: monthly RSI was a constant 50.0 for every stock because the 365-day fetch
        // window cannot produce the 15 monthly bars RSI-14 needs. It is nullable now — this
        // row is how a repeat of that failure becomes visible on the day it happens.
        s.add(new Spec("MonthlyRsi", x -> present(x.getMonthlyRsi()), MultibaggerScore::getMonthlyRsi, true));
        s.add(new Spec("WeeklyRsi", x -> present(x.getWeeklyRsi()), MultibaggerScore::getWeeklyRsi, true));
        s.add(new Spec("LiquidityAdv20d", x -> present(x.getLiquidityAdv20d()),
                MultibaggerScore::getLiquidityAdv20d, false));
        s.add(new Spec("CircuitDays", x -> present(x.getCircuitDaysLast60()), x -> d(x.getCircuitDaysLast60()), false));

        return List.copyOf(s);
    }
}
