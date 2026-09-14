package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Repository for sector reversal signals.
 */
@Repository
public interface SectorReversalRepository extends JpaRepository<SectorReversalEntity, Long> {

    /**
     * Find signals for a specific sector and date.
     */
    List<SectorReversalEntity> findBySectorNameAndScanDate(String sectorName, LocalDate scanDate);

    /**
     * Find signals for a specific symbol.
     */
    List<SectorReversalEntity> findBySymbolOrderByScanDateDesc(String symbol);

    /**
     * Find today's signals ordered by score.
     */
    List<SectorReversalEntity> findByScanDateOrderByUpsideScoreDesc(LocalDate scanDate);

    /**
     * Find strong buy signals today.
     */
    @Query("SELECT s FROM SectorReversalEntity s WHERE s.scanDate = :date AND s.recommendation = 'STRONG_BUY' ORDER BY s.upsideScore DESC")
    List<SectorReversalEntity> findStrongBuySignals(LocalDate date);

    /**
     * Find all buy/strong_buy signals today.
     */
    @Query("SELECT s FROM SectorReversalEntity s WHERE s.scanDate = :date AND s.recommendation IN ('STRONG_BUY', 'BUY') ORDER BY s.upsideScore DESC")
    List<SectorReversalEntity> findBuySignals(LocalDate date);

    /**
     * Find signals for reversing sectors today.
     */
    @Query("SELECT s FROM SectorReversalEntity s WHERE s.scanDate = :date AND s.sectorReversing = true ORDER BY s.reversalStrength DESC, s.upsideScore DESC")
    List<SectorReversalEntity> findReversingSectorSignals(LocalDate date);

    /**
     * Find top N stock picks today across all sectors.
     */
    @Query("SELECT s FROM SectorReversalEntity s WHERE s.scanDate = :date AND s.recommendation IN ('STRONG_BUY', 'BUY') ORDER BY s.upsideScore DESC LIMIT :limit")
    List<SectorReversalEntity> findTopPicksToday(LocalDate date, int limit);

    /**
     * Find actionable picks (STRONG_BUY, BUY, ACCUMULATE) within a date range for performance tracking.
     */
    @Query("SELECT s FROM SectorReversalEntity s WHERE s.recommendation IN ('STRONG_BUY', 'BUY', 'ACCUMULATE') " +
           "AND s.scanDate BETWEEN :startDate AND :endDate ORDER BY s.scanDate DESC, s.upsideScore DESC")
    List<SectorReversalEntity> findActionablePicksBetween(LocalDate startDate, LocalDate endDate);

    /**
     * Delete old sector reversal signals for data cleanup.
     * @return number of records deleted
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM SectorReversalEntity s WHERE s.createdAt < :cutoffDate")
    int deleteByCreatedAtBefore(@org.springframework.data.repository.query.Param("cutoffDate") java.time.LocalDateTime cutoffDate);
}
