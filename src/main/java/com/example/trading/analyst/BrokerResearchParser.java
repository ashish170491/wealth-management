package com.example.trading.analyst;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the broker-research feed's JSON into {@link BrokerResearchRow} (SPEC §49.11). Pure: no
 * network, no repository, no clock beyond the caller's.
 *
 * <p><b>Written against the live payload, not against an assumption about it.</b> A census over
 * 800 real rows found four different key sets, {@code scid} typed as either a string or an array,
 * numbers arriving as {@code String}, {@code int} and {@code double} in the same field, and a
 * rating of {@code "-"}. Every one of those is handled here rather than left to throw inside a
 * capture loop, because a single malformed row must cost one row and never the batch — the same
 * rule {@code MacroEventExtractor} follows for a hallucinated enum (SPEC §48).
 *
 * <p>What it will <em>not</em> do is invent. A row with no usable target, no house or no date
 * returns null and is counted in the funnel, because the one thing worse than a thin ledger is a
 * ledger with a number nobody published in it.
 */
@Slf4j
public final class BrokerResearchParser {

    private BrokerResearchParser() {
    }

    /** Stamped on every row this path writes, so a reading can be traced to its reader. */
    public static final String VERSION = "mcbr1";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * A price target on an Indian listed share. Identical bounds to the headline parser: below a
     * rupee is not a target, and above two lakh is a market capitalisation or a misread.
     */
    private static final double MIN_TARGET = 1.0;
    private static final double MAX_TARGET = 200_000.0;

    /**
     * One feed row, or null when it cannot be trusted.
     *
     * @param node one element of the feed's {@code data} array
     */
    public static BrokerResearchRow parse(JsonNode node) {
        if (node == null || !node.isObject()) return null;

        Double target = number(node.get("target_price"));
        if (target == null || target < MIN_TARGET || target > MAX_TARGET) return null;

        String house = text(node.get("organization"));
        if (house == null || house.isBlank()) return null;

        LocalDate published = date(node.get("entry_date"));
        LocalDate called = date(node.get("target_price_date"));
        if (published == null && called == null) return null;

        return new BrokerResearchRow(
                id(node.get("id")),
                house.trim(),
                scids(node.get("scid")),
                text(node.get("heading")),
                target,
                positiveOrNull(number(node.get("recommended_price"))),
                called,
                published,
                rating(text(node.get("recommend_flag"))),
                previousTarget(node.get("stock_data")),
                text(node.get("attachment")),
                text(node.get("exchange")));
    }

    /**
     * The published rating.
     *
     * <p>The feed writes {@code "-"} when a note carries a target but no stance. That is an
     * absence and is filed as one: collapsing it into HOLD would invent a neutral opinion for a
     * house that expressed none, which is the same error as reading a null score as 50.
     */
    static AnalystTargetParser.Rating rating(String flag) {
        if (flag == null) return AnalystTargetParser.Rating.NOT_STATED;
        String f = flag.trim().toUpperCase(Locale.ROOT);
        return switch (f) {
            case "BUY", "B", "ACCUMULATE", "ADD", "OUTPERFORM", "OVERWEIGHT" -> AnalystTargetParser.Rating.BUY;
            case "HOLD", "H", "NEUTRAL", "EQUAL WEIGHT", "EQUALWEIGHT" -> AnalystTargetParser.Rating.HOLD;
            case "SELL", "S", "REDUCE", "UNDERPERFORM", "UNDERWEIGHT" -> AnalystTargetParser.Rating.SELL;
            default -> AnalystTargetParser.Rating.NOT_STATED;
        };
    }

    /**
     * The same house's prior target, when the feed carries one.
     *
     * <p>This is the revision chain handed over rather than reconstructed, and it is what lets a
     * RAISE or CUT be stated as arithmetic instead of inferred from a verb.
     */
    static Double previousTarget(JsonNode stockData) {
        if (stockData == null || !stockData.isObject()) return null;
        JsonNode prev = stockData.get("previous");
        if (prev == null || !prev.isObject()) return null;
        return positiveOrNull(number(prev.get("target_price")));
    }

    /**
     * Internal stock ids.
     *
     * <p>Returns every id the row carries. One company can publish under two, only one of which
     * maps to a listed NSE symbol, so the caller tries them in order — taking the first blindly
     * resolves NALCO to a company called Ondeo Nalco.
     */
    static List<String> scids(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) return out;
        if (node.isArray()) {
            for (JsonNode n : node) {
                String s = text(n);
                if (s != null && !s.isBlank()) out.add(s.trim());
            }
            return out;
        }
        String s = text(node);
        if (s != null && !s.isBlank()) out.add(s.trim());
        return out;
    }

    // ------------------------------------------------------------------ coercion

    /** A number that may arrive as a JSON number or as a quoted string. */
    static Double number(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isNumber()) return node.asDouble();
        String s = text(node);
        if (s == null) return null;
        s = s.replace(",", "").trim();
        if (s.isEmpty() || "-".equals(s)) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double positiveOrNull(Double v) {
        return (v == null || v <= 0) ? null : v;
    }

    /** The feed's row id, which is numeric but quoted. Null rather than a guess if it is not. */
    static Long id(JsonNode node) {
        Double d = number(node);
        return d == null ? null : (long) (double) d;
    }

    static String text(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String s = node.isValueNode() ? node.asText() : null;
        if (s == null) return null;
        s = s.trim();
        return (s.isEmpty() || "-".equals(s)) ? null : s;
    }

    /**
     * An ISO date, or null.
     *
     * <p>Never today on failure. Filing an unparseable date as now would date a two-year-old call
     * to this morning and measure it over a window it never ran in (the rule {@code parseRssDate}
     * already follows on the headline path).
     */
    static LocalDate date(JsonNode node) {
        String s = text(node);
        if (s == null) return null;
        try {
            return LocalDate.parse(s, ISO);
        } catch (Exception e) {
            return null;
        }
    }
}
