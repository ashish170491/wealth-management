package com.example.trading.persistence;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-time startup schema fixes that Hibernate ddl-auto=update cannot handle
 * (it ignores column-type changes on existing columns). Currently migrates
 * PostgreSQL {@code oid}-typed LOB columns to {@code TEXT} so they can be read
 * outside the originating transaction. See SPEC.md §17 Persistence.
 *
 * Each migration is idempotent — it checks the current column type before
 * acting. Failures are logged but do not block application startup.
 */
@Component
@DependsOn("entityManagerFactory")
@RequiredArgsConstructor
@Slf4j
public class SchemaMigrationRunner {

    private static final List<String[]> LOB_TO_TEXT_COLUMNS = List.of(
            new String[]{"signal_performance_tracking", "signal_details"},
            new String[]{"market_suggestions", "reasoning"},
            new String[]{"market_suggestions", "news_snippets"},
            new String[]{"strategy_execution_logs", "market_snapshot"}
    );

    private final JdbcTemplate jdbc;

    @PostConstruct
    public void run() {
        log.info("Schema migration: checking LOB→TEXT conversions...");
        for (String[] tableColumn : LOB_TO_TEXT_COLUMNS) {
            migrateLobToText(tableColumn[0], tableColumn[1]);
        }
        addMissingColumns();
        dropObsoleteNotNullConstraints();
        normalizeRecommendationSymbols();
        backfillOutcomeDaysElapsed();
    }

