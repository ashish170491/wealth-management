package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TradeRepository extends JpaRepository<TradeEntity, Long> {

    /**
     * Find all trades created between start and end date/time.
     */
    @Query("SELECT t FROM TradeEntity t WHERE t.createdAt >= :startDate AND t.createdAt < :endDate ORDER BY t.createdAt DESC")
    List<TradeEntity> findTradesCreatedBetween(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    /**
     * Find all completed trades (status = COMPLETED) for today.
     */
    @Query("SELECT t FROM TradeEntity t WHERE t.status = 'COMPLETED' AND t.createdAt >= :startDate AND t.createdAt < :endDate ORDER BY t.createdAt DESC")
    List<TradeEntity> findCompletedTradesForToday(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    /**
     * Find all trades with a specific status.
     */
    List<TradeEntity> findByStatus(String status);

    /**
     * Find a trade by its broker order ID.
     */
    java.util.Optional<TradeEntity> findByOrderId(String orderId);

    /**
     * Find all trades with SUBMITTED status for order monitoring.
     */
    @Query("SELECT t FROM TradeEntity t WHERE t.status = 'SUBMITTED' AND t.orderId IS NOT NULL ORDER BY t.createdAt ASC")
    List<TradeEntity> findPendingOrders();

    /**
     * Find all trades by symbol and status.
     * Used to check for pending SUBMITTED orders before placing new orders.
     */
    List<TradeEntity> findBySymbolAndStatus(String symbol, String status);

    /**
     * Delete old completed/cancelled/failed trades for data cleanup.
     * @return number of records deleted
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM TradeEntity t WHERE t.completedAt < :cutoffDate AND t.status IN :statuses")
    int deleteByCompletedAtBeforeAndStatusIn(@Param("cutoffDate") LocalDateTime cutoffDate, @Param("statuses") List<String> statuses);
}
