package com.example.trading.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MultibaggerScoreRepository extends JpaRepository<MultibaggerScoreEntity, Long> {

    Optional<MultibaggerScoreEntity> findBySymbolAndScreeningDate(String symbol, LocalDate screeningDate);

    List<MultibaggerScoreEntity> findByScreeningDateOrderByCompositeScoreDesc(LocalDate screeningDate);

    @Query("SELECT m FROM MultibaggerScoreEntity m WHERE m.screeningDate = :date AND m.compositeScore >= :minScore ORDER BY m.compositeScore DESC")
    List<MultibaggerScoreEntity> findCandidates(@Param("date") LocalDate date, @Param("minScore") int minScore);

    @Query("SELECT m FROM MultibaggerScoreEntity m WHERE m.screeningDate = :date AND m.verdict IN ('STRONG_MULTIBAGGER', 'POTENTIAL_MULTIBAGGER') ORDER BY m.compositeScore DESC")
    List<MultibaggerScoreEntity> findTopCandidates(@Param("date") LocalDate date);

    @Query("SELECT m FROM MultibaggerScoreEntity m WHERE m.symbol = :symbol ORDER BY m.screeningDate DESC")
    List<MultibaggerScoreEntity> findHistoryBySymbol(@Param("symbol") String symbol);

    @Query("SELECT m FROM MultibaggerScoreEntity m WHERE m.symbol = :symbol AND m.screeningDate >= :fromDate ORDER BY m.screeningDate ASC")
    List<MultibaggerScoreEntity> findTrend(@Param("symbol") String symbol, @Param("fromDate") LocalDate fromDate);

    @Query("SELECT m FROM MultibaggerScoreEntity m WHERE m.inHoldings = true AND m.screeningDate = :date ORDER BY m.compositeScore DESC")
    List<MultibaggerScoreEntity> findHoldingsScores(@Param("date") LocalDate date);

    @Query("SELECT DISTINCT m.screeningDate FROM MultibaggerScoreEntity m ORDER BY m.screeningDate DESC")
    List<LocalDate> findScreeningDates();

    /**
     * Recent rows for many symbols at once, oldest first (SPEC 41 bulk lens).
     *
     * <p>Exists so the portfolio can resolve the compounding verdict for every holding in ONE
     * query. Doing it per row would be ~130 queries on a page load, because each of ~33 holdings
     * resolves through up to four symbol spellings (Gotcha 84).
     *
     * <p>Ordered ASC on purpose: a caller building a symbol map keeps the last row written, which
     * is the newest - the same convention {@link #findTrend} already relies on.
     */
    @Query("SELECT m FROM MultibaggerScoreEntity m WHERE m.symbol IN :symbols "
            + "AND m.screeningDate >= :fromDate ORDER BY m.screeningDate ASC")
    List<MultibaggerScoreEntity> findRecentForSymbols(
            @Param("symbols") java.util.Collection<String> symbols,
            @Param("fromDate") LocalDate fromDate);

    /**
     * The NEWEST row per symbol inside the window - one row each, not the whole history.
     *
     * <p>Exists because every caller of {@link #findRecentForSymbols} above was loading a year of
     * screening history only to keep the last row of each symbol in a map. On the screener page
     * that is ~276 symbols x up to four spellings x ~100 screening dates of 103-column entities,
     * and it cost 20-30 s against an 8 s client timeout, so the dashboard showed its "did not
     * answer in time" banner on every load (B-115). Same result, a fraction of the rows.
     *
     * <p>The (symbol, screeningDate) unique constraint on the table guarantees the subquery picks
     * exactly one row per symbol, and gives the index that makes it cheap.
     */
    @Query("SELECT m FROM MultibaggerScoreEntity m WHERE (m.symbol, m.screeningDate) IN ("
            + "SELECT m2.symbol, MAX(m2.screeningDate) FROM MultibaggerScoreEntity m2 "
            + "WHERE m2.symbol IN :symbols AND m2.screeningDate >= :fromDate GROUP BY m2.symbol)")
    List<MultibaggerScoreEntity> findLatestForSymbolsSince(
            @Param("symbols") java.util.Collection<String> symbols,
            @Param("fromDate") LocalDate fromDate);

    @Query("SELECT m FROM MultibaggerScoreEntity m WHERE m.screeningDate = :date AND m.marketCapCategory = :category ORDER BY m.compositeScore DESC")
    List<MultibaggerScoreEntity> findByMarketCapCategory(@Param("date") LocalDate date, @Param("category") String category);
}
