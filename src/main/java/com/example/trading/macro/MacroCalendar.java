package com.example.trading.macro;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Dated events that are known in advance (SPEC 48.5), read from {@code macro-calendar.csv}.
 *
 * <p><b>It is a calendar, not a forecast.</b> Every row says when something will happen and which
 * factor it belongs to. No row says which way it will go, and there is deliberately no field in
 * which to record a guess: a expected-direction column would be a short-term price prediction with
 * a date attached, which SPEC 19 bars outright. What it is for is the other half of the question
 * the investor asked - not only which stocks were affected by what has happened, but which of them
 * stand in front of something that is coming.
 *
 * <p><b>Nothing fetches this.</b> The dates come from the RBI's own release calendar, the Budget
 * convention, the Fed's published schedule and the statistics ministry's release dates, seeded
 * offline. That is a deliberate trade: a scheduled fetch would need a new cron and a new external
 * dependency to maintain a table that changes a few times a year and that the investor can edit in
 * a text editor in a minute. When it goes stale it says so rather than guessing, because a row
 * whose date has passed is simply not returned.
 */
@Slf4j
public final class MacroCalendar {

    private MacroCalendar() {
    }

    private static final String RESOURCE = "/macro-calendar.csv";

    /** How a row repeats. Anything else in the file is a startup error. */
    public enum Recurrence {
        /** A single dated event: this Budget, this election, this policy meeting. */
        ONCE,
        /** Every month on the anchor's day, clamped to the month's length. */
        MONTHLY,
        /** Every year on the anchor's month and day. */
        ANNUAL
    }

    /**
     * @param date       the anchor date; for a repeating row, the first occurrence
     * @param label      what happens, in the investor's words
     * @param geography  India, United States, Global - so a reader can tell whose decision it is
     */
    public record Entry(LocalDate date, MacroFactor factor, String label, MacroEventKind kind,
                        String geography, Recurrence recurrence, String notes) {
    }

    /** One expanded occurrence inside a look-ahead window. */
    public record Occurrence(LocalDate date, MacroFactor factor, String label, MacroEventKind kind,
                             String geography, String notes, long daysAway) {
    }

    private static volatile List<Entry> entries;

    /** Every row in the file, in file order. */
    public static List<Entry> all() {
        List<Entry> e = entries;
        if (e == null) {
            synchronized (MacroCalendar.class) {
                e = entries;
                if (e == null) {
                    e = load();
                    entries = e;
                }
            }
        }
        return e;
    }

    /** Dated occurrences from today (inclusive) to {@code days} ahead, soonest first. */
    public static List<Occurrence> upcoming(LocalDate today, int days) {
        return upcoming(all(), today, days);
    }

    /**
     * Expand the rows into the window.
     *
     * <p>Anything already past is excluded rather than shown greyed out: a calendar of things that
     * have happened is what the event ledger is for, and mixing the two would put a date the app
     * has no reading on beside dates it does.
     */
    public static List<Occurrence> upcoming(List<Entry> rows, LocalDate today, int days) {
        LocalDate from = today == null ? LocalDate.now() : today;
        LocalDate to = from.plusDays(Math.max(0, days));
        List<Occurrence> out = new ArrayList<>();
        for (Entry e : rows) {
            for (LocalDate d : occurrencesOf(e, from, to)) {
                out.add(new Occurrence(d, e.factor(), e.label(), e.kind(), e.geography(), e.notes(),
                        ChronoUnit.DAYS.between(from, d)));
            }
        }
        out.sort((a, b) -> {
            int byDate = a.date().compareTo(b.date());
            return byDate != 0 ? byDate : a.label().compareTo(b.label());
        });
        return List.copyOf(out);
    }

