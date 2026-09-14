package com.example.trading.analyst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the headline parser (SPEC §49.3).
 *
 * <p>The cases that matter here are the refusals. A missed target costs one row; a rupee amount
 * misread as a price target files a claim of several hundred per cent upside that then dominates
 * every average on the scoreboard.
 */
class AnalystTargetParserTest {

    @Test
    @DisplayName("An attributed target is read whole: house, figure, and what the house did")
    void readsAnAttributedTarget() {
        AnalystTargetParser.Result r = AnalystTargetParser.parse(
                "Motilal Oswal raises Bharti Airtel target price to Rs 2,100");

        assertThat(r.ok()).isTrue();
        assertThat(r.call().brokerage()).isEqualTo("Motilal Oswal");
        assertThat(r.call().targetPrice()).isEqualTo(2100.0);
        assertThat(r.call().action()).isEqualTo(AnalystTargetParser.Action.RAISE_TARGET);
    }

    @Test
    @DisplayName("A rupee amount carrying a scale word is never a price target")
    void crorAmountsAreNotTargets() {
        // This is the failure the parser exists to prevent. An order book read as a per-share
        // target puts a 1,200-rupee "target" on a 90-rupee stock, and that single row would then
        // carry every median on the scoreboard (Gotcha 48's shape).
        assertThat(AnalystTargetParser.parse(
                "Nomura sees order book target of Rs 1,200 crore for the company").ok()).isFalse();
        assertThat(AnalystTargetParser.parse(
                "Kotak: revenue target Rs 900 million by FY27").ok()).isFalse();
    }

    @Test
    @DisplayName("An aggregate target abandons the headline; it does not fall through to the next number")
    void aggregateTargetDoesNotFallThroughToAYear() {
        // Found while building this: rejecting "Rs 900 million" and then continuing would have
        // picked up "2027" and filed a price target of Rs 2,027 from a headline about revenue.
        // A percentage rejection means "the level follows"; a scale rejection means "this keyword
        // is not about a share price at all".
        assertThat(AnalystTargetParser.parse(
                "Kotak: revenue target of Rs 900 million by 2027").ok()).isFalse();
    }

    @Test
    @DisplayName("A number glued to letters is not an amount")
    void fiscalYearIsNotAnAmount() {
        // "by FY27" was being read as a target of Rs 27.
        assertThat(AnalystTargetParser.parse(
                "Kotak: revenue target Rs 900 million by FY27").ok()).isFalse();
    }

    @Test
    @DisplayName("A percentage after the target keyword does not stop the real figure being found")
    void skipsRejectedCandidateAndKeepsLooking() {
        // "target price cut 20% to Rs 1,150" has a bad candidate before the good one. Stopping at
        // the first match would drop a perfectly good target.
        AnalystTargetParser.Result r = AnalystTargetParser.parse(
                "Citi cuts Infosys target price by 20% to Rs 1,150");

        assertThat(r.ok()).isTrue();
        assertThat(r.call().targetPrice()).isEqualTo(1150.0);
        assertThat(r.call().action()).isEqualTo(AnalystTargetParser.Action.CUT_TARGET);
    }

    @Test
    @DisplayName("A rating change is filed under the rating it moved TO, not the one it left")
    void ratingAfterToWins() {
        // Reading the first rating word instead would file every upgrade under the rating it was
        // upgraded away from, inverting exactly the calls that matter most.
        AnalystTargetParser.Result up = AnalystTargetParser.parse(
                "Jefferies upgrades stock to Buy from Neutral; target Rs 950");
        assertThat(up.call().rating()).isEqualTo(AnalystTargetParser.Rating.BUY);
        assertThat(up.call().action()).isEqualTo(AnalystTargetParser.Action.UPGRADE);

        AnalystTargetParser.Result down = AnalystTargetParser.parse(
                "Kotak downgrades Infosys to Sell from Add, target price Rs 1,150");
        assertThat(down.call().rating()).isEqualTo(AnalystTargetParser.Rating.SELL);
    }

    @Test
    @DisplayName("'buy' inside 'buyback' is not a Buy rating")
    void wholeWordRatingMatching() {
        // Gotcha 53's family: before matching a term as a substring, check what contains it.
        AnalystTargetParser.Result r = AnalystTargetParser.parse(
                "Emkay keeps target at Rs 400 after buyback announcement");

        assertThat(r.ok()).isTrue();
        assertThat(r.call().rating()).isEqualTo(AnalystTargetParser.Rating.NOT_STATED);
    }

    @Test
    @DisplayName("'underperform' is not 'perform', and is a Sell")
    void underperformIsNotPerform() {
        AnalystTargetParser.Result r = AnalystTargetParser.parse(
                "CLSA rates stock Underperform with target price Rs 600");
        assertThat(r.call().rating()).isEqualTo(AnalystTargetParser.Rating.SELL);
    }

    @Test
    @DisplayName("A target nobody is named for is dropped, not filed as 'unnamed'")
    void unattributedTargetsAreDropped() {
        AnalystTargetParser.Result r = AnalystTargetParser.parse(
                "Brokerages see upside, target price Rs 1,400 for the stock");

        assertThat(r.ok()).isFalse();
        assertThat(r.reject()).isEqualTo(AnalystTargetParser.Reject.NO_BROKERAGE);
    }

    @Test
    @DisplayName("A rating change with no figure is not a target and belongs to the §24 signal")
    void ratingWithoutTargetIsNotALedgerRow() {
        AnalystTargetParser.Result r = AnalystTargetParser.parse("Nomura downgrades Infosys to Neutral");

        assertThat(r.ok()).isFalse();
        assertThat(r.reject()).isEqualTo(AnalystTargetParser.Reject.NO_TARGET);
    }

