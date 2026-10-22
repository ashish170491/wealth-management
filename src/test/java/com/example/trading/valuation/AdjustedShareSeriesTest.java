package com.example.trading.valuation;

import com.example.trading.fundamentals.AdjustedShareSeries;
import com.example.trading.fundamentals.AnnualFundamentalsEntity;
import com.example.trading.fundamentals.CapitalAllocationRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * SPEC §57.3 rule VG-S1 over the measured cases. The ratios and the refusals are the ones round 1 and
 * round 2 of the design review found in the live table, restated as series.
 */
class AdjustedShareSeriesTest {

    private static final AdjustedShareSeries.Tolerances TOL = new AdjustedShareSeries.Tolerances(
            ValuationGapRules.MIN_BONUS_RATIO, ValuationGapRules.RATIO_GRID_TOLERANCE,
            ValuationGapRules.AMBIGUOUS_CEILING, ValuationGapRules.NON_ACTION_BAND);

    /** Shares by year from FY2020, face value constant at 10 unless a case says otherwise. */
    private static List<AnnualFundamentalsEntity> shares(double... counts) {
        List<AnnualFundamentalsEntity> out = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            out.add(ValuationFixtures.year("T", 2020 + i, 100.0, counts[i], 10.0, true));
        }
        return out;
    }

    private static AdjustedShareSeries.Result run(double... counts) {
        return AdjustedShareSeries.analyse(shares(counts), TOL);
    }

    @Test
    @DisplayName("BEL-style 3.000x bonus with no face-value data is divided out of every earlier year")
    void exactBonusIsDividedOut() {
        var r = run(100, 100, 300, 300, 300);
        assertThat(r.usable()).isTrue();
        assertThat(r.actions()).hasSize(1);
        assertThat(r.actions().get(0).ratio()).isEqualTo(3.0);
        assertThat(r.points().get(0).factor()).isCloseTo(3.0, within(1e-9));
        assertThat(r.points().get(2).factor()).isCloseTo(1.0, within(1e-9));
        assertThat(r.points().get(0).adjustedShares()).isCloseTo(300.0, within(1e-9));
    }

    @Test
    @DisplayName("an ESOP allotment in the same year as a 2:1 split still lands on the grid at 1% (the old 0.05% refused it)")
    void esopInTheBonusYearSurvivesTheWiderTolerance() {
        var r = run(100, 100, 100, 100 * 2.004, 100 * 2.004);     // WIPRO-style 2.004x
        assertThat(r.usable()).isTrue();
        assertThat(r.actions().get(0).ratio()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a genuine 1-for-2 bonus (IOC, MANINFRA, MOTHERSON read 1.500x) is divided out even when it lands a hair under 1.5")
    void oneForTwoBonusAtTheBoundary() {
        for (double step : new double[]{1.5, 1.4999, 1.49996, 1.4925, 1.5075}) {
            var r = run(100, 100, 100, 100 * step, 100 * step);
            assertThat(r.usable()).as("step %s", step).isTrue();
            assertThat(r.actions().get(0).ratio()).isEqualTo(1.5);
        }
        // 1.4 and 1.47 are not 1.5 within 1%: refused, as designed (an ESOP-sized gap off a real bonus).
        assertThat(run(100, 100, 100, 140, 140).problemCode()).isEqualTo(AdjustedShareSeries.UNEXPLAINED_SHARE_STEP);
        assertThat(run(100, 100, 100, 147, 147).problemCode()).isEqualTo(AdjustedShareSeries.UNEXPLAINED_SHARE_STEP);
    }

    @Test
    @DisplayName("the mirror case: a 2-for-3 consolidation lands on the inverse grid at the boundary too")
    void twoForThreeConsolidation() {
        var r = run(150, 150, 100.01, 100.01, 100.01);
        assertThat(r.usable()).isTrue();
        assertThat(r.actions().get(0).ratio()).isCloseTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    @DisplayName("PIDILITIND 2.001, METROPOLIS 4.002 and SHAKTIPUMP 6.000 are accepted")
    void observedBonusRatiosAreAccepted() {
        assertThat(run(100, 100, 100, 200.1, 200.1).usable()).isTrue();
        assertThat(run(100, 100, 100, 400.2, 400.2).usable()).isTrue();
        assertThat(run(100, 100, 100, 600.0, 600.0).usable()).isTrue();
    }

    @Test
    @DisplayName("a face-value change is a split, and the face-value ratio is the action")
    void faceValueSplit() {
        List<AnnualFundamentalsEntity> rows = shares(100, 100, 200, 200, 200);
        rows.get(2).setFaceValue(5.0);
        rows.get(3).setFaceValue(5.0);
        rows.get(4).setFaceValue(5.0);
        var r = AdjustedShareSeries.analyse(rows, TOL);
        assertThat(r.usable()).isTrue();
        assertThat(r.actions()).hasSize(1);
        assertThat(r.actions().get(0).via()).isEqualTo(AdjustedShareSeries.VIA_FACE_VALUE);
        assertThat(r.points().get(0).factor()).isCloseTo(2.0, within(1e-9));
    }

    @Test
    @DisplayName("a face-value change the share count disagrees with is refused, not trusted")
    void faceValueChangeWithoutTheShares() {
        List<AnnualFundamentalsEntity> rows = shares(100, 100, 100, 100, 100);
        rows.get(2).setFaceValue(5.0);
        rows.get(3).setFaceValue(5.0);
        rows.get(4).setFaceValue(5.0);
        var r = AdjustedShareSeries.analyse(rows, TOL);
        assertThat(r.problemCode()).isEqualTo(AdjustedShareSeries.UNEXPLAINED_SHARE_STEP);
    }

    @Test
    @DisplayName("APARINDS 1.0497x and ESCORTS 1.0999x with no face-value change are ambiguous, so refused")
    void smallGridLikeStepsAreAmbiguous() {
        assertThat(run(100, 100, 100, 104.97, 104.97).problemCode())
                .isEqualTo(AdjustedShareSeries.AMBIGUOUS_SHARE_STEP);
        assertThat(run(100, 100, 100, 109.99, 109.99).problemCode())
                .isEqualTo(AdjustedShareSeries.AMBIGUOUS_SHARE_STEP);
    }

    @Test
    @DisplayName("a 5-for-4 rights issue is refused rather than divided out as a free bonus")
    void rightsIssueIsNotABonus() {
        assertThat(run(100, 100, 100, 125, 125).problemCode()).isEqualTo(AdjustedShareSeries.AMBIGUOUS_SHARE_STEP);
    }

    @Test
    @DisplayName("an ordinary issue or buyback inside +/-25% and off the grid stays in the series")
    void realIssuesStayIn() {
        var r = run(100, 103, 108, 112, 115);
        assertThat(r.usable()).isTrue();
        assertThat(r.actions()).isEmpty();
        assertThat(r.points().get(0).factor()).isEqualTo(1.0);
        var buyback = run(100, 97, 94, 91, 88);
        assertThat(buyback.usable()).isTrue();
        assertThat(buyback.actions()).isEmpty();
    }

    @Test
    @DisplayName("a step beyond +/-25% that no rule explains is refused")
    void unexplainedBigStep() {
        assertThat(run(100, 100, 100, 137, 137).problemCode()).isEqualTo(AdjustedShareSeries.UNEXPLAINED_SHARE_STEP);
        assertThat(run(100, 100, 100, 123, 123).usable()).isTrue();     // inside the band
    }

    @Test
    @DisplayName("a 1-for-2 consolidation on the inverse grid is divided out")
    void reverseSplit() {
        var r = run(200, 200, 100, 100, 100);
        assertThat(r.usable()).isTrue();
        assertThat(r.actions().get(0).ratio()).isCloseTo(0.5, within(1e-9));
        assertThat(r.points().get(0).adjustedShares()).isCloseTo(100.0, within(1e-9));
    }

    @Test
    @DisplayName("SUNPHARMA-style 239.9 -> 0.000 -> 239.9 is a unit slip, refused as inconsistent")
    void unitSlip() {
        assertThat(run(239.9, 239.9, 0.0001, 239.9, 239.9).problemCode())
                .isEqualTo(AdjustedShareSeries.SHARE_COUNT_INCONSISTENT);
    }

    @Test
    @DisplayName("a one-year spike that reverses is a unit slip even when no value is tiny")
    void reversingSpike() {
        assertThat(run(100, 100, 1000, 100, 100).problemCode())
                .isEqualTo(AdjustedShareSeries.SHARE_COUNT_INCONSISTENT);
    }

    @Test
    @DisplayName("a face value that goes back to an earlier value is a tagging error")
    void faceValueReturns() {
        List<AnnualFundamentalsEntity> rows = shares(100, 200, 200, 100, 100);
        double[] fv = {10, 5, 5, 10, 10};
        for (int i = 0; i < 5; i++) rows.get(i).setFaceValue(fv[i]);
        assertThat(AdjustedShareSeries.analyse(rows, TOL).problemCode())
                .isEqualTo(AdjustedShareSeries.SHARE_COUNT_INCONSISTENT);
    }

    @Test
    @DisplayName("a share count of zero or a null is inconsistent, never divided into")
    void zeroShares() {
        assertThat(run(100, 100, 0.005, 100, 100).problemCode())
                .isEqualTo(AdjustedShareSeries.SHARE_COUNT_INCONSISTENT);
    }

    @Test
    @DisplayName("two bonuses in the series multiply: the earliest year carries both factors")
    void twoActionsCompound() {
        var r = run(100, 200, 200, 600, 600);
        assertThat(r.usable()).isTrue();
        assertThat(r.actions()).hasSize(2);
        assertThat(r.points().get(0).factor()).isCloseTo(6.0, within(1e-9));
        assertThat(r.points().get(1).factor()).isCloseTo(3.0, within(1e-9));
        assertThat(r.points().get(3).factor()).isCloseTo(1.0, within(1e-9));
    }

    // ---------------------------------------------------------------------------------- parity

    /**
     * SPEC §57.8 parity: on series whose corporate actions are exact (face-value splits and ratios
     * of 1.5 or more on the strict grid) this class and {@code CapitalAllocationRecord} must detect
     * the same number of actions. The tolerances differ on purpose elsewhere (1% against 0.05%);
     * agreement where the evidence is unambiguous is what guards against the two copies drifting.
     */
    @Test
    @DisplayName("parity: the action count equals CapitalAllocationRecord's on exact bonuses and splits")
    void parityWithCapitalAllocationRecord() {
        List<List<AnnualFundamentalsEntity>> cases = new ArrayList<>();
        cases.add(shares(100, 100, 300, 300, 300, 300));                // BEL 3.000x
        cases.add(shares(100, 100, 200, 200, 200, 200));                // RELIANCE 2.000x
        List<AnnualFundamentalsEntity> hdfc = shares(100, 100, 200, 200, 400, 400);
        double[] fv = {2, 2, 1, 1, 1, 1};                               // face-value split, then a 1:1 bonus
        for (int i = 0; i < 6; i++) hdfc.get(i).setFaceValue(fv[i]);
        cases.add(hdfc);
        cases.add(shares(100, 100, 100, 100, 100, 100));                // nothing at all
        cases.add(shares(100, 103, 106, 109, 112, 115));                // ordinary issues
        for (List<AnnualFundamentalsEntity> c : cases) {
            int mine = AdjustedShareSeries.analyse(c, TOL).actions().size();
            int theirs = CapitalAllocationRecord.analyse(c).corporateActionsDetected();
            assertThat(mine).as("series starting %s", c.get(0).getShareCount()).isEqualTo(theirs);
        }
    }
}
