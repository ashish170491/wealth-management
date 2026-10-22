package com.example.trading.valuation;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Narrow, append-only access to {@code valuation_gap_snapshot} (SPEC §57.6).
 *
 * <p>It extends {@link Repository}, not {@code JpaRepository}, so that nothing inherited can delete
 * or bulk-save. There is no {@code delete*}, no {@code saveAll} (Gotcha 129: one bad row in a bulk
 * save rolls back the lot, and here each row must stand or fall alone) and no {@code @Modifying}
 * query; {@code ValuationGapSnapshotImmutabilityTest} fails if one appears. The entity is also
 * {@code @Immutable}, so Hibernate would not issue an UPDATE for it in any case.
 *
 * <p>Only the snapshot service, the mapper and the read-side view code may refer to this
 * interface - a source-scan test enforces that too.
 */
@org.springframework.stereotype.Repository
public interface ValuationGapSnapshotRepository extends Repository<ValuationGapSnapshotEntity, Long> {

    /** One row per call, each committing on its own. */
    <S extends ValuationGapSnapshotEntity> S save(S entity);

    long count();

    boolean existsBySymbolAndRulesetAndInputsHash(String symbol, String ruleset, String inputsHash);

    long countBySymbol(String symbol);

    List<ValuationGapSnapshotEntity> findBySymbolOrderByCreatedAtDesc(String symbol);

    /** Every stored {@code [symbol, inputsHash]} for a rule-set, so a capture pass asks the database once. */
    @Query("SELECT s.symbol, s.inputsHash FROM ValuationGapSnapshotEntity s WHERE s.ruleset = :ruleset")
    List<Object[]> findStoredHashes(@Param("ruleset") String ruleset);

    @Query("SELECT MAX(s.createdAt) FROM ValuationGapSnapshotEntity s")
    LocalDateTime findLatestCreatedAt();

    @Query("SELECT MAX(s.createdAt) FROM ValuationGapSnapshotEntity s WHERE s.symbol = :symbol")
    LocalDateTime findLatestCreatedAtForSymbol(@Param("symbol") String symbol);

    @Query("SELECT MIN(s.createdAt) FROM ValuationGapSnapshotEntity s")
    LocalDateTime findEarliestCreatedAt();

    /** {@code [status, count]}. */
    @Query("SELECT s.status, COUNT(s) FROM ValuationGapSnapshotEntity s GROUP BY s.status")
    List<Object[]> countByStatus();

    /** {@code [ruleset, count]}. */
    @Query("SELECT s.ruleset, COUNT(s) FROM ValuationGapSnapshotEntity s GROUP BY s.ruleset")
    List<Object[]> countByRuleset();
}
