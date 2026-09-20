package com.example.trading.portfolio.goal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the goal arithmetic (SPEC §53.3).
 *
 * <p>Every case here is about a <b>refusal</b> or about the choice of headline figure. The
 * arithmetic itself is an annuity formula and is not interesting; what is interesting is that the
 * figure this app leads with is the one that contains no forecast.
 */
class GoalMathTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 20);

    @Test
    @DisplayName("an unstated earmark leaves the shortfall unmeasured, never equal to the target")
    void unstatedEarmarkIsNotZero() {
        // Treating "not stated" as zero would report a shortfall of the whole goal and then a
        // monthly figure to fund it from scratch - a confident, wrong and quite alarming number.
        // Gotcha 21 with a rupee sign on it.
        assertThat(GoalMath.shortfall(BigDecimal.valueOf(1_000_000), null)).isNull();
        assertThat(GoalMath.progressPct(BigDecimal.valueOf(1_000_000), null)).isNull();

        // And an unstated target is equally unmeasured, rather than being read as zero needed.
        assertThat(GoalMath.shortfall(null, BigDecimal.valueOf(50_000))).isNull();
    }

    @Test
    @DisplayName("an over-funded goal reports zero to find, never a negative contribution")
    void overFundedGoalsFloorAtZero() {
        BigDecimal gap = GoalMath.shortfall(BigDecimal.valueOf(100_000), BigDecimal.valueOf(150_000));
        assertThat(gap).isEqualByComparingTo(BigDecimal.ZERO);
        // A negative "required monthly" would read as an instruction to take money out.
        assertThat(GoalMath.requiredMonthlyAtZeroReturn(gap, 24))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("the zero-return figure is plain division and rounds up, never down")
    void zeroReturnFigureRoundsUp() {
        // Rounding down produces a monthly figure that does not reach the goal, which is the one
        // direction this number must never fail in.
        BigDecimal monthly = GoalMath.requiredMonthlyAtZeroReturn(BigDecimal.valueOf(100_000), 7);
        assertThat(monthly.doubleValue() * 7).isGreaterThanOrEqualTo(100_000.0);
        assertThat(monthly.doubleValue()).isCloseTo(14285.72, within(0.02));
    }

    @Test
    @DisplayName("a goal due this month gets no monthly figure at all")
    void aGoalDueNowHasNoMonthlyAnswer() {
        // The honest answer is that the money is needed now, not that it can be found in zero
        // instalments. Returning anything here would be a division by zero wearing a plan's clothes.
        assertThat(GoalMath.requiredMonthlyAtZeroReturn(BigDecimal.valueOf(100_000), 0)).isNull();
        assertThat(GoalMath.monthsRemaining(TODAY, LocalDate.of(2026, 9, 1))).isZero();
        assertThat(GoalMath.monthsRemaining(TODAY, LocalDate.of(2020, 1, 1))).isZero();
    }

    @Test
    @DisplayName("the measured-rate figure needs a rate and refuses without one")
    void noRateNoProjection() {
        // The second line exists so a reader can see how much of the gap history would have closed
        // for them. Without a measured rate there is no second line - it is never defaulted to a
        // conventional 12%, which is where every other goal calculator gets its confidence from.
        assertThat(GoalMath.wouldHaveNeeded(BigDecimal.valueOf(100_000), 60, null)).isNull();
        assertThat(GoalMath.wouldHaveNeeded(null, 60, 12.0)).isNull();
        assertThat(GoalMath.wouldHaveNeeded(BigDecimal.valueOf(100_000), 0, 12.0)).isNull();
    }

    @Test
    @DisplayName("a compounding rate lowers the contribution, and a zero rate matches the floor")
    void compoundingIsArithmeticallySane() {
        BigDecimal gap = BigDecimal.valueOf(1_200_000);
        BigDecimal atZero = GoalMath.requiredMonthlyAtZeroReturn(gap, 120);
        BigDecimal atTwelve = GoalMath.wouldHaveNeeded(gap, 120, 12.0);

        assertThat(atTwelve).isLessThan(atZero);
        // The two must agree exactly at a rate of nothing, or the headline figure and the line
        // beneath it are computed by two different rules (Gotcha 85 in miniature).
        assertThat(GoalMath.wouldHaveNeeded(gap, 120, 0.0)).isEqualByComparingTo(atZero);
    }

    @Test
    @DisplayName("a negative measured rate raises the contribution rather than throwing")
    void negativeRatesAreHandled() {
        // The archive contains windows where the measured rate was negative. A goal planner that
        // fell over on them would only work in good markets, which is the wrong half.
        BigDecimal needed = GoalMath.wouldHaveNeeded(BigDecimal.valueOf(600_000), 36, -4.0);
        assertThat(needed).isNotNull();
        assertThat(needed).isGreaterThan(
                GoalMath.requiredMonthlyAtZeroReturn(BigDecimal.valueOf(600_000), 36));
    }

    @Test
    @DisplayName("months are counted between month starts, so a date late in a month is not lost")
    void monthCountingIsStable() {
        assertThat(GoalMath.monthsRemaining(TODAY, LocalDate.of(2027, 9, 20))).isEqualTo(12);
        // The 1st and the 28th of the same month must give the same answer, or a goal's horizon
        // - and therefore its whole mix - depends on which day of the month it is read.
        assertThat(GoalMath.monthsRemaining(LocalDate.of(2026, 9, 1), LocalDate.of(2029, 9, 1)))
                .isEqualTo(GoalMath.monthsRemaining(LocalDate.of(2026, 9, 28),
                        LocalDate.of(2029, 9, 28)));
        assertThat(GoalMath.monthsRemaining(null, TODAY)).isZero();
        assertThat(GoalMath.monthsRemaining(TODAY, null)).isZero();
    }
}
