package com.example.trading.universe.theme;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The twelve policy-backed themes this app tracks, and the one sentence of caution each one needs
 * (SPEC §51.2).
 *
 * <p><b>Why an enum and not a free-text column.</b> The theme name is rendered on six screens and
 * filtered on four. A typo in the CSV would otherwise create a thirteenth theme containing one
 * stock, which looks exactly like a real theme nobody has populated yet — the failure shape of
 * B-074, where a signal measured on nothing read as a signal that found nothing. {@link
 * UniverseThemes} rejects a row whose theme is not listed here and says which line it was on,
 * the same fail-loud contract {@code MacroExposureMap} uses.
 *
 * <p><b>The caution is not decoration.</b> Every theme here has a structural reason it could
 * disappoint, and it is usually the same reason the government is funding it: the demand is
 * policy-created, so policy can withdraw it. SPEC §21 requires the reader be told what a thing
 * means, and what "policy-backed" means is a demand signal with a political dependency — never a
 * quality signal. The dashboard prints this line beside the theme rather than hiding it in a
 * tooltip, because the investor most likely to act on a theme list is the one least likely to
 * hunt for its caveat.
 *
 * <p>Contributes zero points to any score, as does everything else in SPEC §51.
 */
public enum ThemeCatalog {

    SEMICONDUCTORS(
            "Semiconductors",
            "India Semiconductor Mission, Modified SPECS, Design-Linked Incentive",
            "India has no volume fab in production yet. Nearly every listed name is assembly and "
                    + "test or design services, and for the largest companies here it is a small "
                    + "corner of a much bigger business — check the role before reading a share "
                    + "price as a bet on chips."),

    ELECTRONICS_EMS(
            "Electronics manufacturing",
            "Large-Scale Electronics Manufacturing PLI, IT Hardware PLI 2.0, SPECS",
            "Contract manufacturing earns thin margins by design and makes its return on how "
                    + "hard the assets work. Revenue can compound while capital employed compounds "
                    + "faster, so read return on capital rather than the growth rate."),

    AI_DATA_CENTRES(
            "AI and data centres",
            "IndiaAI Mission, IT Hardware PLI 2.0, state data-centre policies",
            "Three quite different businesses sit under one label: whoever builds the shell, "
                    + "whoever powers and cools it, and whoever sells the servers. Only the third "
                    + "is really an AI business, and for several names here the exposure is a "
                    + "minority of revenue."),

    WATER_INFRASTRUCTURE(
            "Water infrastructure",
            "Jal Jeevan Mission, AMRUT 2.0, Namami Gange, PM-KUSUM",
            "Demand is almost entirely government-funded, so the customer that places the order "
                    + "is also the customer that pays late. Read receivables and working capital "
                    + "before reading the order book."),

    DEFENCE_INDIGENISATION(
            "Defence indigenisation",
            "Positive indigenisation lists, DAP 2020, iDEX",
            "Order books are long and lumpy. A single contract can flatter one year's growth and "
                    + "its absence can wreck the next, so judge these over a cycle rather than a "
                    + "quarter."),

    RAILWAY_MODERNISATION(
            "Railway modernisation",
            "National Rail Plan, Kavach mandate, Vande Bharat and freight corridors",
            "Nearly every rupee of revenue comes from one customer. That is a concentration risk "
                    + "no diversification within the theme can reduce — owning four railway "
                    + "suppliers is owning one counterparty four times."),

    SOLAR_MANUFACTURING(
            "Solar manufacturing",
            "PLI for High Efficiency Solar PV Modules, ALMM list, customs duty on imports",
            "The economics rest on a tariff wall against cheaper imported cells. That wall is a "
                    + "policy choice, and the theme's main risk is the day it moves."),

    WIND_ENERGY(
            "Wind energy",
            "RLMM list, inter-state transmission charge waiver, repowering policy",
            "This industry has been through one full boom and bust in India already, and the "
                    + "survivors carry the balance sheets to prove it. Read leverage and past "
                    + "restructurings, not just current order inflow."),

    GREEN_HYDROGEN(
            "Green hydrogen",
            "National Green Hydrogen Mission, SIGHT electrolyser incentives",
            "The earliest theme here. For most of these companies the exposure is an announced "
                    + "intention rather than revenue on the books, so the tag marks who has "
                    + "committed capital, not who is earning from it."),

    POWER_TRANSMISSION(
            "Power transmission and grid",
            "National Electricity Plan, Green Energy Corridor, RDSS smart metering",
            "The least fashionable theme and the one the other energy themes depend on — "
                    + "renewables only pay if the grid can carry them. For several names the grid "
                    + "is a minority of a wider cables or industrial business."),

    EV_BATTERY(
            "Electric vehicles and batteries",
            "PM E-DRIVE, Advanced Chemistry Cell battery PLI, Auto PLI",
            "Incentives here have been revised repeatedly and retrospectively. Cell manufacturing "
                    + "is also a capital race against imports, so treat announced capacity as a "
                    + "cost commitment before it is a revenue opportunity."),

    PHARMA_API(
            "Pharma APIs and key inputs",
            "PLI for Bulk Drugs and Key Starting Materials, Bulk Drug Parks",
            "Import substitution against Chinese supply. These are commodity chemicals once "
                    + "domestic capacity arrives, so the incentive window and the pricing that "
                    + "follows it matter more than current margins.");

    private final String label;
    private final String policy;
    private final String caution;

    ThemeCatalog(String label, String policy, String caution) {
        this.label = label;
        this.policy = policy;
        this.caution = caution;
    }

    /** Human wording for every screen. Never render the enum name at the investor. */
    public String label() {
        return label;
    }

    /** The schemes that make this a policy-backed theme, summarised for a heading. */
    public String policy() {
        return policy;
    }

    /** Why this theme could disappoint, in one sentence. Shown, never hidden behind a tooltip. */
    public String caution() {
        return caution;
    }

    /** Parse a CSV theme token; empty when the token names no theme in this catalogue. */
    public static Optional<ThemeCatalog> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String t = token.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(v -> v.name().equals(t)).findFirst();
    }
}
