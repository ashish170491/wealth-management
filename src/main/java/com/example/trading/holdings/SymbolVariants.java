package com.example.trading.holdings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Exchange-prefix variants of a symbol, most-specific first.
 *
 * <p>Why this exists: a holding's exchange prefix is not stable (B-061 — the broker sync
 * re-prefixes {@code NSE:INFY} as {@code BSE:INFY}, and 22 of 33 holdings are BSE-prefixed),
 * while every analytical table in this system is keyed on the NSE symbol because screening runs
 * on NSE candles. Looking a holding up by its own symbol therefore misses its screening history
 * for two-thirds of the portfolio — which is how thesis-decay reported {@code NO_DATA} for 21
 * holdings that had months of scores on file.
 *
 * <p>Reusing NSE screening history for a BSE-held position is correct rather than approximate:
 * the composite describes the <em>company</em> (its candles, filings and shareholding), not the
 * listing venue, and the two listings differ only by a few paise of price. What is <em>not</em>
 * safe is inventing a variant for a symbol that never had a prefix, so a bare symbol yields
 * both prefixes and an unprefixed fallback, in that order, and the caller uses the first that
 * actually resolves — never a blend.
 *
 * <p>The series suffix (B-013 {@code -BE}/{@code -BZ}) is deliberately <b>not</b> stripped here:
 * that is a Kite tradingsymbol concern, and stripping it would silently map a trade-to-trade
 * listing onto the ordinary one.
 */
public final class SymbolVariants {

    private SymbolVariants() {}

    private static final List<String> EXCHANGES = List.of("NSE", "BSE");

    /**
     * Ordered lookup candidates for {@code symbol}: the symbol itself first (an exact match must
     * always win), then the same base under the other exchange prefixes, then the bare base.
     * Returns an empty list for a null/blank input rather than a list containing null.
     */
    public static List<String> candidates(String symbol) {
        if (symbol == null || symbol.isBlank()) return List.of();
        String s = symbol.trim();
        String base = base(s);
        List<String> out = new ArrayList<>();
        out.add(s);
        for (String ex : EXCHANGES) {
            String candidate = ex + ":" + base;
            if (!out.contains(candidate)) out.add(candidate);
        }
        if (!out.contains(base)) out.add(base);
        return List.copyOf(out);
    }

    /** The symbol without its exchange prefix: {@code BSE:INFY} → {@code INFY}. */
    public static String base(String symbol) {
        if (symbol == null) return null;
        String s = symbol.trim();
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1).trim().toUpperCase(Locale.ROOT)
                : s.toUpperCase(Locale.ROOT);
    }

    /** True when two symbols name the same company on either exchange. */
    public static boolean sameStock(String a, String b) {
        String ba = base(a);
        String bb = base(b);
        return ba != null && ba.equals(bb);
    }
}
