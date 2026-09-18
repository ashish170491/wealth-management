package com.example.trading.ai;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches financial data from NSE India API:
 * - Quarterly financial results (revenue, profit, EPS)
 * - Shareholding patterns (promoter, FII, DII, public)
 * - Corporate actions and announcements
 */
@Service
@Slf4j
@lombok.RequiredArgsConstructor
public class NseDataService {

    /** Bhavcopy-backed delivery % (B-021) — replaces the bot-walled per-stock endpoint. */
    private final NseDeliveryDataService deliveryDataService;

    private WebClient webClient;
    private String cachedCookies;
    private long cookieExpiry;

    private static final String NSE_BASE_URL = "https://www.nseindia.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L; // 30 minutes

    /**
     * Max bytes buffered from an NSE response (B-054). The 256 KB WebClient default was
     * silently truncating the corporate-announcements feed for large caps into an empty
     * list, which read downstream as "no announcements" rather than as a failure.
     */
    private static final int NSE_MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    // ---------------------------------------------------------------- NSE request pacing

    /**
     * Process-wide spacing between bulk NSE archive requests, in milliseconds.
     *
     * <p><b>Why this exists.</b> Nothing in this class was paced. The per-year archive loop and
     * the per-symbol holdings loop were both tight, while the holdings-backfill javadoc claimed
     * "it is paced" — a 33-stock run was ~360 unthrottled requests at the host that has already
     * bot-walled {@code /api/quote-equity} for good (B-018). Extending that to the full screening
     * universe is ~4,000 requests, so the pacing has to be real before the volume arrives.
     *
     * <p><b>Why static.</b> The limit belongs to NSE, not to a caller, so the gate must be
     * process-wide — the same argument as the Kite pacing in {@code KiteBrokerClient} (Gotcha 23).
     * Two independently-paced loops running on two of the four scheduler threads would each
     * respect their own budget and together break the real one.
     *
     * <p>Applies only to the bulk archive paths. Single interactive calls (one quote, one
     * shareholding fetch) are not gated: they are not the volume problem and delaying them would
     * slow a page the investor is waiting on.
     */
    private static final Object NSE_PACE_LOCK = new Object();
    private static long lastPacedCallAt = 0L;
    private static volatile long archivePaceMs = 1200L;

    /** Configure the bulk-archive spacing. Clamped: 0 disables nothing, it just floors at 100 ms. */
    public static void setArchivePaceMs(long ms) {
        archivePaceMs = Math.max(100L, ms);
    }

    public static long getArchivePaceMs() {
        return archivePaceMs;
    }

