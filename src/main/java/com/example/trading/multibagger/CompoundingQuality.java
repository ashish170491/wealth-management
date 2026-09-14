package com.example.trading.multibagger;

import com.example.trading.fundamentals.ForensicSeverity;

import java.util.ArrayList;
import java.util.List;

/**
 * "Can this business compound earnings and capital?" — the long-horizon quality lens (SPEC §41).
 *
 * <h2>Why this exists</h2>
 * The 0-100 composite gives <b>59% of its weight to price behaviour</b> (momentum, volume,
 * relative strength, price structure). That is a reasonable way to rank what is working now and a
 * poor way to answer the question this platform exists for: will this business still be earning
 * more, on more capital, in ten years. Nothing on any screen answered that directly, even though
 * every input below was already computed and persisted per stock.
 *
 * <h2>The arithmetic it is built on</h2>
 * A business grows its intrinsic value at roughly <i>return on capital × the share of profit it
 * reinvests</i>. Earn 25% on capital, retain 60%, and value compounds around 15% a year — about
 * 4x in a decade. So the first gate is the return on capital itself, and the rest ask whether that
 * return is <em>real</em> (cash, not accruals), <em>self-funded</em> (not borrowed) and
 * <em>durable</em> (steady, with margins that are not being competed away).
 *
 * <h2>What it deliberately does NOT do</h2>
 * <ul>
 *   <li><b>It never enters the composite.</b> A new signal ships in shadow here (Gotcha 30, SPEC
 *       §20 rule 9): computed, shown, IC-measured, worth zero points. Same contract as the
 *       Under-Discovery lens (§12.10). Promotion is only ever through §38.10.</li>
 *   <li><b>It does not gate on reinvestment</b>, though that is half the arithmetic above.
 *       Retention needs the dividend payout ratio, which the capital-efficiency analysis computes
 *       and then discards rather than persisting. Gating on {@code capexVerdict} instead was tried
 *       and rejected: only 83 of 288 stocks read {@code INVESTING}, and many that read
 *       {@code STEADY} are the asset-light businesses with the <em>highest</em> returns on capital
 *       — CAMS, CDSL and Oracle Financial among them. A gate that failed a business for not
 *       needing factories would invert the very thing being measured. Capex is shown as context
 *       and scored at nothing until payout is persisted.</li>
 *   <li><b>It does not claim persistence.</b> Every gate is evaluated on the <b>latest single
 *       year</b> of accounts. Whether a 25% return has held for eight years is the strongest
 *       compounder evidence there is, and it is unanswerable for about 93% of the universe today
 *       (multi-year history exists for roughly 19 of 288 stocks). {@code yearsOfAccounts} carries
 *       that count so a caller can say "one year, not yet a track record" rather than implying
 *       more than was checked.</li>
 * </ul>
 *
 * <h2>Thresholds are measured, not assumed</h2>
 * Set against the live 2026-09-05 cross-section of 288 stocks, the same discipline as
 * {@link ScreenerTimingVerdict}:
 * <pre>
 *   ROCE                 n=229  p10  7.7   median 16.9   p75 24.1   p90 31.6  -&gt; bar at 18
 *   cash conversion      n=252  p10 -1.1   median  1.13  p75  1.58            -&gt; bar at 0.8
 *   debt / equity        n=229  p10  0.0   median  0.10  p75  0.37  p90 0.81  -&gt; bar at 0.5
 *   earnings consistency n=280  p10  0     median 41     p75 60               -&gt; bar at 50
 *   gross-margin trend   n=261  p10 -4.4   median  0.00  p75  1.27            -&gt; bar at 0
 * </pre>
 * The ROCE bar sits between the median and the upper quartile and comfortably above a ~12% cost of
 * capital, so clearing it means the business creates value rather than merely surviving. On that
 * run the whole gate set named <b>22 of 288 stocks (8%)</b>.
 *
 * <h2>Null and not-applicable discipline</h2>
 * A gate is {@link GateStatus#NOT_MEASURED} when its input is null, and
 * {@link GateStatus#NOT_APPLICABLE} when the question does not apply to the business — leverage and
 * gross margin for a lender, whose borrowing <em>is</em> its raw material. Neither counts as a pass
 * and neither counts toward the denominator (Gotcha 68: a gate that passes on absent data is a
 * pass, not evidence). An unmeasured check is never rendered as a failure, and an absence of red
 * flags is never rendered as a clean bill of health (Gotcha 44).
 */
