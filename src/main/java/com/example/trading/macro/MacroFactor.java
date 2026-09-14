package com.example.trading.macro;

import java.util.Locale;
import java.util.Optional;

/**
 * The macro and geopolitical forces this app is willing to reason about (SPEC 48.1).
 *
 * <p><b>Every factor is defined as a quantity that can rise or fall</b>, and {@link #risesMeans()}
 * says which way is "up". That is the whole trick that makes the exposure map possible: a headline
 * is turned into (factor, direction) and the map already knows, for each business, whether a rise
 * helps or hurts it. Sentiment cannot do this. "Rupee falls" is bad news for the country and good
 * news for an exporter, and a keyword scorer that reads the word "falls" gets it backwards for
 * half the portfolio - which is why the two keyword-sentiment engines this app used to have were
 * deleted on 2026-09-03 (SPEC 39.2, 39.5) rather than repaired.
 *
 * <p><b>The list is deliberately short and deliberately coarse.</b> These are forces that move a
 * whole sector's economics for quarters, not events that move one share price for a day. There is
 * no factor for "results season", "index rebalancing" or "FII flows": those are market weather,
 * and a five-to-ten-year holding decision does not turn on them.
 *
 * <p>{@code US_RATES} is the one concession, scored LOW everywhere in the map on purpose. It earns
 * its place because the calendar should be able to show an FOMC date, not because a Fed decision
 * should change what anyone owns.
 */
public enum MacroFactor {

    // ---- input costs -------------------------------------------------------------------------
    CRUDE_OIL("Crude oil", "the price of Brent crude goes up",
            "India imports most of its oil, so a rise moves fuel, freight, plastics and paint costs."),
    METALS_PRICES("Metal and commodity prices", "base metal prices on the LME go up",
            "Good for the miners and smelters, a cost for everyone who buys metal to build things."),
    COAL_POWER_PRICES("Coal and power prices", "imported coal or merchant power tariffs go up",
            "Power is a big share of cost for smelters and cement; for a coal producer it is revenue."),
    GOLD("Gold", "the gold price goes up",
            "Moves the loan book of gold-lenders and the inventory and demand of jewellers in opposite ways."),
    FOOD_INFLATION("Food inflation", "food prices in the CPI basket go up",
            "Squeezes packaged-food and restaurant margins, and keeps the RBI from cutting rates."),

    // ---- money -------------------------------------------------------------------------------
    INTEREST_RATES("RBI interest rates", "the RBI repo rate goes up",
            "Dearer money hurts anyone who borrows to buy or to build; lenders reprice on their own schedule."),
    USDINR("Rupee vs dollar", "the rupee weakens against the dollar",
            "Helps exporters who earn dollars, hurts anyone paying for imports in them."),
    US_RATES("US Fed rates", "the US Fed raises rates or turns hawkish",
            "Pulls foreign money out of Indian shares and weakens the rupee. Weather, not a thesis."),

    // ---- trade and demand --------------------------------------------------------------------
    US_TARIFFS("US tariffs", "the United States raises tariffs on Indian goods",
            "A direct tax on what Indian exporters sell there, paid out of somebody's margin."),
    TRADE_BARRIERS_CHINA("Chinese import pressure", "cheap Chinese imports rise or a safeguard duty is removed",
            "Undercuts Indian steel and chemicals on price. A duty imposed is this factor falling."),
    GLOBAL_DEMAND_SLOWDOWN("Global demand slowdown", "recession risk in the US and Europe rises",
            "Client budgets get cut. Reaches IT services, chemicals and auto components first."),

    // ---- the state ---------------------------------------------------------------------------
    GOVT_CAPEX("Government capital spending", "the government raises capital spending or announces orders",
            "Roads, railways and power lines are somebody's order book."),
    DEFENCE_SPEND("Defence spending", "the defence budget or order flow rises",
            "A small set of listed manufacturers builds nearly all of it."),

    // ---- conflict ----------------------------------------------------------------------------
    GEOPOLITICAL_CONFLICT_REGIONAL("Conflict near India", "tension on India's borders escalates",
            "Closed airspace, deferred spending, and orders for the defence makers."),
    GEOPOLITICAL_CONFLICT_GLOBAL("Conflict elsewhere in the world", "a major conflict escalates outside the region",
            "Reaches India through shipping, fertiliser and fuel imports rather than directly."),

    // ---- weather -----------------------------------------------------------------------------
    MONSOON_DEFICIT("Monsoon shortfall", "monsoon rainfall runs below the long-period average",
            "Rural incomes fall a season before tractor, two-wheeler and rural FMCG volumes do."),

    // ---- rules -------------------------------------------------------------------------------
    REGULATORY_TELECOM("Telecom regulation", "a rule or dues demand goes against the telecom operators",
            "One regulator decides the economics of a three-company industry."),
    REGULATORY_CAPITAL_MARKETS("Market regulation", "SEBI tightens trading rules or transaction taxes rise",
            "Exchanges, depositories and brokers earn per trade, so fewer trades is less revenue."),
    REGULATORY_NBFC("Lending regulation", "the RBI tightens risk weights or provisioning",
            "Raises the capital a lender must hold against the same book, which slows growth."),
    REGULATORY_SIN_GOODS("Tobacco and alcohol taxes", "excise or GST on tobacco and alcohol goes up",
            "A tax rise these companies must either absorb or pass on to a price-sensitive buyer."),
    REGULATORY_PHARMA_USFDA("US drug regulation", "the USFDA acts against a plant, or price controls tighten",
            "A single plant can carry a large share of a company's US revenue.");

    private final String label;
    private final String risesMeans;
    private final String plainEnglish;

    MacroFactor(String label, String risesMeans, String plainEnglish) {
        this.label = label;
        this.risesMeans = risesMeans;
        this.plainEnglish = plainEnglish;
    }

    /** Human label for reports and screens. Never render the enum name at an investor. */
    public String label() {
        return label;
    }

    /** What "this factor rose" means in words, so a direction can never be read backwards. */
    public String risesMeans() {
        return risesMeans;
    }

    /** One sentence on why a long-term investor should care. Used in the guide and glossary. */
    public String plainEnglish() {
        return plainEnglish;
    }

    /** Lenient parse for CSV cells and model output; empty when the value is not a known factor. */
    public static Optional<MacroFactor> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (MacroFactor f : values()) {
            if (f.name().equals(key)) return Optional.of(f);
        }
        return Optional.empty();
    }
}
