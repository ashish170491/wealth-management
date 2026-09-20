package com.example.trading.portfolio.goal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvestmentGoalRepository extends JpaRepository<InvestmentGoalEntity, Long> {

    List<InvestmentGoalEntity> findByActiveTrueOrderByTargetDateAsc();

    Optional<InvestmentGoalEntity> findByNameIgnoreCase(String name);
}
