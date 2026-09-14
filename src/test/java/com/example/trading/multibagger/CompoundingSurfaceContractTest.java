package com.example.trading.multibagger;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.watchlist.WatchlistItemView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract the compounding lens (SPEC 41) shares across four screens.
 *
 * <p>The screener, the stock page, the portfolio and the watchlist all render the verdict with the
 * <b>same</b> function — {@code compoundingCell} in {@code static/js/compounding.js} — which reads
 * five fields by name. There is no build step for that file and no type checking across the wire,
 * so a rename or a typo on any one surface does not fail anything: the cell simply finds
 * {@code undefined} and draws the striped "not measured" marker, for ever, on a stock the app
 * measured perfectly well.
 *
 * <p>That is this codebase's dominant failure shape pointed the other way. Gotcha 44 warns that an
 * unmeasured value must never be drawn as measured; the mirror is just as bad on a screen someone
 * makes decisions from, and it is quieter — nothing throws, nothing logs, and "not measured" is a
 * perfectly ordinary thing to see. So the names are asserted here rather than trusted.
 *
 * <p>A deliberate rename should therefore fail this test <i>and</i> change {@code compounding.js}
 * in the same commit. That is the whole point of it failing.
 */
class CompoundingSurfaceContractTest {

    /**
     * Exactly what {@code compoundingCell(row)} dereferences, with the type each field must carry.
     * {@code compoundingReason} is the tooltip, the two counts render "3/5", and the year count
     * appends the track-record caveat.
     */
    private static final Map<String, Class<?>> REQUIRED = required();

    private static Map<String, Class<?>> required() {
        Map<String, Class<?>> m = new LinkedHashMap<>();
        m.put("compounding", String.class);
        m.put("compoundingReason", String.class);
        m.put("compoundingPassed", Integer.class);
        m.put("compoundingApplicable", Integer.class);
        m.put("compoundingYearsOfAccounts", Integer.class);
        return Map.copyOf(m);
    }

    @Test
    @DisplayName("The watchlist row carries every field the shared renderer reads")
    void watchlistViewHonoursTheContract() {
        Map<String, Class<?>> actual = Arrays.stream(WatchlistItemView.class.getRecordComponents())
                .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType,
                        (a, b) -> a, LinkedHashMap::new));

        assertThat(actual).containsKeys(REQUIRED.keySet().toArray(new String[0]));
        REQUIRED.forEach((name, type) -> assertThat(actual.get(name))
                .as("WatchlistItemView.%s must be %s or the cell cannot read it",
                        name, type.getSimpleName())
                .isEqualTo(type));
    }

    @Test
    @DisplayName("The portfolio row carries every field the shared renderer reads")
    void holdingsEntityHonoursTheContract() {
        List<String> fields = Arrays.stream(HoldingsEntity.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList();
        assertThat(fields).containsAll(REQUIRED.keySet());

        REQUIRED.forEach((name, type) -> {
            try {
                assertThat(HoldingsEntity.class.getDeclaredField(name).getType())
                        .as("HoldingsEntity.%s must be %s", name, type.getSimpleName())
                        .isEqualTo(type);
            } catch (NoSuchFieldException e) {
                throw new AssertionError("HoldingsEntity is missing " + name, e);
            }
        });
    }

    @Test
    @DisplayName("A primitive would break the contract, because a null verdict must stay null")
    void countsAreWrappersNotPrimitives() {
        // An `int` unboxes a "never screened" null to 0, and "0 of 0 gates passed" reads as a
        // failing business rather than an unexamined one - Gotcha 21 at the wire boundary, and
        // the same mistake as the Integer-not-null migration in the legacy bug list.
        for (String name : List.of("compoundingPassed", "compoundingApplicable",
                                   "compoundingYearsOfAccounts")) {
            assertThat(component(name).isPrimitive())
                    .as("WatchlistItemView.%s must be a wrapper so it can be null", name)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Each surface records which symbol answered, so a reading can be traced")
    void provenanceIsCarried() {
        // Gotcha 84/107: a BSE-held position reads its NSE screening history, which is correct
        // rather than approximate - but only if the row says so. The watchlist stamps the date,
        // the portfolio stamps the symbol; both exist so a verdict can be argued with.
        assertThat(component("compoundingAsOf")).isEqualTo(java.time.LocalDate.class);
        assertThat(Arrays.stream(HoldingsEntity.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList())
                .contains("compoundingFrom");
    }

    private static Class<?> component(String name) {
        return Arrays.stream(WatchlistItemView.class.getRecordComponents())
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("WatchlistItemView has no " + name))
                .getType();
    }
}
