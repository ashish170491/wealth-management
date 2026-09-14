package com.example.trading.watchlist;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Migrates the YAML {@code watchlist.symbols} list into the table once (SPEC §37.5).
 *
 * <p>Runs on every boot and is idempotent: a symbol with no row is inserted, a legacy analysis
 * row with no {@code addedOn} is stamped from its {@code createdAt}, and everything else —
 * including a row the investor <b>removed</b> — is left alone. Resurrecting removed rows would
 * be the old in-memory bug in a new coat. Nothing is analysed here (09:00 boot, token may not
 * be valid yet; the 11:00 run does it). {@code SchemaMigrationRunner} is {@code @PostConstruct},
 * so the new columns exist by the time this fires.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WatchlistSeedRunner {

    private final WatchlistRepository repository;
    private final WatchlistConfig config;

    public enum SeedDecision { INSERT, STAMP, SKIP }

    /** Pure decision so the rule is testable without a repository. */
    public static SeedDecision decide(WatchlistEntity existing) {
        if (existing == null) return SeedDecision.INSERT;
        if (Boolean.FALSE.equals(existing.getActive())) return SeedDecision.SKIP; // never resurrect a removal
        if (existing.getAddedOn() == null) return SeedDecision.STAMP;
        return SeedDecision.SKIP;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!config.isEnabled() || config.getSymbols() == null) return;
        int inserted = 0, stamped = 0, skipped = 0;
        LocalDate today = LocalDate.now();

        for (String raw : config.getSymbols()) {
            String symbol;
            try {
                symbol = WatchlistSymbols.normalise(raw);
            } catch (IllegalArgumentException e) {
                log.warn("Watchlist seed: skipping invalid YAML symbol '{}' ({})", raw, e.getMessage());
                continue;
            }
            try {
                WatchlistEntity existing = repository.findBySymbol(symbol).orElse(null);
                switch (decide(existing)) {
                    case INSERT -> {
                        WatchlistEntity row = new WatchlistEntity();
                        row.setSymbol(symbol);
                        row.setTradingSymbol(WatchlistSymbols.tradingSymbol(symbol));
                        row.setExchange(symbol.substring(0, symbol.indexOf(':')));
                        row.setActive(true);
                        row.setSource("SEED");
                        row.setAddedOn(today);
                        repository.save(row);
                        inserted++;
                    }
                    case STAMP -> {
                        existing.setAddedOn(existing.getCreatedAt() != null ? existing.getCreatedAt().toLocalDate() : today);
                        existing.setSource("SEED");
                        existing.setActive(true);
                        repository.save(existing);
                        stamped++;
                    }
                    case SKIP -> skipped++;
                }
            } catch (Exception e) {
                log.warn("Watchlist seed: {} failed ({}) — will retry next boot", symbol, e.getMessage());
            }
        }
        log.info("Watchlist seed: {} inserted, {} stamped from created_at, {} unchanged", inserted, stamped, skipped);
    }
}
