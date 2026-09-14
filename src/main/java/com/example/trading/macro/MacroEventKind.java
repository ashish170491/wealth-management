package com.example.trading.macro;

import java.util.Locale;
import java.util.Optional;

/**
 * Whether the event was on the calendar (SPEC 48.2).
 *
 * <p>The distinction matters for a long-term investor in one specific way: a scheduled event was
 * knowable in advance, so the businesses it touches have had time to plan for it and its arrival
 * changes less than its <i>content</i> does. An unscheduled one - a border incident, an emergency
 * duty, a plant ban - lands on a business that had made no arrangements.
 *
 * <p>It is not a measure of importance and does not enter any verdict. A scheduled rate decision
 * can matter far more to a leveraged builder than a surprise headline about a distant war.
 */
public enum MacroEventKind {

    /** Was on the published calendar: an RBI policy date, the Budget, an FOMC meeting, a CPI print. */
    SCHEDULED("scheduled"),
    /** Was not. */
    SURPRISE("surprise");

    private final String label;

    MacroEventKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<MacroEventKind> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (MacroEventKind k : values()) {
            if (k.name().equals(key)) return Optional.of(k);
        }
        return Optional.empty();
    }
}
