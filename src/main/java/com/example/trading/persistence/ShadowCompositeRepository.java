package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShadowCompositeRepository extends JpaRepository<ShadowCompositeEntity, Long> {

    List<ShadowCompositeEntity> findByScreeningDateAndVariantName(LocalDate screeningDate, String variantName);

    List<ShadowCompositeEntity> findByScreeningDate(LocalDate screeningDate);

    /** Dates that already have shadow rows — used to skip work when back-filling. */
    @Query("SELECT DISTINCT s.screeningDate FROM ShadowCompositeEntity s ORDER BY s.screeningDate ASC")
    List<LocalDate> findComputedDates();

    /** Most recent date with any shadow row; empty before the first run. */
    @Query("SELECT MAX(s.screeningDate) FROM ShadowCompositeEntity s")
    Optional<LocalDate> findLatestDate();

    /**
     * Every shadow row from {@code fromDate} onward, oldest first.
     *
     * <p>The harness reads whole date blocks at once rather than one date at a time: the
     * cross-sectional Information Coefficient it computes is defined per date, so a partial
     * date is not a smaller sample, it is a different statistic.
     */
    @Query("SELECT s FROM ShadowCompositeEntity s WHERE s.screeningDate >= :fromDate "
            + "ORDER BY s.screeningDate ASC, s.variantName ASC")
    List<ShadowCompositeEntity> findFrom(@Param("fromDate") LocalDate fromDate);

    @Query("SELECT COUNT(s) FROM ShadowCompositeEntity s WHERE s.variantName = :variant")
    long countByVariant(@Param("variant") String variantName);

    /**
     * Clear one date so it can be recomputed.
     *
     * <p>A re-run replaces rather than accumulates, matching how {@code persistScores} treats
     * the score rows these describe. Deleting by date is the only supported reset: partially
     * replacing one variant would leave a date whose variants came from different code, which
     * is precisely the pooling error this whole substrate exists to prevent.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ShadowCompositeEntity s WHERE s.screeningDate = :date")
    void deleteByScreeningDate(@Param("date") LocalDate date);
}
