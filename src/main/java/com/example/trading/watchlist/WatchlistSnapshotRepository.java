package com.example.trading.watchlist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface WatchlistSnapshotRepository extends JpaRepository<WatchlistSnapshotEntity, Long> {

    boolean existsBySymbolAndSnapshotDate(String symbol, LocalDate snapshotDate);

    List<WatchlistSnapshotEntity> findBySymbolAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
            String symbol, LocalDate fromDate);

    /** Every tracked symbol's rows from {@code fromDate}, so the page can be served in one query. */
    List<WatchlistSnapshotEntity> findBySnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(LocalDate fromDate);

    @Query("SELECT MAX(s.snapshotDate) FROM WatchlistSnapshotEntity s")
    LocalDate findLatestSnapshotDate();

    /** Latest row that carries a Nifty close — the DB-only "Nifty now" for the page. */
    @Query("SELECT s FROM WatchlistSnapshotEntity s WHERE s.niftyClose IS NOT NULL ORDER BY s.snapshotDate DESC")
    List<WatchlistSnapshotEntity> findWithNiftyDesc();

    @Modifying
    @Transactional
    @Query("DELETE FROM WatchlistSnapshotEntity s WHERE s.snapshotDate < :cutoff")
    int deleteBySnapshotDateBefore(LocalDate cutoff);
}
