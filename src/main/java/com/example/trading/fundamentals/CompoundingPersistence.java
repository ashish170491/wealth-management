package com.example.trading.fundamentals;

import java.util.ArrayList;
import java.util.List;

/**
 * Has this business compounded, or did it just have a good year? (SPEC §43)
 *
 * <h2>Why this exists</h2>
 * {@code CompoundingQuality} (§41) answers "can this business compound?" from the <b>latest single
 * year</b>, and says so — {@code yearsOfAccounts} exists precisely so every surface can admit it.
 * That was not a design choice, it was the data: multi-year history existed for roughly 19 of 288
 * screened stocks. Whether a 25% return on capital held for eight years is the strongest
 * compounder evidence there is, and it was unanswerable for 93% of the universe.
 *
 * <h2>The one rule that matters: count years, never average them</h2>
 * A commodity business's ten-year return on capital oscillates roughly 5% → 35% → 5%. An
 * <em>average</em> clears an 18% bar comfortably; "held above 18% in seven of ten years" does not.
 * Averaging lets a single boom year carry a decade, which is the exact failure a persistence test
 * exists to catch. Every gate below is therefore a count of qualifying years over measured years.
 *
 * <h2>Two tiers, because the data resolves unevenly</h2>
 * SPEC §32.5 records that balance-sheet facts — equity, borrowings, net block — often fail to
 * resolve for pre-2022 archive filings, while profit-and-loss lines and share count come through.
 * Building every gate on return on capital would therefore produce a lens that reads
 * {@code NOT_MEASURED} for most of the universe: §41's limitation again, with more work behind it.
 * Tier A runs on what resolves; Tier B runs on the balance sheet where it is there, and is absent
 * rather than failing where it is not.
 *
 * <h2>What it refuses to do</h2>
 * <ul>
 *   <li><b>It never enters the composite</b> (Gotcha 30, SPEC §20 rule 9). A lens: computed,
 *       shown, worth zero points.</li>
 *   <li><b>Fewer than {@link #MIN_YEARS} years is NOT_MEASURED for the whole lens</b> — not a
 *       poor persistence score. A business whose filings could not be read has not failed.</li>
 *   <li><b>An unmeasured gate never counts as a pass and never enters the denominator</b>
 *       (Gotcha 68). Three of the gates below can pass on thin evidence if that rule is relaxed,
 *       which is how a company with nothing on file collects free passes.</li>
 * </ul>
 *
 * <p>Pure: no repository, no clock, no network. Computed on read like §41 and §12.11, never
 * stored — a persisted copy can disagree with the history it describes after the next backfill
 * batch deepens it.
 */
public final class CompoundingPersistence {

    private CompoundingPersistence() {
    }

    /** Below this many years, this class says nothing at all. */
    public static final int MIN_YEARS = 5;

    /**
     * The share of measured years a gate must hold in.
     *
     * <p>0.7 is "held in seven years out of ten". Deliberately not 1.0: a business that never once
     * dipped over a decade is a business that has not met a recession, and requiring it would
     * select for short histories over durable ones. Deliberately not a majority either, which a
     * cyclical clears at the top of its cycle.
     *
     * <p>This bar is a starting point set on judgement, <b>not</b> on the measured cross-section,
     * because the cross-section does not exist yet — the backfill has to land first. It is
     * therefore the one number here that SPEC §43 flags for recalibration against real data, the
     * §12.11 and §41.3 discipline applied as soon as it can be.
     */
    public static final double REQUIRED_SHARE = 0.7;

    /** Return on capital employed a year must clear to count toward persistence (§41.3's bar). */
    public static final double ROCE_BAR = 18.0;

    /** Debt-to-equity a year must stay under. */
    public static final double LEVERAGE_BAR = 0.5;

    /**
     * Measured years a <b>Tier B</b> gate needs before it will express any opinion.
     *
     * <p>Found by running this against live data rather than in review: BEL has 8 years of
     * accounts but a balance sheet in only 3 of them, so return on capital passed 3/3 and
     * contributed to a {@code PROVEN_COMPOUNDER} badge. Three readings is thinner than the word
     * "persistence" claims, and a gate that passes on thin evidence while sounding authoritative
     * is the Gotcha 68 failure in its most flattering form.
     *
     * <p>Four matches {@code FundamentalsHistoryService.MIN_YEARS_FOR_ANALYSIS}, the bar every
     * other multi-year check in this codebase already uses. Below it the gate is
     * {@code NOT_MEASURED} — absent, never failed — which is the honest reading while SPEC §32.5's
     * pre-2022 balance-sheet gap persists.
     */
    public static final int MIN_TIER_B_YEARS = 4;

