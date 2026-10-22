package com.example.trading.valuation;

import com.example.trading.fundamentals.AnnualFundamentalsEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.example.trading.valuation.ValuationFixtures.eligible;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** SPEC §57.6: what a stored row contains, what it must never contain, and what its hash is blind to. */
class SnapshotMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ValuationReading read(ValuationGapService.Inputs in) {
        return ValuationGapService.assemble(in);
    }

    // ------------------------------------------------------------------------------- contents

    @Test
    @DisplayName("a MEASURED row carries the whole fit, the anchor and the raw series with provenance")
    void measuredRow() throws Exception {
        var r = read(eligible("NSE:GOOD"));
        var e = SnapshotMapper.toEntity(r, "SCHEDULED");
        assertThat(e.getStatus()).isEqualTo("MEASURED");
        assertThat(e.getRuleset()).isEqualTo("VG-2");
        assertThat(e.getFingerprint()).isEqualTo(ValuationGapRules.fingerprint());
        assertThat(e.getCaptureOrigin()).isEqualTo("SCHEDULED");
        assertThat(e.getSnapshotDate()).isEqualTo(ValuationFixtures.TODAY);
        assertThat(e.getBaseFiscalYear()).isEqualTo(2026);
        assertThat(e.getFirstFiscalYear()).isEqualTo(2017);
        assertThat(e.getObservations()).isEqualTo(10);
        assertThat(e.getNormalisedEps()).isNotNull().isPositive();
        assertThat(e.getTrendLnSlope()).isNotNull();
        assertThat(e.getTrendLnIntercept()).isNotNull();
        assertThat(Math.exp(e.getTrendLnIntercept())).isCloseTo(e.getNormalisedEps(), within(e.getNormalisedEps() * 0.5));
        assertThat(e.getTrendStdErrPp()).isNotNull();
        assertThat(e.getTrendRSquared()).isNotNull();
        assertThat(e.getTrendCohort()).isEqualTo("GATES_PASSED");
        assertThat(e.getShareCountBaseCr()).isEqualTo(10.0);
        assertThat(e.getDcfImpliedGrowthPct()).isEqualTo(14.0);
        assertThat(e.getInputsHash()).hasSize(64);

        JsonNode doc = JSON.readTree(e.getEpsSeriesJson());
        assertThat(doc.get("provenance").get("netProfitCr").asText()).startsWith("REPORTED");
        assertThat(doc.get("provenance").get("epsAdjusted").asText()).startsWith("DERIVED");
        JsonNode y0 = doc.get("years").get(0);
        assertThat(y0.has("availableFrom")).isTrue();
        assertThat(y0.has("availableFromIsInferred")).isTrue();
        assertThat(y0.get("source").asText()).isEqualTo("XBRL");
        assertThat(doc.get("years")).hasSize(10);
    }

    @Test
    @DisplayName("a refusal row keeps the raw series so a stock that later turns loss-making still has its earlier years")
    void refusalKeepsTheRawSeries() throws Exception {
        var base = eligible("NSE:LOSS");
        List<AnnualFundamentalsEntity> annual = new ArrayList<>(base.annual());
        annual.get(5).setNetProfit(-10.0);
        var r = read(ValuationFixtures.withAnnual(base, annual, base.quarters()));
        var e = SnapshotMapper.toEntity(r, "SCHEDULED");
        assertThat(e.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(e.getReasonCode()).isEqualTo("LOSS_IN_RECORD");
        assertThat(e.getReason()).contains("FY2022");
        assertThat(e.getTrendCohort()).isEqualTo("NOT_FITTED");
        assertThat(e.getTrendLnSlope()).isNull();
        assertThat(e.getNormalisedEps()).isNull();
        JsonNode years = JSON.readTree(e.getEpsSeriesJson()).get("years");
        assertThat(years).hasSize(10);
        assertThat(years.get(5).get("netProfitCr").asDouble()).isEqualTo(-10.0);
    }

    @Test
    @DisplayName("a never-screened stock has nothing to store")
    void neverScreenedHasNoRow() {
        var base = eligible("NSE:NEW");
        var r = read(new ValuationGapService.Inputs("NSE:NEW", "NSE:NEW", ValuationFixtures.TODAY, null,
                base.annual(), List.of(), false));
        assertThat(SnapshotMapper.toEntity(r, "SCHEDULED")).isNull();
    }

    @Test
    @DisplayName("corporate actions divided out are stored with their basis")
    void actionsStored() throws Exception {
        List<AnnualFundamentalsEntity> annual = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double shares = i < 4 ? 10 : 30;
            annual.add(ValuationFixtures.year("NSE:B", 2019 + i, 1000.0 * Math.pow(1.12, i), shares, 10.0, true));
        }
        var base = eligible("NSE:B");
        double latestEps = annual.get(7).getNetProfit() / 30.0;
        var s = ValuationFixtures.score("NSE:B", 800, 800 * 30.0, "Consumer Durables");
        var r = read(new ValuationGapService.Inputs("NSE:B", "NSE:B", ValuationFixtures.TODAY, s, annual,
                ValuationFixtures.quarters("NSE:B", 2026, latestEps, true), false));
        var e = SnapshotMapper.toEntity(r, "MANUAL");
        JsonNode actions = JSON.readTree(e.getActionsJson());
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).get("ratio").asDouble()).isEqualTo(3.0);
        assertThat(actions.get(0).get("via").asText()).isEqualTo("RATIO_GRID");
        assertThat(e.getCaptureOrigin()).isEqualTo("MANUAL");
    }

    // ------------------------------------------------------------------- what is never stored

    @Test
    @DisplayName("no future earnings level, grid, multiple, verdict, AI text or analyst data is in a row")
    void nothingForwardIsStored() throws Exception {
        var r = read(eligible("NSE:GOOD"));
        var e = SnapshotMapper.toEntity(r, "SCHEDULED");

        // 1. The entity has no column for any of them.
        List<String> banned = List.of("grid", "multiple", "verdict", "target", "forecast", "projection", "upside",
                "range", "rating", "analyst", "critique", "ai", "epsiftrend", "requiredgrowth", "gap");
        for (Field f : ValuationGapSnapshotEntity.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            for (String b : banned) {
                if (b.equals("ai")) continue;    // too short to substring-match safely
                assertThat(n).as("column %s", f.getName()).doesNotContain(b);
            }
        }
        // 2. The stored JSON carries none of them either.
        String all = (e.getEpsSeriesJson() + e.getActionsJson()).toLowerCase(Locale.ROOT);
        for (String b : List.of("epsiftrend", "referencemultiple", "requiredgrowth", "gapto", "verdict", "fy2031")) {
            assertThat(all).as(b).doesNotContain(b);
        }
        // 3. A future level cannot be recovered from a single column: it needs slope AND anchor AND base year.
        assertThat(e.getTrendLnSlope()).isNotNull();
        assertThat(e.getNormalisedEps()).isNotNull();
    }

    @Test
    @DisplayName("the Capture type has no field for a future level, a grid, a multiple or a verdict")
    void captureTypeHasNoForwardField() {
        for (var c : ValuationReading.Capture.class.getRecordComponents()) {
            String n = c.getName().toLowerCase(Locale.ROOT);
            assertThat(n).doesNotContain("grid").doesNotContain("multiple").doesNotContain("verdict")
                    .doesNotContain("epsiftrend").doesNotContain("required");
        }
    }

    // ------------------------------------------------------------------------------- the hash

    @Test
    @DisplayName("the inputs hash ignores price, market cap and the reverse-DCF columns, which change daily")
    void hashIgnoresDailyNoise() {
        var base = eligible("NSE:GOOD");
        var moved = new ValuationGapService.Inputs("NSE:GOOD", "NSE:GOOD", ValuationFixtures.TODAY,
                new ValuationGapService.ScoreFacts("NSE:GOOD", ValuationFixtures.TODAY, 805, 8050.0,
                        "Consumer Durables", "EXPENSIVE", 19.0, "v2"),
                base.annual(), base.quarters(), false);
        // Share-basis G7 still passes at +0.6%, so the status is unchanged.
        assertThat(SnapshotMapper.inputsHash(read(moved))).isEqualTo(SnapshotMapper.inputsHash(read(base)));
    }

    @Test
    @DisplayName("the hash ignores the stored row's updated-at timestamp, which the XBRL writer re-stamps every run")
    void hashIgnoresRowUpdatedAt() {
        var a = eligible("NSE:GOOD");
        var b = eligible("NSE:GOOD");
        for (var y : b.annual()) y.setUpdatedAt(java.time.LocalDateTime.now());
        assertThat(SnapshotMapper.inputsHash(read(a))).isEqualTo(SnapshotMapper.inputsHash(read(b)));
    }

    @Test
    @DisplayName("the hash changes on a new fiscal year, a restatement, a changed basis flag or an availability date")
    void hashChangesOnInputChange() {
        var base = eligible("NSE:GOOD");
        String h0 = SnapshotMapper.inputsHash(read(base));

        // A restated net profit.
        var restated = eligible("NSE:GOOD");
        restated.annual().get(9).setNetProfit(restated.annual().get(9).getNetProfit() * 1.01);
        assertThat(SnapshotMapper.inputsHash(read(restated))).isNotEqualTo(h0);

        // An availability date that moved from inferred to filed.
        var dated = eligible("NSE:GOOD");
        dated.annual().get(9).setAvailableFrom(LocalDate.of(2026, 6, 1));
        assertThat(SnapshotMapper.inputsHash(read(dated))).isNotEqualTo(h0);

        // The estimated flag flipping.
        var flag = eligible("NSE:GOOD");
        flag.annual().get(9).setAvailableFromEstimated(true);
        assertThat(SnapshotMapper.inputsHash(read(flag))).isNotEqualTo(h0);

        // A status flip: the same series refused by a cross-check failure.
        var refused = ValuationFixtures.withAnnual(base, base.annual(), List.of());
        assertThat(SnapshotMapper.inputsHash(read(refused))).isNotEqualTo(h0);
    }

    @Test
    @DisplayName("the hash is stable across calls and does not depend on the capture origin")
    void hashStable() {
        var r = read(eligible("NSE:GOOD"));
        assertThat(SnapshotMapper.inputsHash(r)).isEqualTo(SnapshotMapper.inputsHash(r));
        assertThat(SnapshotMapper.toEntity(r, "SCHEDULED").getInputsHash())
                .isEqualTo(SnapshotMapper.toEntity(r, "MANUAL").getInputsHash());
    }

    @Test
    @DisplayName("the entity is immutable to Hibernate and every column maps to a wrapper type")
    void entityShape() throws Exception {
        assertThat(ValuationGapSnapshotEntity.class.isAnnotationPresent(org.hibernate.annotations.Immutable.class)).isTrue();
        for (Field f : ValuationGapSnapshotEntity.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            assertThat(f.getType().isPrimitive()).as("%s must be a wrapper (B-026)", f.getName()).isFalse();
            assertThat(f.getType().isEnum()).as("%s must not be @Enumerated (Gotcha 148)", f.getName()).isFalse();
        }
    }
}
