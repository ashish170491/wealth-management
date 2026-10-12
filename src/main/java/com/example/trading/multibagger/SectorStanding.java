package com.example.trading.multibagger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "How does this business compare with its own sector?" - the sector-relative lens (SPEC §54).
 *
 * <h2>Why this exists</h2>
 * Every quality bar in this app compares a stock with the <b>whole</b> universe: the compounding
 * lens (§41) asks for ROCE of 18%, earnings steadiness of 50, ROA of 1.5% for a lender. That is the
 * right question for "can this business compound?", and it is the wrong one for "is this a strong
 * business <em>of its kind</em>?". Measured on the 2026-10-10 run (300 stocks): capital goods fails
 * the steadiness bar on 33 of 40 (sector median 22.5, against 50 for IT), and banks fail the ROA bar
 * on 16 of 20 (sector median 1.1%). A capital-goods company whose steadiness is 45 reads "fails"
 * when it is steadier than three in four of its peers. Nothing on any screen could say that.
 *
 * <h2>It explains, it never rescues</h2>
 * Being the best business in a low-return sector does not make a business compound - it still has
 * to earn more than its cost of capital. So this reading sits <em>beside</em> the absolute lens and
 * never overrides it, the way §43 sits beside §41 (Gotcha 105: two different questions, so the
 * disagreement is explained rather than reconciled). It contributes <b>zero points</b> to any score,
 * is computed on read and never stored, and there is no flag that would change that.
 *
 * <h2>What it measures, and what it deliberately leaves out</h2>
 * Six figures already on every screening row: ROCE, ROE, ROA, cash conversion, debt-to-equity and
 * earnings steadiness. Two are left out on purpose:
 * <ul>
 *   <li><b>Gross margin</b> reads exactly 100.0 for 54 stocks that report no cost of goods (IT,
 *       services, financials) - a rank on it would rank the absence of a line item.</li>
 *   <li><b>P/E</b> is not persisted on the screening row, and the stored {@code peDeviation}
 *       carries a sector median that depends on the order stocks were screened in.</li>
 * </ul>
 *
 * <h2>Refusals</h2>
 * A metric that does not fit the business (ROCE, leverage or cash conversion for a lender) is
 * {@link Status#NOT_APPLICABLE}; a stock whose own figure is missing is {@link Status#NOT_MEASURED},
 * never "bottom of the sector"; a peer group under {@link #MIN_PEERS} measured stocks is
 * {@link Status#TOO_FEW_PEERS}, because a quartile among six is one stock wide (the §52.8 floor);
 * and a bucket listed in {@link #MIXED_BUCKETS} is {@link Status#MIXED_PEER_GROUP} (none since B-136
 * separated asset managers from lenders). Lenders and non-lenders never share a denominator, even
 * inside one sector.
 *
 * <p>Ranks are published as <b>quartiles</b>, not as 0-100 percentiles: with 10 to 40 peers that is
 * as fine a resolution as the data supports (the §52.13 rule - a band must be coarser than the
 * data behind it).
 */
public final class SectorStanding {

    private SectorStanding() {
    }

    /** Below this many measured peers a quartile is refused rather than published. */
    public static final int MIN_PEERS = 10;

    /** Fewer ranked checks than this and there is no summary verdict, only the per-check rows. */
    public static final int MIN_RANKED_FOR_SUMMARY = 3;

    /**
     * Buckets whose members are not comparable with each other until a known defect is fixed.
     * Empty since B-136: FINANCIALS used to be refused because asset managers and registrars were
     * judged as lenders. They are now non-lenders, so they and the lenders in the same bucket never
     * share a denominator, which is the split the refusal was waiting for. The mechanism stays for
     * the next bucket that needs it.
     */
    static final Map<String, String> MIXED_BUCKETS = Map.of();

    /**
     * Buckets the shared sector vocabulary folds together that are not one peer group for this
     * lens, split by the screening row's own industry (B-139). ENERGY holds regulated power
     * utilities (median debt-to-equity 1.47) and oil and gas (0.45): ranked together, a utility's
     * ordinary regulated leverage read as "weaker than most of its sector", and the same capital
     * intensity handed it the top quarter on cash conversion - one structural feature, two votes.
     * The split lives here and not in {@code SectorMapping}, because allocation drift reads that
     * vocabulary and moving it would move a portfolio figure for a display lens's sake.
     */
    static String peerGroup(String sector, String industry) {
        if (sector == null) return null;
        if (!"ENERGY".equals(sector)) return sector;
        String i = industry == null ? "" : industry.toLowerCase(Locale.ROOT);
        if (i.contains("power") || i.contains("utilit")) return "POWER";
        if (i.contains("oil") || i.contains("gas") || i.contains("energy") || i.contains("petro")) return "OIL_GAS";
        return sector; // an unrecognised energy business keeps the broad bucket, which will be small
    }

    /** The six figures the lens ranks. */
    public enum Metric {
        ROCE("Return on capital", true, Applies.NON_LENDER, "%"),
        ROA("Return on assets", true, Applies.LENDER, "%"),
        ROE("Return on equity", true, Applies.ALL, "%"),
        CASH_CONVERSION("Cash conversion", true, Applies.NON_LENDER, "x"),
        DEBT_TO_EQUITY("Debt to equity", false, Applies.NON_LENDER, "x"),
        STEADINESS("Earnings steadiness", true, Applies.ALL, "");

        final String label;
        final boolean higherIsBetter;
        final Applies applies;
        final String unit;

        Metric(String label, boolean higherIsBetter, Applies applies, String unit) {
            this.label = label;
            this.higherIsBetter = higherIsBetter;
            this.applies = applies;
            this.unit = unit;
        }

        public String label() {
            return label;
        }

        public boolean higherIsBetter() {
            return higherIsBetter;
        }

        boolean appliesTo(boolean lender) {
            return applies == Applies.ALL || (applies == Applies.LENDER) == lender;
        }
    }

    enum Applies { ALL, LENDER, NON_LENDER }

    /** Where the stock sits among its peers on one figure. "Top" always means "best". */
    public enum Quartile { TOP_QUARTILE, UPPER_MIDDLE, LOWER_MIDDLE, BOTTOM_QUARTILE }

    /** Why a figure was, or was not, ranked. */
    public enum Status { RANKED, NOT_APPLICABLE, NOT_MEASURED, TOO_FEW_PEERS, MIXED_PEER_GROUP, NOT_CLASSIFIED }

    /** The summary across the ranked figures. Counted, never averaged (Gotcha 103). */
    public enum Verdict {
        /**
         * In the better half of its sector on MORE than two in three ranked checks. Strict on
         * purpose: with the three checks a lender has, a non-strict cut-off made "middle"
         * unreachable - every bank read above or below (measured on the first live run).
         */
        ABOVE_PEERS,
        /** Neither mostly better nor mostly worse than its sector. */
        IN_LINE,
        /** In the better half of its sector on FEWER than one in three ranked checks. */
        BELOW_PEERS,
        /** Too few checks could be ranked to summarise. Never to be shown as a weak reading. */
        NOT_RANKED
    }

    /**
     * One screening row, reduced to what the lens reads. Every figure nullable - null means
     * "could not measure", never a neutral or zero value.
     *
     * @param sector the shared bucket ({@code SectorMapping.resolve}); null when unclassified
     * @param lender true when the business is a lender - {@code capexVerdict == NA_FINANCIAL}, the
     *               marker that actually fires (Gotcha 88)
     */
    public record Row(
            String symbol,
            String sector,
            boolean lender,
            Double rocePercent,
            Double roaPercent,
            Double roePercent,
            Double cashConversionRatio,
            Double debtToEquity,
            Integer earningsConsistencyScore) {

        Double value(Metric m) {
            return switch (m) {
                case ROCE -> rocePercent;
                case ROA -> roaPercent;
                case ROE -> roePercent;
                case CASH_CONVERSION -> cashConversionRatio;
                case DEBT_TO_EQUITY -> debtToEquity;
                case STEADINESS -> earningsConsistencyScore == null ? null : earningsConsistencyScore.doubleValue();
            };
        }
    }

    /** One figure, read against the sector. Quartile and median are null unless RANKED. */
    public record MetricReading(
            Metric metric,
            Status status,
            Double value,
            Double sectorMedian,
            Integer peers,
            Quartile quartile,
            String detail) {
    }

    /**
     * @param above       ranked checks on which the stock sits in the better half of its sector
     * @param ranked      checks that could be ranked - the denominator
     * @param peers       the largest peer group any check was ranked within; null when none
     * @param divergences sentences naming where this reading and the market-wide lens disagree
     */
    public record Result(
            String symbol,
            String sector,
            Verdict verdict,
            List<MetricReading> metrics,
            int above,
            int ranked,
            Integer peers,
            String reason,
            List<String> divergences) {
    }

    /**
     * The sorted, measured values per (sector, lender-or-not, metric). Built once per screening run
     * and reused for every stock in it.
     */
    public static final class CrossSection {
        private final Map<String, Map<Metric, double[]>> groups;
        private final int rows;

        private CrossSection(Map<String, Map<Metric, double[]>> groups, int rows) {
            this.groups = groups;
            this.rows = rows;
        }

        double[] values(String sector, boolean lender, Metric m) {
            Map<Metric, double[]> g = groups.get(key(sector, lender));
            return g == null ? null : g.get(m);
        }

        /** Rows the cross-section was built from - used as part of the cache key. */
        public int rows() {
            return rows;
        }
    }

    private static String key(String sector, boolean lender) {
        return sector + (lender ? "|L" : "|N");
    }

    /** Builds the per-sector distributions from one screening run. */
    public static CrossSection crossSection(Collection<Row> run) {
        Map<String, Map<Metric, List<Double>>> acc = new HashMap<>();
        int n = 0;
        for (Row r : run) {
            if (r == null) continue;
            n++;
            if (r.sector() == null) continue;
            Map<Metric, List<Double>> g = acc.computeIfAbsent(key(r.sector(), r.lender()),
                    k -> new EnumMap<>(Metric.class));
            for (Metric m : Metric.values()) {
                if (!m.appliesTo(r.lender())) continue;
                Double v = r.value(m);
                if (v == null || v.isNaN() || v.isInfinite()) continue;
                g.computeIfAbsent(m, k -> new ArrayList<>()).add(v);
            }
        }
        Map<String, Map<Metric, double[]>> out = new HashMap<>();
        acc.forEach((k, metrics) -> {
            Map<Metric, double[]> sorted = new EnumMap<>(Metric.class);
            metrics.forEach((m, list) -> {
                double[] a = list.stream().mapToDouble(Double::doubleValue).toArray();
                Arrays.sort(a);
                sorted.put(m, a);
            });
            out.put(k, sorted);
        });
        return new CrossSection(out, n);
    }

    /** Reads one stock against its sector, without the market-wide lens beside it. */
    public static Result evaluate(Row row, CrossSection cs) {
        return evaluate(row, cs, null);
    }

    /**
     * Reads one stock against its sector.
     *
     * @param compounding the market-wide lens for the same row, or null. Used only to name where
     *                    the two readings disagree; it never changes a quartile or the verdict.
     */
    public static Result evaluate(Row row, CrossSection cs, CompoundingQuality.Result compounding) {
        if (row == null) return null;
        String sector = row.sector();
        List<MetricReading> readings = new ArrayList<>();

        if (sector == null) {
            for (Metric m : Metric.values()) {
                if (!m.appliesTo(row.lender())) continue;
                readings.add(new MetricReading(m, Status.NOT_CLASSIFIED, row.value(m), null, null, null,
                        "This stock's sector is not classified, so there is no peer group to compare with."));
            }
            return new Result(row.symbol(), null, Verdict.NOT_RANKED, readings, 0, 0, null,
                    "Not ranked: this stock's sector is not classified.", List.of());
        }

        String mixed = MIXED_BUCKETS.get(sector);
        int above = 0;
        int ranked = 0;
        Integer widest = null;
        for (Metric m : Metric.values()) {
            Double v = row.value(m);
            if (!m.appliesTo(row.lender())) {
                readings.add(new MetricReading(m, Status.NOT_APPLICABLE, v, null, null, null,
                        notApplicableText(m, row.lender())));
                continue;
            }
            if (mixed != null) {
                readings.add(new MetricReading(m, Status.MIXED_PEER_GROUP, v, null, null, null, mixed));
                continue;
            }
            if (v == null || v.isNaN() || v.isInfinite()) {
                readings.add(new MetricReading(m, Status.NOT_MEASURED, null, null, null, null,
                        m.label + " is not on record for this stock, so it is not ranked."));
                continue;
            }
            double[] peers = cs == null ? null : cs.values(sector, row.lender(), m);
            int n = peers == null ? 0 : peers.length;
            if (n < MIN_PEERS) {
                readings.add(new MetricReading(m, Status.TOO_FEW_PEERS, v, null, n, null,
                        "Only " + n + " " + peerNoun(sector, row.lender()) + " report " + m.label.toLowerCase(Locale.ROOT)
                                + " - too few to rank among (at least " + MIN_PEERS + " needed)."));
                continue;
            }
            Quartile q = quartile(v, peers, m.higherIsBetter);
            double median = median(peers);
            readings.add(new MetricReading(m, Status.RANKED, v, median, n, q,
                    rankedText(m, v, median, n, q, sector, row.lender())));
            ranked++;
            if (q == Quartile.TOP_QUARTILE || q == Quartile.UPPER_MIDDLE) above++;
            widest = widest == null ? n : Math.max(widest, n);
        }

        Verdict verdict;
        String reason;
        if (mixed != null) {
            verdict = Verdict.NOT_RANKED;
            reason = "Not ranked. " + mixed;
        } else if (ranked < MIN_RANKED_FOR_SUMMARY) {
            verdict = Verdict.NOT_RANKED;
            reason = ranked == 0
                    ? "Not ranked: no check could be compared with enough " + peerNoun(sector, row.lender()) + "."
                    : "Only " + ranked + " check" + (ranked == 1 ? "" : "s")
                            + " could be ranked - too few to summarise (at least " + MIN_RANKED_FOR_SUMMARY + ").";
        } else if (above * 3 > ranked * 2) {
            verdict = Verdict.ABOVE_PEERS;
            reason = "In the better half of its " + label(sector) + " peers on " + above + " of " + ranked + " checks.";
        } else if (above * 3 < ranked) {
            verdict = Verdict.BELOW_PEERS;
            reason = "In the better half of its " + label(sector) + " peers on only " + above + " of " + ranked + " checks.";
        } else {
            verdict = Verdict.IN_LINE;
            reason = "In the better half of its " + label(sector) + " peers on " + above + " of " + ranked
                    + " checks - neither mostly stronger nor mostly weaker.";
        }

        return new Result(row.symbol(), sector, verdict, List.copyOf(readings), above, ranked, widest, reason,
                divergences(readings, compounding, sector, row.lender()));
    }

    /**
     * Position by <b>mid-rank</b>: the peers strictly worse, plus half of the <em>other</em> peers
     * tied with this stock, over everyone else. Two stocks with the same figure always land
     * together, in the middle of their tie.
     *
     * <p>The first version counted only peers strictly worse, which put a tied group at the
     * <em>bottom</em> of its tie while this comment claimed the opposite (B-138): TCS, debt-free
     * alongside six other debt-free IT companies, read "upper middle" on debt. The literal
     * alternative - ties share the better quartile - was rejected too: 12 of 40 capital-goods
     * companies sit tied at the steadiness floor of 0, and lifting all of them out of the bottom
     * quarter would flatter exactly the erratic businesses the floor describes.
     *
     * @param sortedPeers the peer group's figures, this stock's own included
     */
    static Quartile quartile(double v, double[] sortedPeers, boolean higherIsBetter) {
        int n = sortedPeers.length;
        int worse = 0;
        int equal = 0;
        for (double p : sortedPeers) {
            if (p == v) equal++;
            else if (higherIsBetter ? p < v : p > v) worse++;
        }
        int otherTies = Math.max(0, equal - 1); // the stock's own figure is in the peer list
        double share = n <= 1 ? 1.0 : (worse + otherTies / 2.0) / (n - 1);
        if (share >= 0.75) return Quartile.TOP_QUARTILE;
        if (share >= 0.50) return Quartile.UPPER_MIDDLE;
        if (share >= 0.25) return Quartile.LOWER_MIDDLE;
        return Quartile.BOTTOM_QUARTILE;
    }

    static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    /**
     * Which compounding gate asks the same question as which figure. ROE has no gate of its own,
     * and gross margin is not ranked, so neither ever produces a divergence.
     */
    private static String gateFor(Metric m, boolean lender) {
        return switch (m) {
            case ROCE -> lender ? null : "capitalReturn";
            case ROA -> lender ? "capitalReturn" : null;
            case CASH_CONVERSION -> "realCash";
            case DEBT_TO_EQUITY -> "internallyFunded";
            case STEADINESS -> "steady";
            case ROE -> null;
        };
    }

    private static List<String> divergences(List<MetricReading> readings, CompoundingQuality.Result compounding,
                                            String sector, boolean lender) {
        if (compounding == null || compounding.gates() == null) return List.of();
        Map<String, CompoundingQuality.GateStatus> gates = new HashMap<>();
        for (CompoundingQuality.Gate g : compounding.gates()) gates.put(g.key(), g.status());

        List<String> out = new ArrayList<>();
        for (MetricReading r : readings) {
            if (r.status() != Status.RANKED) continue;
            String gate = gateFor(r.metric(), lender);
            CompoundingQuality.GateStatus gs = gate == null ? null : gates.get(gate);
            if (gs == CompoundingQuality.GateStatus.FAIL && r.quartile() == Quartile.TOP_QUARTILE) {
                out.add(r.metric().label + ": misses the market-wide bar, but is in the top quarter of its "
                        + r.peers() + " " + label(sector) + " peers. Strong for its kind is not the same as "
                        + "compounding - the bar stays where it is.");
            } else if (gs == CompoundingQuality.GateStatus.PASS && r.quartile() == Quartile.BOTTOM_QUARTILE) {
                out.add(r.metric().label + ": clears the market-wide bar, but is in the bottom quarter of its "
                        + r.peers() + " " + label(sector) + " peers - its sector does better than this as a rule.");
            }
        }
        return List.copyOf(out);
    }

    private static String rankedText(Metric m, double v, double median, int n, Quartile q, String sector, boolean lender) {
        String where = switch (q) {
            case TOP_QUARTILE -> "top quarter";
            case UPPER_MIDDLE -> "upper middle";
            case LOWER_MIDDLE -> "lower middle";
            case BOTTOM_QUARTILE -> "bottom quarter";
        };
        String dir = m.higherIsBetter ? "" : " (lower is better)";
        return m.label + " " + fmt(v, m) + " against a median of " + fmt(median, m) + " across " + n + " "
                + peerNoun(sector, lender) + dir + " - " + where + ".";
    }

    private static String notApplicableText(Metric m, boolean lender) {
        if (lender) {
            return switch (m) {
                case ROCE -> "Not used for a lender - borrowing is its raw material, so return on assets is the measure.";
                case DEBT_TO_EQUITY -> "Not used for a lender - its debt is the business, not a risk taken on top of it.";
                case CASH_CONVERSION -> "Not used for a lender - its operating cash flow moves with lending, not with profit quality.";
                default -> m.label + " does not apply to this business.";
            };
        }
        return m == Metric.ROA
                ? "Return on assets is the lender's measure; return on capital is used for this business."
                : m.label + " does not apply to this business.";
    }

    private static String fmt(double v, Metric m) {
        return switch (m.unit) {
            case "%" -> String.format(Locale.ROOT, "%.1f%%", v);
            case "x" -> String.format(Locale.ROOT, "%.2f", v);
            default -> String.format(Locale.ROOT, "%.0f", v);
        };
    }

    private static String peerNoun(String sector, boolean lender) {
        return label(sector) + (lender ? " lenders" : " peers");
    }

    /** The bucket as a reader would write it: "capital goods", but "IT" and "FMCG". */
    static String label(String sector) {
        if (sector == null) return "unclassified";
        if ("OIL_GAS".equals(sector)) return "oil and gas";
        if (sector.length() <= 4) return sector;
        return sector.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    /** Every enum constant name, for the no-instruction-to-transact vocabulary test. */
    static Set<String> vocabulary() {
        java.util.TreeSet<String> s = new java.util.TreeSet<>();
        for (Verdict v : Verdict.values()) s.add(v.name());
        for (Quartile q : Quartile.values()) s.add(q.name());
        for (Status st : Status.values()) s.add(st.name());
        for (Metric m : Metric.values()) s.add(m.label);
        return s;
    }
}
