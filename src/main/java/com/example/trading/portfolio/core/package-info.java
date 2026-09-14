/**
 * Core Holding Classifier (SPEC §35) — the answer to "which stock should I never sell".
 *
 * <p><b>The split that matters.</b> {@link com.example.trading.portfolio.core.CoreHoldingService},
 * {@link com.example.trading.portfolio.core.DurabilityScorer} and
 * {@link com.example.trading.portfolio.core.CoreHysteresis} are pure: they take values and return
 * values, touching no repository, no broker and no clock beyond a date passed in. Every rule in
 * them is pinned by a test, so changing a gate, a component or a hysteresis condition makes a test
 * fail rather than quietly changing what the daily email says.
 * {@link com.example.trading.portfolio.core.CoreClassificationService} is the only class here that
 * reads a repository or calls the broker.
 *
 * <p><b>Nothing in this package feeds a score.</b> Tiers never enter the multibagger composite, so
 * no weight validation is touched, and the stored {@code holdings.recommendation} column is never
 * modified, so ML labels are unaffected. The one behavioural switch,
 * {@code portfolio.core.suppress-technical-exits}, ships off.
 */
package com.example.trading.portfolio.core;
