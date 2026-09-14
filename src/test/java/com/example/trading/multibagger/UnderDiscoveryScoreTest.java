package com.example.trading.multibagger;

import com.example.trading.ai.NseDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Under-Discovery lens (SPEC §12.10, F2).
 *
 * <p>Two properties are load-bearing and must never be "simplified away":
 * <ol>
 *   <li><b>Null, not zero</b>, whenever the stock was not measured. A 0 sorts last but is
 *       still a claim; null says nothing, which is the truth.</li>
 *   <li><b>Renormalisation</b> over measurable components, so a stock is not punished for
 *       NSE's publishing gaps — the same rule the composite follows for its nullable
 *       dimensions.</li>
 * </ol>
 */
class UnderDiscoveryScoreTest {

    private final UnderDiscoveryService service = new UnderDiscoveryService();

    private static NseDataService.ShareholdingHistory shareholding(Double fii, Double dii,
                                                                   Double fiiChange, Double diiChange) {
        NseDataService.ShareholdingQuarter q = new NseDataService.ShareholdingQuarter();
        q.setFiiHolding(fii);
        q.setDiiHolding(dii);
        NseDataService.ShareholdingHistory h = new NseDataService.ShareholdingHistory();
        h.setQuarters(List.of(q));
        h.setFiiChange(fiiChange);
        h.setDiiChange(diiChange);
        return h;
    }

    @Test
    @DisplayName("Below the quality gate the score is null — under-discovered junk is still junk")
    void qualityGateReturnsNull() {
        Integer s = service.compute(54, "DECENT", shareholding(1.0, 1.0, 0.5, 0.5),
                "SMALL_CAP", 800.0, "STRONG_HANDS", 1.5, 1, new ArrayList<>());
        assertThat(s).isNull();
    }

    @Test
    @DisplayName("WEAK and HIGH_RISK balance sheets are never surfaced as under-the-radar finds")
    void fragileBalanceSheetsExcluded() {
        for (String verdict : List.of("WEAK", "HIGH_RISK")) {
            assertThat(service.compute(80, verdict, shareholding(1.0, 1.0, 0.5, 0.5),
                    "SMALL_CAP", 800.0, "STRONG_HANDS", 1.5, 1, new ArrayList<>()))
                    .as("verdict %s must not be scored", verdict)
                    .isNull();
        }
    }

    @Test
    @DisplayName("Nothing measurable at all yields null, not 0")
    void noMeasurableComponentsYieldsNull() {
        Integer s = service.compute(70, "DECENT", null, "UNKNOWN", null, null, null, null, new ArrayList<>());
        assertThat(s).isNull();
    }

    @Test
    @DisplayName("An ideal under-the-radar micro-cap scores near the top")
    void idealCaseScoresHigh() {
        List<String> reasons = new ArrayList<>();
        Integer s = service.compute(75, "HIGH_QUALITY", shareholding(0.4, 0.6, 0.8, 0.5),
                "SMALL_CAP", 700.0, "STRONG_HANDS", 1.5, 1, reasons);
        assertThat(s).isNotNull();
        assertThat(s).isGreaterThanOrEqualTo(90);
        assertThat(reasons).anyMatch(r -> r.contains("Barely institutionally owned"));
        assertThat(reasons).anyMatch(r -> r.contains("Micro-cap"));
    }

    @Test
    @DisplayName("A heavily-owned large cap scores low even with perfect quality")
    void wellOwnedLargeCapScoresLow() {
        Integer s = service.compute(85, "HIGH_QUALITY", shareholding(28.0, 22.0, -0.4, -0.2),
                "LARGE_CAP", 500000.0, "MODERATE", 0.9, 40, new ArrayList<>());
        assertThat(s).isNotNull();
        assertThat(s).isLessThan(20);
    }

