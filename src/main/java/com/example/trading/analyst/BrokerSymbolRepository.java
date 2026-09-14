package com.example.trading.analyst;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Lookups for the research feed's stock-id map (SPEC §49.11). */
@Repository
public interface BrokerSymbolRepository extends JpaRepository<BrokerSymbolEntity, Long> {

    /**
     * Whatever is known about this id, including a recorded failure.
     *
     * <p>An empty result means "never asked". A present row with a null {@code nseSymbol} means
     * "asked, and there is no NSE listing" — the two must stay distinguishable or the resolver
     * re-probes every dead id on every run.
     */
    Optional<BrokerSymbolEntity> findByScid(String scid);

    long countByNseSymbolIsNotNull();
}
