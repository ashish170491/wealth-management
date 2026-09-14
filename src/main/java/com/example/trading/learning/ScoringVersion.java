package com.example.trading.learning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A stable, short identifier for "the scoring configuration that produced this number"
 * (SPEC §38.1).
 *
 * <p><b>Why this exists.</b> Every score in {@code multibagger_scores} and every pick in
 * {@code recommendations} is a claim made by a particular version of the engine, but until
 * now the row recorded only the claim. That is fine while nothing changes and actively
 * misleading the moment something does: B-019 scaled every composite by 15% for months and
 * B-018 added a constant 6.5 to everyone, so a "63" from June and a "63" from September are
 * different measurements filed under the same name. Any accuracy figure, IC panel or future
 * training set that pools them is pooling incomparable observations.
 *
 * <p><b>Format:</b> {@code <engine><codeRevision>-<6 hex>}, e.g. {@code mb1-7a1c3e}. The
 * hash covers the score-affecting configuration; the code revision is bumped by hand when
 * the scoring <i>logic</i> changes in a way configuration cannot express.
 *
 * <p><b>Deliberately over-sensitive.</b> A parameter that only matters while a flag is on
 * (an inactive bonus's point value, say) still contributes to the hash. Versioning two runs
 * apart that in fact scored identically costs a needless split in a later analysis; treating
 * two genuinely different engines as one is unrecoverable, because there is no way to tell
 * afterwards which rows came from which. Prefer the recoverable error.
 *
 * <p>Pure — no Spring, no I/O, deterministic across JVMs (SHA-256, {@link Locale#ROOT}
 * number formatting). {@code ScoringVersionRegistry} is the wiring.
 */
public final class ScoringVersion {

    /**
     * Bump when multibagger scoring <b>logic</b> changes in a way the configuration hash
     * cannot see: a new dimension, a changed sub-score formula, a new bonus, a changed cap.
     * Configuration changes (weights, actionable flags, bonus sizes) are picked up
     * automatically and must NOT require a bump.
     */
    public static final int MULTIBAGGER_CODE_REVISION = 2;   // rev 2: Sector Tailwind removed (2026-09-03)

    /** Quantitative discovery has no configuration bean; its version tracks code only. */
    public static final int QUANT_DISCOVERY_CODE_REVISION = 1;

    /** Sector reversal likewise. Measured but not actionable (Gotcha 25). */
    public static final int SECTOR_REVERSAL_CODE_REVISION = 1;

    /**
     * Macro event exposure (SPEC 48). Bump when the <b>join</b> changes - a new verdict rule, a
     * changed precedence, a new lender or leverage adjustment. Changes to the exposure map file
     * itself are picked up automatically: its content hash is one of the version parameters, so
     * re-writing a rationale re-versions the readings that quoted it, which is the point.
     */
    public static final int MACRO_EVENT_CODE_REVISION = 1;

    /**
     * Stamped on rows written before versioning existed, or when the version cannot be
     * resolved. Never {@code null}-as-"probably current": a row whose provenance is unknown
     * must say so, or it will be pooled with rows whose provenance is known.
     */
    public static final String UNKNOWN = "unversioned";

    private ScoringVersion() {
    }

    /**
     * Build a version string from an engine prefix, a hand-maintained code revision, and the
     * ordered set of score-affecting parameters.
     *
     * @param engine     short engine prefix, e.g. {@code "mb"}
     * @param codeRevision the hand-maintained logic revision
     * @param params     ordered parameter map; iteration order is part of the hash input, so
     *                   pass a {@link LinkedHashMap} or {@link Map#of()} is NOT acceptable
     *                   (its order is unspecified)
     */
    public static String of(String engine, int codeRevision, Map<String, ?> params) {
        if (engine == null || engine.isBlank()) return UNKNOWN;
        StringBuilder canonical = new StringBuilder();
        if (params != null) {
            for (Map.Entry<String, ?> e : params.entrySet()) {
                canonical.append(e.getKey()).append('=').append(format(e.getValue())).append(';');
            }
        }
        return engine + codeRevision + "-" + shortHash(canonical.toString());
    }

    /**
     * Canonical rendering of a parameter value. Doubles are fixed to 4 decimals in
     * {@link Locale#ROOT} so a weight of 0.12 hashes the same on every machine and is not
     * re-versioned by floating-point noise below the fourth decimal — which is finer than
     * any weight this system sets.
     */
    private static String format(Object value) {
        if (value == null) return "null";
        if (value instanceof Double d) return String.format(Locale.ROOT, "%.4f", d);
        if (value instanceof Float f) return String.format(Locale.ROOT, "%.4f", f.doubleValue());
        return String.valueOf(value);
    }

    /** First 6 hex characters of the SHA-256 of the canonical string. */
    private static String shortHash(String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conformant JVM; unreachable in practice.
            return UNKNOWN;
        }
    }
}
