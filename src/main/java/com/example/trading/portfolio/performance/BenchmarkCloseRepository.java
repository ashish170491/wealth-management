package com.example.trading.portfolio.performance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BenchmarkCloseRepository extends JpaRepository<BenchmarkCloseEntity, Long> {

    Optional<BenchmarkCloseEntity> findBySymbolAndCloseDate(String symbol, LocalDate closeDate);

    List<BenchmarkCloseEntity> findBySymbolAndCloseDateBetweenOrderByCloseDateAsc(
            String symbol, LocalDate from, LocalDate to);

    @Query("SELECT MAX(b.closeDate) FROM BenchmarkCloseEntity b WHERE b.symbol = :symbol")
    LocalDate findLatestDate(@Param("symbol") String symbol);

    @Query("SELECT MIN(b.closeDate) FROM BenchmarkCloseEntity b WHERE b.symbol = :symbol")
    LocalDate findEarliestDate(@Param("symbol") String symbol);

    long countBySymbol(String symbol);
}
