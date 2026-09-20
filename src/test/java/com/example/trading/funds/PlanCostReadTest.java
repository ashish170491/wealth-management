package com.example.trading.funds;

import com.example.trading.funds.FundVocabulary.PlanCostVerdict;
import com.example.trading.funds.RollingWindowRecord.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * SPEC §52.8 - the distributor commission, measured from the two share classes of one scheme.
 *
 * <p>This is the only genuinely new number the module produces, and the only one whose usefulness
 * does not decay: a fund's past return does not predict its future return, and its cost does.
 */
class PlanCostReadTest {

    private static final int MIN = 36;

    @Test
    @DisplayName("the gap between the two plans IS the commission, in points a year")
    void dragIsMeasuredFromTheTwoSeries() {
        // One portfolio, two share classes: 14.0% a year Direct, 12.9% Regular.
        List<Point> direct = compounding(60, 14.0);
        List<Point> regular = compounding(60, 12.9);

        PlanCostRead r = PlanCostRead.measure(direct, regular, MIN);

        assertThat(r.verdict()).isEqualTo(PlanCostVerdict.REGULAR_PLAN_MEASURED);
        assertThat(r.annualDragPct()).isCloseTo(1.1, offset(0.02));
        assertThat(r.reason())
                .contains("same portfolio")
                .contains("distributor commission");
    }

    @Test
    @DisplayName("a Regular plan with no Direct twin on file is unmeasured, never free")
    void missingTwinIsNotZeroDrag() {
        PlanCostRead r = PlanCostRead.measure(List.of(), compounding(60, 12.0), MIN);

        assertThat(r.verdict()).isEqualTo(PlanCostVerdict.REGULAR_PLAN_NO_TWIN);
        assertThat(r.annualDragPct()).as("null, never 0.0 - a commission we cannot see is still paid").isNull();
        assertThat(r.reason()).contains("not evidence that the plan is free");
    }

    @Test
    @DisplayName("a Direct holding has nothing to measure and says so without implying a saving")
    void directPlanIsNotApplicable() {
        PlanCostRead r = PlanCostRead.alreadyDirect();

        assertThat(r.verdict()).isEqualTo(PlanCostVerdict.DIRECT_PLAN);
        assertThat(r.annualDragPct()).isNull();
        assertThat(r.caveat()).as("no switching caveat is needed where there is no switch").isNull();
    }

    @Test
    @DisplayName("a short overlap is refused - a commission off a few months is rounding")
    void shortOverlapIsRefused() {
        PlanCostRead r = PlanCostRead.measure(compounding(10, 14.0), compounding(10, 12.9), MIN);

        assertThat(r.verdict()).isEqualTo(PlanCostVerdict.NOT_MEASURED);
        assertThat(r.annualDragPct()).isNull();
        assertThat(r.reason()).contains("rounding in two net asset values");
    }

    @Test
    @DisplayName("every measured drag carries the sentence that stops it reading as advice")
    void measuredDragAlwaysCarriesTheSwitchingCaveat() {
        PlanCostRead r = PlanCostRead.measure(compounding(60, 14.0), compounding(60, 12.9), MIN);

        assertThat(r.caveat()).isEqualTo(PlanCostRead.SWITCHING_CAVEAT);
        assertThat(r.caveat())
                .contains("redemption")
                .contains("deliberately does not compute");
    }

    @Test
    @DisplayName("the two series are aligned by month, so a gap in one does not shift the other")
    void alignmentIsByMonthNotByPosition() {
        List<Point> direct = compounding(60, 14.0);
        List<Point> regular = new ArrayList<>(compounding(60, 12.9));
        regular.remove(30);
        regular.remove(29);

        PlanCostRead r = PlanCostRead.measure(direct, regular, MIN);

        assertThat(r.monthsCompared()).isEqualTo(58);
        assertThat(r.annualDragPct())
                .as("two missing months must not turn a 1.1-point commission into something else")
                .isCloseTo(1.1, offset(0.05));
    }

    private static List<Point> compounding(int months, double annualPct) {
        List<Point> out = new ArrayList<>();
        double nav = 100;
        double step = Math.pow(1 + annualPct / 100, 1.0 / 12);
        for (int i = 0; i < months; i++) {
            out.add(new Point(YearMonth.of(2020, 1).plusMonths(i), BigDecimal.valueOf(nav)));
            nav *= step;
        }
        return out;
    }
}
