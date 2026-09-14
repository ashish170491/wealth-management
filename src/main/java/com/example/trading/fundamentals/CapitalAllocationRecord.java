package com.example.trading.fundamentals;

import java.util.ArrayList;
import java.util.List;

/**
 * What management actually did with a decade of cash (SPEC §42) — the management-quality pillar.
 *
 * <h2>Why this exists</h2>
 * SPEC §40.1 records management quality as the one pillar with <b>no capital-allocation record at
 * all</b>. The app could see insider filings and what management <em>said</em> on an earnings call;
 * it could not see what they did with the money. That is the half of the judgement that actually
 * separates a compounder from a company that grew: profits retained and reinvested badly compound
 * nothing, and a share count that grows as fast as profits hands the owner nothing either.
 *
 * <h2>The five components</h2>
 * <ol>
 *   <li><b>Share-count discipline</b> — dilution excluding bonus issues and splits. A bonus
 *       multiplies the count by an exact simple ratio and takes nothing from the owner; a placement
 *       does. Conflating them is B-066.</li>
 *   <li><b>Payout and retention</b> — cumulative dividends over cumulative profit. Retention is
 *       half the compounding arithmetic in §41.1 and was the single input §41.4 named as missing.</li>
 *   <li><b>Reinvestment intensity</b> — cumulative capex against cumulative depreciation. Above 1
 *       the asset base is growing; near 1 it is being maintained.</li>
 *   <li><b>Debt trajectory</b> — where leverage ended up, and which way it moved.</li>
 *   <li><b>Incremental return on capital</b> — the extra operating profit earned per rupee of
 *       extra capital employed. This is the Buffett metric: a company can show a fine average
 *       return while every fresh rupee it invests earns nothing.</li>
 * </ol>
 *
 * <h2>What it refuses to do</h2>
 * <ul>
 *   <li><b>It never enters the composite.</b> Computed, shown, worth zero points — Gotcha 30 and
 *       SPEC §20 rule 9. Promotion only through §38.10.</li>
 *   <li><b>It says nothing below five years.</b> Capital allocation over three years is one capex
 *       decision and one dividend. {@link #MIN_YEARS} is a floor, not a preference.</li>
 *   <li><b>A component that could not be measured is never a weak grade</b>, and one that does not
 *       apply leaves the denominator entirely (Gotcha 68). A company that has never paid a
 *       dividend has a 100% retention rate, which is a fact about it; a company whose filings do
 *       not tag dividends has no payout reading at all, which is a fact about the filings.</li>
 * </ul>
 *
 * <p>Pure: no repository, no clock, no network. Same contract as {@code CompoundingQuality}.
 */
public final class CapitalAllocationRecord {

    private CapitalAllocationRecord() {
    }

    /** Capital allocation over fewer years than this is an anecdote, not a record. */
    public static final int MIN_YEARS = 5;

    /**
     * How close a share-count ratio must sit to a simple fraction to be read as a bonus or split.
     *
     * <p>0.05%, not 0.5%. At the looser tolerance the grid of simple ratios swallows real money
     * raises — measured on BANKINDIA, an infusion plus a QIP landed 0.18% from 5/4 and 0.15% from
     * 10/9 (Gotcha 86). A tolerance wide enough to be forgiving is wide enough to classify a
     * dilution as a gift.
     */
    private static final double RATIO_TOLERANCE = 0.0005;

    /** Simple ratios a bonus or split actually produces. */
    private static final double[][] SIMPLE_RATIOS = {
            {2, 1}, {3, 1}, {4, 1}, {5, 1}, {10, 1}, {3, 2}, {5, 2}, {5, 4},
            {7, 5}, {10, 9}, {4, 3}, {6, 5}, {11, 10}, {21, 20}, {3, 5}, {1, 2}
    };

    public enum Status { STRONG, ADEQUATE, WEAK, NOT_MEASURED, NOT_APPLICABLE }

    /**
     * One component of the record.
     *
     * @param figure the number behind the verdict, null when unmeasured — never zero as a stand-in
     * @param detail one plain sentence the investor can read without knowing the metric
     */
    public record Component(String key, String question, Status status, Double figure, String detail) {
        public boolean measured() {
            return status != Status.NOT_MEASURED && status != Status.NOT_APPLICABLE;
        }
    }