public final class CompoundingQuality {

    private CompoundingQuality() {
    }

    /** How the business scores on the five checks, taken together. */
    public enum Verdict {
        /** Every applicable check passed, on enough of them to mean something. */
        COMPOUNDER,
        /** Some checks passed. A business with a strength and a weakness, not a compounder. */
        PARTIAL,
        /** Most checks failed, or a disqualifying red flag was found. */
        NO,
        /** Too little was measurable to say anything. Never to be shown as a failure. */
        NOT_MEASURED
    }

    /** Per-check outcome. NOT_APPLICABLE and NOT_MEASURED are excluded from the denominator. */
    public enum GateStatus { PASS, FAIL, NOT_APPLICABLE, NOT_MEASURED }

    /** Every field nullable — null means "could not measure", never a neutral or zero value. */
    public record Input(
            Double rocePercent,
            Double roaPercent,
            Double cashConversionRatio,
            Double debtToEquity,
            Integer earningsConsistencyScore,
            Double grossMarginTrend,
            String capexVerdict,
            String forensicFlags,
            String financialQualityVerdict,
            Integer yearsOfAccounts) {
    }

    /** One check: the question in the reader's words, the outcome, and the figure behind it. */
    public record Gate(String key, String question, GateStatus status, String detail) {
    }

    /**
     * @param passed     applicable checks that passed
     * @param applicable checks that were both measurable and relevant — the denominator
     * @param reason     one plain sentence naming what decided the verdict
     */
    public record Result(
            Verdict verdict,
            List<Gate> gates,
            int passed,
            int applicable,
            String reason,
            Integer yearsOfAccounts,
            String capexContext) {

        /** True only for the badge. Kept explicit so a UI cannot infer it from a score. */
        public boolean isCompounder() {
            return verdict == Verdict.COMPOUNDER;
        }
    }

    // Thresholds — see the class javadoc for the distribution each was set against.
    static final double MIN_ROCE = 18.0;
    static final double MIN_ROA_FINANCIAL = 1.5;
    static final double MIN_CASH_CONVERSION = 0.8;
    static final double MAX_DEBT_TO_EQUITY = 0.5;
    static final int MIN_CONSISTENCY = 50;

    /** Below this many applicable checks nothing honest can be concluded. */
    static final int MIN_APPLICABLE = 3;
    /** A non-financial has five relevant checks; it must clear at least four to qualify. */
    static final int MIN_APPLICABLE_NON_FINANCIAL = 4;
    /**
     * A lender has exactly two relevant checks, so two is both its floor and its bar.
     *
     * <p>Three of the five do not apply to a lender: leverage and gross margin by design
     * (borrowing is its raw material; it has no cost of goods), and cash conversion because a
     * bank's operating cash flow is dominated by deposit and loan flows rather than by the
     * quality of its earnings - {@code OCF / profit} is noise for a lender, not a signal. The
     * old rule left financials measurable on 3 and applicable on 2, so **every one of the 24
     * financials in the universe read NOT_MEASURED** - a whole business class silently
     * unjudgeable, which is the failure this file's own null discipline exists to prevent.
     *
     * <p>Two checks is genuinely thinner evidence than a non-financial's four, so the verdict
     * says so in words rather than hiding it behind an identical badge.
     */
    static final int MIN_APPLICABLE_FINANCIAL = 2;

