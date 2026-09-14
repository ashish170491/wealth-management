package com.example.trading.macro;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * What a language model returns after reading a batch of headlines (SPEC 48.4).
 *
 * <p>A wrapper rather than a bare list because Spring AI's structured-output converter builds a
 * JSON schema from a type, and a top-level object gives the model somewhere to put an empty list
 * without producing a malformed reply. An empty list is the expected answer on most days: a normal
 * news batch contains one or two macro events, and frequently none.
 */
@JsonClassDescription("Every macro event found in the numbered headlines. Empty when there are none.")
public record EventBatch(

        @JsonProperty(required = true)
        @JsonPropertyDescription("The macro events found. Return an empty list if there are none.")
        List<ExtractedEvent> events
) {
}
