package com.example.trading.macro;

import com.example.trading.macro.MacroExposureMap.Entry;
import com.example.trading.macro.MacroExposureMap.OnRise;
import com.example.trading.macro.MacroExposureMap.Strength;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What recent macro events mean for one business (SPEC 48.4). Pure rule table, computed on read,
 * never stored.
 *
 * <p><b>The vocabulary contains no instruction to transact.</b> {@code TAILWIND} is not "buy" and
 * {@code HEADWIND} is not "sell"; they say which way the ground is tilting under a business over
 * the coming quarters. A long-term holder's most valuable use of a headwind is to read the next
 * weak quarter correctly - as weather the company was always going to walk through, rather than as
 * evidence the thesis has broken. SPEC 19 bars a buy/sell signal engine and SPEC 20 rule 10 makes
 * that a review gate; this class is pinned by a test that fails if a transacting word appears.
 *
 * <p><b>Five outcomes, and two of them are easy to confuse.</b>
 * <ul>
 *   <li>{@code NOT_MEASURED} - the map has no rule for this business. A gap in <i>our</i> table,
 *       never a finding about the company.</li>
 *   <li>{@code NOT_EXPOSED} - the map has rules and nothing in the window matched them. This is a
 *       real finding, and on most days for most stocks it is the correct one.</li>
 * </ul>
 * Collapsing those two would let a blind spot read as an all-clear, which is the failure Gotcha 44
 * and Gotcha 68 describe in two other corners of this codebase.
 *
 * <p><b>MIXED is never netted off.</b> An integrated refiner hit by a crude rise on one side and
 * helped on the other is reported as both, because averaging them produces a confident small
 * number in place of two real and opposing effects.
 */
public final class MacroExposureRead {

    private MacroExposureRead() {
    }

    /**
     * A leveraged business feels a rate move harder. Above this debt-to-equity the strength of a
     * rate reading is raised, because the interest line is already large relative to the equity
     * absorbing it.
     *
     * <p>Never applied to a lender: a bank or an NBFC is <i>funded</i> by debt by construction and
     * runs at five to eight times equity in the ordinary course, so this test would mark every one
     * of them highly rate-sensitive on a number that says nothing about rate sensitivity. Same
     * reasoning that makes SPEC 12.8 refuse to compute a debt-to-equity verdict for financials at
     * all.
     */
    public static final double LEVERAGED_DEBT_TO_EQUITY = 1.0;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** The reading. See the class note on why {@code NOT_EXPOSED} and {@code NOT_MEASURED} differ. */
    public enum Verdict { TAILWIND, HEADWIND, MIXED, NOT_EXPOSED, NOT_MEASURED }

    /** What one event does to one business. */
    public enum Effect { TAILWIND, HEADWIND, MIXED }

    /**
     * One event, as the ledger recorded it.
     *
     * @param occurredAt the day the event happened, not the day it was read
     * @param dismissed  the investor marked it noise; it stays on the record and stops counting
     */
    public record Event(long id, MacroFactor factor, MacroDirection direction, MacroMagnitude magnitude,
                        LocalDate occurredAt, String summary, boolean dismissed) {
    }

    /**
     * Why the verdict says what it says.
     *
     * @param text      one line for a table cell: what moved, when, through which channel
     * @param rationale the map's own sentence, so the rule can be checked rather than trusted
     */
    public record Reason(long eventId, MacroFactor factor, MacroDirection eventDirection, Effect effect,
                         Strength strength, String channel, LocalDate occurredAt, String text,
                         String rationale, String from) {
    }

    /**
     * @param sector        a {@link com.example.trading.portfolio.SectorMapping} bucket
     * @param lender        a bank or non-bank lender; suppresses the leverage adjustment only
     * @param debtToEquity  latest annual figure, null when it was never measured
     * @param exposures     every rule that applies to this stock, from {@link MacroExposureMap}
     * @param events        candidate events; filtered here rather than by the caller so the window
     *                      rule lives in one place
     * @param windowDays    how far back an event still counts
     */
    public record Input(String symbol, String sector, boolean lender, Double debtToEquity,
                        List<Entry> exposures, List<Event> events, LocalDate asOf, int windowDays) {
    }

    /**
     * @param eventsConsidered how many events were in the window at all, matched or not - so a
     *                         reader can tell "nothing happened" from "nothing here was affected"
     * @param answeredBy       which rule carried the headline reason, e.g. {@code SYMBOL:ASIANPAINT}
     * @param note             plain English for the case where there is no verdict to give
     */
    public record Result(Verdict verdict, Strength strength, List<Reason> reasons,
                         int eventsConsidered, String answeredBy, String note) {

        /** True when the map could say something about this business, whatever the answer was. */
        public boolean measured() {
            return verdict != Verdict.NOT_MEASURED;
        }
    }

