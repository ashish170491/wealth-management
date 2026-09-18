package com.example.trading.earnings;

import com.example.trading.ai.NseDataService;
import com.example.trading.holdings.SymbolVariants;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Persists filed quarterly results and reads them back (SPEC.md §50).
 *
 * <p>The rules live in {@link QuarterlyResultRead} and {@link EarningsCalendar}, both pure. This
 * class does the I/O and nothing else — the same split as {@code ScreeningCoverageService} and
 * for the same reason: a rule that can only be exercised through a database is a rule nobody
 * tests.
 *
 * <h2>Capture costs nothing</h2>
 * {@link #capture} takes figures the caller <em>already has</em>. The 14:00 screening fetches
 * these filings for the earnings-growth bonus and {@code NseDataService} caches them for 30
 * minutes, so persisting them adds <b>zero NSE requests</b> and needs no scheduler of its own
 * (SPEC §3.4, Gotcha 28). This feature is not new data; it is the app keeping what it was already
 * throwing away.
 *
 * <p>Capture never throws into its caller. A measurement of a screening run must not be able to
 * break the run — but a failure is logged at WARN naming what the absence will be mistaken for
 * (Gotcha 52), because a silently missing quarter reads later as "this company did not report".
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QuarterlyResultService {

    private final QuarterlyResultRepository repository;
    private final NseDataService nseDataService;
    private final EarningsConfig config;

    private static final String SOURCE_INTEGRATED_FILING = "INTEGRATED_FILING";

    @PostConstruct
    void registerCoverage() {
        QuarterlyResultCoverage.register(() -> new HashSet<>(repository.findCoveredSymbols()));
        log.info("Quarterly result tracking active (SPEC 50): capture during screening={}, "
                        + "new-result window={} days. Contributes zero points to any score.",
                config.isCaptureDuringScreening(), config.getNewResultWindowDays());
    }

    // ------------------------------------------------------------------ capture

    /**
     * Persist the quarters already fetched for {@code symbol}. Never throws.
     *
     * @return how many rows were written or updated
     */
    @Transactional
    public int capture(String symbol, List<NseDataService.QuarterlyResult> fetched) {
        String key = SymbolVariants.base(symbol);
        if (key == null || key.isBlank() || fetched == null || fetched.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (NseDataService.QuarterlyResult q : fetched) {
            try {
                if (upsert(key, q)) written++;
            } catch (Exception e) {
                // Per row, so one unreadable quarter never loses the other seven — and never
                // rolls back a run that has already done real work (B-116's lesson).
                log.warn("Quarterly capture: {} {} not stored ({}). That quarter will read as "
                                + "'not reported', which is not what it means.",
                        key, q.getPeriod(), e.getMessage());
            }
        }
        if (written > 0) QuarterlyResultCoverage.invalidate();
        return written;
    }

    /**
     * Fetch from NSE and persist. Live calls — only ever from an explicit endpoint, never from a
     * page load.
     */
    @Transactional
    public int captureLive(String symbol) {
        String bare = SymbolVariants.base(symbol);
        return capture(bare, nseDataService.fetchQuarterlyResults(bare));
    }

    /**
     * Insert or merge one quarter.
     *
     * <h3>Field-by-field, never row-by-row</h3>
     * A field NSE did not tag in this fetch arrives null, and an unconditional setter would erase
     * a figure a previous fetch got right — permanently, and invisibly. That is B-046 exactly, and
     * it cost this codebase two forensic checks that reported "not measured" for every stock while
     * presenting themselves as completed screens.
     *
     * @return true when a row was created or a stored value actually changed
     */
    private boolean upsert(String symbol, NseDataService.QuarterlyResult q) {
        LocalDate quarterEnd = q.getQuarterEnd();
        if (quarterEnd == null) {
            // A quarter with no end date has no key and cannot be compared with anything.
            log.debug("Quarterly capture: {} filing has no parseable quarter end; skipped.", symbol);
            return false;
        }
        Optional<QuarterlyResultEntity> existing =
                repository.findBySymbolAndQuarterEnd(symbol, quarterEnd);
        QuarterlyResultEntity row = existing.orElseGet(() -> QuarterlyResultEntity.builder()
                .symbol(symbol)
                .quarterEnd(quarterEnd)
                .firstSeenAt(LocalDateTime.now())
                .build());

        boolean wasRevised = Boolean.TRUE.equals(row.getRevised());

        row.setFiscalLabel(FiscalQuarter.label(quarterEnd));
        row.setSource(SOURCE_INTEGRATED_FILING);

        row.setRevenue(keep(q.getRevenue(), row.getRevenue()));
        row.setProfit(keep(q.getProfit(), row.getProfit()));
        row.setEps(keep(q.getEps(), row.getEps()));
        row.setOperatingProfit(keep(q.getOperatingProfit(), row.getOperatingProfit()));
        row.setOperatingMargin(keep(q.getOperatingMargin(), row.getOperatingMargin()));
        row.setNetMargin(keep(q.getNetMargin(), row.getNetMargin()));
        row.setGrossMargin(keep(q.getGrossMargin(), row.getGrossMargin()));
        row.setFinanceCost(keep(q.getFinanceCost(), row.getFinanceCost()));
        row.setDepreciation(keep(q.getDepreciation(), row.getDepreciation()));
        row.setTax(keep(q.getTax(), row.getTax()));
        row.setTotalExpenses(keep(q.getTotalExpenses(), row.getTotalExpenses()));

        if (q.getConsolidated() != null) row.setConsolidated(q.getConsolidated());
        if (q.getAudited() != null) row.setAudited(q.getAudited());
        if (q.getFilingSeqId() != null) row.setFilingSeqId(trim(q.getFilingSeqId(), 32));
        if (q.getRevisionRemark() != null) row.setRevisionRemark(trim(q.getRevisionRemark(), 500));
        if (q.getRevised() != null) row.setRevised(q.getRevised());

        applyAvailability(row, q.getAvailableFrom(), quarterEnd);

        // A restatement is news in its own right — the company has changed figures it already
        // published. Clearing the announcement stamp surfaces it once more, and only on the
        // transition, so a row that was already known to be revised is not re-announced every run.
        if (!wasRevised && Boolean.TRUE.equals(row.getRevised()) && row.getAnnouncedAt() != null) {
            row.setAnnouncedAt(null);
        }

        repository.save(row);
        return true;
    }

    /**
     * Record when these figures became public, and whether that date is filed or assumed.
     *
     * <h3>Why the estimate is the regulatory deadline and not more</h3>
     * {@code annual_fundamentals} estimates five months against a 60-day rule, deliberately late,
     * because erring early is look-ahead bias (Gotcha 100). The same instinct applied here would
     * be wrong: quarter ends are about 91 days apart and filings land around day 45, so an
     * estimate much past the deadline would place one quarter's assumed publication <em>after</em>
     * the next quarter's real one and silently reorder the series. So the estimate sits exactly on
     * SEBI's deadline — already later than almost every company files, and unable to leapfrog.
     *
     * <p>A filed date always replaces an estimate; an estimate never replaces a filed date.
     */
    static void applyAvailability(QuarterlyResultEntity row, LocalDate broadcast, LocalDate quarterEnd) {
        if (broadcast != null) {
            row.setAvailableFrom(broadcast);
            row.setAvailableFromEstimated(false);
            return;
        }
        boolean haveFiled = row.getAvailableFrom() != null
                && Boolean.FALSE.equals(row.getAvailableFromEstimated());
        if (haveFiled) return;
        int deadline = quarterEnd.getMonthValue() == 3
                ? EarningsCalendar.ANNUAL_DEADLINE_DAYS : EarningsCalendar.QUARTERLY_DEADLINE_DAYS;
        row.setAvailableFrom(quarterEnd.plusDays(deadline));
        row.setAvailableFromEstimated(true);
    }

    // ------------------------------------------------------------------ read model (DB-only)

    /**
     * Everything one screen needs about a company's results.
     *
     * @param symbolAnswered which spelling carried the rows, so a reading can be traced (Gotcha 84)
     */
    public record Reading(QuarterlyResultRead.Result result,
                          EarningsCalendar.Expectation expectation,
                          List<QuarterlyResultEntity> quarters,
                          String symbolAnswered) {
    }

    /** One company. Never 404s into a null — an absence is a {@code NOT_MEASURED} reading. */
    public Reading forSymbol(String symbol) {
        String key = SymbolVariants.base(symbol);
        List<QuarterlyResultEntity> rows = key == null ? List.of()
                : repository.findBySymbolOrderByQuarterEndDesc(key);
        return build(symbol, key, rows);
    }

    /**
     * Several companies in one query.
     *
     * <p>Keyed by the caller's own spelling so a portfolio row can look itself up, while the query
     * runs on bare symbols. Per-row lookups here would be roughly 120 queries on a page load,
     * which is the shape B-115 made the dashboard time out on.
     */
    public Map<String, Reading> forSymbols(List<String> symbols) {
        Map<String, Reading> out = new LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty()) return out;

        List<String> keys = symbols.stream()
                .map(SymbolVariants::base)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        if (keys.isEmpty()) return out;

        Map<String, List<QuarterlyResultEntity>> bySymbol = new LinkedHashMap<>();
        for (QuarterlyResultEntity row : repository.findForSymbols(keys)) {
            bySymbol.computeIfAbsent(row.getSymbol(), k -> new ArrayList<>()).add(row);
        }
        for (String original : symbols) {
            String key = SymbolVariants.base(original);
            out.put(original, build(original, key, bySymbol.getOrDefault(key, List.of())));
        }
        return out;
    }

    private Reading build(String original, String key, List<QuarterlyResultEntity> rows) {
        if (rows.isEmpty()) {
            return new Reading(
                    QuarterlyResultRead.notMeasured(original,
                            "No quarterly result has been captured for this company yet — that is a "
                                    + "gap in what the app has collected, not a statement that the "
                                    + "company has not reported."),
                    EarningsCalendar.notMeasured(),
                    List.of(),
                    null);
        }
        List<QuarterlyResultEntity> capped = rows.size() > config.getMaxQuartersPerSymbol()
                ? rows.subList(0, config.getMaxQuartersPerSymbol()) : rows;
        return new Reading(
                QuarterlyResultRead.of(original, rows),
                EarningsCalendar.next(rows, LocalDate.now()),
                List.copyOf(capped),
                key);
    }

    /** Results published in the last {@code days} days, newest publication first. DB-only. */
    public List<QuarterlyResultEntity> recentlyPublished(int days) {
        List<QuarterlyResultEntity> rows =
                repository.findPublishedSince(LocalDate.now().minusDays(Math.max(1, days)));
        return rows.size() > config.getRecentLimit() ? rows.subList(0, config.getRecentLimit()) : rows;
    }

    /**
     * Results for these companies that landed recently and have never been reported to the
     * investor — the daily email's candidates.
     *
     * <p>The dedup is persisted rather than held in memory, because the alternative is what §6.4
     * lists as a known gap: the same quarter re-announced every day for six weeks, which trains
     * the reader to skip the section that matters.
     */
    public List<QuarterlyResultEntity> newResultsFor(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return List.of();
        List<String> keys = symbols.stream()
                .map(SymbolVariants::base)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        if (keys.isEmpty()) return List.of();
        return repository.findUnannouncedSince(
                LocalDate.now().minusDays(config.getNewResultWindowDays()), keys);
    }

    /** Stamp rows as reported, so the next email does not repeat them. */
    @Transactional
    public void markAnnounced(List<QuarterlyResultEntity> rows) {
        if (rows == null || rows.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        for (QuarterlyResultEntity row : rows) {
            row.setAnnouncedAt(now);
        }
        repository.saveAll(rows);
    }

    /** The newest publication date on file, for the freshness strip. */
    public Optional<LocalDate> latestPublished() {
        return repository.findLatestAvailableFrom();
    }

    /** How many companies have at least one captured quarter. */
    public int coveredCompanies() {
        return repository.findCoveredSymbols().size();
    }

    public Set<String> coveredSymbols() {
        return new HashSet<>(repository.findCoveredSymbols());
    }

    // ------------------------------------------------------------------ helpers

    /** Incoming value when present, otherwise whatever is already stored (B-046). */
    private static Double keep(Double incoming, Double stored) {
        return incoming != null ? incoming : stored;
    }

    /**
     * Truncate to the column width.
     *
     * <p>B-116: a third party's free text does not fit a column sized from today's feed, and the
     * width is only half the fix — the other half is not trusting the width.
     */
    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