    @Test
    @DisplayName("Renormalisation: an absent component neither helps nor hurts the score")
    void absentComponentIsRenormalised() {
        // Same stock scored with and without news coverage available. The news component
        // would have been earned in full, so the renormalised score must be identical.
        List<String> r1 = new ArrayList<>();
        List<String> r2 = new ArrayList<>();
        Integer withNews = service.compute(70, "DECENT", shareholding(0.5, 0.5, 1.0, 1.0),
                "SMALL_CAP", 500.0, "STRONG_HANDS", 1.5, 1, r1);
        Integer withoutNews = service.compute(70, "DECENT", shareholding(0.5, 0.5, 1.0, 1.0),
                "SMALL_CAP", 500.0, "STRONG_HANDS", 1.5, null, r2);
        assertThat(withNews).isEqualTo(100);
        assertThat(withoutNews).isEqualTo(100);
    }

    @Test
    @DisplayName("Institutional bands step at 2%, 5% and 10% of combined FII+DII")
    void institutionalBands() {
        // Isolate the ownership component: no cap tier, no delivery, no volume, no news.
        Integer under2 = service.compute(70, "DECENT", shareholding(1.0, 0.5, null, null),
                "UNKNOWN", null, null, null, null, new ArrayList<>());
        Integer under5 = service.compute(70, "DECENT", shareholding(3.0, 1.0, null, null),
                "UNKNOWN", null, null, null, null, new ArrayList<>());
        Integer under10 = service.compute(70, "DECENT", shareholding(6.0, 2.0, null, null),
                "UNKNOWN", null, null, null, null, new ArrayList<>());
        Integer over10 = service.compute(70, "DECENT", shareholding(20.0, 15.0, null, null),
                "UNKNOWN", null, null, null, null, new ArrayList<>());

        assertThat(under2).isGreaterThan(under5);
        assertThat(under5).isGreaterThan(under10);
        assertThat(under10).isGreaterThan(over10);
        assertThat(over10).isZero();   // measured, and it is genuinely not under-discovered
    }

    @Test
    @DisplayName("B-043: institutions adding to an already well-owned stock is not discovery")
    void risingFromAHighBaseEarnsNothing() {
        // 35% institutionally owned and rising hard. That is ordinary flow into a stock the
        // market already follows — the exact opposite of what this lens looks for. The
        // component previously paid its full 20 points here, rewarding well-covered names.
        List<String> reasons = new ArrayList<>();
        Integer s = service.compute(70, "DECENT", shareholding(20.0, 15.0, 2.0, 1.5),
                "UNKNOWN", null, null, null, null, reasons);

        assertThat(s).isZero();   // measured across both ownership components, and earned neither
        assertThat(reasons).noneMatch(r -> r.contains("accumulating from a low base"));
    }

    @Test
    @DisplayName("B-043: rising institutional holding on a genuinely low base still scores")
    void risingFromALowBaseStillScores() {
        List<String> reasons = new ArrayList<>();
        Integer s = service.compute(70, "DECENT", shareholding(1.0, 0.5, 2.0, 1.5),
                "UNKNOWN", null, null, null, null, reasons);

        assertThat(s).isEqualTo(100);   // 25 of 25 owned-low + 20 of 20 rising
        assertThat(reasons).anyMatch(r -> r.contains("accumulating from a low base"));
    }

    @Test
    @DisplayName("B-043: an unknown FII holding is not 0% — the component drops out entirely")
    void missingHoldingLegIsNotZeroFilled() {
        // With FII null and DII 1%, summing to 1% would report "barely institutionally
        // owned" — the strongest finding this lens makes — from a number half of which was
        // never published. The pair is measured together or not at all.
        List<String> reasons = new ArrayList<>();
        Integer s = service.compute(70, "DECENT", shareholding(null, 1.0, 1.0, 1.0),
                "UNKNOWN", null, null, null, null, reasons);

        assertThat(s).isNull();   // nothing else was measurable either
        assertThat(reasons).noneMatch(r -> r.contains("Barely institutionally owned"));
    }

    @Test
    @DisplayName("Score never escapes 0-100 even when every component maxes out")
    void scoreIsBounded() {
        Integer s = service.compute(100, "HIGH_QUALITY", shareholding(0.1, 0.1, 5.0, 5.0),
                "SMALL_CAP", 100.0, "STRONG_HANDS", 9.9, 0, new ArrayList<>());
        assertThat(s).isBetween(0, 100);
    }
}
