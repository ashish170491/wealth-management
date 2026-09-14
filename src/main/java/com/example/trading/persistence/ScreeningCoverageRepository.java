package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScreeningCoverageRepository extends JpaRepository<ScreeningCoverageEntity, Long> {

    Optional<ScreeningCoverageEntity> findByScreeningDateAndSignalName(LocalDate screeningDate, String signalName);

    List<ScreeningCoverageEntity> findByScreeningDateOrderBySignalNameAsc(LocalDate screeningDate);

    /**
     * Most recent date that actually has coverage rows. Follows the rule from Gotcha 20:
     * "today" is empty until the 14:00 screening run, so anything wanting the latest real
     * measurement must walk back rather than assume today.
     */
    @Query("SELECT MAX(c.screeningDate) FROM ScreeningCoverageEntity c")
    Optional<LocalDate> findLatestDate();

    @Query("SELECT c FROM ScreeningCoverageEntity c WHERE c.signalName = :signal "
            + "AND c.screeningDate >= :fromDate ORDER BY c.screeningDate ASC")
    List<ScreeningCoverageEntity> findTrend(@Param("signal") String signalName,
                                            @Param("fromDate") LocalDate fromDate);
}
