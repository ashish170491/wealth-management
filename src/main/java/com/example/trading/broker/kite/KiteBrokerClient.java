package com.example.trading.broker.kite;

import com.example.trading.broker.BrokerClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ClientCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class KiteBrokerClient implements BrokerClient {

    private static final DateTimeFormatter KITE_DATETIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss[XXX][XX][X]");
    /**
     * Minimum spacing between outbound Kite requests, applied process-wide (B-027).
     *
     * <p>Kite caps most endpoints at 3 requests/second and answers a burst with HTTP 429.
     * {@link #executeWithRetry} already retried those, but retries alone only react after the
     * limit is hit — on 2026-08-24 they were exhausted 31 times (17 at 09:30, 10 at 15:00,
     * 4 at 15:22), each one costing a real quote. The bursts come from the 4-thread scheduler
     * pool: several Kite-heavy jobs fire on the same cron minute and race each other.
     *
     * <p>350 ms keeps the process under ~2.9 req/s. Pacing costs nothing when calls are already
     * spread out — the gate only delays a request that would otherwise overtake its predecessor.
     */
    private static final long MIN_REQUEST_INTERVAL_MS = 350;

    /** Epoch-millis of the next free request slot; advanced atomically by {@link #reserveSlot()}. */
    private final AtomicLong nextRequestSlot = new AtomicLong(0);

    private final KiteConfig config;
    private final WebClient webClient;

    /**
     * Where a dead session is reported (B-171). Setter-injected and optional so a client built by
     * hand in a test needs none; {@link TokenManagementService} is the listener.
     */
    private ApplicationEventPublisher events;

    @Autowired(required = false)
    void setEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public KiteBrokerClient(KiteConfig config, WebClient.Builder webClientBuilder) {
        this.config = config;

        // Increase buffer size to 10MB for large historical data responses
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();

        this.webClient = webClientBuilder
                .baseUrl(config.getBaseUrl())
                .defaultHeader("X-Kite-Version", "3")
                .exchangeStrategies(strategies)
                .build();
    }

    @Override
    public String placeOrder(Map<String, Object> orderParams) {
        log.info("Request: Placing order with params: {}", sanitize(orderParams));

        return executeWithRetry(
                webClient.post()
                        .uri("/orders/regular")
                        .headers(h -> {
                            h.set("Authorization", "token " + config.getApiKey() + ":" + config.getAccessToken());
                        })
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .bodyValue(buildFormBody(orderParams))
                        .retrieve()
                        .onStatus(
                                status -> status.is4xxClientError(),
                                response -> response.bodyToMono(String.class)
                                        .flatMap(errorBody -> {
                                            log.error("Order placement failed with status {}: {}",
                                                    response.statusCode(), errorBody);
                                            return Mono.error(
                                                    new RuntimeException("Order placement failed: " + errorBody));
                                        }))
                        .bodyToMono(Map.class)
                        .map(response -> {
                            log.info("Response: Order placed successfully: {}", response);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = (Map<String, Object>) response.get("data");
                            return (String) data.get("order_id");
                        }))
                .block();
    }

    /**
     * Which Kite tradingsymbol answered for a requested symbol this session (B-169). Kite names a
     * stock by its CURRENT NSE series, so a stock moved into trade-to-trade surveillance is
     * {@code HFCL-BE} on Kite while every list in this app says {@code HFCL}. Cached per process
     * (the app restarts daily), so a series change is picked up the next morning.
     */
    private final Map<String, String> resolvedQuoteSymbol = new java.util.concurrent.ConcurrentHashMap<>();

    /** Symbols no series form resolved this session - asked once, not on every screening loop. */
    private final java.util.Set<String> unresolvableQuoteSymbols = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public Map<String, Object> getQuote(String symbol) {
        // Ask for the symbol exactly as given; if Kite returns an empty data map for an NSE equity,
        // try the same ticker on its other series boards. This replaces B-013's one-way strip,
        // which assumed "Kite tradingsymbols never carry the series suffix". They do: on
        // 2026-10-12 Kite listed 230 -BE and 39 -BZ equity symbols, and HFCL, STLTECH, MTARTECH,
        // RBZJEWEL, ARIES and UGARSUGAR were unpriceable under their plain names (B-169). It is
        // the same company on a different board - never a guess at a different company, which is
        // what Gotcha 14 forbids. Whichever form answers is aliased back to the requested key, so
        // callers reading data.get(symbol) keep working unchanged.
        String cached = resolvedQuoteSymbol.get(symbol);
        java.util.List<String> candidates = cached != null ? java.util.List.of(cached)
                : unresolvableQuoteSymbols.contains(symbol) ? java.util.List.of(symbol)
                : seriesCandidates(symbol);

        Map<String, Object> first = null;
        for (String querySymbol : candidates) {
            Map<String, Object> result = fetchQuote(symbol, querySymbol);
            if (first == null) first = result;
            if (hasQuoteFor(result, querySymbol)) {
                if (!querySymbol.equals(symbol)) {
                    aliasQuote(result, querySymbol, symbol);
                    if (resolvedQuoteSymbol.putIfAbsent(symbol, querySymbol) == null) {
                        log.info("Kite quotes {} as {} - the stock is on a different NSE series board "
                                + "(-BE/-BZ = trade-to-trade surveillance, -SM/-ST = SME). B-169.", symbol, querySymbol);
                    }
                }
                return result;
            }
        }
        if (candidates.size() > 1) {
            unresolvableQuoteSymbols.add(symbol);
        }
        return first;
    }

    private Map<String, Object> fetchQuote(String symbol, String querySymbol) {
        log.info("Request: Fetching quote for symbol: {}{}", symbol,
                querySymbol.equals(symbol) ? "" : " (querying Kite as " + querySymbol + ")");
        return executeWithRetry(
                webClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/quote").queryParam("i", querySymbol).build())
                        .headers(h -> {
                            h.set("Authorization", "token " + config.getApiKey() + ":" + config.getAccessToken());
                        })
                        .retrieve()
                        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                response -> response.bodyToMono(String.class).flatMap(errorBody -> {
                                    log.error("Quote fetch failed for {} with status {}: {}", symbol,
                                            response.statusCode(), errorBody);
                                    return Mono.error(new RuntimeException("Quote fetch failed: " + errorBody));
                                }))
                        .bodyToMono(Map.class)
                        .map(response -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> result = (Map<String, Object>) response;
                            log.info("Response: Quote received for {}: {}", querySymbol, result);
                            return result;
                        }))
                .block();
    }

    private static boolean hasQuoteFor(Map<String, Object> result, String key) {
        return result != null && result.get("data") instanceof Map<?, ?> data && data.get(key) != null;
    }

    @SuppressWarnings("unchecked")
    private static void aliasQuote(Map<String, Object> result, String from, String to) {
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        if (!data.containsKey(to)) data.put(to, data.get(from));
    }

    /** NSE series suffixes a Kite equity tradingsymbol can carry. */
    private static final java.util.regex.Pattern SERIES_SUFFIX =
            java.util.regex.Pattern.compile("-(BE|BZ|BL|IL|SM|ST)$");

    /**
     * The forms to ask Kite for, in order: as given, then the plain ticker, then the plain ticker
     * on each series board. Only NSE equities get the series forms: an index ("NSE:NIFTY 50") is
     * returned untouched, and a BSE symbol only gets the B-013 strip. Pure.
     */
    static java.util.List<String> seriesCandidates(String symbol) {
        if (symbol == null) return java.util.Collections.singletonList(null);
        if (symbol.contains(" ") || !symbol.contains(":")) return java.util.List.of(symbol);
        String base = SERIES_SUFFIX.matcher(symbol).replaceAll("");
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(symbol);
        out.add(base);
        if (symbol.startsWith("NSE:")) {
            for (String series : java.util.List.of("BE", "BZ", "SM", "ST")) out.add(base + "-" + series);
        }
        return java.util.List.copyOf(out);
    }

    private final Map<String, String> instrumentTokenCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public java.util.List<Map<String, Object>> getHistoricalData(String symbol, String interval, String from,
            String to) {
        String token = getInstrumentToken(symbol);
        log.info("Request: Fetching historical data for {}({}): {} from {} to {}", symbol, token, interval, from, to);

        return executeWithRetry(
                webClient.get()
                        .uri(uriBuilder -> uriBuilder.path("/instruments/historical/{instrument_token}/{interval}")
                                .queryParam("from", from)
                                .queryParam("to", to)
                                .build(token, interval))
                        .headers(h -> {
                            h.set("Authorization", "token " + config.getApiKey() + ":" + config.getAccessToken());
                        })
                        .retrieve()
                        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                response -> response.bodyToMono(String.class).flatMap(errorBody -> {
                                    log.error("Historical fetch failed for {}({}) with status {}: {}", symbol, token,
                                            response.statusCode(), errorBody);
                                    return Mono.error(new RuntimeException("Historical fetch failed: " + errorBody));
                                }))
                        .bodyToMono(Map.class)
                        .map(response -> {
                            log.info("Response: Historical data received for {}", symbol);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = (Map<String, Object>) response.get("data");
                            @SuppressWarnings("unchecked")
                            java.util.List<java.util.List<Object>> candlesRaw = (java.util.List<java.util.List<Object>>) data
                                    .get("candles");

                            // Kite returns [[timestamp, open, high, low, close, volume], ...]
                            // Convert to List<Map<String, Object>>
                            java.util.List<Map<String, Object>> candles = new java.util.ArrayList<>();
                            if (candlesRaw != null) {
                                for (java.util.List<Object> candle : candlesRaw) {
                                    if (candle.size() >= 6) {
                                        Map<String, Object> candleMap = new java.util.HashMap<>();
                                        Object rawTime = candle.get(0);
                                        if (rawTime instanceof String s) {
                                            try {
                                                candleMap.put("timestamp",
                                                        ZonedDateTime.parse(s, KITE_DATETIME_FORMATTER));
                                            } catch (Exception e) {
                                                log.warn("Failed to parse Kite timestamp: {}", s);
                                                candleMap.put("timestamp", s); // Fallback to raw string
                                            }
                                        } else {
                                            candleMap.put("timestamp", rawTime);
                                        }
                                        candleMap.put("open", candle.get(1));
                                        candleMap.put("high", candle.get(2));
                                        candleMap.put("low", candle.get(3));
                                        candleMap.put("close", candle.get(4));
                                        candleMap.put("volume", candle.get(5));
                                        candles.add(candleMap);
                                    }
                                }
                            }
                            return candles;
                        }))
                .block();
    }

    private String getInstrumentToken(String symbol) {
        return instrumentTokenCache.computeIfAbsent(symbol, s -> {
            log.info("Token cache miss for {}. Fetching numeric instrument token...", s);
            Map<String, Object> quote = getQuote(s);
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) quote.get("data");
                if (data != null && data.containsKey(s)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> symbolData = (Map<String, Object>) data.get(s);
                    Object token = symbolData.get("instrument_token");
                    if (token != null) {
                        return String.valueOf(token);
                    }
                }
                throw new RuntimeException("Kite returned no quote for " + s + " under any series form "
                        + seriesCandidates(s) + " (keys in response: "
                        + (data == null ? "none" : data.keySet()) + ")");
            } catch (Exception e) {
                // ONE line per failure, naming the symbol (B-169): this used to log two ERRORs, the
                // first with no ticker in it, so the jobs page quoted "Token resolution failed.
                // Available keys in response: []" and counted every dead symbol twice.
                log.error("Instrument token resolution failed for {}: {}", s, e.getMessage());
                throw new RuntimeException("Instrument token resolution failed for " + s, e);
            }
        });
    }

    @Override
    public void authenticate() {
        log.info("Authenticating with Kite using API Key: {}", config.getApiKey());
        // In a real scenario, this would handle the login redirect or session
        // generation
    }

    @Override
    public Map<String, Object> getAccountMargins() {
        log.info("Request: Fetching account margins");

        return executeWithRetry(
                webClient.get()
                        .uri("/user/margins")
                        .headers(h -> {
                            h.set("Authorization", "token " + config.getApiKey() + ":" + config.getAccessToken());
                        })
                        .retrieve()
                        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                response -> response.bodyToMono(String.class).flatMap(errorBody -> {
                                    log.error("Margins fetch failed with status {}: {}",
                                            response.statusCode(), errorBody);
                                    return Mono.error(new RuntimeException("Margins fetch failed: " + errorBody));
                                }))
                        .bodyToMono(Map.class)
                        .map(response -> {
                            log.info("Response: Margins received: {}", response);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = (Map<String, Object>) response.get("data");
                            return data != null ? data : response;
                        }))
                .block();
    }

    @Override
    public java.util.List<Map<String, Object>> getPositions() {
        log.info("Request: Fetching all open positions from broker");

        Map<String, Object> response = executeWithRetry(
                webClient.get()
                        .uri("/portfolio/positions")
                        .headers(h -> {
                            h.set("Authorization", "token " + config.getApiKey() + ":" + config.getAccessToken());
                        })
                        .retrieve()
                        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                resp -> resp.bodyToMono(String.class).flatMap(errorBody -> {
                                    log.error("Positions fetch failed with status {}: {}",
                                            resp.statusCode(), errorBody);
                                    return Mono.error(new RuntimeException("Positions fetch failed: " + errorBody));
                                }))
                        .bodyToMono(Map.class))
                .block();

        log.info("Response: Positions received from broker");
        if (response != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            if (data != null) {
                // Kite returns positions in two arrays: 'net' and 'day'
                // We want 'day' positions for intraday (MIS) trades
                Object dayObj = data.get("day");
                if (dayObj instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> rawList = (java.util.List<Object>) dayObj;
                    java.util.List<Map<String, Object>> dayPositions = new java.util.ArrayList<>();
                    for (Object item : rawList) {
                        if (item instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> posMap = (Map<String, Object>) item;
                            dayPositions.add(posMap);
                        }
                    }
                    if (!dayPositions.isEmpty()) {
                        log.info("Found {} day positions from broker", dayPositions.size());
                        return dayPositions;
                    }
                }
            }
        }
        log.info("No open day positions found from broker");
        return java.util.Collections.emptyList();
    }

    @Override
    public java.util.List<Map<String, Object>> getHoldings() {
        log.info("Request: Fetching all holdings from broker");

        Map<String, Object> response = executeWithRetry(
                webClient.get()
                        .uri("/portfolio/holdings")
                        .headers(h -> {
                            h.set("Authorization", "token " + config.getApiKey() + ":" + config.getAccessToken());
                        })
                        .retrieve()
                        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                resp -> resp.bodyToMono(String.class).flatMap(errorBody -> {
                                    log.error("Holdings fetch failed with status {}: {}",
                                            resp.statusCode(), errorBody);
                                    return Mono.error(new RuntimeException("Holdings fetch failed: " + errorBody));
                                }))
                        .bodyToMono(Map.class))
                .block();

        log.info("Response: Holdings received from broker");
        if (response != null) {
            @SuppressWarnings("unchecked")
            Object dataObj = response.get("data");
            if (dataObj instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> rawList = (java.util.List<Object>) dataObj;
                java.util.List<Map<String, Object>> holdings = new java.util.ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> holdingMap = (Map<String, Object>) item;
                        holdings.add(holdingMap);
                    }
                }
                log.info("Found {} holdings from broker", holdings.size());
                return holdings;
            }
        }
        log.info("No holdings found from broker");
        return java.util.Collections.emptyList();
    }

    /**
     * Mutual fund units held in the demat account (SPEC §52.3).
     *
     * <p>Goes through {@code executeWithRetry} like every other call, so it claims a slot on the
     * process-wide ~2.9 req/s gate rather than bypassing it (Gotcha 23).
     */
    @Override
    public java.util.List<Map<String, Object>> getMutualFundHoldings() {
        log.info("Request: Fetching mutual fund holdings from broker");

        Map<String, Object> response = executeWithRetry(
                webClient.get()
                        .uri("/mf/holdings")
                        .headers(h -> h.set("Authorization",
                                "token " + config.getApiKey() + ":" + config.getAccessToken()))
                        .retrieve()
                        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                resp -> resp.bodyToMono(String.class).flatMap(errorBody -> {
                                    log.error("MF holdings fetch failed with status {}: {}",
                                            resp.statusCode(), errorBody);
                                    return Mono.error(new RuntimeException(
                                            "MF holdings fetch failed: " + errorBody));
                                }))
                        .bodyToMono(Map.class))
                .block();

        if (response != null && response.get("data") instanceof java.util.List<?> rawList) {
            java.util.List<Map<String, Object>> holdings = new java.util.ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = (Map<String, Object>) item;
                    holdings.add(row);
                }
            }
            log.info("Found {} mutual fund holdings from broker", holdings.size());
            return holdings;
        }

        // Distinguishable in the log from a failure: the call succeeded and the book is empty.
        log.info("No mutual fund holdings returned by broker");
        return java.util.Collections.emptyList();
    }

    @Override
    public String exitPosition(String exchange, String tradingSymbol, String product, int quantity, String transactionType) {
        log.info("Request: Exiting position - Exchange: {}, Symbol: {}, Product: {}, Qty: {}, Type: {}",
                exchange, tradingSymbol, product, quantity, transactionType);

        // Zerodha Kite API uses PUT /portfolio/positions to exit positions
        // This is more efficient than placing a new order as it:
        // 1. Doesn't require additional margin
        // 2. Is specifically designed for closing existing positions
        Map<String, Object> exitParams = new java.util.HashMap<>();
        exitParams.put("exchange", exchange);
        exitParams.put("tradingsymbol", tradingSymbol);
        exitParams.put("transaction_type", transactionType);
        exitParams.put("quantity", quantity);
        exitParams.put("order_type", "MARKET");
        exitParams.put("product", product);
        exitParams.put("old_product", product);  // Required by Kite API for position exit
        exitParams.put("validity", "DAY");

        return executeWithRetry(
                webClient.put()
                        .uri("/portfolio/positions")
                        .headers(h -> {
                            h.set("Authorization", "token " + config.getApiKey() + ":" + config.getAccessToken());
                        })
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .bodyValue(buildFormBody(exitParams))
                        .retrieve()
                        .onStatus(
                                status -> status.is4xxClientError(),
                                response -> response.bodyToMono(String.class)
                                        .flatMap(errorBody -> {
                                            log.error("Position exit failed with status {}: {}",
                                                    response.statusCode(), errorBody);
                                            // If position exit fails, fall back to regular order
                                            log.warn("Position exit API failed, will fall back to regular order placement");
                                            return Mono.error(
                                                    new RuntimeException("Position exit failed: " + errorBody));
                                        }))
                        .bodyToMono(Map.class)
                        .map(response -> {
                            log.info("Response: Position exit successful: {}", response);
                            // The response may contain status or order_id
                            if (response.containsKey("data")) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> data = (Map<String, Object>) response.get("data");
                                if (data != null && data.containsKey("order_id")) {
                                    return (String) data.get("order_id");
                                }
                            }
                            return "EXIT-" + System.currentTimeMillis();
                        }))
                .block();
    }

    /**
     * Reserves the next request slot and returns how long the caller must wait for it.
     *
     * <p>Lock-free: each caller atomically claims a slot {@link #MIN_REQUEST_INTERVAL_MS} after
     * the later of "now" and the previously claimed slot, so concurrent scheduler threads queue
     * behind one another instead of bursting. Returns {@link Duration#ZERO} when the line is
     * clear, which is the common case outside the packed cron minutes.
     */
    private Duration reserveSlot() {
        long now = System.currentTimeMillis();
        long slot = nextRequestSlot.updateAndGet(prev -> Math.max(now, prev) + MIN_REQUEST_INTERVAL_MS);
        long waitMs = slot - MIN_REQUEST_INTERVAL_MS - now;
        return waitMs > 0 ? Duration.ofMillis(waitMs) : Duration.ZERO;
    }

    private <T> Mono<T> executeWithRetry(Mono<T> action) {
        // Defer so every subscription — including each retry — claims its own slot. Pacing via
        // delaySubscription keeps this non-blocking: these calls are .block()ed from scheduler
        // threads, and sleeping inside the reactive chain would stall the event loop.
        Mono<T> paced = Mono.defer(() -> {
            // A token from before the last 06:00 is dead whatever it says (B-171). Report it so
            // a login starts now; this request still goes out and fails, which is no worse than
            // before, and the next one has a live session.
            if (KiteSessionClock.isExpired(config.getAccessTokenIssuedAt(), java.time.Instant.now())) {
                reportDeadSession("token issued " + config.getAccessTokenIssuedAt()
                        + ", before today's 06:00 expiry");
            }
            return action.delaySubscription(reserveSlot());
        });
        return executeWithRetryInternal(paced)
                // Kite answers a dead token with 403 + TokenException, not 401, so the retry
                // filter below never saw it. Every method maps the error body into its message.
                .doOnError(error -> {
                    if (isTokenRejection(error)) {
                        reportDeadSession("Kite answered TokenException");
                    }
                });
    }

    /** True when an error anywhere in the cause chain carries Kite's TokenException. Pure. */
    static boolean isTokenRejection(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String msg = t instanceof WebClientResponseException wcre
                    ? wcre.getResponseBodyAsString() + " " + wcre.getMessage()
                    : t.getMessage();
            if (msg != null && msg.contains("TokenException")) return true;
        }
        return false;
    }

    private void reportDeadSession(String reason) {
        ApplicationEventPublisher publisher = events;
        if (publisher != null) {
            publisher.publishEvent(new KiteTokenRejectedEvent(reason));
        }
    }

    private <T> Mono<T> executeWithRetryInternal(Mono<T> action) {
        // 5 attempts up to 8 s backoff with jitter — sized for burst scans (e.g. multibagger
        // screening of ~90 symbols in quick succession) where Kite's per-second cap fires
        // 429s on the back of the burst. Old config (3 attempts / 2 s cap) gave up too soon.
        return action.retryWhen(
                Retry.backoff(5, java.time.Duration.ofMillis(750))
                        .maxBackoff(java.time.Duration.ofSeconds(8))
                        .jitter(0.5)
                        .filter(error -> {
                            // Retry on 401 Unauthorized or 429 Too Many Requests
                            if (error instanceof WebClientResponseException) {
                                WebClientResponseException wcre = (WebClientResponseException) error;
                                return wcre.getStatusCode() == HttpStatus.UNAUTHORIZED ||
                                        wcre.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
                            }
                            // Also retry on network/DNS errors
                            if (error instanceof org.springframework.web.reactive.function.client.WebClientRequestException) {
                                log.warn("Network error encountered: {}. Will retry...", error.getMessage());
                                return true;
                            }
                            return false;
                        })
                        .doBeforeRetry(retrySignal -> {
                            Throwable failure = retrySignal.failure();
                            boolean rateLimited = failure instanceof WebClientResponseException ex
                                    && ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
                            log.warn("Retrying request. Attempt {} of 5{}. Error: {}",
                                    retrySignal.totalRetries() + 1,
                                    rateLimited ? " (rate-limited)" : "",
                                    failure.getMessage());
                            if (isUnauthorized(failure)) {
                                reportDeadSession("Kite answered 401 Unauthorized");
                            }
                        }));
    }

    private boolean isUnauthorized(Throwable throwable) {
        if (throwable instanceof WebClientResponseException ex) {
            return ex.getStatusCode() == HttpStatus.UNAUTHORIZED;
        }
        return false;
    }

    private String buildFormBody(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0)
                sb.append("&");
            sb.append(k).append("=").append(v);
        });
        return sb.toString();
    }

    private Map<String, Object> sanitize(Map<String, Object> params) {
        if (params == null)
            return java.util.Collections.emptyMap();
        Map<String, Object> sanitized = new java.util.HashMap<>(params);
        // Explicitly mask sensitive data if it ever ends up in params
        String[] sensitiveKeys = { "api_key", "access_token", "api_secret", "pin", "password" };
        for (String key : sensitiveKeys) {
            if (sanitized.containsKey(key)) {
                sanitized.put(key, "********");
            }
        }
        return sanitized;
    }

    @Override
    public java.util.List<Map<String, Object>> getOrders() {
        log.info("Request: Fetching all orders for today");

        Map<String, Object> response = executeWithRetry(
                webClient.get()
                        .uri("/orders")
                        .headers(h -> {
                            h.set("Authorization", "token " + config.getApiKey() + ":" + config.getAccessToken());
                        })
                        .retrieve()
                        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                resp -> resp.bodyToMono(String.class).flatMap(errorBody -> {
                                    log.error("Orders fetch failed with status {}: {}",
                                            resp.statusCode(), errorBody);
                                    return Mono.error(new RuntimeException("Orders fetch failed: " + errorBody));
                                }))
                        .bodyToMono(Map.class))
                .block();

        log.info("Response: Orders received from broker");
        if (response != null) {
            @SuppressWarnings("unchecked")
            Object dataObj = response.get("data");
            if (dataObj instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> rawList = (java.util.List<Object>) dataObj;
                java.util.List<Map<String, Object>> orders = new java.util.ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> orderMap = (Map<String, Object>) item;
                        orders.add(orderMap);
                    }
                }
                log.info("Found {} orders from broker", orders.size());
                return orders;
            }
        }
        log.info("No orders found from broker");
        return java.util.Collections.emptyList();
    }

    @Override
    public java.util.List<Map<String, Object>> getTodayTrades() {
        log.info("Request: Fetching today's trades from broker");

        Map<String, Object> response = executeWithRetry(
                webClient.get()
                        .uri("/trades")
                        .headers(h -> {
                            h.set("Authorization", "token " + config.getApiKey() + ":" + config.getAccessToken());
                        })
                        .retrieve()
                        .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                resp -> resp.bodyToMono(String.class).flatMap(errorBody -> {
                                    log.error("Trades fetch failed with status {}: {}",
                                            resp.statusCode(), errorBody);
                                    return Mono.error(new RuntimeException("Trades fetch failed: " + errorBody));
                                }))
                        .bodyToMono(Map.class))
                .block();

        if (response != null) {
            Object dataObj = response.get("data");
            if (dataObj instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> rawList = (java.util.List<Object>) dataObj;
                java.util.List<Map<String, Object>> trades = new java.util.ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> tradeMap = (Map<String, Object>) item;
                        trades.add(tradeMap);
                    }
                }
                log.info("Found {} trades from broker today", trades.size());
                return trades;
            }
        }
        return java.util.Collections.emptyList();
    }

    @Override
    public Map<String, Object> getOrderStatus(String orderId) {
        log.info("Request: Fetching order status for orderId: {}", orderId);

        // First get all orders and find the matching one
        java.util.List<Map<String, Object>> allOrders = getOrders();
        for (Map<String, Object> order : allOrders) {
            String id = String.valueOf(order.get("order_id"));
            if (orderId.equals(id)) {
                log.info("Found order status for {}: {}", orderId, order.get("status"));
                return order;
            }
        }

        // If not found in orders list, try the order history endpoint
        // Note: Kite API returns 404 for orders from previous days (only current day orders are available)
        try {
            Map<String, Object> response = executeWithRetry(
                    webClient.get()
                            .uri("/orders/{order_id}", orderId)
                            .headers(h -> {
                                h.set("Authorization", "token " + config.getApiKey() + ":" + config.getAccessToken());
                            })
                            .retrieve()
                            .onStatus(status -> status == HttpStatus.NOT_FOUND,
                                    resp -> {
                                        // 404 is expected for orders from previous days - don't throw exception
                                        log.debug("Order {} not found (likely from previous trading day)", orderId);
                                        return Mono.empty();
                                    })
                            .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                    resp -> resp.bodyToMono(String.class).flatMap(errorBody -> {
                                        log.error("Order status fetch failed for {} with status {}: {}",
                                                orderId, resp.statusCode(), errorBody);
                                        return Mono.error(new RuntimeException("Order status fetch failed: " + errorBody));
                                    }))
                            .bodyToMono(Map.class))
                    .block();

            if (response != null) {
                @SuppressWarnings("unchecked")
                Object dataObj = response.get("data");
                if (dataObj instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> orderHistory = (java.util.List<Object>) dataObj;
                    if (!orderHistory.isEmpty()) {
                        // Return the latest state (last entry in history)
                        @SuppressWarnings("unchecked")
                        Map<String, Object> latestState = (Map<String, Object>) orderHistory.get(orderHistory.size() - 1);
                        log.info("Order {} status from history: {}", orderId, latestState.get("status"));
                        return latestState;
                    }
                }
            }
        } catch (Exception e) {
            // Handle 404 errors gracefully - these are expected for orders from previous days
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                log.debug("Order {} not found in broker (likely from previous trading day)", orderId);
                return java.util.Collections.emptyMap();
            }
            log.warn("Error fetching order status for {}: {}", orderId, e.getMessage());
        }

        log.debug("Order {} not found in current day's orders", orderId);
        return java.util.Collections.emptyMap();
    }

    @Override
    public boolean cancelOrder(String orderId) {
        log.info("Request: Cancelling order {}", orderId);

        try {
            Map<String, Object> response = executeWithRetry(
                    webClient.delete()
                            .uri("/orders/regular/{order_id}", orderId)
                            .headers(h -> {
                                h.set("Authorization", "token " + config.getApiKey() + ":" + config.getAccessToken());
                            })
                            .retrieve()
                            .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                                    resp -> resp.bodyToMono(String.class).flatMap(errorBody -> {
                                        log.error("Order cancellation failed for {} with status {}: {}",
                                                orderId, resp.statusCode(), errorBody);
                                        return Mono.error(new RuntimeException("Order cancellation failed: " + errorBody));
                                    }))
                            .bodyToMono(Map.class))
                    .block();

            if (response != null && "ok".equals(response.get("status"))) {
                log.info("Order {} cancelled successfully", orderId);
                return true;
            }
            log.warn("Order {} cancellation response: {}", orderId, response);
            return response != null;
        } catch (Exception e) {
            log.error("Failed to cancel order {}: {}", orderId, e.getMessage());
            return false;
        }
    }

    @Override
    public String modifyOrder(String orderId, Map<String, Object> modifyParams) {
        log.info("Request: Modifying order {} with params: {}", orderId, sanitize(modifyParams));

        return executeWithRetry(
                webClient.put()
                        .uri("/orders/regular/{order_id}", orderId)
                        .headers(h -> {
                            h.set("Authorization", "token " + config.getApiKey() + ":" + config.getAccessToken());
                        })
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .bodyValue(buildFormBody(modifyParams))
                        .retrieve()
                        .onStatus(
                                status -> status.is4xxClientError(),
                                response -> response.bodyToMono(String.class)
                                        .flatMap(errorBody -> {
                                            log.error("Order modification failed for {} with status {}: {}",
                                                    orderId, response.statusCode(), errorBody);
                                            return Mono.error(
                                                    new RuntimeException("Order modification failed: " + errorBody));
                                        }))
                        .bodyToMono(Map.class)
                        .map(response -> {
                            log.info("Response: Order {} modified successfully: {}", orderId, response);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = (Map<String, Object>) response.get("data");
                            if (data != null && data.containsKey("order_id")) {
                                return (String) data.get("order_id");
                            }
                            return orderId;
                        }))
                .block();
    }
}
