package com.example.trading.portfolio.performance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface PortfolioCashSnapshotRepository extends JpaRepository<PortfolioCashSnapshotEntity, Long> {

    Optional<PortfolioCashSnapshotEntity> findBySnapshotDate(LocalDate date);

    Optional<PortfolioCashSnapshotEntity> findTopByOrderBySnapshotDateDesc();
}
