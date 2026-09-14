package com.example.trading.analyst;

import java.time.LocalDate;
import java.util.List;

/**
 * One broker recommendation as the research feed publishes it (SPEC §49.11).
 *
 * <p>This is the shape the ledger always wanted and had to infer: the house, the target, the
 * rating and the dates arrive as fields rather than as a sentence to be parsed. Everything
 * {@link AnalystTargetParser} and {@link HeadlineSubjectResolver} exist to guess is simply
 * stated here, which is why this path is primary and those two became the fallback.
 *
 * <p><b>Two dates, and the difference is a look-ahead guard.</b> {@code calledOn} is the date the
 * house put on its note; {@code publishedOn} is the date it appeared on the feed. Measurement
 * starts from the day after {@code publishedOn}, because that is the first day the claim could
 * have been acted on by anyone reading it here. Crediting a house with a move that happened
 * before its note was public is exactly the bias SPEC §32.6 introduced {@code available_from} to
 * avoid, and it flatters the analyst in precisely the direction a track record must not.
 *
 * <p><b>{@code scids} is a list, not a value.</b> The feed occasionally carries two internal ids
 * for one company (NALCO publishes as {@code ["NCI","NAC"]}, only one of which resolves to an NSE
 * symbol), and those rows drop the display-name fields entirely. Modelling it as a list keeps the
 * resolver honest rather than silently taking the first.
 *
 * @param feedId          the feed's own row id — stable, and the dedup key
 * @param brokerage       the house as published, before canonicalisation
 * @param scids           internal stock ids; may hold more than one, may be empty
 * @param heading         the published one-line summary, kept verbatim for provenance
 * @param targetPrice     the target, in rupees
 * @param brokerPrice     the price the note itself quoted as current, or null
 * @param calledOn        the date on the note
 * @param publishedOn     the date the feed carried it
 * @param rating          BUY / HOLD / SELL as published, or NOT_STATED
 * @param previousTarget  the same house's prior target on this stock, or null
 * @param researchPdfUrl  link to the note itself
 * @param exchange        "N" or "B" as published, or null
 */
public record BrokerResearchRow(Long feedId,
                                String brokerage,
                                List<String> scids,
                                String heading,
                                double targetPrice,
                                Double brokerPrice,
                                LocalDate calledOn,
                                LocalDate publishedOn,
                                AnalystTargetParser.Rating rating,
                                Double previousTarget,
                                String researchPdfUrl,
                                String exchange) {

    /**
     * What this note did relative to the same house's last one.
     *
     * <p>Derived from the two targets rather than from words in the heading, which is the whole
     * advantage of a structured feed: the headline parser has to infer "raises" or "cuts" from a
     * verb that may not be there, while here the arithmetic is unambiguous.
     * With no previous target on file the honest answer is {@code INITIATE} only when the feed
     * says so — absent that, it is simply not stated.
     */
    public AnalystTargetParser.Action action() {
        if (previousTarget == null || previousTarget <= 0) {
            return AnalystTargetParser.Action.NOT_STATED;
        }
        double diff = targetPrice - previousTarget;
        // A rupee either way on a four-figure target is a rounding difference, not a revision.
        if (Math.abs(diff) < 0.5) return AnalystTargetParser.Action.MAINTAIN;
        return diff > 0 ? AnalystTargetParser.Action.RAISE_TARGET : AnalystTargetParser.Action.CUT_TARGET;
    }

    /**
     * The date the ledger files this call under.
     *
     * <p>The later of the two dates, so a note dated last week but published today is measured
     * from today. See the class note: the alternative silently awards the house whatever the
     * price did while the note was private.
     */
    public LocalDate effectiveDate() {
        if (publishedOn == null) return calledOn;
        if (calledOn == null) return publishedOn;
        return calledOn.isAfter(publishedOn) ? calledOn : publishedOn;
    }
}
