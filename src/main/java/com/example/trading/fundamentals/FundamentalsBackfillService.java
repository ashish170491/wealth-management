package com.example.trading.fundamentals;

import com.example.trading.multibagger.MultibaggerScreenerService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives the universe-wide annual-history backfill to convergence, one small batch at a time
 * (SPEC §32.6).
 *
 * <h2>Why this exists</h2>
 * Every long-horizon judgement this platform wants to make — whether a business has earned well
 * on capital for eight years, what management did with a decade of retained earnings, whether
 * margins survived a cycle — is computed from {@code annual_fundamentals}. That table held
 * multi-year history for roughly <b>19 of 288</b> screened stocks. The single-symbol and
 * holdings backfills existed and covered the portfolio; nothing covered the universe the screener
 * actually searches.
 *
 * <h2>Why a batch rather than one long run</h2>
 * The universe is ~370 symbols at ~11 NSE requests each: about <b>4,000 requests</b>, over an
 * hour of paced wall clock. That does not fit the Saturday window, which already carries a full
 * screening run plus a measured 11-22 minute coarse scan and must finish before the 09:00 report.
 * Thirty symbols a weekday converges in roughly thirteen runs, and after that the cost is
 * ~zero: {@code recordFromXbrl} maintains the newest year on every screening run (Gotcha 49).
 *
 * <h2>The two things that make it converge</h2>
 * <ol>
 *   <li><b>Terminal states are terminal.</b> COMPLETE and UNAVAILABLE are never re-queued. A
 *       company that has only ever filed three annual results is finished at three, and a picker
 *       that ordered purely by "fewest years held" would otherwise return to it every single day
 *       and never reach the symbols nobody has touched.</li>
 *   <li><b>A failure is bounded and dated.</b> FAILED retries after a cool-off, at most
 *       {@code maxAttempts} times, so one permanently broken symbol cannot consume the budget
 *       indefinitely.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundamentalsBackfillService {

    private final FundamentalsHistoryService historyService;
    private final FundamentalsBackfillStatusRepository statusRepository;
    private final AnnualFundamentalsRepository annualRepository;
    private final MultibaggerScreenerService screenerService;
    private final HoldingsRepository holdingsRepository;
    private final FundamentalsBackfillConfig config;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** What one batch did. Attempted is reported alongside written — a bare success count hides a partial failure (B-026). */
    public record BatchResult(int symbolsAttempted, int yearsWritten, int completed, int partial,
                              int unavailable, int failed, boolean stoppedOnDeadline,
                              int remainingPending, List<String> symbols) {
    }

    /**
     * Run one batch.
     *
     * <p>Stops early when {@code stopAfter} passes. That deadline is checked <b>inside</b> the
     * loop, not only at entry: a batch that started legitimately can still run into the 14:00
     * screening, and the whole point of the guard is to avoid contending with it for the single
     * NSE session.
     */
    public BatchResult runBatch(int limit) {
        List<String> queue = nextSymbols(limit);
        int written = 0, completed = 0, partial = 0, unavailable = 0, failed = 0;
        boolean stopped = false;
        List<String> done = new ArrayList<>();

        for (String symbol : queue) {
            if (pastDeadline()) {
                stopped = true;
                log.info("Fundamentals backfill: stopping at the {} deadline with {} of {} symbols "
                        + "done — the rest stay PENDING and are picked up by the next batch",
                        config.getStopAfter(), done.size(), queue.size());
                break;
            }
            FundamentalsBackfillStatusEntity row = statusFor(symbol);
            row.setLastAttemptAt(LocalDateTime.now());
            row.setAttempts(row.getAttempts() == null ? 1 : row.getAttempts() + 1);
            try {
                FundamentalsHistoryService.BackfillOutcome outcome =
                        historyService.backfillDetailed(symbol, config.getMaxYears());
                written += outcome.yearsWritten();
                row.setYearsInArchive(outcome.listedYears());
                row.setYearsWritten(outcome.yearsWritten());
                row.setYearsSkippedBasis(outcome.mixedBasisSkipped());
                row.setNonMarchSkipped(outcome.nonMarchSkipped());
                row.setConsolidatedBasis(outcome.consolidatedBasis());
                row.setLastError(null);

                if (outcome.listingFailed()) {
                    row.setStatus(exhausted(row)
                            ? FundamentalsBackfillStatusEntity.UNAVAILABLE
                            : FundamentalsBackfillStatusEntity.FAILED);
                    row.setLastError("Archive listing call failed");
                    failed++;
                } else if (outcome.listedYears() == 0) {
                    // A finding about the company, not about us: the archive genuinely lists no
                    // annual filing under this symbol. The CSV import is the fallback, and this
                    // status is what tells the investor which names need it.
                    row.setStatus(FundamentalsBackfillStatusEntity.UNAVAILABLE);
                    row.setLastError(outcome.nonMarchSkipped() > 0
                            ? outcome.nonMarchSkipped() + " filings exist but none has a 31-March "
                                    + "year end, so none can join a comparable annual series"
                            : "No annual filings listed in the NSE archive for this symbol");
                    unavailable++;
                } else if (outcome.complete()) {
                    row.setStatus(FundamentalsBackfillStatusEntity.COMPLETE);
                    completed++;
                } else {
                    row.setStatus(exhausted(row)
                            ? FundamentalsBackfillStatusEntity.COMPLETE
                            : FundamentalsBackfillStatusEntity.PARTIAL);
                    partial++;
                }
            } catch (Exception e) {
                row.setStatus(exhausted(row)
                        ? FundamentalsBackfillStatusEntity.UNAVAILABLE
                        : FundamentalsBackfillStatusEntity.FAILED);
                row.setLastError(truncate(e.getMessage()));
                failed++;
                log.warn("Fundamentals backfill: {} failed on attempt {} — {}",
                        symbol, row.getAttempts(), e.getMessage());
            }
            statusRepository.save(row);
            done.add(symbol);
        }

        int remaining = Math.max(0, pendingSymbols().size());
        log.info("Fundamentals backfill batch: {} symbols, {} years written "
                        + "({} complete, {} partial, {} unavailable, {} failed); {} still pending",
                done.size(), written, completed, partial, unavailable, failed, remaining);
        return new BatchResult(done.size(), written, completed, partial, unavailable, failed,
                stopped, remaining, done);
    }

    /**
     * A symbol whose attempts are used up stops being retried.
     *
     * <p>It settles into a terminal state rather than staying FAILED forever, because a FAILED
     * row that can never be retried is indistinguishable from one that is about to be, and the
     * coverage report would carry it as outstanding work that will never happen.
     */
    private boolean exhausted(FundamentalsBackfillStatusEntity row) {
        return row.getAttempts() != null && row.getAttempts() >= config.getMaxAttempts();
    }

    /** Symbols still owing work, neediest first. */
    /**
     * The next symbols to backfill: <b>the investor's own holdings first</b>, then the shallowest.
     *
     * <p>Holdings lead because they are the stocks actually looked at. Ordering by depth alone
     * meant the queue was walked essentially alphabetically (ATGL, ATUL, ATULAUTO, AUBANK...),
     * so a holding could sit unbackfilled for a fortnight while the compounding lens (SPEC §41)
     * and every multi-year check read "not measured" on the portfolio page - the one screen the
     * investor opens daily. Measured on the live portfolio: 5 of 31 holdings had no annual row
     * at all while 216 symbols were still queued ahead of them.
     *
     * <p>Within each group the shallowest go first, so a symbol with nothing beats one with
     * three years. A holding's exchange prefix is stripped and re-qualified to NSE because the
     * archive and {@code annual_fundamentals} are keyed on the NSE symbol (Gotcha 84) - 22 of 33
     * holdings are BSE-prefixed and would otherwise never match a pending entry.
     */
    public List<String> nextSymbols(int limit) {
        List<String> pending = pendingSymbols();
        Map<String, Long> depth = yearsBySymbol();
        Set<String> held = heldSymbols();
        pending.sort(Comparator.comparing((String s) -> held.contains(s) ? 0 : 1)
                .thenComparingLong(s -> depth.getOrDefault(s, 0L))
                .thenComparing(Comparator.naturalOrder()));
        return pending.size() > limit ? new ArrayList<>(pending.subList(0, limit)) : pending;
    }

    /**
     * Active holdings as NSE-qualified symbols. Empty on failure, which degrades to the old
     * depth-only ordering rather than stopping the batch - a prioritisation that cannot be
     * computed is a worse queue, not a broken one.
     */
    private Set<String> heldSymbols() {
        Set<String> out = new HashSet<>();
        try {
            for (HoldingsEntity h : holdingsRepository.findActive()) {
                String symbol = h.getSymbol();
                if (symbol == null || symbol.isBlank()) continue;
                int colon = symbol.indexOf(':');
                String bare = colon >= 0 ? symbol.substring(colon + 1) : symbol;
                if (!bare.isBlank()) out.add("NSE:" + bare.trim());
            }
        } catch (Exception e) {
            log.warn("Fundamentals backfill: could not read holdings, so the queue falls back to "
                    + "depth order and a held stock may wait behind the rest of the universe: {}",
                    e.getMessage());
        }
        return out;
    }

    private List<String> pendingSymbols() {
        Set<String> settled = new HashSet<>(statusRepository.findSettledSymbols());
        Map<String, FundamentalsBackfillStatusEntity> bySymbol = new HashMap<>();
        for (FundamentalsBackfillStatusEntity s : statusRepository.findAll()) {
            bySymbol.put(s.getSymbol(), s);
        }
        LocalDateTime retryBefore = LocalDateTime.now().minusDays(config.getRetryAfterDays());

        List<String> out = new ArrayList<>();
        for (String symbol : screenerService.resolvedScreeningUniverse()) {
            if (settled.contains(symbol)) continue;
            FundamentalsBackfillStatusEntity s = bySymbol.get(symbol);
            if (s == null) {
                out.add(symbol);
                continue;
            }
            if (exhausted(s)) continue;
            boolean cooledOff = s.getLastAttemptAt() == null || s.getLastAttemptAt().isBefore(retryBefore);
            boolean neverRun = s.getStatus() == null
                    || FundamentalsBackfillStatusEntity.PENDING.equals(s.getStatus());
            if (neverRun || cooledOff) out.add(symbol);
        }
        return out;
    }

    private FundamentalsBackfillStatusEntity statusFor(String symbol) {
        return statusRepository.findBySymbol(symbol)
                .orElseGet(() -> FundamentalsBackfillStatusEntity.builder()
                        .symbol(symbol)
                        .status(FundamentalsBackfillStatusEntity.PENDING)
                        .attempts(0)
                        .build());
    }

    private Map<String, Long> yearsBySymbol() {
        Map<String, Long> out = new HashMap<>();
        for (Object[] r : annualRepository.countYearsBySymbol()) {
            if (r.length >= 2 && r[0] != null && r[1] != null) {
                out.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
            }
        }
        return out;
    }

    /**
     * End of the daily contention window - the app's scheduled shutdown. After this the
     * NSE-heavy afternoon jobs cannot fire, so there is nothing left to yield to.
     */
    private static final LocalTime CONTENTION_END = LocalTime.of(15, 45);

    /**
     * True while this batch must not issue NSE requests.
     *
     * <p><b>Bounded at both ends (B-091).</b> The guard exists to keep the backfill out of the
     * way of the 14:00 screening and the 14:45 insider capture, which share the single NSE
     * session - so what it must block is that <i>contention window</i>, not all of time after
     * {@code stop-after}. Written open-ended, it also refused every evening, night and weekend
     * run, when nothing whatsoever is competing: a manual catch-up after a missed 11:30 fire
     * silently did nothing and reported a successful batch of zero symbols.
     *
     * <p>This is B-082's lesson in mirror image. There the NSE-crunch guard checked only the
     * <i>start</i> time and so let a run sail into the window; here it checked only that the
     * window had begun and never that it had ended. Both come from writing a guard for an app
     * that only ran during market hours. The reason for the guard is contention, so the guard
     * covers exactly the contention (Gotcha 97, Gotcha 101).
     */
    boolean pastDeadline() {
        try {
            LocalTime stop = LocalTime.parse(config.getStopAfter());
            return inContentionWindow(ZonedDateTime.now(IST).toLocalTime(), stop);
        } catch (Exception e) {
            // An unparseable deadline must not silently mean "no deadline" — that would turn the
            // guard off exactly when someone has fat-fingered the config.
            log.warn("Fundamentals backfill: unreadable stop-after '{}', treating the batch as "
                    + "past its deadline rather than running unbounded", config.getStopAfter());
            return true;
        }
    }

    /** The rule itself, separated from the clock so it can be tested (B-091). */
    static boolean inContentionWindow(LocalTime now, LocalTime stop) {
        return now.isAfter(stop) && now.isBefore(CONTENTION_END);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 480 ? s : s.substring(0, 480);
    }

    // ---------------------------------------------------------------- coverage report

    /**
     * How deep the universe is, and what is left to do.
     *
     * <p>This is the instrument for the rollout (plan §9). Composites will move while it runs, in
     * both directions, as forensic checks that need 3-4 years start reporting for the first time.
     * That is correct behaviour and it needs to be attributable rather than mysterious, so the
     * depth histogram is queryable rather than inferred from a log.
     */
    @Data
    @Builder
    public static class CoverageReport {
        private int universeSize;
        private int symbolsWithAnyHistory;
        private int atLeast3Years;
        private int atLeast4Years;
        private int atLeast5Years;
        private int atLeast8Years;
        private double medianYears;
        /** Percent of the universe deep enough for the turnaround detector and the B-066 discriminator. */
        private double forensicReadyPercent;
        private Map<String, Long> byStatus;
        private int pendingSymbols;
        private List<String> unavailableSymbols;
        private String note;
    }

    public CoverageReport coverage() {
        List<String> universe = screenerService.resolvedScreeningUniverse();
        Map<String, Long> depth = yearsBySymbol();

        int with = 0, y3 = 0, y4 = 0, y5 = 0, y8 = 0;
        List<Long> counts = new ArrayList<>();
        for (String symbol : universe) {
            long n = depth.getOrDefault(symbol, 0L);
            counts.add(n);
            if (n > 0) with++;
            if (n >= 3) y3++;
            if (n >= 4) y4++;
            if (n >= 5) y5++;
            if (n >= 8) y8++;
        }
        counts.sort(Comparator.naturalOrder());
        double median = counts.isEmpty() ? 0 : counts.get(counts.size() / 2);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] r : statusRepository.countByStatus()) {
            if (r.length >= 2 && r[0] != null) byStatus.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        }
        List<String> unavailable = statusRepository
                .findByStatus(FundamentalsBackfillStatusEntity.UNAVAILABLE).stream()
                .map(FundamentalsBackfillStatusEntity::getSymbol).sorted().toList();

        int universeSize = Math.max(1, universe.size());
        return CoverageReport.builder()
                .universeSize(universe.size())
                .symbolsWithAnyHistory(with)
                .atLeast3Years(y3).atLeast4Years(y4).atLeast5Years(y5).atLeast8Years(y8)
                .medianYears(median)
                .forensicReadyPercent(y4 * 100.0 / universeSize)
                .byStatus(byStatus)
                .pendingSymbols(pendingSymbols().size())
                .unavailableSymbols(unavailable)
                .note("Depth is what every long-horizon check is gated on: 3 years for the "
                        + "receivables and cash-conversion forensic flags, 4 for the turnaround "
                        + "detector and for telling a bonus issue apart from dilution (B-066), "
                        + "5+ before any persistence claim is made. A symbol listed as "
                        + "unavailable is a finding, not a failure — the archive has no annual "
                        + "filing for it, and the CSV import is the fallback.")
                .build();
    }

    /** Today's date in IST, for callers that report when a batch ran. */
    public LocalDate today() {
        return LocalDate.now(IST);
    }
}
