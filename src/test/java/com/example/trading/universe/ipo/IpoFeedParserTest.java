package com.example.trading.universe.ipo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the NSE IPO feed parsing (SPEC §45.2) on rows captured live on 2026-09-09.
 *
 * <p>Two properties matter more than the rest: a placeholder is null (Gotcha 61) and an
 * issue-size sentence that mentions a leg whose amount cannot be read leaves the split
 * unmeasured rather than defaulting that leg to zero.
 */
class IpoFeedParserTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ---- list rows ----

    @Test
    @DisplayName("Upcoming-feed row: band, dates, size and series are read")
    void listRow() {
        IpoFeedParser.ListRow r = IpoFeedParser.parseListRow(map(
                "companyName", "LCC Projects Limited", "issueEndDate", "11-Sep-2026",
                "issuePrice", "Rs.139 to Rs.146", "issueSize", "20927281",
                "issueStartDate", "09-Sep-2026", "series", "EQ", "status", "Active", "symbol", "LCCPROJECT"));
        assertThat(r.tradingSymbol()).isEqualTo("LCCPROJECT");
        assertThat(r.isMainboard()).isTrue();
        assertThat(r.bandLow()).isEqualTo(139.0);
        assertThat(r.bandHigh()).isEqualTo(146.0);
        assertThat(r.issueStart()).isEqualTo(LocalDate.of(2026, 9, 9));
        assertThat(r.issueEnd()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(r.issueSizeShares()).isEqualTo(20927281L);
        assertThat(r.totalTimes()).isNull();
    }

    @Test
    @DisplayName("Current-feed row carries the running total subscription as a number")
    void currentRowTotalTimes() {
        IpoFeedParser.ListRow r = IpoFeedParser.parseListRow(map(
                "symbol", "KARAMTARA", "series", "EQ", "noOfTime", "0.5174950069716876"));
        assertThat(r.totalTimes()).isCloseTo(0.5175, within(0.0001));
    }

    @Test
    @DisplayName("An SME row is parsed but is not mainboard")
    void smeIsNotMainboard() {
        IpoFeedParser.ListRow r = IpoFeedParser.parseListRow(map("symbol", "X", "series", "SM"));
        assertThat(r.isMainboard()).isFalse();
    }

    // ---- past rows ----

    @Test
    @DisplayName("Past-issue row: padded price, upper-case month, EQ type")
    void pastRowListed() {
        IpoFeedParser.PastRow r = IpoFeedParser.parsePastRow(map(
                "company", "Deepa Jewellers Limited", "htmSym", "deepa", "ipoEndDate", "03-SEP-2026",
                "ipoStartDate", "01-SEP-2026", "issuePrice", "   177", "linkRemovalDate", "04-SEP-2026",
                "listingDate", "08-SEP-2026", "priceRange", "Rs.168 to Rs.177", "securityType", "EQ",
                "symbol", "DEEPA"));
        assertThat(r.companyName()).isEqualTo("Deepa Jewellers Limited");
        assertThat(r.issuePrice()).isEqualTo(177.0);
        assertThat(r.listingDate()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(r.bandLow()).isEqualTo(168.0);
        assertThat(r.bandHigh()).isEqualTo(177.0);
        assertThat(r.isMainboard()).isTrue();
    }

    @Test
    @DisplayName("NSE's '-' placeholder is null, never a number or a date (Gotcha 61)")
    void placeholdersAreNull() {
        IpoFeedParser.PastRow r = IpoFeedParser.parsePastRow(map(
                "companyName", "Qualiance International Limited", "ipoEndDate", "08-SEP-2026",
                "ipoStartDate", "04-SEP-2026", "issuePrice", "-", "listingDate", "-",
                "priceRange", "Rs.120 to Rs.127", "securityType", "SME", "symbol", "QUALIANCE"));
        assertThat(r.issuePrice()).isNull();
        assertThat(r.listingDate()).isNull();
        assertThat(r.isMainboard()).isFalse();
    }

    @Test
    @DisplayName("A fixed-price issue has a single-number band")
    void fixedPriceBand() {
        IpoFeedParser.PastRow r = IpoFeedParser.parsePastRow(map(
                "symbol", "THEJO", "priceRange", "402", "securityType", "SME", "issuePrice", null));
        assertThat(r.bandLow()).isEqualTo(402.0);
        assertThat(r.bandHigh()).isEqualTo(402.0);
        assertThat(r.issuePrice()).isNull();
    }

    // ---- issue size ----

    @Test
    @DisplayName("Fresh in rupees, OFS in shares priced at the top of the band")
    void freshRupeesOfsShares() {
        IpoFeedParser.IssueSplit s = IpoFeedParser.parseIssueSize(
                "Initial public offer comprising of Fresh Issue aggregating up to Rs. 2580 million and Offer for "
                        + "sale of up to 11,585,000 Equity shares (Including Anchor Investor portion of 87,76,869 Equity Shares)",
                146.0);
        assertThat(s.freshCr()).isCloseTo(258.0, within(0.01));
        assertThat(s.offerForSaleCr()).isCloseTo(11_585_000 * 146.0 / 1e7, within(0.01));
        assertThat(s.freshSharePct()).isCloseTo(60.4, within(0.5));
    }

    @Test
    @DisplayName("Both legs in rupees, million and crore units")
    void bothLegsRupees() {
        IpoFeedParser.IssueSplit s = IpoFeedParser.parseIssueSize(
                "Initial Public offer comprising of Fresh issue aggregating up to Rs. 800 million and Offer for sale "
                        + "aggregating up to Rs. 4200 million (including Anchor portion of 22,18,930 Equity Shares)", 676.0);
        assertThat(s.freshCr()).isEqualTo(80.0);
        assertThat(s.offerForSaleCr()).isEqualTo(420.0);
        assertThat(s.freshSharePct()).isCloseTo(16.0, within(0.01));
    }

    @Test
    @DisplayName("A rupee amount with no 'Rs.' marker still parses when the unit word is present (MPIMANIPAL, first live run)")
    void amountWithoutCurrencyMarker() {
        IpoFeedParser.IssueSplit s = IpoFeedParser.parseIssueSize(
                "Initial Public Offer comprising of fresh issue aggregating up to 3200 million and offer for sale up to "
                        + "14,306,785 Equity shares  (including Anchor portion of 1,06,85,841 Equity Shares)", 339.0);
        assertThat(s.freshCr()).isEqualTo(320.0);
        assertThat(s.offerForSaleCr()).isCloseTo(14_306_785 * 339.0 / 1e7, within(0.01));
    }

    @Test
    @DisplayName("Pure offer for sale: fresh is a measured zero, not unmeasured")
    void pureOfs() {
        IpoFeedParser.IssueSplit s = IpoFeedParser.parseIssueSize(
                "Initial Public Offer comprising of Offer for Sale of up to 5,27,31,946 Equity shares (including "
                        + "Anchor portion of 1,58,19,583 Equity Shares)", 139.0);
        assertThat(s.freshCr()).isEqualTo(0.0);
        assertThat(s.offerForSaleCr()).isCloseTo(5_27_31_946L * 139.0 / 1e7, within(0.01));
        assertThat(s.freshSharePct()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Pure fresh issue in lakhs")
    void pureFreshLakhs() {
        IpoFeedParser.IssueSplit s = IpoFeedParser.parseIssueSize(
                "Initial Public offer comprising of Fresh issue of aggregating up to Rs 21,000 lakhs ", 500.0);
        assertThat(s.freshCr()).isEqualTo(210.0);
        assertThat(s.offerForSaleCr()).isEqualTo(0.0);
        assertThat(s.freshSharePct()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Indian digit grouping and an employee reservation clause do not derail the OFS leg")
    void indianGroupingAndEmployeeClause() {
        IpoFeedParser.IssueSplit s = IpoFeedParser.parseIssueSize(
                "Initial Public Offer comprising of Fresh Issue aggregating upto Rs. 1,500 million and Offer for Sale of "
                        + "upto 27,365,529 Equity Shares (including Employee Reservation Portion aggregating up to Rs. 20 "
                        + "million and Anchor reservation portion of 93,08,667 Equity Shares)", 404.0);
        assertThat(s.freshCr()).isEqualTo(150.0);
        assertThat(s.offerForSaleCr()).isCloseTo(27_365_529 * 404.0 / 1e7, within(0.01));
    }

    @Test
    @DisplayName("A sentence naming a fresh issue whose amount cannot be read is unmeasured, not zero")
    void unreadableFreshLegIsUnmeasured() {
        IpoFeedParser.IssueSplit s = IpoFeedParser.parseIssueSize(
                "Initial public offer comprising of a fresh issue and an offer for sale of up to 1,000,000 equity shares", 100.0);
        assertThat(s.freshCr()).isNull();
        assertThat(s.offerForSaleCr()).isNull();
        assertThat(s.freshSharePct()).isNull();
    }

    @Test
    @DisplayName("Shares-denominated leg without a band is unmeasured")
    void sharesLegNeedsBand() {
        IpoFeedParser.IssueSplit s = IpoFeedParser.parseIssueSize(
                "Fresh Issue aggregating up to Rs. 100 million and Offer for sale of up to 1,000,000 Equity shares", null);
        assertThat(s.freshSharePct()).isNull();
    }

    @Test
    @DisplayName("Blank or null text is unmeasured")
    void blankIsUnmeasured() {
        assertThat(IpoFeedParser.parseIssueSize(null, 10.0).freshSharePct()).isNull();
        assertThat(IpoFeedParser.parseIssueSize("  ", 10.0).freshSharePct()).isNull();
    }

    // ---- detail ----

    @Test
    @DisplayName("Detail page: info rows, links, quotas and the category book keyed by serial number")
    void detail() {
        List<Map<String, Object>> info = List.of(
                map("title", "Symbol", "value", "RENTOMOJO"),
                map("title", "Issue Size", "value", "\"Initial Public Offer comprising of Fresh Issue aggregating upto Rs. 1,500 million and Offer for Sale of upto 27,365,529 Equity Shares\""),
                map("title", "Issue Type", "value", "Book Building"),
                map("title", "Price Range", "value", "Rs. 384 to Rs. 404 per Equity Share"),
                map("title", "Discount", "value", "Rs. 20 per equity share to Eligible Employees"),
                map("title", "Face Value", "value", "Rs. 2 per Equity Share"),
                map("title", "Bid Lot", "value", "37 Equity Shares and in multiples thereof"),
                map("title", "Book Running Lead Managers", "value", "ICICI Securities Limited"),
                map("title", "Name of the Registrar", "value", "MUFG Intime India Private Limited"),
                map("title", "Red Herring Prospectus", "value", "https://nsearchives.nseindia.com/content/ipo/RHP_RENTOMOJO.zip"),
                map("title", "Processing of ASBA Applications", "value", "<a href=https://archives.nseindia.com/content/circulars/IPO53197.pdf target=new>NSE Circular</a>"),
                map("title", null, "value", "some SEBI boilerplate"));
        List<Map<String, Object>> bids = List.of(
                map("category", "Qualified Institutional Buyers(QIBs)", "noOfSharesOffered", "10012000", "noOfTime", "77.01", "noOfsharesBid", "771046848", "srNo", "1"),
                map("category", "Foreign Institutional Investors(FIIs)", "noOfSharesOffered", "", "noOfTime", "", "noOfsharesBid", "225197730", "srNo", "1(a)"),
                map("category", "Non Institutional Investors", "noOfSharesOffered", "7509001", "noOfTime", "21.42", "noOfsharesBid", "160880940", "srNo", "2"),
                map("category", "Non Institutional Investors(Bid amount of more than Ten Lakh Rupees)", "noOfSharesOffered", "5006001", "noOfTime", "23.29", "noOfsharesBid", "116603460", "srNo", "2.1"),
                map("category", "Non Institutional Investors(Bid amount of more than Two Lakh Rupees upto Ten Lakh Rupees)", "noOfSharesOffered", "2503000", "noOfTime", "17.6", "noOfsharesBid", "44277480", "srNo", "2.2"),
                map("category", "Retail Individual Investors(RIIs)", "noOfSharesOffered", "17521002", "noOfTime", "7.76", "noOfsharesBid", "135962000", "srNo", "3"),
                map("category", "Cut Off", "noOfSharesOffered", "", "noOfTime", "", "noOfsharesBid", "1", "srNo", "3(a)"),
                map("category", "Employees", "noOfSharesOffered", "52083", "noOfTime", "0.2152", "noOfsharesBid", "11211", "srNo", "4"),
                map("category", "Total", "noOfSharesOffered", "35094086", "noOfTime", "30.5", "noOfsharesBid", "1070000000", "srNo", null));
        Map<String, Object> d = map("companyName", "RENTOMOJO",
                "issueInfo", map("dataList", info), "bidDetails", bids);

        IpoFeedParser.Detail x = IpoFeedParser.parseDetail(d);
        assertThat(x.issueType()).isEqualTo("Book Building");
        assertThat(x.faceValue()).isEqualTo(2.0);
        assertThat(x.lotSize()).isEqualTo(37);
        assertThat(x.bandLow()).isEqualTo(384.0);
        assertThat(x.bandHigh()).isEqualTo(404.0);
        assertThat(x.employeeDiscountRs()).isEqualTo(20.0);
        assertThat(x.rhpUrl()).isEqualTo("https://nsearchives.nseindia.com/content/ipo/RHP_RENTOMOJO.zip");
        assertThat(x.anchorUrl()).isNull();
        assertThat(x.issueSizeText()).startsWith("Initial Public Offer").doesNotStartWith("\"");
        assertThat(x.category(IpoFeedParser.QIB).times()).isEqualTo(77.01);
        assertThat(x.category(IpoFeedParser.NII).times()).isEqualTo(21.42);
        assertThat(x.category(IpoFeedParser.BIG_NII).times()).isEqualTo(23.29);
        assertThat(x.category(IpoFeedParser.SMALL_NII).times()).isEqualTo(17.6);
        assertThat(x.category(IpoFeedParser.RETAIL).sharesOffered()).isEqualTo(17521002L);
        assertThat(x.category(IpoFeedParser.EMPLOYEE).sharesOffered()).isEqualTo(52083L);
        assertThat(x.category(IpoFeedParser.SHAREHOLDER)).isNull();
        assertThat(x.category(IpoFeedParser.TOTAL).times()).isEqualTo(30.5);
    }

    @Test
    @DisplayName("An empty times cell is null while bidding has not started")
    void emptyTimesIsNull() {
        List<Map<String, Object>> bids = List.of(
                map("category", "Qualified Institutional Buyers(QIBs)", "noOfSharesOffered", "100", "noOfTime", "", "noOfsharesBid", "0", "srNo", "1"));
        IpoFeedParser.Detail x = IpoFeedParser.parseDetail(map("bidDetails", bids));
        assertThat(x.category(IpoFeedParser.QIB).times()).isNull();
        assertThat(x.category(IpoFeedParser.QIB).sharesOffered()).isEqualTo(100L);
    }

    @Test
    @DisplayName("Discount 'NA' is null; an empty detail map is null")
    void discountNaAndEmptyDetail() {
        assertThat(IpoFeedParser.discount("NA")).isNull();
        assertThat(IpoFeedParser.parseDetail(Map.of())).isNull();
        assertThat(IpoFeedParser.parseDetail(null)).isNull();
    }

    @Test
    @DisplayName("Category keys: serial number decides among the three NII rows; names decide quotas")
    void categoryKeys() {
        assertThat(IpoFeedParser.categoryKey("Non Institutional Investors", "2")).isEqualTo(IpoFeedParser.NII);
        assertThat(IpoFeedParser.categoryKey("Non Institutional Investors(Bid amount of more than Ten Lakh Rupees)", "2.1")).isEqualTo(IpoFeedParser.BIG_NII);
        assertThat(IpoFeedParser.categoryKey("Corporates", "2.1(a)")).isNull();
        assertThat(IpoFeedParser.categoryKey("Shareholders", "5")).isEqualTo(IpoFeedParser.SHAREHOLDER);
        assertThat(IpoFeedParser.categoryKey("Employees", "4")).isEqualTo(IpoFeedParser.EMPLOYEE);
        assertThat(IpoFeedParser.categoryKey("Total", null)).isEqualTo(IpoFeedParser.TOTAL);
        assertThat(IpoFeedParser.categoryKey(null, "1")).isNull();
    }
}
