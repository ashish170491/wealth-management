package com.example.trading.analyst;

import com.example.trading.persistence.HoldingsEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the portfolio-coverage read of the analyst ledger (SPEC §49.14).
 *
 * <p><b>Why a test for field names.</b> {@code analystCoverageCell} in
 * {@code static/js/analyst-cells.js} reads a dozen fields off a holdings row by name. There is no
 * build step for that file and nothing type-checks the wire, so a rename fails nothing: the cell
 * finds {@code undefined} and draws the unmeasured marker for ever.
 *
 * <p>That failure is worse here than on most surfaces, because the three states this column
 * distinguishes sit one badge apart. A broken field name converts <em>"six firms are quoting a
 * target on this"</em> into <em>"nobody is looking at it"</em> — a claim the app is in no
 * position to make (SPEC §49.7) — and nothing anywhere reports it.
 *
 * <p>A deliberate rename should fail this test <i>and</i> change {@code analyst-cells.js} in the
 * same commit. That is the point of it failing.
 */
class AnalystCoverageSurfaceTest {

    /** Exactly what the portfolio cell and panel dereference, with the type each must carry. */
    private static final Map<String, Class<?>> REQUIRED = required();

    private static Map<String, Class<?>> required() {
        Map<String, Class<?>> m = new LinkedHashMap<>();
        // Integer, never int: null means the lookup did not run and 0 means it ran and found
        // nothing. A primitive would collapse those two into one another (SPEC §21 rule 7).
        m.put("analystHouses", Integer.class);
        m.put("analystHouseNames", List.class);
        m.put("analystOpenTargets", Integer.class);
        m.put("analystMedianTarget", Double.class);
        m.put("analystHighestTarget", Double.class);
        m.put("analystLowestTarget", Double.class);
        m.put("analystUpsidePct", Double.class);
        // The basis the upside was measured from. Without it the cell cannot say which price it
        // used, and the figure beside the Price column looks like an arithmetic error.
        m.put("analystPriceAsStored", Double.class);
        m.put("analystPriceAsOf", LocalDate.class);
        m.put("analystHousesEver", Integer.class);
        m.put("analystHouseNamesEver", List.class);
        m.put("analystTargetsEver", Integer.class);
        m.put("analystLastCallOn", LocalDate.class);
        m.put("analystTargetsFrom", String.class);
        m.put("analystNote", String.class);
        return Map.copyOf(m);
    }

    @Test
    @DisplayName("The portfolio row carries every field the analyst coverage cell reads")
    void holdingsRowHonoursTheContract() {
        Map<String, Class<?>> actual = Arrays.stream(HoldingsEntity.class.getDeclaredFields())
                .collect(java.util.stream.Collectors.toMap(Field::getName, Field::getType,
                        (a, b) -> a, LinkedHashMap::new));

        assertThat(actual).containsKeys(REQUIRED.keySet().toArray(new String[0]));
        REQUIRED.forEach((name, type) -> assertThat(actual.get(name))
                .as("HoldingsEntity.%s must be %s or the cell cannot read it",
                        name, type.getSimpleName())
                .isEqualTo(type));
    }

    /**
     * A counted zero and an absent lookup are different facts, and the record must express both.
     *
     * <p>"No target on file" is something this app measured — it searched the ledger. "The lookup
     * did not run" is not. Collapsing them lets a failed query render as a statement that nobody
     * covers the stock, which is the one claim SPEC §49.7 says the ledger can never support.
     */
    @Test
    @DisplayName("Nothing on file is a counted zero, and it says what a zero means")
    void nothingOnFileIsACountedZero() {
        AnalystTargetViewService.Coverage c = AnalystTargetViewService.noCoverage("NSE:X");

        assertThat(c.houses()).isZero();
        assertThat(c.housesEver()).isZero();
        assertThat(c.targetsEver()).isZero();
        assertThat(c.houseNames()).isEmpty();
        // Never a fabricated figure to sit beside a zero count.
        assertThat(c.medianTarget()).isNull();
        assertThat(c.impliedUpsidePct()).isNull();
        assertThat(c.symbolAnswered()).isNull();
        // And the note must not claim the company is uncovered — only that nothing reached us.
        assertThat(c.note()).contains("news feeds this app reads");
    }

    /**
     * A house that revised three times is one opinion, not three (B-041).
     *
     * <p>The count beside a median target is the count of <em>firms</em>. Counting notes would
     * make one talkative desk look like a chorus, which is the whole failure the overlap screen's
     * agreement threshold was built to avoid (SPEC §49.12).
     */
    @Test
    @DisplayName("Firms are counted, not notes")
    void housesCountFirmsNotNotes() {
        AnalystTargetViewService.Coverage c = AnalystTargetViewService.coverage(List.of(
                open("Motilal Oswal", 100.0),
                open("Motilal Oswal", 110.0),
                open("Emkay", 120.0)), "NSE:X");

        assertThat(c.houses()).isEqualTo(2);
        assertThat(c.openTargets()).isEqualTo(3);
        assertThat(c.houseNames()).containsExactly("Emkay", "Motilal Oswal");
    }

