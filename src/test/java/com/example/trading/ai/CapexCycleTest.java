package com.example.trading.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the capex-cycle signal (SPEC §31, F4).
 *
 * <p>This is the only scoring input in the system that <i>leads</i> the P&amp;L, so the
 * properties that matter are the ones that keep it from leading in the wrong direction:
 * <ul>
 *   <li>banks never get a capex verdict — their growth is the loan book, not plant</li>
 *   <li>a missing prior-year comparative yields "direction unknown", never "no change"</li>
 *   <li>unmeasurable stays null, and NO_DATA/NA_FINANCIAL never score as a low number</li>
 * </ul>
 */
class CapexCycleTest {

    /** Non-banking balance sheet with the capex fields populated. */
    private static NseDataService.BalanceSheetData bs(Double cwip, Double priorCwip,
                                                      Double ppe, Double priorPpe,
                                                      Double dep, boolean priorAvailable) {
        NseDataService.BalanceSheetData b = new NseDataService.BalanceSheetData();
        b.setSymbol("ACME");
        b.setFinancialYear("01-Apr-2024 To 31-Mar-2025");
        b.setCapitalWorkInProgress(cwip);
        b.setPriorCapitalWorkInProgress(priorCwip);
        b.setPropertyPlantEquipment(ppe);
        b.setPriorPropertyPlantEquipment(priorPpe);
        b.setDepreciation(dep);
        b.setPriorYearAvailable(priorAvailable);
        return b;
    }

    @Test
    @DisplayName("B-048: an unknown CWIP change does not become a zero inside the capex proxy")
    void unknownCwipChangeLeavesTheRatioUnmeasured() {
        // Net block and depreciation are known, so the ratio LOOKS computable — but the
        // company is carrying Rs 500 cr of construction whose year-on-year change is
        // unknown. Substituting 0 for that change understates capex by exactly the
        // spending this analysis exists to detect, and does it silently: the ratio still
        // comes out looking measured.
        var d = NseDataService.classifyCapexCycle(
                bs(500.0, null, 1000.0, 900.0, 50.0, false), false, null, 900.0);

        assertThat(d.getCapexToDepreciation()).isNull();
        assertThat(d.getCapexProxy()).isNull();
    }

