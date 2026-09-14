package com.example.trading.scheduler;

import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Service to check if the Indian stock market is currently open.
 * Market Hours: 09:15 to 15:30 IST.
 */
@Service
public class MarketHoursService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    // Saturday screening window (SPEC §3.4 carve-out, 2026-08-25): the Windows tasks
    // start the app 07:50 and stop it 10:35 on Saturday so the weekly multibagger
    // screening (08:00) and report (09:00) can run off-market.
    private static final LocalTime SATURDAY_WINDOW_OPEN = LocalTime.of(7, 45);
    private static final LocalTime SATURDAY_WINDOW_CLOSE = LocalTime.of(10, 30);

    public boolean isMarketOpen() {
        ZonedDateTime nowIST = ZonedDateTime.now(IST);

        // Check for weekends
        int dayOfWeek = nowIST.getDayOfWeek().getValue();
        if (dayOfWeek >= 6) { // 6 = Saturday, 7 = Sunday
            return false;
        }

        LocalTime nowTime = nowIST.toLocalTime();
        return !nowTime.isBefore(MARKET_OPEN) && !nowTime.isAfter(MARKET_CLOSE);
    }

    /**
     * Check if we are inside the Saturday screening window: 07:45–10:30 IST, Saturday only.
     *
     * <p>This is the ONE sanctioned exception to the SPEC §3.4 market-hours rule. Exactly two
     * <b>schedulers</b> may guard with this method instead of {@link #isMarketOpen()}: the
     * weekly multibagger screening (08:00 SAT) and its report email (09:00 SAT). Any other
     * {@code @Scheduled} method using it must be rejected in review — the rule lives here so
     * it can be audited in one place.
     *
     * <p><b>Read-only consumers are not schedulers.</b> The restriction exists to stop new
     * work being *scheduled* outside market hours; asking "is it Saturday morning?" to decide
     * whether a request may proceed does not schedule anything and does not extend the
     * carve-out. {@code UniverseController} reads it that way, to allow a manual long Kite
     * sweep in the one window where nothing else needs the broker (B-049). Adding a caller of
     * that kind is fine; adding a third scheduled job is not.
     */
    public boolean isSaturdayScreeningWindow() {
        ZonedDateTime nowIST = ZonedDateTime.now(IST);
        if (nowIST.getDayOfWeek() != DayOfWeek.SATURDAY) {
            return false;
        }
        LocalTime nowTime = nowIST.toLocalTime();
        return !nowTime.isBefore(SATURDAY_WINDOW_OPEN) && !nowTime.isAfter(SATURDAY_WINDOW_CLOSE);
    }

    /**
     * Check if current time is in safe trading hours (avoid last 15 minutes only).
     * Signal generation starts at 9:15 AM but order execution is blocked until 9:30 AM.
     * Last 15 minutes (15:15-15:30): EOD manipulation and square-offs - still blocked.
     *
     * NOTE: This is now used for signal generation only.
     * Use isOrderExecutionAllowed() for actual order placement.
     */
    public boolean isInSafeTradingHours() {
        if (!isMarketOpen()) {
            return false;
        }

        ZonedDateTime nowIST = ZonedDateTime.now(IST);
        LocalTime nowTime = nowIST.toLocalTime();

        // Signal generation allowed from market open (9:15)
        // No longer blocking first 15 minutes for signal generation

        // Avoid last 15 minutes (EOD manipulation)
        LocalTime safeEnd = LocalTime.of(15, 15);
        if (nowTime.isAfter(safeEnd)) {
            return false;
        }

        return true;
    }

    /**
     * Check if order execution is allowed.
     * Orders are blocked during first 15 minutes (09:15-09:30) due to high opening volatility.
     * Signals can still be generated during this time, but orders won't be placed.
     */
    public boolean isOrderExecutionAllowed() {
        if (!isMarketOpen()) {
            return false;
        }

        ZonedDateTime nowIST = ZonedDateTime.now(IST);
        LocalTime nowTime = nowIST.toLocalTime();

        // Block orders during first 15 minutes (high volatility opening)
        LocalTime orderStartTime = LocalTime.of(9, 30);
        if (nowTime.isBefore(orderStartTime)) {
            return false;
        }

        // Block orders during last 15 minutes (EOD manipulation)
        LocalTime orderEndTime = LocalTime.of(15, 15);
        if (nowTime.isAfter(orderEndTime)) {
            return false;
        }

        return true;
    }

    /**
     * Check if we're in the early signal generation window (9:15-9:30).
     * During this time, signals are generated but orders are queued/logged only.
     */
    public boolean isInEarlySignalWindow() {
        if (!isMarketOpen()) {
            return false;
        }

        ZonedDateTime nowIST = ZonedDateTime.now(IST);
        LocalTime nowTime = nowIST.toLocalTime();

        LocalTime earlyStart = LocalTime.of(9, 15);
        LocalTime earlyEnd = LocalTime.of(9, 30);

        return !nowTime.isBefore(earlyStart) && nowTime.isBefore(earlyEnd);
    }

    public ZoneId getMarketZone() {
        return IST;
    }
}
