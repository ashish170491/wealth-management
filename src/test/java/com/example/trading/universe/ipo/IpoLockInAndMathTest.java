package com.example.trading.universe.ipo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.example.trading.universe.ipo.IpoLockIn.Stage.BASE_FORMING;
import static com.example.trading.universe.ipo.IpoLockIn.Stage.HYPE_WINDOW;
import static com.example.trading.universe.ipo.IpoLockIn.Stage.NOT_MEASURED;
import static com.example.trading.universe.ipo.IpoLockIn.Stage.RECOVERING;
import static com.example.trading.universe.ipo.IpoLockIn.Stage.WASHOUT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the lock-in calendar, the post-listing stage (SPEC §45.4) and the application arithmetic
 * (SPEC §45.5).
 *
 * <p>The property that matters most: inside the six-month hype window the stage is HYPE_WINDOW
 * regardless of how good the chart looks, because the sellers have not arrived yet — that is
 * SPEC §30.4's refusal, restated one level up.
 */
class IpoLockInAndMathTest {

    private static final LocalDate LISTED = LocalDate.of(2026, 3, 2);

    @Test
    @DisplayName("Four milestones in order, with passed/upcoming split by today")
    void calendar() {
        List<IpoLockIn.Milestone> cal = IpoLockIn.calendar(LISTED, LocalDate.of(2026, 5, 1));
        assertThat(cal).hasSize(4);
        assertThat(cal.get(0).approxDate()).isEqualTo(LISTED.plusDays(30));
        assertThat(cal.get(0).passed()).isTrue();
        assertThat(cal.get(1).approxDate()).isEqualTo(LISTED.plusDays(90));
        assertThat(cal.get(1).passed()).isFalse();
        assertThat(cal.get(1).daysAway()).isEqualTo(30);
        assertThat(cal.get(2).approxDate()).isEqualTo(LISTED.plusMonths(6));
        assertThat(cal.get(3).approxDate()).isEqualTo(LISTED.plusMonths(18));
        assertThat(IpoLockIn.calendar(null, LocalDate.now())).isEmpty();
    }

    @Test
    @DisplayName("Inside six months the stage is HYPE_WINDOW however good the chart looks")
    void hypeWindowIsNotJudged() {
        IpoLockIn.StageRead s = IpoLockIn.stage(LISTED, LISTED.plusMonths(5), 6, true, true);
        assertThat(s.stage()).isEqualTo(HYPE_WINDOW);
        assertThat(s.reason()).contains("deliberately");
    }

    @Test
    @DisplayName("Exactly six months on, the window has passed")
    void boundaryDay() {
        assertThat(IpoLockIn.pastHypeWindow(LISTED, LISTED.plusMonths(6), 6)).isTrue();
        assertThat(IpoLockIn.pastHypeWindow(LISTED, LISTED.plusMonths(6).minusDays(1), 6)).isFalse();
    }

    @Test
    @DisplayName("Past the window: below the listing high is WASHOUT, above without a base is RECOVERING, with a base is BASE_FORMING")
    void stagesPastWindow() {
        LocalDate today = LISTED.plusMonths(8);
        assertThat(IpoLockIn.stage(LISTED, today, 6, false, true).stage()).isEqualTo(WASHOUT);
        assertThat(IpoLockIn.stage(LISTED, today, 6, true, false).stage()).isEqualTo(RECOVERING);
        assertThat(IpoLockIn.stage(LISTED, today, 6, true, null).stage()).isEqualTo(RECOVERING);
        assertThat(IpoLockIn.stage(LISTED, today, 6, true, true).stage()).isEqualTo(BASE_FORMING);
    }

    @Test
    @DisplayName("No price history past the window is NOT_MEASURED, never WASHOUT")
    void noHistoryIsUnmeasured() {
        assertThat(IpoLockIn.stage(LISTED, LISTED.plusMonths(8), 6, null, null).stage()).isEqualTo(NOT_MEASURED);
        assertThat(IpoLockIn.stage(null, LocalDate.now(), 6, true, true).stage()).isEqualTo(NOT_MEASURED);
    }

    // ---- application maths ----

    @Test
    @DisplayName("LCC Projects: 102 shares at 146 - one lot 14,892; retail 13 lots; small NII starts at 14; big NII at 68")
    void sizingLcc() {
        IpoApplicationMath.Sizing s = IpoApplicationMath.size(102, 146.0);
        assertThat(s.measured()).isTrue();
        assertThat(s.lotCostRs()).isEqualTo(14892.0);
        assertThat(s.retailMaxLots()).isEqualTo(13);
        assertThat(s.retailMaxRs()).isEqualTo(13 * 14892.0);
        assertThat(s.retailMaxRs()).isLessThanOrEqualTo(IpoApplicationMath.RETAIL_CAP_RS);
        assertThat(s.smallNiiMinLots()).isEqualTo(14);
        assertThat(s.smallNiiMinRs()).isGreaterThan(IpoApplicationMath.RETAIL_CAP_RS);
        assertThat(s.bigNiiMinLots()).isEqualTo(68);
        assertThat(s.bigNiiMinRs()).isGreaterThan(IpoApplicationMath.SMALL_NII_CAP_RS);
        assertThat(s.employeeMaxLots()).isEqualTo(33);
        assertThat(s.employeeMaxRs()).isLessThanOrEqualTo(IpoApplicationMath.EMPLOYEE_CAP_RS);
    }

    @Test
    @DisplayName("Sizing is at the top of the band: a lot priced at the bottom would overshoot the retail ceiling")
    void sizedAtTopOfBand() {
        IpoApplicationMath.Sizing top = IpoApplicationMath.size(37, 404.0);
        IpoApplicationMath.Sizing bottom = IpoApplicationMath.size(37, 384.0);
        assertThat(top.retailMaxLots()).isEqualTo(13);
        assertThat(bottom.retailMaxLots()).isEqualTo(14);
        assertThat(14 * 37 * 404.0).isGreaterThan(IpoApplicationMath.RETAIL_CAP_RS); // the overshoot avoided
    }

    @Test
    @DisplayName("Missing lot size or band is unmeasured with a reason, never zero lots")
    void unmeasuredSizing() {
        IpoApplicationMath.Sizing s = IpoApplicationMath.size(null, 146.0);
        assertThat(s.measured()).isFalse();
        assertThat(s.retailMaxLots()).isNull();
        assertThat(s.note()).contains("not published");
        assertThat(IpoApplicationMath.size(102, 0.0).measured()).isFalse();
    }

    @Test
    @DisplayName("Retail odds are the inverse of the final multiple, and null until final")
    void retailOdds() {
        assertThat(IpoApplicationMath.retailOddsOneIn(30.4, true)).isEqualTo(30);
        assertThat(IpoApplicationMath.retailOddsOneIn(0.7, true)).isEqualTo(1);
        assertThat(IpoApplicationMath.retailOddsOneIn(30.4, false)).isNull();
        assertThat(IpoApplicationMath.retailOddsOneIn(null, true)).isNull();
    }
}
