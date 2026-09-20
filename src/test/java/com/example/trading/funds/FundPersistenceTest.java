package com.example.trading.funds;

import com.example.trading.funds.FundPersistence.Entry;
import com.example.trading.funds.FundPersistence.PersistenceVerdict;
import com.example.trading.funds.FundPersistence.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §52.13 - the measurement that decides whether a "best funds" list can ever be honest here.
 *
 * <p>The cases that matter are the refusals and the survivorship treatment. A transition matrix is
 * easy arithmetic; what is hard is not letting it flatter itself.
 */
class FundPersistenceTest {

    @Test
    @DisplayName("perfect persistence reads as found; perfect reshuffling reads as chance")
    void theTwoEndsOfTheScale() {
        // Every fund keeps its exact place: the strongest possible evidence that ranking carries
        // information. 20 funds, so 5 in each quarter.
        List<Entry> stable = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            stable.add(new Entry(i, "Fund " + i, 30.0 - i, 30.0 - i));
        }
        Result perfect = FundPersistence.measure(Map.of(SebiCategory.LARGE_CAP, stable));
        assertThat(perfect.verdict()).isEqualTo(PersistenceVerdict.PERSISTENCE_FOUND);
        assertThat(perfect.shareStayedTopPct()).isEqualTo(100.0);
        assertThat(perfect.rankCorrelation()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));

        // Window B exactly reverses window A. The best quarter becomes the worst quarter, which is
        // the one result a reader must never see described as "past ranking told you nothing".
        //
        // SIXTY funds, not twenty, and that is the resolution guard rather than a detail: with
        // twenty funds the top quarter holds five, the largest gap below chance that can possibly
        // be observed is 25 points, and three funds are worth 60 - so no direction is claimable
        // from five funds however cleanly they flip, and the class correctly refuses to claim one.
        List<Entry> reversed = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            reversed.add(new Entry(i, "Fund " + i, 60.0 - i, (double) i));
        }
        Result flipped = FundPersistence.measure(Map.of(SebiCategory.LARGE_CAP, reversed));
        assertThat(flipped.verdict()).isEqualTo(PersistenceVerdict.REVERSAL);
        assertThat(flipped.shareStayedTopPct()).isZero();
        assertThat(flipped.fellToBottomQuartile()).isEqualTo(flipped.topQuartileA());
    }

    @Test
    @DisplayName("a top-quartile fund that stops reporting is counted, and drags the lower bound down")
    void survivorshipIsCountedRatherThanCaveated() {
        // Eight of sixteen survivors hold their place - 4 of 4 in the top quarter, which reads as
        // flawless persistence. But four MORE funds were in the best quarter of window A and then
        // wound up, and they cannot appear in window B's table at all. Ignoring them would publish
        // 100% persistence for a category where half the winners disappeared (SPEC 52.9).
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            entries.add(new Entry(i, "Survivor " + i, 40.0 - i, 40.0 - i));
        }
        for (int i = 0; i < 4; i++) {
            entries.add(new Entry(100 + i, "Wound up " + i, 50.0 + i, null));
        }

        Result r = FundPersistence.measure(Map.of(SebiCategory.FLEXI_CAP, entries));

        assertThat(r.topQuartileA()).isEqualTo(4);
        assertThat(r.stayedTopQuartile()).isEqualTo(4);
        assertThat(r.shareStayedTopPct()).isEqualTo(100.0);

        // The dead were the four best performers of window A, so they take the whole top quarter
        // once they are ranked back in - and the lower bound halves.
        assertThat(r.vanishedAfterA()).isEqualTo(4);
        assertThat(r.vanishedAfterATopQuartile()).isEqualTo(4);
        assertThat(r.shareStayedTopLowerBoundPct()).isEqualTo(50.0);
        assertThat(r.caveats()).anyMatch(c -> c.contains("stopped reporting"));
    }

    @Test
    @DisplayName("a two-fund gap from chance never becomes a claim about manager skill")
    void theBandIsCoarserThanTheData() {
        // VERBATIM the first live run: 40 funds in the top quarter across eight categories, 8 of
        // them still there in window B. 20% against a 25% null - and with a fixed five-point band
        // that read REVERSAL, a directional claim about whether managers have skill, off a gap of
        // TWO FUNDS. One fund is worth 2.5 points at this sample size, so the band has to be
        // coarser than that or it reports noise as a finding (Gotcha 88's "a threshold belongs to
        // a scale", applied to a percentage whose scale is set by the size of the top quarter).
        //
        // Built as one 160-fund category so the top quarter is exactly 40, 8 of which hold.
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            double a = 200.0 - i;                       // window A rank = i
            double b;
            if (i < 8) {
                b = 200.0 - i;                          // eight hold their place
            } else if (i < 40) {
                b = 50.0 - i;                           // the other 32 top-quartile funds drop out
            } else {
                b = 150.0 - i;                          // everyone else fills the gap
            }
            entries.add(new Entry(i, "Fund " + i, a, b));
        }

        Result r = FundPersistence.measure(Map.of(SebiCategory.FLEXI_CAP, entries));

        assertThat(r.topQuartileA()).isEqualTo(40);
        assertThat(r.stayedTopQuartile()).isEqualTo(8);
        assertThat(r.shareStayedTopPct()).isEqualTo(20.0);
        assertThat(r.onePerFundPp()).isEqualTo(2.5);
        assertThat(r.bandFloorPp()).isEqualTo(7.5);
        // The whole point: 20 against 25 is two funds, and two funds is not a direction.
        assertThat(r.verdict()).isEqualTo(PersistenceVerdict.NO_BETTER_THAN_CHANCE);
        assertThat(r.headline()).contains("a couple of funds");
    }

    @Test
    @DisplayName("a per-category percentage is withheld when one fund would swing it")
    void perCategorySharesAreCountsNotPercentages() {
        // On the live archive every category's top quarter holds 3 to 7 funds, so one fund moving
        // swings its share by 14 to 33 points - and a table reading "Large & Mid Cap 50%, Large Cap
        // 0%" invites the reader to conclude something about managers from four coin flips. The
        // counts stay, because "1 of 7" cannot be over-read the way "14%" can.
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {   // top quarter of five, below MIN_TOP_QUARTILE_FOR_SHARE
            entries.add(new Entry(i, "Fund " + i, 30.0 - i, 30.0 - i));
        }

        Result r = FundPersistence.measure(Map.of(SebiCategory.LARGE_CAP, entries));
        FundPersistence.CategoryResult c = r.categories().get(0);

        assertThat(c.topQuartileA()).isEqualTo(5);
        assertThat(c.stayedTopQuartile()).isEqualTo(5);
        assertThat(c.shareStayedTopPct()).as("withheld, not zero").isNull();
        assertThat(c.reason()).contains("5 of the 5").contains("too few to turn into a percentage");
    }

    @Test
    @DisplayName("a category below the floor publishes no quartile and says so")
    void tooFewFundsIsARefusalNotAZero() {
        List<Entry> few = new ArrayList<>();
        for (int i = 0; i < FundPersistence.MIN_FUNDS_PER_CATEGORY - 1; i++) {
            few.add(new Entry(i, "Fund " + i, 10.0 + i, 10.0 + i));
        }

        Result r = FundPersistence.measure(Map.of(SebiCategory.CONTRA, few));

        assertThat(r.categoriesMeasured()).isZero();
        assertThat(r.verdict()).isEqualTo(PersistenceVerdict.NOT_MEASURED);
        assertThat(r.categories()).hasSize(1);
        assertThat(r.categories().get(0).inHeadline()).isFalse();
        // The reason must not read as a finding about those funds.
        assertThat(r.categories().get(0).reason())
                .contains("says nothing about the funds in it");
    }

    @Test
    @DisplayName("sectoral funds are measured and shown but never reach the headline")
    void sectoralIsRankedAndExcluded() {
        // Perfect persistence inside Sectoral/Thematic, which is 265 of the 637 screenable schemes.
        // If it counted, that one artefact - the same sector leading twice - would dominate the
        // answer to a question about manager skill.
        List<Entry> sectoral = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            sectoral.add(new Entry(i, "Thematic " + i, 30.0 - i, 30.0 - i));
        }

        Result r = FundPersistence.measure(Map.of(SebiCategory.SECTORAL_THEMATIC, sectoral));

        assertThat(r.categories()).hasSize(1);
        FundPersistence.CategoryResult only = r.categories().get(0);
        assertThat(only.inHeadline()).isFalse();
        // Measured, not skipped: the figures are there to be read.
        assertThat(only.fundsInBothWindows()).isEqualTo(40);
        assertThat(only.shareStayedTopPct()).isEqualTo(100.0);
        assertThat(only.reason()).contains("which sector ran");
        // ...and contributed nothing to the headline.
        assertThat(r.categoriesMeasured()).isZero();
        assertThat(r.verdict()).isEqualTo(PersistenceVerdict.NOT_MEASURED);
        assertThat(r.rankCorrelation()).isNull();
    }

    @Test
    @DisplayName("chance reads as chance, and the headline says so in words")
    void aCoinFlipIsReportedAsACoinFlip() {
        // Sixteen funds, so the best quarter holds exactly four, and window B leaves exactly one of
        // them there - 25%, the null. Twelve funds would NOT do: the quarter is then three, one of
        // three is 33%, and the bands are finer than the resolution of the data. That coarseness is
        // real and is why the headline pools every category rather than reading one.
        List<Entry> shuffled = List.of(
                new Entry(1, "A", 40.0, 40.0),   // the one that holds its place
                new Entry(2, "B", 39.0, 12.0),
                new Entry(3, "C", 38.0, 11.0),
                new Entry(4, "D", 37.0, 10.0),
                new Entry(5, "E", 36.0, 39.0),
                new Entry(6, "F", 35.0, 38.0),
                new Entry(7, "G", 34.0, 37.0),
                new Entry(8, "H", 33.0, 9.0),
                new Entry(9, "I", 32.0, 8.0),
                new Entry(10, "J", 31.0, 7.0),
                new Entry(11, "K", 30.0, 6.0),
                new Entry(12, "L", 29.0, 5.0),
                new Entry(13, "M", 28.0, 4.0),
                new Entry(14, "N", 27.0, 3.0),
                new Entry(15, "O", 26.0, 2.0),
                new Entry(16, "P", 25.0, 1.0));

        Result r = FundPersistence.measure(Map.of(SebiCategory.MID_CAP, shuffled));

        assertThat(r.topQuartileA()).isEqualTo(4);
        assertThat(r.stayedTopQuartile()).isEqualTo(1);
        assertThat(r.verdict()).isEqualTo(PersistenceVerdict.NO_BETTER_THAN_CHANCE);
        assertThat(r.headline()).contains("drawing names out of a hat");
    }

    @Test
    @DisplayName("no significance language is produced anywhere, however clean the result")
    void neverClaimsSignificance() {
        // Forty funds holding their places exactly is as strong as a result can look, and is
        // precisely when a p-value would be most tempting and most wrong: funds in one category
        // hold overlapping stocks, so this is nothing like forty independent draws (Gotcha 92).
        List<Entry> stable = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            stable.add(new Entry(i, "Fund " + i, 30.0 - i, 30.0 - i));
        }
        Result r = FundPersistence.measure(Map.of(SebiCategory.LARGE_CAP, stable));

        List<String> prose = new ArrayList<>(r.caveats());
        prose.add(r.headline());
        for (String s : prose) {
            String lower = s.toLowerCase();
            assertThat(lower).doesNotContain("p-value").doesNotContain("significant")
                    .doesNotContain("t-statistic").doesNotContain("confidence interval");
        }
        // And it says out loud that the fund count is not a sample size.
        assertThat(r.caveats()).anyMatch(c -> c.contains("not a sample size"));
    }

    @Test
    @DisplayName("nothing here names a fund as worth owning, or tells anyone to transact")
    void carriesNoInstructionToTransact() {
        List<Entry> stable = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            stable.add(new Entry(i, "Fund " + i, 30.0 - i, 30.0 - i));
        }
        Result r = FundPersistence.measure(Map.of(SebiCategory.SMALL_CAP, stable));

        List<String> prose = new ArrayList<>(r.caveats());
        prose.add(r.headline());
        for (FundPersistence.CategoryResult c : r.categories()) prose.add(c.reason());
        for (PersistenceVerdict v : PersistenceVerdict.values()) prose.add(v.label());

        // Word boundaries, because the SPEC 51 version of this test failed in its first draft on
        // "whoever SELLS the servers" (Gotcha 53). Here the trap is "the fund HOLDS".
        for (String s : prose) {
            assertThat(s.toLowerCase())
                    .as("prose: %s", s)
                    .doesNotMatch("(?s).*\\b(buy|sell|exit|redeem|switch|invest in|recommend)\\b.*");
        }
    }

    @Test
    @DisplayName("a fund missing from window A is a launch, not a death, and never counts as vanished")
    void aNewFundIsNotAVanishedOne() {
        // Absent from A and present in B means the fund launched mid-archive. Counting that as a
        // survivorship failure would invert the meaning of the number entirely.
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            entries.add(new Entry(i, "Fund " + i, 30.0 - i, 30.0 - i));
        }
        entries.add(new Entry(99, "Launched later", null, 45.0));

        Result r = FundPersistence.measure(Map.of(SebiCategory.MULTI_CAP, entries));

        assertThat(r.vanishedAfterA()).isZero();
        assertThat(r.fundsInBothWindows()).isEqualTo(16);
    }
}
