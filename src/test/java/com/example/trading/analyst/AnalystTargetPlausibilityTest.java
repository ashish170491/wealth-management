package com.example.trading.analyst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Running the §12.5 reverse DCF backwards from an analyst's target (SPEC §49.13).
 *
 * <p>The load-bearing case here is the <b>round trip</b>: at a target equal to the current price
 * the requirement must come back as the stored implied growth, to solver precision. That is what
 * proves the inversion is the same model rather than a second one that happens to agree, and it is
 * the case that fails if anyone changes the discount rate, terminal growth or forecast window in
 * {@code IntrinsicValuationService} without changing them here — at which point every reading on
 * screen would be inverted from a stored figure produced under different assumptions.
 */
class AnalystTargetPlausibilityTest {

    /** A record of {@code cagr}% a year over a comfortably-deep run of accounts. */
    private static AnalystTargetPlausibility.GrowthRecord record(double cagr) {
        return new AnalystTargetPlausibility.GrowthRecord(cagr, 7, 2019, 2026, "test record");
    }

    /** No record at all -- the state most of the universe is in until the backfill converges. */
    private static AnalystTargetPlausibility.GrowthRecord noRecord() {
        return new AnalystTargetPlausibility.GrowthRecord(null, 2, null, null,
                "Only 2 years of accounts are on file.");
    }

    @Test
    @DisplayName("A target equal to the price requires exactly the growth the price already assumes")
    void roundTripsAtTheCurrentPrice() {
        for (double implied : new double[] {-8, 0, 4, 7.5, 12, 18, 25, 40}) {
            AnalystTargetPlausibility.Read r =
                    AnalystTargetPlausibility.forTarget(implied, record(10.0), 1000, 1000);
            assertThat(r.requiredGrowthPercent())
                    .as("implied %.1f%% must invert to itself", implied)
                    .isCloseTo(implied, within(0.01));
            assertThat(r.gapVsTodayPoints()).isCloseTo(0.0, within(0.01));
        }
    }

    @Test
    @DisplayName("A higher target always requires more growth, and a lower one less")
    void monotone() {
        AnalystTargetPlausibility.Read up =
                AnalystTargetPlausibility.forTarget(12.0, record(10.0), 1000, 1400);
        AnalystTargetPlausibility.Read down =
                AnalystTargetPlausibility.forTarget(12.0, record(10.0), 1000, 700);

        assertThat(up.requiredGrowthPercent()).isGreaterThan(12.0);
        assertThat(down.requiredGrowthPercent()).isLessThan(12.0);
        assertThat(up.gapVsTodayPoints()).isPositive();
        assertThat(down.gapVsTodayPoints()).isNegative();
    }

    @Test
    @DisplayName("Bands are the §12.5 expectation-gap bands, measured against the record not the price")
    void bandsFollowTheRecord() {
        // Same target, same price, same implied growth - only the company's record differs, and
        // that alone must move the verdict. A target is demanding relative to what the business
        // has done, never in the abstract.
        assertThat(AnalystTargetPlausibility.classify(-9.0))
                .isEqualTo(AnalystTargetPlausibility.Verdict.BELOW_ITS_RECORD);
        assertThat(AnalystTargetPlausibility.classify(0.0))
                .isEqualTo(AnalystTargetPlausibility.Verdict.IN_LINE_WITH_RECORD);
        assertThat(AnalystTargetPlausibility.classify(4.9))
                .isEqualTo(AnalystTargetPlausibility.Verdict.IN_LINE_WITH_RECORD);
        assertThat(AnalystTargetPlausibility.classify(5.0))
                .isEqualTo(AnalystTargetPlausibility.Verdict.ABOVE_ITS_RECORD);
        assertThat(AnalystTargetPlausibility.classify(11.9))
                .isEqualTo(AnalystTargetPlausibility.Verdict.ABOVE_ITS_RECORD);
        assertThat(AnalystTargetPlausibility.classify(12.0))
                .isEqualTo(AnalystTargetPlausibility.Verdict.FAR_ABOVE_ITS_RECORD);
    }

    @Test
    @DisplayName("No stored valuation yields no number, and says why it is not a comment on the target")
    void lossMakingIsNotMeasuredRatherThanZero() {
        AnalystTargetPlausibility.Read r =
                AnalystTargetPlausibility.forTarget(null, record(20.0), 1000, 1500);

        assertThat(r.verdict()).isEqualTo(AnalystTargetPlausibility.Verdict.NOT_MEASURED);
        assertThat(r.requiredGrowthPercent()).isNull();
        assertThat(r.measured()).isFalse();
        // The distinction Gotcha 44 exists for: nothing was checked, which is not a finding
        // against the analyst.
        assertThat(r.reason()).contains("not a comment on the target");
    }

