package com.example.trading.learning;

import com.example.trading.multibagger.MultibaggerScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the coverage vector (SPEC §38.2).
 *
 * <p>The whole value of this feature is in three distinctions it refuses to collapse:
 * <ol>
 *   <li>a signal that was <b>not measured</b> is not a signal that scored zero;</li>
 *   <li>a signal that <b>does not apply</b> (ROCE on a bank) is not a coverage gap, and must
 *       leave the denominator rather than count against it;</li>
 *   <li>a spread that <b>could not be computed</b> is not a healthy spread — {@code collapsed}
 *       is null there, never false.</li>
 * </ol>
 */
class ScreeningCoverageTest {

    private static MultibaggerScore base() {
        MultibaggerScore s = new MultibaggerScore();
        s.setSymbol("NSE:TEST");
        s.setTechnicalMomentumScore(60);
        s.setVolumeAccumulationScore(50);
        s.setRelativeStrengthScore(55);
        s.setPriceStructureScore(45);
        return s;
    }

    private static ScreeningCoverage.SignalCoverage find(List<ScreeningCoverage.SignalCoverage> all, String name) {
        return all.stream().filter(c -> c.signal().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("An unmeasured dimension is counted as a gap, not as a low score")
    void nullIsAGapNotAZero() {
        List<MultibaggerScore> scores = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MultibaggerScore s = base();
            if (i < 4) s.setValuationScore(70);   // 4 measured, 6 null
            scores.add(s);
        }

        ScreeningCoverage.SignalCoverage valuation = find(ScreeningCoverage.compute(scores), "Valuation");

        assertThat(valuation.attempted()).isEqualTo(10);
        assertThat(valuation.measured()).isEqualTo(4);
        assertThat(valuation.notMeasured()).isEqualTo(6);
        assertThat(valuation.coveragePercent()).isEqualTo(40.0);
        // The six nulls contributed no value at all — they did not drag the mean toward zero.
        assertThat(valuation.mean()).isNull();   // only 4 measured values, below the spread floor
    }

    @Test
    @DisplayName("Not-applicable leaves the denominator: a bank without ROCE is not a data gap")
    void notApplicableIsExcludedFromCoverage() {
        List<MultibaggerScore> scores = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MultibaggerScore s = base();
            if (i < 4) {
                // Banks: ROCE is suppressed by design (SPEC §12.8). The marker is capexVerdict
                // — capitalEfficiencyVerdict still grades a bank on ROE/ROA and never carries
                // NA_FINANCIAL (0 of 295 rows on the 2026-08-29 live run).
                s.setCapexVerdict("NA_FINANCIAL");
            } else {
                s.setRocePercent(18.0 + i);
            }
            scores.add(s);
        }

        ScreeningCoverage.SignalCoverage roce = find(ScreeningCoverage.compute(scores), "Roce");

        assertThat(roce.notApplicable()).isEqualTo(4);
        assertThat(roce.measured()).isEqualTo(6);
        assertThat(roce.notMeasured()).isZero();
        // 6 of the 6 stocks it could apply to — not 60%.
        assertThat(roce.coveragePercent()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("An insurer's NO_DATA is a genuine gap, not a not-applicable")
    void noDataIsAGapEvenForFinancials() {
        List<MultibaggerScore> scores = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MultibaggerScore s = base();
            s.setCapitalEfficiencyVerdict("NO_DATA");   // NSE publishes nothing for insurers
            scores.add(s);
        }

        ScreeningCoverage.SignalCoverage verdict =
                find(ScreeningCoverage.compute(scores), "CapitalEfficiencyVerdict");

        assertThat(verdict.notMeasured()).isEqualTo(10);
        assertThat(verdict.notApplicable()).isZero();
        assertThat(verdict.coveragePercent()).isZero();
    }

    @Test
    @DisplayName("Coverage is null, not 0%, when the signal applies to nobody")
    void emptyDenominatorIsNullNotZero() {
        List<MultibaggerScore> scores = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MultibaggerScore s = base();
            s.setCapexVerdict("NA_FINANCIAL");   // every stock a bank
            scores.add(s);
        }

        ScreeningCoverage.SignalCoverage roce = find(ScreeningCoverage.compute(scores), "Roce");

        assertThat(roce.notApplicable()).isEqualTo(10);
        assertThat(roce.coveragePercent()).isNull();
    }

