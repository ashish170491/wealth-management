package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface TargetHitEventRepository extends JpaRepository<TargetHitEventEntity, Long> {

    boolean existsByDedupKey(String dedupKey);

    List<TargetHitEventEntity> findByHitDateOrderBySourceAscSymbolAsc(LocalDate hitDate);
}
