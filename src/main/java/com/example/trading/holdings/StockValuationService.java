package com.example.trading.holdings;

import com.example.trading.ai.NseDataService;
import com.example.trading.marketdata.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service providing stock valuation data (PE, Sector PE, market cap, EPS).
 *
 * <p><b>Primary source (B-018, 2026-08-22): computed from fundamentals.</b> NSE walled
 * {@code /api/quote-equity} behind bot protection — it returns 403 for every request
 * (1,898/1,898 in the 18-20 Aug audit) while sibling endpoints on the same session still
 * work. That silently nulled stock PE, market cap and industry for all 374 screened
 * stocks, which in turn killed the reverse-DCF outright (it hard-bails on a null market
 * cap) and neutralised the multibagger Valuation dimension.
 *
 * <p>Rather than chase header/cookie workarounds on a hostile endpoint, valuation is now
 * <b>derived from data the app already fetches reliably</b>:
 * <ul>
 *   <li>TTM EPS and TTM net profit — integrated-filing XBRL via {@link NseDataService} (B-017)</li>
 *   <li>Live price — Kite via {@link MarketDataService}</li>
 *   <li>{@code stockPe = price / ttmEps}</li>
 *   <li>{@code sharesCr = ttmProfit / ttmEps} &rarr; {@code marketCapCr = price * sharesCr}</li>
 * </ul>
 * Both identities are unit-consistent (profit in ₹cr, EPS in ₹/share) and mutually
 * consistent ({@code marketCap == stockPe * ttmProfit}).
 *
 * <p>The NSE endpoint is still attempted first so the richer payload (dividend yield,
 * book value, official sector PE) returns automatically if NSE ever unblocks it — but a
 * circuit breaker trips after {@value #NSE_FAILURE_THRESHOLD} consecutive failures to stop
 * burning ~1,100 doomed HTTP calls per screening run.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StockValuationService {

    private final WebClient.Builder webClientBuilder;
    private final NseDataService nseDataService;
    private final MarketDataService marketDataService;

    // Cache for valuation data (symbol -> ValuationData)
    private final Map<String, ValuationData> valuationCache = new ConcurrentHashMap<>();

    /**
     * Circuit breaker for the bot-walled NSE quote-equity endpoint (B-018). Opens after
     * NSE_FAILURE_THRESHOLD consecutive failures and re-arms after NSE_RETRY_INTERVAL_MS
     * so a genuine NSE recovery is picked up without a redeploy.
     */
    private static final int NSE_FAILURE_THRESHOLD = 10;
    private static final long NSE_RETRY_INTERVAL_MS = 6 * 60 * 60 * 1000L; // 6 hours
    private final AtomicInteger nseConsecutiveFailures = new AtomicInteger(0);
    private volatile long nseCircuitOpenedAt = 0;

    /**
     * Live per-sector PE samples, keyed by the caller-supplied sector hint. Sector PE is
     * the median of computed peer PEs once at least MIN_SECTOR_PE_SAMPLES stocks in that
     * bucket have been valued; below that we fall back to the hardcoded table. Using one
     * taxonomy for both bucketing and lookup avoids the sector-name mismatch that caused
     * the Institutional-Interest zero-variance bug (CLAUDE.md "Critical Bug Fixes" #9).
     */
    private static final int MIN_SECTOR_PE_SAMPLES = 5;
    private final Map<String, List<Double>> sectorPeSamples = new ConcurrentHashMap<>();

    // NSE session cookies (3-minute TTL, same as FiiDiiDataService)
    private volatile String cachedCookies;
    private volatile long cookieExpiry = 0;
    private static final long COOKIE_TTL_MS = 180_000; // 3 minutes
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private WebClient nseClient;

    // Default industry PE values (fallback when NSE API fails)
    private static final Map<String, Double> DEFAULT_INDUSTRY_PE = Map.ofEntries(
        // Banking
        Map.entry("BANKS", 12.0),
        Map.entry("BANKING", 12.0),
        Map.entry("PRIVATE SECTOR BANK", 16.0),
        Map.entry("PUBLIC SECTOR BANK", 7.5),
        Map.entry("PRIVATE BANKS", 16.0),
        Map.entry("PUBLIC BANKS", 7.5),

        // IT & Software
        Map.entry("IT", 28.0),
        Map.entry("INFORMATION TECHNOLOGY", 28.0),
        Map.entry("SOFTWARE", 28.0),
        Map.entry("COMPUTERS - SOFTWARE & CONSULTING", 28.0),
        Map.entry("IT ENABLED SERVICES", 28.0),

        // Pharma
        Map.entry("PHARMA", 30.0),
        Map.entry("PHARMACEUTICALS", 30.0),

        // FMCG
        Map.entry("FMCG", 45.0),
        Map.entry("DIVERSIFIED FMCG", 50.0),
        Map.entry("TEA & COFFEE", 80.0),

        // Auto
        Map.entry("AUTO", 18.0),
        Map.entry("AUTOMOBILES", 18.0),
        Map.entry("AUTOMOBILE", 18.0),
        Map.entry("PASSENGER CARS & UTILITY VEHICLES", 18.0),

        // Metals
        Map.entry("METALS", 10.0),
        Map.entry("STEEL", 8.0),
        Map.entry("ALUMINIUM", 11.0),
        Map.entry("ZINC", 10.0),

        // Power & Energy
        Map.entry("POWER", 13.0),
        Map.entry("POWER GENERATION", 14.0),
        Map.entry("INTEGRATED POWER UTILITIES", 14.0),
        Map.entry("OIL & GAS", 14.0),
        Map.entry("ENERGY", 14.0),
        Map.entry("REFINERIES & MARKETING", 14.0),
        Map.entry("REFINERIES", 14.0),

        // Telecom
        Map.entry("TELECOM", 35.0),

        // Cement & Infrastructure
        Map.entry("CEMENT", 22.0),
        Map.entry("INFRASTRUCTURE", 15.0),

        // Real Estate
        Map.entry("REALTY", 25.0),
        Map.entry("REAL ESTATE", 25.0),
        Map.entry("DIVERSIFIED RETAIL", 60.0),

        // Chemicals
        Map.entry("CONSUMER DURABLES", 40.0),
        Map.entry("CHEMICALS", 25.0),
        Map.entry("TEXTILES", 15.0),
        Map.entry("FERTILIZERS", 12.0),
        Map.entry("OTHER ELECTRICAL EQUIPMENT", 25.0),
        Map.entry("OTHER AGRICULTURAL PRODUCTS", 20.0),

        // Finance & Insurance
        Map.entry("INSURANCE", 20.0),
        Map.entry("GENERAL INSURANCE", 50.0),
        Map.entry("LIFE INSURANCE", 45.0),
        Map.entry("NBFC", 15.0),
        Map.entry("FINANCE", 15.0),
        Map.entry("FINANCIAL SERVICES", 15.0),
        Map.entry("FINANCIAL INSTITUTION", 4.0)
    );

    /**
     * Fetch valuation data for a stock.
     * Returns cached data if available, otherwise fetches from NSE API.
     */
    @Cacheable(value = "stockValuation", key = "#symbol", unless = "#result == null")
    public ValuationData getValuationData(String symbol) {
        return getValuationData(symbol, null);
    }

    /**
     * Fetch valuation data, with an optional sector hint from the caller.
     *
     * <p>The hint is what makes a <i>live</i> sector PE possible (B-018): callers that
     * already know a stock's sector — the multibagger screener via its STOCK_SECTOR_MAP,
     * the holdings report via {@code HoldingsEntity.getIndustry()} — let us bucket peer
     * PEs and take a median instead of falling back to a hardcoded constant. Passing
     * {@code null} is safe; it just means the hardcoded table is used.
     *
     * @param symbol       stock symbol, with or without an exchange prefix
     * @param sectorHint   caller's sector label, or null if unknown
     */
    public ValuationData getValuationData(String symbol, String sectorHint) {
        // Clean symbol (remove exchange prefix)
        String cleanSymbol = cleanSymbol(symbol);

        // Check cache first
        ValuationData cached = valuationCache.get(cleanSymbol);
        if (cached != null && !cached.isStale()) {
            return cached;
        }

        // 1. NSE quote-equity — richest payload when reachable, but bot-walled since
        //    ~Aug 2026 (B-018). Skipped entirely while the circuit breaker is open.
        if (isNseCircuitClosed()) {
            try {
                ValuationData data = fetchFromNSE(cleanSymbol);
                if (data != null) {
                    nseConsecutiveFailures.set(0);
                    recordSectorPeSample(sectorHint, data.getStockPe());
                    valuationCache.put(cleanSymbol, data);
                    return data;
                }
                recordNseFailure();
            } catch (Exception e) {
                log.debug("NSE valuation fetch failed for {}: {}", cleanSymbol, e.getMessage());
                recordNseFailure();
            }
        }

        // 2. Compute from fundamentals — the primary path today.
        try {
            ValuationData computed = computeValuationFromFundamentals(symbol, cleanSymbol, sectorHint);
            if (computed != null) {
                valuationCache.put(cleanSymbol, computed);
                return computed;
            }
        } catch (Exception e) {
            log.debug("Computed valuation failed for {}: {}", cleanSymbol, e.getMessage());
        }

        // 3. Return cached stale data if both live paths failed
        if (cached != null) {
            log.debug("Returning stale cached PE data for {}", cleanSymbol);
            return cached;
        }

        // 4. Last resort: sector PE only, no stock PE. Downstream code must treat a null
        //    stockPe/marketCap as "unknown" and score it neutral — never as zero.
        log.debug("No PE/market-cap data available for {} — sector-PE-only fallback", cleanSymbol);
        return createFallbackValuationData(cleanSymbol, sectorHint);
    }

    // ======================== COMPUTED VALUATION (B-018) ========================

    /**
     * Derive PE, EPS and market cap from trailing-twelve-month fundamentals + live price.
     *
     * <p>Returns null when the inputs can't support a trustworthy number — no quarterly
     * filings, no price, or a non-positive TTM EPS (loss-making, where PE is meaningless
     * and shares outstanding can't be back-solved). Returning null rather than a guess is
     * deliberate: a wrong PE silently mis-ranks a stock, whereas a null is scored neutral.
     */
    private ValuationData computeValuationFromFundamentals(String originalSymbol, String cleanSymbol, String sectorHint) {
        List<NseDataService.QuarterlyResult> quarters = nseDataService.fetchQuarterlyResults(cleanSymbol);
        if (quarters == null || quarters.isEmpty()) {
            return null;
        }

        // Newest-first; take up to 4 quarters for a trailing-twelve-month view.
        double epsSum = 0, profitSum = 0;
        int epsCount = 0, profitCount = 0;
        Double sharesCr = null;
        for (NseDataService.QuarterlyResult q : quarters.stream().limit(4).toList()) {
            if (q.getEps() != null) { epsSum += q.getEps(); epsCount++; }
            if (q.getProfit() != null) { profitSum += q.getProfit(); profitCount++; }
            // Share count is a point-in-time figure, not a flow — take the most recent
            // filing that reports it rather than summing.
            if (sharesCr == null && q.getSharesOutstandingCr() != null && q.getSharesOutstandingCr() > 0) {
                sharesCr = q.getSharesOutstandingCr();
            }
        }
        if (profitCount == 0) {
            return null;
        }

        // Annualise if fewer than 4 quarters are published (the integrated-filing system
        // only began ~Mar-2025, so short histories are expected — see B-017).
        double ttmProfit = profitSum * 4.0 / profitCount;
        Double ttmEps = epsCount > 0 ? epsSum * 4.0 / epsCount : null;

        if (ttmProfit <= 0) {
            log.debug("Loss-making TTM profit for {} ({}) — PE not meaningful", cleanSymbol, ttmProfit);
            return null;
        }

        // Fall back to back-solving shares from profit÷EPS only when the filing omits
        // paid-up capital. Less exact (a quarter with an exceptional item skews EPS) but
        // better than no market cap at all.
        if (sharesCr == null && ttmEps != null && ttmEps > 0) {
            sharesCr = ttmProfit / ttmEps;
        }
        if (sharesCr == null || sharesCr <= 0) {
            log.debug("No share count derivable for {} — skipping valuation", cleanSymbol);
            return null;
        }

        Double price = resolvePrice(originalSymbol, cleanSymbol);
        if (price == null || price <= 0) {
            return null;
        }

        double marketCapCr = price * sharesCr;        // ₹ crore
        // Derive PE from market cap ÷ TTM profit rather than price ÷ EPS. Identical when
        // both are present, but this keeps PE available for banks and any other filer
        // whose EPS element we can't parse.
        double stockPe = marketCapCr / ttmProfit;

        // Reject implausible values instead of letting them poison the score.
        if (stockPe <= 0 || stockPe > 500 || marketCapCr < 10 || marketCapCr > 30_00_000) {
            log.debug("Computed valuation out of bounds for {} (PE={}, mcap={} cr) — discarding",
                    cleanSymbol, stockPe, marketCapCr);
            return null;
        }

        ValuationData data = new ValuationData();
        data.setSymbol(cleanSymbol);
        data.setFetchedAt(System.currentTimeMillis());
        data.setStockPe(stockPe);
        data.setEps(ttmEps != null ? ttmEps : ttmProfit / sharesCr);
        data.setMarketCap(marketCapCr);
        data.setIndustry(sectorHint != null ? sectorHint : getIndustryForSymbol(cleanSymbol));

        recordSectorPeSample(sectorHint, stockPe);
        applySectorPe(data, sectorHint);

        log.debug("Computed valuation {} -> PE={} (price={}, shares={} cr, ttmProfit={} cr), mcap={} cr, sectorPE={}",
                cleanSymbol, String.format("%.1f", stockPe), price, String.format("%.1f", sharesCr),
                String.format("%.0f", ttmProfit), String.format("%.0f", marketCapCr), data.getIndustryPe());
        return data;
    }

    /** Resolve a live price, preferring the caller's exchange prefix and defaulting to NSE. */
    private Double resolvePrice(String originalSymbol, String cleanSymbol) {
        String quoteSymbol = originalSymbol != null && originalSymbol.contains(":")
                ? originalSymbol
                : "NSE:" + cleanSymbol;
        try {
            Double price = marketDataService.getCurrentPrice(quoteSymbol);
            if (price != null && price > 0) return price;
        } catch (Exception e) {
            log.debug("Price lookup failed for {}: {}", quoteSymbol, e.getMessage());
        }
        return null;
    }

    // ======================== SECTOR PE ========================

    /** Record a peer PE so the sector median becomes available once the bucket fills. */
    private void recordSectorPeSample(String sectorHint, Double stockPe) {
        if (sectorHint == null || stockPe == null || stockPe <= 0 || stockPe > 500) return;
        sectorPeSamples.computeIfAbsent(sectorHint, k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(stockPe);
    }

    /**
     * Set industry PE from the live peer median when we have enough samples, otherwise
     * from the hardcoded table. Also computes PE deviation.
     */
    private void applySectorPe(ValuationData data, String sectorHint) {
        Double sectorPe = livePeerMedianPe(sectorHint);
        if (sectorPe == null) {
            sectorPe = getDefaultIndustryPe(data.getIndustry());
        }
        data.setIndustryPe(sectorPe);

        if (data.getStockPe() != null && sectorPe != null && sectorPe > 0) {
            data.setPeDeviation(((data.getStockPe() - sectorPe) / sectorPe) * 100);
        }
    }

    /** Median PE of already-valued peers in this sector, or null if too few samples. */
    private Double livePeerMedianPe(String sectorHint) {
        if (sectorHint == null) return null;
        List<Double> samples = sectorPeSamples.get(sectorHint);
        if (samples == null) return null;

        List<Double> copy;
        synchronized (samples) {
            if (samples.size() < MIN_SECTOR_PE_SAMPLES) return null;
            copy = new ArrayList<>(samples);
        }
        copy.sort(Comparator.naturalOrder());
        int n = copy.size();
        return n % 2 == 1 ? copy.get(n / 2) : (copy.get(n / 2 - 1) + copy.get(n / 2)) / 2.0;
    }

    // ======================== NSE CIRCUIT BREAKER (B-018) ========================

    private boolean isNseCircuitClosed() {
        if (nseConsecutiveFailures.get() < NSE_FAILURE_THRESHOLD) return true;
        if (System.currentTimeMillis() - nseCircuitOpenedAt > NSE_RETRY_INTERVAL_MS) {
            log.info("NSE valuation circuit breaker: re-arming after cool-down, retrying quote-equity");
            nseConsecutiveFailures.set(0);
            return true;
        }
        return false;
    }

    private void recordNseFailure() {
        int failures = nseConsecutiveFailures.incrementAndGet();
        if (failures == NSE_FAILURE_THRESHOLD) {
            nseCircuitOpenedAt = System.currentTimeMillis();
            log.warn("NSE valuation circuit breaker OPEN after {} consecutive failures on "
                    + "/api/quote-equity (B-018). Using computed PE/market cap; will retry in 6h.", failures);
        }
    }

    // ======================== NSE API ========================

    /**
     * Fetch valuation from NSE official API with proper session cookies.
     * NSE /api/quote-equity returns:
     *   metadata.pdSymbolPe = Stock PE
     *   metadata.pdSectorPe = Sector PE (actual sector average)
     *   metadata.industry   = Industry name
     */
    @SuppressWarnings("unchecked")
    private ValuationData fetchFromNSE(String symbol) {
        try {
            // Ensure we have valid session cookies
            refreshCookies();

            if (cachedCookies == null || cachedCookies.isEmpty()) {
                log.debug("No NSE cookies available, cannot fetch PE for {}", symbol);
                return null;
            }

            Map<String, Object> response = getNseClient().get()
                .uri("/api/quote-equity?symbol={symbol}", symbol)
                .header(HttpHeaders.COOKIE, cachedCookies)
                .retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(TIMEOUT)
                .onErrorResume(e -> {
                    log.debug("NSE API call failed for {}: {}", symbol, e.getMessage());
                    return reactor.core.publisher.Mono.empty();
                })
                .block();

            if (response == null || response.isEmpty()) {
                log.debug("Empty response from NSE API for {}", symbol);
                return null;
            }

            ValuationData data = new ValuationData();
            data.setSymbol(symbol);
            data.setFetchedAt(System.currentTimeMillis());

            // Extract metadata (contains PE ratios)
            Map<String, Object> metadata = (Map<String, Object>) response.get("metadata");
            if (metadata != null) {
                // pdSymbolPe = Stock PE
                data.setStockPe(parseDouble(metadata.get("pdSymbolPe")));

                // pdSectorPe = Sector PE (actual sector average from NSE)
                data.setIndustryPe(parseDouble(metadata.get("pdSectorPe")));

                // Industry name
                Object industryObj = metadata.get("industry");
                if (industryObj != null) {
                    data.setIndustry(industryObj.toString());
                }
            }

            // Fallback: Extract industry from industryInfo
            if (data.getIndustry() == null) {
                Map<String, Object> industryInfo = (Map<String, Object>) response.get("industryInfo");
                if (industryInfo != null) {
                    Object basicIndustry = industryInfo.get("basicIndustry");
                    if (basicIndustry != null) {
                        data.setIndustry(basicIndustry.toString());
                    }
                }
            }

            // If pdSectorPe was empty/zero, fall back to hardcoded
            if (data.getIndustryPe() == null || data.getIndustryPe() <= 0) {
                if (data.getIndustry() != null) {
                    Double fallbackPe = getDefaultIndustryPe(data.getIndustry());
                    data.setIndustryPe(fallbackPe);
                    log.debug("pdSectorPe unavailable for {}, using hardcoded {} for '{}'",
                            symbol, fallbackPe, data.getIndustry());
                }
            }

            // Calculate PE deviation
            if (data.getStockPe() != null && data.getIndustryPe() != null && data.getIndustryPe() > 0) {
                double deviation = ((data.getStockPe() - data.getIndustryPe()) / data.getIndustryPe()) * 100;
                data.setPeDeviation(deviation);
            }

            // Extract market cap: NSE gives issuedSize (shares outstanding) + lastPrice.
            // marketCap (crores) = issuedSize * lastPrice / 10^7
            Map<String, Object> priceInfo = (Map<String, Object>) response.get("priceInfo");
            Map<String, Object> securityInfo = (Map<String, Object>) response.get("securityInfo");
            if (priceInfo != null && securityInfo != null) {
                Double lastPrice = parseDouble(priceInfo.get("lastPrice"));
                if (lastPrice == null) lastPrice = parseDouble(priceInfo.get("close"));
                Double issuedSize = parseDouble(securityInfo.get("issuedSize"));
                if (lastPrice != null && lastPrice > 0 && issuedSize != null && issuedSize > 0) {
                    double marketCapCrores = (lastPrice * issuedSize) / 1_00_00_000.0;
                    data.setMarketCap(marketCapCrores);
                }
            }

            // Log the source of Sector PE for debugging
            String peSource = (metadata != null && parseDouble(metadata.get("pdSectorPe")) != null
                    && parseDouble(metadata.get("pdSectorPe")) > 0) ? "NSE API" : "Hardcoded";

            log.info("PE Data: {} -> StockPE={}, SectorPE={} ({}), Industry={}, Deviation={}%",
                symbol, data.getStockPe(), data.getIndustryPe(), peSource, data.getIndustry(),
                data.getPeDeviation() != null ? String.format("%.1f", data.getPeDeviation()) : "N/A");

            return data;

        } catch (Exception e) {
            log.debug("NSE API error for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    // ======================== NSE SESSION MANAGEMENT ========================

    /**
     * Get or create the NSE WebClient (cached).
     */
    private WebClient getNseClient() {
        if (nseClient == null) {
            nseClient = webClientBuilder
                .baseUrl("https://www.nseindia.com")
                .defaultHeader(HttpHeaders.USER_AGENT,
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9")
                .defaultHeader("Referer", "https://www.nseindia.com/")
                // Don't set Accept-Encoding - WebClient auto-decompresses
                .build();
        }
        return nseClient;
    }

    /**
     * Refresh NSE session cookies by visiting the homepage.
     * Same pattern as FiiDiiDataService (3-minute TTL).
     */
    private void refreshCookies() {
        if (System.currentTimeMillis() < cookieExpiry && cachedCookies != null) {
            return;
        }

        try {
            log.debug("Refreshing NSE session cookies for PE data...");

            // Visit NSE homepage to get session cookies
            getNseClient().get()
                .uri("/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .exchangeToMono(clientResponse -> {
                    List<String> cookies = clientResponse.headers().header(HttpHeaders.SET_COOKIE);
                    if (!cookies.isEmpty()) {
                        StringBuilder cookieBuilder = new StringBuilder();
                        for (String cookie : cookies) {
                            String[] parts = cookie.split(";")[0].split("=", 2);
                            if (parts.length == 2) {
                                if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
                                cookieBuilder.append(parts[0]).append("=").append(parts[1]);
                            }
                        }
                        cachedCookies = cookieBuilder.toString();
                    }
                    return clientResponse.bodyToMono(String.class);
                })
                .timeout(TIMEOUT)
                .block();

            // Visit a quote page to establish full session
            if (cachedCookies != null) {
                getNseClient().get()
                    .uri("/get-quotes/equity?symbol=RELIANCE")
                    .header(HttpHeaders.COOKIE, cachedCookies)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .exchangeToMono(clientResponse -> {
                        List<String> newCookies = clientResponse.headers().header(HttpHeaders.SET_COOKIE);
                        if (!newCookies.isEmpty()) {
                            for (String cookie : newCookies) {
                                String[] parts = cookie.split(";")[0].split("=", 2);
                                if (parts.length == 2 && !cachedCookies.contains(parts[0])) {
                                    cachedCookies += "; " + parts[0] + "=" + parts[1];
                                }
                            }
                        }
                        return clientResponse.bodyToMono(String.class);
                    })
                    .timeout(TIMEOUT)
                    .block();
            }

            cookieExpiry = System.currentTimeMillis() + COOKIE_TTL_MS;
            log.debug("NSE cookies refreshed for PE data");

        } catch (Exception e) {
            log.warn("Failed to refresh NSE cookies: {}", e.getMessage());
            cachedCookies = null;
            cookieExpiry = 0;
        }
    }

    // ======================== FALLBACK ========================

    /**
     * Sector-PE-only fallback: no stock PE, no market cap, no deviation.
     *
     * <p>Callers must treat the null stockPe/marketCap as "unknown" and score it neutral.
     * Scoring a null as zero would rank an unmeasurable stock as infinitely cheap.
     */
    private ValuationData createFallbackValuationData(String symbol, String sectorHint) {
        ValuationData data = new ValuationData();
        data.setSymbol(symbol);
        data.setFetchedAt(System.currentTimeMillis());

        // Prefer the caller's sector hint over the small hardcoded symbol map
        String industry = sectorHint != null ? sectorHint : getIndustryForSymbol(symbol);
        data.setIndustry(industry);

        Double sectorPe = livePeerMedianPe(sectorHint);
        data.setIndustryPe(sectorPe != null ? sectorPe : getDefaultIndustryPe(industry));

        // Stock PE unavailable in fallback
        data.setStockPe(null);
        data.setPeDeviation(null);

        return data;
    }

    /**
     * Map major NSE symbols to industries (for fallback).
     */
    private String getIndustryForSymbol(String symbol) {
        Map<String, String> symbolMap = Map.ofEntries(
            Map.entry("RELIANCE", "REFINERIES"),
            Map.entry("TCS", "IT"),
            Map.entry("HDFCBANK", "BANKS"),
            Map.entry("INFY", "IT"),
            Map.entry("SBIN", "BANKS"),
            Map.entry("JINDALSTEL", "METALS"),
            Map.entry("JSWSTEEL", "METALS"),
            Map.entry("VEDL", "METALS"),
            Map.entry("M&M", "AUTO"),
            Map.entry("TATAPOWER", "POWER"),
            Map.entry("ICICIBANK", "BANKS"),
            Map.entry("MARKSANS", "PHARMA"),
            Map.entry("ALKEM", "PHARMA"),
            Map.entry("LUPIN", "PHARMA"),
            Map.entry("IPCALAB", "PHARMA"),
            Map.entry("AUROPHARMA", "PHARMA"),
            Map.entry("ONGC", "OIL & GAS"),
            Map.entry("POWERGRID", "POWER"),
            Map.entry("RVNL", "INFRASTRUCTURE"),
            Map.entry("HSCL", "INFRASTRUCTURE")
        );
        String known = symbolMap.get(symbol.toUpperCase());
        if (known != null) return known;
        // B-096: the screener's ~90-name table knows far more of the universe than this list.
        // "GENERAL" remains the honest last resort, and SectorMapping treats it as unclassified.
        String fromScreener = com.example.trading.multibagger.MultibaggerScreenerService.sectorFor("NSE:" + symbol.toUpperCase());
        return fromScreener != null ? fromScreener : "GENERAL";
    }

    // ======================== UTILITIES ========================

    /**
     * Parse a value from NSE response to Double.
     * Handles String, Number, and "-" (dash) values.
     */
    private Double parseDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            return d > 0 ? d : null;
        }
        try {
            String str = value.toString().trim();
            if (str.isEmpty() || "-".equals(str)) return null;
            double d = Double.parseDouble(str.replaceAll(",", ""));
            return d > 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Get default industry PE based on industry name.
     */
    public Double getDefaultIndustryPe(String industry) {
        if (industry == null) return 20.0; // Default market PE

        String upperIndustry = industry.toUpperCase().trim();

        // Exact match first
        if (DEFAULT_INDUSTRY_PE.containsKey(upperIndustry)) {
            return DEFAULT_INDUSTRY_PE.get(upperIndustry);
        }

        // Partial match
        for (Map.Entry<String, Double> entry : DEFAULT_INDUSTRY_PE.entrySet()) {
            if (upperIndustry.contains(entry.getKey()) || entry.getKey().contains(upperIndustry)) {
                return entry.getValue();
            }
        }

        return 20.0; // Default market PE
    }

    /**
     * Clean symbol by removing exchange prefix.
     */
    private String cleanSymbol(String symbol) {
        if (symbol == null) return "";
        // Remove NSE:, BSE: prefix
        if (symbol.contains(":")) {
            return symbol.substring(symbol.indexOf(":") + 1);
        }
        return symbol;
    }

    /**
     * Get valuation interpretation.
     */
    public String getValuationInterpretation(Double stockPe, Double industryPe) {
        if (stockPe == null || industryPe == null || industryPe == 0) {
            return "N/A";
        }

        double deviation = ((stockPe - industryPe) / industryPe) * 100;

        if (deviation < -30) {
            return "Significantly Undervalued";
        } else if (deviation < -15) {
            return "Undervalued";
        } else if (deviation < -5) {
            return "Slightly Undervalued";
        } else if (deviation <= 5) {
            return "Fairly Valued";
        } else if (deviation <= 15) {
            return "Slightly Overvalued";
        } else if (deviation <= 30) {
            return "Overvalued";
        } else {
            return "Significantly Overvalued";
        }
    }

    /**
     * Get badge type for valuation.
     */
    public String getValuationBadgeType(Double peDeviation) {
        if (peDeviation == null) return "neutral";

        if (peDeviation < -15) {
            return "success"; // Undervalued = good for buying
        } else if (peDeviation <= 15) {
            return "neutral"; // Fairly valued
        } else {
            return "warning"; // Overvalued
        }
    }

    /**
     * Valuation data container.
     */
    @lombok.Data
    public static class ValuationData {
        private String symbol;
        private String industry;
        private Double stockPe;
        private Double industryPe;
        private Double peDeviation;      // % deviation from industry PE
        private Double marketCap;        // In crores
        private Double bookValue;
        private Double priceToBook;
        private Double eps;
        private Double dividendYield;
        private long fetchedAt;

        // Cache expires after 24 hours
        private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000;

        public boolean isStale() {
            return System.currentTimeMillis() - fetchedAt > CACHE_TTL_MS;
        }
    }
}
