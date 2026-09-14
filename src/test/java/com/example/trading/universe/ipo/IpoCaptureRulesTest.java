package com.example.trading.universe.ipo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two capture-side rules that decide what a listing row can say without an analysis
 * (SPEC §45.6). Found on the first live run: NSE's past-issues feed never filled in NSDL's
 * listing date, so a 13-month-old listing was invisible; and 53 of 96 listings read
 * NOT_MEASURED because the stage waited for an analysis the daily capture had the inputs for.
 */
class IpoCaptureRulesTest {

    private static final LocalDate CLOSE = LocalDate.of(2025, 8, 1);

    @Test
    @DisplayName("The feed's own listing date wins when present")
    void feedDateWins() {
        assertThat(IpoTrackingService.effectiveListingDate(LocalDate.of(2025, 8, 6), LocalDate.of(2025, 8, 7), CLOSE))
                .isEqualTo(LocalDate.of(2025, 8, 6));
    }

    @Test
    @DisplayName("The equity list fills a missing feed date when it falls after the issue closed (NSDL)")
    void equityListFillsGap() {
        assertThat(IpoTrackingService.effectiveListingDate(null, LocalDate.of(2025, 8, 6), CLOSE))
                .isEqualTo(LocalDate.of(2025, 8, 6));
    }

    @Test
    @DisplayName("An equity-list date on or before the issue close is a re-used ticker, not this listing")
    void staleTickerRejected() {
        assertThat(IpoTrackingService.effectiveListingDate(null, LocalDate.of(2019, 3, 1), CLOSE)).isNull();
        assertThat(IpoTrackingService.effectiveListingDate(null, CLOSE, CLOSE)).isNull();
    }

    @Test
    @DisplayName("No date anywhere stays null")
    void nothingKnown() {
        assertThat(IpoTrackingService.effectiveListingDate(null, null, CLOSE)).isNull();
        assertThat(IpoTrackingService.effectiveListingDate(null, LocalDate.of(2025, 8, 6), null))
                .isEqualTo(LocalDate.of(2025, 8, 6));
    }

    @Test
    @DisplayName("Above-listing-high: the analysis wins, else the captured prices decide, else null")
    void aboveListingHigh() {
        assertThat(IpoTrackingService.aboveListingHigh(false, 200.0, 100.0)).isFalse();
        assertThat(IpoTrackingService.aboveListingHigh(null, 200.0, 100.0)).isTrue();
        assertThat(IpoTrackingService.aboveListingHigh(null, 90.0, 100.0)).isFalse();
        assertThat(IpoTrackingService.aboveListingHigh(null, 100.0, 100.0)).isFalse();
        assertThat(IpoTrackingService.aboveListingHigh(null, null, 100.0)).isNull();
        assertThat(IpoTrackingService.aboveListingHigh(null, 200.0, null)).isNull();
        assertThat(IpoTrackingService.aboveListingHigh(null, 200.0, 0.0)).isNull();
    }

    @Test
    @DisplayName("Status is derived from dates: listed beats open beats forthcoming; closed is the remainder")
    void status() {
        LocalDate today = LocalDate.of(2026, 9, 9);
        IpoIssueEntity e = IpoIssueEntity.builder().symbol("NSE:X")
                .issueStartDate(LocalDate.of(2026, 9, 8)).issueEndDate(LocalDate.of(2026, 9, 10)).build();
        assertThat(IpoTrackingService.status(e, today)).isEqualTo("OPEN");
        e.setIssueStartDate(LocalDate.of(2026, 9, 10));
        e.setIssueEndDate(LocalDate.of(2026, 9, 12));
        assertThat(IpoTrackingService.status(e, today)).isEqualTo("FORTHCOMING");
        e.setIssueStartDate(LocalDate.of(2026, 9, 1));
        e.setIssueEndDate(LocalDate.of(2026, 9, 3));
        assertThat(IpoTrackingService.status(e, today)).isEqualTo("CLOSED");
        e.setListingDate(LocalDate.of(2026, 9, 8));
        assertThat(IpoTrackingService.status(e, today)).isEqualTo("LISTED");
        e.setListingDate(LocalDate.of(2026, 9, 11)); // published ahead of time
        assertThat(IpoTrackingService.status(e, today)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("Percent change refuses a zero or missing base")
    void pctChange() {
        assertThat(IpoTrackingService.pctChange(100.0, 150.0)).isEqualTo(50.0);
        assertThat(IpoTrackingService.pctChange(0.0, 150.0)).isNull();
        assertThat(IpoTrackingService.pctChange(null, 150.0)).isNull();
        assertThat(IpoTrackingService.pctChange(100.0, null)).isNull();
    }

    @Test
    @DisplayName("Symbols normalise to the NSE-prefixed form the rest of the app joins on")
    void normalise() {
        assertThat(IpoTrackingService.normalise("nsdl")).isEqualTo("NSE:NSDL");
        assertThat(IpoTrackingService.normalise(" NSE:NSDL ")).isEqualTo("NSE:NSDL");
        assertThat(IpoTrackingService.normalise("BSE:NSDL")).isEqualTo("BSE:NSDL");
    }
}
