package com.example.trading.portfolio.dividend;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.portfolio.AllocationDto;
import com.example.trading.portfolio.AllocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.Month;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dividend tracking (SPEC.md §11). MVP: manual entry + YTD aggregation + reinvestment
 * suggestions based on allocation drift. Automated NSE fetch is out of scope here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DividendService {

    private final DividendEventRepository repository;
    private final HoldingsRepository holdingsRepository;
    private final AllocationService allocationService;

    @Transactional
    public DividendEventEntity record(DividendDto.CreateEventRequest req) {
        DividendEventEntity e = DividendEventEntity.builder()
                .symbol(req.symbol())
                .exDate(req.exDate())
                .recordDate(req.recordDate())
                .amountPerShare(req.amountPerShare())
                .type(req.type())
                .status(req.status() != null ? req.status() : "ANNOUNCED")
                .totalReceived(req.totalReceived())
                .notes(req.notes())
                .build();
        e = repository.save(e);
        log.info("Dividend: recorded {} ₹{}/share {} {} (status={})",
                e.getSymbol(), e.getAmountPerShare(), e.getType(), e.getExDate(), e.getStatus());
        return e;
    }

    /**
     * Records that an announced dividend actually arrived (SPEC §11). The amount is the investor's
     * figure from the bank statement, not a projection: only RECEIVED rows count toward income.
     */
    @Transactional
    public DividendDto.DividendView markReceived(Long id, Double amount) {
        DividendEventEntity e = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No dividend event " + id));
        e.setStatus("RECEIVED");
        e.setTotalReceived(amount != null ? amount : projectedFor(e));
        e = repository.save(e);
        log.info("Dividend: {} marked received, Rs {}", e.getSymbol(), e.getTotalReceived());
        return toView(e);
    }

    public List<DividendDto.DividendView> list() {
        return repository.findAll().stream().map(this::toView).toList();
    }

    public List<DividendDto.DividendView> listForSymbol(String symbol) {
        return repository.findBySymbol(symbol).stream().map(this::toView).toList();
    }

    public DividendDto.AnnualSummary annualSummary(int fiscalYear) {
        LocalDate start = LocalDate.of(fiscalYear, Month.APRIL, 1);
        LocalDate end = LocalDate.of(fiscalYear + 1, Month.MARCH, 31);
        List<DividendEventEntity> events = repository.findByExDateBetween(start, end);
        double received = events.stream()
                .filter(e -> "RECEIVED".equals(e.getStatus()) && e.getTotalReceived() != null)
                .mapToDouble(DividendEventEntity::getTotalReceived).sum();
        double projected = events.stream()
                .filter(e -> "ANNOUNCED".equals(e.getStatus()))
                .mapToDouble(this::projectedFor).sum();
        Map<String, Double> perSymbol = events.stream()
                .filter(e -> "RECEIVED".equals(e.getStatus()) && e.getTotalReceived() != null)
                .collect(Collectors.groupingBy(DividendEventEntity::getSymbol,
                        Collectors.summingDouble(DividendEventEntity::getTotalReceived)));
        return new DividendDto.AnnualSummary(fiscalYear, received, projected, events.size(), perSymbol);
    }

    /**
     * Reinvestment suggestions: for each target-STOCK under-weighted by > 1pp,
     * suggest adding using accrued dividend income on that symbol (or pooled).
     */
    public List<DividendDto.ReinvestmentSuggestion> reinvestmentSuggestions() {
        AllocationDto.DriftResponse drift = allocationService.computeDrift();
        Map<String, Double> accruedBySymbol = listUnreinvestedBySymbol();
        double totalAccrued = accruedBySymbol.values().stream().mapToDouble(Double::doubleValue).sum();

        List<DividendDto.ReinvestmentSuggestion> out = new ArrayList<>();
        for (AllocationDto.DriftBucket b : drift.buckets()) {
            if (!AllocationService.BUCKET_STOCK.equals(b.bucketType())) continue;
            if (b.driftPp() >= -1.0) continue; // only under-weighted > 1pp
            out.add(new DividendDto.ReinvestmentSuggestion(
                    b.bucketKey(),
                    accruedBySymbol.getOrDefault(b.bucketKey(), 0.0),
                    b.actualWeight(),
                    b.targetWeight(),
                    b.driftPp(),
                    String.format("Under-weight by %.1fpp. Consider reinvesting ₹%.0f accrued dividends (or pooled ₹%.0f).",
                            Math.abs(b.driftPp()),
                            accruedBySymbol.getOrDefault(b.bucketKey(), 0.0),
                            totalAccrued)));
        }
        // Sort most-under-weight first.
        out.sort(Comparator.comparingDouble(DividendDto.ReinvestmentSuggestion::driftPp));
        return out;
    }

    private Map<String, Double> listUnreinvestedBySymbol() {
        // "Unreinvested" ≈ RECEIVED dividends in the current fiscal year. Once we add a
        // reinvested-against field, this can track actual consumption.
        LocalDate today = LocalDate.now();
        int fy = today.getMonth().getValue() >= Month.APRIL.getValue()
                ? today.getYear() : today.getYear() - 1;
        LocalDate start = LocalDate.of(fy, Month.APRIL, 1);
        LocalDate end = LocalDate.of(fy + 1, Month.MARCH, 31);
        return repository.findByExDateBetween(start, end).stream()
                .filter(e -> "RECEIVED".equals(e.getStatus()) && e.getTotalReceived() != null)
                .collect(Collectors.groupingBy(DividendEventEntity::getSymbol,
                        Collectors.summingDouble(DividendEventEntity::getTotalReceived)));
    }

    private DividendDto.DividendView toView(DividendEventEntity e) {
        Double projected = "ANNOUNCED".equals(e.getStatus()) ? projectedFor(e) : null;
        return new DividendDto.DividendView(
                e.getId(), e.getSymbol(), e.getExDate(), e.getRecordDate(),
                e.getAmountPerShare(), e.getType(), e.getStatus(), e.getTotalReceived(), projected);
    }

    private double projectedFor(DividendEventEntity e) {
        HoldingsEntity h = holdingsRepository.findBySymbol(e.getSymbol()).orElse(null);
        return (h != null) ? e.getAmountPerShare() * h.getQuantity() : 0.0;
    }
}
