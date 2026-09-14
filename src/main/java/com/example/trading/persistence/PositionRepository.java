package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PositionRepository extends JpaRepository<PositionEntity, Long> {
    List<PositionEntity> findByStatus(String status);
    Optional<PositionEntity> findBySymbolAndStatus(String symbol, String status);

    /**
     * Find all positions by symbol and status (for handling duplicates).
     */
    List<PositionEntity> findAllBySymbolAndStatus(String symbol, String status);

    /**
     * Find the most recent position by symbol and status.
     */
    Optional<PositionEntity> findFirstBySymbolAndStatusOrderByOpenedAtDesc(String symbol, String status);

    /**
     * Find positions closed within a date range (for EOD summary).
     */
    @Query("SELECT p FROM PositionEntity p WHERE p.status = 'CLOSED' AND p.closedAt >= :startTime AND p.closedAt < :endTime")
    List<PositionEntity> findClosedPositionsBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * Find positions opened within a date range (for EOD summary).
     */
    @Query("SELECT p FROM PositionEntity p WHERE p.openedAt >= :startTime AND p.openedAt < :endTime")
    List<PositionEntity> findPositionsOpenedBetween(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    /**
     * Find a position by its trade/order ID.
     */
    Optional<PositionEntity> findByTradeId(String tradeId);
}
