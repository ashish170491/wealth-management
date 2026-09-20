package com.example.trading.funds;

import com.example.trading.funds.FundVocabulary.BenchmarkAvailability;
import com.example.trading.funds.RollingWindowRecord.Benchmark;
import com.example.trading.funds.RollingWindowRecord.Point;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The one place a fund's measured record is assembled (SPEC §52.8).
 *
 * <p><b>One reader, every surface</b> - §41.5's rule, and the reason it exists: when the compounding
 * lens had two code paths, one screen said {@code UPPER_QUARTILE} and the next said not measured for
 * the same stock on the same day (Gotcha 85, B-065). Every fund figure this app shows comes through
 * here, so a screen can only render what it is given.
 *
 * <p>DB-only. No AMFI call, no broker call, so every read is page-load safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundLensService {

    /** The window that leads. Five years exists in the archive and has under two independent
     *  windows in it, so it is deliberately not offered (see {@link RollingWindowRecord}). */
    public static final int WINDOW_MONTHS = 36;

    private final MutualFundConfig config;
    private final MutualFundSchemeRepository schemeRepository;
    private final MutualFundNavRepository navRepository;
    private final MutualFundHoldingRepository holdingRepository;

    /**
     * Scratch space for one request.
     *
     * <p>Without it, reading a portfolio of six funds queries the whole scheme table eighteen times
     * and rebuilds a 249-scheme category's month-end series twice per holding. That is B-115's
     * shape - a query whose cost grows with how long the app has been running, inside a loop - and
     * it is cheaper to not write it than to find it later at 31.7 seconds cold.
     */
    private static final class Scratch {
        private List<MutualFundSchemeEntity> schemes;
        private final Map<SebiCategory, Map<Long, Map<YearMonth, BigDecimal>>> byCategory =
                new HashMap<>();
    }

    // ------------------------------------------------------------------ public reads

    /** Every fund the investor holds, measured. */
    public Map<String, Object> portfolio() {
        List<MutualFundHoldingEntity> held = holdingRepository.findByActiveTrue();
        Scratch scratch = new Scratch();
        List<Map<String, Object>> rows = new ArrayList<>();
        // Four states, because the rows draw four. Counting only NOT_MEASURED reported three of
        // six holdings as "measured" on the live book when exactly one carried a verdict - the two
        // NOT_COMPARABLE rows were swept in with the real one. That is B-117's shape: a summary
        // that distinguishes fewer states than the renderer beneath it.
        int measured = 0;
        int notComparable = 0;
        int notMeasured = 0;
        int unresolved = 0;

        for (MutualFundHoldingEntity h : held) {
            if (h.getAmfiSchemeCode() == null) {
                unresolved++;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("isin", h.getIsin());
                row.put("folio", h.getFolio());
                row.put("brokerFundName", h.getBrokerFundName());
                row.put("recordVerdict", FundVocabulary.FundRecordVerdict.NOT_MEASURED.name());
                row.put("reason", "this holding has not been matched to an AMFI scheme, so there is "
                        + "no published series to measure. Reported rather than guessed");
                rows.add(row);
                continue;
            }
            Map<String, Object> row =
                    lensFor(h.getAmfiSchemeCode(), scratch).orElseGet(LinkedHashMap::new);
            row.put("isin", h.getIsin());
            row.put("folio", h.getFolio());
            row.put("units", h.getUnits());
            row.put("currentValue", h.getCurrentValue());
            Object verdict = row.get("recordVerdict");
            if (FundVocabulary.FundRecordVerdict.NOT_MEASURED.name().equals(verdict)) {
                notMeasured++;
            } else if (FundVocabulary.FundRecordVerdict.NOT_COMPARABLE.name().equals(verdict)) {
                notComparable++;
            } else {
                measured++;
            }
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("holdings", rows);
        out.put("holdingsMeasured", measured);
        out.put("holdingsNotComparable", notComparable);
        out.put("holdingsNotMeasured", notMeasured);
        out.put("holdingsUnresolved", unresolved);
        out.put("coverageLine",
                coverageLine(held.size(), measured, notComparable, notMeasured, unresolved));
        out.put("caveat", PORTFOLIO_CAVEAT);
        return out;
    }

    /** One scheme's full record, or empty when this app has never persisted it. */
    public Optional<Map<String, Object>> lensFor(long schemeCode) {
        return lensFor(schemeCode, new Scratch());
    }

    private Optional<Map<String, Object>> lensFor(long schemeCode, Scratch scratch) {
        Optional<MutualFundSchemeEntity> maybe = schemeRepository.findByAmfiSchemeCode(schemeCode);
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        MutualFundSchemeEntity scheme = maybe.get();
        SebiCategory category = scheme.getCategory() == null
                ? SebiCategory.UNCLASSIFIED : scheme.getCategory();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("amfiSchemeCode", schemeCode);
        out.put("schemeName", scheme.getSchemeName());
        out.put("amcName", scheme.getAmcName());
        out.put("category", category.name());
        out.put("categoryLabel", category.label());
        out.put("categoryRaw", scheme.getCategoryRaw());
        out.put("plan", scheme.getPlan() == null ? null : scheme.getPlan().name());
        out.put("option", scheme.getNavOption() == null ? null : scheme.getNavOption().name());
        out.put("nav", scheme.getNav());
        out.put("navAsOf", scheme.getNavDate());

        List<Point> series = monthEndSeries(List.of(schemeCode)).getOrDefault(schemeCode, List.of());
        out.put("monthsOnFile", series.size());
        out.put("minMonthsToMeasure", config.getMinMonthsToMeasure());

        BenchmarkAvailability availability = benchmarkAvailability(category);
        out.put("benchmarkAvailability", availability.name());
        out.put("benchmarkAvailabilityLabel", availability.label());
        out.put("benchmarkNote", benchmarkNote(category, availability));

        // The units guard, and it fires on this investor's real book: an IDCW series records the
        // return AFTER every distribution while its Growth peers record it before, so comparing
        // them understates the fund. Checked before anything is measured rather than corrected
        // afterwards (SPEC 52.8, Gotcha 139b).
        SeriesComparability comparability = SeriesComparability.of(scheme.getNavOption());
        out.put("seriesComparable", comparability.comparable());
        out.put("seriesComparabilityReason", comparability.reason());

        // The primary comparator: the fund's own SEBI category, measured over exactly the same
        // month ends from exactly the same file. It needs no index and carries no TRI bias.
        PeerMedianBenchmark peers = peerBenchmark(category, schemeCode, scratch);
        RollingWindowRecord.Record record = comparability.comparable()
                ? RollingWindowRecord.measure(series, peers, WINDOW_MONTHS,
                        config.getMinMonthsToMeasure())
                : RollingWindowRecord.notComparable(WINDOW_MONTHS, series.size(),
                        comparability.reason());

        out.put("recordVerdict", record.verdict().name());
        out.put("recordVerdictLabel", record.verdict().label());
        out.put("recordReason", record.reason());
        out.put("windowMonths", record.windowMonths());
        out.put("windowsMeasured", record.windowsMeasured());
        out.put("independentWindows", record.independentWindows());
        out.put("windowsAhead", record.windowsAhead());
        out.put("shareAheadPct", record.shareAhead() == null ? null : record.shareAhead() * 100);
        out.put("medianExcessPp", record.medianExcessPp());
        out.put("mayUseConsistently", record.mayUseConsistently());
        out.put("sampleSizeNote", SAMPLE_NOTE);

        CategoryCrossSection standing = comparability.comparable()
                ? standingFor(category, schemeCode, series, peers)
                : new CategoryCrossSection(category, peers.peerCount(), null, null, null,
                        FundVocabulary.CategoryStanding.NOT_MEASURED, comparability.reason());
        out.put("categoryStanding", standing.standing().name());
        out.put("categoryStandingLabel", standing.standing().label());
        out.put("categoryStandingReason", standing.reason());
        out.put("categoryPeers", standing.peersMeasured());
        out.put("categoryMedianPct", standing.medianReturnPct());
        out.put("ownReturnPct", standing.subjectReturnPct());
        out.put("categoryPercentile", standing.percentile());
        out.put("divergenceNote", divergenceNote(record, standing, series.size()));

        PlanCostRead cost = planCost(scheme, series, scratch);
        out.put("planCostVerdict", cost.verdict().name());
        out.put("planCostVerdictLabel", cost.verdict().label());
        out.put("planCostReason", cost.reason());
        out.put("planAnnualDragPct", cost.annualDragPct());
        out.put("planCostCaveat", cost.caveat());

        SurvivorshipLedger ledger = survivorship(category, series, scratch);
        out.put("survivorshipFrom", ledger.from());
        out.put("survivorshipReportingAtStart", ledger.reportingAtStart());
        out.put("survivorshipNoLongerReporting", ledger.noLongerReporting());
        out.put("survivorshipAttritionPct", ledger.attritionPct());
        out.put("survivorshipReason", ledger.reason());
        out.put("survivorshipCaveat", ledger.caveat());

        return Optional.of(out);
    }

    // ------------------------------------------------------------------ pieces

    /**
     * The peer benchmark: the median of the category's Direct-Growth schemes over each window.
     *
     * <p>The subject is excluded from its own median, which matters most in the small categories -
     * with four Contra schemes, leaving the subject in would have it partly grading itself.
     */
    private PeerMedianBenchmark peerBenchmark(SebiCategory category, long subject, Scratch scratch) {
        Map<Long, Map<YearMonth, BigDecimal>> all = categorySeries(category, scratch);
        Set<Long> screenable = schemes(scratch).stream()
                .filter(s -> s.getCategory() == category)
                .filter(MutualFundSchemeEntity::isScreenable)
                .map(MutualFundSchemeEntity::getAmfiSchemeCode)
                .collect(Collectors.toSet());
        Map<Long, Map<YearMonth, BigDecimal>> peers = new HashMap<>();
        all.forEach((code, series) -> {
            if (code != subject && screenable.contains(code)) {
                peers.put(code, series);
            }
        });
        return new PeerMedianBenchmark(category, peers);
    }

    private List<MutualFundSchemeEntity> schemes(Scratch scratch) {
        if (scratch.schemes == null) {
            scratch.schemes = schemeRepository.findAll();
        }
        return scratch.schemes;
    }

    /** Every scheme in a category with its month-end series - built once per category per request. */
    private Map<Long, Map<YearMonth, BigDecimal>> categorySeries(SebiCategory category,
                                                                 Scratch scratch) {
        return scratch.byCategory.computeIfAbsent(category, c -> {
            List<Long> codes = schemes(scratch).stream()
                    .filter(s -> s.getCategory() == c)
                    .map(MutualFundSchemeEntity::getAmfiSchemeCode)
                    .toList();
            return monthEndSeriesAsMaps(codes);
        });
    }

    /** Median-of-peers over exactly the window asked for. Never a constructed index - see
     *  {@link RollingWindowRecord}'s note on why a base month would tilt toward survivors. */
    private static final class PeerMedianBenchmark implements Benchmark {

        private final SebiCategory category;
        private final Map<Long, Map<YearMonth, BigDecimal>> peers;

        PeerMedianBenchmark(SebiCategory category, Map<Long, Map<YearMonth, BigDecimal>> peers) {
            this.category = category;
            this.peers = peers;
        }

        int peerCount() {
            return peers.size();
        }

        @Override
        public String label() {
            return "the " + category.label() + " category median";
        }

        @Override
        public boolean appliesTriHaircut() {
            // Both sides are net asset values, net of their own costs. There is no total-return
            // bias to correct here, and applying the haircut anyway would invent a penalty.
            return false;
        }

        @Override
        public Double cagrPctFor(YearMonth from, YearMonth to) {
            int months = RollingWindowRecord.monthsBetween(from, to);
            List<Double> rates = new ArrayList<>();
            for (Map<YearMonth, BigDecimal> p : peers.values()) {
                Double r = RollingWindowRecord.cagrPct(p.get(from), p.get(to), months);
                if (r != null) {
                    rates.add(r);
                }
            }
            if (rates.size() < CategoryCrossSection.MIN_PEERS) {
                // Below the peer floor the median is arithmetic rather than a comparison, so the
                // benchmark declines to answer rather than returning a thin number (Gotcha 68).
                return null;
            }
            rates.sort(Double::compareTo);
            return RollingWindowRecord.median(rates);
        }

        Map<Long, Double> ratesFor(YearMonth from, YearMonth to) {
            int months = RollingWindowRecord.monthsBetween(from, to);
            Map<Long, Double> out = new HashMap<>();
            peers.forEach((code, p) -> {
                Double r = RollingWindowRecord.cagrPct(p.get(from), p.get(to), months);
                if (r != null) {
                    out.put(code, r);
                }
            });
            return out;
        }

        Set<Long> reportingIn(YearMonth month) {
            return peers.entrySet().stream()
                    .filter(e -> e.getValue().containsKey(month))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
        }
    }

    private CategoryCrossSection standingFor(SebiCategory category, long subject,
                                             List<Point> series, PeerMedianBenchmark peers) {
        if (series.size() < config.getMinMonthsToMeasure()) {
            return new CategoryCrossSection(category, peers.peerCount(), null, null, null,
                    FundVocabulary.CategoryStanding.NOT_MEASURED,
                    "only " + series.size() + " month-end observations are on file for this fund, "
                            + "and " + config.getMinMonthsToMeasure() + " are needed before it can "
                            + "be placed among its peers");
        }
        YearMonth to = series.get(series.size() - 1).month();
        YearMonth from = to.minusMonths(WINDOW_MONTHS);

        Map<YearMonth, BigDecimal> own = series.stream()
                .collect(Collectors.toMap(Point::month, Point::nav, (a, b) -> b, TreeMap::new));
        Double ownRate = RollingWindowRecord.cagrPct(own.get(from), own.get(to), WINDOW_MONTHS);

        Map<Long, Double> rates = new HashMap<>(peers.ratesFor(from, to));
        if (ownRate != null) {
            rates.put(subject, ownRate);
        }
        return CategoryCrossSection.of(category, subject, rates);
    }

    /**
     * The Direct-versus-Regular reading for a held scheme.
     *
     * <p>The twin is found by {@link SchemeTwinKey} over every persisted scheme - the two plans of
     * one fund share no code and no ISIN, only a name.
     */
    private PlanCostRead planCost(MutualFundSchemeEntity scheme, List<Point> ownSeries,
                                  Scratch scratch) {
        if (scheme.getPlan() == SchemePlanKey.Plan.DIRECT) {
            return PlanCostRead.alreadyDirect();
        }
        if (scheme.getPlan() != SchemePlanKey.Plan.REGULAR) {
            return new PlanCostRead(FundVocabulary.PlanCostVerdict.NOT_MEASURED,
                    null, null, null, 0,
                    "this scheme's plan could not be resolved from AMFI's columns or its name, so "
                            + "there is no twin to look for. Reported rather than assumed to be "
                            + "Regular", PlanCostRead.SWITCHING_CAVEAT);
        }

        String key = SchemeTwinKey.of(scheme.getAmcName(), scheme.getSchemeName());
        if (key == null) {
            return new PlanCostRead(FundVocabulary.PlanCostVerdict.REGULAR_PLAN_NO_TWIN,
                    null, null, null, 0,
                    "this scheme has no AMC name on file, so its Direct twin cannot be identified",
                    PlanCostRead.SWITCHING_CAVEAT);
        }

        Optional<MutualFundSchemeEntity> twin = schemes(scratch).stream()
                .filter(s -> s.getPlan() == SchemePlanKey.Plan.DIRECT)
                .filter(s -> s.getNavOption() == scheme.getNavOption())
                .filter(s -> key.equals(SchemeTwinKey.of(s.getAmcName(), s.getSchemeName())))
                .min(Comparator.comparing(MutualFundSchemeEntity::getAmfiSchemeCode));

        if (twin.isEmpty()) {
            return new PlanCostRead(FundVocabulary.PlanCostVerdict.REGULAR_PLAN_NO_TWIN,
                    null, null, null, 0,
                    "no Direct-plan series for this scheme is on file, so the commission cannot be "
                            + "measured. That is a gap in this app's data, not evidence that the "
                            + "plan is free", PlanCostRead.SWITCHING_CAVEAT);
        }

        List<Point> directSeries = monthEndSeries(List.of(twin.get().getAmfiSchemeCode()))
                .getOrDefault(twin.get().getAmfiSchemeCode(), List.of());
        return PlanCostRead.measure(directSeries, ownSeries, config.getMinMonthsToMeasure());
    }

    private SurvivorshipLedger survivorship(SebiCategory category, List<Point> series,
                                            Scratch scratch) {
        if (series.isEmpty()) {
            return SurvivorshipLedger.between(category, null, null, Set.of(), Set.of());
        }
        YearMonth to = series.get(series.size() - 1).month();
        YearMonth from = series.get(0).month();

        Map<Long, Map<YearMonth, BigDecimal>> byScheme = categorySeries(category, scratch);

        Set<Long> atStart = byScheme.entrySet().stream()
                .filter(e -> e.getValue().containsKey(from))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        Set<Long> atEnd = byScheme.entrySet().stream()
                .filter(e -> e.getValue().containsKey(to))
                .map(Map.Entry::getKey).collect(Collectors.toSet());

        return SurvivorshipLedger.between(category, from, to, atStart, atEnd);
    }

    // ------------------------------------------------------------------ series

    /**
     * One observation per calendar month per scheme: the latest stored NAV in that month.
     *
     * <p>The screenable universe also has a daily row, so the newest month's point is whatever the
     * last pull stored. That is the fund's current value rather than a true month end, and it only
     * ever forms the far end of a window whose two dates are still exactly {@code WINDOW_MONTHS}
     * apart on the calendar.
     */
    Map<Long, List<Point>> monthEndSeries(Collection<Long> codes) {
        Map<Long, Map<YearMonth, BigDecimal>> maps = monthEndSeriesAsMaps(codes);
        Map<Long, List<Point>> out = new HashMap<>();
        maps.forEach((code, byMonth) -> out.put(code, byMonth.entrySet().stream()
                .map(e -> new Point(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(Point::month))
                .toList()));
        return out;
    }

    private Map<Long, Map<YearMonth, BigDecimal>> monthEndSeriesAsMaps(Collection<Long> codes) {
        Map<Long, Map<YearMonth, BigDecimal>> out = new HashMap<>();
        if (codes == null || codes.isEmpty()) {
            return out;
        }
        Map<Long, LocalDate> latestSeen = new HashMap<>();
        for (MutualFundNavEntity n : navRepository.findForSchemes(codes)) {
            Map<YearMonth, BigDecimal> byMonth =
                    out.computeIfAbsent(n.getAmfiSchemeCode(), k -> new TreeMap<>());
            YearMonth m = YearMonth.from(n.getNavDate());
            LocalDate best = latestSeen.get(key(n.getAmfiSchemeCode(), m));
            if (best == null || n.getNavDate().isAfter(best)) {
                latestSeen.put(key(n.getAmfiSchemeCode(), m), n.getNavDate());
                byMonth.put(m, n.getNav());
            }
        }
        return out;
    }

    private static Long key(Long code, YearMonth m) {
        // A composite key without allocating a record per row - the map is scratch space.
        return code * 10_000L + m.getYear() * 12L + m.getMonthValue();
    }

    // ------------------------------------------------------------------ benchmark availability

    /**
     * Whether a category has an index this app could honestly measure against (SPEC §52.8).
     *
     * <p>Two exact, one blend, the rest refused - and the two kinds of refusal are different facts.
     * {@code NOT_HELD} is this app's gap and is fixable by loading Nifty 500 or Smallcap 250;
     * {@code NOT_SINGULAR} is a property of the question, because a thousand sectoral and thematic
     * schemes each track a different index, and it is permanent.
     */
    static BenchmarkAvailability benchmarkAvailability(SebiCategory category) {
        return switch (category) {
            case MID_CAP -> BenchmarkAvailability.HELD_EXACT;
            case LARGE_CAP -> BenchmarkAvailability.HELD_PROXY;
            case LARGE_AND_MID -> BenchmarkAvailability.HELD_BLEND;
            case SECTORAL_THEMATIC -> BenchmarkAvailability.NOT_SINGULAR;
            case INDEX_EQUITY, EQUITY_ETF, DEBT, HYBRID, GOLD, SOLUTION_ORIENTED, FUND_OF_FUNDS,
                 OTHER, LEGACY_PRE_2018, UNCLASSIFIED -> BenchmarkAvailability.OUT_OF_SCOPE;
            default -> BenchmarkAvailability.NOT_HELD;
        };
    }

    private static String benchmarkNote(SebiCategory category, BenchmarkAvailability a) {
        return switch (a) {
            case HELD_EXACT -> "This category's own index is stored here, so a benchmark comparison "
                    + "is possible once enough index history is on file.";
            case HELD_PROXY -> "Only a near neighbour of this category's index is stored (the Nifty "
                    + "50 against a Nifty 100 mandate), and the substitution is named rather than "
                    + "hidden.";
            case HELD_BLEND -> "This category would be measured against a stated blend of two "
                    + "stored indices, with the weights shown.";
            case NOT_HELD -> "The right index for " + category.label() + " exists and this app has "
                    + "not loaded it. Substituting a midcap index for a small-cap mandate would be "
                    + "comparing two different things and calling the difference skill, so nothing "
                    + "is substituted. The category median below needs no index at all.";
            case NOT_SINGULAR -> "There is no single index for this category - roughly a thousand "
                    + "sectoral and thematic schemes each track a different one. This is a property "
                    + "of the question rather than a gap that will be filled later.";
            case OUT_OF_SCOPE -> "This category is judged on a different question. An index fund or "
                    + "an exchange-traded fund is measured on how closely it tracks its stated "
                    + "index, not on excess return over a category.";
        };
    }

    /**
     * Names the disagreement between the two readings instead of hiding it (Gotcha 105).
     *
     * <p>They answer different questions and are both right when they disagree. The rolling record
     * asks <em>how often, across everything on file, was this fund ahead?</em>; the category
     * standing asks <em>where did it finish over the latest three years?</em> On the live book,
     * one ELSS fund read behind its category in 47 of 66 windows and in the top quarter over the
     * most recent window - a fund that lagged for years and has been strong lately.
     *
     * <p>That gap is the finding, the way §41 reading "No" above §43's "Held up" is the
     * finding. The reader this app is written for will otherwise see two adjacent contradictory
     * badges and conclude the app is arguing with itself, so the divergence is explained in words
     * rather than reconciled into one number - which would destroy the distinction both readings
     * exist to draw.
     */
    static String divergenceNote(RollingWindowRecord.Record record,
                                         CategoryCrossSection standing, int monthsOnFile) {
        boolean recordAhead = record.verdict() == FundVocabulary.FundRecordVerdict.AHEAD_OF_BENCHMARK;
        boolean recordBehind = record.verdict() == FundVocabulary.FundRecordVerdict.BEHIND_BENCHMARK;
        boolean standingHigh = standing.standing() == FundVocabulary.CategoryStanding.UPPER_QUARTILE
                || standing.standing() == FundVocabulary.CategoryStanding.UPPER_HALF;
        boolean standingLow = standing.standing() == FundVocabulary.CategoryStanding.LOWER_QUARTILE
                || standing.standing() == FundVocabulary.CategoryStanding.LOWER_HALF;

        if (recordBehind && standingHigh) {
            return "These two readings disagree, and both are right. The record covers every "
                    + monthsOnFile + " months on file and asks how often this fund was ahead of its "
                    + "category; the standing covers the latest " + WINDOW_MONTHS + " months alone "
                    + "and asks where it finished. A fund that trailed for years and has been "
                    + "strong recently reads exactly like this. The gap between them is the "
                    + "finding, not an error - what it cannot tell you is whether the recent "
                    + "strength lasts.";
        }
        if (recordAhead && standingLow) {
            return "These two readings disagree, and both are right. The record covers every "
                    + monthsOnFile + " months on file; the standing covers only the latest "
                    + WINDOW_MONTHS + " months. A fund with a long good record that has fallen "
                    + "behind its peers lately reads exactly like this, and that is the reading "
                    + "worth paying attention to, because it is the one that is more recent.";
        }
        return null;
    }

    // ------------------------------------------------------------------ prose

    private static final String SAMPLE_NOTE =
            "windowsMeasured counts overlapping windows and is not a sample size. "
                    + "independentWindows is the one that behaves like a sample size, and over the "
                    + "history available it is small - no significance claim is made anywhere here, "
                    + "and the word \"consistently\" is withheld below two independent windows.";

    private static final String PORTFOLIO_CAVEAT =
            "Nothing on this screen is a suggestion to transact. A fund's record over rolling "
                    + "windows describes what already happened, and the single most reliable "
                    + "finding in fund research is that past return does not predict future return "
                    + "- which is why there is no score here, no ranking and no \"best funds\" "
                    + "list. The cost reading is the one figure here that does persist.";

    /**
     * The mandatory coverage line, over <b>four</b> states.
     *
     * <p>Each one means something different and folding any two of them flatters the total:
     * <em>measured</em> carries a verdict; <em>not comparable</em> was measured perfectly well and
     * has nothing like-for-like to grade it against (an income-distribution series, or a category
     * with too few peers); <em>not measured</em> has too little history yet; and <em>unresolved</em>
     * never matched an AMFI scheme.
     */
    private static String coverageLine(int total, int measured, int notComparable, int notMeasured,
                                       int unresolved) {
        if (total == 0) {
            return "No mutual fund holdings are on file. That means none have been synced from the "
                    + "broker, not that none are held.";
        }
        StringBuilder b = new StringBuilder();
        b.append(measured).append(" of ").append(total)
                .append(" fund holdings carry a measured record. ");
        if (notComparable > 0) {
            b.append(notComparable).append(" could be measured but had nothing like-for-like to be "
                    + "compared against - an income-distribution series, or a category with too few "
                    + "peers to rank inside. ");
        }
        if (notMeasured > 0) {
            b.append(notMeasured).append(" do not have enough month-end history yet. ");
        }
        if (unresolved > 0) {
            b.append(unresolved).append(" could not be matched to an AMFI scheme at all. ");
        }
        b.append("Every one of those three is a gap in this app, never a judgement on the fund.");
        return b.toString();
    }
}
