package com.example.trading.insider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the PIT V2.0 XBRL ingestion that replaced the dead JSON feed (B-089, SPEC §28).
 *
 * <p>Replaces {@code InsiderCaptureRotationTest}, whose subject no longer exists. That test
 * pinned a rotation through a per-symbol probing budget; NSE now publishes an all-market
 * filing index, so there is no budget and no rotation to get wrong — which is also the fix for
 * B-090, where the rotation was handed zero slots whenever holdings and candidates filled the
 * cap. A cap that defers work to the next run replaced a keyhole that skipped it forever.
 *
 * <p>The facts below are taken verbatim from a real filing (BERGEPAINT, 08-Sep-2026) rather
 * than invented, because the thing most likely to break here is a field name.
 */
class InsiderPitFilingTest {

    /** Neither dependency is touched by the mapping methods under test. */
    private final InsiderDisclosureService service = new InsiderDisclosureService(null, null);

    private static Map<String, String> bergePaintPromoterBuy() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("Symbol", "BERGEPAINT");
        f.put("DateOfFiling", "2026-09-08");
        f.put("CategoryOfPerson", "Promoter");
        f.put("NameOfThePerson", "Mr Kanwardip Singh Dhingra");
        f.put("ModeOfAcquisitionOrDisposal", "Market Purchase");
        f.put("SecuritiesAcquiredOrDisposedTransactionType", "Buy");
        f.put("SecuritiesAcquiredOrDisposedNumberOfSecurity", "19788");
        f.put("SecuritiesAcquiredOrDisposedValueOfSecurity", "9599954");
        f.put("DateOfAllotmentAdviceOrAcquisitionOfSharesOrSaleOfSharesSpecifyFromDate", "2026-09-07");
        f.put("SecuritiesHeldPostAcquistionOrDisposalPercentageOfShareholding", "0.0007");
        return f;
    }

    @Test
    @DisplayName("A promoter's open-market purchase maps to a scoreable row")
    void promoterMarketPurchaseIsScoreable() {
        InsiderDisclosureEntity e = service.xbrlToEntity(
                "NSE:BERGEPAINT", "BERGEPAINT", "488", bergePaintPromoterBuy(), LocalDate.of(2026, 9, 8));

        assertThat(e).isNotNull();
        assertThat(e.getMode()).isEqualTo("MARKET_PURCHASE");
        assertThat(e.getPersonCategory()).isEqualTo("PROMOTER");
        assertThat(e.getTransactionType()).isEqualTo("BUY");
        assertThat(e.getQuantity()).isEqualTo(19788.0);
        assertThat(e.getSource()).isEqualTo("PIT");
        assertThat(e.getFilingAppId()).isEqualTo("488");
        assertThat(e.getTransactionDate()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(e.getDisclosureDate()).isEqualTo(LocalDate.of(2026, 9, 8));

        // The whole point of the migration: the old JSON feed routinely omitted the value, so
        // the size half of the pulse verdict could not run. The XBRL states it in rupees.
        assertThat(e.getValue()).isEqualTo(9599954.0);

        assertThat(InsiderDisclosureService.isSignalRow(e)).isTrue();
    }

    @Test
    @DisplayName("The post-trade holding is stored as a percentage, not the filing's fraction")
    void holdingFractionIsConvertedToPercent() {
        InsiderDisclosureEntity e = service.xbrlToEntity(
                "NSE:BERGEPAINT", "BERGEPAINT", "488", bergePaintPromoterBuy(), LocalDate.of(2026, 9, 8));
        assertThat(e.getPctOfEquityAfter()).isCloseTo(0.07, within(1e-9));
    }

    @Test
    @DisplayName("A pledge is recorded but never scored - it is borrowing, not conviction")
    void pledgeIsRecordedButNotScored() {
        Map<String, String> f = bergePaintPromoterBuy();
        f.put("ModeOfAcquisitionOrDisposal", "Pledge Creation");
        f.put("SecuritiesAcquiredOrDisposedTransactionType", "Buy");

        InsiderDisclosureEntity e = service.xbrlToEntity(
                "NSE:BERGEPAINT", "BERGEPAINT", "488", f, LocalDate.of(2026, 9, 8));

        assertThat(e.getMode()).isEqualTo("PLEDGE_CREATION");
        assertThat(InsiderDisclosureService.isSignalRow(e)).isFalse();
    }

    @Test
    @DisplayName("A future-dated trade falls back to its disclosure date (B-031), on this path too")
    void futureDatedTradeFallsBackToDisclosureDate() {
        LocalDate future = LocalDate.now().plusMonths(2);
        Map<String, String> f = bergePaintPromoterBuy();
        f.put("DateOfAllotmentAdviceOrAcquisitionOfSharesOrSaleOfSharesSpecifyFromDate", future.toString());
        f.put("DateOfFiling", "2026-03-11");

        InsiderDisclosureEntity e = service.xbrlToEntity(
                "NSE:SOLARINDS", "SOLARINDS", "1", f, LocalDate.of(2026, 3, 11));

        // Not the future date: a row dated ahead of today never ages out of a trailing window,
        // so it would read as "recent insider activity" forever.
        assertThat(e.getTransactionDate()).isEqualTo(LocalDate.of(2026, 3, 11));
    }

    @Test
    @DisplayName("A row that is future-dated with no usable fallback is dropped, not guessed")
    void unusableFutureRowIsDropped() {
        LocalDate future = LocalDate.now().plusMonths(2);
        Map<String, String> f = bergePaintPromoterBuy();
        f.put("DateOfAllotmentAdviceOrAcquisitionOfSharesOrSaleOfSharesSpecifyFromDate", future.toString());
        f.put("DateOfFiling", future.toString());

        assertThat(service.xbrlToEntity("NSE:X", "X", "1", f, future)).isNull();
    }

    @Test
    @DisplayName("An absent transaction type defers to the mode, never defaulting to BUY (B-040)")
    void absentTypeDefersToMode() {
        Map<String, String> f = bergePaintPromoterBuy();
        f.remove("SecuritiesAcquiredOrDisposedTransactionType");
        f.put("ModeOfAcquisitionOrDisposal", "Market Sale");

        InsiderDisclosureEntity e = service.xbrlToEntity("NSE:X", "X", "1", f, LocalDate.of(2026, 9, 8));

        // The one direction this must never fail in: a sale added to net insider buying.
        assertThat(e.getTransactionType()).isEqualTo("SELL");
        assertThat(e.getMode()).isEqualTo("MARKET_SALE");
    }

    @Test
    @DisplayName("Both eras' date formats parse, and nonsense stays null")
    void datesParseInBothFormats() {
        assertThat(InsiderDisclosureService.parseIsoOrNseDate("2026-09-07"))
                .isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(InsiderDisclosureService.parseIsoOrNseDate("07-Sep-2026"))
                .isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(InsiderDisclosureService.parseIsoOrNseDate("-")).isNull();
        assertThat(InsiderDisclosureService.parseIsoOrNseDate(null)).isNull();
    }
}
