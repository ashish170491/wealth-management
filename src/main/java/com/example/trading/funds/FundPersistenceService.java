package com.example.trading.funds;

import com.example.trading.funds.MutualFundHistoryFetchEntity.Status;
import com.example.trading.funds.RollingWindowRecord.Point;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Does picking a fund on its past record work? (SPEC §52.13, §52.14)
 *
 * <p>The DB-backed half of {@link FundPersistence}: it builds the two windows out of the month-end
 * archive, computes each fund's compound rate over each of them, and hands the pure class a plain
 * map to measure. It also answers the question that only becomes askable once index funds are on
 * file (§52.14): <b>how many actively managed funds in a category beat the tracker covering the
 * same part of the market</b>.
 *
 * <p>Three implementation decisions carry weight.
 *
 * <ol>
 *   <li><b>The window bounds come from {@code mf_history_fetch}, not from the NAV rows.</b> The
 *       daily 12:30 pull writes a row for every tracked scheme, so the newest month present in
 *       {@code mf_nav_history} is the current part-month rather than an archive month end - and a
 *       fund's "return to the end of the archive" would then silently be measured to a different
 *       date for a scheme the daily pull covers than for one it does not. Asking the fetch ledger
 *       for the newest month it actually fetched makes both windows the same dates for every fund,
 *       which is the whole basis of a cross-sectional ranking (B-047's family).</li>
 *   <li><b>A boundary month is required exactly, and a fund missing one is dropped rather than
 *       interpolated.</b> The archive stores one file per month, so a fund present that month has a
 *       row; a missing boundary means it was not reporting. Filling it in would invent the datum the
 *       whole measurement turns on.</li>
 *   <li><b>The median tracker is the yardstick, never one tracker.</b> Several funds track the same
 *       index with different tracking error, and picking whichever one happens to sort first would
 *       let one badly-run tracker decide whether active management looks good.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FundPersistenceService {

    /**
     * Months of archive needed before the question can be asked at all.
     *
     * <p>Sixty, so each window is at least thirty months - close to the thirty-six any single
     * rolling figure needs elsewhere in this module. Below that the two windows are short enough
     * that each one mostly records one market mood, and comparing two moods is not a test of skill.
     */
    public static final int MIN_TOTAL_MONTHS = 60;

    private final MutualFundSchemeRepository schemeRepository;
    private final MutualFundHistoryFetchRepository historyFetchRepository;
    private final FundLensService lensService;

    /** The whole reading, shaped for the wire. */
    public Map<String, Object> persistence() {
        Map<String, Object> out = new LinkedHashMap<>();

        List<YearMonth> fetched = historyFetchRepository.findAllByOrderByForMonthAsc().stream()
                .filter(e -> e.getStatus() == Status.FETCHED)
                .map(e -> YearMonth.from(e.getForMonth()))
                .sorted()
                .toList();

        if (fetched.size() < MIN_TOTAL_MONTHS) {
            out.put("measured", false);
            out.put("monthsOnFile", fetched.size());
            out.put("monthsNeeded", MIN_TOTAL_MONTHS);
            out.put("reason", "the month-end archive holds " + fetched.size() + " months and "
                    + MIN_TOTAL_MONTHS + " are needed before it can be split into two windows long "
                    + "enough to mean anything. That is a gap in this app's record, not a finding "
                    + "about funds.");
            return out;
        }

        YearMonth start = fetched.get(0);
        YearMonth end = fetched.get(fetched.size() - 1);
        int span = (int) start.until(end, java.time.temporal.ChronoUnit.MONTHS);
        YearMonth mid = start.plusMonths(span / 2);

        List<MutualFundSchemeEntity> schemes = schemeRepository.findAll();
        Map<Long, MutualFundSchemeEntity> byCode = new LinkedHashMap<>();
        for (MutualFundSchemeEntity s : schemes) byCode.put(s.getAmfiSchemeCode(), s);

        Map<Long, List<Point>> series = lensService.monthEndSeries(byCode.keySet());

        // ------------------------------------------------------------------ the two windows
        int monthsA = (int) start.until(mid, java.time.temporal.ChronoUnit.MONTHS);
        int monthsB = (int) mid.until(end, java.time.temporal.ChronoUnit.MONTHS);

        Map<SebiCategory, List<FundPersistence.Entry>> byCategory = new EnumMap<>(SebiCategory.class);
        int droppedIncomplete = 0;

        for (MutualFundSchemeEntity s : schemes) {
            // Only the judged set. Index funds are on file now and are the yardstick, not
            // contestants: a tracker inside the active cross-section would be ranked on how well it
            // failed to try, which is not the question.
            if (!s.isScreenable() || s.getCategory() == null) {
                continue;
            }
            Map<YearMonth, BigDecimal> m = asMap(series.get(s.getAmfiSchemeCode()));
            Double a = cagr(m, start, mid, monthsA);
            Double b = cagr(m, mid, end, monthsB);

            if (a != null && b == null && !stoppedReporting(m, end)) {
                // Present throughout but missing a boundary month: not measurable over this exact
                // window, and NOT a fund that wound up. Counted so the survivorship figure below
                // cannot quietly absorb it.
                droppedIncomplete++;
                continue;
            }
            if (a == null && b == null) {
                continue;
            }
            byCategory.computeIfAbsent(s.getCategory(), k -> new ArrayList<>())
                    .add(new FundPersistence.Entry(s.getAmfiSchemeCode(), s.getSchemeName(), a, b));
        }

        FundPersistence.Result result = FundPersistence.measure(byCategory);

        out.put("measured", true);
        out.put("windowAFrom", start.toString());
        out.put("windowATo", mid.toString());
        out.put("windowBFrom", mid.toString());
        out.put("windowBTo", end.toString());
        out.put("windowMonths", Math.min(monthsA, monthsB));
        out.put("monthsOnFile", fetched.size());
        out.put("droppedIncompleteSeries", droppedIncomplete);

        out.put("verdict", result.verdict().name());
        out.put("verdictLabel", result.verdict().label());
        out.put("headline", result.headline());
        out.put("chancePct", FundPersistence.CHANCE * 100);
        out.put("categoriesMeasured", result.categoriesMeasured());
        out.put("fundsInBothWindows", result.fundsInBothWindows());
        out.put("topQuartileA", result.topQuartileA());
        out.put("stayedTopQuartile", result.stayedTopQuartile());
        out.put("fellToBottomQuartile", result.fellToBottomQuartile());
        out.put("shareStayedTopPct", result.shareStayedTopPct());
        out.put("shareStayedTopLowerBoundPct", result.shareStayedTopLowerBoundPct());
        // The resolution of the headline, so a reader can see how many funds the gap is worth.
        out.put("onePerFundPp", result.onePerFundPp());
        out.put("bandFloorPp", result.bandFloorPp());
        out.put("rankCorrelation", result.rankCorrelation());
        out.put("vanishedAfterA", result.vanishedAfterA());
        out.put("vanishedAfterATopQuartile", result.vanishedAfterATopQuartile());
        out.put("caveats", result.caveats());

        List<Map<String, Object>> cats = new ArrayList<>();
        for (FundPersistence.CategoryResult c : result.categories()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", c.category() == null ? "UNCLASSIFIED" : c.category().name());
            row.put("categoryLabel", c.categoryLabel());
            row.put("fundsInBothWindows", c.fundsInBothWindows());
            row.put("topQuartileA", c.topQuartileA());
            row.put("stayedTopQuartile", c.stayedTopQuartile());
            row.put("fellToBottomQuartile", c.fellToBottomQuartile());
            row.put("shareStayedTopPct", c.shareStayedTopPct());
            row.put("rankCorrelation", c.rankCorrelation());
            row.put("vanishedAfterA", c.vanishedAfterA());
            row.put("vanishedAfterATopQuartile", c.vanishedAfterATopQuartile());
            row.put("inHeadline", c.inHeadline());
            row.put("reason", c.reason());
            cats.add(row);
        }
        out.put("categories", cats);

        out.put("indexComparison", indexComparison(schemes, series, end));
        return out;
    }

    // ------------------------------------------------------------------ the tracker comparison

    /**
     * Minimum months a tracker comparison needs before it is published.
     *
     * <p>The same thirty-six every other rolling figure in this module needs. Below it the
     * comparison is one market mood rather than a record.
     */
    public static final int MIN_TRACKER_MONTHS = 36;

    /**
     * Active funds a category needs before a "share that beat the tracker" percentage is published.
     *
     * <p>Multi Cap measured <b>8 of 8</b> on the first live run - 100%, from eight funds, five more
     * of which had wound up. A percentage over eight is one fund per twelve and a half points, and
     * "100% of multi-cap funds beat the index" is the most over-readable sentence this endpoint
     * could produce. The counts are still published; the percentage is withheld. Same rule and same
     * reason as {@link FundPersistence#MIN_TOP_QUARTILE_FOR_SHARE}.
     */
    public static final int MIN_ACTIVE_FOR_SHARE = 10;

    /**
     * How many actively managed funds in each category beat the index fund covering the same part
     * of the market - over <b>the longest window both sides actually share</b>.
     *
     * <p>The first version measured this over the whole archive and could answer for exactly one
     * category: index funds in the mid, small, flexi and large-and-mid segments are mostly
     * post-2021 launches, so they had no 2018 series and every one of those categories reported a
     * gap. That is honest and nearly useless, and the fix is not to relax the comparison - it is to
     * shorten the <em>window</em> and say which window it is. Both sides are then measured over
     * identical dates, which is the only thing that ever mattered; what changes is how much history
     * stands behind each category's answer, and that is printed beside it.
     *
     * <p>The alternative - comparing an eight-year active record against a three-year tracker
     * record - is the units error this module exists to refuse (B-047's family, and B-113's: two
     * rates measured over different spans are not comparable however carefully each was computed).
     *
     * <p><b>Survivorship is counted here too.</b> The active funds with a complete series are the
     * ones that survived, so a raw "79% of active funds beat the tracker" is computed over exactly
     * the population that did not die. Funds reporting when the window opened and gone before it
     * closed are counted and published beside it, because a fund that wound up beat nothing.
     */
    private List<Map<String, Object>> indexComparison(
            List<MutualFundSchemeEntity> schemes, Map<Long, List<Point>> series, YearMonth end) {

        Map<Long, String> indexFunds = new LinkedHashMap<>();
        for (MutualFundSchemeEntity s : schemes) {
            if (s.getCategory() == SebiCategory.INDEX_EQUITY) {
                indexFunds.put(s.getAmfiSchemeCode(), s.getSchemeName());
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (SebiCategory category : SebiCategory.values()) {
            if (!category.isActivelyManagedEquity()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", category.name());
            row.put("categoryLabel", category.label());

            IndexYardstick.Yardstick y = IndexYardstick.forCategory(category, indexFunds);
            if (!y.available()) {
                row.put("available", false);
                row.put("reason", y.reason());
                row.put("trackers", List.of());
                out.add(row);
                continue;
            }

            // The earliest month from which at least one matched tracker has an unbroken series to
            // the end of the archive. One long-lived tracker is enough to set the window; the
            // median is then taken over every tracker that covers it.
            YearMonth windowStart = null;
            for (IndexYardstick.Match m : y.funds()) {
                Map<YearMonth, BigDecimal> s = asMap(series.get(m.schemeCode()));
                if (s.isEmpty() || !s.containsKey(end)) {
                    continue;
                }
                YearMonth first = ((TreeMap<YearMonth, BigDecimal>) s).firstKey();
                if (windowStart == null || first.isBefore(windowStart)) {
                    windowStart = first;
                }
            }
            int months = windowStart == null ? 0
                    : (int) windowStart.until(end, java.time.temporal.ChronoUnit.MONTHS);
            if (windowStart == null || months < MIN_TRACKER_MONTHS) {
                row.put("available", false);
                row.put("reason", "the index funds tracking this part of the market have "
                        + (windowStart == null ? "no" : months + " months of")
                        + " history on file and " + MIN_TRACKER_MONTHS + " are needed - most "
                        + "trackers outside the large-cap segment launched recently. A gap in this "
                        + "app's record, not a statement about " + category.label() + ".");
                row.put("trackers", List.of());
                out.add(row);
                continue;
            }

            List<Double> trackerCagrs = new ArrayList<>();
            List<Map<String, Object>> used = new ArrayList<>();
            Set<String> seenNames = new HashSet<>();
            for (IndexYardstick.Match m : y.funds()) {
                // One AMC listing the same tracker twice would otherwise count twice in the median.
                if (!seenNames.add(m.schemeName() == null ? "" : m.schemeName().trim())) {
                    continue;
                }
                Double v = cagr(asMap(series.get(m.schemeCode())), windowStart, end, months);
                if (v == null) {
                    continue;
                }
                trackerCagrs.add(v);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("schemeName", m.schemeName());
                f.put("matchedPhrase", m.matchedPhrase());
                f.put("cagrPct", v);
                used.add(f);
            }

            List<Double> active = new ArrayList<>();
            int vanished = 0;
            for (MutualFundSchemeEntity s : schemes) {
                if (!s.isScreenable() || s.getCategory() != category) {
                    continue;
                }
                Map<YearMonth, BigDecimal> m = asMap(series.get(s.getAmfiSchemeCode()));
                if (m.isEmpty() || !m.containsKey(windowStart)) {
                    continue;
                }
                Double v = cagr(m, windowStart, end, months);
                if (v != null) {
                    active.add(v);
                } else if (stoppedReporting(m, end)) {
                    // Reporting when the window opened, gone before it closed. It did not beat the
                    // tracker; it stopped existing, and leaving it out flatters active management.
                    vanished++;
                }
            }

            if (trackerCagrs.isEmpty() || active.isEmpty()) {
                row.put("available", false);
                row.put("reason", "no complete series on both sides over " + windowStart + " to "
                        + end + ", so there is nothing to compare");
                row.put("trackers", List.of());
                out.add(row);
                continue;
            }

            double tracker = median(trackerCagrs);
            long beat = active.stream().filter(v -> v > tracker).count();
            row.put("available", true);
            row.put("windowFrom", windowStart.toString());
            row.put("windowTo", end.toString());
            row.put("windowMonths", months);
            row.put("activeFundsMeasured", active.size());
            row.put("activeFundsVanished", vanished);
            row.put("trackerCagrPct", tracker);
            row.put("trackers", used);
            row.put("beatTracker", (int) beat);
            boolean enough = active.size() >= MIN_ACTIVE_FOR_SHARE;
            row.put("beatTrackerPct", enough ? (beat * 100.0) / active.size() : null);
            // The honest lower bound: a fund that wound up mid-window beat nothing.
            row.put("beatTrackerPctIncludingVanished",
                    enough ? (beat * 100.0) / (active.size() + vanished) : null);
            row.put("activeMedianCagrPct", median(active));
            // Built from the trackers ACTUALLY used, not from the ones the name table matched:
            // several match and only some have history over this window, and a summary describing
            // a different set from the rows beneath it is B-117 exactly. The word "median" is only
            // used where there is more than one to take a median of.
            row.put("reason", (used.size() == 1
                    ? "one index fund covers this window - " + used.get(0).get("schemeName")
                    : used.size() + " index funds covering this window are used and the median of "
                            + "them is the figure compared against, so one badly-run tracker cannot "
                            + "decide the answer")
                    + (enough ? "" : ". Only " + active.size() + " active funds have a complete "
                            + "series over it, too few to turn into a percentage without one fund "
                            + "moving it by " + String.format("%.1f", 100.0 / active.size())
                            + " points"));
            out.add(row);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static Map<YearMonth, BigDecimal> asMap(List<Point> points) {
        Map<YearMonth, BigDecimal> m = new TreeMap<>();
        if (points != null) {
            for (Point p : points) m.put(p.month(), p.nav());
        }
        return m;
    }

    /** Compound rate between two exact months, or null when either end is missing. */
    private static Double cagr(Map<YearMonth, BigDecimal> series, YearMonth from, YearMonth to,
                               int months) {
        if (series == null || months <= 0) return null;
        BigDecimal a = series.get(from);
        BigDecimal b = series.get(to);
        if (a == null || b == null) return null;
        return RollingWindowRecord.cagrPct(a, b, months);
    }

    /**
     * True when this fund's series stops well before the end of the archive.
     *
     * <p>Three months of slack, because a scheme that merges is removed from the file on a date of
     * its own choosing and the last month is often partial. Anything short of that and a fund that
     * simply missed the final month end would be reported as wound up - a survivorship figure
     * inflated by a publishing gap.
     */
    private static boolean stoppedReporting(Map<YearMonth, BigDecimal> series, YearMonth end) {
        if (series.isEmpty()) return true;
        YearMonth last = ((TreeMap<YearMonth, BigDecimal>) series).lastKey();
        return last.isBefore(end.minusMonths(3));
    }

    private static double median(List<Double> xs) {
        List<Double> sorted = new ArrayList<>(xs);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }
}
