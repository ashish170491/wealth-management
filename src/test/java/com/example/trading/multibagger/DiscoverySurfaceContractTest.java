package com.example.trading.multibagger;

import com.example.trading.persistence.MultibaggerScoreEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract behind the discovery screen (SPEC 47, B-099).
 *
 * <p>{@code static/js/page-discovery.js} reads these fields by name off the same row that
 * {@code DashboardService.screener()} builds for the screener — the map its javadoc describes as
 * being shaped "for the screener <b>and discovery</b>". {@link ScreenerSurfaceContractTest} already
 * pins the business figures the two screens share; this pins the fields discovery reads that the
 * screener does not, so neither list has to be the superset of the other.
 *
 * <p>Why a test rather than trust: there is no build step for that file and nothing type-checks the
 * wire, so a rename does not fail — the cell finds {@code undefined} and draws the striped "not
 * measured" marker for ever, on a stock the app measured. That is quieter than Gotcha 44's failure
 * and just as wrong on a screen someone picks businesses from, because "not measured" is an
 * entirely ordinary thing to see. A deliberate rename should fail this test <i>and</i> change
 * {@code page-discovery.js} in the same commit.
 */
class DiscoverySurfaceContractTest {

    /**
     * Entity fields only {@code page-discovery.js} dereferences, with the type each must carry.
     *
     * <p>{@code compositeScore} and {@code inHoldings} are deliberately absent: both are
     * primitives, always written, and are asserted separately below.
     */
    private static final Map<String, Class<?>> REQUIRED = required();

    private static Map<String, Class<?>> required() {
        Map<String, Class<?>> m = new LinkedHashMap<>();
        // The contrarian lane (SPEC 47): the fall, the turnaround read, the quality gate's inputs.
        m.put("turnaroundVerdict", String.class);
        m.put("priceVs52WeekHigh", Double.class);
        m.put("priceVs52WeekLow", Double.class);
        // buyabilityCell — "how many days to build a position", SPEC 12.9.
        m.put("liquidityTier", String.class);
        m.put("liquidityAdv20d", Double.class);
        m.put("circuitDaysLast60", Integer.class);
        // Under the Radar (SPEC 12.10) and the insider column.
        m.put("underDiscoveryScore", Integer.class);
        m.put("insiderPulseVerdict", String.class);
        m.put("insiderNetBuy90dPct", Double.class);
        m.put("insiderPulseScore", Integer.class);
        // scoreCell's holding sub-line.
        m.put("holdingsPnlPercent", Double.class);
        return Map.copyOf(m);
    }

    /**
     * Keys the page reads that are <b>not</b> entity fields — they are added to the row map by
     * {@code DashboardService.withBuyTiming}. Reflection cannot see them, so the source is read
     * instead. Crude on purpose: this boundary has no compiler, which is the whole reason the
     * fields keep going missing quietly.
     */
    private static final List<String> DERIVED_KEYS = List.of(
            "sector",                   // B-096/B-098: never the literal "Other"
            "rangePosition52w",         // B-062: half a position is not a position
            "scoreDelta30dRelative",    // B-064: a raw delta means nothing
            "scoreDelta30d",
            "scoreDelta30dFrom",
            "universeShift30d",
            "compounding",              // SPEC 41, also pinned by CompoundingSurfaceContractTest
            "buyTiming",                // SPEC 37.3, the one shared rule table (Gotcha 85)
            "suggestedEntryPrice");

    @Test
    @DisplayName("The screening row carries every discovery-only field, as a nullable wrapper")
    void entityHonoursTheContract() {
        Map<String, Class<?>> actual = Arrays.stream(MultibaggerScoreEntity.class.getDeclaredFields())
                .collect(Collectors.toMap(Field::getName, Field::getType, (a, b) -> a, LinkedHashMap::new));

        assertThat(actual).containsKeys(REQUIRED.keySet().toArray(new String[0]));
        REQUIRED.forEach((name, type) -> assertThat(actual.get(name))
                .as("MultibaggerScoreEntity.%s must be %s or the discovery cell cannot read it", name, type.getSimpleName())
                .isEqualTo(type));
    }

    @Test
    @DisplayName("An unmeasured discovery figure stays null, never 0 — so none of them is a primitive")
    void figuresAreWrappersNotPrimitives() {
        for (Map.Entry<String, Class<?>> e : REQUIRED.entrySet()) {
            assertThat(e.getValue().isPrimitive())
                    .as("%s must be a wrapper so it can be null (Gotcha 21/33 at the wire): UNKNOWN "
                            + "liquidity is not THIN, and an unscored under-discovery is not a zero", e.getKey())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("DashboardService still adds every derived key the page renders")
    void derivedKeysAreStillPut() throws IOException {
        Path src = Path.of("src", "main", "java", "com", "example", "trading", "dashboard", "DashboardService.java");
        assertThat(src).as("DashboardService source must be readable to check the un-compiled wire").exists();
        String body = Files.readString(src, StandardCharsets.UTF_8);

        for (String key : DERIVED_KEYS) {
            assertThat(body)
                    .as("DashboardService must put(\"%s\") — page-discovery.js reads it, and dropping it "
                            + "draws \"not measured\" rather than failing", key)
                    .contains("\"" + key + "\"");
        }
    }

    @Test
    @DisplayName("The two always-written fields stay primitive, so the page never has to test them for null")
    void alwaysWrittenFieldsStayPrimitive() {
        Map<String, Class<?>> actual = Arrays.stream(MultibaggerScoreEntity.class.getDeclaredFields())
                .collect(Collectors.toMap(Field::getName, Field::getType, (a, b) -> a, LinkedHashMap::new));
        assertThat(actual.get("compositeScore")).isEqualTo(int.class);
        assertThat(actual.get("inHoldings")).isEqualTo(boolean.class);
    }
}
