package com.example.trading.funds;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface MutualFundNavRepository extends JpaRepository<MutualFundNavEntity, Long> {

    List<MutualFundNavEntity> findByAmfiSchemeCodeOrderByNavDateAsc(Long amfiSchemeCode);

    boolean existsByAmfiSchemeCodeAndNavDate(Long amfiSchemeCode, LocalDate navDate);

    @Query("SELECT COUNT(DISTINCT n.navDate) FROM MutualFundNavEntity n WHERE n.amfiSchemeCode = :code")
    long countDatesForScheme(@Param("code") Long code);

    /**
     * How many schemes have at least {@code min} stored NAV points. A coverage denominator, never a
     * performance figure - it answers "how much could we measure", not "how did they do".
     */
    // Native, because JPQL has no derived-table support - a subquery in FROM is not valid JPQL and
    // would fail at context startup rather than at compile time.
    @Query(value = "SELECT COUNT(*) FROM (SELECT amfi_scheme_code FROM mf_nav_history "
            + "GROUP BY amfi_scheme_code HAVING COUNT(*) >= :min) g", nativeQuery = true)
    long countSchemesWithAtLeast(@Param("min") long min);

    /**
     * Scheme codes that already have a NAV stored for this date - one query per date instead of one
     * existence check per scheme, which on a 571-scheme universe is the difference between one
     * round trip and 571.
     */
    @Query("SELECT n.amfiSchemeCode FROM MutualFundNavEntity n WHERE n.navDate = :d")
    List<Long> schemeCodesWithNavOn(@Param("d") LocalDate d);
}