    public static Result evaluate(Input in) {
        if (in == null) {
            return new Result(Verdict.NOT_MEASURED, List.of(), 0, 0,
                    "No screening row for this stock.", null, null);
        }

        // Banks and NBFCs file under a different taxonomy: capex does not apply to them, and that
        // marker is the reliable discriminator (Gotcha 88 — capitalEfficiencyVerdict is NOT: it
        // grades a bank SOLID on ROE/ROA and its own NA marker never fires).
        boolean financial = "NA_FINANCIAL".equals(in.capexVerdict());

        List<Gate> gates = new ArrayList<>(5);
        gates.add(capitalReturnGate(in, financial));
        gates.add(cashGate(in, financial));
        gates.add(fundingGate(in, financial));
        gates.add(steadinessGate(in));
        gates.add(marginGate(in, financial));

        int passed = (int) gates.stream().filter(g -> g.status() == GateStatus.PASS).count();
        int applicable = (int) gates.stream()
                .filter(g -> g.status() == GateStatus.PASS || g.status() == GateStatus.FAIL).count();

        String capexContext = capexContext(in.capexVerdict());

        int floor = financial ? MIN_APPLICABLE_FINANCIAL : MIN_APPLICABLE;
        if (applicable < floor) {
            return new Result(Verdict.NOT_MEASURED, gates, passed, applicable,
                    "Only " + applicable + " of the five checks could be measured, which is too few to "
                            + "judge. That is a gap in the filings available, not a mark against the business.",
                    in.yearsOfAccounts(), capexContext);
        }

        // Disqualifiers. A serious accounting flag, or a balance sheet the screen already called
        // fragile, ends the question: every quality figure above is computed FROM the accounts, so
        // a doubt about the accounts makes a clean reading meaningless rather than reassuring.
        String disqualifier = disqualifier(in);
        if (disqualifier != null) {
            return new Result(Verdict.NO, gates, passed, applicable, disqualifier,
                    in.yearsOfAccounts(), capexContext);
        }

        int required = financial ? MIN_APPLICABLE_FINANCIAL : MIN_APPLICABLE_NON_FINANCIAL;
        if (passed == applicable && applicable >= required) {
            // A lender's badge rests on two checks where another business needs four. Saying so
            // is the whole reason the rule is allowed to be different: the alternative was a
            // verdict nobody could ever earn.
            String basis = financial
                    ? "Passed both checks that apply to a lender - return on assets and earnings "
                      + "steadiness. Leverage, margins and cash conversion are not meaningful "
                      + "tests for a bank, so this rests on two checks rather than four."
                    : "Passed all " + applicable + " checks that apply to this business.";
            return new Result(Verdict.COMPOUNDER, gates, passed, applicable, basis,
                    in.yearsOfAccounts(), capexContext);
        }
        if (passed >= 2) {
            return new Result(Verdict.PARTIAL, gates, passed, applicable,
                    "Passed " + passed + " of " + applicable + " checks. " + firstFailure(gates),
                    in.yearsOfAccounts(), capexContext);
        }
        return new Result(Verdict.NO, gates, passed, applicable,
                "Passed only " + passed + " of " + applicable + " checks. " + firstFailure(gates),
                in.yearsOfAccounts(), capexContext);
    }

    /** The engine: what the business earns on the money invested in it. */
    private static Gate capitalReturnGate(Input in, boolean financial) {
        if (financial) {
            Double roa = in.roaPercent();
            if (roa == null) {
                return new Gate("capitalReturn", "Earns well on the money in it",
                        GateStatus.NOT_MEASURED, "Return on assets not published");
            }
            return new Gate("capitalReturn", "Earns well on the money in it",
                    roa >= MIN_ROA_FINANCIAL ? GateStatus.PASS : GateStatus.FAIL,
                    String.format("Return on assets %.1f%% — a lender or insurer is judged on this "
                            + "rather than on return on capital, and %.1f%% is the bar",
                            roa, MIN_ROA_FINANCIAL));
        }
        Double roce = in.rocePercent();
        if (roce == null) {
            return new Gate("capitalReturn", "Earns well on the money in it",
                    GateStatus.NOT_MEASURED,
                    "Return on capital could not be calculated from the filings");
        }
        return new Gate("capitalReturn", "Earns well on the money in it",
                roce >= MIN_ROCE ? GateStatus.PASS : GateStatus.FAIL,
                String.format("Earns %.1f%% a year on the capital in the business — the bar is %.0f%%, "
                        + "and borrowing costs about 12%%", roce, MIN_ROCE));
    }

    /** Whether the profit is cash or only an accounting entry. */
    private static Gate cashGate(Input in, boolean financial) {
        if (financial) {
            // Not a gap in the filings - a question that does not apply. A lender's operating
            // cash flow is dominated by deposit and loan movements, so OCF/profit measures the
            // size of the loan book's swings, not the quality of the earnings. Measured across
            // the universe it is published for only 4 of 24 financials anyway, and treating that
            // as "not measured" left every bank unjudgeable.
            return new Gate("realCash", "Profit arrives as real cash",
                    GateStatus.NOT_APPLICABLE,
                    "A lender's cash flow is driven by deposits and lending, so this is not a "
                    + "meaningful test of earnings quality");
        }
        Double cc = in.cashConversionRatio();
        if (cc == null) {
            return new Gate("realCash", "Profit arrives as real cash",
                    GateStatus.NOT_MEASURED, "Cash-flow statement not available");
        }
        return new Gate("realCash", "Profit arrives as real cash",
                cc >= MIN_CASH_CONVERSION ? GateStatus.PASS : GateStatus.FAIL,
                String.format("%.2f of every rupee of profit showed up as cash from operations", cc));
    }

