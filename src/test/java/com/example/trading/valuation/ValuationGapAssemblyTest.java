package com.example.trading.valuation;

import com.example.trading.fundamentals.AnnualFundamentalsEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.example.trading.valuation.ValuationFixtures.TODAY;
import static com.example.trading.valuation.ValuationFixtures.eligible;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * SPEC §57.3 end to end through the one assembly method: every gate refuses with its own code and
 * its failing number, a refusal carries no derived figure, and the three blocks stay separate.
 */
class ValuationGapAssemblyTest {

    private static ValuationReading assemble(ValuationGapService.Inputs in) {
        return ValuationGapService.assemble(in);
    }

    private static ValuationGapService.Inputs withScore(ValuationGapService.Inputs b, ValuationGapService.ScoreFacts s) {
        return new ValuationGapService.Inputs(b.requested(), b.annualSymbol(), b.asOf(), s, b.annual(),
                b.quarters(), b.naFinancial());
    }

    // ------------------------------------------------------------------------------------ the pass

    @Test
    @DisplayName("an eligible stock is MEASURED with all of D, E and F and a stored fit in the passing cohort")
    void eligibleIsMeasured() {
        var r = assemble(eligible("NSE:GOOD"));
        assertThat(r.status()).isEqualTo(ValuationReading.Status.MEASURED);
        assertThat(r.reasonCode()).isNull();
        assertThat(r.ruleset()).isEqualTo("VG-2");
        assertThat(r.fingerprint()).isEqualTo(ValuationGapRules.fingerprint());
        assertThat(r.earnings().series()).hasSize(10);
        assertThat(r.earnings().trend().reliable()).isTrue();
        assertThat(r.trendComparison().epsIfTrendContinues()).isNotNull().isPositive();
        assertThat(r.trendComparison().referenceYear()).isEqualTo(2031);
        assertThat(r.requiredGrowthGrid().rows()).hasSize(3);
        assertThat(r.section125Reading().impliedGrowthPct()).isEqualTo(14.0);
        assertThat(r.blocks().values()).containsOnly("AVAILABLE");
        assertThat(r.horizon().growthYears()).isEqualTo(5);
        assertThat(r.horizon().yearsFromValuationDate()).isCloseTo(4.4627, within(1e-4));
        assertThat(r.basis().growthIntervals()).isEqualTo(9);
        var c = r.capture();
        assertThat(c.cohort()).isEqualTo(ValuationReading.TrendCohort.GATES_PASSED);
        assertThat(Math.exp(c.trendLnSlope()) - 1).isCloseTo(0.15, within(0.01));
        assertThat(c.snapshotDate()).isEqualTo(TODAY);
        assertThat(c.series()).hasSize(10);
    }

    @Test
    @DisplayName("E0 equals the latest EPS for an ordinary record, and PE0 is price over E0")
    void normalisedEpsAndPe() {
        var r = assemble(eligible("NSE:GOOD"));
        double latest = r.earnings().series().get(9).epsAdjusted();
        assertThat(r.earnings().normalisedEps()).isCloseTo(latest, within(1e-9));
        assertThat(r.trendComparison().pe0()).isCloseTo(800.0 / latest, within(1e-9));
    }

    // ------------------------------------------------------------------------ reliability gates

