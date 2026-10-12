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
            boolean targetsStated,

            /**
             * Total wealth: directly-held shares + funds + broker cash, in rupees.
             *
             * <p>The denominator for every {@code ASSET_CLASS} bucket, and <b>only</b> for those.
             * {@code totalPortfolioValue} above is the equity book and remains the denominator for
             * the sector, size and stock buckets, for the reason in
             * {@link AllocationService#BUCKET_ASSET_CLASS}: a 16% position is still a 16% bet on one
             * business whatever share of total wealth it is. Rendering two differently-denominated
             * percentages without saying which is which is B-047's family, so both totals are on the
             * wire and the screen names them.
             */
            double combinedTotalValue,

            /** Market value of non-equity holdings, in rupees. */
            double fundValue,

            /**
             * False means the broker payload carried no equity segment — we could not see the cash,
             * which is a different fact from there being none (SPEC §46.4). When false the asset
             * class percentages are of a total that excludes an unknown amount, and the screen must
             * say so rather than presenting them as shares of everything.
             */
            boolean cashMeasured,

            /**
             * Funds whose asset class could not be resolved, as a share of combined wealth.
             *
             * <p>These are <b>absent</b> from the asset-class buckets rather than filed under a
             * bucket called "Other" (B-096, Gotcha 110), which means the buckets do not sum to 100
             * whenever this is non-zero. Reported by weight and by name so that gap cannot be
             * mistaken for a smaller portfolio — the empty-collection-read-as-fact failure (B-054).
             */
            double unclassifiedFundWeightPercent,

            List<String> unclassifiedFunds
    ) {}
}