    @Test
    @DisplayName("B-048: a company with no construction in either year still gets a capex ratio")
    void absentCwipInBothYearsIsAGenuineZero() {
        // The other side of the same rule: an asset-light company reports no CWIP at all,
        // and the change between two absences really is zero. Refusing to measure here
        // would throw away a computable ratio for every company without a build programme.
        var d = NseDataService.classifyCapexCycle(
                bs(null, null, 1000.0, 900.0, 50.0, true), false, null, 900.0);

        // capex = (1000 - 900) + 0 + 50 = 150; 150 / 50 = 3.0
        assertThat(d.getCapexToDepreciation()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("B-048: the prior net block supplied from stored history unlocks the ratio")
    void historyFallbackSuppliesPriorNetBlock() {
        // The filing carries no comparative balance sheet (B-034), so priorPpe arrives from
        // annual_fundamentals. Until the history row stored a net block, this argument was
        // always null and capex-to-depreciation was unreachable in production.
        var withHistory = NseDataService.classifyCapexCycle(
                bs(200.0, 150.0, 1000.0, null, 50.0, false), false, 150.0, 900.0);
        var withoutHistory = NseDataService.classifyCapexCycle(
                bs(200.0, 150.0, 1000.0, null, 50.0, false), false, 150.0, null);

        assertThat(withHistory.getCapexToDepreciation()).isNotNull();
        assertThat(withoutHistory.getCapexToDepreciation()).isNull();
    }

    // ---- Financial-sector suppression ----

    @Test
    @DisplayName("Banks get NA_FINANCIAL, not a verdict computed from branch fit-outs")
    void banksAreSuppressed() {
        // Give it CWIP that would otherwise read as a huge expansion.
        var d = NseDataService.classifyCapexCycle(bs(500.0, 100.0, 1000.0, 900.0, 50.0, true), true);

        assertThat(d.getVerdict()).isEqualTo("NA_FINANCIAL");
        assertThat(d.isApplicable()).isFalse();
        // Suppressed means suppressed: no half-computed ratios leaking into a report.
        assertThat(d.getCwipIntensityPercent()).isNull();
        assertThat(d.getCapexToDepreciation()).isNull();
        // NA is an absence, not a low score -- it must never rank below a real HARVESTING.
        assertThat(d.toScore()).isNull();
    }

    // ---- The signal this feature exists for ----

    @Test
    @DisplayName("A large and growing build is EXPANSION_UNDERWAY")
    void bigRisingBuildIsExpansion() {
        // CWIP 300 on a 1000 net block = 30% intensity, up from 100 last year.
        var d = NseDataService.classifyCapexCycle(bs(300.0, 100.0, 1000.0, 950.0, 60.0, true), false);

        assertThat(d.getVerdict()).isEqualTo("EXPANSION_UNDERWAY");
        assertThat(d.isApplicable()).isTrue();
        assertThat(d.getCwipIntensityPercent()).isEqualTo(30.0);
        assertThat(d.getCwipChange()).isEqualTo(200.0);
        assertThat(d.toScore()).isEqualTo(90);
    }

    @Test
    @DisplayName("A large build that is SHRINKING is not an expansion")
    void bigShrinkingBuildIsNotExpansion() {
        // Same 30% intensity, but the project is winding down -- capacity is being
        // commissioned, not started. Reading this as "expansion underway" would put the
        // signal exactly one cycle late, which is the failure this feature exists to avoid.
        var d = NseDataService.classifyCapexCycle(bs(300.0, 500.0, 1000.0, 950.0, 60.0, true), false);

        assertThat(d.getVerdict()).isNotEqualTo("EXPANSION_UNDERWAY");
        assertThat(d.getCwipChange()).isEqualTo(-200.0);
    }

    // ---- Capex-to-depreciation band ----

    @Test
    @DisplayName("Spending well above depreciation is INVESTING")
    void investingBand() {
        // capex = dPPE(200) + dCWIP(0) + dep(50) = 250 => 5.0x depreciation
        var d = NseDataService.classifyCapexCycle(bs(10.0, 10.0, 1200.0, 1000.0, 50.0, true), false);

        assertThat(d.getVerdict()).isEqualTo("INVESTING");
        assertThat(d.getCapexToDepreciation()).isEqualTo(5.0);
        assertThat(d.toScore()).isEqualTo(70);
    }

    @Test
    @DisplayName("Spending below depreciation is HARVESTING")
    void harvestingBand() {
        // Net block shrinking by more than the year's depreciation: capex = -60 + 0 + 50 = -10
        var d = NseDataService.classifyCapexCycle(bs(10.0, 10.0, 940.0, 1000.0, 50.0, true), false);

        assertThat(d.getVerdict()).isEqualTo("HARVESTING");
        assertThat(d.getCapexToDepreciation()).isLessThan(0.8);
        assertThat(d.toScore()).isEqualTo(30);
    }

    @Test
    @DisplayName("Replacing roughly what wears out is STEADY")
    void steadyBand() {
        // capex = dPPE(0) + dCWIP(0) + dep(50) = 50 => exactly 1.0x
        var d = NseDataService.classifyCapexCycle(bs(10.0, 10.0, 1000.0, 1000.0, 50.0, true), false);

        assertThat(d.getVerdict()).isEqualTo("STEADY");
        assertThat(d.getCapexToDepreciation()).isEqualTo(1.0);
        assertThat(d.toScore()).isEqualTo(50);
    }

    // ---- Missing-data discipline ----

    @Test
    @DisplayName("Without a prior-year column the direction is unknown, never assumed flat")
    void noComparativeMeansDirectionUnknown() {
        var d = NseDataService.classifyCapexCycle(bs(300.0, null, 1000.0, null, 60.0, false), false);

        // Intensity is measurable from the current column alone, so we still say something...
        assertThat(d.getCwipIntensityPercent()).isEqualTo(30.0);
        // ...but never EXPANSION_UNDERWAY, which asserts a direction we cannot see.
        assertThat(d.getVerdict()).isEqualTo("INVESTING");
        assertThat(d.getReason()).contains("not available yet");
        // A null change must not be silently read as zero change.
        assertThat(d.getCwipChange()).isNull();
        assertThat(d.getCapexToDepreciation()).isNull();
        assertThat(d.isPriorYearAvailable()).isFalse();
    }

    @Test
    @DisplayName("A prior year supplied from stored history unlocks EXPANSION_UNDERWAY")
    void historyFallbackSuppliesPriorYear() {
        // This is the real-world path (B-034): NSE's integrated filing tags no comparative
        // balance sheet, so without the annual-history table the headline verdict could
        // never fire at all.
        var withoutHistory = NseDataService.classifyCapexCycle(
                bs(300.0, null, 1000.0, null, 60.0, false), false);
        assertThat(withoutHistory.getVerdict()).isEqualTo("INVESTING");

        var withHistory = NseDataService.classifyCapexCycle(
                bs(300.0, null, 1000.0, null, 60.0, false), false, 100.0, null);
        assertThat(withHistory.getVerdict()).isEqualTo("EXPANSION_UNDERWAY");
        assertThat(withHistory.getCwipChange()).isEqualTo(200.0);
        assertThat(withHistory.isPriorYearAvailable()).isTrue();
    }

    @Test
    @DisplayName("The filing's own comparative wins over stored history when both exist")
    void filingComparativeOutranksHistory() {
        // The filing is the primary source; history is a reconstruction. If they disagree,
        // trusting the reconstruction would silently prefer the weaker evidence.
        var d = NseDataService.classifyCapexCycle(
                bs(300.0, 250.0, 1000.0, 950.0, 60.0, true), false, 100.0, 500.0);

        assertThat(d.getCwipChange()).isEqualTo(50.0);   // 300 - 250, not 300 - 100
    }

    @Test
    @DisplayName("'Prior year available' means usable figures, not merely a resolved context")
    void priorYearAvailableReflectsUsableData() {
        // The bug this pins: the parser resolved a prior context id and reported
        // priorYearAvailable=true while every comparative figure was null, so a missing
        // measurement looked like a measured one (B-034).
        var d = NseDataService.classifyCapexCycle(bs(300.0, null, 1000.0, null, 60.0, true), false);

        assertThat(d.isPriorYearAvailable()).isFalse();
    }

    @Test
    @DisplayName("STEADY never claims a spending ratio it did not measure")
    void steadyDoesNotAssertUnmeasuredRatio() {
        // Small build, no prior year: capex-to-depreciation is null. The reason line must
        // not say "spending is in line with depreciation" from a number never computed.
        var d = NseDataService.classifyCapexCycle(bs(50.0, null, 1000.0, null, 60.0, false), false);

        assertThat(d.getVerdict()).isEqualTo("STEADY");
        assertThat(d.getCapexToDepreciation()).isNull();
        assertThat(d.getReason()).contains("cannot be measured");
        assertThat(d.getReason()).doesNotContain("x depreciation");
    }

    @Test
    @DisplayName("A filing with no capex tags at all is NO_DATA, not STEADY")
    void untaggedFilingIsNoData() {
        var d = NseDataService.classifyCapexCycle(bs(null, null, null, null, null, true), false);

        assertThat(d.getVerdict()).isEqualTo("NO_DATA");
        assertThat(d.isApplicable()).isFalse();
        // The distinction that matters: "we could not measure" must not score as mid-range,
        // which would rank an unanalysable stock above a measured harvester.
        assertThat(d.toScore()).isNull();
        assertThat(d.getReason()).isNotBlank();
    }

    @Test
    @DisplayName("Zero net block does not produce an infinite or NaN intensity")
    void zeroNetBlockIsNotDividedBy() {
        var d = NseDataService.classifyCapexCycle(bs(300.0, 100.0, 0.0, 0.0, 0.0, true), false);

        assertThat(d.getCwipIntensityPercent()).isNull();
        assertThat(d.getCapexToDepreciation()).isNull();
        assertThat(d.getVerdict()).isEqualTo("NO_DATA");
    }

    @Test
    @DisplayName("The reason line is plain English, for a reader who is not a stock-market expert")
    void reasonIsReadable() {
        var d = NseDataService.classifyCapexCycle(bs(300.0, 100.0, 1000.0, 950.0, 60.0, true), false);

        // SPEC §21: a verdict token like EXPANSION_UNDERWAY means nothing on its own.
        assertThat(d.getReason()).isNotBlank();
        assertThat(d.getReason()).doesNotContain("_");
        assertThat(d.getReason().toLowerCase()).contains("capacity");
    }
}