    /**
     * Covered-but-quiet is not never-covered.
     *
     * <p>Measured on the live book: NATIONALUM carries ten recorded targets and nothing running.
     * Reporting that as "no coverage" would lose the distinction between a stock the desks have
     * stopped quoting and one they never quoted — the first is a finding, the second is mostly a
     * fact about this app's feeds.
     */
    @Test
    @DisplayName("A stock with only closed calls is covered but quiet, not uncovered")
    void closedCallsStillCountAsHavingBeenCovered() {
        AnalystTargetEntity closed = AnalystTargetEntity.builder()
                .symbol("NSE:X").brokerage("Emkay").targetPrice(150.0)
                .issuedOn(LocalDate.now().minusDays(400))
                .status(AnalystTargetStatus.MISSED.name()).dedupKey("k1")
                .build();

        AnalystTargetViewService.Coverage c =
                AnalystTargetViewService.coverage(List.of(closed), "NSE:X");

        assertThat(c.houses()).isZero();
        assertThat(c.openTargets()).isZero();
        assertThat(c.housesEver()).isEqualTo(1);
        assertThat(c.houseNamesEver()).containsExactly("Emkay");
        assertThat(c.targetsEver()).isEqualTo(1);
        assertThat(c.lastCallOn()).isEqualTo(closed.getIssuedOn());
        // No live target means no median, and therefore no upside figure invented from one.
        assertThat(c.medianTarget()).isNull();
        assertThat(c.impliedUpsidePct()).isNull();
    }

    /**
     * "First hit wins" must be "first hit that answers wins" (Gotcha 107, B-088).
     *
     * <p>Targets are filed under the NSE symbol while two thirds of this portfolio is held
     * BSE-prefixed — measured: 15 of 30 holdings. A BSE-keyed row carrying only a stale closed
     * call must not beat the NSE row that has six firms live on it, or half the column blanks.
     */
    @Test
    @DisplayName("A spelling with live targets beats one carrying only closed calls")
    void resolutionPrefersTheSpellingThatCanAnswer() {
        Map<String, List<AnalystTargetEntity>> bySymbol = new LinkedHashMap<>();
        bySymbol.put("BSE:X", List.of(AnalystTargetEntity.builder()
                .symbol("BSE:X").brokerage("Stale House").targetPrice(50.0)
                .issuedOn(LocalDate.now().minusDays(500))
                .status(AnalystTargetStatus.MISSED.name()).dedupKey("k2").build()));
        bySymbol.put("NSE:X", List.of(open("Motilal Oswal", 900.0)));

        AnalystTargetViewService.Coverage c = AnalystTargetViewService.resolve("BSE:X", bySymbol);

        assertThat(c.symbolAnswered()).isEqualTo("NSE:X");
        assertThat(c.houses()).isEqualTo(1);
        assertThat(c.houseNames()).containsExactly("Motilal Oswal");
    }

    /**
     * The upside needs both legs, or it is not a figure.
     *
     * <p>A median target with no stored price would otherwise read as an upside of zero, which is
     * the exact shape of every bug SPEC §21 rule 7 exists to prevent.
     */
    @Test
    @DisplayName("Upside is null, never zero, when the ledger has no stored price")
    void upsideNeedsBothLegs() {
        AnalystTargetViewService.Coverage c =
                AnalystTargetViewService.coverage(List.of(open("Emkay", 400.0)), "NSE:X");

        assertThat(c.medianTarget()).isEqualTo(400.0);
        assertThat(c.priceAsStored()).isNull();
        assertThat(c.impliedUpsidePct()).isNull();
    }

    /** The upside is computed from the most recently measured stored price, not the oldest. */
    @Test
    @DisplayName("The stored price comes from the most recently measured row")
    void upsideUsesTheFreshestStoredPrice() {
        AnalystTargetEntity old = open("Emkay", 400.0);
        old.setLastPrice(100.0);
        old.setLastMeasuredAt(LocalDateTime.now().minusDays(10));
        AnalystTargetEntity fresh = open("LKP", 400.0);
        fresh.setLastPrice(200.0);
        fresh.setLastMeasuredAt(LocalDateTime.now());

        AnalystTargetViewService.Coverage c =
                AnalystTargetViewService.coverage(List.of(old, fresh), "NSE:X");

        assertThat(c.priceAsStored()).isEqualTo(200.0);
        assertThat(c.impliedUpsidePct()).isEqualTo(100.0);
    }

    /**
     * The portfolio column and the stock page must not count houses twice.
     *
     * <p>Two pieces of arithmetic answering "how many brokerages cover this?" is Gotcha 85, and
     * its failure mode is not a wrong number but two different numbers on two screens. This pins
     * that {@code liveSummary} is a view of the same record rather than a second computation.
     */
    @Test
    @DisplayName("The stock page summary and the portfolio coverage agree by construction")
    void oneComputationTwoSurfaces() {
        List<AnalystTargetEntity> rows =
                List.of(open("Emkay", 100.0), open("LKP", 140.0), open("Emkay", 120.0));

        AnalystTargetViewService.Coverage c = AnalystTargetViewService.coverage(rows, "NSE:X");
        Map<String, Object> summary = new AnalystTargetViewService(
                null, null, null, null, null).liveSummary(rows);

        assertThat(summary.get("houses")).isEqualTo(c.houses());
        assertThat(summary.get("houseNames")).isEqualTo(c.houseNames());
        assertThat(summary.get("medianTarget")).isEqualTo(c.medianTarget());
        assertThat(summary.get("openTargets")).isEqualTo(c.openTargets());
        assertThat(summary.get("note")).isEqualTo(c.note());
    }

    private static AnalystTargetEntity open(String house, double target) {
        return AnalystTargetEntity.builder()
                .symbol("NSE:X").brokerage(house).targetPrice(target)
                .issuedOn(LocalDate.now().minusDays(30))
                .resolvesOn(LocalDate.now().plusDays(335))
                .status(AnalystTargetStatus.PENDING.name())
                .dedupKey(house + target)
                .build();
    }
}
