package com.example.trading.jobs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AppBootRepository extends JpaRepository<AppBootEntity, String> {

    /** Boots that were alive at some point at or after {@code from}. */
    List<AppBootEntity> findByLastSeenAtGreaterThanEqualOrderByStartedAtAsc(LocalDateTime from);

    /** The first boot ever recorded - the day this ledger's history begins. */
    Optional<AppBootEntity> findFirstByOrderByStartedAtAsc();

    @Modifying
    @Transactional
    @Query("update AppBootEntity b set b.lastSeenAt = :t where b.bootId = :id and b.lastSeenAt < :t")
    int touch(@Param("id") String bootId, @Param("t") LocalDateTime at);

    @Modifying
    @Transactional
    int deleteByLastSeenAtBefore(LocalDateTime cutoff);
}
