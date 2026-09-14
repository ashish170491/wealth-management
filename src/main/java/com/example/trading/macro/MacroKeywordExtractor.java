package com.example.trading.macro;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The fallback event extractor: rules, no model (SPEC 48.4).
 *
 * <p>This runs when {@code spring.ai.model.chat} is {@code none}, when no key is configured, and
 * whenever a model call fails or returns something unparseable. Every event it produces is stamped
 * {@code KEYWORD} so a reader can see which reader read it, and it reports <b>no confidence figure
 * at all</b> rather than an invented one - a keyword match has no calibrated probability behind it,
 * and printing 0.4 as though it were measured would be exactly the kind of confident-looking number
 * SPEC 21 rule 7 exists to forbid.
 *
 * <p><b>Every pattern carries its own direction.</b> That is the important design choice here. The
 * predecessor keyword engines - deleted on 2026-09-03 (SPEC 39.2, 39.5) - matched a topic and then
 * scored a separate sentiment, which is how "rupee falls" became a bearish reading for an exporter
 * it was good news for. Here "rupee weakens" IS the USDINR-rises pattern, and which businesses that
 * helps or hurts is decided later, by the map, per stock.
 *
 * <p><b>It refuses the conditional.</b> A headline containing "expected to", "likely", "ahead of"
 * or "rules out" describes an event that has not happened, and matching it would fill the ledger
 * with events that never occurred - the same class of error as B-081's "unqualified opinion"
 * matching "qualified opinion". Erring toward a miss is deliberate: a missed event costs a reading
 * this feature was never guaranteed to give, an invented one puts a false headwind against a stock.
 */
public final class MacroKeywordExtractor {

    private MacroKeywordExtractor() {
    }

