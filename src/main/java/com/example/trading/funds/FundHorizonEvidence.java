package com.example.trading.funds;

import com.example.trading.portfolio.goal.HorizonEvidenceSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Supplies the goal planner with what equity and debt actually did (SPEC §53.2).
 *
 * <p>Implements the port defined in the portfolio package, so the dependency points one way, the
 * same arrangement {@link MutualFundAllocationSource} uses.
 *
 * <p><b>Both legs are pooled across every scheme of that asset class</b>, not taken from one
 * representative category. The goal planner is asking "what did equity do over a stretch this
 * long", and the median across every actively managed equity fund is what a typical equity fund
 * did; standing mid caps in for all of equity would answer a narrower question under a wider name.
 *
 * <p><b>The debt leg pools credit-risk and gilt funds in with liquid funds deliberately.</b> They
 * are what a person who "put it in a debt fund" might actually have owned, and the two bad
 * episodes in this archive - the credit events of late 2018 and April 2020 - are precisely the
 * thing a goal plan needs to have survived. Excluding them would make the debt leg look like a
 * bank account, which is the flattering direction and the one a downside figure must never fail in.
 */
@Component
@RequiredArgsConstructor
public class FundHorizonEvidence implements HorizonEvidenceSource {

    private final MutualFundSchemeRepository schemeRepository;
    private final MonthEndSeriesCache seriesCache;

    @Override
    public Evidence forHorizon(int months, double equityPct) {
        int m = Math.max(1, months);
        return forHorizons(List.of(new Request(m, equityPct))).get(m);
    }

    /**
     * Every requested window length off <b>one</b> read of the archive.
     *
     * <p>The series are the same whatever the window is - only the arithmetic over them changes -
     * so reading them once per goal would be reading the same rows six times on a page load
     * (B-115's shape, in code written after B-115).
     */
    @Override
    public Map<Integer, Evidence> forHorizons(java.util.Collection<Request> requests) {
        Map<Integer, Evidence> out = new LinkedHashMap<>();
        List<MutualFundSchemeEntity> all = schemeRepository.findAll();

        // The shared cache holds every measurable scheme's series; the two legs are slices of it.
        // Loading them here would read the same rows the horizon screen has already read.
        Map<Long, List<CategoryDownside.Point>> archive = all.isEmpty()
                ? Map.of() : seriesCache.all();
        Map<Long, List<CategoryDownside.Point>> equitySeries =
                slice(archive, codesFor(all, AssetClass.EQUITY));
        Map<Long, List<CategoryDownside.Point>> debtSeries =
                slice(archive, codesFor(all, AssetClass.DEBT));

        for (Request req : requests) {
            if (req == null) {
                continue;
            }
            int w = Math.max(1, req.months());
            if (all.isEmpty()) {
                out.put(req.months(), Evidence.notMeasured(w,
                        "no fund scheme is on file yet, so there is nothing to measure this "
                        + "against. A gap in this app's record, not a statement about how safe "
                        + "anything is."));
                continue;
            }
            out.put(req.months(), build(w, req.equityPct(), equitySeries, debtSeries));
        }
        return out;
    }

    private Evidence build(int w, double equityPct,
                           Map<Long, List<CategoryDownside.Point>> equitySeries,
                           Map<Long, List<CategoryDownside.Point>> debtSeries) {
        CategoryDownside.Reading equity =
                CategoryDownside.measureGroup("Equity funds", w, equitySeries);
        CategoryDownside.Reading debt =
                CategoryDownside.measureGroup("Debt funds", w, debtSeries);

        // The blend walked as a real path - each window priced on BOTH legs over the same months
        // and then mixed - rather than the two legs' separate worst months put together, which
        // describe different months and so describe a portfolio nobody held.
        CategoryDownside.Reading blend = CategoryDownside.measureBlend(
                "The proposed split", w, equityPct, equitySeries, debtSeries);

        if (!blend.measured() && !equity.measured() && !debt.measured()) {
            return Evidence.notMeasured(w, blend.reason());
        }

        return new Evidence(
                blend.measured(),
                w,
                equity.worstPct(),
                debt.worstPct(),
                blend.worstPct(),
                equityPct,
                equity.medianPct(),
                debt.medianPct(),
                equity.shareNegativePct(),
                blend.measured() ? blend.independentWindows() : null,
                blend.measured() ? blend.windowsMeasured() : null,
                blend.worstFrom() == null ? null : blend.worstFrom().toString(),
                blend.worstTo() == null ? null : blend.worstTo().toString(),
                blend.measured() ? null : blend.reason(),
                blend.measured() ? blend.caveat() : null);
    }

    private Set<Long> codesFor(List<MutualFundSchemeEntity> all, AssetClass assetClass) {
        return all.stream()
                // Direct-plan Growth only, for the reason every other reading in this module has:
                // an income-distributing series records the return after every payout and a Regular
                // plan records it after a commission, so mixing them compares three scales.
                .filter(s -> s.getAssetClass() == assetClass)
                .filter(s -> s.getPlan() == SchemePlanKey.Plan.DIRECT)
                .filter(s -> s.getNavOption() == SchemePlanKey.Option.GROWTH)
                // A tracker is an equity fund for this purpose - it is what the money did - but an
                // exchange-traded one is priced away from its NAV, so it is not what the money did.
                .filter(s -> s.getCategory() == null || !s.getCategory().isExchangeTraded())
                .map(MutualFundSchemeEntity::getAmfiSchemeCode)
                .collect(Collectors.toSet());
    }

    private static Map<Long, List<CategoryDownside.Point>> slice(
            Map<Long, List<CategoryDownside.Point>> archive, Set<Long> codes) {
        if (codes.isEmpty() || archive.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<CategoryDownside.Point>> out = new LinkedHashMap<>();
        for (Long code : codes) {
            List<CategoryDownside.Point> points = archive.get(code);
            if (points != null && !points.isEmpty()) {
                out.put(code, points);
            }
        }
        return out;
    }
}