    /**
     * @param yearsCovered   financial years the record spans
     * @param measured       components that produced a reading — the denominator
     * @param corporateActionsDetected bonus issues or splits divided out before any growth rate
     */
    public record Result(List<Component> components, int yearsCovered, int measured, int strong,
                         int weak, String verdict, String reason, int corporateActionsDetected) {

        public Component component(String key) {
            return components.stream().filter(c -> key.equals(c.key())).findFirst().orElse(null);
        }
    }

    /**
     * Build the record from a symbol's annual history, oldest year first.
     *
     * <p>The caller is responsible for having filtered to one reporting basis — the backfill does
     * that at write time (Gotcha 73), and mixing consolidated with standalone here would produce a
     * share count and a profit that describe different entities.
     */
    public static Result analyse(List<AnnualFundamentalsEntity> history) {
        List<AnnualFundamentalsEntity> h = history == null ? List.of() : history;
        if (h.size() < MIN_YEARS) {
            return new Result(List.of(), h.size(), 0, 0, 0, "NOT_MEASURED",
                    "Capital allocation needs at least " + MIN_YEARS + " years of accounts to mean "
                            + "anything; " + h.size() + " on file. Over a shorter window this is one "
                            + "capex decision and one dividend, not a record.",
                    0);
        }

        List<Component> out = new ArrayList<>();
        int[] actions = new int[1];
        out.add(shareCountDiscipline(h, actions));
        out.add(payoutAndRetention(h));
        out.add(reinvestmentIntensity(h));
        out.add(debtTrajectory(h));
        out.add(incrementalReturn(h));

        int measured = 0, strong = 0, weak = 0;
        for (Component c : out) {
            if (!c.measured()) continue;
            measured++;
            if (c.status() == Status.STRONG) strong++;
            if (c.status() == Status.WEAK) weak++;
        }

        String verdict;
        String reason;
        if (measured < 3) {
            // Fewer than three readings is not a poor record, it is an unread one (Gotcha 44).
            verdict = "NOT_MEASURED";
            reason = "Only " + measured + " of 5 components could be measured from the filings on "
                    + "file, which is too few to characterise how this management allocates capital.";
        } else if (weak == 0 && strong >= measured - 1) {
            verdict = "DISCIPLINED";
            reason = strong + " of " + measured + " measured components are strong and none is weak.";
        } else if (weak >= measured - 1) {
            verdict = "POOR";
            reason = weak + " of " + measured + " measured components are weak.";
        } else {
            verdict = "MIXED";
            reason = strong + " strong and " + weak + " weak of " + measured + " measured components.";
        }

        return new Result(out, h.size(), measured, strong, weak, verdict, reason, actions[0]);
    }

    // ---------------------------------------------------------------- components

