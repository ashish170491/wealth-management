package com.example.trading.portfolio.tax;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TaxLotRepository extends JpaRepository<TaxLotEntity, Long> {

    List<TaxLotEntity> findBySymbol(String symbol);

    List<TaxLotEntity> findBySymbolAndStatus(String symbol, String status);

    List<TaxLotEntity> findByStatus(String status);

    /** Idempotency lookup for CSV import / live auto-capture. */
    java.util.Optional<TaxLotEntity> findByTradeId(String tradeId);

    /** Cross-exchange lookup — the same ISIN can be held as {@code NSE:X} or {@code BSE:X}. */
    List<TaxLotEntity> findByIsinAndStatus(String isin, String status);

    /** FIFO order for lot matching. */
    @Query("SELECT l FROM TaxLotEntity l WHERE l.symbol = :symbol AND l.status = 'OPEN' ORDER BY l.buyDate ASC, l.id ASC")
    List<TaxLotEntity> findOpenBySymbolFifo(@Param("symbol") String symbol);

    /** LIFO order. */
    @Query("SELECT l FROM TaxLotEntity l WHERE l.symbol = :symbol AND l.status = 'OPEN' ORDER BY l.buyDate DESC, l.id DESC")
    List<TaxLotEntity> findOpenBySymbolLifo(@Param("symbol") String symbol);

    /** HIFO (highest-in-first-out) — sells highest cost lots first to minimize gains. */
    @Query("SELECT l FROM TaxLotEntity l WHERE l.symbol = :symbol AND l.status = 'OPEN' ORDER BY l.buyPrice DESC, l.buyDate ASC")
    List<TaxLotEntity> findOpenBySymbolHifo(@Param("symbol") String symbol);
}
