package com.example.trading.analyst;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AnalystTargetRepository extends JpaRepository<AnalystTargetEntity, Long> {

    Optional<AnalystTargetEntity> findByDedupKey(String dedupKey);

    List<AnalystTargetEntity> findBySymbolOrderByIssuedOnDesc(String symbol);

    List<AnalystTargetEntity> findBySymbolInOrderByIssuedOnDesc(List<String> symbols);

    /**
     * The second dedup layer: the same house repeating the same target within a few days is one
     * call written up twice, not two calls (SPEC §49.5). The exact key catches a re-run on the
     * same day; this catches Wednesday's note being reported again on Friday.
     */
    @Query("SELECT t FROM AnalystTargetEntity t WHERE t.symbol = :symbol AND t.brokerage = :brokerage "
            + "AND t.issuedOn >= :since ORDER BY t.issuedOn DESC")
    List<AnalystTargetEntity> findRecentByHouse(@Param("symbol") String symbol,
                                                @Param("brokerage") String brokerage,
                                                @Param("since") LocalDate since);

    /** Live calls by this house on this stock, so a new one can supersede them. */
    @Query("SELECT t FROM AnalystTargetEntity t WHERE t.symbol = :symbol AND t.brokerage = :brokerage "
            + "AND t.status IN ('PENDING', 'UNPRICED') AND t.issuedOn < :before")
    List<AnalystTargetEntity> findOpenByHouseBefore(@Param("symbol") String symbol,
                                                    @Param("brokerage") String brokerage,
                                                    @Param("before") LocalDate before);

    /**
     * Rows the outcome pass should look at, least-recently-measured first.
     *
     * <p>Ordered so a bounded run rotates through the whole open book rather than re-measuring
     * the same head of the list every day — the defect that left Insider Pulse measuring nothing
     * for months (B-074). Nulls first: a row never measured outranks one measured last week.
     */
    @Query("SELECT t FROM AnalystTargetEntity t WHERE t.status IN ('PENDING', 'UNPRICED') "
            + "ORDER BY t.lastMeasuredAt ASC NULLS FIRST, t.issuedOn ASC")
    List<AnalystTargetEntity> findOpenForMeasurement(Pageable pageable);

    @Query("SELECT COUNT(t) FROM AnalystTargetEntity t WHERE t.status IN ('PENDING', 'UNPRICED')")
    long countOpen();

    /** Distinct symbols carrying a live target, for the §38.2 coverage row. */
    @Query("SELECT DISTINCT t.symbol FROM AnalystTargetEntity t WHERE t.status IN ('PENDING', 'UNPRICED') "
            + "OR t.issuedOn >= :since")
    List<String> findSymbolsWithRecentTarget(@Param("since") LocalDate since);

    /** Everything resolved or resolvable, for the track record. */
    @Query("SELECT t FROM AnalystTargetEntity t WHERE t.issuedOn >= :since ORDER BY t.issuedOn DESC")
    List<AnalystTargetEntity> findIssuedSince(@Param("since") LocalDate since);

    /** Every still-running target, for the overlap read (SPEC §49.12). Excludes UNPRICED:
     *  a call we could not price is not a claim we can compare against a price. */
    @Query("SELECT t FROM AnalystTargetEntity t WHERE t.status = 'PENDING'")
    List<AnalystTargetEntity> findAllOpen();

    @Query("SELECT MAX(t.createdAt) FROM AnalystTargetEntity t")
    LocalDateTime findLatestCreatedAt();

    @Query("SELECT MAX(t.lastMeasuredAt) FROM AnalystTargetEntity t")
    LocalDateTime findLatestMeasuredAt();
}