    @Test
    @DisplayName("A requirement without a record is reported as a fact, not graded")
    void noHistoryIsItsOwnVerdict() {
        AnalystTargetPlausibility.Read r =
                AnalystTargetPlausibility.forTarget(11.0, noRecord(), 1000, 1300);

        assertThat(r.verdict()).isEqualTo(AnalystTargetPlausibility.Verdict.NO_RECORD_TO_COMPARE);
        // The requirement IS computable and is useful on its own - withholding it because the
        // comparison is missing would throw away the measurement that did work.
        assertThat(r.requiredGrowthPercent()).isNotNull().isGreaterThan(11.0);
        assertThat(r.gapVsHistoryPoints()).isNull();
        assertThat(r.reason()).contains("nothing to compare");
    }

    @Test
    @DisplayName("A target outside the solver's range yields no number, never one pinned to the edge")
    void beyondRangeIsRefusedNotClamped() {
        // Measured while writing this: from a 12% implied growth the bracket [-50%, +60%] reaches
        // roughly a 25x target, so BEYOND_MODEL_RANGE is a guard rather than an everyday branch --
        // a 20x target still solves. Worth knowing: no published target on a listed company comes
        // close, so a reading that DOES hit this bound is a data problem (a units error, a
        // truncated figure -- B-111's family) and not a bold analyst.
        AnalystTargetPlausibility.Read solvable =
                AnalystTargetPlausibility.forTarget(12.0, record(10.0), 100, 2000);
        assertThat(solvable.requiredGrowthPercent())
                .as("a 20x target is extreme but still inside the model")
                .isNotNull();

        AnalystTargetPlausibility.Read r =
                AnalystTargetPlausibility.forTarget(12.0, record(10.0), 100, 10000);
        assertThat(r.verdict()).isEqualTo(AnalystTargetPlausibility.Verdict.BEYOND_MODEL_RANGE);
        assertThat(r.requiredGrowthPercent())
                .as("60% would look exactly like a measurement")
                .isNull();
    }

    @Test
    @DisplayName("Rare growth is named in words, because a reader should not have to infer it")
    void rareGrowthIsSpeltOut() {
        AnalystTargetPlausibility.Read r =
                AnalystTargetPlausibility.forTarget(25.0, record(8.0), 1000, 1900);

        assertThat(r.requiredGrowthPercent()).isGreaterThan(AnalystTargetPlausibility.RARE_GROWTH);
        assertThat(r.reason()).contains("rare for any business");
        assertThat(r.verdict()).isEqualTo(AnalystTargetPlausibility.Verdict.FAR_ABOVE_ITS_RECORD);
    }

    @Test
    @DisplayName("The record is multi-year, because a two-year CAGR made every stock read alike")
    void theRecordMustBeDeepEnoughToJudgeATenYearRequirement() {
        // The defect this test exists for (B-113). dcf_historical_growth_percent is a TWO-year
        // profit CAGR and on the first live run it read: BRIGADE 37%, SONACOMS 47%, TITAN 63%,
        // DIVISLAB 66%, GALAXYSURF 109%. Those are base effects. Against them a 10-26% ten-year
        // requirement is always "below its record", and all six stocks returned one verdict --
        // the zero-variance signature of a measurement that is not measuring.
        Map<Integer, Double> sevenYears = new LinkedHashMap<>();
        sevenYears.put(2019, 100.0);
        sevenYears.put(2020, 60.0);   // a bad year in the middle must not be smoothed away
        sevenYears.put(2021, 120.0);
        sevenYears.put(2022, 150.0);
        sevenYears.put(2023, 170.0);
        sevenYears.put(2024, 190.0);
        sevenYears.put(2025, 200.0);

        AnalystTargetPlausibility.GrowthRecord rec =
                AnalystTargetPlausibility.growthRecord(sevenYears);
        assertThat(rec.measured()).isTrue();
        assertThat(rec.years()).isEqualTo(7);
        assertThat(rec.fromYear()).isEqualTo(2019);
        assertThat(rec.toYear()).isEqualTo(2025);
        // 100 -> 200 over six spans is 2^(1/6) - 1, about 12.2%/yr. Nothing like the 109% a
        // two-year window off the 2020 trough would have produced.
        assertThat(rec.cagrPercent()).isCloseTo(12.2, within(0.2));

        // Three years is not a record for a ten-year requirement.
        Map<Integer, Double> three = new LinkedHashMap<>();
        three.put(2023, 100.0);
        three.put(2024, 150.0);
        three.put(2025, 225.0);
        AnalystTargetPlausibility.GrowthRecord thin = AnalystTargetPlausibility.growthRecord(three);
        assertThat(thin.measured()).isFalse();
        assertThat(thin.cagrPercent()).as("50%/yr off three years is not a record").isNull();
        assertThat(thin.note()).contains("3 years");
    }

