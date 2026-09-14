package com.example.trading.insider;

import com.example.trading.ai.NseDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Insider Pulse signal (SPEC §28, F1).
 *
 * <p>The single most important behaviour under test is <b>mode filtering</b>. NSE's PIT feed
 * mixes open-market conviction trades with pledge creations, ESOP allotments, gifts and
 * inter-se promoter transfers. A screen that counts those as "promoter buying" is worse than
 * having no screen: a pledge creation is a promoter <em>borrowing</em> against the company,
 * which is close to the opposite signal.
 */
class InsiderPulseServiceTest {

    private final InsiderPulseService pulse = new InsiderPulseService(null);
    private final InsiderDisclosureService disclosures = new InsiderDisclosureService(null, null);

    private static InsiderDisclosureEntity row(String category, String mode, String type, double value) {
        return row(category, mode, type, value, "A Promoter");
    }

    private static InsiderDisclosureEntity row(String category, String mode, String type,
                                               double value, String personName) {
        return InsiderDisclosureEntity.builder()
                .symbol("NSE:TEST")
                .personName(personName)
                .personCategory(category)
                .mode(mode)
                .transactionType(type)
                .value(value)
                .quantity(100d)
                .transactionDate(LocalDate.now().minusDays(10))
                .build();
    }

    // ---- Mode normalisation ----

    @Test
    @DisplayName("NSE acquisition modes map to the scoring vocabulary, including its own spelling of 'Revokation'")
    void modeNormalisation() {
        assertThat(InsiderDisclosureService.normaliseMode("Market Purchase")).isEqualTo("MARKET_PURCHASE");
        assertThat(InsiderDisclosureService.normaliseMode("Market Sale")).isEqualTo("MARKET_SALE");
        assertThat(InsiderDisclosureService.normaliseMode("Pledge Creation")).isEqualTo("PLEDGE_CREATION");
        assertThat(InsiderDisclosureService.normaliseMode("Revokation of Pledge")).isEqualTo("PLEDGE_REVOCATION");
        assertThat(InsiderDisclosureService.normaliseMode("Off Market")).isEqualTo("OFF_MARKET");
        assertThat(InsiderDisclosureService.normaliseMode("Gift")).isEqualTo("GIFT");
        assertThat(InsiderDisclosureService.normaliseMode("Others")).isEqualTo("OTHER");
        assertThat(InsiderDisclosureService.normaliseMode(null)).isEqualTo("OTHER");
    }

    @Test
    @DisplayName("Only open-market trades by insiders count as signal")
    void onlyOpenMarketInsiderTradesAreSignal() {
        assertThat(InsiderDisclosureService.isSignalRow(row("PROMOTER", "MARKET_PURCHASE", "BUY", 1)))
                .isTrue();
        assertThat(InsiderDisclosureService.isSignalRow(row("PROMOTER_GROUP", "MARKET_SALE", "SELL", 1)))
                .isTrue();

        // The false positives that make naive insider screens useless:
        assertThat(InsiderDisclosureService.isSignalRow(row("PROMOTER", "PLEDGE_CREATION", "BUY", 1))).isFalse();
        assertThat(InsiderDisclosureService.isSignalRow(row("PROMOTER", "PLEDGE_REVOCATION", "BUY", 1))).isFalse();
        assertThat(InsiderDisclosureService.isSignalRow(row("PROMOTER", "GIFT", "BUY", 1))).isFalse();
        assertThat(InsiderDisclosureService.isSignalRow(row("PROMOTER", "INTER_SE", "BUY", 1))).isFalse();
        assertThat(InsiderDisclosureService.isSignalRow(row("EMPLOYEE", "ESOP", "BUY", 1))).isFalse();
        // An unrelated person's open-market trade is not an insider signal either.
        assertThat(InsiderDisclosureService.isSignalRow(row("OTHER", "MARKET_PURCHASE", "BUY", 1))).isFalse();
    }

    // ---- Verdicts ----

    @Test
    @DisplayName("No disclosures at all yields a NULL verdict — distinct from NEUTRAL")
    void noRowsIsNullNotNeutral() {
        InsiderPulse p = pulse.classify("NSE:TEST", List.of(), 1000.0);
        assertThat(p.getVerdict()).isNull();
        assertThat(p.toScore()).isNull();
    }

