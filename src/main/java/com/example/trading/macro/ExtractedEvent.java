package com.example.trading.macro;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The wire shape a language model returns when it reads headlines (SPEC §48.4).
 *
 * <p><b>Every field is a String, deliberately.</b> Spring AI builds a JSON schema from this record
 * and Jackson parses the reply into it; if {@code factor} were an enum, one hallucinated value
 * would fail the parse and lose <i>the whole batch</i> including the events the model got right.
 * As text, an unrecognised value is dropped by {@link #validate} with a log line naming it, and
 * everything else survives. The same reasoning applies to the date.
 *
 * <p><b>The model never names a company.</b> There is no field for one, and that absence is the
 * design: the model extracts what happened and the exposure map decides who it touches. A model
 * asked which stocks an event hurts will answer fluently every time, including when it is wrong,
 * and nothing downstream could tell the difference.
 */
@JsonPropertyOrder({"factor", "direction", "magnitude", "kind", "geography", "occurredOn",
        "headlineNumbers", "confidence", "summary"})
@JsonClassDescription("A macro or geopolitical event extracted from news headlines. Never mentions "
        + "an individual company or a share price.")
public record ExtractedEvent(

        @JsonProperty(required = true)
        @JsonPropertyDescription("One of the allowed factor names, exactly as listed in the instructions.")
        String factor,

        @JsonProperty(required = true)
        @JsonPropertyDescription("UP if the factor rose, DOWN if it fell, as defined in the instructions. "
                + "For USDINR, UP means the rupee weakened.")
        String direction,

        @JsonProperty(required = true)
        @JsonPropertyDescription("SMALL, MODERATE or LARGE, judged from how the headlines describe the move.")
        String magnitude,

        @JsonProperty(required = true)
        @JsonPropertyDescription("SCHEDULED if this was on a published calendar such as a policy meeting, "
                + "a budget or a data release. SURPRISE otherwise.")
        String kind,

        @JsonPropertyDescription("Where it happened: India, United States, China or Global.")
        String geography,

        @JsonPropertyDescription("The date the event happened, as yyyy-MM-dd. Use the headline's own date "
                + "when the text does not say.")
        String occurredOn,

        @JsonProperty(required = true)
        @JsonPropertyDescription("The numbers of the headlines that report this event, from the numbered list.")
        List<Integer> headlineNumbers,

        @JsonPropertyDescription("How sure you are, from 0 to 1.")
        Double confidence,

        @JsonProperty(required = true)
        @JsonPropertyDescription("One sentence describing what happened. No company names, no share prices, "
                + "no advice.")
        String summary
) {

    /** A validated event, with the headlines it was drawn from resolved back to stored rows. */
    public record Validated(MacroFactor factor, MacroDirection direction, MacroMagnitude magnitude,
                            MacroEventKind kind, String geography, LocalDate occurredAt,
                            List<Long> headlineIds, List<String> sourceUrls, Double confidence,
                            String summary) {
    }

    /**
     * Turn one model row into something storable, or explain why it cannot be.
     *
     * @param references the numbered headlines given to the model, in order
     * @param problems   appended to when a row is rejected, so the caller can log what was dropped
     *                   rather than silently losing it
     */
    public Optional<Validated> validate(List<MacroKeywordExtractor.Headline> references, List<String> problems) {
        Optional<MacroFactor> f = MacroFactor.parse(factor);
        if (f.isEmpty()) {
            problems.add("unknown factor \"" + factor + "\"");
            return Optional.empty();
        }
        Optional<MacroDirection> d = MacroDirection.parse(direction);
        if (d.isEmpty()) {
            problems.add(f.get() + ": unreadable direction \"" + direction + "\"");
            return Optional.empty();
        }

        List<Long> ids = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        LocalDate fromHeadline = null;
        if (headlineNumbers != null) {
            for (Integer n : headlineNumbers) {
                if (n == null || n < 1 || n > references.size()) continue;
                MacroKeywordExtractor.Headline h = references.get(n - 1);
                ids.add(h.id());
                if (h.url() != null && !h.url().isBlank()) urls.add(h.url());
                if (fromHeadline == null || (h.publishedOn() != null && h.publishedOn().isBefore(fromHeadline))) {
                    if (h.publishedOn() != null) fromHeadline = h.publishedOn();
                }
            }
        }
        if (ids.isEmpty()) {
            // An event with no headline behind it cannot be traced to a source, and an untraceable
            // claim is exactly what this design refuses to put in front of the investor.
            problems.add(f.get() + ": cites no headline the app actually holds");
            return Optional.empty();
        }

        LocalDate on = parseDate(occurredOn);
        if (on == null) on = fromHeadline;
        if (on == null) on = LocalDate.now();
        // A model asked for a date sometimes returns next week's. A future event belongs to the
        // calendar, not the ledger, and would never age out of a trailing window (the B-031 trap).
        if (on.isAfter(LocalDate.now())) on = LocalDate.now();

        return Optional.of(new Validated(f.get(), d.get(),
                MacroMagnitude.parse(magnitude).orElse(MacroMagnitude.MODERATE),
                MacroEventKind.parse(kind).orElse(MacroEventKind.SURPRISE),
                geography == null || geography.isBlank() ? "India" : geography.trim(),
                on, ids, urls, clampConfidence(confidence),
                summary == null || summary.isBlank() ? f.get().label() + " " + d.get().pastTense() : summary.trim()));
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** A confidence outside 0..1 is not a confidence; it is reported as unmeasured rather than clipped silently. */
    private static Double clampConfidence(Double c) {
        if (c == null || c.isNaN() || c < 0.0 || c > 1.0) return null;
        return c;
    }
}
