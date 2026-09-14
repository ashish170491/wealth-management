package com.example.trading.fundamentals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the fundamentals CSV import (SPEC §32.1-2, F5).
 *
 * <p>The import is a one-time bridge to a decade of history the live feed cannot reach, so
 * the properties defended are about not corrupting that history: an unparseable cell must
 * stay null rather than become zero, a percentage row must not be mistaken for a rupee row,
 * and a column that yields nothing must not be counted as a year of data.
 */
class FundamentalsImportTest {

    private final FundamentalsHistoryService service = new FundamentalsHistoryService(null, null);

    /** A cut-down screener.in "Data Sheet" export: metrics down, years across. */
    private static final String SCREENER_CSV = String.join("\n",
            "PROFIT & LOSS",
            "Report Date,Mar-2022,Mar-2023,Mar-2024,Mar-2025",
            "Sales,\"1,000\",\"1,100\",\"1,250\",\"1,450\"",
            "Sales Growth %,8.0,10.0,13.6,16.0",
            "Operating Profit,80,95,120,200",
            "Interest,90,75,60,45",
            "Depreciation,30,32,35,38",
            "Net profit,10,25,55,110",
            "",
            "BALANCE SHEET",
            "Report Date,Mar-2022,Mar-2023,Mar-2024,Mar-2025",
            "Equity Share Capital,100,100,100,100",
            "Reserves,400,420,480,600",
            "Borrowings,900,800,650,550",
            "Receivables,200,220,240,260",
            "Capital Work in Progress,50,80,140,260",
            "No. of Equity Shares,50,50,50,50",
            "",
            "CASH FLOW:",
            "Report Date,Mar-2022,Mar-2023,Mar-2024,Mar-2025",
            "Cash from Operating Activity,60,90,130,190");

    @Test
    @DisplayName("A screener-style export parses into one row per financial year")
    void parsesScreenerExport() {
        List<String> warnings = new ArrayList<>();
        Map<Integer, AnnualFundamentalsEntity> byYear = service.parse("NSE:ACME", SCREENER_CSV, warnings);

        assertThat(byYear.keySet()).containsExactlyInAnyOrder(2022, 2023, 2024, 2025);
        assertThat(warnings).isEmpty();

        AnnualFundamentalsEntity y25 = byYear.get(2025);
        assertThat(y25.getSales()).isEqualTo(1450.0);          // thousands separator handled
        assertThat(y25.getOperatingProfit()).isEqualTo(200.0);
        assertThat(y25.getNetProfit()).isEqualTo(110.0);
        assertThat(y25.getInterestCost()).isEqualTo(45.0);
        assertThat(y25.getBorrowings()).isEqualTo(550.0);
        assertThat(y25.getReceivables()).isEqualTo(260.0);
        assertThat(y25.getOperatingCashFlow()).isEqualTo(190.0);
        assertThat(y25.getShareCount()).isEqualTo(50.0);
        assertThat(y25.getCapitalWorkInProgress()).isEqualTo(260.0);
        // Equity is share capital + reserves, summed across two separate rows.
        assertThat(y25.getEquity()).isEqualTo(700.0);
        assertThat(y25.getSource()).isEqualTo("IMPORT");
    }

    /**
     * The real screener.in layout: a QUARTERS block sits between the annual P&L and the
     * balance sheet, with its own header of month columns. B-047.
     */
    private static final String SCREENER_CSV_WITH_QUARTERS = String.join("\n",
            "PROFIT & LOSS",
            "Report Date,Mar-2022,Mar-2023,Mar-2024,Mar-2025",
            "Sales,\"1,000\",\"1,100\",\"1,250\",\"1,450\"",
            "Net profit,10,25,55,110",
            "",
            "Quarters",
            "Report Date,Jun-2024,Sep-2024,Dec-2024,Mar-2025",
            "Sales,300,320,360,470",
            "Net profit,20,24,30,36",
            "",
            "BALANCE SHEET",
            "Report Date,Mar-2022,Mar-2023,Mar-2024,Mar-2025",
            "Borrowings,900,800,650,550",
            "Receivables,200,220,240,260");

