package com.example.trading.valuation;

import com.example.trading.earnings.QuarterlyResultEntity;
import com.example.trading.fundamentals.AnnualFundamentalsEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** SPEC §57.3: gates G3-G9, one named reason each, nothing repaired. */
class EpsSeriesTest {

    private static final int REQUIRED = 2026;

    private static EpsSeries.Built build(List<AnnualFundamentalsEntity> rows) {
        return EpsSeries.build(rows, REQUIRED);
    }

    @Test
    @DisplayName("ten steady years pass every series gate and EPS is net profit over shares")
    void steadySeriesPasses() {
        var b = build(ValuationFixtures.steady("A", 2026, 10, 10.0, 0.15, 10.0));
        assertThat(b.usable()).isTrue();
        assertThat(b.observations()).isEqualTo(10);
        assertThat(b.years().get(0).eps()).isCloseTo(10.0, within(1e-9));
        assertThat(b.years().get(9).eps()).isCloseTo(10.0 * Math.pow(1.15, 9), within(1e-9));
        assertThat(b.consolidated()).isTrue();
    }

    @Test
    @DisplayName("a bonus is divided out of earlier years so EPS does not fall off a cliff")
    void bonusRestatesEarlierEps() {
        List<AnnualFundamentalsEntity> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double shares = i < 3 ? 100 : 300;
            rows.add(ValuationFixtures.year("A", 2021 + i, 3000.0 * Math.pow(1.1, i), shares, 10.0, true));
        }
        var b = build(rows);
        assertThat(b.usable()).isTrue();
        assertThat(b.actions()).hasSize(1);
        // Pre-bonus year: 3000 / (100 x 3) = 10, comparable with the post-bonus years.
        assertThat(b.years().get(0).eps()).isCloseTo(10.0, within(1e-9));
        assertThat(b.years().get(5).eps()).isCloseTo(3000.0 * Math.pow(1.1, 5) / 300.0, within(1e-9));
    }

    @Test
    @DisplayName("fewer than five contiguous years is INSUFFICIENT_HISTORY, naming the count")
    void tooShort() {
        var b = build(ValuationFixtures.steady("A", 2026, 4, 10.0, 0.1, 10.0));
        assertThat(b.reason()).isEqualTo(ValuationReading.Reason.INSUFFICIENT_HISTORY);
        assertThat(b.reasonText()).contains("4");
    }

    @Test
    @DisplayName("exactly five years is enough (four growth intervals)")
    void fiveIsTheMinimum() {
        assertThat(build(ValuationFixtures.steady("A", 2026, 5, 10.0, 0.1, 10.0)).usable()).isTrue();
    }

    @Test
    @DisplayName("a gap year ends the run: the trailing contiguous years are what counts")
    void gapYear() {
        List<AnnualFundamentalsEntity> rows = ValuationFixtures.steady("A", 2026, 10, 10.0, 0.1, 10.0);
        rows.remove(6);   // FY2023 missing: only FY2024-2026 are contiguous at the end
        var b = build(rows);
        assertThat(b.reason()).isEqualTo(ValuationReading.Reason.INSUFFICIENT_HISTORY);
    }

    @Test
    @DisplayName("one-year history (208 symbols in the live table) is refused, not stretched")
    void oneYear() {
        var b = build(ValuationFixtures.steady("A", 2026, 1, 10.0, 0.1, 10.0));
        assertThat(b.reason()).isEqualTo(ValuationReading.Reason.INSUFFICIENT_HISTORY);
    }

    @Test
    @DisplayName("no rows at all is INSUFFICIENT_HISTORY with its own wording")
    void noRows() {
        var b = build(List.of());
        assertThat(b.reason()).isEqualTo(ValuationReading.Reason.INSUFFICIENT_HISTORY);
        assertThat(b.reasonText()).contains("No annual accounts");
    }

    @Test
    @DisplayName("a series whose newest year is older than required is STALE_LATEST_YEAR; an early filer with a newer year is not")
    void staleAndEarlyFiler() {
        var stale = EpsSeries.build(ValuationFixtures.steady("A", 2025, 6, 10.0, 0.1, 10.0), 2026);
        assertThat(stale.reason()).isEqualTo(ValuationReading.Reason.STALE_LATEST_YEAR);
        var early = EpsSeries.build(ValuationFixtures.steady("A", 2027, 6, 10.0, 0.1, 10.0), 2026);
        assertThat(early.usable()).isTrue();
    }

    @Test
    @DisplayName("a null basis on the newest year is BASIS_UNKNOWN, never assumed")
    void nullBasisNewest() {
        List<AnnualFundamentalsEntity> rows = ValuationFixtures.steady("A", 2026, 8, 10.0, 0.1, 10.0);
        rows.get(7).setConsolidated(null);
        assertThat(build(rows).reason()).isEqualTo(ValuationReading.Reason.BASIS_UNKNOWN);
    }

    @Test
    @DisplayName("an older stretch on the other basis is dropped; if five recent years remain they are used")
    void basisSwitchOldStretchDropped() {
        List<AnnualFundamentalsEntity> rows = ValuationFixtures.steady("A", 2026, 10, 10.0, 0.1, 10.0);
        for (int i = 0; i < 3; i++) rows.get(i).setConsolidated(false);   // standalone before FY2020
        var b = build(rows);
        assertThat(b.usable()).isTrue();
        assertThat(b.observations()).isEqualTo(7);
        assertThat(b.years().get(0).fiscalYear()).isEqualTo(2020);
    }

    @Test
    @DisplayName("CERA-style: the basis changes so recently that fewer than five years share it is BASIS_SWITCH")
    void basisSwitchRecent() {
        List<AnnualFundamentalsEntity> rows = ValuationFixtures.steady("A", 2026, 8, 10.0, 0.1, 10.0);
        for (int i = 0; i < 5; i++) rows.get(i).setConsolidated(false);
        var b = build(rows);
        assertThat(b.reason()).isEqualTo(ValuationReading.Reason.BASIS_SWITCH);
    }

    @Test
    @DisplayName("an unstated basis inside the run stops the tail and refuses when too few years remain")
    void unknownBasisOlderYear() {
        List<AnnualFundamentalsEntity> rows = ValuationFixtures.steady("A", 2026, 8, 10.0, 0.1, 10.0);
        rows.get(4).setConsolidated(null);
        assertThat(build(rows).reason()).isEqualTo(ValuationReading.Reason.BASIS_UNKNOWN);
    }

    @Test
    @DisplayName("a missing net profit or share count names the year and the field")
    void fieldMissing() {
        List<AnnualFundamentalsEntity> rows = ValuationFixtures.steady("A", 2026, 7, 10.0, 0.1, 10.0);
        rows.get(3).setShareCount(null);
        var b = build(rows);
        assertThat(b.reason()).isEqualTo(ValuationReading.Reason.FIELD_MISSING);
        assertThat(b.reasonText()).contains("FY2023").contains("share count");
    }

    @Test
    @DisplayName("a loss anywhere in the record is LOSS_IN_RECORD, quoting the year")
    void lossInRecord() {
        List<AnnualFundamentalsEntity> rows = ValuationFixtures.steady("A", 2026, 7, 10.0, 0.1, 10.0);
        rows.get(2).setNetProfit(-50.0);
        var b = build(rows);
        assertThat(b.reason()).isEqualTo(ValuationReading.Reason.LOSS_IN_RECORD);
        assertThat(b.reasonText()).contains("FY2022");
        assertThat(b.years()).hasSize(7);   // the raw record stays, so a later row can still freeze it
    }

    @Test
    @DisplayName("share-count refusals surface as their own reasons")
    void shareRefusalsMapThrough() {
        List<AnnualFundamentalsEntity> rows = ValuationFixtures.steady("A", 2026, 7, 10.0, 0.1, 10.0);
        rows.get(4).setShareCount(10.0 * 1.05);
        rows.get(5).setShareCount(10.0 * 1.05);
        rows.get(6).setShareCount(10.0 * 1.05);
        rows.get(3).setShareCount(10.0);
        assertThat(build(rows).reason()).isEqualTo(ValuationReading.Reason.AMBIGUOUS_SHARE_STEP);
    }

    // ------------------------------------------------------------------------------------- G7

    @Test
    @DisplayName("G7: market cap over price must match the year-end share count within 2%")
    void shareBasis() {
        assertThat(EpsSeries.shareBasisGap(10000.0, 100.0, 100.0)).isNull();
        assertThat(EpsSeries.shareBasisGap(10190.0, 100.0, 100.0)).isNull();       // +1.9%
        assertThat(EpsSeries.shareBasisGap(10300.0, 100.0, 100.0)).isCloseTo(0.03, within(1e-9));
        assertThat(EpsSeries.shareBasisGap(20000.0, 100.0, 100.0)).isCloseTo(1.0, within(1e-9));   // IRB-style 2.0x
        assertThat(EpsSeries.shareBasisGap(null, 100.0, 100.0)).isNull();
    }

    // ------------------------------------------------------------------------------------- G9

    @Test
    @DisplayName("G9: four quarters on the same basis that add to the annual EPS pass")
    void crossCheckPasses() {
        var q = ValuationFixtures.quarters("A", 2026, 40.0, true);
        var c = EpsSeries.crossCheck(q, 2026, true, 40.0);
        assertThat(c.passed()).isTrue();
        assertThat(c.ratio()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("G9: within 5% passes, beyond it fails with the figures quoted")
    void crossCheckBoundary() {
        var q = ValuationFixtures.quarters("A", 2026, 40.0, true);
        assertThat(EpsSeries.crossCheck(q, 2026, true, 41.9).passed()).isTrue();       // +4.75%
        var bad = EpsSeries.crossCheck(q, 2026, true, 43.0);                            // +7.5%
        assertThat(bad.passed()).isFalse();
        assertThat(bad.reason()).isEqualTo(ValuationReading.Reason.EPS_CROSSCHECK_FAILED);
        assertThat(bad.reasonText()).contains("43.00").contains("40.00");
    }

    @Test
    @DisplayName("G9: quarters on another basis, or fewer than four, are unverifiable - never waved through")
    void crossCheckUnverifiable() {
        var otherBasis = ValuationFixtures.quarters("A", 2026, 40.0, false);
        assertThat(EpsSeries.crossCheck(otherBasis, 2026, true, 40.0).reason())
                .isEqualTo(ValuationReading.Reason.EPS_UNVERIFIABLE);
        var unstated = ValuationFixtures.quarters("A", 2026, 40.0, null);
        assertThat(EpsSeries.crossCheck(unstated, 2026, true, 40.0).reason())
                .isEqualTo(ValuationReading.Reason.EPS_UNVERIFIABLE);
        var three = ValuationFixtures.quarters("A", 2026, 40.0, true).subList(0, 3);
        assertThat(EpsSeries.crossCheck(three, 2026, true, 40.0).reason())
                .isEqualTo(ValuationReading.Reason.EPS_UNVERIFIABLE);
        assertThat(EpsSeries.crossCheck(List.of(), 2026, true, 40.0).reason())
                .isEqualTo(ValuationReading.Reason.EPS_UNVERIFIABLE);
    }

    @Test
    @DisplayName("G9: a missing quarterly EPS is unverifiable, and quarters of another year are ignored")
    void crossCheckMissingEps() {
        var q = new ArrayList<>(ValuationFixtures.quarters("A", 2026, 40.0, true));
        q.get(1).setEps(null);
        assertThat(EpsSeries.crossCheck(q, 2026, true, 40.0).reason())
                .isEqualTo(ValuationReading.Reason.EPS_UNVERIFIABLE);
        var wrongYear = ValuationFixtures.quarters("A", 2025, 40.0, true);
        assertThat(EpsSeries.crossCheck(wrongYear, 2026, true, 40.0).reason())
                .isEqualTo(ValuationReading.Reason.EPS_UNVERIFIABLE);
    }

    @Test
    @DisplayName("G9: a revised duplicate of a quarter does not double-count it - the newer one stands")
    void crossCheckDuplicateQuarter() {
        List<QuarterlyResultEntity> q = new ArrayList<>(ValuationFixtures.quarters("A", 2026, 40.0, true));
        QuarterlyResultEntity old = QuarterlyResultEntity.builder().symbol("A").quarterEnd(q.get(0).getQuarterEnd())
                .eps(99.0).consolidated(true).availableFrom(q.get(0).getAvailableFrom().minusDays(30)).build();
        q.add(old);
        assertThat(EpsSeries.crossCheck(q, 2026, true, 40.0).passed()).isTrue();
    }
}
