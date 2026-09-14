package com.example.trading.concall;

import com.example.trading.ai.AiService;
import com.example.trading.ai.NseDataService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads earnings-call transcripts and keeps score of what management promised (SPEC.md §34).
 *
 * <p><b>Why this is cheap.</b> Transcripts are mandatory exchange filings under SEBI LODR
 * Reg 46, published as PDF attachments on the corporate-announcements feed this system
 * already fetches. No new scraping surface, no new bot-wall to negotiate — just the
 * attachment nobody was opening.
 *
 * <p><b>The AI boundary (plan principle 2).</b> The model extracts and summarises; it never
 * scores. What management said is a fact worth reading. Whether they are trustworthy is a
 * measurement, and it comes from {@link GuidanceItemEntity} — claims recorded at the time
 * and checked against outcomes a year later. A model's impression of a confident tone is
 * exactly the kind of input that would look insightful and predict nothing, so the delivery
 * ratio stays out of the composite until at least
 * {@link #MIN_RESOLVED_FOR_CREDIBILITY} promises have actually come due.
 *
 * <p><b>Cost.</b> One transcript is 30-60 pages, far past what belongs in a prompt. Text is
 * truncated to {@link #MAX_TRANSCRIPT_CHARS}, and this runs on demand or for holdings —
 * never across the screening universe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConcallAnalysisService {

    private final NseDataService nseDataService;
    private final AiService aiService;
    private final GuidanceItemRepository guidanceRepository;

    /** Resolved promises required before a credibility ratio may be quoted at all. */
    public static final int MIN_RESOLVED_FOR_CREDIBILITY = 4;

    /**
     * Transcript characters sent to the model. Roughly the first half of a typical call —
     * management's prepared remarks and the start of Q&amp;A, which is where guidance lives.
     */
    public static final int MAX_TRANSCRIPT_CHARS = 60_000;

    private static final String SYSTEM_PROMPT = """
            You are a financial analyst reading an Indian company's earnings-call transcript for a
            long-term retail investor who is NOT a stock-market expert.

            Extract ONLY what management actually said. Do not evaluate the investment, do not give
            a rating, and do not predict the share price. If something is not in the transcript, say
            "not discussed" rather than inferring it.

            Reply in EXACTLY these five sections, using these headings:

            GUIDANCE
            Concrete forward-looking figures management committed to, one per line, in the form
            METRIC | WHAT THEY SAID | TIMEFRAME
            Include only specific claims (a number, a percentage, a capacity, a date). Skip vague
            optimism. If none, write "None given".

            CAPEX AND EXPANSION
            Any plant, capacity, or capital-spending statements, with amounts and dates if given.
            If none, write "Not discussed".

            TONE SHIFT
            How confident management sounds compared with what they said last quarter, if the
            transcript references it. Quote the words that support your read. If there is no basis
            for comparison, write "No basis for comparison".

            WHAT THEY AVOIDED
            Analyst questions that were deflected or left unanswered. These are often the most
            informative part of a call. If none, write "None apparent".

            PLAIN SUMMARY
            Three sentences a non-expert can follow: what the company said about how business is
            going, what they plan to do next, and what they were vague about.
            """;

    // ------------------------------------------------------------------ DTOs

    @Data
    @Builder
    public static class ConcallResult {
        private String symbol;
        /** SUCCESS / NO_TRANSCRIPT / NOT_READABLE / AI_UNAVAILABLE */
        private String status;
        private String reason;
        private String transcriptSubject;
        private String transcriptDate;
        private String transcriptUrl;
        private int extractedChars;
        private boolean truncated;
        /** The model's structured extraction, as returned. Prose, not a score. */
        private String analysis;
        /** Guidance lines parsed out of the GUIDANCE section. */
        private List<String> guidanceLines;
    }

    @Data
    @Builder
    public static class CredibilityResult {
        private String symbol;
        private int promisesRecorded;
        private int resolved;
        private int met;
        private int missed;
        /** Met / resolved, as a percentage. Null until enough promises have come due. */
        private Double deliveryRatioPercent;
        /** MEASURED / TOO_EARLY / NO_DATA — never a number dressed up as a verdict. */
        private String status;
        private String explanation;
    }

    // ------------------------------------------------------------------ analysis

    /**
     * Find, download and analyse the latest earnings-call transcript for a stock.
     *
     * <p>Live NSE calls plus one AI call — on demand only, never from a page load or a
     * screening loop.
     */
    public ConcallResult analyzeLatest(String symbol) {
        String tradingSymbol = symbol.contains(":") ? symbol.substring(symbol.indexOf(':') + 1) : symbol;

        NseDataService.AnnouncementRecord transcript = null;
        try {
            // 40 records is roughly a year of filings for an active company, enough to
            // reach back past results, board meetings and routine disclosures to the
            // most recent call.
            for (NseDataService.AnnouncementRecord r : nseDataService.fetchAnnouncementRecords(tradingSymbol, 40)) {
                if (r.isTranscript()) {
                    transcript = r;
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("Announcement lookup failed for {}: {}", symbol, e.getMessage());
        }

        if (transcript == null) {
            return ConcallResult.builder()
                    .symbol(symbol)
                    .status("NO_TRANSCRIPT")
                    .reason("No earnings-call transcript found in this company's recent exchange filings. "
                            + "Smaller companies often do not hold calls, and some file the transcript "
                            + "as a scanned document this cannot read.")
                    .build();
        }

        byte[] pdf = nseDataService.fetchAttachmentBytes(transcript.attachmentUrl());
        String text = extractText(pdf);
        if (text == null || text.length() < 500) {
            return ConcallResult.builder()
                    .symbol(symbol)
                    .status("NOT_READABLE")
                    .reason("The transcript was found but no text could be read from it — usually a "
                            + "scanned image rather than a text PDF.")
                    .transcriptSubject(transcript.subject())
                    .transcriptDate(transcript.date())
                    .transcriptUrl(transcript.attachmentUrl())
                    .extractedChars(text == null ? 0 : text.length())
                    .build();
        }

        boolean truncated = text.length() > MAX_TRANSCRIPT_CHARS;
        String forModel = truncated ? text.substring(0, MAX_TRANSCRIPT_CHARS) : text;

        String userPrompt = "Company: " + symbol + "\n"
                + "Filing: " + transcript.subject() + " (" + transcript.date() + ")\n"
                + (truncated ? "NOTE: this is the first part of the transcript only.\n" : "")
                + "\n--- TRANSCRIPT ---\n" + forModel;

        String analysis;
        try {
            analysis = aiService.analyze(SYSTEM_PROMPT, userPrompt);
        } catch (Exception e) {
            log.debug("Concall AI analysis failed for {}: {}", symbol, e.getMessage());
            analysis = null;
        }
        if (analysis == null || analysis.isBlank()) {
            return ConcallResult.builder()
                    .symbol(symbol)
                    .status("AI_UNAVAILABLE")
                    .reason("The transcript was read but the AI service is disabled or unavailable, "
                            + "so it could not be summarised.")
                    .transcriptSubject(transcript.subject())
                    .transcriptDate(transcript.date())
                    .transcriptUrl(transcript.attachmentUrl())
                    .extractedChars(text.length())
                    .truncated(truncated)
                    .build();
        }

        return ConcallResult.builder()
                .symbol(symbol)
                .status("SUCCESS")
                .transcriptSubject(transcript.subject())
                .transcriptDate(transcript.date())
                .transcriptUrl(transcript.attachmentUrl())
                .extractedChars(text.length())
                .truncated(truncated)
                .analysis(analysis)
                .guidanceLines(parseGuidanceLines(analysis))
                .build();
    }

    /**
     * Analyse the latest call and record each concrete guidance line in the ledger.
     *
     * @param quarter label to file the promises under, e.g. {@code Q1FY26}
     */
    @Transactional
    public ConcallResult analyzeAndRecord(String symbol, String quarter) {
        ConcallResult result = analyzeLatest(symbol);
        if (!"SUCCESS".equals(result.getStatus())) return result;

        for (String line : result.getGuidanceLines()) {
            // "METRIC | WHAT THEY SAID | TIMEFRAME"
            String[] parts = line.split("\\|");
            if (parts.length < 2) continue;
            String metric = parts[0].trim();
            String guided = parts[1].trim();
            if (metric.isEmpty() || guided.isEmpty()) continue;
            if (metric.length() > 64) metric = metric.substring(0, 64);

            // Same promise re-extracted from the same quarter must not create a second row,
            // or one statement counts several times against the credibility ratio.
            if (guidanceRepository.findBySymbolAndQuarterAndMetric(symbol, quarter, metric).isPresent()) continue;

            guidanceRepository.save(GuidanceItemEntity.builder()
                    .symbol(symbol)
                    .quarter(quarter)
                    .metric(metric)
                    .guidedValue(truncate(guided, 512))
                    .guidedNumeric(firstNumber(guided))
                    .status("PENDING")
                    // A guidance statement is judged when the period it covers has reported.
                    // A year is the honest default: most guidance here is annual, and marking
                    // something missed before it is due would manufacture a bad record.
                    .dueDate(LocalDate.now().plusYears(1))
                    .sourceDate(LocalDate.now())
                    .sourceUrl(truncate(result.getTranscriptUrl(), 512))
                    .build());
        }
        return result;
    }

    // ------------------------------------------------------------------ credibility

    /**
     * Management credibility as a measured ratio (SPEC.md §34.3).
     *
     * <p>Returns {@code TOO_EARLY} until {@link #MIN_RESOLVED_FOR_CREDIBILITY} promises have
     * actually come due. A ratio computed over one or two resolved items is noise with a
     * percent sign on it, and quoting it would invite exactly the false confidence this
     * feature is meant to replace.
     */
    public CredibilityResult credibility(String symbol) {
        return computeCredibility(symbol, guidanceRepository.findBySymbol(symbol));
    }

    /** Pure half of {@link #credibility} — no repository, so the gate is directly testable. */
    static CredibilityResult computeCredibility(String symbol, List<GuidanceItemEntity> all) {
        List<GuidanceItemEntity> resolved = all.stream().filter(GuidanceItemEntity::isResolved).toList();
        int met = (int) resolved.stream().filter(g -> "MET".equals(g.getStatus())).count();
        int missed = resolved.size() - met;

        if (all.isEmpty()) {
            return CredibilityResult.builder()
                    .symbol(symbol).promisesRecorded(0).resolved(0).met(0).missed(0)
                    .status("NO_DATA")
                    .explanation("No earnings-call guidance has been recorded for this company yet.")
                    .build();
        }
        if (resolved.size() < MIN_RESOLVED_FOR_CREDIBILITY) {
            return CredibilityResult.builder()
                    .symbol(symbol)
                    .promisesRecorded(all.size())
                    .resolved(resolved.size())
                    .met(met).missed(missed)
                    .status("TOO_EARLY")
                    .explanation(String.format(
                            "%d promise(s) recorded, %d of which have come due. At least %d need to have "
                                    + "come due before a delivery record means anything — until then the "
                                    + "ratio would swing on a single outcome.",
                            all.size(), resolved.size(), MIN_RESOLVED_FOR_CREDIBILITY))
                    .build();
        }

        double ratio = met * 100.0 / resolved.size();
        return CredibilityResult.builder()
                .symbol(symbol)
                .promisesRecorded(all.size())
                .resolved(resolved.size())
                .met(met).missed(missed)
                .deliveryRatioPercent(ratio)
                .status("MEASURED")
                .explanation(String.format(
                        "Management delivered on %d of %d things they said they would (%.0f%%). This counts "
                                + "only promises whose deadline has passed.", met, resolved.size(), ratio))
                .build();
    }

    public List<GuidanceItemEntity> ledger(String symbol) {
        return guidanceRepository.findBySymbol(symbol);
    }

    /** Record the outcome of one guidance item. Called when the covering period reports. */
    @Transactional
    public boolean resolve(Long id, String actualValue, boolean met) {
        return guidanceRepository.findById(id).map(g -> {
            g.setActualValue(truncate(actualValue, 512));
            g.setActualNumeric(firstNumber(actualValue));
            g.setStatus(met ? "MET" : "MISSED");
            guidanceRepository.save(g);
            return true;
        }).orElse(false);
    }

    // ------------------------------------------------------------------ helpers

    /** Extract text from a PDF, or null when it is not a readable text PDF. */
    static String extractText(byte[] pdf) {
        if (pdf == null || pdf.length == 0) return null;
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                     org.apache.pdfbox.Loader.loadPDF(pdf)) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return text == null || text.isBlank() ? null : text;
        } catch (Exception e) {
            log.debug("PDF text extraction failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Pull the pipe-delimited lines out of the model's GUIDANCE section.
     *
     * <p>Stops at the next heading so capex or tone lines never get filed as promises, and
     * ignores "None given" so an empty section does not become a phantom commitment.
     */
    static List<String> parseGuidanceLines(String analysis) {
        List<String> out = new ArrayList<>();
        if (analysis == null) return out;

        boolean inSection = false;
        for (String raw : analysis.split("\r?\n")) {
            String line = raw.trim();
            String upper = line.toUpperCase(Locale.ENGLISH);
            if (upper.startsWith("GUIDANCE")) {
                inSection = true;
                continue;
            }
            if (inSection) {
                if (upper.startsWith("CAPEX") || upper.startsWith("TONE")
                        || upper.startsWith("WHAT THEY AVOIDED") || upper.startsWith("PLAIN SUMMARY")) {
                    break;
                }
                if (line.isEmpty()) continue;
                if (upper.contains("NONE GIVEN") || upper.contains("NOT DISCUSSED")) continue;
                // Only pipe-delimited rows are commitments; prose in this section is not.
                if (line.contains("|")) out.add(line.replaceAll("^[-*\\s]+", ""));
            }
        }
        return out;
    }

    /** First number in a string, for the rare guidance that is cleanly numeric. */
    static Double firstNumber(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("-?\\d+(?:\\.\\d+)?").matcher(s.replace(",", ""));
        return m.find() ? Double.parseDouble(m.group()) : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
