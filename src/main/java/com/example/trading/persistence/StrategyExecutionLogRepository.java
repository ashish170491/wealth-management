package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface StrategyExecutionLogRepository extends JpaRepository<StrategyExecutionLogEntity, Long> {

    /**
     * Delete old strategy execution logs for data cleanup.
     * @return number of records deleted
     */
    @Modifying
    @Query("DELETE FROM StrategyExecutionLogEntity s WHERE s.timestamp < :cutoffDate")
    int deleteByTimestampBefore(@Param("cutoffDate") LocalDateTime cutoffDate);
}
