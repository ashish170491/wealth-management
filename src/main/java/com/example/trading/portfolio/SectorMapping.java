package com.example.trading.portfolio;

import java.util.Locale;
import java.util.Map;

/**
 * Maps granular NSE industry labels (returned by Kite Connect) to the simple
 * user-facing sector buckets a retail investor typically thinks in (IT, BANKING,
 * METALS, ...). Unmapped industries fall through to their raw name so no holding
 * is ever lost.
 *
 * <p>This fixes the taxonomy mismatch that caused every simple sector target
 * to report 0% actual weight (SPEC.md 5 known gap, reported 2026-04-18).
 *
 * <p><b>B-096 (2026-09-09): the fix above only covered one of the three vocabularies.</b>
 * A holding's {@code industry} rarely carries an NSE label at all: it is written from
 * {@code StockValuationService.getIndustryForSymbol}, whose fallback is a ~20-name table
 * emitting {@code "METALS"}, {@code "BANKS"}, {@code "REFINERIES"} and, for everything else,
 * {@code "GENERAL"} - while the screener's {@code STOCK_SECTOR_MAP} spells the same sectors
 * {@code "Metals"}, {@code "Banking"}, {@code "Energy"}. The profile targets are seeded as
 * {@code IT / BANKING / METALS / ...}. Matching was case-sensitive and exact, so on the live
 * portfolio every target read <em>actual 0%</em>, every real sector read <em>no target</em>,
 * and 42% of the money sat in {@code GENERAL} + {@code Other}. Three panels inherited it: the
 * drift table, the sector donut and the rebalance plan.
 *
 * <p>Two rules now: matching is case-insensitive over every spelling the codebase emits, and a
 * placeholder ({@code GENERAL}, {@code Other}, blank) resolves to {@link #UNKNOWN} - which the
 * drift engine reports as <em>unclassified</em> with its weight and names, never as a sector
 * called "Other" that happens to be 42% of the book (Gotcha 21: null is not zero, and here a
 * placeholder is not a sector).
 */
public final class SectorMapping {

    private SectorMapping() {}

    public static final String UNKNOWN = "UNKNOWN";

    /**
     * The short labels emitted by the screener's sector map, the valuation fallback table and
     * the profile seed, folded to upper-case. Kept separate from {@link #DEFAULTS} so the NSE
     * long-name table stays a faithful copy of NSE's vocabulary.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("IT", "IT"),
            Map.entry("BANKING", "BANKING"),
            Map.entry("BANKS", "BANKING"),
            Map.entry("BANK", "BANKING"),
            Map.entry("FINANCE", "FINANCIALS"),
            Map.entry("FINANCIALS", "FINANCIALS"),
            Map.entry("NBFC", "FINANCIALS"),
            Map.entry("INSURANCE", "FINANCIALS"),
            Map.entry("METALS", "METALS"),
            Map.entry("METAL", "METALS"),
            Map.entry("MINING", "METALS"),
            Map.entry("ENERGY", "ENERGY"),
            Map.entry("POWER", "ENERGY"),
            Map.entry("OIL & GAS", "ENERGY"),
            Map.entry("OIL AND GAS", "ENERGY"),
            Map.entry("REFINERIES", "ENERGY"),
            Map.entry("UTILITIES", "ENERGY"),
            Map.entry("AUTO", "AUTO"),
            Map.entry("AUTOMOBILE", "AUTO"),
            Map.entry("AUTOMOBILES", "AUTO"),
            Map.entry("PHARMA", "PHARMA"),
            Map.entry("PHARMACEUTICALS", "PHARMA"),
            Map.entry("HEALTHCARE", "PHARMA"),
            Map.entry("CONSUMER", "CONSUMER"),
            Map.entry("CONSUMER DURABLES", "CONSUMER_DURABLES"),
            Map.entry("CONSUMER_DURABLES", "CONSUMER_DURABLES"),
            Map.entry("RETAIL", "RETAIL"),
            Map.entry("FMCG", "FMCG"),
            Map.entry("TELECOM", "TELECOM"),
            Map.entry("DEFENCE", "DEFENSE"),
            Map.entry("DEFENSE", "DEFENSE"),
            Map.entry("CAPITAL GOODS", "CAPITAL_GOODS"),
            Map.entry("CAPITAL_GOODS", "CAPITAL_GOODS"),
            Map.entry("INFRASTRUCTURE", "INFRASTRUCTURE"),
            Map.entry("INFRA", "INFRASTRUCTURE"),
            Map.entry("CEMENT", "INFRASTRUCTURE"),
            Map.entry("CONSTRUCTION", "INFRASTRUCTURE"),
            Map.entry("LOGISTICS", "LOGISTICS"),
            Map.entry("REALTY", "REALTY"),
            Map.entry("REAL ESTATE", "REALTY"),
            Map.entry("CHEMICALS", "CHEMICALS"),
            Map.entry("CHEMICAL", "CHEMICALS"),
            Map.entry("MEDIA", "MEDIA"),
            Map.entry("TEXTILES", "TEXTILES"),
            Map.entry("TEXTILE", "TEXTILES"),
            Map.entry("AGRI", "AGRI"),
            Map.entry("AGRICULTURE", "AGRI"),
            // NSE's index-constituent macro sectors, the vocabulary of universe-sectors.csv
            // (SPEC 27.2, B-098). Folded into the same buckets the profile targets use so the
            // screener, the portfolio and the drift table cannot group one stock two ways.
            Map.entry("FINANCIAL SERVICES", "FINANCIALS"),
            Map.entry("FAST MOVING CONSUMER GOODS", "FMCG"),
            Map.entry("CONSUMER SERVICES", "CONSUMER_SERVICES"),
            Map.entry("AUTOMOBILE AND AUTO COMPONENTS", "AUTO"),
            Map.entry("INFORMATION TECHNOLOGY", "IT"),
            Map.entry("SERVICES", "SERVICES"),
            Map.entry("METALS & MINING", "METALS"),
            Map.entry("CONSTRUCTION MATERIALS", "INFRASTRUCTURE"),
            Map.entry("OIL GAS & CONSUMABLE FUELS", "ENERGY"),
            Map.entry("TELECOMMUNICATION", "TELECOM"),
            Map.entry("MEDIA ENTERTAINMENT & PUBLICATION", "MEDIA"),
            Map.entry("DIVERSIFIED", "DIVERSIFIED"),
            Map.entry("FOREST MATERIALS", "MATERIALS")
    );

    /**
     * Strings that mean "no sector was recorded", not a sector. {@code GENERAL} is the valuation
     * fallback's default and {@code Other} is what the screener writes for anything it did not
     * classify - both are placeholders and must never become a bucket called "Other" that
     * silently absorbs half the portfolio.
     */
    private static final java.util.Set<String> PLACEHOLDERS = java.util.Set.of(
            "GENERAL", "OTHER", "OTHERS", "UNKNOWN", "UNCLASSIFIED", "N/A", "NA", "-", "NONE");

