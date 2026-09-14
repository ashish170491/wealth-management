package com.example.trading.portfolio.performance;

import com.example.trading.broker.BrokerClient;
import com.example.trading.marketdata.MarketDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the two daily facts the portfolio page needs that {@code holdings_history} never
 * carried: the benchmark closes and the broker's available cash (SPEC §46.2, §46.4).
 *
 * <p>Called from {@code HoldingsAnalysisService.recordDailySnapshot()} at 15:00 - deliberately
 * <em>not</em> a scheduler of its own (SPEC §3.4, §15: no new cron for two Kite calls that belong
 * beside the snapshot they describe). The backfill is a manual, guarded POST.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioSnapshotService {

    /** Kite symbols, spaces included (Gotcha 1). */
    public static final String NIFTY_50 = "NSE:NIFTY 50";
    public static final String NIFTY_MIDCAP_150 = "NSE:NIFTY MIDCAP 150";
    public static final List<String> BENCHMARKS = List.of(NIFTY_50, NIFTY_MIDCAP_150);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final BenchmarkCloseRepository benchmarkRepository;
    private final PortfolioCashSnapshotRepository cashRepository;
    private final MarketDataService marketDataService;
    private final BrokerClient brokerClient;

    /**
     * Today's benchmark prices and cash. Each part fails independently and says what its
     * absence will look like (B-054's rule): a missing benchmark day breaks the comparison line
     * for that date; a missing cash row leaves the cash tile on yesterday's figure with its date.
     */
    public void captureDailyExtras() {
        LocalDate today = LocalDate.now(IST);
        for (String symbol : BENCHMARKS) {
            try {
                Double price = marketDataService.getCurrentPrice(symbol);
                if (price == null || price <= 0) {
                    log.warn("Benchmark snapshot: no price for {} today - the 'vs index' line will "
                            + "have a gap on {}", symbol, today);
                    continue;
                }
                upsertClose(symbol, today, price, "SNAPSHOT");
            } catch (Exception e) {
                log.warn("Benchmark snapshot failed for {}: {} - the comparison line will have a gap "
                        + "on {}", symbol, e.getMessage(), today);
            }
        }
        try {
            captureCash(today);
        } catch (Exception e) {
            log.warn("Cash snapshot failed: {} - the cash tile will show the previous day's figure "
                    + "with its own date", e.getMessage());
        }
    }

    /**
     * Reads {@code equity.available.cash} and {@code equity.net} from Kite's margins payload.
     * A payload without the equity segment records nothing rather than a zero: zero cash is a
     * fact about the account, absence is a fact about the feed (Gotcha 21).
     */
    @SuppressWarnings("unchecked")
    void captureCash(LocalDate today) {
        Map<String, Object> margins = brokerClient.getAccountMargins();
        if (margins == null) return;
        Object equity = margins.get("equity");
        if (!(equity instanceof Map<?, ?> eq)) {
            log.warn("Cash snapshot: margins payload had no 'equity' segment (keys={}); nothing recorded",
                    margins.keySet());
            return;
        }
        Double available = null;
        Object avail = eq.get("available");
        if (avail instanceof Map<?, ?> a) {
            available = num(a.get("cash"));
            if (available == null) available = num(a.get("live_balance"));
        }
        Double net = num(eq.get("net"));
        if (available == null && net == null) {
            log.warn("Cash snapshot: equity segment carried neither available.cash nor net; nothing recorded");
            return;
        }
        PortfolioCashSnapshotEntity row = cashRepository.findBySnapshotDate(today)
                .orElseGet(() -> PortfolioCashSnapshotEntity.builder().snapshotDate(today).build());
        row.setAvailableCash(available);
        row.setNetCash(net);
        row.setCapturedAt(LocalDateTime.now(IST));
        cashRepository.save(row);
        log.info("Cash snapshot {}: available={} net={}", today, available, net);
    }

    /**
     * Fills benchmark history from Kite daily candles. One paced call per benchmark; idempotent.
     *
     * @return rows written per symbol
     */
    public Map<String, Integer> backfillBenchmarks(int days) {
        LocalDate to = LocalDate.now(IST);
        LocalDate from = to.minusDays(Math.max(30, Math.min(days, 1500)));
        Map<String, Integer> written = new LinkedHashMap<>();
        for (String symbol : BENCHMARKS) {
            int n = 0;
            try {
                List<Map<String, Object>> candles = marketDataService.getRecentCandles(
                        symbol, "day", from.toString(), to.toString());
                for (Map<String, Object> c : candles == null ? List.<Map<String, Object>>of() : candles) {
                    LocalDate d = candleDate(c.get("timestamp"));
                    Double close = num(c.get("close"));
                    if (d == null || close == null || close <= 0) continue;
                    if (benchmarkRepository.findBySymbolAndCloseDate(symbol, d).isEmpty()) {
                        upsertClose(symbol, d, close, "BACKFILL");
                        n++;
                    }
                }
            } catch (Exception e) {
                log.warn("Benchmark backfill failed for {}: {}", symbol, e.getMessage());
            }
            written.put(symbol, n);
            log.info("Benchmark backfill {}: {} new closes from {} to {}", symbol, n, from, to);
        }
        return written;
    }

    private void upsertClose(String symbol, LocalDate date, double close, String source) {
        BenchmarkCloseEntity row = benchmarkRepository.findBySymbolAndCloseDate(symbol, date)
                .orElseGet(() -> BenchmarkCloseEntity.builder().symbol(symbol).closeDate(date).build());
        // A snapshot is the 15:00 price, a backfill is the true close: the close wins once known.
        if ("SNAPSHOT".equals(source) && "BACKFILL".equals(row.getSource())) return;
        row.setClose(close);
        row.setSource(source);
        benchmarkRepository.save(row);
    }

    static LocalDate candleDate(Object raw) {
        if (raw == null) return null;
        if (raw instanceof LocalDate ld) return ld;
        String s = String.valueOf(raw);
        int t = s.indexOf('T');
        if (t > 0) s = s.substring(0, t);
        if (s.length() > 10) s = s.substring(0, 10);
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static Double num(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return null;
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