    public enum Verdict {
        /** Held up on every applicable check, across enough years to mean something. */
        PROVEN_COMPOUNDER,
        /** Held on some checks. A record with a strength and a weakness. */
        PARTIAL,
        /** Did not hold. */
        NO,
        /** Too little history or too few readings to say anything. Never shown as a failure. */
        NOT_MEASURED
    }

    public enum GateStatus { PASS, FAIL, NOT_APPLICABLE, NOT_MEASURED }

    /**
     * @param qualifyingYears years that met the test
     * @param measuredYears   years the test could be evaluated in — the denominator
     */
    public record Gate(String key, String question, GateStatus status,
                       int qualifyingYears, int measuredYears, String detail) {
    }

    public record Result(Verdict verdict, List<Gate> gates, int passed, int applicable,
                         int yearsOfAccounts, String reason) {

        /** True only for the badge. Explicit so a UI cannot infer it from a count. */
        public boolean proven() {
            return verdict == Verdict.PROVEN_COMPOUNDER;
        }
    }

    /**
     * Evaluate a symbol's annual history, oldest year first.
     *
     * @param history single-basis annual rows (the backfill enforces one basis per series)
     * @param lender  true for a bank or NBFC: leverage and gross margin do not describe a business
     *                whose borrowing is its raw material (§41.2's rule, one level up)
     */
    public static Result analyse(List<AnnualFundamentalsEntity> history, boolean lender) {
        List<AnnualFundamentalsEntity> h = history == null ? List.of() : history;
        if (h.size() < MIN_YEARS) {
            return new Result(Verdict.NOT_MEASURED, List.of(), 0, 0, h.size(),
                    "A track record needs at least " + MIN_YEARS + " years of accounts; " + h.size()
                            + " on file. This is not a poor record — it is an unread one.");
        }

        List<Gate> gates = new ArrayList<>();
        gates.add(marginStability(h));
        gates.add(earningsSteadiness(h));
        gates.add(compoundingRecord(h));
        gates.add(shareCountDiscipline(h));
        gates.add(returnPersistence(h));
        gates.add(leverageDiscipline(h, lender));

        int passed = 0, applicable = 0;
        for (Gate g : gates) {
            if (g.status() == GateStatus.PASS) {
                passed++;
                applicable++;
            } else if (g.status() == GateStatus.FAIL) {
                applicable++;
            }
        }

        // Four applicable checks is the floor for the badge. Below that the lens has seen too
        // little of the business to call it proven, however good what it did see was.
        Verdict verdict;
        String reason;
        if (applicable < 4) {
            verdict = Verdict.NOT_MEASURED;
            reason = "Only " + applicable + " of 6 checks could be evaluated from " + h.size()
                    + " years of accounts. Too few to judge a track record either way.";
        } else if (passed == applicable) {
            verdict = Verdict.PROVEN_COMPOUNDER;
            reason = "Held up on all " + applicable + " checks that could be measured across "
                    + h.size() + " years of accounts.";
        } else if (passed >= applicable - 1) {
            verdict = Verdict.PARTIAL;
            reason = "Held up on " + passed + " of " + applicable + " checks across " + h.size()
                    + " years — one weakness in an otherwise steady record.";
        } else if (passed * 2 >= applicable) {
            verdict = Verdict.PARTIAL;
            reason = "Held up on " + passed + " of " + applicable + " checks across " + h.size()
                    + " years.";
        } else {
            verdict = Verdict.NO;
            reason = "Held up on only " + passed + " of " + applicable + " checks across " + h.size()
                    + " years of accounts.";
        }
        return new Result(verdict, gates, passed, applicable, h.size(), reason);
    }

    // ---------------------------------------------------------------- Tier A: P&L and share count

