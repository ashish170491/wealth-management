package com.example.trading.insider;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Rolling insider signal for one stock (SPEC §28, F1).
 *
 * <p>A null {@code verdict} means <b>no disclosure rows at all</b> for this symbol — which is
 * different from NEUTRAL (rows exist, and they net to nothing). Callers must keep the two
 * apart: "nobody filed anything" is missing data, "insiders traded and it netted flat" is a
 * measurement. Collapsing them is the B-019 mistake in a new place.
 */
@Data
@Builder
public class InsiderPulse {

    private String symbol;

    /** STRONG_ACCUMULATION / ACCUMULATION / NEUTRAL / DISTRIBUTION / STRONG_DISTRIBUTION, or null. */
    private String verdict;

    /** Net open-market buy value over the window, in rupees (negative = net selling). */
    private Double netBuyValue;

    /** Net buy value as a percentage of market cap. Null when market cap is unknown. */
    private Double netBuyPercentOfMarketCap;

    private int buyEvents;
    private int sellEvents;

    /** Rows seen but deliberately excluded from scoring (pledge, ESOP, gift, inter-se...). */
    private int excludedNonMarketRows;

    private int windowDays;

    @Builder.Default
    private List<String> notes = List.of();

    /**
     * 0-100 rendering of the verdict, so the signal is visible to the per-dimension IC
     * machinery, which reads MULTIBAGGER sub-scores as Integers off {@code multibagger_scores}.
     * Null verdict maps to null — never to 50.
     */
    public Integer toScore() {
        if (verdict == null) return null;
        return switch (verdict) {
            case "STRONG_ACCUMULATION" -> 100;
            case "ACCUMULATION" -> 75;
            case "NEUTRAL" -> 50;
            case "DISTRIBUTION" -> 25;
            case "STRONG_DISTRIBUTION" -> 0;
            default -> null;
        };
    }
}
