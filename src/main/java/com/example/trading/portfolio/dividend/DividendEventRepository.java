package com.example.trading.portfolio.dividend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DividendEventRepository extends JpaRepository<DividendEventEntity, Long> {
    List<DividendEventEntity> findBySymbol(String symbol);
    List<DividendEventEntity> findByExDateBetween(LocalDate from, LocalDate to);
    List<DividendEventEntity> findByStatus(String status);
}