    private static List<LocalDate> occurrencesOf(Entry e, LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        if (e.date() == null) return out;
        switch (e.recurrence()) {
            case ONCE -> {
                if (!e.date().isBefore(from) && !e.date().isAfter(to)) out.add(e.date());
            }
            case MONTHLY -> {
                YearMonth ym = YearMonth.from(from);
                YearMonth end = YearMonth.from(to);
                while (!ym.isAfter(end)) {
                    int day = Math.min(e.date().getDayOfMonth(), ym.lengthOfMonth());
                    LocalDate d = ym.atDay(day);
                    if (!d.isBefore(from) && !d.isAfter(to) && !d.isBefore(e.date())) out.add(d);
                    ym = ym.plusMonths(1);
                }
            }
            case ANNUAL -> {
                for (int year = from.getYear(); year <= to.getYear(); year++) {
                    LocalDate d;
                    try {
                        d = LocalDate.of(year, e.date().getMonth(), e.date().getDayOfMonth());
                    } catch (Exception ex) {
                        continue; // 29 February in a common year
                    }
                    if (!d.isBefore(from) && !d.isAfter(to) && !d.isBefore(e.date())) out.add(d);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ loading

    private static List<Entry> load() {
        try (InputStream in = MacroCalendar.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Macro calendar " + RESOURCE + " is missing from the classpath.");
            }
            StringBuilder text = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    text.append(line).append('\n');
                }
            }
            List<Entry> parsed = parse(text.toString());
            log.info("Macro calendar loaded: {} scheduled rows", parsed.size());
            return parsed;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Macro calendar " + RESOURCE + " could not be read: " + e.getMessage(), e);
        }
    }

    /** Parse and validate. Visible for tests. Fails naming the line, never drops a row silently. */
    static List<Entry> parse(String csv) {
        List<Entry> out = new ArrayList<>();
        String[] lines = csv.split("\n", -1);
        boolean headerSeen = false;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].trim();
            int lineNo = i + 1;
            if (raw.isEmpty() || raw.startsWith("#")) continue;
            if (!headerSeen && raw.toLowerCase(Locale.ROOT).startsWith("date,")) {
                headerSeen = true;
                continue;
            }
            String[] f = raw.split(",", 7);
            if (f.length < 7) {
                throw new IllegalStateException(fail(lineNo, raw, "expected 7 fields "
                        + "(date,factor,label,kind,geography,recurrence,notes) but found " + f.length));
            }
            LocalDate date;
            try {
                date = LocalDate.parse(f[0].trim());
            } catch (DateTimeParseException ex) {
                throw new IllegalStateException(fail(lineNo, raw, "the date must be yyyy-MM-dd, not \"" + f[0].trim() + "\""));
            }
            final String factorRaw = f[1];
            MacroFactor factor = MacroFactor.parse(factorRaw).orElseThrow(() -> new IllegalStateException(
                    fail(lineNo, raw, "unknown factor \"" + factorRaw.trim() + "\"")));
            String label = f[2].trim();
            if (label.isEmpty()) {
                throw new IllegalStateException(fail(lineNo, raw, "the label is blank"));
            }
            final String kindRaw = f[3];
            MacroEventKind kind = MacroEventKind.parse(kindRaw).orElseThrow(() -> new IllegalStateException(
                    fail(lineNo, raw, "kind must be SCHEDULED or SURPRISE, not \"" + kindRaw.trim() + "\"")));
            String geography = f[4].trim();
            final String recRaw = f[5];
            Recurrence recurrence = parseRecurrence(recRaw).orElseThrow(() -> new IllegalStateException(
                    fail(lineNo, raw, "recurrence must be ONCE, MONTHLY or ANNUAL, not \"" + recRaw.trim() + "\"")));
            out.add(new Entry(date, factor, label, kind, geography, recurrence, f[6].trim()));
        }
        return Collections.unmodifiableList(out);
    }

    private static Optional<Recurrence> parseRecurrence(String raw) {
        if (raw == null) return Optional.empty();
        String k = raw.trim().toUpperCase(Locale.ROOT);
        for (Recurrence r : Recurrence.values()) {
            if (r.name().equals(k)) return Optional.of(r);
        }
        return Optional.empty();
    }

    private static String fail(int lineNo, String raw, String why) {
        return "macro-calendar.csv line " + lineNo + ": " + why + "\n  " + raw;
    }
}
