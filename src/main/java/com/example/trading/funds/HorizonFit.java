package com.example.trading.funds;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which fund categories are <b>built</b> for money you need in N months (SPEC §52.16).
 *
 * <p><b>Why this is answerable when "which fund will do best" is not.</b> §52.13 measured that past
 * ranking carries no information about future ranking, which is why this module publishes no ranked
 * list. This class does not rank anything and predicts nothing. It reads SEBI's own category
 * definitions - the mandated Macaulay duration bands in the 2017 categorisation circular and its
 * amendments - and reports what the category is <em>designed</em> to do. A Liquid fund may hold
 * nothing maturing beyond 91 days because a rule says so, not because a manager is skilful. That is
 * a fact about the instrument, and it does not decay the way a performance ranking does.
 *
 * <p><b>The default answer for short horizons is "none of them", and that is the honest one.</b>
 * Below three months this returns no category at all for equity, and the fitting debt categories
 * carry the tax and exit-load facts that usually make a bank deposit or a sweep account the better
 * answer. The class is built to be able to say that.
 *
 * <p>Three states, never two (Gotcha 121). {@link Fit#FITS} is a finding;
 * {@link Fit#LONGER_THAN_NEEDED} and {@link Fit#TOO_VOLATILE_FOR_THIS_HORIZON} are findings in the
 * other direction; {@link Fit#NOT_MEASURED} means AMFI filed the scheme under a header whose
 * mandate this app cannot pin to a horizon, which is a gap in the <em>feed</em>, not a statement
 * about the fund. They must never render alike.
 *
 * <p>This class is pure: no Spring, no repository, no clock, no I/O.
 */
public final class HorizonFit {

    private HorizonFit() {}

    /**
     * The longest horizon this class will answer for, in months.
     *
     * <p>Twelve years. Past it every equity category reads the same and the answer stops being
     * about the horizon, so extending the scale adds precision nobody has.
     */
    public static final int MAX_HORIZON_MONTHS = 144;

    /**
     * The horizon below which equity is refused outright, in months.
     *
     * <p><b>Thirty-six, and measured rather than assumed.</b> {@code FundPersistenceService}
     * computes the share of rolling 12-month windows in this app's own archive in which the equity
     * category median was negative; the same question at 36 months is what this floor rests on. It
     * is stated as a constant here so the refusal is arguable, and §52.16 records the figure the
     * live run produced.
     */
    public static final int EQUITY_FLOOR_MONTHS = 36;

    /** Where a category sits against the horizon asked about. */
    public enum Fit {
        /** The category's mandate matches this horizon. */
        FITS("Built for this"),
        /**
         * The category is built for a longer horizon than the money has. Holding it means the
         * value can be below what you put in on the day you need it.
         */
        TOO_VOLATILE_FOR_THIS_HORIZON("Longer money than this"),
        /**
         * The category is built for a shorter horizon. Not a mistake - it will not lose the money -
         * but over a long horizon it gives up return for safety that is not being used.
         */
        LONGER_THAN_NEEDED("Shorter money than this"),
        /**
         * AMFI filed these schemes under a header whose mandate spans too much to match a horizon,
         * or under no mandate at all. The app's gap, reported by name.
         */
        NOT_MEASURED("Not measured");

        private final String label;

        Fit(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One category's mandated horizon band, in months, straight from the SEBI circular.
     *
     * @param minMonths the shortest horizon the category suits, or 0
     * @param maxMonths the longest horizon at which it is still the right tool, or
     *                  {@link #MAX_HORIZON_MONTHS} when there is no upper bound
     * @param basis     the rule this band comes from, printed on screen so it can be checked
     */
    public record Band(int minMonths, int maxMonths, String basis) {}

    /**
     * The SEBI mandate per category.
     *
     * <p>The debt bands are the circular's Macaulay duration requirements, converted from a
     * portfolio duration to a holding horizon on the one principle that does not need a forecast:
     * <b>hold at least as long as the portfolio's duration and a rate move cannot cost you money</b>,
     * because the higher reinvestment yield offsets the capital loss over that span. Shorter than
     * the duration and it can.
     *
     * <p>The equity bands are not a mandate - SEBI sets no holding period for an equity fund - so
     * they carry {@link #EQUITY_FLOOR_MONTHS} and say so in their basis text. ELSS is the one
     * equity category with a statutory floor, and it is a hard one.
     */
    private static final Map<SebiCategory, Band> BANDS = new EnumMap<>(SebiCategory.class);

    static {
        // ---- debt, by mandated Macaulay duration ----
        BANDS.put(SebiCategory.DEBT_OVERNIGHT, new Band(0, 1,
                "SEBI requires every security to mature in one business day, so there is no "
                        + "interest-rate risk and no credit risk worth the name."));
        BANDS.put(SebiCategory.DEBT_LIQUID, new Band(0, 3,
                "SEBI caps every holding at 91 days to maturity."));
        BANDS.put(SebiCategory.DEBT_ULTRA_SHORT, new Band(3, 6,
                "SEBI requires a portfolio duration of 3 to 6 months."));
        BANDS.put(SebiCategory.DEBT_LOW_DURATION, new Band(6, 12,
                "SEBI requires a portfolio duration of 6 to 12 months."));
        BANDS.put(SebiCategory.DEBT_MONEY_MARKET, new Band(3, 12,
                "SEBI caps every holding at one year to maturity."));
        BANDS.put(SebiCategory.DEBT_SHORT_DURATION, new Band(12, 36,
                "SEBI requires a portfolio duration of 1 to 3 years."));
        BANDS.put(SebiCategory.DEBT_MEDIUM_DURATION, new Band(36, 48,
                "SEBI requires a portfolio duration of 3 to 4 years."));
        BANDS.put(SebiCategory.DEBT_MEDIUM_TO_LONG, new Band(48, 84,
                "SEBI requires a portfolio duration of 4 to 7 years."));
        BANDS.put(SebiCategory.DEBT_LONG_DURATION, new Band(84, MAX_HORIZON_MONTHS,
                "SEBI requires a portfolio duration above 7 years."));
        BANDS.put(SebiCategory.DEBT_CORPORATE_BOND, new Band(24, 60,
                "SEBI requires at least 80% in the highest-rated corporate paper, but sets no "
                        + "duration - so the horizon is the typical duration these funds run."));
        BANDS.put(SebiCategory.DEBT_BANKING_PSU, new Band(12, 48,
                "SEBI requires at least 80% in bank, public-sector and public-financial-institution "
                        + "paper, but sets no duration."));
        // Both of these were missing from the first live run and rendered NOT_MEASURED - which
        // says "this app has no rule", when in fact the app has a view and it is an unflattering
        // one. Gotcha 44's shape: an absence of findings presented as an absence of checking.
        BANDS.put(SebiCategory.DEBT_CREDIT_RISK, new Band(36, 60,
                "SEBI requires at least 65% in paper rated below the top grade, and sets no "
                        + "duration - so the horizon is not what decides this one. What decides it "
                        + "is whether the borrowers repay, and that is not in this feed at all. "
                        + "Both bad episodes in this app's archive happened here."));
        BANDS.put(SebiCategory.DEBT_DYNAMIC_BOND, new Band(36, MAX_HORIZON_MONTHS,
                "SEBI sets no duration for these at all - the manager moves it with their own view "
                        + "on interest rates. So what you are exposed to on any given day is not "
                        + "fixed, is not published here, and can be anything from a liquid fund to "
                        + "a gilt fund. The horizon below assumes the long end, because that is "
                        + "the one that can cost you money."));
        BANDS.put(SebiCategory.DEBT_FLOATER, new Band(6, 36,
                "SEBI requires at least 65% in floating-rate instruments, whose price moves far "
                        + "less when rates move."));
        BANDS.put(SebiCategory.DEBT_GILT, new Band(60, MAX_HORIZON_MONTHS,
                "SEBI requires at least 80% in government securities. No credit risk at all, and "
                        + "the most interest-rate risk in the debt table."));
        BANDS.put(SebiCategory.DEBT_GILT_10Y, new Band(84, MAX_HORIZON_MONTHS,
                "SEBI fixes the portfolio duration at 10 years, so a rate move is felt in full."));
        BANDS.put(SebiCategory.INDEX_DEBT, new Band(12, MAX_HORIZON_MONTHS,
                "A debt index fund follows a stated maturity profile; most track a target-maturity "
                        + "date, which is the horizon that matters and is not in this feed."));

        // ---- hybrid ----
        BANDS.put(SebiCategory.HYBRID_ARBITRAGE, new Band(3, 24,
                "SEBI requires at least 65% in offsetting cash and futures positions, so the return "
                        + "comes from the gap between the two rather than from the market direction."));
        BANDS.put(SebiCategory.HYBRID_CONSERVATIVE, new Band(24, 60,
                "SEBI requires 10-25% in equity and the rest in debt."));
        BANDS.put(SebiCategory.HYBRID_EQUITY_SAVINGS, new Band(24, 60,
                "SEBI requires at least 65% in equity of which much is hedged, plus at least 10% "
                        + "in debt - so the equity actually at risk is usually 20-40%."));
        BANDS.put(SebiCategory.HYBRID_BALANCED, new Band(36, MAX_HORIZON_MONTHS,
                "SEBI requires 40-60% in equity."));
        BANDS.put(SebiCategory.HYBRID_BALANCED_ADVANTAGE, new Band(36, MAX_HORIZON_MONTHS,
                "The equity share moves with the manager's own model, so what you are exposed to "
                        + "on any given day is not fixed and is not published in this feed."));
        BANDS.put(SebiCategory.HYBRID_AGGRESSIVE, new Band(EQUITY_FLOOR_MONTHS, MAX_HORIZON_MONTHS,
                "SEBI requires 65-80% in equity, which is most of the way to an equity fund."));
        BANDS.put(SebiCategory.HYBRID_MULTI_ASSET, new Band(36, MAX_HORIZON_MONTHS,
                "SEBI requires at least 10% in each of three asset classes."));

        // ---- equity: no mandated horizon exists, so the floor is this app's own ----
        String equityBasis = "SEBI sets no holding period for an equity fund. The floor of "
                + EQUITY_FLOOR_MONTHS + " months is this app's own, and §52.16 records the share of "
                + "rolling windows in its archive over which the category median was negative.";
        for (SebiCategory c : SebiCategory.values()) {
            if (c.assetClass() == AssetClass.EQUITY && c != SebiCategory.EQUITY_ETF) {
                BANDS.put(c, new Band(EQUITY_FLOOR_MONTHS, MAX_HORIZON_MONTHS, equityBasis));
            }
        }
        // ELSS is the exception with a statutory floor, and it binds per instalment.
        BANDS.put(SebiCategory.ELSS, new Band(60, MAX_HORIZON_MONTHS,
                "Every instalment is locked for three years from the day it is invested under "
                        + "section 80C, so the last instalment of a monthly plan is locked three "
                        + "years after that - which is why the honest floor is five years, not three."));
    }

    /**
     * What one category is for, and where it sits against a horizon.
     *
     * @param fit           where the horizon falls against the mandate
     * @param bandFromMonth the mandate's lower bound, null when there is no rule for the category
     * @param bandToMonth   the mandate's upper bound, null when there is no rule
     * @param basis         the rule the band comes from, or why there is none
     */
    public record Read(SebiCategory category, String categoryLabel, Fit fit,
                       Integer bandFromMonth, Integer bandToMonth, String basis) {}

    /**
     * Where {@code category} sits against a horizon of {@code months}.
     *
     * <p>Never throws. A category with no band reads {@link Fit#NOT_MEASURED} with the reason, which
     * is the right answer for the pre-2018 buckets whose mandate spans an order of magnitude of
     * duration, and for anything the resolver could not pin.
     */
    public static Read forCategory(SebiCategory category, int months) {
        if (category == null) {
            return new Read(SebiCategory.UNCLASSIFIED, "Not classified", Fit.NOT_MEASURED,
                    null, null, "no category");
        }
        Band band = BANDS.get(category);
        if (band == null) {
            return new Read(category, category.label(), Fit.NOT_MEASURED, null, null,
                    noBandReason(category));
        }
        Fit fit;
        if (months < band.minMonths()) {
            fit = Fit.TOO_VOLATILE_FOR_THIS_HORIZON;
        } else if (months > band.maxMonths()) {
            fit = Fit.LONGER_THAN_NEEDED;
        } else {
            fit = Fit.FITS;
        }
        return new Read(category, category.label(), fit, band.minMonths(), band.maxMonths(),
                band.basis());
    }

    private static String noBandReason(SebiCategory category) {
        if (category == SebiCategory.DEBT) {
            return "AMFI files these schemes under a pre-2018 header whose mandate runs from three "
                    + "months to three years. That is too wide to match to a horizon, so nothing is "
                    + "claimed - a gap in the published category, not a judgement on the schemes.";
        }
        if (category == SebiCategory.HYBRID) {
            return "AMFI files these schemes under a pre-2018 header that does not say how much of "
                    + "the money is in equity, which is the only thing that decides the horizon.";
        }
        if (category.isExchangeTraded()) {
            return "An exchange-traded fund is bought at a market price that can sit away from its "
                    + "net asset value, so this module does not follow it at all.";
        }
        return "No SEBI duration or asset mandate this app can read for " + category.label() + ".";
    }

    /** Every category that fits {@code months}, in the order they appear in {@link SebiCategory}. */
    public static List<Read> fitting(int months) {
        return java.util.Arrays.stream(SebiCategory.values())
                .map(c -> forCategory(c, months))
                .filter(r -> r.fit() == Fit.FITS)
                .toList();
    }

    /**
     * The facts that decide a short-horizon answer and are <b>not</b> about which fund to pick.
     *
     * <p>These are why §52.16 exists at all. Over a horizon under a year the return on a debt fund
     * is close to the prevailing short rate whichever fund you choose, so what is left to decide is
     * tax, exit load and whether a fund is the right wrapper at all. Every line here is a rule
     * anyone can check, not a projection.
     *
     * <p>Written for a reader who is explicitly not a market professional (SPEC §21 rule 1), and it
     * contains no instruction to transact - a fact about the tax code is not advice.
     */
    public static List<String> realities(int months) {
        List<String> out = new java.util.ArrayList<>();
        out.add("Gains on a debt or hybrid fund bought after 1 April 2023 are added to your income "
                + "and taxed at your slab rate whatever the holding period, so the tax does not "
                + "improve by waiting. On an equity fund, a gain realised inside 12 months is taxed "
                + "at 20% and after that at 12.5% above the yearly exemption.");
        if (months < 12) {
            out.add("Most equity funds charge an exit load of about 1% if you take the money out "
                    + "inside a year. Liquid funds charge a small graded load for the first week and "
                    + "nothing after it; overnight funds charge none.");
        }
        if (months <= 3) {
            out.add("Over a period this short the honest comparison is not between funds but "
                    + "between a fund and a bank sweep account. A liquid fund typically holds a "
                    + "small edge on rate; the bank account settles the same day and has no tax "
                    + "paperwork. The difference over three months on a lakh is a few hundred rupees.");
        }
        if (months < EQUITY_FLOOR_MONTHS) {
            out.add("No equity category appears above, and that is the finding rather than a gap. "
                    + "Over a span this short an equity fund's return is the market's direction, "
                    + "and this app deleted a whole feature for trying to forecast that (SPEC §39.5).");
        }
        out.add("Nothing here ranks funds or names one worth owning. §52.13 measured on this "
                + "archive that past ranking carried no information about future ranking, so within "
                + "a category that fits, the things left to compare are cost and what the fund is "
                + "allowed to hold.");
        return out;
    }

    /** Every category with its band, for the screen that prints the rule table itself. */
    public static Map<SebiCategory, Band> bands() {
        return new LinkedHashMap<>(BANDS);
    }
}
