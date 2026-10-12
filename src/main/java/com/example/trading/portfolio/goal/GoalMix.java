package com.example.trading.portfolio.goal;

import java.util.ArrayList;
import java.util.List;

/**
 * How much of a dated goal's money belongs in equity (SPEC §53.2).
 *
 * <p><b>This is the one investment decision in the whole app that needs no forecast</b>, which is
 * exactly why it is the one the app is willing to make. It does not ask which fund will do well, or
 * whether the market is cheap, or what happens next - all questions §19 bars and §52.13 measured to
 * be unanswerable from a track record anyway. It asks only: <em>on the day you need this money, how
 * much of it can you afford to find is worth less than you put in?</em> The answer follows from the
 * date, and the date is a fact the investor supplies.
 *
 * <p><b>The ramp below is a stated rule, not a measurement, and it is printed on screen so it can
 * be argued with.</b> That is the §43.4 discipline for a threshold with no cross-section to
 * calibrate against: say it is a judgement, say what it rests on, and flag it for recalibration.
 * What it rests on is measured, though, and is shown beside it - {@code GoalPlanService} asks this
 * app's own fund archive what the worst stretch of exactly this length did to the equity category
 * median, and prints what that would have done to the proposed mix. A reader who thinks the ramp is
 * too cautious can see the number it is protecting against.
 *
 * <p><b>The cap is 75% and that is because a goal has a date.</b> An open-ended long-term portfolio
 * is a different question and this table does not apply to it: there is no day on which it must be
 * worth a particular amount, so the argument for holding debt inside it is quite different. A goal
 * cannot be postponed by definition - that is what made it a goal - so the last few years before
 * the date are spent stepping down, which this ramp does automatically as the horizon shrinks.
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public final class GoalMix {

    private GoalMix() {}

    /**
     * The most equity a dated goal ever gets, as a percentage.
     *
     * <p>75, a judgement rather than a measured percentile, for the reason in the class note. It is
     * the parameter most worth arguing about and is the one to move first if the measured downside
     * beside it turns out to be milder than the ramp assumes (SPEC §53.5).
     */
    public static final double MAX_EQUITY_PCT = 75.0;

    /**
     * The horizon below which a goal gets no equity at all, in months.
     *
     * <p>Thirty-six, the same floor {@code HorizonFit.EQUITY_FLOOR_MONTHS} uses, and deliberately
     * the same number in both places rather than two constants that can drift apart (Gotcha 85).
     */
    public static final int NO_EQUITY_BELOW_MONTHS = 36;

    /**
     * A recommended split, and the reason for it.
     *
     * @param equityPct      share in equity, 0-{@link #MAX_EQUITY_PCT}
     * @param debtPct        the rest, always {@code 100 - equityPct}
     * @param months         the horizon this was computed for
     * @param stated         true when the horizon was long enough to say anything at all
     * @param reason         plain-English sentence, written for a reader who is not a professional
     */
    public record Mix(double equityPct, double debtPct, int months, boolean stated, String reason) {}

    /** The split for a goal {@code months} away. Never throws; a past date reads as zero months. */
    public static Mix forHorizon(int months) {
        int m = Math.max(0, months);
        double equity = equityPctFor(m);
        return new Mix(equity, 100.0 - equity, m, true,
                "This money is needed in " + phrase(m) + ". " + bandReason(m));
    }

    /** The equity share for a horizon, without the sentence. */
    public static double equityPctFor(int months) {
        int m = Math.max(0, months);
        if (m < NO_EQUITY_BELOW_MONTHS) return 0.0;
        if (m < 60) return 30.0;
        if (m < 84) return 50.0;
        if (m < 120) return 65.0;
        return MAX_EQUITY_PCT;
    }

    /**
     * Why a band is what it is, <b>without</b> the "this money is needed in N" preamble.
     *
     * <p>Separate because the rule table on screen prints one row per band, and repeating the
     * preamble down a column tells the reader the horizon twice per row in a table whose first
     * column is the horizon. The per-goal sentence keeps the preamble, because there the horizon is
     * the thing that decided it and the reader is meeting one goal rather than a table.
     */
    public static String bandReason(int months) {
        int m = Math.max(0, months);
        if (m < NO_EQUITY_BELOW_MONTHS) {
            return "Nothing goes in equity, because over a stretch that short what shares do is "
                    + "the market's mood rather than the businesses behind them, and a goal with a "
                    + "date cannot wait for a bad mood to pass.";
        }
        String why;
        if (m < 60) {
            why = "Three to five years is long enough for some equity, but not long enough to "
                    + "recover comfortably from a bad one - so most of it stays in debt.";
        } else if (m < 84) {
            why = "Five to seven years has room for a bad year and a recovery, so the split is "
                    + "about even.";
        } else if (m < 120) {
            why = "Seven to ten years is long enough that the businesses matter more than the "
                    + "mood, so equity leads.";
        } else {
            why = "Ten years or more is the horizon equity is actually for.";
        }
        return why + " The remainder sits in debt so that a fall in the last year before the date "
                + "cannot take the whole goal with it.";
    }

    /** The caveats that must travel with any mix. Never empty. */
    public static List<String> caveats() {
        List<String> out = new ArrayList<>();
        out.add("This is a split between two kinds of investment, not a list of funds. It says "
                + "nothing about which fund to hold, because this app measured on its own archive "
                + "that a fund's past ranking carried no information about its next one.");
        out.add("The percentages are a stated rule rather than a measurement, printed here so you "
                + "can disagree with them. What is measured is the figure beside them: the worst "
                + "stretch of exactly this length in this app's fund archive, which starts in "
                + "April 2018 and therefore contains one bad year for shares and one for bonds.");
        out.add("A goal with no date is not a goal for these purposes, and this does not apply to "
                + "money you are simply accumulating. There is no day on which that has to be "
                + "worth a particular amount, which is the whole thing this table turns on.");
        out.add("Moving money between equity and debt is a sale and a purchase. It can be taxable "
                + "and it can carry a charge on the way out, and neither is calculated here.");
        return out;
    }

    static String phrase(int months) {
        if (months <= 0) {
            return "less than a month";
        }
        if (months < 12) {
            return months + (months == 1 ? " month" : " months");
        }
        int years = months / 12;
        int rest = months % 12;
        String y = years + (years == 1 ? " year" : " years");
        return rest == 0 ? y : y + " and " + rest + (rest == 1 ? " month" : " months");
    }
}
