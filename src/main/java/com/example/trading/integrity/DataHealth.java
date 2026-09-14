package com.example.trading.integrity;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The rule table behind the data-health screen (SPEC 44). Pure: no repository, no clock of
 * its own, no I/O. Everything here is a function of values the caller has already read.
 *
 * <h2>What this exists to catch</h2>
 * <p>The dominant defect in this codebase is not a wrong number. It is <b>a value that was
 * never measured being rendered as though it were</b>, three times over: Institutional
 * Interest constant at 40 for three months (bug #9), monthly RSI constant at 50.0 across the
 * whole universe (B-060), and Insider Pulse producing a verdict for no stock at all (B-074).
 * None of the three was found by looking at a screen - every one of them looked entirely
 * plausible. They were found by asking two questions this class asks mechanically: <i>how
 * many stocks was it measured on</i>, and <i>did the answer vary</i>.
 *
 * <h2>Three rules that are easy to get wrong here</h2>
 * <ol>
 *   <li><b>A run this app cannot distinguish from a market holiday is never a PROBLEM.</b>
 *       There is no holiday calendar in the JVM, so one missing session and a closed
 *       exchange are the same observation. One session behind is a {@link Severity#WATCH}
 *       that says so; two is a problem, because the market is not shut for two sessions
 *       often and the reader can check that in a second.</li>
 *   <li><b>A known cause has an expiry date.</b> {@link #KNOWN_ZERO} carries the bug id
 *       <i>and</i> the date its fix shipped. A screening run later than the fix that still
 *       reads zero is escalated to a problem rather than excused - otherwise the exception
 *       list quietly becomes the thing that hides the next regression.</li>
 *   <li><b>Every check reports, including the ones that pass.</b> An empty findings list
 *       would read as "nothing is wrong" when it can equally mean "nothing was checked" -
 *       the exact confusion Gotcha 44 exists to prevent. The screen shows its own
 *       denominator.</li>
 * </ol>
 */
public final class DataHealth {

    /**
     * How late a job may be before its table counts as behind. The crons fire on the minute
     * but a Kite-heavy job can take many minutes to write its first row, and a false alarm on
     * the channel that exists to raise alarms is how a real one gets ignored.
     */
    static final int GRACE_MINUTES = 90;

    /** Below this, a signal is measured on too little of the universe to rank anything. */
    static final double LOW_COVERAGE_PERCENT = 80.0;

    /** Above this, a signal is measured widely enough that zero spread is a real finding. */
    static final double WIDE_COVERAGE_PERCENT = 90.0;

    /** Depth at which the turnaround detector and the B-066 bonus discriminator can run. */
    static final int FORENSIC_READY_YEARS = 4;

    /** Days of insider-feed silence worth a look. Beyond any holiday cluster (B-089). */
    static final int PIT_FEED_WATCH_DAYS = 7;

    /** Days of insider-feed silence that mean the feed has stopped, not the insiders. */
    static final int PIT_FEED_PROBLEM_DAYS = 21;

    /** Weekdays without a backfill attempt before the batch job counts as stalled. */
    static final int BACKFILL_STALL_DAYS = 4;

    public enum Severity {
        /** Something is wrong now and the reader should act. */
        PROBLEM,
        /** Worth knowing. May be a holiday, a thin sample, or a gap that is filling itself. */
        WATCH,
        /** Anomalous-looking, cause understood and recorded in BUGS.md. */
        KNOWN,
        /** Checked and healthy. Reported so the reader can see what was checked. */
        OK
    }

    /**
     * One check's result.
     *
     * @param figure the number the verdict was reached on, always shown beside it - a reader
     *               who can see "0 of 288 stocks" can disagree with the verdict, which a
     *               reader shown only a red badge cannot
     */
    public record Finding(String check,
                          String subject,
                          Severity severity,
                          String headline,
                          String detail,
                          String figure) {
    }

    /**
     * What writes one freshness key, and when.
     *
     * @param sparse a table that legitimately has nothing to write on some days. Outcomes are
     *               only recorded when a pick reaches a horizon anniversary, so an old newest
     *               row is normal there and must never be escalated past a WATCH.
     */
    public record ScheduleSpec(String key,
                               String label,
                               String job,
                               int hour,
                               int minute,
                               boolean saturday,
                               boolean sparse,
                               String cronProperty) {

        /** A job whose cron is hard-coded in its {@code @Scheduled} annotation. */
        static ScheduleSpec fixed(String key, String label, String job,
                                  int hour, int minute, boolean saturday, boolean sparse) {
            return new ScheduleSpec(key, label, job, hour, minute, saturday, sparse, null);
        }
    }

    /** A zero reading with an understood cause. {@code fixedOn} null means still unresolved. */
    private record Known(String bugId, LocalDate fixedOn, String note) {
    }

    /**
     * The freshness keys served by {@code /api/dashboard/health}, each against the job that
     * writes it. Times are the cron, taken from the {@code @Scheduled} methods themselves
     * rather than from the summary in CLAUDE.md - the summary has drifted before.
     */
    public static final Map<String, ScheduleSpec> SCHEDULE = schedule();

    private static Map<String, ScheduleSpec> schedule() {
        Map<String, ScheduleSpec> m = new LinkedHashMap<>();
        m.put("holdingsSynced", new ScheduleSpec("holdingsSynced", "Holding prices",
                "HoldingsScheduler.syncHoldingsFromBroker", 9, 20, false, false,
                "holdings.scheduler.sync-cron"));
        m.put("holdingsAnalyzed", new ScheduleSpec("holdingsAnalyzed", "Holdings analysis",
                "HoldingsScheduler.analyzeHoldings", 15, 15, false, false,
                "holdings.scheduler.analysis-cron"));
        m.put("holdingsHistory", new ScheduleSpec("holdingsHistory", "Holdings history",
                "HoldingsScheduler.analyzeHoldings", 15, 15, false, false,
                "holdings.scheduler.analysis-cron"));
        m.put("holdingClassification", new ScheduleSpec("holdingClassification", "Core tiers",
                "CoreClassificationService (end of analyzeHoldings)", 15, 15, false, false,
                "holdings.scheduler.analysis-cron"));
        m.put("multibaggerScores", ScheduleSpec.fixed("multibaggerScores", "Screening scores",
                "MultibaggerScheduler (14:00 weekdays, 08:00 Saturday)", 14, 0, true, false));
        m.put("watchlistAnalyzed", new ScheduleSpec("watchlistAnalyzed", "Watchlist analysis",
                "WatchlistScheduler.analyzeAndReportWatchlist", 15, 0, false, false,
                "watchlist.scheduler.analysis-cron"));
        m.put("watchlistSnapshot", new ScheduleSpec("watchlistSnapshot", "Watchlist history",
                "WatchlistScheduler (last fire of the day)", 15, 0, false, false,
                "watchlist.scheduler.analysis-cron"));
        m.put("recommendationOutcomes", ScheduleSpec.fixed("recommendationOutcomes",
                "Pick outcomes", "RecommendationOutcomeScheduler", 15, 22, false, true));
        m.put("ipoIssues", ScheduleSpec.fixed("ipoIssues", "IPO pipeline",
                "IpoCaptureScheduler.captureDaily", 12, 15, false, false));
        // The headline scan runs every 15 minutes, so its LAST fire of the day is 15:15. Hard-coded
        // on purpose: resolve() reads a cron's hour and minute fields, and it cannot make sense of
        // the "*/15" that this one uses - it would silently leave the spec at whatever was passed
        // in. Naming the real last fire here is the honest version of that limitation.
        m.put("marketImpactNews", ScheduleSpec.fixed("marketImpactNews", "News headlines",
                "MarketImpactNewsService.scheduledNewsCheck (every 15 min)", 15, 15, false, true));
        // Stamped when the daily pass last MEASURED a target, not when it last recorded one.
        // Brokerages do not publish every day, so a capture stamp would go amber on an ordinary
        // quiet week and train the eye past the colour on the keys where it means something. The
        // measurement pass runs every weekday whatever the news did (SPEC 49.6).
        m.put("analystTargets", ScheduleSpec.fixed("analystTargets", "Analyst targets",
                "AnalystTargetScheduler.captureAndMeasure", 13, 20, false, false));
        return Map.copyOf(m);
    }

    /**
     * A table written only when the investor asks for it (SPEC §48.9).
     *
     * <p>{@link ScheduleSpec} cannot describe this, and forcing it to would produce a lie: every
     * freshness rule here is built on "a job was due by now and the newest row is older than that",
     * and no job is ever due for these. An on-demand table can be empty, or old, and neither is a
     * fault - so these never reach PROBLEM. What they can report is the honest fact that nothing has
     * been asked for in a while, which is worth a WATCH because every reading downstream is then
     * running on an empty window while looking exactly the same on screen.
     *
     * @param quietAfterDays how long since the last run before saying so
     */
    public record OnDemandSpec(String key, String label, String trigger, int quietAfterDays) {
    }

    public static final Map<String, OnDemandSpec> ON_DEMAND = Map.of(
            "macroEvents", new OnDemandSpec("macroEvents", "Macro events",
                    "the \"Read the news feeds now\" button on the Events page", 14));

    /**
     * Freshness for a table that has no schedule. Never PROBLEM - see {@link OnDemandSpec}.
     */
    public static Finding onDemandFreshness(OnDemandSpec spec, LocalDate newest, ZonedDateTime now) {
        if (newest == null) {
            return new Finding("freshness", spec.label(), Severity.WATCH,
                    "Never run - on demand only",
                    "This table is written only by " + spec.trigger() + ". Nothing is wrong; nothing "
                    + "has been asked for yet. Until it is, every screen that reads it will honestly "
                    + "report that no event has been recorded.",
                    "no rows");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(newest, now.toLocalDate());
        if (days > spec.quietAfterDays()) {
            return new Finding("freshness", spec.label(), Severity.WATCH,
                    "Nothing read for " + days + " days",
                    "This table has no schedule, so it cannot be late - but nothing has been read "
                    + "since " + newest + ". Every reading built on it is running on an empty window, "
                    + "which looks the same on screen as a genuinely quiet fortnight. Use "
                    + spec.trigger() + ".",
                    "last run " + newest);
        }
        return new Finding("freshness", spec.label(), Severity.OK,
                "On demand; last run " + newest,
                "This table has no schedule, so it cannot be late. It moves only when you use "
                + spec.trigger() + ".",
                "last run " + newest);
    }

    /**
     * Apply the cron this job is actually configured with (B-087).
     *
     * <p>The first version of this table copied the times out of the {@code @Scheduled}
     * annotations. That was wrong in a way worth remembering: several of those annotations read
     * {@code @Scheduled(cron = "${holdings.scheduler.analysis-cron:0 30 10 * * MON-FRI}")}, and
     * the literal in the source is the <b>fallback</b>, not the schedule. application.yml sets
     * 15:15, so the screen reported holdings analysis, holdings history and core tiers as a
     * session behind every morning - three false alarms on the one channel whose whole job is to
     * be believed. A schedule must be read from the configuration that decides it.
     *
     * <p>An unparseable or absent cron leaves the spec untouched rather than guessing, and a
     * multi-hour field resolves to its <b>last</b> fire, which is when the table is final.
     */
    public static ScheduleSpec resolve(ScheduleSpec base, String cron) {
        if (base == null || cron == null || cron.isBlank()) return base;
        String[] f = cron.trim().split("\\s+");
        if (f.length < 6) return base;

        Integer minute = lastValue(f[1]);
        Integer hour = lastValue(f[2]);
        if (minute == null || hour == null) return base;

        boolean saturday = f[5].toUpperCase().contains("SAT");
        return new ScheduleSpec(base.key(), base.label(), base.job(), hour, minute, saturday,
                base.sparse(), base.cronProperty());
    }

    /** Largest value in a cron field: {@code 15} -> 15, {@code 11,13,15} -> 15, {@code 9-15} -> 15. */
    private static Integer lastValue(String field) {
        if (field == null || field.isBlank() || field.contains("*") || field.contains("/")) {
            return null;   // cannot name a single time; keep what the caller had
        }
        int best = -1;
        for (String part : field.split(",")) {
            String piece = part.contains("-") ? part.substring(part.indexOf('-') + 1) : part;
            try {
                best = Math.max(best, Integer.parseInt(piece.trim()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return best < 0 ? null : best;
    }

    /**
     * Signals whose zero or thin coverage has a recorded cause. Deliberately short: every
     * entry cites a bug id, and every entry that has been fixed carries the date, so this
     * table cannot grow into a place where regressions go to be forgiven.
     */
    private static final Map<String, Known> KNOWN_ZERO = Map.of(
            "MonthlyRsi", new Known("B-060", null,
                    "RSI over monthly bars needs 15 bars - about 330 trading days - and the price "
                    + "window is 365 calendar days, roughly 12 bars. It returns nothing rather "
                    + "than the fake neutral 50.0 it used to return for every stock. Zero here is "
                    + "the fix working; it stays zero until the fetch window widens or the signal "
                    + "is retired."),
            "InsiderPulseScore", new Known("B-089", LocalDate.of(2026, 9, 8),
                    "NSE stopped publishing to the old per-symbol PIT endpoint on 2026-05-01 "
                    + "when Reg 7(2) filings moved to the integrated filing system, so there "
                    + "was nothing inside the 90-day window to score. Ingestion was repointed "
                    + "at the PIT V2.0 all-market filing index. A screening run after this "
                    + "date that still reads zero means the new feed is not landing either - "
                    + "read the insider-feed check above, which watches the source directly."),
            "InsiderPulseVerdict", new Known("B-089", LocalDate.of(2026, 9, 8),
                    "Same cause as InsiderPulseScore - the feed, not the scoring."),
            "TurnaroundVerdict", new Known("SPEC 32.6", null,
                    "Needs four years of annual accounts, which most of the universe does not "
                    + "have yet. The weekday backfill is filling it; watch the history-depth "
                    + "check below rather than this one."));

    private DataHealth() {
    }

    // --------------------------------------------------------------------- freshness

    static boolean isRunDay(ScheduleSpec spec, LocalDate day) {
        DayOfWeek d = day.getDayOfWeek();
        if (d == DayOfWeek.SATURDAY) return spec.saturday();
        return d != DayOfWeek.SUNDAY;
    }

    /**
     * The most recent date on which this job should already have finished.
     *
     * <p>This is the calculation the reader gets wrong by hand, and so did I: on a Sunday the
     * screening scores are correctly from Saturday, and on a Monday morning they are correctly
     * from Saturday too. Encoding it removes the judgement call from reading the strip.
     */
    static LocalDate lastExpectedRun(ScheduleSpec spec, ZonedDateTime now) {
        LocalTime cutoff = LocalTime.of(spec.hour(), spec.minute()).plusMinutes(GRACE_MINUTES);
        LocalDate day = now.toLocalDate();
        if (isRunDay(spec, day) && now.toLocalTime().isBefore(cutoff)) {
            day = day.minusDays(1);
        }
        for (int i = 0; i < 14; i++) {
            if (isRunDay(spec, day)) return day;
            day = day.minusDays(1);
        }
        return null;
    }

    /** How many expected runs have come and gone since this table was last written. */
    static int sessionsBehind(ScheduleSpec spec, LocalDate newest, LocalDate lastExpected) {
        if (lastExpected == null || newest == null) return 0;
        int behind = 0;
        LocalDate day = lastExpected;
        while (day.isAfter(newest) && behind < 40) {
            if (isRunDay(spec, day)) behind++;
            day = day.minusDays(1);
        }
        return behind;
    }

    public static Finding freshness(ScheduleSpec spec, LocalDate newest, ZonedDateTime now) {
        LocalDate expected = lastExpectedRun(spec, now);

        if (newest == null) {
            return new Finding("freshness", spec.label(), Severity.WATCH,
                    "Nothing recorded yet",
                    "This table has no rows at all. On a fresh database that is expected and it "
                    + "fills in on the next run of " + spec.job() + ". If the app has been "
                    + "running for a while, that job is not writing.",
                    "no rows");
        }

        int behind = sessionsBehind(spec, newest, expected);
        String figure = behind == 0
                ? "up to date (" + newest + ")"
                : behind + " session" + (behind == 1 ? "" : "s") + " behind (newest " + newest + ")";

        if (behind == 0) {
            return new Finding("freshness", spec.label(), Severity.OK,
                    "Up to date",
                    "The newest row is from " + newest + ", which is the last run "
                    + spec.job() + " was due to complete.",
                    figure);
        }

        if (behind == 1) {
            return new Finding("freshness", spec.label(), Severity.WATCH,
                    "One session behind",
                    "The newest row is from " + newest + " and one more run was due by now. "
                    + "This app has no market-holiday calendar, so a closed exchange and a "
                    + "missed job look identical here. Check whether the market was open; if it "
                    + "was, look for " + spec.job() + " in logs/trading-app.log.",
                    figure);
        }

        if (spec.sparse()) {
            return new Finding("freshness", spec.label(), Severity.WATCH,
                    "Nothing written for " + behind + " sessions",
                    "This table is only written when there is something due - an outcome is "
                    + "recorded when a pick reaches its 30, 90, 180 or 365-day anniversary, and "
                    + "on many days none does. A gap here is usually normal, which is exactly "
                    + "why it can never be relied on as a liveness check.",
                    figure);
        }

        return new Finding("freshness", spec.label(), Severity.PROBLEM,
                "Stale by " + behind + " sessions",
                "The newest row is from " + newest + ". The market is rarely shut for "
                + behind + " sessions running, so " + spec.job() + " has most likely not been "
                + "completing. Search logs/trading-app.log for it, and check the app was "
                + "running during its window.",
                figure);
    }

    // --------------------------------------------------------------------- coverage

    /**
     * One signal from the latest {@code screening_coverage} run.
     *
     * @param coveragePercent measured over (attempted - not-applicable); null when every stock
     *                        was not-applicable, which is nothing rather than 0%
     * @param collapsed       true when a 0-100 signal's spread is implausibly narrow; null both
     *                        when the spread could not be computed and for signals that are not
     *                        on that scale - never false in either case
     */
    public static Finding coverage(String signal,
                                   int measured,
                                   int attempted,
                                   int notApplicable,
                                   Double coveragePercent,
                                   Double stdDev,
                                   Boolean collapsed,
                                   LocalDate screeningDate) {

        int denominator = Math.max(0, attempted - notApplicable);
        String of = measured + " of " + denominator + " stocks";

        if (denominator == 0) {
            return new Finding("coverage", signal, Severity.OK,
                    "Does not apply to any stock screened",
                    "Every stock in this run was not-applicable for this signal, so there is no "
                    + "denominator to take a percentage over. That is a finding about the "
                    + "universe, not a gap in the data.",
                    "0 applicable");
        }

        if (measured == 0) {
            Known known = KNOWN_ZERO.get(signal);
            if (known != null && (known.fixedOn() == null || screeningDate == null
                    || !screeningDate.isAfter(known.fixedOn()))) {
                return new Finding("coverage", signal, Severity.KNOWN,
                        "Measured on no stock - cause known (" + known.bugId() + ")",
                        known.note(),
                        of);
            }
            if (known != null) {
                return new Finding("coverage", signal, Severity.PROBLEM,
                        "Still zero after its fix shipped (" + known.bugId() + ")",
                        "This was fixed on " + known.fixedOn() + " and this screening run is "
                        + "later, so the fix did not take. Its Information Coefficient will read "
                        + "as \"no signal\" indefinitely while nothing throws and nothing logs an "
                        + "error - reopen " + known.bugId() + ".",
                        of);
            }
            return new Finding("coverage", signal, Severity.PROBLEM,
                    "Measured on no stock at all",
                    "This signal produced a value for nothing in the universe, so anything "
                    + "computed from it - its accuracy, its contribution to a score - is "
                    + "meaningless. This exact shape has been a real bug three times "
                    + "(bug #9, B-060, B-074) and each time it ran undetected for months, "
                    + "because nothing throws and every component behaves as specified.",
                    of);
        }

        if (Boolean.TRUE.equals(collapsed)) {
            return new Finding("coverage", signal, Severity.PROBLEM,
                    "Almost the same value on every stock",
                    "A score out of 100 that barely varies cannot rank anything, so it is "
                    + "contributing weight without contributing information. Either its input "
                    + "is broken or the signal genuinely does not separate this universe - both "
                    + "are worth knowing, and neither is visible on any other screen.",
                    of + ", spread " + fmt(stdDev));
        }

        boolean wide = coveragePercent != null && coveragePercent >= WIDE_COVERAGE_PERCENT;
        if (wide && stdDev != null && stdDev == 0.0) {
            return new Finding("coverage", signal, Severity.WATCH,
                    "Identical on every stock measured",
                    "Measured almost everywhere and the answer never varies. That may be "
                    + "genuine - some counts really are zero across a large-cap universe - but "
                    + "it carries no information today, and a signal stuck on one value is how "
                    + "all three historical bugs of this kind looked. Nothing flags it "
                    + "automatically because the collapse threshold only applies to 0-100 "
                    + "scores, and this is not one.",
                    of + ", spread 0");
        }

        if (coveragePercent != null && coveragePercent < LOW_COVERAGE_PERCENT) {
            // A recorded cause applies to thin coverage as well as to none. Without this the
            // KNOWN_ZERO entry for a signal that is merely sparse could never fire, and a
            // rule that cannot fire is worse than no rule - it reads as a check that ran.
            Known known = KNOWN_ZERO.get(signal);
            String why = known == null ? "" : " " + known.note();
            return new Finding("coverage", signal, Severity.WATCH,
                    "Measured on part of the universe"
                    + (known == null ? "" : " (" + known.bugId() + ")"),
                    "Read this signal's accuracy against this number, not on its own. A weak "
                    + "reading and a reading taken on a third of the market look the same in an "
                    + "accuracy table, which is the entire reason this coverage row exists."
                    + why,
                    of + " (" + fmt(coveragePercent) + "%)");
        }

        return new Finding("coverage", signal, Severity.OK,
                "Measured across the universe",
                "Measured on " + of + ", with values that vary across stocks.",
                of + (coveragePercent == null ? "" : " (" + fmt(coveragePercent) + "%)"));
    }

    // --------------------------------------------------------------------- history depth

    /**
     * Is the insider feed itself still delivering? (B-089)
     *
     * <p>The check that did not exist when it was needed. NSE's {@code corporates-pit} feed
     * stopped publishing on 2026-05-01 and nothing noticed for four months: the capture job
     * ran daily and succeeded, the endpoint returned HTTP 200, the pulse correctly returned a
     * null verdict for every stock, and the coverage row read 0% - which was attributed to a
     * capture bug (B-074) because that was the known cause of a zero here.
     *
     * <p>It is deliberately keyed on the newest <b>PIT</b> row and not on the table's newest
     * row of any kind. Bulk and block deals kept arriving daily throughout the outage, so a
     * table-level freshness check would have read "up to date" every single day of it. A
     * tripwire that watches the aggregate cannot see one contributing feed die.
     *
     * <p>Thresholds are set well beyond any holiday cluster, because a stopped feed and a
     * quiet week must not be confused (the same discipline as the one-session rule above):
     * insiders in a 290-stock universe file most weeks, so {@value #PIT_FEED_WATCH_DAYS} days
     * of silence is worth a look and {@value #PIT_FEED_PROBLEM_DAYS} is a broken feed. On the
     * real outage this would have escalated in the last week of May.
     *
     * @param newestPitTransaction newest transaction date among PIT-sourced rows, null when
     *                             none has ever been captured
     */
    public static Finding pitFeedFreshness(LocalDate newestPitTransaction, LocalDate today) {
        String check = "feed";
        String subject = "Insider filings (PIT)";

        if (newestPitTransaction == null) {
            return new Finding(check, subject, Severity.WATCH, "No insider filings captured yet",
                    "No PIT-sourced disclosure has ever been stored, so the insider signal has "
                    + "nothing to read. That is expected before the first capture run and a "
                    + "problem after it.", "no rows");
        }

        long days = ChronoUnit.DAYS.between(newestPitTransaction, today);
        String figure = "newest filing " + newestPitTransaction + " (" + days + " days ago)";

        if (days >= PIT_FEED_PROBLEM_DAYS) {
            return new Finding(check, subject, Severity.PROBLEM, "The feed has stopped advancing",
                    "The most recent insider trade on file is " + days + " days old. Insiders "
                    + "across a universe this size file most weeks, so this is a broken feed "
                    + "rather than a quiet market - which is exactly how B-089 looked for four "
                    + "months while every component reported success. Check that the filing "
                    + "index still returns rows before trusting any NEUTRAL verdict: an empty "
                    + "feed and an inactive insider are indistinguishable downstream.", figure);
        }
        if (days >= PIT_FEED_WATCH_DAYS) {
            return new Finding(check, subject, Severity.WATCH, "Nothing new for over a week",
                    "The most recent insider trade on file is " + days + " days old. That can "
                    + "be a genuinely quiet stretch; it is flagged because a feed that has "
                    + "stopped looks identical to one nobody is filing to.", figure);
        }
        return new Finding(check, subject, Severity.OK, "Filings still arriving",
                "The insider feed is delivering - the newest trade on file is "
                + (days <= 0 ? "from today" : days + " days old") + ".", figure);
    }

    /** Whether the multi-year lenses have enough accounts to say anything yet. */
    public static Finding historyDepth(int universeSize, int deepEnough, double medianYears) {
        if (universeSize <= 0) {
            return new Finding("history", "Years of accounts", Severity.WATCH,
                    "Universe not resolved",
                    "The screening universe came back empty, so depth could not be measured "
                    + "against anything.", "no universe");
        }
        double percent = deepEnough * 100.0 / universeSize;
        String figure = deepEnough + " of " + universeSize + " stocks (" + fmt(percent) + "%)";
        String detail = "The turnaround detector, the receivables and cash-conversion flags, and "
                + "telling a bonus issue apart from real dilution (B-066) all need at least "
                + FORENSIC_READY_YEARS + " years. Median depth is " + years(medianYears)
                + ". Where it is missing the app says \"not enough years\", which is a "
                + "statement about this database and never about the company.";

        Severity severity = percent >= 60 ? Severity.OK : Severity.WATCH;
        String headline = percent >= 60
                ? "Deep enough for the multi-year checks"
                : "Most stocks are not deep enough yet";
        return new Finding("history", "Years of accounts", severity, headline, detail, figure);
    }

    /** Whether the weekday backfill batch is still running. A stalled queue never converges. */
    public static Finding backfillProgress(Integer pending, LocalDate lastAttempt,
                                           ZonedDateTime now) {
        if (pending != null && pending == 0) {
            return new Finding("history", "History backfill", Severity.OK,
                    "Nothing left to fetch",
                    "Every symbol has been settled - either its archive is fully read or it is "
                    + "recorded as having none, which is a finding about the filing, not a "
                    + "failure.", "0 pending");
        }
        String pendingText = (pending == null ? "?" : String.valueOf(pending)) + " symbols pending";

        if (lastAttempt == null) {
            return new Finding("history", "History backfill", Severity.WATCH,
                    "Has never run",
                    "No symbol has been attempted yet. The batch runs at 11:30 on weekdays and "
                    + "needs the app to be up then; until it does, the multi-year sections stay "
                    + "at \"not enough years\".", pendingText);
        }

        ScheduleSpec batch = ScheduleSpec.fixed("backfill", "History backfill",
                "FundamentalsBackfillScheduler", 11, 30, false, false);
        int behind = sessionsBehind(batch, lastAttempt, lastExpectedRun(batch, now));
        String figure = pendingText + ", last attempt " + lastAttempt;

        if (behind >= BACKFILL_STALL_DAYS) {
            return new Finding("history", "History backfill", Severity.PROBLEM,
                    "Stalled for " + behind + " weekdays",
                    "The queue still has work but nothing has been attempted since " + lastAttempt
                    + ". Check the app was running at 11:30, and that "
                    + "trading.fundamentals.backfill.enabled is still true.", figure);
        }
        return new Finding("history", "History backfill", Severity.OK,
                "Running",
                "Still working through the queue a batch per weekday. Sections that say \"not "
                + "enough years\" today should fill in as it goes.", figure);
    }

    // --------------------------------------------------------------------- helpers

    /** "1 year" / "3.5 years" - this string is read by a non-expert, so it has to be English. */
    private static String years(double v) {
        String n = fmt(v);
        return n + ("1".equals(n) ? " year" : " years");
    }

    private static String fmt(Double v) {
        if (v == null) return "not measured";
        return Math.abs(v - Math.rint(v)) < 0.05
                ? String.valueOf(Math.round(v))
                : String.format("%.1f", v);
    }
}
