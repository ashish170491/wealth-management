package com.example.trading.portfolio.core;

import com.example.trading.portfolio.core.CoreDto.Durability;
import com.example.trading.portfolio.core.CoreDto.DurabilityComponent;
import com.example.trading.portfolio.core.CoreDto.PricePoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.example.trading.portfolio.core.CoreEvidenceFixture.year;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The five durability components, their renormalisation, and the rule that keeps the current
 * drawdown out of the score (SPEC §35.3).
 */
class DurabilityScoreTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

    private final CoreHoldingConfig config = new CoreHoldingConfig();
    private final DurabilityScorer scorer = new DurabilityScorer(config);

    private static CoreEvidenceFixture withFiveGoodYears() {
        CoreEvidenceFixture f = new CoreEvidenceFixture();
        f.annualHistory = new ArrayList<>(List.of(
                year(2021, 1000.0, 150.0, 10.0, 200.0, 800.0, 165.0, 50.0),
                year(2022, 1120.0, 170.0, 10.0, 200.0, 800.0, 185.0, 50.0),
                year(2023, 1250.0, 195.0, 10.0, 200.0, 800.0, 210.0, 50.0),
                year(2024, 1380.0, 225.0, 10.0, 200.0, 800.0, 245.0, 50.0),
                year(2025, 1500.0, 260.0, 10.0, 200.0, 800.0, 285.0, 50.0)));
        return f;
    }

    private static DurabilityComponent component(Durability d, String code) {
        return d.components().stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ renormalisation

    @Test
    @DisplayName("Fewer than three measured components means no score at all, never a low one")
    void thinDataScoresNull() {
        CoreEvidenceFixture f = new CoreEvidenceFixture();   // no history, no prices
        Durability d = scorer.score(f.build(), TODAY);

        // Only D2 can be measured from a screening row alone.
        assertThat(d.components().stream().filter(DurabilityComponent::measured)).hasSize(1);
        assertThat(d.score()).isNull();
        assertThat(d.coverage()).contains("1 of 5 components measured");
        assertThat(d.coverage()).contains("import this stock's annual history");
    }

    @Test
    @DisplayName("The score is out of the components that were measured, not out of five")
    void renormalisesOverMeasuredComponents() {
        CoreEvidenceFixture f = withFiveGoodYears();          // D1-D4 measurable, D5 is not
        Durability d = scorer.score(f.build(), TODAY);

        List<DurabilityComponent> measured =
                d.components().stream().filter(DurabilityComponent::measured).toList();
        assertThat(measured).hasSize(4);
        assertThat(component(d, "D5").measured()).isFalse();

        int earned = measured.stream().mapToInt(DurabilityComponent::points).sum();
        assertThat(d.score()).isEqualTo(Math.round(100.0f * earned / (20 * 4)));
        assertThat(d.coverage()).contains("4 of 5 components measured");
    }

    // ------------------------------------------------------------------ individual components

    @Test
    @DisplayName("D1 counts years above the bar, four points each, and needs four of them")
    void d1CountsQualifyingYears() {
        CoreEvidenceFixture f = withFiveGoodYears();
        assertThat(scorer.d1ReturnOnCapital(f.annualHistory, false).points()).isEqualTo(20);

        // Three years is not enough evidence to call return-on-capital persistent.
        f.annualHistory = new ArrayList<>(f.annualHistory.subList(2, 5));
        DurabilityComponent thin = scorer.d1ReturnOnCapital(f.annualHistory, false);
        assertThat(thin.measured()).isFalse();
        assertThat(thin.note()).contains("needs 4 years");
    }

    @Test
    @DisplayName("D3 is unmeasured — not zero — when debt-to-equity cannot be computed at both ends")
    void d3UnmeasuredWithoutLeverage() {
        CoreEvidenceFixture f = withFiveGoodYears();
        assertThat(scorer.d3BalanceSheet(f.annualHistory).points()).isEqualTo(15);

        List<CoreDto.AnnualYear> noEquity = new ArrayList<>(List.of(
                year(2023, 1250.0, 195.0, 10.0, 200.0, null, 210.0, 50.0),
                year(2024, 1380.0, 225.0, 10.0, 200.0, 800.0, 245.0, 50.0),
                year(2025, 1500.0, 260.0, 10.0, 200.0, 800.0, 285.0, 50.0)));
        DurabilityComponent d3 = scorer.d3BalanceSheet(noEquity);
        assertThat(d3.measured()).isFalse();
        assertThat(d3.note()).contains("not computable");
    }

    @Test
    @DisplayName("D4 refuses to compute a cash-conversion ratio against a cumulative loss")
    void d4UndefinedOnLosses() {
        List<CoreDto.AnnualYear> losses = new ArrayList<>(List.of(
                year(2023, 1250.0, -80.0, 10.0, 200.0, 800.0, -20.0, 50.0),
                year(2024, 1380.0, -60.0, 10.0, 200.0, 800.0, -10.0, 50.0),
                year(2025, 1500.0, 20.0, 10.0, 200.0, 800.0, 30.0, 50.0)));
        DurabilityComponent d4 = scorer.d4CashDiscipline(losses);
        assertThat(d4.measured()).isFalse();
        assertThat(d4.note()).contains("undefined");
    }

    // ------------------------------------------------------------------ D5, the R-3 rule

    /**
     * The finding that made this component be rewritten: the original scored a stock on whether it
     * was near its high, so a core holding mid-drawdown was downgraded at exactly the moment the
     * tier exists to hold it. Deepening the <em>open</em> fall must not move the score by a point.
     */
    @Test
    @DisplayName("R-3: the drawdown a holding is in today is described, never scored")
    void openDrawdownIsExcludedFromTheScore() {
        List<PricePoint> shallow = syntheticPath(85.0);
        List<PricePoint> deep = syntheticPath(55.0);

        DurabilityComponent a = scorer.d5HiccupRecovery(shallow, TODAY);
        DurabilityComponent b = scorer.d5HiccupRecovery(deep, TODAY);

        assertThat(a.measured()).isTrue();
        assertThat(a.points())
                .as("a deeper open drawdown must not change the durability score")
                .isEqualTo(b.points());
        assertThat(b.note()).contains("not scored");
        assertThat(b.note()).contains("below its high");
    }

    @Test
    @DisplayName("D5 scores the two closed episodes: one fast recovery, one slow")
    void closedEpisodesAreScored() {
        DurabilityComponent d5 = scorer.d5HiccupRecovery(syntheticPath(85.0), TODAY);
        // 5 for the recovery inside 12 months, 3 for the one inside 24. The price CAGR is
        // negative over this path, so the trend point is not awarded.
        assertThat(d5.points()).isEqualTo(8);
        assertThat(d5.note()).contains("2 past fall(s)");
    }

    @Test
    @DisplayName("A recent recovery is not yet evidence — episodes must have closed 18 months ago")
    void recentlyClosedEpisodeDoesNotScoreYet() {
        List<PricePoint> path = syntheticPath(85.0);
        // Pretend today is shortly after the second recovery: both episodes are now too recent.
        LocalDate justAfterSecondRecovery = LocalDate.of(2024, 10, 1);
        DurabilityComponent early = scorer.d5HiccupRecovery(path, justAfterSecondRecovery);
        assertThat(early.points()).isLessThan(8);
    }

    @Test
    @DisplayName("Under about three years of prices D5 is unmeasured, not zero")
    void shortPriceHistoryIsUnmeasured() {
        List<PricePoint> shortSeries = CoreEvidenceFixture.flatSeries(200, 100.0, TODAY);
        DurabilityComponent d5 = scorer.d5HiccupRecovery(shortSeries, TODAY);
        assertThat(d5.measured()).isFalse();
        assertThat(d5.note()).contains("3 years of daily prices");
    }

    @Test
    @DisplayName("Drawdown detection closes an episode when the prior peak is regained")
    void drawdownEpisodeDetection() {
        DurabilityScorer.DrawdownHistory dd =
                DurabilityScorer.findDrawdowns(syntheticPath(85.0), 15.0);
        assertThat(dd.closed).hasSize(2);
        assertThat(dd.open).isNotNull();
        assertThat(dd.open.recoveredOn()).isNull();
    }

    // ------------------------------------------------------------------ fixture

    /**
     * A five-year daily path with two completed falls (one recovered in about five months, one in
     * about fifteen) and one still open at the end. {@code endValue} sets how deep the open fall is
     * — the whole point of the R-3 test is that changing it changes nothing.
     */
    private static List<PricePoint> syntheticPath(double endValue) {
        List<Double> v = new ArrayList<>();
        flat(v, 100.0, 200);
        ramp(v, 100.0, 80.0, 60);      // episode 1 begins
        ramp(v, 80.0, 106.0, 140);     // and closes about five months after it began
        flat(v, 106.0, 200);
        ramp(v, 106.0, 80.0, 100);     // episode 2 begins
        ramp(v, 80.0, 110.0, 500);     // and closes about fifteen months after it began
        flat(v, 110.0, 300);
        ramp(v, 110.0, endValue, 327); // still open today

        LocalDate d = TODAY.minusDays(v.size() - 1L);
        List<PricePoint> out = new ArrayList<>(v.size());
        for (Double close : v) {
            out.add(new PricePoint(d, close));
            d = d.plusDays(1);
        }
        return out;
    }

    private static void flat(List<Double> v, double value, int days) {
        for (int i = 0; i < days; i++) v.add(value);
    }

    private static void ramp(List<Double> v, double from, double to, int days) {
        for (int i = 1; i <= days; i++) v.add(from + (to - from) * i / days);
    }
}
