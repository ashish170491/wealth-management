package com.example.trading.portfolio.accumulation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccumulationPlanRepository extends JpaRepository<AccumulationPlanEntity, Long> {
    List<AccumulationPlanEntity> findByStatus(String status);
    List<AccumulationPlanEntity> findBySymbol(String symbol);
}
