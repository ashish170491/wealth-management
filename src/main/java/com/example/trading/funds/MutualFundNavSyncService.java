package com.example.trading.funds;

import com.example.trading.funds.amfi.AmfiNavClient;
import com.example.trading.funds.amfi.AmfiNavParser;
import com.example.trading.funds.amfi.AmfiSchemeRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pulls AMFI's daily file and keeps {@code mf_scheme} and {@code mf_nav_history} current
 * (SPEC §52.4, §52.6).
 *
 * <p><b>What is persisted, and why it is not everything.</b> The file has 14,375 rows and the real
 * universe is 571: 4,711 rows are dead close-ended fixed-maturity plans, and most of the rest are
 * IDCW and Regular-plan clones of a scheme nobody would look at. Persisting all of them would make
 * every category listing three copies of one fund. So the rule is <em>screenable, plus every scheme
 * the investor actually holds whatever its category</em> — that second clause is the one that is
 * easy to forget, and without it the module is blind to money it can see.
 *
 * <p><b>A scheme that disappears is not deleted.</b> {@code lastSeenInFile} stops advancing and the
 * row stays, because a scheme leaving the feed has merged or wound up and that event is exactly
 * what makes survivorship measurable later (SPEC §52.7, §25.1). Deleting it would delete the
 * evidence that the surviving category median is flattered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MutualFundNavSyncService {

    private final AmfiNavClient client;
    private final MutualFundConfig config;
    private final MutualFundSchemeRepository schemeRepository;
    private final MutualFundNavRepository navRepository;
    private final MutualFundHoldingRepository holdingRepository;
    private final MutualFundSyncRunRepository syncRunRepository;

    /** Only for {@code resolveUnmatched()} - this class never calls the broker. */
    private final MutualFundHoldingsService holdingsService;

    /**
     * @param schemesWritten schemes inserted or updated
     * @param navRowsWritten new NAV points stored
     * @param unclassifiedHeaders category headers with no rule, by name — the app's gap, reported
     */
    public record SyncResult(
            LocalDateTime pulledAt,
            LocalDate navDateInFile,
            int rowsParsed,
            int schemesWritten,
            int navRowsWritten,
            int unparsedLines,
            List<String> unclassifiedHeaders,
            String failureReason
    ) {
        public boolean succeeded() {
            return failureReason == null;
        }
    }

    @Transactional
    public SyncResult sync() {
        LocalDateTime pulledAt = LocalDateTime.now();
        try {
            String body = client.fetchNavAll();
            AmfiNavParser.ParseResult parsed = AmfiNavParser.parse(body);
            return persist(pulledAt, parsed);
        } catch (Exception e) {
            // A failed run is recorded rather than silently skipped: a freshness key that simply
            // stops moving, with no row saying why, is what made B-116 invisible for days.
            log.error("AMFI sync failed: {}", e.getMessage(), e);
            SyncResult failed = new SyncResult(pulledAt, null, 0, 0, 0, 0, List.of(), e.getMessage());
            record(failed);
            return failed;
        }
    }

    private SyncResult persist(LocalDateTime pulledAt, AmfiNavParser.ParseResult parsed) {
        LocalDate today = LocalDate.now();

        Set<String> ownedIsins = holdingRepository.findByActiveTrue().stream()
                .map(MutualFundHoldingEntity::getIsin)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<AmfiSchemeRow> toPersist = parsed.rows().stream()
                .filter(r -> r.isScreenable()
                        || (r.isinGrowth() != null && ownedIsins.contains(r.isinGrowth()))
                        || (r.isinReinvest() != null && ownedIsins.contains(r.isinReinvest())))
                .toList();

        Map<Long, MutualFundSchemeEntity> existing = schemeRepository.findAll().stream()
                .collect(Collectors.toMap(MutualFundSchemeEntity::getAmfiSchemeCode, s -> s, (a, b) -> a));

        List<MutualFundSchemeEntity> upserts = new ArrayList<>();
        for (AmfiSchemeRow row : toPersist) {
            MutualFundSchemeEntity e = existing.get(row.schemeCode());
            if (e == null) {
                e = MutualFundSchemeEntity.builder()
                        .amfiSchemeCode(row.schemeCode())
                        .firstSeen(today)
                        .build();
            }
            e.setIsinGrowth(row.isinGrowth());
            e.setIsinReinvest(row.isinReinvest());
            e.setSchemeName(row.schemeName());
            e.setAmcName(row.amcName());
            e.setCategoryRaw(row.categoryRaw());
            e.setCategory(row.category());
            e.setAssetClass(row.category().assetClass());
            e.setStructure(row.structure());
            e.setPlan(row.plan());
            e.setNavOption(row.option());
            e.setScreenable(row.isScreenable());
            e.setLastSeenInFile(today);
            // Null, never zero: a scheme that did not publish is not a scheme worth nothing.
            e.setNav(row.nav());
            e.setNavDate(row.navDate());
            upserts.add(e);
        }
        schemeRepository.saveAll(upserts);

        // Close the ordering loop: a scheme persisted just now may be the one an owned holding
        // could not match on an earlier pass. Without this a fresh install reports the investor's
        // own funds as unresolved for a full day, which reads as "AMFI has no record of them".
        int rescued = holdingsService.resolveUnmatched();
        if (rescued > 0) {
            log.info("AMFI sync rescued {} previously-unresolved holding(s)", rescued);
        }

        int navWritten = config.isStoreNavForScreenableUniverse()
                ? storeNavPoints(toPersist)
                : storeNavPoints(toPersist.stream()
                        .filter(r -> r.isinGrowth() != null && ownedIsins.contains(r.isinGrowth()))
                        .toList());

        LocalDate headline = headlineNavDate(parsed.rows());

        SyncResult result = new SyncResult(
                pulledAt,
                headline,
                parsed.rowCount(),
                upserts.size(),
                navWritten,
                parsed.unparsedLines(),
                parsed.unclassifiedHeaders().stream().sorted().toList(),
                null);
        record(result);

        log.info("AMFI sync: {} rows parsed, {} schemes written, {} NAV points, {} unparsed, {} unclassified headers",
                result.rowsParsed(), result.schemesWritten(), result.navRowsWritten(),
                result.unparsedLines(), result.unclassifiedHeaders().size());
        if (result.unparsedLines() > 0) {
            log.warn("AMFI sync could not read {} data lines - the feed layout may have changed. "
                    + "Those schemes are absent from this run, which downstream would otherwise read "
                    + "as 'these funds do not exist'.", result.unparsedLines());
        }
        return result;
    }

    /** Inserts a NAV point for every row that actually published one, skipping dates already stored. */
    private int storeNavPoints(List<AmfiSchemeRow> rows) {
        Map<LocalDate, Set<Long>> alreadyStored = new HashMap<>();
        List<MutualFundNavEntity> inserts = new ArrayList<>();

        for (AmfiSchemeRow row : rows) {
            if (!row.hasNav()) {
                continue;
            }
            Set<Long> onDate = alreadyStored.computeIfAbsent(row.navDate(),
                    d -> new HashSet<>(navRepository.schemeCodesWithNavOn(d)));
            if (onDate.add(row.schemeCode())) {
                inserts.add(MutualFundNavEntity.builder()
                        .amfiSchemeCode(row.schemeCode())
                        .navDate(row.navDate())
                        .nav(row.nav())
                        .build());
            }
        }
        navRepository.saveAll(inserts);
        return inserts.size();
    }

    /**
     * The date most rows carry.
     *
     * <p>Not the maximum: 706 rows in the live file are dated <em>ahead</em> of the rest, because
     * liquid and overnight funds strike a next-day NAV. Taking the max would report tomorrow as the
     * file's date on every single run.
     */
    private LocalDate headlineNavDate(List<AmfiSchemeRow> rows) {
        return rows.stream()
                .map(AmfiSchemeRow::navDate)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(d -> d, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private void record(SyncResult r) {
        syncRunRepository.save(MutualFundSyncRunEntity.builder()
                .lastPulledAt(r.pulledAt())
                .navDateInFile(r.navDateInFile())
                .rowsParsed(r.rowsParsed())
                .schemesWritten(r.schemesWritten())
                .navRowsWritten(r.navRowsWritten())
                .unparsedLines(r.unparsedLines())
                .unclassifiedHeaders(r.unclassifiedHeaders().isEmpty()
                        ? null : String.join("\n", r.unclassifiedHeaders()))
                .failureReason(r.failureReason())
                .build());
    }
}
