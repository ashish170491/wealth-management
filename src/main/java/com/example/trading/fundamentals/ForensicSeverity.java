package com.example.trading.fundamentals;

import java.util.Locale;

/**
 * The one parser for the {@code CODE:severity} tokens the forensic screen persists (SPEC §32.4).
 *
 * <p>Why it is shared rather than copied: two buy-timing engines read the same
 * {@code forensicFlags} string and, until 2026-08-28, graded it differently — the screener
 * respected severity (Gotcha 77) while the watchlist flattened every non-INFO token into AVOID.
 * The same three holdings were therefore "worth a look" on one screen and "avoid" on another, on
 * a medium receivables note. A duplicated parser is how one gets fixed and the other does not.
 *
 * <h2>Unknown is not INFO</h2>
 * A token with no parseable severity is {@link Level#UNKNOWN}, and callers treat it as a caution
 * rather than silently ignoring it. Downgrading an unrecognised flag to "informational" is the
 * same mistake as reading a null as a zero (Gotcha 21/44/68): the screen said something, and we
 * could not tell how loudly.
 */
public final class ForensicSeverity {

    private ForensicSeverity() {}

    /** Ordered least to most serious, so {@link #worst} can simply take the maximum. */
    public enum Level { NONE, INFO, UNKNOWN, MEDIUM, HIGH }

    /**
     * The most serious severity present across all tokens.
     * {@link Level#NONE} for null, blank, or an explicit {@code NONE}/{@code CLEAN} marker —
     * which means "the screen ran and found nothing", not "the screen never ran". Whether it ran
     * at all is a separate question the caller must answer from a null flags string
     * (Gotcha 44: an empty flag list is usually "nothing was checked").
     */
    public static Level worst(String flags) {
        if (flags == null || flags.isBlank()) return Level.NONE;
        Level worst = Level.NONE;
        for (String token : flags.split("[,;|]")) {
            Level l = severityOf(token);
            if (l.ordinal() > worst.ordinal()) worst = l;
        }
        return worst;
    }

    /** Severity of a single {@code CODE:severity} token. */
    public static Level severityOf(String token) {
        if (token == null) return Level.NONE;
        String t = token.trim();
        if (t.isEmpty()) return Level.NONE;
        String upper = t.toUpperCase(Locale.ROOT);
        if (upper.equals("NONE") || upper.equals("CLEAN")) return Level.NONE;

        int colon = t.lastIndexOf(':');
        String suffix = colon < 0 ? "" : t.substring(colon + 1).trim().toUpperCase(Locale.ROOT);
        return switch (suffix) {
            case "HIGH" -> Level.HIGH;
            case "MEDIUM" -> Level.MEDIUM;
            case "INFO" -> Level.INFO;
            // No severity suffix: an INFO_-prefixed code is still informational (the older
            // un-suffixed convention); anything else is a flag of unknown weight, not a free pass.
            default -> upper.startsWith("INFO") ? Level.INFO : Level.UNKNOWN;
        };
    }

    /**
     * The first flag at {@code level}, human-readable and without its severity suffix
     * ({@code RECEIVABLES:MEDIUM} → {@code receivables}). Null when no token matches, so a caller
     * can never print a severity it did not find.
     */
    public static String firstAt(String flags, Level level) {
        if (flags == null || flags.isBlank()) return null;
        for (String token : flags.split("[,;|]")) {
            if (severityOf(token) == level) return humanise(token);
        }
        return null;
    }

    /** A flag name fit for a sentence the investor reads. */
    private static String humanise(String token) {
        String t = token.trim();
        int colon = t.lastIndexOf(':');
        String code = colon > 0 ? t.substring(0, colon).trim() : t;
        return code.isEmpty() ? t : code.replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
