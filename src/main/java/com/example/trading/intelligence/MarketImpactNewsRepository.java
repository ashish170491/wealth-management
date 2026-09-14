package com.example.trading.intelligence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MarketImpactNewsRepository extends JpaRepository<MarketImpactNewsEntity, Long> {

    boolean existsByTitleHash(String titleHash);

    List<MarketImpactNewsEntity> findByImpactLevelAndAlertedFalseOrderByPublishedAtDesc(String impactLevel);

    List<MarketImpactNewsEntity> findByCreatedAtAfterOrderByPublishedAtDesc(LocalDateTime since);

    List<MarketImpactNewsEntity> findByImpactCategoryOrderByCreatedAtDesc(String impactCategory);

    @Query("SELECT n FROM MarketImpactNewsEntity n WHERE n.createdAt >= :since " +
           "ORDER BY CASE n.impactLevel WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 ELSE 3 END, n.publishedAt DESC")
    List<MarketImpactNewsEntity> findTodaysNewsByPriority(@Param("since") LocalDateTime since);

    @Modifying
    @Transactional
    @Query("DELETE FROM MarketImpactNewsEntity n WHERE n.createdAt < :cutoffDate")
    int deleteByCreatedAtBefore(@Param("cutoffDate") LocalDateTime cutoffDate);

    // ---------------------------------------------------------------- macro event ledger (SPEC 48.2)

    /**
     * The newest headline this app already holds, so the next scan fetches from there rather than
     * from a fixed number of hours ago (B-105 - the fixed two-hour window made every overnight
     * story invisible).
     */
    @Query("SELECT MAX(n.publishedAt) FROM MarketImpactNewsEntity n")
    LocalDateTime findLatestPublishedAt();

    /** Freshness key {@code marketImpactNews}: when the scan last stored anything. */
    @Query("SELECT MAX(n.createdAt) FROM MarketImpactNewsEntity n")
    LocalDateTime findLatestCreatedAt();

    /**
     * Headlines the event extractor has not read yet, newest first.
     *
     * <p>Ordered newest-first and capped by the caller so one ingest reads the most recent news
     * rather than the oldest unread backlog: a fortnight-old headline can no longer produce an
     * event that matters, and paying a model to read it would be paying for nothing.
     */
    @Query("SELECT n FROM MarketImpactNewsEntity n WHERE n.macroExtractedAt IS NULL "
            + "AND n.publishedAt >= :since ORDER BY n.publishedAt DESC")
    List<MarketImpactNewsEntity> findUnreadSince(@Param("since") LocalDateTime since);

    // ------------------------------------------------------------- analyst target ledger (SPEC 49.5)

    /**
     * Headlines the analyst target ledger has not read yet, newest first.
     *
     * <p>A separate marker column from the macro extractor's: the two read the same rows for
     * different reasons and must not consume each other's backlog.
     */
    @Query("SELECT n FROM MarketImpactNewsEntity n WHERE n.analystExtractedAt IS NULL "
            + "AND n.publishedAt >= :since ORDER BY n.publishedAt DESC")
    List<MarketImpactNewsEntity> findAnalystUnreadSince(@Param("since") LocalDateTime since);

    List<MarketImpactNewsEntity> findByIdIn(List<Long> ids);
}
