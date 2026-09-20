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

    /**
     * Direct equity vs funds vs cash (SPEC §5.2, implemented 2026-09-20 with §52.5).
     *
     * <p>Fed <b>only</b> by {@link #computeCombinedWeights()}. {@link #computeActualWeights()} is
     * untouched and still denominated in the equity book, which is the safety property for the
     * whole mutual-fund integration: every existing consumer — HHI, sector concentration, stock
     * concentration, the rebalance plan, the donut — keeps reading the weights it has always read.
     * Changing that denominator would silently rescale all of them, and a 16% position that becomes
     * 11% of combined wealth is still a 16% bet on one business.
     */
    public static final String BUCKET_ASSET_CLASS = "ASSET_CLASS";

    public static final String ALERT_IN_TOLERANCE = "IN_TOLERANCE";
    public static final String ALERT_OVER_TOLERANCE = "OVER_TOLERANCE";
    public static final String ALERT_NO_TARGET = "NO_TARGET";

    private final HoldingsRepository holdingsRepository;
    private final PortfolioProfileRepository profileRepository;
    private final PortfolioTargetWeightRepository targetRepository;
    private final PortfolioConfig config;

    /**
     * Never a hard dependency (the {@code ObjectProvider<ChatModel>} pattern from §48). The
     * allocation module must start and work with no fund module present at all; the asset-class
     * bucket then holds direct equity and cash, which is a true statement rather than a broken one.
     */
    private final org.springframework.beans.factory.ObjectProvider<FundAllocationSource> fundAllocationSource;

    private final com.example.trading.portfolio.performance.PortfolioCashSnapshotRepository cashRepository;

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
        CombinedWeights combined = computeCombinedWeights(actual);

        double tolerancePp = profile.getToleranceAbsolutePp() != null
                ? profile.getToleranceAbsolutePp() : config.getDefaultTolerancePp();
        double toleranceRel = profile.getToleranceRelative() != null
                ? profile.getToleranceRelative() : config.getDefaultRelativeTolerance();

        List<AllocationDto.DriftBucket> buckets = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        for (PortfolioTargetWeightEntity target : targets) {
            // A placeholder is never a bucket (B-096, Gotcha 110), and a TARGET on one is worse
            // than a slice: "OTHER is 0.0% against a 15.0% target" is a warning the investor
            // cannot act on, because since SectorMapping.resolve classifies every holding there
            // is nothing that can ever land in it. Unsatisfiable by construction, so it is not
            // reported as drift at all - the unclassified weight is already carried separately
            // on the response, by weight and by name, which is the honest form of the same fact.
            if (BUCKET_SECTOR.equals(target.getBucketType())
                    && SectorMapping.isUnknown(SectorMapping.normalize(target.getBucketKey()))) {
                continue;
            }
            double targetWeight = target.getTargetWeight();
            double actualWeight = actualFor(actual, combined, target.getBucketType(), target.getBucketKey());
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
        appendUntargetedBuckets(combined.byAssetClass(), BUCKET_ASSET_CLASS, seenKeys, buckets);

        return new AllocationDto.DriftResponse(
                profile.getName(), actual.totalValue(), actual.holdingsCount(), buckets,
                actual.unclassifiedWeight(), actual.unclassifiedSymbols(),
                targetsStated(profile));
    }

    /**
     * Has the investor actually chosen these targets, or are they the ones we seeded?
     *
     * <p>TRUE only when the flag says so. Null - a profile written before the column existed - is
     * deliberately NOT read as true: it is unknown, and the caller is expected to say so rather
     * than raise an alarm against numbers whose author it cannot name (Gotcha 68).
     */
    public static boolean targetsStated(PortfolioProfileEntity profile) {
        return profile != null && Boolean.TRUE.equals(profile.getTargetsStated());
    }

    @Transactional
    public void replaceTargetWeights(Long profileId, List<PortfolioTargetWeightEntity> newWeights) {
        // This is the investor's own write, and the only one: nothing else calls this method, and
        // the bootstrapper seeds a profile with no weights at all. Recording it here is what lets
        // every reader tell a chosen target from a seeded one.
        profileRepository.findById(profileId).ifPresent(p -> {
            p.setTargetsStated(true);
            profileRepository.save(p);
        });
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

    private double actualFor(ActualWeights actual, CombinedWeights combined,
                            String bucketType, String bucketKey) {
        return switch (bucketType) {
            // A target key is normalised through the same table as a holding's industry, so a
            // profile seeded as "Banking" or "BANKING" both meet a holding written as "Banks".
            case BUCKET_SECTOR -> actual.bySector().getOrDefault(SectorMapping.normalize(bucketKey), 0.0);
            case BUCKET_MARKET_CAP -> actual.byCap().getOrDefault(bucketKey, 0.0);
            case BUCKET_STOCK -> actual.byStock().getOrDefault(bucketKey, 0.0);
            // Denominated in TOTAL wealth, not the equity book - the only bucket that is.
            case BUCKET_ASSET_CLASS -> combined.byAssetClass()
                    .getOrDefault(bucketKey == null ? "" : bucketKey.trim().toUpperCase(java.util.Locale.ROOT), 0.0);
            default -> 0.0;
        };
    }

    /**
     * Direct equity, funds and cash as shares of <b>total</b> wealth (SPEC §5.2, §52.5).
     *
     * <p>This is a second, additive method precisely so that {@link #computeActualWeights()} does
     * not change. Everything that already reads equity-denominated weights keeps doing so, and only
     * {@link #BUCKET_ASSET_CLASS} reads this.
     *
     * <p>Three rules the implementation must keep:
     * <ul>
     *   <li><b>A fund's weight never reaches HHI.</b> Concentration is computed from
     *       {@code byStock()}, which this method does not touch. A mid-cap fund at 20% of combined
     *       wealth is ~60 businesses; squaring 20 would assert concentration that does not exist.</li>
     *   <li><b>An unresolved fund is named, not bucketed.</b> Same treatment as an unclassified
     *       sector: reported by weight so a coverage gap cannot masquerade as an asset class.</li>
     *   <li><b>Cash is absent rather than zero</b> when the broker payload carries none (§46.4) —
     *       {@code cashMeasured} says which, because zero cash is a fact about the account and
     *       absence is a fact about the feed.</li>
     * </ul>
     */
    public CombinedWeights computeCombinedWeights() {
        return computeCombinedWeights(computeActualWeights());
    }

    private CombinedWeights computeCombinedWeights(ActualWeights equity) {
        double equityValue = equity.totalValue();
        double fundValue = 0.0;
        double unresolvedFundValue = 0.0;
        List<String> unresolvedFunds = new ArrayList<>();
        Map<String, Double> valueByClass = new HashMap<>();

        FundAllocationSource source = fundAllocationSource.getIfAvailable();
        List<FundAllocationSource.FundSlice> slices =
                source == null ? List.of() : source.fundSlices();
        for (FundAllocationSource.FundSlice f : slices) {
            fundValue += f.value();
            if (f.assetClass() == null || f.assetClass().isBlank()) {
                unresolvedFundValue += f.value();
                unresolvedFunds.add(f.label());
            } else {
                valueByClass.merge(f.assetClass(), f.value(), Double::sum);
            }
        }

        // Absent, not zero, when the broker payload carried no equity segment (SPEC §46.4):
        // zero cash is a fact about the account, absence is a fact about the feed.
        Double cash = cashRepository.findTopByOrderBySnapshotDateDesc()
                .map(com.example.trading.portfolio.performance.PortfolioCashSnapshotEntity::getAvailableCash)
                .orElse(null);
        double cashValue = cash == null ? 0.0 : cash;
        double total = equityValue + fundValue + cashValue;

        Map<String, Double> byAssetClass = new HashMap<>();
        if (total > 0) {
            if (equityValue > 0) byAssetClass.put("EQUITY_DIRECT", equityValue / total * 100.0);
            valueByClass.forEach((k, v) -> byAssetClass.put(k, v / total * 100.0));
            if (cash != null && cashValue > 0) byAssetClass.put("CASH", cashValue / total * 100.0);
        }

        return new CombinedWeights(
                total, equityValue, fundValue, cash, cash != null,
                byAssetClass,
                total > 0 ? unresolvedFundValue / total * 100.0 : 0.0,
                unresolvedFunds);
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

    /**
     * Direct equity, funds and cash as shares of total wealth (SPEC §5.2, §52.5).
     *
     * <p>The only weights in this class denominated in anything other than the equity book, and the
     * only ones {@link #BUCKET_ASSET_CLASS} reads. Nothing here reaches HHI, the single-stock cap or
     * the sector limits — those stay measured on directly-held stocks and carry a caveate naming the
     * fund value they exclude, because rescaling a 16% position down to 11% of combined wealth would
     * delete a working risk control by arithmetic (Gotcha 42's asymmetry).
     *
     * @param totalCash    null when the broker payload carried no equity segment — unknown, not zero
     * @param cashMeasured false means "we could not see it", which is different from "there is none"
     * @param unclassifiedFundWeight funds whose asset class could not be resolved, reported by
     *                               weight and name rather than bucketed (B-096)
     */
    public record CombinedWeights(
            double totalValue,
            double directEquityValue,
            double fundValue,
            Double totalCash,
            boolean cashMeasured,
            Map<String, Double> byAssetClass,
            double unclassifiedFundWeight,
            List<String> unclassifiedFunds
    ) {}
}
