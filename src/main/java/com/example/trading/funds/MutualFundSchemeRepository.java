package com.example.trading.funds;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MutualFundSchemeRepository extends JpaRepository<MutualFundSchemeEntity, Long> {

    Optional<MutualFundSchemeEntity> findByAmfiSchemeCode(Long amfiSchemeCode);

    List<MutualFundSchemeEntity> findByAmfiSchemeCodeIn(Collection<Long> codes);

    /**
     * ISIN is <b>not</b> unique in the AMFI feed (five measured collisions), so this returns a list
     * and the caller decides. Gotcha 107: first hit that <em>answers</em> wins, never simply the
     * first hit - and which row answered is recorded on the holding.
     */
    List<MutualFundSchemeEntity> findByIsinGrowth(String isinGrowth);

    List<MutualFundSchemeEntity> findByIsinReinvest(String isinReinvest);

    List<MutualFundSchemeEntity> findByScreenableTrue();

    long countByScreenableTrue();

    @Query("SELECT s.category, COUNT(s) FROM MutualFundSchemeEntity s WHERE s.screenable = true GROUP BY s.category")
    List<Object[]> countScreenableByCategory();
}
