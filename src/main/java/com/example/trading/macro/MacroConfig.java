package com.example.trading.macro;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.LocalTime;

/**
 * Settings for macro event exposure (SPEC §48). Bound from {@code macro.*}.
 *
 * <p>Every field is listed in {@code application.yml} on purpose: these are
 * {@code @ConfigurationProperties}, so a key omitted from the file keeps its Java default rather
 * than being disabled - the failure that inflated every composite for four months (B-019).
 */
@Configuration
@ConfigurationProperties(prefix = "macro")
@Data
@Slf4j
public class MacroConfig {

    /**
     * How far back an event still counts against a stock.
     *
     * <p>A fortnight is a judgement, not a measurement, and it is the shortest window that still
     * spans a policy decision and the data print that follows it. It is deliberately not longer:
     * an event that has been on the ledger for a month has been in the share price for a month,
     * and the reading would be describing history rather than context.
     */
    private int windowDays = 14;

    /** Look-ahead for the calendar. */
    private int calendarDays = 30;

    /** Two reports of the same factor and direction this many days apart are one event. */
    private int dedupWindowDays = 3;

    private Ingest ingest = new Ingest();

    @Data
    public static class Ingest {

        /**
         * <b>Does not create a scheduler, and is false.</b> No {@code @Scheduled} method exists for
         * extraction in this phase; the flag is bound and logged at boot so the intent is on the
         * record. Adding a cron needs a SPEC §15 row and an argument for the slot (09:16, 12:40 and
         * 14:20 are the candidates, all inside the 09:15-15:30 window SPEC §3.4 allows).
         *
         * <p>Extraction runs only from {@code POST /api/macro/ingest} - the button on the Events
         * page - which is what keeps a model call an act the investor chose rather than a standing
         * cost.
         */
        private boolean scheduled = false;

        /** Headlines older than this are not offered to the extractor. */
        private int headlineLookbackHours = 36;

        /** Per ingest. Bounds both the token bill and the wall clock. */
        private int maxHeadlines = 120;

        /** Live-call guard: from this time the screening and report jobs own NSE and the broker. */
        private String refuseFrom = "14:00";

        public LocalTime refuseFromTime() {
            try {
                return LocalTime.parse(refuseFrom);
            } catch (Exception e) {
                return LocalTime.of(14, 0);
            }
        }
    }

    @PostConstruct
    void announce() {
        log.info("Macro event exposure: window {} days, dedup {} days, ingest cap {} headlines. "
                        + "Scheduled extraction is {} - extraction runs only from POST /api/macro/ingest.",
                windowDays, dedupWindowDays, ingest.getMaxHeadlines(),
                ingest.isScheduled() ? "ENABLED IN CONFIG BUT NO CRON EXISTS (SPEC 48.6)" : "off");
    }
}
