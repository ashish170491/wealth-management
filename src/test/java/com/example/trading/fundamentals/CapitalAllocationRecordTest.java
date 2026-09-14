package com.example.trading.fundamentals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the capital-allocation record (SPEC §42).
 *
 * <p>The defect this feature is most likely to reintroduce is B-066: reading a bonus issue as
 * dilution. A bonus multiplies the share count by an exact simple ratio and takes nothing from
 * the owner; a placement raises money and does. Two tests below hold that line — one that a bonus
 * is divided out, and one that a raise landing <em>near</em> a simple ratio is still a raise,
 * which is why the tolerance is 0.05% and not 0.5% (Gotcha 86).
 *
 * <p>The rest is null discipline: an unmeasurable component is never a weak grade, and one that
 * does not apply leaves the denominator (Gotcha 68).
 */
class CapitalAllocationRecordTest {

    private static AnnualFundamentalsEntity year(int fy, double shares, Double faceValue) {
        return AnnualFundamentalsEntity.builder()
                .symbol("NSE:TEST").fiscalYear(fy)
                .sales(1000.0).netProfit(150.0).operatingProfit(200.0)
                .profitBeforeTax(190.0).interestCost(10.0).depreciation(30.0)
                .equity(800.0).borrowings(100.0).netBlock(500.0)
                .capitalWorkInProgress(20.0)
                .dividendsPaid(40.0)
                .shareCount(shares).faceValue(faceValue)
                .build();
    }

    private static List<AnnualFundamentalsEntity> flatSeries(double shares) {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        for (int fy = 2018; fy <= 2025; fy++) h.add(year(fy, shares, 10.0));
        return h;
    }

    @Test
    @DisplayName("Fewer than five years says nothing at all")
    void shortHistoryIsNotMeasured() {
        List<AnnualFundamentalsEntity> h = flatSeries(100).subList(0, 4);
        CapitalAllocationRecord.Result r = CapitalAllocationRecord.analyse(h);
        assertThat(r.verdict()).isEqualTo("NOT_MEASURED");
        assertThat(r.components()).isEmpty();
        assertThat(r.reason()).contains("not a record");
    }

