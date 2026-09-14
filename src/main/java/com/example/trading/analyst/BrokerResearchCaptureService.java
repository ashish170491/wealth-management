package com.example.trading.analyst;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * Captures attributed price targets from the structured broker-research feed (SPEC §49.11).
 *
 * <p><b>This is the primary capture path; the headline parser is the fallback.</b> Every field
 * {@link AnalystTargetParser} and {@link HeadlineSubjectResolver} work to infer is published
 * here as a field, which removes three classes of defect outright — a target truncated by a
 * corrupt separator (B-111), a company name resolving to a bigger company whose name it contains
 * (B-109), and a commodity contract resolving to the exchange that lists it (B-110). Measured on
 * 6,275 historical headlines the parser produced 611 rows with 4 wrong; this path produces a row
 * per published note with none of those failure modes available to it.
 *
 * <p><b>It also removes the four selection filters</b> that made §49's coverage thin. A headline
 * had to exist, name a house, carry a rupee figure, and name a company the resolver knew. Here
 * every note the feed carries is a row, which is why a sample of 800 spanned 416 companies where
 * 6,275 headlines spanned 291.
 *
 * <p><b>What it does not change: nothing here contributes a point to any score.</b> A third
 * party's opinion is recorded so it can be checked, and the shadow rule is unchanged (Gotcha 30).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerResearchCaptureService {

    private final BrokerResearchClient client;
    private final AnalystTargetCaptureService capture;
    private final AnalystTargetConfig config;

    /**
     * What one pass did, reported as a funnel rather than a count.
     *
     * <p>A bare "wrote 3 rows" cannot distinguish a quiet week from a feed that has moved, which
     * is the failure B-089 ran for four months (Gotcha 94).
     */
    public record FeedResult(int pagesRead,
                             int pagesFailed,
                             int rowsRead,
                             int written,
                             int duplicates,
                             int unresolvedSymbol,
                             int rejected,
                             LocalDate oldestSeen,
                             LocalDate newestSeen,
                             Map<String, Integer> byHouse) {
    }

    /**
     * The daily pass: read from the top and stop once a page adds nothing.
     *
     * <p>Cheap by construction — one or two pages on an ordinary day, because yesterday's notes
     * are already on file and the dedup key rejects them without a write.
     */
    public FeedResult captureLatest() {
        return run(config.getFeedDailyMaxPages(), true, () -> false);
    }

    /**
     * The one-off deep pass over the archive.
     *
     * @param maxPages   how far back to walk; the archive runs to roughly eighty-five pages
     * @param shouldStop checked between pages so a deadline can cut the run short
     */
    public FeedResult backfill(int maxPages, BooleanSupplier shouldStop) {
        FeedResult result = run(maxPages, false, shouldStop);
        // The archive is walked newest-first, so every older note is written AFTER the newer one
        // that replaced it and the on-write supersession pass - which looks backwards - finds
        // nothing to supersede. Without this the whole of a house's revision history reads as a
        // dozen simultaneously-open calls: "still running" inflates, the revision rate collapses,
        // and one call is counted many times in a hit rate.
        capture.reconcileSupersessions();
        return result;
    }

    private FeedResult run(int maxPages, boolean stopWhenPageAddsNothing, BooleanSupplier shouldStop) {
        int limit = config.getFeedPageSize();
        int pages = 0, pagesFailed = 0, read = 0, written = 0, dup = 0, unresolved = 0, rejected = 0;
        LocalDate oldest = null, newest = null;
        Map<String, Integer> byHouse = new TreeMap<>();

        for (int page = 0; page < Math.max(1, maxPages); page++) {
            if (shouldStop.getAsBoolean()) {
                log.info("Analyst ledger: research feed pass stopping early at page {} - deadline", page);
                break;
            }
            BrokerResearchClient.Page fetched = client.fetchPageSplitting(page * limit, limit);
            pages++;

            // A failed page is NOT the end of the archive. Reading it as one is how the first
            // backfill stopped at 40% of the archive and reported success: one page 400'd, the
            // empty list looked exactly like "no more rows", and fourteen months of history were
            // silently dropped. Skip the slice, count it, keep walking.
            if (fetched.failed()) {
                pagesFailed++;
                log.warn("Analyst ledger: page {} could not be read even split - skipping that "
                        + "slice and continuing; {} pages have now failed", page, pagesFailed);
                continue;
            }
            if (fetched.endOfArchive()) break;

            List<BrokerResearchRow> rows = fetched.rows();

            int writtenOnPage = 0;
            for (BrokerResearchRow row : rows) {
                read++;
                LocalDate on = row.effectiveDate();
                if (on != null) {
                    if (oldest == null || on.isBefore(oldest)) oldest = on;
                    if (newest == null || on.isAfter(newest)) newest = on;
                }

                Outcome outcome = store(row);
                switch (outcome) {
                    case WRITTEN -> {
                        written++;
                        writtenOnPage++;
                        byHouse.merge(canonicalHouse(row.brokerage()), 1, Integer::sum);
                    }
                    case DUPLICATE -> dup++;
                    case NO_SYMBOL -> unresolved++;
                    case REJECTED -> rejected++;
                }
            }

            if (stopWhenPageAddsNothing && writtenOnPage == 0 && page > 0) break;
        }

        FeedResult result = new FeedResult(pages, pagesFailed, read, written, dup, unresolved,
                rejected, oldest, newest, byHouse);
        log.info("Analyst ledger: research feed pass - {} pages ({} failed), {} rows read, "
                        + "{} written, {} already on file, {} unresolvable to a symbol, "
                        + "{} rejected ({} to {})",
                pages, pagesFailed, read, written, dup, unresolved, rejected, oldest, newest);
        return result;
    }

    private enum Outcome { WRITTEN, DUPLICATE, NO_SYMBOL, REJECTED }

    /**
     * Resolve one feed row to a stock and file it.
     *
     * <p>Resolution is by the feed's own stock id first — an exact identifier, looked up once and
     * cached — and falls back to reading the heading only when the row carries no usable id. That
     * ordering matters: the fallback is the path that produced B-109 and B-110, so it is used
     * where nothing better exists rather than as a peer.
     */
    private Outcome store(BrokerResearchRow row) {
        LocalDate on = row.effectiveDate();
        if (on == null || row.targetPrice() <= 0) return Outcome.REJECTED;

        String symbol = null;
        String company = null;
        String matchedOn = null;

        Optional<String> byId = client.resolveAny(row.scids());
        if (byId.isPresent()) {
            symbol = "NSE:" + byId.get();
            matchedOn = "scid:" + String.join("+", row.scids());
        } else if (row.heading() != null) {
            HeadlineSubjectResolver.Subject subject = HeadlineSubjectResolver.resolve(row.heading());
            if (subject.resolved()) {
                symbol = subject.symbol();
                company = subject.companyName();
                matchedOn = "heading:" + subject.matchedOn();
            }
        }
        if (symbol == null) return Outcome.NO_SYMBOL;

        AnalystTargetParser.ParsedCall parsed = new AnalystTargetParser.ParsedCall(
                canonicalHouse(row.brokerage()),
                row.targetPrice(),
                row.rating(),
                row.action(),
                // The feed states no horizon. The ledger's default applies and is recorded as an
                // assumption rather than a statement (B-057's rule: a default is not a statement).
                null);

        AnalystTargetEntity saved = capture.record(
                symbol,
                company,
                canonicalHouse(row.brokerage()),
                row.targetPrice(),
                on,
                parsed,
                row.heading(),
                config.getFeedSourceName(),
                row.researchPdfUrl(),
                row.feedId(),
                matchedOn,
                row.calledOn(),
                row.brokerPrice(),
                BrokerResearchParser.VERSION);

        return saved == null ? Outcome.DUPLICATE : Outcome.WRITTEN;
    }

    /**
     * One house, one name.
     *
     * <p>The feed lists "Anand Rathi", "AnandRathi" and "Anand Rathi Financial Services" as three
     * separate publishers. Left alone that splits one firm's record across three rows of the
     * scoreboard, each below the five-call floor, so a house with a real track record reports
     * TOO_EARLY for ever. {@link Brokerages} is the app's existing canonical vocabulary and is
     * reused here rather than started again; a house it does not know keeps its published name,
     * because inventing a canonical form for an unknown firm is worse than a duplicate.
     */
    static String canonicalHouse(String published) {
        if (published == null || published.isBlank()) return null;
        String cleaned = published.trim().replaceAll("\\s+", " ");
        String known = Brokerages.find(cleaned);
        if (known != null) return known;

        // Second pass on a squeezed form. The feed publishes "AnandRathi" alongside "Anand Rathi",
        // and a substring match cannot see through the missing space - which showed up on the
        // first live scoreboard as ONE firm occupying two rows with visibly different records
        // (44.3% hit over 61 resolved beside 37.5% over 80). Comparing with spacing and
        // punctuation removed merges those without loosening the match itself.
        String squeezed = squeeze(cleaned);
        if (squeezed.isEmpty()) return cleaned;
        String best = null;
        for (String name : Brokerages.NAMES) {
            String candidate = squeeze(name);
            if (candidate.isEmpty() || !squeezed.contains(candidate)) continue;
            // Longest wins, so a shorter alias inside a fuller name never captures it.
            if (best == null || candidate.length() > squeeze(best).length()) best = name;
        }
        return best != null ? best : cleaned;
    }

    /** Lower-case letters and digits only - spacing and punctuation carry no meaning in a firm name. */
    private static String squeeze(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Coverage of the id map, for the status endpoint. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("feed", config.getFeedSourceName());
        out.put("enabled", config.isFeedEnabled());
        out.put("baseUrl", config.getFeedBaseUrl());
        return out;
    }
}
