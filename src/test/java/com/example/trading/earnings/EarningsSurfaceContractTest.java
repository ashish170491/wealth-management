package com.example.trading.earnings;

import com.example.trading.persistence.HoldingsEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract between the result read and the screens that draw it (SPEC §50.5).
 *
 * <p><b>Why a test for field names.</b> {@code resultCell} in {@code static/js/earnings-cells.js}
 * reads a dozen fields off a holdings row by name. There is no build step for that file and
 * nothing type-checks the wire, so a rename fails nothing: the cell finds {@code undefined} and
 * draws the unmeasured marker for ever, on a company whose results the app captured perfectly
 * well. That is the {@code CompoundingSurfaceContractTest} failure shape, and it is quieter here
 * than most, because "not measured" is an ordinary thing to see in this column.
 *
 * <p>A deliberate rename should fail this test <i>and</i> change {@code earnings-cells.js} in the
 * same commit. That is the point of it failing.
 */
class EarningsSurfaceContractTest {

    /** Exactly what the portfolio cell and the stock-page panel dereference, and the type each needs. */
    private static final Map<String, Class<?>> REQUIRED = required();

    private static Map<String, Class<?>> required() {
        Map<String, Class<?>> m = new LinkedHashMap<>();
        m.put("resultVerdict", String.class);
        m.put("resultQuarter", String.class);
        m.put("resultHeadline", String.class);
        // The day the company published, not the quarter end. Without it the cell cannot say how
        // old the reading is, and a result six weeks old renders identically to one from today.
        m.put("resultPublishedOn", LocalDate.class);
        // Double, never double: null means the comparison could not be made — most often because
        // the year-ago quarter is filed on a different basis — and 0.0 means it was made and came
        // out flat. A primitive would collapse a refusal into a measurement (SPEC §21 rule 7).
        m.put("resultRevenueYoyPercent", Double.class);
        m.put("resultProfitYoyPercent", Double.class);
        m.put("resultMarginDeltaPp", Double.class);
        // Both halves of the coverage fraction, so a surface prints "3 of 4" rather than implying
        // a completed screen (Gotcha 44).
        m.put("resultMeasuredSignals", Integer.class);
        m.put("resultTotalSignals", Integer.class);
        m.put("resultRevised", Boolean.class);
        m.put("resultFrom", String.class);
        m.put("nextResultStatus", String.class);
        m.put("nextResultText", String.class);
        return Map.copyOf(m);
    }

    @Test
    @DisplayName("The portfolio row carries every field the result cell reads, as nullable wrappers")
    void holdingsRowHonoursTheContract() {
        Map<String, Class<?>> actual = Arrays.stream(HoldingsEntity.class.getDeclaredFields())
                .collect(Collectors.toMap(Field::getName, Field::getType, (a, b) -> a, LinkedHashMap::new));

        assertThat(actual).containsKeys(REQUIRED.keySet().toArray(new String[0]));
        REQUIRED.forEach((name, type) -> assertThat(actual.get(name))
                .as("%s must be %s so that 'not captured' and a measured zero stay distinct",
                        name, type.getSimpleName())
                .isEqualTo(type));
    }

    @Test
    @DisplayName("Every result field on the row is @Transient — nothing here is stored")
    void nothingIsPersisted() {
        for (String name : REQUIRED.keySet()) {
            Field f = Arrays.stream(HoldingsEntity.class.getDeclaredFields())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertThat(f.isAnnotationPresent(jakarta.persistence.Transient.class))
                    .as("%s is computed on read; a stored copy can disagree with the row it "
                            + "describes after the next capture", name)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("There is no actionable flag, because there is no bonus to switch on")
    void contributesNothingToAnyScore() {
        // Shadow mode exists for a signal that would otherwise steer the portfolio before it could
        // be judged (Gotcha 30). A ledger that feeds no score has nothing to shadow — and the
        // absence of a flag is what makes that claim checkable rather than asserted in a comment.
        boolean hasActionableFlag = Arrays.stream(EarningsConfig.class.getDeclaredFields())
                .anyMatch(f -> f.getName().toLowerCase(Locale.ROOT).contains("actionable")
                        || f.getName().toLowerCase(Locale.ROOT).contains("bonus")
                        || f.getName().toLowerCase(Locale.ROOT).contains("weight"));
        assertThat(hasActionableFlag).isFalse();
    }

    @Test
    @DisplayName("The capture switch ships on — a record that steers nothing needs no shadow")
    void captureDefaultsOn() {
        assertThat(new EarningsConfig().isCaptureDuringScreening()).isTrue();
    }

    @Test
    @DisplayName("The next-result vocabulary carries no instruction to transact either")
    void calendarVocabularyIsDescriptive() {
        for (EarningsCalendar.Status s : EarningsCalendar.Status.values()) {
            assertThat(s.name().toLowerCase(Locale.ROOT))
                    .doesNotContain("buy").doesNotContain("sell").doesNotContain("exit");
        }
    }
}
