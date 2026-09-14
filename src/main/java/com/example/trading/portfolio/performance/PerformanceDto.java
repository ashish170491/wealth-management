package com.example.trading.portfolio.performance;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Read models for SPEC §46 - portfolio truth. Every figure carries its coverage. */
public final class PerformanceDto {

    private PerformanceDto() {}

    /** One benchmark's move over the same window as the portfolio. */
    public record BenchmarkRead(
            String symbol,
            String label,
            LocalDate from,
            LocalDate to,
            Double returnPercent,          // null when either leg is missing
            Double annualisedPercent,
            int closesInWindow,
            String coverage                // plain-English reason when unmeasured
    ) {}

    /** The rebased lines for the chart: portfolio index and each benchmark, keyed by date. */
    public record IndexedPoint(LocalDate d, Double portfolio, Double nifty50, Double midcap150) {}

    public record TotalReturn(
            double unrealisedGain,
            Double realisedStcgFy,
            Double realisedLtcgFy,
            double dividendsReceivedFy,
            int dividendEventsLogged,
            Double totalReturn,            // unrealised + realised (FY) + dividends (FY)
            String note
    ) {}

    public record CashRead(
            LocalDate asOf,
            Double availableCash,
            Double netCash,
            Double cashPercentOfTotal,     // cash / (cash + holdings value)
            String note
    ) {}

    public record LotCoverage(
            int holdings,
            int holdingsWithLots,
            List<String> holdingsWithoutLots,
            String note
    ) {}

    public record PerformanceResponse(
            LocalDate from,
            LocalDate to,
            int windowDays,
            int snapshots,
            long daysSpanned,
            Double simpleGainPercent,      // the headline: unrealised P&L over cost, today
            Double twrPercent,
            Double twrAnnualisedPercent,
            int flowsCorrected,
            int flowsUncorrected,
            List<BenchmarkRead> benchmarks,
            Double excessVsNifty50Pp,
            Double excessVsMidcap150Pp,
            PerformanceMath.Drawdown drawdown,
            List<IndexedPoint> indexed,
            TotalReturn totalReturn,
            CashRead cash,
            LotCoverage lotCoverage,
            String method,
            List<String> caveats
    ) {}

    /** One portfolio-weighted fundamental with its coverage. */
    public record WeightedMetric(
            String key,
            String label,
            Double value,                  // null below the coverage floor
            double coveragePercentOfValue, // share of portfolio value the metric was measured on
            int holdingsMeasured,
            int holdingsTotal,
            String unit,
            String note
    ) {}

    public record PortfolioQualityResponse(
            LocalDate screeningDate,
            List<WeightedMetric> metrics,
            Map<String, Double> valueShareByCompounding,
            String note
    ) {}
}
