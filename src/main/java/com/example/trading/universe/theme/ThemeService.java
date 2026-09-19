package com.example.trading.universe.theme;

import com.example.trading.multibagger.MultibaggerScreenerService;
import com.example.trading.multibagger.UniverseSectors;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Joins the theme map (SPEC §51.2) to what this app actually screens and owns.
 *
 * <p><b>The only class here that touches a repository.</b> {@link UniverseThemes},
 * {@link ThemeCatalog} and {@link ThemeCoverage} are pure and unit-tested; this one composes them
 * with the screening universe, the latest screening run and the active holdings. Everything it
 * reads is already in the database, so every endpoint built on it is page-load safe (SPEC §20
 * rule 7) — no broker call, no NSE call, no email, nothing written.
 *
 * <p><b>It changes no score.</b> A theme reading is attached to rows for display and filtering and
 * is never an input to a composite, a bonus, a cap or a verdict (SPEC §51.1). There is deliberately
 * no actionable flag to switch on, so "contributes zero points" is checkable rather than asserted
 * in a comment — {@code ThemeSurfaceContractTest} pins it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThemeService {

    private final MultibaggerScreenerService multibaggerScreenerService;
    private final MultibaggerScoreRepository multibaggerScoreRepository;
    private final HoldingsRepository holdingsRepository;
    private final com.example.trading.universe.ipo.IpoIssueRepository ipoIssueRepository;
    /**
     * Who else is quoting a target on these businesses (SPEC §51.8). DB-only — the ledger the
     * 13:20 pass already wrote, never a live fetch, so this page stays load-safe.
     *
     * <p>The ledger was already market-wide, so this adds no capture and no new engine: it
     * surfaces coverage the app had and was not showing beside the themes it now tracks.
     */
    private final com.example.trading.analyst.AnalystTargetViewService analystTargetViewService;

    /**
     * What this app can say about one business's themes.
     *
     * @param themes  catalogue entries, empty when the business is in no tracked theme
     * @param labels  the same in the investor's words, for a cell that must not print an enum
     * @param tags    the full rows, so a tooltip can name the scheme and the role
     * @param screened whether the screening universe reaches this symbol at all
     */
    public record Reading(String symbol, List<ThemeCatalog> themes, List<String> labels,
                          List<UniverseThemes.Tag> tags, boolean screened) {

        /** True when no tracked theme names this business — a finding, not a gap. */
        public boolean notInTheme() {
            return themes.isEmpty();
        }
    }

    /** One theme's coverage plus how the screened members are actually scoring. */
    public record ThemeView(ThemeCatalog theme, String label, String policy, String caution,
                            ThemeCoverage.Coverage coverage, String caveat,
                            List<Map<String, Object>> screenedRows) {
    }

    /**
     * Coverage for every populated theme, with each theme's screened members and their latest
     * scores attached.
     *
     * <p>DB-only: one universe resolution, one holdings read, one screening-run read.
     */
    public List<ThemeView> overview() {
        Set<String> screened = ThemeCoverage.normalise(universe());
        Set<String> held = ThemeCoverage.normalise(heldSymbols());
        Map<String, MultibaggerScoreEntity> latest = latestRunBySymbol();

        // One bulk query for every screened member across every theme, not one per row: each
        // symbol resolves through up to four exchange spellings (Gotcha 84), so per-row it would
        // be several hundred lookups for one page.
        Map<String, com.example.trading.analyst.AnalystTargetViewService.Coverage> analyst =
                analystCoverage(screened);

        List<ThemeView> out = new ArrayList<>();
        for (ThemeCoverage.Coverage c : ThemeCoverage.all(screened, held)) {
            ThemeCatalog t = c.theme();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ThemeCoverage.Member m : c.members()) {
                if (m.status() != ThemeCoverage.Status.SCREENED) {
                    continue;
                }
                MultibaggerScoreEntity e = latest.get(m.symbol());
                rows.add(row(m, e, analyst.get(m.symbol())));
            }
            // Strongest first, but a member with no row from the latest run keeps its place at the
            // end rather than being dropped: "screened but not in the last run" is a different
            // thing from "not screened", and collapsing them hides a stock that fell out.
            rows.sort((a, b) -> {
                Integer sa = (Integer) a.get("compositeScore");
                Integer sb = (Integer) b.get("compositeScore");
                if (sa == null && sb == null) return 0;
                if (sa == null) return 1;
                if (sb == null) return -1;
                return sb - sa;
            });
            out.add(new ThemeView(t, t.label(), t.policy(), t.caution(), c, c.caveat(), rows));
        }
        return out;
    }

    /** One theme, or null when the catalogue has no rows for it. */
    public ThemeView forTheme(ThemeCatalog theme) {
        if (theme == null) {
            return null;
        }
        return overview().stream().filter(v -> v.theme() == theme).findFirst().orElse(null);
    }

    /** One symbol's reading. */
    public Reading forSymbol(String symbol) {
        return read(symbol, ThemeCoverage.normalise(universe()));
    }

    private Reading read(String symbol, Set<String> screened) {
        List<UniverseThemes.Tag> tags = UniverseThemes.tagsFor(symbol);
        List<ThemeCatalog> themes = UniverseThemes.themesFor(symbol);
        List<String> labels = themes.stream().map(ThemeCatalog::label).toList();
        Set<String> bare = ThemeCoverage.normalise(List.of(symbol));
        boolean isScreened = !bare.isEmpty() && screened.contains(bare.iterator().next());
        return new Reading(symbol, themes, labels, tags, isScreened);
    }

    /**
     * The headline the question deserves: across every theme, how many tagged businesses the app
     * screens, how many it does not reach, and how many it cannot confirm.
     */
    public Map<String, Object> summary() {
        List<ThemeView> views = overview();
        int tagged = 0;
        int covered = 0;
        int gaps = 0;
        int unverified = 0;
        int held = 0;
        for (ThemeView v : views) {
            tagged += v.coverage().tagged();
            covered += v.coverage().covered();
            gaps += v.coverage().gaps();
            unverified += v.coverage().unverified();
            held += v.coverage().held();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("themes", views.size());
        // Rows, not distinct companies: a business in two themes is two rows of coverage, because
        // the question "how much of this theme do we see" is asked per theme.
        m.put("taggedRows", tagged);
        m.put("distinctCompanies", UniverseThemes.taggedSymbols().size());
        m.put("screened", covered);
        m.put("notScreened", gaps);
        m.put("unverified", unverified);
        m.put("heldRows", held);
        m.put("coveragePercent", (covered + gaps) == 0 ? null : covered * 100.0 / (covered + gaps));
        // The map's own vintage, on the screen that reads it. Nothing can update this file, so a
        // reader deciding how much to trust a coverage figure needs to know how old the list it is
        // counted against actually is (SPEC 51.9).
        m.put("reviewedOn", UniverseThemes.reviewedOn());
        m.put("policyAsOf", UniverseThemes.policyAsOf());
        m.put("reviewDaysAgo", UniverseThemes.reviewedOn() == null ? null
                : java.time.temporal.ChronoUnit.DAYS.between(UniverseThemes.reviewedOn(), LocalDate.now()));
        m.put("caveat", "Counted against this app's own hand-kept theme map, which names businesses "
                + "someone added and is not a complete census of any sector. Screened means the app "
                + "analyses the business — never that it is worth owning, and never that the "
                + "government's money will reach its shareholders.");
        return m;
    }

    private Map<String, Object> row(ThemeCoverage.Member m, MultibaggerScoreEntity e,
                                    com.example.trading.analyst.AnalystTargetViewService.Coverage c) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("symbol", m.symbol());
        r.put("theme", m.theme().name());
        r.put("themeLabel", m.theme().label());
        r.put("policy", m.policy());
        r.put("role", m.role());
        r.put("held", m.held());
        // Absent, not zero, when the latest run has no row for this symbol: a stock screened at
        // some point but missing from the last run must read "not measured", never a score of 0
        // (Gotcha 21).
        r.put("compositeScore", e == null ? null : e.getCompositeScore());
        r.put("verdict", e == null ? null : e.getVerdict());
        r.put("grade", e == null ? null : e.getGrade());
        r.put("screeningDate", e == null ? null : e.getScreeningDate());
        // The same fifteen keys every other screen writes, from the one shared writer (B-099).
        // A null coverage writes NOTHING, so the cell draws "not measured" rather than claiming
        // nobody covers the stock - different facts, and only one of them is ours to assert.
        r.putAll(com.example.trading.analyst.AnalystTargetViewService.wireFields(c));
        return r;
    }

    /** Coverage keyed by bare symbol, empty on any failure - the page must still render. */
    private Map<String, com.example.trading.analyst.AnalystTargetViewService.Coverage>
            analystCoverage(Set<String> bareSymbols) {
        Map<String, com.example.trading.analyst.AnalystTargetViewService.Coverage> out =
                new LinkedHashMap<>();
        try {
            List<String> tagged = UniverseThemes.taggedSymbols().stream()
                    .filter(bareSymbols::contains)
                    .map(sym -> "NSE:" + sym)
                    .toList();
            analystTargetViewService.forSymbols(tagged).forEach((k, v) ->
                    ThemeCoverage.normalise(List.of(k)).forEach(bare -> out.put(bare, v)));
        } catch (Exception ex) {
            log.warn("Theme analyst coverage unavailable ({}), so the Analysts column will read "
                    + "'not measured' rather than 'nobody covers it' - different facts.",
                    ex.getMessage());
        }
        return out;
    }

    /**
     * Businesses that look like they belong in a funded theme and are not in the map (SPEC 51.10).
     *
     * <p>DB-only: the listings the 12:15 IPO capture already wrote, plus two classpath files. No
     * fetch, no broker call, nothing written - this proposes, and a person edits the map.
     *
     * @param months how far back to read listings; the decay path the map cannot otherwise see
     */
    public ThemeCandidates.Scan candidates(int months) {
        int window = Math.max(1, Math.min(months, 120));
        List<UniverseSectors.Entry> constituents = UniverseSectors.symbols().stream()
                .map(UniverseSectors::entryFor)
                .filter(java.util.Objects::nonNull)
                .toList();
        return ThemeCandidates.scan(recentListings(window), constituents,
                UniverseThemes.taggedSymbols(), ThemeCoverage.normalise(universe()));
    }

    /**
     * Recent mainboard listings, or an empty list.
     *
     * <p>An empty list on failure is safe here only because {@link ThemeCandidates.Scan#recall()}
     * reports the number scanned: "0 of 0 listings" reads as a lookup that returned nothing, which
     * is a different sentence from "0 of 246", and the reader can tell them apart (B-054's rule).
     */
    private List<ThemeCandidates.Listing> recentListings(int months) {
        try {
            return ipoIssueRepository.findListedSince(LocalDate.now().minusMonths(months)).stream()
                    .map(i -> new ThemeCandidates.Listing(
                            i.getSymbol(), i.getCompanyName(), i.getListingDate()))
                    .toList();
        } catch (Exception ex) {
            log.warn("Theme candidates: recent listings unavailable ({}), so the new-listing lane "
                    + "will report nothing scanned rather than nothing found.", ex.getMessage());
            return List.of();
        }
    }

    private List<String> universe() {
        try {
            return multibaggerScreenerService.resolvedScreeningUniverse();
        } catch (Exception ex) {
            // An empty universe would report every theme at 0% covered, which reads as a broken
            // feature rather than a failed lookup. Say so in the log; the caveat line on every
            // surface already tells the reader what the figure is counted against.
            log.warn("Theme coverage: the screening universe could not be resolved ({}), so every "
                    + "theme will report as uncovered. This is a lookup failure, not a coverage "
                    + "finding.", ex.getMessage());
            return List.of();
        }
    }

    private List<String> heldSymbols() {
        try {
            return holdingsRepository.findActive().stream()
                    .map(HoldingsEntity::getSymbol)
                    .filter(s -> s != null && !s.isBlank())
                    .toList();
        } catch (Exception ex) {
            log.warn("Theme coverage: holdings unavailable ({}), so no member will be marked as "
                    + "owned.", ex.getMessage());
            return List.of();
        }
    }

    /**
     * The latest screening run that actually has rows, keyed by bare symbol.
     *
     * <p>Walks back through known screening dates rather than asking for today's, for the reason
     * Gotcha 20 gives: the run fires at 14:00 and this app restarts daily, so "today" is empty
     * for most of the day.
     */
    private Map<String, MultibaggerScoreEntity> latestRunBySymbol() {
        Map<String, MultibaggerScoreEntity> out = new LinkedHashMap<>();
        try {
            List<LocalDate> dates = multibaggerScoreRepository.findScreeningDates();
            for (LocalDate date : dates.stream().limit(10).toList()) {
                List<MultibaggerScoreEntity> rows =
                        multibaggerScoreRepository.findByScreeningDateOrderByCompositeScoreDesc(date);
                if (rows.isEmpty()) {
                    continue;
                }
                for (MultibaggerScoreEntity e : rows) {
                    Set<String> bare = ThemeCoverage.normalise(List.of(String.valueOf(e.getSymbol())));
                    if (bare.isEmpty()) {
                        continue;
                    }
                    // A company can be screened under more than one exchange spelling on one date
                    // (B-061). First write wins and the rest are ignored rather than blended.
                    out.putIfAbsent(bare.iterator().next(), e);
                }
                break;
            }
        } catch (Exception ex) {
            log.warn("Theme coverage: latest screening run unavailable ({}), so screened members "
                    + "will show no score.", ex.getMessage());
        }
        return out;
    }
}
