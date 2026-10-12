package com.example.trading.funds;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MutualFundSyncRunRepository extends JpaRepository<MutualFundSyncRunEntity, Long> {

    /** The freshness key reads this - when the job ran, never when a NAV was struck (B-119). */
    Optional<MutualFundSyncRunEntity> findFirstByOrderByLastPulledAtDesc();

    Optional<MutualFundSyncRunEntity> findFirstByFailureReasonIsNullOrderByLastPulledAtDesc();
}