    @Test
    @DisplayName("A recovery from a loss has no growth rate, and that is not a bad reading")
    void lossBaseYieldsNoCagr() {
        Map<Integer, Double> fromLoss = new LinkedHashMap<>();
        fromLoss.put(2021, -50.0);
        fromLoss.put(2022, 10.0);
        fromLoss.put(2023, 40.0);
        fromLoss.put(2024, 80.0);
        fromLoss.put(2025, 120.0);

        AnalystTargetPlausibility.GrowthRecord rec =
                AnalystTargetPlausibility.growthRecord(fromLoss);
        // A CAGR from a negative base is arithmetically meaningless and would come out either
        // negative or enormous -- both of them wrong about a genuine turnaround.
        assertThat(rec.cagrPercent()).isNull();
        assertThat(rec.note()).contains("not a bad sign");

        // ...and currently loss-making is its own, different absence.
        Map<Integer, Double> intoLoss = new LinkedHashMap<>();
        intoLoss.put(2021, 100.0);
        intoLoss.put(2022, 80.0);
        intoLoss.put(2023, 40.0);
        intoLoss.put(2024, 10.0);
        intoLoss.put(2025, -30.0);
        assertThat(AnalystTargetPlausibility.growthRecord(intoLoss).cagrPercent()).isNull();

        assertThat(AnalystTargetPlausibility.growthRecord(null).measured()).isFalse();
        assertThat(AnalystTargetPlausibility.growthRecord(Map.of()).years()).isZero();
    }

    @Test
    @DisplayName("Nothing here predicts a price, promises a date or names a trade")
    void carriesNoInstructionToTransact() {
        AnalystTargetPlausibility.Read r =
                AnalystTargetPlausibility.forTarget(12.0, record(10.0), 1000, 1400);

        String text = (r.verdict().name() + " " + r.reason()).toLowerCase();
        assertThat(text).doesNotContain("buy").doesNotContain("sell").doesNotContain("exit");
        // SPEC 19: no short-term price prediction. The reading is about earnings, and the wording
        // must not drift into forecasting the share price or putting odds on it.
        assertThat(text).doesNotContain("will reach").doesNotContain("probability")
                .doesNotContain("likely to hit");

        String caveats = String.join(" ", AnalystTargetPlausibility.caveat().values()).toLowerCase();
        assertThat(caveats).doesNotContain("buy").doesNotContain("sell");
        // The known model bias must be stated wherever the reading is, or a compounder's
        // FAR_ABOVE_ITS_RECORD reads as a finding about the analyst instead of a limitation
        // of a ten-year DCF (SPEC 12.5's documented caveat).
        assertThat(caveats).contains("under-values");
        assertThat(AnalystTargetPlausibility.caveat()).containsKeys(
                "whatThisIs", "notAPrediction", "knownBias", "notForLenders", "oneInputOnly");
    }

    @Test
    @DisplayName("The present-value factor is the §12.5 model with a unit cash flow")
    void pvFactorMatchesTheValuationModel() {
        // fairValue(fcf, g, r) is linear in fcf, which is the whole reason the cash-flow proxy
        // cancels out of the inversion. If that stopped being true the inversion would be wrong
        // in a way no other test here could see.
        double g = 0.11, r = 0.12;
        double unit = AnalystTargetPlausibility.pvFactor(g, r);
        assertThat(unit).isGreaterThan(0);

        double scaled = 0;
        double cf = 250.0;
        for (int y = 1; y <= AnalystTargetPlausibility.FORECAST_YEARS; y++) {
            cf = cf * (1 + g);
            scaled += cf / Math.pow(1 + r, y);
        }
        double tg = Math.min(AnalystTargetPlausibility.TERMINAL_GROWTH, g);
        scaled += (cf * (1 + tg) / (r - tg))
                / Math.pow(1 + r, AnalystTargetPlausibility.FORECAST_YEARS);

        assertThat(unit * 250.0).isCloseTo(scaled, within(1e-6));
    }
}
