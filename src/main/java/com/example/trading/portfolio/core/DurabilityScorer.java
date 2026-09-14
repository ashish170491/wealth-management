package com.example.trading.portfolio.core;

import com.example.trading.portfolio.core.CoreDto.AnnualYear;
import com.example.trading.portfolio.core.CoreDto.Durability;
import com.example.trading.portfolio.core.CoreDto.DurabilityComponent;
import com.example.trading.portfolio.core.CoreDto.HoldingEvidence;
import com.example.trading.portfolio.core.CoreDto.PricePoint;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Durability score (SPEC §35.3) — "based on past data", the question's own words.
 *
 * <p>Five components worth up to 20 points each, <b>renormalised over the components that could
 * actually be measured</b>. A stock with two measured components is scored out of 40 and reported
 * as "2 of 5 measured", not scored out of 100 with three silent zeros. Below
 * {@code durability-min-components} the score is {@code null}: a number derived from one component
 * is a worse answer than an honest absence, because it looks like the same kind of number as a
 * fully-measured one.
 *
 * <p>This class is pure — no repositories, no clock beyond the {@code today} it is handed — so
 * every rule below is pinned by {@code DurabilityScoreTest}.
 */
@Component
public class DurabilityScorer {

    private final CoreHoldingConfig config;

    public DurabilityScorer(CoreHoldingConfig config) {
        this.config = config;
    }

    private static final int MAX_PER_COMPONENT = 20;

    /** D1 for financials: ROA is the headline metric for a bank, ROCE on equity is not. */
    private static final double FINANCIAL_ROA_PERSISTENCE_PERCENT = 1.2;

    /** D2 bonus: sales compounding at or above this, with profit keeping pace. */
    private static final double D2_SALES_CAGR_PERCENT = 10.0;

    /** D3: debt-to-equity at or below this counts the company as effectively debt-free. */
    private static final double DEBT_FREE_DE = 0.05;

    /** D3: leverage is "flat or falling" if it did not rise by more than this over three years. */
    private static final double DE_FLAT_TOLERANCE = 0.05;

    /** D3: share count compounding faster than this is dilution. */
    private static final double DILUTION_CAGR_PERCENT = 2.0;

    /** D5: five-year price CAGR at or above this earns the trend point. */
    private static final double D5_PRICE_CAGR_PERCENT = 12.0;

    /** D5: the recovery points available across all closed episodes, before the CAGR point. */
    private static final int D5_EPISODE_CAP = 15;

    /** D5: roughly three years of trading sessions. Below this the episode history is too short. */
    static final int D5_MIN_SESSIONS = 500;

    public Durability score(HoldingEvidence e, LocalDate today) {
        List<AnnualYear> history = e.annualHistory() == null ? List.of() : e.annualHistory();
        boolean financial = isFinancial(e);

        List<DurabilityComponent> components = new ArrayList<>();
        components.add(d1ReturnOnCapital(history, financial));
        components.add(d2GrowthConsistency(e, history));
        components.add(d3BalanceSheet(history));
        components.add(d4CashDiscipline(history));
        components.add(d5HiccupRecovery(e.dailyCloses(), today));

        int measured = 0;
        int earned = 0;
        for (DurabilityComponent c : components) {
            if (c.measured()) {
                measured++;
                earned += c.points();
            }
        }

        Integer score = null;
        if (measured >= config.getDurabilityMinComponents() && measured > 0) {
            score = (int) Math.round(100.0 * earned / (MAX_PER_COMPONENT * (double) measured));
        }
        return new Durability(score, components, coverageText(components, measured, score));
    }

