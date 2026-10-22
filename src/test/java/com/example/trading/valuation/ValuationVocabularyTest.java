package com.example.trading.valuation;

import com.example.trading.fundamentals.AnnualFundamentalsEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.example.trading.valuation.ValuationFixtures.eligible;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §57.1.3: the lens carries no instruction to transact, no forecast wording and no verdict, in
 * anything it prints, in any field name and in any enum constant. Printed strings are matched on
 * <b>word boundaries</b> (the §51 version of this test failed in its first draft on "whoever
 * <b>sells</b> the servers", Gotcha 53); identifiers are matched as substrings because an identifier
 * is one word.
 *
 * <p>Also pinned here: the surface carries no score-shaped field, so "contributes zero points" is
 * checkable rather than asserted in a comment.
 */
class ValuationVocabularyTest {

    private static final List<String> BANNED = List.of(
            "buy", "sell", "exit", "add", "avoid", "accumulate", "hold", "reduce", "trim", "book", "target",
            "upside", "recommend", "undervalued", "overvalued", "cheap", "expensive", "forecast", "projection",
            "projected", "estimate", "estimated", "expected", "predict", "prediction", "outlook", "verdict");

    private static final Pattern WORDS = Pattern.compile(
            "\\b(" + String.join("|", BANNED) + ")\\b", Pattern.CASE_INSENSITIVE);

    /** Identifiers that may not contain a score, a rank or a weight: nothing here is a score. */
    private static final List<String> SCORE_SHAPED = List.of(
            "actionable", "bonus", "weight", "points", "composite", "score", "rank", "verdict", "position",
            "range", "lowinr", "highinr", "badge", "exitpe", "endpe", "fairpe", "justifiedpe");

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    // ------------------------------------------------------------------------- printed strings

    private static void strings(JsonNode n, List<String> out) {
        if (n.isTextual()) out.add(n.asText());
        else if (n.isContainerNode()) n.forEach(c -> strings(c, out));
    }

    private static void keys(JsonNode n, List<String> out) {
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) {
                var e = it.next();
                out.add(e.getKey());
                keys(e.getValue(), out);
            }
        } else if (n.isArray()) {
            n.forEach(c -> keys(c, out));
        }
    }

    private static List<ValuationReading> sampleReadings() {
        List<ValuationReading> out = new ArrayList<>();
        out.add(ValuationGapService.assemble(eligible("NSE:GOOD")));

        var wobbly = eligible("NSE:WOBBLY");
        double[] eps = {10, 25, 8, 30, 12, 28, 9, 26, 11, 27};
        List<AnnualFundamentalsEntity> annual = new ArrayList<>();
        for (int i = 0; i < 10; i++) annual.add(ValuationFixtures.year("NSE:WOBBLY", 2017 + i, eps[i] * 10, 10.0, 10.0, true));
        out.add(ValuationGapService.assemble(ValuationFixtures.withAnnual(wobbly, annual,
                ValuationFixtures.quarters("NSE:WOBBLY", 2026, 27.0, true))));

        var noDcf = eligible("NSE:NODCF");
        out.add(ValuationGapService.assemble(new ValuationGapService.Inputs("NSE:NODCF", "NSE:NODCF", ValuationFixtures.TODAY,
                new ValuationGapService.ScoreFacts("NSE:NODCF", ValuationFixtures.TODAY, 800, 8000.0, "Consumer Durables",
                        "NOT_APPLICABLE", null, "v1"), noDcf.annual(), noDcf.quarters(), false)));

        // One refusal per family of gate.
        var base = eligible("NSE:R");
        out.add(ValuationGapService.assemble(new ValuationGapService.Inputs("NSE:R", "NSE:R", ValuationFixtures.TODAY, null,
                base.annual(), List.of(), false)));
        out.add(ValuationGapService.assemble(new ValuationGapService.Inputs("NSE:R", "NSE:R", ValuationFixtures.TODAY,
                ValuationFixtures.score("NSE:R", 800, 8000, "Private Sector Bank"), base.annual(), base.quarters(), false)));
        out.add(ValuationGapService.assemble(new ValuationGapService.Inputs("NSE:ZZNOSUCH", "NSE:R", ValuationFixtures.TODAY,
                ValuationFixtures.score("NSE:ZZNOSUCH", 800, 8000, "GENERAL"), base.annual(), base.quarters(), false)));
        out.add(ValuationGapService.assemble(ValuationFixtures.withAnnual(base,
                ValuationFixtures.steady("NSE:R", 2026, 4, 10, 0.1, 10), base.quarters())));
        out.add(ValuationGapService.assemble(ValuationFixtures.withAnnual(base, base.annual(), List.of())));
        out.add(ValuationGapService.assemble(ValuationFixtures.withAnnual(base, base.annual(),
                ValuationFixtures.quarters("NSE:R", 2026, 5.0, true))));
        List<AnnualFundamentalsEntity> amb = ValuationFixtures.steady("NSE:R", 2026, 7, 10, 0.1, 10);
        for (int i = 4; i < 7; i++) amb.get(i).setShareCount(10.5);
        out.add(ValuationGapService.assemble(ValuationFixtures.withAnnual(base, amb, base.quarters())));
        out.add(ValuationGapService.assemble(new ValuationGapService.Inputs("NSE:R", "NSE:R", ValuationFixtures.TODAY,
                new ValuationGapService.ScoreFacts("NSE:R", ValuationFixtures.TODAY, 800, 16000.0, "Consumer Durables",
                        "FAIRLY_VALUED", 14.0, "v1"), base.annual(), base.quarters(), false)));
        List<AnnualFundamentalsEntity> loss = new ArrayList<>(eligible("NSE:R").annual());
        loss.get(2).setNetProfit(-1.0);
        out.add(ValuationGapService.assemble(ValuationFixtures.withAnnual(base, loss, base.quarters())));
        List<AnnualFundamentalsEntity> sw = ValuationFixtures.steady("NSE:R", 2026, 8, 10, 0.1, 10);
        for (int i = 0; i < 5; i++) sw.get(i).setConsolidated(false);
        out.add(ValuationGapService.assemble(ValuationFixtures.withAnnual(base, sw, base.quarters())));
        return out;
    }

    @Test
    @DisplayName("nothing the lens prints carries a transaction, forecast or verdict word (word-boundary match)")
    void printedStringsAreClean() throws Exception {
        List<String> all = new ArrayList<>();
        for (ValuationReading r : sampleReadings()) strings(JSON.valueToTree(r), all);
        ValuationGapRules.rules().forEach(r -> { all.add(r.rule()); all.add(r.selects()); });
        all.add(ValuationGapRules.MULTIPLES_LIMITS);
        all.add(RequiredGrowthGrid.TREND_LABEL);
        all.add(RequiredGrowthGrid.TREND_STATEMENT);
        all.add(RequiredGrowthGrid.DIFFERENCE_BETWEEN_METHODS);
        RequiredGrowthGrid.gridMethodology().terms().forEach(all::add);
        RequiredGrowthGrid.section125Methodology().terms().forEach(all::add);
        all.add(RequiredGrowthGrid.section125(14.0).note());

        List<String> hits = new ArrayList<>();
        for (String s : all) {
            Matcher m = WORDS.matcher(s);
            if (m.find()) hits.add("'" + m.group() + "' in: " + s);
        }
        assertThat(hits).isEmpty();
        assertThat(all).hasSizeGreaterThan(40);       // the scan is not vacuously empty
    }

    @Test
    @DisplayName("the word-boundary match is a real check: it catches 'sell' and lets 'sells the servers' pass as a different word")
    void theCheckBites() {
        assertThat(WORDS.matcher("You should sell this").find()).isTrue();
        assertThat(WORDS.matcher("whoever sells the servers").find()).isFalse();
        assertThat(WORDS.matcher("a forecast for FY2031").find()).isTrue();
        assertThat(WORDS.matcher("the share count withheld").find()).isFalse();
    }

    @Test
    @DisplayName("no JSON key, field name or enum constant contains a banned word or a score-shaped word")
    void identifiersAreClean() throws Exception {
        TreeSet<String> names = new TreeSet<>();
        for (ValuationReading r : sampleReadings()) {
            List<String> k = new ArrayList<>();
            keys(JSON.valueToTree(r), k);
            names.addAll(k);
        }
        names.addAll(ValuationReading.vocabulary());
        names.addAll(componentNames(ValuationReading.class));
        for (Class<?> nested : ValuationReading.class.getDeclaredClasses()) {
            if (nested.isRecord()) names.addAll(componentNames(nested));
        }
        for (Class<?> c : List.of(RequiredGrowthGrid.Grid.class, RequiredGrowthGrid.GridRow.class,
                RequiredGrowthGrid.RateRow.class, ValuationReading.Capture.class)) {
            names.addAll(componentNames(c));
        }
        for (Field f : ValuationGapSnapshotEntity.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) names.add(f.getName());
        }
        assertThat(names.size()).isGreaterThan(60);

        List<String> offenders = new ArrayList<>();
        for (String n : names) {
            String low = n.toLowerCase(Locale.ROOT).replace("_", "");
            for (String b : BANNED) {
                if (low.contains(b)) offenders.add(n + " contains '" + b + "'");
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("the wire surface has no score, rank, weight, bonus, range or badge field: it contributes zero points")
    void noScoreShapedField() {
        List<String> offenders = new ArrayList<>();
        List<Class<?>> wire = new ArrayList<>();
        wire.add(ValuationReading.class);
        for (Class<?> nested : ValuationReading.class.getDeclaredClasses()) if (nested.isRecord()) wire.add(nested);
        wire.add(RequiredGrowthGrid.Grid.class);
        wire.add(RequiredGrowthGrid.GridRow.class);
        wire.add(RequiredGrowthGrid.RateRow.class);
        wire.add(ValuationGapConfig.class);
        for (Class<?> c : wire) {
            List<String> names = c.isRecord() ? componentNames(c)
                    : Stream.of(c.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers()))
                    .map(Field::getName).collect(Collectors.toList());
            for (String n : names) {
                String low = n.toLowerCase(Locale.ROOT);
                for (String s : SCORE_SHAPED) {
                    if (low.contains(s)) offenders.add(c.getSimpleName() + "." + n + " ~ " + s);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("a trend FY2031 comparison and the grid never appear fused: no field or string multiplies them")
    void noFusedProduct() throws Exception {
        for (ValuationReading r : sampleReadings()) {
            String json = JSON.writeValueAsString(r).toLowerCase(Locale.ROOT);
            assertThat(json).doesNotContain("implied price").doesNotContain("fair price")
                    .doesNotContain("justified").doesNotContain("price in fy");
        }
    }

    @Test
    @DisplayName("every refusal and withheld-block reason code is a closed enum value with printed text")
    void everyReadingCarriesItsReason() {
        for (ValuationReading r : sampleReadings()) {
            if (r.status() == ValuationReading.Status.MEASURED) {
                assertThat(r.reasonCode()).isNull();
            } else {
                assertThat(r.reasonCode()).isNotNull();
                assertThat(r.reason()).isNotBlank();
            }
        }
    }

    // ------------------------------------------------------------------------------- helpers

    private static List<String> componentNames(Class<?> record) {
        List<String> out = new ArrayList<>();
        if (!record.isRecord()) return out;
        for (RecordComponent c : record.getRecordComponents()) out.add(c.getName());
        return out;
    }

    @Test
    @DisplayName("the sample covers at least ten distinct reason codes, so the scan above is meaningful")
    void sampleCoversManyReasons() {
        var reasons = sampleReadings().stream().map(ValuationReading::reasonCode)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        assertThat(reasons).hasSizeGreaterThanOrEqualTo(10);
    }
}
