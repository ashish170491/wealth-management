package com.example.trading.portfolio.core;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface CoreHoldingSnapshotRepository extends JpaRepository<CoreHoldingSnapshotEntity, Long> {

    Optional<CoreHoldingSnapshotEntity> findBySymbolAndClassifiedOn(String symbol, LocalDate classifiedOn);

    /** A symbol's rows newest first — what hysteresis walks. */
    @Query("SELECT c FROM CoreHoldingSnapshotEntity c WHERE c.symbol = :symbol "
            + "ORDER BY c.classifiedOn DESC")
    List<CoreHoldingSnapshotEntity> findHistory(@Param("symbol") String symbol);

    @Query("SELECT c FROM CoreHoldingSnapshotEntity c WHERE c.symbol = :symbol "
            + "AND c.classifiedOn >= :from ORDER BY c.classifiedOn ASC")
    List<CoreHoldingSnapshotEntity> findSeries(@Param("symbol") String symbol,
                                               @Param("from") LocalDate from);

    List<CoreHoldingSnapshotEntity> findByClassifiedOn(LocalDate classifiedOn);

    /**
     * The dates that actually have rows, newest first. Same walk-back discipline as the screener:
     * "today" is empty until the 10:30 job has run, and this app restarts every morning
     * (CLAUDE.md Gotcha 20).
     */
    @Query("SELECT DISTINCT c.classifiedOn FROM CoreHoldingSnapshotEntity c ORDER BY c.classifiedOn DESC")
    List<LocalDate> findClassificationDates();
}
