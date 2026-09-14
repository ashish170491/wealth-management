package com.example.trading.portfolio.conviction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface HoldingConvictionRepository extends JpaRepository<HoldingConvictionEntity, Long> {
    Optional<HoldingConvictionEntity> findBySymbol(String symbol);
}
