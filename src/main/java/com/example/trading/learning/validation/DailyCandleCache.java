package com.example.trading.learning.validation;

import com.example.trading.marketdata.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One process-wide cache of daily candles for point-in-time return measurement.
 *
 * <p><b>Why this is a shared component rather than a field on its owner.</b> Every Kite request
 * in this JVM passes through a single ~2.9 requests-per-second gate (B-027), and exhausting it
 * has twice starved the afternoon scheduled jobs (B-014, B-049). Two independent caches over
 * the same symbols would double the cost of the same information for no benefit — which is
 * exactly the mistake recorded in Gotcha 52, where the response-buffer lesson was learned on one
 * WebClient and not applied to the other. The per-dimension Information Coefficient panel and
 * the walk-forward harness ask the same question of the same symbols, so they share one cache.
 *
 * <p><b>The window is part of the key, not just the value.</b> A cache keyed on symbol alone is
 * a trap: an entry warmed with a 400-day window satisfies a later request needing 700 days, and
 * every date beyond the window silently returns no price. The sample then quietly shrinks and
 * the missing observations are the oldest ones — the very periods a walk-forward split depends
 * on. So each entry records the window it was fetched over and is re-fetched when a caller needs
 * an earlier start. A short entry is widened, never reused as though it were complete.
 *
 * <p>Prices are read from daily closes on or before the target date, walking back up to
 * {@link #MAX_BACKWARD_DAYS} calendar days over weekends and holidays. It never looks forward:
 * a return measured from a candle after its target date would be look-ahead, which is the defect
 * that makes a backtest agree with itself and disagree with reality.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DailyCandleCache {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_DATE;

    /** Entries older than this are re-fetched. Ample for horizons measured in weeks. */
    private static final long TTL_MS = 6 * 60 * 60 * 1000L;

    /**
     * Pause between Kite historical-data calls. Additional to the process-wide gate in
     * {@code KiteBrokerClient}, not a replacement for it — this one keeps a long warm from
     * monopolising the shared budget while a scheduled job is waiting behind it.
     */
    private static final long CALL_SPACING_MS = 350;

    /**
     * How far back a close may be taken when the target date has no candle. Five calendar days
     * covers a weekend plus a public holiday. Beyond that the observation is dropped rather than
     * stretched: a price from a week earlier is not the price on the date being measured.
     */
    static final int MAX_BACKWARD_DAYS = 5;

    private final MarketDataService marketDataService;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** @param windowStart the earliest date this entry's candles can answer for */
    private record Entry(List<Map<String, Object>> candles, LocalDate windowStart, long fetchedAt) {
        boolean staleAt(long now) {
            return now - fetchedAt > TTL_MS;
        }

        boolean covers(LocalDate from) {
            return !windowStart.isAfter(from);
        }
    }

    /** What a warm pass did, so callers can report it rather than assume it worked. */
    public record WarmResult(int requested, int fetched, int reused, int widened, int failed) {
    }

    /**
     * Ensure every symbol has candles reaching back to {@code from}.
     *
     * <p>Sequential and paced on purpose. Parallel fetching would not go faster — the shared
     * broker gate serialises it anyway — and would take the whole budget rather than leaving
     * room for anything else running at the same time.
     *
     * @param from earliest date any caller will ask about; entries not reaching it are re-fetched
     */
    public WarmResult warm(Collection<String> symbols, LocalDate from) {
        Set<String> unique = new LinkedHashSet<>(symbols == null ? List.of() : symbols);
        LocalDate to = LocalDate.now().plusDays(10);
        String fromStr = from.format(ISO_DATE);
        String toStr = to.format(ISO_DATE);
        long now = System.currentTimeMillis();

        int fetched = 0, reused = 0, widened = 0, failed = 0;

        for (String symbol : unique) {
            Entry existing = cache.get(symbol);
            if (existing != null && !existing.staleAt(now) && existing.covers(from)) {
                reused++;
                continue;
            }
            boolean isWiden = existing != null && !existing.covers(from);
            try {
                List<Map<String, Object>> candles =
                        marketDataService.getRecentCandles(symbol, "day", fromStr, toStr);
                if (candles == null || candles.isEmpty()) {
                    failed++;
                    continue;
                }
                cache.put(symbol, new Entry(candles, from, System.currentTimeMillis()));
                if (isWiden) widened++; else fetched++;
                Thread.sleep(CALL_SPACING_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Candle cache warm interrupted after {} symbols. The caller's sample is "
                        + "incomplete and is missing whichever symbols came later in the list — "
                        + "not a random subset.", fetched + widened);
                return new WarmResult(unique.size(), fetched, reused, widened, failed);
            } catch (Exception e) {
                failed++;
                log.debug("Candle cache warm failed for {}: {}", symbol, e.getMessage());
            }
        }

        if (failed > 0) {
            log.info("Candle cache warm: {} of {} symbols have no usable candles and will be "
                            + "absent from any measurement built on this cache — absent, not zero.",
                    failed, unique.size());
        }
        log.info("Candle cache warm [from {}]: {} fetched, {} reused, {} widened, {} failed.",
                from, fetched, reused, widened, failed);
        return new WarmResult(unique.size(), fetched, reused, widened, failed);
    }

    /**
     * The daily close on {@code target}, or the most recent close within
     * {@link #MAX_BACKWARD_DAYS} before it.
     *
     * @return null when the symbol has no entry, the entry does not reach back to the target, or
     *         no candle falls in the window. Null means unmeasured and must not be defaulted:
     *         a price of 0.0 is "no price" everywhere in this codebase (Gotcha 22), and here
     *         even that would be too generous — the caller must drop the observation.
     */
    public Double closeOnOrBefore(String symbol, LocalDate target) {
        Entry entry = cache.get(symbol);
        if (entry == null || target == null) return null;
        // An entry that starts after the date being asked about cannot answer it. Returning null
        // rather than the earliest candle it happens to hold is the whole point of tracking the
        // window: the alternative silently substitutes a price from a different period.
        if (!entry.covers(target.minusDays(MAX_BACKWARD_DAYS))) return null;

        LocalDate earliest = target.minusDays(MAX_BACKWARD_DAYS);
        Double best = null;
        LocalDate bestDate = null;
        for (Map<String, Object> candle : entry.candles()) {
            LocalDate d = parseCandleDate(candle.get("timestamp"));
            if (d == null || d.isAfter(target) || d.isBefore(earliest)) continue;
            if (bestDate == null || d.isAfter(bestDate)) {
                Object close = candle.get("close");
                if (close instanceof Number n) {
                    best = n.doubleValue();
                    bestDate = d;
                }
            }
        }
        return best;
    }

    /**
     * Percentage return between two dates, both resolved from daily closes.
     *
     * @return null when either leg is missing. Never a one-sided estimate: a return computed
     *         from one real price and one substituted one is not a smaller measurement, it is a
     *         different and wrong one.
     */
    public Double returnPercent(String symbol, LocalDate from, LocalDate to) {
        Double start = closeOnOrBefore(symbol, from);
        Double end = closeOnOrBefore(symbol, to);
        if (start == null || end == null || start <= 0) return null;
        return (end - start) / start * 100.0;
    }

    /**
     * The highest high, or lowest low, over a closed date range.
     *
     * <p><b>Why an extreme and not a close.</b> "Did the price ever reach this level" is not the
     * same question as "where is the price now", and answering it with closes understates every
     * level that was touched and given back — which for a price target is most of them. The
     * analyst ledger (SPEC §49.5) needs the touch, so it reads the intraday extreme of the daily
     * candle.
     *
     * <p>Lives here rather than in the ledger for the reason this class exists at all: the Kite
     * budget is one process-wide gate, and a second cache over the same symbols would double the
     * cost of identical information (Gotcha 97).
     *
     * @param high true for the highest high, false for the lowest low
     * @return null when the symbol has no entry, the entry does not reach back to {@code from},
     *         or no candle falls in the range. Null is unmeasured and must not be defaulted.
     */
    public Extreme extremeBetween(String symbol, LocalDate from, LocalDate to, boolean high) {
        Entry entry = cache.get(symbol);
        if (entry == null || from == null || to == null || from.isAfter(to)) return null;
        if (!entry.covers(from)) return null;

        Double best = null;
        LocalDate bestDate = null;
        for (Map<String, Object> candle : entry.candles()) {
            LocalDate d = parseCandleDate(candle.get("timestamp"));
            if (d == null || d.isBefore(from) || d.isAfter(to)) continue;
            Object raw = candle.get(high ? "high" : "low");
            if (!(raw instanceof Number n)) continue;
            double v = n.doubleValue();
            if (v <= 0) continue;
            if (best == null || (high ? v > best : v < best)) {
                best = v;
                bestDate = d;
            }
        }
        return best == null ? null : new Extreme(best, bestDate);
    }

    /**
     * The first date in a range on which the daily extreme crossed a level, and the level reached.
     *
     * <p>First, not best: a target is reached the day it is first touched, and taking the most
     * extreme day instead would report the wrong number of days-to-target for every call that
     * kept running afterwards.
     */
    public Extreme firstCrossing(String symbol, LocalDate from, LocalDate to, double level, boolean upward) {
        Entry entry = cache.get(symbol);
        if (entry == null || from == null || to == null || from.isAfter(to)) return null;
        if (!entry.covers(from)) return null;

        Extreme earliest = null;
        for (Map<String, Object> candle : entry.candles()) {
            LocalDate d = parseCandleDate(candle.get("timestamp"));
            if (d == null || d.isBefore(from) || d.isAfter(to)) continue;
            Object raw = candle.get(upward ? "high" : "low");
            if (!(raw instanceof Number n)) continue;
            double v = n.doubleValue();
            if (v <= 0) continue;
            boolean crossed = upward ? v >= level : v <= level;
            if (!crossed) continue;
            if (earliest == null || d.isBefore(earliest.date())) earliest = new Extreme(v, d);
        }
        return earliest;
    }

    /** A price and the day it happened, so a reading can be traced rather than assumed. */
    public record Extreme(double value, LocalDate date) {}

    /** Whether a symbol currently has an entry reaching back to {@code from}. */
    public boolean covers(String symbol, LocalDate from) {
        Entry e = cache.get(symbol);
        return e != null && e.covers(from);
    }

    /** Drop everything. Used by tests and by an explicit refresh; nothing schedules it. */
    public void clear() {
        cache.clear();
    }

    /**
     * Kite hands back a {@code ZonedDateTime} under {@code timestamp}, whose {@code toString} is
     * {@code 2026-04-17T00:00+05:30}; older paths supply a plain date. Truncating at the
     * {@code T} handles both without a timezone conversion that could move the date by a day.
     */
    static LocalDate parseCandleDate(Object raw) {
        if (raw == null) return null;
        if (raw instanceof LocalDate ld) return ld;
        String s = raw.toString();
        int tIdx = s.indexOf('T');
        if (tIdx > 0) s = s.substring(0, tIdx);
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
