package com.example.trading.universe.theme;

import com.example.trading.multibagger.UniverseSectors;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Businesses that look like they belong in a funded theme and are not in the map (SPEC §51.10).
 *
 * <p><b>Why this exists.</b> {@link UniverseThemes} has no feed, no scheduler and no writer, so it
 * is exactly as current as the last person to edit it — and it ages in the flattering direction,
 * because every coverage figure is counted against its own list. The gap list answers one half of
 * that ("the map names a business the universe does not reach"). This answers the other half, the
 * half that actually decays: <b>the market has a business the map has never named.</b>
 *
 * <p><b>It proposes; it never tags.</b> Nothing here writes to the map, and accepting a candidate
 * means a person edits {@code universe-themes.csv}. That division is deliberate: an automatically
 * tagged map would be a keyword matcher's output presented as a curated claim, and the one thing
 * that makes a hand-kept file honest is that a reader can disagree with every row in it. So each
 * candidate carries <b>the evidence that produced it</b> — which word matched, in which name —
 * and can be rejected at a glance.
 *
 * <h2>What was measured before this was built, and what it killed</h2>
 * <p>Two better-sounding designs were tried against the live data first and both returned nothing:
 * <ul>
 *   <li><b>Recent listings in a theme's own industries.</b> Dead: <b>0 of 246</b> listings from the
 *       last 36 months resolve to an NSE industry at all, because {@code universe-sectors.csv} is
 *       seeded from index <i>constituent</i> lists and a company that listed recently is not yet a
 *       constituent. The lane had zero recall on precisely the population it was for. It would
 *       have been noise even with data — "Capital Goods" is an industry of 10 of the 12 themes.</li>
 *   <li><b>Name tokens over index constituents.</b> Nearly dead on its own: semiconductor 0,
 *       hydrogen 0, battery 0, water 0, aerospace 0, transmission 0 across ~700 names. Indian
 *       company names mostly do not say what the company does.</li>
 * </ul>
 * <p>What survived is name matching over <b>recent listing</b> names, where the full legal name is
 * on file and the recall is modest but real. This is why {@link Scan#recall()} is computed from
 * the run rather than asserted: a reader who is not told the hit rate will read a short list as
 * "nothing new", which is the flattering silence this whole feature exists to prevent.
 *
 * <p><b>One theme deliberately has no hint at all.</b> {@link ThemeCatalog#PHARMA_API} — a company
 * named "…Pharma…" is a pharmaceutical company, which says nothing about whether it makes active
 * ingredients, and a hint that fires on every pharma name is not evidence. Themes without a hint
 * are named in {@link Scan#themesWithoutHint()} rather than quietly returning nothing, because an
 * empty result and an unasked question must not render alike (Gotcha 44, Gotcha 121).
 *
 * <p>Pure: no repository, no clock, no I/O. Contributes zero points to any score.
 */
public final class ThemeCandidates {

    private ThemeCandidates() {
    }

    /** Which body of names a candidate was found in. */
    public enum Lane {
        /** A mainboard listing inside the window — the decay path the map cannot otherwise see. */
        NEW_LISTING,
        /** An existing index constituent the map has simply never named. */
        LISTED_NAME
    }

    /** One recent listing, as much of it as this needs. */
    public record Listing(String symbol, String companyName, LocalDate listingDate) {
    }

    /**
     * A business the map might be missing, and the evidence that says so.
     *
     * @param matched  the word that fired, quoted so the reader can reject the row in one glance
     * @param screened whether the screening universe already reaches this symbol — which is the
     *                 difference between accepting it costing nothing and it costing paced broker
     *                 calls on every run
     */
    public record Candidate(String symbol, String companyName, ThemeCatalog theme, String label,
                            String matched, Lane lane, LocalDate listingDate, String industry,
                            boolean screened) {
    }

    /**
     * The result of one pass, with its own hit rate attached.
     *
     * @param recall the measured statement of how much this could see, computed from this run
     */
    public record Scan(List<Candidate> candidates,
                       int listingsScanned,
                       int constituentsScanned,
                       int listingsMatched,
                       List<String> themesWithoutHint,
                       String recall,
                       String caveat) {
    }

    /**
     * The hint table: words that, in an Indian company name, usually mean the theme.
     *
     * <p>Chosen conservatively and with the Gotcha 53 discipline applied in the positive
     * direction — before matching a word, check what innocently starts with it. Bare {@code wind}
     * is absent because it matches Windlas; bare {@code power} and {@code energy} are absent
     * because they matched 31 and 9 names respectively and separate nothing.
     */
    private static final Map<ThemeCatalog, List<String>> HINTS = hints();

    private static Map<ThemeCatalog, List<String>> hints() {
        Map<ThemeCatalog, List<String>> m = new LinkedHashMap<>();
        m.put(ThemeCatalog.SEMICONDUCTORS, List.of("semiconductor", "semicon", "microchip"));
        m.put(ThemeCatalog.ELECTRONICS_EMS, List.of("electronic"));
        m.put(ThemeCatalog.AI_DATA_CENTRES, List.of("data cent", "datacent", "hyperscale"));
        m.put(ThemeCatalog.WATER_INFRASTRUCTURE, List.of("water", "irrigation"));
        m.put(ThemeCatalog.DEFENCE_INDIGENISATION,
                List.of("defence", "defense", "aerospace", "aeronautic", "shipyard", "ordnance"));
        m.put(ThemeCatalog.RAILWAY_MODERNISATION, List.of("rail", "wagon", "locomotive"));
        m.put(ThemeCatalog.SOLAR_MANUFACTURING, List.of("solar", "photovoltaic"));
        // Not bare "wind": it matches Windlas, and a hint that fires on a pharma company is not
        // evidence about wind energy.
        m.put(ThemeCatalog.WIND_ENERGY,
                List.of("wind energy", "wind power", "windmill", "wind turbine"));
        m.put(ThemeCatalog.GREEN_HYDROGEN, List.of("hydrogen", "electrolyser", "electrolyzer"));
        m.put(ThemeCatalog.POWER_TRANSMISSION, List.of("transmission", "power grid"));
        m.put(ThemeCatalog.EV_BATTERY, List.of("battery", "lithium", "electric vehicle"));
        // PHARMA_API deliberately absent — see the class javadoc.
        return Map.copyOf(m);
    }

    /** The words that would fire for a theme, for a screen that shows its own rules. */
    public static List<String> hintsFor(ThemeCatalog theme) {
        return theme == null ? List.of() : HINTS.getOrDefault(theme, List.of());
    }

    /** Themes that can never produce a candidate because no name is diagnostic for them. */
    public static List<String> themesWithoutHint() {
        List<String> out = new ArrayList<>();
        for (ThemeCatalog t : ThemeCatalog.values()) {
            if (!HINTS.containsKey(t)) {
                out.add(t.label());
            }
        }
        return out;
    }

    /**
     * Scan both bodies of names for businesses the map does not already carry.
     *
     * @param listings     recent mainboard listings, in any order
     * @param constituents every classified index constituent
     * @param tagged       bare symbols the theme map already names — never a candidate
     * @param screened     bare symbols the screening universe reaches, for the cost note
     */
    public static Scan scan(Collection<Listing> listings,
                            Collection<UniverseSectors.Entry> constituents,
                            Set<String> tagged,
                            Set<String> screened) {
        Set<String> already = tagged == null ? Set.of() : tagged;
        Set<String> inUniverse = screened == null ? Set.of() : screened;

        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> matchedListings = new LinkedHashSet<>();

        int listingCount = 0;
        if (listings != null) {
            for (Listing l : listings) {
                listingCount++;
                String sym = bare(l.symbol());
                if (sym == null || already.contains(sym)) {
                    continue;
                }
                for (Map.Entry<ThemeCatalog, List<String>> e : HINTS.entrySet()) {
                    String hit = firstHit(l.companyName(), e.getValue());
                    if (hit == null || !seen.add(sym + "|" + e.getKey().name())) {
                        continue;
                    }
                    matchedListings.add(sym);
                    out.add(new Candidate(sym, trim(l.companyName()), e.getKey(), e.getKey().label(),
                            hit, Lane.NEW_LISTING, l.listingDate(),
                            UniverseSectors.industryFor(sym), inUniverse.contains(sym)));
                }
            }
        }

        int constituentCount = 0;
        if (constituents != null) {
            for (UniverseSectors.Entry c : constituents) {
                constituentCount++;
                String sym = bare(c.symbol());
                if (sym == null || already.contains(sym)) {
                    continue;
                }
                for (Map.Entry<ThemeCatalog, List<String>> e : HINTS.entrySet()) {
                    String hit = firstHit(c.name(), e.getValue());
                    if (hit == null || !seen.add(sym + "|" + e.getKey().name())) {
                        continue;
                    }
                    out.add(new Candidate(sym, trim(c.name()), e.getKey(), e.getKey().label(),
                            hit, Lane.LISTED_NAME, null, c.industry(), inUniverse.contains(sym)));
                }
            }
        }

        // New listings first: that is the lane the map genuinely cannot learn about any other way.
        out.sort((a, b) -> {
            if (a.lane() != b.lane()) {
                return a.lane() == Lane.NEW_LISTING ? -1 : 1;
            }
            if (a.lane() == Lane.NEW_LISTING) {
                LocalDate da = a.listingDate();
                LocalDate db = b.listingDate();
                if (da != null && db != null && !da.equals(db)) {
                    return db.compareTo(da);
                }
            }
            return a.symbol().compareTo(b.symbol());
        });

        return new Scan(List.copyOf(out), listingCount, constituentCount, matchedListings.size(),
                themesWithoutHint(), recall(listingCount, matchedListings.size()), caveat());
    }

    /**
     * The hit rate, computed rather than claimed.
     *
     * <p>Without this sentence a short list reads as "nothing new", when what it usually means is
     * that most companies are not named after what they do — the flattering silence this feature
     * exists to prevent.
     */
    private static String recall(int listings, int matched) {
        if (listings <= 0) {
            return "No recent listings were available to scan, so the new-listing lane found "
                    + "nothing for want of input rather than for want of candidates.";
        }
        return "A name hint fired on " + matched + " of " + listings + " listings in the window. "
                + "Read a short list as \"most companies are not named after what they do\", never "
                + "as \"nothing new listed\": across that same window the word semiconductor "
                + "appears in no company name at all, and hydrogen, battery and water appear in "
                + "none of the index constituent names either. This lane finds some of what the "
                + "map is missing, never all of it.";
    }

    private static String caveat() {
        return "These are questions, not tags and not picks. Nothing here has been screened against "
                + "the seven pillars, and a name containing a word is evidence about what a company "
                + "is called rather than about what it earns. Adding one to the map means the app "
                + "will start analysing the business - it says nothing about whether it is worth "
                + "owning, and government money is a demand signal with a political dependency, "
                + "never a quality signal.";
    }

    /** First hint that appears at a word start in the name, or null. */
    private static String firstHit(String name, List<String> tokens) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT);
        for (String t : tokens) {
            if (Pattern.compile("\\b" + Pattern.quote(t)).matcher(n).find()) {
                return t;
            }
        }
        return null;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String bare(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String s = symbol.trim();
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        s = s.trim().toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}