    /**
     * Did operating margin hold up, rather than being competed away?
     *
     * <p>Measured against the series' own median rather than an absolute bar, because a healthy
     * margin for a supermarket and for a software company differ by an order of magnitude. What
     * generalises is whether the business kept its own level.
     */
    private static Gate marginStability(List<AnnualFundamentalsEntity> h) {
        List<Double> margins = new ArrayList<>();
        for (AnnualFundamentalsEntity r : h) {
            Double m = r.operatingMarginPercent();
            if (m != null) margins.add(m);
        }
        if (margins.size() < MIN_YEARS - 1) {
            return unmeasured("marginStability", "Have margins held up?",
                    "Sales or operating profit is missing from too many years to see a margin trend.");
        }
        double median = median(margins);
        // Two points of margin below the business's own median is a real erosion; noise is smaller.
        long held = margins.stream().filter(m -> m >= median - 2.0).count();
        return count("marginStability", "Have margins held up?", (int) held, margins.size(),
                String.format("Operating margin stayed within 2 points of its %.1f%% median in "
                        + "%d of %d years.", median, held, margins.size()));
    }

    /** Was the business profitable year in, year out, rather than in bursts? */
    private static Gate earningsSteadiness(List<AnnualFundamentalsEntity> h) {
        List<Double> profits = new ArrayList<>();
        for (AnnualFundamentalsEntity r : h) {
            if (r.getNetProfit() != null) profits.add(r.getNetProfit());
        }
        if (profits.size() < MIN_YEARS - 1) {
            return unmeasured("earningsSteadiness", "Does it earn every year?",
                    "Net profit is missing from too many years to judge steadiness.");
        }
        long positive = profits.stream().filter(p -> p > 0).count();
        return count("earningsSteadiness", "Does it earn every year?", (int) positive, profits.size(),
                String.format("Profitable in %d of %d years. A business that loses money in the bad "
                        + "years has to spend the good ones recovering.", positive, profits.size()));
    }

    /**
     * Did profit actually compound, and did it keep pace with sales?
     *
     * <p>Sales growing faster than profit for a decade means the business bought its growth by
     * giving margin away, which is why this checks both legs rather than revenue alone.
     */
    private static Gate compoundingRecord(List<AnnualFundamentalsEntity> h) {
        Double salesFirst = null, salesLast = null, profitFirst = null, profitLast = null;
        int salesSpan = 0, profitSpan = 0, i = 0, salesFirstIdx = -1, profitFirstIdx = -1;
        for (AnnualFundamentalsEntity r : h) {
            if (r.getSales() != null && r.getSales() > 0) {
                if (salesFirst == null) {
                    salesFirst = r.getSales();
                    salesFirstIdx = i;
                }
                salesLast = r.getSales();
                salesSpan = i - salesFirstIdx;
            }
            if (r.getNetProfit() != null && r.getNetProfit() > 0) {
                if (profitFirst == null) {
                    profitFirst = r.getNetProfit();
                    profitFirstIdx = i;
                }
                profitLast = r.getNetProfit();
                profitSpan = i - profitFirstIdx;
            }
            i++;
        }
        if (salesFirst == null || profitFirst == null || salesSpan < 3 || profitSpan < 3) {
            return unmeasured("compounding", "Have earnings actually compounded?",
                    "Sales or profit is missing from the start or the end of this period, so a "
                            + "growth rate over it cannot be worked out.");
        }
        double salesCagr = (Math.pow(salesLast / salesFirst, 1.0 / salesSpan) - 1) * 100;
        double profitCagr = (Math.pow(profitLast / profitFirst, 1.0 / profitSpan) - 1) * 100;
        // Roughly nominal GDP: below this the business is not outgrowing the economy it sits in.
        boolean pass = profitCagr >= 10.0 && profitCagr >= salesCagr - 2.0;
        String detail = String.format("Profit compounded %.1f%% a year against sales at %.1f%%. %s",
                profitCagr, salesCagr,
                profitCagr < 10 ? "Below the pace at which the economy itself grows."
                        : profitCagr >= salesCagr - 2.0
                        ? "Profit kept pace with sales, so growth was not bought by giving margin away."
                        : "Sales grew faster than profit, so the growth cost margin.");
        return new Gate("compounding", "Have earnings actually compounded?",
                pass ? GateStatus.PASS : GateStatus.FAIL, pass ? 1 : 0, 1, detail);
    }

    /** Did the owner's slice survive the growth? Delegates the corporate-action rule to §42. */
    private static Gate shareCountDiscipline(List<AnnualFundamentalsEntity> h) {
        CapitalAllocationRecord.Result r = CapitalAllocationRecord.analyse(h);
        CapitalAllocationRecord.Component c = r.component("shareCount");
        if (c == null || !c.measured() || c.figure() == null) {
            return unmeasured("shareCount", "Did your slice survive the growth?",
                    "Share count is missing from too many years to measure dilution.");
        }
        boolean pass = c.figure() <= 2.0;
        return new Gate("shareCount", "Did your slice survive the growth?",
                pass ? GateStatus.PASS : GateStatus.FAIL, pass ? 1 : 0, 1, c.detail());
    }

