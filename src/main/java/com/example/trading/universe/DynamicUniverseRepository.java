package com.example.trading.universe;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DynamicUniverseRepository extends JpaRepository<DynamicUniverseEntity, Long> {

    Optional<DynamicUniverseEntity> findBySymbol(String symbol);

    List<DynamicUniverseEntity> findByActiveTrue();

    @Query("SELECT d FROM DynamicUniverseEntity d WHERE d.active = true AND d.status = :status "
            + "ORDER BY d.coarseScore DESC")
    List<DynamicUniverseEntity> findActiveByStatus(@Param("status") String status);

    @Query("SELECT d FROM DynamicUniverseEntity d WHERE d.active = true AND d.status = 'PROMOTED' "
            + "ORDER BY d.lastCompositeScore DESC")
    List<DynamicUniverseEntity> findPromoted();

    @Query("SELECT d FROM DynamicUniverseEntity d WHERE d.promotedDate >= :since ORDER BY d.promotedDate DESC")
    List<DynamicUniverseEntity> findPromotedSince(@Param("since") LocalDate since);

    @Query("SELECT count(d) FROM DynamicUniverseEntity d WHERE d.active = true AND d.status = 'PROMOTED'")
    long countPromoted();
}
