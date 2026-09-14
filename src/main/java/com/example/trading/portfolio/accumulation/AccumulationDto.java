package com.example.trading.portfolio.accumulation;

import java.time.LocalDate;
import java.util.List;

/** Accumulation-planner DTOs (SPEC.md §8). */
public final class AccumulationDto {

    private AccumulationDto() {}

    public record CreatePlanRequest(
            String symbol,
            double targetAmount,
            int tranchesCount,
            String mode,                   // SIP | PRICE_LADDER (SIGNAL_GATED is refused, B-077)
            LocalDate startDate,
            LocalDate endDate,             // for SIP
            Double initialPrice,           // for PRICE_LADDER (step below this)
            Double priceStepPercent,       // for PRICE_LADDER, e.g. 3.0 = -3% per tranche
            String signalName,             // legacy SIGNAL_GATED field; ignored, mode is refused
            String notes
    ) {}

    public record TrancheView(
            Long id,
            int trancheNumber,
            double amount,
            LocalDate triggerDate,
            Double triggerPrice,
            String triggerSignal,
            String status,
            Integer filledQuantity,
            Double filledPrice,
            LocalDate filledDate
    ) {}

    public record PlanView(
            Long id,
            String symbol,
            double targetAmount,
            int tranchesCount,
            String mode,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            double filledAmount,
            int filledQuantity,
            Double averageCost,
            double progressPercent,
            List<TrancheView> tranches
    ) {}

    public record FillTrancheRequest(
            int quantity,
            double price,
            LocalDate fillDate
    ) {}
}
