package com.example.trading.universe.ipo;

/**
 * The arithmetic of applying (SPEC §45.5): what one lot costs, how many lots fit under each
 * SEBI category ceiling, and what each category means for allotment. Pure.
 *
 * <p>The ceilings are SEBI's (ICDR 2018 as amended), stated in rupees of application value:
 * retail up to 2 lakh; small non-institutional above 2 lakh up to 10 lakh; big non-institutional
 * above 10 lakh; eligible employees up to 5 lakh in total (2 lakh initially, a further 3 lakh only
 * if the reserved portion is under-subscribed); UPI as a payment route up to 5 lakh, above which
 * the application goes through a bank's ASBA. A shareholder-quota application of up to 2 lakh is
 * allotted on the retail basis (minimum one lot by lottery when oversubscribed).
 *
 * <p>Everything is computed at the <b>top of the price band</b>: retail bids at cut-off and the
 * allotment happens at the discovered price, which is the top of the band in nearly every
 * oversubscribed issue. Sizing at the bottom of the band is how an application overshoots the
 * category ceiling and gets rejected outright.
 */
public final class IpoApplicationMath {

    private IpoApplicationMath() {
    }

    public static final double RETAIL_CAP_RS = 2_00_000;
    public static final double SMALL_NII_CAP_RS = 10_00_000;
    public static final double EMPLOYEE_CAP_RS = 5_00_000;
    public static final double UPI_CAP_RS = 5_00_000;

    public record Sizing(Integer lotSize, Double upperBand, Double lotCostRs,
                         Integer retailMaxLots, Double retailMaxRs,
                         Integer smallNiiMinLots, Double smallNiiMinRs,
                         Integer bigNiiMinLots, Double bigNiiMinRs,
                         Integer employeeMaxLots, Double employeeMaxRs,
                         String note) {
        public boolean measured() {
            return lotCostRs != null;
        }
    }

    public static Sizing size(Integer lotSize, Double upperBand) {
        if (lotSize == null || lotSize <= 0 || upperBand == null || upperBand <= 0) {
            return new Sizing(lotSize, upperBand, null, null, null, null, null, null, null, null, null,
                    "Lot size or price band not published yet, so nothing can be sized.");
        }
        double lot = lotSize * upperBand;
        int retailLots = (int) Math.floor(RETAIL_CAP_RS / lot);
        int sniiLots = retailLots + 1;                       // the smallest count that crosses 2 lakh
        int bniiLots = (int) Math.floor(SMALL_NII_CAP_RS / lot) + 1; // the smallest count that crosses 10 lakh
        int employeeLots = (int) Math.floor(EMPLOYEE_CAP_RS / lot);
        String note = retailLots == 0
                ? "One lot alone exceeds the 2 lakh retail ceiling — this issue cannot be applied for in the retail category."
                : String.format("One lot is %d shares. A retail application is any number of lots up to %d; "
                        + "when the retail book is oversubscribed every applicant has the same chance of one lot "
                        + "regardless of how many they bid for, so one lot per PAN is the efficient bid.",
                        lotSize, retailLots);
        return new Sizing(lotSize, upperBand, lot,
                retailLots, retailLots * lot,
                sniiLots, sniiLots * lot,
                bniiLots, bniiLots * lot,
                employeeLots, employeeLots * lot,
                note);
    }

    /**
     * The chance of one lot when a retail book is oversubscribed, stated as "about 1 in N".
     * SEBI's basis of allotment gives every retail applicant an equal chance of the minimum lot
     * by lottery, so the odds are roughly the inverse of the subscription multiple. Null when the
     * figure is not final or the book was not oversubscribed (then everyone is allotted).
     */
    public static Integer retailOddsOneIn(Double retailTimes, Boolean subscriptionFinal) {
        if (retailTimes == null || !Boolean.TRUE.equals(subscriptionFinal)) return null;
        if (retailTimes <= 1.0) return 1;
        return (int) Math.round(retailTimes);
    }
}
