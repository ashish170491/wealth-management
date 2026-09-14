package com.example.trading.universe.ipo;

import java.util.ArrayList;
import java.util.List;

/**
 * What can honestly be said about an issue <b>before it lists</b>, from the exchange feed alone
 * (SPEC §45.3). Pure rule table, computed on read, never stored.
 *
 * <p>This is deliberately <b>not</b> an "apply / skip" verdict. The app has no filings for a
 * company that has not yet listed — no XBRL, no shareholding pattern, no cash-flow history — so
 * it cannot run a single one of the seven pillars on it. What it can read is the <i>structure</i>
 * of the offer, which is the part of an IPO most retail investors never look at and the part
 * that most reliably separates a capital raise from an exit:
 * <ul>
 *   <li><b>Fresh issue versus offer for sale.</b> Fresh money goes into the business; an offer
 *       for sale goes to the people who know it best and are choosing this price to leave.</li>
 *   <li><b>Who filled the book, once it is final.</b> Institutions read the full prospectus and
 *       meet management; a book they left unfilled is the strongest single warning the feed
 *       carries. Retail piling in while they stay cool is the hype pattern.</li>
 * </ul>
 *
 * <p>Two refusals are built in. <b>A mid-issue subscription is not read as a verdict</b> —
 * institutions bid on the last afternoon, so a "QIB 0.5×" on day one is normal and means
 * nothing; the read waits for {@code subscriptionFinal}. And <b>an unparsed issue-size sentence
 * leaves the structure unmeasured</b> rather than defaulting a leg to zero (Gotcha 21).
 */
public final class IpoStructureRead {

    private IpoStructureRead() {
    }

    public enum Verdict { FAVOURABLE, MIXED, UNFAVOURABLE, NOT_MEASURED }

    /**
     * @param freshSharePct     fresh / (fresh + OFS) in percent; null when the sentence did not parse
     * @param qibTimes          institutional subscription, null until published
     * @param retailTimes       retail subscription, null until published
     * @param subscriptionFinal true only when the figures were read after the last bidding day
     */
    public record Input(Double freshSharePct, Double qibTimes, Double retailTimes, Boolean subscriptionFinal) {
    }

    public record Result(Verdict verdict, List<String> reasons, boolean structureMeasured,
                         boolean subscriptionMeasured) {
    }

    /** Below this share of fresh money the issue is read as mostly an exit. */
    public static final double MOSTLY_EXIT_BELOW_PCT = 25.0;
    /** At or above this share the issue is read as a genuine capital raise. */
    public static final double CAPITAL_RAISE_FROM_PCT = 50.0;
    /** Institutions did not fill their book — the strongest warning the feed carries. */
    public static final double QIB_UNFILLED = 1.0;
    /** Institutions wanted it: their book covered at least this many times. */
    public static final double QIB_CONVINCED = 2.0;
    /** Retail this far ahead of a cool institutional book is the hype pattern. */
    public static final double RETAIL_HYPE_TIMES = 10.0;

    public static Result read(Input in) {
        List<String> reasons = new ArrayList<>();
        boolean structure = in.freshSharePct() != null;
        boolean subscription = Boolean.TRUE.equals(in.subscriptionFinal()) && in.qibTimes() != null;

        if (!structure && !subscription) {
            reasons.add(Boolean.TRUE.equals(in.subscriptionFinal())
                    ? "The issue-size wording could not be read and no institutional figure was published, so nothing here can be judged."
                    : "The issue-size wording could not be read, and subscription figures only mean something after the last bidding day.");
            return new Result(Verdict.NOT_MEASURED, reasons, false, false);
        }

        // Rule 1: institutions, with the full prospectus in hand, passed. Nothing else outranks it.
        if (subscription && in.qibTimes() < QIB_UNFILLED) {
            reasons.add(String.format("Institutions covered only %.2f× of their book. They read the full prospectus "
                    + "and met the management, and they passed.", in.qibTimes()));
            addStructureReason(in, reasons);
            return new Result(Verdict.UNFAVOURABLE, reasons, structure, true);
        }

        // Rule 2: the hype pattern — retail chasing while institutions stay cool.
        if (subscription && in.retailTimes() != null
                && in.retailTimes() >= RETAIL_HYPE_TIMES && in.qibTimes() < QIB_CONVINCED) {
            reasons.add(String.format("Retail bid %.1f× while institutions covered %.2f×. Small investors are "
                    + "chasing this one and the informed money is not — the usual shape of a listing-day story.",
                    in.retailTimes(), in.qibTimes()));
            addStructureReason(in, reasons);
            return new Result(Verdict.UNFAVOURABLE, reasons, structure, true);
        }

        // Rule 3: mostly an exit.
        if (structure && in.freshSharePct() < MOSTLY_EXIT_BELOW_PCT) {
            addStructureReason(in, reasons);
            if (subscription) addSubscriptionReason(in, reasons);
            // Institutions strongly wanting an exit-heavy issue is a real counterweight, but it
            // does not turn a sale by insiders into a capital raise.
            return new Result(Verdict.MIXED, reasons, true, subscription);
        }

        // Rule 4: a genuine raise the institutions wanted, or one they have not yet judged.
        if (structure && in.freshSharePct() >= CAPITAL_RAISE_FROM_PCT
                && (!subscription || in.qibTimes() >= QIB_CONVINCED)) {
            addStructureReason(in, reasons);
            if (subscription) addSubscriptionReason(in, reasons);
            else reasons.add("Subscription is not final yet; institutions usually bid on the last afternoon.");
            return new Result(Verdict.FAVOURABLE, reasons, true, subscription);
        }

        // Everything else is mixed: a middling fresh share, or a raise institutions were lukewarm on,
        // or a strong book on an issue whose structure could not be read.
        if (structure) addStructureReason(in, reasons);
        else reasons.add("The issue-size wording could not be read, so the fresh-versus-sale split is unknown.");
        if (subscription) addSubscriptionReason(in, reasons);
        return new Result(Verdict.MIXED, reasons, structure, subscription);
    }

    private static void addStructureReason(Input in, List<String> reasons) {
        if (in.freshSharePct() == null) return;
        double f = in.freshSharePct();
        if (f >= CAPITAL_RAISE_FROM_PCT) {
            reasons.add(String.format("%.0f%% of the money raised goes into the company, not to sellers.", f));
        } else if (f >= MOSTLY_EXIT_BELOW_PCT) {
            reasons.add(String.format("Only %.0f%% of the money raised goes into the company; the rest pays out existing holders.", f));
        } else if (f > 0) {
            reasons.add(String.format("Just %.0f%% of the money raised goes into the company. This is mostly existing "
                    + "holders selling at a price they chose.", f));
        } else {
            reasons.add("Entirely an offer for sale: not one rupee goes into the business. The people who know "
                    + "it best are the sellers.");
        }
    }

    private static void addSubscriptionReason(Input in, List<String> reasons) {
        if (in.qibTimes() == null) return;
        if (in.qibTimes() >= QIB_CONVINCED) {
            reasons.add(String.format("Institutions covered their book %.1f× — they wanted it at this price.", in.qibTimes()));
        } else {
            reasons.add(String.format("Institutions covered their book only %.2f× — filled, but without enthusiasm.", in.qibTimes()));
        }
    }
}
