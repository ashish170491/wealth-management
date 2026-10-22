package com.example.trading.valuation;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * What the model returns: a wrapper so an empty list - the right answer when nothing in the
 * reading looks questionable - is a well-formed reply rather than a parse failure.
 */
@JsonClassDescription("Every question raised about the reading. Empty when there is nothing to question.")
public record CritiqueBatch(

        @JsonProperty(required = true)
        @JsonPropertyDescription("At most five questions. Return an empty list if there is nothing to raise.")
        List<AssumptionCritique> critiques
) {
}
