package com.example.trading.funds;

import com.example.trading.funds.amfi.AmfiColumnLayout;
import com.example.trading.funds.amfi.AmfiNavParser;
import com.example.trading.funds.amfi.AmfiSchemeRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the AMFI NAVAll parser (SPEC §52).
 *
 * <p>Every fixture here is <b>verbatim from the live 1.5 MB file</b>, for the reason Gotcha 126(a)
 * records: on the analyst ledger every invented test case passed while the real headlines failed.
 * The same happened here — see {@link #amcNameContainingBracketsIsNotACategory()}, which is a real
 * defect this parser shipped with and which only a run over the real file exposed.
 */
class AmfiNavParserTest {

    private static final String HEADER =
            "Scheme Code;ISIN Div Payout/ ISIN Growth;ISIN Div Reinvestment;Scheme Name;Plan;Option;Net Asset Value;Date";

    /**
     * The historical endpoint's header. Same eight fields, different order - see B-130 and
     * {@link #historyLayoutIsReadFromTheHeaderNotAssumed()}.
     */
    private static final String HISTORY_HEADER =
            "Scheme Code;NAV Name;Plan;Option;ISIN Div Payout/ISIN Growth;ISIN Div Reinvestment;Net Asset Value;Date";

    @Test
    @DisplayName("B-130: the history feed's column order is read from its header, not assumed")
    void historyLayoutIsReadFromTheHeaderNotAssumed() {
        // Verbatim from the 31-Aug-2026 historical file. Under the daily file's fixed positions
        // this row still parses - the scheme code, NAV and date sit in the same places - while the
        // name, both ISINs, the plan and the option are all silently taken from the wrong columns.
        // That is how the first backfill reported "6 months fetched, 0 failed" and wrote four NAV
        // points where hundreds were due: nothing threw, and only the figure being too small said so.
        String file = HISTORY_HEADER + "\n"
                + "\n"
                + "Open Ended Schemes ( Equity Scheme - Mid Cap Fund )\n"
                + "\n"
                + "Axis Mutual Fund\n"
                + "\n"
                + "120505;Axis Midcap Fund - Direct Plan - Growth Option;Direct Plan;Growth Option;"
                + "INF846K01DP9;INF846K01CH8;123.4560;31-Aug-2026\n";

        AmfiNavParser.ParseResult r = AmfiNavParser.parse(file);

        assertThat(r.rowCount()).isEqualTo(1);
        AmfiSchemeRow row = r.rows().get(0);
        assertThat(row.schemeName()).isEqualTo("Axis Midcap Fund - Direct Plan - Growth Option");
        assertThat(row.plan()).isEqualTo(SchemePlanKey.Plan.DIRECT);
        assertThat(row.option()).isEqualTo(SchemePlanKey.Option.GROWTH);
        assertThat(row.category()).isEqualTo(SebiCategory.MID_CAP);
        assertThat(row.isScreenable())
                .as("the whole point: under the assumed layout this row is not screenable and the "
                        + "month-end series is empty")
                .isTrue();
        assertThat(r.layout().source()).isEqualTo(HISTORY_HEADER);
    }

    @Test
    @DisplayName("the daily feed still parses, and the two layouts disagree about every middle field")
    void dailyAndHistoryLayoutsDiffer() {
        AmfiColumnLayout daily = AmfiColumnLayout.fromHeader(HEADER);
        AmfiColumnLayout history = AmfiColumnLayout.fromHeader(HISTORY_HEADER);

        assertThat(daily.isUsable()).isTrue();
        assertThat(history.isUsable()).isTrue();

        // The three fields that agree are exactly why this was invisible.
        assertThat(history.code()).isEqualTo(daily.code());
        assertThat(history.nav()).isEqualTo(daily.nav());
        assertThat(history.date()).isEqualTo(daily.date());

        // Every field that identifies the scheme moves.
        assertThat(history.name()).isNotEqualTo(daily.name());
        assertThat(history.plan()).isNotEqualTo(daily.plan());
        assertThat(history.option()).isNotEqualTo(daily.option());
        assertThat(history.isinGrowth()).isNotEqualTo(daily.isinGrowth());
        assertThat(history.isinReinvest()).isNotEqualTo(daily.isinReinvest());
    }

    @Test
    @DisplayName("the two ISIN columns are told apart, not both mapped to the payout column")
    void isinColumnsAreDistinct() {
        AmfiColumnLayout daily = AmfiColumnLayout.fromHeader(HEADER);

        // "ISIN Div Payout/ ISIN Growth" contains both words, so testing GROWTH before REINVEST
        // puts the reinvestment ISIN into the growth field and loses one of them entirely.
        assertThat(daily.isinGrowth()).isEqualTo(1);
        assertThat(daily.isinReinvest()).isEqualTo(2);
    }

    @Test
    @DisplayName("a file with no column header says its layout was assumed rather than read")
    void missingHeaderIsReportedAsAssumed() {
        String file = "Open Ended Schemes(Equity Scheme - Mid Cap Fund)\n"
                + "\n"
                + "Axis Mutual Fund\n"
                + "\n"
                + "120505;INF846K01CH8;-;Axis Midcap Fund;Direct Plan;Growth;123.4560;18-Sep-2026\n";

        AmfiNavParser.ParseResult r = AmfiNavParser.parse(file);

        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.layout().source())
                .as("an assumption must be reportable as one, never presented as a fact")
                .contains("assumed");
    }

    @Test
    @DisplayName("a category header carries down onto every row beneath it")
    void categoryCarriesDown() {
        String file = HEADER + "\n"
                + "\n"
                + "Open Ended Schemes(Equity Scheme - Flexi Cap Fund)\n"
                + "\n"
                + "Abakkus Mutual Fund\n"
                + "\n"
                + "154043;INF2JJD01136;-;Abakkus Flexi Cap Fund;Direct Plan;Growth;11.1510;18-Sep-2026\n";

        AmfiNavParser.ParseResult r = AmfiNavParser.parse(file);

        assertThat(r.rowCount()).isEqualTo(1);
        AmfiSchemeRow row = r.rows().get(0);
        assertThat(row.category()).isEqualTo(SebiCategory.FLEXI_CAP);
        assertThat(row.structure()).isEqualTo(SchemeStructure.OPEN_ENDED);
        assertThat(row.amcName()).isEqualTo("Abakkus Mutual Fund");
        assertThat(row.schemeCode()).isEqualTo(154043L);
        assertThat(row.isinReinvest()).as("AMFI writes \"-\" for an absent ISIN; that is null, not a value").isNull();
        assertThat(r.unparsedLines()).isZero();
    }

    @Test
    @DisplayName("an AMC name containing brackets is not a category header — the real defect")
    void amcNameContainingBracketsIsNotACategory() {
        // "IL&FS Mutual Fund (IDF)" is an AMC, not a category. The first version of
        // looksLikeCategoryHeader() tested only for a parenthesis, so this line was read as a
        // category header and every scheme beneath it inherited UNCLASSIFIED — 2,535 rows, 18% of
        // the file, silently filed under a category that does not exist. Nothing threw; the row
        // count was still right; only counting rows per category exposed it.
        String file = HEADER + "\n"
                + "Open Ended Schemes(Equity Scheme - Mid Cap Fund)\n"
                + "\n"
                + "IL&FS Mutual Fund (IDF)\n"
                + "\n"
                + "120505;INF846K01EH3;-;Axis Midcap Fund;Direct Plan;Growth Option;143.87;18-Sep-2026\n";

        AmfiNavParser.ParseResult r = AmfiNavParser.parse(file);

        AmfiSchemeRow row = r.rows().get(0);
        assertThat(row.category())
                .as("the scheme must keep the real category above the AMC line")
                .isEqualTo(SebiCategory.MID_CAP);
        assertThat(row.amcName()).isEqualTo("IL&FS Mutual Fund (IDF)");
        assertThat(r.unclassifiedHeaders()).isEmpty();
        assertThat(r.categoryHeadersSeen()).isEqualTo(1);
    }

    @Test
    @DisplayName("the column header repeats mid-file and is never a scheme row")
    void repeatedColumnHeaderIsSkipped() {
        String file = HEADER + "\n"
                + "Open Ended Schemes(Equity Scheme - Large Cap Fund)\n"
                + "Axis Mutual Fund\n"
                + "120503;INF846K01131;-;Axis Bluechip Fund;Direct Plan;Growth;62.30;18-Sep-2026\n"
                + HEADER + "\n"
                + "120504;INF846K01149;-;Axis Bluechip Fund;Regular Plan;Growth;58.10;18-Sep-2026\n";

        AmfiNavParser.ParseResult r = AmfiNavParser.parse(file);

        assertThat(r.rowCount()).isEqualTo(2);
        assertThat(r.unparsedLines()).isZero();
    }

    @Test
    @DisplayName("an absent NAV is null, never zero")
    void absentNavIsNullNotZero() {
        // A zero here would flow into every return calculation downstream as a total loss. A scheme
        // that did not publish is not a scheme worth nothing (Gotcha 21, 22).
        String file = HEADER + "\n"
                + "Open Ended Schemes(Equity Scheme - Value Fund)\n"
                + "Some Mutual Fund\n"
                + "100001;INF000000001;-;Some Value Fund;Direct Plan;Growth;N.A.;18-Sep-2026\n"
                + "100002;INF000000002;-;Other Value Fund;Direct Plan;Growth;-;18-Sep-2026\n";

        AmfiNavParser.ParseResult r = AmfiNavParser.parse(file);

        assertThat(r.rows()).allSatisfy(row -> {
            assertThat(row.nav()).isNull();
            assertThat(row.hasNav()).isFalse();
        });
        assertThat(r.unparsedLines()).as("an absent NAV is a readable row, not a parse failure").isZero();
    }

    @Test
    @DisplayName("a NAV dated ahead of the file is kept — liquid funds strike a next-day NAV")
    void futureDatedRowIsKept() {
        // 706 rows in the live file are dated ahead of the headline date, all liquid and overnight
        // funds. Treating a future date as corruption would silently drop a whole category.
        String file = HEADER + "\n"
                + "Open Ended Schemes(Debt Scheme - Liquid Fund)\n"
                + "Some Mutual Fund\n"
                + "100003;INF000000003;-;Some Liquid Fund;Direct Plan;Growth;1234.5678;20-Sep-2026\n";

        AmfiNavParser.ParseResult r = AmfiNavParser.parse(file);

        assertThat(r.rows().get(0).navDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(r.rows().get(0).hasNav()).isTrue();
    }

    @Test
    @DisplayName("an unreadable date is null, never today")
    void unreadableDateIsNull() {
        // Filing a row of unknown date under today's date is how a decade-old document becomes
        // current (Gotcha 100, B-119).
        String file = HEADER + "\n"
                + "Open Ended Schemes(Equity Scheme - Focused Fund)\n"
                + "Some Mutual Fund\n"
                + "100004;INF000000004;-;Some Focused Fund;Direct Plan;Growth;10.00;not-a-date\n";

        AmfiNavParser.ParseResult r = AmfiNavParser.parse(file);

        assertThat(r.rows().get(0).navDate()).isNull();
        assertThat(r.rows().get(0).hasNav()).isFalse();
    }

    @Test
    @DisplayName("a malformed data line is counted, not silently dropped")
    void malformedLineIsCounted() {
        // An empty result and a result that quietly lost half its rows must not look alike — the
        // B-054 rule. The count is returned rather than logged so a layout change is loud.
        String file = HEADER + "\n"
                + "Open Ended Schemes(Equity Scheme - Contra Fund)\n"
                + "Some Mutual Fund\n"
                + "100005;INF000000005;-;Some Contra Fund;Direct Plan;Growth;10.00;18-Sep-2026\n"
                + "NOT-A-CODE;INF000000006;-;Broken Row;Direct Plan;Growth;10.00;18-Sep-2026\n"
                + "too;few;fields\n";

        AmfiNavParser.ParseResult r = AmfiNavParser.parse(file);

        assertThat(r.rowCount()).isEqualTo(1);
        assertThat(r.unparsedLines()).isEqualTo(2);
    }

    @Test
    @DisplayName("only open-ended, actively managed, Direct-plan Growth rows are screenable")
    void screenableSetIsNarrow() {
        String file = HEADER + "\n"
                + "Open Ended Schemes(Equity Scheme - Small Cap Fund)\n"
                + "Some Mutual Fund\n"
                + "1;INF1;-;A Small Cap Fund;Direct Plan;Growth;10.00;18-Sep-2026\n"
                + "2;INF2;-;A Small Cap Fund;Regular Plan;Growth;10.00;18-Sep-2026\n"
                + "3;INF3;-;A Small Cap Fund;Direct Plan;IDCW;10.00;18-Sep-2026\n"
                + "Close Ended Schemes(Equity Scheme - Small Cap Fund)\n"
                + "Some Mutual Fund\n"
                + "4;INF4;-;A Closed Small Cap Fund;Direct Plan;Growth;10.00;18-Sep-2026\n";

        AmfiNavParser.ParseResult r = AmfiNavParser.parse(file);

        // The Regular clone would double the peer population; the IDCW clone is a payout-depressed
        // series measured on a different scale; the close-ended row is unbuyable.
        assertThat(r.screenable()).hasSize(1);
        assertThat(r.screenable().get(0).schemeCode()).isEqualTo(1L);
    }

    @Test
    @DisplayName("an empty or header-only file yields no rows and says so without throwing")
    void emptyFileIsSafe() {
        assertThat(AmfiNavParser.parse("").rowCount()).isZero();
        assertThat(AmfiNavParser.parse(HEADER).rowCount()).isZero();
        assertThat(AmfiNavParser.parse(HEADER).unparsedLines()).isZero();
    }
}
