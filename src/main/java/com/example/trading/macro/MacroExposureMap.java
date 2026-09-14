package com.example.trading.macro;

import com.example.trading.learning.ScoringVersion;
import com.example.trading.multibagger.UniverseSectors;
import com.example.trading.portfolio.SectorMapping;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The written-down rules for which businesses each macro factor helps or hurts, and why
 * (SPEC 48.3). Read once from the classpath resource {@code macro-exposure.csv}.
 *
 * <p><b>This file is the feature.</b> The language model that reads the news never names a company
 * and never decides a direction for one; it extracts what happened, and this table decides who is
 * affected. That separation is deliberate and is the same boundary {@code ConcallAnalysisService}
 * draws: the model extracts, the rules score. A model asked "which stocks does this hurt?" will
 * answer fluently and unfalsifiably every time, including when it is wrong, and nothing downstream
 * could tell the difference.
 *
 * <p><b>Precedence is SYMBOL, then INDUSTRY, then SECTOR</b>, first match per factor. A sector is
 * often the wrong unit: {@code ENERGY} contains both a company that sells crude and one that buys
 * it, and the same rise is a tailwind for one and a headwind for the other. Where the sector is
 * wrong the map says so by name, and the symbol row wins.
 *
 * <p><b>What the map deliberately does not do.</b> It never infers a second-order factor: a war
 * does not imply a crude rise here, because inferring it would manufacture an event nobody
 * reported and then score stocks against it. If crude moved, a headline said so and there is an
 * event for it. And a strength is never turned into a score - it labels how much of the business
 * the channel touches, so a reader can weigh a reading, and it is not arithmetic.
 *
 * <p><b>Validation is fatal.</b> An unknown factor, a sector bucket {@link SectorMapping} cannot
 * produce, or a malformed row stops the application at startup, naming the line. The alternative
 * is a typo that matches nothing, for ever, on every stock, reporting "not measured" with nothing
 * logged - which is the shape of three separate defects in this codebase already (Gotcha 94).
 */
@Slf4j
public final class MacroExposureMap {

    private MacroExposureMap() {
    }

    private static final String RESOURCE = "/macro-exposure.csv";

    /** What a row is keyed on. Checked in this order; the first hit for a factor wins. */
    public enum Scope { SYMBOL, INDUSTRY, SECTOR }

    /** What a <i>rise</i> in the factor does to this business. */
    public enum OnRise {
        /** The business is helped when the factor rises. */
        HELPED,
        /** The business is hurt when the factor rises. */
        HURT,
        /**
         * Both, through different channels, and the map refuses to net them off. An integrated
         * refiner that also produces crude is the standing example: netting would report a
         * confident small number in place of two real and opposing effects.
         */
        MIXED
    }

    /** How much of the business the channel touches. A label for the reader, never a multiplier. */
    public enum Strength {
        HIGH, MEDIUM, LOW;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Higher is stronger. Used to pick the headline reason, not to compute anything. */
        public int rank() {
            return values().length - 1 - ordinal();
        }
    }

    /**
     * One rule.
     *
     * @param channel   how the factor reaches the profit and loss, in two or three words:
     *                  "input cost", "translation gain", "order book", "funding cost"
     * @param rationale one sentence a non-expert can check the rule against
     */
    public record Entry(MacroFactor factor, Scope scope, String key, OnRise onRise,
                        Strength strength, String channel, String rationale) {

        /** Where the reading came from, for the "answered by" line every surface shows. */
        public String from() {
            return scope + ":" + key;
        }
    }

    record Loaded(List<Entry> entries, String version) {
    }

    private static volatile Loaded loaded;

    // ------------------------------------------------------------------ public API

