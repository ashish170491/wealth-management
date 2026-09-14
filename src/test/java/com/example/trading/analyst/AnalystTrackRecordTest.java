package com.example.trading.analyst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the per-house record and, mostly, its refusals (SPEC §49.6).
 *
 * <p>Every case here is a way of not saying something the data does not support. That is the same
 * discipline as the concall guidance ledger, whose value is entirely in what it declines to
 * conclude (Gotcha 47).
 */
class AnalystTrackRecordTest {

    private static final int FLOOR = 5;

    private static AnalystTargetEntity target(String house, String status) {
        return target(house, status, null, "ABOVE");
    }

    private static AnalystTargetEntity target(String house, String status, Double excess, String direction) {
        return AnalystTargetEntity.builder()
                .symbol("NSE:X")
                .brokerage(house)
                .status(status)
                .direction(direction)
                .excessReturnPct(excess)
                .targetPrice(100.0)
                .issuedOn(LocalDate.now().minusDays(400))
                .horizonDays(365)
                .resolvesOn(LocalDate.now().minusDays(35))
                .dedupKey("k" + Math.random())
                .build();
    }

    @Test
    @DisplayName("A pending call is never counted as met or missed")
    void pendingIsNeverAMiss() {
        // Marking a call missed before its date manufactures a bad record; counting pending calls
        // in the denominator penalises a house for publishing often.
        List<AnalystTargetEntity> rows = List.of(
                target("Nomura", "REACHED"), target("Nomura", "REACHED"), target("Nomura", "REACHED"),
                target("Nomura", "MISSED"), target("Nomura", "MISSED"),
                target("Nomura", "PENDING"), target("Nomura", "PENDING"));

        AnalystTrackRecord.HouseRecord r = house(rows, "Nomura");

        assertThat(r.resolved()).isEqualTo(5);
        assertThat(r.pending()).isEqualTo(2);
        assertThat(r.hitRatePercent()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("A revised call is excluded from the hit rate but published as a revision rate")
    void supersededIsExcludedAndReported() {
        // A house that revises down the week before a deadline would otherwise escape every miss
        // it ever made. It is not counted as a miss — it withdrew the claim — but the manoeuvre
        // has to be visible.
        List<AnalystTargetEntity> rows = List.of(
                target("CLSA", "REACHED"), target("CLSA", "REACHED"), target("CLSA", "REACHED"),
                target("CLSA", "MISSED"), target("CLSA", "MISSED"),
                target("CLSA", "SUPERSEDED"), target("CLSA", "SUPERSEDED"),
                target("CLSA", "SUPERSEDED"));

        AnalystTrackRecord.HouseRecord r = house(rows, "CLSA");

        assertThat(r.resolved()).isEqualTo(5);
        assertThat(r.superseded()).isEqualTo(3);
        assertThat(r.hitRatePercent()).isEqualTo(60.0);
        assertThat(r.revisionRatePercent()).isEqualTo(37.5);
    }

    @Test
    @DisplayName("Below the floor there is no hit rate at all — an absent record, not a bad one")
    void tooFewResolvedWithholdsTheHitRate() {
        // Two from two is a hundred per cent, and that is exactly the number that misleads.
        List<AnalystTargetEntity> rows = List.of(
                target("Ambit", "REACHED"), target("Ambit", "REACHED"));

        AnalystTrackRecord.HouseRecord r = house(rows, "Ambit");

        assertThat(r.status()).isEqualTo(AnalystTrackRecord.Status.TOO_EARLY);
        assertThat(r.hitRatePercent()).isNull();
        assertThat(r.medianExcessReturnPct()).isNull();
        // The counts are still published: "2 calls, none resolved" is a useful thing to read.
        assertThat(r.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("A correct Sell call counts in the house's favour, not against it")
    void excessReturnIsSignedTheWayTheCallWasMade() {
        // The row stores what the stock did against the index, which is one fact. Aggregating it
        // raw would score a house's correct sell calls as failures and cancel them against its
        // correct buys.
        List<AnalystTargetEntity> rows = List.of(
                target("UBS", "MISSED", -12.0, "BELOW"), target("UBS", "MISSED", -10.0, "BELOW"),
                target("UBS", "MISSED", -8.0, "BELOW"), target("UBS", "MISSED", -14.0, "BELOW"),
                target("UBS", "MISSED", -6.0, "BELOW"));

        AnalystTrackRecord.HouseRecord r = house(rows, "UBS");

        // Every one of those stocks fell 6-14% more than the index, which is what a Sell call
        // hopes for. The median must read positive.
        assertThat(r.medianExcessReturnPct()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("A pending call's return so far never enters the return column")
    void pendingReturnsAreNotAveragedIn() {
        // A partial observation of a different holding period would make a house look better or
        // worse purely for having published recently.
        List<AnalystTargetEntity> rows = List.of(
                target("Citi", "REACHED", 10.0, "ABOVE"), target("Citi", "REACHED", 10.0, "ABOVE"),
                target("Citi", "REACHED", 10.0, "ABOVE"), target("Citi", "MISSED", 10.0, "ABOVE"),
                target("Citi", "MISSED", 10.0, "ABOVE"),
                target("Citi", "PENDING", -90.0, "ABOVE"));

        assertThat(house(rows, "Citi").medianExcessReturnPct()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("An unpriced call is counted separately and never resolves")
    void unpricedIsItsOwnState() {
        // A recorded claim we cannot measure is neither a hit nor a miss. Letting it fall into
        // either would be a number where there is no measurement (SPEC §21 rule 7).
        List<AnalystTargetEntity> rows = List.of(
                target("Emkay", "UNPRICED"), target("Emkay", "UNPRICED"));

        AnalystTrackRecord.HouseRecord r = house(rows, "Emkay");

        assertThat(r.unpriced()).isEqualTo(2);
        assertThat(r.resolved()).isZero();
        assertThat(r.hitRatePercent()).isNull();
    }

    @Test
    @DisplayName("The combined row comes first and covers every house")
    void combinedRowLeads() {
        List<AnalystTargetEntity> rows = List.of(
                target("Nomura", "REACHED"), target("CLSA", "MISSED"));

        List<AnalystTrackRecord.HouseRecord> all = AnalystTrackRecord.compute(rows, FLOOR);

        assertThat(all.get(0).brokerage()).isEqualTo(AnalystTrackRecord.ALL_HOUSES);
        assertThat(all.get(0).total()).isEqualTo(2);
        assertThat(all).hasSize(3);
    }

    @Test
    @DisplayName("An empty ledger produces no rows rather than a row of zeroes")
    void emptyIsEmpty() {
        assertThat(AnalystTrackRecord.compute(List.of(), FLOOR)).isEmpty();
        assertThat(AnalystTrackRecord.compute(null, FLOOR)).isEmpty();
    }

    @Test
    @DisplayName("A median over nothing is null, never zero")
    void medianOfNothingIsNull() {
        assertThat(AnalystTrackRecord.median(List.of())).isNull();
        assertThat(AnalystTrackRecord.median(null)).isNull();
        assertThat(AnalystTrackRecord.median(List.of(1.0, 3.0))).isEqualTo(2.0);
    }

    @Test
    @DisplayName("The sample-bias caveat travels with every table")
    void caveatIsPresent() {
        // These tables are read by someone who did not build them, so the screen has to say what
        // the sample is and is not. Two of these were added when SPEC 49.11 made a structured
        // research feed the primary source: the old wording described a headline-only ledger and
        // overstated the selection filters, and a caveat that describes a different system from
        // the one running is worse than none.
        assertThat(AnalystTrackRecord.caveat(FLOOR))
                .containsKeys("whatThisIs", "sampleBias", "hitRateFloor", "whyExcessReturn",
                        "notAdvice", "ratingMix", "revisionEffect");

        // The revision caveat is load-bearing on live data: the two highest hit rates on file
        // (95.7% and 93.8%) belong to the two houses that resolve the FEWEST of their calls
        // (4.3% and 5.1%), and the correlation between resolved share and hit rate across 16
        // houses is -0.57. Without this line the scoreboard reads as a ranking of skill.
        assertThat(AnalystTrackRecord.caveat(FLOOR).get("revisionEffect").toString())
                .contains("revision rate");
    }

    private static AnalystTrackRecord.HouseRecord house(List<AnalystTargetEntity> rows, String name) {
        return AnalystTrackRecord.compute(rows, FLOOR).stream()
                .filter(h -> h.brokerage().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
