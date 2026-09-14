package com.example.trading.analyst;

import com.example.trading.holdings.SymbolVariants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Database-only read models for the analyst screens (SPEC §49.8). Page-load safe: no broker
 * call, no external fetch, no write.
 *
 * <p>Everything on screen comes off the ledger row, including the "price now" figure — which is
 * the last price the measurement pass stored, labelled with the date it was stored. A live quote
 * would be more current and would also make a page load hit the broker, which SPEC §20 rule 7
 * forbids outright.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalystTargetViewService {

    private final AnalystTargetRepository repository;
    private final AnalystTargetConfig config;
    private final com.example.trading.persistence.MultibaggerScoreRepository scoreRepository;
    private final com.example.trading.fundamentals.AnnualFundamentalsRepository fundamentalsRepository;
    private final com.example.trading.multibagger.MultibaggerConfig multibaggerConfig;

    /** Registers the coverage supplier once the bean exists (SPEC §49.7). */
    @jakarta.annotation.PostConstruct
    void registerCoverage() {
        AnalystTargetCoverage.register(() -> {
            LocalDate since = LocalDate.now().minusDays(config.getTrackRecordLookbackDays());
            return new TreeSet<>(repository.findSymbolsWithRecentTarget(since));
        });
    }

    // ------------------------------------------------------------------ one stock

    /**
     * Every target on file for one stock, newest first, plus what the live ones add up to.
     *
     * <p>Resolved across exchange prefixes: targets are filed under the NSE symbol because that is
     * what headlines name, while two thirds of this portfolio is held BSE-prefixed (B-061,
     * Gotcha 84). The symbol that actually answered is reported, so a reading can be traced.
     */
    public Map<String, Object> forStock(String symbol) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("symbol", symbol);

        List<AnalystTargetEntity> rows = List.of();
        String resolvedSymbol = null;
        for (String candidate : SymbolVariants.candidates(symbol)) {
            List<AnalystTargetEntity> found = repository.findBySymbolOrderByIssuedOnDesc(candidate);
            if (!found.isEmpty()) {
                rows = found;
                resolvedSymbol = candidate;
                break;
            }
        }

        out.put("resolvedSymbol", resolvedSymbol);
        out.put("targets", rows.stream().map(AnalystTargetViewService::row).toList());
        out.put("live", liveSummary(rows));
        out.put("plausibility", plausibility(symbol, rows));
        out.put("caveat", AnalystTrackRecord.caveat(config.getMinResolvedForTrackRecord()));
        return out;
    }

    /**
     * What would have to be true for these targets to be the fair value (SPEC §49.13).
     *
     * <p>The answer to the investor's actual question — *can I check whether a target is genuine?*
     * — and §49.12 established that the composite cannot be that check (it correlates **-0.322**
     * with claimed upside, by construction). This one can, because it is a statement about
     * earnings rather than about price behaviour.
     *
     * <p><b>Which screening row answers is decided on whether it CAN answer</b>, not on spelling
     * order. Some BSE-keyed rows carry no balance-sheet figures at all, and taking the first hit
     * would report "not measured" on a stock whose NSE row has a full valuation — B-088 exactly,
     * which surfaced the moment the compounding lens was joined to the portfolio. Exact-first
     * still decides between two rows that can both answer, so Gotcha 84's order is intact.
     */
    Map<String, Object> plausibility(String symbol, List<AnalystTargetEntity> rows) {
        Map<String, Object> out = new LinkedHashMap<>();

        com.example.trading.persistence.MultibaggerScoreEntity score = null;
        com.example.trading.persistence.MultibaggerScoreEntity firstSeen = null;
        for (String candidate : SymbolVariants.candidates(symbol)) {
            List<com.example.trading.persistence.MultibaggerScoreEntity> history =
                    scoreRepository.findHistoryBySymbol(candidate);
            if (history.isEmpty()) continue;
            com.example.trading.persistence.MultibaggerScoreEntity latest = history.get(0);
            if (firstSeen == null) firstSeen = latest;
            if (latest.getDcfImpliedGrowthPercent() != null) {
                score = latest;
                break;
            }
        }
        // "Screened but unjudgeable" and "never screened" must stay distinct (Gotcha 107), so a
        // row that answered nothing is still reported with the symbol and date that produced it.
        if (score == null) score = firstSeen;

        if (score == null) {
            out.put("verdict", AnalystTargetPlausibility.Verdict.NOT_MEASURED.name());
            out.put("reason", "This stock has never been through the app's screening, so there is "
                    + "no valuation model to run backwards from. That is a gap in this app's "
                    + "coverage, not a finding about the targets.");
            out.put("caveat", AnalystTargetPlausibility.caveat());
            return out;
        }

        out.put("basedOn", score.getSymbol());
        out.put("asOf", score.getScreeningDate() == null ? null : score.getScreeningDate().toString());
        out.put("impliedGrowthPercent", score.getDcfImpliedGrowthPercent());
        out.put("dcfVerdict", score.getDcfVerdict());

        // The rest of "all the factors": shown raw beside the growth requirement, never blended
        // into a second score (Gotcha 113c). Growth is one of several things that would have to
        // be true, and these are the others the app has already measured.
        Map<String, Object> business = new LinkedHashMap<>();
        business.put("financialQualityVerdict", score.getFinancialQualityVerdict());
        business.put("forensicFlags", score.getForensicFlags());
        business.put("compositeScore", score.getCompositeScore());
        business.put("verdict", score.getVerdict());
        out.put("business", business);

        // The record the requirement is judged against comes from annual_fundamentals, NOT from
        // dcf_historical_growth_percent -- that is a two-year CAGR, and a ten-year requirement
        // measured against it returned one verdict for every stock (SPEC 49.13, B-113).
        AnalystTargetPlausibility.GrowthRecord record =
                AnalystTargetPlausibility.growthRecord(profitByYear(score.getSymbol(), symbol));
        // LinkedHashMap, not Map.of: Map.of rejects a null value, and stringifying around that
        // would put the literal "null" on the wire where the UI expects an absence. A missing
        // growth record must arrive as null and render as the unmeasured marker (SPEC §21 rule 7).
        Map<String, Object> recordOut = new LinkedHashMap<>();
        recordOut.put("cagrPercent", record.cagrPercent());
        recordOut.put("years", record.years());
        recordOut.put("fromYear", record.fromYear());
        recordOut.put("toYear", record.toYear());
        recordOut.put("note", record.note());
        out.put("growthRecord", recordOut);
        out.put("historicalGrowthPercent", record.cagrPercent());
        out.put("yearsOfRecord", record.years());
        out.put("shortTermGrowthPercent", score.getDcfHistoricalGrowthPercent());

        double price = score.getCurrentPrice();
        List<Map<String, Object>> perTarget = new ArrayList<>();
        List<Double> liveTargets = new ArrayList<>();
        for (AnalystTargetEntity t : rows) {
            if (!AnalystTargetStatus.PENDING.name().equals(t.getStatus())) continue;
            if (t.getTargetPrice() == null || t.getTargetPrice() <= 0) continue;
            liveTargets.add(t.getTargetPrice());

            AnalystTargetPlausibility.Read read = AnalystTargetPlausibility.forTarget(
                    score.getDcfImpliedGrowthPercent(), record, price, t.getTargetPrice());

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("targetId", t.getId());
            m.put("brokerage", t.getBrokerage());
            m.put("targetPrice", t.getTargetPrice());
            m.putAll(readMap(read));
            perTarget.add(m);
        }
        out.put("targets", perTarget);

        // One headline reading, at the median of the open targets. Deliberately the median and
        // not an average: on most stocks there are one or two calls and an outlier would carry it.
        Double median = AnalystTrackRecord.median(liveTargets);
        if (median != null) {
            out.put("atMedianTarget", median);
            out.putAll(readMap(AnalystTargetPlausibility.forTarget(
                    score.getDcfImpliedGrowthPercent(), record, price, median)));
        } else {
            out.put("verdict", AnalystTargetPlausibility.Verdict.NOT_MEASURED.name());
            out.put("reason", "No open target on this stock, so there is nothing to run the "
                    + "valuation backwards from.");
        }
        out.put("caveat", AnalystTargetPlausibility.caveat());
        return out;
    }

    /**
     * Net profit by fiscal year, resolved across exchange prefixes.
     *
     * <p>Tries the symbol the screening row was filed under first, then the other spellings -
     * annual accounts are keyed on the NSE symbol while two thirds of this portfolio is held
     * BSE-prefixed, and KAJARIACER claimed one year of history while seven sat under its NSE
     * name (Gotcha 107). First spelling with figures wins, not first spelling with a row.
     */
    Map<Integer, Double> profitByYear(String scoreSymbol, String requested) {
        java.util.LinkedHashSet<String> tried = new java.util.LinkedHashSet<>();
        if (scoreSymbol != null) tried.add(scoreSymbol);
        tried.addAll(SymbolVariants.candidates(requested));
        for (String candidate : tried) {
            Map<Integer, Double> out = new LinkedHashMap<>();
            for (com.example.trading.fundamentals.AnnualFundamentalsEntity y
                    : fundamentalsRepository.findHistory(candidate)) {
                if (y.getFiscalYear() != null && y.getNetProfit() != null) {
                    out.put(y.getFiscalYear(), y.getNetProfit());
                }
            }
            if (!out.isEmpty()) return out;
        }
        return Map.of();
    }

    /** One plausibility reading on the wire. Pinned by {@code AnalystSurfaceContractTest}. */
    static Map<String, Object> readMap(AnalystTargetPlausibility.Read r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verdict", r.verdict().name());
        m.put("requiredGrowthPercent", r.requiredGrowthPercent());
        m.put("impliedGrowthPercent", r.impliedGrowthPercent());
        m.put("historicalGrowthPercent", r.historicalGrowthPercent());
        m.put("yearsOfRecord", r.yearsOfRecord());
        m.put("gapVsHistoryPoints", r.gapVsHistoryPoints());
        m.put("gapVsTodayPoints", r.gapVsTodayPoints());
        m.put("reason", r.reason());
        return m;
    }

    /**
     * What the open targets on a stock currently say.
     *
     * <p>Deliberately not called a consensus. A consensus is an average over a covered universe of
     * analysts; this is however many quotable calls happened to reach a headline, which on most
     * stocks is one or none. {@code houses} is published beside the median precisely so a "median
     * target" computed from a single note cannot be mistaken for the market's view.
     */
    Map<String, Object> liveSummary(List<AnalystTargetEntity> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<AnalystTargetEntity> live = rows.stream()
                .filter(r -> AnalystTargetStatus.PENDING.name().equals(r.getStatus()))
                .toList();

        Set<String> houses = new TreeSet<>();
        List<Double> targets = new ArrayList<>();
        Double lastPrice = null;
        LocalDate lastPriceOn = null;

        for (AnalystTargetEntity r : live) {
            houses.add(r.getBrokerage());
            targets.add(r.getTargetPrice());
        }
        // Latest stored price across all rows for this stock, whatever their status: the most
        // recently measured row has the most recent close, and this is a display figure only.
        for (AnalystTargetEntity r : rows) {
            if (r.getLastPrice() == null || r.getLastMeasuredAt() == null) continue;
            LocalDate on = r.getLastMeasuredAt().toLocalDate();
            if (lastPriceOn == null || on.isAfter(lastPriceOn)) {
                lastPriceOn = on;
                lastPrice = r.getLastPrice();
            }
        }

        Double median = AnalystTrackRecord.median(targets);
        out.put("openTargets", live.size());
        out.put("houses", houses.size());
        out.put("houseNames", List.copyOf(houses));
        out.put("medianTarget", median);
        out.put("highestTarget", targets.stream().max(Double::compareTo).orElse(null));
        out.put("lowestTarget", targets.stream().min(Double::compareTo).orElse(null));
        out.put("priceAsStored", lastPrice);
        out.put("priceAsOf", lastPriceOn == null ? null : lastPriceOn.toString());
        out.put("impliedUpsidePct",
                (median != null && lastPrice != null && lastPrice > 0)
                        ? (median - lastPrice) / lastPrice * 100.0 : null);
        out.put("note", note(rows, live, houses.size()));
        return out;
    }

    /**
     * The line above the table, which must never contradict the table.
     *
     * <p>Found by running it: the first version said "no analyst target has been recorded for this
     * stock" whenever nothing was <em>running</em> — printed directly above a recorded target that
     * simply had not been priced yet. Three states have to stay distinct here, for the same reason
     * they stay distinct everywhere else in this codebase: nothing on file, something on file that
     * cannot yet be measured, and something on file that has already run its course are three
     * different facts (Gotcha 21, 44).
     */
    static String note(List<AnalystTargetEntity> all, List<AnalystTargetEntity> live, int houses) {
        if (all.isEmpty()) {
            return "No analyst target has been recorded for this stock. That usually means none "
                    + "reached the news feeds this app reads — not that nobody covers it.";
        }
        if (!live.isEmpty()) {
            return houses == 1
                    ? "One brokerage. A single target is one firm's opinion, not a market view."
                    : houses + " brokerages have published a target that is still running.";
        }
        boolean anyUnpriced = all.stream()
                .anyMatch(r -> AnalystTargetStatus.UNPRICED.name().equals(r.getStatus()));
        if (anyUnpriced) {
            return "Recorded, but not measurable yet: the closing price on the day of the call has "
                    + "not been recovered, so there is nothing to measure the target against. The "
                    + "daily pass fills that in.";
        }
        return "No target is currently running on this stock. The ones below have either run their "
                + "course or been revised by the brokerage that made them.";
    }

    // ------------------------------------------------------------------ the overlap

    /**
     * Where this app's screening and the brokerages' open targets agree and disagree
     * (SPEC §49.12). DB-only: the latest screening run that has rows, joined to the open book.
     *
     * <p>Walks back through screening dates the way {@code DashboardService.screener()} does,
     * because the newest date has no rows until the 14:00 run and this app restarts daily
     * (Gotcha 20).
     */
    public Map<String, Object> overlap() {
        List<com.example.trading.persistence.MultibaggerScoreEntity> screened = List.of();
        for (LocalDate d : scoreRepository.findScreeningDates()) {
            List<com.example.trading.persistence.MultibaggerScoreEntity> rows =
                    scoreRepository.findByScreeningDateOrderByCompositeScoreDesc(d);
            if (!rows.isEmpty()) {
                screened = rows;
                break;
            }
        }

        Map<String, List<AnalystTargetEntity>> openByStock = new java.util.HashMap<>();
        for (AnalystTargetEntity t : repository.findAllOpen()) {
            if (t.getSymbol() == null) continue;
            openByStock.computeIfAbsent(t.getSymbol(), k -> new ArrayList<>()).add(t);
        }

        AnalystOverlap.Result r = AnalystOverlap.compute(screened, openByStock);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("screeningDate", r.screeningDate() == null ? null : r.screeningDate().toString());
        out.put("goodMeans", "the app's own verdict of Potential or Strong multibagger");
        out.put("screenedStocks", r.screenedStocks());
        out.put("screenedWithOpenTarget", r.screenedWithOpenTarget());
        out.put("goodStocks", r.goodStocks());
        out.put("goodWithOpenTarget", r.goodWithOpenTarget());
        out.put("bands", r.bands().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", b.label());
            m.put("stocks", b.stocks());
            m.put("withOpenTarget", b.withOpenTarget());
            m.put("coveragePercent", b.coveragePercent());
            return m;
        }).toList());
        out.put("upsideVsCompositeCorrelation", r.upsideVsCompositeCorrelation());
        out.put("correlationSample", r.correlationSample());
        out.put("good", r.good().stream().map(AnalystTargetViewService::overlapRow).toList());
        out.put("abovePublishedTargets",
                r.abovePublishedTargets().stream().map(AnalystTargetViewService::overlapRow).toList());
        out.put("goodWithoutTarget",
                r.goodWithoutTarget().stream().map(AnalystTargetViewService::overlapRow).toList());
        out.put("strongestAgreement",
                r.strongestAgreement().stream().map(AnalystTargetViewService::overlapRow).toList());
        out.put("minHousesForAgreement", AnalystOverlap.MIN_HOUSES_FOR_AGREEMENT);
        out.put("actions", r.actions().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", a.title());
            m.put("guidance", a.guidance());
            return m;
        }).toList());
        out.put("notes", r.notes());
        out.put("caveat", AnalystTrackRecord.caveat(config.getMinResolvedForTrackRecord()));
        return out;
    }

    /**
     * One overlap row on the wire.
     *
     * <p>Pinned by {@code AnalystSurfaceContractTest} for the same reason every other wire record
     * here is: nothing type-checks these names, so a rename draws "not measured" for ever on a
     * stock the app measured perfectly well.
     */
    static Map<String, Object> overlapRow(AnalystOverlap.Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", r.symbol());
        m.put("composite", r.composite());
        m.put("verdict", r.verdict());
        m.put("qualityVerdict", r.qualityVerdict());
        m.put("forensicFlags", r.forensicFlags());
        m.put("price", r.price());
        m.put("targets", r.targets());
        m.put("houses", r.houses());
        m.put("medianTarget", r.medianTarget());
        m.put("highestTarget", r.highestTarget());
        m.put("lowestTarget", r.lowestTarget());
        m.put("upsideToMedianPct", r.upsideToMedianPct());
        m.put("upsideToHighestPct", r.upsideToHighestPct());
        m.put("abovePublishedTargets", r.abovePublishedTargets());
        return m;
    }

    // ------------------------------------------------------------------ the scoreboard

    /** Per-house record over the configured window, plus the caveat that must travel with it. */
    public Map<String, Object> trackRecord() {
        LocalDate since = LocalDate.now().minusDays(config.getTrackRecordLookbackDays());
        List<AnalystTargetEntity> rows = repository.findIssuedSince(since);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", config.getTrackRecordLookbackDays());
        out.put("minResolvedForHitRate", config.getMinResolvedForTrackRecord());
        out.put("houses", AnalystTrackRecord.compute(rows, config.getMinResolvedForTrackRecord()));
        out.put("coverage", coverage(rows));
        out.put("caveat", AnalystTrackRecord.caveat(config.getMinResolvedForTrackRecord()));
        return out;
    }

    /**
     * How much of the ledger is actually measurable, stated on the same screen as the numbers.
     *
     * <p>Three of this codebase's worst defects were a signal drawn as though it had been
     * measured when it had not (Gotcha 94, 106). The answer to "is this hit rate worth reading"
     * is mostly here, not in the hit rate.
     */
    Map<String, Object> coverage(List<AnalystTargetEntity> rows) {
        int resolved = 0, pending = 0, superseded = 0, unpriced = 0;
        Set<String> stocks = new TreeSet<>();
        Set<String> houses = new TreeSet<>();
        for (AnalystTargetEntity r : rows) {
            stocks.add(r.getSymbol());
            if (r.getBrokerage() != null) houses.add(r.getBrokerage());
            switch (r.getStatus() == null ? "" : r.getStatus()) {
                case "REACHED", "MISSED" -> resolved++;
                case "SUPERSEDED" -> superseded++;
                case "UNPRICED" -> unpriced++;
                default -> pending++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("targets", rows.size());
        out.put("stocks", stocks.size());
        out.put("brokerages", houses.size());
        out.put("resolved", resolved);
        out.put("stillRunning", pending);
        out.put("revised", superseded);
        out.put("notYetPriced", unpriced);
        out.put("note", resolved == 0
                ? "No target has run its course yet, so there is no hit rate to report — an empty "
                        + "record, not a bad one. The first twelve-month calls resolve a year after "
                        + "they were recorded."
                : resolved + " of " + rows.size() + " recorded targets have run their course.");
        return out;
    }

    /**
     * Recent targets across every stock, newest first, capped.
     *
     * <p><b>The cap is not cosmetic.</b> Once §49.11's archive backfill landed, a year's window
     * returned 2,281 rows and the accuracy page's DOM went from 44 KB to 3 MB on every load. The
     * table is folded by default (§27.15) but folding hides a list, it does not avoid building it.
     *
     * <p>{@code total} is published alongside so the caller can say <em>"the 200 most recent of
     * 2,281"</em> rather than printing a count that describes a different list from the one on
     * screen - which is B-098's defect exactly, and the rule §27.15 turns on.
     */
    public Map<String, Object> recent(int days, int limit) {
        int window = days > 0 ? days : 90;
        int cap = limit > 0 ? limit : DEFAULT_RECENT_LIMIT;
        List<AnalystTargetEntity> rows = repository.findIssuedSince(LocalDate.now().minusDays(window));
        rows = rows.stream()
                .sorted(Comparator.comparing(AnalystTargetEntity::getIssuedOn).reversed())
                .toList();

        List<AnalystTargetEntity> shown = rows.size() > cap ? rows.subList(0, cap) : rows;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", window);
        out.put("total", rows.size());
        out.put("shown", shown.size());
        out.put("truncated", rows.size() > shown.size());
        out.put("targets", shown.stream().map(AnalystTargetViewService::row).toList());
        out.put("caveat", AnalystTrackRecord.caveat(config.getMinResolvedForTrackRecord()));
        return out;
    }

    /** Enough to scan, small enough that a page load is not three megabytes. */
    static final int DEFAULT_RECENT_LIMIT = 200;

    /**
     * One row on the wire.
     *
     * <p>Field names here are a contract with {@code analyst-cells.js} and nothing type-checks
     * them, so {@code AnalystSurfaceContractTest} asserts they exist — a rename would otherwise
     * draw "not measured" for ever on a target the app measured perfectly well, which is the
     * quietest possible failure (the {@code CompoundingSurfaceContractTest} shape).
     */
    static Map<String, Object> row(AnalystTargetEntity t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("symbol", t.getSymbol());
        m.put("companyName", t.getCompanyName());
        m.put("brokerage", t.getBrokerage());
        m.put("rating", t.getRating());
        m.put("action", t.getAction());
        m.put("targetPrice", t.getTargetPrice());
        m.put("issuedOn", t.getIssuedOn() == null ? null : t.getIssuedOn().toString());
        m.put("calledOn", t.getCalledOn() == null ? null : t.getCalledOn().toString());
        m.put("brokerStatedPrice", t.getBrokerStatedPrice());
        m.put("resolvesOn", t.getResolvesOn() == null ? null : t.getResolvesOn().toString());
        m.put("horizonDays", t.getHorizonDays());
        m.put("horizonStated", t.isHorizonStated());
        m.put("priceAtCall", t.getPriceAtCall());
        m.put("upsidePctAtCall", t.getUpsidePctAtCall());
        m.put("direction", t.getDirection());
        m.put("status", t.getStatus());
        m.put("reachedOn", t.getReachedOn() == null ? null : t.getReachedOn().toString());
        m.put("daysToReach", t.getDaysToReach());
        m.put("lastPrice", t.getLastPrice());
        m.put("returnPct", t.getReturnPct());
        m.put("niftyReturnPct", t.getNiftyReturnPct());
        m.put("excessReturnPct", t.getExcessReturnPct());
        m.put("maxFavourablePct", t.getMaxFavourablePct());
        m.put("headline", t.getHeadline());
        m.put("sourceName", t.getSourceName());
        m.put("sourceUrl", t.getSourceUrl());
        m.put("lastMeasuredAt", t.getLastMeasuredAt() == null ? null : t.getLastMeasuredAt().toString());
        return m;
    }
}
