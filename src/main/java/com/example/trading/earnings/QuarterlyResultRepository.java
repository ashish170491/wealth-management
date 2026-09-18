package com.example.trading.earnings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Reads over {@code quarterly_results} (SPEC.md §50). */
public interface QuarterlyResultRepository extends JpaRepository<QuarterlyResultEntity, Long> {

    Optional<QuarterlyResultEntity> findBySymbolAndQuarterEnd(String symbol, LocalDate quarterEnd);

    /** A company's filed quarters, newest first — the order every read here wants. */
    List<QuarterlyResultEntity> findBySymbolOrderByQuarterEndDesc(String symbol);

    /**
     * Newest-first quarters for several companies in one query.
     *
     * <p>One query for a whole table rather than one per row: the portfolio resolves each holding
     * through up to four symbol spellings, so per-row lookups would be ~120 queries on a page
     * load (the B-115 shape).
     */
    @Query("SELECT q FROM QuarterlyResultEntity q WHERE q.symbol IN :symbols "
            + "ORDER BY q.symbol ASC, q.quarterEnd DESC")
    List<QuarterlyResultEntity> findForSymbols(@Param("symbols") List<String> symbols);

    /**
     * Results that became public on or after {@code since}, newest first.
     *
     * <p>Ordered by {@code availableFrom} — the day the company published — and not by when this
     * app happened to read it, so a backfill of old quarters can never present itself as a week
     * of fresh results.
     */
    @Query("SELECT q FROM QuarterlyResultEntity q WHERE q.availableFrom >= :since "
            + "ORDER BY q.availableFrom DESC, q.symbol ASC")
    List<QuarterlyResultEntity> findPublishedSince(@Param("since") LocalDate since);

    /** Published since {@code since} and never announced — the candidates for a new-result alert. */
    @Query("SELECT q FROM QuarterlyResultEntity q WHERE q.availableFrom >= :since "
            + "AND q.announcedAt IS NULL AND q.symbol IN :symbols "
            + "ORDER BY q.availableFrom DESC")
    List<QuarterlyResultEntity> findUnannouncedSince(@Param("since") LocalDate since,
                                                     @Param("symbols") List<String> symbols);

    /** The newest {@code availableFrom} on file — the last time a company published. */
    @Query("SELECT MAX(q.availableFrom) FROM QuarterlyResultEntity q")
    Optional<LocalDate> findLatestAvailableFrom();

    /**
     * When the capture last touched any row — the freshness stamp.
     *
     * <p>Deliberately not {@code availableFrom}: companies report in a cluster and then go quiet
     * for two months, so a publication stamp would turn the strip amber every inter-season week
     * and train the eye past the colour on the keys where it means something (Gotcha 125). The
     * screening run touches these rows every weekday whatever the reporting calendar is doing,
     * which is exactly what amber should be answering — did the job run.
     *
     * <p>Returns a bare {@code LocalDateTime}, null when empty, because that is what every other
     * freshness source returns and {@code DashboardService.asString} simply calls {@code toString}
     * on whatever it is handed. An {@code Optional} here rendered as the literal string
     * {@code "Optional[2026-09-17T23:33:24]"} on the freshness strip at the top of every screen —
     * caught by reading the value, not by any check (Gotcha 104's rule, applied to a wire format).
     */
    @Query("SELECT MAX(q.updatedAt) FROM QuarterlyResultEntity q")
    java.time.LocalDateTime findLatestCapturedAt();

    /** Distinct companies with at least one filed quarter — the coverage denominator's numerator. */
    @Query("SELECT DISTINCT q.symbol FROM QuarterlyResultEntity q")
    List<String> findCoveredSymbols();

    long countBySymbol(String symbol);
}
