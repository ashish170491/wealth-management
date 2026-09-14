package com.example.trading.universe.ipo;

import com.example.trading.ai.NseDataService;
import com.example.trading.marketdata.MarketDataService;
import com.example.trading.multibagger.MultibaggerScore;
import com.example.trading.multibagger.MultibaggerScreenerService;
import com.example.trading.universe.UniverseConfig;
import com.example.trading.universe.UniverseExpansionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * IPO pipeline and post-listing tracker (SPEC §45).
 *
 * <p>Three operations, each with a different cost, deliberately kept apart:
 * <ul>
 *   <li>{@link #capture} — the daily job. Three NSE list calls, one detail call per issue in the
 *       pipeline (and a bounded backfill of older ones), one paced Kite quote per listing under a
 *       year old. Everything it learns lands on {@code ipo_issues}.</li>
 *   <li>{@link #analyse} — on demand, for one listed issue: Kite candles since listing, NSE
 *       results and shareholding, and a compute-to-decide composite (Gotcha 50) once the stock is
 *       past its six-month hype window. Persisted on the row so the page stays DB-only.</li>
 *   <li>{@link #pipelineView} / {@link #recentView} / {@link #issueView} — DB-only read models.
 *       Every verdict on them is computed on read from stored figures (the §12.11 discipline), so
 *       a stored copy can never disagree with the row it describes.</li>
 * </ul>
 *
 * <p><b>SME issues are dropped at capture</b> and counted in the result, not silently: NSE's feeds
 * mix them in, and SPEC §30.1 excludes them structurally because their liquidity and disclosure
 * quality are below what this app's guardrails assume.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpoTrackingService {

    private final NseDataService nse;
    private final MarketDataService marketData;
    private final MultibaggerScreenerService screener;
    private final IpoIssueRepository repository;
    private final UniverseConfig config;
    private final ObjectMapper objectMapper;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter CANDLE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Listings younger than this get a daily price. The whole 36-month window, deliberately: the
     * listings past the six-month hype window are the ones whose stage matters, and a 12-month
     * limit left 185 of 238 reading NOT_MEASURED on the first run. ~240 paced Kite quotes is
     * about 85 seconds once a day.
     */
    static final int PRICE_REFRESH_MONTHS = 36;
    /** Older listings whose detail page has never been read, per run — converges in a few weeks. */
    static final int DETAIL_BACKFILL_PER_RUN = 25;
    /** Listing-day candles fetched per run for rows that lack them; the window converges in four runs. */
    static final int LISTING_CANDLE_BACKFILL_PER_RUN = 60;
    /** How long after close the final subscription is still worth re-reading. */
    static final int FINAL_SUBSCRIPTION_WINDOW_DAYS = 10;
    /** A closed issue with no listing date after this long is withdrawn or stuck, not "awaiting listing". */
    static final int AWAITING_LISTING_MAX_DAYS = 30;
    /** Candles needed before a base of higher lows can be judged (same bar as the IPO watch). */
    static final int MIN_HISTORY_FOR_BASE = 120;
    /** Consecutive "no price" answers before the price refresh gives up for the run. */
    private static final int PRICE_FAILURE_ABORT = 5;

    public record CaptureResult(int upcomingSeen, int pastSeen, int smeDropped, int rowsTouched,
                                int detailsFetched, int pricesRefreshed, int listingCandlesFetched,
                                boolean stoppedEarly, String note) {
    }

    // ------------------------------------------------------------------ capture

    /**
     * @param stop polled between network calls; when it turns true the run keeps what it has and
     *             returns, so a run begun late never pushes into a protected window (Gotcha 97)
     */
    public CaptureResult capture(BooleanSupplier stop) {
        LocalDateTime now = LocalDateTime.now(IST);
        LocalDate today = now.toLocalDate();

        List<Map<String, Object>> upcoming = nse.fetchIpoUpcomingIssues();
        List<Map<String, Object>> current = nse.fetchIpoCurrentIssues();
        List<Map<String, Object>> past = nse.fetchIpoPastIssues();
        if (upcoming.isEmpty() && current.isEmpty() && past.isEmpty()) {
            // Nothing is stamped: the freshness key must go stale rather than claim a capture.
            log.warn("IPO capture: all three NSE feeds returned nothing — leaving the table untouched "
                    + "so its freshness reads as behind, not as 'no IPOs today'.");
            return new CaptureResult(0, 0, 0, 0, 0, 0, 0, false,
                    "NSE returned nothing on all three IPO feeds; nothing was written.");
        }

        Map<String, IpoIssueEntity> touched = new LinkedHashMap<>();
        Set<String> needDetail = new LinkedHashSet<>();
        int sme = 0;

        for (Map<String, Object> m : concat(upcoming, current)) {
            IpoFeedParser.ListRow r = IpoFeedParser.parseListRow(m);
            if (r == null) continue;
            if (!r.isMainboard()) { sme++; continue; }
            IpoIssueEntity e = rowFor(touched, r.tradingSymbol());
            if (r.companyName() != null) e.setCompanyName(r.companyName());
            e.setSeries("EQ");
            if (r.issueStart() != null) e.setIssueStartDate(r.issueStart());
            if (r.issueEnd() != null) e.setIssueEndDate(r.issueEnd());
            if (r.bandLow() != null) e.setPriceBandLow(r.bandLow());
            if (r.bandHigh() != null) e.setPriceBandHigh(r.bandHigh());
            if (r.issueSizeShares() != null) e.setIssueSizeShares(r.issueSizeShares());
            needDetail.add(e.getSymbol());
        }

        // NSE's past-issues feed never fills in the listing date for some issues (NSDL, listed
        // 2025-08-06, still reads "-" on both listingDate and issuePrice). The mainboard equity
        // list carries the authoritative date for every symbol, so it is the fallback - accepted
        // only when it falls after the issue closed, or a re-used ticker would inherit an old date.
        Map<String, LocalDate> equityListDates = new LinkedHashMap<>();
        for (NseDataService.EquityListing l : nse.fetchEquityList()) {
            if (l.isMainboardEquity() && l.listingDate() != null) equityListDates.put(l.symbol(), l.listingDate());
        }

        LocalDate listedFrom = today.minusMonths(config.getRecentIpoMonths());
        LocalDate closedFrom = today.minusDays(AWAITING_LISTING_MAX_DAYS);
        List<IpoIssueEntity> finalSubscription = new ArrayList<>();
        List<IpoIssueEntity> detailBackfill = new ArrayList<>();
        int pastSeen = 0;
        int listingDatesFilled = 0;
        for (Map<String, Object> m : past) {
            IpoFeedParser.PastRow r = IpoFeedParser.parsePastRow(m);
            if (r == null) continue;
            if (!r.isMainboard()) { if ("SME".equalsIgnoreCase(r.securityType())) sme++; continue; }
            LocalDate listing = effectiveListingDate(r.listingDate(), equityListDates.get(r.tradingSymbol()), r.issueEnd());
            if (listing != null && r.listingDate() == null) listingDatesFilled++;
            boolean listedRecently = listing != null && !listing.isBefore(listedFrom);
            boolean closedRecently = listing == null && r.issueEnd() != null && !r.issueEnd().isBefore(closedFrom);
            if (!listedRecently && !closedRecently) continue;
            pastSeen++;

            IpoIssueEntity e = rowFor(touched, r.tradingSymbol());
            if (e.getCompanyName() == null && r.companyName() != null) e.setCompanyName(r.companyName());
            e.setSeries("EQ");
            if (r.issueStart() != null) e.setIssueStartDate(r.issueStart());
            if (r.issueEnd() != null) e.setIssueEndDate(r.issueEnd());
            if (listing != null) e.setListingDate(listing);
            if (e.getPriceBandLow() == null && r.bandLow() != null) e.setPriceBandLow(r.bandLow());
            if (e.getPriceBandHigh() == null && r.bandHigh() != null) e.setPriceBandHigh(r.bandHigh());
            if (r.issuePrice() != null) e.setIssuePrice(r.issuePrice());

            boolean closed = e.getIssueEndDate() != null && today.isAfter(e.getIssueEndDate());
            boolean finalDue = closed && !Boolean.TRUE.equals(e.getSubscriptionFinal())
                    && !e.getIssueEndDate().isBefore(today.minusDays(FINAL_SUBSCRIPTION_WINDOW_DAYS));
            boolean neverDetailed = e.getLotSize() == null && e.getRhpUrl() == null && e.getIssueSizeText() == null;
            if (finalDue) finalSubscription.add(e);
            else if (neverDetailed && !needDetail.contains(e.getSymbol())) detailBackfill.add(e);
        }

        // Detail pages: the live pipeline first, then issues whose final book is now readable,
        // then a bounded slice of history so older listings acquire their structure over a few weeks.
        int details = 0;
        boolean stoppedEarly = false;
        List<IpoIssueEntity> detailOrder = new ArrayList<>();
        for (String s : needDetail) detailOrder.add(touched.get(s));
        detailOrder.addAll(finalSubscription);
        detailBackfill.sort(Comparator.comparing(IpoIssueEntity::getListingDate,
                Comparator.nullsLast(Comparator.reverseOrder())));
        detailOrder.addAll(detailBackfill.subList(0, Math.min(DETAIL_BACKFILL_PER_RUN, detailBackfill.size())));
        for (IpoIssueEntity e : detailOrder) {
            if (stop.getAsBoolean()) { stoppedEarly = true; break; }
            NseDataService.pace();
            IpoFeedParser.Detail d = IpoFeedParser.parseDetail(nse.fetchIpoDetail(e.tradingSymbol()));
            if (d == null) continue;
            applyDetail(e, d, now, today);
            details++;
        }

        for (IpoIssueEntity e : touched.values()) e.setCapturedAt(now);
        repository.saveAll(touched.values());

        int prices = 0;
        int candles = 0;
        if (!stoppedEarly) {
            prices = refreshPrices(touched.values(), today, stop);
            candles = backfillListingCandles(touched.values(), today, stop);
            stoppedEarly = stop.getAsBoolean();
        }
        repository.saveAll(touched.values());

        String note = String.format("%d mainboard issues touched (%d in the pipeline, %d listed/closed, %d listing "
                        + "dates taken from the equity list where the feed had none); %d SME rows dropped by design; "
                        + "%d detail pages read; %d prices refreshed; %d listing-day candles fetched%s.",
                touched.size(), needDetail.size(), pastSeen, listingDatesFilled, sme, details, prices, candles,
                stoppedEarly ? "; stopped early at the protected window" : "");
        log.info("IPO capture: {}", note);
        return new CaptureResult(upcoming.size() + current.size(), pastSeen, sme, touched.size(),
                details, prices, candles, stoppedEarly, note);
    }

    private IpoIssueEntity rowFor(Map<String, IpoIssueEntity> touched, String tradingSymbol) {
        String symbol = "NSE:" + tradingSymbol.trim().toUpperCase();
        return touched.computeIfAbsent(symbol, s -> repository.findBySymbol(s)
                .orElseGet(() -> IpoIssueEntity.builder().symbol(s).build()));
    }

    private static void applyDetail(IpoIssueEntity e, IpoFeedParser.Detail d, LocalDateTime now, LocalDate today) {
        if (d.issueSizeText() != null) e.setIssueSizeText(d.issueSizeText());
        if (d.issueType() != null) e.setIssueType(d.issueType());
        if (d.faceValue() != null) e.setFaceValue(d.faceValue());
        if (d.lotSize() != null) e.setLotSize(d.lotSize());
        if (e.getPriceBandLow() == null && d.bandLow() != null) e.setPriceBandLow(d.bandLow());
        if (e.getPriceBandHigh() == null && d.bandHigh() != null) e.setPriceBandHigh(d.bandHigh());
        if (d.leadManagers() != null) e.setLeadManagers(d.leadManagers());
        if (d.registrar() != null) e.setRegistrar(d.registrar());
        if (d.rhpUrl() != null) e.setRhpUrl(d.rhpUrl());
        if (d.ratiosUrl() != null) e.setRatiosUrl(d.ratiosUrl());
        if (d.anchorUrl() != null) e.setAnchorUrl(d.anchorUrl());
        if (d.employeeDiscountRs() != null) e.setEmployeeDiscountRs(d.employeeDiscountRs());

        IpoFeedParser.IssueSplit split = IpoFeedParser.parseIssueSize(e.getIssueSizeText(), e.getPriceBandHigh());
        e.setFreshIssueCr(split.freshCr());
        e.setOfferForSaleCr(split.offerForSaleCr());
        e.setFreshSharePct(split.freshSharePct());

        IpoFeedParser.Category qib = d.category(IpoFeedParser.QIB);
        IpoFeedParser.Category nii = d.category(IpoFeedParser.NII);
        IpoFeedParser.Category bnii = d.category(IpoFeedParser.BIG_NII);
        IpoFeedParser.Category snii = d.category(IpoFeedParser.SMALL_NII);
        IpoFeedParser.Category retail = d.category(IpoFeedParser.RETAIL);
        IpoFeedParser.Category emp = d.category(IpoFeedParser.EMPLOYEE);
        IpoFeedParser.Category sh = d.category(IpoFeedParser.SHAREHOLDER);
        IpoFeedParser.Category total = d.category(IpoFeedParser.TOTAL);

        e.setEmployeeQuota(emp != null && emp.sharesOffered() != null && emp.sharesOffered() > 0);
        e.setEmployeeReservedShares(emp == null ? null : emp.sharesOffered());
        e.setShareholderQuota(sh != null && sh.sharesOffered() != null && sh.sharesOffered() > 0);
        e.setShareholderReservedShares(sh == null ? null : sh.sharesOffered());

        boolean anyFigure = false;
        if (qib != null) { e.setQibSharesOffered(qib.sharesOffered()); e.setQibTimes(qib.times()); anyFigure |= qib.times() != null; }
        if (nii != null) { e.setNiiSharesOffered(nii.sharesOffered()); e.setNiiTimes(nii.times()); anyFigure |= nii.times() != null; }
        if (bnii != null) e.setBigNiiTimes(bnii.times());
        if (snii != null) e.setSmallNiiTimes(snii.times());
        if (retail != null) { e.setRetailSharesOffered(retail.sharesOffered()); e.setRetailTimes(retail.times()); anyFigure |= retail.times() != null; }
        if (emp != null) e.setEmployeeTimes(emp.times());
        if (sh != null) e.setShareholderTimes(sh.times());
        if (total != null) e.setTotalTimes(total.times());
        if (anyFigure) {
            e.setSubscriptionAsOf(now);
            // Final only once the last bidding day is behind us: institutions bid on the last afternoon.
            e.setSubscriptionFinal(e.getIssueEndDate() != null && today.isAfter(e.getIssueEndDate()));
        }
    }

    private int refreshPrices(Iterable<IpoIssueEntity> rows, LocalDate today, BooleanSupplier stop) {
        LocalDate from = today.minusMonths(PRICE_REFRESH_MONTHS);
        int done = 0;
        int failures = 0;
        for (IpoIssueEntity e : rows) {
            if (e.getListingDate() == null || e.getListingDate().isAfter(today) || e.getListingDate().isBefore(from)) continue;
            if (stop.getAsBoolean()) break;
            Double p = quietPrice(e.getSymbol());
            if (p == null) {
                if (++failures >= PRICE_FAILURE_ABORT && done == 0) {
                    log.warn("IPO capture: {} consecutive symbols returned no price — the broker session is "
                            + "probably not live; skipping the rest of the price refresh for this run.", failures);
                    break;
                }
                continue;
            }
            failures = 0;
            e.setLatestPrice(p);
            e.setLatestPriceDate(today);
            done++;
        }
        return done;
    }

    private int backfillListingCandles(Iterable<IpoIssueEntity> rows, LocalDate today, BooleanSupplier stop) {
        int done = 0;
        for (IpoIssueEntity e : rows) {
            if (done >= LISTING_CANDLE_BACKFILL_PER_RUN) break;
            if (e.getListingDate() == null || e.getListingDate().isAfter(today) || e.getListingDayClose() != null) continue;
            // A symbol the broker does not know (a renamed ticker, a partly-paid instrument such as
            // ADANIENPP1) is tried once, recorded as zero candles, and not retried every day: eight
            // of them produced eight ERROR lines per run on the first day, and a channel that
            // cries wolf daily is how the next real alarm gets ignored. Analyse resets the count.
            if (e.getCandlesAvailable() != null && e.getCandlesAvailable() == 0) continue;
            if (stop.getAsBoolean()) break;
            List<Map<String, Object>> c = candles(e.getSymbol(), e.getListingDate(), e.getListingDate().plusDays(6));
            if (c == null || c.isEmpty()) {
                e.setCandlesAvailable(0);
                continue;
            }
            applyListingDay(e, c.get(0));
            done++;
        }
        return done;
    }

    private static void applyListingDay(IpoIssueEntity e, Map<String, Object> first) {
        Double open = num(first.get("open"));
        Double high = num(first.get("high"));
        Double close = num(first.get("close"));
        if (open != null && open > 0) e.setListingDayOpen(open);
        if (high != null && high > 0) e.setListingDayHigh(high);
        if (close != null && close > 0) e.setListingDayClose(close);
    }

    // ------------------------------------------------------------------ analyse

    /**
     * Everything that can be measured about one listed issue today. Roughly two paced Kite calls
     * and three NSE calls; a compute-to-decide composite on top once the hype window has passed.
     *
     * @throws IllegalArgumentException when the symbol is not on record
     * @throws IllegalStateException    when it has not listed yet
     */
    public IpoIssueEntity analyse(String symbol) {
        String key = normalise(symbol);
        IpoIssueEntity e = repository.findBySymbol(key)
                .orElseThrow(() -> new IllegalArgumentException("No IPO on record for " + key));
        LocalDate today = LocalDate.now(IST);
        if (e.getListingDate() == null || e.getListingDate().isAfter(today)) {
            throw new IllegalStateException(key + " has not listed yet; there is nothing post-listing to analyse.");
        }

        List<String> measured = new ArrayList<>();
        List<String> notMeasured = new ArrayList<>();

        List<Map<String, Object>> history = candles(e.getSymbol(), e.getListingDate(), today);
        if (history == null || history.isEmpty()) {
            e.setCandlesAvailable(0);
            e.setAboveListingHigh(null);
            e.setBaseFormed(null);
            notMeasured.add("price history (broker returned nothing)");
        } else {
            e.setCandlesAvailable(history.size());
            if (e.getListingDayClose() == null) applyListingDay(e, history.get(0));
            Double latest = num(history.get(history.size() - 1).get("close"));
            if (latest != null && latest > 0) {
                e.setLatestPrice(latest);
                e.setLatestPriceDate(today);
            }
            Double high = e.getListingDayHigh();
            e.setAboveListingHigh(high != null && latest != null && latest > high);
            e.setBaseFormed(history.size() >= MIN_HISTORY_FOR_BASE
                    ? UniverseExpansionService.hasHigherLows(history) : null);
            measured.add(history.size() + " daily candles since listing");
            if (history.size() < MIN_HISTORY_FOR_BASE) notMeasured.add("base of higher lows (needs " + MIN_HISTORY_FOR_BASE + " candles)");
        }

        String ts = e.tradingSymbol();
        try {
            NseDataService.EarningsGrowthData g = nse.analyzeEarningsGrowth(ts);
            e.setEarningsVerdict(g == null ? null : g.getGrowthVerdict());
            if (e.getEarningsVerdict() != null) measured.add("earnings growth"); else notMeasured.add("earnings growth (no quarterly filings yet)");
        } catch (Exception ex) {
            log.warn("IPO analyse {}: earnings read failed — {}", ts, ex.toString());
            notMeasured.add("earnings growth (fetch failed)");
        }
        try {
            NseDataService.FinancialQualityData q = nse.analyzeFinancialQuality(ts);
            e.setFinancialQualityVerdict(q == null ? null : q.getQualityVerdict());
            if (e.getFinancialQualityVerdict() != null) measured.add("financial quality"); else notMeasured.add("financial quality (no filings yet)");
        } catch (Exception ex) {
            log.warn("IPO analyse {}: financial-quality read failed — {}", ts, ex.toString());
            notMeasured.add("financial quality (fetch failed)");
        }
        try {
            NseDataService.ShareholdingHistory sh = nse.fetchShareholdingHistory(ts);
            if (sh != null && sh.getQuarters() != null && !sh.getQuarters().isEmpty()) {
                NseDataService.ShareholdingQuarter latest = sh.getQuarters().get(0);
                e.setPromoterHoldingPct(latest.getPromoterHolding());
                e.setPromoterPledgePct(latest.getPledgedPercent());
                measured.add("shareholding pattern");
            } else {
                e.setPromoterHoldingPct(null);
                e.setPromoterPledgePct(null);
                notMeasured.add("shareholding pattern (none filed yet)");
            }
        } catch (Exception ex) {
            log.warn("IPO analyse {}: shareholding read failed — {}", ts, ex.toString());
            notMeasured.add("shareholding pattern (fetch failed)");
        }

        boolean past = IpoLockIn.pastHypeWindow(e.getListingDate(), today, config.getMinMonthsSinceListing());
        if (past && e.getCandlesAvailable() != null && e.getCandlesAvailable() >= MIN_HISTORY_FOR_BASE) {
            try {
                // Compute-to-decide: nothing is persisted to multibagger_scores and no recommendation
                // is recorded (Gotcha 50). The score is research input on this row only.
                MultibaggerScore s = screener.evaluateSingleStock(e.getSymbol());
                e.setCompositeScore(s == null ? null : s.getCompositeScore());
                if (s != null) measured.add("screening composite (not published)"); else notMeasured.add("screening composite");
            } catch (Exception ex) {
                log.warn("IPO analyse {}: composite evaluation failed — {}", ts, ex.toString());
                notMeasured.add("screening composite (failed)");
            }
        } else {
            e.setCompositeScore(null);
            notMeasured.add(past ? "screening composite (too little price history)"
                    : "screening composite (inside the six-month hype window, deliberately)");
        }

        e.setLastAnalysedAt(LocalDateTime.now(IST));
        e.setAnalysisNote("Measured: " + (measured.isEmpty() ? "nothing" : String.join(", ", measured))
                + ". Not measured: " + (notMeasured.isEmpty() ? "nothing" : String.join("; ", notMeasured)) + ".");
        return repository.save(e);
    }

    // ------------------------------------------------------------------ read models

    /** Open, forthcoming and recently closed issues. DB-only. */
    public Map<String, Object> pipelineView() {
        LocalDate today = LocalDate.now(IST);
        List<Map<String, Object>> open = new ArrayList<>();
        List<Map<String, Object>> forthcoming = new ArrayList<>();
        List<Map<String, Object>> closed = new ArrayList<>();
        for (IpoIssueEntity e : repository.findPipeline(today)) {
            if (e.getIssueEndDate() != null && e.getIssueEndDate().isBefore(today.minusDays(AWAITING_LISTING_MAX_DAYS))) continue;
            Map<String, Object> v = view(e, today);
            switch ((String) v.get("status")) {
                case "OPEN" -> open.add(v);
                case "FORTHCOMING" -> forthcoming.add(v);
                default -> closed.add(v);
            }
        }
        open.sort(Comparator.comparing(v -> String.valueOf(v.get("issueEndDate"))));
        forthcoming.sort(Comparator.comparing(v -> String.valueOf(v.get("issueStartDate"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", today.toString());
        out.put("capturedAt", stamp(repository.findLatestCapturedAt()));
        out.put("open", open);
        out.put("forthcoming", forthcoming);
        out.put("closedAwaitingListing", closed);
        out.put("retailCapRs", IpoApplicationMath.RETAIL_CAP_RS);
        out.put("smallNiiCapRs", IpoApplicationMath.SMALL_NII_CAP_RS);
        out.put("employeeCapRs", IpoApplicationMath.EMPLOYEE_CAP_RS);
        out.put("upiCapRs", IpoApplicationMath.UPI_CAP_RS);
        out.put("note", "Mainboard issues only; SME (EMERGE) listings are excluded by design. Subscription "
                + "figures are read once a day around 12:15 and only mean something after the last bidding day.");
        return out;
    }

    /** Issues listed within the window, newest first, with their stage and lock-in calendar. DB-only. */
    public Map<String, Object> recentView(int months) {
        LocalDate today = LocalDate.now(IST);
        int m = months <= 0 ? config.getRecentIpoMonths() : Math.min(months, config.getRecentIpoMonths());
        List<Map<String, Object>> rows = new ArrayList<>();
        int analysed = 0;
        for (IpoIssueEntity e : repository.findListedSince(today.minusMonths(m))) {
            if (e.getListingDate().isAfter(today)) continue;
            rows.add(view(e, today));
            if (e.getLastAnalysedAt() != null) analysed++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", today.toString());
        out.put("windowMonths", m);
        out.put("maturityMonths", config.getMinMonthsSinceListing());
        out.put("capturedAt", stamp(repository.findLatestCapturedAt()));
        out.put("count", rows.size());
        out.put("analysedCount", analysed);
        out.put("listings", rows);
        return out;
    }

    public Optional<Map<String, Object>> issueView(String symbol) {
        LocalDate today = LocalDate.now(IST);
        return repository.findBySymbol(normalise(symbol)).map(e -> view(e, today));
    }

    /** Every stored field plus the on-read verdicts. */
    Map<String, Object> view(IpoIssueEntity e, LocalDate today) {
        Map<String, Object> v = objectMapper.convertValue(e, new TypeReference<LinkedHashMap<String, Object>>() { });
        v.put("tradingSymbol", e.tradingSymbol());
        v.put("status", status(e, today));
        if (e.getIssueStartDate() != null && e.getIssueEndDate() != null) {
            v.put("issueDays", ChronoUnit.DAYS.between(e.getIssueStartDate(), e.getIssueEndDate()) + 1);
            v.put("dayOfIssue", "OPEN".equals(v.get("status"))
                    ? ChronoUnit.DAYS.between(e.getIssueStartDate(), today) + 1 : null);
            v.put("daysToOpen", "FORTHCOMING".equals(v.get("status"))
                    ? ChronoUnit.DAYS.between(today, e.getIssueStartDate()) : null);
            v.put("daysToClose", "OPEN".equals(v.get("status"))
                    ? ChronoUnit.DAYS.between(today, e.getIssueEndDate()) : null);
        }

        IpoStructureRead.Result structure = IpoStructureRead.read(new IpoStructureRead.Input(
                e.getFreshSharePct(), e.getQibTimes(), e.getRetailTimes(), e.getSubscriptionFinal()));
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("verdict", structure.verdict().name());
        s.put("reasons", structure.reasons());
        s.put("structureMeasured", structure.structureMeasured());
        s.put("subscriptionMeasured", structure.subscriptionMeasured());
        v.put("structure", s);

        v.put("sizing", objectMapper.convertValue(
                IpoApplicationMath.size(e.getLotSize(), e.getPriceBandHigh()), Map.class));
        v.put("retailOddsOneIn", IpoApplicationMath.retailOddsOneIn(e.getRetailTimes(), e.getSubscriptionFinal()));
        v.put("issueSizeCr", e.getFreshIssueCr() == null || e.getOfferForSaleCr() == null
                ? null : e.getFreshIssueCr() + e.getOfferForSaleCr());

        if (e.getListingDate() != null && !e.getListingDate().isAfter(today)) {
            v.put("monthsSinceListing", ChronoUnit.MONTHS.between(e.getListingDate(), today));
            v.put("daysSinceListing", ChronoUnit.DAYS.between(e.getListingDate(), today));
            v.put("lockIn", objectMapper.convertValue(IpoLockIn.calendar(e.getListingDate(), today), List.class));
            Boolean above = aboveListingHigh(e.getAboveListingHigh(), e.getLatestPrice(), e.getListingDayHigh());
            v.put("aboveListingHigh", above);
            IpoLockIn.StageRead stage = IpoLockIn.stage(e.getListingDate(), today,
                    config.getMinMonthsSinceListing(), above, e.getBaseFormed());
            v.put("stage", stage.stage().name());
            v.put("stageReason", stage.reason());
            v.put("vsIssuePricePct", pctChange(e.getIssuePrice(), e.getLatestPrice()));
            v.put("vsListingCloseFromIssuePct", pctChange(e.getIssuePrice(), e.getListingDayClose()));
            v.put("vsListingHighPct", pctChange(e.getListingDayHigh(), e.getLatestPrice()));
        }
        return v;
    }

    /**
     * The listing date to trust: the feed's own when it has one, else the equity list's — but
     * only if that date falls after the issue closed. A ticker re-used from a delisted company
     * would otherwise inherit a listing date years before its own IPO.
     */
    static LocalDate effectiveListingDate(LocalDate feedDate, LocalDate equityListDate, LocalDate issueEnd) {
        if (feedDate != null) return feedDate;
        if (equityListDate == null) return null;
        if (issueEnd != null && !equityListDate.isAfter(issueEnd)) return null;
        return equityListDate;
    }

    /**
     * Whether the stock trades above its listing-day high, from the analysis when one has run and
     * otherwise from the two prices the daily capture already holds. Null when either is missing,
     * so an unpriced listing reads NOT_MEASURED rather than WASHOUT.
     */
    static Boolean aboveListingHigh(Boolean stored, Double latestPrice, Double listingDayHigh) {
        if (stored != null) return stored;
        if (latestPrice == null || listingDayHigh == null || listingDayHigh <= 0) return null;
        return latestPrice > listingDayHigh;
    }

    static String status(IpoIssueEntity e, LocalDate today) {
        if (e.getListingDate() != null && !e.getListingDate().isAfter(today)) return "LISTED";
        if (e.getIssueStartDate() != null && today.isBefore(e.getIssueStartDate())) return "FORTHCOMING";
        if (e.getIssueEndDate() != null && !today.isAfter(e.getIssueEndDate())) return "OPEN";
        return "CLOSED";
    }

    // ------------------------------------------------------------------ helpers

    private Double quietPrice(String symbol) {
        try {
            Double p = marketData.getCurrentPrice(symbol);
            return p == null || p <= 0 ? null : p;   // 0.0 is "no price", never a quote (Gotcha 22)
        } catch (Exception ex) {
            return null;
        }
    }

    private List<Map<String, Object>> candles(String symbol, LocalDate from, LocalDate to) {
        try {
            return marketData.getRecentCandles(symbol, "day",
                    from.atTime(9, 15).format(CANDLE_FMT), to.atTime(15, 30).format(CANDLE_FMT));
        } catch (Exception ex) {
            log.debug("IPO candles failed for {}: {}", symbol, ex.getMessage());
            return null;
        }
    }

    static Double pctChange(Double from, Double to) {
        if (from == null || to == null || from <= 0) return null;
        return (to / from - 1.0) * 100.0;
    }

    private static Double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static String stamp(LocalDateTime t) {
        return t == null ? null : t.toString();
    }

    static String normalise(String symbol) {
        String s = symbol == null ? "" : symbol.trim().toUpperCase();
        return s.contains(":") ? s : "NSE:" + s;
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        List<T> out = new ArrayList<>();
        for (List<T> l : lists) if (l != null) out.addAll(l);
        return out;
    }
}
