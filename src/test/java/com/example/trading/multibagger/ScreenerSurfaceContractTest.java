package com.example.trading.multibagger;

import com.example.trading.persistence.MultibaggerScoreEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract behind the screener's business columns (SPEC 12.5, 2026-09-09).
 *
 * <p>{@code static/js/fundamentals-cells.js} reads these fields by name off the row that
 * {@code DashboardService.screener()} builds by flattening the whole {@link MultibaggerScoreEntity}.
 * There is no build step for that file and no type check across the wire, so a rename here does
 * not fail anything: the cell finds {@code undefined} and draws "not measured" for ever, on a
 * stock the app measured — the {@code CompoundingSurfaceContractTest} failure shape, again.
 *
 * <p>The seven growth-and-ownership fields also have to exist on the {@link MultibaggerScore} DTO
 * (or be derivable from it), or {@code persistScores} cannot fill the column it declares.
 */
class ScreenerSurfaceContractTest {

    /** Every entity field a cell in fundamentals-cells.js or page-screener.js dereferences. */
    private static final Map<String, Class<?>> REQUIRED = required();

    private static Map<String, Class<?>> required() {
        Map<String, Class<?>> m = new LinkedHashMap<>();
        // finQualityCell
        m.put("financialQualityVerdict", String.class);
        m.put("financialQualityScore", Integer.class);
        m.put("interestCoverage", Double.class);
        m.put("ocfToProfitRatio", Double.class);
        // roceCell / leverageCell
        m.put("rocePercent", Double.class);
        m.put("roePercent", Double.class);
        m.put("roaPercent", Double.class);
        m.put("debtToEquity", Double.class);
        m.put("cashConversionRatio", Double.class);
        m.put("capexVerdict", String.class);          // the lender marker (Gotcha 88)
        // growthCell
        m.put("yoyProfitGrowth", Double.class);
        m.put("yoyRevenueGrowth", Double.class);
        m.put("earningsGrowthVerdict", String.class);
        // promoterCell
        m.put("promoterHoldingPct", Double.class);
        m.put("promoterHoldingChangePct", Double.class);
        m.put("promoterPledgePercent", Double.class);
        m.put("fiiHoldingPct", Double.class);
        m.put("diiHoldingPct", Double.class);
        // valuationCell
        m.put("dcfVerdict", String.class);
        m.put("dcfImpliedGrowthPercent", Double.class);
        m.put("dcfHistoricalGrowthPercent", Double.class);
        m.put("dcfExpectationGapPercent", Double.class);
        m.put("peDeviation", Double.class);
        // redFlagsCell
        m.put("forensicFlags", String.class);
        // marketCapCell / sectorCell / ownedCell / scoreCell
        m.put("marketCapCrores", Double.class);
        m.put("marketCapCategory", String.class);
        m.put("industry", String.class);
        m.put("holdingsPnlPercent", Double.class);
        m.put("percentileRank", Double.class);
        m.put("grade", String.class);
        return Map.copyOf(m);
    }

    @Test
    @DisplayName("The screening row carries every field the business cells read, as a nullable wrapper")
    void entityHonoursTheContract() {
        Map<String, Class<?>> actual = Arrays.stream(MultibaggerScoreEntity.class.getDeclaredFields())
                .collect(Collectors.toMap(Field::getName, Field::getType, (a, b) -> a, LinkedHashMap::new));

        assertThat(actual).containsKeys(REQUIRED.keySet().toArray(new String[0]));
        REQUIRED.forEach((name, type) -> assertThat(actual.get(name))
                .as("MultibaggerScoreEntity.%s must be %s or the cell cannot read it", name, type.getSimpleName())
                .isEqualTo(type));
    }

    @Test
    @DisplayName("A primitive would break the contract: an unmeasured figure must stay null, never 0")
    void figuresAreWrappersNotPrimitives() {
        for (Map.Entry<String, Class<?>> e : REQUIRED.entrySet()) {
            assertThat(e.getValue().isPrimitive())
                    .as("%s must be a wrapper so it can be null (Gotcha 21 at the wire)", e.getKey())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("The DTO carries what persistScores writes for growth and ownership")
    void dtoCarriesTheSourceOfEachPersistedField() {
        List<String> dto = Arrays.stream(MultibaggerScore.class.getDeclaredFields())
                .map(Field::getName).toList();
        assertThat(dto).contains("earningsGrowthVerdict", "yoyRevenueGrowth", "yoyProfitGrowth",
                "promoterHoldingChange", "promoterHoldingPct", "fiiHoldingPct", "diiHoldingPct");
    }
}
