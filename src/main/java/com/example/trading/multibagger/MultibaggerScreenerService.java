package com.example.trading.multibagger;

import com.example.trading.ai.AnalystSignalService;
import com.example.trading.ai.IntrinsicValuationService;
import com.example.trading.ai.NseDataService;
import com.example.trading.broker.BrokerClient;
import com.example.trading.fiidii.FiiDiiDTO.SectorFlow;
import com.example.trading.fiidii.FiiDiiSectorAnalysisService;
import com.example.trading.holdings.StockValuationService;
import com.example.trading.holdings.StockValuationService.ValuationData;
import com.example.trading.intelligence.recommendation.RecommendationTracker;
import com.example.trading.marketdata.MarketDataService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import com.example.trading.persistence.RecommendationEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Core Multibagger Screening Engine.
 *
 * Scores stocks across 7 dimensions to identify potential multibagger candidates:
 * 1. Technical Momentum (20%) - Weekly/monthly EMA trend, RSI positioning
 * 2. Volume Accumulation (15%) - Smart money accumulation patterns
 * 3. Relative Strength (15%) - Outperformance vs Nifty 50 index
 * 4. Price Structure (15%) - Base building, higher lows, proximity to 52w high
 * 5. Valuation (15%) - PE vs sector PE, market cap category
 * 6. Institutional Interest (10%) - FII/DII sector flows
 * 7. Sector Tailwind (10%) - Sector rotation and momentum
 *
 * Uses Kite API (daily candles) + NSE valuation data + existing FII/DII infrastructure.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MultibaggerScreenerService {

    private final BrokerClient brokerClient;
    private final MarketDataService marketDataService;
    private final StockValuationService valuationService;
    private final HoldingsRepository holdingsRepository;
    private final MultibaggerScoreRepository scoreRepository;
    private final FiiDiiSectorAnalysisService sectorAnalysisService;
    private final NseDataService nseDataService;
    private final IntrinsicValuationService intrinsicValuationService;
    private final AnalystSignalService analystSignalService;
    private final com.example.trading.analyst.AnalystTargetCaptureService analystTargetCaptureService;
    private final MultibaggerConfig config;
    private final com.example.trading.scanner.Nifty200WatchlistService nseWatchlist;
    private final RecommendationTracker recommendationTracker;
    private final UnderDiscoveryService underDiscoveryService;
    private final com.example.trading.insider.InsiderPulseService insiderPulseService;
    private final com.example.trading.fundamentals.FundamentalsHistoryService fundamentalsHistoryService;
    private final com.example.trading.fundamentals.TurnaroundDetectionService turnaroundDetectionService;
    private final com.example.trading.fundamentals.ForensicScreenService forensicScreenService;
    private final com.example.trading.fundamentals.CapexCycleService capexCycleService;
    private final com.example.trading.learning.ScoringVersionRegistry scoringVersionRegistry;
    private final com.example.trading.learning.ScreeningCoverageService screeningCoverageService;
    private final com.example.trading.learning.ShadowCompositeService shadowCompositeService;
    /**
     * SPEC 50. Persists the quarterly figures this run has already fetched for the earnings
     * bonus below — zero extra NSE requests, and the reason result tracking needs no scheduler
     * of its own (Gotcha 28).
     */
    private final com.example.trading.earnings.QuarterlyResultService quarterlyResultService;
    private final com.example.trading.earnings.EarningsConfig earningsConfig;

    /** Composite score at or above which a pick is recorded for accuracy tracking (SPEC.md §23). */
    private static final int RECOMMENDATION_THRESHOLD = 65;

    // Cache for latest screening results
    private volatile List<MultibaggerScore> latestScores = new ArrayList<>();
    private volatile LocalDate lastScreeningDate = null;

    /**
     * Ceiling on the combined contribution of the two insider bonuses (quarterly
     * shareholding + daily PIT pulse). They observe the same promoter through two windows;
     * without this, one person buying once is worth up to 13 points.
     */
    private static final int INSIDER_COMBINED_CAP = 10;

    /** Lookback windows for the buyability guard (SPEC §12.9). */
    private static final int LIQUIDITY_LOOKBACK_DAYS = 20;
    private static final int CIRCUIT_LOOKBACK_DAYS = 60;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Expanded screening universe — broader than intraday watchlist for long-term potential
    private static final List<String> SCREENING_UNIVERSE = List.of(
            // Large Cap - Index Heavyweights
            "NSE:RELIANCE", "NSE:TCS", "NSE:HDFCBANK", "NSE:INFY", "NSE:ICICIBANK",
            "NSE:SBIN", "NSE:LT", "NSE:BAJFINANCE", "NSE:BAJAJFINSV",
            // Metals & Mining
            "NSE:TATASTEEL", "NSE:JSWSTEEL", "NSE:HINDALCO", "NSE:JINDALSTEL",
            "NSE:VEDL", "NSE:SAIL", "NSE:NATIONALUM", "NSE:NMDC", "NSE:COALINDIA",
            // Auto & EV (TATAMOTORS removed 2026-05-10 — post-demerger code unverified;
            // user can re-add TMPV/TMLCOMM after confirming Kite tradingsymbol)
            "NSE:M&M", "NSE:BAJAJ-AUTO", "NSE:EICHERMOT", "NSE:TVSMOTOR",
            "NSE:ASHOKLEY", "NSE:MOTHERSON",
            // IT & Technology
            "NSE:TECHM", "NSE:LTTS", "NSE:PERSISTENT", "NSE:COFORGE", "NSE:MPHASIS",
            "NSE:TATAELXSI", "NSE:KPITTECH", "NSE:DIXON",
            // Energy & Power
            "NSE:ONGC", "NSE:BPCL", "NSE:TATAPOWER", "NSE:POWERGRID",
            "NSE:ADANIGREEN", "NSE:NTPC",
            // Defence & Capital Goods
            "NSE:HAL", "NSE:BEL", "NSE:BHEL",
            // Pharma & Healthcare
            "NSE:SUNPHARMA", "NSE:CIPLA", "NSE:BIOCON", "NSE:AUROPHARMA",
            "NSE:LAURUSLABS", "NSE:GRANULES", "NSE:LUPIN",
            // Real Estate
            "NSE:DLF", "NSE:GODREJPROP", "NSE:OBEROIRLTY",
            // Infrastructure & Railways
            "NSE:IRFC", "NSE:IRCTC", "NSE:RVNL",
            // Chemicals
            "NSE:AARTIIND", "NSE:DEEPAKNTR", "NSE:TATACHEM",
            // Consumer & Electronics
            "NSE:VOLTAS", "NSE:HAVELLS", "NSE:POLYCAB",
            // Banking - Mid/Small cap
            "NSE:BANDHANBNK", "NSE:FEDERALBNK", "NSE:PNB", "NSE:IDFCFIRSTB",
            "NSE:BANKBARODA", "NSE:CANBK", "NSE:AUBANK", "NSE:INDUSINDBK",
            // Adani Group
            "NSE:ADANIENT", "NSE:ADANIPORTS",
            // Emerging / Speculative with multibagger potential
            "NSE:SUZLON", "NSE:IEX", "NSE:MCX", "NSE:PAYTM",
            // FMCG
            "NSE:ITC", "NSE:HINDUNILVR", "NSE:NESTLEIND", "NSE:BRITANNIA",
            // Telecom
            "NSE:BHARTIARTL",
            // Cement
            "NSE:ULTRACEMCO", "NSE:AMBUJACEM", "NSE:SHREECEM",
            // Owned but previously unscreened (A0(b), 2026-08-26). The core-holding classifier
            // reads multibagger_scores, so a holding outside the screening universe can only ever
            // be UNCLASSIFIED however much annual history is imported — these seven were 21% of
            // the portfolio. The last three are held on BSE; their NSE line is what gets screened,
            // and the classifier joins across the exchanges by trading symbol.
            "NSE:ABCAPITAL", "NSE:BANKINDIA", "NSE:LGEINDIA", "NSE:WABAG",
            "NSE:KAJARIACER", "NSE:STARHEALTH", "NSE:CPPLUS"
    );

    // Sector mapping for each stock
    private static final Map<String, String> STOCK_SECTOR_MAP = new HashMap<>();

    /**
     * The screener's own sector for a symbol, matched across exchange prefixes, or null when the
     * table has never heard of it. Read by {@code SectorMapping.resolve} so the portfolio's
     * sector layer can fall back to this list when a holding's industry field is the
     * {@code GENERAL} placeholder (B-096). Null, not "Other": the caller decides what an
     * unknown means, and here it means unclassified rather than a sector of its own.
     */
    public static String sectorFor(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        for (String candidate : com.example.trading.holdings.SymbolVariants.candidates(symbol)) {
            String s = STOCK_SECTOR_MAP.get(candidate);
            if (s != null) return s;
        }
        // The hand-kept map is the override; NSE's own index classification covers the rest
        // (B-098). Before this fallback the map's 89 entries left ~two-thirds of the universe
        // reading the literal "Other", which the screener then presented as a sector.
        String bare = symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;
        return UniverseSectors.industryFor(bare);
    }

    static {
        STOCK_SECTOR_MAP.put("NSE:RELIANCE", "Energy"); STOCK_SECTOR_MAP.put("NSE:TCS", "IT");
        STOCK_SECTOR_MAP.put("NSE:HDFCBANK", "Banking"); STOCK_SECTOR_MAP.put("NSE:INFY", "IT");
        STOCK_SECTOR_MAP.put("NSE:ICICIBANK", "Banking"); STOCK_SECTOR_MAP.put("NSE:SBIN", "Banking");
        STOCK_SECTOR_MAP.put("NSE:LT", "Capital Goods"); STOCK_SECTOR_MAP.put("NSE:BAJFINANCE", "Finance");
        STOCK_SECTOR_MAP.put("NSE:BAJAJFINSV", "Finance");
        STOCK_SECTOR_MAP.put("NSE:TATASTEEL", "Metals"); STOCK_SECTOR_MAP.put("NSE:JSWSTEEL", "Metals");
        STOCK_SECTOR_MAP.put("NSE:HINDALCO", "Metals"); STOCK_SECTOR_MAP.put("NSE:JINDALSTEL", "Metals");
        STOCK_SECTOR_MAP.put("NSE:VEDL", "Metals"); STOCK_SECTOR_MAP.put("NSE:SAIL", "Metals");
        STOCK_SECTOR_MAP.put("NSE:NATIONALUM", "Metals"); STOCK_SECTOR_MAP.put("NSE:NMDC", "Metals");
        STOCK_SECTOR_MAP.put("NSE:COALINDIA", "Metals");
        STOCK_SECTOR_MAP.put("NSE:M&M", "Auto"); STOCK_SECTOR_MAP.put("NSE:BAJAJ-AUTO", "Auto");
        STOCK_SECTOR_MAP.put("NSE:EICHERMOT", "Auto"); STOCK_SECTOR_MAP.put("NSE:TVSMOTOR", "Auto");
        STOCK_SECTOR_MAP.put("NSE:ASHOKLEY", "Auto"); STOCK_SECTOR_MAP.put("NSE:MOTHERSON", "Auto");
        STOCK_SECTOR_MAP.put("NSE:TECHM", "IT"); STOCK_SECTOR_MAP.put("NSE:LTTS", "IT");
        STOCK_SECTOR_MAP.put("NSE:PERSISTENT", "IT"); STOCK_SECTOR_MAP.put("NSE:COFORGE", "IT");
        STOCK_SECTOR_MAP.put("NSE:MPHASIS", "IT"); STOCK_SECTOR_MAP.put("NSE:TATAELXSI", "IT");
        STOCK_SECTOR_MAP.put("NSE:KPITTECH", "IT"); STOCK_SECTOR_MAP.put("NSE:DIXON", "IT");
        STOCK_SECTOR_MAP.put("NSE:ONGC", "Energy"); STOCK_SECTOR_MAP.put("NSE:BPCL", "Energy");
        STOCK_SECTOR_MAP.put("NSE:TATAPOWER", "Power"); STOCK_SECTOR_MAP.put("NSE:POWERGRID", "Power");
        STOCK_SECTOR_MAP.put("NSE:ADANIGREEN", "Power"); STOCK_SECTOR_MAP.put("NSE:NTPC", "Power");
        STOCK_SECTOR_MAP.put("NSE:HAL", "Defence"); STOCK_SECTOR_MAP.put("NSE:BEL", "Defence");
        STOCK_SECTOR_MAP.put("NSE:BHEL", "Capital Goods");
        STOCK_SECTOR_MAP.put("NSE:SUNPHARMA", "Pharma"); STOCK_SECTOR_MAP.put("NSE:CIPLA", "Pharma");
        STOCK_SECTOR_MAP.put("NSE:BIOCON", "Pharma"); STOCK_SECTOR_MAP.put("NSE:AUROPHARMA", "Pharma");
        STOCK_SECTOR_MAP.put("NSE:LAURUSLABS", "Pharma"); STOCK_SECTOR_MAP.put("NSE:GRANULES", "Pharma");
        STOCK_SECTOR_MAP.put("NSE:LUPIN", "Pharma");
        STOCK_SECTOR_MAP.put("NSE:DLF", "Real Estate"); STOCK_SECTOR_MAP.put("NSE:GODREJPROP", "Real Estate");
        STOCK_SECTOR_MAP.put("NSE:OBEROIRLTY", "Real Estate");
        STOCK_SECTOR_MAP.put("NSE:IRFC", "Infrastructure"); STOCK_SECTOR_MAP.put("NSE:IRCTC", "Infrastructure");
        STOCK_SECTOR_MAP.put("NSE:RVNL", "Infrastructure");
        STOCK_SECTOR_MAP.put("NSE:AARTIIND", "Chemicals"); STOCK_SECTOR_MAP.put("NSE:DEEPAKNTR", "Chemicals");
        STOCK_SECTOR_MAP.put("NSE:TATACHEM", "Chemicals");
        STOCK_SECTOR_MAP.put("NSE:VOLTAS", "Consumer"); STOCK_SECTOR_MAP.put("NSE:HAVELLS", "Consumer");
        STOCK_SECTOR_MAP.put("NSE:POLYCAB", "Consumer");
        STOCK_SECTOR_MAP.put("NSE:BANDHANBNK", "Banking"); STOCK_SECTOR_MAP.put("NSE:FEDERALBNK", "Banking");
        // A0(b) additions — owned but previously unscreened (2026-08-26)
        STOCK_SECTOR_MAP.put("NSE:ABCAPITAL", "Finance"); STOCK_SECTOR_MAP.put("NSE:BANKINDIA", "Banking");
        STOCK_SECTOR_MAP.put("NSE:STARHEALTH", "Finance"); STOCK_SECTOR_MAP.put("NSE:LGEINDIA", "Consumer");
        STOCK_SECTOR_MAP.put("NSE:WABAG", "Capital Goods"); STOCK_SECTOR_MAP.put("NSE:KAJARIACER", "Consumer");
        STOCK_SECTOR_MAP.put("NSE:CPPLUS", "Consumer");
        STOCK_SECTOR_MAP.put("NSE:PNB", "Banking"); STOCK_SECTOR_MAP.put("NSE:IDFCFIRSTB", "Banking");
        STOCK_SECTOR_MAP.put("NSE:BANKBARODA", "Banking"); STOCK_SECTOR_MAP.put("NSE:CANBK", "Banking");
        STOCK_SECTOR_MAP.put("NSE:AUBANK", "Banking"); STOCK_SECTOR_MAP.put("NSE:INDUSINDBK", "Banking");
        STOCK_SECTOR_MAP.put("NSE:ADANIENT", "Infrastructure"); STOCK_SECTOR_MAP.put("NSE:ADANIPORTS", "Infrastructure");
        STOCK_SECTOR_MAP.put("NSE:SUZLON", "Power"); STOCK_SECTOR_MAP.put("NSE:IEX", "Power");
        STOCK_SECTOR_MAP.put("NSE:MCX", "Finance"); STOCK_SECTOR_MAP.put("NSE:PAYTM", "Finance");
        STOCK_SECTOR_MAP.put("NSE:ITC", "FMCG"); STOCK_SECTOR_MAP.put("NSE:HINDUNILVR", "FMCG");
        STOCK_SECTOR_MAP.put("NSE:NESTLEIND", "FMCG"); STOCK_SECTOR_MAP.put("NSE:BRITANNIA", "FMCG");
        STOCK_SECTOR_MAP.put("NSE:BHARTIARTL", "Telecom");
        STOCK_SECTOR_MAP.put("NSE:ULTRACEMCO", "Cement"); STOCK_SECTOR_MAP.put("NSE:AMBUJACEM", "Cement");
        STOCK_SECTOR_MAP.put("NSE:SHREECEM", "Cement");
        // Curated small-caps absent from the NSE index lists in universe-sectors.csv (measured on
        // the 2026-09-09 run: 13 of 284 rows had no sector after the file was added, B-098).
        STOCK_SECTOR_MAP.put("NSE:MANINFRA", "Real Estate"); STOCK_SECTOR_MAP.put("NSE:MAHLIFE", "Real Estate");
        STOCK_SECTOR_MAP.put("NSE:CENTUM", "Capital Goods");
        STOCK_SECTOR_MAP.put("NSE:GALAXYSURF", "Chemicals"); STOCK_SECTOR_MAP.put("NSE:FINEORG", "Chemicals");
        STOCK_SECTOR_MAP.put("NSE:LUMAXIND", "Auto"); STOCK_SECTOR_MAP.put("NSE:SUNDRMFAST", "Auto");
        STOCK_SECTOR_MAP.put("NSE:MUNJALSHOW", "Auto");
        STOCK_SECTOR_MAP.put("NSE:GARFIBRES", "Textiles"); STOCK_SECTOR_MAP.put("NSE:DOLLAR", "Textiles");
        STOCK_SECTOR_MAP.put("NSE:WONDERLA", "Consumer"); STOCK_SECTOR_MAP.put("NSE:TCIEXP", "Logistics");
        STOCK_SECTOR_MAP.put("NSE:SANOFI", "Pharma");
    }

    /**
     * Run full screening on the entire universe.
     * Returns scored and sorted list of candidates.
     */
    public List<MultibaggerScore> runFullScreening() {
        List<String> universe = resolveUniverse();
        log.info("Multibagger Screening: Starting full screening of {} stocks (tier={})...",
                universe.size(), config.getScreeningTier());

        // Fetch holdings for cross-referencing
        Map<String, HoldingsEntity> holdingsMap = new HashMap<>();
        try {
            List<HoldingsEntity> holdings = holdingsRepository.findAll();
            for (HoldingsEntity h : holdings) {
                holdingsMap.put(h.getSymbol(), h);
            }
        } catch (Exception e) {
            log.warn("Could not fetch holdings for cross-reference: {}", e.getMessage());
        }

        // Fetch Nifty 50 benchmark data for relative strength calculation
        List<Map<String, Object>> niftyHistory = fetchDailyHistory("NSE:NIFTY 50");

        // Fetch FII/DII sector flows
        Map<String, Double> sectorFlows = fetchSectorFlows();

        // Fetch sector reversal data

        List<MultibaggerScore> scores = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;
        int qualityRejected = 0;

        for (String symbol : universe) {
            try {
                MultibaggerScore score = screenStock(symbol, niftyHistory, holdingsMap, sectorFlows);
                if (score == null) { failCount++; continue; }
                if (!passesTierGate(score)) { qualityRejected++; continue; }
                scores.add(score);
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.debug("Failed to screen {}: {}", symbol, e.getMessage());
            }
        }

        // Sort by composite score descending
        scores.sort(Comparator.comparingInt(MultibaggerScore::getCompositeScore).reversed());

        assignPercentileRanks(scores);

        // Persist scores
        persistScores(scores);

        latestScores = scores;
        lastScreeningDate = LocalDate.now();

        logDimensionVariance(scores);

        // Record how much of the universe each signal was actually measured on (SPEC §38.2).
        // Deliberately only here and not in the single-stock path: a one-symbol run would
        // overwrite the day's coverage vector with a sample of one.
        screeningCoverageService.capture(LocalDate.now(), scoringVersionRegistry.forMultibagger(),
                universe.size(), qualityRejected, failCount, scores);

        // Record what each pre-registered alternative weighting would have scored (SPEC §38.7).
        // Reads the rows persistScores() just wrote, changes nothing, and is read only by the
        // walk-forward harness. Same restriction as the coverage vector above: full runs only —
        // a single-stock run would replace the day's cross-section with a sample of one, and a
        // cross-sectional rank over one stock is not a smaller measurement, it is meaningless.
        shadowCompositeService.captureForDate(LocalDate.now());

        long candidates = scores.stream().filter(this::isCandidate).count();
        log.info("Multibagger Screening: Completed. Screened: {}, Quality-rejected: {}, Failed: {}, "
                        + "Candidates (top {}% and score>={}): {} ({}% of screened)",
                successCount, qualityRejected, failCount,
                config.getCandidateTopPercentile(), config.getMinScoreForCandidate(), candidates,
                successCount > 0 ? Math.round(100.0 * candidates / successCount) : 0);

        return scores;
    }

    /**
     * Assign each stock its cross-sectional percentile rank within this screening run.
     *
     * <p>Percentile 100 = best in the universe, 0 = worst. Ties share the rank of the best
     * position in the tie, so an 18-way tie at the top all read 100 rather than being
     * ordered arbitrarily.
     *
     * <p>Rank is what makes the engine robust to score-scale drift. Every distortion found
     * in the 2026-08 audit was a <i>uniform</i> shift — weights summing to 1.15 scaled every
     * score by 15% (B-019), a constant Valuation dimension added a fixed 6.5 to everyone
     * (B-018), and the bonus stack added a median +9.8 to 85% of stocks. All three passed
     * straight through an absolute {@code >= 60} gate and turned a 39%-pass screen into a
     * 69%-pass screen; none of them move a percentile rank at all.
     */
    private void assignPercentileRanks(List<MultibaggerScore> scores) {
        int n = scores.size();
        if (n == 0) return;
        if (n == 1) {
            scores.get(0).setPercentileRank(100.0);
            return;
        }

        // scores is already sorted best-first.
        for (int i = 0; i < n; i++) {
            int firstOfTie = i;
            while (firstOfTie > 0
                    && scores.get(firstOfTie - 1).getCompositeScore() == scores.get(i).getCompositeScore()) {
                firstOfTie--;
            }
            scores.get(i).setPercentileRank(round1(100.0 * (n - 1 - firstOfTie) / (n - 1)));
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * A candidate must be BOTH in the top slice of this run AND clear an absolute floor.
     *
     * <p>The percentile keeps the shortlist a constant, reviewable size and immunises it
     * against score-scale drift; the absolute floor stops a uniformly terrible market from
     * promoting the best of a bad universe. Neither alone is sufficient — relative-only
     * always yields "candidates" even in a crash, absolute-only is what produced a 69%
     * pass rate.
     */
    boolean isCandidate(MultibaggerScore score) {
        if (score == null) return false;
        Double pct = score.getPercentileRank();
        boolean inTopSlice = pct == null || pct >= (100.0 - config.getCandidateTopPercentile());
        return inTopSlice && score.getCompositeScore() >= config.getMinScoreForCandidate();
    }

    /**
     * Whether a pick is selective enough to enter the §23 accuracy-tracked recommendation
     * set. Uses the same top slice as {@link #isCandidate}; a null percentile (single-stock
     * screen, no universe to rank against) passes so ad-hoc screens still record.
     */
    boolean isRecommendable(MultibaggerScore score) {
        if (score == null) return false;
        Double pct = score.getPercentileRank();
        return pct == null || pct >= (100.0 - config.getCandidateTopPercentile());
    }

    /**
     * Whether a candidate is <b>high conviction</b> — the top band where realised returns
     * actually concentrated (median 4-month return 7.2–8.4% vs 4.7% for 65-69; see
     * {@link MultibaggerConfig#getHighConvictionScore()}).
     *
     * <p>A presentation tier layered on top of {@link #isCandidate}: it changes what reports lead
     * with, never which stocks qualify. Everything that was a candidate stays a candidate.
     */
    public boolean isHighConviction(MultibaggerScore score) {
        return isCandidate(score) && score.getCompositeScore() >= config.getHighConvictionScore();
    }

    /** Resolve the screening universe from config tier, via the NSE watchlist service. */
    private List<String> resolveUniverse() {
        try {
            List<String> tierSymbols = nseWatchlist.getSymbolsByTier(config.getScreeningTier());
            if (tierSymbols != null && !tierSymbols.isEmpty()) {
                // Merge tier universe with the legacy hardcoded set so nothing is lost
                java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(tierSymbols);
                merged.addAll(SCREENING_UNIVERSE);
                return new java.util.ArrayList<>(merged);
            }
        } catch (Exception e) {
            log.warn("Tier universe resolution failed, falling back to legacy SCREENING_UNIVERSE: {}",
                    e.getMessage());
        }
        return SCREENING_UNIVERSE;
    }

    /**
     * Tier-aware quality gate. Large-caps and mid-caps pass through. Small/micro-caps
     * must meet a higher min composite score AND (optionally) extra fundamental checks:
     * <ul>
     *   <li>Current price &gt; {@code smallcapMinPrice} (penny-stock filter)</li>
     *   <li>Promoter pledge &lt; {@code smallcapMaxPledgePercent}</li>
     *   <li>Earnings verdict not DECLINING</li>
     * </ul>
     */
    private boolean passesTierGate(MultibaggerScore score) {
        String cat = nseWatchlist.getCategory(score.getSymbol());
        if (com.example.trading.scanner.Nifty200WatchlistService.CAT_SMALLCAP.equals(cat)) {
            if (score.getCompositeScore() < config.getSmallcapMinScore()) return false;
            return !config.isRequireSmallcapQualityFilters() || passesFundamentalChecks(score);
        }
        if (com.example.trading.scanner.Nifty200WatchlistService.CAT_MICROCAP.equals(cat)) {
            if (score.getCompositeScore() < config.getMicrocapMinScore()) return false;
            return !config.isRequireSmallcapQualityFilters() || passesFundamentalChecks(score);
        }
        return true;
    }

    private boolean passesFundamentalChecks(MultibaggerScore score) {
        if (score.getCurrentPrice() < config.getSmallcapMinPrice()) return false;
        if (config.isSmallcapRequirePositiveEarnings()
                && "DECLINING".equalsIgnoreCase(score.getEarningsGrowthVerdict())) {
            return false;
        }
        try {
            com.example.trading.ai.NseDataService.ShareholdingHistory sh =
                    nseDataService.fetchShareholdingHistory(score.getTradingSymbol());
            if (sh != null && sh.getPledgePercent() != null
                    && sh.getPledgePercent() > config.getSmallcapMaxPledgePercent()) {
                return false;
            }
        } catch (Exception e) {
            log.debug("Pledge check failed for {}: {}", score.getSymbol(), e.getMessage());
        }
        return true;
    }

    /**
     * Screen a specific stock for holdings integration.
     */
    /**
     * Score a stock <b>without persisting anything</b> (B-035).
     *
     * <p>Exists for the universe-expansion funnel, which evaluates candidates that are not
     * in the screening universe and — while `dynamic-expansion.enabled` is false — must not
     * appear anywhere downstream. {@link #screenSingleStock} writes a `multibagger_scores`
     * row under today's date and records a MULTIBAGGER recommendation at composite ≥ 65, so
     * calling it from Stage B put unreviewed names into the dashboard, the morning briefing,
     * `/history`, and the accuracy tracker while the feature reported itself as off.
     *
     * <p>Use this whenever a score is being computed to <i>decide</i> something rather than
     * to <i>publish</i> it.
     */
    public MultibaggerScore evaluateSingleStock(String symbol) {
        return screenOne(symbol, false);
    }

    public MultibaggerScore screenSingleStock(String symbol) {
        return screenOne(symbol, true);
    }

    private MultibaggerScore screenOne(String symbol, boolean persist) {
        List<Map<String, Object>> niftyHistory = fetchDailyHistory("NSE:NIFTY 50");
        Map<String, HoldingsEntity> holdingsMap = new HashMap<>();
        try {
            holdingsRepository.findBySymbol(symbol).ifPresent(h -> holdingsMap.put(symbol, h));
        } catch (Exception e) {
            log.debug("Holdings lookup failed for {}: {}", symbol, e.getMessage());
        }
        Map<String, Double> sectorFlows = fetchSectorFlows();

        MultibaggerScore score = screenStock(symbol, niftyHistory, holdingsMap, sectorFlows);
        // Persist so downstream consumers (conviction auto-seed, drift tracking) see the
        // score — but ONLY when the caller is publishing rather than evaluating (B-035).
        if (score != null && persist) {
            persistScores(List.of(score));
        }
        return score;
    }

    /**
     * The <b>legacy hardcoded list only</b> — roughly 90 names, not the universe the 14:00
     * screening actually runs over.
     *
     * <p>⚠️ Despite what CLAUDE.md Gotcha 57 says, this method does <em>not</em> union the tier
     * list. Anything asking "is this stock already covered?" must call
     * {@link #resolvedScreeningUniverse()} instead — that is the B-053 question, and answering it
     * from this list sees roughly a quarter of the picture.
     */
    public List<String> getScreeningUniverse() {
        return SCREENING_UNIVERSE;
    }

    /**
     * Every symbol the screening run will actually score: the configured tier unioned with the
     * legacy hardcoded set (B-053 — either alone is a partial picture).
     *
     * <p>This is the list to size any universe-wide job against. {@link #getScreeningUniverse()}
     * is the ~90-name legacy subset and reading it as the universe is how the expansion funnel
     * "discovered" a stock with five months of screening history behind it.
     */
    public List<String> resolvedScreeningUniverse() {
        return resolveUniverse();
    }

    /**
     * Is this symbol one the 14:00 screening will ever score? Unions the tier list with the
     * legacy hardcoded set (B-053 — either alone is a partial picture). Used by the watchlist
     * so a "no quality score" can say "never screened" rather than "not yet".
     */
    public boolean isInScreeningUniverse(String symbol) {
        if (symbol == null) return false;
        return resolveUniverse().contains(symbol);
    }

    /**
     * Get latest screening results (cached).
     */
    public List<MultibaggerScore> getLatestScores() {
        return latestScores;
    }

    /**
     * Get top candidates from latest screening.
     */
    public List<MultibaggerScore> getTopCandidates() {
        return latestScores.stream()
                .filter(this::isCandidate)
                .limit(config.getTopCandidatesInReport())
                .collect(Collectors.toList());
    }

    /**
     * Get multibagger scores for holdings only.
     */
    public List<MultibaggerScore> getHoldingsScores() {
        return latestScores.stream()
                .filter(MultibaggerScore::isInHoldings)
                .collect(Collectors.toList());
    }

    // ============================================================
    // Core Scoring Engine
    // ============================================================

    private MultibaggerScore screenStock(String symbol, List<Map<String, Object>> niftyHistory,
                                         Map<String, HoldingsEntity> holdingsMap,
                                         Map<String, Double> sectorFlows) {
        // Fetch daily candle data (1 year)
        List<Map<String, Object>> history = fetchDailyHistory(symbol);
        if (history == null || history.size() < 50) {
            log.debug("Insufficient history for {}: {} candles", symbol, history != null ? history.size() : 0);
            return null;
        }

        double currentPrice = toDouble(history.get(history.size() - 1).get("close"));
        if (currentPrice <= 0) return null;

        String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
        // Null when nobody has classified the stock — never "Other". A placeholder written here
        // reached the screener's Industry column as a sector (B-098); a null reads "not measured".
        String sector = sectorFor(symbol);

        List<String> bullishFactors = new ArrayList<>();
        List<String> bearishFactors = new ArrayList<>();

        // 1. Technical Momentum Score
        int techScore = scoreTechnicalMomentum(history, currentPrice, bullishFactors, bearishFactors);

        // 2. Volume Accumulation Score
        int volScore = scoreVolumeAccumulation(history, bullishFactors, bearishFactors);

        // 3. Relative Strength Score
        int rsScore = scoreRelativeStrength(history, niftyHistory, bullishFactors, bearishFactors);

        // 4. Price Structure Score
        int structScore = scorePriceStructure(history, currentPrice, bullishFactors, bearishFactors);

        // 5. Valuation Score — blend of PE-deviation (50%) and reverse-DCF (50%, when computable)
        ValuationData valuation = null;
        try {
            // Pass the sector so peer PEs bucket together and sector PE can be a live
            // median rather than a hardcoded constant (B-018).
            valuation = valuationService.getValuationData(symbol, sector);
        } catch (Exception e) {
            log.debug("Valuation data unavailable for {}", symbol);
        }
        Integer peScore = scoreValuation(valuation, currentPrice, bullishFactors, bearishFactors);
        com.example.trading.ai.IntrinsicValuationService.ReverseDcfResult dcf = null;
        Integer dcfScore = null;
        try {
            dcf = intrinsicValuationService.analyze(symbol);
            dcfScore = scoreFromDcfVerdict(dcf);
            if (dcfScore != null) {
                if ("DEEPLY_UNDERVALUED".equals(dcf.getVerdict()) || "UNDERVALUED".equals(dcf.getVerdict())) {
                    bullishFactors.add(String.format("Reverse-DCF %s: market prices in only %.0f%%/yr growth",
                            humanizeDcfVerdict(dcf.getVerdict()),
                            dcf.getImpliedGrowthPercent() != null ? dcf.getImpliedGrowthPercent() : 0));
                } else if ("EXTREMELY_EXPENSIVE".equals(dcf.getVerdict())) {
                    bearishFactors.add(String.format("Reverse-DCF Extremely Expensive: implies %.0f%%/yr growth for 10 years",
                            dcf.getImpliedGrowthPercent() != null ? dcf.getImpliedGrowthPercent() : 0));
                } else if ("EXPENSIVE".equals(dcf.getVerdict())) {
                    bearishFactors.add(String.format("Reverse-DCF Expensive: implied %.0f%% vs historical %.0f%%",
                            dcf.getImpliedGrowthPercent() != null ? dcf.getImpliedGrowthPercent() : 0,
                            dcf.getHistoricalGrowthPercent() != null ? dcf.getHistoricalGrowthPercent() : 0));
                }
            }
        } catch (Exception e) {
            log.debug("Reverse DCF unavailable for {}: {}", symbol, e.getMessage());
        }
        // Blend PE-deviation and reverse-DCF 50/50 when both are available; use whichever
        // one is, otherwise leave the whole dimension unmeasured so it drops out of the
        // composite rather than contributing a fabricated neutral.
        Integer valScore;
        if (peScore != null && dcfScore != null) {
            valScore = (int) Math.round(0.5 * peScore + 0.5 * dcfScore);
        } else {
            valScore = (peScore != null) ? peScore : dcfScore;
        }

        // Fetch shareholding once — reused by the Institutional Interest dimension (below)
        // and the Insider Activity bonus (further down). Cached for 30 min inside NseDataService.
        NseDataService.ShareholdingHistory shHistory = null;
        try {
            String shTs = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
            shHistory = nseDataService.fetchShareholdingHistory(shTs);
        } catch (Exception e) {
            log.debug("Shareholding history unavailable for {}: {}", symbol, e.getMessage());
        }

        // 6. Institutional Interest Score — per-stock FII/DII accumulation (fix for constant-40 bug).
        //    Unmeasurable without shareholding history AND without sector flow data.
        Integer instScore = (shHistory == null && (sectorFlows == null || sectorFlows.isEmpty()))
                ? null
                : scoreInstitutionalInterest(symbol, sector, sectorFlows, shHistory, bullishFactors, bearishFactors);

        // 7. Financial Quality Score (balance-sheet / cash-flow depth — SPEC §6)
        String fqTradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
        NseDataService.FinancialQualityData fq = null;
        Integer fqScore = null; // unmeasured until NSE filings actually yield a score
        try {
            fq = nseDataService.analyzeFinancialQuality(fqTradingSymbol);
            if (fq != null && fq.getQualityScore() != null) {
                fqScore = fq.getQualityScore();
                if (fq.getStrengths() != null) bullishFactors.addAll(fq.getStrengths());
                if (fq.getRedFlags() != null) bearishFactors.addAll(fq.getRedFlags());
            }
        } catch (Exception e) {
            log.debug("Financial quality unavailable for {}: {}", symbol, e.getMessage());
        }

        // Calculate weighted composite over the dimensions we could actually measure.
        int composite = weightedComposite(
                techScore, config.getTechnicalMomentumWeight(),
                volScore, config.getVolumeAccumulationWeight(),
                rsScore, config.getRelativeStrengthWeight(),
                structScore, config.getPriceStructureWeight(),
                valScore, config.getValuationWeight(),
                instScore, config.getInstitutionalInterestWeight(),
                fqScore, config.getFinancialQualityWeight());

        // NOTE: the HIGH_RISK cap is applied AFTER all bonuses (see end of this method).
        // It used to be applied here, before them, which let a capped stock claw back up
        // to +47 points and defeat the guarantee in SPEC §12.5 — MPHASIS reached 62 and
        // INDIGO 60 (ROE -34%) on the 2026-08-20 run despite being capped at 54 (B-020).

        // Market cap classification
        Double marketCap = valuation != null ? valuation.getMarketCap() : null;
        String marketCapCategory = MultibaggerScore.classifyMarketCap(marketCap, config.getSmallCapMax(), config.getMidCapMax());

        // Market cap bonus: small caps have more multibagger potential
        if ("SMALL_CAP".equals(marketCapCategory)) {
            composite = Math.min(100, composite + 5);
            bullishFactors.add("Small-cap: higher growth potential");
        } else if ("LARGE_CAP".equals(marketCapCategory)) {
            composite = Math.max(0, composite - 3);
            bearishFactors.add("Large-cap: limited multibagger upside");
        }

        // Earnings Growth Bonus (up to +8 points)
        String earningsVerdict = null;
        Double yoyRevGrowth = null, yoyProfGrowth = null, netMargin = null;
        try {
            String earningsTradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
            NseDataService.EarningsGrowthData earnings = nseDataService.analyzeEarningsGrowth(earningsTradingSymbol);
            captureQuarterlyResults(earningsTradingSymbol);
            if (earnings != null && earnings.getGrowthVerdict() != null) {
                earningsVerdict = earnings.getGrowthVerdict();
                yoyRevGrowth = earnings.getYoyRevenueGrowth();
                yoyProfGrowth = earnings.getYoyProfitGrowth();
                netMargin = earnings.getLatestNetMargin();
                int earningsBonus = 0;
                switch (earnings.getGrowthVerdict()) {
                    case "STRONG_GROWTH":
                        earningsBonus = 8;
                        bullishFactors.add(String.format("Strong earnings growth (YoY Rev: %+.1f%%, Profit: %+.1f%%)",
                            earnings.getYoyRevenueGrowth() != null ? earnings.getYoyRevenueGrowth() : 0,
                            earnings.getYoyProfitGrowth() != null ? earnings.getYoyProfitGrowth() : 0));
                        break;
                    case "MODERATE_GROWTH":
                        earningsBonus = 4;
                        bullishFactors.add("Moderate earnings growth");
                        break;
                    case "STAGNANT":
                        earningsBonus = 0;
                        break;
                    case "DECLINING":
                        earningsBonus = -5;
                        bearishFactors.add("Declining earnings");
                        break;
                }
                if (earnings.isEarningsAccelerating()) {
                    earningsBonus += 3;
                    bullishFactors.add("Earnings accelerating (growth rate increasing)");
                }
                if (earnings.getLatestNetMargin() != null && earnings.getLatestNetMargin() > 15) {
                    earningsBonus += 2;
                    bullishFactors.add(String.format("High net margin: %.1f%%", earnings.getLatestNetMargin()));
                }
                composite = Math.max(0, Math.min(100, composite + earningsBonus));
            }
        } catch (Exception e) {
            log.debug("Earnings growth data unavailable for {}: {}", symbol, e.getMessage());
        }

        // Insider Activity Bonus (up to +5 points) — reuses shHistory fetched for Institutional Interest above
        String insiderSig = null;
        Double promoterChange = null;
        boolean fiiInc = false;
        // Latest-quarter holding levels, kept so the screener can show promoter skin-in-the-game
        // (SPEC §12.5, 2026-09-09). Read from the fetch already made above: no extra NSE call.
        // The change is recorded whether or not an insider signal was derived from it.
        Double promoterPct = null, fiiPct = null, diiPct = null;
        if (shHistory != null) {
            promoterChange = shHistory.getPromoterChange();
            if (shHistory.getQuarters() != null && !shHistory.getQuarters().isEmpty()) {
                NseDataService.ShareholdingQuarter latest = shHistory.getQuarters().get(0); // newest first
                promoterPct = latest.getPromoterHolding();
                fiiPct = latest.getFiiHolding();
                diiPct = latest.getDiiHolding();
            }
        }
        // Retained so the daily Insider Pulse (SPEC §28) can cap the combined contribution
        // of two bonuses that measure the same actor — see the overlap guard further down.
        int insiderBonusApplied = 0;
        try {
            if (shHistory != null && shHistory.getInsiderSignal() != null) {
                insiderSig = shHistory.getInsiderSignal();
                promoterChange = shHistory.getPromoterChange();
                fiiInc = shHistory.isFiiIncreasing();
                int insiderBonus = 0;
                switch (shHistory.getInsiderSignal()) {
                    case "STRONG_BUY":
                        insiderBonus = 5;
                        bullishFactors.add(String.format("Promoter increasing stake (+%.1f%%)",
                            shHistory.getPromoterChange() != null ? shHistory.getPromoterChange() : 0));
                        break;
                    case "BUY":
                        insiderBonus = 2;
                        bullishFactors.add("Promoter marginally increasing stake");
                        break;
                    case "SELL":
                        insiderBonus = -2;
                        bearishFactors.add("Promoter reducing stake");
                        break;
                    case "STRONG_SELL":
                        insiderBonus = -5;
                        bearishFactors.add(String.format("Promoter significantly reducing stake (%.1f%%)",
                            shHistory.getPromoterChange() != null ? shHistory.getPromoterChange() : 0));
                        break;
                }
                if (shHistory.getPledgePercent() != null && shHistory.getPledgePercent() > 20) {
                    insiderBonus -= 3;
                    bearishFactors.add(String.format("High promoter pledge: %.1f%%", shHistory.getPledgePercent()));
                }
                if (shHistory.isFiiIncreasing()) {
                    insiderBonus += 2;
                    bullishFactors.add("FII increasing holdings");
                }
                composite = Math.max(0, Math.min(100, composite + insiderBonus));
                insiderBonusApplied = insiderBonus;
            }
        } catch (Exception e) {
            log.debug("Shareholding history unavailable for {}: {}", symbol, e.getMessage());
        }

        // Analyst Signal Bonus (up to ±5 points) — SPEC.md §24
        // Trend-break (earnings surprise proxy) + brokerage-action news. Low weight on purpose:
        // news keyword-matching is noisy and trend projection is a proxy for missing consensus data.
        try {
            String companyName = valuation != null ? valuation.getIndustry() : null; // best available company hint
            com.example.trading.ai.AnalystSignalService.AnalystSignal analystSignal =
                    analystSignalService.analyze(tradingSymbol, companyName);
            if (analystSignal != null) {
                // Map aggregateScore (-10..+10) to bonus (-5..+5) — half-scale to avoid overweighting
                int analystBonus = Math.max(-5, Math.min(5, analystSignal.getAggregateScore() / 2));
                if (analystBonus != 0) {
                    composite = Math.max(0, Math.min(100, composite + analystBonus));
                    String verdict = analystSignal.getVerdict();
                    if (analystBonus > 0) {
                        bullishFactors.add(String.format("Analyst signal %s (trend-break + brokerage flow)",
                                humanizeAnalystVerdict(verdict)));
                    } else {
                        bearishFactors.add(String.format("Analyst signal %s (trend-break + brokerage flow)",
                                humanizeAnalystVerdict(verdict)));
                    }
                }

                // Record any attributed price target the scan turned up (SPEC 49.5). The news
                // fetch has already happened for the signal above; until now the rupee target in
                // those headlines was counted and then discarded. The symbol is known here, so
                // nothing is inferred and a misattribution is structurally impossible.
                //
                // This writes on the evaluate path as well as the screen path, and that is
                // deliberate rather than an oversight of Gotcha 50: what is written is a record
                // that a third party published a target, which is true whether or not this app
                // chooses to publish a score. It creates no score row, no recommendation and no
                // candidate, which is what B-035 was actually about.
                try {
                    analystTargetCaptureService.recordFromScan(symbol, analystSignal.getBrokerageActions());
                } catch (Exception ledgerError) {
                    log.debug("Analyst target ledger skipped {}: {}", symbol, ledgerError.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Analyst signal unavailable for {}: {}", symbol, e.getMessage());
        }

        // Wealth-Signal Bonus (-8..+10) — SPEC.md §12.7
        // Long-term compounding signals from already-fetched data: gross-margin trend (pricing
        // power / moat proxy), earnings-growth consistency (steady beats lumpy), delivery %
        // (genuine accumulation vs. intraday churn), and PEG (growth-adjusted valuation).
        Double grossMargin = null, grossMarginTrend = null, pegRatio = null, deliveryPct = null;
        Integer earningsConsistency = null;
        String deliveryVerdict = null;   // STRONG_HANDS / SPECULATIVE — reused by the under-discovery lens
        try {
            String wsTradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
            NseDataService.WealthSignalsData ws = nseDataService.analyzeWealthSignals(wsTradingSymbol);
            if (ws != null) {
                grossMargin = ws.getGrossMarginLatest();
                grossMarginTrend = ws.getGrossMarginTrend();
                earningsConsistency = ws.getEarningsConsistencyScore();
                deliveryPct = ws.getDeliveryPercent();
                deliveryVerdict = "NA".equals(ws.getDeliveryVerdict()) ? null : ws.getDeliveryVerdict();
                int wsBonus = 0;

                if ("EXPANDING".equals(ws.getGrossMarginVerdict())) {
                    wsBonus += 3;
                    bullishFactors.add(String.format("Gross margin expanding (%+.1f pp) — pricing power",
                            grossMarginTrend != null ? grossMarginTrend : 0));
                } else if ("CONTRACTING".equals(ws.getGrossMarginVerdict())) {
                    wsBonus -= 3;
                    bearishFactors.add(String.format("Gross margin contracting (%.1f pp) — margin pressure",
                            grossMarginTrend != null ? grossMarginTrend : 0));
                }

                if (earningsConsistency != null) {
                    if (earningsConsistency >= 70) {
                        wsBonus += 3;
                        bullishFactors.add("Consistent earnings growth (steady compounder)");
                    } else if (earningsConsistency < 30) {
                        wsBonus -= 2;
                        bearishFactors.add("Erratic earnings (lumpy / unpredictable growth)");
                    }
                }

                if ("STRONG_HANDS".equals(ws.getDeliveryVerdict())) {
                    wsBonus += 2;
                    bullishFactors.add(String.format("High delivery %% (%.0f%%) — genuine accumulation",
                            deliveryPct != null ? deliveryPct : 0));
                } else if ("SPECULATIVE".equals(ws.getDeliveryVerdict())) {
                    wsBonus -= 1;
                    bearishFactors.add(String.format("Low delivery %% (%.0f%%) — speculative churn",
                            deliveryPct != null ? deliveryPct : 0));
                }

                // PEG = PE / profit growth%. Only meaningful when both are positive.
                Double pe = valuation != null ? valuation.getStockPe() : null;
                if (pe != null && pe > 0 && yoyProfGrowth != null && yoyProfGrowth > 0) {
                    pegRatio = pe / yoyProfGrowth;
                    if (pegRatio < 1.0) {
                        wsBonus += 2;
                        bullishFactors.add(String.format("PEG %.2f — cheap relative to growth", pegRatio));
                    } else if (pegRatio > 2.0) {
                        wsBonus -= 2;
                        bearishFactors.add(String.format("PEG %.2f — expensive relative to growth", pegRatio));
                    }
                }

                wsBonus = Math.max(-8, Math.min(10, wsBonus));
                composite = Math.max(0, Math.min(100, composite + wsBonus));
            }
        } catch (Exception e) {
            log.debug("Wealth signals unavailable for {}: {}", symbol, e.getMessage());
        }

        // Capital-Efficiency Bonus (-10..+12) — SPEC.md §12.8
        // Balance-sheet wealth metrics from NSE annual Ind-AS XBRL: ROCE, ROE,
        // Debt-to-Equity, real cash conversion (CFO/PAT). These most directly identify
        // long-term compounders, so the band is the widest of the post-composite bonuses.
        Double roce = null, roe = null, roa = null, debtToEquity = null, cashConversion = null;
        String capEffVerdict = null;
        try {
            String ceTradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
            String industryHint = valuation != null ? valuation.getIndustry() : sector;
            NseDataService.CapitalEfficiencyData ce = nseDataService.analyzeCapitalEfficiency(ceTradingSymbol, industryHint);
            if (ce != null && ce.isApplicable()) {
                roce = ce.getRocePercent();
                roe = ce.getRoePercent();
                roa = ce.getRoaPercent();
                debtToEquity = ce.getDebtToEquity();
                cashConversion = ce.getCashConversionRatio();
                capEffVerdict = ce.getOverallVerdict();
                int ceBonus = 0;
                if (!"NA_FINANCIAL".equals(ce.getRoceVerdict()) && roce != null) {
                    if (roce >= 20) ceBonus += 5;
                    else if (roce >= 15) ceBonus += 3;
                    else if (roce < 10) ceBonus -= 3;
                }
                if (roe != null) {
                    if (roe >= 18) ceBonus += 3;
                    else if (roe < 8) ceBonus -= 2;
                }
                // Banks/financials: ROCE & D/E don't apply — ROA is the headline efficiency metric.
                if (ce.isFinancialSector() && roa != null) {
                    if (roa >= 1.5) ceBonus += 3;
                    else if (roa < 0.8) ceBonus -= 3;
                }
                if (!"NA_FINANCIAL".equals(ce.getLeverageVerdict()) && debtToEquity != null) {
                    if (debtToEquity <= 0.3) ceBonus += 2;
                    else if (debtToEquity > 2.0) ceBonus -= 4;
                }
                if (cashConversion != null) {
                    if (cashConversion >= 0.8) ceBonus += 2;
                    else if (cashConversion < 0.5) ceBonus -= 2;
                }
                ceBonus = Math.max(-10, Math.min(12, ceBonus));
                composite = Math.max(0, Math.min(100, composite + ceBonus));

                if (ce.getStrengths() != null) bullishFactors.addAll(ce.getStrengths());
                if (ce.getRedFlags() != null) {
                    for (String f : ce.getRedFlags()) {
                        if (!f.toLowerCase().contains("not directly comparable")) bearishFactors.add(f);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Capital efficiency unavailable for {}: {}", symbol, e.getMessage());
        }

        // Capex-Cycle Bonus (-3..+8) — SPEC.md §31
        // The one signal here that leads the P&L instead of following it: capital committed
        // to plants that are not producing revenue yet. Everything else in this composite
        // can only see an expansion after the earnings arrive, by which time the stock has
        // usually moved.
        Double cwipIntensity = null, capexToDep = null;
        String capexVerdict = null;
        Integer capexScore = null;
        try {
            String cxTradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(":") + 1) : symbol;
            String industryHint = valuation != null ? valuation.getIndustry() : sector;
            NseDataService.CapexCycleData cx = capexCycleService.analyze(symbol, industryHint);
            if (cx != null) {
                capexVerdict = cx.getVerdict();
                capexScore = cx.toScore();
                cwipIntensity = cx.getCwipIntensityPercent();
                capexToDep = cx.getCapexToDepreciation();

                // Interaction guard: debt-funded capex on a fragile balance sheet is how a
                // small cap dies, not how it 5x's. When financial quality is WEAK or
                // HIGH_RISK the bonus is suppressed entirely rather than merely reduced —
                // a big build is a *risk* there, so paying points for it inverts the signal.
                String fqVerdict = fq != null ? fq.getQualityVerdict() : null;
                boolean fragile = "WEAK".equals(fqVerdict) || "HIGH_RISK".equals(fqVerdict);

                // SHADOW MODE BY DEFAULT (trading.multibagger.capex-actionable=false): the
                // verdict is computed, persisted and IC-measured, but adds zero points.
                if (cx.isApplicable() && !fragile && config.isCapexActionable()) {
                    int capexBonus = switch (capexVerdict) {
                        case "EXPANSION_UNDERWAY" -> config.getCapexExpansionBonus();
                        case "INVESTING" -> config.getCapexInvestingBonus();
                        // A mature compounder harvesting while earnings still grow is fine —
                        // TCS does not build factories. Only penalise harvesting when growth
                        // has already stalled, which is the combination that signals decline.
                        case "HARVESTING" -> ("STAGNANT".equals(earningsVerdict)
                                || "DECLINING".equals(earningsVerdict)) ? -config.getCapexHarvestingPenalty() : 0;
                        default -> 0;
                    };
                    if (capexBonus != 0) {
                        composite = Math.max(0, Math.min(100, composite + capexBonus));
                        if (capexBonus > 0) bullishFactors.add(cx.getReason());
                        else bearishFactors.add(cx.getReason() + ", while earnings are already stalling");
                    }
                } else if (cx.isApplicable() && fragile) {
                    if ("EXPANSION_UNDERWAY".equals(capexVerdict) || "INVESTING".equals(capexVerdict)) {
                        bearishFactors.add("Building new capacity on a weak balance sheet — "
                                + "expansion is a risk here, not a strength");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Capex cycle unavailable for {}: {}", symbol, e.getMessage());
        }

        // Record this year's filing into the long-horizon table (SPEC §32). The annual
        // statement was just fetched and is cached for 7 days, so this costs no network
        // call — and it is what makes the history table self-maintaining once the one-time
        // import has covered the pre-2025 past.
        try {
            fundamentalsHistoryService.recordFromXbrl(symbol);
        } catch (Exception e) {
            log.debug("Annual fundamentals record failed for {}: {}", symbol, e.getMessage());
        }

        // Insider Pulse (SPEC §28) — daily SEBI PIT open-market promoter/KMP trades, a far
        // fresher read than the quarterly shareholding pattern the Insider Activity bonus uses.
        //
        // SHADOW MODE BY DEFAULT (trading.multibagger.insider-pulse-actionable=false): the
        // verdict is computed, persisted and IC-measured, but adds zero points. A new signal
        // must earn its way into the score, not arrive in it — see MultibaggerConfig.
        String insiderPulseVerdict = null;
        Double insiderNetBuyPct = null;
        Integer insiderPulseScore = null;
        try {
            com.example.trading.insider.InsiderPulse pulse =
                    insiderPulseService.computePulse(symbol, marketCap);
            if (pulse != null && pulse.getVerdict() != null) {
                insiderPulseVerdict = pulse.getVerdict();
                insiderNetBuyPct = pulse.getNetBuyPercentOfMarketCap();
                insiderPulseScore = pulse.toScore();

                if (config.isInsiderPulseActionable()) {
                    int pulseBonus = switch (insiderPulseVerdict) {
                        case "STRONG_ACCUMULATION" -> config.getInsiderPulseStrongBonus();
                        case "ACCUMULATION" -> config.getInsiderPulseModerateBonus();
                        case "DISTRIBUTION" -> -config.getInsiderPulseModerateBonus();
                        case "STRONG_DISTRIBUTION" -> -config.getInsiderPulseStrongBonus();
                        default -> 0;
                    };
                    // Overlap guard: the quarterly Insider Activity bonus above and this daily
                    // one measure the SAME actor at different frequencies. Letting both fire
                    // fully would double-count one promoter's conviction. When they agree,
                    // the combined contribution is capped; when they disagree, the daily
                    // signal wins on freshness and the conflict is logged as a data tripwire.
                    if (pulseBonus != 0 && insiderBonusApplied != 0) {
                        if (Integer.signum(pulseBonus) == Integer.signum(insiderBonusApplied)) {
                            int room = Math.max(0, INSIDER_COMBINED_CAP - Math.abs(insiderBonusApplied));
                            pulseBonus = Integer.signum(pulseBonus) * Math.min(Math.abs(pulseBonus), room);
                        } else {
                            log.info("Insider signal conflict for {}: quarterly={} daily={} — daily wins (fresher)",
                                    symbol, insiderSig, insiderPulseVerdict);
                        }
                    }
                    if (pulseBonus != 0) {
                        composite = Math.max(0, Math.min(100, composite + pulseBonus));
                        if (pulseBonus > 0) {
                            bullishFactors.add("Insiders buying on the open market (" + insiderPulseVerdict + ")");
                        } else {
                            bearishFactors.add("Insiders selling on the open market (" + insiderPulseVerdict + ")");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Insider pulse unavailable for {}: {}", symbol, e.getMessage());
        }

        // Long-horizon fundamentals (SPEC §32) — turnaround bonus and forensic flags.
        //
        // Both read the annual-history table, which is only populated for stocks the user
        // has imported. A stock with no history produces INSUFFICIENT_HISTORY and no flags,
        // which must NOT be read as "clean": nothing was checked. That distinction is
        // carried through to the reports rather than collapsed here.
        String turnaroundVerdict = null;
        String forensicFlags = null;
        boolean auditorForcesHighRisk = false;
        try {
            if (fundamentalsHistoryService.yearsAvailable(symbol)
                    >= com.example.trading.fundamentals.FundamentalsHistoryService.MIN_YEARS_FOR_ANALYSIS) {

                var ta = turnaroundDetectionService.detect(symbol);
                turnaroundVerdict = ta.getVerdict();
                if (ta.isCandidate()) {
                    // Suppressed on a HIGH_RISK balance sheet: "the numbers have turned" is
                    // not a reason to buy a company whose numbers cannot be trusted.
                    boolean fragile = fq != null && "HIGH_RISK".equals(fq.getQualityVerdict());
                    if (!fragile) {
                        // Shadow mode by default (trading.multibagger.turnaround-actionable).
                        // The reasons are still surfaced — the reader gets the finding even
                        // while the score stays untouched.
                        if (config.isTurnaroundActionable()) {
                            composite = Math.max(0, Math.min(100, composite + config.getTurnaroundBonus()));
                        }
                        bullishFactors.add("Recovering from a bad stretch — " + ta.getCriteriaMet()
                                + " of 4 turnaround signs present");
                        bullishFactors.addAll(ta.getSignals());
                    }
                }

                // Scan announcements only for stocks that could still be recommended (B-038).
                // The auditor flag's only job is the HIGH_RISK cap at 54, so below that
                // threshold the network call cannot change an outcome. Above it, skipping
                // the call left SPEC §32.4's guarantee unimplemented exactly where it
                // matters — the run that decides what the user is shown.
                boolean scanAnnouncements =
                        composite >= com.example.trading.fundamentals.ForensicScreenService.AUDITOR_SCAN_MIN_COMPOSITE;
                var fr = forensicScreenService.screen(symbol, scanAnnouncements);
                if (!fr.isClean()) {
                    forensicFlags = fr.toStorageString();
                    // Risk controls are armed by default, unlike the bonuses above — see
                    // MultibaggerConfig.forensicActionable for why the asymmetry is deliberate.
                    auditorForcesHighRisk = fr.isForcesHighRisk() && config.isForensicActionable();
                    if (fr.getTotalPenalty() > 0 && config.isForensicActionable()) {
                        composite = Math.max(0, composite - fr.getTotalPenalty());
                    }
                    for (var f : fr.getFlags()) {
                        bearishFactors.add(f.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Long-horizon fundamentals unavailable for {}: {}", symbol, e.getMessage());
        }

        // HIGH_RISK financial-quality stocks are structurally unsafe for long-term holds.
        // Applied LAST, after every bonus, so the SPEC §12.5 guarantee actually holds:
        // a fragile balance sheet can never cross the 65-point recommendation threshold,
        // no matter how strong its momentum/earnings/capital-efficiency bonuses are.
        if (fq != null && "HIGH_RISK".equals(fq.getQualityVerdict())) {
            composite = Math.min(composite, 54);
            bearishFactors.add("Financial quality HIGH_RISK — composite capped at 54");
        }

        // An auditor resignation or qualified opinion (SPEC §32.4) triggers the same cap
        // independently of the financial-quality verdict. It has to: every quality metric
        // above is computed FROM the audited numbers, so a compromised audit makes a clean
        // financial-quality reading meaningless rather than reassuring.
        if (auditorForcesHighRisk) {
            composite = Math.min(composite, 54);
            bearishFactors.add("Auditor problem disclosed — composite capped at 54 until it is explained");
        }

        // Check if in holdings
        boolean inHoldings = holdingsMap.containsKey(symbol);
        Double holdingsPnl = null;
        if (inHoldings) {
            HoldingsEntity h = holdingsMap.get(symbol);
            holdingsPnl = h.getPnlPercent();
        }

        // Extract key metrics for display
        double[] priceMetrics = calculatePriceMetrics(history, currentPrice);
        double weeklyRsi = calculateRSI(aggregateToWeekly(history), config.getMonthlyRsiPeriod());
        // Nullable on purpose (B-060). RSI-14 on monthly bars needs 15 bars = ~330 trading days,
        // and `history-days: 365` is 365 *calendar* days, i.e. ~276 candles = ~12 bars. So this is
        // not merely thin for some stocks, it is uncomputable for every one of them, and the old
        // primitive call returned calculateRSI's 50.0 "neutral default" for all 294 rows — a
        // constant wearing the shape of a measurement (Gotcha 21, and the same zero-variance
        // signature as the Institutional-Interest bug). Reporting it as unmeasured is correct;
        // widening the fetch window to make it real would change the input window of every other
        // calculation here and cost ~300 extra paced Kite calls, which is a separate decision.
        Double monthlyRsi = calculateRsiOrNull(aggregateToMonthly(history), config.getMonthlyRsiPeriod());

        // Support and range, from candles this run already has in hand - no extra Kite call.
        // Both nullable: a short history yields no level rather than a misleading one (B-060).
        Double support20d = lowestLow(history, 20);
        Double atr14 = calculateAtrOrNull(history, 14);
        // Free: the candles are already in hand. Null below 50 bars rather than a short-window
        // average masquerading as a 50-day one (B-060's lesson).
        Double ema50 = history.size() >= 50 ? calculateEMA(history, 50) : null;

        // Buyability (SPEC §12.9) — an execution property, computed from candles already in
        // hand and deliberately kept out of the composite. A stock can be an excellent
        // business and still be un-accumulable; those are two different facts.
        Double adv20 = calculateAdv20(history);
        String liquidityTier = MultibaggerScore.classifyLiquidity(adv20);
        Integer circuitDays = countCircuitDays(history);
        if ("THIN".equals(liquidityTier)) {
            bearishFactors.add(String.format("Thin liquidity — only Rs %.1f lakh traded per day on average",
                    adv20 / 1_00_000d));
        }
        if (circuitDays != null && circuitDays >= 3) {
            bearishFactors.add(String.format("Circuit risk — locked with no intraday range on %d of the last 60 days",
                    circuitDays));
        }

        // Under-discovery lens (SPEC §12.10). News coverage is passed as null here: 361
        // Google-News RSS fetches per screening run is not a reasonable cost, so that
        // component is renormalised away rather than guessed at.
        Integer underDiscovery = underDiscoveryService.compute(
                composite,
                fq != null ? fq.getQualityVerdict() : null,
                shHistory,
                marketCapCategory,
                marketCap,
                deliveryVerdict,
                priceMetrics[3],
                null,
                bullishFactors);

        MultibaggerScore score = MultibaggerScore.builder()
                .symbol(symbol)
                .tradingSymbol(tradingSymbol)
                .industry(sector != null ? sector : (valuation != null ? valuation.getIndustry() : null))
                .currentPrice(currentPrice)
                .marketCapCrores(marketCap)
                .technicalMomentumScore(techScore)
                .volumeAccumulationScore(volScore)
                .relativeStrengthScore(rsScore)
                .priceStructureScore(structScore)
                .valuationScore(valScore)
                .institutionalInterestScore(instScore)
                .financialQualityScore(fqScore)
                .financialQualityVerdict(fq != null ? fq.getQualityVerdict() : null)
                .interestCoverage(fq != null ? fq.getInterestCoverageLatest() : null)
                .ocfToProfitRatio(fq != null ? fq.getOcfToProfitRatio() : null)
                .promoterPledgePercent(fq != null ? fq.getPromoterPledgePercent() : null)
                .dcfVerdict(dcf != null ? dcf.getVerdict() : null)
                .dcfImpliedGrowthPercent(dcf != null ? dcf.getImpliedGrowthPercent() : null)
                .dcfHistoricalGrowthPercent(dcf != null ? dcf.getHistoricalGrowthPercent() : null)
                .dcfExpectationGapPercent(dcf != null ? dcf.getExpectationGapPercent() : null)
                .compositeScore(composite)
                .grade(MultibaggerScore.calculateGrade(composite))
                .verdict(MultibaggerScore.calculateVerdict(composite))
                .marketCapCategory(marketCapCategory)
                .weeklyRsi(weeklyRsi)
                .monthlyRsi(monthlyRsi)
                .support20d(support20d)
                .atr14(atr14)
                .ema50(ema50)
                .relativeStrengthVsNifty(priceMetrics[0])
                .peDeviation(valuation != null ? valuation.getPeDeviation() : null)
                .priceVs52WeekHigh(priceMetrics[1])
                .priceVs52WeekLow(priceMetrics[2])
                .avgVolumeRatio(priceMetrics[3])
                .weeklyEmaSlope(priceMetrics[4])
                .bullishFactors(bullishFactors)
                .bearishFactors(bearishFactors)
                .earningsGrowthVerdict(earningsVerdict)
                .yoyRevenueGrowth(yoyRevGrowth)
                .yoyProfitGrowth(yoyProfGrowth)
                .latestNetMargin(netMargin)
                .insiderSignal(insiderSig)
                .promoterHoldingChange(promoterChange)
                .fiiIncreasing(fiiInc)
                .promoterHoldingPct(promoterPct)
                .fiiHoldingPct(fiiPct)
                .diiHoldingPct(diiPct)
                .grossMarginPercent(grossMargin)
                .grossMarginTrend(grossMarginTrend)
                .pegRatio(pegRatio)
                .deliveryPercent(deliveryPct)
                .earningsConsistencyScore(earningsConsistency)
                .rocePercent(roce)
                .roePercent(roe)
                .roaPercent(roa)
                .debtToEquity(debtToEquity)
                .cashConversionRatio(cashConversion)
                .capitalEfficiencyVerdict(capEffVerdict)
                .cwipIntensityPercent(cwipIntensity)
                .capexToDepreciation(capexToDep)
                .capexVerdict(capexVerdict)
                .capexScore(capexScore)
                .turnaroundVerdict(turnaroundVerdict)
                .forensicFlags(forensicFlags)
                .liquidityAdv20d(adv20)
                .liquidityTier(liquidityTier)
                .circuitDaysLast60(circuitDays)
                .underDiscoveryScore(underDiscovery)
                .insiderPulseVerdict(insiderPulseVerdict)
                .insiderNetBuy90dPct(insiderNetBuyPct)
                .insiderPulseScore(insiderPulseScore)
                .inHoldings(inHoldings)
                .holdingsPnlPercent(holdingsPnl)
                .scoredAt(LocalDateTime.now())
                .build();

        log.debug("Multibagger Score: {} -> {} (Grade: {}, Verdict: {})",
                symbol, composite, score.getGrade(), score.getVerdict());

        return score;
    }

    // ============================================================
    // Dimension 1: Technical Momentum (0-100)
    // ============================================================

    // Package-private for characterisation tests (SPEC §25.6: the scoring engine was untested).
    int scoreTechnicalMomentum(List<Map<String, Object>> history, double currentPrice,
                                       List<String> bullish, List<String> bearish) {
        int score = 0;

        // Weekly aggregated data
        List<Map<String, Object>> weekly = aggregateToWeekly(history);
        if (weekly.size() < 26) return 30; // Neutral if insufficient data

        // The three structural checks below are deliberately worth 45 combined, not 55.
        // They are near-binary "is this an uptrend" flags, and when they carried 55 of the
        // 100 points alongside two coarsely-bucketed components the dimension saturated:
        // any stock above both weekly EMAs with a positive crossover, >1% slope and RSI
        // anywhere in 50-75 scored exactly 100. On the 2026-08-20 run that was 122 of 291
        // stocks (42%) — the heaviest-weighted dimension could not rank the top 42% of the
        // universe, and 18 stocks tied at composite 100.
        //
        // Components 4 and 5 are now continuous, so trend *strength* separates stocks
        // instead of merely trend *presence*. A routine uptrend now lands near 80; 100
        // requires an exceptionally steep slope AND ideal RSI positioning.

        // 1. Price above weekly EMA-26 (long-term uptrend) — 20 pts
        double ema26 = calculateEMA(weekly, config.getWeeklyEmaLong());
        if (currentPrice > ema26) {
            score += 20;
            bullish.add("Price above weekly EMA-26 (long-term uptrend)");
        } else {
            bearish.add("Price below weekly EMA-26 (downtrend)");
        }

        // 2. Price above weekly EMA-12 (medium-term uptrend) — 12 pts
        double ema12 = calculateEMA(weekly, config.getWeeklyEmaShort());
        if (currentPrice > ema12) {
            score += 12;
        }

        // 3. EMA-12 > EMA-26 (bullish crossover) — 13 pts
        if (ema12 > ema26) {
            score += 13;
            bullish.add("Weekly EMA bullish crossover (12 > 26)");
        }

        // 4. Weekly EMA slope — continuous, 0..25 pts, saturating at +5%.
        if (weekly.size() >= 4) {
            List<Map<String, Object>> olderWeekly = weekly.subList(0, weekly.size() - 3);
            double olderEma = calculateEMA(olderWeekly, config.getWeeklyEmaShort());
            if (olderEma > 0) {
                double slope = (ema12 - olderEma) / olderEma * 100;
                int slopePoints = (int) Math.round(Math.max(0, Math.min(25, slope / 5.0 * 25)));
                score += slopePoints;
                if (slope > 1.0) {
                    bullish.add(String.format("Strong weekly EMA slope: +%.1f%%", slope));
                }
            }
        }

        // 5. Weekly RSI positioning — continuous, 0..30 pts, peaking mid-sweet-spot.
        double rsi = calculateRSI(weekly, config.getMonthlyRsiPeriod());
        score += scoreRsiPosition(rsi, bullish, bearish);

        return Math.max(0, Math.min(100, score));
    }

    // ============================================================
    // Dimension 2: Volume Accumulation (0-100)
    // ============================================================

    // Package-private for characterisation tests (SPEC §25.6: the scoring engine was untested).
    int scoreVolumeAccumulation(List<Map<String, Object>> history,
                                        List<String> bullish, List<String> bearish) {
        int score = 0;
        if (history.size() < 60) return 30;

        // Calculate average volume over last 60 days
        int lookback = Math.min(history.size(), 60);
        double avgVolume = 0;
        for (int i = history.size() - lookback; i < history.size(); i++) {
            avgVolume += toDouble(history.get(i).get("volume"));
        }
        avgVolume /= lookback;

        if (avgVolume <= 0) return 30;

        // 1. Recent volume vs average (last 10 days vs 60-day avg) — 30 pts
        double recentVolume = 0;
        for (int i = history.size() - 10; i < history.size(); i++) {
            recentVolume += toDouble(history.get(i).get("volume"));
        }
        recentVolume /= 10;
        double volumeRatio = recentVolume / avgVolume;

        if (volumeRatio >= 2.0) {
            score += 30;
            bullish.add(String.format("Strong volume surge: %.1fx average", volumeRatio));
        } else if (volumeRatio >= config.getVolumeSurgeMultiplier()) {
            score += 20;
            bullish.add(String.format("Volume accumulation: %.1fx average", volumeRatio));
        } else if (volumeRatio >= 1.0) {
            score += 10;
        } else {
            bearish.add(String.format("Declining volume: %.1fx average", volumeRatio));
        }

        // 2. Volume-price trend: up days with higher volume — 35 pts
        int upDaysHighVol = 0;
        int downDaysHighVol = 0;
        int totalDays = Math.min(20, history.size() - 1);
        for (int i = history.size() - totalDays; i < history.size(); i++) {
            double close = toDouble(history.get(i).get("close"));
            double open = toDouble(history.get(i).get("open"));
            double vol = toDouble(history.get(i).get("volume"));
            if (close > open && vol > avgVolume) {
                upDaysHighVol++;
            } else if (close < open && vol > avgVolume) {
                downDaysHighVol++;
            }
        }

        if (upDaysHighVol > downDaysHighVol * 2) {
            score += 35;
            bullish.add("Smart money accumulation pattern (up days with high volume)");
        } else if (upDaysHighVol > downDaysHighVol) {
            score += 20;
        } else if (downDaysHighVol > upDaysHighVol) {
            bearish.add("Distribution pattern (down days with high volume)");
        }

        // 3. Increasing volume trend over weeks — 35 pts
        if (history.size() >= 40) {
            double olderAvgVol = 0;
            for (int i = history.size() - 40; i < history.size() - 20; i++) {
                olderAvgVol += toDouble(history.get(i).get("volume"));
            }
            olderAvgVol /= 20;

            double newerAvgVol = 0;
            for (int i = history.size() - 20; i < history.size(); i++) {
                newerAvgVol += toDouble(history.get(i).get("volume"));
            }
            newerAvgVol /= 20;

            if (olderAvgVol > 0) {
                double volGrowth = (newerAvgVol - olderAvgVol) / olderAvgVol * 100;
                if (volGrowth > 50) {
                    score += 35;
                    bullish.add(String.format("Volume trend strongly rising: +%.0f%%", volGrowth));
                } else if (volGrowth > 20) {
                    score += 20;
                } else if (volGrowth > 0) {
                    score += 10;
                }
            }
        }

        return Math.min(100, score);
    }

    // ============================================================
    // Dimension 3: Relative Strength vs Nifty 50 (0-100)
    // ============================================================

    // Package-private for characterisation tests (SPEC §25.6: the scoring engine was untested).
    int scoreRelativeStrength(List<Map<String, Object>> stockHistory,
                                      List<Map<String, Object>> niftyHistory,
                                      List<String> bullish, List<String> bearish) {
        int score = 0;
        if (niftyHistory == null || niftyHistory.size() < 50 || stockHistory.size() < 50) return 40;

        int lookbackDays = Math.min(Math.min(stockHistory.size(), niftyHistory.size()), 250);

        // Calculate returns over multiple periods
        double stockReturn3M = calculateReturn(stockHistory, 63);
        double niftyReturn3M = calculateReturn(niftyHistory, 63);
        double stockReturn6M = calculateReturn(stockHistory, 126);
        double niftyReturn6M = calculateReturn(niftyHistory, 126);
        double stockReturn1Y = calculateReturn(stockHistory, Math.min(lookbackDays, 250));
        double niftyReturn1Y = calculateReturn(niftyHistory, Math.min(lookbackDays, 250));

        // 1. 3-month relative strength — 30 pts
        double rs3M = stockReturn3M - niftyReturn3M;
        if (rs3M > 15) {
            score += 30;
            bullish.add(String.format("3M RS: stock +%.1f%% vs Nifty +%.1f%% (outperform %.1f%%)", stockReturn3M, niftyReturn3M, rs3M));
        } else if (rs3M > 5) {
            score += 20;
        } else if (rs3M > 0) {
            score += 10;
        } else {
            bearish.add(String.format("3M underperforming Nifty by %.1f%%", Math.abs(rs3M)));
        }

        // 2. 6-month relative strength — 35 pts
        double rs6M = stockReturn6M - niftyReturn6M;
        if (rs6M > 20) {
            score += 35;
            bullish.add(String.format("6M strong outperformance: +%.1f%% vs Nifty", rs6M));
        } else if (rs6M > 10) {
            score += 25;
        } else if (rs6M > 0) {
            score += 12;
        }

        // 3. 1-year relative strength — 35 pts
        double rs1Y = stockReturn1Y - niftyReturn1Y;
        if (rs1Y > 30) {
            score += 35;
            bullish.add(String.format("1Y strong outperformance: +%.1f%% vs Nifty", rs1Y));
        } else if (rs1Y > 15) {
            score += 25;
        } else if (rs1Y > 0) {
            score += 12;
        } else {
            bearish.add(String.format("1Y underperforming Nifty by %.1f%%", Math.abs(rs1Y)));
        }

        return Math.min(100, score);
    }

    // ============================================================
    // Dimension 4: Price Structure (0-100)
    // ============================================================

    // Package-private for characterisation tests (SPEC §25.6: the scoring engine was untested).
    int scorePriceStructure(List<Map<String, Object>> history, double currentPrice,
                                    List<String> bullish, List<String> bearish) {
        int score = 0;
        if (history.size() < 50) return 30;

        // Find 52-week high and low
        int lookback = Math.min(history.size(), 252);
        double high52w = Double.MIN_VALUE;
        double low52w = Double.MAX_VALUE;
        for (int i = history.size() - lookback; i < history.size(); i++) {
            double high = toDouble(history.get(i).get("high"));
            double low = toDouble(history.get(i).get("low"));
            if (high > high52w) high52w = high;
            if (low < low52w) low52w = low;
        }

        // 1. Proximity to 52-week high — 25 pts (near high = strong momentum)
        double pctFromHigh = (high52w - currentPrice) / high52w * 100;
        if (pctFromHigh <= 5) {
            score += 25;
            bullish.add(String.format("Within %.1f%% of 52-week high (strong momentum)", pctFromHigh));
        } else if (pctFromHigh <= config.getNearHighPercent()) {
            score += 18;
            bullish.add(String.format("Within %.1f%% of 52-week high", pctFromHigh));
        } else if (pctFromHigh <= 20) {
            score += 10;
        } else {
            bearish.add(String.format("%.1f%% below 52-week high", pctFromHigh));
        }

        // 2. Distance from 52-week low — 25 pts (far from low = recovery)
        double pctFromLow = (currentPrice - low52w) / low52w * 100;
        if (pctFromLow >= 100) {
            score += 25;
            bullish.add(String.format("Price doubled from 52-week low (+%.0f%%)", pctFromLow));
        } else if (pctFromLow >= 50) {
            score += 20;
        } else if (pctFromLow >= 20) {
            score += 12;
        } else {
            score += 5;
        }

        // 3. Higher lows pattern (last 3 months) — 25 pts
        int higherLows = countHigherLows(history, 63);
        if (higherLows >= 4) {
            score += 25;
            bullish.add("Strong higher-lows pattern (consistent uptrend structure)");
        } else if (higherLows >= 2) {
            score += 15;
        } else {
            bearish.add("No clear higher-lows pattern");
        }

        // 4. Base building detection (tight consolidation before breakout) — 25 pts
        boolean isBaseBuilding = detectBasePattern(history);
        if (isBaseBuilding) {
            score += 25;
            bullish.add("Base building pattern detected (potential breakout setup)");
        }

        return Math.min(100, score);
    }

    // ============================================================
    // Dimension 5: Valuation (0-100)
    // ============================================================

    // Package-private for characterisation tests (SPEC §25.6: the scoring engine was untested).
    /**
     * Continuous RSI positioning score, 0..30, peaking in the middle of the sweet spot.
     *
     * <p>Replaces a four-bucket step function that gave a flat +30 to everything between
     * 50 and 75. A stock at RSI 51 and one at 62 are not equally well-positioned, and
     * flattening them was a major contributor to the dimension's saturation.
     *
     * <p>Shape: peak 30 at the sweet-spot midpoint, tapering to 20 at each edge; a gentle
     * ramp up from 40; and a decay above the overbought line rather than a cliff.
     */
    private int scoreRsiPosition(double rsi, List<String> bullish, List<String> bearish) {
        double lo = config.getMinRsiForUptrend();   // 50
        double hi = config.getMaxRsiForEntry();     // 75
        double mid = (lo + hi) / 2.0;

        if (rsi >= lo && rsi <= hi) {
            // 30 at the midpoint, 20 at either edge.
            double distanceFromMid = Math.abs(rsi - mid) / ((hi - lo) / 2.0); // 0..1
            bullish.add(String.format("Weekly RSI in sweet spot: %.1f (uptrend, not overbought)", rsi));
            return (int) Math.round(30 - 10 * distanceFromMid);
        }
        if (rsi >= 40 && rsi < lo) {
            // Ramp 10 -> 20 across the neutral zone.
            return (int) Math.round(10 + 10 * ((rsi - 40) / (lo - 40)));
        }
        if (rsi > hi) {
            // Decay 20 -> 0 between the overbought line and 90; the further past, the worse.
            bearish.add(String.format("Weekly RSI overbought: %.1f", rsi));
            return (int) Math.round(Math.max(0, 20 * (1 - (rsi - hi) / (90 - hi))));
        }
        // Below 40 — weak. Ramp 0 -> 10 across 30..40.
        bearish.add(String.format("Weekly RSI weak: %.1f", rsi));
        return (int) Math.round(Math.max(0, 10 * ((rsi - 30) / 10.0)));
    }

    /**
     * Weighted mean over the dimensions that returned a score, renormalised by the weight
     * actually present.
     *
     * <p>Takes alternating (score, weight) pairs; a null score drops that dimension and its
     * weight from both the numerator and the denominator. This is the fix for the
     * missing-data asymmetry: previously an unmeasurable dimension scored a neutral 50,
     * which sat <i>above</i> what a measured-but-weak dimension scores (Financial Quality
     * bottoms out at 24, Institutional Interest at 10). The engine therefore rewarded stocks
     * it knew nothing about over stocks it had successfully analysed and found wanting —
     * adverse selection, and under B-018 it applied to the entire universe at once.
     *
     * <p>Renormalising instead says the honest thing: "of what we could measure, here is the
     * score." A stock measured on 6 of 8 dimensions is scored on those 6.
     *
     * <p>If nothing at all is measurable the result is 0 — such a stock should rank last,
     * not middling.
     */
    private int weightedComposite(Object... scoreWeightPairs) {
        double weighted = 0;
        double availableWeight = 0;

        for (int i = 0; i < scoreWeightPairs.length; i += 2) {
            Integer score = (Integer) scoreWeightPairs[i];
            double weight = (Double) scoreWeightPairs[i + 1];
            if (score == null) continue;
            weighted += score * weight;
            availableWeight += weight;
        }

        if (availableWeight <= 0) return 0;
        return (int) Math.round(weighted / availableWeight);
    }

    /**
     * @return 0-100, or {@code null} when this stock's valuation is genuinely unmeasurable.
     *         Null means "exclude from the composite and renormalise", NOT "average" — see
     *         {@link #weightedComposite}. Returning a neutral 50 here is what let B-018 hide:
     *         a dimension pinned at a constant still contributed 13% of every score while
     *         adding zero cross-sectional information, and reported IC=null, which is
     *         indistinguishable from "not enough data yet".
     */
    Integer scoreValuation(ValuationData valuation, double currentPrice,
                               List<String> bullish, List<String> bearish) {
        int score = 50; // Baseline for a stock we CAN measure and find unremarkable.

        // No object, or an object with nothing measurable on it (the shape
        // StockValuationService returns when it falls back to sector-PE only).
        if (valuation == null
                || (valuation.getPeDeviation() == null
                    && valuation.getMarketCap() == null
                    && valuation.getStockPe() == null)) {
            return null;
        }

        // 1. PE deviation from sector — 40 pts
        Double peDeviation = valuation.getPeDeviation();
        if (peDeviation != null) {
            if (peDeviation < config.getUndervaluedPeThreshold()) {
                score += 30;
                bullish.add(String.format("Undervalued: PE %.0f%% below sector average", Math.abs(peDeviation)));
            } else if (peDeviation < 0) {
                score += 15;
                bullish.add(String.format("Slightly undervalued: PE %.0f%% below sector", Math.abs(peDeviation)));
            } else if (peDeviation > config.getOvervaluedPePenalty()) {
                score -= 20;
                bearish.add(String.format("Overvalued: PE %.0f%% above sector average", peDeviation));
            } else if (peDeviation > 15) {
                score -= 10;
                bearish.add(String.format("Premium valuation: PE %.0f%% above sector", peDeviation));
            }
        }

        // 2. Market cap bonus for growth potential — 30 pts
        Double marketCap = valuation.getMarketCap();
        if (marketCap != null) {
            if (marketCap <= config.getSmallCapMax()) {
                score += 20;
            } else if (marketCap <= config.getMidCapMax()) {
                score += 10;
            }
            // Large caps get no bonus (already priced in)
        }

        // 3. Low PE (absolute) = growth at reasonable price — 20 pts
        Double stockPe = valuation.getStockPe();
        if (stockPe != null) {
            if (stockPe > 0 && stockPe <= 15) {
                score += 20;
                bullish.add(String.format("Low PE of %.1f (value opportunity)", stockPe));
            } else if (stockPe > 0 && stockPe <= 25) {
                score += 10;
            } else if (stockPe > 50) {
                score -= 10;
                bearish.add(String.format("High PE of %.1f", stockPe));
            }
        }

        return Math.max(0, Math.min(100, score));
    }

    // ============================================================
    // Dimension 6: Institutional Interest (0-100)
    // ============================================================

    /**
     * Per-stock institutional accumulation — pivoted 2026-04-19 from sector-level
     * FII/DII flow (which was zero-variance due to sparse bulk-deal data + sector
     * taxonomy mismatch — see SPEC §23.2 per-dimension IC finding). Uses
     * {@link NseDataService.ShareholdingHistory}'s per-stock quarterly FII and DII
     * change (which we already fetch reliably for the Insider Activity bonus).
     *
     * Sector flow is retained as a small tilt (up to ±5) when data is available.
     * Promoter pledge is deliberately NOT part of this dimension — that signal
     * belongs to the Insider Activity bonus to avoid double-counting.
     */
    // Package-private for characterisation tests (SPEC §25.6: the scoring engine was untested).
    int scoreInstitutionalInterest(String symbol,
                                           String sector,
                                           Map<String, Double> sectorFlows,
                                           NseDataService.ShareholdingHistory shHistory,
                                           List<String> bullish,
                                           List<String> bearish) {
        int score = 50; // true-midpoint neutral default

        // === Primary signal: per-stock FII holding trend ===
        if (shHistory != null && shHistory.getFiiChange() != null) {
            double fiiChange = shHistory.getFiiChange();
            if (fiiChange > 2.0) {
                score += 25;
                bullish.add(String.format("FII strongly accumulating (+%.2fpp)", fiiChange));
            } else if (fiiChange > 0.5) {
                score += 15;
                bullish.add(String.format("FII increasing (+%.2fpp)", fiiChange));
            } else if (fiiChange > 0.0) {
                score += 5;
            } else if (fiiChange < -2.0) {
                score -= 25;
                bearish.add(String.format("FII heavily reducing (%.2fpp)", fiiChange));
            } else if (fiiChange < -0.5) {
                score -= 15;
                bearish.add(String.format("FII reducing (%.2fpp)", fiiChange));
            } else {
                score -= 5;
            }
        }

        // === Secondary signal: per-stock DII holding trend ===
        if (shHistory != null && shHistory.getDiiChange() != null) {
            double diiChange = shHistory.getDiiChange();
            if (diiChange > 0.5) {
                score += 10;
                bullish.add(String.format("DII accumulating (+%.2fpp)", diiChange));
            } else if (diiChange > 0.0) {
                score += 3;
            } else if (diiChange < -0.5) {
                score -= 10;
                bearish.add(String.format("DII reducing (%.2fpp)", diiChange));
            }
        }

        // === Confluence bonus: both institutions on the same side ===
        if (shHistory != null && shHistory.getFiiChange() != null && shHistory.getDiiChange() != null) {
            if (shHistory.getFiiChange() > 0 && shHistory.getDiiChange() > 0) {
                score += 5;
                bullish.add("Both FII and DII buying (confluence)");
            } else if (shHistory.getFiiChange() < 0 && shHistory.getDiiChange() < 0) {
                score -= 5;
                bearish.add("Both FII and DII selling (confluence)");
            }
        }

        // === Tilt: sector-flow context (up to ±5, only when data is available) ===
        if (sector != null && sectorFlows != null) {
            Double sectorFlow = sectorFlows.get(sector);
            if (sectorFlow != null) {
                if (sectorFlow > 500) score += 5;
                else if (sectorFlow < -500) score -= 5;
            }
        }

        return Math.max(0, Math.min(100, score));
    }

    // ============================================================
    // Technical Helper Methods
    // ============================================================

    private double calculateEMA(List<Map<String, Object>> data, int period) {
        if (data.size() < period) return toDouble(data.get(data.size() - 1).get("close"));

        double multiplier = 2.0 / (period + 1);
        double ema = 0;

        // Start with SMA for first 'period' values
        for (int i = 0; i < period; i++) {
            ema += toDouble(data.get(i).get("close"));
        }
        ema /= period;

        // Calculate EMA for remaining values
        for (int i = period; i < data.size(); i++) {
            double close = toDouble(data.get(i).get("close"));
            ema = (close - ema) * multiplier + ema;
        }

        return ema;
    }

    /**
     * RSI, or null when there are not enough bars to compute one (B-060).
     *
     * <p>Prefer this over {@link #calculateRSI} anywhere the result is persisted or displayed. The
     * primitive version's 50.0 fallback is indistinguishable from a genuine neutral reading, so a
     * stock that could not be measured looks exactly like one that was measured and found calm.
     */
    /**
     * Lowest low over the last {@code period} candles - the level the stock has actually traded
     * down to recently, used as immediate support. Null when the history is shorter than the
     * window: a "20-day low" computed from 6 days is a different statistic wearing the same name.
     */
    static Double lowestLow(List<Map<String, Object>> data, int period) {
        if (data == null || data.size() < period) return null;
        double low = Double.MAX_VALUE;
        for (int i = data.size() - period; i < data.size(); i++) {
            double l = toDouble(data.get(i).get("low"));
            if (l > 0 && l < low) low = l;
        }
        return low == Double.MAX_VALUE ? null : low;
    }

    /** ATR over {@code period} candles, or null when there are not enough to compute one. */
    static Double calculateAtrOrNull(List<Map<String, Object>> data, int period) {
        if (data == null || data.size() < period + 1) return null;
        double sum = 0;
        int n = 0;
        for (int i = data.size() - period; i < data.size(); i++) {
            double high = toDouble(data.get(i).get("high"));
            double low = toDouble(data.get(i).get("low"));
            double prevClose = toDouble(data.get(i - 1).get("close"));
            if (high <= 0 || low <= 0 || prevClose <= 0) continue;
            double tr = Math.max(high - low,
                    Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sum += tr;
            n++;
        }
        return n == 0 ? null : sum / n;
    }

    static Double calculateRsiOrNull(List<Map<String, Object>> data, int period) {
        if (data == null || data.size() < period + 1) return null;
        return calculateRSI(data, period);
    }

    static double calculateRSI(List<Map<String, Object>> data, int period) {
        if (data.size() < period + 1) return 50.0; // Neutral default — see calculateRsiOrNull

        double avgGain = 0;
        double avgLoss = 0;

        // Calculate initial average gain/loss
        for (int i = 1; i <= period; i++) {
            double change = toDouble(data.get(i).get("close")) - toDouble(data.get(i - 1).get("close"));
            if (change >= 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;

        // Smooth using Wilder's method
        for (int i = period + 1; i < data.size(); i++) {
            double change = toDouble(data.get(i).get("close")) - toDouble(data.get(i - 1).get("close"));
            if (change >= 0) {
                avgGain = (avgGain * (period - 1) + change) / period;
                avgLoss = (avgLoss * (period - 1)) / period;
            } else {
                avgGain = (avgGain * (period - 1)) / period;
                avgLoss = (avgLoss * (period - 1) + Math.abs(change)) / period;
            }
        }

        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    private double calculateReturn(List<Map<String, Object>> data, int days) {
        if (data.size() < days) return 0;
        double currentClose = toDouble(data.get(data.size() - 1).get("close"));
        double pastClose = toDouble(data.get(data.size() - days).get("close"));
        if (pastClose <= 0) return 0;
        return (currentClose - pastClose) / pastClose * 100;
    }

    private int countHigherLows(List<Map<String, Object>> history, int lookbackDays) {
        int count = 0;
        int startIdx = Math.max(0, history.size() - lookbackDays);
        int weekSize = 5;

        double prevWeekLow = Double.MAX_VALUE;
        for (int w = 0; w < (lookbackDays / weekSize); w++) {
            int from = startIdx + w * weekSize;
            int to = Math.min(from + weekSize, history.size());
            if (from >= history.size()) break;

            double weekLow = Double.MAX_VALUE;
            for (int i = from; i < to; i++) {
                double low = toDouble(history.get(i).get("low"));
                if (low < weekLow) weekLow = low;
            }

            if (weekLow > prevWeekLow) count++;
            prevWeekLow = weekLow;
        }
        return count;
    }

    private boolean detectBasePattern(List<Map<String, Object>> history) {
        if (history.size() < 40) return false;

        // Look at last 40 days for consolidation (tight range)
        int baseStart = history.size() - 40;
        int baseEnd = history.size() - 5; // Exclude last week (potential breakout)

        double baseHigh = Double.MIN_VALUE;
        double baseLow = Double.MAX_VALUE;
        for (int i = baseStart; i < baseEnd; i++) {
            double high = toDouble(history.get(i).get("high"));
            double low = toDouble(history.get(i).get("low"));
            if (high > baseHigh) baseHigh = high;
            if (low < baseLow) baseLow = low;
        }

        double baseRange = (baseHigh - baseLow) / baseLow * 100;

        // Base pattern: range < 15% over ~7 weeks and current price near base high
        double currentPrice = toDouble(history.get(history.size() - 1).get("close"));
        double pctFromBaseHigh = (baseHigh - currentPrice) / baseHigh * 100;

        return baseRange < 15 && pctFromBaseHigh < 3;
    }

    private List<Map<String, Object>> aggregateToWeekly(List<Map<String, Object>> dailyData) {
        List<Map<String, Object>> weekly = new ArrayList<>();
        for (int i = 0; i < dailyData.size(); i += 5) {
            int end = Math.min(i + 5, dailyData.size());
            double open = toDouble(dailyData.get(i).get("open"));
            double close = toDouble(dailyData.get(end - 1).get("close"));
            double high = Double.MIN_VALUE;
            double low = Double.MAX_VALUE;
            double volume = 0;

            for (int j = i; j < end; j++) {
                double h = toDouble(dailyData.get(j).get("high"));
                double l = toDouble(dailyData.get(j).get("low"));
                if (h > high) high = h;
                if (l < low) low = l;
                volume += toDouble(dailyData.get(j).get("volume"));
            }

            Map<String, Object> bar = new HashMap<>();
            bar.put("open", open);
            bar.put("high", high);
            bar.put("low", low);
            bar.put("close", close);
            bar.put("volume", volume);
            weekly.add(bar);
        }
        return weekly;
    }

    private List<Map<String, Object>> aggregateToMonthly(List<Map<String, Object>> dailyData) {
        List<Map<String, Object>> monthly = new ArrayList<>();
        for (int i = 0; i < dailyData.size(); i += 22) {
            int end = Math.min(i + 22, dailyData.size());
            double open = toDouble(dailyData.get(i).get("open"));
            double close = toDouble(dailyData.get(end - 1).get("close"));
            double high = Double.MIN_VALUE;
            double low = Double.MAX_VALUE;
            double volume = 0;

            for (int j = i; j < end; j++) {
                double h = toDouble(dailyData.get(j).get("high"));
                double l = toDouble(dailyData.get(j).get("low"));
                if (h > high) high = h;
                if (l < low) low = l;
                volume += toDouble(dailyData.get(j).get("volume"));
            }

            Map<String, Object> bar = new HashMap<>();
            bar.put("open", open);
            bar.put("high", high);
            bar.put("low", low);
            bar.put("close", close);
            bar.put("volume", volume);
            monthly.add(bar);
        }
        return monthly;
    }

    /**
     * Calculate key price metrics:
     * [0] = relative strength vs nifty (3M return ratio)
     * [1] = % below 52-week high
     * [2] = % above 52-week low
     * [3] = recent volume ratio
     * [4] = weekly EMA slope
     */
    private double[] calculatePriceMetrics(List<Map<String, Object>> history, double currentPrice) {
        double[] metrics = new double[5];

        if (history.size() < 50) return metrics;

        // 52-week high/low
        int lookback = Math.min(history.size(), 252);
        double high52w = Double.MIN_VALUE;
        double low52w = Double.MAX_VALUE;
        for (int i = history.size() - lookback; i < history.size(); i++) {
            double h = toDouble(history.get(i).get("high"));
            double l = toDouble(history.get(i).get("low"));
            if (h > high52w) high52w = h;
            if (l < low52w) low52w = l;
        }

        metrics[0] = calculateReturn(history, Math.min(63, history.size() - 1)); // 3M return as RS proxy
        metrics[1] = (high52w - currentPrice) / high52w * 100;
        metrics[2] = (currentPrice - low52w) / low52w * 100;

        // Volume ratio
        double avgVol = 0;
        int volDays = Math.min(60, history.size());
        for (int i = history.size() - volDays; i < history.size(); i++) {
            avgVol += toDouble(history.get(i).get("volume"));
        }
        avgVol /= volDays;
        double recentVol = 0;
        for (int i = history.size() - Math.min(10, history.size()); i < history.size(); i++) {
            recentVol += toDouble(history.get(i).get("volume"));
        }
        recentVol /= Math.min(10, history.size());
        metrics[3] = avgVol > 0 ? recentVol / avgVol : 1.0;

        // Weekly EMA slope
        List<Map<String, Object>> weekly = aggregateToWeekly(history);
        if (weekly.size() >= 15) {
            double emaRecent = calculateEMA(weekly, 12);
            List<Map<String, Object>> olderWeekly = weekly.subList(0, weekly.size() - 4);
            double emaOlder = calculateEMA(olderWeekly, 12);
            metrics[4] = emaOlder > 0 ? (emaRecent - emaOlder) / emaOlder * 100 : 0;
        }

        return metrics;
    }

    // ============================================================
    // Data Fetching
    // ============================================================

    private List<Map<String, Object>> fetchDailyHistory(String symbol) {
        try {
            LocalDateTime now = LocalDateTime.now(IST);
            LocalDateTime from = now.minusDays(config.getHistoryDays());
            String fromStr = from.format(DATE_FMT);
            String toStr = now.format(DATE_FMT);
            return brokerClient.getHistoricalData(symbol, "day", fromStr, toStr);
        } catch (Exception e) {
            log.debug("Failed to fetch daily history for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private Map<String, Double> fetchSectorFlows() {
        Map<String, Double> flows = new HashMap<>();
        try {
            List<SectorFlow> sectorFlows = sectorAnalysisService.analyzeSectorFlows(LocalDate.now());
            if (sectorFlows != null) {
                for (SectorFlow sf : sectorFlows) {
                    flows.put(sf.getSector(), sf.getTotalNetFlow());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to fetch sector flows: {}", e.getMessage());
        }
        return flows;
    }

    // ============================================================
    // Persistence
    // ============================================================

    private void persistScores(List<MultibaggerScore> scores) {
        LocalDate today = LocalDate.now();
        // Resolved once per call, not per stock: every row of a run must carry the same
        // provenance stamp, and a config reload mid-run would otherwise split it (SPEC §38.1).
        String scoringVersion = scoringVersionRegistry.forMultibagger();
        int saved = 0;
        int failedToPersist = 0;
        String firstPersistError = null;
        for (MultibaggerScore score : scores) {
            try {
                MultibaggerScoreEntity entity = MultibaggerScoreEntity.builder()
                        .symbol(score.getSymbol())
                        .tradingSymbol(score.getTradingSymbol())
                        .industry(score.getIndustry())
                        .screeningDate(today)
                        .currentPrice(score.getCurrentPrice())
                        .marketCapCrores(score.getMarketCapCrores())
                        .marketCapCategory(score.getMarketCapCategory())
                        .technicalMomentumScore(score.getTechnicalMomentumScore())
                        .volumeAccumulationScore(score.getVolumeAccumulationScore())
                        .relativeStrengthScore(score.getRelativeStrengthScore())
                        .priceStructureScore(score.getPriceStructureScore())
                        .valuationScore(score.getValuationScore())
                        .institutionalInterestScore(score.getInstitutionalInterestScore())
                        .sectorTailwindScore(score.getSectorTailwindScore())
                        .financialQualityScore(score.getFinancialQualityScore())
                        .financialQualityVerdict(score.getFinancialQualityVerdict())
                        .interestCoverage(score.getInterestCoverage())
                        .ocfToProfitRatio(score.getOcfToProfitRatio())
                        .promoterPledgePercent(score.getPromoterPledgePercent())
                        .dcfVerdict(score.getDcfVerdict())
                        .dcfImpliedGrowthPercent(score.getDcfImpliedGrowthPercent())
                        .dcfHistoricalGrowthPercent(score.getDcfHistoricalGrowthPercent())
                        .dcfExpectationGapPercent(score.getDcfExpectationGapPercent())
                        .grossMarginPercent(score.getGrossMarginPercent())
                        .grossMarginTrend(score.getGrossMarginTrend())
                        .pegRatio(score.getPegRatio())
                        .deliveryPercent(score.getDeliveryPercent())
                        .earningsConsistencyScore(score.getEarningsConsistencyScore())
                        .rocePercent(score.getRocePercent())
                        .roePercent(score.getRoePercent())
                        .roaPercent(score.getRoaPercent())
                        .debtToEquity(score.getDebtToEquity())
                        .cashConversionRatio(score.getCashConversionRatio())
                        .capitalEfficiencyVerdict(score.getCapitalEfficiencyVerdict())
                        .cwipIntensityPercent(score.getCwipIntensityPercent())
                        .capexToDepreciation(score.getCapexToDepreciation())
                        .capexVerdict(score.getCapexVerdict())
                        .capexScore(score.getCapexScore())
                        .turnaroundVerdict(score.getTurnaroundVerdict())
                        .forensicFlags(score.getForensicFlags())
                        .liquidityAdv20d(score.getLiquidityAdv20d())
                        .liquidityTier(score.getLiquidityTier())
                        .circuitDaysLast60(score.getCircuitDaysLast60())
                        .underDiscoveryScore(score.getUnderDiscoveryScore())
                        .insiderPulseVerdict(score.getInsiderPulseVerdict())
                        .insiderNetBuy90dPct(score.getInsiderNetBuy90dPct())
                        .insiderPulseScore(score.getInsiderPulseScore())
                        // Growth + ownership (SPEC §12.5, 2026-09-09) — previously computed and dropped.
                        .earningsGrowthVerdict(score.getEarningsGrowthVerdict())
                        .yoyRevenueGrowth(score.getYoyRevenueGrowth())
                        .yoyProfitGrowth(score.getYoyProfitGrowth())
                        .promoterHoldingPct(score.getPromoterHoldingPct())
                        .promoterHoldingChangePct(score.getPromoterHoldingChange())
                        .fiiHoldingPct(score.getFiiHoldingPct())
                        .diiHoldingPct(score.getDiiHoldingPct())
                        .scoringVersion(scoringVersion)
                        .compositeScore(score.getCompositeScore())
                        .percentileRank(score.getPercentileRank())
                        .grade(score.getGrade())
                        .verdict(score.getVerdict())
                        .weeklyRsi(score.getWeeklyRsi())
                        .monthlyRsi(score.getMonthlyRsi())
                        .support20d(score.getSupport20d())
                        .atr14(score.getAtr14())
                        .ema50(score.getEma50())
                        .relativeStrengthVsNifty(score.getRelativeStrengthVsNifty())
                        .peDeviation(score.getPeDeviation())
                        .priceVs52WeekHigh(score.getPriceVs52WeekHigh())
                        .priceVs52WeekLow(score.getPriceVs52WeekLow())
                        .avgVolumeRatio(score.getAvgVolumeRatio())
                        .weeklyEmaSlope(score.getWeeklyEmaSlope())
                        .bullishFactors(String.join("|", score.getBullishFactors()))
                        .bearishFactors(String.join("|", score.getBearishFactors()))
                        .inHoldings(score.isInHoldings())
                        .holdingsPnlPercent(score.getHoldingsPnlPercent())
                        .build();

                // Upsert: delete existing for same symbol+date, then save
                scoreRepository.findBySymbolAndScreeningDate(score.getSymbol(), today)
                        .ifPresent(existing -> scoreRepository.delete(existing));
                scoreRepository.save(entity);
                saved++;

                // SPEC.md §23: record picks crossing the recommendation threshold. Gated on
                // percentile as well as the absolute score so the accuracy-tracked pick set
                // stays a consistent slice of the universe — an absolute-only gate captured
                // 62% of all screened stocks as "recommendations", which is why MULTIBAGGER
                // shows 8,261 outcomes at 30d against QUANT_DISCOVERY's 66.
                if (score.getCompositeScore() >= RECOMMENDATION_THRESHOLD && isRecommendable(score)) {
                    recommendationTracker.record(
                            RecommendationEntity.Source.MULTIBAGGER,
                            score.getSymbol(),
                            score.getCurrentPrice(),
                            score.getCompositeScore(),
                            score.getGrade(),
                            score.getVerdict(),
                            null,
                            null,
                            score.getIndustry(),
                            score.getMarketCapCategory());
                }
            } catch (Exception e) {
                failedToPersist++;
                if (firstPersistError == null) firstPersistError = e.getMessage();
                log.debug("Failed to persist score for {}: {}", score.getSymbol(), e.getMessage());
            }
        }
        // A persistence shortfall is a data-loss event, not a detail: trend tracking,
        // per-dimension IC and the §23 accuracy loop all read this table. It was previously
        // logged only at DEBUG, so a run that saved 78 of 288 rows reported success.
        if (failedToPersist > 0) {
            log.error("Multibagger Screening: PERSISTED ONLY {} of {} scores — {} rows were rejected. "
                    + "First error: {}. Trend tracking and per-dimension IC will have gaps for this run.",
                    saved, saved + failedToPersist, failedToPersist, firstPersistError);
        } else {
            log.info("Multibagger Screening: Persisted {} scores to database", saved);
        }
    }

    // ============================================================
    // Utilities
    // ============================================================

    /** Map a reverse-DCF verdict to a 0–100 score component that can blend with PE-based scoring. */
    private static Integer scoreFromDcfVerdict(com.example.trading.ai.IntrinsicValuationService.ReverseDcfResult dcf) {
        if (dcf == null || dcf.getVerdict() == null) return null;
        return switch (dcf.getVerdict()) {
            case "DEEPLY_UNDERVALUED" -> 95;
            case "UNDERVALUED" -> 80;
            case "FAIRLY_VALUED" -> 50;
            case "EXPENSIVE" -> 25;
            case "EXTREMELY_EXPENSIVE" -> 5;
            default -> null; // NOT_APPLICABLE / INSUFFICIENT_DATA → no signal; fall back to PE
        };
    }

    private static String humanizeAnalystVerdict(String v) {
        if (v == null) return "Unknown";
        return switch (v) {
            case "STRONG_POSITIVE" -> "Strong Positive";
            case "POSITIVE" -> "Positive";
            case "NEUTRAL" -> "Neutral";
            case "NEGATIVE" -> "Negative";
            case "STRONG_NEGATIVE" -> "Strong Negative";
            default -> v;
        };
    }

    private static String humanizeDcfVerdict(String v) {
        if (v == null) return "Unknown";
        return switch (v) {
            case "DEEPLY_UNDERVALUED" -> "Deeply Undervalued";
            case "UNDERVALUED" -> "Undervalued";
            case "FAIRLY_VALUED" -> "Fairly Valued";
            case "EXPENSIVE" -> "Expensive";
            case "EXTREMELY_EXPENSIVE" -> "Extremely Expensive";
            case "NOT_APPLICABLE" -> "Not Applicable";
            case "INSUFFICIENT_DATA" -> "Insufficient Data";
            default -> v;
        };
    }

    /**
     * Score only the price/volume dimensions for an arbitrary history window.
     *
     * <p>Exists for the retro-backtest (SPEC §33), which reconstructs scores as-of a past
     * date. These four are the only dimensions that can be rebuilt historically — everything
     * else needs point-in-time fundamentals, which do not exist before ~Mar-2025.
     *
     * <p>A public façade rather than opening up the individual scorers: they are
     * package-private so the characterisation tests can pin them without making the
     * per-dimension internals part of the service's contract.
     */
    public Map<String, Integer> scoreTechnicalDimensions(List<Map<String, Object>> history,
                                                         List<Map<String, Object>> niftyHistory,
                                                         double asOfPrice) {
        List<String> bull = new ArrayList<>();
        List<String> bear = new ArrayList<>();
        Map<String, Integer> dims = new LinkedHashMap<>();
        dims.put("Technical Momentum", scoreTechnicalMomentum(history, asOfPrice, bull, bear));
        dims.put("Volume Accumulation", scoreVolumeAccumulation(history, bull, bear));
        dims.put("Relative Strength", scoreRelativeStrength(history, niftyHistory, bull, bear));
        dims.put("Price Structure", scorePriceStructure(history, asOfPrice, bull, bear));
        return dims;
    }

    /** A dimension whose spread falls below this is not ranking anything. */
    static final double MIN_HEALTHY_DIMENSION_STDDEV =
            com.example.trading.learning.ScreeningCoverage.MIN_HEALTHY_STDDEV;

    /**
     * Log the cross-sectional spread of every scoring dimension, and raise an ERROR for any
     * that has collapsed.
     *
     * <p>This exists because of a specific, expensive failure: the Institutional Interest
     * dimension scored <b>every stock exactly 40</b> for over three months. Nothing broke,
     * no exception was thrown, and 10% of the composite was silently dead weight until a
     * per-dimension IC run happened to surface the zero variance. A dimension that cannot
     * separate the universe is not contributing information, whatever its weight says.
     *
     * <p>Reported as ERROR rather than a metric to eyeball: the previous version of this
     * check was "someone will notice", and nobody did.
     */
    void logDimensionVariance(List<MultibaggerScore> scores) {
        if (scores == null || scores.size() < 10) return;   // too few to judge spread

        record Dim(String name, java.util.function.Function<MultibaggerScore, Integer> get) {}
        List<Dim> dims = List.of(
                new Dim("TechnicalMomentum", s -> s.getTechnicalMomentumScore()),
                new Dim("VolumeAccumulation", s -> s.getVolumeAccumulationScore()),
                new Dim("RelativeStrength", s -> s.getRelativeStrengthScore()),
                new Dim("PriceStructure", s -> s.getPriceStructureScore()),
                new Dim("Valuation", MultibaggerScore::getValuationScore),
                new Dim("InstitutionalInterest", MultibaggerScore::getInstitutionalInterestScore),
                new Dim("FinancialQuality", MultibaggerScore::getFinancialQualityScore),
                new Dim("UnderDiscovery", MultibaggerScore::getUnderDiscoveryScore),
                new Dim("InsiderPulse", MultibaggerScore::getInsiderPulseScore));

        StringBuilder summary = new StringBuilder();
        for (Dim d : dims) {
            List<Integer> vals = new ArrayList<>();
            for (MultibaggerScore s : scores) {
                Integer v = d.get().apply(s);
                if (v != null) vals.add(v);
            }
            if (vals.size() < 10) {
                summary.append(String.format("%s=n/a(%d) ", d.name(), vals.size()));
                continue;
            }
            double mean = vals.stream().mapToInt(Integer::intValue).average().orElse(0);
            double var = vals.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / vals.size();
            double sd = Math.sqrt(var);
            summary.append(String.format("%s=%.1f±%.1f ", d.name(), mean, sd));

            if (sd < MIN_HEALTHY_DIMENSION_STDDEV) {
                log.error("Multibagger dimension '{}' has collapsed: sd={} across {} stocks (mean {}). "
                                + "It is not separating the universe, so its weight is contributing no "
                                + "information to the composite. This is the shape of the 2026-04 "
                                + "Institutional-Interest bug — check its upstream data source before "
                                + "trusting today's ranking.",
                        d.name(), String.format("%.2f", sd), vals.size(), String.format("%.1f", mean));
            }
        }
        log.info("Multibagger dimension spread (mean±sd): {}", summary.toString().trim());
    }

    // ============================================================
    // Buyability / liquidity (SPEC §12.9, F7)
    // ============================================================

    /**
     * 20-day average traded value in rupees, from candles already fetched for scoring.
     *
     * <p>Returns null rather than 0 when it cannot be measured (too little history, or
     * every candle missing volume). A 0 here would classify a stock as THIN and get it
     * excluded from promotion — a measurement gap must not masquerade as a finding.
     */
    Double calculateAdv20(List<Map<String, Object>> history) {
        if (history == null || history.size() < LIQUIDITY_LOOKBACK_DAYS) return null;
        List<Map<String, Object>> window = history.subList(history.size() - LIQUIDITY_LOOKBACK_DAYS, history.size());
        double sum = 0;
        int counted = 0;
        for (Map<String, Object> c : window) {
            double close = toDouble(c.get("close"));
            double volume = toDouble(c.get("volume"));
            if (close > 0 && volume > 0) {
                sum += close * volume;
                counted++;
            }
        }
        if (counted == 0) return null;
        // Divide by the WINDOW, not by the days that happened to trade (B-044).
        //
        // "Average daily traded value" answers "how much can I buy per day", and days the
        // stock did not trade at all are days you could not buy — they belong in the
        // denominator. Dividing by `counted` inflated exactly the stocks this guard exists
        // to catch: a stock trading 3 days out of 20 read ~6.7x too liquid, which is enough
        // to lift it out of THIN and past the F3 promotion filter that depends on it.
        return sum / LIQUIDITY_LOOKBACK_DAYS;
    }

    /**
     * Days in the last 60 candles where the stock was locked at a circuit band — detected
     * as a candle with no intraday range at all (high == low). Such a stock cannot be
     * bought on the way up or sold on the way down, whatever its score says.
     *
     * <p>Null when there is not enough history to judge.
     */
    Integer countCircuitDays(List<Map<String, Object>> history) {
        if (history == null || history.size() < CIRCUIT_LOOKBACK_DAYS) return null;
        List<Map<String, Object>> window = history.subList(history.size() - CIRCUIT_LOOKBACK_DAYS, history.size());
        int locked = 0;
        for (Map<String, Object> c : window) {
            double high = toDouble(c.get("high"));
            double low = toDouble(c.get("low"));
            if (high > 0 && low > 0 && Math.abs(high - low) < 1e-9) locked++;
        }
        return locked;
    }

    private static double toDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Double) return (Double) value;
        if (value instanceof Integer) return ((Integer) value).doubleValue();
        if (value instanceof Long) return ((Long) value).doubleValue();
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Keep the quarterly figures this run just fetched (SPEC 50).
     *
     * <p>{@code analyzeEarningsGrowth} above has already pulled these filings and
     * {@code NseDataService} caches them for 30 minutes, so this call costs <b>no NSE
     * requests at all</b>. Until it existed the figures were computed on every screening run and
     * discarded, which is why nothing in the app could say what a company had reported or notice
     * a result landing — the same shape as the analyst targets B-109 recovered and the growth
     * columns B-098 recovered.
     *
     * <p>Never throws: a ledger of the run must not be able to break the run.
     */
    private void captureQuarterlyResults(String tradingSymbol) {
        if (!earningsConfig.isCaptureDuringScreening()) {
            return;
        }
        try {
            quarterlyResultService.capture(tradingSymbol,
                    nseDataService.fetchQuarterlyResults(tradingSymbol));
        } catch (Exception e) {
            // Name what the absence will look like (Gotcha 52): a missing quarter reads later as
            // "this company did not report", which is a claim about the business rather than
            // about our capture.
            log.warn("Quarterly result capture failed for {} ({}). Its latest quarter will read as "
                    + "'not captured', which must not be taken as 'did not report'.",
                    tradingSymbol, e.getMessage());
        }
    }

}
