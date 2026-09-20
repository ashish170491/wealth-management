package com.example.trading.funds;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * AMFI's scheme category, resolved to one value per SEBI category.
 *
 * <p><b>Why this class exists (B-096 / Gotcha 110, in a new file).</b> AMFI prints the category as
 * a block header above each run of schemes, and it prints <em>every equity category under two
 * spellings</em> - {@code Equity Scheme - Flexi Cap Fund} (130 rows) and {@code Equity Schemes -
 * Flexi Cap Fund} (60). Measured on the live file: Large Cap 112+52, Small Cap 98+45, Mid Cap
 * 83+52, ELSS 112+84 (the second spelled {@code ELSS- Tax Saver Fund}), Sectoral/Thematic split
 * three ways 735+222+80.
 *
 * <p>Match the literal label and a fund's peer group is either 98 schemes or 45 depending on which
 * spelling its AMC happened to use - and <b>every percentile computed inside it is wrong while
 * looking perfectly fine</b>. That is the exact failure B-096 caused for equity sectors, where
 * three vocabularies for one sector made every allocation target read 0%.
 *
 * <p>So, as with {@code SectorMapping.resolve}, there is <b>one entry point</b>:
 * {@link #resolve(String)}. Two rules travel with it:
 *
 * <ol>
 *   <li><b>The raw header is kept.</b> {@link Resolved#rawHeader()} carries AMFI's own spelling, so
 *       a mapping bug is diagnosable rather than invisible.</li>
 *   <li><b>An unknown header is {@link #UNCLASSIFIED} and is reported by name</b> - never folded
 *       into the nearest-looking category. A category holding one fund because a spelling missed is
 *       indistinguishable on screen from a real category nobody populated (B-074's shape).</li>
 * </ol>
 *
 * <p>Runtime is deliberately lenient - AMFI may invent a fourth spelling tomorrow and that must not
 * fail a scheduled job. <b>The test is strict</b>: {@code SebiCategoryTest} asserts this table
 * against a captured real header set, so a new spelling fails the build rather than silently
 * halving a peer group.
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public enum SebiCategory {

    LARGE_CAP("Large Cap", AssetClass.EQUITY),
    LARGE_AND_MID("Large & Mid Cap", AssetClass.EQUITY),
    MID_CAP("Mid Cap", AssetClass.EQUITY),
    SMALL_CAP("Small Cap", AssetClass.EQUITY),
    FLEXI_CAP("Flexi Cap", AssetClass.EQUITY),
    MULTI_CAP("Multi Cap", AssetClass.EQUITY),
    FOCUSED("Focused", AssetClass.EQUITY),
    VALUE("Value", AssetClass.EQUITY),
    CONTRA("Contra", AssetClass.EQUITY),
    DIVIDEND_YIELD("Dividend Yield", AssetClass.EQUITY),
    ELSS("ELSS (tax-saving)", AssetClass.EQUITY),
    SECTORAL_THEMATIC("Sectoral / Thematic", AssetClass.EQUITY),
    INDEX_EQUITY("Equity index fund", AssetClass.EQUITY),
    EQUITY_ETF("Equity ETF", AssetClass.EQUITY),

    /**
     * The pre-2018 taxonomy's bare {@code Growth} bucket, which still appears in the live file
     * (245 rows) and throughout the historical archive. It is equity, but SEBI's 2018
     * recategorisation means it describes no mandate this app can compare like-for-like - so it is
     * named rather than left {@link #UNCLASSIFIED}. The distinction matters: unclassified means
     * <em>this app has no rule</em>, and here the rule is precisely that the category is obsolete.
     */
    LEGACY_PRE_2018("Pre-2018 category", AssetClass.EQUITY),

    DEBT("Debt", AssetClass.DEBT),
    HYBRID("Hybrid", AssetClass.HYBRID),
    GOLD("Gold", AssetClass.GOLD),
    SOLUTION_ORIENTED("Solution-oriented", AssetClass.SOLUTION_ORIENTED),
    FUND_OF_FUNDS("Fund of funds", AssetClass.OTHER),
    OTHER("Other", AssetClass.OTHER),

    /** No rule for the header AMFI printed. The app's gap - reported by name, never bucketed. */
    UNCLASSIFIED("Not classified", AssetClass.UNCLASSIFIED);

    private final String label;
    private final AssetClass assetClass;

    SebiCategory(String label, AssetClass assetClass) {
        this.label = label;
        this.assetClass = assetClass;
    }

    public String label() {
        return label;
    }

    public AssetClass assetClass() {
        return assetClass;
    }

    /**
     * True for the categories this module screens. Index funds and ETFs are equity but are judged
     * on tracking difference against their stated index, not on excess return over a category
     * benchmark - a different question, so they are not part of the screenable set.
     */
    public boolean isActivelyManagedEquity() {
        return assetClass == AssetClass.EQUITY
                && this != INDEX_EQUITY
                && this != EQUITY_ETF
                && this != LEGACY_PRE_2018;
    }

    /**
     * What one AMFI block header resolved to.
     *
     * @param rawHeader AMFI's own spelling, kept verbatim so a mapping bug is diagnosable
     */
    public record Resolved(SebiCategory category, SchemeStructure structure, String rawHeader) {
        public boolean isUnclassified() {
            return category == UNCLASSIFIED;
        }
    }

    /**
     * Keyed on the <em>normalised</em> inner text of the header (see {@link #normalise}), which
     * folds the Scheme/Schemes and Fund/Funds plurals, punctuation and case - so each real SEBI
     * category needs one entry per genuinely different wording, not one per spelling.
     */
    private static final Map<String, SebiCategory> ALIASES = Map.ofEntries(
            Map.entry("EQUITY SCHEME LARGE CAP", LARGE_CAP),
            Map.entry("EQUITY SCHEME LARGE & MID CAP", LARGE_AND_MID),
            Map.entry("EQUITY SCHEME MID CAP", MID_CAP),
            Map.entry("EQUITY SCHEME SMALL CAP", SMALL_CAP),
            Map.entry("EQUITY SCHEME FLEXI CAP", FLEXI_CAP),
            Map.entry("EQUITY SCHEME MULTI CAP", MULTI_CAP),
            Map.entry("EQUITY SCHEME FOCUSED", FOCUSED),
            Map.entry("EQUITY SCHEME VALUE", VALUE),
            Map.entry("EQUITY SCHEME CONTRA", CONTRA),
            Map.entry("EQUITY SCHEME DIVIDEND YIELD", DIVIDEND_YIELD),

            // Two genuinely different wordings for one category: 112 rows and 84 rows.
            Map.entry("EQUITY SCHEME ELSS", ELSS),
            Map.entry("EQUITY SCHEME ELSS TAX SAVER", ELSS),

            // Three for this one: 735 + 222 + 80 rows.
            Map.entry("EQUITY SCHEME SECTORAL THEMATIC", SECTORAL_THEMATIC),
            Map.entry("EQUITY SCHEME SECTORAL", SECTORAL_THEMATIC),
            Map.entry("EQUITY SCHEME THEMATIC", SECTORAL_THEMATIC),

            Map.entry("INDEX FUND EQUITY", INDEX_EQUITY),
            Map.entry("EXCHANGE TRADED FUND ETFS EQUITY ETF", EQUITY_ETF),
            Map.entry("EXCHANGE TRADED FUND ETFS GOLD ETF", GOLD),
            Map.entry("EXCHANGE TRADED FUND ETFS OTHER ETF", OTHER),
            Map.entry("EXCHANGE TRADED FUND ETFS ETFS INVESTING OVERSEAS", OTHER),
            Map.entry("EXCHANGE TRADED FUND ETFS SILVER ETF", OTHER),

            // Where index funds actually live today - 1,074 rows, the single largest block that had
            // no rule on the first live run. "Index Funds - Equity Funds" above is the older heading.
            Map.entry("OTHER SCHEME INDEX", INDEX_EQUITY),
            Map.entry("OTHER SCHEME OTHER ETFS", OTHER),

            // Close-ended blocks print the bare category with no "Equity Scheme" prefix.
            Map.entry("ELSS", ELSS),
            Map.entry("GROWTH", LEGACY_PRE_2018)
    );

    /**
     * Coarse keyword rules for the non-equity remainder, applied only after {@link #ALIASES}
     * misses. Insertion-ordered and first-hit-wins, so the most specific words come first -
     * {@code CORPORATE BOND} must be reachable before the bare {@code BOND} would swallow it, and
     * {@code SOLUTION ORIENTED} before {@code INCOME} catches a retirement fund.
     */
    private static final Map<String, SebiCategory> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put("SOLUTION ORIENTED", SOLUTION_ORIENTED);
        KEYWORDS.put("RETIREMENT", SOLUTION_ORIENTED);
        KEYWORDS.put("CHILDREN", SOLUTION_ORIENTED);
        KEYWORDS.put("LIFE CYCLE", SOLUTION_ORIENTED);
        KEYWORDS.put("FUND OF FUND", FUND_OF_FUNDS);
        KEYWORDS.put("FOF", FUND_OF_FUNDS);
        KEYWORDS.put("GOLD", GOLD);
        KEYWORDS.put("HYBRID", HYBRID);
        KEYWORDS.put("EQUITY SAVINGS", HYBRID);
        KEYWORDS.put("ARBITRAGE", HYBRID);
        KEYWORDS.put("BALANCED", HYBRID);
        KEYWORDS.put("CORPORATE BOND", DEBT);
        KEYWORDS.put("CREDIT RISK", DEBT);
        KEYWORDS.put("MONEY MARKET", DEBT);
        KEYWORDS.put("DEBT", DEBT);
        KEYWORDS.put("GILT", DEBT);
        KEYWORDS.put("BOND", DEBT);
        KEYWORDS.put("LIQUID", DEBT);
        KEYWORDS.put("OVERNIGHT", DEBT);
        KEYWORDS.put("DURATION", DEBT);
        KEYWORDS.put("FLOATER", DEBT);
        // Pre-2018 spelling of the same thing, and the ONE header in the 2018-2026 archive
        // that the live-file alias table did not already cover.
        KEYWORDS.put("FLOATING RATE", DEBT);
        // Pre-2018 taxonomy, seen in the historical files: "Open Ended Schemes ( Income )".
        KEYWORDS.put("INCOME", DEBT);
    }

    /**
     * Resolve one AMFI block header, e.g. {@code "Open Ended Schemes(Equity Scheme - Flexi Cap Fund)"}
     * or the historical file's spaced form {@code "Open Ended Schemes ( Equity Scheme - Mid Cap Fund )"}.
     *
     * <p>Never throws and never returns null: an unrecognised header resolves to
     * {@link #UNCLASSIFIED} carrying its raw text.
     */
    public static Resolved resolve(String rawHeader) {
        if (rawHeader == null || rawHeader.isBlank()) {
            return new Resolved(UNCLASSIFIED, SchemeStructure.UNKNOWN, rawHeader == null ? "" : rawHeader);
        }
        String raw = rawHeader.trim();

        int open = raw.indexOf('(');
        int close = raw.lastIndexOf(')');
        String prefix = open >= 0 ? raw.substring(0, open) : raw;
        String inner = (open >= 0 && close > open) ? raw.substring(open + 1, close) : raw;

        SchemeStructure structure = structureOf(prefix);
        String key = normalise(inner);

        SebiCategory hit = ALIASES.get(key);
        if (hit == null) {
            hit = byKeyword(key);
        }
        return new Resolved(hit == null ? UNCLASSIFIED : hit, structure, raw);
    }

    private static SchemeStructure structureOf(String prefix) {
        String p = prefix.toUpperCase(Locale.ROOT);
        if (p.contains("CLOSE")) return SchemeStructure.CLOSE_ENDED;
        if (p.contains("INTERVAL")) return SchemeStructure.INTERVAL;
        if (p.contains("OPEN")) return SchemeStructure.OPEN_ENDED;
        return SchemeStructure.UNKNOWN;
    }

    private static SebiCategory byKeyword(String key) {
        for (Map.Entry<String, SebiCategory> e : KEYWORDS.entrySet()) {
            if (key.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Upper-case, drop punctuation (keeping {@code &}), collapse whitespace, fold the
     * Scheme/Schemes and Fund/Funds plurals, then strip a trailing {@code FUND}. This is what makes
     * {@code "Equity Scheme - Small Cap Fund"} and {@code "Equity Schemes - Small Cap Fund"} one key
     * rather than two peer groups of 98 and 45.
     */
    static String normalise(String text) {
        String s = text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9&]+", " ").trim();
        s = s.replaceAll("\\bSCHEMES\\b", "SCHEME").replaceAll("\\bFUNDS\\b", "FUND");
        s = s.replaceAll("\\s+", " ").trim();
        while (s.endsWith(" FUND")) {
            s = s.substring(0, s.length() - " FUND".length()).trim();
        }
        return s;
    }
}
