package com.example.trading.universe.theme;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Policy-backed theme coverage (SPEC §51.6). Every endpoint here is DB-only and page-load safe:
 * no broker call, no NSE call, no email, nothing written.
 *
 * <p><b>{@code symbol} is a query parameter, never a path variable</b> — symbols carry a colon
 * ({@code NSE:KAYNES}), which is legal but hazardous across Tomcat, Spring and {@code fetch()}.
 *
 * <p>Nothing here produces a verdict on a stock or contributes a point to any score.
 */
@Slf4j
@RestController
@RequestMapping("/api/themes")
@RequiredArgsConstructor
public class ThemeController {

    private final ThemeService themeService;

    /**
     * Every populated theme with its coverage, its members and the latest scores of the ones we
     * screen. This is the screen that answers "are we even looking at these stocks?".
     */
    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", themeService.summary());
        out.put("themes", themeService.overview().stream().map(ThemeController::describe).toList());
        return out;
    }

    /** The catalogue itself — the rules, so they can be read rather than trusted. No I/O at all. */
    @GetMapping("/catalog")
    public List<Map<String, Object>> catalog() {
        return UniverseThemes.populatedThemes().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("theme", t.name());
            m.put("label", t.label());
            m.put("policy", t.policy());
            m.put("caution", t.caution());
            m.put("tagged", UniverseThemes.membersOf(t).size());
            return m;
        }).toList();
    }

    /**
     * One theme. 404 when the name is not in the catalogue or has no rows — an unknown theme is a
     * different fact from a theme with nothing in it, and only the second deserves an empty list.
     */
    @GetMapping("/theme")
    public ResponseEntity<Map<String, Object>> theme(@RequestParam String name) {
        var parsed = ThemeCatalog.parse(name);
        if (parsed.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ThemeService.ThemeView v = themeService.forTheme(parsed.get());
        if (v == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(describe(v));
    }

    /**
     * One stock's themes.
     *
     * <p>Always 200. An untagged business returns {@code notInTheme: true}, which means the map
     * was consulted and no tracked theme names it — a finding. A 404 would be read as "this stock
     * does not exist", which is a different and wrong claim (Gotcha 121's distinction).
     */
    @GetMapping("/stock")
    public Map<String, Object> stock(@RequestParam String symbol) {
        ThemeService.Reading r = themeService.forSymbol(symbol);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", r.symbol());
        m.put("themes", r.themes().stream().map(Enum::name).toList());
        m.put("themeLabels", r.labels());
        m.put("notInTheme", r.notInTheme());
        m.put("screened", r.screened());
        m.put("tags", r.tags().stream().map(t -> {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("theme", t.theme().name());
            tm.put("label", t.theme().label());
            tm.put("policy", t.policy());
            tm.put("role", t.role());
            tm.put("caution", t.theme().caution());
            tm.put("verified", t.verified());
            return tm;
        }).toList());
        return m;
    }

    /**
     * The gap list on its own: confirmed listings in a funded theme that the screening universe
     * does not reach. This is the actionable half of the feature.
     */
    @GetMapping("/gaps")
    public Map<String, Object> gaps() {
        List<Map<String, Object>> rows = themeService.overview().stream()
                .flatMap(v -> v.coverage().gapMembers().stream().map(m -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("symbol", m.symbol());
                    r.put("theme", m.theme().name());
                    r.put("themeLabel", m.theme().label());
                    r.put("policy", m.policy());
                    r.put("role", m.role());
                    return r;
                }))
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        out.put("gaps", rows);
        out.put("note", "Confirmed NSE listings in a funded theme that the screening universe does "
                + "not reach, so no pillar has ever been measured on them. Adding one means the app "
                + "will analyse it — it is not a suggestion to buy it.");
        return out;
    }

    /**
     * Businesses that look like they belong in a funded theme and are not in the map (SPEC 51.10).
     *
     * <p>The other half of {@link #gaps()}. That one finds a business the map names and the
     * universe does not reach; this one finds a business the market has and the map has never
     * named - the half that actually decays, because nothing in this app can update the map.
     *
     * <p>DB- and classpath-only. It <b>proposes</b>: accepting a candidate means editing
     * {@code universe-themes.csv} by hand, and the response carries its own measured hit rate so a
     * short list is not mistaken for a quiet market.
     */
    @GetMapping("/candidates")
    public Map<String, Object> candidates(@RequestParam(defaultValue = "36") int months) {
        ThemeCandidates.Scan scan = themeService.candidates(months);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", scan.candidates().size());
        out.put("windowMonths", months);
        out.put("listingsScanned", scan.listingsScanned());
        out.put("listingsMatched", scan.listingsMatched());
        out.put("constituentsScanned", scan.constituentsScanned());
        out.put("themesWithoutHint", scan.themesWithoutHint());
        out.put("recall", scan.recall());
        out.put("caveat", scan.caveat());
        out.put("candidates", scan.candidates().stream().map(c -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("symbol", c.symbol());
            r.put("companyName", c.companyName());
            r.put("theme", c.theme().name());
            r.put("themeLabel", c.label());
            r.put("matched", c.matched());
            r.put("lane", c.lane().name());
            r.put("listingDate", c.listingDate());
            r.put("industry", c.industry());
            r.put("screened", c.screened());
            return r;
        }).toList());
        out.put("note", "Evidence, not a verdict: each row says which word matched which company "
                + "name. A row already screened costs nothing to add to the map; one that is not "
                + "adds a symbol the app will fetch prices for on every run.");
        return out;
    }

    private static Map<String, Object> describe(ThemeService.ThemeView v) {
        ThemeCoverage.Coverage c = v.coverage();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("theme", v.theme().name());
        m.put("label", v.label());
        m.put("policy", v.policy());
        m.put("caution", v.caution());
        m.put("tagged", c.tagged());
        m.put("screened", c.covered());
        m.put("notScreened", c.gaps());
        m.put("unverified", c.unverified());
        m.put("held", c.held());
        m.put("coveragePercent", c.coveragePercent());
        m.put("caveat", v.caveat());
        m.put("members", c.members().stream().map(mem -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("symbol", mem.symbol());
            r.put("status", mem.status().name());
            r.put("policy", mem.policy());
            r.put("role", mem.role());
            r.put("held", mem.held());
            return r;
        }).toList());
        m.put("screenedRows", v.screenedRows());
        return m;
    }
}
