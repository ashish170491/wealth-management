package com.example.trading.funds;

import com.example.trading.funds.FundVocabulary.PlanCostVerdict;
import com.example.trading.funds.RollingWindowRecord.Point;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the distributor commission on a Regular-plan holding actually costs, measured (SPEC §52.8).
 *
 * <p><b>Why this is the most useful number in the module.</b> AMFI publishes no machine-readable
 * expense ratio - the page is a React shell with per-AMC downloads and no endpoint - so the obvious
 * cost signal is unavailable. But the Direct and Regular plans of one scheme are <b>two published
 * NAV series over a single identical portfolio</b>, differing only by the commission paid out of
 * one of them. The gap between their compound rates therefore <em>is</em> the commission,
 * per fund, per year, from a free file. That is the B-018 move: when NSE walled the quote endpoint,
 * the price-to-earnings ratio was computed from filings rather than abandoned.
 *
 * <p>Note what this is <b>not</b>: it is not a second cost penalty on top of the return figures. A
 * net asset value is already net of expenses, so an excess return measured from NAVs has already
 * paid for the commission once. Subtracting it again would be double-counting. This number answers
 * a different question - <em>what is this particular plan costing, and does a cheaper share class
 * of the same portfolio exist?</em>
 *
 * <p><b>And it stops at the fact.</b> Naming the drag without naming that moving between plans is a
 * redemption followed by a purchase - taxable, possibly exit-loaded - would read as advice, which
 * SPEC §19 and §20 rule 10 bar. So the drag is stated and the arithmetic of acting on it is
 * refused, deliberately and in writing (SPEC §52.11 refusal 3).
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public record PlanCostRead(
        PlanCostVerdict verdict,
        Double directCagrPct,
        Double regularCagrPct,
        Double annualDragPct,
        int monthsCompared,
        String reason,
        String caveat
) {

    /** The sentence that must travel with every measured drag. */
    public static final String SWITCHING_CAVEAT =
            "Moving from a Regular plan to the Direct plan of the same scheme is a redemption "
                    + "followed by a fresh purchase: it can realise capital gains tax and can "
                    + "attract an exit load, and it restarts the holding period. This app measures "
                    + "the annual drag and deliberately does not compute whether covering those "
                    + "costs is worthwhile - that calculation depends on figures it does not have.";

    /**
     * Measure one scheme's two share classes against each other.
     *
     * @param direct    the Direct-plan Growth month-end series
     * @param regular   the Regular-plan Growth month-end series of the same scheme
     * @param minMonths minimum overlapping observations before a figure is published
     */
    public static PlanCostRead measure(List<Point> direct, List<Point> regular, int minMonths) {
        if (regular == null || regular.isEmpty()) {
            return new PlanCostRead(PlanCostVerdict.NOT_APPLICABLE, null, null, null, 0,
                    "no Regular-plan series was supplied, so there is no commission to measure",
                    null);
        }
        if (direct == null || direct.isEmpty()) {
            // Reported as unmeasured, never as zero drag. A Regular plan whose Direct twin is
            // absent from the file is still paying a commission; what is missing is our ability to
            // see it (Gotcha 21).
            return new PlanCostRead(PlanCostVerdict.REGULAR_PLAN_NO_TWIN, null, null, null, 0,
                    "this is a Regular plan and no Direct series for the same scheme is on file, "
                            + "so the commission cannot be measured here. That is a gap in this "
                            + "app's data, not evidence that the plan is free",
                    SWITCHING_CAVEAT);
        }

        Map<YearMonth, BigDecimal> d = byMonth(direct);
        Map<YearMonth, BigDecimal> r = byMonth(regular);
        List<YearMonth> aligned = d.keySet().stream().filter(r::containsKey).sorted().toList();

        if (aligned.size() < minMonths) {
            return new PlanCostRead(PlanCostVerdict.NOT_MEASURED, null, null, null, aligned.size(),
                    "the two plans overlap on only " + aligned.size() + " month ends and "
                            + minMonths + " are needed. A commission read off a short window is "
                            + "mostly the rounding in two net asset values",
                    SWITCHING_CAVEAT);
        }

        YearMonth from = aligned.get(0);
        YearMonth to = aligned.get(aligned.size() - 1);
        int months = (to.getYear() - from.getYear()) * 12 + (to.getMonthValue() - from.getMonthValue());

        Double directCagr = RollingWindowRecord.cagrPct(d.get(from), d.get(to), months);
        Double regularCagr = RollingWindowRecord.cagrPct(r.get(from), r.get(to), months);
        if (directCagr == null || regularCagr == null) {
            return new PlanCostRead(PlanCostVerdict.NOT_MEASURED, directCagr, regularCagr, null,
                    aligned.size(),
                    "one of the two series has no usable value at an end of the window",
                    SWITCHING_CAVEAT);
        }

        double drag = directCagr - regularCagr;
        String reason = String.format(
                "over %d months to %s the Direct plan of this scheme compounded at %.2f%% a year "
                        + "and the Regular plan at %.2f%%, a difference of %.2f percentage points a "
                        + "year. Both series price the same portfolio, so that difference is the "
                        + "distributor commission rather than a difference in what is owned",
                months, to, directCagr, regularCagr, drag);

        return new PlanCostRead(PlanCostVerdict.REGULAR_PLAN_MEASURED, directCagr, regularCagr,
                drag, aligned.size(), reason, SWITCHING_CAVEAT);
    }

    /** A position already held in the Direct plan - there is no commission to measure. */
    public static PlanCostRead alreadyDirect() {
        return new PlanCostRead(PlanCostVerdict.DIRECT_PLAN, null, null, null, 0,
                "this position is already the Direct plan, which carries no distributor commission",
                null);
    }

    private static Map<YearMonth, BigDecimal> byMonth(List<Point> points) {
        Map<YearMonth, BigDecimal> out = new LinkedHashMap<>();
        for (Point p : points) {
            if (p != null && p.month() != null && p.nav() != null && p.nav().signum() > 0) {
                out.put(p.month(), p.nav());
            }
        }
        return out;
    }
}
