package com.example.trading.fundamentals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the turnaround detector (SPEC §32.3, F5).
 *
 * <p>The properties that matter here are about what the detector refuses to say:
 * it must not call a turnaround on three years of data, must not treat a missing
 * year as a flat year, and must not fire on falling debt alone when the balance
 * sheet is still crushing.
 */
class TurnaroundDetectionTest {

    /** Builder for one year of history. Nulls are meaningful — they mean "not reported". */
    private static AnnualFundamentalsEntity yr(int year, Double sales, Double opProfit, Double netProfit,
                                               Double interest, Double borrowings, Double equity) {
        return AnnualFundamentalsEntity.builder()
                .symbol("NSE:ACME").fiscalYear(year).source("IMPORT")
                .sales(sales).operatingProfit(opProfit).netProfit(netProfit)
                .interestCost(interest).borrowings(borrowings).equity(equity)
                .build();
    }

    /** A textbook recovery: debt down, interest down, margins inflecting, operating leverage. */
    private static List<AnnualFundamentalsEntity> textbookTurnaround() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        //                 year  sales  opProfit netProfit interest borrowings equity
        h.add(yr(2022, 1000.0, 80.0, 10.0, 90.0, 900.0, 500.0));   // D/E 1.80
        h.add(yr(2023, 1100.0, 95.0, 25.0, 75.0, 800.0, 520.0));   // D/E 1.54
        h.add(yr(2024, 1250.0, 120.0, 55.0, 60.0, 650.0, 580.0));  // D/E 1.12
        h.add(yr(2025, 1450.0, 200.0, 110.0, 45.0, 550.0, 700.0)); // D/E 0.79, margin 13.8% vs 9.1% avg
        return h;
    }

    @Test
    @DisplayName("A textbook recovery is flagged, with each reason stated in plain English")
    void detectsTextbookTurnaround() {
        var r = TurnaroundDetectionService.classify("NSE:ACME", textbookTurnaround());

        assertThat(r.getVerdict()).isEqualTo("TURNAROUND_CANDIDATE");
        assertThat(r.isCandidate()).isTrue();
        assertThat(r.getCriteriaMet()).isGreaterThanOrEqualTo(TurnaroundDetectionService.CRITERIA_REQUIRED);
        assertThat(r.getSignals()).isNotEmpty();
        // SPEC §21: the reader is not a stock-market expert. Reasons carry numbers and words,
        // not verdict tokens.
        assertThat(r.getSignals()).allSatisfy(s -> assertThat(s).doesNotContain("_"));
    }

    @Test
    @DisplayName("Three years of history is not enough to call a turnaround")
    void refusesOnShortHistory() {
        var short3 = textbookTurnaround().subList(1, 4);
        var r = TurnaroundDetectionService.classify("NSE:ACME", short3);

        assertThat(r.getVerdict()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(r.isCandidate()).isFalse();
        assertThat(r.getCriteriaMet()).isZero();
        // The reason must be visible, or a short history looks identical to a clean bill.
        assertThat(r.getNotMeasured()).isNotEmpty();
    }

    @Test
    @DisplayName("Falling debt on a still-crushing balance sheet is not yet a turnaround")
    void deleveragingAloneIsNotEnough() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        // Debt falls every year but stays far above equity; nothing else improves.
        h.add(yr(2022, 1000.0, 80.0, 10.0, 90.0, 5000.0, 500.0));  // D/E 10.0
        h.add(yr(2023, 1000.0, 80.0, 10.0, 92.0, 4500.0, 500.0));  // D/E 9.0
        h.add(yr(2024, 1000.0, 80.0, 10.0, 94.0, 4000.0, 500.0));  // D/E 8.0
        h.add(yr(2025, 1000.0, 80.0, 10.0, 96.0, 3500.0, 500.0));  // D/E 7.0
        var r = TurnaroundDetectionService.classify("NSE:ACME", h);

        assertThat(r.getVerdict()).isEqualTo("NOT_TURNING");
        assertThat(r.getNotMet()).anySatisfy(m -> assertThat(m).contains("still high"));
    }

    @Test
    @DisplayName("A flat, healthy company is not a turnaround")
    void steadyCompanyIsNotTurning() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        for (int y = 2022; y <= 2025; y++) {
            h.add(yr(y, 1000.0, 200.0, 150.0, 10.0, 100.0, 1000.0));
        }
        var r = TurnaroundDetectionService.classify("NSE:ACME", h);

        assertThat(r.getVerdict()).isEqualTo("NOT_TURNING");
        assertThat(r.getCriteriaMet()).isLessThan(TurnaroundDetectionService.CRITERIA_REQUIRED);
    }

    @Test
    @DisplayName("A missing year is unmeasured, never treated as no change")
    void missingDataIsNotZero() {
        var h = new ArrayList<>(textbookTurnaround());
        // Knock out one year's borrowings: the debt trend becomes unknowable, not flat.
        h.get(2).setBorrowings(null);
        var r = TurnaroundDetectionService.classify("NSE:ACME", h);

        assertThat(r.getNotMeasured()).anySatisfy(m -> assertThat(m).contains("Debt trend"));
        // The criterion must not appear in the passed list on the strength of a guess.
        assertThat(r.getSignals()).noneSatisfy(s -> assertThat(s).contains("three years running"));
    }

    @Test
    @DisplayName("A loss-making base yields no growth rate rather than a meaningless one")
    void negativeBaseIsNotMeasured() {
        List<AnnualFundamentalsEntity> h = new ArrayList<>();
        h.add(yr(2022, 1000.0, 80.0, -50.0, 90.0, 900.0, 500.0));   // loss in the base year
        h.add(yr(2023, 1100.0, 95.0, -20.0, 75.0, 800.0, 520.0));
        h.add(yr(2024, 1250.0, 120.0, 30.0, 60.0, 650.0, 580.0));
        h.add(yr(2025, 1450.0, 200.0, 110.0, 45.0, 550.0, 700.0));
        var r = TurnaroundDetectionService.classify("NSE:ACME", h);

        // Profit CAGR from -50 to 110 is not a number anyone can interpret.
        assertThat(r.getNotMeasured()).anySatisfy(m -> assertThat(m).contains("Operating leverage"));
    }

    @Test
    @DisplayName("CAGR is null from a zero or negative base, never infinity or NaN")
    void cagrGuardsItsBase() {
        assertThat(TurnaroundDetectionService.cagr(0.0, 100.0, 3)).isNull();
        assertThat(TurnaroundDetectionService.cagr(-10.0, 100.0, 3)).isNull();
        assertThat(TurnaroundDetectionService.cagr(100.0, 0.0, 3)).isNull();
        assertThat(TurnaroundDetectionService.cagr(100.0, 200.0, 0)).isNull();
        assertThat(TurnaroundDetectionService.cagr(100.0, 200.0, 3))
                .isCloseTo(25.99, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    @DisplayName("Every criterion is accounted for: passed, failed, or explicitly unmeasured")
    void allCriteriaAreAccountedFor() {
        var r = TurnaroundDetectionService.classify("NSE:ACME", textbookTurnaround());

        // Four criteria exist; none may silently vanish, or "2 of 4" becomes unauditable.
        int total = r.getSignals().size() + r.getNotMet().size() + r.getNotMeasured().size();
        assertThat(total).isEqualTo(4);
    }
}
