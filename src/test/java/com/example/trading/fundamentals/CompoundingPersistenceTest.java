package com.example.trading.fundamentals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the multi-year compounding track record (SPEC §43).
 *
 * <p>The property this defends above all others is that <b>gates count years and never average
 * them</b>. A commodity business's return on capital oscillates across a cycle; an average clears
 * a bar that no individual bad year clears, so averaging lets one boom year carry a decade. That
 * is the exact failure a persistence test exists to catch, and it is the first thing a
 * well-meaning simplification would reintroduce.
 *
 * <p>The rest is the house null discipline: an unmeasured check is neither a pass nor a fail, a
 * not-applicable check leaves the denominator, and too little history yields NOT_MEASURED rather
 * than a poor grade (Gotcha 21, 44, 68).
 */
class CompoundingPersistenceTest {

    /** A year with everything the gates need, so a test can vary one figure at a time. */
    private static AnnualFundamentalsEntity year(int fy, double sales, double profit,
                                                 double equity, double borrowings, double shares) {
        return AnnualFundamentalsEntity.builder()
                .symbol("NSE:TEST").fiscalYear(fy)
                .sales(sales)
                .netProfit(profit)
                .operatingProfit(sales * 0.20)
                .profitBeforeTax(profit * 1.3)
                .interestCost(borrowings * 0.08)
                .depreciation(sales * 0.03)
                .equity(equity)
                .borrowings(borrowings)
                .netBlock(equity * 0.6)
                .shareCount(shares)
                .faceValue(10.0)
                .build();
    }

