package com.example.trading.funds;

import com.example.trading.funds.amfi.AmfiNavClient;
import com.example.trading.funds.amfi.AmfiNavParser;
import com.example.trading.funds.amfi.AmfiSchemeRow;
import com.example.trading.funds.MutualFundHistoryFetchEntity.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the month-end NAV series the measurement lens needs (SPEC §52.7).
 *
 * <p>Bounded, resumable and paced. A pass does at most {@code history-batch-months} months, records
 * what each one concluded, and stops; the next pass resumes where it left off. That shape is not
 * politeness about runtime - it is what lets ~100 requests against a third party's free endpoint be
 * spread out rather than hammered, and what stops one bad month costing a whole run.
 *
 * <p><b>Four things here were learned by running it rather than by reading the endpoint.</b>
 *
 * <ol>
 *   <li><b>A weekend month end returns an almost empty file</b> - 481 rows and 8 equity schemes for
 *       {@code 30-Jun-2018}. See {@link MonthEndCalendar}.</li>
 *   <li><b>A weekday month end can be short and still be good</b> - {@code 30-Apr-2018} is missing
 *       6,000 debt rows and has a complete equity cross-section. See {@link NavFileAcceptance}.</li>
 *   <li><b>Each month is committed on its own.</b> {@code IpoTrackingService} ended an expensive
 *       paced run with a single {@code saveAll}, so one over-long string rolled back 255 good rows
 *       and four minutes of fetching (B-116). Here a month that throws is recorded {@code FAILED}
 *       and the pass carries on.</li>
 *   <li><b>A scheme discovered only in the archive is stored, not skipped.</b> It is precisely a
 *       scheme that has since wound up or merged, and that is the evidence the survivorship ledger
 *       is made of (SPEC §52.9, §25.1). Its <em>current</em> fields are never overwritten by a
 *       historical row, so a 2018 category can never displace today's.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmfiHistoryBackfillService {

    private final AmfiNavClient client;
    private final MutualFundConfig config;
    private final MutualFundSchemeRepository schemeRepository;
    private final MutualFundNavRepository navRepository;
    private final MutualFundHoldingRepository holdingRepository;
    private final MutualFundHistoryFetchRepository fetchRepository;

    /**
     * @param monthsFetched      months that yielded an equity cross-section
     * @param monthsNotPublished months where every candidate business day was silent - a finding
     * @param monthsFailed       months whose request did not succeed - the only real gap
     * @param monthsRemaining    months still with no settled row after this pass
     */
    public record BackfillResult(
            int monthsAttempted,
            int monthsFetched,
            int monthsNotPublished,
            int monthsFailed,
            int navRowsWritten,
            int schemesDiscovered,
            int monthsRemaining,
            List<String> detail
    ) {}

    /** Months from the configured start to the last <em>complete</em> month, oldest first. */
    public List<YearMonth> allMonths(LocalDate today) {
        YearMonth start = YearMonth.parse(config.getHistoryStartMonth());
        YearMonth end = YearMonth.from(today).minusMonths(1);
        return MonthEndCalendar.monthsBetween(start, end);
    }

    /** Months with no row, or whose only row records a failed request. Oldest first. */
    public List<YearMonth> pendingMonths(LocalDate today) {
        return pendingMonths(today, false);
    }

    /**
     * @param force re-attempt months already settled as FETCHED or NOT_PUBLISHED
     *
     * <p>{@code force} exists for one situation and it has already happened once: the parser that
     * read those months was wrong, so what is on file is not what a correct pass would have
     * written (B-130). It is the same reason {@code POST /api/learning/shadow/backfill?force=true}
     * exists after a variant-set change. Re-fetching is safe - a NAV point is keyed on
     * (scheme, date) and an existing one is skipped - so the worst a forced pass costs is time.
     */
    public List<YearMonth> pendingMonths(LocalDate today, boolean force) {
        Map<LocalDate, Status> known = fetchRepository.findAll().stream()
                .collect(Collectors.toMap(MutualFundHistoryFetchEntity::getForMonth,
                        MutualFundHistoryFetchEntity::getStatus, (a, b) -> a));
        List<YearMonth> out = new ArrayList<>();
        for (YearMonth m : allMonths(today)) {
            Status s = known.get(m.atDay(1));
            // FAILED is retried; FETCHED and NOT_PUBLISHED are settled. Retrying a settled month
            // for ever is the "shallow but complete" trap that made the annual backfill return to
            // the same symbols every day and never reach the untouched ones (Gotcha 102).
            if (force || s == null || s == Status.FAILED) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Run one bounded pass.
     *
     * @param limit months to attempt, or null for the configured batch size
     */
    public BackfillResult backfill(Integer limit, LocalDate today) {
        return backfill(limit, false, today);
    }

    public BackfillResult backfill(Integer limit, boolean force, LocalDate today) {
        int batch = limit != null && limit > 0 ? limit : config.getHistoryBatchMonths();
        List<YearMonth> todo = pendingMonths(today, force).stream().limit(batch).toList();

        Set<String> ownedIsins = ownedIsins();
        int fetched = 0;
        int notPublished = 0;
        int failed = 0;
        int navRows = 0;
        int discovered = 0;
        List<String> detail = new ArrayList<>();

        for (YearMonth month : todo) {
            MonthOutcome o;
            try {
                o = doMonth(month, today, ownedIsins);
            } catch (Exception e) {
                // Never let one month end the pass. Recorded FAILED - a request that did not
                // succeed is never a fact about the market (B-054).
                log.warn("AMFI history backfill failed for {}: {}", month, e.getMessage());
                recordMonth(month, null, Status.FAILED, 0, null, null, 0, e.getMessage());
                failed++;
                detail.add(month + ": FAILED - " + e.getMessage());
                continue;
            }
            switch (o.status()) {
                case FETCHED -> {
                    fetched++;
                    navRows += o.navRowsWritten();
                    discovered += o.schemesDiscovered();
                    detail.add(month + ": " + o.dateUsed() + ", " + o.equityRows()
                            + " equity schemes, " + o.navRowsWritten() + " NAV points"
                            + (o.datesTried() > 1 ? " (walked back " + (o.datesTried() - 1) + ")" : "")
                            // Named on every line, because a layout that was assumed rather than
                            // read is what made B-130 look like a clean run.
                            + (o.layoutAssumed() ? " [COLUMN LAYOUT ASSUMED - see B-130]" : ""));
                }
                case NOT_PUBLISHED -> {
                    notPublished++;
                    detail.add(month + ": no business day published an equity cross-section");
                }
                case FAILED -> {
                    failed++;
                    detail.add(month + ": FAILED - " + o.note());
                }
            }
        }

        int remaining = pendingMonths(today).size();
        log.info("AMFI history backfill: {} months attempted, {} fetched, {} not published, "
                        + "{} failed, {} NAV points, {} schemes discovered, {} months still pending",
                todo.size(), fetched, notPublished, failed, navRows, discovered, remaining);
        return new BackfillResult(todo.size(), fetched, notPublished, failed, navRows, discovered,
                remaining, detail);
    }

    private record MonthOutcome(Status status, LocalDate dateUsed, int datesTried, int equityRows,
                                int navRowsWritten, int schemesDiscovered, boolean layoutAssumed,
                                String note) {}

    private MonthOutcome doMonth(YearMonth month, LocalDate today, Set<String> ownedIsins) {
        List<LocalDate> candidates = MonthEndCalendar.candidates(month, today);
        int tried = 0;
        NavFileAcceptance lastRejection = null;

        for (LocalDate d : candidates) {
            tried++;
            pace();
            String body = client.fetchNavHistory(d);
            AmfiNavParser.ParseResult parsed = AmfiNavParser.parse(body);

            int equityRows = (int) parsed.rows().stream()
                    .filter(r -> r.category().assetClass() == AssetClass.EQUITY)
                    .count();
            NavFileAcceptance verdict = NavFileAcceptance.assess(parsed.rowCount(), equityRows);

            if (!verdict.acceptable()) {
                lastRejection = verdict;
                log.debug("AMFI history {} rejected: {}", d, verdict.reason());
                continue;
            }

            boolean assumed = parsed.layout().source().startsWith("assumed");
            if (assumed) {
                log.warn("AMFI history {} carried no column header - field positions were assumed. "
                        + "That is how B-130 wrote four NAV points while reporting success.", d);
            }
            Written w = persistMonth(parsed, d, ownedIsins);
            recordMonth(month, d, Status.FETCHED, tried, parsed.rowCount(), equityRows,
                    w.navRows(), assumed ? "column layout assumed, not read" : null);
            return new MonthOutcome(Status.FETCHED, d, tried, equityRows, w.navRows(),
                    w.discovered(), assumed, null);
        }

        String note = candidates.isEmpty()
                ? "no business day in this month has passed yet"
                : (lastRejection == null ? "no candidate dates" : lastRejection.reason());
        recordMonth(month, null, Status.NOT_PUBLISHED, tried, null,
                lastRejection == null ? null : lastRejection.equityRows(), 0, note);
        return new MonthOutcome(Status.NOT_PUBLISHED, null, tried, 0, 0, 0, false, note);
    }

    private record Written(int navRows, int discovered) {}

    /**
     * Stores one accepted day's NAVs for the schemes this module tracks.
     *
     * <p>A historical row never edits a scheme that already exists: the daily file is the authority
     * on what a scheme is called and which category it is in today, and letting a 2018 header
     * overwrite that would reintroduce the pre-recategorisation taxonomy through the back door.
     */
    @Transactional
    public Written persistMonth(AmfiNavParser.ParseResult parsed, LocalDate date,
                                Set<String> ownedIsins) {
        List<AmfiSchemeRow> relevant = parsed.rows().stream()
                // See AmfiSchemeRow.isTrackable(): wider than the judged set on purpose, so index
                // funds get a month-end series without entering any peer median (§52.14).
                .filter(r -> r.isTrackable()
                        || (r.isinGrowth() != null && ownedIsins.contains(r.isinGrowth()))
                        || (r.isinReinvest() != null && ownedIsins.contains(r.isinReinvest())))
                .toList();

        Map<Long, MutualFundSchemeEntity> existing = schemeRepository.findAll().stream()
                .collect(Collectors.toMap(MutualFundSchemeEntity::getAmfiSchemeCode, s -> s, (a, b) -> a));

        List<MutualFundSchemeEntity> newSchemes = new ArrayList<>();
        Set<Long> addedThisFile = new HashSet<>();
        for (AmfiSchemeRow row : relevant) {
            MutualFundSchemeEntity e = existing.get(row.schemeCode());
            if (e == null) {
                // Present in the archive and absent from today's file: a scheme that has since
                // merged or wound up. Stored deliberately - it is the survivorship evidence, and
                // deleting it would delete the reason the surviving median is flattered.
                if (addedThisFile.add(row.schemeCode())) {
                    newSchemes.add(MutualFundSchemeEntity.builder()
                            .amfiSchemeCode(row.schemeCode())
                            .isinGrowth(row.isinGrowth())
                            .isinReinvest(row.isinReinvest())
                            .schemeName(row.schemeName())
                            .amcName(row.amcName())
                            .categoryRaw(row.categoryRaw())
                            .category(row.category())
                            .assetClass(row.category().assetClass())
                            .structure(row.structure())
                            .plan(row.plan())
                            .navOption(row.option())
                            .screenable(row.isScreenable())
                            .firstSeen(date)
                            .lastSeenInFile(date)
                            .build());
                }
            } else {
                boolean changed = false;
                if (e.getFirstSeen() == null || date.isBefore(e.getFirstSeen())) {
                    // Moved only backwards: how long this scheme has been on the record.
                    e.setFirstSeen(date);
                    changed = true;
                }
                // The archive fills a blank plan or option, and may never overwrite a resolved one.
                // Found on the live book: the daily file leaves both columns empty for "Motilal
                // Oswal Midcap Fund" and its name carries no plan words either, while the archive
                // names it "Motilal Oswal Midcap Fund-Direct Plan-Growth Option". A share class is
                // an immutable property of the scheme, so filling a blank is safe in a way that
                // overwriting a category would not be - and without it the fund reads as a series
                // this app cannot confirm is comparable, and goes unmeasured for no reason.
                if (isUnset(e.getPlan()) && row.plan() != SchemePlanKey.Plan.UNSPECIFIED) {
                    e.setPlan(row.plan());
                    changed = true;
                }
                if (isUnsetOption(e.getNavOption()) && row.option() != SchemePlanKey.Option.UNSPECIFIED) {
                    e.setNavOption(row.option());
                    changed = true;
                }
                if (changed) {
                    schemeRepository.save(e);
                }
            }
        }
        if (!newSchemes.isEmpty()) {
            schemeRepository.saveAll(newSchemes);
        }

        Set<String> already = navRepository.schemeCodesWithNavOn(date).stream()
                .map(c -> c + "|" + date)
                .collect(Collectors.toCollection(HashSet::new));

        Map<String, MutualFundNavEntity> byKey = new HashMap<>();
        for (AmfiSchemeRow row : relevant) {
            if (!row.hasNav()) {
                continue;
            }
            // The row's own date, not the requested one: liquid and overnight funds strike a
            // next-day NAV and 706 rows in the live file are dated ahead of the rest.
            String key = row.schemeCode() + "|" + row.navDate();
            if (already.contains(key)) {
                continue;
            }
            byKey.putIfAbsent(key, MutualFundNavEntity.builder()
                    .amfiSchemeCode(row.schemeCode())
                    .navDate(row.navDate())
                    .nav(row.nav())
                    .build());
        }

        List<MutualFundNavEntity> toSave = byKey.values().stream()
                .filter(n -> !navRepository.existsByAmfiSchemeCodeAndNavDate(
                        n.getAmfiSchemeCode(), n.getNavDate()))
                .toList();
        navRepository.saveAll(toSave);
        return new Written(toSave.size(), newSchemes.size());
    }

    @Transactional
    public void recordMonth(YearMonth month, LocalDate dateUsed, Status status, int tried,
                            Integer totalRows, Integer equityRows, int navRows, String note) {
        MutualFundHistoryFetchEntity e = fetchRepository.findByForMonth(month.atDay(1))
                .orElseGet(() -> MutualFundHistoryFetchEntity.builder()
                        .forMonth(month.atDay(1))
                        .build());
        e.setDateUsed(dateUsed);
        e.setStatus(status);
        e.setDatesTried(tried);
        e.setTotalRows(totalRows);
        e.setEquityRows(equityRows);
        e.setNavRowsWritten(navRows);
        e.setAttemptedAt(LocalDateTime.now());
        e.setNote(note == null ? null : note.substring(0, Math.min(note.length(), 490)));
        fetchRepository.save(e);
    }

    private static boolean isUnset(SchemePlanKey.Plan p) {
        return p == null || p == SchemePlanKey.Plan.UNSPECIFIED;
    }

    private static boolean isUnsetOption(SchemePlanKey.Option o) {
        return o == null || o == SchemePlanKey.Option.UNSPECIFIED;
    }

    private Set<String> ownedIsins() {
        return holdingRepository.findByActiveTrue().stream()
                .map(MutualFundHoldingEntity::getIsin)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** Courtesy pacing against a third party's free endpoint. Never zero - see the config javadoc. */
    private void pace() {
        long ms = Math.max(250, config.getHistoryPaceMillis());
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
