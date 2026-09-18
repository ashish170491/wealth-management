package com.example.trading.portfolio;

import java.util.List;

/**
 * API DTOs for the Portfolio Goals & Allocation module. See SPEC.md §5.
 */
public final class AllocationDto {

    private AllocationDto() {}

    /** One target-weight row in an API response. */
    public record WeightEntry(
            String bucketType,
            String bucketKey,
            double targetWeight
    ) {}

    /** Read-model for the active portfolio profile. */
    public record ProfileDto(
            Long id,
            String name,
            String description,
            boolean active,
            Double toleranceAbsolutePp,
            Double toleranceRelative,
            List<WeightEntry> targetWeights
    ) {}

    /** Request body for PUT /api/portfolio/profile — full replacement of target weights. */
    public record UpdateWeightsRequest(
            String description,
            Double toleranceAbsolutePp,
            Double toleranceRelative,
            List<WeightEntry> weights
    ) {}

    /** One bucket in the drift response. */
    public record DriftBucket(
            String bucketType,
            String bucketKey,
            double targetWeight,
            double actualWeight,
            double driftPp,
            double driftRelative,
            String alertLevel
    ) {}

    /** Full drift response: actual vs target weights across all buckets. */
    /**
     * @param unclassifiedWeightPercent share of the portfolio with no resolvable sector (B-096).
     *                                  Shown as a coverage line beside the sector table, never as
     *                                  a bucket - a sector called "Other" that is 42% of the book
     *                                  tells the reader nothing about the portfolio.
     * @param unclassifiedSymbols       which holdings those are, so the gap can be closed
     */
    public record DriftResponse(
            String profileName,
            double totalPortfolioValue,
            int holdingsCount,
            List<DriftBucket> buckets,
            double unclassifiedWeightPercent,
            List<String> unclassifiedSymbols,
            /**
             * True when the investor set these targets themselves; false when they are the ones
             * seeded at first boot, or when the profile predates the flag and cannot say.
             *
             * <p>Readers must not raise a finding against unstated targets. A default is not a
             * statement (Gotcha 68), and treating one as a statement put ten permanent warnings
             * on the landing page against weights nobody chose.
             */
            boolean targetsStated
    ) {}
}