    /**
     * Has the owner's slice been protected?
     *
     * <p>Corporate actions are divided out before the growth rate is taken. A bonus issue and a
     * placement both multiply the share count; only one of them took something from the existing
     * owner, and treating them alike is how a 1:2 bonus fired {@code DILUTION:HIGH} on BEL (B-066).
     */
    private static Component shareCountDiscipline(List<AnnualFundamentalsEntity> h, int[] actions) {
        List<Double> adjusted = new ArrayList<>();
        Double running = null;
        double cumulativeFactor = 1.0;
        for (AnnualFundamentalsEntity row : h) {
            Double sc = row.getShareCount();
            if (sc == null || sc <= 0) {
                adjusted.add(null);
                continue;
            }
            if (running != null) {
                double ratio = sc / running;
                Double action = corporateActionRatio(ratio, previousFaceValue(h, row), row.getFaceValue());
                if (action != null) {
                    cumulativeFactor *= action;
                    actions[0]++;
                }
            }
            running = sc;
            adjusted.add(sc / cumulativeFactor);
        }

        Double first = firstNonNull(adjusted);
        Double last = lastNonNull(adjusted);
        int span = spanBetweenNonNull(adjusted);
        if (first == null || last == null || span < 2 || first <= 0) {
            return new Component("shareCount", "Has your slice of the company been protected?",
                    Status.NOT_MEASURED, null,
                    "Share count is missing from too many years to measure dilution. Not a finding "
                            + "about the company — a gap in what the filings tagged.");
        }
        double cagr = (Math.pow(last / first, 1.0 / span) - 1) * 100.0;
        Status s = cagr <= 0.5 ? Status.STRONG : cagr <= 2.0 ? Status.ADEQUATE : Status.WEAK;
        String detail;
        if (cagr < -0.5) {
            detail = String.format("Share count shrank %.1f%% a year — the company has been buying "
                    + "its own shares back, so your slice grew.", -cagr);
        } else if (cagr <= 0.5) {
            detail = "Share count is essentially flat, so growth has not been paid for by issuing "
                    + "new shares.";
        } else {
            detail = String.format("Share count grew %.1f%% a year excluding bonus issues and "
                    + "splits, which dilutes your share of every rupee of profit.", cagr);
        }
        if (actions[0] > 0) {
            detail += " " + actions[0] + " bonus issue or split was divided out first — those "
                    + "multiply the share count without taking anything from you.";
        }
        return new Component("shareCount", "Has your slice of the company been protected?",
                s, cagr, detail);
    }

    /** Cumulative dividends over cumulative profit: what was handed back versus kept to reinvest. */
    private static Component payoutAndRetention(List<AnnualFundamentalsEntity> h) {
        double dividends = 0, profit = 0;
        int paired = 0;
        for (AnnualFundamentalsEntity r : h) {
            if (r.getDividendsPaid() == null || r.getNetProfit() == null) continue;
            dividends += Math.abs(r.getDividendsPaid());
            profit += r.getNetProfit();
            paired++;
        }
        if (paired < 3) {
            return new Component("payout", "How much profit is kept to reinvest?",
                    Status.NOT_MEASURED, null,
                    "Dividends are not tagged in enough of the filings on file to work out how much "
                            + "profit was kept. This says nothing about whether the company pays one.");
        }
        if (profit <= 0) {
            return new Component("payout", "How much profit is kept to reinvest?",
                    Status.NOT_APPLICABLE, null,
                    "The company did not earn a cumulative profit over this period, so a payout "
                            + "ratio would not describe anything.");
        }
        double payout = dividends / profit * 100.0;
        double retention = 100.0 - payout;
        // A high retention rate is only a virtue if the retained money earns a good return, which
        // is what the incremental-return component is for. Judged here on whether the company is
        // keeping enough to compound at all, not on retention being high for its own sake.
        Status s = payout <= 40 ? Status.STRONG : payout <= 70 ? Status.ADEQUATE : Status.WEAK;
        String detail = String.format(
                "Paid out %.0f%% of profits as dividends and kept %.0f%% to reinvest. %s",
                payout, retention,
                payout <= 40 ? "Keeping most of the profit is what lets a business compound, "
                        + "provided the money earns a good return."
                        : payout <= 70 ? "A balance between paying you now and reinvesting."
                        : "Most of the profit leaves the business, which caps how fast it can grow "
                        + "from its own earnings.");
        return new Component("payout", "How much profit is kept to reinvest?", s, payout, detail);
    }

