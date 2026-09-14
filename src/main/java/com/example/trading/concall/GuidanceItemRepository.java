package com.example.trading.concall;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GuidanceItemRepository extends JpaRepository<GuidanceItemEntity, Long> {

    @Query("SELECT g FROM GuidanceItemEntity g WHERE g.symbol = :symbol ORDER BY g.sourceDate DESC")
    List<GuidanceItemEntity> findBySymbol(@Param("symbol") String symbol);

    Optional<GuidanceItemEntity> findBySymbolAndQuarterAndMetric(String symbol, String quarter, String metric);

    /** Only resolved items count toward a credibility ratio — pending promises measure nothing. */
    @Query("SELECT g FROM GuidanceItemEntity g WHERE g.symbol = :symbol AND g.status IN ('MET', 'MISSED')")
    List<GuidanceItemEntity> findResolved(@Param("symbol") String symbol);

    long countBySymbolAndStatus(String symbol, String status);
}