    public static Result read(Input in) {
        if (in == null) {
            return new Result(Verdict.NOT_MEASURED, null, List.of(), 0, null,
                    "Nothing to read.");
        }

        // Rule 0: no rule for this business. Not a finding about the company - a gap in our map.
        if (in.exposures() == null || in.exposures().isEmpty()) {
            String where = in.sector() == null || in.sector().isBlank() || "UNKNOWN".equals(in.sector())
                    ? "this business"
                    : "the " + in.sector().toLowerCase(Locale.ROOT).replace('_', ' ') + " sector";
            return new Result(Verdict.NOT_MEASURED, null, List.of(), 0, null,
                    "The exposure map has no rule for " + where + " yet, so the app cannot say. "
                            + "That is a gap in the map, not a verdict on the company.");
        }

        // Rule 1: only events inside the window, not dismissed, and not dated in the future.
        // A future-dated event belongs to the calendar, not to a reading about what has happened;
        // counting one would also mean it never ages out of a trailing window (the B-031 trap).
        LocalDate asOf = in.asOf() == null ? LocalDate.now() : in.asOf();
        LocalDate cutoff = asOf.minusDays(Math.max(0, in.windowDays()));
        List<Event> live = new ArrayList<>();
        if (in.events() != null) {
            for (Event e : in.events()) {
                if (e == null || e.dismissed() || e.occurredAt() == null) continue;
                if (e.occurredAt().isBefore(cutoff) || e.occurredAt().isAfter(asOf)) continue;
                live.add(e);
            }
        }

        Map<MacroFactor, Entry> byFactor = new LinkedHashMap<>();
        for (Entry entry : in.exposures()) {
            byFactor.putIfAbsent(entry.factor(), entry);
        }

        // Rule 2: match each event against the rules, and build a reason for every hit.
        List<Reason> reasons = new ArrayList<>();
        for (Event e : live) {
            Entry rule = byFactor.get(e.factor());
            if (rule == null) continue;
            Effect effect = effectOf(rule.onRise(), e.direction());
            Strength strength = adjustedStrength(rule, e, in);
            reasons.add(new Reason(e.id(), e.factor(), e.direction(), effect, strength, rule.channel(),
                    e.occurredAt(), sentence(e, rule, effect, strength), rule.rationale(), rule.from()));
        }

        if (reasons.isEmpty()) {
            String note = live.isEmpty()
                    ? "No macro event was recorded in the last " + in.windowDays() + " days."
                    : live.size() + " macro " + (live.size() == 1 ? "event" : "events")
                            + " in the last " + in.windowDays() + " days, and none of them touches this business.";
            return new Result(Verdict.NOT_EXPOSED, null, List.of(), live.size(), null, note);
        }

        // Strongest first, so the headline reason is the one that matters most, and a table cell
        // that shows only the first line shows the right line.
        reasons.sort((a, b) -> {
            int byStrength = Integer.compare(b.strength().rank(), a.strength().rank());
            if (byStrength != 0) return byStrength;
            return b.occurredAt().compareTo(a.occurredAt());
        });

        Verdict verdict = verdictOf(reasons);
        Strength strength = reasons.get(0).strength();
        return new Result(verdict, strength, List.copyOf(reasons), live.size(), reasons.get(0).from(), null);
    }

    // ------------------------------------------------------------------ rules

    /**
     * A rise that helps is a tailwind; a fall in the same factor is a headwind. This one line is
     * what a keyword sentiment score cannot do: the same event with the same words is a tailwind
     * for the exporter and a headwind for the importer, and only the map knows which is which.
     */
    static Effect effectOf(OnRise onRise, MacroDirection direction) {
        if (onRise == OnRise.MIXED) return Effect.MIXED;
        boolean helped = (onRise == OnRise.HELPED) == (direction == MacroDirection.UP);
        return helped ? Effect.TAILWIND : Effect.HEADWIND;
    }

    /**
     * Any {@code MIXED} effect, or one of each direction, makes the whole reading mixed. Never a
     * net: two opposing effects are two facts, and averaging them into one small number would
     * report a precision that does not exist.
     */
    private static Verdict verdictOf(List<Reason> reasons) {
        boolean tail = false;
        boolean head = false;
        for (Reason r : reasons) {
            switch (r.effect()) {
                case MIXED -> {
                    return Verdict.MIXED;
                }
                case TAILWIND -> tail = true;
                case HEADWIND -> head = true;
            }
        }
        if (tail && head) return Verdict.MIXED;
        if (tail) return Verdict.TAILWIND;
        return Verdict.HEADWIND;
    }

    /**
     * The map's strength, raised for a leveraged borrower facing a rate move.
     *
     * <p>The magnitude of the event is deliberately <b>not</b> an input. A small crude rise and a
     * large one are both headwinds for a paint maker, and the strength column answers "how much of
     * this business does the channel touch", which the size of one week's move does not change.
     */
    private static Strength adjustedStrength(Entry rule, Event event, Input in) {
        Strength base = rule.strength();
        boolean rateFactor = rule.factor() == MacroFactor.INTEREST_RATES || rule.factor() == MacroFactor.US_RATES;
        if (!rateFactor || in.lender()) return base;
        Double de = in.debtToEquity();
        if (de == null || de <= LEVERAGED_DEBT_TO_EQUITY) return base;
        return Strength.HIGH;
    }

    private static String sentence(Event e, Entry rule, Effect effect, Strength strength) {
        String when = e.occurredAt() == null ? "" : " (" + e.occurredAt().format(DAY) + ")";
        String size = e.magnitude() == null ? "" : " " + e.magnitude().label();
        String effectWord = switch (effect) {
            case TAILWIND -> "a tailwind";
            case HEADWIND -> "a headwind";
            case MIXED -> "both a help and a cost";
        };
        boolean leverageRaised = strength != rule.strength();
        String tail = leverageRaised
                ? ", and this business carries enough debt for it to matter more than usual"
                : "";
        return rule.factor().label() + " " + e.direction().pastTense() + size + when
                + ": " + rule.channel() + " - " + effectWord + ", " + strength.label() + tail + ".";
    }
}