    /**
     * Cumulative capex against cumulative depreciation.
     *
     * <p>Both endpoints of the asset base must be present. An unknown delta defaulted to zero
     * understates capex by exactly the amount under construction — the spending the measure exists
     * to see — while still producing a ratio that looks measured (B-048).
     */
    private static Component reinvestmentIntensity(List<AnnualFundamentalsEntity> h) {
        AnnualFundamentalsEntity first = h.get(0);
        AnnualFundamentalsEntity last = h.get(h.size() - 1);
        Double nbFirst = first.getNetBlock(), nbLast = last.getNetBlock();
        double depreciation = 0;
        int depYears = 0;
        for (AnnualFundamentalsEntity r : h) {
            if (r.getDepreciation() != null) {
                depreciation += r.getDepreciation();
                depYears++;
            }
        }
        if (nbFirst == null || nbLast == null || depYears < 3 || depreciation <= 0) {
            return new Component("reinvestment", "Is the company still building?",
                    Status.NOT_MEASURED, null,
                    "The asset base or the depreciation charge is missing from the filings at one "
                            + "end of this period, so capital spending cannot be worked out. "
                            + "Unknown, not zero.");
        }
        Double cwipFirst = first.getCapitalWorkInProgress(), cwipLast = last.getCapitalWorkInProgress();
        double cwipDelta;
        if (cwipFirst != null && cwipLast != null) {
            cwipDelta = cwipLast - cwipFirst;
        } else if (cwipFirst == null && cwipLast == null) {
            cwipDelta = 0;              // Neither year reports construction: a genuine zero.
        } else {
            return new Component("reinvestment", "Is the company still building?",
                    Status.NOT_MEASURED, null,
                    "Work under construction is reported at one end of this period and not the "
                            + "other, so the change in it is unknown. Treating that as zero would "
                            + "understate spending by exactly the amount being built (B-048).");
        }
        double capex = (nbLast - nbFirst) + cwipDelta + depreciation;
        double ratio = capex / depreciation;
        Status s = ratio >= 1.5 ? Status.STRONG : ratio >= 0.9 ? Status.ADEQUATE : Status.WEAK;
        String detail = String.format(
                "Spent about %.1fx its depreciation charge on assets over these %d years. %s",
                ratio, h.size(),
                ratio >= 1.5 ? "The asset base is growing well beyond replacement."
                        : ratio >= 0.9 ? "Roughly enough to keep the existing asset base intact."
                        : "Less than it is wearing out, so the asset base is shrinking. For an "
                        + "asset-light business that can be normal rather than a warning.");
        return new Component("reinvestment", "Is the company still building?", s, ratio, detail);
    }

    /** Where leverage ended up, and which way it moved. Not applicable to a lender. */
    private static Component debtTrajectory(List<AnnualFundamentalsEntity> h) {
        Double first = null, last = null;
        for (AnnualFundamentalsEntity r : h) {
            Double de = r.debtToEquity();
            if (de == null) continue;
            if (first == null) first = de;
            last = de;
        }
        if (first == null || last == null) {
            return new Component("debt", "Was growth funded with borrowed money?",
                    Status.NOT_MEASURED, null,
                    "Borrowings or equity are missing from the filings on file, so the debt trend "
                            + "cannot be measured. This is the balance-sheet gap in NSE's older "
                            + "archive filings, not a finding about the company.");
        }
        double change = last - first;
        Status s;
        String detail;
        if (last <= 0.5 && change <= 0.1) {
            s = Status.STRONG;
            detail = String.format("Debt is %.2fx equity and has not risen — growth has been funded "
                    + "out of profits rather than borrowing.", last);
        } else if (last <= 1.0 || change < -0.2) {
            s = Status.ADEQUATE;
            detail = String.format("Debt is %.2fx equity, %s over the period.", last,
                    change < -0.05 ? "down" : change > 0.05 ? "up" : "roughly flat");
        } else {
            s = Status.WEAK;
            detail = String.format("Debt is %.2fx equity and %s. Borrowed growth flatters returns "
                    + "while rates are low and is the first thing to hurt when they are not.",
                    last, change > 0 ? "has risen" : "remains high");
        }
        return new Component("debt", "Was growth funded with borrowed money?", s, last, detail);
    }

