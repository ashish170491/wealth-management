package com.example.trading.fundamentals;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FundamentalsBackfillStatusRepository
        extends JpaRepository<FundamentalsBackfillStatusEntity, Long> {

    Optional<FundamentalsBackfillStatusEntity> findBySymbol(String symbol);

    List<FundamentalsBackfillStatusEntity> findByStatus(String status);

    /**
     * Symbols that have reached a terminal state and must not be re-queued.
     *
     * <p>COMPLETE and UNAVAILABLE are both terminal: one has everything the archive holds, the
     * other has established that the archive holds nothing for it. Re-attempting either burns
     * requests to learn what is already recorded - and since the batch picker orders by need,
     * they would be picked first, forever.
     */
    @Query("SELECT s.symbol FROM FundamentalsBackfillStatusEntity s "
            + "WHERE s.status IN ('COMPLETE', 'UNAVAILABLE')")
    List<String> findSettledSymbols();

    /** Status counts in one query rather than one per symbol (Gotcha 17/39). */
    @Query("SELECT s.status, COUNT(s) FROM FundamentalsBackfillStatusEntity s GROUP BY s.status")
    List<Object[]> countByStatus();

    /**
     * When any symbol was last attempted, for the data-health screen's stall check (SPEC 44).
     *
     * <p>Deliberately over the whole table rather than over pending rows only: a queue that
     * still has work but has not been touched in days is stalled, and asking only the rows it
     * failed to reach would answer with their own staleness rather than the job's.
     */
    @Query("SELECT MAX(s.lastAttemptAt) FROM FundamentalsBackfillStatusEntity s")
    java.time.LocalDateTime findLastAttemptAt();
}
