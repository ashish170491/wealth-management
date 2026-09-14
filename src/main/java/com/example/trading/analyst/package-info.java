/**
 * The analyst target ledger — who said what, and whether they were right (SPEC §49).
 *
 * <p><b>What this package does not do.</b> It produces no verdict on any stock, contributes zero
 * points to any score, is never an input to {@code BuyTimingVerdict}, and its vocabulary contains
 * no instruction to transact. An analyst target arriving through a news headline is the exact
 * shape of input that ended two engines on 2026-09-03 — both news-keyword engines, both running
 * for months without ever producing a measured hit rate (SPEC §39.3). This package is the
 * measurement those two never had, pointed at somebody else's opinions rather than the app's own.
 *
 * <p><b>Why it is allowed to name a company when §48's news reader is not.</b> The macro event
 * extractor may not name a business because doing so would be deciding which companies a general
 * story affects — a judgement that belongs to the exposure map the investor can read on screen.
 * Here the headline itself names the company, as the stated subject of an attributed price
 * target, and {@link com.example.trading.analyst.HeadlineSubjectResolver} only matches that name
 * to a ticker, refusing outright when two companies are named. Reading a name is not inferring a
 * relevance.
 *
 * <p><b>The shape.</b> Four pure classes carry the rules and are unit-tested without Spring:
 * {@link com.example.trading.analyst.Brokerages} (the one house vocabulary),
 * {@link com.example.trading.analyst.AnalystTargetParser} (headline → attributed target),
 * {@link com.example.trading.analyst.HeadlineSubjectResolver} (headline → ticker) and
 * {@link com.example.trading.analyst.AnalystTrackRecord} (rows → per-house record, and its
 * refusals). Three services touch the world:
 * {@link com.example.trading.analyst.AnalystTargetCaptureService} writes claims from feeds other
 * jobs already fetch, {@link com.example.trading.analyst.AnalystTargetOutcomeService} measures
 * them against daily candles, and {@link com.example.trading.analyst.AnalystTargetViewService}
 * shapes database rows for the screens.
 *
 * <p><b>The two numbers, and why both.</b> Whether a target was reached is the analyst's own
 * scoreboard, and a scoreboard a feature keeps on itself always reads better than a neutral one
 * (Gotcha 25). Beside it the ledger records return over the Nifty 50 across exactly the same
 * dates — the measure §23 applies to this app's own picks — so the two records can be read
 * against each other rather than each in its own flattering frame.
 */
package com.example.trading.analyst;
