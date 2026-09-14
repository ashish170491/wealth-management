package com.example.trading.dashboard;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Wire shapes for the dashboard UI (SPEC section 27). Records only - no behaviour.
 *
 * <p><b>Nullable by design.</b> Every "measured" number here is a wrapper type, never a
 * primitive, so that "we could not measure this" survives serialization as {@code null}
 * instead of collapsing into a confident-looking {@code 0}. SPEC section 21 rule 7 makes
 * rendering an unmeasured value as a number a bug, and the UI relies on these nulls to
 * show "not measured" instead. Do not "clean this up" by defaulting to zero.
 */
public final class DashboardDto {

    private DashboardDto() {
    }

    /**
     * Answers "is the server up, and how old is the data it is serving?" - two genuinely
     * independent questions (SPEC section 27.7). A live server can happily serve Friday's
     * prices on Monday morning, so the UI shows reachability and freshness separately.
     *
     * @param freshness table key -> ISO date/datetime of the newest row, or null if empty
     */
    public record HealthResponse(
            String serverTimeIst,
            boolean marketOpen,
            String scheduledShutdownAt,
            int activeHoldingsCount,
            Map<String, String> freshness) {
    }

    /** Landing-page aggregate. Sanctioned by SPEC section 25.6, specified in section 27. */
    public record SummaryResponse(
            PortfolioKpis portfolio,
            RiskHeadline risk,
            AccuracyHeadline accuracy,
            List<AttentionItem> attention,
            Map<String, String> freshness) {
    }

    public record PortfolioKpis(
            double investedValue,
            double currentValue,
            double pnl,
            Double pnlPercent,
            double dayChangeValue,
            Double dayChangePercent,
            int holdingsCount,
            long profitableCount,
            long losingCount) {
    }

    /**
     * @param hhi              Herfindahl-Hirschman Index, 0-10000; higher = more concentrated
     * @param hhiClassification LOW / MODERATE / CONCENTRATED / DANGEROUS
     */
    public record RiskHeadline(
            Double hhi,
            String hhiClassification,
            String topSector,
            Double topSectorWeight,
            String topStock,
            Double topStockWeight,
            int alertCount) {
    }

    /**
     * Headline calibration numbers for one (source, horizon) cell.
     *
     * @param sampleSize     number of scored picks behind these figures
     * @param enoughData     false when {@code sampleSize} is below the display threshold; the
     *                       UI must then say "not enough data yet" rather than show the numbers
     * @param informationCoefficient score-vs-return correlation; null when not computable
     */
    public record AccuracyHeadline(
            String source,
            int horizonDays,
            int sampleSize,
            boolean enoughData,
            Double hitRatePercent,
            Double meanReturnPercent,
            Double meanExcessReturnPercent,
            Double informationCoefficient) {
    }

    /**
     * One row of the merged "what needs your attention today" list.
     *
     * @param kind     EXIT_SIGNAL / THESIS_DECAY / ALLOCATION_DRIFT / CONCENTRATION_RISK
     * @param severity URGENT / WARNING / INFO - drives sort order and colour
     * @param symbol   null for portfolio-level items (drift, concentration)
     */
    public record AttentionItem(
            String kind,
            String severity,
            String symbol,
            String headline,
            String detail,
            Double pnlPercent) {
    }

    /**
     * The most recent screening run that actually has rows, plus its date.
     *
     * <p>Exists because the two obvious endpoints both come up empty in normal use:
     * {@code /api/multibagger/scores} serves an in-memory cache that is empty after every
     * restart (and this app restarts daily), and {@code /api/multibagger/history} defaults
     * to today, which has no rows until the 14:00 screening runs. A screener screen built
     * on either would look broken every morning.
     *
     * @param screeningDate the date these rows are from - always stamped, because "latest
     *                      available" may well be several days old
     */
    public record ScreenerResponse(
            LocalDate screeningDate,
            int totalScreened,
            List<Object> scores) {
    }

    /** One point of a per-stock history series. Compact keys - these arrive in bulk. */
    public record HoldingSeriesPoint(
            LocalDate d,
            double close,
            double pnl,
            double pnlPct,
            Integer tech,
            Integer overall) {
    }

    /** One point of the portfolio equity curve. */
    public record PortfolioSeriesPoint(
            LocalDate d,
            double invested,
            double value,
            double pnl,
            Double pnlPct) {
    }

    /**
     * The data-health screen (SPEC 44): every automated check, including the ones that passed.
     *
     * @param today       the weekday spelled out, because reading a freshness date requires
     *                    knowing what day it is - screening scores dated Saturday are correct
     *                    on a Sunday and stale on a Wednesday
     * @param notChecked  what this screen cannot verify. Reported alongside the findings so an
     *                    all-clear can never be mistaken for a guarantee (Gotcha 44)
     */
    public record DataHealthResponse(
            String generatedAt,
            String today,
            DataHealthSummary summary,
            List<com.example.trading.integrity.DataHealth.Finding> findings,
            List<String> notChecked,
            String note) {
    }

    /** Counts by severity, so the reader sees the denominator and not only the alarms. */
    public record DataHealthSummary(
            int checks,
            int problems,
            int watch,
            int known,
            int ok) {
    }
}
