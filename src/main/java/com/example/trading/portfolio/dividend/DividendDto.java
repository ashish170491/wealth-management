package com.example.trading.portfolio.dividend;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Dividend-tracking DTOs (SPEC.md §11). */
public final class DividendDto {

    private DividendDto() {}

    public record CreateEventRequest(
            String symbol,
            LocalDate exDate,
            LocalDate recordDate,
            double amountPerShare,
            String type,     // INTERIM | FINAL | SPECIAL
            String status,   // ANNOUNCED (default) | RECEIVED | SKIPPED
            Double totalReceived,
            String notes
    ) {}

    public record DividendView(
            Long id,
            String symbol,
            LocalDate exDate,
            LocalDate recordDate,
            double amountPerShare,
            String type,
            String status,
            Double totalReceived,
            Double projectedIncome       // amountPerShare × held quantity (when status = ANNOUNCED)
    ) {}

    public record AnnualSummary(
            int fiscalYear,
            double totalReceived,
            double totalProjected,
            int eventCount,
            Map<String, Double> receivedBySymbol
    ) {}

    public record ReinvestmentSuggestion(
            String symbol,
            double accruedDividend,
            double currentWeight,
            double targetWeight,
            double driftPp,
            String reason
    ) {}
}
