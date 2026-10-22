package com.example.trading.valuation;

import com.example.trading.fundamentals.AdjustedShareSeries;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a {@link ValuationReading} into a snapshot row (SPEC §57.6). Pure: no repository, no clock,
 * no Spring. {@code ValuationGapService.assemble} builds the reading once and this reads it, so a
 * stored row is exactly what the screen would have shown for the same inputs.
 *
 * <p><b>What the hash covers - and what it must not.</b> {@link #inputsHash} is the identity of the
 * <i>method's inputs</i>: status, reason, the rule fingerprint, the raw annual series (year, net
 * profit, share count, face value, basis, availability date, source) and the corporate actions
 * divided out. It deliberately excludes the price, the market capitalisation, the reverse-DCF
 * columns and the row-updated timestamps. Those change daily; including them would write a row per
 * stock per day and bury the few rows that matter, which are the ones where the <i>record</i>
 * changed: a new fiscal year filed, a restatement, history added by a backfill, a status that
 * flipped, or a rule-set change.
 *
 * <p><b>Never stored, and impossible to store from here:</b> a future earnings level, the grid, a
 * reference multiple, a verdict, AI text, analyst data. The {@link ValuationReading.Capture} type
 * simply has no field for any of them; {@code SnapshotMapperTest} also scans the produced JSON.
 */
public final class SnapshotMapper {

    private SnapshotMapper() {
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** What each stored value is. Written once into the series JSON so no number is read out of context. */
    static final Map<String, String> PROVENANCE = orderedMap(
            "netProfitCr", "REPORTED (annual_fundamentals.net_profit, includes minority interest)",
            "shareCountCr", "REPORTED (annual_fundamentals.share_count, year end, crore)",
            "faceValue", "REPORTED",
            "consolidated", "REPORTED",
            "shareFactor", "DERIVED (product of bonus issues and splits after this year)",
            "epsAdjusted", "DERIVED (net profit / (share count x share factor))",
            "availableFrom", "REPORTED when availableFromIsInferred is false, INFERRED otherwise",
            "rowUpdatedAt", "REPORTED (when the stored row was last written; not part of the inputs hash)");

    /**
     * @param origin {@code SCHEDULED} or {@code MANUAL}
     * @return null when the reading has nothing to store (a stock that was never screened)
     */
    public static ValuationGapSnapshotEntity toEntity(ValuationReading r, String origin) {
        ValuationReading.Capture c = r.capture();
        if (c == null) return null;
        return ValuationGapSnapshotEntity.builder()
                .snapshotDate(c.snapshotDate())
                .symbol(r.symbol())
                .symbolAnswered(c.symbolAnswered())
                .ruleset(r.ruleset())
                .fingerprint(r.fingerprint())
                .scoringVersion(c.scoringVersion())
                .captureOrigin(origin)
                .status(r.status().name())
                .reasonCode(r.reasonCode() == null ? null : r.reasonCode().name())
                .reason(r.reason())
                .price(c.price())
                .marketCapCr(c.marketCapCr())
                .pe0(c.pe0())
                .baseFiscalYear(c.baseFiscalYear())
                .firstFiscalYear(c.firstFiscalYear())
                .observations(c.observations())
                .consolidated(c.consolidated())
                .latestEps(c.latestEps())
                .normalisedEps(c.normalisedEps())
                .normalisationFlags(c.normalisationFlags())
                .trendLnIntercept(c.trendLnIntercept())
                .trendLnSlope(c.trendLnSlope())
                .trendGrowthPct(c.trendGrowthPct())
                .trendStdErrPp(c.trendStdErrPp())
                .trendRSquared(c.trendRSquared())
                .trendCohort(c.cohort() == null ? null : c.cohort().name())
                .endpointCagrPct(c.endpointCagrPct())
                .shareCountBaseCr(c.shareCountBaseCr())
                .faceValueBase(c.faceValueBase())
                .dcfImpliedGrowthPct(c.dcfImpliedGrowthPct())
                .crosscheckRatio(c.crosscheckRatio())
                .epsSeriesJson(seriesJson(c.series()))
                .actionsJson(actionsJson(c.actions()))
                .inputsHash(inputsHash(r))
                .build();
    }

    /** SHA-256 of the canonical method inputs; see the class comment for what is, and is not, in it. */
    public static String inputsHash(ValuationReading r) {
        ValuationReading.Capture c = r.capture();
        Map<String, Object> canon = new LinkedHashMap<>();
        canon.put("ruleset", r.ruleset());
        canon.put("fingerprint", r.fingerprint());
        canon.put("status", r.status().name());
        canon.put("reason", r.reasonCode() == null ? null : r.reasonCode().name());
        canon.put("answered", c == null ? null : c.symbolAnswered());
        canon.put("series", c == null ? List.of() : seriesRows(c.series(), false));
        canon.put("actions", c == null ? List.of() : actionRows(c.actions()));
        return ValuationGapRules.sha256(write(canon));
    }

    // ------------------------------------------------------------------------------------- JSON

    static String seriesJson(List<EpsSeries.Year> years) {
        if (years == null || years.isEmpty()) return null;
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("provenance", PROVENANCE);
        doc.put("years", seriesRows(years, true));
        return write(doc);
    }

    static String actionsJson(List<AdjustedShareSeries.Action> actions) {
        if (actions == null || actions.isEmpty()) return null;
        return write(actionRows(actions));
    }

    private static List<Map<String, Object>> seriesRows(List<EpsSeries.Year> years, boolean forStorage) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (years == null) return rows;
        for (EpsSeries.Year y : years) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fy", y.fiscalYear());
            m.put("netProfitCr", y.netProfitCr());
            m.put("shareCountCr", y.shareCountCr());
            m.put("faceValue", y.faceValue());
            m.put("consolidated", y.consolidated());
            m.put("availableFrom", y.availableFrom() == null ? null : y.availableFrom().toString());
            m.put("availableFromIsInferred", y.availableFromIsInferred());
            m.put("source", y.source());
            if (forStorage) {
                m.put("shareFactor", y.shareFactor());
                m.put("epsAdjusted", y.eps());
                m.put("rowUpdatedAt", y.rowUpdatedAt() == null ? null : y.rowUpdatedAt().toString());
            }
            rows.add(m);
        }
        return rows;
    }

    private static List<Map<String, Object>> actionRows(List<AdjustedShareSeries.Action> actions) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (actions == null) return rows;
        for (AdjustedShareSeries.Action a : actions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fy", a.fiscalYear());
            m.put("ratio", a.ratio());
            m.put("via", a.via());
            rows.add(m);
        }
        return rows;
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Snapshot JSON could not be written", e);
        }
    }

    private static Map<String, String> orderedMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
