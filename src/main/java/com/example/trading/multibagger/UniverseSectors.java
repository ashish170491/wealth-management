package com.example.trading.multibagger;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * NSE's own sector classification for the screening universe, read once from the classpath
 * resource {@code universe-sectors.csv} (SPEC §27.2, B-098).
 *
 * <p><b>Why a file and not a feed.</b> The screener classified stocks from an 89-entry hand-kept map
 * and wrote the literal {@code "Other"} for everything else — roughly two-thirds of a ~370-stock
 * universe. The Industry column then presented a placeholder as a classification, and the one
 * lens a portfolio is actually built on — sector — could not be filtered or grouped. The file is
 * seeded offline from the NSE index constituent lists (Nifty Total Market + Microcap 250, which
 * carry an {@code Industry} column), so there is no runtime download and no new scheduler
 * (SPEC §3.4). The hand-kept map stays as the override for names the investor has classified more
 * finely than the exchange does.
 *
 * <p><b>Banks are refined out of "Financial Services".</b> NSE files banks, NBFCs, insurers,
 * exchanges and asset managers under one macro sector. A retail portfolio is built with banks as a
 * sector of their own — the profile targets already say {@code BANKING} — so a Financial Services
 * row whose company name says "Bank" reads as Banking here, matching what the hand-kept map has
 * always said for HDFCBANK and SBIN. Everything else in that macro sector stays Financials.
 *
 * <p>Unknown is <b>null</b>, never "Other": the caller decides what an absent classification means,
 * and on every screen it means "not measured" (Gotcha 21 — a placeholder is not a sector).
 */
@Slf4j
public final class UniverseSectors {

    private UniverseSectors() {}

    private static final String RESOURCE = "/universe-sectors.csv";

    /** One CSV row: the exchange's macro sector and the company name it was filed under. */
    public record Entry(String symbol, String industry, String name) {}

    private static volatile Map<String, Entry> table;

    /** NSE's macro sector for a bare trading symbol (no exchange prefix), refined; null if unknown. */
    public static String industryFor(String tradingSymbol) {
        Entry e = entryFor(tradingSymbol);
        return e == null ? null : refine(e);
    }

    /** The raw row, for diagnostics and tests. Null when the symbol is not listed. */
    public static Entry entryFor(String tradingSymbol) {
        if (tradingSymbol == null || tradingSymbol.isBlank()) return null;
        return table().get(tradingSymbol.trim().toUpperCase(Locale.ROOT));
    }

    /** How many symbols the file classifies. */
    public static int size() {
        return table().size();
    }

    /**
     * Every bare trading symbol the file carries.
     *
     * <p>Exists so the headline subject resolver (SPEC §49.4) can build a company-name index from
     * the one company-name table this app already has, rather than starting a second one.
     */
    public static java.util.Set<String> symbols() {
        return java.util.Collections.unmodifiableSet(table().keySet());
    }

    /**
     * Every distinct refined industry spelling in the file, sorted.
     *
     * <p>Exists so a hand-written table keyed on industry names can be checked against reality at
     * boot instead of quietly matching nothing for ever (SPEC 48.3).
     */
    public static java.util.Set<String> industries() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (Entry e : table().values()) {
            String refined = refine(e);
            if (refined != null) out.add(refined);
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    /**
     * Banks are their own sector to an investor even though the exchange files them with every
     * other financial. The company name is the only field in the list that tells them apart.
     */
    static String refine(Entry e) {
        if (e == null || e.industry() == null) return null;
        String industry = e.industry().trim();
        if ("Financial Services".equalsIgnoreCase(industry) && e.name() != null
                && e.name().toUpperCase(Locale.ROOT).contains("BANK")) {
            return "Banking";
        }
        return industry.isEmpty() ? null : industry;
    }

    private static Map<String, Entry> table() {
        Map<String, Entry> t = table;
        if (t == null) {
            synchronized (UniverseSectors.class) {
                t = table;
                if (t == null) {
                    t = load();
                    table = t;
                }
            }
        }
        return t;
    }

    private static Map<String, Entry> load() {
        Map<String, Entry> out = new HashMap<>();
        try (InputStream in = UniverseSectors.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("Sector table {} is missing from the classpath: every stock outside the "
                        + "hand-kept map will read 'not measured' for sector.", RESOURCE);
                return Collections.emptyMap();
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                boolean header = true;
                while ((line = r.readLine()) != null) {
                    if (header) { header = false; continue; }
                    if (line.isBlank()) continue;
                    // The name is last and may in principle carry a comma, so split at most twice.
                    String[] f = line.split(",", 3);
                    if (f.length < 2) continue;
                    String symbol = f[0].trim().toUpperCase(Locale.ROOT);
                    String industry = f[1].trim();
                    if (symbol.isEmpty() || industry.isEmpty()) continue;
                    out.putIfAbsent(symbol, new Entry(symbol, industry, f.length > 2 ? f[2].trim() : null));
                }
            }
            log.info("Sector table loaded: {} symbols classified from {}", out.size(), RESOURCE);
        } catch (Exception ex) {
            log.warn("Sector table {} could not be read ({}): sectors outside the hand-kept map "
                    + "will read 'not measured'.", RESOURCE, ex.getMessage());
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(out);
    }
}
