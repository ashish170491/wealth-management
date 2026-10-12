package com.example.trading.jobs;

import com.example.trading.scheduler.MarketHoursService;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A cron expression, said in plain English, plus the facts about it the page needs (SPEC §56.5).
 *
 * <p>Pure: no Spring context, no clock - the caller passes the reference instant. Built on
 * Spring's own {@link CronExpression}, so "when does it fire" is answered by the same parser the
 * scheduler uses, and only the <i>wording</i> is ours.
 *
 * <p>The cron passed in must be the <b>resolved</b> one from the running scheduler, never the
 * literal in the annotation: several annotations read {@code ${holdings.scheduler.analysis-cron:0 30 10 ...}}
 * and the literal is the fallback, not the schedule. Data Health learned that by getting it wrong
 * (see the javadoc on {@code DataHealth.SCHEDULE}).
 *
 * @param text                    "Weekdays at 14:00", "Weekdays, every 15 min from 09:00 to 15:45"
 * @param timesOnRunDay           every fire time on a day the job runs
 * @param outsideWindowOnRunDay   how many of those fall outside both market windows - fires the
 *                                job's own guard refuses by design, so their SKIPPED rows are expected
 * @param runsPerYear             fires over the next 365 days, for sorting by frequency
 */
public record JobSchedule(String cron, String text, List<LocalTime> timesOnRunDay,
                          int outsideWindowOnRunDay, int runsPerYear) {

    private static final Map<String, String> DAY_NAMES = Map.of(
            "MON", "Monday", "TUE", "Tuesday", "WED", "Wednesday", "THU", "Thursday",
            "FRI", "Friday", "SAT", "Saturday", "SUN", "Sunday");

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    /** An expression Spring cannot parse, or {@code "-"} (Spring's "disabled"), described as such. */
    public static JobSchedule unparseable(String cron) {
        String text = "-".equals(cron) ? "Disabled (cron is \"-\")" : "Unreadable schedule: " + cron;
        return new JobSchedule(cron, text, List.of(), 0, 0);
    }

    public static JobSchedule describe(String cron, ZoneId zone, ZonedDateTime reference) {
        if (cron == null || !CronExpression.isValidExpression(cron)) {
            return unparseable(cron);
        }
        CronExpression ce = CronExpression.parse(cron);
        // From the START of the reference day, never the reference instant: described at 10:05, a
        // job that fires every 15 minutes from 09:00 would otherwise read "from 10:15" - the rest
        // of today passed off as the schedule. Caught on the first live load, not by a test whose
        // reference time happened to sit before every fire.
        ZonedDateTime start = reference.withZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).minusSeconds(1);
        ZonedDateTime end = start.plusDays(365);

        int perYear = 0;
        LocalDate firstDay = null;
        List<LocalTime> firstDayTimes = new ArrayList<>();
        ZonedDateTime t = ce.next(start);
        while (t != null && t.isBefore(end)) {
            perYear++;
            if (firstDay == null) firstDay = t.toLocalDate();
            if (t.toLocalDate().equals(firstDay)) firstDayTimes.add(t.toLocalTime());
            t = ce.next(t);
        }
        if (firstDay == null) {
            return new JobSchedule(cron, "Never fires in the next year (" + cron + ")", List.of(), 0, 0);
        }

        int outside = 0;
        for (LocalTime lt : firstDayTimes) {
            ZonedDateTime at = ZonedDateTime.of(firstDay, lt, zone);
            if (!MarketHoursService.isMarketOpenAt(at) && !MarketHoursService.isSaturdayScreeningWindowAt(at)) {
                outside++;
            }
        }

        String[] f = cron.trim().split("\\s+");
        String days = f.length == 6 ? describeDays(f[3], f[4], f[5]) : "On its schedule";
        String text = days + describeTimes(firstDayTimes);
        return new JobSchedule(cron, text, List.copyOf(firstDayTimes), outside, perYear);
    }

    /** The day part: "Weekdays", "Saturdays", "First Friday of the month in Jan, Apr, Jul, Oct". */
    static String describeDays(String dom, String month, String dow) {
        String d = dow.toUpperCase();
        String base;
        boolean firstWeek = "1-7".equals(dom);
        if (d.equals("MON-FRI")) {
            base = "Weekdays";
        } else if (d.equals("*") || d.equals("?")) {
            base = "Every day";
        } else if (DAY_NAMES.containsKey(d)) {
            base = firstWeek ? "First " + DAY_NAMES.get(d) + " of the month" : DAY_NAMES.get(d) + "s";
        } else {
            base = "Days " + dow;
        }
        if (!firstWeek && !dom.equals("*") && !dom.equals("?")) {
            base += ", day " + dom + " of the month";
        }
        if (!month.equals("*") && !month.equals("?")) {
            base += " in " + describeMonths(month);
        }
        return base;
    }

    private static String describeMonths(String month) {
        List<String> out = new ArrayList<>();
        for (String part : month.split(",")) {
            try {
                int m = Integer.parseInt(part.trim());
                out.add(m >= 1 && m <= 12 ? MONTHS[m - 1] : part);
            } catch (NumberFormatException e) {
                out.add(part.trim());
            }
        }
        return String.join(", ", out);
    }

    /** The time part: " at 09:30", " at 11:00, 13:00 and 15:00", ", every 15 min from 09:00 to 15:45". */
    static String describeTimes(List<LocalTime> times) {
        if (times.isEmpty()) return "";
        if (times.size() == 1) return " at " + hhmm(times.get(0));

        Duration gap = Duration.between(times.get(0), times.get(1));
        boolean uniform = true;
        for (int i = 2; i < times.size(); i++) {
            if (!Duration.between(times.get(i - 1), times.get(i)).equals(gap)) {
                uniform = false;
                break;
            }
        }
        if (uniform && times.size() >= 4) {
            long mins = gap.toMinutes();
            String every = mins % 60 == 0
                    ? (mins == 60 ? "every hour" : "every " + (mins / 60) + " hours")
                    : "every " + mins + " min";
            return ", " + every + " from " + hhmm(times.get(0)) + " to " + hhmm(times.get(times.size() - 1));
        }
        List<String> parts = times.stream().map(JobSchedule::hhmm).toList();
        return " at " + String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    private static String hhmm(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    /**
     * The fire this run answered: the latest fire at or before {@code startedAt}, looking back at
     * most {@code lookback}. Null when none is found in that span - a run that started more than
     * the look-back after its fire is not attributed to a guess.
     */
    public static LocalDateTime latestFireAtOrBefore(String cron, ZoneId zone, LocalDateTime startedAt,
                                                     Duration lookback) {
        if (cron == null || !CronExpression.isValidExpression(cron) || startedAt == null) return null;
        CronExpression ce = CronExpression.parse(cron);
        ZonedDateTime target = startedAt.atZone(zone);
        // next() is strictly after its argument, so start one second before the look-back.
        ZonedDateTime t = ce.next(target.minus(lookback).minusSeconds(1));
        ZonedDateTime best = null;
        while (t != null && !t.isAfter(target)) {
            best = t;
            t = ce.next(t);
        }
        return best == null ? null : best.toLocalDateTime();
    }

    /** Every fire in (from, to], oldest first. */
    public static List<LocalDateTime> firesBetween(String cron, ZoneId zone, LocalDateTime from, LocalDateTime to) {
        List<LocalDateTime> out = new ArrayList<>();
        if (cron == null || !CronExpression.isValidExpression(cron) || from == null || to == null) return out;
        CronExpression ce = CronExpression.parse(cron);
        ZonedDateTime end = to.atZone(zone);
        ZonedDateTime t = ce.next(from.atZone(zone));
        while (t != null && !t.isAfter(end)) {
            out.add(t.toLocalDateTime());
            t = ce.next(t);
        }
        return out;
    }

    /** True when a fire at this instant would be refused by both market-window guards. */
    public static boolean outsideWindows(LocalDateTime at, ZoneId zone) {
        ZonedDateTime z = at.atZone(zone);
        return !MarketHoursService.isMarketOpenAt(z) && !MarketHoursService.isSaturdayScreeningWindowAt(z);
    }
}
