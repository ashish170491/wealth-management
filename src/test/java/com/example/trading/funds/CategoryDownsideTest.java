package com.example.trading.funds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the measured half of the horizon answer (SPEC §52.16).
 *
 * <p>The properties that matter are all refusals or directions, not arithmetic. A downside figure
 * read the wrong way round is the most dangerous thing this module could publish: it is a
 * <b>floor</b> on how bad it got, because a fund that failed outright left the record.
 */
class CategoryDownsideTest {

    /** A steady series compounding at {@code monthlyPct} from {@code start} for {@code months}. */
    private static List<CategoryDownside.Point> steady(YearMonth start, int months, double monthlyPct) {
        List<CategoryDownside.Point> out = new ArrayList<>();
        double nav = 100.0;
        for (int i = 0; i <= months; i++) {
            out.add(new CategoryDownside.Point(start.plusMonths(i), BigDecimal.valueOf(nav)));
            nav *= (1 + monthlyPct / 100.0);
        }
        return out;
    }

    /** The same, with one month where the value drops by {@code shockPct}. */
    private static List<CategoryDownside.Point> withShock(YearMonth start, int months,
                                                          double monthlyPct, int shockAt,
                                                          double shockPct) {
        List<CategoryDownside.Point> out = new ArrayList<>();
        double nav = 100.0;
        for (int i = 0; i <= months; i++) {
            out.add(new CategoryDownside.Point(start.plusMonths(i), BigDecimal.valueOf(nav)));
            nav *= (1 + (i == shockAt ? shockPct : monthlyPct) / 100.0);
        }
        return out;
    }

    private static Map<Long, List<CategoryDownside.Point>> category(
            List<CategoryDownside.Point>... funds) {
        Map<Long, List<CategoryDownside.Point>> m = new LinkedHashMap<>();
        for (int i = 0; i < funds.length; i++) {
            m.put(1000L + i, funds[i]);
        }
        return m;
    }

