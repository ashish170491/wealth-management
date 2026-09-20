package com.example.trading.funds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which index fund is the fair yardstick for an actively managed category (SPEC §52.14).
 *
 * <p>Every rupee in an actively managed fund is a rupee not in the index fund that covers the same
 * part of the market, so "did the active funds beat the tracker" is the question a fund screen is
 * really being asked. The app could not ask it until now, because index funds were excluded from
 * the persisted universe entirely - correctly for measurement (a tracker is judged on tracking
 * difference, not on excess return over peers) and disastrously for comparison.
 *
 * <p><b>Name matching, and why it is safe enough here but only just.</b> There is no field linking
 * a scheme to the index it tracks, so the mapping is made on the scheme's own name. That is the
 * technique §51.10 uses to propose theme candidates and it carries the same hazard in the same
 * direction: a bare token matches things it should not. Three rules contain it.
 *
 * <ol>
 *   <li><b>Phrases, not words.</b> {@code "NIFTY 50"} rather than {@code "NIFTY"}, which appears in
 *       every one of these names. A single token here would map a Nifty Smallcap tracker to the
 *       Large Cap category, which is B-047's family wearing an index's clothes.</li>
 *   <li><b>An exclusion list, checked first.</b> {@code "NIFTY 50 EQUAL WEIGHT"} and
 *       {@code "NIFTY 50 VALUE 20"} both contain {@code "NIFTY 50"} and are different indices with
 *       different returns. This is Gotcha 53 in the positive direction - before matching on a
 *       phrase, check what innocently contains it.</li>
 *   <li><b>Every matched fund name is published</b>, so a reader can reject a bad match at a
 *       glance rather than having to trust the table. Same reason §51.10 prints the word that
 *       matched each candidate.</li>
 * </ol>
 *
 * <p><b>Categories with no defensible yardstick get none, and are named.</b> A Focused fund holds
 * 25-30 stocks from anywhere; a Value or Contra fund is defined by a philosophy rather than a
 * segment; a Dividend Yield fund has no mainstream tracker in India. Inventing a mapping for those
 * would publish a comparison against an index they never claimed to follow, and the difference
 * would be read as skill. Sectoral and thematic funds are the clearest case of all - there is no
 * single index for "thematic", which is exactly why {@link FundVocabulary.BenchmarkAvailability}
 * calls them {@code NOT_SINGULAR} rather than {@code NOT_HELD}.
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public final class IndexYardstick {

    private IndexYardstick() {}

    /**
     * Phrases that, appearing in an index fund's name, identify the index it tracks.
     *
     * <p>Order matters within a list only in that any match is accepted; order across the map does
     * not, because a scheme is tested against one category's phrases at a time.
     */
    private static final Map<SebiCategory, List<String>> TRACKS = new LinkedHashMap<>();

    /**
     * Phrases that disqualify a name even when a {@link #TRACKS} phrase matched.
     *
     * <p>These are real indices sold as index funds and they are <b>not</b> the broad index whose
     * name they contain: an equal-weight Nifty 50 and a market-cap Nifty 50 have diverged by
     * several points a year, and a "Value 20" or "Low Volatility 30" is a factor strategy in a
     * tracker's clothing. Comparing an active fund against one of these and calling the gap skill
     * would be measuring against a yardstick the fund never claimed.
     */
    private static final List<String> NOT_A_PLAIN_TRACKER = List.of(
            "EQUAL WEIGHT", "EQUALWEIGHT", "VALUE 20", "VALUE20", "LOW VOLATILITY", "LOWVOL",
            "QUALITY 30", "ALPHA", "MOMENTUM", "ESG", "TOP 10", "TOP 15", "DIVIDEND OPPORTUNITIES");

    static {
        TRACKS.put(SebiCategory.LARGE_CAP, List.of("NIFTY 50", "NIFTY50", "SENSEX", "BSE 30", "NIFTY 100"));
        TRACKS.put(SebiCategory.MID_CAP, List.of("MIDCAP 150", "MIDCAP150", "NIFTY MIDCAP"));
        TRACKS.put(SebiCategory.SMALL_CAP, List.of("SMALLCAP 250", "SMALLCAP250", "NIFTY SMALLCAP"));
        TRACKS.put(SebiCategory.LARGE_AND_MID, List.of("LARGEMIDCAP 250", "LARGEMIDCAP250",
                "LARGE MIDCAP 250", "NIFTY LARGEMIDCAP"));
        // A flexi-cap or multi-cap fund may hold anything listed, so the broadest mainstream index
        // is the fair comparison. The Nifty 500 covers about 92% of listed market value.
        TRACKS.put(SebiCategory.FLEXI_CAP, List.of("NIFTY 500", "NIFTY500", "TOTAL MARKET"));
        TRACKS.put(SebiCategory.MULTI_CAP, List.of("NIFTY 500", "NIFTY500", "TOTAL MARKET"));
    }

    /**
     * Categories with no yardstick, and the reason - published rather than silently absent.
     *
     * <p>An empty column reads as "nothing to report"; a named refusal reads as "this comparison
     * cannot be made honestly", which is the actual fact (SPEC §51's rule about an empty column
     * being a claim).
     */
    private static final Map<SebiCategory, String> NO_YARDSTICK = new LinkedHashMap<>();

    static {
        NO_YARDSTICK.put(SebiCategory.FOCUSED,
                "a focused fund holds 25-30 stocks chosen from anywhere in the market, so no index "
                        + "describes what it set out to do");
        NO_YARDSTICK.put(SebiCategory.VALUE,
                "a value fund is defined by a philosophy rather than a slice of the market, and the "
                        + "value indices sold in India are factor strategies rather than the thing "
                        + "these managers claim to be doing");
        NO_YARDSTICK.put(SebiCategory.CONTRA,
                "a contra fund deliberately buys what the index is selling, so measuring it against "
                        + "that index describes the strategy rather than the manager");
        NO_YARDSTICK.put(SebiCategory.DIVIDEND_YIELD,
                "there is no mainstream plain dividend-yield tracker in India, and the ones that "
                        + "exist are factor products with their own rules");
        NO_YARDSTICK.put(SebiCategory.ELSS,
                "an ELSS fund is diversified but its three-year lock-in changes what the manager can "
                        + "do, so it is not straightforwardly the same job as a tracker");
        NO_YARDSTICK.put(SebiCategory.SECTORAL_THEMATIC,
                "there is no single index for 'thematic' - each of these funds follows a different "
                        + "one, which is why the app calls this category unbenchmarkable rather than "
                        + "unbenchmarked");
    }

    /** One index fund proposed as a category's yardstick, with the evidence for the match. */
    public record Match(long schemeCode, String schemeName, String matchedPhrase) {}

    /** Whether a category can be compared with a tracker at all, and against which funds. */
    public record Yardstick(
            SebiCategory category,
            List<Match> funds,
            boolean available,
            String reason
    ) {}

    /** True when this category is one the app will ever look for a tracker for. */
    public static boolean hasMapping(SebiCategory category) {
        return TRACKS.containsKey(category);
    }

    /**
     * Find the index funds that could serve as this category's yardstick.
     *
     * @param category  the actively managed category being compared
     * @param indexFunds every persisted {@link SebiCategory#INDEX_EQUITY} scheme, code to name
     */
    public static Yardstick forCategory(SebiCategory category, Map<Long, String> indexFunds) {
        if (category == null) {
            return new Yardstick(null, List.of(), false, "no category on this scheme");
        }
        String refusal = NO_YARDSTICK.get(category);
        if (refusal != null) {
            return new Yardstick(category, List.of(), false, refusal);
        }
        List<String> phrases = TRACKS.get(category);
        if (phrases == null) {
            return new Yardstick(category, List.of(), false,
                    "this app has no index mapped to " + category.label()
                            + ", so no tracker comparison is published for it");
        }

        List<Match> found = new ArrayList<>();
        for (Map.Entry<Long, String> e : (indexFunds == null ? Map.<Long, String>of() : indexFunds).entrySet()) {
            String name = e.getValue() == null ? "" : e.getValue().toUpperCase(Locale.ROOT);
            if (name.isBlank() || isFactorProduct(name)) {
                continue;
            }
            for (String phrase : phrases) {
                if (name.contains(phrase)) {
                    found.add(new Match(e.getKey(), e.getValue(), phrase));
                    break;
                }
            }
        }

        if (found.isEmpty()) {
            return new Yardstick(category, List.of(), false,
                    "no index fund tracking " + phrases.get(0) + " has enough month-end history on "
                            + "file yet - a gap in this app's record, not a statement about "
                            + category.label());
        }
        return new Yardstick(category, List.copyOf(found), true,
                found.size() + " index " + (found.size() == 1 ? "fund" : "funds")
                        + " tracking " + phrases.get(0) + " are used as the yardstick; the median of "
                        + "them is the figure compared against, so one badly-run tracker cannot "
                        + "decide the answer");
    }

    /** True when the name is a factor or strategy product rather than a plain tracker. */
    public static boolean isFactorProduct(String upperName) {
        if (upperName == null) return false;
        for (String bad : NOT_A_PLAIN_TRACKER) {
            if (upperName.contains(bad)) return true;
        }
        return false;
    }

    /** Every category this class knows how to look for a tracker for. */
    public static List<SebiCategory> mappedCategories() {
        return List.copyOf(TRACKS.keySet());
    }
}
