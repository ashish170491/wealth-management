/**
 * The learning substrate (SPEC §38).
 *
 * <p>This package holds the machinery that makes it possible for the system to be judged,
 * and eventually to tune itself, <b>without</b> containing any model. That ordering is
 * deliberate. The previous ML chain was deleted on 2026-08-28 because nothing measured it
 * (see CLAUDE.md "ML analysis REMOVED"), and the failure was never the algorithm — it was
 * that no record existed of what the model saw, which version produced a given pick, or how
 * much of the universe the inputs actually covered.
 *
 * <p>Two things live here today:
 * <ul>
 *   <li>{@link com.example.trading.learning.ScoringVersion} / {@code ScoringVersionRegistry}
 *       — provenance. Every score row and every recorded recommendation is stamped with the
 *       version of the scoring configuration that produced it, so an outcome measured in
 *       2027 can be attributed to the engine that actually issued it. Without this, a weight
 *       change silently makes old and new scores incomparable — which has already happened
 *       twice (B-018, B-019).</li>
 *   <li>{@link com.example.trading.learning.ScreeningCoverage} / {@code ScreeningCoverageService}
 *       — the coverage vector. For every signal, per screening run: how many stocks it was
 *       actually measured on, how many it legitimately does not apply to, and how many are
 *       plain gaps. A dimension whose IC reads ~0 because it was unmeasurable on 60% of the
 *       universe is a data bug, not a weak signal, and until now the two were
 *       indistinguishable.</li>
 * </ul>
 *
 * <p><b>What is deliberately NOT here:</b> any adaptive re-weighting. SPEC §25.5 blocks it
 * until the outcome data can support it, and the arithmetic has not changed — ~8,900 30-day
 * outcomes are ~82 screening dates of a highly correlated cross-section, worth roughly five
 * independent periods. Nothing in this package changes a single score.
 */
package com.example.trading.learning;
