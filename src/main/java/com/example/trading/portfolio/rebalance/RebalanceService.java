package com.example.trading.portfolio.rebalance;

import com.example.trading.persistence.HoldingsEntity;
import com.example.trading.persistence.HoldingsRepository;
import com.example.trading.portfolio.AllocationDto;
import com.example.trading.portfolio.AllocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebalancing engine (SPEC.md §10). MVP: generates a non-executing trade list for
 * STOCK-level target-weight drifts, plus narrative hints for SECTOR / MARKET_CAP
 * drifts (which require human judgment to decide which stocks to adjust).
 *
 * <p>Future work: STCG-minimization (prefer selling LTCG-eligible lots), cost optimization,
 * and automatic stock selection inside over-weight sectors.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RebalanceService {

    private final AllocationService allocationService;
    private final HoldingsRepository holdingsRepository;

    public RebalanceDto.RebalanceProposal generateProposal() {
        AllocationDto.DriftResponse drift = allocationService.computeDrift();
        List<RebalanceDto.Trade> trades = new ArrayList<>();
        List<RebalanceDto.BucketHint> hints = new ArrayList<>();

        for (AllocationDto.DriftBucket b : drift.buckets()) {
            if (!"OVER_TOLERANCE".equals(b.alertLevel())) continue;

            switch (b.bucketType()) {
                case AllocationService.BUCKET_STOCK -> tradeForStock(drift.totalPortfolioValue(), b, trades);
                case AllocationService.BUCKET_SECTOR, AllocationService.BUCKET_MARKET_CAP ->
                        hints.add(bucketHint(b));
                default -> { /* ignore unknown bucket types */ }
            }
        }

        double buyValue = trades.stream().filter(t -> "BUY".equals(t.action())).mapToDouble(RebalanceDto.Trade::amount).sum();
        double sellValue = trades.stream().filter(t -> "SELL".equals(t.action())).mapToDouble(RebalanceDto.Trade::amount).sum();
        double estimatedCost = (buyValue + sellValue) * 0.0035; // ~0.35% round-trip estimate (broker + STT + GST + slippage)

        String notes = trades.isEmpty() && hints.isEmpty()
                ? "No drift exceeds tolerance. Portfolio is balanced against the current profile."
                : "Review each suggestion before placing trades. This engine does not auto-execute.";

        return new RebalanceDto.RebalanceProposal(
                LocalDate.now(), drift.profileName(), drift.totalPortfolioValue(),
                trades, hints, buyValue, sellValue, estimatedCost, notes);
    }

    private void tradeForStock(double totalValue, AllocationDto.DriftBucket b, List<RebalanceDto.Trade> out) {
        HoldingsEntity h = holdingsRepository.findBySymbol(b.bucketKey()).orElse(null);
        double currentPrice = (h != null && h.getCurrentPrice() > 0) ? h.getCurrentPrice() : 0;
        double amountToMove = Math.abs(b.driftPp()) * totalValue / 100.0;
        String action = b.driftPp() > 0 ? "SELL" : "BUY";
        int qty = currentPrice > 0 ? (int) Math.round(amountToMove / currentPrice) : 0;
        String reason = String.format("Drift %+.1fpp from target %.1f%% (actual %.1f%%)",
                b.driftPp(), b.targetWeight(), b.actualWeight());
        out.add(new RebalanceDto.Trade(
                b.bucketKey(), action, qty, currentPrice, amountToMove,
                b.actualWeight(), b.targetWeight(), b.driftPp(), reason));
    }

    private RebalanceDto.BucketHint bucketHint(AllocationDto.DriftBucket b) {
        String rec = b.driftPp() > 0
                ? String.format("Reduce %s exposure by %.1fpp — trim the largest holdings in this bucket.",
                        b.bucketKey(), Math.abs(b.driftPp()))
                : String.format("Increase %s exposure by %.1fpp — add to under-weighted conviction-intact holdings or start a new position.",
                        b.bucketKey(), Math.abs(b.driftPp()));
        return new RebalanceDto.BucketHint(
                b.bucketType(), b.bucketKey(), b.actualWeight(), b.targetWeight(), b.driftPp(), rec);
    }
}