    /**
     * Columns Hibernate {@code ddl-auto=update} did not create, added explicitly.
     * Format: {table, column, type}.
     *
     * <p>Update usually adds columns, but not always — the boolean {@code consolidated} on
     * {@code annual_fundamentals} was declared on the entity and never appeared, so every write
     * to that table failed with "column does not exist" and the backfill reported zero years
     * written while looking like it had run. `ADD COLUMN IF NOT EXISTS` is idempotent, so this
     * list is safe to keep and cheap to extend.
     */
    private static final List<String[]> ENSURE_COLUMNS = List.<String[]>of(
            new String[]{"annual_fundamentals", "consolidated", "boolean"},
            // B-097: a seeded thesis is not a statement. Gotcha 74 case - a silently missing
            // column here would make every investor-written thesis read as generated.
            new String[]{"holding_conviction", "thesis_stated", "boolean"},
            // Portfolio truth (SPEC 46): benchmark closes and cash are new tables, but the
            // unique constraints are declared on the entities; nothing to ensure here.
            // PIT V2.0 ingestion key (B-089). Gotcha 74: ddl-auto=update does not reliably
            // add a declared column, and if this one is missing every insider filing is
            // re-downloaded on every run while appearing to work.
            new String[]{"insider_disclosures", "filing_app_id", "varchar(32)"},
            // Schema lock for the universe backfill (SPEC §32.6). These five must exist BEFORE
            // the ~4,000-request archive pass runs: every year it writes is stamped source=XBRL,
            // and the import path then refuses to touch that row (Gotcha 49), so a column added
            // afterwards can only be filled by re-fetching every filing. The XBRL cache is 7 days
            // and process-local, and this app restarts daily, so a re-run pays the full price.
            new String[]{"annual_fundamentals", "available_from", "date"},
            new String[]{"annual_fundamentals", "available_from_estimated", "boolean"},
            new String[]{"annual_fundamentals", "dividends_paid", "double precision"},
            new String[]{"annual_fundamentals", "profit_before_tax", "double precision"},
            new String[]{"annual_fundamentals", "face_value", "double precision"},
            // SPEC §12.12 suggested entry - Gotcha 74: ddl-auto=update does not reliably add
            // a declared column, and a silent failure here shows as "no entry level" everywhere.
            // Macro event exposure (SPEC 48). market_impact_news is an existing, populated table,
            // so this column is the Gotcha 74 case: without it every ingest re-offers the same
            // headlines to the extractor for ever, while appearing to work.
            new String[]{"market_impact_news", "macro_extracted_at", "timestamp"},
            // Analyst target ledger (SPEC 49.5). Same Gotcha 74 case one row down: without this
            // column every capture re-offers the same headlines for ever while appearing to work,
            // and the ledger's own freshness stamp would never move.
            new String[]{"market_impact_news", "analyst_extracted_at", "timestamp"},

            // Analyst target ledger, structured research feed (SPEC 49.11). Gotcha 74 again: both
            // are added to a table that already has rows, and a write that silently fails here
            // would report "wrote 0 rows" from a backfill that ran perfectly.
            new String[]{"analyst_targets", "called_on", "date"},
            new String[]{"analyst_targets", "broker_stated_price", "double precision"},
            new String[]{"multibagger_scores", "support20d", "double precision"},
            new String[]{"multibagger_scores", "atr14", "double precision"},
            new String[]{"multibagger_scores", "ema50", "double precision"},
            // Watchlist tracking (SPEC §37): the two booleans are the Gotcha-74 case; the rest
            // are listed so a seed run on a partially-migrated table cannot fail silently.
            new String[]{"watchlist", "active", "boolean"},
            new String[]{"watchlist", "in_holdings", "boolean"},
            new String[]{"watchlist", "added_on", "date"},
            new String[]{"watchlist", "price_at_add", "double precision"},
            new String[]{"watchlist", "nifty_at_add", "double precision"},
            new String[]{"watchlist", "added_note", "varchar(500)"},
            new String[]{"watchlist", "source", "varchar(255)"},
            new String[]{"watchlist", "removed_on", "date"},
            new String[]{"watchlist", "price_at_removal", "double precision"},
            new String[]{"watchlist", "adhoc_quality_score", "integer"},
            new String[]{"watchlist", "adhoc_quality_at", "timestamp"},
            // Scoring provenance (SPEC §38.1). Both tables are populated, so these can only
            // ever be nullable — a historical row's provenance is genuinely unknown and must
            // not be back-filled with today's version.
            new String[]{"multibagger_scores", "scoring_version", "varchar(32)"},
            new String[]{"recommendations", "scoring_version", "varchar(32)"},
            // Shadow composites and weight reviews (SPEC §38.7). Both tables are created by
            // Hibernate on first boot, so these entries only matter on an upgrade where the
            // table exists but a later column does not — the Gotcha 74 case, where the feature
            // then writes nothing while appearing to run.
            new String[]{"shadow_composites", "variant_set_revision", "integer"},
            new String[]{"shadow_composites", "reconstruction_exact", "boolean"},
            new String[]{"weight_reviews", "passed_except_stability", "boolean"},
            new String[]{"weight_reviews", "adopted", "boolean"},
            // Screener growth + ownership columns (SPEC §12.5, 2026-09-09). Gotcha 74: a silently
            // missing column here would fail every screening row's persist, and the page would
            // read "not measured" for growth and promoter holding on a stock the run measured.
            new String[]{"multibagger_scores", "earnings_growth_verdict", "varchar(32)"},
            new String[]{"multibagger_scores", "yoy_revenue_growth", "double precision"},
            new String[]{"multibagger_scores", "yoy_profit_growth", "double precision"},
            new String[]{"multibagger_scores", "promoter_holding_pct", "double precision"},
            new String[]{"multibagger_scores", "promoter_holding_change_pct", "double precision"},
            new String[]{"multibagger_scores", "fii_holding_pct", "double precision"},
            new String[]{"multibagger_scores", "dii_holding_pct", "double precision"}
    );

    /** Add any column the entity declares that the table is missing. */
    private void addMissingColumns() {
        for (String[] spec : ENSURE_COLUMNS) {
            try {
                jdbc.execute("ALTER TABLE " + spec[0] + " ADD COLUMN IF NOT EXISTS "
                        + spec[1] + " " + spec[2]);
            } catch (Exception e) {
                // Never fatal: the table may not exist yet on a first boot, and Hibernate will
                // create it with the column. Logged at WARN because a silent skip here shows up
                // downstream as "this feature wrote nothing", which is much harder to trace.
                log.warn("Schema migration: could not ensure {}.{} exists ({}). Writes to that "
                        + "table may fail until it does.", spec[0], spec[1], e.getMessage());
            }
        }
    }