    @Test
    @DisplayName("a category that never fell reports zero negative windows, not a missing reading")
    void aCalmCategoryIsMeasuredRatherThanSilent() {
        YearMonth start = YearMonth.of(2018, 4);
        var series = category(steady(start, 60, 0.5), steady(start, 60, 0.55),
                steady(start, 60, 0.45), steady(start, 60, 0.6), steady(start, 60, 0.5),
                steady(start, 60, 0.52));

        var r = CategoryDownside.measure(SebiCategory.DEBT_LIQUID, 3, series);

        assertThat(r.measured()).isTrue();
        assertThat(r.windowsNegative()).isZero();
        assertThat(r.shareNegativePct()).isEqualTo(0.0);
        // "Never fell" is a finding. Reporting it as "not measured" would be Gotcha 44 - an absence
        // of findings presented as an absence of checking.
        assertThat(r.worstPct()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("one fund's collapse does not become the category's worst stretch")
    void oneFundCannotDecideTheCategory() {
        // The median is asked window by window (Gotcha 143). A single blown-up fund moves the
        // median by one place, not by its own loss - which is right, because a category reading
        // driven by one scheme is a fact about that scheme.
        YearMonth start = YearMonth.of(2018, 4);
        var series = category(
                steady(start, 60, 0.5), steady(start, 60, 0.5), steady(start, 60, 0.5),
                steady(start, 60, 0.5), steady(start, 60, 0.5),
                withShock(start, 60, 0.5, 20, -45.0));

        var r = CategoryDownside.measure(SebiCategory.DEBT_CREDIT_RISK, 3, series);

        assertThat(r.measured()).isTrue();
        assertThat(r.worstPct()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("a shock most of the category shares does show up, at the month it happened")
    void abroadShockIsFoundAndDated() {
        YearMonth start = YearMonth.of(2018, 4);
        var series = category(
                withShock(start, 60, 0.5, 24, -9.0), withShock(start, 60, 0.5, 24, -10.0),
                withShock(start, 60, 0.5, 24, -11.0), withShock(start, 60, 0.5, 24, -8.0),
                withShock(start, 60, 0.5, 24, -12.0), withShock(start, 60, 0.5, 24, -10.5));

        var r = CategoryDownside.measure(SebiCategory.DEBT_CREDIT_RISK, 3, series);

        assertThat(r.measured()).isTrue();
        assertThat(r.worstPct()).isLessThan(-5.0);
        assertThat(r.windowsNegative()).isGreaterThan(0);
        // The window containing the shock month is the worst one, and its dates are published so a
        // reader can go and look at what happened rather than take the number on trust.
        assertThat(r.worstFrom()).isNotNull();
        assertThat(r.worstTo()).isEqualTo(r.worstFrom().plusMonths(3));
        assertThat(r.worstFrom()).isBetween(start.plusMonths(22), start.plusMonths(24));
    }

    @Test
    @DisplayName("the return is over the whole window and is never annualised")
    void notAnnualised() {
        // Annualising a three-month fall multiplies it by four and states a loss nobody had. Same
        // family as B-047 (a quarter filed as a year) and B-113 (two years against ten).
        YearMonth start = YearMonth.of(2018, 4);
        List<CategoryDownside.Point>[] funds = new List[6];
        for (int i = 0; i < 6; i++) {
            funds[i] = steady(start, 60, 1.0);
        }
        var r = CategoryDownside.measure(SebiCategory.FLEXI_CAP, 3, category(funds));

        // Three months at 1%/month is about 3.03%, not about 12.7%.
        assertThat(r.medianPct()).isCloseTo(3.03, within(0.2));
    }

    @Test
    @DisplayName("a thin category is NOT_MEASURED with the reason, never a clean bill of health")
    void thinCategoryRefuses() {
        // Contra has four schemes on the live file. A category whose median is two funds has not
        // been measured, and reporting "worst: 0%" for it would be the most flattering possible
        // failure (Gotcha 44, 68).
        YearMonth start = YearMonth.of(2018, 4);
        var series = category(steady(start, 60, 0.5), steady(start, 60, 0.5));

        var r = CategoryDownside.measure(SebiCategory.CONTRA, 3, series);

        assertThat(r.measured()).isFalse();
        assertThat(r.worstPct()).isNull();
        assertThat(r.reason()).contains("gap in this app");
    }

    @Test
    @DisplayName("too few windows is refused even when there are plenty of funds")
    void shortArchiveRefuses() {
        // Six funds and eighteen months of history gives only a handful of three-year windows. The
        // window count is not the sample size either way (Gotcha 92) - but below MIN_WINDOWS the
        // worst reading is simply whichever month happened to be in the sample.
        YearMonth start = YearMonth.of(2025, 1);
        List<CategoryDownside.Point>[] funds = new List[6];
        for (int i = 0; i < 6; i++) {
            funds[i] = steady(start, 18, 0.5);
        }
        var r = CategoryDownside.measure(SebiCategory.MID_CAP, 36, category(funds));

        assertThat(r.measured()).isFalse();
        assertThat(r.reason()).contains("36 months");
    }

    @Test
    @DisplayName("the window count is never presented as the sample size")
    void independentWindowsIsTheSampleSize() {
        YearMonth start = YearMonth.of(2018, 4);
        List<CategoryDownside.Point>[] funds = new List[6];
        for (int i = 0; i < 6; i++) {
            funds[i] = steady(start, 96, 0.5);
        }
        var r = CategoryDownside.measure(SebiCategory.FLEXI_CAP, 12, category(funds));

        // 96 months stepped monthly gives ~85 twelve-month windows and exactly 8 independent ones.
        assertThat(r.windowsMeasured()).isGreaterThan(50);
        assertThat(r.independentWindows()).isEqualTo(8);
        assertThat(r.independentWindows()).isLessThan(r.windowsMeasured());
    }

    @Test
    @DisplayName("the caveat says the worst figure is a floor, and counts what left the record")
    void survivorshipIsCountedAndStatedInTheRightDirection() {
        YearMonth start = YearMonth.of(2018, 4);
        // Five survivors plus one that stopped publishing halfway - the shape of a fund that failed
        // and was merged away, which is exactly the population a downside reading must not omit
        // silently.
        var series = category(
                steady(start, 96, 0.5), steady(start, 96, 0.5), steady(start, 96, 0.5),
                steady(start, 96, 0.5), steady(start, 96, 0.5), steady(start, 40, 0.1));

        var r = CategoryDownside.measure(SebiCategory.DEBT_CREDIT_RISK, 3, series);

        assertThat(r.measured()).isTrue();
        assertThat(r.schemesVanished()).isEqualTo(1);
        assertThat(r.caveat())
                .contains("floor on how bad it got")
                .contains("stopped publishing");
        // The direction matters more than the wording: a reader who takes this as an upper bound on
        // the risk has been told the opposite of the truth.
        assertThat(r.caveat()).doesNotContain("worst case");
    }

    @Test
    @DisplayName("an all-debt blend reads the debt series, not a constant zero")
    void allDebtBlendIsMeasuredNotFlooredToZero() {
        // The defect the first live run produced. The blend used to be assembled from the two legs'
        // separate worst months with the debt leg floored at zero to stop a good bond year
        // flattering a bad equity one - so a 0/100 goal came out at exactly 0.0% at every horizon.
        // A constant on screen is the shape of a measurement nobody took (B-060).
        YearMonth start = YearMonth.of(2018, 4);
        var equity = category(steady(start, 96, 1.0), steady(start, 96, 1.0),
                steady(start, 96, 1.0), steady(start, 96, 1.0), steady(start, 96, 1.0),
                steady(start, 96, 1.0));
        var debt = category(withShock(start, 96, 0.5, 30, -4.0), withShock(start, 96, 0.5, 30, -4.2),
                withShock(start, 96, 0.5, 30, -3.8), withShock(start, 96, 0.5, 30, -4.1),
                withShock(start, 96, 0.5, 30, -3.9), withShock(start, 96, 0.5, 30, -4.0));

        var r = CategoryDownside.measureBlend("all debt", 6, 0.0, equity, debt);

        assertThat(r.measured()).isTrue();
        assertThat(r.worstPct()).isNotNull();
        assertThat(r.worstPct()).isNotEqualTo(0.0);
        // And it is the DEBT series it read, not the equity one, which never fell.
        assertThat(r.worstPct()).isLessThan(0.0);
    }

    @Test
    @DisplayName("each window is priced on both legs over the SAME months")
    void bothLegsArePricedOverTheSameWindow() {
        // The reason the blend is walked rather than assembled: the worst equity window and the
        // worst debt window happen in different months, so mixing those two numbers describes a
        // portfolio nobody held. Here equity falls early and debt falls late, and they never
        // overlap - so a 50/50 blend must be milder than either leg's own worst.
        YearMonth start = YearMonth.of(2018, 4);
        var equity = category(withShock(start, 96, 0.8, 10, -30.0), withShock(start, 96, 0.8, 10, -30.0),
                withShock(start, 96, 0.8, 10, -30.0), withShock(start, 96, 0.8, 10, -30.0),
                withShock(start, 96, 0.8, 10, -30.0), withShock(start, 96, 0.8, 10, -30.0));
        var debt = category(withShock(start, 96, 0.4, 70, -20.0), withShock(start, 96, 0.4, 70, -20.0),
                withShock(start, 96, 0.4, 70, -20.0), withShock(start, 96, 0.4, 70, -20.0),
                withShock(start, 96, 0.4, 70, -20.0), withShock(start, 96, 0.4, 70, -20.0));

        var blend = CategoryDownside.measureBlend("half and half", 6, 50.0, equity, debt);
        var equityAlone = CategoryDownside.measure(SebiCategory.FLEXI_CAP, 6, equity);
        var debtAlone = CategoryDownside.measure(SebiCategory.DEBT_LIQUID, 6, debt);

        assertThat(blend.measured()).isTrue();
        // Stitching the two worst months together would give roughly -25%. The real path never
        // saw both shocks in one window, so the true worst is about half of one of them.
        assertThat(blend.worstPct()).isGreaterThan(
                0.5 * equityAlone.worstPct() + 0.5 * debtAlone.worstPct());
    }

    @Test
    @DisplayName("a blend whose worst window is still a gain is reported as such, not hidden")
    void aPositiveWorstIsStillAReading() {
        // On the live archive - eight years holding one crash that recovered inside a year - every
        // window longer than about two years finishes ahead, so the worst is a GAIN. That is a fact
        // about the length of the record and it must be published rather than suppressed; the
        // caller is what says so in words.
        YearMonth start = YearMonth.of(2018, 4);
        var equity = category(steady(start, 96, 1.0), steady(start, 96, 1.0), steady(start, 96, 1.0),
                steady(start, 96, 1.0), steady(start, 96, 1.0), steady(start, 96, 1.0));
        var debt = category(steady(start, 96, 0.5), steady(start, 96, 0.5), steady(start, 96, 0.5),
                steady(start, 96, 0.5), steady(start, 96, 0.5), steady(start, 96, 0.5));

        var r = CategoryDownside.measureBlend("rising", 36, 50.0, equity, debt);

        assertThat(r.measured()).isTrue();
        assertThat(r.worstPct()).isGreaterThan(0.0);
        assertThat(r.windowsNegative()).isZero();
    }

    @Test
    @DisplayName("a blend is refused when a leg that carries weight has nothing on file")
    void aWeightedLegMustAnswer() {
        YearMonth start = YearMonth.of(2018, 4);
        var debt = category(steady(start, 96, 0.5), steady(start, 96, 0.5), steady(start, 96, 0.5),
                steady(start, 96, 0.5), steady(start, 96, 0.5), steady(start, 96, 0.5));

        // 50% equity with no equity series: refused, with the reason.
        var refused = CategoryDownside.measureBlend("half", 12, 50.0, Map.of(), debt);
        assertThat(refused.measured()).isFalse();
        assertThat(refused.reason()).contains("one side of this split");

        // 0% equity with no equity series: perfectly answerable, because that leg carries no
        // weight. Refusing here would deny a short-horizon goal its evidence for no reason.
        var answered = CategoryDownside.measureBlend("all debt", 12, 0.0, Map.of(), debt);
        assertThat(answered.measured()).isTrue();
    }

    @Test
    @DisplayName("a null, empty or zero-length input is refused rather than thrown")
    void nullSafe() {
        assertThat(CategoryDownside.measure(SebiCategory.DEBT_LIQUID, 3, null).measured()).isFalse();
        assertThat(CategoryDownside.measure(SebiCategory.DEBT_LIQUID, 3, Map.of()).measured()).isFalse();
        assertThat(CategoryDownside.measure(null, 3, Map.of()).categoryLabel()).isNotNull();
        assertThat(CategoryDownside.measure(SebiCategory.DEBT_LIQUID, 0,
                category(steady(YearMonth.of(2018, 4), 60, 0.5))).measured()).isFalse();
    }
}
