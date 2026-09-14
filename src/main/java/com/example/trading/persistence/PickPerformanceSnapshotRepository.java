package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PickPerformanceSnapshotRepository extends JpaRepository<PickPerformanceSnapshotEntity, Long> {

    /**
     * Latest snapshot for each symbol (today's report).
     */
    List<PickPerformanceSnapshotEntity> findBySnapshotDateOrderByValuePickScoreDesc(LocalDate snapshotDate);

    /**
     * Historical snapshots for a specific symbol (track how its score evolved).
     */
    List<PickPerformanceSnapshotEntity> findBySymbolOrderBySnapshotDateDesc(String symbol);

    /**
     * Historical snapshots for a symbol within a date range.
     */
    List<PickPerformanceSnapshotEntity> findBySymbolAndSnapshotDateBetweenOrderBySnapshotDateDesc(
            String symbol, LocalDate startDate, LocalDate endDate);

    /**
     * Top value picks on a given date.
     */
    @Query("SELECT p FROM PickPerformanceSnapshotEntity p WHERE p.snapshotDate = :date " +
           "AND p.valuePickScore >= :minScore ORDER BY p.valuePickScore DESC")
    List<PickPerformanceSnapshotEntity> findTopValuePicks(
            @Param("date") LocalDate date, @Param("minScore") double minScore);

    /**
     * Find consistent performers - stocks that appear on multiple snapshot dates with high scores.
     */
    @Query("SELECT p.symbol, COUNT(DISTINCT p.snapshotDate) as appearances, AVG(p.valuePickScore) as avgScore " +
           "FROM PickPerformanceSnapshotEntity p " +
           "WHERE p.snapshotDate >= :since AND p.valuePickScore >= :minScore " +
           "GROUP BY p.symbol HAVING COUNT(DISTINCT p.snapshotDate) >= :minAppearances " +
           "ORDER BY avgScore DESC")
    List<Object[]> findConsistentHighScorers(
            @Param("since") LocalDate since,
            @Param("minScore") double minScore,
            @Param("minAppearances") long minAppearances);

    /**
     * Find stocks by category on a given date.
     */
    List<PickPerformanceSnapshotEntity> findBySnapshotDateAndPickCategoryOrderByValuePickScoreDesc(
            LocalDate snapshotDate, String pickCategory);

    /**
     * Check if snapshot already exists for a symbol on a date (avoid duplicates).
     */
    boolean existsBySymbolAndSnapshotDate(String symbol, LocalDate snapshotDate);

    /**
     * Delete snapshots for a date (for re-running).
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM PickPerformanceSnapshotEntity p WHERE p.snapshotDate = :date")
    int deleteBySnapshotDate(@Param("date") LocalDate date);

    /**
     * Cleanup old snapshots for data retention.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM PickPerformanceSnapshotEntity p WHERE p.createdAt < :cutoffDate")
    int deleteByCreatedAtBefore(@Param("cutoffDate") LocalDateTime cutoffDate);
}
