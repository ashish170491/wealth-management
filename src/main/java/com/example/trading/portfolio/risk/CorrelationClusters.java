package com.example.trading.portfolio.risk;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Correlation clusters across holdings (SPEC 7.2) - pure, no repository, no clock.
 *
 * <p>Listed as a required metric since the portfolio modules shipped and never computed. Sector
 * concentration cannot see it: three pharma names can move as one, and so can a solar maker and a
 * capital-goods name riding the same order cycle while sitting in different sectors. Two holdings
 * are linked when their daily returns over the lookback correlate at or above the threshold; a
 * cluster is a connected group of links, and one carrying more than the configured share of the
 * book is reported.
 *
 * <p>Three refusals. A pair with fewer than {@link #MIN_COMMON_RETURNS} common daily returns is not
 * linked and is counted as unmeasured - a correlation from a fortnight is noise. A holding with no
 * price history is reported, never assumed uncorrelated. And nothing here says to sell anything:
 * a cluster is a fact about how the book moves, worded as such.
 */
public final class CorrelationClusters {

    /** Fewest common daily returns a pair needs before its correlation is read at all. */
    public static final int MIN_COMMON_RETURNS = 40;

    private CorrelationClusters() {
    }

    /**
     * @param members      the holdings in the cluster, heaviest first
     * @param weightPercent their combined share of the portfolio
     * @param averageCorrelation mean pairwise correlation of the linked pairs inside it
     */
    public record Cluster(List<String> members, double weightPercent, double averageCorrelation) {
    }

    /**
     * @param clusters            every cluster of two or more holdings, heaviest first
     * @param pairsMeasured       pairs with enough common history to read
     * @param pairsUnmeasured     pairs without it
     * @param symbolsWithoutHistory holdings with too few prices to pair at all
     */
    public record Result(List<Cluster> clusters, int pairsMeasured, int pairsUnmeasured,
                         List<String> symbolsWithoutHistory) {
    }

    /**
     * @param closes    daily closes per holding, keyed by date
     * @param weights   portfolio weight per holding, percent
     * @param threshold pairwise correlation at or above which two holdings are linked
     */
    public static Result compute(Map<String, NavigableMap<LocalDate, Double>> closes,
                                 Map<String, Double> weights, double threshold) {
        List<String> symbols = new ArrayList<>(weights.keySet());
        symbols.sort(Comparator.naturalOrder());
        Map<String, Map<LocalDate, Double>> returns = new HashMap<>();
        List<String> noHistory = new ArrayList<>();
        for (String s : symbols) {
            Map<LocalDate, Double> r = dailyReturns(closes.get(s));
            if (r.size() < MIN_COMMON_RETURNS) noHistory.add(s);
            returns.put(s, r);
        }

        int n = symbols.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        Map<Long, Double> linked = new HashMap<>();
        int measured = 0, unmeasured = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Double c = correlation(returns.get(symbols.get(i)), returns.get(symbols.get(j)));
                if (c == null) {
                    unmeasured++;
                    continue;
                }
                measured++;
                if (c >= threshold) {
                    union(parent, i, j);
                    linked.put(((long) i << 32) | j, c);
                }
            }
        }

        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        List<Cluster> clusters = new ArrayList<>();
        for (List<Integer> g : groups.values()) {
            if (g.size() < 2) continue;
            double weight = 0, sumC = 0;
            int links = 0;
            List<String> members = new ArrayList<>();
            for (int a : g) {
                members.add(symbols.get(a));
                weight += weights.getOrDefault(symbols.get(a), 0.0);
                for (int b : g) {
                    if (b <= a) continue;
                    Double c = linked.get(((long) a << 32) | b);
                    if (c != null) {
                        sumC += c;
                        links++;
                    }
                }
            }
            members.sort(Comparator.comparingDouble((String s) -> -weights.getOrDefault(s, 0.0)));
            clusters.add(new Cluster(members, weight, links == 0 ? 0 : sumC / links));
        }
        clusters.sort(Comparator.comparingDouble((Cluster c) -> -c.weightPercent()));
        return new Result(clusters, measured, unmeasured, noHistory);
    }

    static Map<LocalDate, Double> dailyReturns(NavigableMap<LocalDate, Double> closes) {
        Map<LocalDate, Double> out = new TreeMap<>();
        if (closes == null) return out;
        Map.Entry<LocalDate, Double> prev = null;
        for (Map.Entry<LocalDate, Double> e : closes.entrySet()) {
            // A price of 0 is "no price", never a quote (Gotcha 22).
            if (e.getValue() == null || e.getValue() <= 0) {
                prev = null;
                continue;
            }
            if (prev != null) out.put(e.getKey(), e.getValue() / prev.getValue() - 1.0);
            prev = e;
        }
        return out;
    }

    /** Pearson over the dates both series share; null below {@link #MIN_COMMON_RETURNS}. */
    static Double correlation(Map<LocalDate, Double> a, Map<LocalDate, Double> b) {
        if (a == null || b == null) return null;
        List<double[]> xy = new ArrayList<>();
        for (Map.Entry<LocalDate, Double> e : a.entrySet()) {
            Double y = b.get(e.getKey());
            if (y != null) xy.add(new double[]{e.getValue(), y});
        }
        if (xy.size() < MIN_COMMON_RETURNS) return null;
        double mx = 0, my = 0;
        for (double[] p : xy) {
            mx += p[0];
            my += p[1];
        }
        mx /= xy.size();
        my /= xy.size();
        double sxy = 0, sxx = 0, syy = 0;
        for (double[] p : xy) {
            sxy += (p[0] - mx) * (p[1] - my);
            sxx += (p[0] - mx) * (p[0] - mx);
            syy += (p[1] - my) * (p[1] - my);
        }
        if (sxx <= 1e-18 || syy <= 1e-18) return null;
        return sxy / Math.sqrt(sxx * syy);
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        p[find(p, a)] = find(p, b);
    }
}
