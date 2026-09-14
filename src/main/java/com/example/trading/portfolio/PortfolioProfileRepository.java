package com.example.trading.portfolio;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PortfolioProfileRepository extends JpaRepository<PortfolioProfileEntity, Long> {

    Optional<PortfolioProfileEntity> findByActiveTrue();

    Optional<PortfolioProfileEntity> findByName(String name);
}