    /** Whether growth is funded by the business or by lenders. */
    private static Gate fundingGate(Input in, boolean financial) {
        if (financial) {
            return new Gate("internallyFunded", "Growth funded from its own profits",
                    GateStatus.NOT_APPLICABLE,
                    "Borrowing is the raw material of a lender, so this measure does not apply");
        }
        Double de = in.debtToEquity();
        if (de == null) {
            return new Gate("internallyFunded", "Growth funded from its own profits",
                    GateStatus.NOT_MEASURED, "Borrowings not published");
        }
        return new Gate("internallyFunded", "Growth funded from its own profits",
                de <= MAX_DEBT_TO_EQUITY ? GateStatus.PASS : GateStatus.FAIL,
                String.format("Owes %.2f for every rupee the owners put in — %.1f is the bar",
                        de, MAX_DEBT_TO_EQUITY));
    }

    /** Whether earnings are steady rather than lumpy — a compounder does it repeatedly. */
    private static Gate steadinessGate(Input in) {
        Integer c = in.earningsConsistencyScore();
        if (c == null) {
            return new Gate("steady", "Earnings are steady, not lumpy",
                    GateStatus.NOT_MEASURED, "Not enough quarters on file to judge");
        }
        return new Gate("steady", "Earnings are steady, not lumpy",
                c >= MIN_CONSISTENCY ? GateStatus.PASS : GateStatus.FAIL,
                "Consistency " + c + " out of 100 — " + MIN_CONSISTENCY + " is the bar");
    }

    /** Margins holding up is the closest thing here to evidence of pricing power. */
    private static Gate marginGate(Input in, boolean financial) {
        if (financial) {
            return new Gate("margins", "Margins are holding up", GateStatus.NOT_APPLICABLE,
                    "A lender has no gross margin to measure");
        }
        Double t = in.grossMarginTrend();
        if (t == null) {
            return new Gate("margins", "Margins are holding up",
                    GateStatus.NOT_MEASURED, "Gross margin could not be calculated");
        }
        return new Gate("margins", "Margins are holding up",
                t >= 0 ? GateStatus.PASS : GateStatus.FAIL,
                t >= 0
                        ? String.format("Gross margin up %.1f points on last year", t)
                        : String.format("Gross margin down %.1f points on last year — a sign "
                                + "competitors are taking the pricing", Math.abs(t)));
    }

    /**
     * A doubt about the accounts, or a balance sheet the screen already called fragile.
     * Only HIGH severity disqualifies: MEDIUM is a caution and INFO is not a stop (Gotcha 77).
     */
    private static String disqualifier(Input in) {
        ForensicSeverity.Level worst = ForensicSeverity.worst(in.forensicFlags());
        if (worst == ForensicSeverity.Level.HIGH) {
            String flag = ForensicSeverity.firstAt(in.forensicFlags(), ForensicSeverity.Level.HIGH);
            return "A serious question about the accounts"
                    + (flag != null ? " (" + flag + ")" : "")
                    + ". Every quality figure here is calculated from those same accounts, so a "
                    + "clean reading cannot be trusted while that stands.";
        }
        if ("HIGH_RISK".equals(in.financialQualityVerdict())) {
            return "The balance-sheet screen rates this business high-risk, which no return on "
                    + "capital can offset — a company that does not survive cannot compound.";
        }
        return null;
    }

    private static String firstFailure(List<Gate> gates) {
        for (Gate g : gates) {
            if (g.status() == GateStatus.FAIL) {
                return "Weakest point: " + g.question().toLowerCase() + ".";
            }
        }
        return "";
    }

    /**
     * Capex shown as context, never scored. See the class javadoc for why gating on it would
     * penalise exactly the asset-light businesses with the highest returns on capital.
     */
    private static String capexContext(String capexVerdict) {
        if (capexVerdict == null) return null;
        return switch (capexVerdict) {
            case "EXPANSION_UNDERWAY" -> "Building new capacity right now";
            case "INVESTING" -> "Spending more than it wears out — growing the asset base";
            case "STEADY" -> "Spending about what it wears out. For an asset-light business that "
                    + "is normal, not a weakness";
            case "HARVESTING" -> "Spending less than it wears out — taking cash out rather than building";
            default -> null;
        };
    }
}
