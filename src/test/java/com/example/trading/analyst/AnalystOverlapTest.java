package com.example.trading.analyst;

import com.example.trading.persistence.MultibaggerScoreEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The screening-vs-analyst overlap (SPEC §49.12).
 *
 * <p>The thing most worth pinning here is not an arithmetic result but a <b>refusal</b>: this
 * read compares two measurements and must never resolve them into a verdict on a target. The
 * live correlation is about -0.33 and a reader who is handed that without its explanation will
 * conclude one side is wrong, when it is mostly an artefact of what each side measures.
 */
class AnalystOverlapTest {

    private static MultibaggerScoreEntity stock(String symbol, int composite, double price) {
        MultibaggerScoreEntity e = new MultibaggerScoreEntity();
        e.setSymbol(symbol);
        e.setCompositeScore(composite);
        e.setCurrentPrice(price);
        e.setScreeningDate(LocalDate.of(2026, 9, 12));
        // The real band table (SPEC 12.5). Using a two-way split here would file a score-30
        // stock as a candidate and quietly break the "good stocks" assertions below.
        e.setVerdict(composite >= 80 ? "STRONG_MULTIBAGGER"
                : composite >= 65 ? "POTENTIAL_MULTIBAGGER"
                : composite >= 50 ? "WATCHLIST"
                : composite >= 35 ? "MONITOR" : "AVOID");
        return e;
    }

    private static AnalystTargetEntity target(String symbol, String house, double price) {
        return AnalystTargetEntity.builder()
                .symbol(symbol).brokerage(house).targetPrice(price)
                .status(AnalystTargetStatus.PENDING.name())
                .issuedOn(LocalDate.of(2026, 9, 1)).build();
    }

    @Test
    @DisplayName("A good stock with no target is reported as uncovered by the feed, not as a gap")
    void noTargetIsItsOwnFact() {
        AnalystOverlap.Result r = AnalystOverlap.compute(
                List.of(stock("NSE:OFSS", 100, 1000), stock("NSE:TITAN", 94, 5000)),
                Map.of("NSE:TITAN", List.of(target("NSE:TITAN", "Motilal Oswal", 6000))));

        assertThat(r.goodStocks()).isEqualTo(2);
        assertThat(r.goodWithOpenTarget()).isEqualTo(1);
        assertThat(r.goodWithoutTarget()).extracting(AnalystOverlap.Row::symbol)
                .containsExactly("NSE:OFSS");

        // The wording matters as much as the count: a high scorer nobody quotes is interesting
        // (SPEC 12.10's whole premise), not suspect.
        assertThat(r.notes().get("noTargetIsNotUncovered"))
                .contains("not evidence that nobody follows the stock");
    }

    @Test
    @DisplayName("A stock past every open target is flagged as a comparison, never as a sell")
    void abovePublishedTargets() {
        // Live case: SONACOMS scored 80 while trading at 788 against a best open target of 652.
        AnalystOverlap.Result r = AnalystOverlap.compute(
                List.of(stock("NSE:SONACOMS", 80, 788)),
                Map.of("NSE:SONACOMS", List.of(target("NSE:SONACOMS", "Emkay", 652))));

        AnalystOverlap.Row row = r.abovePublishedTargets().get(0);
        assertThat(row.abovePublishedTargets()).isTrue();
        assertThat(row.upsideToHighestPct()).isNegative();

        String note = r.notes().get("aboveTarget");
        assertThat(note).contains("look harder before adding");
        // SPEC 20 rule 10: this vocabulary carries no instruction to transact.
        assertThat(note.toLowerCase()).doesNotContain("sell now").doesNotContain("exit");
    }

    @Test
    @DisplayName("The correlation always travels with the reason it is negative")
    void correlationCarriesItsExplanation() {
        // Built to disagree the way the live cross-section does: the best-scoring stock carries
        // the smallest claimed upside.
        AnalystOverlap.Result r = AnalystOverlap.compute(
                List.of(stock("NSE:A", 95, 100), stock("NSE:B", 75, 100), stock("NSE:C", 55, 100)),
                Map.of("NSE:A", List.of(target("NSE:A", "H", 105)),
                        "NSE:B", List.of(target("NSE:B", "H", 130)),
                        "NSE:C", List.of(target("NSE:C", "H", 170))));

        assertThat(r.upsideVsCompositeCorrelation()).isNotNull().isNegative();
        assertThat(r.correlationSample()).isEqualTo(3);
        assertThat(r.notes().get("whyTheyDisagree"))
                .contains("price behaviour")
                .contains("opposite things");
        // And the reader is told what CAN check a target, since the composite cannot.
        assertThat(r.notes().get("soWhatVerifies")).contains("fundamental");
    }