    @Test
    @DisplayName("A collapsed dimension is flagged — the shape of bug #9 and B-060")
    void collapsedSpreadIsFlagged() {
        List<MultibaggerScore> scores = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            MultibaggerScore s = base();
            s.setInstitutionalInterestScore(40);   // the exact 2026-04 failure: every stock 40
            s.setMonthlyRsi(50.0);                 // the exact B-060 failure: constant neutral
            scores.add(s);
        }

        List<ScreeningCoverage.SignalCoverage> vector = ScreeningCoverage.compute(scores);

        ScreeningCoverage.SignalCoverage institutional = find(vector, "InstitutionalInterest");
        assertThat(institutional.coveragePercent()).isEqualTo(100.0);   // fully "covered"...
        assertThat(institutional.stdDev()).isZero();                    // ...and carrying no information
        assertThat(institutional.collapsed()).isTrue();

        assertThat(find(vector, "MonthlyRsi").collapsed()).isTrue();
    }

    @Test
    @DisplayName("A healthy spread is not flagged as collapsed")
    void healthySpreadIsNotFlagged() {
        List<MultibaggerScore> scores = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            MultibaggerScore s = base();
            s.setInstitutionalInterestScore(i * 5);   // 0..95
            scores.add(s);
        }

        ScreeningCoverage.SignalCoverage institutional =
                find(ScreeningCoverage.compute(scores), "InstitutionalInterest");

        assertThat(institutional.stdDev()).isGreaterThan(ScreeningCoverage.MIN_HEALTHY_STDDEV);
        assertThat(institutional.collapsed()).isFalse();
    }

    @Test
    @DisplayName("Below the sample floor, spread is null and collapsed is null — not false")
    void unknownSpreadIsNullNotFalse() {
        List<MultibaggerScore> scores = new ArrayList<>();
        for (int i = 0; i < ScreeningCoverage.MIN_SAMPLE_FOR_SPREAD - 1; i++) {
            MultibaggerScore s = base();
            s.setInstitutionalInterestScore(40);
            scores.add(s);
        }

        ScreeningCoverage.SignalCoverage institutional =
                find(ScreeningCoverage.compute(scores), "InstitutionalInterest");

        assertThat(institutional.stdDev()).isNull();
        assertThat(institutional.collapsed()).isNull();
    }

    @Test
    @DisplayName("UNKNOWN liquidity is a measurement gap, never a tier (Gotcha 33)")
    void unknownLiquidityIsAGap() {
        List<MultibaggerScore> scores = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MultibaggerScore s = base();
            s.setLiquidityTier(i < 3 ? "UNKNOWN" : "LIQUID");
            scores.add(s);
        }

        ScreeningCoverage.SignalCoverage tier = find(ScreeningCoverage.compute(scores), "LiquidityTier");

        assertThat(tier.notMeasured()).isEqualTo(3);
        assertThat(tier.measured()).isEqualTo(7);
    }

    @Test
    @DisplayName("An empty run yields no rows at all rather than a vector of zeroes")
    void emptyRunProducesNothing() {
        assertThat(ScreeningCoverage.compute(List.of())).isEmpty();
        assertThat(ScreeningCoverage.compute(null)).isEmpty();
    }

    @Test
    @DisplayName("Signals are reported once each, and the eight weighted dimensions are all present")
    void everyWeightedDimensionIsCovered() {
        List<MultibaggerScore> scores = List.of(base(), base());
        List<String> names = ScreeningCoverage.compute(scores).stream()
                .map(ScreeningCoverage.SignalCoverage::signal).toList();

        assertThat(names).doesNotHaveDuplicates();
        assertThat(names).contains("TechnicalMomentum", "VolumeAccumulation", "RelativeStrength",
                "PriceStructure", "Valuation", "InstitutionalInterest",
                "FinancialQuality");
        // forensicFlags is deliberately absent: null there means "clean" OR "never checked"
        // and this table cannot tell them apart (Gotcha 44). A guessed number would be worse
        // than the missing row.
        assertThat(names).doesNotContain("ForensicFlags");
    }

    @Test
    @DisplayName("A ratio is never called collapsed — the 0-100 threshold means nothing on it")
    void ratioSignalsGetNoCollapseVerdict() {
        // Debt-to-equity across a real universe: mean ~0.3, sd ~0.5. That is a wide spread,
        // but it sits below the 0-100 score threshold, and the first live run duly raised an
        // ERROR on it. A false alarm on the loudest channel is how a real one gets ignored.
        List<MultibaggerScore> scores = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            MultibaggerScore s = base();
            s.setDebtToEquity(i * 0.1);       // 0.0 .. 1.9, sd well under 5
            s.setOcfToProfitRatio(1.0 + i * 0.05);
            scores.add(s);
        }

        List<ScreeningCoverage.SignalCoverage> vector = ScreeningCoverage.compute(scores);

        ScreeningCoverage.SignalCoverage de = find(vector, "DebtToEquity");
        assertThat(de.stdDev()).isNotNull();          // spread still recorded and trendable
        assertThat(de.stdDev()).isLessThan(ScreeningCoverage.MIN_HEALTHY_STDDEV);
        assertThat(de.collapsed()).isNull();          // ...but no verdict on this scale

        assertThat(find(vector, "OcfToProfit").collapsed()).isNull();
    }

    @Test
    @DisplayName("0-100 signals still get the verdict, so the real alarm survives the fix")
    void scoreScaledSignalsStillFlagged() {
        List<MultibaggerScore> scores = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            MultibaggerScore s = base();
            s.setInstitutionalInterestScore(43);   // a genuinely collapsed dimension
            scores.add(s);
        }
        assertThat(find(ScreeningCoverage.compute(scores), "InstitutionalInterest").collapsed()).isTrue();
    }

    @Test
    @DisplayName("The bank marker is capexVerdict — capitalEfficiencyVerdict never carries NA_FINANCIAL")
    void financialMarkerIsCapexVerdict() {
        // Measured on the 2026-08-29 live run: 0 of 295 rows had
        // capitalEfficiencyVerdict=NA_FINANCIAL (it still grades banks on ROE/ROA), while 24
        // had capexVerdict=NA_FINANCIAL and all 24 of those had a null ROCE. Keying the
        // not-applicable rule on the wrong field left the branch permanently dead, so every
        // bank counted as a ROCE coverage gap.
        List<MultibaggerScore> banks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MultibaggerScore s = base();
            s.setCapexVerdict("NA_FINANCIAL");
            s.setCapitalEfficiencyVerdict("SOLID");   // what a bank actually gets
            banks.add(s);
        }

        ScreeningCoverage.SignalCoverage roce = find(ScreeningCoverage.compute(banks), "Roce");
        assertThat(roce.notApplicable()).isEqualTo(10);
        assertThat(roce.notMeasured()).isZero();
    }

    @Test
    @DisplayName("Macro exposure is never NOT_APPLICABLE — an absence is our gap, not an exemption")
    void macroExposureNeverExemptsAStock() {
        // Every listed business is exposed to something. A bank genuinely has no ROCE, so it
        // leaves that denominator (Gotcha 68); nothing is genuinely outside the weather. So a
        // missing rule has to count AGAINST coverage, or the exposure map could stay 20% written
        // and report full coverage for ever - the zero-coverage failure (B-074) inverted.
        List<MultibaggerScore> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            MultibaggerScore s = base();
            s.setSymbol("NSE:NOSUCHSTOCK" + i);
            s.setIndustry("A Made-Up Industry");
            rows.add(s);
        }

        ScreeningCoverage.SignalCoverage macro =
                find(ScreeningCoverage.compute(rows), "MacroExposure");

        assertThat(macro.notApplicable())
                .as("a stock outside the map is a gap in the map, never an exemption")
                .isZero();
        assertThat(macro.notMeasured()).isEqualTo(8);
        // No 0-100 value behind this row, so it gets no collapse verdict (Gotcha 88): flagging a
        // spread that was never computed is how a real collapse warning gets ignored.
        assertThat(macro.collapsed()).isNull();
    }
}
