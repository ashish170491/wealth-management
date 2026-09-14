package com.example.trading.concall;

import com.example.trading.ai.NseDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the concall / management-quality layer (SPEC §34, F8).
 *
 * <p>This is the feature most at risk of laundering an AI impression into evidence, so the
 * properties defended are the boundaries:
 * <ul>
 *   <li>a credibility ratio is refused until enough promises have actually come due</li>
 *   <li>a pending promise is never counted as met or missed</li>
 *   <li>"no record" is reported as unknown, never as a bad record</li>
 *   <li>an announcement of an upcoming call is not mistaken for a transcript of a past one</li>
 * </ul>
 */
class ConcallAnalysisTest {

    private static GuidanceItemEntity item(String status) {
        return GuidanceItemEntity.builder()
                .symbol("NSE:ACME").quarter("Q1FY26").metric("revenue growth")
                .guidedValue("15-18% for the year").status(status).build();
    }

    // ---- Credibility gating

    @Test
    @DisplayName("No recorded guidance is reported as unknown, never as a bad record")
    void noDataIsNotABadRecord() {
        var c = ConcallAnalysisService.computeCredibility("NSE:ACME", List.of());

        assertThat(c.getStatus()).isEqualTo("NO_DATA");
        // A null ratio is the point: 0% would read as "management delivers nothing".
        assertThat(c.getDeliveryRatioPercent()).isNull();
        assertThat(c.getExplanation()).isNotBlank();
    }

    @Test
    @DisplayName("A ratio is refused until enough promises have come due")
    void tooEarlyForARatio() {
        List<GuidanceItemEntity> items = new ArrayList<>();
        items.add(item("MET"));
        items.add(item("MET"));
        items.add(item("PENDING"));
        items.add(item("PENDING"));
        items.add(item("PENDING"));

        var c = ConcallAnalysisService.computeCredibility("NSE:ACME", items);

        assertThat(c.getStatus()).isEqualTo("TOO_EARLY");
        // Two of two resolved is 100%, which is exactly the number that would mislead.
        assertThat(c.getDeliveryRatioPercent()).isNull();
        assertThat(c.getResolved()).isEqualTo(2);
        assertThat(c.getPromisesRecorded()).isEqualTo(5);
    }

    @Test
    @DisplayName("Once enough promises are due, the ratio counts only those")
    void ratioCountsOnlyResolved() {
        List<GuidanceItemEntity> items = new ArrayList<>();
        items.add(item("MET"));
        items.add(item("MET"));
        items.add(item("MET"));
        items.add(item("MISSED"));
        // Pending items must not dilute the denominator, or a company that guides often
        // would score worse than one that says nothing.
        items.add(item("PENDING"));
        items.add(item("PENDING"));

        var c = ConcallAnalysisService.computeCredibility("NSE:ACME", items);

        assertThat(c.getStatus()).isEqualTo("MEASURED");
        assertThat(c.getResolved()).isEqualTo(4);
        assertThat(c.getMet()).isEqualTo(3);
        assertThat(c.getMissed()).isEqualTo(1);
        assertThat(c.getDeliveryRatioPercent()).isEqualTo(75.0);
        assertThat(c.getPromisesRecorded()).isEqualTo(6);
    }

    @Test
    @DisplayName("The threshold is exactly the documented minimum, not one either side")
    void thresholdBoundary() {
        List<GuidanceItemEntity> three = new ArrayList<>();
        for (int i = 0; i < ConcallAnalysisService.MIN_RESOLVED_FOR_CREDIBILITY - 1; i++) three.add(item("MET"));
        assertThat(ConcallAnalysisService.computeCredibility("NSE:ACME", three).getStatus())
                .isEqualTo("TOO_EARLY");

        List<GuidanceItemEntity> four = new ArrayList<>(three);
        four.add(item("MET"));
        assertThat(ConcallAnalysisService.computeCredibility("NSE:ACME", four).getStatus())
                .isEqualTo("MEASURED");
    }

    // ---- Transcript detection

    @Test
    @DisplayName("A transcript PDF is recognised")
    void recognisesTranscript() {
        var r = new NseDataService.AnnouncementRecord("26-Aug-2026",
                "Transcript of the earnings conference call held on 12 August 2026",
                "https://nsearchives.nseindia.com/corporate/ACME_transcript.pdf");
        assertThat(r.isTranscript()).isTrue();
    }