    /** Default NSE-industry → simple-sector map. Keys are case-sensitive exact matches. */
    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            // IT
            Map.entry("Computers - Software & Consulting", "IT"),
            Map.entry("IT Enabled Services", "IT"),

            // BANKING
            Map.entry("Private Sector Bank", "BANKING"),
            Map.entry("Public Sector Bank", "BANKING"),

            // FINANCIALS (non-bank)
            Map.entry("Non Banking Financial Company (NBFC)", "FINANCIALS"),
            Map.entry("Financial Institution", "FINANCIALS"),
            Map.entry("Investment Company", "FINANCIALS"),
            Map.entry("General Insurance", "FINANCIALS"),
            Map.entry("Life Insurance", "FINANCIALS"),
            Map.entry("Other Financial Services", "FINANCIALS"),

            // METALS
            Map.entry("Aluminium", "METALS"),
            Map.entry("Iron & Steel", "METALS"),
            Map.entry("Zinc", "METALS"),
            Map.entry("Copper", "METALS"),
            Map.entry("Trading - Minerals", "METALS"),
            Map.entry("Mining", "METALS"),
            Map.entry("Other Metals", "METALS"),

            // ENERGY (includes power, oil & gas, coal)
            Map.entry("Refineries & Marketing", "ENERGY"),
            Map.entry("Integrated Power Utilities", "ENERGY"),
            Map.entry("Power Generation", "ENERGY"),
            Map.entry("Power - Transmission", "ENERGY"),
            Map.entry("Coal", "ENERGY"),
            Map.entry("LPG/CNG/PNG/LNG Supplier", "ENERGY"),
            Map.entry("Oil Exploration", "ENERGY"),
            Map.entry("Oil Drilling & Allied Services", "ENERGY"),

            // AUTO
            Map.entry("Passenger Cars & Utility Vehicles", "AUTO"),
            Map.entry("Commercial Vehicles", "AUTO"),
            Map.entry("Auto Components & Equipments", "AUTO"),
            Map.entry("2/3 Wheelers", "AUTO"),
            Map.entry("Tyres & Rubber Products", "AUTO"),

            // PHARMA
            Map.entry("Pharmaceuticals", "PHARMA"),
            Map.entry("Healthcare Services", "PHARMA"),
            Map.entry("Hospitals & Diagnostic Centres", "PHARMA"),
            Map.entry("Other Pharmaceuticals", "PHARMA"),

            // FMCG / CONSUMER
            Map.entry("Diversified FMCG", "FMCG"),
            Map.entry("Packaged Foods", "FMCG"),
            Map.entry("Tea & Coffee", "FMCG"),
            Map.entry("Sugar", "FMCG"),
            Map.entry("Other Agricultural Products", "FMCG"),
            Map.entry("Tobacco Products", "FMCG"),
            Map.entry("Alcoholic Beverages", "FMCG"),
            Map.entry("Household Appliances", "CONSUMER_DURABLES"),
            Map.entry("Consumer Electronics", "CONSUMER_DURABLES"),
            Map.entry("Diversified Retail", "RETAIL"),
            Map.entry("Other Retail", "RETAIL"),
            Map.entry("E-Retail/ E-Commerce", "RETAIL"),

