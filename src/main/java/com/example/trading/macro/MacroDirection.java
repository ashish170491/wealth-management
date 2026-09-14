package com.example.trading.macro;

import java.util.Locale;
import java.util.Optional;

/**
 * Which way a {@link MacroFactor} moved. Always about the <i>factor</i>, never about a share price
 * and never about the market (SPEC 48.2).
 *
 * <p>Read it with {@link MacroFactor#risesMeans()}: {@code USDINR} + {@code UP} is "the rupee
 * weakened", not "the rupee went up". Defining every factor as a quantity and every event as a
 * direction on that quantity is what lets one event be a tailwind for an exporter and a headwind
 * for an importer at the same time, which is the thing a sentiment score structurally cannot say.
 */
public enum MacroDirection {

    /** The factor rose - as spelled out by {@link MacroFactor#risesMeans()}. */
    UP("rose"),
    /** The factor fell. */
    DOWN("fell");

    private final String pastTense;

    MacroDirection(String pastTense) {
        this.pastTense = pastTense;
    }

    /** For reason sentences: "Crude oil rose sharply". */
    public String pastTense() {
        return pastTense;
    }

    public MacroDirection opposite() {
        return this == UP ? DOWN : UP;
    }

    public static Optional<MacroDirection> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "UP", "RISE", "RISES", "ROSE", "HIGHER", "+1" -> Optional.of(UP);
            case "DOWN", "FALL", "FALLS", "FELL", "LOWER", "-1" -> Optional.of(DOWN);
            default -> Optional.empty();
        };
    }
}
