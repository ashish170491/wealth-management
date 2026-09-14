package com.example.trading.fundamentals;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AnnualFundamentalsRepository extends JpaRepository<AnnualFundamentalsEntity, Long> {

    /** A symbol's history, oldest year first — the order every multi-year calculation wants. */
    @Query("SELECT a FROM AnnualFundamentalsEntity a WHERE a.symbol = :symbol ORDER BY a.fiscalYear ASC")
    List<AnnualFundamentalsEntity> findHistory(@Param("symbol") String symbol);

    Optional<AnnualFundamentalsEntity> findBySymbolAndFiscalYear(String symbol, Integer fiscalYear);

    /** Symbols with at least {@code minYears} rows — i.e. those the detectors can actually judge. */
    @Query("SELECT a.symbol FROM AnnualFundamentalsEntity a GROUP BY a.symbol HAVING COUNT(a) >= :minYears")
    List<String> findSymbolsWithHistory(@Param("minYears") long minYears);

    /**
     * Years on file per symbol, in one query — how much history exists to judge persistence with.
     *
     * <p>Used by the compounding lens (SPEC §41) to say "based on one year of accounts" rather
     * than implying a track record it never checked. Per-symbol {@code countBySymbol} would be
     * 288 queries on a screener page load; this is one, and the endpoint must stay DB-only and
     * fast (Gotcha 17, 39).
     *
     * @return rows of {@code [symbol, count]}
     */
    /**
     * Every stored year for a set of symbols, oldest first, for bulk reads (SPEC §41 fallback).
     *
     * <p>One query for a whole portfolio: the compounding lens resolves up to four spellings per
     * holding, so per-symbol lookups would be ~130 queries on a page load.
     */
    @Query("SELECT a FROM AnnualFundamentalsEntity a WHERE a.symbol IN :symbols "
            + "ORDER BY a.symbol ASC, a.fiscalYear ASC")
    List<AnnualFundamentalsEntity> findHistoryForSymbols(@Param("symbols") Collection<String> symbols);

    @Query("SELECT a.symbol, COUNT(a) FROM AnnualFundamentalsEntity a GROUP BY a.symbol")
    List<Object[]> countYearsBySymbol();

    long countBySymbol(String symbol);

    void deleteBySymbol(String symbol);
}