    // ---------------------------------------------------------------- Tier B: balance sheet

    /**
     * Did return on capital stay high, year after year?
     *
     * <p>The strongest single piece of compounder evidence, and the one most likely to be
     * unmeasurable: it needs equity and borrowings, the facts SPEC §32.5 records as often failing
     * to resolve in pre-2022 archive filings. It is absent in that case, never a failure.
     */
    private static Gate returnPersistence(List<AnnualFundamentalsEntity> h) {
        int qualifying = 0, measured = 0;
        for (AnnualFundamentalsEntity r : h) {
            Double roce = roce(r);
            if (roce == null) continue;
            measured++;
            if (roce >= ROCE_BAR) qualifying++;
        }
        if (measured < MIN_TIER_B_YEARS) {
            return unmeasured("returnPersistence", "Has it earned well on capital, year after year?",
                    "Equity or borrowings could not be read from enough years. NSE's older archive "
                            + "filings often carry the profit and loss account but not the balance "
                            + "sheet, so this is a gap in the filings rather than in the business.");
        }
        return count("returnPersistence", "Has it earned well on capital, year after year?",
                qualifying, measured,
                String.format("Return on capital employed was above %.0f%% in %d of %d measurable "
                        + "years. One strong year is a cycle; holding it is a moat.",
                        ROCE_BAR, qualifying, measured));
    }

    /** Did it stay unlevered? Not applicable to a lender, whose borrowing is its raw material. */
    private static Gate leverageDiscipline(List<AnnualFundamentalsEntity> h, boolean lender) {
        if (lender) {
            return new Gate("leverage", "Did it stay out of debt?", GateStatus.NOT_APPLICABLE, 0, 0,
                    "Not applicable to a bank or lender: borrowing is what it sells, not a risk it "
                            + "took on. Counting it either way would misdescribe the business.");
        }
        int qualifying = 0, measured = 0;
        for (AnnualFundamentalsEntity r : h) {
            Double de = r.debtToEquity();
            if (de == null) continue;
            measured++;
            if (de <= LEVERAGE_BAR) qualifying++;
        }
        if (measured < MIN_TIER_B_YEARS) {
            return unmeasured("leverage", "Did it stay out of debt?",
                    "Borrowings or equity could not be read from enough years — the same gap in "
                            + "older archive filings that limits the return-on-capital check.");
        }
        return count("leverage", "Did it stay out of debt?", qualifying, measured,
                String.format("Debt stayed at or below %.1fx equity in %d of %d measurable years.",
                        LEVERAGE_BAR, qualifying, measured));
    }

    // ---------------------------------------------------------------- helpers

    /** Return on capital employed for one year: (PBT + finance costs) / (equity + borrowings). */
    static Double roce(AnnualFundamentalsEntity r) {
        if (r.getEquity() == null) return null;
        double borrowings = r.getBorrowings() == null ? 0 : r.getBorrowings();
        double capital = r.getEquity() + borrowings;
        if (capital <= 0) return null;
        // Written as an if/else, NOT a ternary. `pbt + interest` is a primitive double, so a
        // conditional mixing it with the boxed getOperatingProfit() is promoted to double and
        // the fallback is UNBOXED before it can be tested - which threw NullPointerException
        // out of GET /api/fundamentals/long-horizon for any company whose row carries neither
        // pair, taking the whole capital-allocation and compounding panel down with it. The
        // null check below reads as though it guards that case; in a ternary it never runs.
        Double ebit;
        if (r.getProfitBeforeTax() != null && r.getInterestCost() != null) {
            ebit = r.getProfitBeforeTax() + r.getInterestCost();
        } else {
            ebit = r.getOperatingProfit();
        }
        if (ebit == null) return null;
        return ebit / capital * 100.0;
    }

    private static Gate count(String key, String question, int qualifying, int measured, String detail) {
        boolean pass = measured > 0 && qualifying >= Math.ceil(measured * REQUIRED_SHARE);
        return new Gate(key, question, pass ? GateStatus.PASS : GateStatus.FAIL,
                qualifying, measured, detail);
    }

    private static Gate unmeasured(String key, String question, String why) {
        return new Gate(key, question, GateStatus.NOT_MEASURED, 0, 0, why);
    }

    private static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        s.sort(Double::compareTo);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }
}
