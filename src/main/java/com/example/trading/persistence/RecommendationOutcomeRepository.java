package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RecommendationOutcomeRepository extends JpaRepository<RecommendationOutcomeEntity, Long> {

    Optional<RecommendationOutcomeEntity> findByRecommendationIdAndHorizonDays(
            Long recommendationId, int horizonDays);

    List<RecommendationOutcomeEntity> findByRecommendationId(Long recommendationId);

    List<RecommendationOutcomeEntity> findByHorizonDaysAndMeasuredDateBetween(
            int horizonDays, LocalDate from, LocalDate to);

    /**
     * Outcomes for one engine at one horizon, excluding rows whose return actually ran far
     * longer than the horizon they are labelled with (B-028).
     *
     * <p>Before the {@code daysElapsed} guard, 20.8% of 30d rows and 29.4% of 90d rows had been
     * measured well past their anniversary — up to 124 days for a "30-day" outcome — because the
     * scheduler measured every unmeasured pick older than the cutoff using today's price. Every
     * hit rate, mean return and Information Coefficient computed from this query inherited that
     * horizon drift, so the calibration numbers described a longer holding period than they
     * claimed. Rows with a null {@code daysElapsed} predate the column and are backfilled by
     * {@code SchemaMigrationRunner}; any that remain null are excluded rather than trusted.
     */
    @Query("""
           SELECT o FROM RecommendationOutcomeEntity o
           WHERE o.horizonDays = :horizon
             AND o.daysElapsed IS NOT NULL
             AND o.daysElapsed <= :maxDaysElapsed
             AND o.recommendationId IN (
               SELECT r.id FROM RecommendationEntity r WHERE r.source = :source
             )
           """)
    List<RecommendationOutcomeEntity> findBySourceAndHorizon(
            @Param("source") String source, @Param("horizon") int horizonDays,
            @Param("maxDaysElapsed") int maxDaysElapsed);

    /** Newest measured outcome date, for the dashboard freshness strip (SPEC §27.7). Null when empty. */
    @Query("SELECT MAX(o.measuredDate) FROM RecommendationOutcomeEntity o")
    java.time.LocalDate findLatestMeasuredDate();
}
