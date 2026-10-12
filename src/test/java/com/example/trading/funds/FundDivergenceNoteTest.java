package com.example.trading.funds;

import com.example.trading.funds.FundVocabulary.CategoryStanding;
import com.example.trading.funds.FundVocabulary.FundRecordVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §52.8 - when the long record and the latest window disagree, say so (Gotcha 105).
 *
 * <p>These two readings answer different questions and can both be right while contradicting each
 * other. On the live book one ELSS fund read <b>behind its category in 47 of 66 rolling
 * windows</b> and <b>top quarter over the latest three years</b> - a fund that lagged for years and
 * has been strong lately. Two adjacent contradictory badges read, to the investor this app is
 * written for, as the app arguing with itself.
 *
 * <p>This exists as a test because after the units guard landed, that fund reads
 * {@code NOT_COMPARABLE} and the divergence <b>no longer fires on the live book at all</b> - so
 * without a test the explanation would be code nobody has ever seen run, which is the same class of
 * thing as a signal measured on no stock (B-074).
 */
class FundDivergenceNoteTest {

    @Test
    @DisplayName("behind over the whole record but top of the category lately is explained, not hidden")
    void laggedForYearsStrongLately() {
        String note = FundLensService.divergenceNote(
                record(FundRecordVerdict.BEHIND_BENCHMARK),
                standing(CategoryStanding.UPPER_QUARTILE),
                102);

        assertThat(note).isNotNull();
        assertThat(note)
                .contains("both are right")
                .contains("102 months")
                .contains("trailed for years and has been strong recently");
        // The honest limit of what the disagreement can tell you.
        assertThat(note).contains("cannot tell you is whether the recent strength lasts");
    }

    @Test
    @DisplayName("a long good record that has slipped lately points at the recent reading")
    void goodRecordSlippingLately() {
        String note = FundLensService.divergenceNote(
                record(FundRecordVerdict.AHEAD_OF_BENCHMARK),
                standing(CategoryStanding.LOWER_QUARTILE),
                102);

        assertThat(note).isNotNull();
        assertThat(note).contains("fallen behind its peers lately")
                .contains("more recent");
    }

    @Test
    @DisplayName("agreement produces no note - a callout on every row is noise")
    void agreementIsSilent() {
        // Noise trains the reader past the line that matters (Gotcha 117c, 132).
        assertThat(FundLensService.divergenceNote(
                record(FundRecordVerdict.AHEAD_OF_BENCHMARK),
                standing(CategoryStanding.UPPER_QUARTILE), 102)).isNull();
        assertThat(FundLensService.divergenceNote(
                record(FundRecordVerdict.BEHIND_BENCHMARK),
                standing(CategoryStanding.LOWER_HALF), 102)).isNull();
    }

    @Test
    @DisplayName("an unmeasured or uncomparable reading never produces a divergence")
    void nothingToDivergeFrom() {
        // Two readings cannot disagree when one of them was never made - that would be a
        // contradiction manufactured out of an absence (Gotcha 121).
        for (FundRecordVerdict v : List.of(FundRecordVerdict.NOT_MEASURED,
                FundRecordVerdict.NOT_COMPARABLE, FundRecordVerdict.MIXED_RECORD)) {
            assertThat(FundLensService.divergenceNote(record(v),
                    standing(CategoryStanding.UPPER_QUARTILE), 102))
                    .as("verdict %s", v)
                    .isNull();
        }
        for (CategoryStanding st : List.of(CategoryStanding.NOT_MEASURED,
                CategoryStanding.TOO_FEW_PEERS)) {
            assertThat(FundLensService.divergenceNote(record(FundRecordVerdict.BEHIND_BENCHMARK),
                    standing(st), 102))
                    .as("standing %s", st)
                    .isNull();
        }
    }

    @Test
    @DisplayName("the note explains the disagreement and never resolves it into one answer")
    void theGapIsTheFinding() {
        String note = FundLensService.divergenceNote(
                record(FundRecordVerdict.BEHIND_BENCHMARK),
                standing(CategoryStanding.UPPER_QUARTILE), 102);

        // Merging the two into a single verdict would destroy the distinction both readings exist
        // to draw - the §41-vs-§43 rule, reused verbatim.
        assertThat(note).contains("The gap between them is the finding");
    }

    private static RollingWindowRecord.Record record(FundRecordVerdict v) {
        return new RollingWindowRecord.Record(36, 102, 66, 2, 19, 0.288, -2.34, 0.0,
                "the category median", v, "reason", List.of());
    }

    private static CategoryCrossSection standing(CategoryStanding s) {
        return new CategoryCrossSection(SebiCategory.ELSS, 38, 14.75, 11.10, 79, s, "reason");
    }
}
