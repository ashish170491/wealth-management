# Core Holdings & Capital Recycling — Implementation Plan

**Date**: 2026-08-26
**Status**: **Phase A SHIPPED 2026-08-26** (observation mode) — classifier, snapshot table,
asymmetric hysteresis, the dedup-free `evaluateHolding` overload, the overlay, the email section,
`GET/POST core-holdings`, and 43 new tests (257 total, green). SPEC §35 and CLAUDE.md landed in the
same commit. **A0 is COMPLETE** — (b) 2026-08-26, and (a) on 2026-08-27 without the manual CSV step: NSE's own
annual filing archive supplied 150 financial years across the 33 holdings in 33 seconds
(SPEC §32.5). §4.4 carries the before/after figures. Phase B remains deferred. Originally a proposal; proposed SPEC sections §35 (Core Holdings) and §36 (Capital Recycling). **Reviewed 2026-08-26 — see §15**: 6 must-change findings (R-1–R-6, two P0) and 7 second-order (R-7–R-13); all thirteen are now reflected in §0–§14, and R-12 is filed as **B-056**. Phase A proceeds in observation mode (R-1); **Phase B is deferred** pending the R-2 evidence gate (next review 2026-11-30).
**Effort**: Phase A0 + A + review ≈ 5 working days now; Phase B (3–4 days) and C (2 days) deferred — see §13
**Prerequisites**: one new API (`medianReturnByBand`, Phase B — R-7) and one **hard data gate** with two parts: screener.in history imported for all 33 active holdings, and the 7 holdings with no `multibagger_scores` row brought into the screening universe, both before Phase A ships (§13 A0, R-3; baseline in §4.4). Everything else already exists in the codebase after F1–F8 (EARLY_DISCOVERY_IMPLEMENTATION_PLAN.md).

## 0. The two questions this answers

The investor asked:

1. *"Which stock in my portfolio should I never sell and keep for long-term wealth, based on past data and ignoring short-term hiccups?"*
2. *"Which stock is a good candidate for profit booking so I can allocate the same funds to different good-quality stocks?"*

Today the app cannot answer either directly (analysis of 2026-08-26):

- It measures every ingredient of "core" per holding — capital-efficiency verdict, financial quality, earnings consistency, thesis drift, forensic flags, reverse-DCF — but never combines them, and **its exit machinery actively contradicts the question**: `HoldingsAnalysisService.determineRecommendation()` returns SELL on a technical score < 40 or a bearish trend, and `ExitTimingAlertService` fires RSI/EMA50/momentum alerts on every holding equally. A compounder in a routine 20% drawdown gets a SELL.
- Its `BOOK_PROFIT` rule is `P&L > 50% AND RSI > 70` — a momentum rule that flags the *best* performers while they are running, which is the opposite of what a long-term investor should trim. Nothing ranks holdings by *forward weakness per rupee tied up*, and nothing links a sale to what the freed capital should buy.

This plan adds one feature with two halves: a **Core Holding Classifier** (question 1) and a **Capital Recycling Ranker** (question 2), plus the behavioural change that makes "ignore short-term hiccups" real — technical exit signals are suppressed for CORE holdings and only *fundamental* invalidation can demote one. That suppression ships **observed first, armed later** (§4.5, R-1): for the first quarter the classifier reports which alerts it *would* have suppressed, and the switch is flipped on that evidence.

---

## 1. Principles (binding)

Inherited from SPEC.md / BUGS.md / the F1–F8 lessons; every design choice below must satisfy them.

1. **Never auto-executes** (SPEC §10, §19). Both halves produce proposals for human review.
2. **"Never sell" is conditional, never literal.** A CORE holding keeps every *fundamental* invalidation trigger armed (SPEC §6.2). What it loses is sensitivity to price noise.
3. **Asymmetric caution.** Wrongly demoting a CORE holding costs a compounder; wrongly keeping one costs opportunity. Promotion and demotion therefore use **hysteresis** (§4.6) and demotion requires fundamental evidence, never price.
4. **Null is not neutral** (Gotcha 21, B-021). A holding the classifier cannot measure is `UNCLASSIFIED` with the missing inputs named — never defaulted to SATELLITE or scored 50.
5. **Data-driven, no AI in scoring.** The existing AI portfolio commentary may *describe* the output; it never decides tier or rank.
6. **Measure before trust** (SPEC §25.5, Gotcha 27). Recycling proposals are recorded and their outcomes measured (§5.7) from day one. Tier assignments are persisted daily so the classifier's own accuracy can be judged later — and the one behavioural switch (§4.5) stays off until a quarter of observed alerts says what it would have done.
7. **Tax-aware by construction** (SPEC §9.4). No sale proposal without its LTCG/STCG split and estimated tax.
8. **Beginner-friendly** (SPEC §21). Every new section carries a "What this means" box; every term (ROCE, drawdown, LTCG, expectation gap) glossed on first use.
9. **Scheduler rule** (SPEC §3.4). No new cron: classification runs inside the existing 10:30 `analyzeHoldings` job; reports read persisted rows.
10. **Dashboard contract** (SPEC §27). New GET endpoints are DB-only; the compute trigger is a POST.
11. **Shadow where it scores, visible where it proposes.** Tier assignment changes what *emails say* (reversible by flag); recycling proposals are explicit proposals. Neither feeds the multibagger composite, so no weight validation is touched.

---

## 2. Definitions (what the words mean operationally)