    @Test
    @DisplayName("B-047: a quarterly block never overwrites the annual figures")
    void quarterlyBlockIsNotReadAsAnnual() {
        List<String> warnings = new ArrayList<>();
        var byYear = service.parse("NSE:ACME", SCREENER_CSV_WITH_QUARTERS, warnings);

        // Mar-2025 annual sales are 1,450. The quarterly block's Mar-2025 column holds 470
        // — one quarter. Applying the annual column map to the quarterly rows wrote 470
        // into the year, and every CAGR, margin and turnaround verdict downstream then ran
        // on a quarter labelled as a year.
        assertThat(byYear.get(2025).getSales()).isEqualTo(1450.0);
        assertThat(byYear.get(2025).getNetProfit()).isEqualTo(110.0);

        // The balance sheet AFTER the quarterly block must still be read: the parser has to
        // resume at the next annual header, not stop at the first quarterly one.
        assertThat(byYear.get(2025).getBorrowings()).isEqualTo(550.0);
        assertThat(byYear.get(2025).getReceivables()).isEqualTo(260.0);

        // And it must say what it skipped. A silently dropped section reads as "the file
        // did not contain that data".
        assertThat(warnings).anyMatch(w -> w.contains("quarterly block"));
    }

    @Test
    @DisplayName("B-046: an absolute share count is normalised to crore")
    void absoluteShareCountsAreNormalised() {
        String csv = String.join("\n",
                "Report Date,Mar-2024,Mar-2025",
                "Sales,1000,1100",
                "No. of Equity Shares,\"12,529,500,000\",\"12,529,500,000\"");
        var byYear = service.parse("NSE:ITC", csv, new ArrayList<>());

        // 12,529,500,000 shares = 1,252.95 crore (ITC's real count). The XBRL pipeline writes crore, so an
        // un-normalised import would put a 10-million-fold step in the middle of the
        // series and the dilution check would read it as a corporate event.
        assertThat(byYear.get(2025).getShareCount()).isEqualTo(1252.95);
    }

    @Test
    @DisplayName("B-046: a share count already in crore is left alone")
    void croreShareCountsAreLeftAlone() {
        String csv = String.join("\n",
                "Report Date,Mar-2024,Mar-2025",
                "Sales,1000,1100",
                "No. of Equity Shares,50,52");
        var byYear = service.parse("NSE:ACME", csv, new ArrayList<>());
        assertThat(byYear.get(2025).getShareCount()).isEqualTo(52.0);
    }

    @Test
    @DisplayName("A 'Sales Growth %' row is not mistaken for the sales figure")
    void percentageRowsDoNotOverwriteFigures() {
        var byYear = service.parse("NSE:ACME", SCREENER_CSV, new ArrayList<>());

        // 16.0 is the growth rate for 2025; 1450 is the sales figure. Matching on
        // "contains" instead of "startsWith" would silently swap one for the other.
        assertThat(byYear.get(2025).getSales()).isEqualTo(1450.0);
    }

    @Test
    @DisplayName("The parsed history feeds the detectors end to end")
    void parsedHistoryIsUsableByDetectors() {
        var byYear = service.parse("NSE:ACME", SCREENER_CSV, new ArrayList<>());
        List<AnnualFundamentalsEntity> ordered = new ArrayList<>(byYear.values());
        ordered.sort(java.util.Comparator.comparing(AnnualFundamentalsEntity::getFiscalYear));

        var r = TurnaroundDetectionService.classify("NSE:ACME", ordered);
        assertThat(r.getVerdict()).isEqualTo("TURNAROUND_CANDIDATE");
    }

