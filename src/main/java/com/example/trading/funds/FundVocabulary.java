package com.example.trading.funds;

/**
 * Every word this module is allowed to say about a fund (SPEC §52.8).
 *
 * <p><b>The vocabulary is the design.</b> Each of these is a past-tense fact about a measured
 * series, never a noun about a fund: {@code AHEAD_OF_BENCHMARK}, not {@code OUTPERFORMER}. The
 * difference is not stylistic. "This fund was ahead of its benchmark in 31 of 44 windows" is
 * checkable and expires; "this is an outperformer" is a property of the fund that the reader
 * carries into next year, which is the belief the whole module exists to avoid manufacturing.
 *
 * <p>Nothing here contains an instruction to transact, and {@code FundVocabularyTest} asserts that
 * by reflection over every constant and label, <b>matching on word boundaries</b>. The word
 * boundary is load-bearing: the §51 test's first draft failed on <em>"whoever sells the servers"</em>
 * (Gotcha 53), and the same trap waits here in a much more natural form - a fund <em>holds</em>
 * securities, and {@code HOLD} is an instruction.
 *
 * <p><b>Four states, never two</b> (Gotcha 121, SPEC §52.2). Across all four enums the same
 * discipline applies:
 *
 * <ul>
 *   <li><b>NOT_MEASURED</b> - this app has no data. Its own gap.</li>
 *   <li><b>NOT_APPLICABLE</b> - the question does not fit this thing.</li>
 *   <li><b>NOT_COMPARABLE</b> - measured perfectly well, with nothing to grade it against. This is
 *       the one that is usually missing, and collapsing it into NOT_MEASURED throws away a fund's
 *       own good record because this app has not loaded an index.</li>
 *   <li><b>TOO_FEW_PEERS</b> - the same idea for a cross-section. Contra has <b>four</b> schemes in
 *       the live file; a percentile over four is a number, not a standing.</li>
 * </ul>
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public final class FundVocabulary {

    private FundVocabulary() {}

    /** How a fund's record read against whatever it was measured against. */
    public enum FundRecordVerdict {
        /** Cleared the bar in at least the required share of rolling windows. */
        AHEAD_OF_BENCHMARK("Ahead in most windows"),
        MIXED_RECORD("Mixed record"),
        BEHIND_BENCHMARK("Behind in most windows"),
        /** Measured, but this app has nothing appropriate to grade it against. Not a gap in data. */
        NOT_COMPARABLE("Nothing to compare against"),
        /** Too little history to say anything at all. */
        NOT_MEASURED("Not measured");

        private final String label;

        FundRecordVerdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Where a fund sat inside its own SEBI category over the same window. */
    public enum CategoryStanding {
        UPPER_QUARTILE("Top quarter of its category"),
        UPPER_HALF("Upper half of its category"),
        LOWER_HALF("Lower half of its category"),
        LOWER_QUARTILE("Bottom quarter of its category"),
        /** Below the peer floor. A ranking inside a handful of schemes is arithmetic, not standing. */
        TOO_FEW_PEERS("Too few peers to rank"),
        NOT_MEASURED("Not measured");

        private final String label;

        CategoryStanding(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * What the plan a position is held under costs, measured rather than looked up.
     *
     * <p>AMFI publishes no machine-readable expense ratio - the page is a React shell with per-AMC
     * downloads and no endpoint. But the same scheme publishes <b>two NAV series over one identical
     * portfolio</b>, and the gap between their compound rates <em>is</em> the distributor
     * commission. That is the B-018 move: NSE walled the quote endpoint, so the price-to-earnings
     * ratio was computed from filings instead of abandoned.
     */
    public enum PlanCostVerdict {
        /** Already the Direct plan - there is no distributor commission to measure. */
        DIRECT_PLAN("Direct plan"),
        /** Regular plan, and its Direct twin exists, so the annual drag is measured. */
        REGULAR_PLAN_MEASURED("Regular plan, drag measured"),
        /** Regular plan with no Direct twin on file - reported as unmeasured, never as zero drag. */
        REGULAR_PLAN_NO_TWIN("Regular plan, no Direct twin on file"),
        NOT_APPLICABLE("Not applicable"),
        NOT_MEASURED("Not measured");

        private final String label;

        PlanCostVerdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Whether this app has an index it can honestly measure a category against (SPEC §52.8).
     *
     * <p><b>Only two categories and one blend are answerable today</b>, and that is the headline
     * rather than a footnote - it is what forces the category-relative reading to be the primary
     * path and the benchmark reading to be the exception.
     */
    public enum BenchmarkAvailability {
        /** The category's own index is already stored, e.g. Nifty Midcap 150 for Mid Cap. */
        HELD_EXACT("Index held"),
        /** A near neighbour, named on screen, e.g. Nifty 50 standing in for Nifty 100. */
        HELD_PROXY("Near-neighbour index, named"),
        /** A stated blend of two held indices, with the weights shown. */
        HELD_BLEND("Blend of two held indices"),
        /**
         * The right index exists and this app has not loaded it - Nifty 500, Smallcap 250. A gap in
         * this app, and fixable. Substituting a midcap index for a small-cap category would be
         * B-047 in index clothing.
         */
        NOT_HELD("Index not loaded here"),
        /**
         * There is no single index for the category. ~1,000 sectoral and thematic schemes each
         * track a different one, so this is a property of the question and is permanently refused,
         * not deferred.
         */
        NOT_SINGULAR("No single index for this category"),
        /** Index funds, ETFs, debt and hybrid - judged on a different question entirely. */
        OUT_OF_SCOPE("Different question");

        private final String label;

        BenchmarkAvailability(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** True when a benchmark comparison may be published at all. */
        public boolean isAnswerable() {
            return this == HELD_EXACT || this == HELD_PROXY || this == HELD_BLEND;
        }
    }
}
