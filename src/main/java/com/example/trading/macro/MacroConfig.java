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
         * Whether {@link MacroIngestScheduler} reads the news at 12:40 on a weekday (SPEC 48.9,
         * revised 2026-10-12). It was false and nothing scheduled the read, so the news went unread
         * from 2026-09-12 and every holding drew "nothing applies" off an empty window (B-154). The
         * original objection was the cost of a model call; on the keyword reader a run costs nothing
         * but ~84 s of RSS fetches, and with a model configured it is a rupee or two a day. Set false
         * to go back to the button only.
         */
        private boolean scheduled = true;

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
                        + "Scheduled extraction is {}; POST /api/macro/ingest still runs it on demand.",
                windowDays, dedupWindowDays, ingest.getMaxHeadlines(),
                ingest.isScheduled() ? "on (12:40 MON-FRI)" : "off");
    }
}
