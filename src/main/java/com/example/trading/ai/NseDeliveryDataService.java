package com.example.trading.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Delivery-percentage data from NSE's daily full bhavcopy (B-021).
 *
 * <p>Delivery % — the share of traded volume actually taken to demat rather than squared
 * off intraday — is the "strong hands vs speculative churn" signal behind the wealth-signal
 * bonus (SPEC §12.7). It used to come from {@code /api/quote-equity?section=trade_info},
 * one call per stock, which NSE bot-walled (B-018) leaving it null for all 287 screened
 * stocks.
 *
 * <p>This replacement is strictly better than what it replaces:
 * <ul>
 *   <li><b>One request covers the entire market</b> (~3,470 symbols) instead of ~300
 *       per-stock calls — a full screening run costs a single ~400ms fetch.</li>
 *   <li>No cookie jar, no session warm-up, no Akamai negotiation — a plain browser
 *       User-Agent on {@code nsearchives.nseindia.com} is sufficient.</li>
 *   <li>Averaging over several sessions smooths the single-day noise that made a
 *       STRONG_HANDS / SPECULATIVE verdict flip on one unusual day.</li>
 * </ul>
 *
 * <p>Verified against the 20-Aug-2026 file: RELIANCE 47.55%, cross-checked identical to
 * the per-symbol {@code historicalOR} feed.
 */
@Service
@Slf4j
public class NseDeliveryDataService {

    private static final String ARCHIVE_BASE = "https://nsearchives.nseindia.com";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** URL uses DDMMYYYY; the DATE1 column inside uses DD-MMM-YYYY. Two formats, one call. */
    private static final DateTimeFormatter URL_DATE = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final DateTimeFormatter CONTENT_DATE =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    /** Sessions to average over. Enough to smooth a single odd day without going stale. */
    private static final int SESSIONS_TO_AVERAGE = 5;
    /** How far back to walk looking for those sessions (covers weekends + holidays). */
    private static final int MAX_CALENDAR_DAYS_BACK = 14;
    private static final long CACHE_TTL_MS = 6 * 60 * 60 * 1000L; // 6 hours

    private WebClient client;

    /** symbol (bare, no exchange prefix) -> average delivery %. */
    private volatile Map<String, Double> deliveryCache = Map.of();
    private volatile long cacheLoadedAt = 0;

    /**
     * Average delivery % for a symbol over the last few sessions, or null if unknown.
     *
     * @param symbol with or without exchange prefix and NSE trading-series suffix
     */
    public Double getDeliveryPercent(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        ensureLoaded();
        return deliveryCache.get(normalize(symbol));
    }

    /** True when delivery data is actually available, so callers can tell "no data" from "0%". */
    public boolean isAvailable() {
        ensureLoaded();
        return !deliveryCache.isEmpty();
    }

    private synchronized void ensureLoaded() {
        if (!deliveryCache.isEmpty() && System.currentTimeMillis() - cacheLoadedAt < CACHE_TTL_MS) {
            return;
        }

        Map<String, List<Double>> samples = new HashMap<>();
        int sessionsLoaded = 0;
        LocalDate day = LocalDate.now();

        for (int i = 0; i < MAX_CALENDAR_DAYS_BACK && sessionsLoaded < SESSIONS_TO_AVERAGE; i++) {
            day = day.minusDays(1); // the file is published after close, so never fetch today
            if (parseSession(day, samples)) {
                sessionsLoaded++;
            }
        }

        if (samples.isEmpty()) {
            log.warn("Delivery data: no bhavcopy sessions could be loaded — delivery-% signal unavailable");
            cacheLoadedAt = System.currentTimeMillis(); // don't hammer on every call
            return;
        }

        Map<String, Double> averaged = new ConcurrentHashMap<>();
        samples.forEach((sym, values) ->
                averaged.put(sym, values.stream().mapToDouble(Double::doubleValue).average().orElse(0)));

        deliveryCache = averaged;
        cacheLoadedAt = System.currentTimeMillis();
        log.info("Delivery data: loaded {} symbols averaged over {} session(s)", averaged.size(), sessionsLoaded);
    }

    /**
     * Fetch and parse one session's file. Returns false if that date has no genuine data.
     *
     * <p>Validating {@code DATE1} against the requested date is not optional: Sunday
     * 16-Aug-2026 returns HTTP 200 with a complete CSV whose DATE1 is 14-Aug — a silently
     * stale copy of the prior Friday. Trusting the status code would double-count a session
     * into the average.
     */
    private boolean parseSession(LocalDate date, Map<String, List<Double>> samples) {
        String body;
        try {
            body = getClient().get()
                    .uri("/products/content/sec_bhavdata_full_" + URL_DATE.format(date) + ".csv")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(TIMEOUT)
                    .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                    .block();
        } catch (Exception e) {
            log.debug("Delivery data: fetch failed for {}: {}", date, e.getMessage());
            return false;
        }
        if (body == null || body.isBlank()) {
            return false; // non-trading day — 404s cleanly
        }

        String expectedDate = CONTENT_DATE.format(date);
        String[] lines = body.split("\\R");
        if (lines.length < 2) return false;

        int parsed = 0;
        for (int i = 1; i < lines.length; i++) {
            // Fields are comma-separated WITH a trailing space: "RELIANCE, EQ, 20-Aug-2026, ..."
            String[] f = lines[i].split(",\\s*");
            if (f.length < 15) continue;

            String series = f[1].trim();
            if (!"EQ".equals(series)) {
                continue; // BE/BZ trade-to-trade rows carry a literal "-" for delivery
            }
            if (!expectedDate.equalsIgnoreCase(f[2].trim())) {
                log.debug("Delivery data: {} returned stale content dated {} — skipping session",
                        date, f[2].trim());
                return false;
            }

            String deliv = f[14].trim();
            if (deliv.isEmpty() || "-".equals(deliv)) continue;
            try {
                samples.computeIfAbsent(f[0].trim(), k -> new java.util.ArrayList<>())
                        .add(Double.parseDouble(deliv));
                parsed++;
            } catch (NumberFormatException ignored) {
                // malformed row — skip rather than fail the session
            }
        }

        if (parsed == 0) return false;
        log.debug("Delivery data: parsed {} EQ rows for {}", parsed, date);
        return true;
    }

    /** Strip exchange prefix and NSE trading-series suffix so lookups match the CSV (B-013). */
    private String normalize(String symbol) {
        String s = symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;
        for (String suffix : List.of("-BE", "-BZ", "-BL", "-IL")) {
            if (s.endsWith(suffix)) {
                s = s.substring(0, s.length() - suffix.length());
                break;
            }
        }
        return s.trim().toUpperCase(Locale.ROOT);
    }

    private WebClient getClient() {
        if (client == null) {
            client = WebClient.builder()
                    .baseUrl(ARCHIVE_BASE)
                    .defaultHeader(HttpHeaders.USER_AGENT, UA)
                    // The archive host needs only a browser UA — no cookies, unlike www.nseindia.com.
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                    .build();
        }
        return client;
    }
}
