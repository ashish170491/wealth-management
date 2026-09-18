package com.example.trading.earnings;

import com.example.trading.holdings.SymbolVariants;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Which companies have a captured quarterly result, for the §38.2 coverage row (SPEC §50.7).
 *
 * <h2>Why the coverage row is the point, not an afterthought</h2>
 * Three signals in this app produced a value that was never measured on anything, and each ran
 * for months looking fine: Institutional Interest constant at 40, monthly RSI constant at 50
 * (B-060), Insider Pulse with a verdict for <b>no stock at all</b> (B-074). Every one was found
 * by the same two questions — measured on how many stocks, and did the answer vary. A result read
 * that cannot say how many companies it covers is not reviewable (SPEC §20 rule 9c), so this row
 * ships with the feature rather than after it.
 *
 * <p><b>NOT_APPLICABLE is never emitted.</b> Every listed company files quarterly results; a
 * company with none captured is this app's blind spot, not an exemption the business earned. That
 * is the same rule §48 applies to macro exposure, and the inverse of the mistake that would let a
 * fifth-written map report full coverage for ever.
 *
 * <p>Structure mirrors {@code AnalystTargetCoverage}: {@code ScreeningCoverage} is pure and runs
 * over three hundred stocks, so a repository call per stock per signal would be three hundred
 * queries for one table row. The service registers a supplier at startup and the answer is cached
 * for the length of a screening pass.
 *
 * <p><b>An unregistered supplier reports "unknown", not "nothing".</b> A signal that has never
 * been wired up would otherwise read as 0% coverage, which looks like a measured finding about
 * the feed rather than a missing connection.
 */
@Slf4j
public final class QuarterlyResultCoverage {

    private QuarterlyResultCoverage() {
    }

    /** Long enough to span one screening pass, short enough that a capture shows up the same day. */
    private static final long TTL_MS = 10 * 60 * 1000L;

    private static volatile Supplier<Set<String>> supplier;
    private static volatile Set<String> cached = Collections.emptySet();
    private static volatile long cachedAt = 0L;

    /** Called once by {@link QuarterlyResultService} at startup. */
    public static void register(Supplier<Set<String>> source) {
        supplier = source;
        cachedAt = 0L;
    }

    /** True when the ledger has been wired up at all. False means "we do not know", never "none". */
    public static boolean available() {
        return supplier != null;
    }

    /**
     * Whether this company has a captured quarter. Any spelling works — rows are keyed on the
     * bare NSE symbol, so {@code NSE:INFY}, {@code BSE:INFY} and {@code INFY} all resolve.
     */
    public static boolean hasResult(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        return symbols().contains(SymbolVariants.base(symbol));
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
                log.debug("Quarterly result coverage: refresh failed ({}); serving the previous set.",
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
