package com.example.trading.valuation;

import com.example.trading.earnings.QuarterlyResultEntity;
import com.example.trading.fundamentals.AnnualFundamentalsEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A zero-variance guard on real inputs (SPEC §57.8, B-113's rule): <i>run a new verdict over real
 * rows and look at the spread before shipping it</i>.
 *
 * <p>The fixture is the frozen inputs of the 97 stocks the lens could read on 2026-10-13, exported
 * read-only from {@code tradingdb}. Five measurements in this codebase once read the same value for
 * every stock for months (Institutional Interest at 40, monthly RSI at 50.0, Insider Pulse on no
 * stock at all, the §49.13 plausibility verdict, Institutional Interest again at 50 - B-168) and each
 * was invisible because nothing threw. If a future edit made every required growth equal, or every
 * trend gate fail, this is the test that says so.
 *
 * <p><b>What it is not.</b> It does not say any figure matches a filing; that is the one thing this
 * lens cannot check about itself (SPEC §57.10, U8). If the gates change on purpose and the count of
 * readable stocks moves, update the expected count here deliberately and say why in the commit.
 */
class FrozenEligibleFixtureTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 13);

    private static List<ValuationReading> readings;

    @BeforeAll
    static void load() throws IOException {
        Map<String, String[]> scores = new LinkedHashMap<>();
        Map<String, List<AnnualFundamentalsEntity>> annual = new LinkedHashMap<>();
        Map<String, List<QuarterlyResultEntity>> quarters = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of("src/test/resources/valuation/eligible-inputs-2026-10-13.csv"))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\\|", -1);
            switch (f[0]) {
                case "S" -> scores.put(f[1], f);
                case "A" -> annual.computeIfAbsent(f[1], k -> new ArrayList<>()).add(
                        AnnualFundamentalsEntity.builder().symbol(f[1]).fiscalYear(Integer.parseInt(f[2]))
                                .netProfit(d(f[3])).shareCount(d(f[4])).faceValue(d(f[5]))
                                .consolidated("t".equals(f[6])).source("XBRL").build());
                case "Q" -> quarters.computeIfAbsent(base(f[1]), k -> new ArrayList<>()).add(
                        QuarterlyResultEntity.builder().symbol(f[1]).quarterEnd(LocalDate.parse(f[2])).eps(d(f[3]))
                                .consolidated("t".equals(f[4])).availableFrom(LocalDate.parse(f[5])).build());
                default -> throw new IllegalStateException("unknown fixture row " + f[0]);
            }
        }
        readings = new ArrayList<>();
        for (var e : scores.entrySet()) {
            String[] s = e.getValue();
            String b = base(e.getKey());
            String annualSymbol = annual.keySet().stream().filter(k -> base(k).equals(b)).findFirst().orElse(null);
            var facts = new ValuationGapService.ScoreFacts(e.getKey(), DATE, Double.parseDouble(s[2]), d(s[3]), s[4],
                    "null".equals(s[5]) ? null : s[5], d(s[6]), s[7]);
            readings.add(ValuationGapService.assemble(new ValuationGapService.Inputs(e.getKey(), annualSymbol, DATE,
                    facts, annualSymbol == null ? List.of() : annual.get(annualSymbol),
                    quarters.getOrDefault(b, List.of()), false)));
        }
    }

    private static Double d(String s) {
        return s == null || s.isBlank() || s.equals("null") ? null : Double.valueOf(s);
    }

    private static String base(String sym) {
        int i = sym.indexOf(':');
        return i >= 0 ? sym.substring(i + 1) : sym;
    }

    private static List<Double> values(java.util.function.Function<ValuationReading, Double> f) {
        return readings.stream().map(f).filter(java.util.Objects::nonNull).collect(Collectors.toList());
    }

    private static double sd(List<Double> v) {
        double m = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return Math.sqrt(v.stream().mapToDouble(x -> (x - m) * (x - m)).average().orElse(0));
    }

    private static int distinct(List<Double> v) {
        Set<String> s = new HashSet<>();
        for (Double x : v) s.add(String.format("%.3f", x));
        return s.size();
    }

    @Test
    @DisplayName("every frozen stock is still readable: a gate change that moves eligibility must be deliberate")
    void everyFrozenStockIsReadable() {
        assertThat(readings).hasSize(97);
        assertThat(readings).allSatisfy(r -> assertThat(r.status()).isNotEqualTo(ValuationReading.Status.UNAVAILABLE));
        assertThat(readings.stream().filter(r -> r.status() == ValuationReading.Status.MEASURED).count())
                .isBetween(40L, 55L);
    }

    @Test
    @DisplayName("trend growth, standard error, R-squared and PE0 vary across stocks: none is a constant")
    void trendFiguresVary() {
        var g = values(r -> r.earnings().trend().growthPct());
        var se = values(r -> r.earnings().trend().stdErrPp());
        var r2 = values(r -> r.earnings().trend().rSquared());
        var pe = values(r -> r.trendComparison().pe0());
        for (var v : List.of(g, se, r2, pe)) {
            assertThat(v.size()).isGreaterThan(90);
            assertThat(distinct(v)).isGreaterThan(80);
            assertThat(sd(v)).isGreaterThan(0.1);
        }
        assertThat(sd(g)).isGreaterThan(5.0);          // growth rates genuinely differ across businesses
        assertThat(sd(pe)).isGreaterThan(5.0);
    }

    @Test
    @DisplayName("required growth at every reference multiple varies, is monotone in the multiple, and is not degenerate")
    void gridVaries() {
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            var g = values(r -> r.requiredGrowthGrid().rows().get(idx).requiredGrowthPct());
            assertThat(distinct(g)).as("multiple #%d", i).isGreaterThan(80);
            assertThat(sd(g)).isGreaterThan(5.0);
        }
        for (var r : readings) {
            var rows = r.requiredGrowthGrid().rows();
            assertThat(rows.get(0).requiredGrowthPct()).isGreaterThan(rows.get(1).requiredGrowthPct());
            assertThat(rows.get(1).requiredGrowthPct()).isGreaterThan(rows.get(2).requiredGrowthPct());
        }
        // Not saturated: the 13x column is not above the reverse-DCF reading for every stock (round 2 measured
        // the opposite of that when 13x was judged against a different quantity - this checks the grid itself).
        long negatives = readings.stream().filter(r -> r.requiredGrowthGrid().rows().get(2).requiredGrowthPct() < 0).count();
        assertThat(negatives).isBetween(1L, 40L);
    }

    @Test
    @DisplayName("the reliability gates separate stocks: neither every stock nor none is withheld")
    void gatesSeparate() {
        long withheld = readings.stream().filter(r -> r.reasonCode() == ValuationReading.Reason.TREND_UNRELIABLE).count();
        assertThat(withheld).isBetween(20L, 80L);
        long cohortPass = readings.stream().filter(r -> r.capture().cohort() == ValuationReading.TrendCohort.GATES_PASSED).count();
        long cohortHeld = readings.stream().filter(r -> r.capture().cohort() == ValuationReading.TrendCohort.GATES_WITHHELD).count();
        assertThat(cohortPass).isGreaterThan(20);
        assertThat(cohortHeld).isGreaterThan(20);
        assertThat(cohortPass + cohortHeld).isGreaterThanOrEqualTo(95);
    }

    @Test
    @DisplayName("every fit used five to ten years, and the stored fit reproduces the stated growth")
    void fitsAreWellFormed() {
        for (var r : readings) {
            var t = r.earnings().trend();
            assertThat(t.observations()).isBetween(5, 10);
            assertThat(t.lastFy()).isGreaterThanOrEqualTo(2026);
            assertThat(Math.exp(r.capture().trendLnSlope()) - 1).isCloseTo(t.growthPct() / 100.0, within(1e-9));
            assertThat(r.capture().normalisedEps()).isPositive();
            assertThat(r.capture().pe0()).isPositive();
        }
    }

    @Test
    @DisplayName("the reverse-DCF card equals the stored column for every stock that has one, and the grid ignores it")
    void cardFIsStored() {
        long withF = readings.stream().filter(r -> r.section125Reading() != null).count();
        assertThat(withF).isGreaterThan(90);
        for (var r : readings) {
            if (r.section125Reading() != null) {
                assertThat(r.section125Reading().impliedGrowthPct()).isEqualTo(r.capture().dcfImpliedGrowthPct());
            }
        }
    }

    @Test
    @DisplayName("the latest year agrees with the reported quarters for every frozen stock (G9 passed)")
    void crossChecksAgree() {
        for (var r : readings) {
            assertThat(r.earnings().crossCheck().passed()).isTrue();
            assertThat(Math.abs(r.earnings().crossCheck().ratio() - 1.0)).isLessThanOrEqualTo(0.05);
        }
    }
}
