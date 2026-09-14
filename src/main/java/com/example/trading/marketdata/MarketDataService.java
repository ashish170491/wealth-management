package com.example.trading.marketdata;

import com.example.trading.broker.BrokerClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for fetching and caching live market data.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MarketDataService {

    private final BrokerClient brokerClient;

    // Thread-safe cache for last fetched prices
    private final Map<String, Double> priceCache = new ConcurrentHashMap<>();

    // NOTE: there is deliberately no candle cache here (B-049). One existed, was written on
    // every fetch and read by nothing — "update cache for reference", never referenced. That
    // made it a pure leak: Stage A of the universe scan fetches ~1,600 symbols x 400 daily
    // candles in one pass, and every one of those series was retained for the life of the
    // JVM. Callers that want candle reuse cache their own (see RecommendationAccuracyService,
    // which keys by symbol and bounds itself to a run).

    /**
     * Gets the current price for a symbol.
     * If not in cache, it fetches from the broker.
     */
    public Double getCurrentPrice(String symbol) {
        Double cached = priceCache.get(symbol);
        if (cached != null && cached > 0) {
            return cached;
        }

        log.info("Price cache miss for {}. Fetching from broker.", symbol);
        Double price = fetchLatestPrice(symbol);

        // Only cache a real price. This used to be computeIfAbsent, which cached the 0.0
        // that fetchLatestPrice returns on failure — permanently, for the JVM's lifetime
        // (B-022). One failed lookup early in the day meant every later caller got 0.0
        // back with no broker call and no log line, which is what made the SECTOR_REVERSAL
        // outcome failure invisible for four months.
        if (price != null && price > 0) {
            priceCache.put(symbol, price);
        }
        return price;
    }

    /**
     * Force refreshes the price for a symbol and updates the cache.
     */
    public Double refreshPrice(String symbol) {
        Double latestPrice = fetchLatestPrice(symbol);
        priceCache.put(symbol, latestPrice);
        return latestPrice;
    }

    /**
     * Gets recent candle data for a symbol.
     * Always fetches fresh data to ensure real-time candles for intraday strategies.
     */
    public List<Map<String, Object>> getRecentCandles(String symbol, String interval, String from, String to) {
        log.debug("Fetching fresh candle data for {} ({})", symbol, interval);
        return brokerClient.getHistoricalData(symbol, interval, from, to);
    }

    /**
     * Clears all caches.
     */
    public void clearCache() {
        log.info("Clearing price cache.");
        priceCache.clear();
    }

    /**
     * Fetches the last traded price, or {@code 0.0} when it cannot be determined.
     *
     * <p><b>Contract:</b> returns {@code 0.0} — never {@code null} — on every failure path.
     * Callers must treat {@code <= 0} as "no price", never as a real quote. The zero is kept
     * (rather than the more honest {@code null}) because 113 of the 125 {@code getCurrentPrice}
     * call sites dereference the result directly and would NPE; changing the contract is a
     * separate refactor, tracked in B-027.
     *
     * <p><b>Every failure is logged</b> (B-027). Previously the empty-payload branch fell
     * through to {@code return 0.0} in total silence, so a permanently delisted symbol was
     * indistinguishable from a transient blip. Six dead tickers burned 152 quote calls per
     * outcome run for months without a single log line. Per CLAUDE.md gotcha #15, a failure
     * path must never degrade quietly into a value downstream code can mistake for data.
     */
    private Double fetchLatestPrice(String symbol) {
        try {
            Map<String, Object> quote = brokerClient.getQuote(symbol);
            // Assuming the quote structure contains 'last_price' in 'data'
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) quote.get("data");
            if (data == null || data.isEmpty()) {
                // Kite answers {status=success, data={}} for a symbol it cannot resolve —
                // delisted, renamed, or carrying an NSE trading-series suffix (gotcha #13/#14).
                log.warn("No price for {}: broker returned an empty quote payload. The symbol is "
                        + "likely delisted or renamed — verify it before leaving it in a pool.", symbol);
                return 0.0;
            }
            if (!data.containsKey(symbol)) {
                log.warn("No price for {}: quote payload present but keyed differently (keys={}).",
                        symbol, data.keySet());
                return 0.0;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> symbolData = (Map<String, Object>) data.get(symbol);
            Object lastPrice = symbolData == null ? null : symbolData.get("last_price");
            if (lastPrice == null) {
                log.warn("No price for {}: quote returned but last_price was absent.", symbol);
                return 0.0;
            }
            return Double.valueOf(String.valueOf(lastPrice));
        } catch (Exception e) {
            log.error("Error fetching latest price for {}: {}", symbol, e.getMessage());
            return 0.0;
        }
    }
}
