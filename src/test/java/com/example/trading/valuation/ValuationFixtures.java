package com.example.trading.valuation;

import com.example.trading.earnings.QuarterlyResultEntity;
import com.example.trading.fundamentals.AnnualFundamentalsEntity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Builders shared by the valuation-gap tests. Plain JVM: no Spring, no database. */
final class ValuationFixtures {

    private ValuationFixtures() {
    }

    static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    static AnnualFundamentalsEntity year(String symbol, int fy, Double netProfit, Double shares, Double faceValue,
                                         Boolean consolidated) {
        return AnnualFundamentalsEntity.builder()
                .symbol(symbol).fiscalYear(fy).netProfit(netProfit).shareCount(shares).faceValue(faceValue)
                .consolidated(consolidated).source("XBRL")
                .availableFrom(LocalDate.of(fy, 5, 20)).availableFromEstimated(false)
                .build();
    }

    /** {@code n} consecutive years ending {@code lastFy}; EPS grows at {@code growth} from {@code eps0}. */
    static List<AnnualFundamentalsEntity> steady(String symbol, int lastFy, int n, double eps0, double growth,
                                                 double shares) {
        List<AnnualFundamentalsEntity> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int fy = lastFy - (n - 1) + i;
            double eps = eps0 * Math.pow(1 + growth, i);
            out.add(year(symbol, fy, eps * shares, shares, 10.0, true));
        }
        return out;
    }

    /** The four quarters of {@code fy}, each carrying a quarter of {@code annualEps}. */
    static List<QuarterlyResultEntity> quarters(String symbol, int fy, double annualEps, Boolean consolidated) {
        List<QuarterlyResultEntity> out = new ArrayList<>();
        LocalDate[] ends = {LocalDate.of(fy - 1, 6, 30), LocalDate.of(fy - 1, 9, 30),
                LocalDate.of(fy - 1, 12, 31), LocalDate.of(fy, 3, 31)};
        for (LocalDate end : ends) {
            out.add(QuarterlyResultEntity.builder().symbol(symbol).quarterEnd(end).eps(annualEps / 4.0)
                    .consolidated(consolidated).availableFrom(end.plusDays(40)).build());
        }
        return out;
    }

    static ValuationGapService.ScoreFacts score(String symbol, double price, double marketCapCr, String industry) {
        return new ValuationGapService.ScoreFacts(symbol, TODAY, price, marketCapCr, industry,
                "FAIRLY_VALUED", 14.0, "v1");
    }

    /**
     * A stock that clears every gate: ten years of 15% growth with a little noise, 10 crore shares,
     * latest EPS 40. Market cap matches the share count so G7 passes.
     */
    static ValuationGapService.Inputs eligible(String symbol) {
        List<AnnualFundamentalsEntity> annual = new ArrayList<>();
        double[] noise = {1.00, 1.03, 0.97, 1.02, 0.98, 1.04, 0.99, 1.01, 1.00, 1.02};
        for (int i = 0; i < 10; i++) {
            double eps = 10.0 * Math.pow(1.15, i) * noise[i];
            annual.add(year(symbol, 2017 + i, eps * 10.0, 10.0, 10.0, true));
        }
        double latestEps = annual.get(9).getNetProfit() / 10.0;
        double price = 800.0;
        return new ValuationGapService.Inputs(symbol, symbol, TODAY,
                score(symbol, price, price * 10.0, "Consumer Durables"),
                annual, quarters(symbol, 2026, latestEps, true), false);
    }

    static ValuationGapService.Inputs withAnnual(ValuationGapService.Inputs base,
                                                  List<AnnualFundamentalsEntity> annual,
                                                  List<QuarterlyResultEntity> quarters) {
        return new ValuationGapService.Inputs(base.requested(), base.annualSymbol(), base.asOf(), base.score(),
                annual, quarters, base.naFinancial());
    }
}
