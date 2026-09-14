package com.example.trading.macro;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.watchlist.WatchlistItemView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
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
 * Pins the wire contract macro exposure (SPEC §48) shares across five screens.
 *
 * <p>The portfolio, the screener, the watchlist, discovery and the stock page all render the
 * reading with the <b>same</b> function - {@code macroExposureCell} in
 * {@code static/js/macro-cells.js} - which reads five fields by name. There is no build step for
 * that file and nothing type-checks the wire, so a rename or a typo on any one surface fails
 * nothing: the cell finds {@code undefined} and draws the striped "not measured" marker for ever,
 * on a stock the app read perfectly well.
 *
 * <p>That is the {@code CompoundingSurfaceContractTest} failure shape, and it is worse here. On
 * that lens "not measured" means the accounts could not be read. Here it means <i>the app has no
 * rule for this business</i> - which sits one badge away from {@code NOT_EXPOSED}, meaning it
 * looked and nothing applies. A broken field name silently converts every measured all-clear into
 * a blind spot, or the reverse, and nothing anywhere reports it.
 *
 * <p>A deliberate rename should fail this test <i>and</i> change {@code macro-cells.js} in the
 * same commit. That is the point of it failing.
 */
class MacroSurfaceContractTest {

    /** Exactly what {@code macroExposureCell(row)} dereferences, with the type it must carry. */
    private static final Map<String, Class<?>> REQUIRED = required();

    private static Map<String, Class<?>> required() {
        Map<String, Class<?>> m = new LinkedHashMap<>();
        m.put("macroExposure", String.class);
        m.put("macroExposureStrength", String.class);
        m.put("macroExposureReasons", List.class);
        m.put("macroExposureEvents", Integer.class);
        m.put("macroExposureFrom", String.class);
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
    @DisplayName("The event count is a wrapper, because no events and zero events differ")
    void eventCountIsNotAPrimitive() {
        // An `int` unboxes "nothing applies" to 0, and a 0 in the Events column is indistinguishable
        // from a stock the app has no rule for - collapsing precisely the distinction this feature
        // exists to keep (Gotcha 21 at the wire boundary).
        assertThat(component("macroExposureEvents").isPrimitive())
                .as("WatchlistItemView.macroExposureEvents must be a wrapper so it can be null")
                .isFalse();
    }

    @Test
    @DisplayName("Each surface records which symbol answered, so a reading can be traced")
    void provenanceIsCarried() {
        // Gotcha 84/107: a BSE-held position reads its NSE screening history, which is correct
        // rather than approximate - but only if the row says so.
        assertThat(component("macroExposureFrom")).isEqualTo(String.class);
        assertThat(Arrays.stream(HoldingsEntity.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList())
                .contains("macroExposureFrom");
    }

    @Test
    @DisplayName("Every factor has a human label in format.js, so none renders as \"Usdinr\"")
    void everyFactorIsLabelledInTheUi() throws IOException {
        // humanLabel() falls back to Title Case, which turns USDINR into "Usdinr" and
        // REGULATORY_PHARMA_USFDA into "Regulatory Pharma Usfda" - an enum name with the
        // shouting taken out, which is not a human label (SPEC 21 rule 3). The fallback never
        // throws, so without this assertion a new factor reaches the investor mis-spelled.
        String formatJs = Files.readString(
                Path.of("src/main/resources/static/js/format.js"), StandardCharsets.UTF_8);

        for (MacroFactor f : MacroFactor.values()) {
            assertThat(formatJs)
                    .as("format.js LABELS has no entry for %s, so the UI would Title-Case it", f)
                    .contains(f.name() + ":");
        }
    }

    @Test
    @DisplayName("The vocabulary contains no instruction to transact")
    void verdictsNeverTellTheInvestorToTrade() {
        // SPEC 20 rule 10: a second buy/sell vocabulary is barred, and this feature is the one
        // most likely to grow one - a headwind reads like a sell to anyone who has not been told
        // otherwise. Pinned on the enum so a later addition has to be argued, not slipped in.
        List<String> banned = List.of("BUY", "SELL", "EXIT", "ADD", "HOLD", "AVOID",
                "ACCUMULATE", "REDUCE", "BOOK", "TRIM");
        for (MacroExposureRead.Verdict v : MacroExposureRead.Verdict.values()) {
            for (String word : banned) {
                assertThat(v.name())
                        .as("%s reads as an instruction to transact", v)
                        .doesNotContain(word);
            }
        }
    }

    private static Class<?> component(String name) {
        return Arrays.stream(WatchlistItemView.class.getRecordComponents())
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("WatchlistItemView has no " + name))
                .getType();
    }
}
