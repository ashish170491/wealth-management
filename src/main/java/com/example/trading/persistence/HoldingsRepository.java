package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HoldingsRepository extends JpaRepository<HoldingsEntity, Long> {

    Optional<HoldingsEntity> findBySymbol(String symbol);

    Optional<HoldingsEntity> findByTradingSymbol(String tradingSymbol);

    List<HoldingsEntity> findByRecommendation(String recommendation);

    @Query("SELECT h FROM HoldingsEntity h WHERE h.overallScore >= :minScore ORDER BY h.overallScore DESC")
    List<HoldingsEntity> findByMinScore(@Param("minScore") Integer minScore);

    @Query("SELECT h FROM HoldingsEntity h WHERE h.overallScore < :maxScore ORDER BY h.overallScore ASC")
    List<HoldingsEntity> findByMaxScore(@Param("maxScore") Integer maxScore);

    @Query("SELECT h FROM HoldingsEntity h WHERE h.recommendation IN ('SELL', 'STRONG_SELL') ORDER BY h.overallScore ASC")
    List<HoldingsEntity> findExitCandidates();

    @Query("SELECT h FROM HoldingsEntity h WHERE h.recommendation IN ('BUY', 'STRONG_BUY') ORDER BY h.overallScore DESC")
    List<HoldingsEntity> findAccumulateCandidates();

    @Query("SELECT h FROM HoldingsEntity h ORDER BY h.pnlPercent DESC")
    List<HoldingsEntity> findAllOrderByPnlDesc();

    @Query("SELECT h FROM HoldingsEntity h ORDER BY h.overallScore DESC")
    List<HoldingsEntity> findAllOrderByScoreDesc();

    // --- Active-only variants (quantity > 0). Exited/zero-quantity rows are reconciled away on the
    // next successful broker sync, but these guard reports/analysis even before that runs (B-016). ---

    @Query("SELECT h FROM HoldingsEntity h WHERE h.quantity > 0 ORDER BY h.overallScore DESC")
    List<HoldingsEntity> findActiveOrderByScoreDesc();

    @Query("SELECT h FROM HoldingsEntity h WHERE h.quantity > 0")
    List<HoldingsEntity> findActive();

    @Query("SELECT SUM(h.investedValue) FROM HoldingsEntity h")
    Double getTotalInvestedValue();

    @Query("SELECT SUM(h.currentValue) FROM HoldingsEntity h")
    Double getTotalCurrentValue();

    @Query("SELECT SUM(h.pnl) FROM HoldingsEntity h")
    Double getTotalPnL();

    @Query("SELECT COUNT(h) FROM HoldingsEntity h WHERE h.pnl > 0")
    Long countProfitableHoldings();

    @Query("SELECT COUNT(h) FROM HoldingsEntity h WHERE h.pnl < 0")
    Long countLossHoldings();
}
