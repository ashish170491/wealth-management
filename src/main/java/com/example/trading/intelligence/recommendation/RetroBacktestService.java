package com.example.trading.intelligence.recommendation;

import com.example.trading.marketdata.MarketDataService;
import com.example.trading.multibagger.MultibaggerScreenerService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Retrospective test of the technical scoring dimensions against known outcomes
 * (SPEC §33, plan feature F6).
 *
 * <p>Forward accuracy tracking (SPEC §23) only started in Apr-2026 and needs quarters to
 * mature. This asks a different question that can be answered today: <b>as of a past date,
 * would our dimensions have separated the eventual winners from their sector peers?</b>
 *
 * <h2>What this can and cannot prove</h2>
 * It can prove a dimension was <b>blind</b> — if the winners scored no better than the
 * controls on Price Structure in their base year, Price Structure was not going to find
 * them. It <b>cannot</b> prove a dimension works: both lists were chosen in 2026 with full
 * knowledge of what happened, so the winners are survivorship-selected and the controls are
 * selected to be unremarkable. A positive separation here is a hypothesis for the forward IC
 * to test, never a result to re-weight on.
 *
 * <p>Fundamental dimensions (Valuation, Financial Quality, Institutional Interest, and every
 * earnings-based bonus) <b>cannot be computed as-of a past date</b> — NSE's integrated-filing
 * data only reaches back to ~Mar-2025 and there is no point-in-time fundamentals store. Those
 * dimensions are reported as untested rather than quietly skipped: a coverage report that
 * looks complete when it is partial is worse than no report.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetroBacktestService {

    private final MarketDataService marketDataService;
    private final MultibaggerScreenerService screenerService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Dimensions computable from price/volume history alone, i.e. testable as-of a past date. */
    static final List<String> TESTED_DIMENSIONS =
            List.of("Technical Momentum", "Volume Accumulation", "Relative Strength", "Price Structure");

    /**
     * Dimensions that exist in the live composite but cannot be reconstructed historically.
     * Named explicitly in the output — see the class note on silent partial coverage.
     */
    static final List<String> UNTESTED_DIMENSIONS =
            List.of("Valuation", "Institutional Interest", "Sector Tailwind", "Financial Quality",
                    "Earnings Growth bonus", "Insider Activity bonus", "Capital Efficiency bonus",
                    "Wealth Signals bonus", "Analyst Signal bonus", "Insider Pulse");

    /**
     * Acknowledged 2018-2023 multibaggers with the year their run began, paired with control
     * stocks from comparable sectors that did <i>not</i> multiply. Controls are not optional
     * garnish: without them a "winners scored 72 on momentum" figure means nothing, because
     * the whole market may have scored 72 that year.
     */
    static final List<RetroCase> DEFAULT_CASES = List.of(
            new RetroCase("NSE:KPITTECH", 2020, true, "IT"),
            new RetroCase("NSE:TANLA", 2020, true, "IT"),
            new RetroCase("NSE:DEEPAKNTR", 2019, true, "Chemicals"),
            new RetroCase("NSE:VBL", 2020, true, "Consumer"),
            new RetroCase("NSE:POLYCAB", 2020, true, "Capital Goods"),
            new RetroCase("NSE:CGPOWER", 2021, true, "Capital Goods"),
            new RetroCase("NSE:APLAPOLLO", 2019, true, "Metals"),
            new RetroCase("NSE:PERSISTENT", 2020, true, "IT"),
            // JBCHEPHARM deliberately omitted: B-027 established the ticker no longer
            // resolves at the broker. Per Gotcha 14, a dead symbol is removed, never
            // replaced with a guess.
            new RetroCase("NSE:SONACOMS", 2021, true, "Auto"),

            new RetroCase("NSE:WIPRO", 2020, false, "IT"),
            new RetroCase("NSE:TECHM", 2020, false, "IT"),
            new RetroCase("NSE:TATACHEM", 2019, false, "Chemicals"),
            new RetroCase("NSE:TATACONSUM", 2020, false, "Consumer"),
            new RetroCase("NSE:BHEL", 2020, false, "Capital Goods"),
            new RetroCase("NSE:NBCC", 2021, false, "Capital Goods"),
            new RetroCase("NSE:SAIL", 2019, false, "Metals"),
            new RetroCase("NSE:LTTS", 2020, false, "IT"),
            new RetroCase("NSE:LUPIN", 2020, false, "Pharma"),
            new RetroCase("NSE:BOSCHLTD", 2021, false, "Auto"));

    /**
     * Score every case as-of its base year and report per-dimension separation between
     * winners and controls.
     *
     * @param cases null to use {@link #DEFAULT_CASES}
     */
    public RetroBacktestResult run(List<RetroCase> cases) {
        List<RetroCase> work = (cases == null || cases.isEmpty()) ? DEFAULT_CASES : cases;
        List<RetroScore> scored = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (RetroCase c : work) {
            try {
                RetroScore s = scoreAsOf(c);
                if (s == null) {
                    skipped.add(c.symbol() + " (" + c.baseYear() + "): no usable history");
                } else {
                    scored.add(s);
                }
            } catch (Exception e) {
                skipped.add(c.symbol() + " (" + c.baseYear() + "): " + e.getMessage());
            }
        }

        Map<String, DimensionSeparation> separation = new LinkedHashMap<>();
        for (String dim : TESTED_DIMENSIONS) {
            List<Integer> win = new ArrayList<>();
            List<Integer> ctl = new ArrayList<>();
            for (RetroScore s : scored) {
                Integer v = s.getDimensionScores().get(dim);
                if (v == null) continue;
                (s.isWinner() ? win : ctl).add(v);
            }
            separation.put(dim, DimensionSeparation.of(dim, win, ctl));
        }

        // Loud about what was dropped: a silent cap reads as full coverage (no-silent-caps rule).
        if (!skipped.isEmpty()) {
            log.warn("Retro-backtest: {} of {} cases could not be scored: {}",
                    skipped.size(), work.size(), skipped);
        }

        return RetroBacktestResult.builder()
                .casesRequested(work.size())
                .casesScored(scored.size())
                .skipped(skipped)
                .scores(scored)
                .separation(separation)
                .testedDimensions(TESTED_DIMENSIONS)
                .untestedDimensions(UNTESTED_DIMENSIONS)
                .caveat("Winners and controls were both selected in 2026 with knowledge of what "
                        + "happened. This can show a dimension was blind to the winners; it cannot "
                        + "show a dimension works. Fundamental dimensions are untested — no "
                        + "point-in-time fundamentals exist before ~Mar-2025.")
                .build();
    }

    /**
     * Reconstruct the price/volume-only dimensions using candles up to 31-Dec of the year
     * BEFORE the base year, so the scoring never sees the move it is supposed to predict.
     */
    private RetroScore scoreAsOf(RetroCase c) {
        String to = (c.baseYear() - 1) + "-12-31 15:30:00";
        String from = (c.baseYear() - 4) + "-01-01 09:15:00";

        List<Map<String, Object>> history = marketDataService.getRecentCandles(c.symbol(), "day", from, to);
        if (history == null || history.size() < 200) return null;

        List<Map<String, Object>> nifty = marketDataService.getRecentCandles("NSE:NIFTY 50", "day", from, to);
        double asOfPrice = closeOf(history.get(history.size() - 1));
        if (asOfPrice <= 0) return null;

        Map<String, Integer> dims = screenerService.scoreTechnicalDimensions(history, nifty, asOfPrice);

        Double forwardReturn = null;
        try {
            String fwdTo = (c.baseYear() + 3) + "-12-31 15:30:00";
            List<Map<String, Object>> fwd = marketDataService.getRecentCandles(
                    c.symbol(), "day", (c.baseYear()) + "-01-01 09:15:00", fwdTo);
            if (fwd != null && !fwd.isEmpty()) {
                double end = closeOf(fwd.get(fwd.size() - 1));
                if (end > 0) forwardReturn = 100.0 * (end - asOfPrice) / asOfPrice;
            }
        } catch (Exception e) {
            log.debug("Retro-backtest: forward return unavailable for {}: {}", c.symbol(), e.getMessage());
        }

        return RetroScore.builder()
                .symbol(c.symbol())
                .baseYear(c.baseYear())
                .winner(c.winner())
                .sector(c.sector())
                .asOfPrice(asOfPrice)
                .forwardReturnPercent(forwardReturn)
                .dimensionScores(dims)
                .build();
    }

    private static double closeOf(Map<String, Object> candle) {
        Object v = candle.get("close");
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    // ---- DTOs ----

    public record RetroCase(String symbol, int baseYear, boolean winner, String sector) {}

    @Data
    @Builder
    public static class RetroScore {
        private String symbol;
        private int baseYear;
        private boolean winner;
        private String sector;
        private double asOfPrice;
        private Double forwardReturnPercent;
        private Map<String, Integer> dimensionScores;
    }

    /** Winners-vs-controls separation for one dimension. */
    @Data
    @Builder
    public static class DimensionSeparation {
        private String dimension;
        private Double winnerMean;
        private Double controlMean;
        private Double gap;              // winnerMean - controlMean
        private int winnerCount;
        private int controlCount;
        private String reading;

        static DimensionSeparation of(String dim, List<Integer> win, List<Integer> ctl) {
            Double wm = mean(win);
            Double cm = mean(ctl);
            Double gap = (wm != null && cm != null) ? wm - cm : null;
            String reading;
            if (win.size() < 3 || ctl.size() < 3) {
                reading = "TOO_FEW_CASES";
            } else if (gap == null) {
                reading = "NOT_MEASURED";
            } else if (gap >= 10) {
                reading = "SEPARATED_WINNERS";
            } else if (gap <= -10) {
                reading = "FAVOURED_CONTROLS";
            } else {
                reading = "BLIND";           // could not tell the two groups apart
            }
            return DimensionSeparation.builder()
                    .dimension(dim).winnerMean(wm).controlMean(cm).gap(gap)
                    .winnerCount(win.size()).controlCount(ctl.size())
                    .reading(reading)
                    .build();
        }

        private static Double mean(List<Integer> xs) {
            if (xs == null || xs.isEmpty()) return null;
            return xs.stream().mapToInt(Integer::intValue).average().orElse(0);
        }
    }

    @Data
    @Builder
    public static class RetroBacktestResult {
        private int casesRequested;
        private int casesScored;
        private List<String> skipped;
        private List<RetroScore> scores;
        private Map<String, DimensionSeparation> separation;
        private List<String> testedDimensions;
        private List<String> untestedDimensions;
        private String caveat;
    }
}
