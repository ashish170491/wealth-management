package com.example.trading.analyst;

import com.example.trading.ai.StockNewsService.BrokerageAction;
import com.example.trading.ai.StockNewsService.BrokerageActionData;
import com.example.trading.intelligence.MarketImpactNewsEntity;
import com.example.trading.intelligence.MarketImpactNewsRepository;
import com.example.trading.multibagger.UniverseSectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.HashMap;
import java.util.Comparator;
import java.util.ArrayList;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Writes attributed price targets into the ledger (SPEC §49.5).
 *
 * <p><b>Two sources, no new network cost.</b> Both feeds this reads are already being fetched
 * for other reasons:
 *
 * <ol>
 *   <li>the market-wide headline table that {@code MarketImpactNewsService} fills every fifteen
 *       minutes (SPEC §48.4), where the company has to be resolved from the text; and</li>
 *   <li>the per-stock brokerage scan the multibagger screener already runs on every stock in the
 *       universe for the §24 signal — where the symbol is known, so there is no attribution risk
 *       at all, and where the extracted target was previously counted and then thrown away.</li>
 * </ol>
 *
 * That second source is the whole reason this feature is cheap: roughly three hundred news
 * fetches a day were already happening and the target price in them was being discarded.
 *
 * <p><b>Capture never prices a call.</b> Resolving the issue-date close costs a paced broker
 * call, and doing that inside a screening loop over three hundred stocks would spend the budget
 * that has twice starved the afternoon schedulers (B-014, B-049). A new row is therefore written
 * {@code UNPRICED} and {@link AnalystTargetOutcomeService} prices it on the next daily pass.
 * {@code UNPRICED} is an honest state — a recorded claim we cannot yet measure — and it is
 * excluded from every hit rate until it is priced.
 *
 * <p>Never throws to its callers: a ledger write must not break a screening run.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnalystTargetCaptureService {

    private final AnalystTargetRepository repository;
    private final MarketImpactNewsRepository newsRepository;
    private final AnalystTargetConfig config;

    /** RSS dates, both spellings Google News emits. */
    private static final List<DateTimeFormatter> RSS_FORMATS = List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH));

    /** What one capture pass did. Every field is a count someone can act on. */
    public record CaptureResult(int headlinesRead,
                                int targetsFound,
                                int recorded,
                                int duplicates,
                                int superseded,
                                int noBrokerage,
                                int noTarget,
                                int unresolvedCompany,
                                int ambiguousCompany,
                                int unparseableDate,
                                String note) {
    }

    // ------------------------------------------------------------------ source 2: the screener

    /**
     * Record whatever the per-stock brokerage scan found for one already-identified stock.
     *
     * <p>Called from the screener, where the symbol is known from the scan itself, so nothing is
     * inferred and a misattribution is structurally impossible on this path.
     *
     * @return how many new rows were written
     */
    public int recordFromScan(String symbol, BrokerageActionData actions) {
        if (!config.isEnabled() || actions == null || actions.getActions() == null) return 0;
        int written = 0;
        for (BrokerageAction action : actions.getActions()) {
            try {
                if (action.getTargetPriceInr() == null) continue;   // a rating change, not a target
                String house = Brokerages.find(action.getBrokerage() + " " + nullToEmpty(action.getHeadline()));
                if (house == null) continue;                        // unattributable - see Brokerages
                LocalDate issued = parseRssDate(action.getPubDate());
                if (issued == null) continue;                       // never falls back to today
                AnalystTargetParser.Result parsed = AnalystTargetParser.parse(action.getHeadline());
                if (record(qualify(symbol), companyNameFor(symbol), house,
                        action.getTargetPriceInr(), issued,
                        parsed.ok() ? parsed.call() : null,
                        action.getHeadline(), action.getSource(), null, null,
                        "screener scan (symbol known)") != null) {
                    written++;
                }
            } catch (Exception e) {
                log.debug("Analyst ledger: could not record a scanned call for {}: {}", symbol, e.getMessage());
            }
        }
        return written;
    }

    // ------------------------------------------------------------------ source 1: stored headlines

    /**
     * Mine the stored market-wide headline feed for targets.
     *
     * <p>Database-only: the headlines were fetched by another job. Each row is stamped read
     * whatever the outcome, so a headline carrying no target is never offered twice.
     */
    public CaptureResult mineStoredHeadlines() {
        if (!config.isEnabled()) {
            return new CaptureResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "Analyst target capture is disabled.");
        }

        LocalDateTime since = LocalDateTime.now().minusDays(config.getHeadlineLookbackDays());
        List<MarketImpactNewsEntity> unread;
        try {
            unread = newsRepository.findAnalystUnreadSince(since);
        } catch (Exception e) {
            log.warn("Analyst ledger: could not read the headline feed ({}). No targets were captured "
                    + "this run - which will read on screen as 'no analyst published a target', so it "
                    + "is reported rather than swallowed.", e.getMessage());
            return new CaptureResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "Headline feed unavailable: " + e.getMessage());
        }

        AtomicInteger targets = new AtomicInteger();
        int recorded = 0, duplicates = 0, noBrokerage = 0, noTarget = 0;
        int unresolved = 0, ambiguous = 0, badDate = 0;

        for (MarketImpactNewsEntity news : unread) {
            try {
                AnalystTargetParser.Result parsed = AnalystTargetParser.parse(news.getTitle());
                if (!parsed.ok()) {
                    if (parsed.reject() == AnalystTargetParser.Reject.NO_BROKERAGE) noBrokerage++;
                    else noTarget++;
                    continue;
                }
                targets.incrementAndGet();

                HeadlineSubjectResolver.Subject subject = HeadlineSubjectResolver.resolve(news.getTitle());
                if (!subject.resolved()) {
                    if (subject.miss() == HeadlineSubjectResolver.Miss.AMBIGUOUS) ambiguous++;
                    else unresolved++;
                    continue;
                }

                LocalDate issued = news.getPublishedAt() != null
                        ? news.getPublishedAt().toLocalDate() : null;
                if (issued == null) { badDate++; continue; }

                AnalystTargetEntity saved = record(subject.symbol(), subject.companyName(),
                        parsed.call().brokerage(), parsed.call().targetPrice(), issued, parsed.call(),
                        news.getTitle(), news.getSource(), news.getUrl(), news.getId(),
                        subject.matchedOn());
                if (saved != null) recorded++; else duplicates++;
            } catch (Exception e) {
                log.debug("Analyst ledger: headline {} could not be mined: {}", news.getId(), e.getMessage());
            } finally {
                markRead(news);
            }
        }

        String note = String.format("%d headlines read, %d carried an attributed target, %d recorded "
                        + "(%d already on file). Not recorded: %d named no house we know, %d named no "
                        + "company we could identify, %d named two.",
                unread.size(), targets.get(), recorded, duplicates, noBrokerage, unresolved, ambiguous);
        log.info("Analyst target capture: {}", note);
        return new CaptureResult(unread.size(), targets.get(), recorded, duplicates, 0,
                noBrokerage, noTarget, unresolved, ambiguous, badDate, note);
    }

    private void markRead(MarketImpactNewsEntity news) {
        try {
            news.setAnalystExtractedAt(LocalDateTime.now());
            newsRepository.save(news);
        } catch (Exception e) {
            log.debug("Analyst ledger: could not stamp headline {} as read: {}", news.getId(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ the write

    /**
     * Write one target, unless it is already on file.
     *
     * <p><b>Dedup is two-layered</b>, the pattern the macro ledger already uses. The unique
     * {@code dedupKey} stops a re-run inserting the same call twice; a window scan over the same
     * house and stock catches Wednesday's note being written up again on Friday, which the exact
     * key would happily record as a second call and thereby double that house's apparent output.
     *
     * @return the saved row, or null when it was a duplicate or was rejected
     */
    AnalystTargetEntity record(String symbol,
                               String companyName,
                               String brokerage,
                               double targetPrice,
                               LocalDate issuedOn,
                               AnalystTargetParser.ParsedCall parsed,
                               String headline,
                               String sourceName,
                               String sourceUrl,
                               Long headlineId,
                               String matchedOn) {
        return record(symbol, companyName, brokerage, targetPrice, issuedOn, parsed, headline,
                sourceName, sourceUrl, headlineId, matchedOn, null, null,
                AnalystTargetParser.VERSION);
    }

    /**
     * The same write, with the provenance a structured feed can supply and a headline cannot
     * (SPEC §49.11).
     *
     * <p>Deliberately the same method rather than a parallel one. Both sources have to pass the
     * same dedup and the same supersession pass, or one house's note arriving down both paths is
     * counted twice and its apparent output doubles - and the composite dedup key is what makes
     * the two paths collapse onto one row when they describe the same call.
     *
     * @param calledOn          the date on the note, when the source states one
     * @param brokerStatedPrice the price the note quoted as current, recorded but never measured on
     * @param extractorVersion  which reader produced this row
     */
    AnalystTargetEntity record(String symbol,
                               String companyName,
                               String brokerage,
                               double targetPrice,
                               LocalDate issuedOn,
                               AnalystTargetParser.ParsedCall parsed,
                               String headline,
                               String sourceName,
                               String sourceUrl,
                               Long headlineId,
                               String matchedOn,
                               LocalDate calledOn,
                               Double brokerStatedPrice,
                               String extractorVersion) {
        // A target dated in the future is a parse error or a re-published preview, never a call
        // that has been made (B-031's rule, applied on this path too).
        if (issuedOn == null || issuedOn.isAfter(LocalDate.now())) return null;
        if (targetPrice <= 0) return null;

        String key = dedupKey(symbol, brokerage, targetPrice, issuedOn);
        if (repository.findByDedupKey(key).isPresent()) return null;

        LocalDate windowStart = issuedOn.minusDays(config.getDedupWindowDays());
        for (AnalystTargetEntity recent : repository.findRecentByHouse(symbol, brokerage, windowStart)) {
            if (sameTarget(recent.getTargetPrice(), targetPrice)) return null;
        }

        Integer horizonMonths = parsed != null ? parsed.horizonMonths() : null;
        int horizonDays = horizonMonths != null
                ? (int) Math.round(horizonMonths * 30.44)
                : config.getDefaultHorizonDays();

        AnalystTargetEntity entity = AnalystTargetEntity.builder()
                .symbol(symbol)
                .companyName(companyName)
                .brokerage(brokerage)
                .rating(parsed != null ? parsed.rating().name() : AnalystTargetParser.Rating.NOT_STATED.name())
                .action(parsed != null ? parsed.action().name() : AnalystTargetParser.Action.NOT_STATED.name())
                .targetPrice(targetPrice)
                .issuedOn(issuedOn)
                .horizonDays(horizonDays)
                .horizonStated(horizonMonths != null)
                .resolvesOn(issuedOn.plusDays(horizonDays))
                // Unpriced until the outcome pass can fetch the issue-date close. Not zero, not
                // today's price - both would be a number where there is no measurement.
                .status(AnalystTargetStatus.UNPRICED.name())
                .headline(headline)
                .sourceName(truncate(sourceName, 200))
                .sourceUrl(truncate(sourceUrl, 1000))
                .headlineId(headlineId)
                .matchedOn(truncate(matchedOn, 200))
                .extractorVersion(extractorVersion != null ? extractorVersion : AnalystTargetParser.VERSION)
                .calledOn(calledOn)
                .brokerStatedPrice(brokerStatedPrice)
                .dedupKey(key)
                .build();

        AnalystTargetEntity saved;
        try {
            saved = repository.save(entity);
        } catch (Exception e) {
            // A unique-constraint collision means a concurrent run got there first, which is a
            // duplicate rather than a failure. Anything else is worth a line.
            log.debug("Analyst ledger: {} target for {} not written: {}", brokerage, symbol, e.getMessage());
            return null;
        }

        supersedeEarlier(saved);
        return saved;
    }

    /**
     * Mark this house's earlier open calls on the same stock as superseded.
     *
     * <p>Only open ones. A call that already reached its target resolved before it was revised,
     * and rewriting that as "superseded" would erase a hit from the record — the ledger never
     * rewrites a resolved row (SPEC §49.2).
     */
    private void supersedeEarlier(AnalystTargetEntity fresh) {
        try {
            for (AnalystTargetEntity old :
                    repository.findOpenByHouseBefore(fresh.getSymbol(), fresh.getBrokerage(), fresh.getIssuedOn())) {
                if (old.getId().equals(fresh.getId())) continue;
                old.setStatus(AnalystTargetStatus.SUPERSEDED.name());
                old.setSupersededByTargetId(fresh.getId());
                old.setSupersededOn(fresh.getIssuedOn());
                repository.save(old);
            }
        } catch (Exception e) {
            log.debug("Analyst ledger: supersession pass failed for {}: {}", fresh.getSymbol(), e.getMessage());
        }
    }

    /**
     * Re-derive supersession across the whole ledger (SPEC §49.11).
     *
     * <p><b>Why this has to exist.</b> {@link #supersedeEarlier} fires on write and looks
     * <em>backwards</em>, which is right when calls arrive in the order they were made. A
     * backfill walks the archive newest-first, so every older note is written after the newer one
     * that replaced it and finds nothing behind it to supersede — leaving a house's entire
     * revision history filed as a dozen simultaneously-open calls. That would inflate "still
     * running", deflate the revision rate, and let one call be counted many times in a hit rate.
     *
     * <p>Idempotent, and it <b>never touches a resolved row</b>: a call that reached or missed its
     * target did so before it was revised, and rewriting it as superseded erases a real outcome
     * from the record (§49.2).
     *
     * @return how many rows were newly marked superseded
     */
    public int reconcileSupersessions() {
        Map<String, List<AnalystTargetEntity>> byHouse = new HashMap<>();
        for (AnalystTargetEntity row : repository.findAll()) {
            if (row.getSymbol() == null || row.getBrokerage() == null || row.getIssuedOn() == null) continue;
            byHouse.computeIfAbsent(row.getSymbol() + "|" + row.getBrokerage(), k -> new ArrayList<>()).add(row);
        }

        int changed = 0;
        for (List<AnalystTargetEntity> calls : byHouse.values()) {
            if (calls.size() < 2) continue;
            calls.sort(Comparator.comparing(AnalystTargetEntity::getIssuedOn));
            AnalystTargetEntity latest = calls.get(calls.size() - 1);

            for (int i = 0; i < calls.size() - 1; i++) {
                AnalystTargetEntity older = calls.get(i);
                String status = older.getStatus();
                boolean open = AnalystTargetStatus.PENDING.name().equals(status)
                        || AnalystTargetStatus.UNPRICED.name().equals(status);
                if (!open) continue;

                AnalystTargetEntity replacement = calls.get(i + 1);
                older.setStatus(AnalystTargetStatus.SUPERSEDED.name());
                older.setSupersededByTargetId(replacement.getId());
                older.setSupersededOn(replacement.getIssuedOn());
                try {
                    repository.save(older);
                    changed++;
                } catch (Exception e) {
                    log.debug("Analyst ledger: could not supersede {} #{}: {}",
                            older.getSymbol(), older.getId(), e.getMessage());
                }
            }
            log.trace("Analyst ledger: {} calls on {} by {}, latest {}",
                    calls.size(), latest.getSymbol(), latest.getBrokerage(), latest.getIssuedOn());
        }
        log.info("Analyst ledger: supersession reconcile marked {} earlier open calls as revised "
                + "across {} house/stock pairs", changed, byHouse.size());
        return changed;
    }

    /** Same house, same stock, same target to the rupee — one call reported twice. */
    private static boolean sameTarget(Double a, double b) {
        return a != null && Math.abs(a - b) < 0.5;
    }

    static String dedupKey(String symbol, String brokerage, double target, LocalDate issuedOn) {
        return symbol + "|" + brokerage + "|" + Math.round(target) + "|" + issuedOn;
    }

    /**
     * RSS publication date.
     *
     * <p>Null on anything unparseable, deliberately: falling back to today would file a call made
     * a week ago as made this morning, and every days-to-target measured from it would be wrong
     * in the flattering direction (Gotcha 100's rule).
     */
    static LocalDate parseRssDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (DateTimeFormatter f : RSS_FORMATS) {
            try {
                return ZonedDateTime.parse(raw.trim(), f).toLocalDate();
            } catch (Exception ignored) {
                // try the next spelling
            }
        }
        return null;
    }

    private static String qualify(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        return symbol.contains(":") ? symbol : "NSE:" + symbol;
    }

    private static String companyNameFor(String symbol) {
        UniverseSectors.Entry e = UniverseSectors.entryFor(
                symbol == null ? null : symbol.substring(symbol.indexOf(':') + 1));
        return e == null ? null : e.name();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** How stale the ledger is, for the freshness strip and the data-health screen. */
    public Duration sinceLastCapture() {
        LocalDateTime last = repository.findLatestCreatedAt();
        return last == null ? null : Duration.between(last, LocalDateTime.now());
    }
}
