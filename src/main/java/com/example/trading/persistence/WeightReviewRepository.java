package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WeightReviewRepository extends JpaRepository<WeightReviewEntity, Long> {

    /**
     * Reviews at one horizon, most recent first.
     *
     * <p>Horizon-scoped on purpose: a 30-day result and a 180-day result answer different
     * questions, and letting them share a consecutive-review run would allow a variant to build
     * credit at the horizon where evidence is cheapest and spend it at the one that matters.
     */
    @Query("SELECT w FROM WeightReviewEntity w WHERE w.horizonDays = :horizon "
            + "ORDER BY w.reviewDate DESC, w.id DESC")
    List<WeightReviewEntity> findRecent(@Param("horizon") Integer horizonDays);

    @Query("SELECT w FROM WeightReviewEntity w ORDER BY w.reviewDate DESC, w.id DESC")
    List<WeightReviewEntity> findAllRecent();
}
