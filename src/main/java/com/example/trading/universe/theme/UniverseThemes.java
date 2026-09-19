package com.example.trading.universe.theme;

import com.example.trading.multibagger.UniverseSectors;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The policy-backed theme map, read once from the classpath resource {@code universe-themes.csv}
 * (SPEC §51.2).
 *
 * <p><b>What this class is for.</b> Before it existed the app could not answer "are we even
 * looking at the semiconductor names?" — not because the answer was no, but because there was
 * nowhere the question could be asked. NSE's classification has 22 macro buckets and none of them
 * is Semiconductors, Water or Data Centres, so a chip assembler read "Capital Goods" and sat
 * indistinguishable from a bearing manufacturer. This file is the finer vocabulary, kept by hand
 * because no feed publishes it and because a claim an investor cannot read is a claim they cannot
 * disagree with.
 *
 * <p><b>Three things it deliberately does not do.</b> It produces no score, no ranking and no
 * verdict on any stock — a theme decides whether the app <em>looks</em> at a business, never
 * whether the business is good (SPEC §51.1). It does not fetch anything, so there is no scheduler
 * and no rate-limit budget to share. And it never invents a symbol: a row naming a ticker that
 * {@link UniverseSectors} has never heard of is <b>kept and flagged</b>, not silently accepted,
 * because a ticker nobody has confirmed must not be counted as a stock we track (Gotcha 44). Those
 * rows read {@code verified=false} and every coverage figure reports them separately.
 *
 * <p><b>A bad theme token fails the row, loudly.</b> {@link ThemeCatalog#parse} rejects anything
 * not in the catalogue and the line number is logged, because a typo would otherwise create a
 * thirteenth theme holding one stock — which on screen is indistinguishable from a real theme
 * nobody has populated. Same contract as {@code MacroExposureMap}, for the same reason.
 */
@Slf4j
public final class UniverseThemes {

    private UniverseThemes() {
    }

    private static final String RESOURCE = "/universe-themes.csv";

    /**
     * One tagged business.
     *
     * @param symbol   bare NSE trading symbol, upper case, no exchange prefix
     * @param theme    the catalogue entry this row files it under
     * @param policy   the scheme that makes this a policy-backed theme, as written in the file
     * @param role     where in the value chain the business actually sits, in plain words
     * @param verified whether {@link UniverseSectors} confirms the ticker exists. False means the
     *                 name is outside NSE's index constituent lists — usually a genuine micro-cap
     *                 — and every count keeps it apart from the names we can vouch for.
     */
    public record Tag(String symbol, ThemeCatalog theme, String policy, String role, boolean verified) {
    }

    private static volatile Map<String, List<Tag>> bySymbol;
    private static volatile Map<ThemeCatalog, List<Tag>> byTheme;
    private static volatile java.time.LocalDate reviewedOn;
    private static volatile String policyAsOf;

    /**
     * Every theme tag on a symbol, in file order; empty when the business is in no tracked theme.
     *
     * <p>Accepts a bare symbol or an exchange-prefixed one ({@code NSE:KAYNES}, {@code BSE:KAYNES})
     * because a holding's prefix is not its identity (Gotcha 84) and the same company must resolve
     * however it happens to be spelled on the calling screen.
     */
    public static List<Tag> tagsFor(String symbol) {
        String bare = bare(symbol);
        if (bare == null) {
            return List.of();
        }
        return bySymbol().getOrDefault(bare, List.of());
    }

    /** Just the themes on a symbol, deduplicated, in file order. */
    public static List<ThemeCatalog> themesFor(String symbol) {
        List<ThemeCatalog> out = new ArrayList<>();
        for (Tag t : tagsFor(symbol)) {
            if (!out.contains(t.theme())) {
                out.add(t.theme());
            }
        }
        return out;
    }

    /** Every tagged business in a theme, in file order. */
    public static List<Tag> membersOf(ThemeCatalog theme) {
        return theme == null ? List.of() : byTheme().getOrDefault(theme, List.of());
    }

    /** Every theme that has at least one row, in catalogue order. */
    public static List<ThemeCatalog> populatedThemes() {
        List<ThemeCatalog> out = new ArrayList<>();
        for (ThemeCatalog t : ThemeCatalog.values()) {
            if (!membersOf(t).isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Every distinct bare symbol the file tags, sorted. */
    public static Set<String> taggedSymbols() {
        return Collections.unmodifiableSet(new TreeSet<>(bySymbol().keySet()));
    }

    /** How many rows the file carries. Diagnostics and tests. */
    public static int rowCount() {
        return byTheme().values().stream().mapToInt(List::size).sum();
    }

    /**
     * Strip an exchange prefix and normalise. Kept private and deliberately simple: the series
     * suffix stripping that {@code SymbolVariants} does is for Kite quotes, and a theme file has
     * no business carrying a {@code -BE} name at all.
     */
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

    /**
     * The day a person last read this map against current policy, from the file's own
     * {@code # REVIEWED:} directive; null when the file does not carry one.
     *
     * <p><b>Why a hand-written date is worth parsing.</b> Nothing in this app can update the theme
     * map — no feed publishes it, no scheduler touches it and no code writes it — so it evolves
     * exactly as often as somebody edits it. The failure that creates is quiet and flattering:
     * every coverage figure on the Themes screen is counted against this file's own denominator,
     * so a map that stops growing while the market does not goes on reporting high coverage of a
     * shrinking list. The instrument built to find a blind spot becomes one. This date is what
     * lets {@code DataHealth} say so out loud (SPEC §51.9).
     *
     * <p>An unparseable date returns null rather than today. A file whose vintage cannot be read
     * must report as unknown, never as freshly reviewed — the same rule that keeps a filing date
     * from defaulting to now (Gotcha 100, Gotcha 130).
     */
    public static java.time.LocalDate reviewedOn() {
        ensureLoaded();
        return reviewedOn;
    }

    /** Which budget and scheme announcements the rows were written against; null when absent. */
    public static String policyAsOf() {
        ensureLoaded();
        return policyAsOf;
    }

    /**
     * Pick the two vintage directives out of a comment line. Deliberately tolerant of spacing and
     * case, and deliberately silent about anything else — the header is prose and most of its
     * lines are not directives.
     */
    private static void readDirective(String commentLine) {
        String body = commentLine.startsWith("#") ? commentLine.substring(1).trim() : commentLine.trim();
        String upper = body.toUpperCase(Locale.ROOT);
        if (upper.startsWith("REVIEWED:")) {
            String value = body.substring("REVIEWED:".length()).trim();
            try {
                reviewedOn = java.time.LocalDate.parse(value);
            } catch (Exception ex) {
                // Null, never today. A vintage we cannot read is an unknown vintage, and calling
                // it fresh is the one answer that removes the reminder this directive exists for.
                log.warn("Theme map REVIEWED directive is not an ISO date and was ignored ({}): "
                        + "the map's vintage will report as unknown rather than as current.", value);
            }
        } else if (upper.startsWith("POLICY-AS-OF:")) {
            String value = body.substring("POLICY-AS-OF:".length()).trim();
            policyAsOf = value.isBlank() ? null : value;
        }
    }

    private static Map<String, List<Tag>> bySymbol() {
        ensureLoaded();
        return bySymbol;
    }

    private static Map<ThemeCatalog, List<Tag>> byTheme() {
        ensureLoaded();
        return byTheme;
    }

    private static void ensureLoaded() {
        if (bySymbol != null) {
            return;
        }
        synchronized (UniverseThemes.class) {
            if (bySymbol != null) {
                return;
            }
            load();
        }
    }

    private static void load() {
        Map<String, List<Tag>> symbols = new LinkedHashMap<>();
        Map<ThemeCatalog, List<Tag>> themes = new LinkedHashMap<>();
        Set<String> unverified = new LinkedHashSet<>();
        int rejected = 0;

        try (InputStream in = UniverseThemes.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("Theme map {} is missing from the classpath: every stock will read "
                        + "'not measured' for theme, and the coverage screen will report nothing "
                        + "tracked rather than nothing tagged.", RESOURCE);
                bySymbol = Map.of();
                byTheme = Map.of();
                return;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int lineNo = 0;
                while ((line = r.readLine()) != null) {
                    lineNo++;
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        readDirective(trimmed);
                        continue;
                    }
                    // The role is last and routinely contains commas, so split at most four ways.
                    String[] f = trimmed.split(",", 4);
                    if (f.length < 3) {
                        log.warn("Theme map line {} has fewer than three fields and was dropped: {}",
                                lineNo, trimmed);
                        rejected++;
                        continue;
                    }
                    String symbol = bare(f[0]);
                    var theme = ThemeCatalog.parse(f[1]);
                    if (symbol == null || theme.isEmpty()) {
                        // A theme token outside the catalogue would otherwise become a thirteenth
                        // theme holding one stock, which reads on screen exactly like a real theme
                        // nobody has populated yet (B-074's shape).
                        log.warn("Theme map line {} names no known theme and was dropped — add it to "
                                + "ThemeCatalog or fix the spelling: {}", lineNo, trimmed);
                        rejected++;
                        continue;
                    }
                    boolean verified = UniverseSectors.entryFor(symbol) != null;
                    if (!verified) {
                        unverified.add(symbol);
                    }
                    Tag tag = new Tag(symbol, theme.get(), f[2].trim(),
                            f.length > 3 ? f[3].trim() : null, verified);
                    symbols.computeIfAbsent(symbol, k -> new ArrayList<>()).add(tag);
                    themes.computeIfAbsent(theme.get(), k -> new ArrayList<>()).add(tag);
                }
            }
        } catch (Exception ex) {
            log.warn("Theme map {} could not be read ({}): every stock will read 'not measured' "
                    + "for theme.", RESOURCE, ex.getMessage());
            bySymbol = Map.of();
            byTheme = Map.of();
            return;
        }

        symbols.replaceAll((k, v) -> List.copyOf(v));
        themes.replaceAll((k, v) -> List.copyOf(v));
        bySymbol = Collections.unmodifiableMap(symbols);
        byTheme = Collections.unmodifiableMap(themes);

        log.info("Theme map loaded: {} symbols across {} themes ({} rows, {} dropped); last "
                + "reviewed {}.", symbols.size(), themes.size(), rowCount(), rejected,
                reviewedOn == null ? "never (no REVIEWED directive)" : reviewedOn);
        if (!unverified.isEmpty()) {
            // Not an error. These are mostly real micro-caps outside NSE's index constituent
            // lists, which is exactly the part of the market the themes reach into. They are
            // named here and reported separately everywhere else so a ticker nobody has
            // confirmed is never counted as a stock this app tracks.
            log.info("Theme map: {} symbols are not in universe-sectors.csv so their tickers could "
                    + "not be confirmed here — reported as UNVERIFIED rather than as covered: {}",
                    unverified.size(), unverified);
        }
    }
}
