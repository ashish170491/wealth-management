package com.example.trading.portfolio.tax;

import com.example.trading.broker.BrokerClient;
import com.example.trading.scheduler.MarketHoursService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Daily auto-capture of broker trades into the tax-lot ledger. Runs while the market is
 * still open (Kite Connect's {@code /trades} endpoint is current-day only — once the
 * server shuts down at 15:35 IST the data is gone), and every BUY trade becomes a
 * {@link TaxLotEntity} while every SELL is matched FIFO via {@link TaxLotService#recordSale}.
 *
 * <p>Scheduling rules (SPEC.md §3.4): fires within 09:15–15:30 IST, MON-FRI. We use
 * <strong>15:28</strong> — 2 minutes before close so most of the day's fills are present,
 * but before EOD square-off (15:25) and the 15:35 app-down window.
 *
 * <p>Idempotency is guaranteed by {@code tradeId} dedup inside {@link TaxLotService}, so
 * manual re-triggers and crash-recovery re-runs never duplicate rows.
 *
 * <p>SPEC.md §9.3 Data Sources.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaxLotAutoCaptureScheduler {

    private static final String SOURCE = "KITE_AUTO_CAPTURE";
    private static final DateTimeFormatter KITE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BrokerClient brokerClient;
    private final TaxLotService taxLotService;
    private final MarketHoursService marketHoursService;

    /**
     * Capture today's executed trades into the tax-lot ledger. 15:28 IST MON-FRI.
     */
    @Scheduled(cron = "0 28 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void captureTodayTrades() {
        if (!marketHoursService.isMarketOpen()) {
            log.info("TaxLot auto-capture: market closed — skipping");
            return;
        }
        runCapture();
    }

    /** Public entry-point so a controller can trigger this on demand without exposing internals. */
    public Map<String, Object> runCapture() {
        log.info("TaxLot auto-capture: fetching today's trades from broker");
        List<Map<String, Object>> trades;
        try {
            trades = brokerClient.getTodayTrades();
        } catch (Exception e) {
            log.error("TaxLot auto-capture: broker call failed — {}", e.getMessage(), e);
            return Map.of("error", e.getMessage(), "buysCreated", 0, "sellsRecorded", 0);
        }
        if (trades == null || trades.isEmpty()) {
            log.info("TaxLot auto-capture: no trades returned by broker");
            return Map.of("tradesFetched", 0, "buysCreated", 0, "sellsRecorded", 0);
        }

        // Process in execution-time order so FIFO matches the actual market sequence
        // (this matters when the same symbol is bought and sold the same day).
        trades.sort(Comparator.comparing(t -> {
            Object ts = t.get("order_timestamp");
            return ts != null ? ts.toString() : "";
        }));

        int buysCreated = 0, sellsRecorded = 0, sellsUnmatched = 0, skippedNonEquity = 0;
        for (Map<String, Object> trade : trades) {
            try {
                String segment = asString(trade.get("exchange"));
                if (segment == null || !(segment.equalsIgnoreCase("NSE") || segment.equalsIgnoreCase("BSE"))) {
                    skippedNonEquity++;
                    continue;
                }
                String tradingSymbol = asString(trade.get("tradingsymbol"));
                String tradeId = asString(trade.get("trade_id"));
                String txn = asString(trade.get("transaction_type"));
                Number qtyNum = (Number) trade.get("quantity");
                Number priceNum = (Number) trade.get("average_price");
                if (priceNum == null) priceNum = (Number) trade.get("price");
                if (tradingSymbol == null || tradeId == null || txn == null || qtyNum == null || priceNum == null) {
                    log.warn("TaxLot auto-capture: skipping malformed trade — {}", trade);
                    continue;
                }

                int qty = qtyNum.intValue();
                if (qty <= 0) continue;
                double price = priceNum.doubleValue();
                String fullSymbol = segment.toUpperCase() + ":" + tradingSymbol;
                LocalDate tradeDate = parseTradeDate(trade.get("order_timestamp"));

                if ("BUY".equalsIgnoreCase(txn)) {
                    TaxLotDto.CreateLotRequest req = new TaxLotDto.CreateLotRequest(
                            fullSymbol, qty, price, tradeDate,
                            0.0,
                            "Auto-captured from broker (order " + asString(trade.get("order_id")) + ")",
                            tradeId, SOURCE);
                    taxLotService.createLot(req); // idempotent on tradeId
                    buysCreated++;
                } else if ("SELL".equalsIgnoreCase(txn)) {
                    TaxLotDto.RecordSaleRequest req = new TaxLotDto.RecordSaleRequest(
                            fullSymbol, qty, price, tradeDate, 0.0, "FIFO", tradeId);
                    try {
                        taxLotService.recordSale(req);
                        sellsRecorded++;
                    } catch (IllegalArgumentException unmatched) {
                        sellsUnmatched++;
                        log.warn("TaxLot auto-capture: unmatched sell for {} qty={} — buy lot missing. {}",
                                fullSymbol, qty, unmatched.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("TaxLot auto-capture: trade row failed — {}", e.getMessage());
            }
        }

        log.info("TaxLot auto-capture done: tradesFetched={}, buysCreated={}, sellsRecorded={}, sellsUnmatched={}, skippedNonEquity={}",
                trades.size(), buysCreated, sellsRecorded, sellsUnmatched, skippedNonEquity);
        return Map.of(
                "tradesFetched", trades.size(),
                "buysCreated", buysCreated,
                "sellsRecorded", sellsRecorded,
                "sellsUnmatched", sellsUnmatched,
                "skippedNonEquity", skippedNonEquity);
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    /** Kite returns {@code order_timestamp} like {@code "2026-04-27 15:23:14"} or as ISO. */
    private static LocalDate parseTradeDate(Object timestamp) {
        if (timestamp == null) return LocalDate.now();
        String s = timestamp.toString();
        try {
            return LocalDateTime.parse(s, KITE_TS).toLocalDate();
        } catch (Exception ignored) {
            try {
                return LocalDate.parse(s.substring(0, 10));
            } catch (Exception e) {
                return LocalDate.now();
            }
        }
    }
}
