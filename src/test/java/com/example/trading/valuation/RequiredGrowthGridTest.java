package com.example.trading.valuation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** SPEC §57.2 block E: a closed form, checked by plugging the answer back in. */
class RequiredGrowthGridTest {

    private static final double T = FiscalHorizon.yearsFrom(LocalDate.of(2026, 10, 13));

    @Test
    @DisplayName("round trip: growing E0 at g_req for n years and paying N times reproduces price x (1+r)^T")
    void roundTrip() {
        double price = 800, e0 = 40;
        int n = 5;
        for (int multiple : new int[]{13, 25, 35}) {
            double g = RequiredGrowthGrid.requiredGrowth(price, e0, n, T, 0.12, multiple);
            double endValue = multiple * e0 * Math.pow(1 + g, n);
            assertThat(endValue).isCloseTo(price * Math.pow(1.12, T), within(1e-6));
        }
    }

    @Test
    @DisplayName("a higher reference multiple needs less growth; a higher hurdle needs more")
    void monotone() {
        double lo = RequiredGrowthGrid.requiredGrowth(800, 40, 5, T, 0.12, 13);
        double mid = RequiredGrowthGrid.requiredGrowth(800, 40, 5, T, 0.12, 25);
        double hi = RequiredGrowthGrid.requiredGrowth(800, 40, 5, T, 0.12, 35);
        assertThat(lo).isGreaterThan(mid);
        assertThat(mid).isGreaterThan(hi);
        double r10 = RequiredGrowthGrid.requiredGrowth(800, 40, 5, T, 0.10, 25);
        double r14 = RequiredGrowthGrid.requiredGrowth(800, 40, 5, T, 0.14, 25);
        assertThat(r10).isLessThan(mid);
        assertThat(r14).isGreaterThan(mid);
    }

    @Test
    @DisplayName("the grid has one row per reference multiple with the 10% and 14% sensitivity attached")
    void gridShape() {
        var g = RequiredGrowthGrid.grid(800, 40, 5, T, 15.0);
        assertThat(g.rows()).extracting(RequiredGrowthGrid.GridRow::referenceMultiple).containsExactly(13, 25, 35);
        for (var row : g.rows()) {
            assertThat(row.atDiscount()).extracting(RequiredGrowthGrid.RateRow::ratePct)
                    .containsExactly(10.0, 14.0);
            assertThat(row.atDiscount().get(0).requiredGrowthPct()).isLessThan(row.requiredGrowthPct());
            assertThat(row.atDiscount().get(1).requiredGrowthPct()).isGreaterThan(row.requiredGrowthPct());
        }
        assertThat(g.limits()).isEqualTo(ValuationGapRules.MULTIPLES_LIMITS);
        assertThat(g.methodology().terms()).anyMatch(t -> t.contains("closed form"));
    }

    @Test
    @DisplayName("the gap to trend is a number in points, and is absent when the trend is withheld")
    void gapToTrend() {
        var with = RequiredGrowthGrid.grid(800, 40, 5, T, 15.0);
        for (var row : with.rows()) {
            assertThat(row.gapToTrendPp()).isCloseTo(row.requiredGrowthPct() - 15.0, within(1e-9));
        }
        var without = RequiredGrowthGrid.grid(800, 40, 5, T, null);
        for (var row : without.rows()) assertThat(row.gapToTrendPp()).isNull();
    }

    @Test
    @DisplayName("negative required growth is shown with its plain-English reading, not hidden")
    void negativeRequiredGrowth() {
        // A very cheap stock against 35x: the price needs earnings to fall and still earn the hurdle.
        var g = RequiredGrowthGrid.grid(100, 40, 5, T, null);
        var at35 = g.rows().get(2);
        assertThat(at35.requiredGrowthPct()).isNegative();
        assertThat(at35.note()).contains("could shrink");
        // A positive requirement carries no note.
        assertThat(RequiredGrowthGrid.grid(800, 40, 5, T, null).rows().get(0).note()).isNull();
    }

    @Test
    @DisplayName("the closed form needs only price, E0, n and T: no trend and no reverse-DCF input")
    void inputsAreMinimal() {
        double a = RequiredGrowthGrid.requiredGrowth(800, 40, 5, T, 0.12, 25);
        double b = RequiredGrowthGrid.grid(800, 40, 5, T, null).rows().get(1).requiredGrowthPct() / 100.0;
        assertThat(b).isCloseTo(a, within(1e-12));
    }
}
