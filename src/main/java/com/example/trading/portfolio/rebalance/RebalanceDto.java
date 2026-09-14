package com.example.trading.portfolio.rebalance;

import java.time.LocalDate;
import java.util.List;

/** Rebalancing-engine DTOs (SPEC.md §10). */
public final class RebalanceDto {

    private RebalanceDto() {}

    /** One suggested trade. {@code quantity} is estimated from current price; investor places it manually. */
    public record Trade(
            String symbol,
            String action,        // BUY | SELL
            int quantity,
            double currentPrice,
            double amount,        // ₹ value of the trade
            double currentWeight,
            double targetWeight,
            double driftPp,
            String reason
    ) {}

    /** Narrative item for buckets that cannot be rebalanced via stock trades alone. */
    public record BucketHint(
            String bucketType,
            String bucketKey,
            double currentWeight,
            double targetWeight,
            double driftPp,
            String recommendation
    ) {}

    public record RebalanceProposal(
            LocalDate generatedOn,
            String profileName,
            double totalPortfolioValue,
            List<Trade> trades,
            List<BucketHint> bucketHints,
            double totalBuyValue,
            double totalSellValue,
            double estimatedCost,
            String notes
    ) {}
}