    /**
     * Block until this thread may make another bulk NSE request.
     *
     * <p>Deliberately a plain {@code wait} on a monitor rather than a sleep outside the lock:
     * with several scheduler threads the naive "sleep then call" spaces each thread's own calls
     * and lets them collide with each other. Interruption restores the flag and returns rather
     * than throwing, because a paced loop must be cancellable at shutdown without losing the work
     * already written.
     */
    public static void pace() {
        long wait;
        synchronized (NSE_PACE_LOCK) {
            long now = System.currentTimeMillis();
            long earliest = lastPacedCallAt + archivePaceMs;
            wait = Math.max(0L, earliest - now);
            lastPacedCallAt = Math.max(now, earliest);
        }
        if (wait <= 0) return;
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final Map<String, CachedData<?>> dataCache = new ConcurrentHashMap<>();

    private static class CachedData<T> {
        T data;
        long expiry;
        CachedData(T data, long ttlMs) {
            this.data = data;
            this.expiry = System.currentTimeMillis() + ttlMs;
        }
        boolean isExpired() { return System.currentTimeMillis() > expiry; }
    }

    @SuppressWarnings("unchecked")
    private <T> T getCached(String key) {
        CachedData<?> cached = dataCache.get(key);
        if (cached != null && !cached.isExpired()) {
            return (T) cached.data;
        }
        if (cached != null && cached.isExpired()) {
            dataCache.remove(key);
        }
        return null;
    }

    private <T> void putCache(String key, T data) {
        dataCache.put(key, new CachedData<>(data, CACHE_TTL_MS));
    }

    private <T> void putCache(String key, T data, long ttlMs) {
        dataCache.put(key, new CachedData<>(data, ttlMs));
    }

    /** Annual filings change ~once a year — cache far longer than the 30-min default. */
    private static final long ANNUAL_CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000L; // 7 days

    /** Listed insurers — NSE's financial-results feed returns nothing for them (they report under
     *  IRDAI format). Used to give a precise NA reason since NSE's industry string is unreliable. */
    private static final java.util.Set<String> KNOWN_INSURERS = java.util.Set.of(
            "SBILIFE", "HDFCLIFE", "ICICIPRULI", "ICICIGI", "LICI", "STARHEALTH",
            "MAXFINANCIAL", "NIACL", "GICRE", "GODIGIT", "NIVABUPA", "MEDIASSIST");

    /**
     * Shared NSE client.
     *
     * <p><b>The buffer size is load-bearing (B-054).</b> WebClient defaults to 256 KB and
     * throws {@code DataBufferLimitException} beyond it. NSE's corporate-announcements feed
     * for an active large cap exceeds that comfortably, so every call for those companies
     * failed — and because the failure was caught and logged at DEBUG, it looked exactly
     * like "this company has no announcements". Same failure mode CLAUDE.md already records
     * for the Kite instruments CSV, on a different client.
     *
     * <p>16 MB is far above any observed NSE JSON payload while still bounding a runaway
     * response. Every new call site inherits it automatically by using this client.
     */
    private WebClient getWebClient() {
        if (webClient == null) {
            webClient = WebClient.builder()
                    .baseUrl(NSE_BASE_URL)
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(NSE_MAX_RESPONSE_BYTES))
                    .defaultHeader(HttpHeaders.USER_AGENT,
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                    .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9")
                    .defaultHeader("Referer", NSE_BASE_URL + "/")
                    .build();
        }
        return webClient;
    }

    private void refreshCookies() {
        if (System.currentTimeMillis() < cookieExpiry && cachedCookies != null) {
            return;
        }
        try {
            getWebClient().get()
                    .uri("/")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .exchangeToMono(response -> {
                        List<String> cookies = response.headers().header(HttpHeaders.SET_COOKIE);
                        if (!cookies.isEmpty()) {
                            StringBuilder cb = new StringBuilder();
                            for (String cookie : cookies) {
                                String[] parts = cookie.split(";")[0].split("=", 2);
                                if (parts.length == 2) {
                                    if (cb.length() > 0) cb.append("; ");
                                    cb.append(parts[0]).append("=").append(parts[1]);
                                }
                            }
                            cachedCookies = cb.toString();
                        }
                        return response.bodyToMono(String.class);
                    })
                    .block(Duration.ofSeconds(10));

            cookieExpiry = System.currentTimeMillis() + 150_000; // 2.5 minutes
        } catch (Exception e) {
            log.debug("NSE cookie refresh failed: {}", e.getMessage());
        }
    }

    /**
     * Fetch quarterly financial results for a stock.
     * Returns last several quarters of revenue, profit, EPS, expense breakdown.
     *
     * <p><b>Data source (B-017, 2026-05-24):</b> NSE migrated financial results to the
     * <b>integrated-filing</b> system (~Jan 2025); the old {@code /api/results-comparision}
     * and {@code /api/corporates-financial-results} endpoints are frozen at Dec-2024. We now
     * read {@code /api/integrated-filing-results} — one "Integrated Filing- Financials" record
     * per quarter, each linking an {@code INTEGRATED_FILING_INDAS}/{@code _BANKING} XBRL under
     * the SEBI {@code in-capmkt} namespace. Each XBRL carries that quarter's P&L in the OneD
     * (current 3-month) context; we fetch up to 8 recent quarters (Consolidated preferred).
     *
     * <p><b>Units:</b> XBRL values are in <b>rupees</b> → {@link #crore} divides by 1e7. EPS is
     * per-share (no conversion). Margin/growth ratios are unit-independent.
     *
     * <p><b>Coverage caveats:</b> the integrated system started ~Mar-2025, so ~5 quarters are
     * available today (enough for QoQ/YoY; 8-quarter CAGR fills in over time). Depreciation IS
     * now exposed ({@code DepreciationDepletionAndAmortisationExpense}). Banks use
     * {@code ProfitLossForThePeriod} + the BANKING XBRL.
     */
    @SuppressWarnings("unchecked")
    public List<QuarterlyResult> fetchQuarterlyResults(String tradingSymbol) {
        String cacheKey = "quarterly:" + tradingSymbol;
        List<QuarterlyResult> cached = getCached(cacheKey);
        if (cached != null) {
            log.debug("Returning cached quarterly results for {}", tradingSymbol);
            return cached;
        }

        List<QuarterlyResult> results = new ArrayList<>();
        try {
            // One financial filing per quarter (newest first, Consolidated preferred). Each filing's
            // XBRL carries that quarter's P&L under the OneD context.
            List<Map<String, Object>> filings = financialFilings(tradingSymbol, false);
            for (Map<String, Object> f : filings) {
                if (results.size() >= 8) break;
                String url = getString(f, "xbrl", null);
                if (url == null || url.isBlank()) continue;
                XbrlDoc doc = fetchAndParseXbrl(url);
                if (doc == null || doc.quarterCtx == null) continue;
                String q = doc.quarterCtx;

                QuarterlyResult qr = new QuarterlyResult();
                qr.setPeriod(getString(f, "qe_Date", "?"));

                // Filing metadata (SPEC §50). These four were always on the index row and were
                // discarded; without them a stored result cannot say when it became public, on
                // what basis it was filed, or whether it superseded an earlier one.
                qr.setQuarterEnd(parseIntegratedDate(getString(f, "qe_Date", null)));
                String basis = getString(f, "consolidated", null);
                qr.setConsolidated(basis == null || basis.isBlank()
                        ? null : "Consolidated".equalsIgnoreCase(basis));
                String audited = getString(f, "audited", null);
                qr.setAudited(audited == null || audited.isBlank()
                        ? null : audited.toLowerCase().contains("audited"));
                qr.setAvailableFrom(parseBroadcastDate(getString(f, "broadcast_Date", null)));
                qr.setFilingSeqId(getString(f, "seq_Id", null));
                String revisedOn = getString(f, "revised_Date", null);
                String subType = getString(f, "type_Sub", null);
                qr.setRevised((revisedOn != null && !revisedOn.isBlank())
                        || (subType != null && subType.toLowerCase().contains("revis")));
                qr.setRevisionRemark(getString(f, "revision_Remark", null));

                qr.setRevenue(crore(factC(doc, q, "RevenueFromOperations", "Income", "TotalIncome", "RevenueFromOperationsNet")));
                qr.setProfit(crore(factC(doc, q, "ProfitLossForPeriod", "ProfitLossForThePeriod",
                        "ProfitLossAfterTaxesMinorityInterestAndShareOfProfitLossOfAssociates")));
                // Ind-AS names first, then the BANKING taxonomy's ...AfterExtraordinaryItems /
                // ...BeforeExtraordinaryItems variants — without these, EPS is null for every
                // bank, which used to make PE and market cap uncomputable for the whole sector.
                qr.setEps(factC(doc, q, "BasicEarningsLossPerShareFromContinuingAndDiscontinuedOperations",
                        "BasicEarningsLossPerShareFromContinuingOperations", "BasicEarningsLossPerShare",
                        "BasicEarningsPerShareAfterExtraordinaryItems",
                        "BasicEarningsPerShareBeforeExtraordinaryItems"));

                // Exact share count: paid-up equity capital ÷ face value. Present in BOTH the
                // Ind-AS and BANKING taxonomies, and — unlike back-solving shares from
                // profit÷EPS — immune to a single quarter's exceptional item. Verified against
                // ITC: 12,529,500,000 / ₹1 = 1,252.95 cr shares (matches the real count).
                Double paidUp = factC(doc, q, "PaidUpValueOfEquityShareCapital");
                Double faceValue = factC(doc, q, "FaceValueOfEquityShareCapital");
                if (paidUp != null && faceValue != null && faceValue > 0 && paidUp > 0) {
                    qr.setSharesOutstandingCr((paidUp / faceValue) / 1_00_00_000.0);
                }

                // COGS = materials consumed + purchases of stock-in-trade + inventory change (banks: null)
                Double cogs = sumNonNull(
                        factC(doc, q, "CostOfMaterialsConsumed"),
                        factC(doc, q, "PurchasesOfStockInTrade"),
                        factC(doc, q, "ChangesInInventoriesOfFinishedGoodsWorkInProgressAndStockInTrade"));
                qr.setCogs(crore(cogs));

                Double pbt = factC(doc, q, "ProfitBeforeTax");
                Double fin = factC(doc, q, "FinanceCosts");
                qr.setFinanceCost(crore(fin));
                // Operating profit proxy = EBIT = PBT + finance costs (what interest-coverage needs)
                if (pbt != null) qr.setOperatingProfit(crore(pbt + (fin != null ? fin : 0)));
                // Depreciation IS exposed in the integrated-filing XBRL (unlike the old JSON — B-010)
                qr.setDepreciation(crore(factC(doc, q,
                        "DepreciationDepletionAndAmortisationExpense", "DepreciationAndAmortisationExpense")));
                qr.setTax(crore(factC(doc, q, "TaxExpense", "CurrentTax", "TotalTaxExpense")));
                qr.setTotalExpenses(crore(factC(doc, q, "Expenses", "TotalExpenses")));

                if (qr.getRevenue() != null && qr.getRevenue() != 0) {
                    if (qr.getOperatingProfit() != null) qr.setOperatingMargin(qr.getOperatingProfit() / qr.getRevenue() * 100.0);
                    if (qr.getProfit() != null) qr.setNetMargin(qr.getProfit() / qr.getRevenue() * 100.0);
                    if (qr.getCogs() != null) qr.setGrossMargin((qr.getRevenue() - qr.getCogs()) / qr.getRevenue() * 100.0);
                }
                results.add(qr);
            }
            log.debug("Fetched {} quarterly results for {} via integrated-filing (latest: period={}, revenue=₹{}cr, profit=₹{}cr, eps={})",
                    results.size(), tradingSymbol,
                    results.isEmpty() ? null : results.get(0).getPeriod(),
                    results.isEmpty() ? null : results.get(0).getRevenue(),
                    results.isEmpty() ? null : results.get(0).getProfit(),
                    results.isEmpty() ? null : results.get(0).getEps());
        } catch (Exception e) {
            log.debug("Quarterly results fetch failed for {}: {}", tradingSymbol, e.getMessage());
        }

        if (!results.isEmpty()) {
            putCache(cacheKey, results);
        }
        return results;
    }

    /** NSE returns financial values in lakhs; convert to crore (1 cr = 100 lakhs). */
    private static Double toCrore(Double lakhs) {
        return lakhs == null ? null : lakhs / 100.0;
    }

    private static Double sumNonNull(Double... values) {
        double sum = 0;
        boolean any = false;
        for (Double v : values) {
            if (v != null) { sum += v; any = true; }
        }
        return any ? sum : null;
    }

    /**
     * Parse NSE's "DD-MMM-YYYY" dates. Tolerant to case — NSE returns uppercase
     * months ("31-DEC-2024") in some endpoints and mixed case ("31-Dec-2024") in
     * others; case-insensitive parsing handles both. Returns null on any parse
     * failure (caller treats as "unknown date").
     */
    private static final java.time.format.DateTimeFormatter NSE_DATE_FMT =
            new java.time.format.DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern("dd-MMM-yyyy")
                    .toFormatter(java.util.Locale.ENGLISH);

    private static java.time.LocalDate parseNseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(s, NSE_DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse the promoter pledge % from a shareholding-pattern XBRL file.
     * NSE's JSON shareholding API doesn't expose pledge — only the boolean
     * "any-pledge-exists" flag. The actual numeric percentage lives in the
     * per-record XBRL at {@code <EncumberedShareUnderPledgedAsPercentageOfTotalNumberOfShares>}
     * with {@code contextRef="ShareholdingOfPromoterAndPromoterGroup_ContextI"}.
     *
     * <p>XBRL stores ratios (0.008 = 0.8%); we multiply by 100 to return percent
     * to match the rest of the system's convention (where
     * {@link ShareholdingQuarter#getPromoterHolding()} is e.g. 74.67 not 0.7467).
     *
     * <p>This means {@code FinancialQuality}'s pledge-based critical-flag check
     * ({@code pledge > 50}) finally fires on debt-trapped promoter-pledged
     * companies — they can no longer cross the 65-point multibagger threshold.
     *
     * <p>Returns null on any error (network failure, bad XML, missing tag).
     * The shareholding fetch as a whole degrades gracefully — pledge null
     * means "unknown", not "no pledge".
     */
    private Double fetchPledgePercentFromXbrl(String xbrlUrl) {
        if (xbrlUrl == null || xbrlUrl.isBlank()) return null;
        try {
            // XBRL files live at nsearchives.nseindia.com (a different host from the
            // API origin), so we use a dedicated client rather than the cookie-bound
            // one used for JSON endpoints. Bump the buffer to 2 MB — XBRL filings are
            // typically 200–500 KB which exceeds WebClient's default 256 KB limit.
            String xml = WebClient.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                    .build().get()
                    .uri(xbrlUrl)
                    .header(HttpHeaders.USER_AGENT,
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0")
                    .header(HttpHeaders.REFERER, NSE_BASE_URL + "/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);
            if (xml == null || xml.isEmpty()) return null;

            javax.xml.parsers.DocumentBuilderFactory dbf =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false); // XBRL uses namespaces; ignoring lets us match local names
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document doc = dbf.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));

            // The tag name in source includes XBRL namespace prefixes (e.g.
            // in-bse-shp:EncumberedShareUnderPledgedAsPercentageOfTotalNumberOfShares).
            // With namespace-aware=false getElementsByTagName matches the full qualified name,
            // so we scan all elements and compare local name.
            //
            // Strategy: first check the boolean
            //   WhetherAnySharesHeldByPromotersAreEncumberedUnderPledgedForPromoterAndPromoterGroup.
            // If false → return 0.0 (explicit "no pledge"). If true or unknown, scan for the
            // numeric value with the promoter context.
            org.w3c.dom.NodeList all = doc.getElementsByTagName("*");
            Boolean promoterHasPledge = null;
            Double promoterPledgeRatio = null;
            for (int i = 0; i < all.getLength(); i++) {
                org.w3c.dom.Node n = all.item(i);
                String localName = n.getNodeName();
                int colon = localName.indexOf(':');
                if (colon >= 0) localName = localName.substring(colon + 1);

                if ("WhetherAnySharesHeldByPromotersAreEncumberedUnderPledgedForPromoterAndPromoterGroup".equals(localName)) {
                    String txt = n.getTextContent();
                    if (txt != null) {
                        promoterHasPledge = "true".equalsIgnoreCase(txt.trim());
                    }
                    continue;
                }

                if (!"EncumberedShareUnderPledgedAsPercentageOfTotalNumberOfShares".equals(localName)) {
                    continue;
                }
                org.w3c.dom.NamedNodeMap attrs = n.getAttributes();
                if (attrs == null) continue;
                org.w3c.dom.Node ctxAttr = attrs.getNamedItem("contextRef");
                if (ctxAttr == null) continue;
                if (!"ShareholdingOfPromoterAndPromoterGroup_ContextI".equals(ctxAttr.getNodeValue())) {
                    continue;
                }
                String txt = n.getTextContent();
                if (txt == null || txt.isBlank()) continue;
                try {
                    promoterPledgeRatio = Double.parseDouble(txt.trim());
                } catch (NumberFormatException ignored) {}
            }

            // If the boolean explicitly says "no pledge", surface 0.0 (vs null which means
            // "unknown"). FinancialQuality treats 0/null identically for the critical-flag
            // check but downstream displays distinguish them.
            if (Boolean.FALSE.equals(promoterHasPledge)) return 0.0;
            if (promoterPledgeRatio == null) return null;
            // XBRL ratio (0.008) → percent (0.8). Note: this is % of TOTAL shares, not % of
            // promoter holding. Divide by (promoter%/100) at the call site if a "% of promoter"
            // unit is needed. FinancialQuality's `pledge > 50` check therefore triggers
            // only at *extreme* pledge levels — a follow-up may want to lower that threshold
            // or convert the unit here once it's clear what downstream expects.
            return promoterPledgeRatio * 100.0;
        } catch (Exception e) {
            log.debug("XBRL pledge parse failed for {}: {}", xbrlUrl, e.getMessage());
            return null;
        }
    }

    // ============================================================
    // Annual balance-sheet / cash-flow from NSE annual Ind-AS XBRL (SPEC.md §12.8)
    // Source: /api/corporates-financial-results?period=Annual → pick latest Consolidated
    //         filing → fetch its in-bse-fin XBRL from nsearchives → parse BS + CF + P&L.
    // Powers the capital-efficiency metrics (ROCE / ROE / D-E / real cash conversion /
    // dividend payout) that the income-statement-only quarterly feed cannot.
    //
    // CONTEXT MODEL (critical): the BSE in-bse-fin taxonomy crams the current quarter AND
    // the full year into contexts whose period <dates> can be identical/unreliable. The
    // period TYPE is encoded in the context-ID convention, not the dates:
    //   FourD = current year-to-date (= full FY in an annual filing)   ← annual flows
    //   OneD  = current quarter                                         (fallback)
    //   OneI  = current period-end instant                             ← balance sheet
    // We therefore select the annual duration context by largest real duration when dates
    // are trustworthy, else fall back to the "FourD"/"OneD" convention; balance-sheet facts
    // use the latest instant context.
    // ============================================================

    /**
     * Fetch and parse a stock's latest annual financial statement (balance sheet +
     * cash flow + annual P&L) from NSE's annual Ind-AS XBRL. Prefers the Consolidated
     * filing. Cached 7 days. Returns null on any failure (degrades gracefully).
     */
    public BalanceSheetData fetchAnnualFinancials(String tradingSymbol) {
        String cacheKey = "annualfin:" + tradingSymbol;
        BalanceSheetData cached = getCached(cacheKey);
        if (cached != null) return cached;

        try {
            // Annual = the latest March-quarter integrated financial filing, whose XBRL carries
            // the full year under the annual (FourD) context and the year-end balance sheet (OneI).
            List<Map<String, Object>> filings = financialFilings(tradingSymbol, true);
            if (filings.isEmpty()) {
                log.debug("Annual financials: no annual integrated filing for {}", tradingSymbol);
                return null;
            }
            Map<String, Object> best = filings.get(0); // newest March filing, Consolidated preferred
            XbrlDoc doc = fetchAndParseXbrl(getString(best, "xbrl", null));
            if (doc == null) return null;

            BalanceSheetData bs = buildAnnual(doc, tradingSymbol,
                    deriveFyLabel(getString(best, "qe_Date", null)),
                    "Consolidated".equalsIgnoreCase(getString(best, "consolidated", "")),
                    getString(best, "broadcast_Date", null));
            if (bs == null) return null;
            putCache(cacheKey, bs, ANNUAL_CACHE_TTL_MS);
            log.debug("Annual financials {} ({} {}{}): equity=Rs.{}cr assets=Rs.{}cr CFO=Rs.{}cr netProfit=Rs.{}cr",
                    tradingSymbol, bs.getFinancialYear(), bs.isConsolidated() ? "Consolidated" : "Standalone",
                    bs.isBanking() ? " BANK" : "",
                    bs.getEquity(), bs.getTotalAssets(), bs.getOperatingCashFlow(), bs.getNetProfit());
            return bs;
        } catch (Exception e) {
            log.debug("Annual financials fetch failed for {}: {}", tradingSymbol, e.getMessage());
            return null;
        }
    }

    /**
     * Every annual filing NSE still serves for a symbol, oldest financial year first (SPEC §32.5).
     *
     * <p><b>Why the "frozen" endpoint.</b> B-017 migrated financial-result <em>discovery</em> to
     * the integrated-filing feed because {@code corporates-financial-results} stopped receiving
     * <em>new</em> filings after Dec-2024. It was never removed: the archive behind it still
     * returns 200 with 13-14 years of annual filings per symbol, each with its XBRL link. That is
     * exactly the pre-2025 past the CSV import existed to bridge, and it needs no parser work —
     * those filings use the {@code in-bse-fin} taxonomy with {@code FourD}/{@code OneD}/{@code OneI}
     * contexts, which is the taxonomy this parser was originally written for.
     *
     * <p>Read-only history, not a live feed. Going forward the table still maintains itself from
     * the integrated filing on every screening run (Gotcha 49); this is the one-time backfill.
     *
     * @param maxYears newest N financial years, or all of them when {@code <= 0}
     */
    public List<BalanceSheetData> fetchAnnualArchive(String tradingSymbol, int maxYears) {
        return fetchAnnualArchiveDetailed(tradingSymbol, maxYears).years();
    }

    /**
     * What one symbol's archive listing actually contained, alongside the years that parsed.
     *
     * <p>The plain {@link #fetchAnnualArchive} cannot distinguish "this company has filed three
     * annual results" from "thirteen were listed and ten failed to parse" — both return three
     * rows. The backfill's status table has to tell those apart or it retries a complete symbol
     * forever (SPEC §32.6), so the counts are reported rather than inferred from the list length.
     *
     * @param years            filings that parsed into figures, oldest first
     * @param listedYears      annual filings the listing offered, after the maxYears cut
     * @param nonMarchSkipped  filings dropped for a non-March year end (defect 7.5)
     * @param listingFailed    true when the listing call itself failed — distinct from an empty
     *                         archive, which is a finding about the company
     */
    public record ArchiveFetch(List<BalanceSheetData> years, int listedYears,
                               int nonMarchSkipped, boolean listingFailed) {
    }

    public ArchiveFetch fetchAnnualArchiveDetailed(String tradingSymbol, int maxYears) {
        List<BalanceSheetData> out = new ArrayList<>();
        int[] nonMarch = new int[1];
        boolean[] failed = new boolean[1];
        Map<String, Map<String, Object>> byYear =
                archiveFilingsByYear(tradingSymbol, nonMarch, failed);
        if (byYear.isEmpty()) return new ArchiveFetch(out, 0, nonMarch[0], failed[0]);

        List<String> years = new ArrayList<>(byYear.keySet());
        years.sort(Comparator.reverseOrder());          // keys are yyyy, newest first
        if (maxYears > 0 && years.size() > maxYears) years = years.subList(0, maxYears);

        for (String year : years) {
            Map<String, Object> f = byYear.get(year);
            try {
                String url = getString(f, "xbrl", null);
                // Pace only an actual network call. A cached filing (7-day TTL, immutable
                // document) must not pay the delay, or a re-run of a symbol already fetched
                // this session would cost the full wall-clock time for no requests at all.
                if (url != null && !isXbrlCached(url)) pace();
                XbrlDoc doc = fetchAndParseXbrl(url);
                if (doc == null) continue;
                BalanceSheetData bs = buildAnnual(doc, tradingSymbol,
                        getString(f, "financialYear", null),
                        "Consolidated".equalsIgnoreCase(getString(f, "consolidated", "")),
                        getString(f, "filingDate", null));
                if (bs != null) out.add(bs);
            } catch (Exception e) {
                // One unreadable year must not lose the other twelve.
                log.debug("Annual archive: {} FY{} unreadable: {}", tradingSymbol, year, e.getMessage());
            }
        }
        Collections.reverse(out);                        // oldest first, the order every caller wants
        log.info("Annual archive {}: {} of {} years parsed{}", tradingSymbol, out.size(), years.size(),
                nonMarch[0] > 0 ? " (" + nonMarch[0] + " filings skipped for a non-March year end)" : "");
        return new ArchiveFetch(out, years.size(), nonMarch[0], failed[0]);
    }

    /**
     * Annual filings from the legacy archive, one per financial year (Consolidated preferred,
     * newest filing within a year wins), keyed by the four-digit year ending.
     */
    private Map<String, Map<String, Object>> archiveFilingsByYear(String tradingSymbol,
                                                                  int[] nonMarchSkipped,
                                                                  boolean[] listingFailed) {
        Map<String, Map<String, Object>> byYear = new LinkedHashMap<>();
        try {
            pace();
            refreshCookies();
            List<?> resp = getWebClient().get()
                    .uri("/api/corporates-financial-results?index=equities&symbol="
                            + tradingSymbol + "&period=Annual")
                    .header("Cookie", cachedCookies != null ? cachedCookies : "")
                    .retrieve()
                    .bodyToMono(List.class)
                    .block(TIMEOUT);
            if (resp == null) return byYear;

            for (Object o : resp) {
                if (!(o instanceof Map<?, ?> raw)) continue;
                Map<String, Object> f = (Map<String, Object>) raw;
                String url = getString(f, "xbrl", null);
                if (url == null || url.isBlank()) continue;
                String toDate = getString(f, "toDate", null);          // "31-Mar-2024"
                if (toDate == null || !toDate.toUpperCase().startsWith("31-MAR")) {
                    // Counted rather than dropped in silence (defect 7.5). A company that changed
                    // its year end used to vanish from the archive with no record of why, which
                    // the backfill would then file as "no history" — a fact about our parser
                    // reported as a fact about the company.
                    if (toDate != null) nonMarchSkipped[0]++;
                    continue;
                }
                String year = toDate.substring(toDate.length() - 4);
                if (!year.matches("\\d{4}")) continue;

                Map<String, Object> existing = byYear.get(year);
                boolean cons = "Consolidated".equalsIgnoreCase(getString(f, "consolidated", ""));
                // Consolidated outranks standalone; among equals, the first row wins (NSE
                // returns newest first, and a later filing for the same year is a revision).
                if (existing == null
                        || (cons && !"Consolidated".equalsIgnoreCase(getString(existing, "consolidated", "")))) {
                    byYear.put(year, f);
                }
            }
        } catch (Exception e) {
            listingFailed[0] = true;
            log.warn("Annual archive listing failed for {} - no history will be backfilled for it, "
                    + "which downstream reads as 'this company has no multi-year record' rather "
                    + "than as a fetch failure: {}", tradingSymbol, e.getMessage());
        }
        return byYear;
    }

    /** True when this filing's parsed document is already in cache, so fetching it costs no request. */
    private boolean isXbrlCached(String url) {
        Object cached = getCached("xbrl:" + url);
        return cached != null;
    }

    /**
     * Turn one parsed annual XBRL into a {@link BalanceSheetData}. Shared by the current-year
     * path and the archive backfill so both read exactly the same elements — a second copy of
     * this extraction would drift, and the two would disagree about the same company.
     */
    private BalanceSheetData buildAnnual(XbrlDoc doc, String tradingSymbol, String fyLabel,
                                         boolean consolidated, String filingDate) {
        try {
            String annualCtx = doc.annualCtx;
            String instantCtx = doc.instantCtx;
            boolean banking = doc.banking;

            BalanceSheetData bs = new BalanceSheetData();
            bs.setSymbol(tradingSymbol);
            bs.setFinancialYear(fyLabel);
            bs.setConsolidated(consolidated);
            bs.setBanking(banking);
            bs.setFilingDate(filingDate);

            if (banking) {
                // Shareholders' funds = share capital + reserves & surplus
                Double capital = factC(doc, instantCtx, "Capital", "EquityShareCapital");
                Double reserves = factC(doc, instantCtx, "ReservesAndSurplus", "ReserveExcludingRevaluationReserves");
                bs.setEquityShareCapital(crore(capital));
                bs.setEquity(crore(sumNonNull(capital, reserves)));
                bs.setTotalAssets(crore(factC(doc, instantCtx, "Assets")));
                bs.setNetProfit(crore(factC(doc, annualCtx,
                        "ProfitLossForThePeriod",
                        "ProfitLossAfterTaxesMinorityInterestAndShareOfProfitLossOfAssociates")));
                bs.setRevenue(crore(factC(doc, annualCtx, "Income", "RevenueFromOperations")));
                // borrowings / financeCosts / CFO / PBT intentionally left null for banks
            } else {
                bs.setEquity(crore(factC(doc, instantCtx, "Equity", "EquityAttributableToOwnersOfParent")));
                bs.setEquityShareCapital(crore(factC(doc, instantCtx, "EquityShareCapital")));
                bs.setOtherEquity(crore(factC(doc, instantCtx, "OtherEquity")));
                bs.setTotalBorrowings(crore(sumNonNull(
                        factC(doc, instantCtx, "BorrowingsNoncurrent"),
                        factC(doc, instantCtx, "BorrowingsCurrent"))));
                bs.setTotalAssets(crore(factC(doc, instantCtx, "Assets")));

                bs.setNetProfit(crore(factC(doc, annualCtx, "ProfitLossForPeriod", "ProfitLossAttributableToOwnersOfParent")));
                bs.setProfitBeforeTax(crore(factC(doc, annualCtx, "ProfitBeforeTax")));
                bs.setFinanceCosts(crore(factC(doc, annualCtx, "FinanceCosts")));
                bs.setRevenue(crore(factC(doc, annualCtx, "RevenueFromOperations", "Revenue")));
                bs.setOperatingCashFlow(crore(factC(doc, annualCtx, "CashFlowsFromUsedInOperatingActivities")));
                bs.setDividendsPaid(crore(factC(doc, annualCtx, "DividendsPaidClassifiedAsFinancingActivities")));
                bs.setDepreciation(crore(factC(doc, annualCtx,
                        "DepreciationDepletionAndAmortisationExpense",
                        "DepreciationAndAmortisationExpense")));

                // Capex cycle (SPEC.md §31). CWIP is capital already committed to plants that
                // are not yet producing revenue — the earliest balance-sheet trace of growth
                // the P&L cannot show for another 12-24 months.
                bs.setCapitalWorkInProgress(crore(factC(doc, instantCtx,
                        "CapitalWorkInProgress", "CapitalWorkinprogress")));
                bs.setPropertyPlantEquipment(crore(factC(doc, instantCtx,
                        "PropertyPlantAndEquipment", "TangibleAssets")));

                // Trade receivables and share count (B-046). Both are needed by the forensic
                // screen — receivables-vs-sales and dilution — and neither was ever read from
                // the annual filing, so both checks reported "not measured" for every stock
                // whose newest year came from XBRL rather than the CSV import.
                bs.setTradeReceivables(crore(sumNonNull(
                        factC(doc, instantCtx, "TradeReceivablesCurrent"),
                        factC(doc, instantCtx, "TradeReceivablesNoncurrent"))));
                if (bs.getTradeReceivables() == null) {
                    bs.setTradeReceivables(crore(factC(doc, instantCtx, "TradeReceivables")));
                }
                if (doc.priorInstantCtx != null) {
                    bs.setPriorYearAvailable(true);
                    bs.setPriorCapitalWorkInProgress(crore(factC(doc, doc.priorInstantCtx,
                            "CapitalWorkInProgress", "CapitalWorkinprogress")));
                    bs.setPriorPropertyPlantEquipment(crore(factC(doc, doc.priorInstantCtx,
                            "PropertyPlantAndEquipment", "TangibleAssets")));
                    // How much the comparative column actually carries. A resolved context
                    // with zero facts means the filing declares a prior period but tags no
                    // balance sheet against it — a data limit, not a parsing bug. Without
                    // this count the two are indistinguishable from the outside.
                    String suffix = "@" + doc.priorInstantCtx;
                    bs.setPriorYearFactCount((int) doc.facts.keySet().stream()
                            .filter(k -> k.endsWith(suffix)).count());
                }
            }

            // Exact share count, same derivation the quarterly parser uses: paid-up equity
            // capital / face value. Present in both the Ind-AS and BANKING taxonomies. Left
            // null when either leg is missing — a guessed share count would corrupt the
            // dilution flag, which depends on nothing else.
            Double paidUp = factC(doc, instantCtx, "PaidUpValueOfEquityShareCapital");
            Double faceValue = factC(doc, instantCtx, "FaceValueOfEquityShareCapital");
            if (paidUp == null) paidUp = factC(doc, annualCtx, "PaidUpValueOfEquityShareCapital");
            if (faceValue == null) faceValue = factC(doc, annualCtx, "FaceValueOfEquityShareCapital");
            if (paidUp != null && faceValue != null && faceValue > 0 && paidUp > 0) {
                bs.setSharesOutstandingCr((paidUp / faceValue) / 1_00_00_000.0);
            }
            // Kept rather than discarded (SPEC §32.6): a face-value change across two years is a
            // stock split as a fact, where B-066 can otherwise only infer one from a ratio.
            if (faceValue != null && faceValue > 0) bs.setFaceValue(faceValue);

            return bs;
        } catch (Exception e) {
            log.debug("Annual XBRL parse failed for {} {}: {}", tradingSymbol, fyLabel, e.getMessage());
            return null;
        }
    }

    // ---- Integrated-filing infrastructure (NSE migrated financial results here ~Jan 2025; B-017) ----

    /** Parsed XBRL: facts keyed "localName@contextRef" + resolved quarter/annual/instant context ids. */
    private static class XbrlDoc {
        Map<String, Double> facts;
        String quarterCtx;   // current 3-month duration (OneD)
        String annualCtx;    // full-year / YTD duration (FourD)
        String instantCtx;   // period-end balance sheet (OneI)
        String priorInstantCtx; // SAME filing's prior-FY comparative balance sheet (SPEC §31)
        boolean banking;     // parsed from INTEGRATED_FILING_BANKING_*.xml
    }

    private static final java.time.format.DateTimeFormatter INTEGRATED_DATE_FMT =
            new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive()
                    .appendPattern("dd-MMM-yyyy").toFormatter(java.util.Locale.ENGLISH);

    private java.time.LocalDate parseIntegratedDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return java.time.LocalDate.parse(s.trim(), INTEGRATED_DATE_FMT); }
        catch (Exception e) { return null; }
    }

