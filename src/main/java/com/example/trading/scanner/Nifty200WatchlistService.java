package com.example.trading.scanner;

import com.example.trading.config.StockFilterConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service providing the Nifty 200 stock watchlist.
 * Contains all Nifty 50 + Nifty Next 50 + Nifty Midcap Select stocks.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class Nifty200WatchlistService {

    private final StockFilterConfig stockFilterConfig;
    /**
     * Dynamic universe (SPEC §30). Injected as the <b>repository</b>, not
     * {@code UniverseExpansionService} — that service depends on this one to find out what
     * is already known, and taking the service here would close a constructor cycle.
     */
    private final com.example.trading.universe.DynamicUniverseRepository dynamicUniverseRepository;
    private final com.example.trading.universe.UniverseConfig universeConfig;

    // Nifty 50 Stocks (50)
    private static final List<String> NIFTY_50 = Arrays.asList(
        "ADANIENT", "ADANIPORTS", "APOLLOHOSP", "ASIANPAINT", "AXISBANK",
        "BAJAJ-AUTO", "BAJFINANCE", "BAJAJFINSV", "BEL", "BPCL",
        "BHARTIARTL", "BRITANNIA", "CIPLA", "COALINDIA", "DRREDDY",
        "EICHERMOT", "GRASIM", "HCLTECH", "HDFCBANK", "HDFCLIFE",
        "HEROMOTOCO", "HINDALCO", "HINDUNILVR", "ICICIBANK", "ITC",
        "INDUSINDBK", "INFY", "JSWSTEEL", "KOTAKBANK", "LT",
        "M&M", "MARUTI", "NTPC", "NESTLEIND", "ONGC",
        "POWERGRID", "RELIANCE", "SBILIFE", "SHRIRAMFIN", "SBIN",
        "SUNPHARMA", "TCS", "TATACONSUM", "TMPV", "TATASTEEL",
        "TECHM", "TITAN", "TRENT", "ULTRACEMCO", "WIPRO"
    );

    // Nifty Next 50 Stocks (50)
    private static final List<String> NIFTY_NEXT_50 = Arrays.asList(
        "ABB", "ADANIENSOL", "ADANIGREEN", "ADANIPOWER", "ATGL",
        "AMBUJACEM", "AUROPHARMA", "BANKBARODA", "BOSCHLTD", "CANBK",
        "CHOLAFIN", "COLPAL", "DLF", "DABUR", "DIVISLAB",
        "GAIL", "GODREJCP", "HAVELLS", "HAL", "ICICIGI",
        "ICICIPRULI", "IOC", "INDUSTOWER", "INDIGO", "JINDALSTEL",
        "JIOFIN", "JSL", "LICI", "LODHA", "LUPIN",
        "MARICO", "MOTHERSON", "NHPC", "NMDC", "NAUKRI",
        "PIDILITIND", "PFC", "PNB", "RECLTD", "SIEMENS",
        "SRF", "SBICARD", "SHREECEM", "TATAPOWER", "TORNTPHARM",
        "TVSMOTOR", "UNITDSPR", "VBL", "VEDL", "ETERNAL"
    );

    /** Tier constants used across the app for screener configuration (SPEC §12.5). */
    public static final String TIER_LARGE_ONLY = "LARGE_ONLY";
    public static final String TIER_LARGE_MID = "LARGE_MID";
    public static final String TIER_LARGE_MID_SMALL = "LARGE_MID_SMALL";
    public static final String TIER_ALL = "ALL";

    public static final String CAT_NIFTY_50 = "NIFTY_50";
    public static final String CAT_NIFTY_NEXT_50 = "NIFTY_NEXT_50";
    public static final String CAT_MIDCAP_100 = "MIDCAP_100";
    public static final String CAT_SMALLCAP = "SMALLCAP_250";
    public static final String CAT_MICROCAP = "MICROCAP";
    public static final String CAT_OTHER = "OTHER";

    // Nifty Midcap 100 Select Stocks (100)
    private static final List<String> NIFTY_MIDCAP_100 = Arrays.asList(
        "AARTIIND", "ACC", "ALKEM", "ASHOKLEY", "ASTRAL",
        "AUBANK", "AUROPHARMA", "BALKRISIND", "BANDHANBNK", "BATAINDIA",
        "BERGEPAINT", "BHARATFORG", "BHEL", "BIOCON", "CANFINHOME",
        "CENTRALBK", "CGPOWER", "CHAMBLFERT", "COFORGE", "CONCOR",
        "CUMMINSIND", "DEEPAKNTR", "DELHIVERY", "DIXON", "ESCORTS",
        "EXIDEIND", "FEDERALBNK", "FORTIS", "GLENMARK", "GMRAIRPORT",
        "GNFC", "GODREJPROP", "HINDPETRO",
        // Removed 2026-08-24 (B-027): Kite /quote returns {status=success, data={}} for
        // GUJGASLTD, MTARTECH, PPAP, BLISSGVS, JBCHEPHARM, ONMOBILE — delisted/renamed, so
        // MarketDataService could never price them. They were re-selected by
        // findDueForOutcome every day forever (152 wasted Kite calls per run, feeding the
        // 429 pressure). Re-add only with a verified tradingsymbol — never guess (gotcha #14).
        // GSPL removed 2026-05-23 (B-013): Kite /quote returns {status=success, data={}} —
        // not a valid Kite tradingsymbol (renamed/delisted). Re-add only with a verified symbol.
        "HONAUT", "IDFCFIRSTB", "IEX", "IIFL", "INDIANB",
        "INDHOTEL", "IRB", "IRCTC", "IRFC", "IDEA",
        "JUBLFOOD", "KAJARIACER", "KPITTECH", "LAURUSLABS", "LICHSGFIN",
        "LTF", "LTTS", "MANAPPURAM", "MFSL", "MGL",
        "MPHASIS", "MRPL", "MUTHOOTFIN", "NAM-INDIA", "NATIONALUM",
        "NAVINFLUOR", "OBEROIRLTY", "OFSS", "OIL", "PAGEIND",
        "PATANJALI", "PERSISTENT", "PETRONET", "PFIZER", "POLYCAB",
        "PVRINOX", "RAIN", "RAMCOCEM", "RBLBANK", "RELAXO",
        "SAIL", "SANOFI", "SCHAEFFLER", "SONACOMS", "STARHEALTH",
        "SUNDARMFIN", "SUNDRMFAST", "SUPREMEIND", "SYNGENE", "TATACHEM",
        "TATACOMM", "TATAELXSI", "TIINDIA", "THERMAX", "TIMKEN",
        "TRIDENT", "CROMPTON", "UBL", "UNIONBANK", "UPL",
        "VOLTAS", "WHIRLPOOL", "YESBANK", "ZEEL", "ZYDUSLIFE"
    );

    /**
     * Curated small-cap watchlist (~100 unique names, mkt cap ~2k–15k Cr).
     * Strictly deduplicated with NIFTY_50, NIFTY_NEXT_50 and NIFTY_MIDCAP_100.
     * This is where most multibagger candidates emerge; quality filters in
     * {@link com.example.trading.multibagger.MultibaggerScreenerService} apply
     * a stricter min-score + fundamental checks for this tier to avoid noise.
     */
    // Stale symbols removed 2026-05-10 — Kite quote endpoint returns empty data
    // for these (delisted, demerged, renamed, or moved series). They were polluting
    // multibagger screening with token-resolution ERRORs and skewing Failed counts.
    // Add replacements only after verifying the new symbol against the Kite
    // instruments cache. Removed: LAKSHMIMACH, AEGISCHEM, LAXMIORG, MINDAIND,
    // RANEENGINE, TVSSUPRA, WABCOINDIA, SEQUENT, STRIDES, SHANKARA, V-MART,
    // VARUNBEV (= VBL, already in NIFTY_NEXT_50), WELSPUNIND, SUBEX, EQUITAS,
    // IIFLWAM (= 360ONE, kept), MAS, ERAMAT, MAITHAN, TATAMETALI, RHI, GMRINFRA
    // (= GMRAIRPORT, already in NIFTY_MIDCAP_100), PGHH.
    private static final List<String> NIFTY_SMALLCAP_250 = Arrays.asList(
        // Capital Goods / Industrials
        "APARINDS", "BLUESTARCO", "CARBORUNIV", "ELGIEQUIP", "GRINDWELL",
        "HEG", "HONDAPOWER", "IFCI", "ISGEC", "JYOTHYLAB",
        "KEC", "KIRLOSBROS", "KIRLOSENG", "RAILTEL", "RATNAMANI", "SANSERA", "SHAILY", "SKIPPER",
        "TRITURBINE",
        // Chemicals / Specialty
        "ALKYLAMINE", "ANURAS", "ATUL", "CLEAN",
        "COROMANDEL", "EPL", "GALAXYSURF", "HEIDELBERG", "HSCL",
        "KANSAINER", "LINDEINDIA", "PIIND", "ROSSARI",
        "SHARDACROP", "SUDARSCHEM", "SUMICHEM", "VINATIORGA",
        // Auto / Auto Components
        "APLAPOLLO", "ATULAUTO", "BAJAJELEC", "CEATLTD", "CENTUM",
        "ENDURANCE", "GABRIEL", "GREAVESCOT", "JAMNAAUTO", "LUMAXIND",
        "LUMAXTECH", "MUNJALSHOW", "SHANTIGEAR", "SUBROS",
        // Pharma / Healthcare
        "ABBOTINDIA", "AJANTPHARM", "APLLTD", "ASTRAZEN", "CAPLIPOINT", "ERIS", "GLAND", "IPCALAB", "KRBL", "LALPATHLAB", "MANKIND", "MARKSANS", "METROPOLIS",
        "NATCOPHARM", "NH", "POLYMED", "SOLARA",
        "SUVEN", "THYROCARE", "TORNTPHARM",
        // Consumer / Retail
        "AEGISLOG", "BAJAJHLDNG", "CARTRADE", "CCL", "CERA",
        "FINEORG", "GREENPANEL", "HATHWAY", "NAZARA",
        "RADICO", "RAINBOW", "SHOPERSTOP", "SYMPHONY",
        "TCIEXP", "TEAMLEASE", "THANGAMAYL", "TITAGARH",
        "VSTIND", "WONDERLA", "ZYDUSWELL",
        // IT / Tech
        "AFFLE", "BSOFT", "CYIENT", "DATAMATICS", "HAPPSTMNDS",
        "INTELLECT", "KFINTECH", "LATENTVIEW", "MASTEK", "NUCLEUS",
        "ROUTE", "TANLA", "TATATECH",
        "ZENSARTECH",
        // Financials
        "360ONE", "APTUS", "CAMS", "CDSL", "CHOLAHLDNG",
        "CREDITACC", "CSBBANK", "HOMEFIRST",
        "JMFINANCIL", "KARURVYSYA", "MCX", "NUVAMA",
        "PNBHOUSING", "REPCOHOME", "SBFC", "SPANDANA", "UJJIVANSFB",
        "UTIAMC",
        // Metals / Mining / Cement
        "GRAPHITE", "GRAVITA", "HINDCOPPER", "JKCEMENT",
        "JKLAKSHMI", "MOIL", "NCC", "ORIENTCEM",
        "STARCEMENT", "WELCORP",
        // Real Estate / Infrastructure
        "ANANDRATHI", "BRIGADE", "CAPACITE", "IRCON", "KALYANKJIL",
        "KNRCON", "MAHLIFE", "MANINFRA", "NBCC", "PRESTIGE",
        "SOBHA", "SUNTECK",
        // Textiles / Apparel
        "AMBER", "DOLLAR", "KPRMILL", "RAYMOND", "SIYSIL", "VGUARD",
        // Defence / PSU
        "COCHINSHIP", "GARFIBRES", "GRSE", "MAZDOCK", "MIDHANI",
        "MTNL", "PARADEEP", "RITES", "SCI", "SOLARINDS", "ZENTEC",
        // Energy / Power
        "IGL", "NLCINDIA", "RPOWER", "TORNTPOWER"
    );

    /**
     * Curated micro-cap watchlist (~60 unique names, mkt cap ≲ 2k Cr).
     * Strictly deduplicated with all larger-tier lists. Extra-strict quality
     * filters apply — min composite score 60, positive earnings verdict,
     * promoter pledge &lt; 30%, price &gt; ₹20.
     */
    private static final List<String> MICROCAP_WATCHLIST = Arrays.asList(
        // EV / new energy
        "GENSOL", "HBLPOWER", "JYOTI", "MUKUNDLTD", "ORIENTELEC",
        "POWERMECH", "SALASAR", "SHIVAUBR", "TPHQ", "UGRO",
        // Defence / aerospace
        "ASTRA", "BEML", "DATAPATTNS", "IDEAFORGE", "JMA",
        "PARAS", "TANEJAERO", "UNIDT",
        // Chemicals / agri-chem
        "BALAXI", "DCMSRIND", "HIKAL", "HINDOILEXP", "LINCOLN",
        "MANALIPETC", "NOCIL", "PUNJABCHEM", "SAGCEM",
        // Healthcare / pharma specialty
        "AARTIDRUGS", "CAPLINPOINT", "JAGRAN", "JASCH", "ORCHPHARMA",
        "SMSLIFE", "TARSONS", "WOCKPHARMA",
        // IT / smallcase
        "DCMSHRIRAM", "ECLERX", "INDOSTAR", "MPSLTD", "ONWARDTEC",
        "RAMCOSYS", "SASKEN", "XCHANGING",
        // Consumer / niche
        "CUPID", "DELTACORP", "FLAIR", "GLOSTERLTD", "GROBTEA",
        "HARIOMPIPE", "LANDMARK", "SUULA", "TCNSBRANDS",
        // Industrial / capital goods
        "AZAD", "CREATIVE", "FIEMIND", "GOLDIAM", "HLEGLAS",
        "INSPIREFIN", "MARKOBENZ", "PRECAM", "RKFORGE", "TEXRAIL",
        "VARROC",
        // Textiles / specialty
        "HIMATSEIDE", "SUTLEJTEX"
    );

    @PostConstruct
    public void init() {
        if (stockFilterConfig.isEnabled()) {
            log.info("Stock filter enabled. Blacklisted stocks: {}", stockFilterConfig.getBlacklistSummary());
            log.info("Price range: {} - {}", stockFilterConfig.getMinPrice(), stockFilterConfig.getMaxPrice());
        }
    }

    /**
     * Get all Nifty 200 stocks with NSE prefix (excluding blacklisted).
     */
    public List<String> getNifty200Symbols() {
        return getAllSymbols().stream()
            .distinct()
            .filter(symbol -> !stockFilterConfig.isBlacklisted(symbol))
            .map(symbol -> "NSE:" + symbol)
            .collect(Collectors.toList());
    }

    /**
     * Get all Nifty 200 stocks including blacklisted (for reference).
     */
    public List<String> getAllNifty200SymbolsUnfiltered() {
        return getAllSymbols().stream()
            .distinct()
            .map(symbol -> "NSE:" + symbol)
            .collect(Collectors.toList());
    }

    /**
     * Get raw symbols without exchange prefix (excluding blacklisted).
     */
    public List<String> getRawSymbols() {
        return getAllSymbols().stream()
            .distinct()
            .filter(symbol -> !stockFilterConfig.isBlacklisted(symbol))
            .collect(Collectors.toList());
    }

    /**
     * Get Nifty 50 stocks only (excluding blacklisted).
     */
    public List<String> getNifty50Symbols() {
        return NIFTY_50.stream()
            .filter(symbol -> !stockFilterConfig.isBlacklisted(symbol))
            .map(symbol -> "NSE:" + symbol)
            .collect(Collectors.toList());
    }

    /**
     * Get Nifty Next 50 stocks only (excluding blacklisted).
     */
    public List<String> getNiftyNext50Symbols() {
        return NIFTY_NEXT_50.stream()
            .filter(symbol -> !stockFilterConfig.isBlacklisted(symbol))
            .map(symbol -> "NSE:" + symbol)
            .collect(Collectors.toList());
    }

    /**
     * Get Midcap 100 stocks only (excluding blacklisted).
     */
    public List<String> getMidcap100Symbols() {
        return NIFTY_MIDCAP_100.stream()
            .filter(symbol -> !stockFilterConfig.isBlacklisted(symbol))
            .map(symbol -> "NSE:" + symbol)
            .collect(Collectors.toList());
    }

    /** Small-cap symbols with NSE prefix (excluding blacklisted). */
    public List<String> getSmallcapSymbols() {
        return NIFTY_SMALLCAP_250.stream()
            .filter(symbol -> !stockFilterConfig.isBlacklisted(symbol))
            .map(symbol -> "NSE:" + symbol)
            .collect(Collectors.toList());
    }

    /** Curated micro-cap watchlist with NSE prefix (excluding blacklisted). */
    public List<String> getMicrocapSymbols() {
        return MICROCAP_WATCHLIST.stream()
            .filter(symbol -> !stockFilterConfig.isBlacklisted(symbol))
            .map(symbol -> "NSE:" + symbol)
            .collect(Collectors.toList());
    }

    /** Full universe across all tiers (large + mid + small + micro), deduplicated. */
    public List<String> getFullUniverseSymbols() {
        return getSymbolsByTier(TIER_ALL);
    }

    /**
     * Return symbols included in the given tier. Tier constants are defined on this class
     * ({@link #TIER_LARGE_ONLY}, {@link #TIER_LARGE_MID}, {@link #TIER_LARGE_MID_SMALL},
     * {@link #TIER_ALL}). Unknown tier strings fall back to LARGE_MID.
     */
    public List<String> getSymbolsByTier(String tier) {
        List<String> out = new java.util.ArrayList<>();
        out.addAll(NIFTY_50);
        out.addAll(NIFTY_NEXT_50);
        if (!TIER_LARGE_ONLY.equals(tier)) {
            out.addAll(NIFTY_MIDCAP_100);
        }
        if (TIER_LARGE_MID_SMALL.equals(tier) || TIER_ALL.equals(tier)) {
            out.addAll(NIFTY_SMALLCAP_250);
        }
        if (TIER_ALL.equals(tier)) {
            out.addAll(MICROCAP_WATCHLIST);
        }
        List<String> curated = out.stream()
            .distinct()
            .filter(symbol -> !stockFilterConfig.isBlacklisted(symbol))
            .map(symbol -> "NSE:" + symbol)
            .collect(Collectors.toList());

        // Symbols the expansion funnel promoted are appended; curated names are never
        // removed or reordered by it (SPEC §30.3). When the feature is off this is a no-op,
        // so the funnel can be watched for weeks before it is allowed to change screening.
        if (!universeConfig.isEnabled()) {
            return curated;
        }
        List<String> merged = new java.util.ArrayList<>(curated);
        java.util.Set<String> seen = new java.util.HashSet<>(curated);
        int added = 0;
        for (com.example.trading.universe.DynamicUniverseEntity d : dynamicUniverseRepository.findPromoted()) {
            String symbol = d.getSymbol();
            if (symbol == null || seen.contains(symbol)) continue;
            if (stockFilterConfig.isBlacklisted(symbol.replace("NSE:", ""))) continue;
            merged.add(symbol);
            seen.add(symbol);
            added++;
        }
        if (added > 0) {
            log.info("Universe: {} curated + {} dynamically promoted = {} symbols (tier={})",
                    curated.size(), added, merged.size(), tier);
        }
        return merged;
    }

    /**
     * Get total count of stocks in watchlist (excluding blacklisted).
     */
    public int getWatchlistSize() {
        return (int) getAllSymbols().stream()
            .distinct()
            .filter(symbol -> !stockFilterConfig.isBlacklisted(symbol))
            .count();
    }

    /**
     * Get count of blacklisted stocks.
     */
    public int getBlacklistCount() {
        return stockFilterConfig.getBlacklist() != null ? stockFilterConfig.getBlacklist().size() : 0;
    }

    /**
     * Get list of blacklisted symbols.
     */
    public List<String> getBlacklistedSymbols() {
        return stockFilterConfig.getBlacklist() != null ?
            stockFilterConfig.getBlacklist() : List.of();
    }

    /**
     * Check if a stock is tradeable (in watchlist and not blacklisted).
     */
    public boolean isTradeable(String symbol) {
        if (stockFilterConfig.isBlacklisted(symbol)) {
            return false;
        }
        return isInWatchlist(symbol);
    }

    private List<String> getAllSymbols() {
        List<String> all = new java.util.ArrayList<>();
        all.addAll(NIFTY_50);
        all.addAll(NIFTY_NEXT_50);
        all.addAll(NIFTY_MIDCAP_100);
        return all;
    }

    /**
     * Check if symbol is in watchlist.
     */
    public boolean isInWatchlist(String symbol) {
        String rawSymbol = symbol.replace("NSE:", "");
        return getAllSymbols().stream()
            .anyMatch(s -> s.equalsIgnoreCase(rawSymbol));
    }

    /**
     * Get category of a symbol across all cap tiers.
     */
    public String getCategory(String symbol) {
        String rawSymbol = symbol.replace("NSE:", "");
        if (NIFTY_50.stream().anyMatch(s -> s.equalsIgnoreCase(rawSymbol))) {
            return CAT_NIFTY_50;
        } else if (NIFTY_NEXT_50.stream().anyMatch(s -> s.equalsIgnoreCase(rawSymbol))) {
            return CAT_NIFTY_NEXT_50;
        } else if (NIFTY_MIDCAP_100.stream().anyMatch(s -> s.equalsIgnoreCase(rawSymbol))) {
            return CAT_MIDCAP_100;
        } else if (NIFTY_SMALLCAP_250.stream().anyMatch(s -> s.equalsIgnoreCase(rawSymbol))) {
            return CAT_SMALLCAP;
        } else if (MICROCAP_WATCHLIST.stream().anyMatch(s -> s.equalsIgnoreCase(rawSymbol))) {
            return CAT_MICROCAP;
        }
        return CAT_OTHER;
    }
}
