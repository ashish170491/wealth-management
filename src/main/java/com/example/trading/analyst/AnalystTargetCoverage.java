package com.example.trading.analyst;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Which stocks currently carry a recorded analyst target, for the §38.2 coverage row (SPEC §49.7).
 *
 * <p><b>Why this exists as a holder rather than a repository call.</b> {@code ScreeningCoverage}
 * is pure by design and computes the whole coverage vector for a run of three hundred stocks; a
 * repository call per stock per signal would be three hundred queries for one row of a table. So
 * the ledger registers a supplier here at startup and this class caches the answer for the
 * length of a screening pass. It mirrors {@code MacroExposureMap.hasMapping}, which the macro
 * coverage row calls the same way — the difference being that the macro map is a classpath file
 * and this one is a query.
 *
 * <p><b>An unregistered supplier reports "unknown", not "nothing".</b> Those are different facts
 * and confusing them is precisely the mistake the coverage vector exists to prevent: a signal
 * that has never been wired up would otherwise read as 0% coverage, which looks like a measured
 * finding about the feed rather than a missing connection (B-074's lesson).
 */
@Slf4j
public final class AnalystTargetCoverage {

    private AnalystTargetCoverage() {}

    /** Long enough to span one screening pass, short enough that a capture shows up the same day. */
    private static final long TTL_MS = 10 * 60 * 1000L;

    private static volatile Supplier<Set<String>> supplier;
    private static volatile Set<String> cached = Collections.emptySet();
    private static volatile long cachedAt = 0L;

    /** Called once by the ledger service at startup. */
    public static void register(Supplier<Set<String>> source) {
        supplier = source;
        cachedAt = 0L;
    }

    /** True when the ledger has been wired up at all. False means "we do not know", never "none". */
    public static boolean available() {
        return supplier != null;
    }

    /** Whether this stock has a target on the ledger. Bare or qualified symbol both work. */
    public static boolean hasTarget(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        Set<String> set = symbols();
        String bare = symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;
        return set.contains(symbol) || set.contains("NSE:" + bare) || set.contains("BSE:" + bare)
                || set.contains(bare);
    }

    private static Set<String> symbols() {
        Supplier<Set<String>> s = supplier;
        if (s == null) return Collections.emptySet();
        long now = System.currentTimeMillis();
        if (now - cachedAt > TTL_MS) {
            try {
                Set<String> fresh = s.get();
                cached = fresh == null ? Collections.emptySet() : fresh;
            } catch (Exception e) {
                log.debug("Analyst target coverage: refresh failed ({}); serving the previous set.",
                        e.getMessage());
            }
            cachedAt = now;
        }
        return cached;
    }

    /** Tests and an explicit refresh. Nothing schedules this. */
    public static void invalidate() {
        cachedAt = 0L;
    }
}
