package com.example.trading.funds;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MutualFundHoldingRepository extends JpaRepository<MutualFundHoldingEntity, Long> {

    /**
     * What the investor holds now. The sibling of {@code HoldingsRepository.findActive()} - but
     * soft-deleted rather than reconciled away, because for funds the exit history is the feature.
     */
    List<MutualFundHoldingEntity> findByActiveTrue();

    Optional<MutualFundHoldingEntity> findByIsinAndFolio(String isin, String folio);

    long countByActiveTrue();

    long countByActiveTrueAndAmfiSchemeCodeIsNull();
}
