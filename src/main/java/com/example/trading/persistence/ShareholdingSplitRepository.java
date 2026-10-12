package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ShareholdingSplitRepository extends JpaRepository<ShareholdingSplitEntity, Long> {

    Optional<ShareholdingSplitEntity> findBySymbolAndQuarterDate(String symbol, LocalDate quarterDate);
}
