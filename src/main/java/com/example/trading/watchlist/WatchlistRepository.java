package com.example.trading.watchlist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for watchlist stocks.
 *
 * <p>Since SPEC §37 the table is the source of truth for membership: {@code active=false} rows
 * are soft-deleted and must be excluded from analysis, email and the default UI list. The
 * legacy {@code findAllOrderByScoreDesc} is kept only for the raw {@code GET /api/watchlist}
 * back-compat endpoint. A former {@code deleteBySymbolNotIn} JPQL delete (no {@code @Modifying},
 * no caller, would have thrown) was removed — B-060.
 */
@Repository
public interface WatchlistRepository extends JpaRepository<WatchlistEntity, Long> {

    Optional<WatchlistEntity> findBySymbol(String symbol);

    /** Every row, removed ones included. Legacy raw endpoint only. */
    @Query("SELECT w FROM WatchlistEntity w ORDER BY w.overallScore DESC NULLS LAST")
    List<WatchlistEntity> findAllOrderByScoreDesc();

    /** Tracked (not removed) rows, best timing score first — what the email and analysis loop use. */
    @Query("SELECT w FROM WatchlistEntity w WHERE w.active IS NULL OR w.active = true ORDER BY w.overallScore DESC NULLS LAST")
    List<WatchlistEntity> findActiveOrderByScoreDesc();

    /** Tracked rows, oldest first — the order the investor built the list in. */
    @Query("SELECT w FROM WatchlistEntity w WHERE w.active IS NULL OR w.active = true ORDER BY w.addedOn ASC NULLS LAST, w.symbol ASC")
    List<WatchlistEntity> findActiveOrderByAddedOn();

    /** Soft-deleted rows, most recently removed first. */
    @Query("SELECT w FROM WatchlistEntity w WHERE w.active = false ORDER BY w.removedOn DESC NULLS LAST")
    List<WatchlistEntity> findRemoved();

    @Query("SELECT w FROM WatchlistEntity w WHERE (w.active IS NULL OR w.active = true) AND w.entrySignal = 'STRONG_BUY' ORDER BY w.overallScore DESC")
    List<WatchlistEntity> findStrongBuySignals();

    @Query("SELECT w FROM WatchlistEntity w WHERE (w.active IS NULL OR w.active = true) AND w.entrySignal IN ('STRONG_BUY', 'BUY') ORDER BY w.overallScore DESC")
    List<WatchlistEntity> findBuySignals();
}
