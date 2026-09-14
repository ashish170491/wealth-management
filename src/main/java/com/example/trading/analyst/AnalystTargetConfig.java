package com.example.trading.analyst;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.LocalTime;

/**
 * Settings for the analyst target ledger (SPEC §49). Bound from {@code trading.analyst-targets.*}.
 *
 * <p>Every field is listed in {@code application.yml} on purpose: these are
 * {@code @ConfigurationProperties}, so a key omitted from the file silently keeps its Java
 * default rather than being disabled — the failure that inflated every composite for four months
 * (B-019).
 */
@Configuration
@ConfigurationProperties(prefix = "trading.analyst-targets")
@Data
@Slf4j
public class AnalystTargetConfig {

    /** Whether the daily capture and measurement run at all. */
    private boolean enabled = true;

    /**
     * <b>Permanently false in this phase, and there is no bonus figure to turn on.</b>
     *
     * <p>An analyst target is a third party's opinion arriving through a news headline, and the
     * two engines deleted on 2026-09-03 were both news-keyword engines that had run for months
     * without producing a single measured hit rate (SPEC §39.3). Letting one steer the composite
     * before this ledger can say whether these calls are worth anything would repeat that exactly.
     * The ledger exists to produce that evidence; until it has, the signal is worth zero points.
     *
     * <p>Note what is deliberately absent: a companion {@code analystTargetBonus}. Choosing how
     * many points a brokerage upgrade is worth before the measurement has established that it has
     * a sign is the failure Gotcha 30 exists to prevent, and the macro lens made the same choice
     * for the same reason. Pinned by {@code NewSignalShadowModeTest}.
     */
    private boolean actionable = false;

    /**
     * Days a target of unstated horizon is measured over.
     *
     * <p>365 because the Indian sell-side convention is a twelve-month target. It is applied as a
     * convention and recorded as one — {@code horizonStated=false} on the row — so nothing can
     * later read the assumption as something the analyst said.
     */
    private int defaultHorizonDays = 365;

    /** The same house repeating the same target inside this window is one call, not two. */
    private int dedupWindowDays = 7;

    /** Headlines older than this are not mined on a capture run. */
    private int headlineLookbackDays = 7;

    /**
     * Open targets measured per run.
     *
     * <p>Each one costs a paced Kite historical-data call on a cold cache, against a process-wide
     * gate of roughly 2.9 requests a second that has twice starved the afternoon schedulers
     * (B-014, B-049). The repository hands back the least-recently-measured first, so a bounded
     * run rotates through the whole book instead of re-reading its head.
     */
    private int maxMeasurementsPerRun = 120;

    /**
     * Resolved targets a house needs before its hit rate is reported at all.
     *
     * <p>Two out of two is one hundred per cent, and that is exactly the number that misleads.
     * The concall guidance ledger refuses below four resolved promises for the same reason
     * (Gotcha 47); this is the same refusal with one more observation, because the sample here is
     * additionally biased by which calls reach a headline.
     */
    private int minResolvedForTrackRecord = 5;

    /** Track-record window. Older calls stay on the ledger but stop counting toward a house's record. */
    private int trackRecordLookbackDays = 1095;

    /** Live-call guard: from this time the 14:00 screening owns the broker budget. */
    private String stopAfter = "13:50";

    // ------------------------------------------------------------------ structured research feed

    /**
     * Whether the structured broker-research feed is read at all (SPEC §49.11).
     *
     * <p>On by default because it is strictly better evidence than the headline parser it
     * supersedes - published fields rather than inferred ones. Turning it off leaves the headline
     * path running, so the ledger degrades rather than stopping.
     */
    private boolean feedEnabled = true;

    /** The recommendations endpoint. */
    private String feedBaseUrl = "https://api.moneycontrol.com/mcapi/v1/broker-research";

    /** Where a feed stock id is resolved to an exchange symbol. Looked up once per company, ever. */
    private String priceFeedUrl = "https://priceapi.moneycontrol.com/pricefeed/nse/equitycash";

    /** Shown on every row this path writes, so the investor can see where a target came from. */
    private String feedSourceName = "Moneycontrol broker research";

    /**
     * Minimum gap between two calls to the feed host, milliseconds.
     *
     * <p>This is a third party's public endpoint being read for one investor's own portfolio, and
     * the courteous rate is the whole licence to use it. A full backfill is a few thousand
     * requests; at this pace that is minutes, which is the right trade.
     */
    private long feedPaceMs = 400;

    private int feedTimeoutSeconds = 30;

    /** Rows per page. The feed accepts 100; larger values are not honoured. */
    private int feedPageSize = 100;

    /**
     * How many pages the daily pass may read before giving up.
     *
     * <p>It normally stops after one or two because the rest is already on file. The cap exists
     * so a feed change that stops the dedup matching cannot turn the daily job into a backfill.
     */
    private int feedDailyMaxPages = 4;

    /** Identifies this client honestly rather than impersonating a browser. */
    private String feedUserAgent =
            "intraday-app/1.0 (personal portfolio research; single user; contact via app owner)";

    private String feedReferer = "https://www.moneycontrol.com/markets/stock-ideas";

    public LocalTime stopAfterTime() {
        try {
            return LocalTime.parse(stopAfter);
        } catch (Exception e) {
            return LocalTime.of(13, 50);
        }
    }

    @PostConstruct
    void announce() {
        log.info("Analyst target ledger: capture {}, default horizon {}d, dedup {}d, "
                        + "{} measurements/run, track record needs {} resolved calls. "
                        + "Scoring contribution: {}.",
                enabled ? "on" : "off", defaultHorizonDays, dedupWindowDays,
                maxMeasurementsPerRun, minResolvedForTrackRecord,
                actionable ? "ACTIONABLE - THIS IS NOT THE SHIPPED DEFAULT (SPEC 49.9)" : "zero points");
    }
}
