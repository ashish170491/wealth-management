package com.example.trading.multibagger;

import com.example.trading.multibagger.SectorStanding.Metric;
import com.example.trading.multibagger.SectorStanding.MetricReading;
import com.example.trading.multibagger.SectorStanding.Quartile;
import com.example.trading.multibagger.SectorStanding.Result;
import com.example.trading.multibagger.SectorStanding.Row;
import com.example.trading.multibagger.SectorStanding.Status;
import com.example.trading.multibagger.SectorStanding.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the sector-relative lens (SPEC §54). The cases that matter most are the refusals: a figure
 * the stock does not have is never "bottom of the sector", a peer group too small to cut into
 * quarters is never cut into quarters, and lenders are never ranked on a non-lender's yardstick.
 */
class SectorStandingTest {

    /** A non-lender with every figure set to the same value v, in sector s. */
    private static Row plain(String symbol, String sector, double v) {
        return new Row(symbol, sector, false, v, null, v, v / 10.0, 1.0 / (v + 1), (int) v);
    }

    private static Row lender(String symbol, String sector, double roa, double roe, int steady) {
        return new Row(symbol, sector, true, null, roa, roe, null, null, steady);
    }

    /** n non-lenders in one sector with values 1..n. */
    private static List<Row> ladder(String sector, int n) {
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i <= n; i++) rows.add(plain("NSE:S" + i, sector, i));
        return rows;
    }

    private static MetricReading reading(Result r, Metric m) {
        return r.metrics().stream().filter(x -> x.metric() == m).findFirst()
                .orElseThrow(() -> new AssertionError("no reading for " + m));
    }

    @Nested
    @DisplayName("Quartiles")
    class Quartiles {

        @Test
        @DisplayName("The best of twenty is top quarter, the worst is bottom quarter, the median sits between")
        void boundaries() {
            List<Row> rows = ladder("AUTO", 20);
            var cs = SectorStanding.crossSection(rows);
            assertThat(reading(SectorStanding.evaluate(rows.get(19), cs), Metric.ROCE).quartile())
                    .isEqualTo(Quartile.TOP_QUARTILE);
            assertThat(reading(SectorStanding.evaluate(rows.get(0), cs), Metric.ROCE).quartile())
                    .isEqualTo(Quartile.BOTTOM_QUARTILE);
            assertThat(reading(SectorStanding.evaluate(rows.get(9), cs), Metric.ROCE).quartile())
                    .isEqualTo(Quartile.LOWER_MIDDLE);
            assertThat(reading(SectorStanding.evaluate(rows.get(10), cs), Metric.ROCE).quartile())
                    .isEqualTo(Quartile.UPPER_MIDDLE);
        }

        @Test
        @DisplayName("A tied group sits in the middle of its tie - seven debt-free IT companies read top quarter")
        void tiesUseMidRank() {
            // The live IT distribution on 2026-10-11: seven companies at exactly 0.0 debt. Counting
            // only peers strictly worse read them "upper middle" (14 of 20 = 0.70) - B-138. This
            // case gives different answers under the two rules, which the first test did not.
            double[] it = {0, 0, 0, 0, 0, 0, 0, 0.002, 0.002, 0.003, 0.027, 0.041, 0.073, 0.087,
                           0.092, 0.130, 0.141, 0.167, 0.190, 0.376, 0.643};
            assertThat(SectorStanding.quartile(0.0, it, false)).isEqualTo(Quartile.TOP_QUARTILE);
        }

        @Test
        @DisplayName("A floor most of the sector shares is still the bottom quarter - ties are not lifted to the better side")
        void tiedFloorStaysBottom() {
            // 12 of 40 capital-goods companies tied at a steadiness of 0. "Ties share the better
            // quartile" would read them lower middle and flatter exactly the erratic businesses.
            double[] peers = new double[40];
            for (int i = 12; i < 40; i++) peers[i] = i;
            assertThat(SectorStanding.quartile(0, peers, true)).isEqualTo(Quartile.BOTTOM_QUARTILE);
        }

        @Test
        @DisplayName("Debt to equity is ranked lower-is-better, so the least indebted company is top quarter")
        void debtIsInverted() {
            List<Row> rows = ladder("AUTO", 20);
            var cs = SectorStanding.crossSection(rows);
            // plain() sets D/E = 1/(v+1): the highest-v company carries the least debt.
            MetricReading best = reading(SectorStanding.evaluate(rows.get(19), cs), Metric.DEBT_TO_EQUITY);
            assertThat(best.quartile()).isEqualTo(Quartile.TOP_QUARTILE);
            assertThat(best.detail()).contains("lower is better");
        }

        @Test
        @DisplayName("The median printed is the sector's own, and the peer count includes the stock")
        void medianAndPeers() {
            List<Row> rows = ladder("AUTO", 11);
            MetricReading r = reading(SectorStanding.evaluate(rows.get(0), SectorStanding.crossSection(rows)), Metric.ROCE);
            assertThat(r.sectorMedian()).isEqualTo(6.0);
            assertThat(r.peers()).isEqualTo(11);
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @Test
        @DisplayName("Fewer than ten measured peers is refused, never cut into quarters")
        void tooFewPeers() {
            List<Row> rows = ladder("TELECOM", 9);
            Result r = SectorStanding.evaluate(rows.get(8), SectorStanding.crossSection(rows));
            assertThat(reading(r, Metric.ROCE).status()).isEqualTo(Status.TOO_FEW_PEERS);
            assertThat(reading(r, Metric.ROCE).quartile()).isNull();
            assertThat(r.verdict()).isEqualTo(Verdict.NOT_RANKED);
            assertThat(r.reason()).doesNotContainIgnoringCase("weak");
        }

        @Test
        @DisplayName("A missing figure is not measured - never bottom of the sector")
        void nullIsNotBottom() {
            List<Row> rows = ladder("AUTO", 20);
            Row missing = new Row("NSE:GAP", "AUTO", false, null, null, 5.0, 0.5, 0.2, 5);
            rows.add(missing);
            MetricReading r = reading(SectorStanding.evaluate(missing, SectorStanding.crossSection(rows)), Metric.ROCE);
            assertThat(r.status()).isEqualTo(Status.NOT_MEASURED);
            assertThat(r.quartile()).isNull();
        }

        @Test
        @DisplayName("A lender is never ranked on ROCE, leverage or cash conversion")
        void lenderGetsLenderMetricsOnly() {
            List<Row> rows = new ArrayList<>();
            for (int i = 1; i <= 12; i++) rows.add(lender("NSE:B" + i, "BANKING", i / 10.0, i, i * 5));
            Result r = SectorStanding.evaluate(rows.get(11), SectorStanding.crossSection(rows));
            assertThat(reading(r, Metric.ROCE).status()).isEqualTo(Status.NOT_APPLICABLE);
            assertThat(reading(r, Metric.DEBT_TO_EQUITY).status()).isEqualTo(Status.NOT_APPLICABLE);
            assertThat(reading(r, Metric.CASH_CONVERSION).status()).isEqualTo(Status.NOT_APPLICABLE);
            assertThat(reading(r, Metric.ROA).status()).isEqualTo(Status.RANKED);
            assertThat(r.ranked()).isEqualTo(3);
        }

        @Test
        @DisplayName("Lenders and non-lenders inside one sector never share a denominator")
        void lendersAndOthersAreSeparate() {
            List<Row> rows = ladder("INFRASTRUCTURE", 12);
            // Two lenders in the same bucket: their ROE must not be ranked among the twelve others.
            rows.add(lender("NSE:L1", "INFRASTRUCTURE", 1.0, 100, 90));
            rows.add(lender("NSE:L2", "INFRASTRUCTURE", 1.2, 110, 95));
            var cs = SectorStanding.crossSection(rows);
            assertThat(reading(SectorStanding.evaluate(rows.get(12), cs), Metric.ROE).status())
                    .isEqualTo(Status.TOO_FEW_PEERS);
            assertThat(reading(SectorStanding.evaluate(rows.get(11), cs), Metric.ROE).peers()).isEqualTo(12);
        }

        @Test
        @DisplayName("FINANCIALS lenders are ranked among themselves now that fee businesses are not lenders (B-136)")
        void financialsLendersAreRanked() {
            List<Row> rows = new ArrayList<>();
            for (int i = 1; i <= 25; i++) rows.add(lender("NSE:F" + i, "FINANCIALS", i, i * 2, i));
            Result r = SectorStanding.evaluate(rows.get(0), SectorStanding.crossSection(rows));
            assertThat(reading(r, Metric.ROA).status()).isEqualTo(Status.RANKED);
            assertThat(reading(r, Metric.ROA).peers()).isEqualTo(25);
        }

        @Test
        @DisplayName("Equity over half of assets is a fee business, not a lender - the measured gap (B-136)")
        void feeFinancialIsNotALender() {
            // ROA / ROE = equity / assets. CAMS 26.1 / 35.7 = 0.73; BAJFINANCE 3.5 / 16.5 = 0.21.
            assertThat(com.example.trading.ai.NseDataService.isFeeFinancialByReturns(26.1, 35.7)).isTrue();
            assertThat(com.example.trading.ai.NseDataService.isFeeFinancialByReturns(3.5, 16.5)).isFalse();
            assertThat(com.example.trading.ai.NseDataService.isFeeFinancialByReturns(null, 16.5)).isFalse();
            assertThat(com.example.trading.ai.NseDataService.isFeeFinancial(900.0, 1000.0)).isTrue();
            assertThat(com.example.trading.ai.NseDataService.isFeeFinancial(150.0, 1000.0)).isFalse();
            var bank = new com.example.trading.ai.NseDataService.BalanceSheetData();
            bank.setBanking(true);
            bank.setEquity(900.0);
            bank.setTotalAssets(1000.0);
            assertThat(com.example.trading.ai.NseDataService.isLender(bank, "Banks")).isTrue(); // taxonomy wins
        }

        @Test
        @DisplayName("ENERGY is split: a power utility is ranked among power companies, not oil and gas")
        void energyIsSplit() {
            assertThat(SectorStanding.peerGroup("ENERGY", "Power")).isEqualTo("POWER");
            assertThat(SectorStanding.peerGroup("ENERGY", "Utilities")).isEqualTo("POWER");
            assertThat(SectorStanding.peerGroup("ENERGY", "Oil Gas & Consumable Fuels")).isEqualTo("OIL_GAS");
            assertThat(SectorStanding.peerGroup("ENERGY", "Energy")).isEqualTo("OIL_GAS");
            assertThat(SectorStanding.peerGroup("ENERGY", null)).isEqualTo("ENERGY");
            assertThat(SectorStanding.peerGroup("IT", "Power")).isEqualTo("IT");
            assertThat(SectorStanding.label("OIL_GAS")).isEqualTo("oil and gas");
        }

        @Test
        @DisplayName("An unclassified stock has no peer group and says so")
        void unclassified() {
            Row r = plain("NSE:X", null, 10);
            Result res = SectorStanding.evaluate(r, SectorStanding.crossSection(List.of(r)));
            assertThat(res.verdict()).isEqualTo(Verdict.NOT_RANKED);
            assertThat(res.metrics()).allMatch(m -> m.status() == Status.NOT_CLASSIFIED);
        }
    }

    @Nested
    @DisplayName("Summary")
    class Summary {

        @Test
        @DisplayName("The verdict counts checks in the better half - it does not average figures")
        void countsNotAverages() {
            List<Row> rows = ladder("AUTO", 20);
            // Superb ROCE (would dominate an average) but bottom-half on the other four.
            Row lopsided = new Row("NSE:ONE", "AUTO", false, 500.0, null, 1.0, 0.1, 5.0, 1);
            rows.add(lopsided);
            Result r = SectorStanding.evaluate(lopsided, SectorStanding.crossSection(rows));
            assertThat(r.above()).isEqualTo(1);
            assertThat(r.ranked()).isEqualTo(5);
            assertThat(r.verdict()).isEqualTo(Verdict.BELOW_PEERS);
        }

        @Test
        @DisplayName("Strong across the board reads ABOVE_PEERS and names the count")
        void above() {
            List<Row> rows = ladder("AUTO", 20);
            Result r = SectorStanding.evaluate(rows.get(19), SectorStanding.crossSection(rows));
            assertThat(r.verdict()).isEqualTo(Verdict.ABOVE_PEERS);
            assertThat(r.reason()).contains("5 of 5");
        }

        @Test
        @DisplayName("A three-check lender can read 'middle' - two of three is not 'above'")
        void threeChecksCanBeMiddle() {
            List<Row> rows = new ArrayList<>();
            for (int i = 1; i <= 20; i++) rows.add(lender("NSE:B" + i, "BANKING", i / 10.0, i, i));
            // Top on ROA and ROE, bottom on steadiness: 2 of 3 in the better half.
            Row mixed = lender("NSE:MIX", "BANKING", 5.0, 50, 0);
            rows.add(mixed);
            Result r = SectorStanding.evaluate(mixed, SectorStanding.crossSection(rows));
            assertThat(r.ranked()).isEqualTo(3);
            assertThat(r.above()).isEqualTo(2);
            assertThat(r.verdict()).isEqualTo(Verdict.IN_LINE);
        }

        @Test
        @DisplayName("Fewer than three ranked checks gives no summary, only the per-check rows")
        void tooFewChecks() {
            List<Row> rows = ladder("AUTO", 20);
            Row thin = new Row("NSE:THIN", "AUTO", false, 20.0, null, 20.0, null, null, null);
            rows.add(thin);
            Result r = SectorStanding.evaluate(thin, SectorStanding.crossSection(rows));
            assertThat(r.ranked()).isEqualTo(2);
            assertThat(r.verdict()).isEqualTo(Verdict.NOT_RANKED);
        }
    }

    @Nested
    @DisplayName("Beside the market-wide lens")
    class Divergence {

        private CompoundingQuality.Result compounding(CompoundingQuality.GateStatus steady,
                                                      CompoundingQuality.GateStatus capital) {
            return new CompoundingQuality.Result(CompoundingQuality.Verdict.PARTIAL, List.of(
                    new CompoundingQuality.Gate("capitalReturn", "q", capital, "d"),
                    new CompoundingQuality.Gate("steady", "q", steady, "d")), 1, 2, "r", null, null);
        }

        @Test
        @DisplayName("Failing the market-wide bar while top quarter in the sector is named - and not rescued")
        void failButTopQuarter() {
            List<Row> rows = ladder("CAPITAL_GOODS", 20);
            Result r = SectorStanding.evaluate(rows.get(19), SectorStanding.crossSection(rows),
                    compounding(CompoundingQuality.GateStatus.FAIL, CompoundingQuality.GateStatus.PASS));
            assertThat(r.divergences()).hasSize(1);
            assertThat(r.divergences().get(0))
                    .startsWith("Earnings steadiness")
                    .contains("top quarter of its 20 capital goods peers")
                    .contains("the bar stays where it is");
        }

        @Test
        @DisplayName("Clearing the bar while bottom quarter in the sector is named too")
        void passButBottomQuarter() {
            List<Row> rows = ladder("IT", 20);
            Result r = SectorStanding.evaluate(rows.get(0), SectorStanding.crossSection(rows),
                    compounding(CompoundingQuality.GateStatus.PASS, CompoundingQuality.GateStatus.PASS));
            assertThat(r.divergences()).anyMatch(s -> s.contains("bottom quarter"));
        }

        @Test
        @DisplayName("When the two readings agree, nothing is said")
        void agreementIsSilent() {
            List<Row> rows = ladder("IT", 20);
            Result r = SectorStanding.evaluate(rows.get(19), SectorStanding.crossSection(rows),
                    compounding(CompoundingQuality.GateStatus.PASS, CompoundingQuality.GateStatus.PASS));
            assertThat(r.divergences()).isEmpty();
        }

        @Test
        @DisplayName("The market-wide lens never changes a quartile or the verdict")
        void compoundingNeverMovesTheReading() {
            List<Row> rows = ladder("AUTO", 20);
            var cs = SectorStanding.crossSection(rows);
            Result without = SectorStanding.evaluate(rows.get(5), cs);
            Result with = SectorStanding.evaluate(rows.get(5), cs,
                    compounding(CompoundingQuality.GateStatus.FAIL, CompoundingQuality.GateStatus.FAIL));
            assertThat(with.verdict()).isEqualTo(without.verdict());
            assertThat(with.metrics()).isEqualTo(without.metrics());
        }
    }

    @Test
    @DisplayName("No word the lens prints is an instruction to transact")
    void vocabularyCarriesNoInstruction() {
        Pattern verbs = Pattern.compile("\\b(buy|sell|exit|add|avoid|accumulate|book)\\b", Pattern.CASE_INSENSITIVE);
        List<Row> rows = ladder("AUTO", 20);
        var cs = SectorStanding.crossSection(rows);
        List<String> text = new ArrayList<>(SectorStanding.vocabulary());
        for (Row row : rows) {
            Result r = SectorStanding.evaluate(row, cs);
            text.add(r.reason());
            r.metrics().forEach(m -> text.add(m.detail()));
        }
        text.addAll(SectorStanding.MIXED_BUCKETS.values());
        assertThat(text).noneMatch(t -> verbs.matcher(t.replace('_', ' ')).find());
    }
}