    @Test
    @DisplayName("an unreliable trend withholds ONLY the FY2031 comparison; the fit, PE0 and the grid remain")
    void unreliableTrend() {
        var base = eligible("NSE:WOBBLY");
        double[] eps = {10, 25, 8, 30, 12, 28, 9, 26, 11, 27};
        List<AnnualFundamentalsEntity> annual = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) annual.add(ValuationFixtures.year("NSE:WOBBLY", 2017 + i, eps[i] * 10, 10.0, 10.0, true));
        var in = ValuationFixtures.withAnnual(base, annual, ValuationFixtures.quarters("NSE:WOBBLY", 2026, 27.0, true));
        var r = assemble(in);
        assertThat(r.status()).isEqualTo(ValuationReading.Status.PARTIAL);
        assertThat(r.reasonCode()).isEqualTo(ValuationReading.Reason.TREND_UNRELIABLE);
        assertThat(r.trendComparison().epsIfTrendContinues()).isNull();
        assertThat(r.trendComparison().withheldReason()).contains("standard error");
        assertThat(r.trendComparison().pe0()).isNotNull();
        assertThat(r.earnings().trend().growthPct()).isNotNull();
        assertThat(r.earnings().trend().stdErrPp()).isGreaterThan(5.0);
        assertThat(r.earnings().trend().observations()).isEqualTo(10);
        assertThat(r.earnings().trend().reliable()).isFalse();
        assertThat(r.requiredGrowthGrid().rows()).hasSize(3);
        for (var row : r.requiredGrowthGrid().rows()) assertThat(row.gapToTrendPp()).isNull();
        assertThat(r.blocks().get("D")).isInstanceOf(java.util.Map.class);
        assertThat(r.capture().cohort()).isEqualTo(ValuationReading.TrendCohort.GATES_WITHHELD);
        assertThat(r.capture().trendLnSlope()).isNotNull();      // still graded later: the gate is a label
    }

    @Test
    @DisplayName("a missing reverse-DCF reading makes the reading PARTIAL, not a refusal, and card F is absent")
    void noSection125() {
        var base = eligible("NSE:X");
        var s = new ValuationGapService.ScoreFacts("NSE:X", TODAY, 800, 8000.0, "Consumer Durables",
                "NOT_APPLICABLE", null, "v1");
        var r = assemble(withScore(base, s));
        assertThat(r.status()).isEqualTo(ValuationReading.Status.PARTIAL);
        assertThat(r.reasonCode()).isEqualTo(ValuationReading.Reason.NO_SECTION_125);
        assertThat(r.section125Reading()).isNull();
        assertThat(r.requiredGrowthGrid()).isNotNull();
    }

    // -------------------------------------------------------------------------------------- gates

    @Test
    @DisplayName("G1a: a stock with accounts but no screening row is NEVER_SCREENED and has nothing to snapshot")
    void neverScreened() {
        var base = eligible("NSE:NEW");
        var r = assemble(withScore(base, null));
        assertThat(r.status()).isEqualTo(ValuationReading.Status.UNAVAILABLE);
        assertThat(r.reasonCode()).isEqualTo(ValuationReading.Reason.NEVER_SCREENED);
        assertThat(r.capture()).isNull();
    }

    @Test
    @DisplayName("G1b: a lender, an insurer or a fee financial is UNSUPPORTED_SECTOR")
    void financials() {
        for (String industry : new String[]{"Private Sector Bank", "Financial Services", "Insurance", "Asset Management Capital Markets"}) {
            var base = eligible("NSE:FIN");
            var s = new ValuationGapService.ScoreFacts("NSE:FIN", TODAY, 800, 8000.0, industry, "FAIRLY_VALUED", 14.0, "v1");
            var r = assemble(withScore(base, s));
            assertThat(r.reasonCode()).as(industry).isEqualTo(ValuationReading.Reason.UNSUPPORTED_SECTOR);
        }
        var marked = new ValuationGapService.Inputs("NSE:BANKISH", "NSE:BANKISH", TODAY, ValuationFixtures.score(
                "NSE:BANKISH", 800, 8000, "Consumer Durables"),
                eligible("NSE:BANKISH").annual(), eligible("NSE:BANKISH").quarters(), true);
        assertThat(assemble(marked).reasonCode()).isEqualTo(ValuationReading.Reason.UNSUPPORTED_SECTOR);
    }

    @Test
    @DisplayName("G1c: a placeholder industry with no table entry is UNCLASSIFIED, never assumed to be an industrial")
    void unclassified() {
        for (String industry : new String[]{"GENERAL", "Other", null, " "}) {
            var base = eligible("NSE:ZZNOSUCHCO");
            var s = new ValuationGapService.ScoreFacts("NSE:ZZNOSUCHCO", TODAY, 800, 8000.0, industry, "FAIRLY_VALUED", 14.0, "v1");
            var r = assemble(withScore(base, s));
            assertThat(r.reasonCode()).as(String.valueOf(industry)).isEqualTo(ValuationReading.Reason.UNCLASSIFIED);
        }
    }

    @Test
    @DisplayName("G2: no market cap, or a price older than 14 days, is refused with its own code")
    void priceGates() {
        var base = eligible("NSE:P");
        var noCap = new ValuationGapService.ScoreFacts("NSE:P", TODAY, 800, null, "Consumer Durables", "FAIRLY_VALUED", 14.0, "v1");
        assertThat(assemble(withScore(base, noCap)).reasonCode()).isEqualTo(ValuationReading.Reason.NO_PRICE_ON_FILE);
        var old = new ValuationGapService.ScoreFacts("NSE:P", TODAY.minusDays(15), 800, 8000.0, "Consumer Durables", "FAIRLY_VALUED", 14.0, "v1");
        assertThat(assemble(withScore(base, old)).reasonCode()).isEqualTo(ValuationReading.Reason.STALE_PRICE);
        var edge = new ValuationGapService.ScoreFacts("NSE:P", TODAY.minusDays(14), 800, 8000.0, "Consumer Durables", "FAIRLY_VALUED", 14.0, "v1");
        assertThat(assemble(withScore(base, edge)).status()).isNotEqualTo(ValuationReading.Status.UNAVAILABLE);
    }

    @Test
    @DisplayName("G7: market cap that implies a different share count from the accounts is SHARE_BASIS_MISMATCH")
    void shareBasisMismatch() {
        var base = eligible("NSE:Q");
        var s = new ValuationGapService.ScoreFacts("NSE:Q", TODAY, 800, 16000.0, "Consumer Durables", "FAIRLY_VALUED", 14.0, "v1");
        var r = assemble(withScore(base, s));
        assertThat(r.reasonCode()).isEqualTo(ValuationReading.Reason.SHARE_BASIS_MISMATCH);
        assertThat(r.reason()).contains("100.0%");
    }

    @Test
    @DisplayName("G8: a loss year refuses the reading but keeps the raw record in the stored row")
    void lossKeepsTheRawSeries() {
        var base = eligible("NSE:LOSS");
        List<AnnualFundamentalsEntity> annual = new java.util.ArrayList<>(base.annual());
        annual.get(4).setNetProfit(-30.0);
        var r = assemble(ValuationFixtures.withAnnual(base, annual, base.quarters()));
        assertThat(r.reasonCode()).isEqualTo(ValuationReading.Reason.LOSS_IN_RECORD);
        assertThat(r.capture()).isNotNull();
        assertThat(r.capture().series()).hasSize(10);
        assertThat(r.capture().cohort()).isEqualTo(ValuationReading.TrendCohort.NOT_FITTED);
        assertThat(r.capture().trendLnSlope()).isNull();
        assertThat(r.capture().series().get(4).netProfitCr()).isEqualTo(-30.0);
    }

    @Test
    @DisplayName("G9: annual EPS that disagrees with the reported quarters by more than 5% is EPS_CROSSCHECK_FAILED")
    void crosscheckFails() {
        var base = eligible("NSE:GRASIMISH");
        double latest = base.annual().get(9).getNetProfit() / 10.0;
        var in = ValuationFixtures.withAnnual(base, base.annual(),
                ValuationFixtures.quarters("NSE:GRASIMISH", 2026, latest * 0.8, true));
        var r = assemble(in);
        assertThat(r.reasonCode()).isEqualTo(ValuationReading.Reason.EPS_CROSSCHECK_FAILED);
        assertThat(r.capture().cohort()).isEqualTo(ValuationReading.TrendCohort.NOT_FITTED);
    }

    @Test
    @DisplayName("G9: no quarterly results on file is EPS_UNVERIFIABLE")
    void crosscheckUnverifiable() {
        var base = eligible("NSE:NOQ");
        var r = assemble(ValuationFixtures.withAnnual(base, base.annual(), List.of()));
        assertThat(r.reasonCode()).isEqualTo(ValuationReading.Reason.EPS_UNVERIFIABLE);
    }

    @Test
    @DisplayName("G10: under 180 days to 31 March 2031 the lens refuses as HORIZON_TOO_SHORT")
    void horizonTooShort() {
        var annual = ValuationFixtures.steady("NSE:LATE", 2030, 8, 10.0, 0.1, 10.0);
        double latest = annual.get(7).getNetProfit() / 10.0;
        LocalDate d = LocalDate.of(2030, 11, 1);
        var in = new ValuationGapService.Inputs("NSE:LATE", "NSE:LATE", d,
                new ValuationGapService.ScoreFacts("NSE:LATE", d, 800, 8000.0, "Consumer Durables", "FAIRLY_VALUED", 14.0, "v1"),
                annual, ValuationFixtures.quarters("NSE:LATE", 2030, latest, true), false);
        assertThat(assemble(in).reasonCode()).isEqualTo(ValuationReading.Reason.HORIZON_TOO_SHORT);
    }

    @Test
    @DisplayName("an insufficient or stale record is refused with the series gate's own reason")
    void seriesGatesSurface() {
        var base = eligible("NSE:SHORT");
        var short4 = ValuationFixtures.withAnnual(base, ValuationFixtures.steady("NSE:SHORT", 2026, 4, 10.0, 0.1, 10.0), base.quarters());
        assertThat(assemble(short4).reasonCode()).isEqualTo(ValuationReading.Reason.INSUFFICIENT_HISTORY);
        var stale = ValuationFixtures.withAnnual(base, ValuationFixtures.steady("NSE:SHORT", 2024, 8, 10.0, 0.1, 10.0), base.quarters());
        assertThat(assemble(stale).reasonCode()).isEqualTo(ValuationReading.Reason.STALE_LATEST_YEAR);
    }

    // ------------------------------------------------------------------- what a refusal carries

    @Nested
    @DisplayName("a refusal carries the failing number and the raw record, and no derived figure")
    class Refusals {

        @Test
        void noDerivedBlocks() {
            var base = eligible("NSE:LOSS2");
            List<AnnualFundamentalsEntity> annual = new java.util.ArrayList<>(base.annual());
            annual.get(2).setNetProfit(-1.0);
            var r = assemble(ValuationFixtures.withAnnual(base, annual, base.quarters()));
            assertThat(r.status()).isEqualTo(ValuationReading.Status.UNAVAILABLE);
            assertThat(r.trendComparison()).isNull();
            assertThat(r.requiredGrowthGrid()).isNull();
            assertThat(r.section125Reading()).isNull();
            assertThat(r.basis()).isNull();
            assertThat(r.horizon()).isNull();
            assertThat(r.earnings().normalisedEps()).isNull();
            assertThat(r.earnings().trend()).isNull();
            assertThat(r.reason()).contains("FY2019");
            assertThat(r.coverageLine()).startsWith("Not read:");
        }

        @Test
        void everyReasonHasText() {
            var base = eligible("NSE:X");
            var r = assemble(withScore(base, null));
            assertThat(r.reason()).isNotBlank();
        }
    }
}
