package com.example.trading.portfolio.tax;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses Zerodha's tradebook CSV export (Console &rarr; Reports &rarr; Tradebook) and
 * replays it chronologically against {@link TaxLotService} to seed the tax-lot ledger.
 *
 * <p>CSV format (as of 2026): {@code symbol, isin, trade_date, exchange, segment, series,
 * trade_type, auction, quantity, price, trade_id, order_id, order_execution_time}.
 *
 * <p>Replay rules:
 * <ul>
 *   <li>Buys become {@link TaxLotEntity} rows with {@code tradeId} set for idempotency.</li>
 *   <li>Sells call {@link TaxLotService#recordSale} which does FIFO matching against open lots.</li>
 *   <li>Sells that can't be matched (because the corresponding buy predates the CSV window)
 *       are logged and skipped — they don't fail the whole import.</li>
 *   <li>Re-running the import is safe — both lots and sales dedup on {@code tradeId}.</li>
 * </ul>
 *
 * <p>SPEC.md §9.3 Data Sources.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ZerodhaTradebookImportService {

    private static final String SOURCE = "ZERODHA_CSV";

    private final TaxLotService taxLotService;
    private final TaxLotRepository lotRepository;

    /**
     * Import a Zerodha tradebook CSV body. Returns a structured summary suitable for
     * the API response.
     */
    public Map<String, Object> importCsv(String csvBody) {
        List<TradeRow> rows = parse(csvBody);
        // Replay chronologically — within the same timestamp, sort by trade_id to keep
        // multi-leg orders (e.g. one limit order filled in 3 partial trades) in deterministic order.
        rows.sort(Comparator
                .comparing(TradeRow::tradeDate)
                .thenComparing(TradeRow::executionTime, Comparator.nullsLast(String::compareTo))
                .thenComparing(TradeRow::tradeId, Comparator.nullsLast(String::compareTo)));

        int buysCreated = 0, buysSkipped = 0;
        int sellsRecorded = 0, sellsSkipped = 0, sellsUnmatched = 0;
        List<String> errors = new ArrayList<>();
        Map<String, Integer> bySymbol = new LinkedHashMap<>();

        for (TradeRow row : rows) {
            try {
                String fullSymbol = row.exchange() + ":" + row.symbol();
                int qty = (int) Math.round(row.quantity());
                if (qty <= 0) {
                    continue;
                }

                if ("buy".equalsIgnoreCase(row.tradeType())) {
                    TaxLotDto.CreateLotRequest req = new TaxLotDto.CreateLotRequest(
                            fullSymbol, qty, row.price(), row.tradeDate(),
                            0.0, // tradebook charges are aggregated separately by Zerodha; leave 0 here
                            "Imported from Zerodha tradebook (order " + row.orderId() + ")",
                            row.tradeId(),
                            SOURCE,
                            row.isin());
                    boolean alreadyImported = lotRepository.findByTradeId(row.tradeId()).isPresent();
                    taxLotService.createLot(req);
                    if (alreadyImported) {
                        buysSkipped++;
                    } else {
                        buysCreated++;
                        bySymbol.merge(fullSymbol, qty, Integer::sum);
                    }
                } else if ("sell".equalsIgnoreCase(row.tradeType())) {
                    TaxLotDto.RecordSaleRequest req = new TaxLotDto.RecordSaleRequest(
                            fullSymbol, qty, row.price(), row.tradeDate(),
                            0.0, "FIFO", row.tradeId());
                    try {
                        TaxLotDto.SaleResponse resp = taxLotService.recordSale(req);
                        if (resp.matches().isEmpty()) {
                            // Either dedup hit (already imported) or zero-qty edge case.
                            sellsSkipped++;
                        } else {
                            sellsRecorded++;
                            bySymbol.merge(fullSymbol, -qty, Integer::sum);
                        }
                    } catch (IllegalArgumentException unmatched) {
                        // The CSV may not include the originating buy (it predates the export
                        // window). Log and continue rather than aborting the whole import.
                        sellsUnmatched++;
                        log.warn("Tradebook import: unmatched sell for {} {} qty={} on {} — buy is outside CSV window. {}",
                                fullSymbol, row.tradeId(), qty, row.tradeDate(), unmatched.getMessage());
                    }
                }
            } catch (Exception e) {
                errors.add(row.symbol() + " " + row.tradeDate() + " " + row.tradeId() + ": " + e.getMessage());
                log.warn("Tradebook import: row failed — {}", e.getMessage());
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rowsParsed", rows.size());
        summary.put("buysCreated", buysCreated);
        summary.put("buysSkippedDuplicate", buysSkipped);
        summary.put("sellsRecorded", sellsRecorded);
        summary.put("sellsSkippedDuplicate", sellsSkipped);
        summary.put("sellsUnmatched", sellsUnmatched);
        summary.put("errors", errors);
        summary.put("netQuantityBySymbol", bySymbol);
        log.info("Tradebook import done: parsed={}, buysCreated={}, buysDuped={}, sellsRecorded={}, sellsDuped={}, unmatched={}, errors={}",
                rows.size(), buysCreated, buysSkipped, sellsRecorded, sellsSkipped, sellsUnmatched, errors.size());
        return summary;
    }

    private List<TradeRow> parse(String csvBody) {
        List<TradeRow> rows = new ArrayList<>();
        try (Reader reader = new StringReader(csvBody);
             BufferedReader br = new BufferedReader(reader)) {
            String header = br.readLine();
            if (header == null) {
                return rows;
            }
            // Build column index map — Zerodha occasionally reorders or adds fields.
            String[] headerCols = header.split(",");
            Map<String, Integer> idx = new HashMap<>();
            for (int i = 0; i < headerCols.length; i++) {
                idx.put(headerCols[i].trim().toLowerCase(), i);
            }
            requireColumn(idx, "symbol");
            requireColumn(idx, "trade_date");
            requireColumn(idx, "exchange");
            requireColumn(idx, "trade_type");
            requireColumn(idx, "quantity");
            requireColumn(idx, "price");
            requireColumn(idx, "trade_id");

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cols = line.split(",", -1);
                try {
                    rows.add(new TradeRow(
                            get(cols, idx, "symbol"),
                            get(cols, idx, "exchange"),
                            LocalDate.parse(get(cols, idx, "trade_date")),
                            get(cols, idx, "trade_type"),
                            Double.parseDouble(get(cols, idx, "quantity")),
                            Double.parseDouble(get(cols, idx, "price")),
                            get(cols, idx, "trade_id"),
                            getOpt(cols, idx, "order_id"),
                            getOpt(cols, idx, "order_execution_time"),
                            getOpt(cols, idx, "segment"),
                            getOpt(cols, idx, "isin")));
                } catch (Exception e) {
                    log.warn("Tradebook import: skipping malformed row — {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read CSV body", e);
        }
        return rows;
    }

    private static void requireColumn(Map<String, Integer> idx, String name) {
        if (!idx.containsKey(name)) {
            throw new IllegalArgumentException("Tradebook CSV missing required column: " + name);
        }
    }

    private static String get(String[] cols, Map<String, Integer> idx, String name) {
        return cols[idx.get(name)].trim();
    }

    private static String getOpt(String[] cols, Map<String, Integer> idx, String name) {
        Integer i = idx.get(name);
        if (i == null || i >= cols.length) return null;
        String v = cols[i].trim();
        return v.isEmpty() ? null : v;
    }

    /** One parsed CSV row. */
    private record TradeRow(
            String symbol,
            String exchange,
            LocalDate tradeDate,
            String tradeType,
            double quantity,
            double price,
            String tradeId,
            String orderId,
            String executionTime,
            String segment,
            String isin
    ) {}
}
