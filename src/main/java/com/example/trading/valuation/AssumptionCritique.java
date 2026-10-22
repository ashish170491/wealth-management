package com.example.trading.valuation;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * One question a language model raises about the lens's assumptions (SPEC §57.8).
 *
 * <p><b>Every field is a String, deliberately</b> (the {@code ExtractedEvent} reasoning): one
 * hallucinated enum value would fail the parse and lose the whole batch, whereas as text it is
 * dropped by {@link ValuationAssumptionChallenger} with a log line and the rest survive. The record
 * has no field for a number, a price or a verdict: a critique is a question to read, not a figure.
 */
@JsonClassDescription("One question about an assumption behind a valuation-gap table. Never a "
        + "recommendation and never a forecast.")
public record AssumptionCritique(

        @JsonProperty(required = true)
        @JsonPropertyDescription("Which part is being questioned. Exactly one of: NORMALISED_EPS, TREND, "
                + "SHARE_ADJUSTMENT, REFERENCE_MULTIPLES, HURDLE_RATE, REVERSE_DCF, DATA.")
        String target,

        @JsonProperty(required = true)
        @JsonPropertyDescription("Exactly one of: QUESTIONABLE_ASSUMPTION, MISSING_EVIDENCE, "
                + "POSSIBLE_CONTRADICTION.")
        String kind,

        @JsonProperty(required = true)
        @JsonPropertyDescription("One or two plain sentences. Use only numbers that appear in the input. "
                + "Do not advise, and do not say what will happen.")
        String text
) {
}
