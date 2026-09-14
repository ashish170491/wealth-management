package com.example.trading.analyst;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an attributed price target out of one headline (SPEC §49.3). Pure — no Spring, no I/O.
 *
 * <p><b>What it will and will not return.</b> A row is produced only when the headline carries
 * <em>both</em> a recognised sell-side house and a rupee target. Either half alone is dropped:
 *
 * <ul>
 *   <li>A target with no house cannot be attributed, and a ledger whose purpose is to find out
 *       whose calls work would fill up with an "unnamed" bucket standing for nobody
 *       ({@link Brokerages}).</li>
 *   <li>A rating change with no target is a real event but not a <em>target</em>, and it is
 *       already counted by the §24 brokerage-flow proxy. Recording it here would put a row in the
 *       ledger that can never be measured against anything, inflating coverage with unmeasurable
 *       claims — the opposite of what the coverage row exists to show.</li>
 * </ul>
 *
 * <p><b>The unit guard is the whole parser.</b> Indian business headlines are full of rupee
 * amounts that are not price targets: "bags Rs 1,200 crore order", "Rs 900 crore QIP", "target
 * price cut 20%". An amount is accepted only when it follows a target keyword <em>and</em> is not
 * followed by a scale or unit word. Getting this wrong does not produce a missing row, it
 * produces a target of 1,200 on a stock trading at 90 — a 1,200% "upside" that would dominate
 * every average in the track record. Same family as Gotcha 48, where a percentage row overwrote
 * a sales figure.
 *
 * <p><b>A default is not a statement.</b> Indian sell-side targets are conventionally
 * twelve-month, but a headline that does not say so has not said so.
 * {@link ParsedCall#horizonMonths()} is null in that case and the caller applies the convention
 * while recording that it did ({@code horizonStated=false}) — the rule B-057 and B-097 each had
 * to learn separately, applied here before it could go wrong a third time.
 */
public final class AnalystTargetParser {

    private AnalystTargetParser() {}

    /** Bumped when a pattern changes, so a row records which parser produced it. */
    public static final String VERSION = "atp1";

    /** Normalised recommendation. NOT_STATED is an absence, never a HOLD. */
    public enum Rating { BUY, HOLD, SELL, NOT_STATED }

    /** What the house did, where the headline says. NOT_STATED is an absence. */
    public enum Action { INITIATE, UPGRADE, DOWNGRADE, RAISE_TARGET, CUT_TARGET, MAINTAIN, NOT_STATED }

    /**
     * One attributed target.
     *
     * @param horizonMonths months, only when the headline actually states one; null otherwise
     */
    public record ParsedCall(String brokerage,
                             double targetPrice,
                             Rating rating,
                             Action action,
                             Integer horizonMonths) {
    }

    /** Why a headline produced nothing, so a capture run can report its funnel rather than a count. */
    public enum Reject { NO_TARGET, NO_BROKERAGE, IMPLAUSIBLE_TARGET }

    /** A parse attempt: exactly one of {@code call} / {@code reject} is non-null. */
    public record Result(ParsedCall call, Reject reject) {
        public boolean ok() { return call != null; }
    }

    /**
     * A price target on an Indian listed share. Below 1 rupee is not a target; above 2,00,000 is
     * an order book, a market capitalisation or a parse of the wrong number — MRF, the most
     * expensive share on the exchange, has never traded near it.
     */
    private static final double MIN_TARGET = 1.0;
    private static final double MAX_TARGET = 200_000.0;

    /** The phrase an amount has to follow before it can be a target at all. */
    private static final Pattern TARGET_KEYWORD =
            Pattern.compile("(?i)\\b(?:price\\s+target|target\\s+price|target|tp)\\b");

    /**
     * An index named just before the target keyword: the target belongs to the index, not to any
     * company the headline also mentions.
     *
     * <p>Found in the live sample: "Bernstein reshuffles India model portfolio: Adani Ports,
     * Eternal, Paytm in, DMart out; <b>sets Nifty 50 target at 26,000</b>" parsed as a ₹26,000
     * target on Eternal. The company was named, the figure was real, and the two had nothing to
     * do with each other — the worst shape of error this ledger can make, because both halves
     * look correct in isolation.
     */
    private static final Pattern INDEX_BEFORE = Pattern.compile(
            "(?i)(nifty|sensex|bankex|bank\\s*nifty|midcap|smallcap|index)\\s*[0-9]{0,3}\\s*$");

    /** How much text before a target keyword is inspected for an index name. */
    private static final int INDEX_LOOKBACK = 28;

    /**
     * A rupee amount. <b>The currency marker is required</b>, and that is a measured decision
     * rather than a cautious one.
     *
     * <p>The first draft made it optional, on the assumption that headlines write "target price
     * 1,200". Run against 162 real Indian market headlines, that assumption produced three false
     * positives out of fourteen accepted rows, and each was the kind that would dominate every
     * median on the scoreboard:
     *
     * <ul>
     *   <li>"Bharti Airtel Share Price Target 2026: Buy for 40% returns" → a target of ₹2,026,
     *       read off a <em>year</em>;</li>
     *   <li>"Nomura raises target price; stock up nearly 200% in 1 year" → a target of ₹1.</li>
     * </ul>
     *
     * Every genuine target in that sample carried Rs or ₹ — "raises target to ₹2,767", "Target at
     * Rs 3,880", "Target Raised to ₹190 by Emkay". So the marker costs nothing real and removes a
     * whole class of nonsense. Headlines that state only a percentage upside ("sees 46% upside")
     * are a known gap recorded in SPEC §49.7, not something to guess a level from.
     */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?i)(?:rs\\.?|₹|inr)\\s*\\b"
                    + "([0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]{1,2})?|[0-9]{1,6}(?:\\.[0-9]{1,2})?)"
                    + "\\b");

    /**
     * How far after a target keyword an amount may sit and still belong to it.
     *
     * <p>Wide enough for the phrasings that actually occur — "target price by 20% to Rs 1,150",
     * "target price of Rs 1,200 apiece" — and narrow enough that a number from the next clause
     * cannot be adopted. The unit guard is the real protection; this only bounds the search.
     */
    private static final int AMOUNT_SEARCH_WINDOW = 45;

    /**
     * A percentage straight after the amount: this number is a change, not a level.
     *
     * <p>Rejected, and the search <b>continues</b> — "cuts target price by 20% to Rs 1,150" puts
     * exactly this in front of the real figure.
     */
    private static final Pattern PERCENT_AFTER = Pattern.compile("(?i)^\\s*(%|per\\s*cent|percent)");

    /**
     * A scale or unit word straight after the amount: crore, million, times, basis points.
     *
     * <p>Rejected, and the search then <b>abandons this keyword entirely</b> rather than trying
     * the next number along. The difference matters: "revenue target Rs 900 million by 2027" has
     * a perfectly matchable number after the rejected one, and continuing would file a price
     * target of ₹2,027 from a headline about revenue. A target stated as an aggregate is not a
     * per-share target written oddly — it is a different quantity, and recording nothing is the
     * honest answer.
     */
    private static final Pattern SCALE_AFTER = Pattern.compile(
            "(?i)^\\s*(crore|cr\\b|lakh|lakhs|billion|bn\\b|million|mn\\b"
                    + "|trillion|points?|pts\\b|bps|times|x\\b)");

    private static final Pattern HORIZON_MONTHS = Pattern.compile("(?i)\\b([0-9]{1,2})\\s*[- ]?months?\\b");
    private static final Pattern HORIZON_YEARS = Pattern.compile("(?i)\\b(one|two|three|1|2|3)\\s*[- ]?years?\\b");

    /**
     * Rating spellings. {@code underperform} must never be read as {@code perform} and
     * {@code strong buy} must not be truncated by an earlier entry, so matching is by whole word
     * and ties at the same position go to the longer spelling. Gotcha 53's rule — before matching
     * a term as a substring, check what contains it.
     */
    private static final List<Rated> RATINGS = List.of(
            new Rated("strong buy", Rating.BUY), new Rated("outperform", Rating.BUY),
            new Rated("out-perform", Rating.BUY), new Rated("overweight", Rating.BUY),
            new Rated("over-weight", Rating.BUY), new Rated("accumulate", Rating.BUY),
            new Rated("add", Rating.BUY), new Rated("buy", Rating.BUY),
            new Rated("underperform", Rating.SELL), new Rated("under-perform", Rating.SELL),
            new Rated("underweight", Rating.SELL), new Rated("under-weight", Rating.SELL),
            new Rated("reduce", Rating.SELL), new Rated("sell", Rating.SELL),
            new Rated("market perform", Rating.HOLD), new Rated("equal-weight", Rating.HOLD),
            new Rated("equalweight", Rating.HOLD), new Rated("neutral", Rating.HOLD),
            new Rated("hold", Rating.HOLD));

    private record Rated(String phrase, Rating rating) {}

    /**
     * Parse one headline.
     *
     * @param headline the title as published; may be null
     * @return a {@link Result} carrying either the call or the reason nothing was produced
     */
    public static Result parse(String headline) {
        if (headline == null || headline.isBlank()) return new Result(null, Reject.NO_TARGET);

        Double target = findTarget(headline);
        if (target == null) return new Result(null, Reject.NO_TARGET);
        if (target < MIN_TARGET || target > MAX_TARGET) return new Result(null, Reject.IMPLAUSIBLE_TARGET);

        String brokerage = Brokerages.find(headline);
        if (brokerage == null) return new Result(null, Reject.NO_BROKERAGE);

        return new Result(new ParsedCall(brokerage, target, findRating(headline),
                findAction(headline), findHorizonMonths(headline)), null);
    }

    /**
     * The first amount after a target keyword that is not carrying a unit.
     *
     * <p><b>Every candidate is tested, not just the first.</b> "Citi cuts Infosys target price by
     * 20% to Rs 1,150" puts a rejected number in front of the real one, and stopping at the first
     * match would drop a perfectly good target. The same applies across keywords: a headline whose
     * first "target" is a revenue figure in crore may still carry a price target later.
     */
    static Double findTarget(String headline) {
        Matcher keyword = TARGET_KEYWORD.matcher(headline);
        while (keyword.find()) {
            int from = keyword.end();
            if (from >= headline.length()) continue;

            // An index target is not this company's target, however clearly the company is named.
            String before = headline.substring(Math.max(0, keyword.start() - INDEX_LOOKBACK), keyword.start());
            if (INDEX_BEFORE.matcher(before).find()) continue;

            // Matched over the whole string and bounded by the match's START offset, never by a
            // region: an end-capped region can cut a number in half and turn 1,150 into 115.
            Matcher amount = AMOUNT.matcher(headline);
            if (!amount.find(from)) continue;
            do {
                if (amount.start() > from + AMOUNT_SEARCH_WINDOW) break;
                String tail = headline.substring(amount.end());
                if (SCALE_AFTER.matcher(tail).find()) break;       // an aggregate, not a share price
                if (PERCENT_AFTER.matcher(tail).find()) continue;  // a change; the level may follow
                try {
                    double v = Double.parseDouble(amount.group(1).replace(",", ""));
                    if (v >= MIN_TARGET && v <= MAX_TARGET) return v;
                } catch (NumberFormatException ignored) {
                    // A number this regex matched but Java will not parse is not a target.
                }
            } while (amount.find());
        }
        return null;
    }

    /**
     * The rating the headline settles on.
     *
     * <p>A rating change names two ratings — "upgrades to Buy from Neutral" — and the one that
     * counts is the one after "to". Reading the first match instead would file an upgrade under
     * the rating it was upgraded <em>from</em>, inverting exactly the calls that matter most.
     */
    static Rating findRating(String headline) {
        String lower = headline.toLowerCase(Locale.ROOT);
        int toIdx = lower.indexOf(" to ");
        if (toIdx >= 0) {
            Rating after = firstRatingIn(lower.substring(toIdx));
            if (after != Rating.NOT_STATED) return after;
        }
        return firstRatingIn(lower);
    }

    /** Earliest-positioned rating phrase, the longer spelling winning at the same position. */
    private static Rating firstRatingIn(String lower) {
        int bestPos = Integer.MAX_VALUE;
        int bestLen = -1;
        Rating best = Rating.NOT_STATED;
        for (Rated r : RATINGS) {
            int idx = indexOfWord(lower, r.phrase());
            if (idx < 0) continue;
            if (idx < bestPos || (idx == bestPos && r.phrase().length() > bestLen)) {
                bestPos = idx;
                bestLen = r.phrase().length();
                best = r.rating();
            }
        }
        return best;
    }

    /** Whole-phrase match, so "add" does not fire inside "added" and "buy" not inside "buyback". */
    private static int indexOfWord(String haystack, String phrase) {
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(phrase, from);
            if (idx < 0) return -1;
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            int end = idx + phrase.length();
            boolean rightOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) return idx;
            from = idx + 1;
        }
    }

    static Action findAction(String headline) {
        String lower = headline.toLowerCase(Locale.ROOT);
        if (lower.contains("initiat")) return Action.INITIATE;
        if (lower.contains("upgrad")) return Action.UPGRADE;
        if (lower.contains("downgrad")) return Action.DOWNGRADE;
        if (containsAnyWord(lower, "raise", "raises", "raised", "hike", "hikes", "hiked",
                "lift", "lifts", "lifted")) return Action.RAISE_TARGET;
        if (containsAnyWord(lower, "cut", "cuts", "slash", "slashes", "slashed",
                "lower", "lowers", "lowered", "trim", "trims", "trimmed")) return Action.CUT_TARGET;
        if (containsAnyWord(lower, "maintain", "maintains", "maintained", "retain", "retains",
                "reiterate", "reiterates", "reaffirm", "reaffirms")) return Action.MAINTAIN;
        return Action.NOT_STATED;
    }

    private static boolean containsAnyWord(String lower, String... words) {
        for (String w : words) {
            if (indexOfWord(lower, w) >= 0) return true;
        }
        return false;
    }

    /** Months, only when the headline states a horizon. Null is "not stated", never twelve. */
    static Integer findHorizonMonths(String headline) {
        Matcher m = HORIZON_MONTHS.matcher(headline);
        if (m.find()) {
            try {
                int months = Integer.parseInt(m.group(1));
                if (months >= 1 && months <= 60) return months;
            } catch (NumberFormatException ignored) {
                // fall through to the years form
            }
        }
        Matcher y = HORIZON_YEARS.matcher(headline);
        if (y.find()) {
            return switch (y.group(1).toLowerCase(Locale.ROOT)) {
                case "one", "1" -> 12;
                case "two", "2" -> 24;
                case "three", "3" -> 36;
                default -> null;
            };
        }
        return null;
    }
}
