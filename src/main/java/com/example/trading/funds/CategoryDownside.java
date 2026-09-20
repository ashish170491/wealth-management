package com.example.trading.funds;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * What the <b>worst</b> stretch in this app's archive looked like for a whole SEBI category, over
 * windows of a stated length (SPEC §52.16).
 *
 * <p><b>Why this exists.</b> {@link HorizonFit} says what a category is <em>built</em> for, which is
 * regulation and needs no forecast. This says what actually happened to it, which is the only thing
 * that can contradict the regulation - and on the two occasions in this archive when it did (the
 * credit events of late 2018 and April 2020) the funds in question were sold as safe. So the
 * horizon table is published with the measured downside beside it: <em>a liquid fund's worst
 * three-month stretch on file, a credit-risk fund's worst, an equity fund's worst</em>.
 *
 * <p><b>The median is asked window by window, never built into an index series</b> (Gotcha 143).
 * A category's membership changes constantly, so an index would need a base month and would
 * silently drop every scheme that did not exist then. Instead each window is priced for every
 * scheme holding both ends of it, and the category's reading for that window is the median across
 * those schemes.
 *
 * <p><b>Survivorship runs the flattering way here, and that is stated rather than corrected.</b>
 * A fund that blew up and was merged away cannot appear in a later window, and the funds that
 * vanish are overwhelmingly the ones that stopped working - so the worst figure below is a
 * <b>lower bound on how bad it got</b>, not an upper bound on how bad it can get. It is the one
 * direction a downside measure must never be read the other way round, which is why
 * {@link Reading#caveat()} is mandatory and why {@code schemesVanished} is counted.
 *
 * <p><b>Month-end granularity hides what happened inside a month.</b> This archive holds one point
 * per scheme per month, so a fall and a recovery inside four weeks is invisible - and a segregated
 * portfolio, which is what a defaulting debt fund produces, shows up as a NAV step that this class
 * reads as a loss without knowing it is one. Both are said in the caveat rather than adjusted for.
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public final class CategoryDownside {

    private CategoryDownside() {}

    /**
     * How many schemes must price a window before the window's median is used.
     *
     * <p>Five. Below it the "median" is one or two funds, and a category's worst stretch decided by
     * a single scheme is a fact about that scheme (Gotcha 147's rule: a band must be coarser than
     * the resolution of the data behind it, and a median over three funds moves in thirds).
     */
    public static final int MIN_SCHEMES_PER_WINDOW = 5;

    /**
     * How many priced windows a category needs before anything is published.
     *
     * <p>Twelve, which at a three-month window is three years of the archive. Below it the worst
     * reading is whichever month happened to be in the sample.
     */
    public static final int MIN_WINDOWS = 12;

    /** One scheme's month-end series point. Same shape as {@code RollingWindowRecord.Point}. */
    public record Point(YearMonth month, BigDecimal nav) {}

    /**
     * What a category did over windows of {@code windowMonths}.
     *
     * @param worstPct            the worst window's median return, as a percentage of the amount
     *                            put in - <b>not annualised</b>, because annualising a three-month
     *                            fall multiplies it by four and states a loss nobody had (B-047)
     * @param medianPct           the middle window's median return, for scale
     * @param windowsNegative     how many windows the median finished below where it started
     * @param windowsMeasured     how many windows were priced at all
     * @param independentWindows  how many non-overlapping windows the history holds - <b>this</b>
     *                            is the sample size, never {@code windowsMeasured} (Gotcha 92)
     * @param schemesVanished     schemes that priced an early window and stopped, which is why the
     *                            worst figure is a lower bound on the badness
     */
    public record Reading(
            SebiCategory category,
            String categoryLabel,
            int windowMonths,
            boolean measured,
            Double worstPct,
            Double medianPct,
            YearMonth worstFrom,
            YearMonth worstTo,
            int windowsNegative,
            int windowsMeasured,
            int independentWindows,
            int schemesSeen,
            int schemesVanished,
            String reason,
            String caveat) {

        /** The share of windows that finished below where they started, or null when unmeasured. */
        public Double shareNegativePct() {
            return measured && windowsMeasured > 0
                    ? (windowsNegative * 100.0) / windowsMeasured : null;
        }
    }

    /**
     * Measure one category.
     *
     * @param series every scheme in the category, keyed by scheme code, each a month-end series
     */
    public static Reading measure(SebiCategory category, int windowMonths,
                                  Map<Long, List<Point>> series) {
        return measure(category, category == null ? "Not classified" : category.label(),
                windowMonths, series);
    }

    /**
     * The same measurement over a group that is not one SEBI category - every actively managed
     * equity scheme pooled, or every debt scheme pooled.
     *
     * <p>Used by the goal planner, which needs "what did equity do over a stretch this long" rather
     * than "what did mid caps do". Pooling is the honest construction for that question: the median
     * across every equity fund is what a typical equity fund did, and picking one category to stand
     * for all of them would answer a narrower question under a wider name.
     */
    public static Reading measureGroup(String label, int windowMonths,
                                       Map<Long, List<Point>> series) {
        return measure(null, label, windowMonths, series);
    }

    private static Reading measure(SebiCategory category, String label, int windowMonths,
                                   Map<Long, List<Point>> series) {
        if (windowMonths < 1) {
            return unmeasured(category, label, windowMonths, 0, 0,
                    "a window shorter than one month is not a window");
        }
        if (series == null || series.isEmpty()) {
            return unmeasured(category, label, windowMonths, 0, 0,
                    "no scheme in this category has a month-end series on file yet");
        }

        Map<Long, NavigableMap<YearMonth, BigDecimal>> byScheme = new java.util.LinkedHashMap<>();
        YearMonth first = null;
        YearMonth last = null;
        for (Map.Entry<Long, List<Point>> e : series.entrySet()) {
            NavigableMap<YearMonth, BigDecimal> m = new TreeMap<>();
            for (Point p : e.getValue()) {
                if (p != null && p.month() != null && p.nav() != null
                        && p.nav().signum() > 0) {
                    m.put(p.month(), p.nav());
                }
            }
            if (m.isEmpty()) {
                continue;
            }
            byScheme.put(e.getKey(), m);
            if (first == null || m.firstKey().isBefore(first)) first = m.firstKey();
            if (last == null || m.lastKey().isAfter(last)) last = m.lastKey();
        }
        if (first == null || byScheme.isEmpty()) {
            return unmeasured(category, label, windowMonths, 0, 0,
                    "no usable net asset values in this category's series");
        }

        int monthsOnFile = (int) first.until(last, java.time.temporal.ChronoUnit.MONTHS);
        List<Double> windowMedians = new ArrayList<>();
        double worst = Double.MAX_VALUE;
        YearMonth worstFrom = null;
        YearMonth worstTo = null;
        int negative = 0;

        for (YearMonth from = first; !from.plusMonths(windowMonths).isAfter(last);
             from = from.plusMonths(1)) {
            YearMonth to = from.plusMonths(windowMonths);
            List<Double> returns = new ArrayList<>();
            for (NavigableMap<YearMonth, BigDecimal> m : byScheme.values()) {
                BigDecimal a = m.get(from);
                BigDecimal b = m.get(to);
                if (a == null || b == null || a.signum() <= 0) {
                    continue;
                }
                returns.add((b.doubleValue() / a.doubleValue() - 1.0) * 100.0);
            }
            if (returns.size() < MIN_SCHEMES_PER_WINDOW) {
                continue;
            }
            double med = median(returns);
            windowMedians.add(med);
            if (med < 0) {
                negative++;
            }
            if (med < worst) {
                worst = med;
                worstFrom = from;
                worstTo = to;
            }
        }

        // A scheme that priced a window early on and has stopped pricing anything. It is why the
        // worst reading below is a floor rather than a ceiling.
        final YearMonth end = last;
        int vanished = (int) byScheme.values().stream()
                .filter(m -> m.lastKey().isBefore(end.minusMonths(3)))
                .count();

        if (windowMedians.size() < MIN_WINDOWS) {
            return unmeasured(category, label, windowMonths, byScheme.size(), vanished,
                    "only " + windowMedians.size() + " windows of " + windowMonths + " months can be "
                            + "priced across at least " + MIN_SCHEMES_PER_WINDOW + " schemes, and "
                            + MIN_WINDOWS + " are needed - a gap in this app's archive, not a "
                            + "statement about the category");
        }

        int independent = Math.max(1, monthsOnFile / windowMonths);
        return new Reading(category, label, windowMonths, true,
                worst, median(windowMedians), worstFrom, worstTo,
                negative, windowMedians.size(), independent,
                byScheme.size(), vanished,
                null, caveatFor(windowMonths, independent, vanished));
    }

    /**
     * The worst window for a <b>blend</b> of equity and debt, walked as a real path.
     *
     * <p><b>Why this is not the two worst figures put together.</b> The first version of the goal
     * planner took the worst equity window and the worst debt window and mixed them by weight. That
     * is arithmetic on two numbers that describe different months, so it describes a portfolio
     * nobody held - and it failed in both directions at once on the first live run. An all-debt mix
     * came out at exactly {@code 0.0%} for every horizon, a constant on screen where a measurement
     * should be (B-060's signature); and because the debt leg had to be floored at zero to stop a
     * good bond year flattering a bad equity one, the floor was doing the work rather than the data.
     *
     * <p>This walks each window once, takes the median equity return and the median debt return
     * <b>over those same months</b>, blends them at the stated weight, and reports the worst of
     * those. That is a path the money could actually have taken.
     *
     * <p><b>The worst window can be a gain, and when it is that is a finding about the archive
     * rather than about the mix.</b> This record starts in April 2018 and holds one crash that
     * recovered inside a year, so no window longer than about two years finishes below where it
     * started. Saying "the worst case is +14%" without saying why would be the most flattering
     * failure this module could produce, so {@link Reading#worstPct()} is published with the window
     * count and the caller is expected to say it in words.
     */
    public static Reading measureBlend(String label, int windowMonths, double equityPct,
                                       Map<Long, List<Point>> equitySeries,
                                       Map<Long, List<Point>> debtSeries) {
        if (windowMonths < 1) {
            return unmeasured(null, label, windowMonths, 0, 0,
                    "a window shorter than one month is not a window");
        }
        Map<Long, NavigableMap<YearMonth, BigDecimal>> equity = index(equitySeries);
        Map<Long, NavigableMap<YearMonth, BigDecimal>> debt = index(debtSeries);
        double eq = Math.max(0.0, Math.min(100.0, equityPct)) / 100.0;
        double dt = 1.0 - eq;

        // Only the legs that carry weight need to answer. An all-debt goal must not be refused
        // because the equity archive is thin, and the reverse holds too.
        if ((eq > 0 && equity.isEmpty()) || (dt > 0 && debt.isEmpty())) {
            return unmeasured(null, label, windowMonths, equity.size() + debt.size(), 0,
                    "there is no month-end series on file for one side of this split yet");
        }

        YearMonth first = null;
        YearMonth last = null;
        for (NavigableMap<YearMonth, BigDecimal> m : concat(equity, debt)) {
            if (first == null || m.firstKey().isBefore(first)) first = m.firstKey();
            if (last == null || m.lastKey().isAfter(last)) last = m.lastKey();
        }
        if (first == null) {
            return unmeasured(null, label, windowMonths, 0, 0,
                    "no usable net asset values on file yet");
        }

        List<Double> blended = new ArrayList<>();
        double worst = Double.MAX_VALUE;
        YearMonth worstFrom = null;
        YearMonth worstTo = null;
        int negative = 0;

        for (YearMonth from = first; !from.plusMonths(windowMonths).isAfter(last);
             from = from.plusMonths(1)) {
            YearMonth to = from.plusMonths(windowMonths);
            Double e = eq > 0 ? medianOver(equity, from, to) : Double.valueOf(0.0);
            Double d = dt > 0 ? medianOver(debt, from, to) : Double.valueOf(0.0);
            if (e == null || d == null) {
                continue;
            }
            double v = eq * e + dt * d;
            blended.add(v);
            if (v < 0) {
                negative++;
            }
            if (v < worst) {
                worst = v;
                worstFrom = from;
                worstTo = to;
            }
        }

        final YearMonth end = last;
        int vanished = (int) concat(equity, debt).stream()
                .filter(m -> m.lastKey().isBefore(end.minusMonths(3)))
                .count();

        if (blended.size() < MIN_WINDOWS) {
            return unmeasured(null, label, windowMonths, equity.size() + debt.size(), vanished,
                    "only " + blended.size() + " windows of " + windowMonths + " months can be "
                            + "priced across both sides, and " + MIN_WINDOWS + " are needed - a gap "
                            + "in this app's archive, not a statement about the split");
        }
        int monthsOnFile = (int) first.until(last, java.time.temporal.ChronoUnit.MONTHS);
        int independent = Math.max(1, monthsOnFile / windowMonths);
        return new Reading(null, label, windowMonths, true,
                worst, median(blended), worstFrom, worstTo,
                negative, blended.size(), independent,
                equity.size() + debt.size(), vanished,
                null, caveatFor(windowMonths, independent, vanished));
    }

    private static List<NavigableMap<YearMonth, BigDecimal>> concat(
            Map<Long, NavigableMap<YearMonth, BigDecimal>> a,
            Map<Long, NavigableMap<YearMonth, BigDecimal>> b) {
        List<NavigableMap<YearMonth, BigDecimal>> out = new ArrayList<>(a.values());
        out.addAll(b.values());
        return out;
    }

    /** The median return across every scheme holding both ends of this window, or null. */
    private static Double medianOver(Map<Long, NavigableMap<YearMonth, BigDecimal>> byScheme,
                                     YearMonth from, YearMonth to) {
        List<Double> returns = new ArrayList<>();
        for (NavigableMap<YearMonth, BigDecimal> m : byScheme.values()) {
            BigDecimal a = m.get(from);
            BigDecimal b = m.get(to);
            if (a == null || b == null || a.signum() <= 0) {
                continue;
            }
            returns.add((b.doubleValue() / a.doubleValue() - 1.0) * 100.0);
        }
        return returns.size() < MIN_SCHEMES_PER_WINDOW ? null : median(returns);
    }

    private static Map<Long, NavigableMap<YearMonth, BigDecimal>> index(
            Map<Long, List<Point>> series) {
        Map<Long, NavigableMap<YearMonth, BigDecimal>> out = new java.util.LinkedHashMap<>();
        if (series == null) {
            return out;
        }
        for (Map.Entry<Long, List<Point>> e : series.entrySet()) {
            NavigableMap<YearMonth, BigDecimal> m = new TreeMap<>();
            for (Point p : e.getValue()) {
                if (p != null && p.month() != null && p.nav() != null && p.nav().signum() > 0) {
                    m.put(p.month(), p.nav());
                }
            }
            if (!m.isEmpty()) {
                out.put(e.getKey(), m);
            }
        }
        return out;
    }

    private static Reading unmeasured(SebiCategory c, String label, int windowMonths,
                                      int schemes, int vanished, String reason) {
        return new Reading(c, label, windowMonths, false, null, null, null, null,
                0, 0, 0, schemes, vanished, reason, null);
    }

    private static String caveatFor(int windowMonths, int independent, int vanished) {
        StringBuilder sb = new StringBuilder();
        sb.append("The worst figure is a floor on how bad it got, not a ceiling on how bad it can "
                + "get: a fund that failed and was merged away leaves the record entirely");
        if (vanished > 0) {
            sb.append(" - ").append(vanished).append(vanished == 1
                    ? " scheme in this category stopped publishing during the period"
                    : " schemes in this category stopped publishing during the period");
        }
        sb.append(". The archive holds one value per month, so a fall and a recovery inside four "
                + "weeks does not appear at all. And the windows overlap: there are only ")
                .append(independent).append(independent == 1
                        ? " independent stretch of this length on file, so this describes what "
                          + "happened rather than what the range of outcomes is"
                        : " independent stretches of this length on file, so this describes what "
                          + "happened rather than what the range of outcomes is");
        sb.append('.');
        return sb.toString();
    }

    static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        java.util.Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }
}