    /**
     * Parse a filing's {@code broadcast_Date}, which carries a time of day:
     * {@code "23-Jul-2026 20:30:01"}.
     *
     * <p>Kept separate from {@link #parseIntegratedDate} rather than made tolerant, because
     * {@code qe_Date} never has a time and a parser that silently accepts trailing rubbish is how
     * a wrong value gets stored looking right. Returns null on anything unrecognised — the caller
     * then falls back to a conservative estimate, never to today (Gotcha 100).
     */
    private java.time.LocalDate parseBroadcastDate(String s) {
        if (s == null || s.isBlank()) return null;
        String date = s.trim();
        int space = date.indexOf(' ');
        if (space > 0) date = date.substring(0, space);
        return parseIntegratedDate(date);
    }

    /** "31-MAR-2026" → "01-Apr-2025 To 31-Mar-2026". */
    private String deriveFyLabel(String qeDate) {
        java.time.LocalDate d = parseIntegratedDate(qeDate);
        if (d == null) return qeDate;
        return String.format("01-Apr-%d To 31-Mar-%d", d.getYear() - 1, d.getYear());
    }

    /** Fetch the integrated-filing list (current financial-results feed), cached 30 min. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchIntegratedFilings(String tradingSymbol) {
        String cacheKey = "intfilings:" + tradingSymbol;
        List<Map<String, Object>> cachedList = getCached(cacheKey);
        if (cachedList != null) return cachedList;
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            refreshCookies();
            Map<String, Object> resp = getWebClient().get()
                    .uri("/api/integrated-filing-results?index=equities&symbol=" + tradingSymbol + "&period=Quarterly")
                    .header("Cookie", cachedCookies != null ? cachedCookies : "")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(TIMEOUT);
            if (resp != null && resp.get("data") instanceof List<?> inner) {
                for (Object o : inner) if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
            }
        } catch (Exception e) {
            log.debug("Integrated filings fetch failed for {}: {}", tradingSymbol, e.getMessage());
        }
        if (!out.isEmpty()) putCache(cacheKey, out);
        return out;
    }

    /**
     * The "Integrated Filing- Financials" records, deduped by quarter (Consolidated preferred),
     * newest first. {@code annualOnly} keeps only the March (year-end) filings.
     */
    private List<Map<String, Object>> financialFilings(String tradingSymbol, boolean annualOnly) {
        Map<String, Map<String, Object>> byQuarter = new LinkedHashMap<>();
        for (Map<String, Object> f : fetchIntegratedFilings(tradingSymbol)) {
            if (!getString(f, "type", "").toLowerCase().contains("financ")) continue;
            String url = getString(f, "xbrl", null);
            if (url == null || url.isBlank()) continue;
            String qe = getString(f, "qe_Date", null);
            if (qe == null) continue;
            if (annualOnly && !qe.toUpperCase().startsWith("31-MAR")) continue;
            boolean cons = "Consolidated".equalsIgnoreCase(getString(f, "consolidated", ""));
            Map<String, Object> existing = byQuarter.get(qe);
            if (existing == null
                    || (cons && !"Consolidated".equalsIgnoreCase(getString(existing, "consolidated", "")))) {
                byQuarter.put(qe, f);
            }
        }
        List<Map<String, Object>> picked = new ArrayList<>(byQuarter.values());
        picked.sort((a, b) -> {
            java.time.LocalDate la = parseIntegratedDate(getString(a, "qe_Date", null));
            java.time.LocalDate lb = parseIntegratedDate(getString(b, "qe_Date", null));
            if (la == null && lb == null) return 0;
            if (la == null) return 1;
            if (lb == null) return -1;
            return lb.compareTo(la);
        });
        return picked;
    }

    /** Download + DOM-parse an XBRL into an {@link XbrlDoc}. Cached per-URL 7 days (filings are immutable). */
    private XbrlDoc fetchAndParseXbrl(String url) {
        if (url == null || url.isBlank()) return null;
        String cacheKey = "xbrl:" + url;
        XbrlDoc cachedDoc = getCached(cacheKey);
        if (cachedDoc != null) return cachedDoc;
        try {
            String xml = WebClient.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                    .build().get()
                    .uri(url)
                    .header(HttpHeaders.USER_AGENT,
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0")
                    .header(HttpHeaders.REFERER, NSE_BASE_URL + "/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);
            if (xml == null || xml.isEmpty()) return null;

            javax.xml.parsers.DocumentBuilderFactory dbf =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false); // match by local name; the namespace prefix (in-capmkt/in-bse-fin) is irrelevant
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document document = dbf.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));

            Map<String, Double> facts = new HashMap<>();
            org.w3c.dom.NodeList all = document.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                org.w3c.dom.Node n = all.item(i);
                org.w3c.dom.NamedNodeMap attrs = n.getAttributes();
                if (attrs == null) continue;
                org.w3c.dom.Node ctx = attrs.getNamedItem("contextRef");
                if (ctx == null) continue;
                String ln = n.getNodeName();
                int colon = ln.indexOf(':');
                if (colon >= 0) ln = ln.substring(colon + 1);
                String txt = n.getTextContent();
                if (txt == null || txt.isBlank()) continue;
                try {
                    facts.put(ln + "@" + ctx.getNodeValue(), Double.parseDouble(txt.trim()));
                } catch (NumberFormatException ignored) { /* non-numeric fact */ }
            }