    /**
     * Every rule that applies to one stock, at most one per factor, precedence resolved.
     *
     * @param symbol   {@code NSE:INFY} or {@code INFY}; the exchange prefix is ignored
     * @param sector   a {@link SectorMapping} bucket, usually from {@code SectorMapping.resolve}
     * @param industry whatever industry string the row carries, if any
     */
    public static List<Entry> forStock(String symbol, String sector, String industry) {
        Set<String> symbolKeys = symbolKeys(symbol);
        Set<String> industryKeys = industryKeys(symbol, industry);
        String sectorKey = sector == null ? null : sector.trim().toUpperCase(Locale.ROOT);

        Map<MacroFactor, Entry> best = new LinkedHashMap<>();
        for (Entry e : all()) {
            boolean matches = switch (e.scope()) {
                case SYMBOL -> symbolKeys.contains(e.key().toUpperCase(Locale.ROOT));
                case INDUSTRY -> industryKeys.contains(e.key().toUpperCase(Locale.ROOT));
                case SECTOR -> sectorKey != null && sectorKey.equals(e.key().toUpperCase(Locale.ROOT));
            };
            if (!matches) continue;
            Entry existing = best.get(e.factor());
            // Scope ordinal IS the precedence: SYMBOL(0) beats INDUSTRY(1) beats SECTOR(2).
            if (existing == null || e.scope().ordinal() < existing.scope().ordinal()) {
                best.put(e.factor(), e);
            }
        }
        return List.copyOf(best.values());
    }

    /**
     * True when this stock has at least one rule.
     *
     * <p>This is the {@code screening_coverage} predicate (SPEC 48.7): false means the map has
     * nothing to say about this business, which is a gap in the map and never a finding about the
     * company. It must not be reported as "no exposure".
     */
    public static boolean hasMapping(String symbol, String sector, String industry) {
        return !forStock(symbol, sector, industry).isEmpty();
    }

    /** Every rule for one factor, for the audit view on the Events page. */
    public static List<Entry> forFactor(MacroFactor factor) {
        if (factor == null) return List.of();
        List<Entry> out = new ArrayList<>();
        for (Entry e : all()) {
            if (e.factor() == factor) out.add(e);
        }
        return List.copyOf(out);
    }

    /** Every rule, in file order. */
    public static List<Entry> all() {
        return loaded().entries();
    }

    /**
     * {@code mx<rev>-<hash>}, covering the code revision and the content of the file.
     *
     * <p>Stamped on every event and every recommendation this feature writes, so a reading taken
     * last month can be traced to the rules that produced it. Editing one rationale re-versions
     * every future reading - deliberately over-sensitive, per {@link ScoringVersion}: splitting two
     * identical engines costs a needless distinction, merging two different ones is unrecoverable.
     */
    public static String version() {
        return loaded().version();
    }

    /** How many rules are loaded. */
    public static int size() {
        return all().size();
    }

    // ------------------------------------------------------------------ key resolution

    private static Set<String> symbolKeys(String symbol) {
        Set<String> keys = new LinkedHashSet<>();
        if (symbol == null || symbol.isBlank()) return keys;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        keys.add(s);
        int colon = s.indexOf(':');
        String bare = colon >= 0 ? s.substring(colon + 1) : s;
        keys.add(bare);
        keys.add("NSE:" + bare);
        keys.add("BSE:" + bare);
        return keys;
    }