    @Test
    @DisplayName("A file with no year columns is rejected with a usable message")
    void rejectsFileWithoutYears() {
        List<String> warnings = new ArrayList<>();
        var byYear = service.parse("NSE:ACME", "Metric,Value\nSales,1000\nProfit,100", warnings);

        assertThat(byYear).isEmpty();
        assertThat(warnings).isNotEmpty();
        assertThat(warnings.get(0)).contains("financial-year columns");
    }

    @Test
    @DisplayName("Year columns with no recognisable metrics are reported, not silently accepted")
    void reportsYearsWithoutMetrics() {
        List<String> warnings = new ArrayList<>();
        var byYear = service.parse("NSE:ACME",
                "Report Date,Mar-2024,Mar-2025\nSome Unknown Metric,1,2", warnings);

        assertThat(byYear).isEmpty();
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("no recognisable metric rows"));
    }

    @Test
    @DisplayName("Unparseable cells stay null instead of becoming zero")
    void unparseableCellsStayNull() {
        var byYear = service.parse("NSE:ACME", String.join("\n",
                "Report Date,Mar-2024,Mar-2025",
                "Sales,1200,1300",
                "Borrowings,NA,-"), new ArrayList<>());

        // A zero here would read as a debt-free balance sheet and flip a turnaround verdict.
        assertThat(byYear.get(2024).getBorrowings()).isNull();
        assertThat(byYear.get(2025).getBorrowings()).isNull();
        assertThat(byYear.get(2024).getSales()).isEqualTo(1200.0);
    }

    @Test
    @DisplayName("Number parsing handles separators, currency marks and bracketed negatives")
    void numberParsing() {
        assertThat(FundamentalsHistoryService.parseNumber("1,234.5")).isEqualTo(1234.5);
        assertThat(FundamentalsHistoryService.parseNumber("(250)")).isEqualTo(-250.0);
        assertThat(FundamentalsHistoryService.parseNumber("₹1,000")).isEqualTo(1000.0);
        assertThat(FundamentalsHistoryService.parseNumber("12.5%")).isEqualTo(12.5);
        assertThat(FundamentalsHistoryService.parseNumber("")).isNull();
        assertThat(FundamentalsHistoryService.parseNumber("NA")).isNull();
        assertThat(FundamentalsHistoryService.parseNumber("-")).isNull();
        assertThat(FundamentalsHistoryService.parseNumber(null)).isNull();
    }

    @Test
    @DisplayName("Financial-year labels are read from several conventions, including NSE's own")
    void fiscalYearParsing() {
        assertThat(FundamentalsHistoryService.fiscalYearOf("Mar-2024")).isEqualTo(2024);
        assertThat(FundamentalsHistoryService.fiscalYearOf("Mar 2024")).isEqualTo(2024);
        assertThat(FundamentalsHistoryService.fiscalYearOf("FY2024")).isEqualTo(2024);
        assertThat(FundamentalsHistoryService.fiscalYearOf("2024")).isEqualTo(2024);
        // NSE writes the whole span; the year it ENDS in is the fiscal year.
        assertThat(FundamentalsHistoryService.fiscalYearOf("01-Apr-2023 To 31-Mar-2024")).isEqualTo(2024);
        assertThat(FundamentalsHistoryService.fiscalYearOf("Report Date")).isNull();
        assertThat(FundamentalsHistoryService.fiscalYearOf("")).isNull();
        assertThat(FundamentalsHistoryService.fiscalYearOf(null)).isNull();
    }

    @Test
    @DisplayName("Quoted fields containing commas survive the split as one field")
    void csvSplitHandlesQuotedCommas() {
        String[] parts = FundamentalsHistoryService.splitCsvLine("Sales,\"1,000\",\"2,500\"");
        // The split keeps the field intact including its separator; stripping the comma is
        // parseNumber's job, one layer up. Splitting on the inner comma would turn a
        // four-year row into a seven-column row and misalign every year after it.
        assertThat(parts).containsExactly("Sales", "1,000", "2,500");
        assertThat(FundamentalsHistoryService.parseNumber(parts[1])).isEqualTo(1000.0);
    }
}