            XbrlDoc x = new XbrlDoc();
            x.facts = facts;
            x.quarterCtx = orConventional(resolveQuarterContext(document), "OneD", facts);
            x.annualCtx = orConventional(resolveAnnualContext(document), "FourD", facts);
            x.instantCtx = orConventional(resolveLatestInstantContext(document), "OneI", facts);
            x.priorInstantCtx = resolvePriorYearInstantContext(document);
            x.banking = url.toUpperCase().contains("BANKING");
            putCache(cacheKey, x, ANNUAL_CACHE_TTL_MS);
            return x;
        } catch (Exception e) {
            log.debug("XBRL fetch/parse failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Fall back to the taxonomy's context-ID convention when the document declares no matching
     * context but its facts reference one anyway.
     *
     * <p>Older archive filings (measured: RELIANCE FY2019-FY2022) tag every fact
     * {@code contextRef="FourD"} / {@code "OneD"} / {@code "OneI"} while declaring no
     * {@code <xbrli:context>} with those ids at all — only the segmented variants. The
     * date-driven resolvers therefore find nothing and every figure comes back null, which is how
     * a backfill produced four financial years of entirely empty rows.
     *
     * <p>Trusting the convention here is not a guess: this parser's whole context model already
     * rests on it ("the period TYPE is encoded in the context-ID convention, not the dates"). The
     * fallback fires only when resolution failed <em>and</em> facts actually reference the
     * conventional id, so a well-formed filing never reaches it.
     */
    private static String orConventional(String resolved, String conventionalId,
                                         Map<String, Double> facts) {
        if (resolved != null) return resolved;
        String suffix = "@" + conventionalId;
        return facts.keySet().stream().anyMatch(k -> k.endsWith(suffix)) ? conventionalId : null;
    }

    private Double factC(XbrlDoc doc, String ctx, String... names) {
        return doc == null ? null : factAt(doc.facts, ctx, names);
    }

    /** Current-quarter context: a plain (no-segment) duration of ~40–130 days with the latest end date; else "OneD". */
    private String resolveQuarterContext(org.w3c.dom.Document doc) {
        org.w3c.dom.NodeList ctxs = doc.getElementsByTagName("context");
        if (ctxs.getLength() == 0) ctxs = doc.getElementsByTagName("xbrli:context");
        String bestId = null; String bestEnd = "";
        boolean haveOneD = false;
        for (int i = 0; i < ctxs.getLength(); i++) {
            org.w3c.dom.Element c = (org.w3c.dom.Element) ctxs.item(i);
            String id = c.getAttribute("id");
            if ("OneD".equals(id)) haveOneD = true;
            if (hasSegmentOrScenario(c)) continue;
            String sd = childText(c, "startDate");
            String ed = childText(c, "endDate");
            if (sd == null || ed == null) continue;
            try {
                long span = java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.parse(sd), java.time.LocalDate.parse(ed));
                if (span >= 40 && span <= 130 && ed.compareTo(bestEnd) > 0) { bestEnd = ed; bestId = id; }
            } catch (Exception ignored) { /* unparseable dates */ }
        }
        if (bestId != null) return bestId;
        return haveOneD ? "OneD" : null;
    }

    /** First non-null fact value among the given local-names for a context. */
    private Double factAt(Map<String, Double> facts, String ctx, String... localNames) {
        if (ctx == null) return null;
        for (String ln : localNames) {
            Double v = facts.get(ln + "@" + ctx);
            if (v != null) return v;
        }
        return null;
    }

    /** Rupees → crore (1 cr = 1e7). */
    private static Double crore(Double rupees) {
        return rupees == null ? null : rupees / 1.0e7;
    }

    /**
     * Resolve the context id for the full-year duration. Dates in this taxonomy are
     * often unreliable (quarter and YTD share the same period element), so: prefer a
     * plain (no-segment) duration context spanning ≥330 days; otherwise fall back to
     * the BSE "FourD" (year-to-date) convention, then "OneD".
     */
    private String resolveAnnualContext(org.w3c.dom.Document doc) {
        org.w3c.dom.NodeList ctxs = doc.getElementsByTagName("context");
        if (ctxs.getLength() == 0) ctxs = doc.getElementsByTagName("xbrli:context");
        String bestId = null; long bestSpan = -1; String bestEnd = "";
        boolean haveFourD = false, haveOneD = false;
        for (int i = 0; i < ctxs.getLength(); i++) {
            org.w3c.dom.Element c = (org.w3c.dom.Element) ctxs.item(i);
            String id = c.getAttribute("id");
            if ("FourD".equals(id)) haveFourD = true;
            if ("OneD".equals(id)) haveOneD = true;
            if (hasSegmentOrScenario(c)) continue;
            String sd = childText(c, "startDate");
            String ed = childText(c, "endDate");
            if (sd == null || ed == null) continue;
            try {
                long span = java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.parse(sd), java.time.LocalDate.parse(ed));
                if (span >= 330 && (span > bestSpan || (span == bestSpan && ed.compareTo(bestEnd) > 0))) {
                    bestSpan = span; bestId = id; bestEnd = ed;
                }
            } catch (Exception ignored) { }
        }
        if (bestId != null) return bestId;          // real annual duration found
        if (haveFourD) return "FourD";              // BSE year-to-date convention
        if (haveOneD) return "OneD";
        return null;
    }

    /** Resolve the plain instant context with the latest date (the balance-sheet date). */
    private String resolveLatestInstantContext(org.w3c.dom.Document doc) {
        org.w3c.dom.NodeList ctxs = doc.getElementsByTagName("context");
        if (ctxs.getLength() == 0) ctxs = doc.getElementsByTagName("xbrli:context");
        String bestId = null; String bestDate = "";
        boolean haveOneI = false;
        for (int i = 0; i < ctxs.getLength(); i++) {
            org.w3c.dom.Element c = (org.w3c.dom.Element) ctxs.item(i);
            String id = c.getAttribute("id");
            if ("OneI".equals(id)) haveOneI = true;
            if (hasSegmentOrScenario(c)) continue;
            String inst = childText(c, "instant");
            if (inst == null) continue;
            if (inst.compareTo(bestDate) > 0) { bestDate = inst; bestId = id; }
        }
        if (bestId != null) return bestId;
        return haveOneI ? "OneI" : null;
    }

    /**
     * Resolve the PRIOR-YEAR comparative instant context (SPEC.md §31).
     *
     * <p>Ind-AS filings carry the previous financial year's balance sheet alongside the
     * current one in the same document, which is the only reason a year-on-year capex
     * delta is computable at all today: the integrated-filing system began ~Mar-2025, so
     * there is no second filing to diff against (B-017).
     *
     * <p>The window is deliberately tight — an instant is accepted only if it sits
     * 300–430 days before the latest one. Quarterly and half-yearly instants also appear
     * in these documents, and silently differencing against a 90-day-old balance sheet
     * would report a quarter's capex as a year's. Returns null when no comparative is
     * present, so callers get "not measured" rather than a wrong number.
     */
    private String resolvePriorYearInstantContext(org.w3c.dom.Document doc) {
        org.w3c.dom.NodeList ctxs = doc.getElementsByTagName("context");
        if (ctxs.getLength() == 0) ctxs = doc.getElementsByTagName("xbrli:context");

        // Pass 1: the latest plain instant — the current balance-sheet date.
        String latest = "";
        for (int i = 0; i < ctxs.getLength(); i++) {
            org.w3c.dom.Element c = (org.w3c.dom.Element) ctxs.item(i);
            if (hasSegmentOrScenario(c)) continue;
            String inst = childText(c, "instant");
            if (inst != null && inst.compareTo(latest) > 0) latest = inst;
        }
        if (latest.isEmpty()) return null;

        // Pass 2: the newest instant that is ~one year older than it.
        java.time.LocalDate latestDate;
        try {
            latestDate = java.time.LocalDate.parse(latest);
        } catch (Exception e) {
            return null;
        }
        String bestId = null;
        String bestDate = "";
        for (int i = 0; i < ctxs.getLength(); i++) {
            org.w3c.dom.Element c = (org.w3c.dom.Element) ctxs.item(i);
            if (hasSegmentOrScenario(c)) continue;
            String inst = childText(c, "instant");
            if (inst == null) continue;
            try {
                long back = java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.parse(inst), latestDate);
                if (back >= 300 && back <= 430 && inst.compareTo(bestDate) > 0) {
                    bestDate = inst;
                    bestId = c.getAttribute("id");
                }
            } catch (Exception ignored) { /* unparseable instant */ }
        }
        return bestId;
    }

    private boolean hasSegmentOrScenario(org.w3c.dom.Element ctx) {
        return ctx.getElementsByTagName("segment").getLength() > 0
                || ctx.getElementsByTagName("xbrli:segment").getLength() > 0
                || ctx.getElementsByTagName("scenario").getLength() > 0
                || ctx.getElementsByTagName("xbrli:scenario").getLength() > 0;
    }

    private String childText(org.w3c.dom.Element parent, String localName) {
        org.w3c.dom.NodeList nl = parent.getElementsByTagName(localName);
        if (nl.getLength() == 0) nl = parent.getElementsByTagName("xbrli:" + localName);
        if (nl.getLength() == 0) return null;
        String t = nl.item(0).getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    /**
     * Capital-efficiency metrics (SPEC.md §12.8) — the balance-sheet ratios that
     * actually identify long-term wealth creators: ROCE, ROE, Debt-to-Equity, real
     * cash conversion (CFO/PAT), and dividend payout. Computed from the latest annual
     * Ind-AS XBRL. Degrades gracefully (applicable=false with a reason) when data is
     * missing or the stock is a bank/financial where ROCE/D-E aren't comparable.
     *
     * @param industryHint optional industry/sector string (for financial-sector flagging); may be null
     */
    public CapitalEfficiencyData analyzeCapitalEfficiency(String tradingSymbol, String industryHint) {
        CapitalEfficiencyData ce = new CapitalEfficiencyData();
        ce.setSymbol(tradingSymbol);
        ce.setStrengths(new ArrayList<>());
        ce.setRedFlags(new ArrayList<>());

        // NSE's industry string is unreliable (often "GENERAL"), so back the industry-hint check with a
        // curated set of listed insurers — they're a small, stable universe and all hit the same data wall.
        boolean isInsurer = (industryHint != null && industryHint.toLowerCase().matches(".*(insur|life insurance|general insurance).*"))
                || KNOWN_INSURERS.contains(tradingSymbol.toUpperCase());

        BalanceSheetData bs = fetchAnnualFinancials(tradingSymbol);
        if (bs == null) {
            ce.setApplicable(false);
            ce.setFinancialSector(isInsurer);
            // Insurers report under IRDAI format (solvency margin, combined ratio, embedded value)
            // and are NOT carried in NSE's standard corporates-financial-results feed at all —
            // a data-availability wall, not a parsing gap. Give an honest, specific reason.
            ce.setNaReason(isInsurer
                    ? "Insurers report under IRDAI format (solvency ratio, combined ratio, embedded value) — "
                      + "not available in NSE's standard financial-results feed; ROCE/ROE/ROA not computed"
                    : "Annual financial statement unavailable from NSE (no filing, very recent IPO, or unsupported format)");
            ce.setOverallVerdict("NA");
            return ce;
        }
        ce.setFinancialYear(bs.getFinancialYear());
        ce.setConsolidated(bs.isConsolidated());

        // Banking taxonomy is authoritative for financial-sector detection; industry hint is a fallback.
        boolean isFinancial = bs.isBanking() || (industryHint != null && industryHint.toLowerCase()
                .matches(".*(bank|financ|nbfc|insur|capital market|holding).*"));
        ce.setFinancialSector(isFinancial);

        Double equity = bs.getEquity();
        Double netProfit = bs.getNetProfit();
        Double debt = bs.getTotalBorrowings();
        Double pbt = bs.getProfitBeforeTax();
        Double fin = bs.getFinanceCosts();
        Double cfo = bs.getOperatingCashFlow();

        // ROE = net profit / equity
        if (netProfit != null && equity != null && equity > 0) {
            double roe = netProfit / equity * 100.0;
            ce.setRoePercent(roe);
            ce.setRoeVerdict(roe >= 18 ? "EXCELLENT" : roe >= 15 ? "GOOD" : roe >= 12 ? "AVERAGE" : roe >= 0 ? "WEAK" : "LOSS");
            if (roe >= 18) ce.getStrengths().add(String.format("High ROE %.1f%% (efficient use of shareholder capital)", roe));
            else if (roe < 10 && roe >= 0) ce.getRedFlags().add(String.format("Low ROE %.1f%% (below cost of equity)", roe));
            else if (roe < 0) ce.getRedFlags().add("Negative ROE (loss-making)");
        }

        // ROA = net profit / total assets — the key efficiency metric for banks/financials
        // (Indian banks: >1.5% good, >1.8% excellent). Also a useful asset-productivity read for others.
        if (netProfit != null && bs.getTotalAssets() != null && bs.getTotalAssets() > 0) {
            double roa = netProfit / bs.getTotalAssets() * 100.0;
            ce.setRoaPercent(roa);
            ce.setRoaVerdict(roa >= 1.8 ? "EXCELLENT" : roa >= 1.5 ? "GOOD" : roa >= 1.0 ? "AVERAGE" : "WEAK");
            if (isFinancial) {
                if (roa >= 1.5) ce.getStrengths().add(String.format("Strong ROA %.2f%% (efficient bank — >1.5%% is good)", roa));
                else if (roa < 1.0) ce.getRedFlags().add(String.format("Low ROA %.2f%% (weak asset productivity for a bank)", roa));
            }
        }

        // ROCE = EBIT / capital employed; EBIT = PBT + finance costs, capital employed = equity + debt.
        // Requires PARSED borrowings: NBFCs/financials file debt under non-standard Ind-AS elements so
        // totalBorrowings comes back null — computing ROCE on an equity-only base then massively
        // overstates it (an NBFC reads ~50% when its true ROCE is mid-teens). A genuine zero-debt company
        // (e.g. TCS) parses borrowings as 0, not null, so it still computes. When debt is null we suppress
        // ROCE rather than emit a misleading number — this catches NBFCs even when NSE's industry string
        // is useless ("GENERAL"), without relying on the flaky industry hint.
        boolean leverageKnown = debt != null;
        if (isFinancial || !leverageKnown) {
            ce.setRoceVerdict("NA_FINANCIAL");
        } else if (pbt != null && equity != null && equity > 0) {
            double ebit = pbt + (fin != null ? fin : 0);
            double capitalEmployed = equity + debt;
            if (capitalEmployed > 0) {
                double roce = ebit / capitalEmployed * 100.0;
                ce.setRocePercent(roce);
                ce.setRoceVerdict(roce >= 20 ? "EXCELLENT" : roce >= 15 ? "GOOD" : roce >= 12 ? "AVERAGE" : "WEAK");
                if (roce >= 20) ce.getStrengths().add(String.format("High ROCE %.1f%% (strong return on capital — a compounding engine)", roce));
                else if (roce < 12) ce.getRedFlags().add(String.format("Low ROCE %.1f%% (capital not earning well)", roce));
            }
        }

        // Debt-to-Equity — needs parsed borrowings; null for financials/parse-gaps (suppress, don't guess)
        if (isFinancial || !leverageKnown) {
            ce.setLeverageVerdict("NA_FINANCIAL");
        } else if (equity != null && equity > 0) {
            double de = debt / equity;
            ce.setDebtToEquity(de);
            ce.setLeverageVerdict(de <= 0.3 ? "VERY_LOW" : de <= 1.0 ? "MODERATE" : de <= 2.0 ? "ELEVATED" : "HIGH");
            if (de <= 0.3) ce.getStrengths().add(String.format("Low debt (D/E %.2f) — resilient balance sheet", de));
            else if (de > 2.0) ce.getRedFlags().add(String.format("High leverage (D/E %.2f) — vulnerable to rate/earnings shocks", de));
        }

        // Cash conversion = CFO / net profit (real CFO, not the profit+depreciation proxy)
        if (cfo != null && netProfit != null && netProfit > 0) {
            double cc = cfo / netProfit;
            ce.setCashConversionRatio(cc);
            ce.setCashConversionVerdict(cc >= 0.8 ? "STRONG" : cc >= 0.5 ? "ADEQUATE" : cc >= 0 ? "WEAK" : "NEGATIVE");
            if (cc >= 0.8) ce.getStrengths().add(String.format("Strong cash conversion (CFO is %.0f%% of profit — earnings are real cash)", cc * 100));
            else if (cc < 0.5) ce.getRedFlags().add(String.format("Weak cash conversion (CFO only %.0f%% of profit — accounting-quality watch)", cc * 100));
        }

        // Dividend payout = dividends paid / net profit
        if (bs.getDividendsPaid() != null && netProfit != null && netProfit > 0) {
            ce.setDividendPayoutPercent(bs.getDividendsPaid() / netProfit * 100.0);
        }

        // Overall verdict — count strong vs weak signals (ROCE/ROE/ROA + leverage + cash conversion)
        ce.setApplicable(ce.getRoePercent() != null || ce.getRocePercent() != null || ce.getRoaPercent() != null);
        if (!ce.isApplicable()) {
            ce.setNaReason("Insufficient balance-sheet data in annual filing");
            ce.setOverallVerdict("NA");
        } else {
            int strong = ce.getStrengths().size();
            int weak = ce.getRedFlags().size();
            int net = strong - weak;
            ce.setOverallVerdict(net >= 2 ? "HIGH_QUALITY_COMPOUNDER" : net >= 1 ? "SOLID" : net == 0 ? "AVERAGE" : net >= -1 ? "WEAK" : "POOR");
        }
        return ce;
    }

    /**
     * Capex-cycle signal (SPEC.md §31) — the earliest balance-sheet trace of future growth.
     *
     * <p>Rising <b>capital work-in-progress</b> means money is committed to plants that are
     * not producing revenue yet. Every P&amp;L-driven dimension in this system — earnings
     * growth, margins, relative strength — can only see that expansion 12–24 months later,
     * once the capacity starts selling. This reads it from the balance sheet today.
     *
     * <p>Three measures, all from the annual Ind-AS XBRL already fetched for capital
     * efficiency (no new endpoint, no extra call):
     * <ul>
     *   <li><b>CWIP intensity</b> = CWIP / net block — how big the build is relative to
     *       the plant already running</li>
     *   <li><b>Capex proxy</b> = ΔPPE + ΔCWIP + depreciation — spend inferred from the
     *       balance sheet, since the cash-flow investing line is not reliably tagged</li>
     *   <li><b>Capex/depreciation</b> — above 1 the asset base is growing, well below 1
     *       the company is harvesting rather than building</li>
     * </ul>
     *
     * <p><b>Banks and financials return {@code NA_FINANCIAL} with null figures.</b> A bank's
     * growth comes from its loan book, not from plant; CWIP for a bank is branch fit-outs
     * and says nothing about capacity. Same suppression pattern as ROCE in §12.8.
     *
     * @param industryHint optional industry/sector string for financial-sector detection; may be null
     * @return never null; check {@code applicable} / {@code verdict} before using the numbers
     */
    public CapexCycleData analyzeCapexCycle(String tradingSymbol, String industryHint) {
        CapexCycleData cc = new CapexCycleData();
        cc.setSymbol(tradingSymbol);

        BalanceSheetData bs = fetchAnnualFinancials(tradingSymbol);
        if (bs == null) {
            cc.setApplicable(false);
            cc.setVerdict("NO_DATA");
            cc.setReason("Annual financial statement unavailable from NSE "
                    + "(no filing, very recent IPO, or unsupported format)");
            return cc;
        }
        boolean isFinancial = bs.isBanking() || (industryHint != null && industryHint.toLowerCase()
                .matches(".*(bank|financ|nbfc|insur|capital market|holding).*"));
        return classifyCapexCycle(bs, isFinancial);
    }

    /**
     * Pure classification half of {@link #analyzeCapexCycle} — no network, no cache.
     * Separated so the verdict boundaries are testable without an NSE round-trip
     * (same split as {@code InsiderPulseService.classify}).
     *
     * @param bs          parsed annual balance sheet; must not be null
     * @param isFinancial true for banks/NBFCs/insurers, where CWIP carries no signal
     */
    private static boolean isNullOrZero(Double v) {
        return v == null || v == 0.0;
    }

    public static CapexCycleData classifyCapexCycle(BalanceSheetData bs, boolean isFinancial) {
        return classifyCapexCycle(bs, isFinancial, null, null);
    }

    /**
     * As {@link #classifyCapexCycle(BalanceSheetData, boolean)}, with prior-year figures
     * supplied from outside the filing.
     *
     * <p><b>Why this overload exists (2026-08-26).</b> The plan assumed Ind-AS filings carry
     * the previous year's balance sheet as a comparative column in the same document.
     * Measured against live data, they do not: NSE's integrated filing declares a prior
     * instant context but tags exactly <b>one</b> fact against it — no comparative balance
     * sheet at all. Verified on RELIANCE, TATASTEEL and NTPC. Without a prior year the
     * capex delta is not computable and {@code EXPANSION_UNDERWAY} could never fire, which
     * would leave this feature as a bare CWIP-intensity threshold.
     *
     * <p>The real source of the prior year is the annual-history table (SPEC §32), which
     * records CWIP and net block per financial year from both the XBRL pipeline and the
     * user's import. {@code CapexCycleService} does that composition.
     *
     * @param fallbackPriorCwip prior-year capital work-in-progress (₹ crore), or null
     * @param fallbackPriorPpe  prior-year net block (₹ crore), or null
     */
    public static CapexCycleData classifyCapexCycle(BalanceSheetData bs, boolean isFinancial,
                                                    Double fallbackPriorCwip, Double fallbackPriorPpe) {
        CapexCycleData cc = new CapexCycleData();
        cc.setSymbol(bs.getSymbol());
        cc.setFinancialYear(bs.getFinancialYear());

        if (isFinancial) {
            cc.setApplicable(false);
            cc.setVerdict("NA_FINANCIAL");
            cc.setReason("Banks and financials grow their loan book, not their plant — "
                    + "capital work-in-progress carries no capacity signal for them");
            return cc;
        }

        Double cwip = bs.getCapitalWorkInProgress();
        Double ppe = bs.getPropertyPlantEquipment();
        Double dep = bs.getDepreciation();
        // The filing's own comparative column first (it is authoritative when present),
        // then the annual-history table. In practice the first is always empty today.
        Double priorCwip = bs.getPriorCapitalWorkInProgress() != null
                ? bs.getPriorCapitalWorkInProgress() : fallbackPriorCwip;
        Double priorPpe = bs.getPriorPropertyPlantEquipment() != null
                ? bs.getPriorPropertyPlantEquipment() : fallbackPriorPpe;

        cc.setCwipCurrent(cwip);
        cc.setCwipPrior(priorCwip);
        cc.setNetBlock(ppe);
        cc.setDepreciation(dep);
        // "Prior year available" must mean the figures are actually usable, not merely that
        // a context id was resolved. Reporting true when the comparative is empty is what
        // made a missing measurement look like a measured one.
        cc.setPriorYearAvailable(priorCwip != null || priorPpe != null);
        cc.setPriorYearFactCount(bs.getPriorYearFactCount());

        // CWIP intensity — measurable from the current column alone.
        if (cwip != null && ppe != null && ppe > 0) {
            cc.setCwipIntensityPercent(cwip / ppe * 100.0);
        }
        // CWIP direction needs the comparative column; null when the filing has none.
        if (cwip != null && priorCwip != null) {
            cc.setCwipChange(cwip - priorCwip);
            if (priorCwip > 0) cc.setCwipGrowthPercent((cwip - priorCwip) / priorCwip * 100.0);
        }
        // Capex proxy = growth in gross productive assets. Depreciation is added back
        // because net block is reported after it: without that, a company spending exactly
        // its depreciation would appear to have spent nothing.
        // The CWIP term must be measured or provably irrelevant — never defaulted to zero
        // (B-048). Substituting 0 for an unknown change understates capex by exactly the
        // amount under construction, which is the spending this whole analysis is about,
        // and does so silently: the ratio still comes out looking computed.
        Double deltaCwip = null;
        if (cwip != null && priorCwip != null) {
            deltaCwip = cwip - priorCwip;
        } else if (isNullOrZero(cwip) && isNullOrZero(priorCwip)) {
            deltaCwip = 0.0;    // nothing under construction in either year
        }

        if (ppe != null && priorPpe != null && dep != null && deltaCwip != null) {
            double capex = (ppe - priorPpe) + deltaCwip + dep;
            cc.setCapexProxy(capex);
            if (dep > 0) cc.setCapexToDepreciation(capex / dep);
        }

        Double intensity = cc.getCwipIntensityPercent();
        Double capexToDep = cc.getCapexToDepreciation();
        Double change = cc.getCwipChange();

        if (intensity == null && capexToDep == null) {
            cc.setApplicable(false);
            cc.setVerdict("NO_DATA");
            cc.setReason(bs.isPriorYearAvailable()
                    ? "Filing does not tag capital work-in-progress or net block"
                    : "Filing carries no prior-year comparative balance sheet, so capex change is not measurable");
            return cc;
        }

        cc.setApplicable(true);

        // EXPANSION_UNDERWAY is the signal this feature exists for: a large build that is
        // still growing. It requires the CWIP direction, so it can only fire when the
        // comparative column is present.
        boolean bigBuild = intensity != null && intensity > 15.0;
        boolean rising = change != null && change > 0;
        if (bigBuild && rising) {
            cc.setVerdict("EXPANSION_UNDERWAY");
            cc.setReason(String.format(
                    "Capital work-in-progress is %.0f%% of the existing plant and still growing — "
                            + "capacity being built now that should start earning in 1-2 years",
                    intensity));
        } else if (capexToDep != null && capexToDep > 2.0) {
            cc.setVerdict("INVESTING");
            cc.setReason(String.format(
                    "Spending %.1fx its depreciation on new assets — the asset base is growing, not just being maintained",
                    capexToDep));
        } else if (capexToDep != null && capexToDep < 0.8) {
            cc.setVerdict("HARVESTING");
            cc.setReason(String.format(
                    "Spending only %.1fx depreciation — taking cash out of the existing plant rather than building",
                    capexToDep));
        } else if (bigBuild) {
            // Big build, direction unknown (no prior year yet). Honest middle verdict.
            cc.setVerdict("INVESTING");
            cc.setReason(String.format(
                    "Capital work-in-progress is %.0f%% of the existing plant, though last year's figure "
                            + "is not available yet so we cannot tell whether the build is starting or finishing",
                    intensity));
        } else {
            cc.setVerdict("STEADY");
            // Only claim what was actually measured. With no prior year the capex ratio is
            // null, and the old wording here asserted "spending is in line with depreciation"
            // from a number that had never been computed.
            cc.setReason(capexToDep != null
                    ? String.format("Capital spending is %.1fx depreciation — maintaining capacity rather than expanding it",
                            capexToDep)
                    : String.format("Capital work-in-progress is only %.0f%% of the existing plant, so nothing "
                            + "large is being built. How much is being spent cannot be measured until a second "
                            + "year of accounts is on file", intensity));
        }
        return cc;
    }

    /**
     * Fetch shareholding pattern for a stock.
     * Returns promoter, FII, DII, and public holding percentages.
     */
    @SuppressWarnings("unchecked")
    public ShareholdingData fetchShareholding(String tradingSymbol) {
        String cacheKey = "shareholding:" + tradingSymbol;
        ShareholdingData cached = getCached(cacheKey);
        if (cached != null) {
            log.debug("Returning cached shareholding for {}", tradingSymbol);
            return cached;
        }

        // /api/quote-equity is bot-walled and 403s for every symbol (B-018). Without this
        // guard the screener burns two doomed HTTP calls per stock — 722 per full run.
        // The delivery-% wealth signal (SPEC §12.7) is unavailable until NSE reopens the
        // endpoint or a replacement source is found; it degrades to null, scored neutral.
        if (!isQuoteEquityCircuitClosed()) {
            return null;
        }

        try {
            refreshCookies();

            // Try the quote endpoint which has basic shareholding info
            Map<String, Object> response = getWebClient().get()
                    .uri("/api/quote-equity?symbol=" + tradingSymbol + "&section=trade_info")
                    .header("Cookie", cachedCookies != null ? cachedCookies : "")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(TIMEOUT);

            if (response != null) {
                ShareholdingData data = new ShareholdingData();
                data.setSymbol(tradingSymbol);

                // Try to extract shareholding from securityWiseDP or marketDeptOrderBook
                if (response.containsKey("securityWiseDP")) {
                    Map<String, Object> swdp = (Map<String, Object>) response.get("securityWiseDP");
                    data.setDeliveryPercent(parseDoubleSafe(swdp.get("deliveryToTradedQuantity")));
                }

                // Also try the corporate governance endpoint
                try {
                    Map<String, Object> shResponse = getWebClient().get()
                            .uri("/api/quote-equity?symbol=" + tradingSymbol)
                            .header("Cookie", cachedCookies != null ? cachedCookies : "")
                            .retrieve()
                            .bodyToMono(Map.class)
                            .block(TIMEOUT);

                    if (shResponse != null) {
                        if (shResponse.containsKey("securityInfo")) {
                            Map<String, Object> secInfo = (Map<String, Object>) shResponse.get("securityInfo");
                            data.setFaceValue(parseDoubleSafe(secInfo.get("faceValue")));
                            data.setIssuedSize(parseDoubleSafe(secInfo.get("issuedSize")));
                        }
                        if (shResponse.containsKey("priceInfo")) {
                            Map<String, Object> priceInfo = (Map<String, Object>) shResponse.get("priceInfo");
                            data.setWeekHigh52(parseDoubleSafe(priceInfo.get("weekHighLow")));
                            data.setUpperBand(parseDoubleSafe(priceInfo.get("upperCP")));
                            data.setLowerBand(parseDoubleSafe(priceInfo.get("lowerCP")));
                        }
                        // Industry info
                        if (shResponse.containsKey("industryInfo")) {
                            data.setIndustry(getString(shResponse, "industryInfo", null));
                        }
                    }
                } catch (Exception ignored) {}

                // Fetch shareholding pattern (NSE schema 2026-04: corporate-share-holdings-master
                // replaced the now-404 corporate-shareholding endpoint).
                try {
                    List<Map<String, Object>> shpResponse = getWebClient().get()
                            .uri("/api/corporate-share-holdings-master?index=equities&symbol=" + tradingSymbol)
                            .header("Cookie", cachedCookies != null ? cachedCookies : "")
                            .retrieve()
                            .bodyToMono(List.class)
                            .block(TIMEOUT);

                    if (shpResponse != null && !shpResponse.isEmpty()) {
                        // Sort to find most recent
                        shpResponse.sort((a, b) -> {
                            java.time.LocalDate la = parseNseDate(getString(a, "date", null));
                            java.time.LocalDate lb = parseNseDate(getString(b, "date", null));
                            if (la == null && lb == null) return 0;
                            if (la == null) return 1;
                            if (lb == null) return -1;
                            return lb.compareTo(la);
                        });
                        Map<String, Object> latest = shpResponse.get(0);
                        data.setPromoterHolding(parseDoubleSafe(latest.get("pr_and_prgrp")));
                        data.setPublicHolding(parseDoubleSafe(latest.get("public_val")));
                        // institutional holding can't be split from public without XBRL parse
                        data.setShareholdingDate(getString(latest, "date", null));
                        log.debug("Shareholding for {}: Promoter={}%, Public={}%, Date={}", tradingSymbol,
                                data.getPromoterHolding(), data.getPublicHolding(), data.getShareholdingDate());
                    }
                } catch (Exception e) {
                    log.debug("Shareholding pattern fetch failed for {}: {}", tradingSymbol, e.getMessage());
                }

                putCache(cacheKey, data);
                return data;
            }
        } catch (Exception e) {
            log.debug("NSE data fetch failed for {}: {}", tradingSymbol, e.getMessage());
            recordQuoteEquityFailure();
        }
        return null;
    }

    // ==================== quote-equity circuit breaker (B-018) ====================

    private static final int QE_FAILURE_THRESHOLD = 10;
    private static final long QE_RETRY_INTERVAL_MS = 6 * 60 * 60 * 1000L; // 6 hours
    private final java.util.concurrent.atomic.AtomicInteger qeConsecutiveFailures =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile long qeCircuitOpenedAt = 0;

    private boolean isQuoteEquityCircuitClosed() {
        if (qeConsecutiveFailures.get() < QE_FAILURE_THRESHOLD) return true;
        if (System.currentTimeMillis() - qeCircuitOpenedAt > QE_RETRY_INTERVAL_MS) {
            log.info("NSE quote-equity circuit breaker: re-arming after cool-down");
            qeConsecutiveFailures.set(0);
            return true;
        }
        return false;
    }

    private void recordQuoteEquityFailure() {
        if (qeConsecutiveFailures.incrementAndGet() == QE_FAILURE_THRESHOLD) {
            qeCircuitOpenedAt = System.currentTimeMillis();
            log.warn("NSE quote-equity circuit breaker OPEN after {} consecutive failures (B-018). "
                    + "Delivery-% wealth signal unavailable; will retry in 6h.", QE_FAILURE_THRESHOLD);
        }
    }

    /**
     * Fetch recent corporate announcements for a stock.
     */
    // ============================================================
    // SEBI PIT / SAST / bulk-deal disclosures (SPEC §28, F1)
    // ============================================================

    /** PIT rows change at most daily — a 30-minute TTL would re-fetch pointlessly. */
    private static final long DISCLOSURE_CACHE_TTL_MS = 6L * 60 * 60 * 1000L; // 6 hours

    /**
     * Fetch SEBI PIT Regulation 7(2) insider-trading disclosures for one symbol.
     *
     * <p><b>Per-symbol only.</b> The all-market date-ranged form
     * ({@code /api/corporates-pit?index=equities&from_date=..&to_date=..}) returns HTTP 200
     * with {@code {"acqNameList":[],"data":[]}} — a <i>silent empty</i>, not an error. Probed
     * 2026-08-25 across several date windows; only the {@code &symbol=} form returns rows.
     * Do not "optimise" this into one all-market call: it will appear to work and quietly
     * report that no insider anywhere is buying anything (Gotcha 15 failure shape).
     *
     * <p>Row keys of interest: {@code acqName}, {@code personCategory}, {@code acqMode}
     * ("Market Purchase" / "Market Sale" / "Off Market" / "Gift" / "Pledge Creation" /
     * "Revokation of Pledge" / "Others"), {@code tdpTransactionType} (Buy/Sell),
     * {@code secAcq} (quantity), {@code secVal} (value), {@code date}, {@code acqfromDt}.
     *
     * @return raw rows, newest first; empty list on any failure (never null)
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchPitDisclosures(String tradingSymbol) {
        String cacheKey = "pit_" + tradingSymbol;
        List<Map<String, Object>> cached = getCached(cacheKey);
        if (cached != null) return cached;

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            refreshCookies();
            Map<String, Object> response = getWebClient().get()
                    .uri("/api/corporates-pit?index=equities&symbol=" + tradingSymbol)
                    .header("Cookie", cachedCookies != null ? cachedCookies : "")
                    .header(HttpHeaders.REFERER, NSE_BASE_URL + "/companies-listing/corporate-filings-insider-trading")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(TIMEOUT);

            if (response != null && response.get("data") instanceof List<?> data) {
                for (Object o : data) {
                    if (o instanceof Map<?, ?> m) rows.add((Map<String, Object>) m);
                }
            }
            log.debug("PIT disclosures for {}: {} rows", tradingSymbol, rows.size());
        } catch (Exception e) {
            log.warn("PIT disclosure fetch failed for {}: {}", tradingSymbol, e.getMessage());
        }
        putCache(cacheKey, rows, DISCLOSURE_CACHE_TTL_MS);
        return rows;
    }

    // ------------------------------------------------------------------
    // PIT V2.0 - the current insider feed (B-089, SPEC 28)
    // ------------------------------------------------------------------

    /** The filing index is the freshness source for the whole signal, so it is cached briefly. */
    private static final long PIT_INDEX_CACHE_TTL_MS = 60L * 60 * 1000L; // 1 hour

    /**
     * Fetch the all-market index of SEBI PIT Regulation 7(2) / 7(3) filings.
     *
     * <p><b>This replaces {@code corporates-pit} as the live feed (B-089).</b> That endpoint
     * stopped receiving filings on <b>2026-05-01</b>: NSE circular NSE/CML/2026/11 (04 May 2026)
     * moved PIT Reg 7(2) and 7(3) onto the single filing system through API-based integration
     * between exchanges, effective <b>05 May 2026</b>, and filings have been published as XBRL
     * under {@code corporates-pit-gg} ever since - the instance documents are stamped
     * {@code PIT V2.0 (30-04-2026)}. The old endpoint still serves its pre-May <i>archive</i>
     * correctly, so what died is the feed and not the history: Gotcha 72's rule, second
     * instance. The two windows abut exactly - the archive ends 2026-05-01, this begins
     * 2026-05-03 - so the combined record has no gap.
     *
     * <p><b>This form IS all-market</b>, unlike its predecessor. Gotcha 31 records that
     * {@code corporates-pit?index=equities} without a symbol returns a silent empty and that
     * only the per-symbol form works. That remains true of the <i>old</i> endpoint and is
     * false of this one: measured 2026-09-08, {@code corporates-pit-gg?index=equities}
     * returned 2,381 filings across 495 symbols in a single call. One call now covers the
     * whole universe, which is what allowed the per-symbol budget and its rotation to be
     * deleted (B-090).
     *
     * <p>Each row is a filing <i>index</i> entry, not a trade - the trades live in the XBRL at
     * {@code xmlFileName}, read by {@link #fetchPitFilingDisclosures(String)}. Keys of
     * interest: {@code appId} (unique per filing, and the ingestion key), {@code symbol},
     * {@code broadcastDateTime}, {@code regulation}, {@code typeOfSubmission}
     * (Original / Revision), {@code xmlFileName}.
     *
     * <p>A failure is <b>not</b> cached. Caching an exception as "no filings" is what makes a
     * manual retry silently useless within the TTL (B-042, same endpoint family).
     *
     * @return filing index rows, newest first; empty list on any failure (never null)
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchPitFilingIndex() {
        String cacheKey = "pit_index_gg";
        List<Map<String, Object>> cached = getCached(cacheKey);
        if (cached != null) return cached;

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            refreshCookies();
            Map<String, Object> response = getWebClient().get()
                    .uri("/api/corporates-pit-gg?index=equities")
                    .header("Cookie", cachedCookies != null ? cachedCookies : "")
                    .header(HttpHeaders.REFERER, NSE_BASE_URL + "/companies-listing/corporate-filings-insider-trading")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(TIMEOUT);

            if (response != null && response.get("data") instanceof List<?> data) {
                for (Object o : data) {
                    if (o instanceof Map<?, ?> m) rows.add((Map<String, Object>) m);
                }
            }
            log.info("PIT filing index: {} filings", rows.size());
        } catch (Exception e) {
            // WARN and say what the emptiness will be mistaken for (B-054's rule): an empty
            // index reads downstream as "no insider anywhere filed anything", which is exactly
            // the false all-clear this feed exists to avoid.
            log.warn("PIT filing index fetch failed ({}) - insider capture will read as "
                    + "'no filings on the market' this run; not cached, so a retry is live.",
                    e.getMessage());
            return rows;   // deliberately NOT cached
        }
        putCache(cacheKey, rows, PIT_INDEX_CACHE_TTL_MS);
        return rows;
    }

    /**
     * Read one PIT filing's XBRL and return one map per disclosed transaction.
     *
     * <p>The instance is flat: every fact carries a {@code contextRef}, the filing-level facts
     * sit in a header context ({@code MainI}) and each transaction in its own
     * ({@code Disclosure1}, {@code Disclosure2}, ...). Rather than hard-coding those ids -
     * they are a convention, not a schema guarantee - a context is treated as a transaction
     * when it carries any fact only a transaction has, and every other context is merged into
     * a header applied to all of them. Parsing is namespace-unaware and matches on local
     * names, the same approach the pledge and financial-results parsers use.
     *
     * <p>Fact names of interest: {@code CategoryOfPerson}, {@code NameOfThePerson},
     * {@code ModeOfAcquisitionOrDisposal}, {@code SecuritiesAcquiredOrDisposedTransactionType},
     * {@code SecuritiesAcquiredOrDisposedNumberOfSecurity},
     * {@code SecuritiesAcquiredOrDisposedValueOfSecurity} (rupees, and actually populated -
     * the old JSON feed frequently omitted it),
     * {@code DateOfAllotmentAdviceOrAcquisitionOfSharesOrSaleOfSharesSpecifyFromDate},
     * {@code DateOfFiling}, {@code Symbol}.
     *
     * @return one map per transaction, header facts merged in; empty on any failure
     */
    public List<Map<String, String>> fetchPitFilingDisclosures(String xmlUrl) {
        List<Map<String, String>> out = new ArrayList<>();
        if (xmlUrl == null || xmlUrl.isBlank()) return out;
        try {
            String xml = WebClient.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                    .build().get()
                    .uri(xmlUrl)
                    .header(HttpHeaders.USER_AGENT,
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0")
                    .header(HttpHeaders.REFERER, NSE_BASE_URL + "/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);
            if (xml == null || xml.isEmpty()) return out;

            javax.xml.parsers.DocumentBuilderFactory dbf =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document doc = dbf.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));

            Map<String, Map<String, String>> byContext = new LinkedHashMap<>();
            org.w3c.dom.NodeList all = doc.getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                org.w3c.dom.Element el = (org.w3c.dom.Element) all.item(i);
                String ctx = el.getAttribute("contextRef");
                if (ctx == null || ctx.isBlank()) continue;
                String tag = el.getTagName();
                String local = tag.contains(":") ? tag.substring(tag.indexOf(':') + 1) : tag;
                String value = el.getTextContent();
                if (value == null || value.isBlank()) continue;
                byContext.computeIfAbsent(ctx, k -> new LinkedHashMap<>()).put(local, value.trim());
            }

            Map<String, String> header = new LinkedHashMap<>();
            List<Map<String, String>> transactions = new ArrayList<>();
            for (Map<String, String> facts : byContext.values()) {
                if (isPitTransactionContext(facts)) {
                    transactions.add(facts);
                } else {
                    header.putAll(facts);
                }
            }
            for (Map<String, String> t : transactions) {
                Map<String, String> merged = new LinkedHashMap<>(header);
                merged.putAll(t);   // a transaction fact always wins over the filing header
                out.add(merged);
            }
        } catch (Exception e) {
            log.warn("PIT filing XBRL parse failed for {} ({}) - this filing's trades will be "
                    + "absent from the pulse, which reads as 'this insider did not trade'.",
                    xmlUrl, e.getMessage());
        }
        return out;
    }

    /** A context describes a transaction when it carries any fact only a transaction has. */
    private static boolean isPitTransactionContext(Map<String, String> facts) {
        return facts.containsKey("SecuritiesAcquiredOrDisposedTransactionType")
                || facts.containsKey("ModeOfAcquisitionOrDisposal")
                || facts.containsKey("CategoryOfPerson");
    }

    /**
     * Fetch today's bulk deals from the NSE archives CSV (all-market, one call for the whole
     * universe). The archives host has never been bot-walled, unlike www.nseindia.com/api
     * (B-018), so this is the reliable half of the insider feed.
     *
     * <p>Columns: Date, Symbol, Security Name, Client Name, Buy/Sell, Quantity Traded,
     * Trade Price / Wght. Avg. Price, Remarks. A no-activity day returns a header plus a
     * literal {@code NO RECORDS} line, which parses to an empty list.
     *
     * @param block true for block deals ({@code block.csv}), false for bulk ({@code bulk.csv})
     */
    public List<Map<String, String>> fetchDealsCsv(boolean block) {
        String file = block ? "block.csv" : "bulk.csv";
        String cacheKey = "deals_" + file;
        List<Map<String, String>> cached = getCached(cacheKey);
        if (cached != null) return cached;

        List<Map<String, String>> rows = new ArrayList<>();
        try {
            String csv = WebClient.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                    .build().get()
                    .uri("https://nsearchives.nseindia.com/content/equities/" + file)
                    .header(HttpHeaders.USER_AGENT,
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header(HttpHeaders.REFERER, NSE_BASE_URL + "/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(TIMEOUT);

            rows = parseSimpleCsv(csv);
            log.debug("{}: {} rows", file, rows.size());
        } catch (Exception e) {
            log.warn("Deals CSV fetch failed for {}: {}", file, e.getMessage());
        }
        putCache(cacheKey, rows, DISCLOSURE_CACHE_TTL_MS);
        return rows;
    }

    /** The mainboard equity list changes only on listings/delistings — cache for a day. */
    private static final long EQUITY_LIST_CACHE_TTL_MS = 24L * 60 * 60 * 1000L;

    /**
     * The full NSE mainboard equity list from the archives (SPEC §30).
     *
     * <p>This is the universe source for expansion. Two things make it the right one:
     * it carries the <b>trading series</b> (so BE/BZ trade-to-trade names can be excluded —
     * Gotcha 14) and the <b>date of listing</b> (which drives the IPO tracker). The Kite
     * instruments dump has neither, and {@code KiteInstrumentsService} only retains NFO
     * options anyway.
     *
     * <p>SME (NSE EMERGE) listings are <b>not</b> in this file, so they are excluded
     * structurally rather than by a filter that could be removed by accident — which is the
     * intended behaviour (SPEC §30 non-goal: SME liquidity and disclosure quality are below
     * what this system's guardrails assume).
     *
     * @return every listed row, including non-EQ series; callers filter. Empty on failure.
     */
    public List<EquityListing> fetchEquityList() {
        List<EquityListing> cached = getCached("equity_list");
        if (cached != null) return cached;

        List<EquityListing> out = new ArrayList<>();
        try {
            String csv = WebClient.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                    .build().get()
                    .uri("https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv")
                    .header(HttpHeaders.USER_AGENT,
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header(HttpHeaders.REFERER, NSE_BASE_URL + "/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            for (Map<String, String> row : parseSimpleCsv(csv)) {
                String symbol = row.get("SYMBOL");
                if (symbol == null || symbol.isBlank()) continue;
                out.add(new EquityListing(
                        symbol.trim(),
                        row.getOrDefault("NAME OF COMPANY", "").trim(),
                        row.getOrDefault("SERIES", "").trim(),
                        parseListingDate(row.get("DATE OF LISTING")),
                        row.getOrDefault("ISIN NUMBER", "").trim()));
            }
            log.info("NSE equity list: {} listed securities ({} mainboard EQ)",
                    out.size(), out.stream().filter(EquityListing::isMainboardEquity).count());
        } catch (Exception e) {
            log.warn("Equity list fetch failed: {}", e.getMessage());
        }
        if (!out.isEmpty()) putCache("equity_list", out, EQUITY_LIST_CACHE_TTL_MS);
        return out;
    }

    private static java.time.LocalDate parseListingDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(s.trim(), new java.time.format.DateTimeFormatterBuilder()
                    .parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH));
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    // IPO feeds (SPEC §45)
    // ============================================================

    /** IPO feeds change intra-day while an issue is open; keep the cache short. */
    private static final long IPO_FEED_CACHE_TTL_MS = 10L * 60 * 1000L;

    /**
     * Every mainboard issue NSE currently lists as forthcoming or open
     * ({@code /api/all-upcoming-issues?category=ipo}). Raw rows: {@code symbol, companyName,
     * issueStartDate, issueEndDate, issuePrice} ("Rs.139 to Rs.146"), {@code issueSize} (shares),
     * {@code series}, {@code status} (Active / Forthcoming). Parsing lives in
     * {@code universe.ipo.IpoFeedParser} so it can be pinned by tests without a network.
     *
     * <p>Empty on failure, logged at WARN with the cause: an empty pipeline is indistinguishable
     * from "no IPOs this week", which is a real and common state, so the log line is the only
     * thing that separates the two (B-054's rule).
     */
    public List<Map<String, Object>> fetchIpoUpcomingIssues() {
        return fetchIpoList("/api/all-upcoming-issues?category=ipo", "ipo:upcoming");
    }

    /**
     * Issues open for bidding right now, with the running total subscription
     * ({@code /api/ipo-current-issue}). Same row shape as the upcoming feed plus
     * {@code noOfTime} (total times subscribed so far).
     */
    public List<Map<String, Object>> fetchIpoCurrentIssues() {
        return fetchIpoList("/api/ipo-current-issue", "ipo:current");
    }

    /**
     * Every past public issue NSE remembers ({@code /api/public-past-issues}) — ~1,400 rows back
     * to 2012 across mainboard, SME and debt. Rows carry {@code securityType} (EQ / SME / ...),
     * {@code issuePrice} (final, may be {@code "-"} or padded with spaces), {@code priceRange},
     * {@code ipoStartDate}, {@code ipoEndDate} and {@code listingDate} ({@code "-"} until listed).
     * Callers filter to EQ and to the window they care about.
     */
    public List<Map<String, Object>> fetchIpoPastIssues() {
        return fetchIpoList("/api/public-past-issues", "ipo:past");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchIpoList(String path, String cacheKey) {
        List<Map<String, Object>> cached = getCached(cacheKey);
        if (cached != null) return cached;
        try {
            refreshCookies();
            List<Map<String, Object>> response = getWebClient().get()
                    .uri(path)
                    .header("Cookie", cachedCookies != null ? cachedCookies : "")
                    .retrieve()
                    .bodyToMono(List.class)
                    .block(TIMEOUT);
            List<Map<String, Object>> out = response == null ? List.of() : response;
            if (!out.isEmpty()) putCache(cacheKey, out, IPO_FEED_CACHE_TTL_MS);
            return out;
        } catch (Exception e) {
            log.warn("NSE IPO feed {} FAILED — downstream will read this as 'no issues', which is "
                    + "not the same thing. Cause: {}", path, e.toString());
            return List.of();
        }
    }

    /**
     * One issue's detail page ({@code /api/ipo-detail?symbol=X&series=EQ}): {@code issueInfo}
     * (title/value rows — issue size text, face value, bid lot, lead managers, RHP link),
     * {@code bidDetails} (category-wise shares offered / bid / times), demand graphs. Works for
     * issues that closed months ago as well as open ones, which is what makes the final
     * subscription figures recoverable after the fact.
     *
     * <p>Not cached across runs on purpose: the subscription block moves hourly while an issue is
     * open. Returns an empty map on failure, logged at WARN.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchIpoDetail(String tradingSymbol) {
        try {
            refreshCookies();
            Map<String, Object> response = getWebClient().get()
                    .uri("/api/ipo-detail?symbol=" + tradingSymbol + "&series=EQ")
                    .header("Cookie", cachedCookies != null ? cachedCookies : "")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(TIMEOUT);
            return response == null ? Map.of() : response;
        } catch (Exception e) {
            log.warn("NSE IPO detail FAILED for {} — its structure and subscription will read as "
                    + "'not measured'. Cause: {}", tradingSymbol, e.toString());
            return Map.of();
        }
    }

    /** One row of NSE's mainboard equity list. */
    public record EquityListing(String symbol, String companyName, String series,
                                java.time.LocalDate listingDate, String isin) {
        /**
         * True only for the plain {@code EQ} series. BE/BZ are trade-to-trade or
         * surveillance-restricted: 100% delivery, no intraday netting, frequently illiquid.
         * Kite tradingsymbols also never carry the suffix (B-013).
         */
        public boolean isMainboardEquity() {
            return "EQ".equalsIgnoreCase(series);
        }

        public String qualifiedSymbol() {
            return "NSE:" + symbol;
        }
    }

    /**
     * Parse a small, well-formed NSE archive CSV into header-keyed rows. Handles quoted
     * fields containing commas (client names routinely do) and skips NSE's "NO RECORDS"
     * sentinel line.
     */
    public static List<Map<String, String>> parseSimpleCsv(String csv) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (csv == null || csv.isBlank()) return rows;
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) return rows;

        List<String> headers = splitCsvLine(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.startsWith("NO RECORDS")) continue;
            List<String> vals = splitCsvLine(line);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < headers.size() && c < vals.size(); c++) {
                row.put(headers.get(c).trim(), vals.get(c).trim());
            }
            if (!row.isEmpty()) rows.add(row);
        }
        return rows;
    }

    private static List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    @SuppressWarnings("unchecked")
    /**
     * One corporate announcement with its attachment link (SPEC.md §34).
     *
     * <p>{@link #fetchCorporateAnnouncements} flattens announcements to display strings,
     * which loses the attachment URL — and the attachment is the whole point for the
     * concall feature, since the transcript itself is the PDF hanging off the filing.
     */
    public record AnnouncementRecord(String date, String subject, String attachmentUrl) {

        private static final java.util.regex.Pattern TRANSCRIPT = java.util.regex.Pattern.compile(
                "(transcript|earnings call|conference call|analyst call|investor call)",
                java.util.regex.Pattern.CASE_INSENSITIVE);

        /**
         * Whether this filing looks like an earnings-call transcript with a readable PDF.
         *
         * <p>Deliberately requires a {@code .pdf} attachment: "intimation of conference
         * call" notices announce a call that has not happened yet and carry no transcript,
         * and treating one as a transcript would feed an empty document to the model.
         */
        public boolean isTranscript() {
            if (attachmentUrl == null || !attachmentUrl.toLowerCase().endsWith(".pdf")) return false;
            if (subject == null) return false;
            String s = subject.toLowerCase();
            // "Intimation of" / "Notice of" precede a call; they do not report one.
            if (s.contains("intimation") || s.contains("notice of") || s.contains("prior intimation")) return false;
            return TRANSCRIPT.matcher(subject).find();
        }
    }

    /**
     * Fetch corporate announcements with their attachment URLs, newest first.
     *
     * @param limit maximum records to return
     */
    @SuppressWarnings("unchecked")
    public List<AnnouncementRecord> fetchAnnouncementRecords(String tradingSymbol, int limit) {
        String cacheKey = "announcerec:" + tradingSymbol;
        List<AnnouncementRecord> cached = getCached(cacheKey);
        if (cached != null) return cached;

        List<AnnouncementRecord> out = new ArrayList<>();
        try {
            refreshCookies();
            List<Map<String, Object>> response = getWebClient().get()
                    .uri("/api/corporate-announcements?index=equities&symbol=" + tradingSymbol)
                    .header("Cookie", cachedCookies != null ? cachedCookies : "")
                    .retrieve()
                    .bodyToMono(List.class)
                    .block(TIMEOUT);

            if (response != null) {
                for (Map<String, Object> item : response) {
                    if (out.size() >= limit) break;
                    String subject = getString(item, "subject", getString(item, "desc", ""));
                    String date = getString(item, "an_dt", "");
                    String url = getString(item, "attchmntFile", null);
                    if (!subject.isEmpty()) out.add(new AnnouncementRecord(date, subject, url));
                }
            }
            putCache(cacheKey, out, CACHE_TTL_MS);
        } catch (Exception e) {
            // WARN, not DEBUG: an empty announcement list is indistinguishable from a
            // company that filed nothing, and that ambiguity hid B-054 for the entire
            // life of this feed. Name the cause so the next failure is diagnosable.
            log.warn("Announcement records fetch FAILED for {} — downstream will see this as "
                    + "'no announcements', which is not the same thing. Cause: {}",
                    tradingSymbol, e.toString());
        }
        return out;
    }

    /**
     * Download a PDF attachment from NSE's archive host and return its raw bytes.
     *
     * <p>Uses the archives host, which has never been bot-walled (unlike
     * {@code www.nseindia.com/api}, B-018). Returns null on any failure — a transcript
     * that cannot be read is an absence, and every caller must treat it as one.
     */
    public byte[] fetchAttachmentBytes(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            return org.springframework.web.reactive.function.client.WebClient.builder()
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024))
                    .build()
                    .get()
                    .uri(java.net.URI.create(url))
                    .header(HttpHeaders.USER_AGENT,
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0")
                    .header(HttpHeaders.REFERER, NSE_BASE_URL + "/")
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(java.time.Duration.ofSeconds(45));
        } catch (Exception e) {
            log.debug("Attachment download failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    public List<String> fetchCorporateAnnouncements(String tradingSymbol) {
        List<String> announcements = new ArrayList<>();
        try {
            refreshCookies();

            List<Map<String, Object>> response = getWebClient().get()
                    .uri("/api/corporate-announcements?index=equities&symbol=" + tradingSymbol)
                    .header("Cookie", cachedCookies != null ? cachedCookies : "")
                    .retrieve()
                    .bodyToMono(List.class)
                    .block(TIMEOUT);

            if (response != null) {
                for (Map<String, Object> item : response) {
                    if (announcements.size() >= 5) break;
                    String subject = getString(item, "subject", getString(item, "desc", ""));
                    String date = getString(item, "an_dt", "");
                    if (!subject.isEmpty()) {
                        announcements.add("[" + date + "] " + subject);
                    }
                }
                log.debug("Found {} announcements for {}", announcements.size(), tradingSymbol);
            }
        } catch (Exception e) {
            log.debug("Announcements fetch failed for {}: {}", tradingSymbol, e.getMessage());
        }
        return announcements;
    }

    /**
     * Analyze earnings growth trends from quarterly results.
     * Calculates QoQ/YoY growth, CAGR, margin trends, and acceleration.
     */
    public EarningsGrowthData analyzeEarningsGrowth(String tradingSymbol) {
        EarningsGrowthData growth = new EarningsGrowthData();
        growth.setSymbol(tradingSymbol);

        List<QuarterlyResult> quarters = fetchQuarterlyResults(tradingSymbol);
        growth.setQuarters(quarters);

        if (quarters.isEmpty()) {
            growth.setGrowthVerdict("INSUFFICIENT_DATA");
            return growth;
        }

        // QoQ growth (latest vs previous quarter)
        if (quarters.size() >= 2) {
            QuarterlyResult latest = quarters.get(0);
            QuarterlyResult prev = quarters.get(1);

            growth.setQoqRevenueGrowth(calcGrowth(latest.getRevenue(), prev.getRevenue()));
            growth.setQoqProfitGrowth(calcGrowth(latest.getProfit(), prev.getProfit()));
        }

        // YoY growth (latest vs same quarter last year, i.e., index 0 vs index 4)
        if (quarters.size() >= 5) {
            QuarterlyResult latest = quarters.get(0);
            QuarterlyResult yoyQuarter = quarters.get(4);

            growth.setYoyRevenueGrowth(calcGrowth(latest.getRevenue(), yoyQuarter.getRevenue()));
            growth.setYoyProfitGrowth(calcGrowth(latest.getProfit(), yoyQuarter.getProfit()));
            growth.setYoyEpsGrowth(calcGrowth(latest.getEps(), yoyQuarter.getEps()));
        }

        // Revenue and Profit CAGR (if 8 quarters available = ~2 years)
        if (quarters.size() >= 8) {
            growth.setRevenueCAGR(calcCAGR(quarters.get(0).getRevenue(), quarters.get(7).getRevenue(), 2.0));
            growth.setProfitCAGR(calcCAGR(quarters.get(0).getProfit(), quarters.get(7).getProfit(), 2.0));
        }

        // Latest margins
        QuarterlyResult latest = quarters.get(0);
        growth.setLatestOperatingMargin(latest.getOperatingMargin());
        growth.setLatestNetMargin(latest.getNetMargin());

        // Margin trend (change in net margin over last 4 quarters)
        if (quarters.size() >= 4) {
            Double latestMargin = quarters.get(0).getNetMargin();
            Double oldestMargin = quarters.get(3).getNetMargin();
            if (latestMargin != null && oldestMargin != null) {
                growth.setMarginTrend(latestMargin - oldestMargin);
            }
        }

        // Earnings acceleration: compare current YoY growth with previous YoY growth
        if (quarters.size() >= 6) {
            Double currentYoy = calcGrowth(quarters.get(0).getProfit(), quarters.size() >= 5 ? quarters.get(4).getProfit() : null);
            Double prevYoy = calcGrowth(quarters.get(1).getProfit(), quarters.size() >= 6 ? quarters.get(5).getProfit() : null);
            growth.setEarningsAccelerating(currentYoy != null && prevYoy != null && currentYoy > prevYoy);
        }

        // Consecutive QoQ revenue growth quarters
        int consecutive = 0;
        for (int i = 0; i < quarters.size() - 1; i++) {
            Double current = quarters.get(i).getRevenue();
            Double previous = quarters.get(i + 1).getRevenue();
            if (current != null && previous != null && current > previous) {
                consecutive++;
            } else {
                break;
            }
        }
        growth.setConsecutiveGrowthQuarters(consecutive);

        // Growth verdict
        growth.setGrowthVerdict(determineGrowthVerdict(growth));

        log.debug("Earnings growth analysis for {}: verdict={}, QoQ revenue={}%, YoY profit={}%",
                tradingSymbol, growth.getGrowthVerdict(), growth.getQoqRevenueGrowth(), growth.getYoyProfitGrowth());

        return growth;
    }

    private Double calcGrowth(Double current, Double previous) {
        if (current == null || previous == null || previous == 0) return null;
        return ((current - previous) / Math.abs(previous)) * 100.0;
    }

    private Double calcCAGR(Double endValue, Double startValue, double years) {
        if (endValue == null || startValue == null || startValue <= 0 || endValue <= 0 || years <= 0) return null;
        return (Math.pow(endValue / startValue, 1.0 / years) - 1.0) * 100.0;
    }

    private String determineGrowthVerdict(EarningsGrowthData growth) {
        Double yoyRevenue = growth.getYoyRevenueGrowth();
        Double yoyProfit = growth.getYoyProfitGrowth();

        // Use QoQ if YoY not available
        if (yoyRevenue == null) yoyRevenue = growth.getQoqRevenueGrowth();
        if (yoyProfit == null) yoyProfit = growth.getQoqProfitGrowth();

        if (yoyRevenue == null && yoyProfit == null) return "INSUFFICIENT_DATA";

        double avgGrowth = 0;
        int count = 0;
        if (yoyRevenue != null) { avgGrowth += yoyRevenue; count++; }
        if (yoyProfit != null) { avgGrowth += yoyProfit; count++; }
        avgGrowth = count > 0 ? avgGrowth / count : 0;

        if (avgGrowth > 20 && growth.getConsecutiveGrowthQuarters() >= 3) return "STRONG_GROWTH";
        if (avgGrowth > 10) return "MODERATE_GROWTH";
        if (avgGrowth > 0) return "STAGNANT";
        return "DECLINING";
    }

    /**
     * Project the company's latest-quarter revenue and profit from the trend of
     * its prior three quarters, then compare actuals to the projection. Returns
     * a trend-break verdict. This is an <em>honest proxy</em> for an "earnings
     * surprise" — we do not have access to paid analyst-consensus feeds, so
     * instead we measure deviation from the company's own recent momentum. A
     * 30% profit jump when the trend said +5% is meaningful regardless of what
     * analysts expected.
     *
     * Uses simple linear regression on quarters [Q-3, Q-2, Q-1] to project Q0,
     * then: surprisePct = (actual − projected) / |projected| × 100.
     * Returns {@code null} if fewer than 4 quarters are available.
     */
    public EarningsTrendBreakData analyzeEarningsTrendBreak(String tradingSymbol) {
        List<QuarterlyResult> quarters = fetchQuarterlyResults(tradingSymbol);
        if (quarters == null || quarters.size() < 4) {
            return null;
        }
        // Quarters are newest-first — reverse to get oldest-first
        List<QuarterlyResult> ordered = new ArrayList<>(quarters);
        Collections.reverse(ordered);
        int n = ordered.size();
        QuarterlyResult latest = ordered.get(n - 1);

        // Project latest from the 3 quarters preceding it
        List<QuarterlyResult> trend = ordered.subList(n - 4, n - 1);
        Double projectedRevenue = projectLinear(trend, QuarterlyResult::getRevenue);
        Double projectedProfit = projectLinear(trend, QuarterlyResult::getProfit);

        EarningsTrendBreakData d = new EarningsTrendBreakData();
        d.setSymbol(tradingSymbol);
        d.setLatestPeriod(latest.getPeriod());
        d.setActualRevenue(latest.getRevenue());
        d.setActualProfit(latest.getProfit());
        d.setProjectedRevenue(projectedRevenue);
        d.setProjectedProfit(projectedProfit);

        d.setRevenueSurprisePercent(surprise(latest.getRevenue(), projectedRevenue));
        d.setProfitSurprisePercent(surprise(latest.getProfit(), projectedProfit));

        // Aggregate verdict — use profit surprise (more relevant for valuation) as primary,
        // fall back to revenue when profit is flat/loss-making
        Double primary = d.getProfitSurprisePercent();
        if (primary == null || (latest.getProfit() != null && Math.abs(latest.getProfit()) < 1.0)) {
            primary = d.getRevenueSurprisePercent();
        }
        d.setVerdict(classifyTrendBreak(primary));
        return d;
    }

    // The projection, the surprise and the threshold table now live in one place —
    // com.example.trading.earnings.TrendBreak — because the stored quarterly read (SPEC §50)
    // asks the identical question of the identical figures. Two copies of a rule table are free
    // to drift into disagreeing in identical words, which is the defect Gotcha 85 exists to
    // prevent; these three methods delegate so there is only ever one answer.

    private <T> Double projectLinear(List<QuarterlyResult> quarters,
                                     java.util.function.Function<QuarterlyResult, Double> accessor) {
        return com.example.trading.earnings.TrendBreak.project(
                quarters.stream().map(accessor).toList());
    }

    private Double surprise(Double actual, Double projected) {
        return com.example.trading.earnings.TrendBreak.surprise(actual, projected);
    }

    private String classifyTrendBreak(Double surprisePct) {
        return com.example.trading.earnings.TrendBreak.classify(surprisePct);
    }

    /**
     * Compute balance-sheet / cash-flow quality signals for a stock. Combines:
     *   • quarterly finance-cost & depreciation (from corporates-financial-results)
     *   • promoter pledge % (from shareholding)
     *   • margin / growth consistency (from EarningsGrowthData)
     *
     * Scores 0–100 across five sub-areas (leverage, interest cover, cash-flow
     * proxy, capital efficiency, earnings quality). Each input is optional;
     * missing inputs score as neutral (half weight) to avoid punishing stocks
     * where NSE simply doesn't publish the field.
     *
     * Red flags are evaluated independently of the numeric score — a clean
     * score with a red flag still yields verdict HIGH_RISK.
     */
    public FinancialQualityData analyzeFinancialQuality(String tradingSymbol) {
        FinancialQualityData q = new FinancialQualityData();
        q.setSymbol(tradingSymbol);
        q.setStrengths(new ArrayList<>());
        q.setRedFlags(new ArrayList<>());

        List<QuarterlyResult> quarters = fetchQuarterlyResults(tradingSymbol);
        q.setQuartersAvailable(quarters.size());

        // --- Leverage & interest coverage (from finance cost) ---
        Double interestCoverage = null;
        Double fcToRevenue = null;
        Double interestTrend = null;
        boolean hasFc = false;
        if (!quarters.isEmpty()) {
            QuarterlyResult latest = quarters.get(0);
            if (latest.getFinanceCost() != null && latest.getFinanceCost() > 0) {
                hasFc = true;
                if (latest.getOperatingProfit() != null) {
                    interestCoverage = latest.getOperatingProfit() / latest.getFinanceCost();
                }
                if (latest.getRevenue() != null && latest.getRevenue() > 0) {
                    fcToRevenue = latest.getFinanceCost() / latest.getRevenue() * 100.0;
                }
            }
            if (quarters.size() >= 5 && latest.getFinanceCost() != null
                    && latest.getOperatingProfit() != null
                    && quarters.get(4).getFinanceCost() != null
                    && quarters.get(4).getFinanceCost() > 0
                    && quarters.get(4).getOperatingProfit() != null) {
                double old = quarters.get(4).getOperatingProfit() / quarters.get(4).getFinanceCost();
                if (interestCoverage != null) interestTrend = interestCoverage - old;
            }
        }
        q.setInterestCoverageLatest(interestCoverage);
        q.setInterestCoverageTrend(interestTrend);
        q.setFinanceCostToRevenueLatest(fcToRevenue);
        q.setDebtDataAvailable(hasFc);

        // --- Cash-flow proxy: profit + depreciation (crude OCF) ---
        Double ocfProxy = null;
        Double ocfToProfit = null;
        boolean hasCf = false;
        if (!quarters.isEmpty()) {
            QuarterlyResult latest = quarters.get(0);
            if (latest.getProfit() != null && latest.getDepreciation() != null) {
                hasCf = true;
                ocfProxy = latest.getProfit() + latest.getDepreciation();
                if (latest.getProfit() > 0) {
                    ocfToProfit = ocfProxy / latest.getProfit();
                }
            }
        }
        q.setOcfProxyLatest(ocfProxy);
        q.setOcfToProfitRatio(ocfToProfit);
        q.setCashFlowDataAvailable(hasCf);

        // --- Promoter pledge (from shareholding history) ---
        Double pledge = null;
        try {
            ShareholdingHistory sh = fetchShareholdingHistory(tradingSymbol);
            if (sh != null) pledge = sh.getPledgePercent();
        } catch (Exception e) {
            log.debug("Financial quality: pledge fetch failed for {}: {}", tradingSymbol, e.getMessage());
        }
        q.setPromoterPledgePercent(pledge);

        // --- Margin & growth quality (from EarningsGrowthData) ---
        Double latestNetMargin = null;
        Double marginTrend = null;
        String growthVerdict = null;
        try {
            EarningsGrowthData eg = analyzeEarningsGrowth(tradingSymbol);
            if (eg != null) {
                latestNetMargin = eg.getLatestNetMargin();
                marginTrend = eg.getMarginTrend();
                growthVerdict = eg.getGrowthVerdict();
            }
        } catch (Exception e) {
            log.debug("Financial quality: earnings fetch failed for {}: {}", tradingSymbol, e.getMessage());
        }
        q.setLatestNetMargin(latestNetMargin);
        q.setMarginTrend(marginTrend);

        // === SCORING ===
        int leverageScore = scoreLeverage(interestCoverage, fcToRevenue, pledge, q.getStrengths(), q.getRedFlags()); // 0-25
        int cashFlowScore = scoreCashFlow(ocfToProfit, q.getStrengths(), q.getRedFlags());                           // 0-20
        int marginScore = scoreMargin(latestNetMargin, marginTrend, q.getStrengths(), q.getRedFlags());              // 0-25
        int consistencyScore = scoreConsistency(growthVerdict, q.getStrengths(), q.getRedFlags());                   // 0-15
        int pledgeTrendBonus = scorePledgeAndTrend(pledge, interestTrend, q.getStrengths(), q.getRedFlags());        // 0-15

        int total = leverageScore + cashFlowScore + marginScore + consistencyScore + pledgeTrendBonus;
        total = Math.max(0, Math.min(100, total));

        q.setQualityScore(total);
        q.setQualityVerdict(classifyQuality(total, q.getRedFlags()));
        return q;
    }

    private int scoreLeverage(Double ic, Double fcPct, Double pledge, List<String> pros, List<String> flags) {
        // default neutral if nothing available
        int s = 12;
        if (ic != null) {
            if (ic > 5)       { s = 25; pros.add(String.format("Strong interest coverage: %.1fx", ic)); }
            else if (ic > 3)  { s = 20; pros.add(String.format("Healthy interest coverage: %.1fx", ic)); }
            else if (ic > 1.5){ s = 10; }
            else              { s = 2;  flags.add(String.format("Weak interest coverage: %.1fx (danger zone)", ic)); }
        }
        if (fcPct != null && fcPct > 8) {
            s = Math.max(0, s - 4);
            flags.add(String.format("High finance-cost drag: %.1f%% of revenue", fcPct));
        }
        if (pledge != null && pledge > 50) {
            s = Math.max(0, s - 6);
        }
        return s;
    }

    private int scoreCashFlow(Double ocfToProfit, List<String> pros, List<String> flags) {
        if (ocfToProfit == null) return 10; // neutral when data missing
        if (ocfToProfit >= 1.3) { pros.add(String.format("Strong cash conversion: OCF %.2fx profit", ocfToProfit)); return 20; }
        if (ocfToProfit >= 1.0) { pros.add(String.format("Healthy cash conversion: OCF %.2fx profit", ocfToProfit)); return 15; }
        if (ocfToProfit >= 0.7) return 10;
        flags.add(String.format("Weak cash conversion: OCF only %.2fx profit (accounting-quality concern)", ocfToProfit));
        return 3;
    }

    private int scoreMargin(Double netMargin, Double marginTrend, List<String> pros, List<String> flags) {
        int s = 12; // neutral
        if (netMargin != null) {
            if (netMargin > 20)      { s = 25; pros.add(String.format("Excellent net margin: %.1f%%", netMargin)); }
            else if (netMargin > 12) { s = 20; pros.add(String.format("Strong net margin: %.1f%%", netMargin)); }
            else if (netMargin > 6)  { s = 14; }
            else if (netMargin > 0)  { s = 7; }
            else                     { s = 0; flags.add(String.format("Loss-making: net margin %.1f%%", netMargin)); }
        }
        if (marginTrend != null) {
            if (marginTrend > 1.0)   { s = Math.min(25, s + 3); pros.add("Margins expanding"); }
            else if (marginTrend < -1.0) { s = Math.max(0, s - 3); flags.add("Margins contracting"); }
        }
        return s;
    }

    private int scoreConsistency(String growthVerdict, List<String> pros, List<String> flags) {
        if (growthVerdict == null) return 7;
        return switch (growthVerdict) {
            case "STRONG_GROWTH"   -> { pros.add("Consistent strong earnings growth"); yield 15; }
            case "MODERATE_GROWTH" -> 10;
            case "STAGNANT"        -> 5;
            case "DECLINING"       -> { flags.add("Earnings in decline"); yield 0; }
            default -> 7;
        };
    }

    private int scorePledgeAndTrend(Double pledge, Double interestTrend, List<String> pros, List<String> flags) {
        int s = 8; // neutral
        if (pledge != null) {
            if (pledge == 0)       { s = 15; pros.add("Zero promoter pledge"); }
            else if (pledge < 10)  s = 12;
            else if (pledge < 20)  s = 8;
            else if (pledge < 50)  { s = 3; flags.add(String.format("Elevated promoter pledge: %.1f%%", pledge)); }
            else                   { s = 0; flags.add(String.format("Very high promoter pledge: %.1f%% (major red flag)", pledge)); }
        }
        if (interestTrend != null) {
            if (interestTrend > 0.5) pros.add("Interest coverage improving YoY");
            else if (interestTrend < -0.5) flags.add("Interest coverage deteriorating YoY");
        }
        return s;
    }

    private String classifyQuality(int score, List<String> redFlags) {
        // Any red flag downgrades to HIGH_RISK unless score is exceptional
        boolean hasCriticalFlag = redFlags.stream().anyMatch(f ->
                f.contains("Loss-making") || f.contains("Very high promoter pledge")
                || f.contains("danger zone") || f.contains("accounting-quality"));
        if (hasCriticalFlag) return "HIGH_RISK";
        if (score >= 80) return "HIGH_QUALITY";
        if (score >= 60) return "DECENT";
        if (score >= 40) return "AVERAGE";
        if (score >= 20) return "WEAK";
        return "HIGH_RISK";
    }

    /**
     * Fetch shareholding history across multiple quarters.
     * Tracks promoter, FII, DII holding changes over time.
     */
    /**
     * Fetch shareholding pattern history (per-quarter promoter %, public %, pledge %).
     *
     * <p><b>Data source as of 2026-04 NSE schema change:</b> uses
     * {@code /api/corporate-share-holdings-master?index=equities&symbol=...}. The
     * legacy {@code /api/corporate-shareholding} endpoint now returns 404.
     *
     * <p><b>Coverage caveats:</b>
     * <ul>
     *   <li>Promoter % comes from {@code pr_and_prgrp}; public % from {@code public_val}.</li>
     *   <li>FII vs DII split is <i>not</i> in the JSON — it lives in the per-record XBRL
     *       file. We approximate by splitting institutional holding (= 100 - promoter -
     *       public-non-institutional) 50/50 between FII and DII; downstream consumers
     *       compare changes over quarters so the split itself doesn't matter much for
     *       trend detection.</li>
     *   <li>Pledge % is not in the JSON either. We rely on the boolean
     *       {@code WhetherAnySharesHeldByPromotersAreEncumberedUnderPledged} from XBRL —
     *       not parsed yet, so {@code pledgedPercent} stays null. {@code FinancialQuality}
     *       treats null pledge as 0 (i.e. assumed safe). If pledge tracking is critical,
     *       a follow-up XBRL parser is needed.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public ShareholdingHistory fetchShareholdingHistory(String tradingSymbol) {
        String cacheKey = "shareholding_history:" + tradingSymbol;
        ShareholdingHistory cached = getCached(cacheKey);
        if (cached != null) {
            log.debug("Returning cached shareholding history for {}", tradingSymbol);
            return cached;
        }

        ShareholdingHistory history = new ShareholdingHistory();
        history.setSymbol(tradingSymbol);
        history.setQuarters(new ArrayList<>());

        try {
            refreshCookies();

            List<Map<String, Object>> response = getWebClient().get()
                    .uri("/api/corporate-share-holdings-master?index=equities&symbol=" + tradingSymbol)
                    .header("Cookie", cachedCookies != null ? cachedCookies : "")
                    .retrieve()
                    .bodyToMono(List.class)
                    .block(TIMEOUT);

            if (response != null && !response.isEmpty()) {
                // Sort by date descending so quarters[0] is most recent
                response.sort((a, b) -> {
                    java.time.LocalDate la = parseNseDate(getString(a, "date", null));
                    java.time.LocalDate lb = parseNseDate(getString(b, "date", null));
                    if (la == null && lb == null) return 0;
                    if (la == null) return 1;
                    if (lb == null) return -1;
                    return lb.compareTo(la);
                });

                for (Map<String, Object> item : response) {
                    if (history.getQuarters().size() >= 8) break; // last 8 quarters max
                    ShareholdingQuarter sq = new ShareholdingQuarter();
                    sq.setDate(getString(item, "date", "N/A"));

                    Double promoter = parseDoubleSafe(item.get("pr_and_prgrp"));
                    Double publicHld = parseDoubleSafe(item.get("public_val"));
                    sq.setPromoterHolding(promoter);
                    sq.setPublicHolding(publicHld);

                    // Approximate institutional from residuals: 100 - promoter - employee trusts (when present)
                    Double employeeTrusts = parseDoubleSafe(item.get("employeeTrusts"));
                    if (promoter != null && publicHld != null) {
                        // The "public" bucket lumps retail + institutional (FII/DII). Without XBRL we
                        // can't isolate FII/DII, so split half-half as a placeholder. Trend detection
                        // (comparing across quarters) still works because both halves move together.
                        double instApprox = Math.max(0, publicHld - 5.0); // crude: assume retail ~5%
                        sq.setFiiHolding(instApprox * 0.5);
                        sq.setDiiHolding(instApprox * 0.5);
                    }
                    if (employeeTrusts != null) {
                        // not exposed on ShareholdingQuarter — informational only
                    }

                    // Pledge % is not in the JSON — it lives in the per-record XBRL.
                    // Parse the latest quarter's XBRL only to keep network cost bounded
                    // (one ~300 KB file fetch per stock per 30-min cache TTL).
                    if (history.getQuarters().isEmpty()) {
                        String xbrlUrl = getString(item, "xbrl", null);
                        sq.setPledgedPercent(fetchPledgePercentFromXbrl(xbrlUrl));
                    } else {
                        sq.setPledgedPercent(null);
                    }

                    history.getQuarters().add(sq);
                }

                if (history.getQuarters().size() >= 2) {
                    ShareholdingQuarter newest = history.getQuarters().get(0);
                    ShareholdingQuarter oldest = history.getQuarters().get(history.getQuarters().size() - 1);

                    history.setPromoterChange(safeSubtract(newest.getPromoterHolding(), oldest.getPromoterHolding()));
                    history.setFiiChange(safeSubtract(newest.getFiiHolding(), oldest.getFiiHolding()));
                    history.setDiiChange(safeSubtract(newest.getDiiHolding(), oldest.getDiiHolding()));
                    history.setPromoterIncreasing(history.getPromoterChange() != null && history.getPromoterChange() > 0);
                    history.setFiiIncreasing(history.getFiiChange() != null && history.getFiiChange() > 0);

                    history.setPledgePercent(newest.getPledgedPercent());

                    ShareholdingQuarter secondLatest = history.getQuarters().get(1);
                    Double recentPromoterChange = safeSubtract(newest.getPromoterHolding(), secondLatest.getPromoterHolding());
                    history.setInsiderSignal(determineInsiderSignal(recentPromoterChange));
                } else if (history.getQuarters().size() == 1) {
                    history.setPledgePercent(history.getQuarters().get(0).getPledgedPercent());
                    history.setInsiderSignal("NEUTRAL");
                }

                log.debug("Fetched {} shareholding quarters for {} (latest promoter={}%, public={}%, pledge={}%, date={})",
                        history.getQuarters().size(), tradingSymbol,
                        history.getQuarters().isEmpty() ? null : history.getQuarters().get(0).getPromoterHolding(),
                        history.getQuarters().isEmpty() ? null : history.getQuarters().get(0).getPublicHolding(),
                        history.getQuarters().isEmpty() ? null : history.getQuarters().get(0).getPledgedPercent(),
                        history.getQuarters().isEmpty() ? null : history.getQuarters().get(0).getDate());
                putCache(cacheKey, history);
            } else {
                log.debug("Shareholding history: empty response for {}", tradingSymbol);
            }
        } catch (Exception e) {
            log.debug("Shareholding history fetch failed for {}: {}", tradingSymbol, e.getMessage());
        }

        return history;
    }

    private Double safeSubtract(Double a, Double b) {
        if (a == null || b == null) return null;
        return a - b;
    }

    private String determineInsiderSignal(Double recentPromoterChange) {
        if (recentPromoterChange == null) return "NEUTRAL";
        if (recentPromoterChange > 1.0) return "STRONG_BUY";
        if (recentPromoterChange > 0.0) return "BUY";
        if (recentPromoterChange == 0.0) return "NEUTRAL";
        if (recentPromoterChange > -1.0) return "SELL";
        return "STRONG_SELL";
    }

    // ============================================================
    // Wealth Signals (SPEC.md §12.7) — fundamental quality signals
    // that drive long-term compounding, derived from already-fetched data:
    //   • Gross margin level + trend  (pricing power / moat proxy)
    //   • Earnings-growth consistency (rewards predictable compounders, not lumpy ones)
    //   • Delivery %                  (real accumulation vs. intraday churn)
    // PEG is computed in the screener (it needs the PE from StockValuationService).
    // ============================================================

    /**
     * Compute fundamental "wealth signals" for a stock. All inputs come from data
     * the app already fetches (quarterly results + shareholding snapshot), both cached.
     * Degrades gracefully: any signal that can't be computed (e.g. banks have no COGS,
     * sparse quarters) is left null with a {@code NA} verdict — never penalises the stock.
     */
    public WealthSignalsData analyzeWealthSignals(String tradingSymbol) {
        WealthSignalsData w = new WealthSignalsData();
        w.setSymbol(tradingSymbol);

        List<QuarterlyResult> quarters = fetchQuarterlyResults(tradingSymbol);
        w.setQuartersAvailable(quarters == null ? 0 : quarters.size());

        // --- Gross margin level + trend (newest first) ---
        if (quarters != null && !quarters.isEmpty()) {
            Double latestGm = quarters.get(0).getGrossMargin();
            w.setGrossMarginLatest(latestGm);
            // Trend = latest − one-year-ago (index 4) when available, else oldest available.
            Double priorGm = null;
            if (quarters.size() >= 5) priorGm = quarters.get(4).getGrossMargin();
            if (priorGm == null) {
                for (int i = quarters.size() - 1; i >= 1; i--) {
                    if (quarters.get(i).getGrossMargin() != null) { priorGm = quarters.get(i).getGrossMargin(); break; }
                }
            }
            if (latestGm != null && priorGm != null) {
                double trend = latestGm - priorGm;
                w.setGrossMarginTrend(trend);
                if (trend >= 2.0)        w.setGrossMarginVerdict("EXPANDING");
                else if (trend <= -2.0)  w.setGrossMarginVerdict("CONTRACTING");
                else                     w.setGrossMarginVerdict("STABLE");
            } else if (latestGm != null) {
                w.setGrossMarginVerdict("STABLE"); // level known, trend not
            } else {
                w.setGrossMarginVerdict("NA");     // banks/financials — no COGS line
            }
        } else {
            w.setGrossMarginVerdict("NA");
        }

        // --- Earnings-growth consistency: low volatility of QoQ profit growth + positive bias. ---
        // A company that grows steadily compounds; one that swings +60%/−40% to the same average
        // is far riskier. Score 0-100: positive-quarter ratio rewarded, growth volatility penalised.
        if (quarters != null && quarters.size() >= 4) {
            List<Double> growths = new ArrayList<>();
            int positive = 0;
            for (int i = 0; i + 1 < quarters.size(); i++) {
                Double cur = quarters.get(i).getProfit();
                Double prev = quarters.get(i + 1).getProfit();
                Double g = calcGrowth(cur, prev); // % change, newer vs older
                if (g != null) {
                    growths.add(g);
                    if (g > 0) positive++;
                }
            }
            if (growths.size() >= 3) {
                double positiveRatio = (double) positive / growths.size();
                double mean = growths.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double variance = growths.stream().mapToDouble(g -> (g - mean) * (g - mean)).average().orElse(0);
                double stdDev = Math.sqrt(variance);
                // Volatility penalty: 0 when stdDev<=15pp, full (60) when stdDev>=120pp.
                double volPenalty = Math.max(0, Math.min(60, (stdDev - 15.0) / 105.0 * 60.0));
                double score = (positiveRatio * 100.0) - volPenalty;
                int consistency = (int) Math.max(0, Math.min(100, Math.round(score)));
                w.setEarningsConsistencyScore(consistency);
                if (consistency >= 70)      w.setConsistencyVerdict("VERY_CONSISTENT");
                else if (consistency >= 50) w.setConsistencyVerdict("CONSISTENT");
                else if (consistency >= 30) w.setConsistencyVerdict("VARIABLE");
                else                        w.setConsistencyVerdict("ERRATIC");
            } else {
                w.setConsistencyVerdict("NA");
            }
        } else {
            w.setConsistencyVerdict("NA");
        }

        // --- Delivery %: share of traded volume taken into demat = genuine buyers ---
        // Sourced from the daily bhavcopy (B-021), averaged over the last few sessions.
        // Previously read per-stock from /api/quote-equity, which NSE walled (B-018),
        // leaving this null for the entire universe.
        try {
            Double dp = deliveryDataService.getDeliveryPercent(tradingSymbol);
            w.setDeliveryPercent(dp);
            if (dp == null)        w.setDeliveryVerdict("NA");
            else if (dp >= 60)     w.setDeliveryVerdict("STRONG_HANDS");
            else if (dp >= 40)     w.setDeliveryVerdict("MODERATE");
            else                   w.setDeliveryVerdict("SPECULATIVE");
        } catch (Exception e) {
            log.debug("Wealth signals: delivery fetch failed for {}: {}", tradingSymbol, e.getMessage());
            w.setDeliveryVerdict("NA");
        }

        return w;
    }

    // ============================================================
    // DTOs
    // ============================================================

    /** Raw annual balance-sheet + cash-flow figures (₹ crore) from NSE annual Ind-AS XBRL (SPEC.md §12.8). */
    @Data
    public static class BalanceSheetData {
        private String symbol;
        private String financialYear;     // e.g. "01-Apr-2023 To 31-Mar-2024"
        private boolean consolidated;
        private boolean banking;          // parsed from BANKING_*.xml taxonomy (vs INDAS_*.xml)
        private String filingDate;
        // Balance sheet (period end)
        private Double equity;            // total equity / net worth (banks: Capital + ReservesAndSurplus)
        private Double equityShareCapital;
        private Double otherEquity;
        private Double totalBorrowings;   // noncurrent + current (null for banks)
        private Double totalAssets;
        // Annual P&L + cash flow (full year)
        private Double netProfit;         // ProfitLossForPeriod (banks: ProfitLossForThePeriod)
        private Double profitBeforeTax;
        private Double financeCosts;
        private Double revenue;
        private Double operatingCashFlow; // real CFO
        private Double dividendsPaid;
        private Double depreciation;      // annual depreciation & amortisation
        // Capex-cycle inputs (SPEC.md §31). "prior" values come from the SAME filing's
        // comparative column, not a second fetch — see resolvePriorYearInstantContext.
        private Double capitalWorkInProgress;       // plants under construction, not yet producing
        private Double priorCapitalWorkInProgress;
        private Double propertyPlantEquipment;      // net block
        private Double priorPropertyPlantEquipment;

        /** Shares outstanding in crore: paid-up equity capital / face value (B-046). */
        private Double sharesOutstandingCr;
        /**
         * Face value per share, ₹ — the denominator of the line above, previously discarded.
         * A change in it across two years <b>is</b> a stock split, which B-066 otherwise has to
         * infer from a share-count ratio landing within 0.05% of a simple fraction (Gotcha 86).
         */
        private Double faceValue;
        /** Trade receivables, current + non-current. Null for banks, which have none. */
        private Double tradeReceivables;
        /** True when the filing carried a prior-year comparative balance sheet at all. */
        private boolean priorYearAvailable;
        /** Number of facts tagged against the prior-year context — 0 means declared but empty. */
        private int priorYearFactCount;
    }

    /** Capital-efficiency ratios derived from the annual statement (SPEC.md §12.8) — see {@link #analyzeCapitalEfficiency}. */
    @Data
    public static class CapitalEfficiencyData {
        private String symbol;
        private String financialYear;
        private boolean consolidated;
        private boolean financialSector;  // bank/NBFC/insurer — ROCE & D/E flagged not-comparable; ROA is the headline
        private boolean applicable;        // false when data missing
        private String naReason;
        // Ratios
        private Double roePercent;
        private Double rocePercent;
        private Double roaPercent;            // return on assets — the key bank metric (>1.5% strong)
        private Double debtToEquity;
        private Double cashConversionRatio;   // CFO / net profit
        private Double dividendPayoutPercent;
        // Verdicts
        private String roeVerdict;            // EXCELLENT / GOOD / AVERAGE / WEAK / LOSS
        private String roaVerdict;            // EXCELLENT / GOOD / AVERAGE / WEAK (bank-calibrated)
        private String roceVerdict;           // EXCELLENT / GOOD / AVERAGE / WEAK / NA_FINANCIAL
        private String leverageVerdict;       // VERY_LOW / MODERATE / ELEVATED / HIGH / NA_FINANCIAL
        private String cashConversionVerdict; // STRONG / ADEQUATE / WEAK / NEGATIVE
        private String overallVerdict;        // HIGH_QUALITY_COMPOUNDER / SOLID / AVERAGE / WEAK / POOR / NA
        private List<String> strengths;
        private List<String> redFlags;
    }

    /** Capex-cycle signal (SPEC.md §31) — see {@link #analyzeCapexCycle}. */
    @Data
    public static class CapexCycleData {
        private String symbol;
        private String financialYear;
        /** false when the stock is a bank/financial, or the filing lacks the needed tags. */
        private boolean applicable;
        /** EXPANSION_UNDERWAY / INVESTING / STEADY / HARVESTING / NA_FINANCIAL / NO_DATA */
        private String verdict;
        /** Plain-English explanation of the verdict, suitable for an email or tooltip. */
        private String reason;
        // Raw figures (₹ crore)
        private Double cwipCurrent;
        private Double cwipPrior;
        private Double netBlock;
        private Double depreciation;
        private Double capexProxy;
        // Derived
        private Double cwipIntensityPercent;  // CWIP / net block
        private Double cwipChange;            // current − prior (null without a comparative)
        private Double cwipGrowthPercent;
        private Double capexToDepreciation;
        /** Whether the filing carried a prior-year comparative at all — distinguishes
         *  "no change" from "change not measurable". */
        private boolean priorYearAvailable;
        /** Facts tagged against the prior-year context; 0 = comparative declared but empty. */
        private int priorYearFactCount;

        /**
         * Verdict as a 0-100 sub-score so per-dimension IC can measure it (Gotcha 29:
         * a String verdict cannot enter the accuracy dataset). Null verdicts stay null —
         * an unmeasured capex cycle is not a mid-range one.
         */
        public Integer toScore() {
            if (verdict == null) return null;
            return switch (verdict) {
                case "EXPANSION_UNDERWAY" -> 90;
                case "INVESTING" -> 70;
                case "STEADY" -> 50;
                case "HARVESTING" -> 30;
                default -> null;   // NA_FINANCIAL / NO_DATA are absences, not low scores
            };
        }
    }

    /** Fundamental "wealth signals" (SPEC.md §12.7) — see {@link #analyzeWealthSignals}. */
    @Data
    public static class WealthSignalsData {
        private String symbol;
        // Gross margin (pricing power / moat proxy) — null for banks/financials (no COGS line)
        private Double grossMarginLatest;       // %
        private Double grossMarginTrend;        // latest − ~1yr-ago (percentage points)
        private String grossMarginVerdict;      // EXPANDING / STABLE / CONTRACTING / NA
        // Earnings-growth consistency (rewards steady compounders over lumpy ones)
        private Integer earningsConsistencyScore; // 0-100, higher = steadier
        private String consistencyVerdict;        // VERY_CONSISTENT / CONSISTENT / VARIABLE / ERRATIC / NA
        // Delivery % (genuine accumulation vs. intraday churn) — latest snapshot only
        private Double deliveryPercent;           // %
        private String deliveryVerdict;           // STRONG_HANDS / MODERATE / SPECULATIVE / NA
        private int quartersAvailable;
    }

    @Data
    public static class QuarterlyResult {
        private String period;
        private Double revenue;
        private Double profit;
        private Double eps;
        private Double expenses;
        private Double operatingProfit;
        private Double operatingMargin;
        private Double netMargin;
        // Cost-of-goods-sold proxy & gross margin (SPEC.md §12.7 "Wealth Signals" — pricing-power proxy).
        // COGS = raw material + purchases + inventory change (excludes staff/overheads). Null for
        // banks/financials which don't report these line items.
        private Double cogs;                 // ₹ cr
        private Double grossMargin;          // (revenue - cogs) / revenue × 100
        // Balance-sheet & cash-flow signals (SPEC.md §6 "Financial Quality" dimension)
        private Double financeCost;          // interest + finance charges (₹ cr)
        private Double depreciation;         // non-cash charge (₹ cr)
        private Double tax;                  // tax expense (₹ cr)
        private Double totalExpenses;        // opex total (₹ cr)
        /**
         * Shares outstanding in crore, from paid-up equity capital ÷ face value (B-018).
         * Drives market cap = price × sharesOutstandingCr. Null when the filing omits
         * either element.
         */
        private Double sharesOutstandingCr;

        // ---- Filing metadata (SPEC §50). Carried off the integrated-filing index row rather
        // than the XBRL, because that is where NSE publishes it. Before these existed the
        // figures above were computed on every screening run and thrown away, so nothing could
        // say when a result became public or on what basis it was filed.

        /** The quarter end as a date, parsed from {@code qe_Date}. */
        private java.time.LocalDate quarterEnd;

        /** True consolidated, false standalone, null when NSE did not say (Gotcha 73). */
        private Boolean consolidated;

        /** True when the filing declares itself audited. */
        private Boolean audited;

        /**
         * The filing's {@code broadcast_Date} — when these figures became public.
         *
         * <p>Not the quarter end: SEBI allows 45 days and companies use them, so filing a result
         * under its period end leaks up to six weeks of look-ahead in the flattering direction
         * (Gotcha 100). Null when NSE published no parseable date.
         */
        private java.time.LocalDate availableFrom;

        /** NSE's own filing sequence id, so a stored row can be traced back to the filing. */
        private String filingSeqId;

        /** True when NSE marked this a revision of an earlier filing. */
        private Boolean revised;

        private String revisionRemark;
    }

    /** Earnings trend-break (proxy for analyst surprise) — see {@link #analyzeEarningsTrendBreak}. */
    @Data
    public static class EarningsTrendBreakData {
        private String symbol;
        private String latestPeriod;
        private Double actualRevenue;
        private Double actualProfit;
        /** Revenue projected by linear regression on the 3 quarters preceding the latest. */
        private Double projectedRevenue;
        /** Profit projected by linear regression on the 3 quarters preceding the latest. */
        private Double projectedProfit;
        /** (actual - projected) / |projected| × 100 — revenue. */
        private Double revenueSurprisePercent;
        /** (actual - projected) / |projected| × 100 — profit. */
        private Double profitSurprisePercent;
        /** BIG_POSITIVE_BREAK / POSITIVE_BREAK / IN_LINE / NEGATIVE_BREAK / BIG_NEGATIVE_BREAK / INSUFFICIENT_DATA. */
        private String verdict;
    }

    /** Aggregated balance-sheet / cash-flow quality signals derived from quarterly data + shareholding + valuation. */
    @Data
    public static class FinancialQualityData {
        private String symbol;
        private Integer qualityScore;              // 0-100
        private String qualityVerdict;             // HIGH_QUALITY / DECENT / AVERAGE / WEAK / HIGH_RISK
        private List<String> strengths;
        private List<String> redFlags;

        // Leverage signals
        private Double interestCoverageLatest;     // OperatingProfit / FinanceCost — >3 healthy, <1.5 stressed
        private Double interestCoverageTrend;      // latest − 4-quarter-ago (+ve = improving)
        private Double financeCostToRevenueLatest; // higher = more leveraged
        private Double promoterPledgePercent;      // from shareholding

        // Cash-flow proxy
        private Double ocfProxyLatest;             // Profit + Depreciation (crude OCF, ₹ cr)
        private Double ocfToProfitRatio;           // OCF proxy / Net Profit — ≥1 healthy, <0.7 accounting-quality flag

        // Capital efficiency
        private Double priceToBook;
        private Double dividendYield;
        private Double latestNetMargin;
        private Double marginTrend;                // expanding = +ve

        // Meta
        private int quartersAvailable;
        private boolean cashFlowDataAvailable;
        private boolean debtDataAvailable;
    }

    @Data
    public static class EarningsGrowthData {
        private String symbol;
        private Double qoqRevenueGrowth;
        private Double qoqProfitGrowth;
        private Double yoyRevenueGrowth;
        private Double yoyProfitGrowth;
        private Double yoyEpsGrowth;
        private Double revenueCAGR;  // 2-year CAGR if 8 quarters
        private Double profitCAGR;
        private Double latestOperatingMargin;
        private Double latestNetMargin;
        private Double marginTrend;  // change in net margin over last 4 quarters
        private boolean earningsAccelerating;  // YoY growth > previous YoY growth
        private int consecutiveGrowthQuarters;  // consecutive QoQ revenue growth
        private String growthVerdict;  // STRONG_GROWTH, MODERATE_GROWTH, STAGNANT, DECLINING
        private List<QuarterlyResult> quarters;
    }

    @Data
    public static class ShareholdingHistory {
        private String symbol;
        private List<ShareholdingQuarter> quarters;  // sorted newest first
        private Double promoterChange;     // latest vs oldest quarter change
        private Double fiiChange;          // FII holding change
        private Double diiChange;          // DII holding change
        private boolean promoterIncreasing;
        private boolean fiiIncreasing;
        private Double pledgePercent;      // pledged shares % (if available)
        private String insiderSignal;      // STRONG_BUY / BUY / NEUTRAL / SELL / STRONG_SELL
    }

    @Data
    public static class ShareholdingQuarter {
        private String date;
        private Double promoterHolding;
        private Double fiiHolding;
        private Double diiHolding;
        private Double publicHolding;
        private Double pledgedPercent;  // percent of promoter shares pledged
    }

    @Data
    public static class ShareholdingData {
        private String symbol;
        private String industry;
        private Double promoterHolding;
        private Double publicHolding;
        private Double institutionalHolding;
        private Double deliveryPercent;
        private Double faceValue;
        private Double issuedSize;
        private Double weekHigh52;
        private Double upperBand;
        private Double lowerBand;
        private String shareholdingDate;
    }

    // ============================================================
    // Utility
    // ============================================================

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) return null;
        for (T v : values) if (v != null) return v;
        return null;
    }

    private Double parseDoubleSafe(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            String s = value.toString().replace(",", "").replace("%", "").trim();
            if (s.isEmpty() || s.equals("-")) return null;
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultValue;
    }
}
