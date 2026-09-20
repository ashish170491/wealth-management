package com.example.trading.funds;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The DB-only read model behind {@code /api/mf/*} (SPEC §52, §27).
 *
 * <p>Every method here is a database read. No broker call, no AMFI fetch, no write — a dashboard
 * page load must never reach anything expensive or side-effecting (§20 rule 7, Gotcha 17), and a
 * javadoc claiming "DB-only" is load-bearing enough that B-039 was caused by one that was wrong.
 *
 * <p><b>Two facts are kept apart everywhere in this class</b>: {@code navAsOf}, the date of the
 * value, and {@code lastPulledAt}, when the job ran. Merging them is B-119 in both directions — a
 * Monday pull would report Friday's NAV as "3 days ago" and make a healthy job look broken, or
 * Thursday's NAV would be labelled as today's.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MutualFundViewService {

    private final MutualFundConfig config;
    private final MutualFundSchemeRepository schemeRepository;
    private final MutualFundNavRepository navRepository;
    private final MutualFundHoldingRepository holdingRepository;
    private final MutualFundSyncRunRepository syncRunRepository;

    /** What the investor owns, joined to the scheme master where the ISIN resolved. */
    public Map<String, Object> holdings() {
        List<MutualFundHoldingEntity> active = holdingRepository.findByActiveTrue();
        List<Long> codes = active.stream()
                .map(MutualFundHoldingEntity::getAmfiSchemeCode)
                .filter(java.util.Objects::nonNull)
                .toList();

        Map<Long, MutualFundSchemeEntity> schemes = codes.isEmpty()
                ? Map.of()
                : schemeRepository.findByAmfiSchemeCodeIn(codes).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                MutualFundSchemeEntity::getAmfiSchemeCode, s -> s, (a, b) -> a));

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalCurrent = BigDecimal.ZERO;
        int withoutCostBasis = 0;

        for (MutualFundHoldingEntity h : active) {
            MutualFundSchemeEntity s = h.getAmfiSchemeCode() == null
                    ? null : schemes.get(h.getAmfiSchemeCode());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("isin", h.getIsin());
            row.put("folio", h.getFolio());
            row.put("fundName", s != null ? s.getSchemeName() : h.getBrokerFundName());
            row.put("amcName", s != null ? s.getAmcName() : null);
            row.put("category", s != null && s.getCategory() != null ? s.getCategory().name() : null);
            row.put("categoryLabel", s != null && s.getCategory() != null ? s.getCategory().label() : null);
            row.put("assetClass", s != null && s.getAssetClass() != null ? s.getAssetClass().name() : null);
            row.put("plan", s != null && s.getPlan() != null ? s.getPlan().name() : null);
            row.put("option", s != null && s.getNavOption() != null ? s.getNavOption().name() : null);
            row.put("units", h.getUnits());
            row.put("averagePrice", h.getAveragePrice());
            row.put("nav", s != null ? s.getNav() : h.getLastPrice());
            // The date of the value, separate from when the job ran.
            row.put("navAsOf", s != null ? s.getNavDate() : h.getLastPriceDate());
            row.put("investedValue", h.getInvestedValue());
            row.put("currentValue", h.getCurrentValue());
            row.put("pnl", h.getPnl());
            row.put("pnlPercent", h.getPnlPercent());
            row.put("schemeResolvedFrom",
                    h.getSchemeResolvedFrom() == null ? null : h.getSchemeResolvedFrom().name());
            rows.add(row);

            if (h.getInvestedValue() != null) totalInvested = totalInvested.add(h.getInvestedValue());
            if (h.getCurrentValue() != null) totalCurrent = totalCurrent.add(h.getCurrentValue());
            if (h.getAveragePrice() == null) withoutCostBasis++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        out.put("holdings", rows);
        out.put("totalInvested", rows.isEmpty() ? null : totalInvested);
        out.put("totalCurrent", rows.isEmpty() ? null : totalCurrent);
        // Null rather than zero when nothing could be measured, so an empty book and an unpriced
        // one do not render alike.
        out.put("totalPnl", rows.isEmpty() ? null : totalCurrent.subtract(totalInvested));
        out.put("holdingsWithoutCostBasis", withoutCostBasis);
        if (withoutCostBasis > 0) {
            out.put("costBasisNote", String.format(
                    "%d of %d positions have no cost basis on record at the broker (units transferred "
                            + "in rather than bought). Their invested value and gain read 'not measured' "
                            + "rather than zero, and they are excluded from the totals above.",
                    withoutCostBasis, rows.size()));
        }
        out.putAll(freshness());
        return out;
    }

    /** One scheme with its stored NAV series. Empty when the code is not tracked. */
    public Optional<Map<String, Object>> scheme(long amfiSchemeCode) {
        return schemeRepository.findByAmfiSchemeCode(amfiSchemeCode).map(s -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("amfiSchemeCode", s.getAmfiSchemeCode());
            out.put("schemeName", s.getSchemeName());
            out.put("amcName", s.getAmcName());
            out.put("category", s.getCategory() == null ? null : s.getCategory().name());
            out.put("categoryLabel", s.getCategory() == null ? null : s.getCategory().label());
            // AMFI's own wording, kept so a category mapping bug is diagnosable (B-096).
            out.put("categoryRaw", s.getCategoryRaw());
            out.put("structure", s.getStructure() == null ? null : s.getStructure().name());
            out.put("plan", s.getPlan() == null ? null : s.getPlan().name());
            out.put("option", s.getNavOption() == null ? null : s.getNavOption().name());
            out.put("screenable", s.isScreenable());
            out.put("nav", s.getNav());
            out.put("navAsOf", s.getNavDate());
            out.put("isinGrowth", s.getIsinGrowth());
            out.put("firstSeen", s.getFirstSeen());
            out.put("lastSeenInFile", s.getLastSeenInFile());

            List<MutualFundNavEntity> series =
                    navRepository.findByAmfiSchemeCodeOrderByNavDateAsc(s.getAmfiSchemeCode());
            List<Map<String, Object>> points = series.stream().map(n -> {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("date", n.getNavDate());
                p.put("nav", n.getNav());
                return p;
            }).toList();
            out.put("navPoints", points.size());
            out.put("navSeries", points);

            int min = config.getMinMonthsToMeasure();
            out.put("minPointsToMeasure", min);
            // A refusal, not a tuning knob: below this the lens says nothing rather than returning
            // a constant that looks like a measurement (B-060).
            out.put("measurable", points.size() >= min);
            if (points.size() < min) {
                out.put("measurementNote", String.format(
                        "%d monthly NAV points on file; %d are needed before any rolling record is "
                                + "published. Until then this reads 'not measured', which is different "
                                + "from a poor record.", points.size(), min));
            }
            return out;
        });
    }

    /**
     * The denominators that keep everything else honest (SPEC §52.2c).
     *
     * <p>Not a {@code screening_coverage} signal: that vector is computed over the equity screening
     * run, so a fund is structurally invisible to it — the same argument §51.1 makes for themes.
     */
    public Map<String, Object> coverage() {
        long persisted = schemeRepository.count();
        long screenable = schemeRepository.countByScreenableTrue();
        long measurable = navRepository.countSchemesWithAtLeast(config.getMinMonthsToMeasure());
        long ownedActive = holdingRepository.countByActiveTrue();
        long ownedUnresolved = holdingRepository.countByActiveTrueAndAmfiSchemeCodeIsNull();

        Map<String, Long> byCategory = new TreeMap<>();
        for (Object[] r : schemeRepository.countScreenableByCategory()) {
            SebiCategory c = (SebiCategory) r[0];
            byCategory.put(c == null ? "UNCLASSIFIED" : c.name(), (Long) r[1]);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemesPersisted", persisted);
        out.put("screenableSchemes", screenable);
        out.put("schemesWithEnoughHistory", measurable);
        out.put("minPointsToMeasure", config.getMinMonthsToMeasure());
        out.put("screenableByCategory", byCategory);
        out.put("ownedFunds", ownedActive);
        out.put("ownedFundsUnresolved", ownedUnresolved);

        Optional<MutualFundSyncRunEntity> last = syncRunRepository.findFirstByOrderByLastPulledAtDesc();
        out.put("unparsedLinesLastRun", last.map(MutualFundSyncRunEntity::getUnparsedLines).orElse(null));
        // Reported by name, not merely counted: an unknown header is the app's gap, and naming it is
        // what lets someone add the rule rather than wonder which schemes went missing.
        out.put("unclassifiedHeaders", last.map(MutualFundSyncRunEntity::getUnclassifiedHeaders)
                .map(s -> List.of(s.split("\n"))).orElse(List.of()));
        out.put("lastRunFailure", last.map(MutualFundSyncRunEntity::getFailureReason).orElse(null));

        out.put("limits", List.of(
                "This counts what was measured, never whether a measurement was right.",
                "A scheme with too little history reads 'not measured', which is not a poor record.",
                "Owned funds that could not be matched to an AMFI scheme are counted here and kept, "
                        + "not dropped and not guessed."));
        out.putAll(freshness());
        return out;
    }

    /** The two dates, deliberately separate (B-119). */
    private Map<String, Object> freshness() {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<MutualFundSyncRunEntity> last = syncRunRepository.findFirstByOrderByLastPulledAtDesc();
        out.put("lastPulledAt", last.map(MutualFundSyncRunEntity::getLastPulledAt).orElse(null));
        out.put("navDateInFile", last.map(MutualFundSyncRunEntity::getNavDateInFile).orElse(null));
        return out;
    }
}
