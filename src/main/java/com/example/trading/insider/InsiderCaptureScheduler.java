package com.example.trading.insider;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Daily capture of insider disclosures (SPEC §28, F1).
 *
 * <p>Runs at 14:45 IST — inside the market window per SPEC §3.4, and deliberately clear of
 * both the 14:00 screening and the packed 15:00–15:30 close ramp that starved jobs before
 * B-014.
 *
 * <h2>One index call, not a rationed sweep — and why the rotation is gone</h2>
 * NSE used to serve PIT disclosures <b>per symbol only</b>, so this job probed a budget of 80
 * symbols a run and tried to rotate fairly through the rest. That design produced two bugs.
 * B-074: the budget was filled from holdings plus a <i>verdict-filtered</i> candidate query,
 * so ~286 of 295 screened stocks were never probed and Insider Pulse recorded <b>0% coverage</b>
 * — a signal computed for no stock at all. B-090: the rotation added to fix it was handed
 * {@code BUDGET − alreadyChosen} slots, which was <b>zero</b> whenever holdings and candidates
 * reached the cap, so it contributed nothing on the very input shape that caused B-074 while
 * logging "full cycle ~1 runs".
 *
 * <p>Both are now structurally impossible rather than guarded against. Since PIT V2.0
 * (B-089) NSE publishes an <b>all-market filing index</b> — one call listing every 7(2)/7(3)
 * filing on the market — so there is no budget to ration, no ordering to be fair about, and
 * every tracked symbol is checked on every run. The only per-filing cost is one XBRL fetch,
 * paid once per filing ever, because ingestion is keyed on the filing's {@code appId}.
 *
 * <p>The remaining limits are cost ceilings, not sampling: {@link #MAX_FILINGS_PER_RUN} and a
 * wall-clock deadline stop a first-run backlog from overrunning the window. Anything not
 * reached is picked up next run, and nothing is skipped permanently — the difference between
 * a cap and a keyhole.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InsiderCaptureScheduler {

    private final InsiderDisclosureService disclosureService;
    private final MarketHoursService marketHoursService;
    private final HoldingsRepository holdingsRepository;
    private final MultibaggerScoreRepository scoreRepository;

    /**
     * Ceiling on XBRL downloads per run. Sized to absorb the initial backlog in one pass
     * (307 filings covered the universe's trailing 90 days when this shipped) while bounding
     * a pathological day. Not a sample: the remainder is fetched by the next run.
     */
    static final int MAX_FILINGS_PER_RUN = 400;

    /** Wall-clock ceiling for the filing fetches, whatever the count. */
    static final long MAX_RUN_MS = 10L * 60 * 1000L;

    /**
     * Latest this job may still be downloading when the market is open — clear of the
     * 15:00–15:30 ramp whose jobs abort silently at 15:30 (B-014). Applied <b>only</b> during
     * market hours: an out-of-hours manual run contends with nothing, and clamping it to a
     * time already past would silently fetch nothing (B-082 / Gotcha 97 — the reason for a
     * guard is contention, so the guard covers the contention and not the clock).
     */
    private static final LocalTime IN_MARKET_STOP_AFTER = LocalTime.of(15, 10);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Scheduled(cron = "0 45 14 * * MON-FRI", zone = "Asia/Kolkata")
    public void captureDaily() {
        if (!marketHoursService.isMarketOpen()) {
            return; // Outside 09:15-15:30 IST window — SPEC §3.4.
        }
        runCapture();
    }

    /** Public so the manual endpoint and tests can drive the same path. */
    public CaptureResult runCapture() {
        long start = System.currentTimeMillis();
        int deals = 0;
        try {
            deals = disclosureService.captureDeals();
        } catch (Exception e) {
            log.error("Insider capture: all-market deal capture failed: {}", e.getMessage(), e);
        }

        Set<String> wanted = resolveWantedSymbols();
        InsiderDisclosureService.PitCaptureSummary pit =
                disclosureService.capturePitFromIndex(wanted, MAX_FILINGS_PER_RUN, deadline());

        long ms = System.currentTimeMillis() - start;
        // Attempted, skipped and persisted are logged together on purpose: a bare success
        // count cannot reveal a partial failure (B-026).
        log.info("Insider capture: feed has {} filings, {} for our {} tracked symbols "
                        + "({} already ingested, {} downloaded{}), {} new PIT rows, "
                        + "{} new deal rows, newest filing on the market {}, {} ms",
                pit.filingsInFeed(), pit.filingsForUniverse(), wanted.size(),
                pit.alreadyIngested(), pit.filingsFetched(), pit.truncated() ? ", capped" : "",
                pit.newRows(), deals, pit.newestFilingDate(), ms);

        if (pit.filingsInFeed() == 0) {
            log.warn("Insider capture: the PIT filing index returned nothing. Every pulse "
                    + "verdict will read as 'no filings', which is indistinguishable from "
                    + "'no insider traded' — check the feed before trusting a NEUTRAL.");
        }

        return new CaptureResult(pit.filingsInFeed(), pit.filingsForUniverse(), pit.filingsFetched(),
                pit.newRows(), deals, pit.alreadyIngested(), pit.truncated(),
                pit.newestFilingDate() == null ? null : pit.newestFilingDate().toString(), ms);
    }

    /** Stop fetching at this instant. Bounded by the market ramp only while the market is open. */
    private long deadline() {
        long byDuration = System.currentTimeMillis() + MAX_RUN_MS;
        if (!marketHoursService.isMarketOpen()) return byDuration;
        long byWindow = ZonedDateTime.now(IST).with(IN_MARKET_STOP_AFTER)
                .toInstant().toEpochMilli();
        return Math.min(byDuration, byWindow);
    }

    /**
     * Every symbol whose insider filings we want: active holdings plus the latest screening
     * universe. Unqualified trading symbols, because that is what the filing index carries.
     *
     * <p>There is no budget and therefore no ordering that matters — the whole set is checked
     * every run. Holdings are included separately from the screening universe because a
     * holding may sit outside it, and because 22 of 33 are BSE-prefixed (Gotcha 84): stripping
     * the exchange prefix is exactly right here, since a company files once and the filing
     * names the company, not the listing venue.
     *
     * <p>Walks back through screening dates rather than assuming today has rows — today has
     * none until the 14:00 run (Gotcha 20).
     */
    private Set<String> resolveWantedSymbols() {
        Set<String> out = new LinkedHashSet<>();

        try {
            for (HoldingsEntity h : holdingsRepository.findActive()) {
                addStripped(out, h.getSymbol());
            }
        } catch (Exception e) {
            log.warn("Insider capture: could not load holdings: {}", e.getMessage());
        }

        try {
            List<LocalDate> dates = scoreRepository.findScreeningDates();
            if (dates != null && !dates.isEmpty()) {
                for (MultibaggerScoreEntity s
                        : scoreRepository.findByScreeningDateOrderByCompositeScoreDesc(dates.get(0))) {
                    addStripped(out, s.getSymbol());
                }
            }
        } catch (Exception e) {
            log.warn("Insider capture: could not load latest screening: {}", e.getMessage());
        }
        return out;
    }

    /** {@code NSE:RELIANCE} / {@code BSE:RELIANCE} -> {@code RELIANCE}. */
    private static void addStripped(Set<String> out, String symbol) {
        if (symbol == null || symbol.isBlank()) return;
        String s = symbol.trim();
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        if (!s.isBlank()) out.add(s);
    }

    public record CaptureResult(int filingsInFeed, int filingsForUniverse, int filingsFetched,
                                int newPitRows, int newDealRows, int alreadyIngested,
                                boolean truncated, String newestFilingDate, long elapsedMs) {}
}
