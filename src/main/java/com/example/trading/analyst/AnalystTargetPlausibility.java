package com.example.trading.analyst;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What would have to be true for an analyst's target to be the fair value (SPEC §49.13).
 * Pure: no repository, no clock, no I/O.
 *
 * <p><b>The question this answers, and the one it refuses.</b> It does not say whether a share
 * price will reach a target — that is short-term price prediction and §19 bars it outright, which
 * is also why nothing here carries a probability, a date or an instruction. It says what the
 * <em>business</em> would have to deliver for the target to be worth paying: run this app's own
 * reverse DCF (§12.5) backwards from the target price instead of from the market price, and
 * compare the growth rate that falls out against what the company has actually delivered.
 *
 * <p><b>Why this is the check and the composite is not.</b> §49.12 measured the correlation between
 * a house's claimed upside and this app's composite at <b>-0.322</b>, because 54% of the composite's
 * weight is price behaviour and a large claimed upside is by construction a stock trading far below
 * where the house thinks it belongs. The composite is therefore structurally incapable of verifying
 * a target. A required growth rate is not: it is a statement about earnings, comparable directly
 * against the record.
 *
 * <p><b>The arithmetic is exact, and the cash flow cancels.</b> {@code fairValue(fcf, g, r)} is
 * linear in the cash flow, so {@code fairValue = fcf × F(g)}. Today's price gives
 * {@code fcf × F(g0) = marketCap}; the target gives {@code fcf × F(g1) = marketCap × target/price}.
 * Dividing, <b>{@code F(g1) = F(g0) × target/price}</b> — the cash-flow proxy drops out entirely.
 * So the requirement is recoverable from the stored implied growth and the price ratio alone, with
 * no new fetch and no re-derivation of a figure that might disagree with the stored one.
 *
 * <p><b>Three refusals.</b> A stock with no stored implied growth (loss-making, or the filing was
 * unreadable) reports {@link Verdict#NOT_MEASURED} and no number — never a zero. A stock with an
 * implied growth but no historical growth reports the requirement as a fact and
 * {@link Verdict#NO_RECORD_TO_COMPARE}, because "18% a year is needed" is useful and "that is more
 * than it has managed" is a claim that needs a record behind it. And a requirement outside the
 * solver's bracket is {@link Verdict#BEYOND_MODEL_RANGE}, not a clamped number at the edge.
 */
public final class AnalystTargetPlausibility {

    private AnalystTargetPlausibility() {
    }

    // The §12.5 model, restated here rather than imported, because these five constants ARE the
    // contract: a reading produced under different ones is not comparable with the stored
    // dcf_implied_growth_percent it is inverted from. If IntrinsicValuationService ever changes
    // them, AnalystTargetPlausibilityTest's round-trip case fails, which is the intended alarm.
    static final double DISCOUNT_RATE = 0.12;
    static final double TERMINAL_GROWTH = 0.04;
    static final int FORECAST_YEARS = 10;
    static final double SEARCH_LO = -0.50;
    static final double SEARCH_HI = 0.60;
    private static final int MAX_ITERS = 80;
    private static final double TOL = 1e-9;

    /**
     * A growth rate above this, sustained for a decade, is rare enough to be worth naming.
     *
     * <p>Same threshold §12.5's {@code classify()} uses for {@code EXTREMELY_EXPENSIVE}. It does
     * not change the verdict — it adds a sentence, because "the target needs 34% a year for ten
     * years" is a fact a reader should be handed in words rather than left to infer from a number.
     */
    static final double RARE_GROWTH = 30.0;

    /**
     * How demanding the target is, measured against the company's own record.
     *
     * <p>The band boundaries are §12.5's expectation-gap boundaries (±5 and +12 percentage points),
     * deliberately: "how much growth does this price assume, versus what has been delivered" is one
     * question, and asking it at a target price rather than at the market price does not make it a
     * different one (Gotcha 85). The <em>vocabulary</em> differs because the subject does — §12.5
     * grades a price you could pay, this grades a claim somebody else published — and the two must
     * not be confusable on a screen that shows both (Gotcha 105).
     */
    public enum Verdict {
        /** The target needs less growth than the company has been delivering. */
        BELOW_ITS_RECORD,
        /** Within ±5pp of its own record — the target asks for more of the same. */
        IN_LINE_WITH_RECORD,
        /** 5 to 12pp a year more than it has delivered. */
        ABOVE_ITS_RECORD,
        /** More than 12pp a year above its record, every year for a decade. */
        FAR_ABOVE_ITS_RECORD,
        /** The requirement is computable but no growth history exists to judge it against. */
        NO_RECORD_TO_COMPARE,
        /** The target sits outside the range the model can solve. */
        BEYOND_MODEL_RANGE,
        /** No stored implied growth — loss-making, or the accounts could not be read. */
        NOT_MEASURED
    }

    /**
     * The fewest years of accounts that can stand behind a ten-year growth requirement.
     *
     * <p><b>Set by a defect, not by taste.</b> The first build of this panel compared the
     * requirement against {@code dcf_historical_growth_percent}, which is a <b>two-year</b> profit
     * CAGR, and on the first live run every stock read the same verdict: BRIGADE 37%, SONACOMS 47%,
     * TITAN 63%, DIVISLAB 66%, GALAXYSURF <b>109%</b>. Those are base effects, not records — a
     * two-year CAGR off a depressed year is enormous — and against them a 10–26% requirement is
     * always "below its record". Zero variance across six stocks is the signature this codebase
     * has been caught by three times (Institutional Interest constant at 40, monthly RSI constant
     * at 50, Insider Pulse measured on nothing), so it is treated as a defect on sight.
     *
     * <p>Comparing a ten-year requirement with a two-year CAGR is a horizon mismatch of the same
     * family as B-047 filing a quarter as a year. Four years is the floor because it is where the
     * app already draws the line for a claim about the past (B-066's bonus discriminator,
     * §32's turnaround detector), and the measured depth supports it: median 6 years, 226 of 366
     * symbols at 4 or more.
     */
    static final int MIN_YEARS_FOR_RECORD = 4;

    /**
     * What the company has actually delivered, and over how long.
     *
     * <p>{@code years} is carried everywhere the CAGR is, because a growth rate over four years
     * and one over ten are different claims wearing the same units (§43's discipline).
     */
    public record GrowthRecord(Double cagrPercent, int years, Integer fromYear, Integer toYear,
                               String note) {
        public boolean measured() {
            return cagrPercent != null;
        }
    }

    /**
     * Profit CAGR across the longest run of annual accounts on file.
     *
     * <p>Three refusals. Fewer than {@link #MIN_YEARS_FOR_RECORD} years is <b>no record</b> rather
     * than a short one. A starting profit at or below zero yields <b>no CAGR at all</b> — a
     * company that went from a loss to a profit has an undefined growth rate, and the arithmetic
     * would otherwise produce either a negative rate or a meaningless huge one for what is
     * genuinely good news. And a missing year in the middle shortens the window rather than being
     * interpolated: this counts the span actually observed.
     *
     * @param profitByYear fiscal year to net profit, any order, nulls allowed
     */
    public static GrowthRecord growthRecord(Map<Integer, Double> profitByYear) {
        if (profitByYear == null || profitByYear.isEmpty()) {
            return new GrowthRecord(null, 0, null, null,
                    "No annual accounts are on file for this company yet.");
        }
        java.util.TreeMap<Integer, Double> sorted = new java.util.TreeMap<>();
        for (Map.Entry<Integer, Double> e : profitByYear.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) sorted.put(e.getKey(), e.getValue());
        }
        if (sorted.size() < MIN_YEARS_FOR_RECORD) {
            return new GrowthRecord(null, sorted.size(), null, null, String.format(
                    "Only %d year%s of accounts %s on file. A growth rate needed for ten years "
                            + "cannot be judged against that, so no comparison is shown.",
                    sorted.size(), sorted.size() == 1 ? "" : "s",
                    sorted.size() == 1 ? "is" : "are"));
        }

        int from = sorted.firstKey();
        int to = sorted.lastKey();
        double first = sorted.firstEntry().getValue();
        double last = sorted.lastEntry().getValue();
        int spans = to - from;
        if (spans <= 0) {
            return new GrowthRecord(null, sorted.size(), from, to,
                    "The accounts on file do not span more than one year.");
        }
        if (first <= 0) {
            return new GrowthRecord(null, sorted.size(), from, to, String.format(
                    "The company was not profitable in %d, the earliest year on file, so a growth "
                            + "rate from that base does not exist. That is not a bad sign — a "
                            + "recovery from a loss simply cannot be expressed as a CAGR.", from));
        }
        if (last <= 0) {
            return new GrowthRecord(null, sorted.size(), from, to, String.format(
                    "The company was loss-making in %d, the latest year on file, so there is no "
                            + "growth rate to compare against.", to));
        }

        double cagr = (Math.pow(last / first, 1.0 / spans) - 1.0) * 100.0;
        return new GrowthRecord(cagr, sorted.size(), from, to, String.format(
                "Profit growth of about %.0f%% a year between %d and %d, from %d years of accounts.",
                cagr, from, to, sorted.size()));
    }

    /** One target, run backwards through the valuation model. */
    public record Read(Verdict verdict,
                       Double requiredGrowthPercent,
                       Double impliedGrowthPercent,
                       Double historicalGrowthPercent,
                       Integer yearsOfRecord,
                       Double gapVsHistoryPoints,
                       Double gapVsTodayPoints,
                       String reason) {

        /** True when there is a number on screen at all. */
        public boolean measured() {
            return requiredGrowthPercent != null;
        }
    }

    /**
     * Invert the valuation at one target price.
     *
     * @param storedImpliedGrowthPct  {@code multibagger_scores.dcf_implied_growth_percent} — the
     *                                growth today's price already assumes. Null means the DCF did
     *                                not apply (loss-making) or could not be computed.
     * @param record                  the multi-year profit record from {@link #growthRecord}.
     *                                Deliberately <b>not</b> {@code dcf_historical_growth_percent}:
     *                                that is a two-year CAGR, and judging a ten-year requirement
     *                                against it made every stock read the same verdict on the
     *                                first live run (see {@link #MIN_YEARS_FOR_RECORD}).
     * @param price                   the price the implied growth was solved at
     * @param target                  the published target
     */
    public static Read forTarget(Double storedImpliedGrowthPct,
                                 GrowthRecord record,
                                 double price,
                                 double target) {
        GrowthRecord rec = record == null
                ? new GrowthRecord(null, 0, null, null, "No annual accounts on file.") : record;
        Double historicalGrowthPct = rec.cagrPercent();
        Integer years = rec.years() == 0 ? null : rec.years();

        if (storedImpliedGrowthPct == null || price <= 0 || target <= 0) {
            return new Read(Verdict.NOT_MEASURED, null, storedImpliedGrowthPct, historicalGrowthPct,
                    years, null, null,
                    "This app has no valuation model on file for this stock, so it cannot say what "
                            + "the target would require. That usually means the company is "
                            + "loss-making — a discounted-cash-flow model does not apply to one — "
                            + "or its latest accounts could not be read. It is not a comment on "
                            + "the target.");
        }

        double g0 = storedImpliedGrowthPct / 100.0;
        double wanted = pvFactor(g0) * (target / price);
        Double g1 = solveGrowthForFactor(wanted);

        if (g1 == null) {
            return new Read(Verdict.BEYOND_MODEL_RANGE, null, storedImpliedGrowthPct,
                    historicalGrowthPct, years, null, null,
                    String.format("The target is %.0f%% away from the price, which lands outside "
                                    + "the range this model solves in (%.0f%% to %.0f%% a year). "
                                    + "No growth figure is shown rather than one pinned to the edge "
                                    + "of the range, which would look like a measurement.",
                            (target - price) / price * 100.0, SEARCH_LO * 100, SEARCH_HI * 100));
        }

        double requiredPct = g1 * 100.0;
        Double gapHistory = historicalGrowthPct == null ? null : requiredPct - historicalGrowthPct;
        double gapToday = requiredPct - storedImpliedGrowthPct;

        Verdict verdict = classify(gapHistory);
        return new Read(verdict, requiredPct, storedImpliedGrowthPct, historicalGrowthPct, years,
                gapHistory, gapToday,
                reason(verdict, requiredPct, storedImpliedGrowthPct, rec, gapHistory));
    }

    static Verdict classify(Double gapHistoryPoints) {
        if (gapHistoryPoints == null) return Verdict.NO_RECORD_TO_COMPARE;
        if (gapHistoryPoints < -5.0) return Verdict.BELOW_ITS_RECORD;
        if (gapHistoryPoints < 5.0) return Verdict.IN_LINE_WITH_RECORD;
        if (gapHistoryPoints < 12.0) return Verdict.ABOVE_ITS_RECORD;
        return Verdict.FAR_ABOVE_ITS_RECORD;
    }

    /** The sentence that must travel with the number, in the reader's language (SPEC §21). */
    static String reason(Verdict v, double required, double implied, GrowthRecord rec, Double gap) {
        Double historical = rec.cagrPercent();
        StringBuilder s = new StringBuilder();
        s.append(String.format("For this target to be what the business is worth, profits would "
                + "have to grow about %.0f%% a year for ten years. Today's price already assumes "
                + "%.0f%%.", required, implied));

        if (historical == null) {
            s.append(" ").append(rec.note())
                    .append(" So there is nothing to compare that requirement against — the number "
                            + "above is a fact about the target, not a judgement on it.");
        } else {
            s.append(String.format(" Over %d years of accounts (%d to %d) it has actually "
                            + "delivered about %.0f%% a year.",
                    rec.years(), rec.fromYear(), rec.toYear(), historical));
            switch (v) {
                case BELOW_ITS_RECORD -> s.append(String.format(
                        " The target therefore asks for %.0f points a year LESS than the company "
                                + "has been managing — an undemanding target, if the record holds.",
                        Math.abs(gap)));
                case IN_LINE_WITH_RECORD -> s.append(
                        " The target asks for roughly more of the same, which is the least "
                                + "demanding thing a target can ask for.");
                case ABOVE_ITS_RECORD -> s.append(String.format(
                        " The target needs about %.0f points a year MORE than that, every year "
                                + "for a decade. Worth asking what the house thinks changes.",
                        gap));
                case FAR_ABOVE_ITS_RECORD -> s.append(String.format(
                        " The target needs about %.0f points a year MORE than that, sustained for "
                                + "a decade. That is a large gap between what the target requires "
                                + "and what the company has shown it can do.", gap));
                default -> { }
            }
        }

        if (required > RARE_GROWTH) {
            s.append(String.format(" Note that %.0f%% a year sustained for ten years is rare for "
                    + "any business.", required));
        }
        return s.toString();
    }

    /**
     * The limits, which travel with every reading.
     *
     * <p>The first of these is not a nicety. §12.5 records that this model systematically
     * under-values long-duration compounders — IT services, platforms, pharma — because ten years
     * plus 4% terminal growth cannot represent a franchise that compounds beyond the window. For
     * exactly those businesses a {@code FAR_ABOVE_ITS_RECORD} reading is the model's known bias
     * showing, and a reader who is not told that will mistake it for a finding about the analyst.
     */
    public static Map<String, String> caveat() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("whatThisIs", "This app's own valuation model, run backwards from the analyst's "
                + "target instead of from today's price, to work out what the business would have "
                + "to deliver for that target to be the fair value.");
        m.put("notAPrediction", "It is not a view on whether the share price will reach the "
                + "target, and there is no probability or date here. It answers a question about "
                + "the company's earnings, which is a thing that can be checked against the record.");
        m.put("knownBias", "The model runs ten years and then assumes 4% growth for ever, which "
                + "systematically under-values businesses that compound for longer than that — "
                + "software, platforms, branded pharma. For those, a large required-growth figure "
                + "is partly the model's own limitation and should be read sceptically.");
        m.put("notForLenders", "It also does not fit banks, insurers or commodity cyclicals, whose "
                + "value is not well described by a cash-flow projection. Those read as not "
                + "measured rather than being given a number that would not mean anything.");
        m.put("oneInputOnly", "Growth is only one of the things that would have to be true. The "
                + "checks beside this one — whether the accounts carry a flag, the balance-sheet "
                + "quality, and whether the business has actually compounded before — are the rest "
                + "of the answer, and a demanding target on a company with a clean long record is "
                + "a different proposition from the same target on one without.");
        return m;
    }

    // ------------------------------------------------------------------ the model

    /**
     * Present value of a unit cash flow growing at {@code g}, then a Gordon terminal value.
     *
     * <p>Character for character the §12.5 {@code fairValue()} with the cash flow set to 1 —
     * including the terminal-growth clamp, which matters below 4% where the terminal rate follows
     * the forecast rate down rather than staying at 4%.
     */
    static double pvFactor(double g) {
        return pvFactor(g, DISCOUNT_RATE);
    }

    static double pvFactor(double g, double r) {
        double pv = 0;
        double cf = 1.0;
        for (int y = 1; y <= FORECAST_YEARS; y++) {
            cf = cf * (1 + g);
            pv += cf / Math.pow(1 + r, y);
        }
        double terminalG = Math.min(TERMINAL_GROWTH, g);
        if (r > terminalG) {
            pv += (cf * (1 + terminalG) / (r - terminalG)) / Math.pow(1 + r, FORECAST_YEARS);
        }
        return pv;
    }

    /**
     * Bisect for the growth rate producing a given present-value factor.
     *
     * <p>Returns null when the bracket does not contain the answer, rather than the nearest bound:
     * a target 400% above the price genuinely cannot be expressed as a ten-year growth rate inside
     * this model, and reporting 60% would be a measurement the model never made.
     */
    static Double solveGrowthForFactor(double wanted) {
        double lo = SEARCH_LO, hi = SEARCH_HI;
        double fLo = pvFactor(lo) - wanted;
        double fHi = pvFactor(hi) - wanted;
        if (fLo * fHi > 0) return null;
        for (int i = 0; i < MAX_ITERS; i++) {
            double mid = 0.5 * (lo + hi);
            double fMid = pvFactor(mid) - wanted;
            if (Math.abs(fMid) < TOL) return mid;
            if (fLo * fMid < 0) {
                hi = mid;
            } else {
                lo = mid;
                fLo = fMid;
            }
        }
        return 0.5 * (lo + hi);
    }
}
