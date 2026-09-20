package com.example.trading.portfolio.goal;

/**
 * What equity and debt actually did over a stretch of a given length (SPEC §53.2).
 *
 * <p>A port, implemented in the funds package, so the dependency points one way exactly as
 * {@code FundAllocationSource} does: funds know about the portfolio, the portfolio does not know
 * about funds. The goal planner must also work with no implementation at all, in which case the
 * mix is still published and the evidence beside it reads <em>not measured</em> - which is the
 * honest state for an app whose fund archive has not been filled yet, and is not the same thing as
 * the evidence saying the mix is safe.
 */
public interface HorizonEvidenceSource {

    /**
     * @param measured           false when the archive cannot answer for this length of window
     * @param worstEquityPct     the worst window's median across every actively managed equity
     *                           scheme, as a percentage over the whole window - <b>not</b>
     *                           annualised, and a floor on the badness rather than a ceiling
     * @param worstDebtPct       the same across every debt scheme
     * @param medianEquityPct    the middle window, so the worst has something to be worse than
     * @param independentWindows non-overlapping windows of this length on file - the sample size,
     *                           never the count of overlapping windows (Gotcha 92)
     */
    record Request(int months, double equityPct) {}

    record Evidence(
            boolean measured,
            int windowMonths,
            Double worstEquityPct,
            Double worstDebtPct,
            /**
             * The worst window for the <b>blend</b>, walked as a real path rather than assembled
             * from the two legs' separate worst months - which describe different months and so
             * describe a portfolio nobody held.
             */
            Double worstOnMixPct,
            Double equityPctUsed,
            Double medianEquityPct,
            Double medianDebtPct,
            Double equityShareNegativePct,
            Integer independentWindows,
            Integer windowsMeasured,
            String archiveFrom,
            String archiveTo,
            String reason,
            String caveat) {

        /** The state to return when there is no fund archive at all. */
        public static Evidence notMeasured(int windowMonths, String reason) {
            return new Evidence(false, windowMonths, null, null, null, null, null, null, null,
                    null, null, null, null, reason, null);
        }
    }

    /** Evidence over windows of {@code months} for a pot split {@code equityPct} in equity. */
    Evidence forHorizon(int months, double equityPct);

    /**
     * The same for several window lengths at once, so the archive is read <b>once</b>.
     *
     * <p>Not an optimisation but a correctness guard of the B-115 kind: a portfolio with six goals
     * would otherwise make twelve bulk reads over most of the NAV table on every page load, and the
     * cost would grow every week the archive deepens with nothing failing to mark the moment it
     * stopped being fast enough. The default implementation is the honest one for a source that
     * has no cheaper path.
     */
    default java.util.Map<Integer, Evidence> forHorizons(java.util.Collection<Request> requests) {
        java.util.Map<Integer, Evidence> out = new java.util.LinkedHashMap<>();
        for (Request r : requests) {
            if (r != null) {
                out.put(r.months(), forHorizon(r.months(), r.equityPct()));
            }
        }
        return out;
    }
}
