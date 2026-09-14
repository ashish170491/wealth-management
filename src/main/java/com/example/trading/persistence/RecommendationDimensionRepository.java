package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Repository
public interface RecommendationDimensionRepository
        extends JpaRepository<RecommendationDimensionEntity, Long> {

    List<RecommendationDimensionEntity> findByRecommendationIdIn(Collection<Long> recommendationIds);

    /** Clear a recommendation's existing dimensions before re-inserting (upsert on same-day re-run). */
    @Transactional
    void deleteByRecommendationId(Long recommendationId);
}
