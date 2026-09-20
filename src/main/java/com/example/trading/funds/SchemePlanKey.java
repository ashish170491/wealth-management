package com.example.trading.funds;

import java.util.Locale;

/**
 * Resolves a scheme row's <b>plan</b> (Direct / Regular) and <b>option</b> (Growth / IDCW) from
 * AMFI's columns, falling back to the scheme name, and <b>recording which one answered</b>.
 *
 * <p><b>Why this matters more than it looks.</b> Both fields carry a units error of the B-047
 * family (a quarter filed as a year), and they fail in two different directions:
 *
 * <ol>
 *   <li><b>Growth vs IDCW.</b> An IDCW scheme pays distributions out, so <em>its NAV is reduced by
 *       every payout</em>. A CAGR computed on an IDCW series therefore understates the fund badly,
 *       and setting it against a Growth series compares two things measured on different scales.
 *       Rule: this module measures <b>Growth only</b>; an IDCW row is {@code NOT_MEASURED}, never a
 *       low reading. AMFI spells the option at least eight ways - {@code Growth},
 *       {@code Growth Option}, {@code GROWTH}, {@code IDCW}, {@code IDCW Option},
 *       {@code Monthly IDCW}, {@code IDCW Payout},
 *       {@code IDCW (Income Distribution CUM Capital Withdrawal)} - plus blank.</li>
 *   <li><b>Direct vs Regular.</b> These are <em>one portfolio under two fee schedules</em>, not two
 *       funds. Two consequences. A peer set must hold <b>one plan per scheme</b> or every percentile
 *       runs over a doubled population in which each fund appears twice at two different returns.
 *       And the gap between the two NAV series is not one fund beating another - it <b>is the
 *       distributor commission</b>, which is the single most actionable number this module can
 *       show.</li>
 * </ol>
 *
 * <p><b>An unresolved plan is {@link Plan#UNSPECIFIED} and is excluded from the peer set, never
 * guessed into Regular.</b> Measured on the live file, 5,760 rows carry a blank Plan column - but
 * only 65 of those are equity (5,695 are debt and other), so within the categories this module
 * screens the columns are populated and the name fallback is a genuine edge case rather than the
 * common path.
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public final class SchemePlanKey {

    private SchemePlanKey() {}

    public enum Plan {
        DIRECT,
        REGULAR,
        /** Neither the column nor the name said. Excluded from peer sets - never defaulted. */
        UNSPECIFIED
    }

    public enum Option {
        GROWTH,
        IDCW,
        /** Neither the column nor the name said. Not measured - never assumed to be Growth. */
        UNSPECIFIED
    }

    /** Which source answered, so a reading can be traced rather than assumed (Gotcha 84). */
    public enum ResolvedFrom {
        COLUMN,
        SCHEME_NAME,
        UNRESOLVED
    }

    /**
     * @param plan       Direct / Regular / unspecified
     * @param option     Growth / IDCW / unspecified
     * @param planFrom   which source answered for the plan
     * @param optionFrom which source answered for the option
     */
    public record Resolved(Plan plan, Option option, ResolvedFrom planFrom, ResolvedFrom optionFrom) {

        /** The one combination this module measures: a Direct-plan Growth series. */
        public boolean isCanonicalForMeasurement() {
            return plan == Plan.DIRECT && option == Option.GROWTH;
        }

        /** True when either half could not be established, so the row must leave the peer set. */
        public boolean isIncomplete() {
            return plan == Plan.UNSPECIFIED || option == Option.UNSPECIFIED;
        }
    }

    /**
     * Resolve from AMFI's Plan and Option columns, falling back to the scheme name.
     *
     * <p>Never throws; every argument may be null or blank.
     */
    public static Resolved resolve(String planColumn, String optionColumn, String schemeName) {
        String name = upper(schemeName);

        Plan plan = planFrom(upper(planColumn));
        ResolvedFrom planFrom = plan == Plan.UNSPECIFIED ? ResolvedFrom.UNRESOLVED : ResolvedFrom.COLUMN;
        if (plan == Plan.UNSPECIFIED) {
            Plan fromName = planFrom(name);
            if (fromName != Plan.UNSPECIFIED) {
                plan = fromName;
                planFrom = ResolvedFrom.SCHEME_NAME;
            }
        }

        Option option = optionFrom(upper(optionColumn));
        ResolvedFrom optionFrom = option == Option.UNSPECIFIED ? ResolvedFrom.UNRESOLVED : ResolvedFrom.COLUMN;
        if (option == Option.UNSPECIFIED) {
            Option fromName = optionFrom(name);
            if (fromName != Option.UNSPECIFIED) {
                option = fromName;
                optionFrom = ResolvedFrom.SCHEME_NAME;
            }
        }

        return new Resolved(plan, option, planFrom, optionFrom);
    }

    private static Plan planFrom(String text) {
        if (text.isEmpty()) return Plan.UNSPECIFIED;
        if (text.contains("DIRECT")) return Plan.DIRECT;
        if (text.contains("REGULAR")) return Plan.REGULAR;
        return Plan.UNSPECIFIED;
    }

    /**
     * IDCW is tested <b>first and deliberately</b>. SEBI's own renaming spells the option
     * {@code "IDCW (Income Distribution CUM Capital Withdrawal)"}, and several AMCs write
     * {@code "IDCW Payout - Growth Option"} style labels - so a string can contain both words. A
     * row that mentions a distribution is a distributing series whatever else it says, and reading
     * it as Growth would file a payout-depressed NAV as a total-return one.
     *
     * <p>This is Gotcha 53's rule in the positive direction: before matching a keyword, check what
     * innocently contains it.
     */
    private static Option optionFrom(String text) {
        if (text.isEmpty()) return Option.UNSPECIFIED;
        if (text.contains("IDCW") || text.contains("DIVIDEND") || text.contains("PAYOUT")
                || text.contains("REINVEST")) {
            return Option.IDCW;
        }
        if (text.contains("GROWTH")) return Option.GROWTH;
        return Option.UNSPECIFIED;
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT).trim();
    }
}
