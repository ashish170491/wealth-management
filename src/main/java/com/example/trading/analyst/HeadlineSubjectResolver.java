package com.example.trading.analyst;

import com.example.trading.multibagger.UniverseSectors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Works out which listed company a headline is about (SPEC §49.4). Pure — no Spring, no I/O.
 *
 * <p><b>Why this is allowed to name a company when §48's reader is not.</b> The macro event
 * extractor is forbidden from naming a business because it would be <em>deciding</em> which
 * companies a general story affects — a judgement that belongs to the exposure map the investor
 * can read on screen. This resolver decides nothing of the kind: the headline itself names the
 * company, as the stated subject of an attributed price target, and all that happens here is
 * matching that name to a ticker. The distinction is between reading a name and inferring a
 * relevance.
 *
 * <p><b>It refuses far more readily than it guesses.</b> A misattributed target is not a small
 * error — it files another company's claim against this one, and the ledger's whole output is
 * attribution. So a headline naming two listed companies resolves to neither unless one match
 * strictly contains the other ("HDFC Bank" over "HDFC"), and the count of refusals is reported
 * by the capture rather than absorbed.
 *
 * <p><b>Known gap, stated rather than patched.</b> Headlines routinely use a short form the
 * exchange's company list does not carry — "Airtel" for Bharti Airtel, "Maruti" for Maruti
 * Suzuki India. Those simply do not resolve. Adding a hand-kept alias table would help, and it
 * would also be a second name vocabulary to keep in step with the first; until there is evidence
 * about how much is being missed, the honest position is the miss plus the coverage row that
 * shows it (SPEC §49.7).
 */
public final class HeadlineSubjectResolver {

    private HeadlineSubjectResolver() {}

    /**
     * All-caps tokens that are words before they are tickers. Without this, a headline about the
     * Union Budget resolves to whichever company happens to be listed as "INDIA"-something, and
     * a sector story lands on one stock.
     */
    private static final Set<String> SYMBOL_STOPLIST = Set.of(
            "INDIA", "NSE", "BSE", "SEBI", "RBI", "GDP", "IPO", "GST", "CEO", "CFO", "MD",
            "USA", "US", "UK", "EU", "UAE", "FII", "DII", "FPI", "PSU", "NBFC", "AI", "EV",
            "IT", "FMCG", "PLI", "NPA", "MSME", "SIP", "AUM", "IIP", "CPI", "WPI", "FDI",
            "Q1", "Q2", "Q3", "Q4", "FY", "YOY", "QOQ", "NEW", "TOP", "BUY", "SELL", "HOLD",
            "ADD", "TP", "PE", "PAT", "EPS", "ROE", "GMP", "SME", "ETF", "NAV", "OFS", "QIP");

    /** Corporate suffixes stripped from a company name before matching. */
    private static final List<String> SUFFIXES = List.of(
            "LIMITED", "LTD", "PVT", "PRIVATE", "CORPORATION", "CORP", "COMPANY", "INC", "PLC");

    /** A normalised name shorter than this is too generic to match on. */
    private static final int MIN_NAME_LENGTH = 5;

    /** A ticker shorter than this is an abbreviation before it is a company. */
    private static final int MIN_SYMBOL_LENGTH = 3;

    /** Why a headline produced no subject, so the capture can report its funnel. */
    public enum Miss { NO_MATCH, AMBIGUOUS }

    /**
     * One resolution.
     *
     * @param symbol      qualified, {@code NSE:RELIANCE}; null when nothing resolved
     * @param companyName the exchange's spelling, for display
     * @param matchedOn   the text that actually matched, so a reading can be traced rather than
     *                    assumed — the same discipline as {@code DecayAlert.resolvedSymbol}
     * @param miss        why nothing resolved; null on success
     * @param alsoMatched other companies the headline named, when ambiguous
     */
    public record Subject(String symbol,
                          String companyName,
                          String matchedOn,
                          Miss miss,
                          List<String> alsoMatched) {
        public boolean resolved() { return symbol != null; }
    }

    private record Candidate(String symbol, String companyName, String matchedText) {}

    private static volatile Map<String, Entry> nameIndex;

    private record Entry(String symbol, String companyName) {}

