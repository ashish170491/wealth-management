package com.example.trading.funds;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MutualFundHistoryFetchRepository
        extends JpaRepository<MutualFundHistoryFetchEntity, Long> {

    Optional<MutualFundHistoryFetchEntity> findByForMonth(LocalDate forMonth);

    List<MutualFundHistoryFetchEntity> findAllByOrderByForMonthAsc();

    long countByStatus(MutualFundHistoryFetchEntity.Status status);
}
