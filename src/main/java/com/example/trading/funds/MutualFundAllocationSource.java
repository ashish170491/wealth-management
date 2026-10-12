package com.example.trading.funds;

import com.example.trading.portfolio.FundAllocationSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Supplies the allocation module with fund value by asset class (SPEC §52.5).
 *
 * <p>Implements the port defined in the portfolio package, so the dependency points one way: funds
 * know about allocation, allocation does not know about funds.
 *
 * <p><b>A holding whose value cannot be measured is omitted, not sent as zero.</b> Sending zero
 * would quietly shrink the denominator of every asset-class weight while looking like a complete
 * answer — the same failure as summing an unknown day-change as zero (B-120).
 *
 * <p><b>A holding whose scheme did not resolve is sent with a null asset class</b>, so the
 * allocation module reports it by weight and by name as a coverage gap. Guessing it into
 * {@code EQUITY_FUND} would be the placeholder-as-bucket bug (B-096), and dropping it would
 * understate the money that is actually invested.
 */
@Component
@RequiredArgsConstructor
public class MutualFundAllocationSource implements FundAllocationSource {

    private final MutualFundHoldingRepository holdingRepository;
    private final MutualFundSchemeRepository schemeRepository;

    @Override
    public List<FundSlice> fundSlices() {
        List<MutualFundHoldingEntity> active = holdingRepository.findByActiveTrue();
        if (active.isEmpty()) {
            return List.of();
        }

        List<Long> codes = active.stream()
                .map(MutualFundHoldingEntity::getAmfiSchemeCode)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, MutualFundSchemeEntity> schemes = codes.isEmpty()
                ? Map.of()
                : schemeRepository.findByAmfiSchemeCodeIn(codes).stream()
                        .collect(Collectors.toMap(
                                MutualFundSchemeEntity::getAmfiSchemeCode, s -> s, (a, b) -> a));

        List<FundSlice> slices = new ArrayList<>();
        for (MutualFundHoldingEntity h : active) {
            BigDecimal value = h.getCurrentValue();
            if (value == null || value.signum() <= 0) {
                // Unpriced today. Omitted rather than counted as zero - see the class javadoc.
                continue;
            }
            MutualFundSchemeEntity s = h.getAmfiSchemeCode() == null
                    ? null : schemes.get(h.getAmfiSchemeCode());
            slices.add(new FundSlice(
                    bucketFor(s),
                    value.doubleValue(),
                    s != null ? s.getSchemeName() : h.getBrokerFundName()));
        }
        return slices;
    }

    /**
     * Coarse asset-class bucket for the drift table.
     *
     * <p>Returns <b>null</b> when the scheme did not resolve or carries no asset class — the port
     * treats that as unclassified and reports it by name. An unresolved fund is a gap in this app,
     * not a category of investment.
     */
    private static String bucketFor(MutualFundSchemeEntity s) {
        if (s == null || s.getAssetClass() == null) {
            return null;
        }
        return switch (s.getAssetClass()) {
            case EQUITY -> "EQUITY_FUND";
            case DEBT -> "DEBT_FUND";
            case HYBRID -> "HYBRID_FUND";
            case GOLD -> "GOLD_FUND";
            case SOLUTION_ORIENTED -> "SOLUTION_FUND";
            case OTHER -> "OTHER_FUND";
            case UNCLASSIFIED -> null;
        };
    }
}