    @Test
    @DisplayName("An announcement of an UPCOMING call is not a transcript")
    void intimationIsNotATranscript() {
        // This filing announces a call that has not happened. Its attachment is a notice,
        // and feeding it to the model would produce confident guidance extracted from
        // a document containing none.
        var r = new NseDataService.AnnouncementRecord("26-Aug-2026",
                "Intimation of earnings conference call to be held on 12 September 2026",
                "https://nsearchives.nseindia.com/corporate/ACME_notice.pdf");
        assertThat(r.isTranscript()).isFalse();

        var r2 = new NseDataService.AnnouncementRecord("26-Aug-2026",
                "Prior intimation of analyst call",
                "https://nsearchives.nseindia.com/corporate/x.pdf");
        assertThat(r2.isTranscript()).isFalse();
    }

    @Test
    @DisplayName("A filing with no PDF attachment is not a transcript")
    void needsAPdfAttachment() {
        assertThat(new NseDataService.AnnouncementRecord("d", "Earnings call transcript", null).isTranscript())
                .isFalse();
        assertThat(new NseDataService.AnnouncementRecord("d", "Earnings call transcript",
                "https://example.com/page.html").isTranscript()).isFalse();
    }

    @Test
    @DisplayName("Unrelated filings are not transcripts")
    void unrelatedFilingsIgnored() {
        assertThat(new NseDataService.AnnouncementRecord("d", "Board meeting outcome",
                "https://nsearchives.nseindia.com/x.pdf").isTranscript()).isFalse();
    }

    // ---- Guidance parsing

    private static final String MODEL_OUTPUT = String.join("\n",
            "GUIDANCE",
            "revenue growth | 15-18% for FY27 | full year",
            "- EBITDA margin | expand to 22% | H2FY27",
            "",
            "CAPEX AND EXPANSION",
            "new plant | Rs 400 cr | commissioning Q3FY27",
            "",
            "TONE SHIFT",
            "More confident than last quarter.",
            "",
            "WHAT THEY AVOIDED",
            "Questions on the receivables build-up.",
            "",
            "PLAIN SUMMARY",
            "Business is growing.");

    @Test
    @DisplayName("Guidance lines are parsed and the section stops at the next heading")
    void parsesGuidanceSectionOnly() {
        List<String> lines = ConcallAnalysisService.parseGuidanceLines(MODEL_OUTPUT);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith("revenue growth");
        // The leading "- " bullet must be stripped, or the metric becomes "- EBITDA margin".
        assertThat(lines.get(1)).startsWith("EBITDA margin");
        // The capex line lives under its own heading and is NOT a promise to score.
        assertThat(lines).noneSatisfy(l -> assertThat(l).contains("new plant"));
    }

    @Test
    @DisplayName("'None given' does not become a phantom commitment")
    void noneGivenYieldsNothing() {
        String out = "GUIDANCE\nNone given\n\nCAPEX AND EXPANSION\nNot discussed";
        assertThat(ConcallAnalysisService.parseGuidanceLines(out)).isEmpty();
    }

    @Test
    @DisplayName("Prose inside the guidance section is not filed as a promise")
    void proseIsNotAPromise() {
        String out = "GUIDANCE\nManagement sounded optimistic about the year ahead.\n\nTONE SHIFT\nx";
        assertThat(ConcallAnalysisService.parseGuidanceLines(out)).isEmpty();
    }

    @Test
    @DisplayName("Null or empty model output yields no guidance rather than throwing")
    void handlesEmptyOutput() {
        assertThat(ConcallAnalysisService.parseGuidanceLines(null)).isEmpty();
        assertThat(ConcallAnalysisService.parseGuidanceLines("")).isEmpty();
    }

    @Test
    @DisplayName("A numeric value is extracted when present, null when the guidance is qualitative")
    void extractsFirstNumber() {
        assertThat(ConcallAnalysisService.firstNumber("15-18% for FY27")).isEqualTo(15.0);
        assertThat(ConcallAnalysisService.firstNumber("Rs 1,200 cr")).isEqualTo(1200.0);
        assertThat(ConcallAnalysisService.firstNumber("margins will improve")).isNull();
        assertThat(ConcallAnalysisService.firstNumber(null)).isNull();
    }

    // ---- PDF extraction

    @Test
    @DisplayName("A missing or unreadable PDF yields null, not an exception or empty string")
    void pdfExtractionDegradesGracefully() {
        assertThat(ConcallAnalysisService.extractText(null)).isNull();
        assertThat(ConcallAnalysisService.extractText(new byte[0])).isNull();
        // A scanned image or corrupt download must not take down the caller.
        assertThat(ConcallAnalysisService.extractText("not a pdf".getBytes())).isNull();
    }
}