    @Test
    @DisplayName("Only non-market disclosures also yields NULL — we saw filings but measured nothing")
    void onlyNonMarketRowsIsNull() {
        List<InsiderDisclosureEntity> rows = List.of(
                row("PROMOTER", "PLEDGE_CREATION", "BUY", 10_00_000),
                row("EMPLOYEE", "ESOP", "BUY", 5_00_000));
        InsiderPulse p = pulse.classify("NSE:TEST", rows, 1000.0);
        assertThat(p.getVerdict()).isNull();
        assertThat(p.getExcludedNonMarketRows()).isEqualTo(2);
        assertThat(p.getNotes()).anyMatch(n -> n.contains("never scored"));
    }

    @Test
    @DisplayName("Pledges cannot turn a selling promoter into an accumulating one")
    void pledgeDoesNotOffsetRealSelling() {
        // Rs 1 cr of genuine selling, plus a Rs 50 cr pledge that must be ignored entirely.
        List<InsiderDisclosureEntity> rows = List.of(
                row("PROMOTER", "MARKET_SALE", "SELL", 1_00_00_000),
                row("PROMOTER", "PLEDGE_CREATION", "BUY", 50_00_00_000L));
        // Market cap Rs 100 cr => 1 cr of selling is 1% => well past the strong threshold.
        InsiderPulse p = pulse.classify("NSE:TEST", rows, 100.0);
        assertThat(p.getVerdict()).isEqualTo("STRONG_DISTRIBUTION");
        assertThat(p.getNetBuyValue()).isNegative();
    }