    /** A headline as stored, with only the fields the extractor needs. */
    public record Headline(long id, String title, String description, String source, String url,
                           LocalDate publishedOn) {

        String text() {
            return ((title == null ? "" : title) + " " + (description == null ? "" : description))
                    .toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One extracted event.
     *
     * @param confidence null from this extractor, always. See the class note.
     */
    public record Extracted(MacroFactor factor, MacroDirection direction, MacroMagnitude magnitude,
                            MacroEventKind kind, String geography, LocalDate occurredAt,
                            List<Long> headlineIds, List<String> sourceUrls, Double confidence,
                            String summary) {
    }

    /** One directional pattern. The direction is part of the rule, never inferred afterwards. */
    private record Rule(MacroFactor factor, MacroDirection direction, Pattern pattern) {
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    /**
     * Headlines describing something that has not happened yet. Checked before any rule, because a
     * preview of Friday's policy is not Friday's policy.
     */
    private static final Pattern HYPOTHETICAL = p(
            "\\b(expected to|expects|likely to|may hike|may cut|could hike|could cut|forecasts?|"
                    + "poll|preview|ahead of|what to expect|rules out|ruled out|unchanged|status quo|"
                    + "no change|keeps? rates? (steady|unchanged)|holds? rates?|awaits?|anticipat|"
                    // "a rate hike could actually be bullish" - the NOUN form, which the verb
                    // cases above do not reach. Found on the first live run: it filed an RBI rate
                    // rise that had not happened, and five holdings read a headwind off it.
                    + "(hike|cut|rise|fall)s? (would|could|might|may)\\b)");

    /**
     * Commentary, not reporting - and that distinction is the whole point of the ledger.
     *
     * <p>A headline that opens by asking a question is a columnist arguing about what something
     * WOULD mean, and the keyword rules cannot tell that from a report of the thing happening:
     * the words are identical. "Why a rate hike could actually be bullish" is an opinion piece,
     * and on the first live run it was recorded as a rate rise.
     *
     * <p>Deliberately anchored at the START of the headline, so an ordinary report that happens
     * to contain "why" further along is untouched. The asymmetry is the reason it is worth
     * having: being wrong here costs one missed event, which the next ingest picks up from a
     * straight report of it, while being wrong the other way files a reading against hundreds of
     * stocks on something that never happened - and a filed reading is never retracted
     * (SPEC 48.7).
     */
    private static final Pattern OPINION_LEDE = p(
            "^\\s*(why |should |what if|is it time|how to|explained:|opinion:|analysis:|view:)");

    /** Words that make a move big, and words that make it small. Nothing in between is guessed. */
    private static final Pattern LARGE = p(
            "\\b(surge|surges|surged|plunge|plunges|plunged|crash|crashes|crashed|soar|soars|soared|"
                    + "record (high|low)|all-time (high|low)|sharply|steep|steeply|emergency|slump|"
                    + "slumps|slumped|collapse|collapses|collapsed|spike|spikes|spiked|biggest)");
    private static final Pattern SMALL = p(
            "\\b(edge|edges|edged|slightly|marginal|marginally|inch|inches|inched|modest|modestly|mild)");

    /** A published-calendar event. Everything else is a surprise. */
    private static final Pattern SCHEDULED = p(
            "\\b(mpc|monetary policy committee|policy (meet|review|decision)|union budget|budget \\d{4}|"
                    + "fomc|fed (meeting|decision)|cpi data|inflation data|retail inflation|iip|"
                    + "gdp data|imd forecast|gst collections)");

    private static final Pattern GEO_US = p("\\b(us |u\\.s\\.|united states|america|washington|fed|federal reserve|white house)");
    private static final Pattern GEO_CHINA = p("\\b(china|chinese|beijing)");
    private static final Pattern GEO_GLOBAL = p(
            "\\b(opec|middle east|israel|iran|gaza|russia|ukraine|red sea|taiwan|europe|global)");

    /**
     * The rule table. Ordered: the first factor to match a headline wins it, so a more specific
     * pattern must come before a broader one.
     */
    private static final List<Rule> RULES = List.of(
            // ---- interest rates ------------------------------------------------------------
            new Rule(MacroFactor.INTEREST_RATES, MacroDirection.UP,
                    p("\\b(repo rate hike|hikes? (the )?repo|raises? (the )?repo|rate hike|hikes? rates?|"
                            + "raises? (interest )?rates?|tightens? monetary|crr hike)")),
            new Rule(MacroFactor.INTEREST_RATES, MacroDirection.DOWN,
                    p("\\b(repo rate cut|cuts? (the )?repo|lowers? (the )?repo|rate cut|cuts? rates?|"
                            + "lowers? (interest )?rates?|eases? monetary|crr cut|slashes? rates?)")),

            // ---- US rates ------------------------------------------------------------------
            new Rule(MacroFactor.US_RATES, MacroDirection.UP,
                    p("\\b(fed (rate )?hike|fed raises|fed hikes|hawkish fed|powell.{0,30}(hawkish|hike))")),
            new Rule(MacroFactor.US_RATES, MacroDirection.DOWN,
                    p("\\b(fed (rate )?cut|fed cuts|fed lowers|dovish fed|powell.{0,30}(dovish|cut))")),

            // ---- currency ------------------------------------------------------------------
            // "The rupee weakened" IS the factor rising. Whether that helps a company is the map's job.
            new Rule(MacroFactor.USDINR, MacroDirection.UP,
                    p("\\brupee\\b.{0,40}\\b(falls?|fell|slides?|slid|weakens?|weakened|plunges?|plunged|"
                            + "sinks?|sank|tumbles?|tumbled|hits? (a )?(record|all-time|lifetime) low|"
                            + "at (a )?record low|depreciat)")),
            new Rule(MacroFactor.USDINR, MacroDirection.DOWN,
                    p("\\brupee\\b.{0,40}\\b(rises?|rose|gains?|gained|strengthens?|strengthened|"
                            + "recovers?|recovered|appreciat|rebounds?|rebounded)")),

            // ---- crude ---------------------------------------------------------------------
            new Rule(MacroFactor.CRUDE_OIL, MacroDirection.UP,
                    p("\\b(crude|brent|oil price)\\b.{0,40}\\b(rises?|rose|surges?|surged|jumps?|jumped|"
                            + "climbs?|climbed|spikes?|spiked|soars?|soared|higher|rally|rallies)")),
            new Rule(MacroFactor.CRUDE_OIL, MacroDirection.DOWN,
                    p("\\b(crude|brent|oil price)\\b.{0,40}\\b(falls?|fell|slides?|slid|drops?|dropped|"
                            + "plunges?|plunged|tumbles?|tumbled|eases?|eased|lower|slumps?|slumped)")),

            // ---- metals and gold -----------------------------------------------------------
            new Rule(MacroFactor.GOLD, MacroDirection.UP,
                    p("\\bgold\\b.{0,40}\\b(rises?|rose|surges?|surged|record high|climbs?|climbed|jumps?|jumped|rally|rallies)")),
            new Rule(MacroFactor.GOLD, MacroDirection.DOWN,
                    p("\\bgold\\b.{0,40}\\b(falls?|fell|slides?|slid|drops?|dropped|eases?|eased|tumbles?|tumbled)")),
            new Rule(MacroFactor.METALS_PRICES, MacroDirection.UP,
                    p("\\b(copper|aluminium|aluminum|zinc|nickel|lme|base metal|steel price)\\b.{0,40}"
                            + "\\b(rises?|rose|surges?|surged|jumps?|jumped|climbs?|climbed|higher|rally|rallies)")),
            new Rule(MacroFactor.METALS_PRICES, MacroDirection.DOWN,
                    p("\\b(copper|aluminium|aluminum|zinc|nickel|lme|base metal|steel price)\\b.{0,40}"
                            + "\\b(falls?|fell|slides?|slid|drops?|dropped|lower|tumbles?|tumbled|slumps?|slumped)")),

            // ---- coal and power ------------------------------------------------------------
            new Rule(MacroFactor.COAL_POWER_PRICES, MacroDirection.UP,
                    p("\\b(coal price|imported coal|power tariff|electricity tariff|thermal coal)\\b.{0,40}"
                            + "\\b(rises?|rose|surges?|surged|jumps?|jumped|higher|hiked?)")),
            new Rule(MacroFactor.COAL_POWER_PRICES, MacroDirection.DOWN,
                    p("\\b(coal price|imported coal|power tariff|electricity tariff|thermal coal)\\b.{0,40}"
                            + "\\b(falls?|fell|drops?|dropped|eases?|eased|lower)")),

            // ---- trade ---------------------------------------------------------------------
            new Rule(MacroFactor.US_TARIFFS, MacroDirection.UP,
                    p("\\b(tariffs? on india|tariffs? on indian|us tariff|american tariff|imposes? tariff|"
                            + "raises? tariff|tariff hike|reciprocal tariff)")),
            new Rule(MacroFactor.US_TARIFFS, MacroDirection.DOWN,
                    p("\\b(tariff (relief|rollback|exemption|cut)|removes? tariff|scraps? tariff|"
                            + "lowers? tariff|trade deal with (the )?us)")),
            new Rule(MacroFactor.TRADE_BARRIERS_CHINA, MacroDirection.UP,
                    p("\\b(chinese (imports|dumping|oversupply)|cheap imports from china|import surge from china|"
                            + "dumping from china)")),
            new Rule(MacroFactor.TRADE_BARRIERS_CHINA, MacroDirection.DOWN,
                    p("\\b(anti-?dumping dut|safeguard dut|minimum import price|curbs? on chinese imports)")),

            // ---- global demand -------------------------------------------------------------
            new Rule(MacroFactor.GLOBAL_DEMAND_SLOWDOWN, MacroDirection.UP,
                    p("\\b(us recession|global recession|recession (fears|risk|warning)|global slowdown|"
                            + "eurozone contraction|demand slowdown in (the )?(us|europe))")),

            // ---- conflict ------------------------------------------------------------------
            new Rule(MacroFactor.GEOPOLITICAL_CONFLICT_REGIONAL, MacroDirection.UP,
                    p("\\b(border (tension|clash|firing)|line of control|pakistan.{0,30}(strike|attack|tension)|"
                            + "india.{0,20}pakistan.{0,20}(tension|conflict)|airspace closed|cross-border strike)")),
            new Rule(MacroFactor.GEOPOLITICAL_CONFLICT_REGIONAL, MacroDirection.DOWN,
                    p("\\b(ceasefire (agreed|holds)|de-?escalat|peace (talks|deal).{0,30}(pakistan|border))")),
            new Rule(MacroFactor.GEOPOLITICAL_CONFLICT_GLOBAL, MacroDirection.UP,
                    p("\\b(missile strike|airstrike|invasion|war (breaks out|escalates)|escalates? in (gaza|ukraine|"
                            + "the middle east)|red sea attack|houthi|strait of hormuz|sanctions on (russia|iran))")),

            // ---- monsoon -------------------------------------------------------------------
            new Rule(MacroFactor.MONSOON_DEFICIT, MacroDirection.UP,
                    p("\\b(monsoon deficit|deficient (rain|monsoon)|below[- ]normal (rain|monsoon)|weak monsoon|"
                            + "poor (rains|monsoon)|drought|delayed monsoon|monsoon delay)")),
            new Rule(MacroFactor.MONSOON_DEFICIT, MacroDirection.DOWN,
                    p("\\b(above[- ]normal (rain|monsoon)|normal monsoon|surplus rain|good monsoon|"
                            + "monsoon revives?|abundant rain)")),

            // ---- food ----------------------------------------------------------------------
            new Rule(MacroFactor.FOOD_INFLATION, MacroDirection.UP,
                    p("\\b(food inflation|vegetable prices|onion prices|tomato prices|edible oil prices|"
                            + "wheat prices|pulses prices)\\b.{0,40}\\b(rises?|rose|surges?|surged|jumps?|jumped|higher|soars?|soared)")),
            new Rule(MacroFactor.FOOD_INFLATION, MacroDirection.DOWN,
                    p("\\b(food inflation|vegetable prices|edible oil prices)\\b.{0,40}"
                            + "\\b(falls?|fell|eases?|eased|cools?|cooled|drops?|dropped|lower)")),

            // ---- government spending -------------------------------------------------------
            new Rule(MacroFactor.GOVT_CAPEX, MacroDirection.UP,
                    p("\\b(capex (push|boost)|capital (expenditure|spending).{0,30}(raise|hike|increase|up)|"
                            + "infrastructure (push|spending boost)|approves? .{0,30}(highway|railway|metro) project)")),
            new Rule(MacroFactor.GOVT_CAPEX, MacroDirection.DOWN,
                    p("\\b(capex (cut|slowdown)|capital (expenditure|spending).{0,30}(cut|slashed|reduced))")),
            new Rule(MacroFactor.DEFENCE_SPEND, MacroDirection.UP,
                    p("\\b(defence (budget|order|contract|procurement).{0,30}(rise|hike|boost|approved|cleared)|"
                            + "defence ministry (clears|approves)|emergency procurement)")),

            // ---- rules ---------------------------------------------------------------------
            new Rule(MacroFactor.REGULATORY_TELECOM, MacroDirection.UP,
                    p("\\b(agr dues|spectrum (dues|charges).{0,30}(rise|demand)|telecom.{0,30}(penalty|levy))")),
            new Rule(MacroFactor.REGULATORY_TELECOM, MacroDirection.DOWN,
                    p("\\b(telecom tariff hike|tariff hike by (airtel|jio|vodafone)|agr (relief|waiver|moratorium))")),
            new Rule(MacroFactor.REGULATORY_CAPITAL_MARKETS, MacroDirection.UP,
                    p("\\b(sebi.{0,40}(curb|tighten|restrict|ban).{0,30}(f&o|derivative|option)|"
                            + "stt (hike|increase)|securities transaction tax.{0,20}(hike|raise)|"
                            + "sebi tightens?)")),
            new Rule(MacroFactor.REGULATORY_NBFC, MacroDirection.UP,
                    p("\\b(rbi.{0,40}(risk weight|provisioning|tightens?).{0,30}(nbfc|lender|unsecured|bank)|"
                            + "higher risk weights?|rbi curbs? (on )?(nbfc|lending))")),
            new Rule(MacroFactor.REGULATORY_NBFC, MacroDirection.DOWN,
                    p("\\b(rbi (eases?|relaxes?|rolls? back).{0,40}(risk weight|norms?|provisioning))")),
            new Rule(MacroFactor.REGULATORY_SIN_GOODS, MacroDirection.UP,
                    p("\\b((excise|gst).{0,30}(on )?(tobacco|cigarette|liquor|alcohol).{0,20}(hike|raise|increase)|"
                            + "sin (tax|goods).{0,20}(hike|raise))")),
            new Rule(MacroFactor.REGULATORY_PHARMA_USFDA, MacroDirection.UP,
                    p("\\b(usfda.{0,40}(warning letter|import alert|observations?|form 483)|"
                            + "us fda.{0,40}(warning letter|import alert)|nppa.{0,30}price (cap|control))")),
            new Rule(MacroFactor.REGULATORY_PHARMA_USFDA, MacroDirection.DOWN,
                    p("\\b(usfda.{0,30}(clears?|approval|resolves?)|import alert (lifted|revoked))"))
    );

    /**
     * True when a headline matches any rule. Used by the RSS scan to decide what is worth storing,
     * so the headline table does not fill with sport and celebrity news.
     */
    public static boolean touchesAnyFactor(String text) {
        if (text == null || text.isBlank()) return false;
        if (HYPOTHETICAL.matcher(text).find()) return false;
        if (OPINION_LEDE.matcher(text).find()) return false;
        for (Rule r : RULES) {
            if (r.pattern().matcher(text).find()) return true;
        }
        return false;
    }

    /**
     * Extract events from a batch of headlines.
     *
     * <p>Headlines naming the same factor moving the same way on the same day become one event, so
     * four outlets covering one rate cut do not become four rate cuts.
     */
    public static List<Extracted> extract(List<Headline> headlines) {
        if (headlines == null || headlines.isEmpty()) return List.of();

        Map<String, Draft> drafts = new LinkedHashMap<>();
        for (Headline h : headlines) {
            if (h == null) continue;
            String text = h.text();
            if (text.isBlank() || HYPOTHETICAL.matcher(text).find()
                    || OPINION_LEDE.matcher(text).find()) continue;

            Rule hit = null;
            for (Rule r : RULES) {
                if (r.pattern().matcher(text).find()) {
                    hit = r;
                    break;
                }
            }
            if (hit == null) continue;

            final Rule rule = hit;
            final LocalDate day = h.publishedOn() == null ? LocalDate.now() : h.publishedOn();
            String key = MacroEventDedup.key(rule.factor(), rule.direction(), day);
            Draft d = drafts.computeIfAbsent(key, k -> new Draft(rule.factor(), rule.direction(), day));
            d.absorb(h, text);
        }

        List<Extracted> out = new ArrayList<>();
        for (Draft d : drafts.values()) {
            out.add(d.build());
        }
        return List.copyOf(out);
    }

    /** Accumulates the headlines that turned out to describe one event. */
    private static final class Draft {
        private final MacroFactor factor;
        private final MacroDirection direction;
        private final LocalDate day;
        private final List<Long> ids = new ArrayList<>();
        private final List<String> urls = new ArrayList<>();
        // Starts unset, NOT at MODERATE. Seeding it at MODERATE and then only ever raising it
        // means a genuinely small move can never be reported as small - caught by its own test.
        private MacroMagnitude magnitude;
        private MacroEventKind kind = MacroEventKind.SURPRISE;
        private String geography = "India";
        private String summary;

        Draft(MacroFactor factor, MacroDirection direction, LocalDate day) {
            this.factor = factor;
            this.direction = direction;
            this.day = day;
        }

        void absorb(Headline h, String text) {
            ids.add(h.id());
            if (h.url() != null && !h.url().isBlank()) urls.add(h.url());
            if (summary == null && h.title() != null && !h.title().isBlank()) summary = h.title().trim();

            MacroMagnitude m = LARGE.matcher(text).find() ? MacroMagnitude.LARGE
                    : SMALL.matcher(text).find() ? MacroMagnitude.SMALL
                    : MacroMagnitude.MODERATE;
            if (magnitude == null || m.rank() > magnitude.rank()) magnitude = m;

            if (SCHEDULED.matcher(text).find()) kind = MacroEventKind.SCHEDULED;

            if (GEO_CHINA.matcher(text).find()) geography = "China";
            else if (GEO_US.matcher(text).find()) geography = "United States";
            else if (GEO_GLOBAL.matcher(text).find()) geography = "Global";
        }

        Extracted build() {
            // Confidence is deliberately null: a keyword match has no calibrated probability, and a
            // plausible-looking number here would be indistinguishable from a measured one.
            return new Extracted(factor, direction,
                    magnitude == null ? MacroMagnitude.MODERATE : magnitude, kind, geography, day,
                    List.copyOf(ids), List.copyOf(urls), null,
                    summary == null ? factor.label() + " " + direction.pastTense() : summary);
        }
    }
}