    private String coverageText(List<DurabilityComponent> components, int measured, Integer score) {
        List<String> missing = new ArrayList<>();
        for (DurabilityComponent c : components) {
            if (!c.measured()) missing.add(c.code() + " (" + c.note() + ")");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(measured).append(" of ").append(components.size()).append(" components measured");
        if (!missing.isEmpty()) sb.append("; missing: ").append(String.join(", ", missing));
        if (score == null) {
            sb.append(". Below ").append(config.getDurabilityMinComponents())
              .append(" measured components no score is shown - import this stock's annual history ")
              .append("(Portfolio -> Fundamentals -> Import) to complete the picture.");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ D1

    /**
     * Return-on-capital persistence: how many of the last five financial years cleared the bar,
     * four points each.
     *
     * <p>ROCE here is {@code (net profit + interest) / (equity + borrowings)} — a <b>post-tax</b>
     * proxy, because profit before tax is not stored on {@code annual_fundamentals}. It therefore
     * reads lower than the pre-tax ROCE on the capital-efficiency dimension, which makes the
     * threshold conservative rather than generous. That is the right direction for a gate on
     * "never sell".
     */
    DurabilityComponent d1ReturnOnCapital(List<AnnualYear> history, boolean financial) {
        List<AnnualYear> last5 = lastN(history, 5);
        List<Double> readings = new ArrayList<>();
        for (AnnualYear y : last5) {
            Double r = financial ? roa(y) : roce(y);
            if (r != null) readings.add(r);
        }
        if (readings.size() < 4) {
            return new DurabilityComponent("D1", "Return-on-capital persistence", null,
                    "needs 4 years of capital-efficiency data, have " + readings.size());
        }
        double bar = financial ? FINANCIAL_ROA_PERSISTENCE_PERCENT : config.getRoceCoreThresholdPercent();
        long good = readings.stream().filter(r -> r >= bar).count();
        int points = Math.min(MAX_PER_COMPONENT, (int) good * 4);
        return new DurabilityComponent("D1", "Return-on-capital persistence", points,
                String.format("%d of %d years at or above %.1f%% %s",
                        good, readings.size(), bar, financial ? "ROA" : "ROCE"));
    }

    // ------------------------------------------------------------------ D2

    /** Growth consistency: steady beats lumpy for a position meant to be held for a decade. */
    DurabilityComponent d2GrowthConsistency(HoldingEvidence e, List<AnnualYear> history) {
        Integer consistency = e.earningsConsistencyScore();
        if (consistency == null) {
            return new DurabilityComponent("D2", "Growth consistency", null,
                    "no earnings-consistency score on the latest screening row");
        }
        int points = (int) Math.round(consistency * 0.2);
        String note = "consistency " + consistency + "/100";

        Double salesCagr = cagr(history, AnnualYear::sales);
        Double profitCagr = cagr(history, AnnualYear::netProfit);
        if (salesCagr != null && profitCagr != null) {
            if (salesCagr >= D2_SALES_CAGR_PERCENT && profitCagr >= salesCagr) {
                points += 4;
                note += String.format("; sales +%.1f%%/yr with profit keeping pace", salesCagr);
            } else {
                note += String.format("; sales %+.1f%%/yr, profit %+.1f%%/yr", salesCagr, profitCagr);
            }
        } else {
            note += "; multi-year CAGR not available";
        }
        return new DurabilityComponent("D2", "Growth consistency",
                Math.min(MAX_PER_COMPONENT, points), note);
    }

    // ------------------------------------------------------------------ D3

    /** Balance-sheet trajectory: is leverage going the right way, and is the share count stable? */
    DurabilityComponent d3BalanceSheet(List<AnnualYear> history) {
        List<AnnualYear> last3 = lastN(history, 3);
        if (last3.size() < 3) {
            return new DurabilityComponent("D3", "Balance-sheet trajectory", null,
                    "needs 3 years, have " + last3.size());
        }
        Double deOldest = debtToEquity(last3.get(0));
        Double deLatest = debtToEquity(last3.get(last3.size() - 1));
        if (deOldest == null || deLatest == null) {
            // The two debt checks are 15 of this component's 20 points and share their inputs;
            // scoring the remaining 5 alone would report a data gap as a weak balance sheet.
            return new DurabilityComponent("D3", "Balance-sheet trajectory", null,
                    "debt-to-equity not computable for both ends of the 3-year window");
        }
        int points = 0;
        List<String> notes = new ArrayList<>();
        if (deLatest <= deOldest + DE_FLAT_TOLERANCE) {
            points += 10;
            notes.add(String.format("debt/equity %.2f to %.2f", deOldest, deLatest));
        } else {
            notes.add(String.format("debt/equity rose %.2f to %.2f", deOldest, deLatest));
        }
        if (deLatest <= DEBT_FREE_DE) {
            points += 5;
            notes.add("effectively debt-free");
        }
        Double shareCagr = cagr(history, AnnualYear::shareCount);
        if (shareCagr == null) {
            notes.add("share count not on file, dilution not checked");
        } else if (shareCagr <= DILUTION_CAGR_PERCENT) {
            points += 5;
            notes.add(String.format("share count %+.1f%%/yr, no dilution", shareCagr));
        } else {
            notes.add(String.format("share count %+.1f%%/yr - dilution", shareCagr));
        }
        return new DurabilityComponent("D3", "Balance-sheet trajectory",
                Math.min(MAX_PER_COMPONENT, points), String.join("; ", notes));
    }

    // ------------------------------------------------------------------ D4

    /**
     * Cash discipline: does reported profit turn into cash?
     *
     * <p>The dividend leg the design sketched is deliberately absent. The dividend module records
     * what <em>this investor</em> received, which is a function of when they bought, not of the
     * company's payout record — scoring it here would measure the holding period and label it a
     * business quality.
     */
    DurabilityComponent d4CashDiscipline(List<AnnualYear> history) {
        List<AnnualYear> last3 = lastN(history, 3);
        if (last3.size() < 3) {
            return new DurabilityComponent("D4", "Cash discipline", null,
                    "needs 3 years, have " + last3.size());
        }
        double cfo = 0, profit = 0;
        for (AnnualYear y : last3) {
            if (y.operatingCashFlow() == null || y.netProfit() == null) {
                return new DurabilityComponent("D4", "Cash discipline", null,
                        "operating cash flow or profit missing in the 3-year window");
            }
            cfo += y.operatingCashFlow();
            profit += y.netProfit();
        }
        if (profit <= 0) {
            // A cash-conversion ratio against a loss is not a low ratio, it is a meaningless one.
            return new DurabilityComponent("D4", "Cash discipline", null,
                    "cumulative 3-year profit is not positive - conversion ratio undefined");
        }
        double ratio = cfo / profit;
        int points = ratio >= 1.0 ? 20 : ratio >= 0.8 ? 14 : ratio >= 0.6 ? 8 : 0;
        return new DurabilityComponent("D4", "Cash discipline", points,
                String.format("3-year operating cash flow is %.2fx reported profit", ratio));
    }

    // ------------------------------------------------------------------ D5

    /**
     * Hiccup recovery — how the business has come through past falls.
     *
     * <p><b>Only closed episodes that finished at least {@code closed-episode-min-age-months} ago
     * are scored.</b> The drawdown a holding is in right now is described and excluded. The
     * original formulation (count falls that recovered to a new high, penalise one that has not)
     * ran backwards against the whole feature: it could only be satisfied by a stock near its
     * high, and it downgraded a core holding at exactly the moment the tier exists to hold it.
     */
    DurabilityComponent d5HiccupRecovery(List<PricePoint> closes, LocalDate today) {
        if (closes == null || closes.size() < D5_MIN_SESSIONS) {
            return new DurabilityComponent("D5", "Hiccup recovery", null,
                    "needs about 3 years of daily prices, have "
                            + (closes == null ? 0 : closes.size()) + " sessions");
        }
        DrawdownHistory dd = findDrawdowns(closes, config.getDrawdownThresholdPercent());
        LocalDate closedBefore = today.minusMonths(config.getClosedEpisodeMinAgeMonths());

        int points = 0;
        int scored = 0;
        for (Episode ep : dd.closed) {
            if (!ep.recoveredOn().isBefore(closedBefore)) continue;   // too recent to be evidence yet
            long months = ChronoUnit.MONTHS.between(ep.startedOn(), ep.recoveredOn());
            if (months <= config.getFastRecoveryMonths()) {
                points += 5;
                scored++;
            } else if (months <= config.getSlowRecoveryMonths()) {
                points += 3;
                scored++;
            }
        }
        points = Math.min(D5_EPISODE_CAP, points);

        List<String> notes = new ArrayList<>();
        notes.add(scored + " past fall(s) of " + (int) config.getDrawdownThresholdPercent()
                + "%+ recovered within " + config.getSlowRecoveryMonths() + " months");

        Double priceCagr = priceCagr(closes);
        if (priceCagr != null && priceCagr >= D5_PRICE_CAGR_PERCENT) {
            points += 5;
            notes.add(String.format("price compounding %+.1f%%/yr", priceCagr));
        } else if (priceCagr != null) {
            notes.add(String.format("price %+.1f%%/yr", priceCagr));
        }

        if (dd.open != null) {
            long monthsIn = ChronoUnit.MONTHS.between(dd.open.startedOn(), today);
            notes.add(String.format("currently %.0f%% below its high, %d month(s) in - not scored",
                    dd.open.depthPercent(), monthsIn));
        }
        return new DurabilityComponent("D5", "Hiccup recovery",
                Math.min(MAX_PER_COMPONENT, points), String.join("; ", notes));
    }

    /** A completed fall-and-recovery, or (with a null recovery date) the one still running. */
    record Episode(LocalDate startedOn, LocalDate recoveredOn, double depthPercent) {}

    static final class DrawdownHistory {
        final List<Episode> closed = new ArrayList<>();
        Episode open;
    }

    /**
     * Walk the close series marking episodes. An episode <em>starts</em> the day the price first
     * closes {@code thresholdPercent} below its running peak and <em>closes</em> the day it
     * regains that peak.
     */
    static DrawdownHistory findDrawdowns(List<PricePoint> closes, double thresholdPercent) {
        DrawdownHistory out = new DrawdownHistory();
        if (closes == null || closes.isEmpty()) return out;

        double peak = closes.get(0).close();
        boolean inEpisode = false;
        LocalDate startedOn = null;
        double peakAtStart = peak;
        double trough = peak;

        for (PricePoint p : closes) {
            double c = p.close();
            if (!inEpisode) {
                if (c >= peak) {
                    peak = c;
                } else if (peak > 0 && (peak - c) / peak * 100.0 >= thresholdPercent) {
                    inEpisode = true;
                    startedOn = p.date();
                    peakAtStart = peak;
                    trough = c;
                }
            } else {
                if (c < trough) trough = c;
                if (c >= peakAtStart) {
                    out.closed.add(new Episode(startedOn, p.date(),
                            (peakAtStart - trough) / peakAtStart * 100.0));
                    inEpisode = false;
                    peak = c;
                }
            }
        }
        if (inEpisode) {
            out.open = new Episode(startedOn, null, (peakAtStart - trough) / peakAtStart * 100.0);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private boolean isFinancial(HoldingEvidence e) {
        if ("NA_FINANCIAL".equals(e.capitalEfficiencyVerdict())) return true;
        String ind = e.industry() == null ? "" : e.industry().toLowerCase(java.util.Locale.ROOT);
        return ind.contains("bank") || ind.contains("financ") || ind.contains("nbfc")
                || ind.contains("insur");
    }

    /** Post-tax ROCE proxy, in percent. Null when the capital base is not usable. */
    static Double roce(AnnualYear y) {
        if (y.netProfit() == null || y.equity() == null) return null;
        double capital = y.equity() + (y.borrowings() == null ? 0 : y.borrowings());
        if (capital <= 0) return null;
        double interest = y.interestCost() == null ? 0 : y.interestCost();
        return (y.netProfit() + interest) / capital * 100.0;
    }

    static Double roa(AnnualYear y) {
        if (y.netProfit() == null || y.totalAssets() == null || y.totalAssets() <= 0) return null;
        return y.netProfit() / y.totalAssets() * 100.0;
    }

    static Double debtToEquity(AnnualYear y) {
        if (y.equity() == null || y.equity() <= 0 || y.borrowings() == null) return null;
        return y.borrowings() / y.equity();
    }

    private static List<AnnualYear> lastN(List<AnnualYear> history, int n) {
        if (history == null || history.isEmpty()) return List.of();
        int from = Math.max(0, history.size() - n);
        return history.subList(from, history.size());
    }

    /**
     * Compound annual growth between the first and last usable readings.
     * Null when either end is missing, non-positive, or the span is under two years — a "CAGR"
     * over one year is a growth rate wearing a longer word.
     */
    static Double cagr(List<AnnualYear> history,
                       java.util.function.Function<AnnualYear, Double> field) {
        if (history == null || history.size() < 3) return null;
        AnnualYear first = null, last = null;
        for (AnnualYear y : history) {
            Double v = field.apply(y);
            if (v == null || v <= 0) continue;
            if (first == null) first = y;
            last = y;
        }
        if (first == null || last == null || first == last) return null;
        int years = last.fiscalYear() - first.fiscalYear();
        if (years < 2) return null;
        double a = field.apply(first);
        double b = field.apply(last);
        return (Math.pow(b / a, 1.0 / years) - 1.0) * 100.0;
    }

    static Double priceCagr(List<PricePoint> closes) {
        if (closes == null || closes.size() < 2) return null;
        PricePoint first = closes.get(0);
        PricePoint last = closes.get(closes.size() - 1);
        if (first.close() <= 0 || last.close() <= 0) return null;
        double years = ChronoUnit.DAYS.between(first.date(), last.date()) / 365.25;
        if (years < 1.0) return null;
        return (Math.pow(last.close() / first.close(), 1.0 / years) - 1.0) * 100.0;
    }
}