    /**
     * Industry spellings to try: the one the row carries, and the exchange's own classification
     * for the symbol. A holding's {@code industry} column is frequently a placeholder, so the file
     * is consulted as well rather than instead.
     */
    private static Set<String> industryKeys(String symbol, String industry) {
        Set<String> keys = new LinkedHashSet<>();
        if (industry != null && !industry.isBlank()) {
            keys.add(industry.trim().toUpperCase(Locale.ROOT));
        }
        if (symbol != null && !symbol.isBlank()) {
            String s = symbol.trim().toUpperCase(Locale.ROOT);
            int colon = s.indexOf(':');
            String bare = colon >= 0 ? s.substring(colon + 1) : s;
            String fromFile = UniverseSectors.industryFor(bare);
            if (fromFile != null && !fromFile.isBlank()) {
                keys.add(fromFile.trim().toUpperCase(Locale.ROOT));
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------ loading

    private static Loaded loaded() {
        Loaded l = loaded;
        if (l == null) {
            synchronized (MacroExposureMap.class) {
                l = loaded;
                if (l == null) {
                    l = load();
                    loaded = l;
                }
            }
        }
        return l;
    }

    private static Loaded load() {
        try (InputStream in = MacroExposureMap.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Exposure map " + RESOURCE + " is missing from the classpath. "
                        + "Without it every stock reads 'not measured' for macro exposure, which is "
                        + "indistinguishable on screen from a map that says nothing applies.");
            }
            StringBuilder text = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    text.append(line).append('\n');
                }
            }
            Loaded l = parse(text.toString());
            log.info("Macro exposure map loaded: {} rules across {} factors, version {}",
                    l.entries().size(), distinctFactors(l.entries()), l.version());
            return l;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Exposure map " + RESOURCE + " could not be read: " + e.getMessage(), e);
        }
    }

    private static int distinctFactors(List<Entry> entries) {
        Set<MacroFactor> f = new LinkedHashSet<>();
        for (Entry e : entries) {
            f.add(e.factor());
        }
        return f.size();
    }

    /**
     * Parse and validate the whole file. Visible for tests, which use it to assert that a bad row
     * is refused rather than silently dropped.
     *
     * @throws IllegalStateException naming the line number and what is wrong with it
     */
    static Loaded parse(String csv) {
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> buckets = SectorMapping.knownBuckets();
        Set<String> industries = knownIndustries();
        StringBuilder canonical = new StringBuilder();

        String[] lines = csv.split("\n", -1);
        boolean headerSeen = false;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].trim();
            int lineNo = i + 1;
            if (raw.isEmpty() || raw.startsWith("#")) {
                continue;
            }
            if (!headerSeen && raw.toLowerCase(Locale.ROOT).startsWith("factor,")) {
                headerSeen = true;
                continue;
            }
            // The rationale is last and may contain commas.
            String[] f = raw.split(",", 7);
            if (f.length < 7) {
                throw new IllegalStateException(fail(lineNo, raw, "expected 7 comma-separated fields "
                        + "(factor,scope,key,onRise,strength,channel,rationale) but found " + f.length));
            }
            final String factorRaw = f[0];
            MacroFactor factor = MacroFactor.parse(factorRaw).orElseThrow(() -> new IllegalStateException(
                    fail(lineNo, raw, "unknown factor " + quote(factorRaw) + ". Known: " + names())));
            final String scopeRaw = f[1];
            Scope scope = parseScope(scopeRaw).orElseThrow(() -> new IllegalStateException(fail(lineNo, raw,
                    "scope must be SYMBOL, INDUSTRY or SECTOR, not " + quote(scopeRaw))));
            String key = f[2].trim();
            if (key.isEmpty()) {
                throw new IllegalStateException(fail(lineNo, raw, "the key is blank"));
            }
            final String onRiseRaw = f[3];
            OnRise onRise = parseOnRise(onRiseRaw).orElseThrow(() -> new IllegalStateException(fail(lineNo, raw,
                    "onRise must be HELPED, HURT or MIXED, not " + quote(onRiseRaw))));
            final String strengthRaw = f[4];
            Strength strength = parseStrength(strengthRaw).orElseThrow(() -> new IllegalStateException(
                    fail(lineNo, raw, "strength must be HIGH, MEDIUM or LOW, not " + quote(strengthRaw))));
            String channel = f[5].trim();
            String rationale = f[6].trim();
            if (channel.isEmpty()) {
                throw new IllegalStateException(fail(lineNo, raw, "the channel is blank - name how the factor "
                        + "reaches the profit and loss, for example: input cost"));
            }
            if (rationale.isEmpty()) {
                throw new IllegalStateException(fail(lineNo, raw, "the rationale is blank. Every rule has to be "
                        + "checkable by the person reading it; an unexplained rule is an assertion."));
            }

            validateKey(lineNo, raw, scope, key, buckets, industries);

            String dedup = factor.name() + "|" + scope + "|" + key.toUpperCase(Locale.ROOT);
            if (!seen.add(dedup)) {
                throw new IllegalStateException(fail(lineNo, raw, "duplicate rule for " + dedup
                        + ". Two rules for one factor at one scope means the file disagrees with itself, "
                        + "and which one wins would depend on line order."));
            }

            entries.add(new Entry(factor, scope, key, onRise, strength, channel, rationale));
            canonical.append(dedup).append('|').append(onRise).append('|').append(strength)
                    .append('|').append(channel).append('|').append(rationale).append('\n');
        }

        if (entries.isEmpty()) {
            throw new IllegalStateException("Exposure map has no rules. Every stock would read 'not measured'.");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("map", sha256(canonical.toString()));
        params.put("rows", entries.size());
        String version = ScoringVersion.of("mx", ScoringVersion.MACRO_EVENT_CODE_REVISION, params);
        return new Loaded(Collections.unmodifiableList(entries), version);
    }

    private static void validateKey(int lineNo, String raw, Scope scope, String key,
                                    Set<String> buckets, Set<String> industries) {
        switch (scope) {
            case SECTOR -> {
                if (!buckets.contains(key.toUpperCase(Locale.ROOT))) {
                    throw new IllegalStateException(fail(lineNo, raw, quote(key) + " is not a sector this app can "
                            + "produce. SectorMapping.normalize never returns it, so the rule would match nothing, "
                            + "for ever, silently. Known buckets: " + buckets));
                }
            }
            case INDUSTRY -> {
                if (!industries.contains(key.toUpperCase(Locale.ROOT))) {
                    throw new IllegalStateException(fail(lineNo, raw, quote(key) + " is not an industry spelling "
                            + "anything in this app emits, so the rule would match nothing. Use a SYMBOL rule, or "
                            + "one of: " + industries));
                }
            }
            case SYMBOL -> {
                String bare = key.toUpperCase(Locale.ROOT);
                int colon = bare.indexOf(':');
                if (colon >= 0) {
                    bare = bare.substring(colon + 1);
                }
                if (!bare.matches("[A-Z0-9&_.-]{1,32}")) {
                    throw new IllegalStateException(fail(lineNo, raw,
                            quote(key) + " does not look like an NSE trading symbol"));
                }
            }
        }
    }

    /** Industry spellings this app can actually produce, in upper case. */
    private static Set<String> knownIndustries() {
        Set<String> out = new TreeSet<>();
        for (String k : SectorMapping.defaults().keySet()) {
            out.add(k.toUpperCase(Locale.ROOT));
        }
        for (String k : UniverseSectors.industries()) {
            out.add(k.toUpperCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(out);
    }

    private static String names() {
        StringBuilder sb = new StringBuilder();
        for (MacroFactor f : MacroFactor.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(f.name());
        }
        return sb.toString();
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.trim()) + "\"";
    }

    private static String fail(int lineNo, String raw, String why) {
        return "macro-exposure.csv line " + lineNo + ": " + why + "\n  " + raw;
    }

    private static Optional<Scope> parseScope(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String k = raw.trim().toUpperCase(Locale.ROOT);
        for (Scope s : Scope.values()) {
            if (s.name().equals(k)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    private static Optional<OnRise> parseOnRise(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String k = raw.trim().toUpperCase(Locale.ROOT);
        return switch (k) {
            case "HELPED", "BENEFITS" -> Optional.of(OnRise.HELPED);
            case "HURT", "HARMS" -> Optional.of(OnRise.HURT);
            case "MIXED", "BOTH" -> Optional.of(OnRise.MIXED);
            default -> Optional.empty();
        };
    }

    private static Optional<Strength> parseStrength(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String k = raw.trim().toUpperCase(Locale.ROOT);
        for (Strength s : Strength.values()) {
            if (s.name().equals(k)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "unhashable";
        }
    }
}