    @Test
    @DisplayName("Net buying is sized against market cap, not counted in absolute rupees")
    void verdictScalesWithMarketCap() {
        List<InsiderDisclosureEntity> rows = List.of(row("PROMOTER", "MARKET_PURCHASE", "BUY", 1_00_00_000));

        // Rs 1 cr into a Rs 100 cr company is 1.0% — a strong signal.
        assertThat(pulse.classify("NSE:TEST", rows, 100.0).getVerdict()).isEqualTo("STRONG_ACCUMULATION");
        // The same Rs 1 cr into a Rs 5,000 cr company is 0.02% — noise.
        assertThat(pulse.classify("NSE:TEST", rows, 5000.0).getVerdict()).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("B-041: three trivial buys are NOT strong accumulation — Rs 3,000 is not conviction")
    void trivialRepeatedBuyingIsNotStrong() {
        // This previously returned STRONG_ACCUMULATION, the strongest verdict the system has,
        // from Rs 3,000 of total buying in a Rs 50,000 cr company. The event-count escape
        // hatch fired even though the value was known and could be sized. SEBI PIT Reg 7(2)
        // has no de-minimis threshold, so tiny filings are common and mean nothing.
        List<InsiderDisclosureEntity> rows = List.of(
                row("PROMOTER", "MARKET_PURCHASE", "BUY", 1000, "P One"),
                row("DIRECTOR", "MARKET_PURCHASE", "BUY", 1000, "D Two"),
                row("KMP", "MARKET_PURCHASE", "BUY", 1000, "K Three"));
        assertThat(pulse.classify("NSE:TEST", rows, 50000.0).getVerdict()).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("B-041: three DIFFERENT insiders buying a material amount is still strong")
    void materialClusterFromDistinctInsidersIsStrong() {
        // The signal the count path exists for: separate people, independently, at a size
        // that clears the floor. Rs 30 lakh total is far too small to register against a
        // Rs 50,000 cr market cap (0.0006%), so only the corroborated count can find it.
        List<InsiderDisclosureEntity> rows = List.of(
                row("PROMOTER", "MARKET_PURCHASE", "BUY", 10_00_000, "P One"),
                row("DIRECTOR", "MARKET_PURCHASE", "BUY", 10_00_000, "D Two"),
                row("KMP", "MARKET_PURCHASE", "BUY", 10_00_000, "K Three"));
        assertThat(pulse.classify("NSE:TEST", rows, 50000.0).getVerdict()).isEqualTo("STRONG_ACCUMULATION");
    }

    @Test
    @DisplayName("B-041: one person filing three times is one person's opinion, not a cluster")
    void samePersonSplittingATradeIsNotACluster() {
        // A director staggering a purchase across three days files three rows. Counting
        // filings instead of people turned one decision into three corroborating ones.
        List<InsiderDisclosureEntity> rows = List.of(
                row("DIRECTOR", "MARKET_PURCHASE", "BUY", 10_00_000, "D Two"),
                row("DIRECTOR", "MARKET_PURCHASE", "BUY", 10_00_000, "D Two"),
                row("DIRECTOR", "MARKET_PURCHASE", "BUY", 10_00_000, "D Two"));
        assertThat(pulse.classify("NSE:TEST", rows, 50000.0).getVerdict()).isEqualTo("NEUTRAL");
    }

    @Test
    @DisplayName("B-040: NSE's '-' placeholder is absent, not BUY — the mode decides")
    void placeholderTransactionTypeDefersToMode() {
        // "-" does not start with "s", so every blanked type became a BUY. A MARKET_SALE
        // then ADDED to net insider buying: promoters selling read as promoters buying.
        assertThat(InsiderDisclosureService.normaliseType("-", "MARKET_SALE")).isEqualTo("SELL");
        assertThat(InsiderDisclosureService.normaliseType("NA", "MARKET_SALE")).isEqualTo("SELL");
        assertThat(InsiderDisclosureService.normaliseType(" ", "MARKET_SALE")).isEqualTo("SELL");
        assertThat(InsiderDisclosureService.normaliseType(null, "MARKET_SALE")).isEqualTo("SELL");
        // A real value still wins over the mode, and an absent one still defaults to BUY
        // when the mode is a purchase.
        assertThat(InsiderDisclosureService.normaliseType("Sell", "MARKET_PURCHASE")).isEqualTo("SELL");
        assertThat(InsiderDisclosureService.normaliseType("-", "MARKET_PURCHASE")).isEqualTo("BUY");
    }

    @Test
    @DisplayName("Unknown market cap falls back to event counts and says so")
    void unknownMarketCapUsesEventCounts() {
        // One buy against one sell. The rupee amounts net positive, but without a market cap
        // those rupees cannot be sized — Rs 4 lakh net is a strong signal in a Rs 50 cr
        // company and pure noise in a Rs 50,000 cr one. Emitting a directional verdict from
        // unsized rupees would be inventing information, so the conservative NEUTRAL stands
        // and the note tells the reader why.
        List<InsiderDisclosureEntity> rows = List.of(
                row("PROMOTER", "MARKET_PURCHASE", "BUY", 5_00_000),
                row("PROMOTER", "MARKET_SALE", "SELL", 1_00_000));
        InsiderPulse p = pulse.classify("NSE:TEST", rows, null);
        assertThat(p.getVerdict()).isEqualTo("NEUTRAL");
        assertThat(p.getNetBuyPercentOfMarketCap()).isNull();
        assertThat(p.getNotes()).anyMatch(n -> n.contains("Market cap unknown"));
    }

    @Test
    @DisplayName("With no market cap, a clear majority of buy events still reads as accumulation")
    void unknownMarketCapStillReadsEventMajority() {
        List<InsiderDisclosureEntity> rows = List.of(
                row("PROMOTER", "MARKET_PURCHASE", "BUY", 5_00_000),
                row("DIRECTOR", "MARKET_PURCHASE", "BUY", 2_00_000),
                row("PROMOTER", "MARKET_SALE", "SELL", 1_00_000));
        InsiderPulse p = pulse.classify("NSE:TEST", rows, null);
        assertThat(p.getVerdict()).isEqualTo("ACCUMULATION");
    }

    @Test
    @DisplayName("Verdict-to-score mapping is monotone and never invents a 50 for null")
    void scoreMapping() {
        assertThat(InsiderPulse.builder().verdict("STRONG_ACCUMULATION").build().toScore()).isEqualTo(100);
        assertThat(InsiderPulse.builder().verdict("ACCUMULATION").build().toScore()).isEqualTo(75);
        assertThat(InsiderPulse.builder().verdict("NEUTRAL").build().toScore()).isEqualTo(50);
        assertThat(InsiderPulse.builder().verdict("DISTRIBUTION").build().toScore()).isEqualTo(25);
        assertThat(InsiderPulse.builder().verdict("STRONG_DISTRIBUTION").build().toScore()).isZero();
        assertThat(InsiderPulse.builder().verdict(null).build().toScore()).isNull();
    }

    // ---- Parsing ----

    @Test
    @DisplayName("Both NSE date casings parse — 'Feb' from PIT and 'AUG' from the archive CSVs")
    void datesParseInBothCasings() {
        assertThat(InsiderDisclosureService.parseDate("13-Feb-2026")).isEqualTo(LocalDate.of(2026, 2, 13));
        assertThat(InsiderDisclosureService.parseDate("25-AUG-2026")).isEqualTo(LocalDate.of(2026, 8, 25));
        assertThat(InsiderDisclosureService.parseDate("garbage")).isNull();
        assertThat(InsiderDisclosureService.parseDate(null)).isNull();
    }

    @Test
    @DisplayName("A PIT row maps into an entity with quantity, category and mode normalised")
    void pitRowMapping() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("acqName", "Ramesh Promoter");
        raw.put("personCategory", "Promoter Group");
        raw.put("acqMode", "Market Purchase");
        raw.put("secAcq", "2,500");
        raw.put("secVal", "0");
        raw.put("acqfromDt", "13-Feb-2026");
        raw.put("date", "18-Feb-2026 19:06");
        raw.put("tdpTransactionType", "Buy");

        InsiderDisclosureEntity e = disclosures.toEntity("NSE:TEST", "TEST", raw, 200.0);
        assertThat(e).isNotNull();
        assertThat(e.getPersonCategory()).isEqualTo("PROMOTER_GROUP");
        assertThat(e.getMode()).isEqualTo("MARKET_PURCHASE");
        assertThat(e.getTransactionType()).isEqualTo("BUY");
        assertThat(e.getQuantity()).isEqualTo(2500d);
        // Filing omitted the value, so it is derived from quantity x price rather than left 0.
        assertThat(e.getValue()).isEqualTo(5_00_000d);
        assertThat(e.getTransactionDate()).isEqualTo(LocalDate.of(2026, 2, 13));
        assertThat(e.getDisclosureHash()).isNotBlank();
    }

    @Test
    @DisplayName("The same filing always hashes identically, so re-ingestion cannot duplicate it")
    void hashIsStableForIdempotentIngest() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("acqName", "Ramesh Promoter");
        raw.put("personCategory", "Promoter");
        raw.put("acqMode", "Market Purchase");
        raw.put("secAcq", "2500");
        raw.put("acqfromDt", "13-Feb-2026");
        raw.put("tdpTransactionType", "Buy");

        String h1 = disclosures.toEntity("NSE:TEST", "TEST", raw, 200.0).getDisclosureHash();
        String h2 = disclosures.toEntity("NSE:TEST", "TEST", raw, 200.0).getDisclosureHash();
        assertThat(h1).isEqualTo(h2);
        // Fixed 64-char SHA-256 hex. The natural key contains person names, and real NSE
        // entity names ("Sohan Devi Nand Lal Nuwal Family Trust") overflowed the column
        // when the key was stored raw, failing the whole batch insert.
        assertThat(h1).hasSize(64);
    }