    @Test
    @DisplayName("A 1:2 bonus is divided out, not reported as dilution")
    void bonusIsNotDilution() {
        // BEL's case (B-066): share count multiplies by exactly 3.0 on a bonus. Before the
        // discriminator existed this fired DILUTION:HIGH and, after B-065, forced AVOID on
        // every screen.
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        for (int fy = 2018; fy <= 2021; fy++) h.add(year(fy, 100.0, 10.0));
        for (int fy = 2022; fy <= 2025; fy++) h.add(year(fy, 300.0, 10.0));

        CapitalAllocationRecord.Result r = CapitalAllocationRecord.analyse(h);
        CapitalAllocationRecord.Component c = r.component("shareCount");
        assertThat(r.corporateActionsDetected()).isEqualTo(1);
        assertThat(c.figure()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(c.status()).isEqualTo(CapitalAllocationRecord.Status.STRONG);
        assertThat(c.detail()).contains("bonus issue or split was divided out");
    }

    @Test
    @DisplayName("A face-value change settles a split outright, with no ratio guessing")
    void faceValueChangeIsASplit() {
        // Face value 10 -> 2 is a 1:5 split: the count multiplies by 5 and nothing was taken.
        // With the field present this needs no inference at all, which is the point of storing it.
        Double ratio = CapitalAllocationRecord.corporateActionRatio(5.0, 10.0, 2.0);
        assertThat(ratio).isNotNull();
        assertThat(ratio).isCloseTo(5.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("A money raise landing near a simple ratio is still a raise")
    void nearMissRatioIsNotABonus() {
        // BANKINDIA's infusion plus QIP sat 0.18% from 5/4 and 0.15% from 10/9 (Gotcha 86).
        // At a 0.5% tolerance the grid of simple fractions swallows real dilution.
        double nearFiveQuarters = 1.25 * 1.0018;
        assertThat(CapitalAllocationRecord.corporateActionRatio(nearFiveQuarters, null, null)).isNull();
        // An exact one is still recognised.
        assertThat(CapitalAllocationRecord.corporateActionRatio(1.25, null, null)).isNotNull();
    }

    @Test
    @DisplayName("A steady share count with real dividends reads as disciplined")
    void steadyRecordIsDisciplined() {
        CapitalAllocationRecord.Result r = CapitalAllocationRecord.analyse(flatSeries(100));
        assertThat(r.component("shareCount").status()).isEqualTo(CapitalAllocationRecord.Status.STRONG);
        assertThat(r.component("payout").measured()).isTrue();
        assertThat(r.measured()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Untagged dividends are unmeasured, never a zero payout")
    void missingDividendsIsNotZeroPayout() {
        List<AnnualFundamentalsEntity> h = flatSeries(100);
        h.forEach(y -> y.setDividendsPaid(null));
        CapitalAllocationRecord.Component c = CapitalAllocationRecord.analyse(h).component("payout");
        assertThat(c.status()).isEqualTo(CapitalAllocationRecord.Status.NOT_MEASURED);
        assertThat(c.figure()).isNull();
        assertThat(c.measured()).isFalse();
        assertThat(c.detail()).contains("says nothing about whether the company pays one");
    }

    @Test
    @DisplayName("A half-reported CWIP refuses to measure rather than assuming zero (B-048)")
    void unknownConstructionDeltaIsNotZero() {
        List<AnnualFundamentalsEntity> h = flatSeries(100);
        h.get(0).setCapitalWorkInProgress(null);      // present at one end only
        CapitalAllocationRecord.Component c =
                CapitalAllocationRecord.analyse(h).component("reinvestment");
        assertThat(c.status()).isEqualTo(CapitalAllocationRecord.Status.NOT_MEASURED);
        assertThat(c.figure()).isNull();
    }

    @Test
    @DisplayName("Neither year reporting construction is a genuine zero, and stays measurable")
    void absentConstructionEverywhereIsAGenuineZero() {
        List<AnnualFundamentalsEntity> h = flatSeries(100);
        h.forEach(y -> y.setCapitalWorkInProgress(null));
        CapitalAllocationRecord.Component c =
                CapitalAllocationRecord.analyse(h).component("reinvestment");
        assertThat(c.measured()).isTrue();
    }

    @Test
    @DisplayName("A business whose capital did not grow leaves the incremental-return denominator")
    void flatCapitalIsNotApplicable() {
        // Asset-light compounders — CAMS, CDSL, Oracle Financial — barely grow capital employed.
        // Judging them on the return earned by new capital would fail them for not needing any.
        CapitalAllocationRecord.Component c =
                CapitalAllocationRecord.analyse(flatSeries(100)).component("incrementalReturn");
        assertThat(c.status()).isEqualTo(CapitalAllocationRecord.Status.NOT_APPLICABLE);
        assertThat(c.measured()).isFalse();
        assertThat(c.detail()).contains("not a shortcoming");
    }

    @Test
    @DisplayName("Fewer than three readings is an unread record, not a poor one")
    void tooFewComponentsIsNotMeasured() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        for (int fy = 2018; fy <= 2025; fy++) {
            h.add(AnnualFundamentalsEntity.builder()
                    .symbol("NSE:SPARSE").fiscalYear(fy).sales(1000.0).build());
        }
        CapitalAllocationRecord.Result r = CapitalAllocationRecord.analyse(h);
        assertThat(r.verdict()).isEqualTo("NOT_MEASURED");
        assertThat(r.verdict()).isNotEqualTo("POOR");
    }

    @Test
    @DisplayName("A genuine share issue is reported as dilution")
    void realIssueIsDilution() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        double shares = 100;
        for (int fy = 2018; fy <= 2025; fy++) {
            h.add(year(fy, shares, 10.0));
            shares *= 1.06;     // 6% a year, on no simple ratio: money being raised.
        }
        CapitalAllocationRecord.Component c = CapitalAllocationRecord.analyse(h).component("shareCount");
        assertThat(c.status()).isEqualTo(CapitalAllocationRecord.Status.WEAK);
        assertThat(c.figure()).isGreaterThan(5.0);
    }
}
