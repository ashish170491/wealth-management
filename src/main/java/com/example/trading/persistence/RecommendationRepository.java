package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RecommendationRepository extends JpaRepository<RecommendationEntity, Long> {

    Optional<RecommendationEntity> findBySymbolAndSourceAndIssuedDate(
            String symbol, String source, LocalDate issuedDate);

    List<RecommendationEntity> findByIssuedDate(LocalDate issuedDate);

    List<RecommendationEntity> findBySymbolOrderByIssuedDateDesc(String symbol);

    List<RecommendationEntity> findBySourceOrderByIssuedDateDesc(String source);

    /** Picks older than {@code cutoff} that still need outcome measurement at the given horizon. */
    @Query("""
           SELECT r FROM RecommendationEntity r
           WHERE r.issuedDate <= :cutoff
             AND NOT EXISTS (
               SELECT 1 FROM RecommendationOutcomeEntity o
               WHERE o.recommendationId = r.id AND o.horizonDays = :horizon
             )
           """)
    List<RecommendationEntity> findDueForOutcome(
            @Param("cutoff") LocalDate cutoff, @Param("horizon") int horizonDays);

    @Query("""
           SELECT r FROM RecommendationEntity r
           WHERE r.issuedDate BETWEEN :from AND :to
           ORDER BY r.issuedDate DESC, r.score DESC
           """)
    List<RecommendationEntity> findInRange(
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
