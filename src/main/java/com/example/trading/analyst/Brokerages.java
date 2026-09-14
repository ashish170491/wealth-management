package com.example.trading.analyst;

import java.util.List;
import java.util.Locale;

/**
 * The sell-side houses this app recognises by name (SPEC §49.3).
 *
 * <p><b>Why the list is shared rather than copied.</b> It was private to
 * {@code StockNewsService} and is now needed by the target ledger as well. Two copies of a
 * name table drift, and the failure mode is invisible: a house added to one list is silently
 * absent from the other, so the §24 flow counts a downgrade the §49 ledger never records, and
 * nothing anywhere reports a disagreement. That is Gotcha 85 one level down from a verdict —
 * one vocabulary, one owner.
 *
 * <p><b>Attribution is not optional here.</b> A headline carrying a target but no house name is
 * deliberately dropped by the ledger rather than filed under "unnamed": the whole point of the
 * ledger is to find out whose calls work, and an anonymous bucket would rapidly become the
 * largest "analyst" in the record while standing for nobody. The count of such headlines is
 * reported instead, so the gap is visible rather than absorbed.
 *
 * <p>Matching is case-insensitive and substring-based, longest name first — {@code "Motilal
 * Oswal"} must win over {@code "Motilal"} so the canonical spelling is what lands in the ledger
 * and the per-house record does not split across two labels.
 */
public final class Brokerages {

    private Brokerages() {}

    /**
     * Canonical spellings. Order is irrelevant on read — {@link #find(String)} sorts by length —
     * but keep related spellings adjacent so a reviewer can see the aliases.
     */
    public static final List<String> NAMES = List.of(
            "Motilal Oswal", "MOFSL", "Nomura", "CLSA", "Jefferies", "Morgan Stanley",
            "Goldman Sachs", "JP Morgan", "JPMorgan", "Citi", "Credit Suisse", "Kotak",
            "ICICI Securities", "ICICI Direct", "HDFC Securities", "Axis Securities",
            "Axis Capital", "Nirmal Bang", "Prabhudas Lilladher", "Emkay", "Elara",
            "Anand Rathi", "Sharekhan", "Geojit", "Yes Securities", "IIFL", "Bernstein",
            "Macquarie", "Bank of America", "BofA", "UBS", "Deutsche Bank", "BNP Paribas",
            "Antique", "IDBI Capital", "IDBI", "Centrum", "JM Financial", "Phillip Capital",
            "Dolat Capital", "InCred", "Choice Broking", "Nuvama", "Ambit", "Investec",
            "HSBC", "Avendus", "Equirus", "SBI Securities", "SBICAP", "Ventura", "LKP",
            "Systematix", "Arihant", "Monarch", "Sushil Finance", "Mirae Asset");

    /** Longest first, so an alias that is a prefix of a fuller name never wins. */
    private static final List<String> BY_LENGTH_DESC =
            NAMES.stream().sorted((a, b) -> b.length() - a.length()).toList();

    /**
     * The house named in {@code text}, in its canonical spelling, or null.
     *
     * <p>Null means <em>no recognised house</em>, which is a different fact from "an unknown
     * house": a boutique this list has never heard of reads the same as a headline with no
     * attribution at all. That is a known limit of the list rather than of the headline, and it
     * is recorded in SPEC §49.7 as a coverage gap, not smoothed over.
     */
    public static String find(String text) {
        if (text == null || text.isBlank()) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String name : BY_LENGTH_DESC) {
            if (lower.contains(name.toLowerCase(Locale.ROOT))) return name;
        }
        return null;
    }
}
