package com.example.trading.multibagger;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.watchlist.WatchlistItemView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract of the sector-relative lens (SPEC §54) across every surface that draws it.
 *
 * <p>{@code sectorStandingCell} in {@code static/js/sector-standing.js} reads five fields by name.
 * Nothing type-checks the wire, so a rename on one surface does not fail - the cell just draws "not
 * measured" for ever on a stock the app ranked perfectly well (the {@link CompoundingSurfaceContractTest}
 * failure shape). The screening-row map is pinned in {@code DiscoverySurfaceContractTest}.
 */
class SectorStandingSurfaceContractTest {

    private static final Map<String, Class<?>> REQUIRED = required();

    private static Map<String, Class<?>> required() {
        Map<String, Class<?>> m = new LinkedHashMap<>();
        m.put("sectorStanding", String.class);
        m.put("sectorStandingReason", String.class);
        m.put("sectorStandingAbove", Integer.class);
        m.put("sectorStandingRanked", Integer.class);
        m.put("sectorStandingPeers", Integer.class);
        return Map.copyOf(m);
    }

    @Test
    @DisplayName("The watchlist row carries every field the shared renderer reads, as nullable wrappers")
    void watchlist() {
        Map<String, Class<?>> actual = Arrays.stream(WatchlistItemView.class.getRecordComponents())
                .collect(Collectors.toMap(RecordComponent::getName, RecordComponent::getType,
                        (a, b) -> a, LinkedHashMap::new));
        REQUIRED.forEach((name, type) -> assertThat(actual.get(name))
                .as("WatchlistItemView.%s must be %s", name, type.getSimpleName()).isEqualTo(type));
    }

    @Test
    @DisplayName("The portfolio row carries every field the shared renderer reads, as nullable wrappers")
    void holdings() {
        REQUIRED.forEach((name, type) -> {
            try {
                Field f = HoldingsEntity.class.getDeclaredField(name);
                assertThat(f.getType()).as("HoldingsEntity.%s", name).isEqualTo(type);
                assertThat(f.isAnnotationPresent(jakarta.persistence.Transient.class))
                        .as("HoldingsEntity.%s must be @Transient - computed on read, never stored", name)
                        .isTrue();
            } catch (NoSuchFieldException e) {
                throw new AssertionError("HoldingsEntity is missing " + name, e);
            }
        });
    }

    @Test
    @DisplayName("Contributes zero points: no field in the lens could carry a weight or a bonus")
    void contributesZeroPointsIsCheckable() {
        Pattern banned = Pattern.compile("(?i)actionable|bonus|weight|points|composite");
        List<Class<?>> types = List.of(SectorStanding.class, SectorStanding.Row.class,
                SectorStanding.Result.class, SectorStanding.MetricReading.class, SectorStandingService.class);
        for (Class<?> c : types) {
            for (Field f : c.getDeclaredFields()) {
                assertThat(banned.matcher(f.getName()).find())
                        .as("%s.%s looks like a scoring hook - the lens must contribute nothing", c.getSimpleName(), f.getName())
                        .isFalse();
            }
        }
    }
}
