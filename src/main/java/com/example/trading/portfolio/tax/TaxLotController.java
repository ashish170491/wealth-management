package com.example.trading.portfolio.tax;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tax-Lot Tracking endpoints. See SPEC.md §9 and §16.
 */
@RestController
@RequestMapping("/api/portfolio/tax-lots")
@RequiredArgsConstructor
public class TaxLotController {

    private final TaxLotService service;
    private final ZerodhaTradebookImportService zerodhaImporter;
    private final TaxLotAutoCaptureScheduler autoCaptureScheduler;

    /** GET /api/portfolio/tax-lots/{symbol} — per-lot view with days held and projected tax. */
    @GetMapping("/{symbol}")
    public ResponseEntity<TaxLotDto.SymbolLotsResponse> getLotsForSymbol(@PathVariable String symbol) {
        return ResponseEntity.ok(service.getLotsForSymbol(symbol));
    }

    /** POST /api/portfolio/tax-lots — record a manual purchase lot. */
    @PostMapping
    public ResponseEntity<TaxLotEntity> createLot(@RequestBody TaxLotDto.CreateLotRequest req) {
        return ResponseEntity.ok(service.createLot(req));
    }

    /** POST /api/portfolio/tax-lots/sell — record a sale, triggers FIFO/LIFO/HIFO matching. */
    @PostMapping("/sell")
    public ResponseEntity<TaxLotDto.SaleResponse> recordSale(@RequestBody TaxLotDto.RecordSaleRequest req) {
        return ResponseEntity.ok(service.recordSale(req));
    }

    /** POST /api/portfolio/tax-harvest — loss-harvest candidates + LTCG-eligible + approaching-cutoff. */
    @PostMapping("/harvest")
    public ResponseEntity<TaxLotDto.HarvestResponse> harvest() {
        return ResponseEntity.ok(service.computeHarvestSuggestions());
    }

    /** POST /api/portfolio/tax-lots/bulk-import — import many lots in one call (e.g. from Zerodha tradebook). */
    @PostMapping("/bulk-import")
    public ResponseEntity<java.util.Map<String, Object>> bulkImport(
            @RequestBody java.util.List<TaxLotDto.CreateLotRequest> requests) {
        return ResponseEntity.ok(service.bulkImport(requests));
    }

    /** DELETE /api/portfolio/tax-lots/all — destructive: wipe every lot and sale (use before re-importing). */
    @DeleteMapping("/all")
    public ResponseEntity<java.util.Map<String, Long>> deleteAll() {
        return ResponseEntity.ok(service.deleteAll());
    }

    /**
     * POST /api/portfolio/tax-lots/import-zerodha-csv
     * <p>One-shot backfill from a Zerodha Console tradebook export. Accepts the CSV either
     * as a multipart upload ({@code file=...}) or as the raw request body
     * ({@code Content-Type: text/csv}). Idempotent — re-running with the same CSV is a no-op
     * because each row is deduped on its broker {@code trade_id}.
     */
    @PostMapping(value = "/import-zerodha-csv", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    public ResponseEntity<java.util.Map<String, Object>> importZerodhaCsv(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestBody(required = false) String rawBody) throws IOException {
        String csv;
        if (file != null && !file.isEmpty()) {
            csv = new String(file.getBytes(), StandardCharsets.UTF_8);
        } else if (rawBody != null && !rawBody.isBlank()) {
            csv = rawBody;
        } else {
            return ResponseEntity.badRequest().body(java.util.Map.of(
                    "error", "Send the CSV either as a multipart 'file' field or as the raw request body."));
        }
        return ResponseEntity.ok(zerodhaImporter.importCsv(csv));
    }

    /**
     * POST /api/portfolio/tax-lots/capture-today
     * <p>Manual trigger for the live auto-capture scheduler — pulls today's broker trades
     * and converts buys/sells into lots. Idempotent: re-runs are deduped on broker
     * {@code trade_id}. The scheduled run is at 15:28 IST MON-FRI.
     */
    @PostMapping("/capture-today")
    public ResponseEntity<java.util.Map<String, Object>> captureToday() {
        return ResponseEntity.ok(autoCaptureScheduler.runCapture());
    }
}