            // TELECOM
            Map.entry("Telecom - Cellular & Fixed line services", "TELECOM"),
            Map.entry("Telecom - Infrastructure", "TELECOM"),
            Map.entry("Telecom - Equipment & Accessories", "TELECOM"),

            // DEFENSE / INDUSTRIALS
            Map.entry("Aerospace & Defense", "DEFENSE"),
            Map.entry("Ship Building & Allied Services", "DEFENSE"),
            Map.entry("Other Electrical Equipment", "CAPITAL_GOODS"),
            Map.entry("Heavy Electrical Equipment", "CAPITAL_GOODS"),
            Map.entry("Industrial Machinery", "CAPITAL_GOODS"),
            Map.entry("Civil Construction", "INFRASTRUCTURE"),
            Map.entry("Construction & Engineering", "INFRASTRUCTURE"),
            Map.entry("Cement & Cement Products", "INFRASTRUCTURE"),

            // LOGISTICS
            Map.entry("Road Transport", "LOGISTICS"),
            Map.entry("Shipping", "LOGISTICS"),
            Map.entry("Airport Services", "LOGISTICS"),
            Map.entry("Logistics Services", "LOGISTICS"),

            // REALTY
            Map.entry("Realty", "REALTY"),
            Map.entry("Residential, Commercial Projects", "REALTY"),

            // CHEMICALS
            Map.entry("Commodity Chemicals", "CHEMICALS"),
            Map.entry("Specialty Chemicals", "CHEMICALS"),
            Map.entry("Fertilisers", "CHEMICALS"),
            Map.entry("Agrochemicals", "CHEMICALS"),

            // MEDIA
            Map.entry("Broadcasting & Cable TV", "MEDIA"),
            Map.entry("Movies & Entertainment", "MEDIA"),
            Map.entry("Print Media", "MEDIA")
    );

    /**
     * Normalize an industry string - an NSE long name, a screener short label, a valuation
     * fallback label or a profile target key - to one simple sector.
     *
     * <p>Returns {@link #UNKNOWN} for null, blank or a placeholder such as {@code GENERAL}; the
     * exact NSE mapping when one exists; the alias mapping (case-insensitive) otherwise; and
     * the raw value upper-cased with spaces as underscores as a last resort, so a sector the
     * tables have not heard of is still shown rather than dropped. Upper-casing the fallback is
     * what lets a target seeded as {@code TELECOM} match a holding written as {@code Telecom}.
     */
    public static String normalize(String rawIndustry) {
        if (rawIndustry == null || rawIndustry.isBlank()) return UNKNOWN;
        String trimmed = rawIndustry.trim();
        String exact = DEFAULTS.get(trimmed);
        if (exact != null) return exact;
        String upper = trimmed.toUpperCase(Locale.ROOT);
        if (PLACEHOLDERS.contains(upper)) return UNKNOWN;
        String alias = ALIASES.get(upper);
        if (alias != null) return alias;
        return upper.replaceAll("[\\s/&-]+", "_").replaceAll("_+", "_");
    }

    /**
     * Resolves a holding's sector from what it carries and, when that is a placeholder, from the
     * screener's own sector table for the symbol. The screener's table is consulted second, not
     * first: an industry the valuation feed actually recorded outranks a hardcoded list.
     */
    public static String resolve(String symbol, String rawIndustry) {
        String fromIndustry = normalize(rawIndustry);
        if (!UNKNOWN.equals(fromIndustry)) return fromIndustry;
        String fromScreener = com.example.trading.multibagger.MultibaggerScreenerService.sectorFor(symbol);
        return normalize(fromScreener);
    }

    /** True when the normalised sector is the "nothing recorded" marker. */
    public static boolean isUnknown(String normalisedSector) {
        return normalisedSector == null || UNKNOWN.equals(normalisedSector);
    }

    /** Unmodifiable view of the default mapping for diagnostic endpoints. */
    public static Map<String, String> defaults() {
        return DEFAULTS;
    }

    /**
     * Every bucket {@link #normalize} can produce from a known spelling, plus {@link #UNKNOWN}.
     *
     * <p>Exists so a hand-written table keyed on sector buckets can be validated at boot rather
     * than failing silently at read time (SPEC 48.3). {@code normalize} never returns null - an
     * unrecognised industry falls through to its own upper-cased name - so a typo in such a table
     * would otherwise simply never match anything and the signal would read "not measured" for
     * ever, on every stock, with nothing logged. That failure has happened three times in this
     * codebase already (Gotcha 94); this is the cheap way to make it a startup error instead.
     */
    public static java.util.Set<String> knownBuckets() {
        java.util.Set<String> out = new java.util.TreeSet<>(DEFAULTS.values());
        out.addAll(ALIASES.values());
        out.add(UNKNOWN);
        return java.util.Collections.unmodifiableSet(out);
    }
}
