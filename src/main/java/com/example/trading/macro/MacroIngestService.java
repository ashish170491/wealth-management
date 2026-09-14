package com.example.trading.macro;

import com.example.trading.intelligence.MarketImpactNewsEntity;
import com.example.trading.intelligence.MarketImpactNewsRepository;
import com.example.trading.intelligence.MarketImpactNewsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads the news and writes the event ledger (SPEC §48.2, §48.6).
 *
 * <p><b>Nothing schedules this.</b> It runs when the investor presses the button on the Events page,
 * and that is the whole design: an extraction costs a model call, and a standing cost incurred by a
 * cron is a cost nobody decided to pay. {@code macro.ingest.scheduled} exists, is bound, is logged
 * at boot and is false; turning it into a real schedule needs a SPEC §15 row and an argument for the
 * slot, because SPEC §3.4 allows nothing outside 09:15-15:30 on a weekday.
 *
 * <p>The pass is: pull the feeds, take the headlines nothing has read yet, extract events from them,
 * merge each one into the ledger or insert it, mark the headlines read, then file the resulting
 * directional readings for measurement. Every step reports its own count, because "the ingest ran"
 * and "the ingest found something" are different facts and an investor pressing a button deserves
 * to be told which happened.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroIngestService {

    private final MarketImpactNewsService newsService;
    private final MarketImpactNewsRepository newsRepository;
    private final MacroEventRepository eventRepository;
    private final MacroEventExtractor extractor;
    private final MacroMeasurementService measurementService;
    private final MacroConfig config;

    /**
     * @param headlinesFetched  new headlines the feed scan stored this run
     * @param headlinesRead     headlines offered to the extractor
     * @param eventsNew         events that had never been seen before
     * @param eventsMerged      fresh reports of events already on the ledger
     * @param note              one sentence for the investor, naming what read the news
     */
    public record IngestResult(int headlinesFetched, int headlinesRead, int eventsExtracted,
                               int eventsNew, int eventsMerged, String extractor,
                               boolean modelUsed, int readingsRecorded, int readingsSkippedNoPrice,
                               long durationMs, String note) {
    }

    /**
     * Fetch and store headlines without extracting anything.
     *
     * <p>Kept separate because the two halves have very different costs: the scan is four RSS
     * requests, the extraction may be a model call. Somebody wanting fresher headlines should not
     * have to pay for a reading they did not ask for.
     */
    public int scanOnly() {
        return newsService.scanOnce();
    }

    public IngestResult ingest() {
        long started = System.currentTimeMillis();

        int fetched = newsService.scanOnce();

        LocalDateTime since = LocalDateTime.now()
                .minusHours(Math.max(1, config.getIngest().getHeadlineLookbackHours()));
        List<MarketImpactNewsEntity> unread;
        try {
            unread = newsRepository.findUnreadSince(since);
        } catch (Exception e) {
            log.warn("Macro ingest: could not read the headline table ({}). Nothing was extracted, "
                    + "which will look on screen exactly like a quiet news day - it is not.", e.getMessage());
            return new IngestResult(fetched, 0, 0, 0, 0, extractor.configuredExtractor(),
                    extractor.modelAvailable(), 0, 0, System.currentTimeMillis() - started,
                    "The headline table could not be read, so nothing was extracted.");
        }

        // Newest first, capped: a fortnight-old headline cannot produce an event that still matters,
        // and paying a model to read one would be paying for nothing.
        int cap = Math.max(1, config.getIngest().getMaxHeadlines());
        List<MarketImpactNewsEntity> batch = unread.size() > cap ? unread.subList(0, cap) : unread;

        List<MacroKeywordExtractor.Headline> headlines = batch.stream()
                .map(MacroIngestService::toHeadline)
                .collect(Collectors.toList());

        MacroEventExtractor.Result extraction = extractor.extract(headlines);

        int newEvents = 0;
        int merged = 0;
        for (ExtractedEvent.Validated e : extraction.events()) {
            if (store(e, extraction.extractor())) {
                newEvents++;
            } else {
                merged++;
            }
        }

        markRead(batch);

        MacroMeasurementService.MeasurementResult measured =
                new MacroMeasurementService.MeasurementResult(0, 0, 0, 0);
        if (newEvents > 0 || merged > 0) {
            try {
                measured = measurementService.recordExposures();
            } catch (Exception e) {
                log.warn("Macro ingest: readings were not filed for measurement ({}). The events are "
                        + "stored and the screens will show them; only the accuracy record is short.",
                        e.getMessage());
            }
        }

        long ms = System.currentTimeMillis() - started;
        String note = buildNote(fetched, headlines.size(), newEvents, merged, extraction, measured);
        log.info("Macro ingest: {} headlines stored, {} read by {}, {} new events, {} merged, "
                        + "{} readings filed, {} ms",
                fetched, headlines.size(), extraction.extractor(), newEvents, merged,
                measured.recorded(), ms);

        return new IngestResult(fetched, headlines.size(), extraction.events().size(), newEvents,
                merged, extraction.extractor(), extractor.modelAvailable(), measured.recorded(),
                measured.skippedNoPrice(), ms, note);
    }

    // ------------------------------------------------------------------ storing

    /**
     * Insert an event, or merge it into one already on the ledger. Returns true when it was new.
     *
     * <p>Two levels of protection against counting one event twice: the exact key stops a re-run
     * inserting the same factor, direction and day again, and the window check catches Wednesday's
     * rate cut being written up again on Friday.
     */
    private boolean store(ExtractedEvent.Validated e, String extractorName) {
        try {
            String key = MacroEventDedup.key(e.factor(), e.direction(), e.occurredAt());

            MacroEventEntity existing = eventRepository.findByDedupKey(key).orElse(null);
            if (existing == null) {
                int window = Math.max(0, config.getDedupWindowDays());
                List<MacroEventEntity> nearby = eventRepository.findByFactorAndDirectionAndOccurredAtBetween(
                        e.factor().name(), e.direction().name(),
                        e.occurredAt().minusDays(window), e.occurredAt().plusDays(window));
                for (MacroEventEntity candidate : nearby) {
                    if (MacroEventDedup.sameEvent(candidate.factorOrNull(), candidate.directionOrNull(),
                            candidate.getOccurredAt(), e.factor(), e.direction(), e.occurredAt(), window)) {
                        existing = candidate;
                        break;
                    }
                }
            }

            if (existing != null) {
                MacroEventDedup.Merged m = MacroEventDedup.merge(
                        existing.getOccurredAt(), existing.magnitudeOrNull(), existing.getConfidence(),
                        existing.headlineIdList(), existing.sourceUrlList(), existing.getSummary(),
                        e.occurredAt(), e.magnitude(), e.confidence(), e.headlineIds(), e.sourceUrls(),
                        e.summary());
                existing.setOccurredAt(m.occurredAt());
                existing.setMagnitude(m.magnitude() == null ? null : m.magnitude().name());
                existing.setConfidence(m.confidence());
                existing.setHeadlineIds(join(m.headlineIds()));
                existing.setSourceUrls(join(m.sourceUrls()));
                existing.setSummary(m.summary());
                existing.setExtractedAt(LocalDateTime.now());
                // The dedup key follows the merged date, so a merge that moved the date earlier
                // cannot leave a second row able to claim the same event tomorrow.
                existing.setDedupKey(MacroEventDedup.key(e.factor(), e.direction(), m.occurredAt()));
                eventRepository.save(existing);
                return false;
            }

            eventRepository.save(MacroEventEntity.builder()
                    .factor(e.factor().name())
                    .direction(e.direction().name())
                    .magnitude(e.magnitude() == null ? null : e.magnitude().name())
                    .kind(e.kind() == null ? null : e.kind().name())
                    .geography(e.geography())
                    .occurredAt(e.occurredAt())
                    .headlineIds(join(e.headlineIds()))
                    .sourceUrls(join(e.sourceUrls()))
                    .summary(e.summary())
                    .extractedAt(LocalDateTime.now())
                    .extractor(extractorName)
                    .confidence(e.confidence())
                    .dedupKey(key)
                    .dismissed(false)
                    .exposureMapVersion(safeMapVersion())
                    .build());
            return true;

        } catch (Exception ex) {
            log.warn("Macro ingest: could not store the {} {} event ({}). The rest of the batch is "
                    + "unaffected.", e.factor(), e.direction(), ex.getMessage());
            return false;
        }
    }

    /**
     * Mark the whole batch read, including headlines no event came from.
     *
     * <p>Deliberate: a headline the extractor looked at and found nothing in has been read, and
     * offering it again on the next ingest would pay to reach the same conclusion for ever. Most
     * headlines produce no event; that is the normal case, not a retry condition.
     */
    private void markRead(List<MarketImpactNewsEntity> batch) {
        if (batch.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        try {
            for (MarketImpactNewsEntity h : batch) {
                h.setMacroExtractedAt(now);
            }
            newsRepository.saveAll(batch);
        } catch (Exception e) {
            log.warn("Macro ingest: could not mark {} headlines as read ({}), so the next ingest will "
                    + "offer them again and pay to read them twice.", batch.size(), e.getMessage());
        }
    }

    private static String buildNote(int fetched, int read, int newEvents, int merged,
                                    MacroEventExtractor.Result extraction,
                                    MacroMeasurementService.MeasurementResult measured) {
        StringBuilder sb = new StringBuilder();
        sb.append("Fetched ").append(fetched).append(" new headline")
                .append(fetched == 1 ? "" : "s").append(", read ").append(read).append('.');
        if (newEvents == 0 && merged == 0) {
            // Said plainly, because it is the ordinary outcome and must not read as a failure.
            sb.append(" No macro event in them - which is the usual result on most days.");
        } else {
            sb.append(' ').append(newEvents).append(" new event")
                    .append(newEvents == 1 ? "" : "s");
            if (merged > 0) {
                sb.append(", ").append(merged).append(" further report")
                        .append(merged == 1 ? "" : "s").append(" of events already recorded");
            }
            sb.append('.');
            if (measured.recorded() > 0) {
                sb.append(' ').append(measured.recorded())
                        .append(" reading(s) filed so their accuracy can be checked later.");
            }
        }
        sb.append(' ').append(extraction.note());
        return sb.toString();
    }

    private static MacroKeywordExtractor.Headline toHeadline(MarketImpactNewsEntity e) {
        LocalDate on = e.getPublishedAt() != null ? e.getPublishedAt().toLocalDate()
                : (e.getCreatedAt() != null ? e.getCreatedAt().toLocalDate() : LocalDate.now());
        return new MacroKeywordExtractor.Headline(
                e.getId() == null ? 0L : e.getId(), e.getTitle(), e.getDescription(),
                e.getSource(), e.getUrl(), on);
    }

    private static String join(List<?> values) {
        if (values == null || values.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (Object v : values) {
            if (v != null) parts.add(String.valueOf(v));
        }
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    private static String safeMapVersion() {
        try {
            return MacroExposureMap.version();
        } catch (Exception e) {
            return null;
        }
    }
}
