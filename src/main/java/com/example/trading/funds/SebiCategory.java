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
 * <p><b>In debt it is worse, and it is not a spelling difference but two whole taxonomies</b>
 * (§52.15, 2026-09-20). SEBI recategorised debt in 2018 and AMFI never retired the old headers, so
 * the same live file carries {@code Debt Scheme - Liquid Fund} (312 rows) <em>and</em>
 * {@code Income/Debt Oriented Schemes - Liquid Fund} (186), both with current NAV dates. Measured
 * across the whole file: Liquid 312+186, Overnight 204+103, Money Market 154+91, Corporate Bond
 * 131+85, Credit Risk 135+37, Gilt 87+70, Banking &amp; PSU 25+150 - that last one inverted, so the
 * category is <em>mostly</em> under the old header. Matching one taxonomy would not halve a peer
 * group here; on Banking &amp; PSU it would leave 14% of it.
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
 * <p><b>Where the old taxonomy does not line up, it gets its own value rather than a guess.</b>
 * {@code Income/Debt Oriented Schemes - Ultra Short to Short Term Fund} (255 rows, 235 of them
 * current) straddles two modern categories whose mandates are three months and three years - so it
 * resolves to {@link #DEBT} and {@code HorizonFit} refuses to match it to a horizon, rather than
 * being filed under whichever neighbour looks closer. A bucket spanning an order of magnitude of
 * duration cannot answer "is this right for money I need in six months", and pretending it can is
 * the units error this module exists to refuse (B-047's family).
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

    // ------------------------------------------------------------------ debt, by SEBI mandate
    // The order below is the order of the SEBI circular: shortest mandated duration first, then
    // the credit-defined and issuer-defined categories, then the ones with no fixed duration.
    // HorizonFit reads these, so a new value added in the wrong place is harmless but a new value
    // with no HorizonFit row is not - the test fails on an unmapped category.

    DEBT_OVERNIGHT("Overnight", AssetClass.DEBT),
    DEBT_LIQUID("Liquid", AssetClass.DEBT),
    DEBT_ULTRA_SHORT("Ultra Short Duration", AssetClass.DEBT),
    DEBT_LOW_DURATION("Low Duration", AssetClass.DEBT),
    DEBT_MONEY_MARKET("Money Market", AssetClass.DEBT),
    DEBT_SHORT_DURATION("Short Duration", AssetClass.DEBT),
    DEBT_MEDIUM_DURATION("Medium Duration", AssetClass.DEBT),
    DEBT_MEDIUM_TO_LONG("Medium to Long Duration", AssetClass.DEBT),
    DEBT_LONG_DURATION("Long Duration", AssetClass.DEBT),
    DEBT_DYNAMIC_BOND("Dynamic Bond", AssetClass.DEBT),
    DEBT_CORPORATE_BOND("Corporate Bond", AssetClass.DEBT),
    DEBT_CREDIT_RISK("Credit Risk", AssetClass.DEBT),
    DEBT_BANKING_PSU("Banking & PSU", AssetClass.DEBT),
    DEBT_GILT("Gilt", AssetClass.DEBT),
    DEBT_GILT_10Y("Gilt, 10-year constant maturity", AssetClass.DEBT),
    DEBT_FLOATER("Floating rate", AssetClass.DEBT),
    INDEX_DEBT("Debt index fund", AssetClass.DEBT),
    DEBT_ETF("Debt ETF", AssetClass.DEBT),

    /**
     * Debt, but not pinned to a SEBI category - the pre-2018 buckets whose mandate spans too much
     * to match a horizon, and anything the keyword rules catch without a specific hit. Reported as
     * a category in its own right, and refused by {@code HorizonFit} rather than approximated.
     */
    DEBT("Debt (category not identified)", AssetClass.DEBT),

    // ------------------------------------------------------------------ hybrid, by SEBI mandate

    HYBRID_ARBITRAGE("Arbitrage", AssetClass.HYBRID),
    HYBRID_CONSERVATIVE("Conservative Hybrid", AssetClass.HYBRID),
    HYBRID_EQUITY_SAVINGS("Equity Savings", AssetClass.HYBRID),
    HYBRID_BALANCED("Balanced Hybrid", AssetClass.HYBRID),
    HYBRID_BALANCED_ADVANTAGE("Balanced Advantage", AssetClass.HYBRID),
    HYBRID_AGGRESSIVE("Aggressive Hybrid", AssetClass.HYBRID),
    HYBRID_MULTI_ASSET("Multi Asset Allocation", AssetClass.HYBRID),

    /** Hybrid, category not pinned - the pre-2018 bare {@code Balanced} bucket and the fallback. */
    HYBRID("Hybrid (category not identified)", AssetClass.HYBRID),

    GOLD_ETF("Gold ETF", AssetClass.GOLD),
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
     * True for the categories this module screens against a peer median. Index funds and ETFs are
     * equity but are judged on tracking difference against their stated index, not on excess return
     * over a category benchmark - a different question, so they are not part of the screenable set.
     *
     * <p>Unchanged by §52.15. Widening the <em>persisted</em> universe to debt and hybrid must not
     * move a single equity number, and this predicate is what guarantees it: peer sets filter on
     * {@code screenable} AND category, and every category added in that pass answers false here.
     */
    public boolean isActivelyManagedEquity() {
        return assetClass == AssetClass.EQUITY
                && this != INDEX_EQUITY
                && this != EQUITY_ETF
                && this != LEGACY_PRE_2018;
    }

    /**
     * True when the scheme trades on an exchange, so its <b>NAV is not the price you would pay</b>.
     * Deliberately excluded from everything this module keeps: an ETF's market price sits away from
     * its net asset value by an amount nobody here measures, so a NAV series would answer a
     * question that was not asked.
     */
    public boolean isExchangeTraded() {
        return this == EQUITY_ETF || this == DEBT_ETF || this == GOLD_ETF;
    }

    /**
     * True for the asset classes whose month-end series this module keeps (§52.15).
     *
     * <p>Equity, debt and hybrid. Gold is reachable only as an ETF in this feed, so it fails on
     * {@link #isExchangeTraded()} rather than here; solution-oriented schemes (retirement and
     * children's funds, 42 Direct-Growth rows) carry a five-year lock-in that makes them a
     * different question and are left out, though one the investor actually holds is persisted
     * anyway by the owned-scheme clause.
     */
    public boolean isKeepableAssetClass() {
        return assetClass == AssetClass.EQUITY
                || assetClass == AssetClass.DEBT
                || assetClass == AssetClass.HYBRID;
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
     *
     * <p>Every debt and hybrid category needs <b>two or three</b> entries, because the 2018
     * recategorisation left both taxonomies live in the same file. Neither may be dropped: on
     * Banking &amp; PSU the modern header carries 25 rows and the legacy one 150.
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
            Map.entry("INDEX FUND DEBT", INDEX_DEBT),
            Map.entry("INDEX FUND HYBRID", HYBRID),
            Map.entry("EXCHANGE TRADED FUND ETFS EQUITY ETF", EQUITY_ETF),
            Map.entry("EXCHANGE TRADED FUND ETFS DEBT ETF", DEBT_ETF),
            Map.entry("EXCHANGE TRADED FUND ETFS HYBRID ETF", HYBRID),
            Map.entry("EXCHANGE TRADED FUND ETFS GOLD ETF", GOLD_ETF),
            Map.entry("OTHER SCHEME GOLD ETF", GOLD_ETF),
            Map.entry("EXCHANGE TRADED FUND ETFS OTHER ETF", OTHER),
            Map.entry("EXCHANGE TRADED FUND ETFS ETFS INVESTING OVERSEAS", OTHER),
            Map.entry("EXCHANGE TRADED FUND ETFS SILVER ETF", OTHER),

            // Where index funds actually live today - 1,074 rows, the single largest block that had
            // no rule on the first live run. "Index Funds - Equity Funds" above is the older heading.
            Map.entry("OTHER SCHEME INDEX", INDEX_EQUITY),
            Map.entry("OTHER SCHEME OTHER ETFS", OTHER),

            // ---------------------------------------------------------------- debt, both taxonomies
            // Left column: the post-2018 SEBI header. Right: the pre-2018 one AMFI still publishes.
            Map.entry("DEBT SCHEME OVERNIGHT", DEBT_OVERNIGHT),
            Map.entry("INCOME DEBT ORIENTED SCHEME OVERNIGHT", DEBT_OVERNIGHT),
            Map.entry("DEBT SCHEME LIQUID", DEBT_LIQUID),
            Map.entry("INCOME DEBT ORIENTED SCHEME LIQUID", DEBT_LIQUID),
            Map.entry("LIQUID", DEBT_LIQUID),
            Map.entry("DEBT SCHEME ULTRA SHORT DURATION", DEBT_ULTRA_SHORT),
            Map.entry("INCOME DEBT ORIENTED SCHEME ULTRA SHORT TERM", DEBT_ULTRA_SHORT),
            Map.entry("DEBT SCHEME LOW DURATION", DEBT_LOW_DURATION),
            Map.entry("DEBT SCHEME MONEY MARKET", DEBT_MONEY_MARKET),
            Map.entry("INCOME DEBT ORIENTED SCHEME MONEY MARKET", DEBT_MONEY_MARKET),
            Map.entry("MONEY MARKET", DEBT_MONEY_MARKET),
            Map.entry("DEBT SCHEME SHORT DURATION", DEBT_SHORT_DURATION),
            Map.entry("INCOME DEBT ORIENTED SCHEME SHORT TERM", DEBT_SHORT_DURATION),
            Map.entry("DEBT SCHEME MEDIUM DURATION", DEBT_MEDIUM_DURATION),
            Map.entry("INCOME DEBT ORIENTED SCHEME MEDIUM TERM", DEBT_MEDIUM_DURATION),
            Map.entry("DEBT SCHEME MEDIUM TO LONG DURATION", DEBT_MEDIUM_TO_LONG),
            Map.entry("INCOME DEBT ORIENTED SCHEME MEDIUM TO LONG TERM", DEBT_MEDIUM_TO_LONG),
            Map.entry("DEBT SCHEME LONG DURATION", DEBT_LONG_DURATION),
            Map.entry("INCOME DEBT ORIENTED SCHEME LONG TERM", DEBT_LONG_DURATION),
            Map.entry("DEBT SCHEME DYNAMIC BOND", DEBT_DYNAMIC_BOND),
            Map.entry("INCOME DEBT ORIENTED SCHEME DYNAMIC TERM", DEBT_DYNAMIC_BOND),
            Map.entry("DEBT SCHEME CORPORATE BOND", DEBT_CORPORATE_BOND),
            Map.entry("INCOME DEBT ORIENTED SCHEME CORPORATE BOND", DEBT_CORPORATE_BOND),
            Map.entry("DEBT SCHEME CREDIT RISK", DEBT_CREDIT_RISK),
            Map.entry("INCOME DEBT ORIENTED SCHEME CREDIT RISK", DEBT_CREDIT_RISK),
            Map.entry("DEBT SCHEME BANKING AND PSU", DEBT_BANKING_PSU),
            Map.entry("INCOME DEBT ORIENTED SCHEME BANKING AND PSU DEBT", DEBT_BANKING_PSU),
            Map.entry("DEBT SCHEME GILT", DEBT_GILT),
            Map.entry("INCOME DEBT ORIENTED SCHEME GILT", DEBT_GILT),
            Map.entry("GILT", DEBT_GILT),
            Map.entry("INCOME DEBT ORIENTED SCHEME 10 YEAR CONSTANT MATURITY GILT", DEBT_GILT_10Y),
            Map.entry("DEBT SCHEME FLOATER", DEBT_FLOATER),
            Map.entry("INCOME DEBT ORIENTED SCHEME FLOATING INTEREST RATE", DEBT_FLOATER),

            // The straddling bucket: three months to three years in one header, 255 rows. Named,
            // and refused a horizon rather than filed under whichever neighbour looks closer.
            Map.entry("INCOME DEBT ORIENTED SCHEME ULTRA SHORT TO SHORT TERM", DEBT),
            Map.entry("INCOME", DEBT),

            // ---------------------------------------------------------------- hybrid, both spellings
            Map.entry("HYBRID SCHEME ARBITRAGE", HYBRID_ARBITRAGE),
            Map.entry("HYBRID SCHEME CONSERVATIVE HYBRID", HYBRID_CONSERVATIVE),
            Map.entry("HYBRID SCHEME EQUITY SAVING", HYBRID_EQUITY_SAVINGS),
            Map.entry("HYBRID SCHEME BALANCED HYBRID", HYBRID_BALANCED),
            Map.entry("HYBRID SCHEME AGGRESSIVE HYBRID", HYBRID_AGGRESSIVE),
            Map.entry("HYBRID SCHEME MULTI ASSET ALLOCATION", HYBRID_MULTI_ASSET),
            Map.entry("HYBRID SCHEME DYNAMIC ASSET ALLOCATION OR BALANCED ADVANTAGE",
                    HYBRID_BALANCED_ADVANTAGE),
            Map.entry("HYBRID SCHEME BALANCED ADVANTAGE FUND DYNAMIC ASSET ALLOCATION",
                    HYBRID_BALANCED_ADVANTAGE),
            Map.entry("BALANCED", HYBRID),

            // Close-ended blocks print the bare category with no "Equity Scheme" prefix.
            Map.entry("ELSS", ELSS),
            Map.entry("GROWTH", LEGACY_PRE_2018)
    );

    /**
     * Coarse keyword rules for whatever {@link #ALIASES} misses. Insertion-ordered and
     * first-hit-wins, so the most specific words come first.
     *
     * <p><b>The ordering carries real weight in debt.</b> Every pre-2018 debt header contains the
     * string {@code DEBT} (they all read "Income/Debt Oriented Schemes - ..."), so a bare
     * {@code DEBT} rule placed early would swallow every one of them and collapse fourteen SEBI
     * categories into one bucket - while nothing threw and the row count stayed right. {@code DEBT}
     * is therefore the <em>last</em> debt rule, reached only when no mandate word matched, and it
     * means exactly what its label says: debt, category not identified.
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

        // Hybrid, specific mandate first.
        KEYWORDS.put("EQUITY SAVING", HYBRID_EQUITY_SAVINGS);
        KEYWORDS.put("ARBITRAGE", HYBRID_ARBITRAGE);
        KEYWORDS.put("BALANCED ADVANTAGE", HYBRID_BALANCED_ADVANTAGE);
        KEYWORDS.put("CONSERVATIVE HYBRID", HYBRID_CONSERVATIVE);
        KEYWORDS.put("AGGRESSIVE HYBRID", HYBRID_AGGRESSIVE);
        KEYWORDS.put("MULTI ASSET", HYBRID_MULTI_ASSET);
        KEYWORDS.put("BALANCED", HYBRID);
        KEYWORDS.put("HYBRID", HYBRID);

        // Debt, specific mandate first. Every one of these must precede the bare DEBT below.
        KEYWORDS.put("OVERNIGHT", DEBT_OVERNIGHT);
        KEYWORDS.put("LIQUID", DEBT_LIQUID);
        KEYWORDS.put("ULTRA SHORT", DEBT_ULTRA_SHORT);
        KEYWORDS.put("LOW DURATION", DEBT_LOW_DURATION);
        KEYWORDS.put("MONEY MARKET", DEBT_MONEY_MARKET);
        KEYWORDS.put("CORPORATE BOND", DEBT_CORPORATE_BOND);
        KEYWORDS.put("CREDIT RISK", DEBT_CREDIT_RISK);
        KEYWORDS.put("BANKING AND PSU", DEBT_BANKING_PSU);
        KEYWORDS.put("CONSTANT MATURITY", DEBT_GILT_10Y);
        KEYWORDS.put("GILT", DEBT_GILT);
        KEYWORDS.put("FLOATER", DEBT_FLOATER);
        // Pre-2018 spelling of the same thing.
        KEYWORDS.put("FLOATING", DEBT_FLOATER);
        KEYWORDS.put("DYNAMIC BOND", DEBT_DYNAMIC_BOND);

        // The catch-alls. Anything reaching here is debt whose mandate was not stated in the
        // header, which is a finding about the header rather than about the scheme.
        KEYWORDS.put("DEBT", DEBT);
        KEYWORDS.put("BOND", DEBT);
        KEYWORDS.put("DURATION", DEBT);
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
     *
     * <p>{@code SAVINGS} folds to {@code SAVING} for the same reason the plurals do: AMFI writes
     * both {@code Hybrid Scheme - Equity Savings} and {@code Hybrid Schemes - Equity Savings Fund}.
     */
    static String normalise(String text) {
        String s = text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9&]+", " ").trim();
        s = s.replaceAll("\\bSCHEMES\\b", "SCHEME").replaceAll("\\bFUNDS\\b", "FUND");
        s = s.replaceAll("\\bSAVINGS\\b", "SAVING").replaceAll("\\bRATES\\b", "RATE");
        s = s.replaceAll("\\s+", " ").trim();
        while (s.endsWith(" FUND")) {
            s = s.substring(0, s.length() - " FUND".length()).trim();
        }
        return s;
    }
}