    @Test
    @DisplayName("An unstated horizon is null — never the twelve-month convention")
    void horizonIsNullWhenUnstated() {
        // The convention is applied by the capture service and recorded as an assumption
        // (horizonStated=false). B-057 and B-097 were each this mistake: a default read back as
        // something someone actually said.
        assertThat(AnalystTargetParser.parse("UBS initiates coverage with Buy, target Rs 300")
                .call().horizonMonths()).isNull();

        assertThat(AnalystTargetParser.parse("CLSA sets 12-month target price of Rs 500")
                .call().horizonMonths()).isEqualTo(12);

        assertThat(AnalystTargetParser.parse("HSBC sees one-year target of Rs 500")
                .call().horizonMonths()).isEqualTo(12);
    }

    @Test
    @DisplayName("Coverage initiation is recognised as its own action")
    void initiationIsAnAction() {
        assertThat(AnalystTargetParser.parse("UBS initiates coverage with Buy, target Rs 300")
                .call().action()).isEqualTo(AnalystTargetParser.Action.INITIATE);
    }

    @Test
    @DisplayName("An absurd figure is rejected rather than recorded")
    void implausibleTargetsRejected() {
        // 8,00,000 is a market capitalisation or a parse of the wrong number. MRF, the most
        // expensive share on the exchange, has never traded near 2,00,000.
        AnalystTargetParser.Result r = AnalystTargetParser.parse(
                "Nomura sets target price of Rs 800000 for the company");
        assertThat(r.ok()).isFalse();
    }

    // ---------------------------------------------------------------- real headlines, 2026-09-12
    //
    // The nine cases below are verbatim from 162 live Indian market headlines pulled off Google
    // News while building this. They are here because the invented cases above all passed while
    // the real ones did not: the first draft accepted 14 rows of which 3 were nonsense, and every
    // one of those 3 would have carried a median on the scoreboard.

    @Test
    @DisplayName("A year after 'Share Price Target' is a year, not a price")
    void yearIsNotAPrice() {
        // Read as a target of Rs 2,026. This is why the currency marker is now required.
        assertThat(AnalystTargetParser.parse(
                "Bharti Airtel Share Price Target 2026: 'Highest ARPU' - Buy for 40% returns, "
                        + "says Axis Securities").ok()).isFalse();
    }

    @Test
    @DisplayName("An index target is not a target for any company the headline also names")
    void indexTargetIsNotAStockTarget() {
        // Read as a Rs 26,000 target on Eternal. Both halves look right in isolation, which is
        // what makes this the worst shape of error the ledger can make.
        assertThat(AnalystTargetParser.parse(
                "Bernstein reshuffles India model portfolio: Adani Ports, Eternal, Paytm in, "
                        + "DMart out; sets Nifty 50 target at 26,000").ok()).isFalse();
    }

    @Test
    @DisplayName("'up 200% in 1 year' does not become a target of one rupee")
    void trailingYearCountIsNotAPrice() {
        assertThat(AnalystTargetParser.parse(
                "Ather Energy shares rise 3% as Nomura raises target price; stock up nearly 200% "
                        + "in 1 year").ok()).isFalse();
    }

    @Test
    @DisplayName("The real targets in that sample all still parse")
    void realTargetsStillParse() {
        assertParsed("Avalon Technologies hits record high, gains 10% as Nomura raises target to ₹2,767",
                "Nomura", 2767.0);
        assertParsed("Park Medi World gets rerating boost as Ventura raises target price to Rs 406; "
                + "sees 41.9% upside", "Ventura", 406.0);
        assertParsed("Ather Energy Stock Climbs 4% As Nomura Raises Target Price To ₹1,926",
                "Nomura", 1926.0);
        assertParsed("ICICI Bank stock: Goldman Sachs raises target to Rs 2,000, sees 40% upside "
                + "on FCNR deposit haul", "Goldman Sachs", 2000.0);
        assertParsed("Adani Enterprises Share Price Target at Rs 3,880: Motilal Oswal",
                "Motilal Oswal", 3880.0);
    }

    @Test
    @DisplayName("A headline that only claims a percentage upside records nothing")
    void percentOnlyClaimsAreAKnownGap() {
        // Most of the sample looked like this — "check target price", "sees 46% upside" — with the
        // rupee figure in the article body rather than the headline. Deriving a level from a
        // percentage and a close is guessing at a number nobody published, so it is recorded as a
        // coverage gap (SPEC §49.7) rather than filled in.
        assertThat(AnalystTargetParser.parse(
                "Jefferies sees 46% upside potential in this Adani stock").ok()).isFalse();
        assertThat(AnalystTargetParser.parse(
                "Nuvama says 'Buy' Marico; check 12-month price target").ok()).isFalse();
    }

    private static void assertParsed(String headline, String house, double target) {
        AnalystTargetParser.Result r = AnalystTargetParser.parse(headline);
        assertThat(r.ok()).as("should parse: %s", headline).isTrue();
        assertThat(r.call().brokerage()).isEqualTo(house);
        assertThat(r.call().targetPrice()).isEqualTo(target);
    }

    @Test
    @DisplayName("Nothing at all is a refusal, not a crash")
    void nullSafe() {
        assertThat(AnalystTargetParser.parse(null).ok()).isFalse();
        assertThat(AnalystTargetParser.parse("   ").ok()).isFalse();
    }
}