    /**
     * Columns whose entity field changed from a primitive to a wrapper, so the legacy
     * NOT NULL constraint must be dropped. Format: {table, column}.
     */
    private static final List<String[]> NULLABLE_NOW = List.of(
            new String[]{"multibagger_scores", "valuation_score"},
            new String[]{"multibagger_scores", "institutional_interest_score"},
            new String[]{"multibagger_scores", "sector_tailwind_score"},
            // B-101: the headline table no longer classifies anything - the impact level and
            // category were the input to two alert emails that told a long-term investor to adjust
            // stop-losses, and both are gone (SPEC 48.2). The columns stay because they hold real
            // history, but impact_level was declared NOT NULL in the classifying era and
            // ddl-auto=update never relaxes a constraint, so without this every single headline
            // insert would fail and the scan would look like it was finding no news at all.
            new String[]{"market_impact_news", "impact_level"},
            new String[]{"market_impact_news", "source"}
    );

    /**
     * Drop NOT NULL from columns whose entity field became nullable.
     *
     * <p>Hibernate {@code ddl-auto=update} adds columns but never relaxes an existing
     * constraint. When the four fundamental dimensions changed from {@code int} to
     * {@code Integer} — so an unmeasurable dimension could be excluded from the composite
     * instead of scored a fake neutral — the columns kept their NOT NULL from the primitive
     * era. Postgres then rejected every row carrying a null dimension.
     *
     * <p>That failure was near-silent and cost real data: the 2026-08-22 17:59 run logged
     * "Screened: 288" and "Persisted 78 scores", a 210-row shortfall visible only as
     * {@code SqlExceptionHelper} noise. Trend tracking, per-dimension IC and the §23
     * accuracy loop all read this table, so the loss was not cosmetic.
     *
     * <p>This is the mirror of the trap in CLAUDE.md "Integer-Not-Null Migration Bug":
     * adding a primitive column to a populated table fails, and so does relaxing one.
     */
    private void dropObsoleteNotNullConstraints() {
        for (String[] tableColumn : NULLABLE_NOW) {
            String table = tableColumn[0];
            String column = tableColumn[1];
            try {
                List<String> nullable = jdbc.queryForList(
                        "SELECT is_nullable FROM information_schema.columns "
                                + "WHERE table_name = ? AND column_name = ?",
                        String.class, table, column);
                if (nullable.isEmpty() || "YES".equalsIgnoreCase(nullable.get(0))) {
                    continue; // absent, or already nullable
                }
                jdbc.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP NOT NULL");
                log.warn("Schema migration: dropped NOT NULL on {}.{} — the dimension is now "
                        + "nullable (unmeasured != neutral) and rows were being rejected.", table, column);
            } catch (Exception e) {
                log.error("Schema migration: could not drop NOT NULL on {}.{}: {}. "
                        + "Screening rows with a null {} will FAIL TO PERSIST.",
                        table, column, e.getMessage(), column);
            }
        }
    }

    /**
     * Backfill exchange prefixes onto bare recommendation symbols (B-022).
     *
     * <p>SECTOR_REVERSAL recorded 1,247 picks as bare names ("AXISBANK") rather than
     * "NSE:AXISBANK". Kite's /quote returns an empty payload for a bare symbol, so
     * {@code RecommendationOutcomeScheduler} silently skipped every one of them and the
     * engine had zero measured outcomes for four months — no hit rate, no IC, and it was
     * missing from the weekly accuracy email entirely.
     *
     * <p>{@code RecommendationTracker} now normalises at write time; this repairs the rows
     * already on disk so ~1,100 already-matured picks become measurable on the next
     * outcome run. Idempotent — the WHERE clause matches only unprefixed rows.
     */
    private void normalizeRecommendationSymbols() {
        try {
            Integer bare = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM recommendations WHERE symbol NOT LIKE '%:%'", Integer.class);
            if (bare == null || bare == 0) {
                return;
            }
            int updated = jdbc.update(
                    "UPDATE recommendations SET symbol = 'NSE:' || symbol WHERE symbol NOT LIKE '%:%'");
            log.warn("Schema migration: prefixed {} bare recommendation symbols with 'NSE:' (B-022). "
                    + "Run POST /api/accuracy/refresh-outcomes to measure the now-eligible picks.", updated);
        } catch (Exception e) {
            // Table may not exist yet on a fresh database — never block startup.
            log.debug("Schema migration: recommendation symbol normalization skipped: {}", e.getMessage());
        }
    }

