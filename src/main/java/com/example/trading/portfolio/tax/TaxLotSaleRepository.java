package com.example.trading.portfolio.tax;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TaxLotSaleRepository extends JpaRepository<TaxLotSaleEntity, Long> {

    List<TaxLotSaleEntity> findBySymbol(String symbol);

    List<TaxLotSaleEntity> findBySellDateBetween(LocalDate from, LocalDate to);

    List<TaxLotSaleEntity> findByGainType(String gainType);

    /** Returns rows whose tradeId starts with the given prefix — used for idempotency checks. */
    List<TaxLotSaleEntity> findByTradeId(String tradeId);
}
