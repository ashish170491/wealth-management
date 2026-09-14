package com.example.trading.strategy;

import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
public class StrategyUtils {

    private static final DateTimeFormatter FLEXIBLE_FORMATTER = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd['T'][ ]HH:mm:ss[XXX][XX][X]")
            .toFormatter();

    public static BarSeries convertToBarSeries(String symbol, List<Map<String, Object>> history) {
        BarSeries series = new BaseBarSeries(symbol);

        // Sort history by timestamp in ascending order (oldest first)
        // This ensures TA4J BarSeries accepts bars in chronological order
        List<Map<String, Object>> sortedHistory = new ArrayList<>(history);
        sortedHistory.sort((a, b) -> {
            try {
                ZonedDateTime timeA = parseTimestamp(a.get("timestamp"));
                ZonedDateTime timeB = parseTimestamp(b.get("timestamp"));
                return timeA.compareTo(timeB);
            } catch (Exception e) {
                log.warn("Failed to parse timestamps for sorting: {}", e.getMessage());
                return 0;
            }
        });

        for (Map<String, Object> candle : sortedHistory) {
            try {
                ZonedDateTime time = parseTimestamp(candle.get("timestamp"));

                series.addBar(time,
                        num(candle.get("open")),
                        num(candle.get("high")),
                        num(candle.get("low")),
                        num(candle.get("close")),
                        num(candle.get("volume")));
            } catch (Exception e) {
                log.warn("Skipping malformed candle for strategy evaluation: {}", e.getMessage());
            }
        }
        return series;
    }

    private static ZonedDateTime parseTimestamp(Object timeObj) {
        if (timeObj instanceof ZonedDateTime zdt) {
            return zdt;
        } else if (timeObj instanceof String s) {
            try {
                return ZonedDateTime.parse(s, FLEXIBLE_FORMATTER);
            } catch (Exception e) {
                return ZonedDateTime.parse(s); // Fallback to ISO format
            }
        }
        return ZonedDateTime.now(); // Fallback to current time
    }

    private static Number num(Object val) {
        if (val == null) {
            log.warn("Null value encountered in OHLCV conversion, using 0.0");
            return 0.0;
        }
        if (val instanceof Number n)
            return n;
        if (val instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                log.warn("Failed to parse numeric value: {}, using 0.0", s);
                return 0.0;
            }
        }
        log.warn("Unexpected value type: {}, using 0.0", val.getClass());
        return 0.0;
    }
}
