package com.example.trading.macro;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MacroEventRepository extends JpaRepository<MacroEventEntity, Long> {

    Optional<MacroEventEntity> findByDedupKey(String dedupKey);

    /**
     * Everything that happened on or after a date, newest first.
     *
     * <p>Dismissed rows are <b>included</b>: the caller decides whether to show them, and the
     * exposure read drops them itself. A repository that hid them would make the dismissed list
     * on the Events page impossible to build without a second query for the same rows.
     */
    List<MacroEventEntity> findByOccurredAtGreaterThanEqualOrderByOccurredAtDescIdDesc(LocalDate from);

    /**
     * Candidates for merging a fresh report into an event already on the ledger. Same factor and
     * direction, within a few days either side - see {@link MacroEventDedup#sameEvent}.
     */
    List<MacroEventEntity> findByFactorAndDirectionAndOccurredAtBetween(
            String factor, String direction, LocalDate from, LocalDate to);

    /** Freshness key {@code macroEvents}: when the ledger was last written to. */
    @Query("SELECT MAX(e.extractedAt) FROM MacroEventEntity e")
    LocalDateTime findLatestExtractedAt();

    /** Retention (SPEC §17). Two years, comfortably past the 365-day outcome horizon. */
    @Modifying
    @Transactional
    @Query("DELETE FROM MacroEventEntity e WHERE e.occurredAt < :cutoff")
    int deleteByOccurredAtBefore(@Param("cutoff") LocalDate cutoff);
}
