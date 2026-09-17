package com.example.trading.portfolio.performance;

import com.example.trading.holdings.SymbolVariants;
import com.example.trading.multibagger.CompoundingLensService;
import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsHistoryRepository;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.persistence.MultibaggerScoreEntity;
import com.example.trading.persistence.MultibaggerScoreRepository;
import com.example.trading.portfolio.dividend.DividendDto;
import com.example.trading.portfolio.dividend.DividendService;
import com.example.trading.portfolio.tax.TaxLotDto;
import com.example.trading.portfolio.tax.TaxLotEntity;
import com.example.trading.portfolio.tax.TaxLotRepository;
import com.example.trading.portfolio.tax.TaxLotSaleEntity;
import com.example.trading.portfolio.tax.TaxLotSaleRepository;
import com.example.trading.portfolio.tax.TaxLotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Composes the portfolio-level truth the page lacked (SPEC §46): a time-weighted return against
 * two benchmarks, total return in rupees, drawdown, cash, and how much of the book the tax
 * ledger actually covers. DB-only; every read here is safe on a page load (SPEC §27.4).
 *
 * <p>This class does no arithmetic of its own - {@link PerformanceMath} is pure and tested - and
 * it attaches a coverage note to every figure, because the figures here are exactly the ones a
 * reader will act on and the ones most likely to rest on partial data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioPerformanceService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int SCORE_LOOKBACK_DAYS = 45;
    /** A weighted metric is withheld when it was measured on less than this share of the book. */
    private static final double MIN_COVERAGE_FOR_WEIGHTED = 50.0;

    private final HoldingsRepository holdingsRepository;
    private final HoldingsHistoryRepository holdingsHistoryRepository;
    private final BenchmarkCloseRepository benchmarkRepository;
    private final PortfolioCashSnapshotRepository cashRepository;
    private final TaxLotRepository taxLotRepository;
    private final TaxLotSaleRepository taxLotSaleRepository;
    private final TaxLotService taxLotService;
    private final DividendService dividendService;
    private final MultibaggerScoreRepository scoreRepository;
    private final CompoundingLensService compoundingLensService;

    // ------------------------------------------------------------------ performance

    public PerformanceDto.PerformanceResponse performance(int days) {
        LocalDate today = LocalDate.now(IST);
        LocalDate from = today.minusDays(days);
        List<String> caveats = new ArrayList<>();

        // 1. Portfolio series from the daily snapshots.
        List<PerformanceMath.Day> series = new ArrayList<>();
        for (Object[] row : holdingsHistoryRepository.findPortfolioSeries(from)) {
            series.add(new PerformanceMath.Day((LocalDate) row[0], toDouble(row[1]), toDouble(row[2])));
        }
        Map<LocalDate, Double> realisedByDate = new HashMap<>();
        if (!series.isEmpty()) {
            for (TaxLotSaleEntity s : taxLotSaleRepository.findBySellDateBetween(series.get(0).date(), today)) {
                realisedByDate.merge(s.getSellDate(), s.getRealizedGain(), Double::sum);
            }
        }
        PerformanceMath.TwrResult twr = PerformanceMath.timeWeighted(series, realisedByDate);
        if (twr.twrPercent() == null) {
            caveats.add("Fewer than two daily snapshots in this window, so no time-weighted return can be computed yet.");
        } else if (twr.annualisedPercent() == null) {
            caveats.add(String.format("Only %d days of history: the annualised figure is withheld until %d.",
                    twr.daysSpanned(), PerformanceMath.MIN_DAYS_TO_ANNUALISE));
        }
        if (twr.flowsUncorrected() > 0) {
            caveats.add(String.format("On %d day%s the cost basis fell with no lot-matched sale on record, so the "
                    + "gain realised on those sales is not in the time-weighted return (it reads slightly low). "
                    + "Importing the Zerodha tradebook fixes this.",
                    twr.flowsUncorrected(), twr.flowsUncorrected() == 1 ? "" : "s"));
        }

        // 2. Benchmarks over the same span.
        LocalDate spanFrom = series.isEmpty() ? from : series.get(0).date();
        LocalDate spanTo = series.isEmpty() ? today : series.get(series.size() - 1).date();
        List<PerformanceDto.BenchmarkRead> benchmarks = new ArrayList<>();
        Map<LocalDate, Double> nifty = closes(PortfolioSnapshotService.NIFTY_50, spanFrom, spanTo);
        Map<LocalDate, Double> midcap = closes(PortfolioSnapshotService.NIFTY_MIDCAP_150, spanFrom, spanTo);
        benchmarks.add(benchmarkRead(PortfolioSnapshotService.NIFTY_50, "Nifty 50", nifty, spanFrom, spanTo, twr.daysSpanned()));
        benchmarks.add(benchmarkRead(PortfolioSnapshotService.NIFTY_MIDCAP_150, "Nifty Midcap 150", midcap, spanFrom, spanTo, twr.daysSpanned()));
        if (nifty.isEmpty() && midcap.isEmpty()) {
            caveats.add("No benchmark closes are stored yet. Run the benchmark backfill once (POST /api/portfolio/benchmark/backfill); "
                    + "after that the 15:00 snapshot keeps it current.");
        }

        Double excessNifty = excess(twr.twrPercent(), benchmarks.get(0).returnPercent());
        Double excessMidcap = excess(twr.twrPercent(), benchmarks.get(1).returnPercent());

        // 3. Indexed lines for the chart, on the union of dates.
        List<PerformanceDto.IndexedPoint> indexed = indexedLines(twr.indexed(), nifty, midcap);

        // 4. Headline simple gain, total return, cash, lot coverage.
        List<HoldingsEntity> active = holdingsRepository.findActive();
        double invested = active.stream().mapToDouble(HoldingsEntity::getInvestedValue).sum();
        double value = active.stream().mapToDouble(HoldingsEntity::getCurrentValue).sum();
        double unrealised = value - invested;
        Double simple = invested > 0 ? PerformanceMath.round2(unrealised / invested * 100.0) : null;

        PerformanceDto.TotalReturn total = totalReturn(unrealised, today);
        PerformanceDto.CashRead cash = cash(value);
        PerformanceDto.LotCoverage lots = lotCoverage(active);
        if (lots.holdingsWithLots() < lots.holdings()) {
            caveats.add(String.format("Tax lots exist for %d of %d holdings; holding period and realised gains are "
                    + "known only for those.", lots.holdingsWithLots(), lots.holdings()));
        }

        String method = "Time-weighted: each day's change in market value with that day's purchases and sales removed, "
                + "chain-linked, so adding or withdrawing money does not move it. Sales are taken at cost plus the "
                + "lot-matched realised gain where one is on record. Benchmarks are point-to-point over the same dates. "
                + "Drawdown is measured on the chain-linked series, so a withdrawal is not a fall.";

        return new PerformanceDto.PerformanceResponse(
                spanFrom, spanTo, days, twr.snapshots(), twr.daysSpanned(),
                simple, twr.twrPercent(), twr.annualisedPercent(), twr.flowsCorrected(), twr.flowsUncorrected(),
                benchmarks, excessNifty, excessMidcap,
                PerformanceMath.drawdown(twr.indexed()), indexed, total, cash, lots, method, caveats);
    }

    private Map<LocalDate, Double> closes(String symbol, LocalDate from, LocalDate to) {
        Map<LocalDate, Double> out = new TreeMap<>();
        try {
            for (BenchmarkCloseEntity b : benchmarkRepository
                    .findBySymbolAndCloseDateBetweenOrderByCloseDateAsc(symbol, from.minusDays(7), to)) {
                out.put(b.getCloseDate(), b.getClose());
            }
        } catch (Exception e) {
            log.warn("Benchmark closes unavailable for {} - the 'vs index' figure will read not measured: {}",
                    symbol, e.getMessage());
        }
        return out;
    }

    /** First close on or after {@code from} (within a week) to the last close on or before {@code to}. */
    private static PerformanceDto.BenchmarkRead benchmarkRead(String symbol, String label, Map<LocalDate, Double> closes,
                                                              LocalDate from, LocalDate to, long spanDays) {
        if (closes.isEmpty()) {
            return new PerformanceDto.BenchmarkRead(symbol, label, from, to, null, null, 0,
                    "No stored closes for this index in the window.");
        }
        // The first close ON OR AFTER the portfolio's first snapshot, so both series start on the
        // same day; only when none exists is the last close before it used (a holiday start).
        LocalDate first = null;
        LocalDate before = null;
        LocalDate last = null;
        for (LocalDate d : closes.keySet()) {
            if (d.isBefore(from)) before = d;
            else if (first == null) first = d;
            if (!d.isAfter(to)) last = d;
        }
        if (first == null) first = before;
        if (first == null || last == null || !last.isAfter(first)) {
            return new PerformanceDto.BenchmarkRead(symbol, label, from, to, null, null, closes.size(),
                    "Stored closes do not span the portfolio's window; run the benchmark backfill.");
        }
        Double ret = PerformanceMath.pctChange(closes.get(first), closes.get(last));
        long days = java.time.temporal.ChronoUnit.DAYS.between(first, last);
        return new PerformanceDto.BenchmarkRead(symbol, label, first, last, ret,
                PerformanceMath.annualise(ret, days), closes.size(),
                Math.abs(days - spanDays) > 7 ? String.format("Index measured over %d days, portfolio over %d.", days, spanDays) : null);
    }

    private static Double excess(Double portfolio, Double benchmark) {
        if (portfolio == null || benchmark == null) return null;
        return PerformanceMath.round2(portfolio - benchmark);
    }

    private static List<PerformanceDto.IndexedPoint> indexedLines(List<PerformanceMath.IndexPoint> portfolio,
                                                                  Map<LocalDate, Double> nifty,
                                                                  Map<LocalDate, Double> midcap) {
        List<PerformanceDto.IndexedPoint> out = new ArrayList<>();
        if (portfolio == null || portfolio.isEmpty()) return out;
        LocalDate start = portfolio.get(0).date();
        Double niftyBase = baseOnOrAfter(nifty, start);
        Double midBase = baseOnOrAfter(midcap, start);
        Map<LocalDate, Double> pf = new HashMap<>();
        for (PerformanceMath.IndexPoint p : portfolio) pf.put(p.date(), p.index());
        Set<LocalDate> dates = new java.util.TreeSet<>(pf.keySet());
        for (LocalDate d : dates) {
            Double n = niftyBase == null ? null : rebased(nifty.get(d), niftyBase);
            Double m = midBase == null ? null : rebased(midcap.get(d), midBase);
            out.add(new PerformanceDto.IndexedPoint(d, pf.get(d), n, m));
        }
        return out;
    }

    private static Double baseOnOrAfter(Map<LocalDate, Double> closes, LocalDate start) {
        Double before = null;
        for (Map.Entry<LocalDate, Double> e : closes.entrySet()) {
            if (e.getKey().isBefore(start)) before = e.getValue();
            else return e.getValue();
        }
        return before;
    }

    private static Double rebased(Double close, double base) {
        if (close == null || base <= 0) return null;
        return PerformanceMath.round2(close / base * 100.0);
    }

    private PerformanceDto.TotalReturn totalReturn(double unrealised, LocalDate today) {
        Double stcg = null;
        Double ltcg = null;
        try {
            TaxLotDto.HarvestResponse h = taxLotService.computeHarvestSuggestions();
            stcg = h.fiscalYearRealizedStcg();
            ltcg = h.fiscalYearRealizedLtcg();
        } catch (Exception e) {
            log.warn("Realised gains unavailable - total return will show unrealised only: {}", e.getMessage());
        }
        int fy = today.getMonth().getValue() >= Month.APRIL.getValue() ? today.getYear() : today.getYear() - 1;
        double dividends = 0.0;
        int events = 0;
        try {
            DividendDto.AnnualSummary s = dividendService.annualSummary(fy);
            dividends = s.totalReceived();
            events = s.eventCount();
        } catch (Exception e) {
            log.warn("Dividend summary unavailable - total return excludes dividends: {}", e.getMessage());
        }
        Double total = stcg == null || ltcg == null ? null : PerformanceMath.round2(unrealised + stcg + ltcg + dividends);
        String note = events == 0
                ? "No dividends have been logged this financial year, so dividend income reads as zero here - which "
                  + "means unlogged, not unpaid. Realised gains cover only lot-matched sales."
                : "Realised gains cover only lot-matched sales; dividends are the ones you logged as received.";
        return new PerformanceDto.TotalReturn(PerformanceMath.round2(unrealised), stcg, ltcg,
                PerformanceMath.round2(dividends), events, total, note);
    }

    private PerformanceDto.CashRead cash(double holdingsValue) {
        return cashRepository.findTopByOrderBySnapshotDateDesc()
                .map(c -> {
                    Double avail = c.getAvailableCash();
                    Double pct = avail == null || avail + holdingsValue <= 0 ? null
                            : PerformanceMath.round2(avail / (avail + holdingsValue) * 100.0);
                    return new PerformanceDto.CashRead(c.getSnapshotDate(), avail, c.getNetCash(), pct,
                            "From the broker's margin statement at the 15:00 snapshot on the date shown.");
                })
                .orElse(new PerformanceDto.CashRead(null, null, null, null,
                        "Not captured yet: the first 15:00 snapshot after this deploy records it."));
    }

    private PerformanceDto.LotCoverage lotCoverage(List<HoldingsEntity> active) {
        Set<String> lotIsins = new HashSet<>();
        Set<String> lotSymbols = new HashSet<>();
        try {
            for (TaxLotEntity l : taxLotRepository.findByStatus("OPEN")) {
                if (l.getIsin() != null && !l.getIsin().isBlank()) lotIsins.add(l.getIsin());
                if (l.getSymbol() != null) lotSymbols.add(SymbolVariants.base(l.getSymbol()));
            }
        } catch (Exception e) {
            log.warn("Tax lots unavailable - lot coverage will read as none: {}", e.getMessage());
        }
        List<String> without = new ArrayList<>();
        int with = 0;
        for (HoldingsEntity h : active) {
            boolean covered = (h.getIsin() != null && lotIsins.contains(h.getIsin()))
                    || lotSymbols.contains(SymbolVariants.base(h.getSymbol()));
            if (covered) with++; else without.add(h.getSymbol());
        }
        without.sort(String::compareTo);
        return new PerformanceDto.LotCoverage(active.size(), with, without,
                with == active.size() ? "Every holding has a purchase lot on file."
                        : "Holding period, tax horizon and realised gains are known only for holdings with a lot. "
                          + "Import the Zerodha tradebook (Console > Reports > Tradebook) to fill the rest.");
    }

    // ------------------------------------------------------------------ weighted quality

    /**
     * Portfolio-weighted fundamentals: what the whole book costs and how well it earns. Each
     * metric is weighted by current value over the holdings it was measured on and withheld
     * below {@value #MIN_COVERAGE_FOR_WEIGHTED}% coverage - a "portfolio P/E" from a third of the
     * money is a third of an answer presented as a whole one.
     */
    public PerformanceDto.PortfolioQualityResponse quality() {
        List<HoldingsEntity> active = holdingsRepository.findActive();
        double total = active.stream().mapToDouble(HoldingsEntity::getCurrentValue).sum();
        Map<String, MultibaggerScoreEntity> latest = latestScores(active);
        LocalDate screeningDate = latest.values().stream().map(MultibaggerScoreEntity::getScreeningDate)
                .max(LocalDate::compareTo).orElse(null);

        List<PerformanceDto.WeightedMetric> metrics = new ArrayList<>();
        metrics.add(weighted("pe", "Price to earnings", "x", active, total, h -> positive(h.getStockPe()),
                "Weighted by value. What you pay per rupee of last year's profit across the book."));
        metrics.add(weighted("roce", "Return on capital employed", "%", active, total,
                h -> latest.get(h.getSymbol()) == null ? null : latest.get(h.getSymbol()).getRocePercent(),
                "Banks and lenders leave the denominator: ROCE does not apply to them (Gotcha 99c)."));
        metrics.add(weighted("roe", "Return on equity", "%", active, total,
                h -> latest.get(h.getSymbol()) == null ? null : latest.get(h.getSymbol()).getRoePercent(),
                "Weighted by value, from the latest annual filing on the screening row."));
        metrics.add(weighted("profitGrowth", "Profit growth, 2-year", "%/yr", active, total,
                h -> latest.get(h.getSymbol()) == null ? null : latest.get(h.getSymbol()).getDcfHistoricalGrowthPercent(),
                "The two-year profit CAGR the reverse-DCF uses. Loss-making or unscreened stocks leave the denominator."));

        Map<String, Double> byCompounding = new LinkedHashMap<>();
        try {
            Map<String, CompoundingLensService.Reading> lens = compoundingLensService.forSymbols(
                    active.stream().map(HoldingsEntity::getSymbol).toList());
            for (HoldingsEntity h : active) {
                CompoundingLensService.Reading r = lens.get(h.getSymbol());
                String v = r == null || r.result() == null ? "NOT_MEASURED" : r.result().verdict().name();
                byCompounding.merge(v, total > 0 ? h.getCurrentValue() / total * 100.0 : 0.0, Double::sum);
            }
        } catch (Exception e) {
            log.warn("Compounding lens unavailable for the quality summary: {}", e.getMessage());
        }
        byCompounding.replaceAll((k, v) -> PerformanceMath.round2(v));

        return new PerformanceDto.PortfolioQualityResponse(screeningDate, metrics, byCompounding,
                "Every figure is weighted by what each holding is worth today and reported with the share of the "
                        + "book it was measured on. A metric measured on under half the money is withheld.");
    }

    private Map<String, MultibaggerScoreEntity> latestScores(List<HoldingsEntity> active) {
        Map<String, MultibaggerScoreEntity> out = new HashMap<>();
        try {
            Set<String> candidates = new HashSet<>();
            Map<String, List<String>> variants = new HashMap<>();
            for (HoldingsEntity h : active) {
                List<String> v = SymbolVariants.candidates(h.getSymbol());
                variants.put(h.getSymbol(), v);
                candidates.addAll(v);
            }
            Map<String, MultibaggerScoreEntity> bySymbol = new HashMap<>();
            for (MultibaggerScoreEntity e : scoreRepository.findLatestForSymbolsSince(candidates,
                    LocalDate.now(IST).minusDays(SCORE_LOOKBACK_DAYS))) {
                bySymbol.put(e.getSymbol(), e);   // one row per symbol, the newest (B-115)
            }
            for (HoldingsEntity h : active) {
                // First spelling that answers (Gotcha 107): an empty BSE row must not beat a full NSE one.
                for (String v : variants.get(h.getSymbol())) {
                    MultibaggerScoreEntity e = bySymbol.get(v);
                    if (e != null && (e.getRocePercent() != null || e.getRoePercent() != null
                            || e.getDcfHistoricalGrowthPercent() != null)) {
                        out.put(h.getSymbol(), e);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Screening rows unavailable - weighted quality metrics will read not measured: {}", e.getMessage());
        }
        return out;
    }

    private static PerformanceDto.WeightedMetric weighted(String key, String label, String unit,
                                                          List<HoldingsEntity> active, double total,
                                                          Function<HoldingsEntity, Double> read, String note) {
        double measuredValue = 0.0;
        double weightedSum = 0.0;
        int n = 0;
        for (HoldingsEntity h : active) {
            Double v = read.apply(h);
            if (v == null || h.getCurrentValue() <= 0) continue;
            measuredValue += h.getCurrentValue();
            weightedSum += v * h.getCurrentValue();
            n++;
        }
        double coverage = total > 0 ? measuredValue / total * 100.0 : 0.0;
        Double value = coverage >= MIN_COVERAGE_FOR_WEIGHTED && measuredValue > 0
                ? PerformanceMath.round2(weightedSum / measuredValue) : null;
        return new PerformanceDto.WeightedMetric(key, label, value, PerformanceMath.round2(coverage), n,
                active.size(), unit, value == null && n > 0
                        ? String.format("Measured on only %.0f%% of your money (%d of %d holdings), so withheld. %s",
                                coverage, n, active.size(), note)
                        : note);
    }

    private static Double positive(Double v) {
        return v == null || v <= 0 ? null : v;
    }

    private static double toDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }
}
