package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface HoldingsHistoryRepository extends JpaRepository<HoldingsHistoryEntity, Long> {

    List<HoldingsHistoryEntity> findBySymbol(String symbol);

    List<HoldingsHistoryEntity> findBySymbolAndRecordDateBetween(String symbol, LocalDate startDate, LocalDate endDate);

    @Query("SELECT h FROM HoldingsHistoryEntity h WHERE h.recordDate = :date ORDER BY h.symbol")
    List<HoldingsHistoryEntity> findByRecordDate(@Param("date") LocalDate date);

    @Query("SELECT h FROM HoldingsHistoryEntity h WHERE h.symbol = :symbol ORDER BY h.recordDate DESC")
    List<HoldingsHistoryEntity> findBySymbolOrderByDateDesc(@Param("symbol") String symbol);

    boolean existsBySymbolAndRecordDate(String symbol, LocalDate recordDate);

    /**
     * All symbols' history in one query, for the dashboard's sparkline matrix (SPEC §27).
     * Deliberately one query rather than N per-symbol calls.
     */
    List<HoldingsHistoryEntity> findByRecordDateGreaterThanEqualOrderByRecordDateAsc(LocalDate from);

    /**
     * Portfolio-level equity curve for the dashboard: one row per date with the whole
     * portfolio's invested cost, market value and P&L summed across symbols.
     *
     * <p>Returns {@code Object[]{LocalDate, Double invested, Double value, Double pnl}}.
     * {@code quantity * averagePrice} is the cost basis and {@code quantity * closePrice}
     * the market value on that date — computed in SQL so we never load 3 years of rows.
     */
    @Query("SELECT h.recordDate, "
         + "SUM(h.quantity * h.averagePrice), "
         + "SUM(h.quantity * h.closePrice), "
         + "SUM(h.pnl) "
         + "FROM HoldingsHistoryEntity h WHERE h.recordDate >= :from "
         + "GROUP BY h.recordDate ORDER BY h.recordDate ASC")
    List<Object[]> findPortfolioSeries(@Param("from") LocalDate from);

    /** Newest snapshot date present, for the dashboard freshness strip. Null when empty. */
    @Query("SELECT MAX(h.recordDate) FROM HoldingsHistoryEntity h")
    LocalDate findLatestRecordDate();

    /**
     * Delete old holdings history for data cleanup.
     * @return number of records deleted
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM HoldingsHistoryEntity h WHERE h.recordDate < :cutoffDate")
    int deleteBySnapshotDateBefore(@Param("cutoffDate") java.time.LocalDate cutoffDate);
}
