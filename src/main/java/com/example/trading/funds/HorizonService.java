package com.example.trading.funds;

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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Where do I put money I need in N months? (SPEC §52.16)
 *
 * <p>The question §52.13 could not answer, for a structural reason rather than a bad one: the
 * module had persisted no debt scheme and no liquid fund at all, so there was no universe to point
 * at. §52.15 widened the kept set; this joins {@link HorizonFit}'s rule table to the archive.
 *
 * <p><b>It ranks nothing and names no scheme as worth owning.</b> Two different things are being
 * reported and they must not be confused with a recommendation. {@link HorizonFit} reads SEBI's own
 * category mandates - what a category is <em>built</em> for, which is regulation and does not decay
 * the way a performance ranking does. {@link CategoryDownside} reads what actually happened to each
 * category in this app's own archive, which is the only evidence that can contradict the
 * regulation, and on the two occasions in this archive when it did the funds involved were sold as
 * safe.
 *
 * <p>Page-load safe: DB-only, no network call of any kind. Heavier than its neighbours, because it
 * reads every stored month-end point for the fitting categories.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HorizonService {

    /**
     * How stale a scheme's last published NAV may be before it is not counted as available.
     *
     * <p>Forty-five days. A scheme that has not published in a month and a half has almost
     * certainly merged, and counting it would overstate how many funds a category actually offers
     * (the live file carries 5,640 rows whose NAV is more than a week old).
     */
    public static final int AVAILABLE_WITHIN_DAYS = 45;

    /**
     * The shortest history a scheme needs before its own worst window is published.
     *
     * <p>Three times the horizon, so the figure rests on more than one stretch of it, and never
     * fewer than 24 months.
     */
    public static final int MIN_SCHEME_MONTHS_MULTIPLE = 3;

    private final MutualFundSchemeRepository schemeRepository;
    private final MutualFundHoldingRepository holdingRepository;
    private final FundLensService lensService;
    private final MonthEndSeriesCache seriesCache;

    /**
     * The whole reading for one horizon.
     *
     * @param months   the horizon in months
     * @param category optional, a single category to expand into its scheme list
     */
    public Map<String, Object> forHorizon(int months, SebiCategory category) {
        Map<String, Object> out = new LinkedHashMap<>();
        int m = Math.max(1, Math.min(HorizonFit.MAX_HORIZON_MONTHS, months));
        out.put("months", m);
        out.put("monthsAsked", months);
        out.put("horizonLabel", horizonLabel(m));

        List<MutualFundSchemeEntity> schemes = schemeRepository.findAll();

        // Only what a person could actually buy today: open-ended, Direct plan, Growth option, and
        // still publishing. A merged scheme in the count would make a category look better stocked
        // than it is.
        List<MutualFundSchemeEntity> available = schemes.stream()
                .filter(HorizonService::isAvailableToday)
                .toList();

        Set<Long> owned = holdingRepository.findByActiveTrue().stream()
                .map(MutualFundHoldingEntity::getAmfiSchemeCode)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        Map<SebiCategory, List<MutualFundSchemeEntity>> byCategory = new EnumMap<>(SebiCategory.class);
        for (MutualFundSchemeEntity s : available) {
            if (s.getCategory() != null) {
                byCategory.computeIfAbsent(s.getCategory(), k -> new ArrayList<>()).add(s);
            }
        }

        // The downside reading needs the series for every scheme in a category, including ones that
        // have stopped publishing - they are the survivorship evidence (§52.9).
        Map<SebiCategory, List<MutualFundSchemeEntity>> allByCategory = new EnumMap<>(SebiCategory.class);
        for (MutualFundSchemeEntity s : schemes) {
            if (s.getCategory() != null && s.getPlan() == SchemePlanKey.Plan.DIRECT
                    && s.getNavOption() == SchemePlanKey.Option.GROWTH) {
                allByCategory.computeIfAbsent(s.getCategory(), k -> new ArrayList<>()).add(s);
            }
        }

        // ONE shared read of the month-end archive for every category at once, not one per
        // category. Forty separate queries over most of mf_nav_history is B-115's shape exactly -
        // a page-load endpoint whose cost grows every week that the archive deepens, with nothing
        // failing to mark the moment it stops being fast enough. Measured at 4.2 s against an 8 s
        // client timeout before the cache landed, with the archive three-quarters filled.
        Map<Long, List<CategoryDownside.Point>> allSeries = seriesCache.all();

        List<Map<String, Object>> rows = new ArrayList<>();
        int fitting = 0;
        for (SebiCategory c : SebiCategory.values()) {
            HorizonFit.Read fit = HorizonFit.forCategory(c, m);
            List<MutualFundSchemeEntity> avail = byCategory.getOrDefault(c, List.of());
            List<MutualFundSchemeEntity> all = allByCategory.getOrDefault(c, List.of());
            if (avail.isEmpty() && all.isEmpty()) {
                // No scheme of this category is on file at all, so there is nothing to say about it.
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", c.name());
            row.put("categoryLabel", c.label());
            row.put("assetClass", c.assetClass().name());
            row.put("fit", fit.fit().name());
            row.put("fitLabel", fit.fit().label());
            row.put("bandFromMonth", fit.bandFromMonth());
            row.put("bandToMonth", fit.bandToMonth());
            row.put("bandLabel", bandLabel(fit));
            row.put("basis", fit.basis());
            row.put("schemesAvailable", avail.size());
            row.put("ownedHere", (int) avail.stream()
                    .filter(s -> owned.contains(s.getAmfiSchemeCode())).count());

            CategoryDownside.Reading d = downside(c, all, m, allSeries);
            row.put("downsideMeasured", d.measured());
            row.put("worstPct", d.worstPct());
            row.put("medianPct", d.medianPct());
            row.put("shareNegativePct", d.shareNegativePct());
            row.put("windowsMeasured", d.windowsMeasured());
            row.put("independentWindows", d.independentWindows());
            row.put("windowsNegative", d.windowsNegative());
            row.put("worstFrom", d.worstFrom() == null ? null : d.worstFrom().toString());
            row.put("worstTo", d.worstTo() == null ? null : d.worstTo().toString());
            row.put("schemesVanished", d.schemesVanished());
            row.put("downsideReason", d.reason());
            row.put("downsideCaveat", d.caveat());

            if (fit.fit() == HorizonFit.Fit.FITS) {
                fitting++;
            }
            rows.add(row);
        }

        out.put("categories", rows);
        out.put("categoriesFitting", fitting);
        out.put("realities", HorizonFit.realities(m));
        out.put("headline", headline(m, fitting, rows));
        out.put("caveats", caveats(m));

        if (category != null) {
            out.put("expanded", expand(category, byCategory.getOrDefault(category, List.of()),
                    owned, m));
        }
        return out;
    }

    // ------------------------------------------------------------------ the per-category downside

    private CategoryDownside.Reading downside(SebiCategory c, List<MutualFundSchemeEntity> all,
                                              int windowMonths,
                                              Map<Long, List<CategoryDownside.Point>> allSeries) {
        if (all.isEmpty()) {
            return CategoryDownside.measure(c, windowMonths, Map.of());
        }
        Map<Long, List<CategoryDownside.Point>> mine = new LinkedHashMap<>();
        for (MutualFundSchemeEntity s : all) {
            List<CategoryDownside.Point> points = allSeries.get(s.getAmfiSchemeCode());
            if (points != null && !points.isEmpty()) {
                mine.put(s.getAmfiSchemeCode(), points);
            }
        }
        return CategoryDownside.measure(c, windowMonths, mine);
    }

    // ------------------------------------------------------------------ one category's schemes

    /**
     * Every scheme a person could buy today in one category, <b>sorted by name</b>.
     *
     * <p>The sort is load-bearing and is the same decision §52.11 made: the moment this sorts on a
     * number the reader takes row one, and §52.13 measured on this archive that past ranking
     * carried no information about future ranking. The one figure carried per scheme is its
     * <em>worst</em> window - a risk reading rather than a performance one - and it is published
     * with the floor-not-ceiling caveat the category carries.
     */
    private Map<String, Object> expand(SebiCategory c, List<MutualFundSchemeEntity> avail,
                                       Set<Long> owned, int windowMonths) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("category", c.name());
        out.put("categoryLabel", c.label());

        int minMonths = Math.max(24, windowMonths * MIN_SCHEME_MONTHS_MULTIPLE);
        out.put("minMonthsForOwnFigure", minMonths);

        // Sliced from the shared cache rather than queried again: these schemes are a subset of
        // what it already holds, and a second read would be the same rows twice.
        Map<Long, List<CategoryDownside.Point>> series =
                avail.isEmpty() ? Map.of() : seriesCache.all();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (MutualFundSchemeEntity s : avail) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("schemeCode", s.getAmfiSchemeCode());
            r.put("schemeName", s.getSchemeName());
            r.put("amcName", s.getAmcName());
            r.put("owned", owned.contains(s.getAmfiSchemeCode()));
            r.put("nav", s.getNav());
            r.put("navDate", s.getNavDate() == null ? null : s.getNavDate().toString());

            NavigableSeries ns = NavigableSeries.of(series.get(s.getAmfiSchemeCode()));
            r.put("monthsOnFile", ns.months());
            if (ns.months() < minMonths) {
                // Not measured, never a good reading. A scheme with a short record has not been
                // through anything yet, which is the opposite of a clean one.
                r.put("worstMeasured", false);
                r.put("worstPct", null);
                r.put("worstReason", ns.months() == 0
                        ? "no month-end history on file for this scheme"
                        : "only " + ns.months() + " months on file, and " + minMonths
                          + " are needed before its own worst stretch means anything");
            } else {
                Worst w = ns.worst(windowMonths);
                r.put("worstMeasured", w.pct() != null);
                r.put("worstPct", w.pct());
                r.put("worstFrom", w.from() == null ? null : w.from().toString());
                r.put("worstTo", w.to() == null ? null : w.to().toString());
                r.put("windowsMeasured", w.windows());
                r.put("worstReason", w.pct() == null
                        ? "no complete window of " + windowMonths + " months in this scheme's series"
                        : null);
            }
            rows.add(r);
        }
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get("schemeName"))));
        out.put("schemes", rows);
        out.put("schemeCount", rows.size());
        out.put("note", "Sorted by name, deliberately. There is no score here and no default sort "
                + "that could be read as one: §52.13 measured on this archive that a fund's past "
                + "ranking carried no information about its next ranking. The figure shown is the "
                + "worst stretch of " + windowMonths + " months in the scheme's own record, which "
                + "is a risk reading rather than a performance one - and it is a floor on how bad "
                + "it got, because a scheme that failed outright is not in this list at all.");
        return out;
    }

    /** A month-end series wrapped for the two questions asked of it here. */
    private record NavigableSeries(TreeMap<YearMonth, BigDecimal> byMonth) {

        static NavigableSeries of(List<CategoryDownside.Point> points) {
            TreeMap<YearMonth, BigDecimal> m = new TreeMap<>();
            if (points != null) {
                for (CategoryDownside.Point p : points) {
                    if (p != null && p.month() != null && p.nav() != null && p.nav().signum() > 0) {
                        m.put(p.month(), p.nav());
                    }
                }
            }
            return new NavigableSeries(m);
        }

        int months() {
            return byMonth.isEmpty() ? 0
                    : (int) byMonth.firstKey().until(byMonth.lastKey(),
                            java.time.temporal.ChronoUnit.MONTHS);
        }

        Worst worst(int windowMonths) {
            if (byMonth.isEmpty()) {
                return new Worst(null, null, null, 0);
            }
            Double worst = null;
            YearMonth wf = null;
            YearMonth wt = null;
            int windows = 0;
            YearMonth last = byMonth.lastKey();
            for (YearMonth from : byMonth.keySet()) {
                YearMonth to = from.plusMonths(windowMonths);
                if (to.isAfter(last)) {
                    break;
                }
                BigDecimal a = byMonth.get(from);
                BigDecimal b = byMonth.get(to);
                if (a == null || b == null || a.signum() <= 0) {
                    continue;
                }
                double pct = (b.doubleValue() / a.doubleValue() - 1.0) * 100.0;
                windows++;
                if (worst == null || pct < worst) {
                    worst = pct;
                    wf = from;
                    wt = to;
                }
            }
            return new Worst(worst, wf, wt, windows);
        }
    }

    private record Worst(Double pct, YearMonth from, YearMonth to, int windows) {}

    // ------------------------------------------------------------------ prose

    private static boolean isAvailableToday(MutualFundSchemeEntity s) {
        return s.getStructure() == SchemeStructure.OPEN_ENDED
                && s.getPlan() == SchemePlanKey.Plan.DIRECT
                && s.getNavOption() == SchemePlanKey.Option.GROWTH
                && s.getNavDate() != null
                && s.getNavDate().isAfter(java.time.LocalDate.now().minusDays(AVAILABLE_WITHIN_DAYS));
    }

    private static String bandLabel(HorizonFit.Read fit) {
        if (fit.bandFromMonth() == null) {
            return null;
        }
        int from = fit.bandFromMonth();
        int to = fit.bandToMonth() == null ? HorizonFit.MAX_HORIZON_MONTHS : fit.bandToMonth();
        if (to >= HorizonFit.MAX_HORIZON_MONTHS) {
            return monthsPhrase(from) + " or longer";
        }
        if (from == 0) {
            return "up to " + monthsPhrase(to);
        }
        return monthsPhrase(from) + " to " + monthsPhrase(to);
    }

    private static String monthsPhrase(int months) {
        if (months < 12) {
            return months + (months == 1 ? " month" : " months");
        }
        int years = months / 12;
        int rest = months % 12;
        String y = years + (years == 1 ? " year" : " years");
        return rest == 0 ? y : y + " " + rest + (rest == 1 ? " month" : " months");
    }

    static String horizonLabel(int months) {
        return monthsPhrase(months);
    }

    private static String headline(int months, int fitting, List<Map<String, Object>> rows) {
        if (fitting == 0) {
            return "No fund category on file is built for money you need in " + monthsPhrase(months)
                    + ". That is the finding rather than a gap: over a period this short the "
                    + "alternatives worth comparing are a bank account and a sweep deposit.";
        }
        long equity = rows.stream()
                .filter(r -> "FITS".equals(r.get("fit")))
                .filter(r -> "EQUITY".equals(r.get("assetClass")))
                .count();
        String tail = equity == 0
                ? " No equity category is among them, which is the point: over " + monthsPhrase(months)
                  + " an equity fund's return is the market's direction, and this app deleted a "
                  + "whole feature rather than forecast that."
                : " Equity categories appear because " + monthsPhrase(months) + " is long enough "
                  + "for the business results behind them to matter more than the mood.";
        return fitting + (fitting == 1 ? " category is" : " categories are")
                + " built for money you need in " + monthsPhrase(months) + "." + tail;
    }

    private static List<String> caveats(int months) {
        List<String> out = new ArrayList<>();
        out.add("What a category is built for comes from SEBI's own rules, printed beside each row "
                + "so you can check it. It is not a prediction and it does not go out of date the "
                + "way a performance ranking does.");
        out.add("What each category actually did is measured on this app's month-end archive, which "
                + "starts in April 2018. The worst figure is a floor on how bad it got, not a "
                + "ceiling on how bad it can get: a fund that failed outright was merged away and "
                + "left the record.");
        out.add("Returns here are over the whole window and are deliberately not annualised. "
                + "Multiplying a three-month fall by four states a loss nobody had.");
        out.add("Nothing here is a recommendation and no scheme is named as worth owning. Within a "
                + "category that fits, what is left to compare is cost and what the fund is allowed "
                + "to hold - and the expense ratio is not published anywhere this app can read.");
        if (months < 12) {
            out.add("The credit quality of what a debt fund holds is the thing that decides whether "
                    + "it is safe, and it is not in this feed at all. A fund's name and its category "
                    + "do not tell you what it lent to.");
        }
        return out;
    }
}
