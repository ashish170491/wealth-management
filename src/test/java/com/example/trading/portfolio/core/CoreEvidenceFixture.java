package com.example.trading.portfolio.core;

import com.example.trading.portfolio.core.CoreDto.AnnualYear;
import com.example.trading.portfolio.core.CoreDto.HoldingEvidence;
import com.example.trading.portfolio.core.CoreDto.PricePoint;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable builder for the {@link HoldingEvidence} record, so a test can say what it is actually
 * about ("G4 has no data") instead of listing twenty-seven nulls to get there.
 *
 * <p>Defaults describe a stock that passes every measurable gate. Each test then breaks exactly
 * one thing, which is what makes a failure point at the rule that changed.
 */
final class CoreEvidenceFixture {

    String symbol = "NSE:ACME";
    String tradingSymbol = "ACME";
    String isin = "INE000A01001";
    String industry = "Consumer Goods";

    LocalDate scoreDate = LocalDate.of(2026, 8, 25);
    Integer compositeScore = 72;
    String capitalEfficiencyVerdict = "HIGH_QUALITY_COMPOUNDER";
    Double rocePercent = 24.0;
    Double roePercent = 21.0;
    Double roaPercent = 12.0;
    String financialQualityVerdict = "HIGH_QUALITY";
    Double interestCoverage = 12.0;
    Integer earningsConsistencyScore = 78;
    String insiderPulseVerdict = "NEUTRAL";
    String turnaroundVerdict = null;
    String capexVerdict = null;

    Boolean forensicMeasured = Boolean.TRUE;
    Integer forensicFlagCount = 0;
    String forensicSummary = null;

    String decayVerdict = "INTACT";
    Double purchaseDriftPoints = 3.0;
    Boolean convictionRecordExists = Boolean.TRUE;
    Integer holdingHorizonMonths = 60;
    Boolean horizonStated = Boolean.TRUE;
    String coreOverride = null;

    List<AnnualYear> annualHistory = new ArrayList<>();
    List<PricePoint> dailyCloses = new ArrayList<>();
    List<String> missingInputs = new ArrayList<>();

    HoldingEvidence build() {
        return new HoldingEvidence(symbol, tradingSymbol, isin, industry,
                scoreDate, compositeScore, capitalEfficiencyVerdict, rocePercent, roePercent,
                roaPercent, financialQualityVerdict, interestCoverage, earningsConsistencyScore,
                insiderPulseVerdict, turnaroundVerdict, capexVerdict,
                forensicMeasured, forensicFlagCount, forensicSummary,
                decayVerdict, purchaseDriftPoints, convictionRecordExists, holdingHorizonMonths,
                horizonStated, coreOverride, annualHistory, dailyCloses, missingInputs);
    }

    /** A year of annual fundamentals; nulls are what the caller wants unmeasured. */
    static AnnualYear year(int fy, Double sales, Double profit, Double interest,
                           Double borrowings, Double equity, Double cfo, Double shares) {
        return new AnnualYear(fy, sales, profit, interest, borrowings, equity, null, cfo, shares);
    }

    /** A flat price series of {@code sessions} closes ending today — no drawdown, no growth. */
    static List<PricePoint> flatSeries(int sessions, double price, LocalDate lastDay) {
        List<PricePoint> out = new ArrayList<>(sessions);
        LocalDate d = lastDay.minusDays((long) sessions * 7 / 5);
        for (int i = 0; i < sessions; i++) {
            out.add(new PricePoint(d, price));
            d = d.plusDays(1);
        }
        return out;
    }
}
