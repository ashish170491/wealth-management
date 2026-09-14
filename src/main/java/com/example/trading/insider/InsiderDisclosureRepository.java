package com.example.trading.insider;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface InsiderDisclosureRepository extends JpaRepository<InsiderDisclosureEntity, Long> {

    List<InsiderDisclosureEntity> findBySymbolOrderByTransactionDateDesc(String symbol);

    @Query("SELECT d FROM InsiderDisclosureEntity d WHERE d.symbol = :symbol "
            + "AND d.transactionDate >= :from ORDER BY d.transactionDate DESC")
    List<InsiderDisclosureEntity> findSince(@Param("symbol") String symbol, @Param("from") LocalDate from);

    @Query("SELECT d FROM InsiderDisclosureEntity d WHERE d.transactionDate >= :from "
            + "ORDER BY d.transactionDate DESC")
    List<InsiderDisclosureEntity> findAllSince(@Param("from") LocalDate from);

    /**
     * Newest transaction date among PIT-sourced rows, or null when none exist.
     *
     * <p>Deliberately restricted to {@code source = 'PIT'}. Bulk and block deals arrive every
     * trading day and would mask a dead PIT feed completely - which is what happened for four
     * months (B-089). The staleness check must watch the contributing feed, not the table.
     */
    @Query("SELECT MAX(d.transactionDate) FROM InsiderDisclosureEntity d WHERE d.source = 'PIT'")
    LocalDate findNewestPitTransactionDate();

    /** Filing ids already ingested, so a PIT filing's XBRL is downloaded at most once (B-089). */
    @Query("SELECT DISTINCT d.filingAppId FROM InsiderDisclosureEntity d WHERE d.filingAppId IS NOT NULL")
    List<String> findIngestedFilingAppIds();

    @Query("SELECT d.disclosureHash FROM InsiderDisclosureEntity d WHERE d.disclosureHash IN :hashes")
    List<String> findExistingHashes(@Param("hashes") Collection<String> hashes);
}