    @Test
    @DisplayName("A very long entity name still produces a column-safe hash")
    void longEntityNamesDoNotOverflowTheHashColumn() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("acqName", "Sohan Devi Nand Lal Nuwal Family Private Discretionary Trust Acting Through "
                + "Its Trustee Shri Kailash Chandra Nuwal And Others Along With Persons Acting In Concert");
        raw.put("personCategory", "Promoter Group");
        raw.put("acqMode", "Market Purchase");
        raw.put("secAcq", "1000");
        raw.put("acqfromDt", "13-Feb-2026");
        raw.put("tdpTransactionType", "Buy");

        InsiderDisclosureEntity e = disclosures.toEntity("NSE:TEST", "TEST", raw, 100.0);
        assertThat(e.getDisclosureHash()).hasSize(64);
    }

    @Test
    @DisplayName("Different filings hash differently — dedup must not merge distinct trades")
    void distinctFilingsHashDistinctly() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("acqName", "P One");
        a.put("personCategory", "Promoter");
        a.put("acqMode", "Market Purchase");
        a.put("secAcq", "1000");
        a.put("acqfromDt", "13-Feb-2026");
        a.put("tdpTransactionType", "Buy");

        Map<String, Object> b = new LinkedHashMap<>(a);
        b.put("secAcq", "1001");   // different quantity = a different trade

        assertThat(disclosures.toEntity("NSE:TEST", "TEST", a, 100.0).getDisclosureHash())
                .isNotEqualTo(disclosures.toEntity("NSE:TEST", "TEST", b, 100.0).getDisclosureHash());
    }

    @Test
    @DisplayName("Archive CSV parsing keeps quoted commas together and skips the NO RECORDS sentinel")
    void csvParsing() {
        String csv = "Date,Symbol,Client Name,Buy/Sell,Quantity Traded\n"
                + "25-AUG-2026,ACME,\"SMITH, JOHN AND SONS\",BUY,1000\n";
        List<Map<String, String>> rows = NseDataService.parseSimpleCsv(csv);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("Client Name")).isEqualTo("SMITH, JOHN AND SONS");
        assertThat(rows.get(0).get("Symbol")).isEqualTo("ACME");

        String empty = "Date,Symbol,Client Name\nNO RECORDS,,\n";
        assertThat(NseDataService.parseSimpleCsv(empty)).isEmpty();
    }

    @Test
    @DisplayName("A bulk-deal row becomes an institution-tagged entity with a derived value")
    void bulkDealMapping() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("Date", "25-AUG-2026");
        row.put("Symbol", "ACME");
        row.put("Client Name", "Some Fund");
        row.put("Buy/Sell", "BUY");
        row.put("Quantity Traded", "1,000");
        row.put("Trade Price / Wght. Avg. Price", "250.50");

        InsiderDisclosureEntity e = disclosures.dealToEntity(row, false);
        assertThat(e).isNotNull();
        assertThat(e.getSymbol()).isEqualTo("NSE:ACME");
        assertThat(e.getSource()).isEqualTo("BULK");
        assertThat(e.getValue()).isEqualTo(250_500d);
        // Bulk deals are institutional, never an insider signal.
        assertThat(InsiderDisclosureService.isSignalRow(e)).isFalse();
    }

    @Test
    @DisplayName("Rows with no usable date are dropped rather than defaulted into the window")
    void undateableRowsAreDropped() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("acqName", "X");
        raw.put("personCategory", "Promoter");
        raw.put("acqMode", "Market Purchase");
        raw.put("secAcq", "10");
        assertThat(disclosures.toEntity("NSE:TEST", "TEST", raw, 100.0)).isNull();
    }

    @Test
    @DisplayName("A future-dated filing falls back to its disclosure date")
    void futureTransactionDateFallsBackToDisclosure() {
        // Real shape from NSE, observed 2026-08-25: three SOLARINDS rows with a transaction
        // date of 09-Nov-2026 disclosed on 11-Mar-2026. A future date never ages out of a
        // trailing window, so it would read as "recent insider activity" forever.
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("acqName", "A Promoter");
        raw.put("personCategory", "Promoter");
        raw.put("acqMode", "Market Purchase");
        raw.put("secAcq", "100");
        raw.put("acqfromDt", LocalDate.now().plusYears(1).format(
                java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH)));
        raw.put("date", LocalDate.now().minusDays(5).format(
                java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH)) + " 19:06");

        InsiderDisclosureEntity e = disclosures.toEntity("NSE:TEST", "TEST", raw, 100.0);
        assertThat(e).isNotNull();
        assertThat(e.getTransactionDate()).isEqualTo(LocalDate.now().minusDays(5));
    }

    @Test
    @DisplayName("A future-dated filing with no usable disclosure date is dropped entirely")
    void futureDatedWithNoFallbackIsDropped() {
        String future = LocalDate.now().plusYears(1).format(
                java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("acqName", "A Promoter");
        raw.put("personCategory", "Promoter");
        raw.put("acqMode", "Market Purchase");
        raw.put("secAcq", "100");
        raw.put("acqfromDt", future);
        raw.put("date", future + " 19:06");

        assertThat(disclosures.toEntity("NSE:TEST", "TEST", raw, 100.0)).isNull();
    }

    @Test
    @DisplayName("Excluded non-market rows are reported, not silently dropped")
    void exclusionsAreVisible() {
        List<InsiderDisclosureEntity> rows = new ArrayList<>();
        rows.add(row("PROMOTER", "MARKET_PURCHASE", "BUY", 10_00_000));
        rows.add(row("PROMOTER", "PLEDGE_CREATION", "BUY", 10_00_000));
        rows.add(row("EMPLOYEE", "ESOP", "BUY", 10_00_000));
        InsiderPulse p = pulse.classify("NSE:TEST", rows, 100.0);
        assertThat(p.getExcludedNonMarketRows()).isEqualTo(2);
        assertThat(p.getNotes()).anyMatch(n -> n.contains("excluded from scoring"));
    }
}
