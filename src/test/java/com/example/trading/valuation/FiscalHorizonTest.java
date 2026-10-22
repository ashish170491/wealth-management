package com.example.trading.valuation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** SPEC §57.2: the fixed reference year and the arithmetic of how far away it is. */
class FiscalHorizonTest {

    @Test
    @DisplayName("T is 4.4627 years from 2026-10-13 to 31 March 2031 (1,630 days)")
    void yearsFromTheDesignDate() {
        LocalDate d = LocalDate.of(2026, 10, 13);
        assertThat(FiscalHorizon.runwayDays(d)).isEqualTo(1630);
        assertThat(FiscalHorizon.yearsFrom(d)).isCloseTo(4.4627, within(0.0001));
    }

    @Test
    @DisplayName("growth years are counted in whole fiscal years from the base year")
    void growthYears() {
        assertThat(FiscalHorizon.growthYears(2026)).isEqualTo(5);
        assertThat(FiscalHorizon.growthYears(2025)).isEqualTo(6);
    }

    @Test
    @DisplayName("the lens retires itself from 2030-10-03: fewer than 180 days remain")
    void runwayBoundary() {
        assertThat(FiscalHorizon.hasRunway(LocalDate.of(2030, 10, 1))).isTrue();
        assertThat(FiscalHorizon.hasRunway(LocalDate.of(2030, 10, 2))).isTrue();      // exactly 180 days
        assertThat(FiscalHorizon.hasRunway(LocalDate.of(2030, 10, 3))).isFalse();
    }

    @Test
    @DisplayName("a leap day moves the year count by a day, not by a year")
    void leapDay() {
        double before = FiscalHorizon.yearsFrom(LocalDate.of(2028, 2, 28));
        double after = FiscalHorizon.yearsFrom(LocalDate.of(2028, 3, 1));
        assertThat(before - after).isCloseTo(2 / 365.25, within(1e-9));
    }

    @Test
    @DisplayName("required last year: autumn needs the year that ended last March")
    void requiredLastYearAutumn() {
        assertThat(FiscalHorizon.requiredLastFiscalYear(LocalDate.of(2026, 10, 13))).isEqualTo(2026);
    }

    @Test
    @DisplayName("required last year: April-June accepts one year older, the filing may not have landed")
    void requiredLastYearFilingSeason() {
        assertThat(FiscalHorizon.requiredLastFiscalYear(LocalDate.of(2026, 6, 30))).isEqualTo(2025);
        assertThat(FiscalHorizon.requiredLastFiscalYear(LocalDate.of(2026, 7, 1))).isEqualTo(2026);
        assertThat(FiscalHorizon.requiredLastFiscalYear(LocalDate.of(2026, 4, 1))).isEqualTo(2025);
    }

    @Test
    @DisplayName("required last year: January-March does not relax; last March's year has been public for months")
    void requiredLastYearWinterIsNotRelaxed() {
        assertThat(FiscalHorizon.requiredLastFiscalYear(LocalDate.of(2027, 2, 10))).isEqualTo(2026);
        assertThat(FiscalHorizon.requiredLastFiscalYear(LocalDate.of(2027, 3, 31))).isEqualTo(2026);
    }
}
