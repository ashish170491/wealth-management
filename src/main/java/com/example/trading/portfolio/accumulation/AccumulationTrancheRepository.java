package com.example.trading.portfolio.accumulation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccumulationTrancheRepository extends JpaRepository<AccumulationTrancheEntity, Long> {
    List<AccumulationTrancheEntity> findByPlanIdOrderByTrancheNumberAsc(Long planId);
    List<AccumulationTrancheEntity> findByPlanIdAndStatus(Long planId, String status);
}
