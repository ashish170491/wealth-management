package com.example.trading.universe.ipo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface IpoIssueRepository extends JpaRepository<IpoIssueEntity, Long> {

    Optional<IpoIssueEntity> findBySymbol(String symbol);

    /** Listed on or after {@code from}, newest listing first. */
    @Query("SELECT i FROM IpoIssueEntity i WHERE i.listingDate IS NOT NULL AND i.listingDate >= :from "
            + "ORDER BY i.listingDate DESC")
    List<IpoIssueEntity> findListedSince(@Param("from") LocalDate from);

    /** Not yet listed (or listing date not yet published), latest close first. */
    @Query("SELECT i FROM IpoIssueEntity i WHERE i.listingDate IS NULL OR i.listingDate > :today "
            + "ORDER BY i.issueEndDate DESC")
    List<IpoIssueEntity> findPipeline(@Param("today") LocalDate today);

    /** Newest capture stamp — the table's freshness key on the dashboard. */
    @Query("SELECT MAX(i.capturedAt) FROM IpoIssueEntity i")
    LocalDateTime findLatestCapturedAt();
}
