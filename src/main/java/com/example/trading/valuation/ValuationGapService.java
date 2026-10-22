package com.example.trading.valuation;

import com.example.trading.earnings.QuarterlyResultEntity;
import com.example.trading.earnings.QuarterlyResultRepository;
import com.example.trading.fundamentals.AnnualFundamentalsEntity;
import com.example.trading.fundamentals.AnnualFundamentalsRepository;
import com.example.trading.holdings.SymbolVariants;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The valuation-gap lens (SPEC §57). <b>DB-only</b>: no NSE call, no Kite call, no model call, so
 * it is safe on a page load and safe in a job that must not depend on the broker session.
 *
 * <p><b>One code path.</b> {@link #assemble} builds the reading from already-loaded inputs and is
 * the only place a reading is made. {@code GET /api/valuation-gap} and the snapshot job both call
 * it, so what is stored is exactly what the screen would have shown for the same inputs
 * (Gotcha 85). It is pure and static so tests can run it without a database or a Spring context.
 *
 * <p>Which rows answer is decided once, here, and nowhere else:
 * <ul>
 *   <li><b>Price row:</b> the newest screening row across the symbol's exchange spellings, at or
 *       before the latest <i>complete</i> screening date (B-150, Gotcha 107). A date is complete when
 *       it carries at least 90% of the rows of the largest recent run; single-stock screens write rows
 *       under today's date, and the table holds dates with 86, 2 and 1 rows.</li>
 *   <li><b>Annual rows:</b> the one spelling whose record ends latest, then runs longest, then comes
 *       first in {@link SymbolVariants#candidates} - never a blend, and never chosen <i>after</i> a
 *       fallback has filled its gaps (B-088).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValuationGapService {

    private final MultibaggerScoreRepository scoreRepository;
    private final AnnualFundamentalsRepository annualRepository;
    private final QuarterlyResultRepository quarterlyRepository;

    /** How far back a screening row may sit and still be looked up. */
    private static final int ROW_LOOKBACK_DAYS = 400;
    /** The window of screening dates considered when deciding which date is complete. */
    private static final int COMPLETENESS_WINDOW_DAYS = 14;

    // ------------------------------------------------------------------------------------ inputs

    /** The facts of one screening row the lens reads. */
    public record ScoreFacts(String symbol, LocalDate screeningDate, double price, Double marketCapCr,
                             String industry, String dcfVerdict, Double dcfImpliedGrowthPct,
                             String scoringVersion) {
        static ScoreFacts of(MultibaggerScoreEntity m) {
            return new ScoreFacts(m.getSymbol(), m.getScreeningDate(), m.getCurrentPrice(), m.getMarketCapCrores(),
                    m.getIndustry(), m.getDcfVerdict(), m.getDcfImpliedGrowthPercent(), m.getScoringVersion());
        }
    }

    /** Everything {@link #assemble} needs. {@code score} is null for a never-screened stock. */
    public record Inputs(String requested, String annualSymbol, LocalDate asOf, ScoreFacts score,
                         List<AnnualFundamentalsEntity> annual, List<QuarterlyResultEntity> quarters,
                         boolean naFinancial) {
    }

    // -------------------------------------------------------------------------- complete screening date

    /**
     * The latest screening date that carries at least {@link ValuationGapRules#COMPLETE_DATE_SHARE} of
     * the rows of the largest run in the last fortnight. Empty when no screening ran in that window.
     */
    public Optional<LocalDate> latestCompleteDate(LocalDate asOf) {
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        for (LocalDate d : scoreRepository.findScreeningDates()) {
            if (d.isBefore(asOf.minusDays(COMPLETENESS_WINDOW_DAYS)) || d.isAfter(asOf)) continue;
            counts.put(d, scoreRepository.countByScreeningDate(d));
        }
        return pickCompleteDate(counts);
    }

    /** Pure: {@code counts} maps each screening date in the window to its row count. */
    static Optional<LocalDate> pickCompleteDate(Map<LocalDate, Long> counts) {
        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        if (max <= 0) return Optional.empty();
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= ValuationGapRules.COMPLETE_DATE_SHARE * max)
                .map(Map.Entry::getKey)
                .max(Comparator.naturalOrder());
    }

    // ----------------------------------------------------------------------------------- one stock

    /**
     * The reading for one stock, or empty (HTTP 404) when it is in neither the screening table nor
     * the annual-accounts table under any spelling - "unknown here" is a different fact from
     * "refused", and only the second is a 200 (SPEC §57.7).
     */
    public Optional<ValuationReading> read(String symbol, LocalDate asOf) {
        List<String> candidates = SymbolVariants.candidates(symbol);
        if (candidates.isEmpty()) return Optional.empty();
        LocalDate complete = latestCompleteDate(asOf).orElse(null);

        List<MultibaggerScoreEntity> recent =
                scoreRepository.findLatestForSymbolsSince(candidates, asOf.minusDays(ROW_LOOKBACK_DAYS));
        List<AnnualFundamentalsEntity> annualAll = annualRepository.findHistoryForSymbols(candidates);
        if (recent.isEmpty() && annualAll.isEmpty()) return Optional.empty();

        MultibaggerScoreEntity row = pickScoreRow(recent, candidates, complete);
        String annualSymbol = pickAnnualSpelling(annualAll, candidates);
        List<AnnualFundamentalsEntity> annual = annualAll.stream()
                .filter(a -> a.getSymbol().equals(annualSymbol)).collect(Collectors.toList());
        List<QuarterlyResultEntity> quarters = quarterlyRepository.findForSymbols(candidates);
        boolean na = recent.stream().anyMatch(r -> "NA_FINANCIAL".equals(r.getCapexVerdict()));

        return Optional.of(assemble(new Inputs(symbol, annualSymbol, asOf,
                row == null ? null : ScoreFacts.of(row), annual, quarters, na)));
    }

    // ------------------------------------------------------------------------------------ the batch

    /** The readings produced for a screening date and how many stocks failed to produce one. */
    public record Batch(List<ValuationReading> readings, int failed) {
    }

    /**
     * Readings for every stock on a screening date, in bulk, for the snapshot job and {@code /coverage}.
     * One stock that appears under both exchange spellings is read once, under the NSE spelling.
     */
    public Batch readAll(LocalDate screeningDate, LocalDate asOf) {
        List<MultibaggerScoreEntity> rows = scoreRepository.findByScreeningDateOrderByCompositeScoreDesc(screeningDate);
        Map<String, MultibaggerScoreEntity> byBase = new LinkedHashMap<>();
        for (MultibaggerScoreEntity r : rows) {
            String base = base(r.getSymbol());
            MultibaggerScoreEntity held = byBase.get(base);
            if (held == null || (!held.getSymbol().startsWith("NSE:") && r.getSymbol().startsWith("NSE:"))) {
                byBase.put(base, r);
            }
        }
        List<String> everySpelling = new ArrayList<>();
        for (MultibaggerScoreEntity r : byBase.values()) {
            for (String c : SymbolVariants.candidates(r.getSymbol())) {
                if (!everySpelling.contains(c)) everySpelling.add(c);
            }
        }
        if (everySpelling.isEmpty()) return new Batch(List.of(), 0);

        Map<String, List<AnnualFundamentalsEntity>> annualBySymbol = annualRepository
                .findHistoryForSymbols(everySpelling).stream()
                .collect(Collectors.groupingBy(AnnualFundamentalsEntity::getSymbol));
        Map<String, List<QuarterlyResultEntity>> quartersBySymbol = quarterlyRepository
                .findForSymbols(everySpelling).stream()
                .collect(Collectors.groupingBy(QuarterlyResultEntity::getSymbol));
        Map<String, MultibaggerScoreEntity> newestBySymbol = scoreRepository
                .findLatestForSymbolsSince(everySpelling, asOf.minusDays(ROW_LOOKBACK_DAYS)).stream()
                .collect(Collectors.toMap(MultibaggerScoreEntity::getSymbol, m -> m, (a, b) -> a));

        List<ValuationReading> out = new ArrayList<>();
        int failed = 0;
        for (MultibaggerScoreEntity row : byBase.values()) {
            List<String> candidates = SymbolVariants.candidates(row.getSymbol());
            List<AnnualFundamentalsEntity> annualAll = new ArrayList<>();
            List<QuarterlyResultEntity> quarters = new ArrayList<>();
            boolean na = false;
            for (String c : candidates) {
                annualAll.addAll(annualBySymbol.getOrDefault(c, List.of()));
                quarters.addAll(quartersBySymbol.getOrDefault(c, List.of()));
                MultibaggerScoreEntity newest = newestBySymbol.get(c);
                if (newest != null && "NA_FINANCIAL".equals(newest.getCapexVerdict())) na = true;
            }
            if ("NA_FINANCIAL".equals(row.getCapexVerdict())) na = true;
            String annualSymbol = pickAnnualSpelling(annualAll, candidates);
            List<AnnualFundamentalsEntity> annual = annualAll.stream()
                    .filter(a -> a.getSymbol().equals(annualSymbol)).collect(Collectors.toList());
            try {
                out.add(assemble(new Inputs(row.getSymbol(), annualSymbol, asOf, ScoreFacts.of(row), annual, quarters, na)));
            } catch (RuntimeException e) {
                // One malformed stock must not discard the other 300: it is logged and left out,
                // and the snapshot job counts it as failed rather than writing a guess.
                log.error("Valuation-gap reading failed for {}: {}", row.getSymbol(), e.toString());
                failed++;
            }
        }
        return new Batch(out, failed);
    }

    // ------------------------------------------------------------------------------- row selection

    /** Newest row at or before the complete date; an exact-spelling row wins a tie. Never a blend. */
    static MultibaggerScoreEntity pickScoreRow(List<MultibaggerScoreEntity> rows, List<String> candidates,
                                               LocalDate complete) {
        MultibaggerScoreEntity best = null;
        for (String candidate : candidates) {
            for (MultibaggerScoreEntity r : rows) {
                if (!candidate.equals(r.getSymbol())) continue;
                if (complete != null && r.getScreeningDate().isAfter(complete)) continue;
                if (best == null || r.getScreeningDate().isAfter(best.getScreeningDate())) best = r;
            }
        }
        return best;
    }

    /**
     * The spelling whose annual record ends latest, then has the most years, then comes first.
     * Null when no spelling has a row.
     */
    static String pickAnnualSpelling(List<AnnualFundamentalsEntity> rows, List<String> candidates) {
        String best = null;
        int bestLast = -1;
        int bestCount = -1;
        for (String candidate : candidates) {
            int last = -1;
            int count = 0;
            for (AnnualFundamentalsEntity a : rows) {
                if (!candidate.equals(a.getSymbol()) || a.getFiscalYear() == null) continue;
                count++;
                last = Math.max(last, a.getFiscalYear());
            }
            if (count == 0) continue;
            if (last > bestLast || (last == bestLast && count > bestCount)) {
                best = candidate;
                bestLast = last;
                bestCount = count;
            }
        }
        return best;
    }

    private static String base(String symbol) {
        int i = symbol.indexOf(':');
        return i >= 0 ? symbol.substring(i + 1) : symbol;
    }

    // ------------------------------------------------------------------------------------ assembly

    private static final List<String> STANDING_CAVEATS = List.of(
            "This states what would have to be true for today's price. It does not say what will happen and "
                    + "it is not advice to transact.",
            "Earnings per share here is stored net profit divided by year-end shares, with bonus issues and "
                    + "splits divided out. Stored net profit includes the minority share, so it can differ from "
                    + "reported earnings per share for a group with large minority interests. Only the latest year "
                    + "is checked against reported quarterly results; earlier years are not.",
            "The price is the screening price on the date shown, not a live quote.",
            "Stored figures have not been compared with the filings one by one.");

    /** Pure and static: the only place a reading is made. */
    static ValuationReading assemble(Inputs in) {
        ScoreFacts score = in.score();
        LocalDate d = score == null ? null : score.screeningDate();
        EpsSeries.Built built = EpsSeries.build(in.annual(),
                FiscalHorizon.requiredLastFiscalYear(d != null ? d : in.asOf()));

        ValuationReading.ValuationReadingBuilder b = ValuationReading.builder()
                .symbol(in.requested())
                .resolvedFrom(in.annualSymbol())
                .ruleset(ValuationGapRules.RULESET)
                .fingerprint(ValuationGapRules.fingerprint())
                .valuationDate(d)
                .price(score != null && score.price() > 0 ? score.price() : null)
                .priceNote("Screening price on the date shown, not a live quote")
                .caveats(STANDING_CAVEATS);

        if (score == null) {
            return refuse(b, in, built, ValuationReading.Reason.NEVER_SCREENED,
                    "This stock has annual accounts on file but has never been through the screening, so there "
                            + "is no price and no classification to read it against.");
        }

        FinancialSectorGate.Result sector = FinancialSectorGate.check(score.symbol(), score.industry(), in.naFinancial());
        if (!sector.passes()) return refuse(b, in, built, sector.reason(), sector.text());

        if (score.price() <= 0 || score.marketCapCr() == null || score.marketCapCr() <= 0) {
            return refuse(b, in, built, ValuationReading.Reason.NO_PRICE_ON_FILE,
                    "The screening row carries no usable price or market capitalisation.");
        }
        if (d.isBefore(in.asOf().minusDays(ValuationGapRules.MAX_PRICE_AGE_DAYS))) {
            return refuse(b, in, built, ValuationReading.Reason.STALE_PRICE,
                    "The newest complete screening price is from " + d + ", more than "
                            + ValuationGapRules.MAX_PRICE_AGE_DAYS + " days before " + in.asOf() + ".");
        }

        EpsSeries.Year latest = built.latest();
        boolean shareBasisCheckable = latest != null && latest.shareCountCr() != null
                && (built.usable() || built.reason() == ValuationReading.Reason.LOSS_IN_RECORD);
        if (shareBasisCheckable) {
            Double gap = EpsSeries.shareBasisGap(score.marketCapCr(), score.price(), latest.shareCountCr());
            if (gap != null) {
                return refuse(b, in, built, ValuationReading.Reason.SHARE_BASIS_MISMATCH,
                        String.format("Market capitalisation over price implies %.1f%% %s shares than the %.3f crore "
                                        + "in the FY%d accounts - a bonus issue or split after the year end, or a "
                                        + "unit slip. Today's price would sit on a different share basis from the "
                                        + "earnings it is compared with.",
                                Math.abs(gap) * 100, gap > 0 ? "more" : "fewer", latest.shareCountCr(),
                                latest.fiscalYear()));
            }
        }
        if (!built.usable()) return refuse(b, in, built, built.reason(), built.reasonText());

        NormalisedEarnings.Fit fit = NormalisedEarnings.fit(built.years());
        EpsSeries.CrossCheck cross = EpsSeries.crossCheck(in.quarters(), built.latest().fiscalYear(),
                built.consolidated(), built.latest().eps());
        if (!cross.passed()) return refuse(b, in, built, cross.reason(), cross.reasonText());

        if (!FiscalHorizon.hasRunway(d)) {
            return refuse(b, in, built, ValuationReading.Reason.HORIZON_TOO_SHORT,
                    "Fewer than " + FiscalHorizon.MIN_RUNWAY_DAYS + " days remain to 31 March "
                            + FiscalHorizon.REFERENCE_FISCAL_YEAR + "; the table would describe a stub, not a horizon.");
        }

        return measured(b, in, score, built, fit, cross);
    }

    private static ValuationReading measured(ValuationReading.ValuationReadingBuilder b, Inputs in, ScoreFacts score,
                                             EpsSeries.Built built, NormalisedEarnings.Fit fit,
                                             EpsSeries.CrossCheck cross) {
        LocalDate d = score.screeningDate();
        int baseFy = fit.lastFiscalYear();
        int n = FiscalHorizon.growthYears(baseFy);
        double t = FiscalHorizon.yearsFrom(d);
        double price = score.price();

        ValuationReading.TrendComparison comparison = RequiredGrowthGrid.trendComparison(price, fit);
        RequiredGrowthGrid.Grid grid = RequiredGrowthGrid.grid(price, fit.normalisedEps(), n, t,
                fit.reliable() ? fit.trendGrowthPct() : null);

        boolean dcfUsable = score.dcfImpliedGrowthPct() != null && score.dcfVerdict() != null
                && !"NOT_APPLICABLE".equals(score.dcfVerdict()) && !"INSUFFICIENT_DATA".equals(score.dcfVerdict());
        ValuationReading.Section125 s125 = dcfUsable ? RequiredGrowthGrid.section125(score.dcfImpliedGrowthPct()) : null;

        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("A", "AVAILABLE");
        blocks.put("B", "AVAILABLE");
        blocks.put("C", "AVAILABLE");
        ValuationReading.Reason withheld = null;
        String withheldText = null;
        if (fit.reliable()) {
            blocks.put("D", "AVAILABLE");
        } else {
            blocks.put("D", withheld(ValuationReading.Reason.TREND_UNRELIABLE, comparison.withheldReason()));
            withheld = ValuationReading.Reason.TREND_UNRELIABLE;
            withheldText = comparison.withheldReason();
        }
        blocks.put("E", "AVAILABLE");
        if (s125 != null) {
            blocks.put("F", "AVAILABLE");
        } else {
            String why = "The existing reverse-DCF reading is not available for this stock ("
                    + (score.dcfVerdict() == null ? "none stored" : score.dcfVerdict()) + ").";
            blocks.put("F", withheld(ValuationReading.Reason.NO_SECTION_125, why));
            if (withheld == null) {
                withheld = ValuationReading.Reason.NO_SECTION_125;
                withheldText = why;
            }
        }
        blocks.put("G", "AVAILABLE");

        List<ValuationReading.AppliedRule> applied = new ArrayList<>();
        if (!built.actions().isEmpty()) {
            applied.add(new ValuationReading.AppliedRule("VG-S1", built.actions().size()
                    + " bonus issue(s) or split(s) were divided out of the share count."));
        }
        if (!fit.flags().isEmpty()) {
            applied.add(new ValuationReading.AppliedRule("VG-N1",
                    "The latest year's earnings per share is more than " + ValuationGapRules.PEAK_FACTOR
                            + "x the fitted value, so the fitted value is the starting point."));
        }
        applied.add(new ValuationReading.AppliedRule("VG-R1", fit.observations() + " years, FY" + fit.firstFiscalYear()
                + " to FY" + fit.lastFiscalYear() + ", on one reporting basis."));
        if (!fit.reliable()) {
            applied.add(new ValuationReading.AppliedRule("VG-T1", "A reliability gate failed, so the FY"
                    + FiscalHorizon.REFERENCE_FISCAL_YEAR + " historical-trend comparison is withheld."));
        }
        applied.add(new ValuationReading.AppliedRule("VG-M1", "Reference multiples are grid columns only."));
        applied.add(new ValuationReading.AppliedRule("VG-H1", "Hurdle "
                + Math.round(ValuationGapRules.HURDLE_RATE * 100) + "% a year, price return only."));
        applied.add(new ValuationReading.AppliedRule("VG-H2", n + " growth years from FY" + baseFy
                + "; " + String.format("%.2f", t) + " years from " + d + " to 31 March "
                + FiscalHorizon.REFERENCE_FISCAL_YEAR + "."));

        EpsSeries.Year latest = built.latest();
        ValuationReading.Trend trend = new ValuationReading.Trend(fit.trendGrowthPct(), fit.stdErrPp(),
                fit.observations(), fit.firstFiscalYear(), fit.lastFiscalYear(), fit.rSquared(),
                fit.endpointCagrPct(),
                new ValuationReading.Gates(fit.stdErrGatePasses() ? "ok" : "fail",
                        fit.rSquaredGatePasses() ? "ok" : "fail"),
                fit.reliable());
        ValuationReading.Earnings earnings = new ValuationReading.Earnings(rows(built.years()),
                actions(built), fit.normalisedEps(),
                new ValuationReading.Normalisation("VG-N1", fit.flags()),
                new ValuationReading.CrossCheckView(cross.quarterlyEpsSum(), cross.ratio(),
                        ValuationGapRules.CROSSCHECK_TOLERANCE, cross.passed()),
                trend, PROVENANCE);

        ValuationReading.Capture capture = new ValuationReading.Capture(d, in.annualSymbol(), score.scoringVersion(),
                price, score.marketCapCr(), price / fit.normalisedEps(),
                baseFy, fit.firstFiscalYear(), fit.observations(), built.consolidated(),
                latest.eps(), fit.normalisedEps(), fit.flags().isEmpty() ? null : String.join(",", fit.flags()),
                fit.intercept(), fit.slope(), fit.trendGrowthPct(), fit.stdErrPp(), fit.rSquared(),
                fit.reliable() ? ValuationReading.TrendCohort.GATES_PASSED : ValuationReading.TrendCohort.GATES_WITHHELD,
                fit.endpointCagrPct(), latest.shareCountCr(), latest.faceValue(),
                score.dcfImpliedGrowthPct(), cross.ratio(), built.years(), built.actions());

        boolean full = withheld == null;
        String coverage = fit.observations() + " years of accounts (FY" + fit.firstFiscalYear() + " to FY"
                + fit.lastFiscalYear() + ") on a " + (built.consolidated() ? "consolidated" : "standalone")
                + " basis; " + built.actions().size() + " bonus issue(s) or split(s) divided out; the latest year "
                + "agrees with the sum of its four reported quarters.";

        return b.status(full ? ValuationReading.Status.MEASURED : ValuationReading.Status.PARTIAL)
                .reasonCode(withheld)
                .reason(withheldText)
                .horizon(new ValuationReading.Horizon("FY" + FiscalHorizon.REFERENCE_FISCAL_YEAR,
                        FiscalHorizon.REFERENCE_YEAR_ENDS, t, n, baseFy))
                .basis(new ValuationReading.Basis(built.consolidated(), fit.observations(), fit.firstFiscalYear(),
                        fit.lastFiscalYear(), fit.observations() - 1))
                .earnings(earnings)
                .trendComparison(comparison)
                .requiredGrowthGrid(grid)
                .section125Reading(s125)
                .blocks(blocks)
                .appliedRules(applied)
                .differenceBetweenMethods(RequiredGrowthGrid.DIFFERENCE_BETWEEN_METHODS)
                .coverageLine(coverage)
                .capture(capture)
                .build();
    }

    private static final String PROVENANCE =
            "Net profit, share count, face value and reporting basis are REPORTED (annual_fundamentals, from "
                    + "NSE filings). The share factor and earnings per share are DERIVED. The hurdle rate, the "
                    + "reference year and the reference multiples are ASSUMPTIONS stated on screen.";

    private static Map<String, Object> withheld(ValuationReading.Reason reason, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("withheld", true);
        m.put("reason", reason.name());
        m.put("detail", detail);
        return m;
    }

    private static List<ValuationReading.SeriesRow> rows(List<EpsSeries.Year> years) {
        List<ValuationReading.SeriesRow> out = new ArrayList<>();
        for (EpsSeries.Year y : years) {
            out.add(new ValuationReading.SeriesRow(y.fiscalYear(), y.netProfitCr(), y.shareCountCr(), y.faceValue(),
                    y.shareFactor(), y.eps(), y.availableFrom(), y.availableFromIsInferred()));
        }
        return out;
    }

    private static List<ValuationReading.CorporateAction> actions(EpsSeries.Built built) {
        return built.actions().stream().map(ValuationReading.CorporateAction::of).collect(Collectors.toList());
    }

    /** A refusal: status, one closed reason, the failing number in words, and the raw record that explains it. */
    private static ValuationReading refuse(ValuationReading.ValuationReadingBuilder b, Inputs in, EpsSeries.Built built,
                                           ValuationReading.Reason reason, String text) {
        ScoreFacts score = in.score();
        Map<String, Object> blocks = new LinkedHashMap<>();
        boolean haveSeries = !built.years().isEmpty();
        blocks.put("A", haveSeries ? "AVAILABLE" : withheld(reason, text));
        for (String k : List.of("B", "C", "D", "E", "F")) blocks.put(k, withheld(reason, text));
        blocks.put("G", "AVAILABLE");

        ValuationReading.Earnings earnings = haveSeries
                ? new ValuationReading.Earnings(rows(built.years()), actions(built), null, null, null, null, PROVENANCE)
                : null;
        EpsSeries.Year latest = built.latest();
        ValuationReading.Capture capture = score == null ? null : new ValuationReading.Capture(
                score.screeningDate(), in.annualSymbol(), score.scoringVersion(),
                score.price() > 0 ? score.price() : null, score.marketCapCr(), null,
                latest == null ? null : latest.fiscalYear(),
                built.years().isEmpty() ? null : built.years().get(0).fiscalYear(),
                built.years().isEmpty() ? null : built.years().size(), built.consolidated(),
                latest == null ? null : latest.eps(), null, null,
                null, null, null, null, null, ValuationReading.TrendCohort.NOT_FITTED, null,
                latest == null ? null : latest.shareCountCr(), latest == null ? null : latest.faceValue(),
                score.dcfImpliedGrowthPct(), null, built.years(), built.actions());

        return b.status(ValuationReading.Status.UNAVAILABLE)
                .reasonCode(reason)
                .reason(text)
                .earnings(earnings)
                .blocks(blocks)
                .appliedRules(List.of())
                .coverageLine("Not read: " + text)
                .capture(capture)
                .build();
    }

    // -------------------------------------------------------------------------------------- coverage

    /** The funnel over the latest complete screening run, counted by first failing gate. */
    public record Coverage(LocalDate valuationDate, int screenedStocks, int measured, int partial, int unavailable,
                           Map<String, Integer> firstFailingGate, int trendWithheld, Double trendWithheldSharePct,
                           LocalDateTime computedAt, String note) {
    }

    private volatile Coverage cached;
    private volatile LocalDateTime cachedAt;

    public Optional<Coverage> coverage(LocalDate asOf) {
        LocalDateTime now = LocalDateTime.now();
        Coverage c = cached;
        if (c != null && cachedAt != null && cachedAt.plusMinutes(10).isAfter(now)
                && asOf.equals(c.computedAt().toLocalDate())) {
            return Optional.of(c);
        }
        Optional<LocalDate> date = latestCompleteDate(asOf);
        if (date.isEmpty()) return Optional.empty();
        List<ValuationReading> all = readAll(date.get(), asOf).readings();
        Coverage fresh = summarise(date.get(), all, now);
        cached = fresh;
        cachedAt = now;
        return Optional.of(fresh);
    }

    /** Pure. */
    static Coverage summarise(LocalDate date, List<ValuationReading> all, LocalDateTime now) {
        int measured = 0, partial = 0, unavailable = 0, trendWithheld = 0;
        Map<ValuationReading.Reason, Integer> reasons = new EnumMap<>(ValuationReading.Reason.class);
        for (ValuationReading r : all) {
            switch (r.status()) {
                case MEASURED -> measured++;
                case PARTIAL -> partial++;
                case UNAVAILABLE -> {
                    unavailable++;
                    reasons.merge(r.reasonCode(), 1, Integer::sum);
                }
            }
            if (r.reasonCode() == ValuationReading.Reason.TREND_UNRELIABLE
                    || (r.trendComparison() != null && r.trendComparison().withheldReason() != null)) {
                trendWithheld++;
            }
        }
        Map<String, Integer> byGate = new LinkedHashMap<>();
        reasons.forEach((k, v) -> byGate.put(k.name(), v));
        int readable = measured + partial;
        Double share = readable == 0 ? null : trendWithheld * 100.0 / readable;
        return new Coverage(date, all.size(), measured, partial, unavailable, byGate, trendWithheld, share, now,
                "Counts are by the first gate a stock failed. A stock that is refused is outside what this lens "
                        + "can read; it is a statement about the data, never about the business.");
    }
}