    /**
     * Backfill {@code recommendation_outcomes.days_elapsed} from the parent pick's issue date (B-028).
     *
     * <p>The column records how long a return actually ran, as opposed to the {@code horizon_days}
     * it is filed under. The two diverged because the outcome scheduler measured every unmeasured
     * pick <i>older than</i> the cutoff using today's price: 20.8% of 30d rows and 29.4% of 90d rows
     * had really run longer than their label, up to 124 days for a "30-day" outcome. Those rows fed
     * every hit rate and Information Coefficient in SPEC §23.
     *
     * <p>The rows are preserved, not deleted — the price and date on them are real, only the label
     * was wrong. Backfilling lets {@code findBySourceAndHorizon} exclude the drifted ones while
     * keeping them available for a longer-horizon analysis later.
     */
    private void backfillOutcomeDaysElapsed() {
        try {
            Integer pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM recommendation_outcomes WHERE days_elapsed IS NULL",
                    Integer.class);
            if (pending == null || pending == 0) {
                return;
            }
            int updated = jdbc.update("""
                    UPDATE recommendation_outcomes o
                       SET days_elapsed = (o.measured_date - r.issued_date)
                      FROM recommendations r
                     WHERE r.id = o.recommendation_id
                       AND o.days_elapsed IS NULL
                    """);
            Integer drifted = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM recommendation_outcomes "
                            + "WHERE days_elapsed IS NOT NULL AND days_elapsed > horizon_days + 7",
                    Integer.class);
            log.warn("Schema migration: backfilled days_elapsed on {} of {} outcome row(s) (B-028). "
                    + "{} row(s) ran past their labelled horizon and are now excluded from accuracy "
                    + "metrics — expect sample sizes and IC to move.", updated, pending, drifted);
            int orphaned = pending - updated;
            if (orphaned > 0) {
                log.error("Schema migration: {} outcome row(s) have no parent recommendation and "
                        + "keep a null days_elapsed; they will be excluded from accuracy metrics.", orphaned);
            }
        } catch (Exception e) {
            log.error("Schema migration: days_elapsed backfill failed: {}. Accuracy metrics will "
                    + "exclude every un-backfilled row — rerun before trusting IC.", e.getMessage());
        }
    }

    private void migrateLobToText(String table, String column) {
        try {
            List<String> types = jdbc.queryForList(
                    "SELECT udt_name FROM information_schema.columns " +
                            "WHERE table_name = ? AND column_name = ?",
                    String.class, table, column);
            if (types.isEmpty()) {
                return; // Hibernate hasn't created the column yet; will be TEXT on first write
            }
            String type = types.get(0);
            if ("text".equalsIgnoreCase(type) || type.toLowerCase().startsWith("varchar")) {
                return; // already correct
            }
            if ("oid".equalsIgnoreCase(type)) {
                log.warn("Schema migration: converting {}.{} from oid→TEXT (audit data will be cleared).",
                        table, column);
                jdbc.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
                jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " TEXT");
                log.info("Schema migration: {}.{} recreated as TEXT.", table, column);
            } else {
                log.warn("Schema migration: {}.{} has unexpected type '{}', leaving as-is.",
                        table, column, type);
            }
        } catch (Exception e) {
            log.error("Schema migration for {}.{} failed (non-fatal): {}",
                    table, column, e.getMessage());
        }
    }
}
