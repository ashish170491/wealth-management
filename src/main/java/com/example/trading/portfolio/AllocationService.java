package com.example.trading.portfolio;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core service for the Portfolio Goals & Allocation module (SPEC.md §5).
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Look up the active {@link PortfolioProfileEntity} and its target weights.</li>
 *   <li>Compute actual portfolio weights from current holdings bucketed by sector,
 *       market-cap band, and individual stock.</li>
 *   <li>Join target vs actual into a per-bucket {@link AllocationDto.DriftResponse} with
 *       alert levels based on the profile's tolerance thresholds.</li>
 *   <li>Replace target weights atomically when the investor updates the profile.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AllocationService {

    public static final String BUCKET_SECTOR = "SECTOR";
    public static final String BUCKET_MARKET_CAP = "MARKET_CAP";
    public static final String BUCKET_STOCK = "STOCK";

    public static final String ALERT_IN_TOLERANCE = "IN_TOLERANCE";
    public static final String ALERT_OVER_TOLERANCE = "OVER_TOLERANCE";
    public static final String ALERT_NO_TARGET = "NO_TARGET";

    private final HoldingsRepository holdingsRepository;
    private final PortfolioProfileRepository profileRepository;
    private final PortfolioTargetWeightRepository targetRepository;
    private final PortfolioConfig config;

    public PortfolioProfileEntity getActiveProfile() {
        return profileRepository.findByActiveTrue()
                .orElseThrow(() -> new IllegalStateException(
                        "No active portfolio profile. Seed one at startup or create via API."));
    }

    public List<PortfolioTargetWeightEntity> getTargetWeights(Long profileId) {
        return targetRepository.findByProfileId(profileId);
    }

    /**
     * Aggregate actual portfolio weights from current holdings.
     * Returns a map keyed by bucket type ({@link #BUCKET_SECTOR}, {@link #BUCKET_MARKET_CAP},
     * {@link #BUCKET_STOCK}) to a per-bucket-key weight map (values are percentages summing to ~100).
     */
    public ActualWeights computeActualWeights() {
        // Active only (Gotcha 16): an exited row with a stale value would still carry weight here.
        List<HoldingsEntity> holdings = holdingsRepository.findActive();
        double totalValue = holdings.stream().mapToDouble(HoldingsEntity::getCurrentValue).sum();

        Map<String, Double> bySector = new HashMap<>();
        Map<String, Double> byCap = new HashMap<>();
        Map<String, Double> byStock = new HashMap<>();
        List<String> unclassified = new ArrayList<>();
        double unclassifiedWeight = 0.0;

        if (totalValue <= 0) {
            return new ActualWeights(0.0, 0, bySector, byCap, byStock, 0.0, unclassified);
        }

        for (HoldingsEntity h : holdings) {
            double weight = (h.getCurrentValue() / totalValue) * 100.0;
            // B-096: resolve across the three vocabularies the codebase writes, and fall back to
            // the screener's table when the valuation feed left the GENERAL placeholder.
            String sector = SectorMapping.resolve(h.getSymbol(), h.getIndustry());
            String cap = classifyMarketCap(h.getMarketCap());
            if (SectorMapping.isUnknown(sector)) {
                // Reported, not bucketed: an "Other" slice that is 42% of the book is not a
                // finding about the portfolio, it is a finding about the data (Gotcha 21).
                unclassified.add(h.getSymbol());
                unclassifiedWeight += weight;
            } else {
                bySector.merge(sector, weight, Double::sum);
            }
            byCap.merge(cap, weight, Double::sum);
            byStock.put(h.getSymbol(), weight);
        }

        return new ActualWeights(totalValue, holdings.size(), bySector, byCap, byStock,
                unclassifiedWeight, unclassified);
    }

    /**
     * Compute drift between target and actual weights for the active profile.
     * Includes buckets with no target as informational rows (alert = {@link #ALERT_NO_TARGET}).
     */
    public AllocationDto.DriftResponse computeDrift() {
        PortfolioProfileEntity profile = getActiveProfile();
        List<PortfolioTargetWeightEntity> targets = getTargetWeights(profile.getId());
        ActualWeights actual = computeActualWeights();

        double tolerancePp = profile.getToleranceAbsolutePp() != null
                ? profile.getToleranceAbsolutePp() : config.getDefaultTolerancePp();
        double toleranceRel = profile.getToleranceRelative() != null
                ? profile.getToleranceRelative() : config.getDefaultRelativeTolerance();

        List<AllocationDto.DriftBucket> buckets = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        for (PortfolioTargetWeightEntity target : targets) {
            double targetWeight = target.getTargetWeight();
            double actualWeight = actualFor(actual, target.getBucketType(), target.getBucketKey());
            double driftPp = actualWeight - targetWeight;
            double driftRel = targetWeight > 0 ? Math.abs(driftPp) / targetWeight : 0.0;
            String alert = (Math.abs(driftPp) > tolerancePp || driftRel > toleranceRel)
                    ? ALERT_OVER_TOLERANCE : ALERT_IN_TOLERANCE;
            buckets.add(new AllocationDto.DriftBucket(
                    target.getBucketType(), target.getBucketKey(),
                    targetWeight, actualWeight, driftPp, driftRel, alert));
            seenKeys.add(key(target.getBucketType(), BUCKET_SECTOR.equals(target.getBucketType())
                    ? SectorMapping.normalize(target.getBucketKey()) : target.getBucketKey()));
        }

        appendUntargetedBuckets(actual.bySector(), BUCKET_SECTOR, seenKeys, buckets);
        appendUntargetedBuckets(actual.byCap(), BUCKET_MARKET_CAP, seenKeys, buckets);
        appendUntargetedBuckets(actual.byStock(), BUCKET_STOCK, seenKeys, buckets);

        return new AllocationDto.DriftResponse(
                profile.getName(), actual.totalValue(), actual.holdingsCount(), buckets,
                actual.unclassifiedWeight(), actual.unclassifiedSymbols());
    }

    @Transactional
    public void replaceTargetWeights(Long profileId, List<PortfolioTargetWeightEntity> newWeights) {
        targetRepository.deleteByProfileId(profileId);
        targetRepository.flush();
        for (PortfolioTargetWeightEntity w : newWeights) {
            w.setId(null);
            w.setProfileId(profileId);
            targetRepository.save(w);
        }
        log.info("Portfolio: replaced {} target weights for profile {}", newWeights.size(), profileId);
    }

    @Transactional
    public PortfolioProfileEntity updateProfileMetadata(
            Long profileId, String description, Double tolerancePp, Double toleranceRel) {
        PortfolioProfileEntity p = profileRepository.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("Profile not found: " + profileId));
        if (description != null) p.setDescription(description);
        if (tolerancePp != null) p.setToleranceAbsolutePp(tolerancePp);
        if (toleranceRel != null) p.setToleranceRelative(toleranceRel);
        return profileRepository.save(p);
    }

    private String classifyMarketCap(Double marketCapCr) {
        if (marketCapCr == null || marketCapCr <= 0) return "UNKNOWN";
        if (marketCapCr < config.getSmallCapMax()) return "SMALL_CAP";
        if (marketCapCr < config.getMidCapMax()) return "MID_CAP";
        return "LARGE_CAP";
    }

    private double actualFor(ActualWeights actual, String bucketType, String bucketKey) {
        return switch (bucketType) {
            // A target key is normalised through the same table as a holding's industry, so a
            // profile seeded as "Banking" or "BANKING" both meet a holding written as "Banks".
            case BUCKET_SECTOR -> actual.bySector().getOrDefault(SectorMapping.normalize(bucketKey), 0.0);
            case BUCKET_MARKET_CAP -> actual.byCap().getOrDefault(bucketKey, 0.0);
            case BUCKET_STOCK -> actual.byStock().getOrDefault(bucketKey, 0.0);
            default -> 0.0;
        };
    }

    private void appendUntargetedBuckets(
            Map<String, Double> actualMap, String bucketType,
            Set<String> seenKeys, List<AllocationDto.DriftBucket> out) {
        for (var e : actualMap.entrySet()) {
            if (seenKeys.add(key(bucketType, e.getKey()))) {
                out.add(new AllocationDto.DriftBucket(
                        bucketType, e.getKey(),
                        0.0, e.getValue(), e.getValue(), 0.0, ALERT_NO_TARGET));
            }
        }
    }

    private static String key(String type, String k) {
        return type + ":" + k;
    }

    /**
     * @param unclassifiedWeight percentage of the book whose holdings carry no resolvable sector.
     *                           Excluded from {@code bySector} on purpose: it is a coverage gap,
     *                           not a sector (B-096).
     */
    public record ActualWeights(
            double totalValue,
            int holdingsCount,
            Map<String, Double> bySector,
            Map<String, Double> byCap,
            Map<String, Double> byStock,
            double unclassifiedWeight,
            List<String> unclassifiedSymbols
    ) {}
}
