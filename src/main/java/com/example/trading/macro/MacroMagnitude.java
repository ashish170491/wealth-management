package com.example.trading.macro;

import java.util.Locale;
import java.util.Optional;

/**
 * How big the move was, as the news described it. Coarse on purpose (SPEC 48.2).
 *
 * <p>This is not a percentage and must never become one. The input is a headline, and a headline's
 * "surges" and "edges up" carry real information about scale while carrying none about magnitude to
 * two decimal places. Three buckets is what the source can honestly support; a number here would
 * look measured and be invented.
 *
 * <p>It contributes nothing to the verdict - a {@code SMALL} rise and a {@code LARGE} rise are both
 * headwinds for the same business. It is shown so the investor can weigh a reading themselves, and
 * it decides ordering when several events compete for one line of a report.
 */
public enum MacroMagnitude {

    SMALL("small"),
    MODERATE("moderate"),
    LARGE("large");

    private final String label;

    MacroMagnitude(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Higher is bigger. Used to keep the larger of two merged reports of the same event. */
    public int rank() {
        return ordinal();
    }

    public static Optional<MacroMagnitude> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (MacroMagnitude m : values()) {
            if (m.name().equals(key)) return Optional.of(m);
        }
        return Optional.empty();
    }
}
