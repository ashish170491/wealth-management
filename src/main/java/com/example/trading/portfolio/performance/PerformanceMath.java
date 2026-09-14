package com.example.trading.portfolio.performance;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The pure arithmetic behind "is my money actually growing?" (SPEC §46.1). No I/O, no clock.
 *
 * <h2>Why a time-weighted return</h2>
 * The headline "total gain" is unrealised P&amp;L over cost, and it cannot move when money is
 * added or withdrawn. On the live portfolio it read +17.3% on 19 February and +17.3% on 9
 * September while invested capital fell by a tenth: seven flat months, invisible. A
 * time-weighted return (TWR) chain-links each day's return with that day's external cash flow
 * removed, so it measures what the <em>holdings</em> did, and it is what an index can be
 * compared with.
 *
 * <h2>What the flow is, and what it is not</h2>
 * The only daily record the app keeps is cost basis and market value per holding. A change in
 * cost basis is a purchase (cash in) or a sale <em>at cost</em> (cash out at the price paid, not
 * the price received). For a sale that leaves out the realised gain, so where a lot-matched sale
 * is on record for the day, its realised gain is added to the outflow. Where it is not, the
 * day's return is understated by that gain. The caveat is carried on the response rather than
 * hidden, because a figure whose method is unstated is a figure the reader cannot disagree with.
 *
 * <h2>Refusals</h2>
 * Fewer than two snapshots is no return. A day whose opening value is zero is skipped, never
 * divided by. Annualising a span under {@link #MIN_DAYS_TO_ANNUALISE} days is refused: a
 * three-week return raised to the power of 17 is not an annual figure, it is noise with a
 * percent sign.
 */
public final class PerformanceMath {

    private PerformanceMath() {}

    /** Below this span the annualised figure is withheld (Gotcha 21 for a rate). */
    public static final int MIN_DAYS_TO_ANNUALISE = 90;

    /** One portfolio-level day: cost basis and market value at the close. */
    public record Day(LocalDate date, double invested, double value) {
    }

    /**
     * @param twrPercent       cumulative time-weighted return over the span, null when fewer
     *                         than two usable days
     * @param annualisedPercent null when the span is too short to annualise honestly
     * @param daysSpanned      calendar days from first to last snapshot
     * @param flowsCorrected   how many days carried a lot-matched realised gain in their flow
     * @param flowsUncorrected days where the cost basis fell with no sale on record (the gain
     *                         on those days is not in the return)
     */
    public record TwrResult(Double twrPercent, Double annualisedPercent, int snapshots,
                            long daysSpanned, int flowsCorrected, int flowsUncorrected,
                            List<IndexPoint> indexed) {
    }

    /** The chain-linked series rebased to 100 at the first snapshot, for the chart. */
    public record IndexPoint(LocalDate date, double index) {
    }

    /**
     * @param realisedGainByDate realised gain from lot-matched sales, keyed by sell date; may
     *                           be empty. Used to correct the outflow on days the cost basis
     *                           fell, so a profitable sale is not read as a loss.
     */
    public static TwrResult timeWeighted(List<Day> days, Map<LocalDate, Double> realisedGainByDate) {
        if (days == null || days.size() < 2) {
            return new TwrResult(null, null, days == null ? 0 : days.size(), 0, 0, 0, List.of());
        }
        double growth = 1.0;
        int corrected = 0;
        int uncorrected = 0;
        List<IndexPoint> indexed = new ArrayList<>();
        Day prev = days.get(0);
        indexed.add(new IndexPoint(prev.date(), 100.0));
        int usable = 1;
        for (int i = 1; i < days.size(); i++) {
            Day d = days.get(i);
            if (prev.value() <= 0) {
                prev = d;
                indexed.add(new IndexPoint(d.date(), round2(growth * 100.0)));
                continue;
            }
            double flow = d.invested() - prev.invested();
            if (flow < -0.005) {
                Double gain = realisedGainByDate == null ? null : realisedGainByDate.get(d.date());
                if (gain != null) {
                    flow -= gain;   // cash actually left at cost + gain
                    corrected++;
                } else {
                    uncorrected++;
                }
            }
            double r = (d.value() - prev.value() - flow) / prev.value();
            growth *= (1.0 + r);
            usable++;
            indexed.add(new IndexPoint(d.date(), round2(growth * 100.0)));
            prev = d;
        }
        if (usable < 2) {
            return new TwrResult(null, null, days.size(), 0, corrected, uncorrected, indexed);
        }
        long span = ChronoUnit.DAYS.between(days.get(0).date(), days.get(days.size() - 1).date());
        double twr = (growth - 1.0) * 100.0;
        Double annualised = annualise(twr, span);
        return new TwrResult(round2(twr), annualised, days.size(), span, corrected, uncorrected, indexed);
    }

    /** Compound annual rate for a cumulative percentage over {@code days}; null when too short. */
    public static Double annualise(Double cumulativePercent, long days) {
        if (cumulativePercent == null || days < MIN_DAYS_TO_ANNUALISE) return null;
        double g = 1.0 + cumulativePercent / 100.0;
        if (g <= 0) return null;
        return round2((Math.pow(g, 365.0 / days) - 1.0) * 100.0);
    }

    /** Point-to-point percentage move; null when either leg is missing or non-positive. */
    public static Double pctChange(Double from, Double to) {
        if (from == null || to == null || from <= 0 || to <= 0) return null;
        return round2((to - from) / from * 100.0);
    }

    /**
     * @param maxDrawdownPercent deepest peak-to-trough fall in the series, as a negative number
     * @param currentDrawdownPercent distance of the last value below the running peak (0 at a
     *                               new high), as a negative number
     */
    public record Drawdown(Double maxDrawdownPercent, LocalDate maxDrawdownTrough, LocalDate peakDate,
                           Double currentDrawdownPercent, double peakValue) {
    }

    /**
     * Drawdown on the <em>index</em> series, not on raw market value. Raw value falls when the
     * investor withdraws money, which is not a drawdown; the chain-linked index has the flows
     * removed. Null for an empty series.
     */
    public static Drawdown drawdown(List<IndexPoint> indexed) {
        if (indexed == null || indexed.isEmpty()) return null;
        double peak = Double.NEGATIVE_INFINITY;
        LocalDate peakDate = null;
        LocalDate runningPeakDate = null;
        double worst = 0.0;
        LocalDate worstDate = null;
        for (IndexPoint p : indexed) {
            if (p.index() > peak) {
                peak = p.index();
                runningPeakDate = p.date();
            }
            double dd = peak > 0 ? (p.index() - peak) / peak * 100.0 : 0.0;
            if (dd < worst) {
                worst = dd;
                worstDate = p.date();
                peakDate = runningPeakDate;
            }
        }
        IndexPoint last = indexed.get(indexed.size() - 1);
        double current = peak > 0 ? (last.index() - peak) / peak * 100.0 : 0.0;
        return new Drawdown(round2(worst), worstDate, peakDate == null ? runningPeakDate : peakDate,
                round2(current), round2(peak));
    }

    /** Rebases a close series to 100 at its first point. Empty in, empty out. */
    public static List<IndexPoint> rebase(List<Map.Entry<LocalDate, Double>> closes) {
        List<IndexPoint> out = new ArrayList<>();
        if (closes == null || closes.isEmpty()) return out;
        double first = closes.get(0).getValue();
        if (first <= 0) return out;
        for (Map.Entry<LocalDate, Double> e : closes) {
            out.add(new IndexPoint(e.getKey(), round2(e.getValue() / first * 100.0)));
        }
        return out;
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