| Term | Meaning in this feature |
|---|---|
| **CORE** | A holding whose *business* has demonstrated durable wealth creation (capital efficiency, consistency, clean books) and whose thesis is intact. Price action is irrelevant to the tier. Technical exit signals are suppressed once `suppress-technical-exits` is on (in the observation quarter they still fire and are reported — §4.5); only fundamental invalidation can demote it. |
| **CORE_WATCH** | Passes every hard gate but one *soft* signal is off (e.g. decay WATCH, insider DISTRIBUTION). Treated as CORE by the overlay; surfaced for attention. |
| **SATELLITE** | Fails at least one hard gate or is not yet proven. Normal exit/alert behaviour applies. Eligible for recycling. |
| **UNCLASSIFIED** | Insufficient data to judge (holding outside screening universe, no annual history, financials with unsupported metrics). Reported explicitly with the missing inputs. Normal exit behaviour applies; **not** eligible for recycling (never propose selling what you couldn't measure). |
| **Durability score** | 0–100 ranking *within* CORE, from multi-year evidence (§4.3). Answers "which core holding is the strongest" — the question's "based on past data". |
| **Weakness score** | 0–100 ranking of non-CORE holdings by forward weakness per rupee (§5.2). High = strongest recycling candidate. |
| **Recycling proposal** | One SELL leg (tax-classified) paired with 2–3 BUY legs from the current candidate pool, with the expected uplift and estimated tax. Non-executing. |

---

## 3. Data inventory — what already exists per holding

Everything below is persisted today and joined by `symbol`. **No new external data source.**

| Input | Where it lives | Used by |
|---|---|---|
| Capital-efficiency verdict, ROCE, ROE, ROA, D/E, cash conversion | `multibagger_scores.capital_efficiency_verdict`, `roce_percent`, … (SPEC §12.8) | Core gate G1, durability D1 |
| Financial-quality verdict / score, interest coverage, pledge % | `multibagger_scores.financial_quality_*` (§12.5) | Core gate G2 |
| Earnings consistency, gross-margin trend, PEG, delivery % | `multibagger_scores.earnings_consistency_score`, … (§12.7) | Core gate G3, durability D2 |
| Forensic flags (dilution, receivables, cash, auditor) | `multibagger_scores.forensic_flags` (§32.4) | Core gate G4, weakness W5 |
| Turnaround / capex verdicts | `multibagger_scores.turnaround_verdict`, `capex_verdict` (§31, §32) | Weakness W9 (protects inflecting stocks) |
| Reverse-DCF verdict + expectation gap, PE deviation | `multibagger_scores.dcf_*`, `pe_deviation` (§12.5) | Weakness W1 |
| Composite, grade, percentile, trend | `multibagger_scores` + `findTrend()` | Weakness W4; decay input |
| Score decay verdict (30d/60d) | `HoldingsDecayService.detectDecay()` (§6.2) | Core gate G5, weakness W2 |
| Insider pulse verdict (shadow, but computed) | `multibagger_scores.insider_pulse_verdict` (§28) | Core gate G6, weakness W7 |
| Liquidity tier, circuit days | `multibagger_scores.liquidity_tier` (§12.9) | Buy-leg filter |
| Thesis, conviction, horizon, invalidation triggers, purchase score | `holding_conviction` (§6) | Core gate G7, weakness W6 |
| Annual sales / profit / borrowings / equity / CFO / share count per FY | `annual_fundamentals` (F5, XBRL + import) | Durability D1–D4 (multi-year) |
| Tax-lot horizon split, days to next LTCG | `TaxLotService.classifyForExit()` (§9.4) | Sell-leg gate |
| P&L, quantity, invested / current value, RSI, trend, industry | `holdings` | Weights, weakness context |
| Portfolio HHI / sector concentration | `portfolio/risk` (§7) | Buy-leg diversification |
| Daily candles (Kite) | `MarketDataService.getRecentCandles` (paced) | Durability D5 drawdown-recovery |

**Known gaps to handle, not fix here**
- Holdings on BSE (`BSE:X`) do not match `NSE:X` score history (SPEC §6.4 known gap). The classifier resolves by **ISIN** first (holdings carry `isin`; tax lots already do this), symbol second.
- `annual_fundamentals` has ≥4 FYs only where the user imported screener.in history or where XBRL has accumulated. Multi-year durability components go null and renormalise (§4.4) — the output states its coverage.
- Holdings outside the screening universe have no `multibagger_scores` row → UNCLASSIFIED, with the reason "not in screening universe — add via universe expansion or manual tier list". **7 of the 33 active holdings are in this state today** (§4.4 baseline); bringing them into the universe is part (b) of the A0 gate (§13).

---

## 4. Module A — Core Holding Classifier

Package: `com.example.trading.portfolio.core`
Classes: `CoreHoldingService` (pure classification), `CoreHoldingSnapshotEntity` + repository (daily persistence), `CoreOverlayService` (behavioural overlay, §4.5), `CoreHoldingConfig` (`@ConfigurationProperties("trading.portfolio.core")`).

### 4.1 Inputs per holding (one `HoldingEvidence` record)

Assembled by a single `evidenceFor(HoldingsEntity)` method from the sources in §3. Every field nullable. The record also carries `List<String> missingInputs` so the output can say what could not be measured.

### 4.2 Hard gates (ALL required for CORE / CORE_WATCH)

| Gate | Rule | Null handling | Why |
|---|---|---|---|
| **G1 Capital efficiency** | verdict ∈ {HIGH_QUALITY_COMPOUNDER, SOLID}; for financials (`NA_FINANCIAL`): ROE ≥ 14 AND ROA ≥ 1.0 | null verdict → gate *unmeasured* | A business that does not earn well above its cost of capital cannot compound |
| **G2 Financial quality** | verdict ∈ {HIGH_QUALITY, DECENT} | null → unmeasured | Fragile balance sheets are not "never sell" |
| **G3 Earnings consistency** | `earningsConsistencyScore ≥ 60` | null → unmeasured | Steady beats lumpy for a hold-forever position |
| **G4 Forensic** | zero forensic flags | null flags (not measured) → `PASS_NO_DATA` — absence of evidence is not a flag, but it is not evidence either (does not count toward the quorum) | Clean books are a precondition, not a bonus |
| **G5 Thesis intact** | decay verdict ∈ {INTACT, WATCH}; purchase drift > −25 if conviction record exists | NO_DATA/STALE → unmeasured | A CORE with a broken thesis is a SATELLITE with sunk cost |
| **G6 Owner alignment** | insider pulse ≠ STRONG_DISTRIBUTION | null → `PASS_NO_DATA` (no filings; does not count) | Promoters selling in size is a fundamental fact, not price noise |
| **G7 Horizon** | if a conviction record exists: `holdingHorizonMonths ≥ 36` | absent → `PASS_NO_DATA` (does not count) | The investor's own stated intent |

Tier resolution:
- Any gate **fails** → `SATELLITE` (reasons list the failed gates).
- ≥ `min-measured-gates` (default 5 of 7) **measured** and none failed → `CORE` if no soft signal is off, else `CORE_WATCH`.
- Fewer than `min-measured-gates` measured → `UNCLASSIFIED` with `missingInputs`.

**What "measured" means (R-4).** A gate that passes *on absent data* — G4 with no forensic history, G6 with no insider filings, G7 with no conviction record — is a pass for the fail/pass decision but **does not count toward `min-measured-gates`**. Otherwise a holding with nothing on file collects three free passes and reaches CORE on G1+G2 alone. So CORE needs five gates with real evidence behind them; `gates_json` records each gate as `PASS`, `PASS_NO_DATA`, `FAIL` or `UNMEASURED`, and only `PASS`/`FAIL` count. `CoreHoldingGatesTest` pins the case "G1+G2 pass, G3 measured pass, G4/G6/G7 no-data → UNCLASSIFIED, not CORE".

Soft signals (turn CORE into CORE_WATCH, never SATELLITE): decay WATCH; insider DISTRIBUTION; capital-efficiency SOLID with ROCE falling two consecutive FYs; financial-quality DECENT with interest coverage < 4; trend-break NEGATIVE_BREAK.

### 4.3 Durability score (0–100) — "based on past data"

Ranks holdings *within* CORE and is shown for every tier so the reader sees why. Components, each 0–20, **renormalised over measured components only**:

| # | Component | Measure | Source | Needs |
|---|---|---|---|---|
| D1 | Return-on-capital persistence | years in last 5 with ROCE ≥ 15% (financials: ROA ≥ 1.2%) → 4/yr | `annual_fundamentals` (PBT+interest / equity+borrowings per FY) | ≥ 4 FYs |
| D2 | Growth consistency | earnings-consistency score scaled ×0.2, plus +4 if 5-yr sales CAGR ≥ 10% and profit CAGR ≥ sales CAGR | `multibagger_scores.earnings_consistency_score`, `annual_fundamentals` | ≥ 4 FYs for CAGR part |
| D3 | Balance-sheet trajectory | D/E flat or falling over 3 FYs +10; net cash +5; no dilution (share-count CAGR ≤ 2%) +5 | `annual_fundamentals` | ≥ 3 FYs |
| D4 | Cash discipline | 3-yr ΣCFO/Σprofit ≥ 0.8 +10; ≥ 1.0 +5; dividend paid in each of last 3 FYs +5 | `annual_fundamentals`, dividend module (§11) | ≥ 3 FYs |
| D5 | **Hiccup recovery (closed episodes only)** | from up to 5 years of daily closes, consider **only drawdown episodes that closed ≥ 18 months ago** (price regained the prior high at least 18 months before today). For each: recovery within 12 months +5, within 24 months +3 (cap 15). The **current** drawdown, and any episode still open, is **excluded from the score entirely** — it is described in the coverage text ("currently 22% below its high, 7 months in; not scored"). 5-yr price CAGR ≥ 12% +5 | Kite daily candles (two paced calls per holding — token + candles — cached for the day) | ≥ 3 years of candles |

D5 measures how the business *has* come through past hiccups. It must never penalise the hiccup the holding is in right now — that is exactly the moment the tier is supposed to hold (R-3). The original formulation (count drawdowns recovered to a new high; −10 for an unrecovered ≥ 30% fall) was backwards: it could only be satisfied by a stock near its high and downgraded a core holding mid-drawdown.

Output per holding: `durabilityScore` (Integer, **null when fewer than `durability-min-components` = 3 are measured** — never shown as a number on thin data), `durabilityCoverage` ("4 of 5 components measured; missing: D1 (needs 4 FYs, have 2)").

### 4.4 Degradation when history is thin

**Pre-import baseline, measured 2026-08-26** (the "before" half of the coverage figure §15 asks for; re-run this query after A0 and record the "after" beside it):

| Measure | Value |
|---|---|
| Symbols with any `annual_fundamentals` row | 378 |
| **Maximum FYs held for _any_ symbol** | **1** |
| Active holdings | 33 |
| … with any annual-history row | 18 |
| … with any `multibagger_scores` row | 26 |
| … with G1/G2/G3 all measurable | 18 |
| … with ≥ 4 FYs (what D1 needs) | **0** |

The load-bearing number is the second: no symbol *anywhere* in the table has more than one financial year, so D1–D4 are unbuildable for the whole universe rather than merely thin for the portfolio (R-3).

**A0 COMPLETE — after-figures measured 2026-08-27.** A0(a) turned out not to need the manual CSV
step at all: NSE still serves 13–14 years of annual filings per symbol behind the endpoint B-017
had written off as frozen (SPEC §32.5). The whole portfolio was backfilled in **33 seconds**.

| Measure | Before (26 Aug) | After (27 Aug) | Note |
|---|---|---|---|
| Symbols with any `annual_fundamentals` row | 378 | 385 | the seven A0(b) additions |
| **Maximum FYs held for any symbol** | **1** | **8** | the binding constraint, gone |
| Financial years written | — | **150** | across 33 holdings, from NSE's own XBRL archive |
| Active holdings with ≥ 3 FYs (forensic screen) | **0** | **24** | |
| Active holdings with ≥ 4 FYs (durability D1, dilution) | **0** | **24** | |
| Rows with `source=IMPORT` | 0 | 0 | no CSV was ever needed |
| Active holdings with a `multibagger_scores` row | 26 of 33 | 26 of 33 **today**, 33 of 33 from the next 14:00 screening | **A0(b) shipped 26 Aug**: the seven were added to `SCREENING_UNIVERSE`, and the classifier falls back from `BSE:X` to `NSE:X` by trading symbol |

**The nine holdings still on zero years are honest coverage, not a failure**: `NSE:EMCURE`,
`NSE:LGEINDIA`, `NSE:PREMIERENE`, `NSE:VMM`, `BSE:WAAREEENER`, `BSE:CPPLUS`, `BSE:RATHIST` are
recent listings with no multi-year archive to fetch; `BSE:STARHEALTH` is an insurer (IRDAI format,
the known §12.8 gap). The CSV import remains the fallback for exactly these.

**Effect on the classifier, measured the same day.** G4 went from `PASS_NO_DATA` on all 33 to
**18 PASS / 5 FAIL / 10 no-data** — the forensic screen now runs, and it found five real flags.
The tier split moved 19/14 SATELLITE/UNCLASSIFIED to **21/12**, and the SATELLITE rows are now
gate *failures* rather than shrugs.

**Still 0 CORE, but the reason has changed.** It is no longer a data ceiling: the ceiling is now
five measurable gates against a quorum of five, so CORE is reachable — every holding simply has at
least one genuine failure or too thin a measured set. G3 (earnings consistency ≥ 60) fails 17 of the
19 it can measure, which is the single harshest constraint on this portfolio. That is a finding
about the holdings, not about the app, and it is not a reason to move the threshold.

The seven were: `NSE:ABCAPITAL`, `NSE:BANKINDIA`, `NSE:LGEINDIA`, `NSE:WABAG`, `BSE:KAJARIACER`,
`BSE:STARHEALTH`, `BSE:CPPLUS`.

**Measured on the first live run (2026-08-26, after the B-057 fix): 19 SATELLITE, 14 UNCLASSIFIED,
0 CORE — and 0 CORE is currently *structural*, not a scoring outcome.** The gate census was:

| Gate | PASS | FAIL | UNMEASURED | PASS_NO_DATA |
|---|---|---|---|---|
| G1 capital efficiency | 13 | 6 | 14 | — |
| G2 financial quality | 17 | 3 | 13 | — |
| G3 earnings consistency | 2 | 17 | 14 | — |
| G4 forensic | — | — | — | **33** |
| G5 thesis intact | 14 | 4 | 15 | — |
| G6 owner alignment | — | — | — | **33** |
| G7 stated horizon | — | — | — | **33** |

Three gates are `PASS_NO_DATA` for **every** holding: G4 because the forensic screen needs ≥ 3 FYs
of annual history and the maximum on file is 1; G6 because no insider disclosures have been
captured per stock; G7 because every conviction record carries a seeded horizon (B-057). That
leaves a ceiling of **four** evidence-carrying gates against a quorum of five, so **no holding can
reach CORE until A0(a) is done** — the import is not merely a coverage improvement for durability,
it is the binding constraint on the classifier producing any core verdict at all.

The correct response is *not* to lower `min-measured-gates` to fit the data. That would trade the
one property the tier is for — that CORE means something was actually checked — for a full-looking
report. The feature says UNCLASSIFIED with reasons until the evidence exists, which is §1 principle
4 doing its job.

**Until A0(a) is done, durability will be null for most holdings and the gates will run on a
narrow quorum.** That is the design working — the score is withheld rather than faked — but it
means §14 criterion 3a is not yet met, and the "after" row above must be re-measured and recorded
here once the import has run. Phase A shipped ahead of A0(a) because the classifier's null
discipline makes thin data visible rather than misleading: every unmeasured component is named,
`durabilityScore` stays null below three components, and a holding that cannot be judged is
UNCLASSIFIED rather than SATELLITE. What A0(a) buys is *coverage*, not correctness.

Two further consequences the gates inherit, not the durability score:

- **7 holdings have no score row at all**, so they are UNCLASSIFIED whatever the import does — gates read `multibagger_scores`, and the import writes `annual_fundamentals`. These need universe coverage, which is why A0's scope is *import **and** cover*, not import alone.
- **Only 18 of 33 have G1/G2/G3 measurable**, and G4 is `PASS_NO_DATA` for every holding today (the forensic screen needs ≥ 3 FYs and the maximum is 1). Under the R-4 quorum the countable set is therefore G1, G2, G3, G5 and G7-where-a-conviction-record-exists — **exactly five for a best-case holding, with no margin**. Expect roughly **15 of 33 holdings to land UNCLASSIFIED at launch**. That is principle 4 working rather than a defect, but §14 criterion 1 should be read with this split in mind, and the email's unclassified one-liner (§6.1 item 5) will be carrying close to half the portfolio at first.

For durability specifically: without the import, D1–D4 are null for *every* holding and the score would rest on D2 (a quarterly consistency score) and D5 (price) — a price signal wearing a fundamentals label. Hence:

1. **The screener.in history import is part (a) of the A0 hard gate** (§13), not a suggestion. Durability is not built until the 33 holdings have their history in, and the "after" column of the table above is recorded here before Phase A is called done.
2. Even after the import, any holding still short of the FY threshold shows its coverage plainly: *"Durability is measured on 2 of 5 components for this stock. Import its history (Portfolio → Fundamentals → Import) to complete the picture."* `durabilityScore` is null (not shown) when fewer than `durability-min-components` (3) are measured.

The classifier still assigns a tier from the hard gates, which do not need multi-year history — that is why gates and durability are separate. But note the baseline's second consequence: with G4 at `PASS_NO_DATA` for every holding, the gates *do* depend on history indirectly (the forensic screen needs ≥ 3 FYs), so the import also widens the quorum.

### 4.5 The behavioural overlay — what CORE actually changes

Implemented as `CoreOverlayService`, applied at **report and alert time**, never inside scoring or ML training data, so labels and models are unaffected.

| Path | Today | With overlay, for CORE / CORE_WATCH |
|---|---|---|
| `ExitTimingAlertService.evaluateHolding()` | NEAR_RESISTANCE, RSI_OVERBOUGHT, BROKE_SUPPORT, DEEP_LOSS, MOMENTUM_REVERSAL on all holdings | **Observation mode (shipped default)**: nothing changes; the four technical alerts still fire and a footer names those that landed on CORE holdings ("3 technical alerts fired on core holdings today: X, Y — suppression would have withheld them"). **Suppression mode**: the four technical alerts are withheld but still *named* in the footer, via the dedup-free overload (below). DEEP_LOSS stays in both modes, re-titled *"Deep drawdown on a core holding — review the thesis, not the price"* and linked to the invalidation triggers |
| `HoldingsAnalysisService.determineRecommendation()` result, as **displayed** | SELL / STRONG_SELL on technical score; BOOK_PROFIT on P&L>50 & RSI>70 | Displayed as `HOLD_CORE` with the underlying technical label in parentheses; BOOK_PROFIT never shown for CORE (recycling ranker handles profit-taking for non-CORE only). The stored `recommendation` column is **unchanged** — the overlay is presentational, so `HoldingsTrainingDataCollector` keeps learning from the raw signal |
| Action Items email "Exit Required" table | technical SELL rows | CORE rows excluded; listed instead under "Core holdings — hold through the noise" with their durability and the one-line reason (both modes — this is presentation, not a risk control, and the underlying alert still reaches the reader in observation mode via the exit-alert email) |
| Action Items email "Book Profit" table (B-056) | `BOOK_PROFIT` on P&L > 50 & RSI > 70 | CORE rows excluded. Until Phase B replaces the table, non-CORE rows keep it but the header carries the B-056 caveat: *"a momentum rule from the intraday era — treat as a prompt to check valuation, not as a sell instruction"* |
| Target-hit alert (§26) | fires on target | still fires (informational), tagged "core — trimming, if any, goes through the recycling ranker once it exists" |

**What can still demote a CORE holding** (fundamental invalidation): financial quality → WEAK/HIGH_RISK; any forensic flag; decay BROKEN; insider STRONG_DISTRIBUTION; capital-efficiency → WEAK/POOR; the investor marking a conviction invalidation trigger as hit (existing §6 record). **Critical triggers demote immediately, soft ones with hysteresis** — see §4.6 (R-6). On demotion the Action Items email leads with *"Core holding demoted: X — reason"*.

**Ships in observation mode (R-1).** `trading.portfolio.core.suppress-technical-exits` defaults **`false`**. Suppressing an exit alert removes a risk control, and the gates that would justify it (G1–G3) rest on metrics whose measured IC panel runs +0.042 to −0.009 with ROE/ROA/cash-conversion *negative* (Gotcha 27). With suppression on from day one the counterfactual is never observed and the feature can never be judged. So for the first quarter the classifier runs, tiers are persisted and shown, the `HOLD_CORE` display label still appears, but every technical alert still fires — and the Action Items email carries one extra line: *"N technical exit alerts fired on core holdings today (X, Y, Z); with suppression on, these would not have been sent."* Each such alert is persisted (`holding_classification.observed_alerts`) so at the quarter's end the flip decision is made on what those alerts would have saved or cost, not on intuition. The flag is a real switch and can be turned on at any time.

**Implementation constraint — Gotcha 19 (R-5).** `ExitTimingAlertService.evaluateHolding()` mutates `sentAlertsToday` inline at six call sites, and the `recordDedup=false` overload CLAUDE.md refers to **does not exist** (verified 2026-08-26). If the overlay filtered alerts *after* evaluation, each suppressed alert would still burn the day's dedup slot, so a later flag flip or same-day demotion would produce silence. **The dedup-free overload is mandatory, not an alternative (R-13).** Filtering CORE holdings out before `evaluateHolding` yields a tier, not a list — and the footer has to *name* the alerts it suppressed. Knowing which ones would have fired means evaluating them, and evaluating them is what mutates the dedup set. So the two modes need different machinery, and only one of them needs new code:

| Mode | What happens | `sentAlertsToday` after the overlay |
|---|---|---|
| **Observation** (`suppress-technical-exits=false`, the shipped default) | Nothing is suppressed. Alerts fire and are sent exactly as today; the observation line simply reports which of them landed on CORE holdings. No new code path. | **Holds their keys, as today** — these alerts really were sent, so they must dedup normally or the 10:00 alert repeats at 12:00 and 14:00 |
| **Suppression** (`true`) | Alerts must be computed to be named, but neither sent nor deduped. Requires `evaluateHolding(holding, recordDedup=false)` — which **does not exist yet** and is therefore a Phase A deliverable, not a fallback | **Holds no key for a CORE holding** |

Since the whole point of the observation quarter is to flip the flag at the end of it, the overload has to be built during Phase A regardless — otherwise the flip either burns dedup slots (the Gotcha 19 silent failure — alerts consumed but never sent) or degrades the footer from named alerts to a bare count.

### 4.6 Hysteresis and persistence

- `holding_classification` table: `symbol`, `isin`, `classified_on` (date), `tier` (provisional, that day's reading), `effective_tier` (after hysteresis), `durability_score` (Integer, nullable), `durability_coverage`, `gates_json` (per-gate `PASS` / `PASS_NO_DATA` / `FAIL` / `UNMEASURED`), `soft_signals`, `missing_inputs`, `reasons`, `observed_alerts` (JSON list of technical alerts that fired on this holding that day while it was CORE — the R-1 evidence). One row per holding per day. Unique (symbol, classified_on).
- **Effective tier — hysteresis is asymmetric (R-6).**
  - **Promotion** to CORE/CORE_WATCH and **soft demotion** (decay DECAYING, capital-efficiency → WEAK, consistency dropping below 60, a soft signal turning into a gate failure on stale data) require `hysteresis-runs` (default 2) consecutive *weekly* anchor rows (Fridays). Daily rows update the display ("would become SATELLITE — 1 of 2 confirmations"). This is what stops a single bad XBRL parse or a one-day NSE outage from flipping exit behaviour.
  - **Critical demotion is immediate** — the same day the daily row shows it: any forensic flag; an auditor problem (Gotcha 45: it escalates, it does not deduct); financial quality HIGH_RISK; decay BROKEN; insider STRONG_DISTRIBUTION. A holding that trips one of these must not keep its exits suppressed for up to 14 days while the anchors accumulate. Re-promotion after a critical demotion goes through the normal two-anchor path.
  - Only the effective tier drives the overlay.
- Manual override: `holding_conviction.core_override` (`FORCE_CORE` / `FORCE_SATELLITE` / null) via `POST /api/portfolio/core-holdings/override` — the investor's judgement wins, logged with timestamp (SPEC §6.2 audit line).

### 4.7 Scheduling

Runs at the end of the existing `HoldingsScheduler.analyzeHoldings()` (10:30 MON-FRI, already guarded) so classification uses that morning's refreshed holdings and the previous day's scores. Kite calls for D5: **two** per holding per day (instrument-token resolution + candles — SPEC §30.6 measured two, not one; R-9), cached in the classifier (not the deleted global cache), paced via `executeWithRetry`. ~35 holdings → ~25 s. No new `@Scheduled` method.

---

## 5. Module B — Capital Recycling Ranker

Package: `com.example.trading.portfolio.recycle`
Classes: `CapitalRecyclingService`, `RecyclingProposalEntity` + repository, `RecyclingConfig` (`trading.portfolio.recycling`).

### 5.1 Candidate set

Holdings with effective tier `SATELLITE` only. CORE/CORE_WATCH are never proposed (question 1 wins over question 2 by construction). UNCLASSIFIED is never proposed (principle 4).

### 5.2 Weakness score (0–100) — "forward weakness per rupee tied up"

Additive, capped at 100, **each component null-safe (skipped and named, never zero-filled)**:

| # | Component | Points | Source |
|---|---|---|---|
| W1 | Valuation stretch | DCF EXTREMELY_EXPENSIVE 25 · EXPENSIVE 15 · PE deviation > +50% vs sector +8 (max 25) | `dcf_verdict`, `pe_deviation` |
| W2 | Thesis decay | BROKEN 20 · DECAYING 12 · WATCH 5 | `HoldingsDecayService` |
| W3 | Quality | HIGH_RISK 25 · WEAK 15 · AVERAGE 5 | `financial_quality_verdict` |
| W4 | Composite standing | composite < 40 → 15 · < 50 → 8 · percentile < 30 → +5 (max 15) | `multibagger_scores` |
| W5 | Forensic | +10 per flag, max 20 | `forensic_flags` |
| W6 | Capital efficiency | POOR 15 · WEAK 8 · ROCE < 10% +5 (max 15) | `capital_efficiency_verdict`, `roce_percent` |
| W7 | Owner distribution | STRONG_DISTRIBUTION 10 · DISTRIBUTION 5 | `insider_pulse_verdict` |
| W8 | Earnings trend | BIG_NEGATIVE_BREAK 8 · NEGATIVE_BREAK 4 | analyst-signal trend-break (§24) |
| W9 | **Inflection protection** | turnaround TURNAROUND_CANDIDATE **−15** · capex EXPANSION_UNDERWAY **−10** | `turnaround_verdict`, `capex_verdict` — never recycle a business that is about to get better |
| W10 | Dead weight | P&L between −5% and +5% **and** held > 24 months **and** composite < 55 → +8 | `holdings`, tax lots — capital that has done nothing for two years has an opportunity cost even without a red flag |

Weakness is only computed when ≥ 4 of W1–W8 are measured; otherwise `null` with reasons.

### 5.3 Expected uplift (the "why move the money" number)

Uses the band table in Gotcha 26 (median 4-month return by composite band: 80+ → 8.43%, 70–79 → 7.23%, 65–69 → 4.71%, 50–64 → 2.34%, < 50 → −0.19%). **That table was produced ad hoc — no `bandReturn`-style method exists in `RecommendationAccuracyService` (R-7, verified 2026-08-26).** Phase B builds `medianReturnByBand(horizonDays)` from `recommendation_outcomes`, returning `null` for any band below a minimum sample (default 15 matured outcomes) rather than a median of three picks; the Prerequisites line's "none new" is wrong by this one API.

`expectedUpliftPp = bandReturn(candidate composite) − bandReturn(holding composite)` per buy leg; the proposal shows the weighted average.

**Uplift must beat tax and friction, or the proposal is not a proposal (R-2).** The original design emitted an uplift (§5.3) and a tax (§5.4) and never compared them. Rule: a proposal is `PROPOSED` only when

`expectedUpliftInr > estimatedTaxInr + friction` where `friction = sellAmount × friction-bps / 10 000` (STT, brokerage, spread; config, default 60 bps round trip).

Otherwise it is emitted with status **`NOT_WORTH_IT`** and its arithmetic shown — *"moving ₹2.4 L from X (band 50–64) to Y (band 80+) is worth about ₹14,600 of expected 4-month uplift against ₹30,000 of LTCG and ₹1,400 of costs"*. The comparison is **not like-for-like** and the gloss says so: a median 4-month uplift is a tendency that can repeat; the tax is paid once and is permanent. A proposal that clears the gate only by assuming the uplift repeats for a year is *not* cleared — the gate uses the single-horizon figure.

**Evidence gate on Phase B itself.** The band table rests on one 4-month window that Gotcha 26 calls *"enough to rank on, not enough to discard on"* — and recycling discards on it, paying tax to do so. The composite underneath is +1.54 pp/month at t ≈ 1.24, p ≈ 0.28 over ~5 independent periods. **Phase B does not ship until a second, non-overlapping return window supports the band ordering** (§13). Phase A answers question 1 and stands alone.

### 5.4 Tax gate (SPEC §9.4)

For each candidate, `TaxLotService.classifyForExit(symbol)`:
- **LTCG-eligible quantity > 0** → propose selling that quantity first; estimated tax = gain × `ltcg-rate` above the annual exemption remaining (both config: `ltcg-rate-percent: 12.5`, `ltcg-exemption-inr: 125000`, `stcg-rate-percent: 20` — rates change with the Finance Act, so they live in yml with the FY noted, never in code).
- **STCG-only** with `daysUntilNextLtcg ≤ wait-for-ltcg-days` (default 60) → proposal status `WAIT`, shows the date, unless weakness ≥ `override-wait-weakness` (default 70) or a forensic flag exists (cut-loss precedence, §9.4).
- **STCG-only, further out** → proposed with STCG tax shown and the note that a realised short-term loss (if P&L < 0) offsets other gains this FY.
- `dataSource = UNKNOWN` (no lots, no purchase date) → proposal still generated but tax shown as "unknown — import tradebook to see", and ranked below otherwise-equal proposals.

### 5.5 Buy legs — where the money goes

From the latest screening that has rows (`findScreeningDates()` walk-back, Gotcha 20):
1. Filter: composite ≥ `buy-min-composite` (default 70 — the high-conviction tier, Gotcha 26); financial quality ∈ {HIGH_QUALITY, DECENT}; liquidity tier ≠ THIN and circuit days < 3; no forensic flags; DCF ≠ EXTREMELY_EXPENSIVE; **not currently held** (by ISIN); not SECTOR_REVERSAL-sourced (Gotcha 25).
2. Rank: composite, then under-discovery score (prefer under-radar names), then capital-efficiency verdict.
3. **Diversification check** using the risk module (§7): if the sold holding's sector is *over* target weight, prefer buy legs from sectors under target; never propose a buy leg that would push any single-stock weight above the §5 cap. Reason line states it ("also reduces IT concentration from 31% to 26%").
4. Take `buy-legs-per-sale` (default 3); split the sale proceeds equally unless a leg's addition would breach a cap (then re-split).
5. Each buy leg carries: composite, grade, verdict, capital-efficiency verdict, under-radar tag, liquidity tier, "days to build ₹X" (F7), and the one-line bullish factor.

If fewer than 2 legs qualify, the proposal is still emitted with status `NO_DESTINATION` — *"nothing in today's candidate pool clears the quality bar; hold the cash or wait for the next screening"*. Never pad with weaker names.

### 5.6 Proposal DTO and persistence

`RecyclingProposal` reuses `RebalanceDto.Trade` for legs (same fields the rebalancing plan already renders) plus a wrapper:

```
RecyclingProposal(
  generatedOn, status (PROPOSED | WAIT | NO_DESTINATION),
  sell: Trade + weaknessScore, weaknessReasons[], taxHorizon (LTCG/STCG/MIXED/UNKNOWN),
        ltcgQty, stcgQty, estimatedTaxInr, daysUntilNextLtcg,
  buys: List<Trade + composite, grade, underRadar, liquidityTier, whyThisOne>,
  expectedUpliftPp, portfolioEffect (sector weights before/after, HHI before/after),
  notes)
```

Table `recycling_proposals`: one row per (sell symbol, generated_on) with legs as JSON, plus `status`, `weakness_score`, `expected_uplift_pp`, `estimated_tax_inr`, `acted_on` (nullable date, set when the investor records the sale via tax-lot capture — auto-detected when a SELL trade for that symbol appears in `tax_lot_sale` within 30 days).

### 5.7 Measurement — is recycling adding value?

New `RecommendationEntity.Source.CAPITAL_RECYCLING`:
- Each **buy leg** is recorded as a recommendation with `score = the candidate's composite` (so `CAPITAL_RECYCLING`'s IC stays comparable with every other source — R-8) and the uplift in its own column `expected_uplift_pp`; the existing outcome scheduler (15:22) measures it at 30/90/180/365d for free.
- The **sell leg** is recorded in a new column `recommendation.direction` (`BUY` default, `SELL`), and `RecommendationAccuracyService` gains a `recyclingAlpha(horizon)` = mean(buy-leg return) − mean(sell-leg return) per proposal. That single number, in the weekly accuracy email, is the answer to "was the app right to tell me to move the money".
- Minimum 20 proposals with matured 90d outcomes before the figure is shown (else "collecting evidence — N of 20"). **At a handful of proposals a month that is over a year away (R-10)** — the email states the expected date so the counter is not mistaken for a near-term readout.

---

## 6. Surfaces

### 6.1 Holdings email — Action Items (email 1 of 2)

New section **"Core Holdings & Capital Recycling"** inserted after `buildActionRequiredSection` and before Thesis Drift, in [HoldingsReportService.java](src/main/java/com/example/trading/holdings/HoldingsReportService.java) (`buildCoreAndRecyclingSection`). Layout:

1. *What this means* box (three sentences: what CORE means, what a recycling proposal is, that nothing is executed).
2. **Core holdings** table: symbol · tier badge · durability (with coverage, e.g. "72 · 3/5 measured", or "not yet measured" below 3 components) · years held · key strength (top gate/durability driver) · watch note. Sorted by durability. Footer, mode-aware: observation — *"N technical exit alerts fired on core holdings today (X, Y); suppression would have withheld them"*; suppression — *"N technical exit alerts withheld on core holdings today (X, Y)"*.
3. **Demotions / promotions since last week** (only when non-empty, leads the section when a demotion exists).
4. **Capital recycling — top 3 proposals** *(Phase B — absent until it ships)*: for each, a compact card — SELL X (qty, ₹, LTCG/STCG, est. tax, weakness N with its top 3 reasons) → BUY Y / Z / W (₹ each, composite/grade, one-line why) · expected uplift vs tax + friction · portfolio effect. `WAIT` shows the LTCG date; `NOT_WORTH_IT` shows its arithmetic; `NO_DESTINATION` shows the sentence from §5.5.
5. **Unclassified holdings** one-liner naming each and the missing input, so the reader knows why a stock is absent from both lists. At launch this line will carry roughly 15 of 33 holdings (§4.4) — the "What this means" box says why, and that the number falls as history is imported.

Existing sections adjust in Phase A: "Exit Required" excludes CORE (§4.5); the "Book Profit" table excludes CORE and carries the B-056 caveat for the rest. In Phase B the Book Profit table is **replaced** by the recycling cards for non-CORE holdings (the LTCG gating logic moves into §5.4, so nothing is lost) — CLAUDE.md §2a is updated at that point.

### 6.2 Weekly holdings email

Full tables: every holding with tier, durability, weakness (non-CORE), and all proposals (not just top 3), plus the week's tier changes.

### 6.3 REST (SPEC §16)

| Endpoint | Cost | Purpose |
|---|---|---|
| `GET /api/portfolio/core-holdings` | DB-only | Latest classification per holding, effective + provisional tier, gates, durability, coverage |
| `GET /api/portfolio/core-holdings/history?symbol=…&days=180` | DB-only | Tier/durability time series for the dashboard |
| `POST /api/portfolio/core-holdings/override` | DB write | `{symbol, override: FORCE_CORE\|FORCE_SATELLITE\|null, note}` |
| `GET /api/portfolio/recycling` | DB-only | Latest proposals (all statuses) |
| `POST /api/portfolio/recycling/run` | compute (~20 s, no email) | Recompute proposals now; guarded outside 14:55–15:30 like `process-queue` (B-049 pattern) |
| `POST /api/portfolio/core-holdings/classify` | compute (~15 s Kite for D5) | Recompute classification now; same guard |

`symbol` is a query parameter throughout (SPEC §27.3).

### 6.4 Dashboard (SPEC §27, phase C) — classifier half shipped 2026-08-27

- Holdings page: tier badge column (CORE green / CORE_WATCH amber / SATELLITE grey / UNCLASSIFIED striped "not measured"), durability as a bar with coverage tooltip; the existing recommendation cell shows `HOLD_CORE` per the overlay.
- Stock page: "Core status" card with gates as a checklist (PASS / FAIL / not measured), durability components, and the tier history sparkline.
- New **Recycling** card on the landing page: top proposal headline + count, linking to a proposals table. All from the two DB-only GETs; the two POSTs are button-only with cost notes, added to `SLOW_BUT_SAFE` only after the Gotcha 17 side-effect check (they send no email).
- Freshness strip gains `holding_classification` and `recycling_proposals` keys (SPEC §27.7).

---

## 7. Configuration (`application.yml` — every key present, B-019)

```yaml
trading:
  portfolio:
    core:
      enabled: true
      suppress-technical-exits: false     # R-1: observation mode first; flip after one quarter of evidence
      min-measured-gates: 5               # of 7 with REAL evidence; a no-data pass does not count (R-4)
      hysteresis-runs: 2                  # weekly anchors for promotion / soft demotion; critical demotion is immediate (R-6)
      durability-min-components: 3        # below this durabilityScore is null, not shown
      roce-core-threshold-percent: 15
      financial-roe-threshold-percent: 14
      financial-roa-threshold-percent: 1.0
      consistency-min: 60
      drawdown-threshold-percent: 15      # D5: what counts as a "hiccup"
      closed-episode-min-age-months: 18   # D5: only episodes that closed at least this long ago are scored (R-3)
      fast-recovery-months: 12            # D5: +5 if the high was regained within this
      slow-recovery-months: 24            # D5: +3 if regained within this
      price-history-years: 5
    recycling:
      enabled: false                      # deferred — see §13 Phase B evidence gate (R-2)
      buy-min-composite: 70
      buy-legs-per-sale: 3
      wait-for-ltcg-days: 60
      override-wait-weakness: 70
      min-weakness-to-propose: 40
      max-proposals-in-email: 3
      dead-weight-months: 24
      ltcg-rate-percent: 12.5             # FY2026-27; update with the Finance Act
      ltcg-exemption-inr: 125000
      stcg-rate-percent: 20
      friction-bps: 60                    # STT + brokerage + spread, round trip (R-2 gate)
      band-min-sample: 15                 # matured outcomes per band before medianReturnByBand returns a value
```

`core.enabled` defaults true because classification is a *report* feature; `suppress-technical-exits` is the one behavioural switch and defaults **off** (R-1). `recycling.enabled` defaults **false** until the §13 Phase-B evidence gate is met.

---

## 8. Persistence & migrations

- New tables (Hibernate `ddl-auto=update` creates them; all numeric columns wrapper types): `holding_classification`, `recycling_proposals`.
- `holding_conviction`: add nullable `core_override` (String) and `core_override_note`, `core_override_at`.
- `recommendations`: add nullable `direction` (String, default null = BUY) — **explicit `ALTER TABLE … ADD COLUMN` is not needed, but `RecommendationEntity.Source` gains `CAPITAL_RECYCLING`**; every `switch` over `Source` must be audited (the accuracy service, report service, dashboard DTO) — an unhandled enum value in a switch expression is a compile error, in a switch statement a silent skip. Add a test that iterates `Source.values()` through the accuracy summary.
- No `NOT NULL` changes anywhere → no `SchemaMigrationRunner` entry (B-026 rule satisfied by construction; re-check at implementation).

---

## 9. Edge cases the design must handle explicitly

| Case | Behaviour |
|---|---|
| Holding bought < 12 months ago | Eligible for CORE (business quality is not about *your* holding period) but D5 needs ≥ 3 years of *price* history — that exists for the stock regardless of when it was bought |
| Holding currently in a deep drawdown | Tier unaffected (price is not a gate); D5 excludes the open episode and the coverage text describes it ("currently 22% below its high, 7 months in; not scored") |
| Financials (banks, NBFCs) | G1 uses ROE/ROA; D1 uses ROA; D3 D/E skipped (NA_FINANCIAL); coverage states it |
| Insurers | Fundamentals feed is empty (Gotcha: insurers report under IRDAI) → UNCLASSIFIED with that reason |
| PSU / commodity cyclicals | Through-cycle caveat: D1 counts years ≥ 15% so a cyclical scores lower — the report glosses that cyclicals rarely qualify as CORE by this definition, by design |
| `BSE:` holdings | ISIN join; if no ISIN on the score row either → UNCLASSIFIED "cross-exchange history unavailable" |
| Stock recently split / bonus | D5 uses Kite's adjusted candles; share-count checks reuse `isBonusOrSplit()` (F5) |
| Holding not in screening universe | UNCLASSIFIED; the email suggests adding it (universe expansion manual endpoint) |
| A CORE holding hits a 52-week high / target | Observation mode: the NEAR_RESISTANCE alert still fires and is listed in the footer; suppression mode: withheld and named. Target-hit alert informational in both; recycling never proposes it |
| Both flags off (`core.enabled=false`) | No classification rows written, no email section, exit behaviour exactly as today — the kill switch |
| A SATELLITE with a turnaround flag | W9 subtracts; if weakness still ≥ threshold the proposal shows the conflict ("weak today, but inflecting — consider waiting one quarter") |
| Both `core_override` and a forensic flag | Override wins for tier; the forensic section still shows the flag in red — overrides hide nothing |
| Kite/NSE outage on classification day | Rows written with `missingInputs`; effective tier unchanged (hysteresis); email says "classification partially measured today" |

---

## 10. Tests (add to `src/test/java`, all pure — no Spring context)

- `CoreHoldingGatesTest` — each gate pass/fail/unmeasured; tier resolution at 4, 5, 7 measured gates; soft-signal → CORE_WATCH; financials path; every gate with null input yields UNMEASURED not FAIL; **a `PASS_NO_DATA` gate does not count toward the quorum** (G1+G2+G3 measured, G4/G6/G7 no-data → UNCLASSIFIED) (R-4).
- `DurabilityScoreTest` — each component with fixture FY series; renormalisation with 3, 4, 5 components; null when < 3; **D5 on a synthetic path with two closed episodes and one open drawdown scores only the closed two, and the same path with the open drawdown deepened scores identically** (R-3).
- `CoreHysteresisTest` — promotion and soft demotion flip only after 2 weekly anchors; a single bad day does not change the effective tier; **a forensic flag / HIGH_RISK / BROKEN demotes the same day**; re-promotion afterwards needs two anchors; override precedence (R-6).
- `CoreOverlayTest` — with `suppress-technical-exits=false` (default): all alerts still fire, the observation line lists them, `HOLD_CORE` label shown, output otherwise byte-identical to today's (snapshot), and `sentAlertsToday` **holds their keys** — the alerts were genuinely sent, so suppressing their dedup would re-send the 10:00 alert at 12:00 and 14:00; with `true`: the four technical alerts absent but named in the footer, DEEP_LOSS re-titled, stored `recommendation` untouched, and `sentAlertsToday` **holds no key** for the holding (R-5, R-13). The second case is unsatisfiable until `evaluateHolding(holding, recordDedup=false)` exists, which is the point of building it in Phase A.
- `WeaknessScoreTest` — component boundaries, W9 subtraction, null-safety (skip and name), ≥ 4-measured rule.
- `RecyclingUpliftGateTest` — `NOT_WORTH_IT` when uplift ≤ tax + friction; boundary at equality; `medianReturnByBand` returns null below `band-min-sample`; a null band never yields a `PROPOSED` status (R-2, R-7).
- `RecyclingTaxGateTest` — LTCG-first quantity, WAIT within 60 days, override at weakness 70, forensic cut-loss precedence, UNKNOWN lots ranked last, exemption arithmetic.
- `RecyclingBuyLegsTest` — filters (held-by-ISIN exclusion, THIN, forensic, EXTREMELY_EXPENSIVE, sector-reversal source), diversification preference, cap breach re-split, NO_DESTINATION with < 2 legs, never pads.
- `RecyclingMeasurementTest` — buy/sell legs recorded with direction; `recyclingAlpha` arithmetic; hidden until 20 matured proposals.
- `SourceEnumCoverageTest` — every `RecommendationEntity.Source` value survives the accuracy summary (guards the enum addition).

Target: ~45 new tests. **Change a gate or weight → a test must fail**, same rule as the scoring engine.

---

## 11. Documentation landing (SPEC §20)

| Artifact | Change |
|---|---|
| SPEC.md | New **§35 Core Holdings** (definitions, gates, durability, overlay, hysteresis, override) and **§36 Capital Recycling** (weakness, uplift, tax gate, buy legs, measurement). §9.4 amended (Book Profit table replaced by recycling cards for non-CORE). §14 report list, §15 (no new cron — note the 10:30 job's extra step), §16 endpoints, §17 tables, §23 new source, §27.2 dashboard scope. |
| CLAUDE.md | New section "Core Holdings & Capital Recycling"; §2a Tax-Aware Profit Booking amended (Phase A: CORE excluded + B-056 caveat; Phase B: rewritten); Gotcha: *"`suppress-technical-exits` ships off and changes only what emails say, never what is stored — ML labels are unaffected; the flip is an evidence decision, not a preference"*; Gotcha: *"UNCLASSIFIED is never SATELLITE — a holding you couldn't measure is never proposed for sale"*; Gotcha: *"a `PASS_NO_DATA` gate is a pass, not evidence — it never counts toward the CORE quorum"*; package structure counts; endpoint list. |
| BUGS.md | **B-056 (P1) filed 2026-08-26** for the `BOOK_PROFIT` momentum rule (R-12). Phase A's B-056 caveat and Phase B's replacement are the two halves of its fix; the entry moves to Resolved only when Phase B ships. |

---

## 12. What this does *not* do (explicit non-goals)

- Does not forecast prices or set price targets for CORE holdings (SPEC §25.3 non-goal stands).
- Does not size positions or run a Kelly-style optimiser — the accumulation planner (§8) and rebalancing engine (§10) remain the sizing tools; recycling only pairs a sale with destinations.
- Does not use AI to pick tiers or proposals; the AI portfolio commentary may narrate them.
- Does not change the multibagger composite, weights, or any scoring-engine test.
- Does not auto-mark `acted_on` from broker positions beyond the tax-lot capture match (no new Kite polling).

---

## 13. Phases, effort, order

| Phase | Deliverable | Effort | Notes |
|---|---|---|---|
| **A0** | ✅ **COMPLETE** — (b) shipped 2026-08-26, (a) 2026-08-27 (automated, see SPEC §32.5 — no CSV needed): (a) screener.in history imported for all 33 active holdings via `POST /api/fundamentals/import-history`; (b) the **7 holdings with no `multibagger_scores` row** brought into the screening universe — gates read that table, so the import alone leaves them UNCLASSIFIED. Record the after-figures beside the §4.4 baseline | ½ day, no code | Without (a) durability is a price signal wearing a fundamentals label (R-3); without (b) ~21% of the portfolio is unclassifiable no matter how much history is imported. Blocks A, does not trail it |
| **A** | ✅ **SHIPPED 2026-08-26.** Classifier + snapshot table + asymmetric hysteresis + the **`evaluateHolding(holding, recordDedup)` overload** (R-13) + overlay in **observation mode** + email "Core holdings" half with the observation line + B-056 caveat on the Book Profit table + `GET/POST core-holdings` + 43 tests (257 total, green) + SPEC §35 + CLAUDE.md gotchas 68–71 | 3 days | Ships the answer to question 1. `suppress-technical-exits=false`. The overload was built even though observation mode does not use it — the flip at the end of A-obs cannot happen without it. Two deliberate deviations from this document, both recorded in SPEC §35: config lives at **`portfolio.core.*`**, not `trading.portfolio.core.*`, to match its siblings `portfolio.tax` / `portfolio.risk`; and the **earnings trend-break soft signal is not implemented** (not persisted on `multibagger_scores`, and a live NSE call per holding is not affordable inside the 10:30 job — the daily email carries its own trend-break section) |
| **A-obs** | One quarter of observation (target review: **2026-11-30**) | — | Decide the flag on what the observed alerts would have saved or cost |
| **B** | *(deferred)* `medianReturnByBand` + uplift-vs-tax gate + weakness score + tax gate + buy legs + proposals table + email "Recycling" half + `GET/POST recycling` + tests | 3–4 days | **Evidence gate**: a second non-overlapping return window supports the band ordering. Next evidence review **2026-11-30** (same date as A-obs; the Sep–Nov window is the second window). Until then §5 stays unbuilt — not half-built |
| **C** (classifier half) | ✅ **SHIPPED 2026-08-27.** Dashboard: core section on the portfolio screen (mode banner, protected list with durability + coverage, explicit not-measured list), Tier column on the holdings table, "Core status" three-state gate checklist on the stock screen, `holdingClassification` freshness key | ½ day | Both surfaces read the DB-only GETs and were hand-verified against Gotcha 39 (a page-load `get()` is ungated). `scripts/check_js_syntax.py` run after every edit — a syntax error in a shared module blanks every page and every server-side check still passes (Gotcha 41) |
| **C** (recycling half) | *(deferred with B)* Measurement — `Source.CAPITAL_RECYCLING`, `recommendation.direction`, recycling alpha in the weekly accuracy email | 1 day | Nothing to measure until proposals exist. Building the enum and column now would add an unhandled `Source` value to every switch for a quarter, for no readout |
| **Review** | Budgeted review pass per phase (R-11) — F1–F8 shipped on a similar estimate and produced 20 review defects | 1 day per phase | Not optional |

Suggested order inside A: gates (with the quorum rule) → durability (D1–D4 from the imported history, D5 closed-episodes) → persistence/asymmetric hysteresis → the dedup-free `evaluateHolding` overload → overlay (observation mode, with the suppression path wired but off) → email.

---

## 14. Acceptance criteria

1. Every current holding receives a tier; every UNCLASSIFIED row names its missing inputs; no holding is SATELLITE by default. Read against the §4.4 baseline: ~15 of 33 UNCLASSIFIED at launch is the expected outcome, not a failure — the criterion is that each one says *why*.
2. With `suppress-technical-exits=false` (the shipped default), a CORE holding with RSI 78 and price below EMA50 still produces its RSI/EMA alerts, they dedup exactly as today (keys present in `sentAlertsToday`), the Action Items email carries the observation line naming them, and output is otherwise byte-identical to today's (snapshot test). With the flag true, no such alert is sent, the footer still names it, a `HOLD_CORE` display appears, and `sentAlertsToday` holds no key for the holding (R-5, R-13).
3. Promotion and soft demotion require two consecutive weekly anchors; a one-day data gap never changes the effective tier (test + one observed live week). A forensic flag, auditor problem, HIGH_RISK or BROKEN demotes the same day.
3a. Durability is shown only for holdings with ≥ 3 measured components; the A0 coverage figure is recorded in §4.4 before Phase A is called done.
3b. No recycling proposal reaches `PROPOSED` status unless its uplift exceeds tax plus friction; `NOT_WORTH_IT` proposals show their arithmetic.
4. No recycling proposal exists for a CORE, CORE_WATCH, or UNCLASSIFIED holding; every proposal carries LTCG/STCG split and estimated tax or the explicit "unknown — import tradebook" note.
5. No buy leg is a held ISIN, THIN, forensic-flagged, EXTREMELY_EXPENSIVE, or SECTOR_REVERSAL-sourced; proposals with < 2 qualifying legs are `NO_DESTINATION`, never padded.
6. Buy and sell legs appear in `recommendations` with `source=CAPITAL_RECYCLING` and `direction`; the outcome scheduler measures them without code changes; the weekly accuracy email shows "collecting evidence — N of 20" until matured.
7. The Action Items email renders the new section with a "What this means" box. Phase A: the Book Profit table excludes CORE and carries the B-056 caveat. Phase B: the table is gone for non-CORE (replaced by recycling cards).
8. All new GET endpoints complete in < 300 ms from DB; both POSTs refuse in the 14:55–15:30 window with a 409 and reason.
9. `mvn test` green with ≥ 45 new tests; SPEC §35/§36 and CLAUDE.md land in the same commit as each phase.

---

## 15. Review Findings & Required Changes *(added 2026-08-26)*

Design review of §0–§14 against the codebase, the measured evidence in SPEC §25 / Gotchas 26–27, and the F1–F8 review lessons (EARLY_DISCOVERY_IMPLEMENTATION_PLAN.md §15).

**ID convention.** Findings are `R-n`, not `B-nnn`. Nothing here is implemented, so nothing here is a defect in shipped code and nothing belongs in BUGS.md — with one exception, R-12, which is a live defect the plan itself identified. Resolve each `R-n` by amending the section it names *before* writing the code it describes.

**Verdict.** The diagnosis in §0 is correct and verified (§15.3). The scaffolding — null discipline, tax gate, `NO_DESTINATION`, measurement from day one, the override audit line — is the strongest of any plan in this repo. **Six changes are required before Phase A** (§15.1, R-1–R-6, two of them P0), and **Module B should not ship on the current evidence base** (R-2). All six, plus R-7–R-13, are now folded into §0–§14 — §15 is kept as the record of what changed and why, not as an outstanding to-do list.

### 15.1 Must change before Phase A ships (priority order)

| # | Finding | What is wrong | The change |
|---|---|---|---|
| 1 | **R-1** P0 | **§7 defaults `suppress-technical-exits: true`.** Suppressing an exit alert is the *removal* of a risk control, and CLAUDE.md Gotcha 42 fixes the asymmetry in the other direction: new signals ship shadowed, risk controls ship armed, "because a bonus being wrong costs a missed opportunity and a risk control being wrong costs capital". The gates deciding the suppression (G1 capital efficiency, G2 financial quality, G3 consistency) rest on the metrics whose measured IC panel runs +0.042 down to −0.009, with ROE, ROA and cash conversion **negative** (Gotcha 27). §1 principle 6 says "measure before trust"; §7 then ships the one behavioural switch armed. With suppression on, the counterfactual is never observed, so the feature can never be judged. | Default **`false`**. Classify, persist, and have the Action Items email print one line — *"N technical exit alerts fired on core holdings today (X, Y, Z); this feature would have suppressed them"*. Flip to `true` after a quarter of that line, judged against what those alerts would have saved or cost. Amend §4.5, §7, §14 criterion 2. |
| 2 | **R-2** P0 | **Module B spends real money on evidence this repo has labelled insufficient for the purpose.** §5.3 builds `expectedUpliftPp` on the Gotcha 26 band table, whose own text reads *"one 4-month window, enough to rank on, not enough to discard on"* — and recycling discards on it, paying tax to do so. Worse, **nothing nets tax against uplift**: §5.3 emits an uplift, §5.4 emits a tax, and no rule compares them. Moving a long-held winner from band 50–64 to 80+ is ~6pp of *median 4-month* uplift; 12.5% LTCG on a doubled position is ~6pp of the position **permanently**, before STT, brokerage and spread. The engine will emit confidently-formatted, value-destroying proposals. The composite underneath is itself +1.54pp/month at **t≈1.24, p≈0.28** over ~5 independent periods. | (a) Add a hard gate: a proposal whose `expectedUplift` does not exceed `estimatedTax + frictionBps` is emitted with status **`NOT_WORTH_IT`** and its arithmetic shown, never as a live proposal. (b) State the comparison over a matched horizon — a 4-month median uplift against a one-off permanent tax is not a like-for-like subtraction; say so in the gloss. (c) **Hold Phase B** until the band table has a second independent window behind it. Phase A answers question 1 and stands alone. |
| 3 | **R-3** P1 | **Durability would be a price signal at launch, and D5 runs backwards against the feature's thesis.** Measured 2026-08-26: `annual_fundamentals` holds 378 symbols with **exactly 1 FY each**; of **33 active holdings, 0 have ≥4 FYs**. So D1–D4 are null for *every* holding — not "most" (§4.4) — leaving D2 (a quarterly consistency score) and D5 (price). And D5's "count drawdowns ≥15% that later recovered to a new high" can only be satisfied by a stock near its high: a holding *currently* in a drawdown scores lower and an unrecovered 30% fall costs −10. §4.3 calls D5 "what makes ignoring short-term hiccups evidential"; it does the opposite — it downgrades a core holding precisely when the tier is supposed to hold. | Make the screener.in import a **hard gate on Phase A shipping**, not the §13 suggestion. Restate D5 as recovery depth and speed measured **only over drawdowns that closed ≥18 months ago**, so the current one cannot score, or drop it and reweight D1–D4. Amend §4.3, §4.4, §13. |
| 4 | **R-4** P1 | **Three of seven gates pass on absent data and still count toward the quorum.** G4 null → pass, G6 null → pass, G7 absent → pass. With `min-measured-gates: 5`, a holding with no forensic history, no insider filings and no conviction record collects three free passes and reaches CORE on G1+G2 alone — a far weaker bar than §4.2 reads, and a direct breach of §1 principle 4. The plan never says whether a null-pass gate is "measured", and that ambiguity is exactly where the bug lands. | State it explicitly: **a gate that passes on absent data does not count toward `min-measured-gates`.** G4's reasoning ("absence of evidence is not a flag") is right for pass/fail and wrong for the coverage count. Amend §4.2 and add the case to `CoreHoldingGatesTest`. |
| 5 | **R-5** P1 | **The overlay walks into Gotcha 19.** `ExitTimingAlertService.evaluateHolding()` mutates `sentAlertsToday` inline at **six** call sites, and the `recordDedup=false` path CLAUDE.md instructs every read-only caller to use **does not exist** (verified: no such parameter in the file). If §4.5 filters alerts after evaluation, each suppressed alert still burns the day's dedup slot — so flipping R-1's flag off, or a demotion later that day, yields silence instead of the alert, exactly the failure that ran unnoticed "for weeks" the first time. | ~~Suppress by filtering the holdings list before `evaluateHolding` runs, or~~ build the dedup-free overload first and route through it — **the filter option was withdrawn by R-13** (it cannot name the alerts it suppresses). Named in §4.5, pinned mode-specifically in `CoreOverlayTest`, and the overload is a Phase A deliverable (§13). |
| 6 | **R-6** P1 | **Hysteresis is armed in the wrong direction for risk.** §4.6 applies 2 weekly anchors to both directions while §4.5 lists "any forensic flag" and "financial quality → HIGH_RISK" among demotion triggers — so a holding that trips a forensic flag keeps its technical exits suppressed for up to **14 days**. Gotcha 45 points the other way: an auditor problem *escalates* rather than deducts, because every quality metric is computed from the audited numbers. | Split the rule: hysteresis on promotion and on soft demotions; **immediate** demotion on a critical trigger (forensic flag, auditor problem, financial quality HIGH_RISK, decay BROKEN). Amend §4.5, §4.6, §14 criterion 3. |

### 15.2 Should change (second-order)

**Module B mechanics**
- **R-7** — §5.3 says the band table is read "from `RecommendationAccuracyService` at run time". **No such method exists** (`bandReturn` / `returnByBand` / `medianReturnBy` appear nowhere in `src/main/java`); the Gotcha 26 figures were produced ad hoc. §Prerequisites' "none new" is wrong by one API. Build it as part of Phase B and have it return `null` below a minimum sample per band rather than a median of three picks.
- **R-8** — §5.7 sets buy-leg `score = expectedUpliftPp`. That makes `CAPITAL_RECYCLING`'s Information Coefficient non-comparable with every other source, because `score` stops being a quality score. Store the composite in `score` and the uplift in its own column.
- **R-10** — "20 proposals with matured 90d outcomes" is correct discipline, but at a handful of proposals a month it is **over a year** before `recyclingAlpha` reads anything. Say so in §5.7 so the number is not mistaken for a near-term readout.

**Module A mechanics**
- **R-13** *(added on the follow-up pass)* — R-5's fix offered two options: filter CORE holdings out before `evaluateHolding`, **or** build a dedup-free overload. Only the second works once suppression is on, because the footer must *name* the alerts it suppressed and naming them means evaluating them. Filtering yields a tier, not a list. Observation mode is unaffected (nothing is suppressed, dedup behaves as today), but the overload is a Phase A deliverable rather than a fallback, since the flip at the end of the observation quarter depends on it. Amended in §4.5 and §10; the §10 assertion also had to become mode-specific — "in both modes `sentAlertsToday` holds no key" was wrong for observation mode, where the alert really is sent and must dedup or it repeats three times a day.
- **R-9** — §4.7's "~35 holdings → ~12 s" assumes one Kite call per holding; SPEC §30.6 measured **two** (instrument-token resolution plus candles). ~25 s. Immaterial in itself, but the identical optimism is what produced B-049.
- **R-11** — §13's 8 days covers two tables, six endpoints, email surgery, a dashboard pass and ~45 tests. F1–F8 shipped on a similar estimate and produced 20 review defects. The phasing is sound; budget the review pass explicitly rather than the build.

**Filing**
- **R-12** — §11 says the `BOOK_PROFIT` rule should be logged as **B-056 when this ships**. It is a live defect **today**: `HoldingsAnalysisService.determineRecommendation()` returns `BOOK_PROFIT` on `pnlPercent > 50 && rsi > 70`, so every daily email is already telling a long-term investor to trim their best compounders while they run. File it now, independently of whether this plan is built — the ledger records defects when found, not when fixed (BUGS.md working rule 1).

### 15.3 Verified as accurate — do not re-check

Spot-checked against the code on 2026-08-26, all confirmed:

- `HoldingsScheduler.analyzeHoldings()` is `0 30 10 * * MON-FRI` — §4.7's 10:30 anchor is right (CLAUDE.md's "3:15 PM holdings analysis" is the *report*, a different job).
- `determineRecommendation()` returns `SELL` on `overallScore < 40` or `BEARISH && rsi > 70`, and `BOOK_PROFIT` on `pnlPercent > 50 && rsi > 70` — §0's critique is exact, including that `BOOK_PROFIT` sits *below* the SELL/BUY branches.
- Every `multibagger_scores` column §3 cites exists: `forensic_flags`, `turnaround_verdict`, `capex_verdict`, `liquidity_tier`, `under_discovery_score`.
- `holding_conviction` exists and holds 46 rows, so G5/G7 and the override have something to read.
- 33 active holdings.

### 15.4 Recommended order of work

1. ~~**R-12** — file B-056 now.~~ **Done 2026-08-26** (BUGS.md Open table).
2. ~~**R-1, R-3–R-11, R-13** — amend §0–§14.~~ **Done 2026-08-26**: every finding is reflected in the body; §15.1 R-5's filter option is struck through in favour of R-13.
3. ~~**A0 — the hard gate, both parts** (§13).~~ **Done.** (b) 2026-08-26 — the seven unscreened holdings added to `SCREENING_UNIVERSE`. (a) 2026-08-27 — **automated rather than manual**: NSE still serves its annual filing archive behind the endpoint B-017 had written off, so `POST /api/fundamentals/backfill-holdings` replaced the screener.in CSV step and wrote 150 financial years in 33 seconds (SPEC §32.5). After-figures recorded in §4.4. Nine recent listings and one insurer have no archive to fetch; the CSV import stays as the fallback for those.
4. ~~**Phase A** with **R-1** applied.~~ **Shipped 2026-08-26** — classifier, snapshot table, asymmetric hysteresis, the dedup-free `evaluateHolding` overload, overlay in observation mode, `GET/POST core-holdings`, the email section with the observation line and the B-056 caveat, SPEC §35, CLAUDE.md gotchas 68–71, 43 tests. **The budgeted review pass (R-11) ran 2026-08-26/27** and produced two entries — **B-057** (a seeded conviction horizon read as the investor's stated intent, demoting 14 holdings on a number nobody chose) and **B-058** (the observation trail overwriting itself after a restart, biasing the suppression evidence toward "few alerts fired"). Both fixed and pinned. That is roughly the rate §15.2 predicted, and the reason the pass was budgeted rather than assumed.
5. **A-obs — one quarter of observation** (review **2026-11-30**). Judge R-1's flag on the persisted `observed_alerts`: what the alerts that fired on CORE holdings would have saved or cost.
6. **R-2 decision point (same date).** Phase B only once `medianReturnByBand` exists, the uplift-vs-tax gate is designed in, *and* the Sep–Nov window independently supports the band ordering. Until then §5 stays unbuilt — not half-built. `recycling.enabled` stays `false`.
7. **R-7, R-8, R-10** land with Phase B; **R-9, R-11** are already in §4.7 / §13.

**Acceptance for closing §15**: *(document half — met 2026-08-26)* R-1, R-3–R-6 and R-13 amended in §4, §7, §10, §13, §14; R-12 filed as B-056. *(work half — open)* A0 complete for all 33 holdings with the after-figures recorded in §4.4; `mvn test` green with the gate-quorum, closed-episode D5, same-day-demotion and mode-specific overlay-dedup cases pinned; and on 2026-11-30 either R-2 satisfied (gate built, second window measured) or Phase B re-deferred here with the next review date — a deferral without a date is how "deferred" becomes "forgotten".