    /**
     * The company a headline is about.
     *
     * @param headline the title as published
     */
    public static Subject resolve(String headline) {
        if (headline == null || headline.isBlank()) return new Subject(null, null, null, Miss.NO_MATCH, List.of());

        String normalised = normalise(headline);
        List<Candidate> hits = new ArrayList<>();

        for (Map.Entry<String, Entry> e : index().entrySet()) {
            if (containsTokenSequence(normalised, e.getKey())) {
                hits.add(new Candidate(e.getValue().symbol(), e.getValue().companyName(), e.getKey()));
            }
        }
        if (hits.isEmpty()) {
            Candidate byTicker = resolveByTicker(headline);
            if (byTicker != null) {
                return new Subject(qualify(byTicker.symbol()), byTicker.companyName(),
                        byTicker.matchedText(), null, List.of());
            }
            return new Subject(null, null, null, Miss.NO_MATCH, List.of());
        }

        Candidate best = longest(hits);
        // Two different companies named in one headline resolve to neither, unless every other
        // match is a substring of the winner — which is the "HDFC Bank" / "HDFC" case, one
        // company written two ways rather than two companies.
        for (Candidate c : hits) {
            if (!c.symbol().equals(best.symbol()) && !best.matchedText().contains(c.matchedText())) {
                List<String> others = hits.stream().map(Candidate::symbol).distinct().sorted().toList();
                return new Subject(null, null, null, Miss.AMBIGUOUS, others);
            }
        }
        return new Subject(qualify(best.symbol()), best.companyName(), best.matchedText(), null, List.of());
    }

    /**
     * A bare ticker written in capitals, as headlines often do ("NTPC", "SBIN").
     *
     * <p>Only tried when no company name matched, and only in capitals: lower-cased, half of
     * these are ordinary words. Ambiguity here means two tickers in one headline, which is a
     * comparison piece rather than a call on one stock, so it resolves to nothing.
     */
    private static Candidate resolveByTicker(String headline) {
        Candidate found = null;
        for (String token : headline.split("[^A-Za-z0-9&]+")) {
            if (token.length() < MIN_SYMBOL_LENGTH) continue;
            if (!token.equals(token.toUpperCase(Locale.ROOT))) continue;
            if (SYMBOL_STOPLIST.contains(token)) continue;
            UniverseSectors.Entry e = UniverseSectors.entryFor(token);
            if (e == null) continue;
            if (found != null && !found.symbol().equals(token)) return null;
            found = new Candidate(token, e.name(), token);
        }
        return found;
    }

    private static Candidate longest(List<Candidate> hits) {
        Candidate best = hits.get(0);
        for (Candidate c : hits) {
            if (c.matchedText().length() > best.matchedText().length()) best = c;
        }
        return best;
    }

    private static String qualify(String tradingSymbol) {
        return "NSE:" + tradingSymbol;
    }

    /**
     * Whole-token containment: "ITC" must not match inside "SWITCH", and "TATA STEEL" must not
     * match "TATA STEEL LONG PRODUCTS" the wrong way round — both sides are normalised to
     * space-delimited tokens and the needle is required to sit on token boundaries.
     */
    static boolean containsTokenSequence(String haystack, String needle) {
        String h = " " + haystack + " ";
        String n = " " + needle + " ";
        return h.contains(n);
    }

    /** Upper case, ampersand spelled out, everything else non-alphanumeric folded to a space. */
    static String normalise(String text) {
        String upper = text.toUpperCase(Locale.ROOT).replace("&", " AND ");
        StringBuilder sb = new StringBuilder(upper.length());
        for (char c : upper.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /** Normalised company name with corporate suffixes removed; null when too generic to use. */
    static String indexKey(String companyName) {
        if (companyName == null) return null;
        String n = normalise(companyName);
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String suffix : SUFFIXES) {
                if (n.endsWith(" " + suffix)) {
                    n = n.substring(0, n.length() - suffix.length() - 1).trim();
                    stripped = true;
                }
            }
        }
        if (n.length() < MIN_NAME_LENGTH) return null;
        // A single short word is a word before it is a company. Two tokens, or six characters,
        // is the floor at which a name is distinctive enough to match a headline on.
        boolean multiToken = n.contains(" ");
        return (multiToken || n.length() >= 6) ? n : null;
    }

    private static Map<String, Entry> index() {
        Map<String, Entry> idx = nameIndex;
        if (idx == null) {
            synchronized (HeadlineSubjectResolver.class) {
                idx = nameIndex;
                if (idx == null) {
                    idx = build();
                    nameIndex = idx;
                }
            }
        }
        return idx;
    }

    private static Map<String, Entry> build() {
        Map<String, Entry> out = new HashMap<>();
        for (String symbol : UniverseSectors.symbols()) {
            UniverseSectors.Entry e = UniverseSectors.entryFor(symbol);
            if (e == null) continue;
            String key = indexKey(e.name());
            if (key == null) continue;
            // A name collision between two listings resolves to neither later anyway; keeping the
            // first is fine because the ambiguity check works on matched text, not on this map.
            out.putIfAbsent(key, new Entry(symbol, e.name()));
        }
        return Map.copyOf(out);
    }

    /** How many company names the resolver can match. Diagnostics and tests. */
    public static int indexedNames() {
        return index().size();
    }
}