    /**
     * The extra operating profit earned per extra rupee of capital employed.
     *
     * <p>The Buffett question, and the one a headline return on capital hides: a business can post
     * a fine average return while every fresh rupee it puts to work earns nothing. Not applicable
     * when capital employed shrank — a company returning capital is doing something this ratio
     * does not describe, and dividing by a negative denominator would report a plausible-looking
     * number with the sign inverted.
     */
    private static Component incrementalReturn(List<AnnualFundamentalsEntity> h) {
        AnnualFundamentalsEntity first = h.get(0);
        AnnualFundamentalsEntity last = h.get(h.size() - 1);
        Double ce0 = capitalEmployed(first), ce1 = capitalEmployed(last);
        Double op0 = first.getOperatingProfit(), op1 = last.getOperatingProfit();
        if (ce0 == null || ce1 == null || op0 == null || op1 == null) {
            return new Component("incrementalReturn", "Does new money earn a good return?",
                    Status.NOT_MEASURED, null,
                    "Equity, borrowings or operating profit is missing at one end of this period, "
                            + "so the return on newly invested capital cannot be worked out.");
        }
        double deltaCapital = ce1 - ce0;
        if (deltaCapital <= 0 || deltaCapital < Math.abs(ce0) * 0.05) {
            return new Component("incrementalReturn", "Does new money earn a good return?",
                    Status.NOT_APPLICABLE, null,
                    "The capital in the business did not grow materially over this period, so there "
                            + "is no meaningful amount of new money to judge. That is common in "
                            + "asset-light businesses and is not a shortcoming.");
        }
        double incremental = (op1 - op0) / deltaCapital * 100.0;
        Status s = incremental >= 20 ? Status.STRONG : incremental >= 12 ? Status.ADEQUATE : Status.WEAK;
        String detail = String.format(
                "Every extra ₹100 of capital put into the business added about ₹%.0f of annual "
                        + "operating profit. %s", incremental,
                incremental >= 20 ? "That is the mark of a business that can reinvest profitably, "
                        + "which is what compounds."
                        : incremental >= 12 ? "Above the roughly 12% it costs to fund that capital, "
                        + "so growth is creating value rather than just adding size."
                        : "Below what the capital costs to fund, so the growth has been adding size "
                        + "rather than value.");
        return new Component("incrementalReturn", "Does new money earn a good return?",
                s, incremental, detail);
    }

    // ---------------------------------------------------------------- helpers

    private static Double capitalEmployed(AnnualFundamentalsEntity r) {
        if (r.getEquity() == null) return null;
        double borrowings = r.getBorrowings() == null ? 0 : r.getBorrowings();
        double ce = r.getEquity() + borrowings;
        return ce > 0 ? ce : null;
    }

    /**
     * The ratio to divide out for a corporate action, or null when the change looks like real money.
     *
     * <p>Face value is checked first and settles it outright: a change in face value <b>is</b> a
     * split, so no inference is needed. Only when face value is unavailable does this fall back to
     * matching the share-count ratio against a grid of simple fractions — which is what B-066 had
     * to do for want of the field, and what the tight tolerance exists to keep honest.
     */
    static Double corporateActionRatio(double shareCountRatio, Double faceValuePrev, Double faceValueNow) {
        if (faceValuePrev != null && faceValueNow != null && faceValuePrev > 0 && faceValueNow > 0
                && Math.abs(faceValuePrev - faceValueNow) > 1e-9) {
            return faceValuePrev / faceValueNow;
        }
        if (shareCountRatio <= 1.0001) return null;   // Only an increase can be a bonus or split.
        for (double[] r : SIMPLE_RATIOS) {
            double target = r[0] / r[1];
            if (target > 1.0001 && Math.abs(shareCountRatio - target) / target <= RATIO_TOLERANCE) {
                return target;
            }
        }
        return null;
    }

    private static Double previousFaceValue(List<AnnualFundamentalsEntity> h, AnnualFundamentalsEntity row) {
        Double prev = null;
        for (AnnualFundamentalsEntity r : h) {
            if (r == row) return prev;
            if (r.getFaceValue() != null) prev = r.getFaceValue();
        }
        return prev;
    }

    private static Double firstNonNull(List<Double> xs) {
        for (Double x : xs) if (x != null) return x;
        return null;
    }

    private static Double lastNonNull(List<Double> xs) {
        for (int i = xs.size() - 1; i >= 0; i--) if (xs.get(i) != null) return xs.get(i);
        return null;
    }

    /** Years between the first and last measured points — the true exponent for a growth rate. */
    private static int spanBetweenNonNull(List<Double> xs) {
        int first = -1, last = -1;
        for (int i = 0; i < xs.size(); i++) {
            if (xs.get(i) == null) continue;
            if (first < 0) first = i;
            last = i;
        }
        return first < 0 ? 0 : last - first;
    }
}