    @Test
    @DisplayName("Correlation is measured over the whole cross-section, not just the good stocks")
    void correlationIsNotMeasuredOnTheSelectedHalf() {
        // Restricting it to high scorers measures the relationship on the half of the range that
        // was selected for, which is how a mechanical artefact gets promoted to a finding.
        AnalystOverlap.Result r = AnalystOverlap.compute(
                List.of(stock("NSE:A", 95, 100), stock("NSE:B", 40, 100), stock("NSE:C", 30, 100)),
                Map.of("NSE:A", List.of(target("NSE:A", "H", 110)),
                        "NSE:B", List.of(target("NSE:B", "H", 150)),
                        "NSE:C", List.of(target("NSE:C", "H", 200))));

        assertThat(r.goodStocks()).isEqualTo(1);
        assertThat(r.correlationSample())
                .as("every screened stock with a target counts, not only the good ones")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("An unmeasurable leg yields null, never a zero")
    void missingLegsAreNull() {
        assertThat(AnalystOverlap.upsidePct(null, 100)).isNull();
        assertThat(AnalystOverlap.upsidePct(120.0, 0)).isNull();
        assertThat(AnalystOverlap.pearson(List.of(1.0, 2.0), List.of(1.0, 2.0)))
                .as("two points is not a correlation").isNull();
        assertThat(AnalystOverlap.pearson(List.of(1.0, 1.0, 1.0), List.of(2.0, 3.0, 4.0)))
                .as("a constant leg has no correlation, and zero would be a claim").isNull();

        // A screened stock with no target must not contribute a zero upside to the correlation.
        AnalystOverlap.Result r = AnalystOverlap.compute(
                List.of(stock("NSE:A", 90, 100), stock("NSE:B", 80, 100)),
                Map.of("NSE:A", List.of(target("NSE:A", "H", 120))));
        assertThat(r.correlationSample()).isEqualTo(1);
        assertThat(r.upsideVsCompositeCorrelation()).isNull();
    }

    @Test
    @DisplayName("Agreement counts firms, not notes, and two firms is not yet agreement")
    void strongestAgreementNeedsThreeHouses() {
        AnalystOverlap.Result r = AnalystOverlap.compute(
                List.of(stock("NSE:THREE", 70, 100), stock("NSE:TWO", 95, 100),
                        stock("NSE:LOUD", 90, 100)),
                Map.of(
                        "NSE:THREE", List.of(target("NSE:THREE", "Motilal Oswal", 120),
                                target("NSE:THREE", "Kotak", 125), target("NSE:THREE", "Nomura", 130)),
                        "NSE:TWO", List.of(target("NSE:TWO", "Motilal Oswal", 120),
                                target("NSE:TWO", "Kotak", 125)),
                        // One house, three revisions. That is one opinion, not a consensus - the
                        // same rule B-041 applied to a director staggering a purchase over 3 days.
                        "NSE:LOUD", List.of(target("NSE:LOUD", "Kotak", 120),
                                target("NSE:LOUD", "Kotak", 140), target("NSE:LOUD", "Kotak", 160))));

        assertThat(r.strongestAgreement()).extracting(AnalystOverlap.Row::symbol)
                .containsExactly("NSE:THREE");
    }

    @Test
    @DisplayName("Every action names a list to read, never a transaction, and its count is the list's")
    void actionsAreDerivedAndCarryNoInstructionToTransact() {
        AnalystOverlap.Result r = AnalystOverlap.compute(
                List.of(stock("NSE:PAST", 80, 788), stock("NSE:QUIET", 96, 100),
                        stock("NSE:AGREED", 75, 100)),
                Map.of(
                        "NSE:PAST", List.of(target("NSE:PAST", "Emkay", 652)),
                        "NSE:AGREED", List.of(target("NSE:AGREED", "Kotak", 120),
                                target("NSE:AGREED", "Nomura", 125),
                                target("NSE:AGREED", "Jefferies", 130))));

        assertThat(r.actions()).isNotEmpty();

        // The sentence and the table under it cannot drift apart (B-098).
        assertThat(r.actions()).anySatisfy(a -> assertThat(a.title())
                .contains(String.valueOf(r.abovePublishedTargets().size())));
        assertThat(r.actions()).anySatisfy(a -> assertThat(a.title())
                .contains(String.valueOf(r.goodWithoutTarget().size())));

        // The correlation is the thing the investor asked about, so its "no action" line is not
        // optional - it is present even when every list is empty.
        AnalystOverlap.Result bare = AnalystOverlap.compute(List.of(), Map.of());
        assertThat(bare.actions()).isNotEmpty()
                .allSatisfy(a -> assertThat(a.guidance()).isNotBlank());

        // SPEC 20 rule 10: this screen points at a list, never at a trade.
        for (AnalystOverlap.Action a : r.actions()) {
            String text = (a.title() + " " + a.guidance()).toLowerCase();
            assertThat(text).doesNotContain("sell").doesNotContain("exit").doesNotContain("buy");
        }
    }

    @Test
    @DisplayName("Bands count every stock once and report their own coverage")
    void bandsArePartition() {
        List<MultibaggerScoreEntity> screened = List.of(
                stock("NSE:A", 85, 100), stock("NSE:B", 75, 100),
                stock("NSE:C", 67, 100), stock("NSE:D", 55, 100), stock("NSE:E", 20, 100));

        AnalystOverlap.Result r = AnalystOverlap.compute(screened,
                Map.of("NSE:A", List.of(target("NSE:A", "H", 120))));

        assertThat(r.bands().stream().mapToInt(AnalystOverlap.Band::stocks).sum())
                .as("every screened stock lands in exactly one band")
                .isEqualTo(screened.size());
        assertThat(r.bands().get(0).withOpenTarget()).isEqualTo(1);
        assertThat(r.bands().get(0).coveragePercent()).isEqualTo(100.0);
        // An empty band reports null coverage, not 0% - nothing was measured there.
        AnalystOverlap.Band empty = new AnalystOverlap.Band("x", 0, 0, 0);
        assertThat(empty.coveragePercent()).isNull();
    }
}