    /** Ten years of a steady compounder: growing, profitable, unlevered, high return on capital. */
    private static List<AnnualFundamentalsEntity> steadyCompounder() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        double sales = 1000, profit = 150, equity = 500;
        for (int fy = 2016; fy <= 2025; fy++) {
            h.add(year(fy, sales, profit, equity, 20, 100));
            sales *= 1.15;
            profit *= 1.15;
            equity *= 1.12;
        }
        return h;
    }

    @Test
    @DisplayName("A steady compounder passes every applicable check")
    void steadyCompounderIsProven() {
        CompoundingPersistence.Result r = CompoundingPersistence.analyse(steadyCompounder(), false);
        assertThat(r.verdict()).isEqualTo(CompoundingPersistence.Verdict.PROVEN_COMPOUNDER);
        assertThat(r.proven()).isTrue();
        assertThat(r.passed()).isEqualTo(r.applicable());
    }

    @Test
    @DisplayName("A cyclical whose AVERAGE return clears the bar still fails persistence")
    void averagingMustNotRescueACyclical() {
        // Return on capital alternates far above and far below the 18% bar. The mean is well
        // above it; the business held it in only three years of ten. If a future refactor
        // computes the mean instead of counting, this test is the thing that notices.
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        // Seven lean years at 4% and three boom years at 60%: mean 20.8%, comfortably over the
        // 18% bar, while the business cleared it in three years out of ten.
        double[] roceByYear = {4, 4, 60, 60, 4, 4, 60, 4, 4, 4};
        for (int i = 0; i < roceByYear.length; i++) {
            double equity = 1000, borrowings = 0;
            double ebit = roceByYear[i] / 100.0 * (equity + borrowings);
            h.add(AnnualFundamentalsEntity.builder()
                    .symbol("NSE:CYCLE").fiscalYear(2016 + i)
                    .sales(5000.0).netProfit(ebit * 0.7)
                    .operatingProfit(ebit)
                    .profitBeforeTax(ebit).interestCost(0.0)
                    .equity(equity).borrowings(borrowings)
                    .shareCount(100.0).faceValue(10.0)
                    .build());
        }
        double mean = 0;
        for (double v : roceByYear) mean += v;
        mean /= roceByYear.length;
        assertThat(mean).isGreaterThan(CompoundingPersistence.ROCE_BAR);  // the trap

        CompoundingPersistence.Result r = CompoundingPersistence.analyse(h, false);
        CompoundingPersistence.Gate gate = gate(r, "returnPersistence");
        assertThat(gate.status()).isEqualTo(CompoundingPersistence.GateStatus.FAIL);
        assertThat(gate.qualifyingYears()).isEqualTo(3);
        assertThat(gate.measuredYears()).isEqualTo(10);
    }

    @Test
    @DisplayName("Too little history is NOT_MEASURED, never a poor record")
    void shortHistorySaysNothing() {
        List<AnnualFundamentalsEntity> h = steadyCompounder().subList(0, 4);
        CompoundingPersistence.Result r = CompoundingPersistence.analyse(h, false);
        assertThat(r.verdict()).isEqualTo(CompoundingPersistence.Verdict.NOT_MEASURED);
        assertThat(r.verdict()).isNotEqualTo(CompoundingPersistence.Verdict.NO);
        assertThat(r.gates()).isEmpty();
        assertThat(r.reason()).contains("unread");
    }

    @Test
    @DisplayName("Empty and null history are NOT_MEASURED, not a crash and not a zero")
    void noHistorySaysNothing() {
        assertThat(CompoundingPersistence.analyse(null, false).verdict())
                .isEqualTo(CompoundingPersistence.Verdict.NOT_MEASURED);
        assertThat(CompoundingPersistence.analyse(List.of(), false).verdict())
                .isEqualTo(CompoundingPersistence.Verdict.NOT_MEASURED);
    }

    @Test
    @DisplayName("A missing balance sheet leaves return-on-capital unmeasured, never failed")
    void missingBalanceSheetIsNotAFailure() {
        // The pre-2022 archive case (SPEC §32.5): profit and loss resolves, the balance sheet
        // does not. The business must not be marked down for a gap in NSE's filings.
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        for (AnnualFundamentalsEntity y : steadyCompounder()) {
            y.setEquity(null);
            y.setBorrowings(null);
            h.add(y);
        }
        CompoundingPersistence.Result r = CompoundingPersistence.analyse(h, false);
        assertThat(gate(r, "returnPersistence").status())
                .isEqualTo(CompoundingPersistence.GateStatus.NOT_MEASURED);
        assertThat(gate(r, "leverage").status())
                .isEqualTo(CompoundingPersistence.GateStatus.NOT_MEASURED);
        // And neither one is counted in the denominator it could not answer.
        assertThat(r.applicable()).isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("A lender's leverage check is NOT_APPLICABLE and leaves the denominator")
    void lenderLeverageDoesNotCount() {
        List<AnnualFundamentalsEntity> h = steadyCompounder();
        CompoundingPersistence.Result asLender = CompoundingPersistence.analyse(h, true);
        CompoundingPersistence.Result asCompany = CompoundingPersistence.analyse(h, false);

        assertThat(gate(asLender, "leverage").status())
                .isEqualTo(CompoundingPersistence.GateStatus.NOT_APPLICABLE);
        // Not applicable removes it from both sides, so the lender is judged on one fewer check
        // rather than being penalised for a question that does not describe it (Gotcha 68).
        assertThat(asLender.applicable()).isEqualTo(asCompany.applicable() - 1);
        assertThat(asLender.verdict()).isEqualTo(CompoundingPersistence.Verdict.PROVEN_COMPOUNDER);
    }

    @Test
    @DisplayName("Sales outgrowing profit for a decade fails the compounding check")
    void marginBoughtGrowthFails() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        double sales = 1000, profit = 200;
        for (int fy = 2016; fy <= 2025; fy++) {
            h.add(year(fy, sales, profit, 500, 20, 100));
            sales *= 1.20;      // Revenue racing ahead...
            profit *= 1.03;     // ...while profit barely moves: growth bought with margin.
        }
        CompoundingPersistence.Result r = CompoundingPersistence.analyse(h, false);
        assertThat(gate(r, "compounding").status()).isEqualTo(CompoundingPersistence.GateStatus.FAIL);
        assertThat(r.verdict()).isNotEqualTo(CompoundingPersistence.Verdict.PROVEN_COMPOUNDER);
    }

    @Test
    @DisplayName("Persistence needs a supermajority of years, not a bare majority")
    void bareMajorityIsNotPersistence() {
        // Six of ten years above the bar is 60%, under the 70% required share. A cyclical
        // spends more than half a cycle in the good half; that is not a moat.
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            double ebit = (i < 6 ? 0.25 : 0.05) * 1000;
            h.add(AnnualFundamentalsEntity.builder()
                    .symbol("NSE:BARE").fiscalYear(2016 + i)
                    .sales(5000.0).netProfit(ebit * 0.7).operatingProfit(ebit)
                    .profitBeforeTax(ebit).interestCost(0.0)
                    .equity(1000.0).borrowings(0.0).shareCount(100.0).faceValue(10.0)
                    .build());
        }
        CompoundingPersistence.Gate g =
                gate(CompoundingPersistence.analyse(h, false), "returnPersistence");
        assertThat(g.qualifyingYears()).isEqualTo(6);
        assertThat(g.status()).isEqualTo(CompoundingPersistence.GateStatus.FAIL);
    }

    @Test
    @DisplayName("A balance sheet in only three years leaves Tier B unmeasured, not passed")
    void thinBalanceSheetDoesNotCarryAPersistenceClaim() {
        // Found on live data, not in review: BEL has 8 years of accounts but a balance sheet in
        // only 3 of them (SPEC §32.5's pre-2022 gap), so return on capital read 3/3 and helped
        // earn a PROVEN_COMPOUNDER badge. Three readings is thinner than the word "persistence"
        // claims — a gate that passes on thin evidence while sounding authoritative is Gotcha 68
        // in its most flattering form.
        List<AnnualFundamentalsEntity> h = steadyCompounder();
        for (int i = 0; i < h.size() - 3; i++) {
            h.get(i).setEquity(null);
            h.get(i).setBorrowings(null);
        }
        CompoundingPersistence.Result r = CompoundingPersistence.analyse(h, false);
        assertThat(gate(r, "returnPersistence").status())
                .isEqualTo(CompoundingPersistence.GateStatus.NOT_MEASURED);
        assertThat(gate(r, "leverage").status())
                .isEqualTo(CompoundingPersistence.GateStatus.NOT_MEASURED);

        // One more measured year and it becomes a real reading.
        h.get(h.size() - 4).setEquity(500.0);
        h.get(h.size() - 4).setBorrowings(20.0);
        assertThat(gate(CompoundingPersistence.analyse(h, false), "returnPersistence").status())
                .isEqualTo(CompoundingPersistence.GateStatus.PASS);
    }

    private static CompoundingPersistence.Gate gate(CompoundingPersistence.Result r, String key) {
        return r.gates().stream().filter(g -> g.key().equals(key)).findFirst().orElseThrow();
    }
}
