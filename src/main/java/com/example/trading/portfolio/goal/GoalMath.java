package com.example.trading.portfolio.goal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * What a goal still needs, computed without predicting anything (SPEC §53.3).
 *
 * <p><b>The headline figure here is the contribution at zero return, and that choice is the whole
 * design.</b> Every goal calculator in the industry asks for an expected rate and then quotes a
 * monthly figure to the rupee, which reads as a plan and is a forecast wearing a plan's clothes -
 * the exact shape §19 bars and §39.5 deleted a feature for. Change the assumed rate from 12% to 9%
 * and the "required" contribution moves by a third, so the precision is entirely borrowed from an
 * assumption nobody can check.
 *
 * <p>The amount needed if the investments earn <em>nothing</em> is different in kind: it is
 * arithmetic on two numbers the investor supplied, it cannot be wrong, and it is a genuine upper
 * bound on what the goal will cost per month. Anything the money earns is then a reason the goal
 * lands early rather than the reason it lands at all. That is also the only framing under which the
 * answer stays honest for a short-horizon goal, where the money really should be earning close to
 * nothing.
 *
 * <p>{@link #wouldHaveNeeded} exists for the second line and is deliberately awkward to use: it
 * takes a <b>measured</b> monthly rate, and every caller must label the result as what would have
 * happened over the period the rate was measured on, never as what will happen.
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public final class GoalMath {

    private GoalMath() {}

    /** Months from {@code today} to {@code target}; zero when the date has passed. */
    public static int monthsRemaining(LocalDate today, LocalDate target) {
        if (today == null || target == null) {
            return 0;
        }
        long m = ChronoUnit.MONTHS.between(
                today.withDayOfMonth(1), target.withDayOfMonth(1));
        return (int) Math.max(0, m);
    }

    /**
     * What is still to be found, in rupees.
     *
     * @return null when either leg is unstated - an unknown earmark is <b>not</b> zero, and
     *         treating it as zero would report a shortfall equal to the whole goal (Gotcha 21)
     */
    public static BigDecimal shortfall(BigDecimal target, BigDecimal earmarked) {
        if (target == null || earmarked == null) {
            return null;
        }
        BigDecimal gap = target.subtract(earmarked);
        return gap.signum() < 0 ? BigDecimal.ZERO : gap;
    }

    /**
     * The monthly contribution that reaches the goal <b>if the money earns nothing at all</b>.
     *
     * <p>The one figure here that is not a forecast, and the reason it is the headline.
     *
     * @return null when the shortfall is unknown, or when the date leaves no months to contribute
     *         in - a goal due this month needs the money now, not a monthly figure
     */
    public static BigDecimal requiredMonthlyAtZeroReturn(BigDecimal shortfall, int monthsRemaining) {
        if (shortfall == null || monthsRemaining <= 0) {
            return null;
        }
        return shortfall.divide(BigDecimal.valueOf(monthsRemaining), 2,
                java.math.RoundingMode.CEILING);
    }

    /**
     * What the monthly contribution would have been had the money compounded at a rate the app
     * actually measured over a past period.
     *
     * <p><b>Never presented as a projection.</b> The caller must name the period the rate came
     * from; a rate measured over 2018-2026 describes 2018-2026. This exists so the reader can see
     * how much of the gap history would have closed for them, which is a fair thing to know and a
     * dishonest thing to promise.
     *
     * @param annualRatePct the measured annual rate, e.g. 12.4 - may be negative
     * @return null when the inputs do not support an answer
     */
    public static BigDecimal wouldHaveNeeded(BigDecimal shortfall, int monthsRemaining,
                                             Double annualRatePct) {
        if (shortfall == null || monthsRemaining <= 0 || annualRatePct == null) {
            return null;
        }
        double monthly = Math.pow(1 + annualRatePct / 100.0, 1.0 / 12.0) - 1.0;
        if (Math.abs(monthly) < 1e-9) {
            return requiredMonthlyAtZeroReturn(shortfall, monthsRemaining);
        }
        // Future value of an ordinary annuity: FV = C * ((1+r)^n - 1) / r, solved for C.
        double factor = (Math.pow(1 + monthly, monthsRemaining) - 1) / monthly;
        if (factor <= 0) {
            return null;
        }
        return shortfall.divide(BigDecimal.valueOf(factor), 2, java.math.RoundingMode.CEILING);
    }

    /**
     * How far along the goal is, as a percentage of its target.
     *
     * @return null when either leg is unstated - never 0%, which would read as "nothing saved"
     *         rather than "not stated" (Gotcha 21, 68)
     */
    public static Double progressPct(BigDecimal target, BigDecimal earmarked) {
        if (target == null || earmarked == null || target.signum() <= 0) {
            return null;
        }
        return earmarked.doubleValue() / target.doubleValue() * 100.0;
    }
}
