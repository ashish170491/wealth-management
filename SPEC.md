# Functional Specification — Long-Term Portfolio Investment System

> **Mission pivot (2026-04-18):** This application was rebuilt from an intraday trading system into a long-term portfolio investment platform for the Indian equity market. The disabled intraday trading code was **removed from the repo on 2026-05-24** (see §3.1); the trading-horizon features that survived it were removed on 2026-09-03 (§39). **Mission re-stated 2026-09-05** (§1, §19, §40): the objective is to identify businesses that can compound earnings and capital over **5–10+ years**. See [CLAUDE.md](CLAUDE.md) for implementation detail; this document defines **what** the system must do.

**Status legend**
- ✅ **Active** — implemented and running
- ✅ **Active (MVP)** — first-pass implementation in place; known caveats listed in that section
- 🔨 **Planned** — target state, not yet built (roadmap)
- 💤 **Disabled** — code exists but switched off via config; kept for reference/future re-use

---

## 1. Purpose

Help a single Indian retail investor **identify high-quality listed businesses that can compound earnings and capital over 5–10+ years**, and hold them well.

This is a **research platform, not a trading application**. It judges a business on seven pillars; each row names the section that measures it today, and §40 carries the gap analysis:

| Pillar | Question the system asks | Measured by |
|---|---|---|
| **Business quality** | Does it earn real cash on a sound balance sheet? | Financial Quality (§12.5), forensic red flags (§32.4), auditor escalation (§32.4) |
| **Growth** | Is earnings growth durable and self-funded, not lumpy? | Earnings analysis (§12.4), wealth signals (§12.7), capex cycle (§31), turnaround detector (§32.3) |
| **Cash generation** | Does profit convert to operating cash, and is capital deployed at a high return? | Capital efficiency: ROCE / ROE / ROA / real cash conversion (§12.8) |
| **Management quality** | Do the people running it act like owners and say what they then do? | Insider Pulse (§28), concall guidance ledger (§34) |
| **Competitive advantage** | Can it hold margins and returns through a cycle? | Partly, since 2026-09-06: the **compounding lens** (§41) joins return on capital, cash conversion, leverage, steadiness and margin trend into one gate. It reads a **single year**, so it measures current quality, not a moat proven over a cycle |
| **Valuation** | What growth does today's price already assume? | Reverse-DCF expectation gap + PE-vs-sector (§12.5) |
| **Risk** | What would break the thesis, and how concentrated is the portfolio? | Forensics (§32), core-holding gates (§35), diversification (§7), thesis drift (§6.2) |

The second half of the purpose is **disciplined ownership** of what the research finds: goal-based allocation (§5), conviction and thesis tracking (§6), diversification (§7), staged accumulation (§8), tax-aware lots (§9), rebalancing proposals (§10), dividends (§11), and continuous monitoring for thesis breaks (§6.2, §32.4, §35).

**What it is not.** It places no trades. It builds no buy/sell signal engines. It does not try to predict short-term prices, and no signal is accepted on a short-term result (§19). The surviving timing verdicts — "still a good time to buy more?" (§6.5), the screener's timing column (§12.11), the entry ladder (§12.12) and the watchlist verdict (§37.3) — are **accumulation timing for already-vetted names**, answered by one shared rule table so they can never disagree, and they are frozen: new surfaces defer to that table, and no new vocabulary is added. The intraday machinery was deleted on 2026-05-24 (§3.1) and the trading-horizon features that outlived it on 2026-09-03 (§39).

---

## 2. Users & Personas

| Persona | Needs from the system |
|---|---|
| **Long-term investor (self)** | Find businesses worth owning for a decade, get conviction-quality research on the seven pillars (§1), build and maintain a goal-aligned portfolio, catch thesis breaks early, stay diversified, time accumulation sensibly, minimize tax drag |
| **System maintainer** | Debug via correlation-ID logs, tune thresholds in `application.yml`, add new data sources, deepen the pillar measurements (§40) and evolve the 7 portfolio modules (§5–§11) — always through the evidence gates in §38 |

Single broker account (Zerodha Kite), single email inbox for reports, single PostgreSQL database. Not multi-tenant.

---

## 3. Operating Modes

### 3.1 Trading Loop — Removed (2026-05-24)
The intraday trading machinery (TradingScheduler 1-minute loop, all strategies, signal-confirmation/regime filters, order execution, position monitoring/sync, risk management, ML trap detection, intraday backtesting, and the dynamic intraday watchlist) was **deleted** from the repo — it had been disabled via `trading.loop.enabled=false` since the 2026-04-18 pivot and is no longer reachable. `MarketRegimeDetector`, `risk/RiskConfig`, `strategy/StrategyUtils`, `ml/XGBoostModelParser`, and `options/` were retained (still used by active long-term features). **2026-08-28**: the remaining ML — `ml/` (holdings price/recommendation/risk/anomaly models, training-data collector, daily retrain) and `scanner/sector/ml/` — was deleted as well. Rationale: nothing measured it, the recommendation model was a binary classifier re-expanded into six classes and its last live output was SELL/STRONG_SELL for every holding it did not discard, the random train/test split leaked adjacent-day rows, and the 30-day target does not serve a 1–3-year mandate. Any future ML must be measured through §23/§25.5 on `recommendation_outcomes` in shadow mode before it can steer a report. The `holdings.ml_*`, `has_anomaly` and `blended_score` columns are orphaned (never written or read). `trade`/`position`/`signal` tables are retained but never written. The `trading.loop.enabled` flag is now a no-op. See CLAUDE.md "Legacy intraday code REMOVED (2026-05-24)".

### 3.2 Paper Simulator Mode — Retained
| Config | Effect |
|---|---|
| `trading.execution.paper-trading-mode: true` (default) | ✅ When the trading loop is re-enabled for experimentation, orders are simulated with `PAPER-` tradeId prefix. Used as a **sandbox for testing accumulation strategies** before committing real capital. |
| `trading.execution.paper-trading-mode: false` | Real orders via Kite (currently discouraged; portfolio additions are meant to use manual/SIP flow, not intraday order placement). |

### 3.3 Data Ingestion — Always On
Market data, NSE fundamentals (quarterly and annual XBRL), FII/DII, insider disclosures, and news fetches run on their own schedules (§15). The research and portfolio modules consume these feeds.

### 3.4 Scheduler Timing — Market Hours Only

The application runs **only during Indian market hours**: **09:15–15:30 IST, Monday–Friday** — plus one sanctioned off-market window: **Saturday 07:45–10:30 IST** (added 2026-08-25), during which the weekly multibagger screening (08:00) and its report email (09:00) run. The Windows scheduled tasks start the app 07:50 and stop it 10:35 on Saturday for exactly this window (see `setup-scheduled-tasks.ps1`). It is stopped at all other off-market times (rest of the weekend, pre-open, post-close, holidays).

**Rule:** Every new `@Scheduled` job MUST fire inside the market-hours window. Any schedule outside `09:15–15:30 IST Mon–Fri` is invalid and will never run in production.

Concretely:
- `cron` hour field must be within `9–15`.
- If the hour is `9`, the minute must be `≥ 15`.
- If the hour is `15`, the minute must be `≤ 30` (leave a 2–5 min buffer before close for jobs that touch the market snapshot — aim for `≤ 28`).
- `day-of-week` must be `MON-FRI` (never `SAT`/`SUN`).
- `zone = "Asia/Kolkata"` is mandatory (already required by §18).

**Pattern for daily / weekly cadence within the constraint:**
| Cadence intent | Acceptable slot (examples) |
|---|---|
| Pre-market data prep | *Not allowed* — move to first market-hour slot (e.g., 09:16) |
| Morning briefing / open-of-day | 09:20–09:45 MON-FRI |
| Mid-day refresh | 11:00–13:30 MON-FRI |
| Pre-close recap / EOD summary | 15:20–15:28 MON-FRI |
| Weekly recap | Use **Friday 15:20–15:28**, not Saturday |

**The only off-window jobs are the two Saturday multibagger jobs** (08:00 screening, 09:00 report — restored to Saturday on 2026-08-25 after a spell at Friday 15:17/15:23, which saturated the packed 15:15–15:30 close ramp). They are explicitly sanctioned, documented in §15, and **not** a precedent for new work. There is **no pre-market job**: the Kite token is obtained on `ApplicationReadyEvent` at startup, and `TokenManagementService.dailyTokenRefresh` at **09:45 MON-FRI** is an in-window recovery for a failed startup login (earlier editions of this section, §39.5, CLAUDE.md and BUGS.md said 08:35 — that was never the cron; corrected 2026-09-05, B-076). The two Saturday multibagger jobs guard with `MarketHoursService.isSaturdayScreeningWindow()` (07:45–10:30 SAT) instead of `isMarketOpen()`; no other **scheduler** may use that guard without a SPEC exemption. A read-only consumer is a different thing and is allowed: `UniverseController` reads the window to decide whether a *manual* long Kite sweep may proceed (§30.6, B-049). The rule exists to stop work being **scheduled** outside market hours, not to stop anyone asking what time it is.

Violations are treated as bugs. Review-gate: any new `@Scheduled` annotation added outside this window must be rejected in review unless explicitly exempted in SPEC.md.

**Defense-in-depth — the runtime guard.** Cron expressions like `0 */15 9-15 * * MON-FRI` look correct but fire at boundary slots **outside** the 09:15–15:30 window (e.g., 09:00 and 15:45 in this case, because the cron's hour-range `9-15` includes minutes 00-59 of every hour from 09 to 15). The cron alone is not a safe enforcer. Every `@Scheduled` method MUST also call `MarketHoursService.isMarketOpen()` as its first guard:

```java
@Scheduled(cron = "...", zone = "Asia/Kolkata")
public void scheduledThing() {
    if (!marketHoursService.isMarketOpen()) {
        return; // Outside 09:15-15:30 IST window — SPEC §3.4.
    }
    // ... actual work
}
```

`MarketHoursService.isMarketOpen()` ([MarketHoursService.java](src/main/java/com/example/trading/scheduler/MarketHoursService.java)) checks weekday + 09:15–15:30 IST in one call. The guard is cheap (no I/O) and centralises the rule — if §3.4 ever changes, only this one method needs editing. Never inline the time-of-day check in a scheduler body; always delegate.

**Codebase audit on 2026-05-10/11** added this guard to **every** `@Scheduled` method in the codebase (52 at the time; **29** remain after the 2026-05-24, 2026-08-28 and 2026-09-03 removals and the 2026-09-12 deletion of the market-impact daily summary — see §15. The macro ingest (§48.9) adds none: it runs only when the investor presses the button). The one method without it is `TokenManagementService.dailyTokenRefresh` (09:45 MON-FRI): it fires inside the window and is the recovery path for the startup login, so guarding it would only stop it doing the one thing it exists for. It is not a precedent for new schedulers. (`KiteInstrumentsService.scheduledRefresh`, the other pre-market exception, was deleted on 2026-09-03.)

**Scheduler executor — multi-threaded (B-014, 2026-05-23).** `@Scheduled` jobs run on a 4-thread `TaskScheduler` ([SchedulingConfig.java](src/main/java/com/example/trading/config/SchedulingConfig.java)), not Spring's single-threaded default. This matters *because of* the `isMarketOpen()` guard above: with one thread, a long-running scan (multibagger/holdings, 14+ min) in the packed 15:00–15:30 window serialised the later jobs behind it and pushed them past 15:30, where the guard then silently aborted them — on Fri 2026-05-22 the 15:22 recommendation-outcome update, 15:25 accuracy email, and 15:28 tax-lot capture were all starved. The pool is intentionally small (4) so several Kite-heavy scans never run in parallel and trip the broker 429 limit. **Residual hazard**: starvation is far less likely but not impossible if all 4 threads are simultaneously occupied by long scans — keep heavy scans staggered (they already are), and treat the 15:20–15:30 slot as reserved for lightweight jobs.

**Why the guard matters even though the server only runs inside market hours**: (a) the server's shutdown ramp can take 10–15 minutes, during which schedulers can still fire (the May 8 log showed activity to 15:45); (b) the server's startup may complete a minute or two before 09:15; (c) if someone ever changes a cron by mistake during a refactor, the guard catches it instead of silently producing bad data on the few minutes the server happens to be up early. Defense in depth.

---

## 4. Core Workflows

The system operates on **weekly and daily cadences**, not per-minute. Typical rhythms:

| Cadence | Workflow |
|---|---|
| **Daily** | Morning briefing (portfolio snapshot, drift alerts); holdings health re-scored; insider disclosures captured; daily screening; recommendation outcomes measured; news impact check |
| **Weekly (Sat)** | Multibagger screening over full universe (with coverage vector and shadow composites, §38); universe expansion pass (§30); deep research on top 3 candidates; Friday accuracy report (§23) |
| **Monthly** | Tax-lot review, dividend reinvestment suggestions, sector-allocation report, portfolio review email |
| **Quarterly (results season)** | Annual-history backfill (§32.5), forensic and turnaround re-screen (§32), guidance-ledger resolution (§34.3), weight review through the promotion gate (§38.10), rebalance proposal (§10) |
| **On demand** | Deep research on the seven pillars (§12.1), quantitative discovery scan (§12.3), fundamentals backfill (§32.5), concall analysis (§34), walk-forward review (§38.9), accumulation plan for a target stock (§8) |

---

## 5. Portfolio Goals & Allocation ✅ Active (MVP)

### 5.1 Purpose
Let the investor declare target portfolio weights, and continuously measure drift from those targets.

### 5.2 Required Behavior
- Persist one or more **portfolio profiles** (e.g., "Retirement 2045", "Growth 2030") with target weights by:
  - **Asset class** (equity / cash / gold — for future extension)
  - **Market cap** (large / mid / small) — initial focus
  - **Sector** (banking, IT, pharma, metals, auto, etc.)
  - **Individual stock** (explicit target weight, optional)
- Compute **actual weights** each morning from holdings × current prices.
- Raise a **drift alert** when actual vs target deviates beyond configurable tolerance (default 5 percentage points absolute, or 25% relative).
- Render drift in morning briefing as green/yellow/red bars per bucket.
- Provide **rebalance suggestions** (see [§10 Rebalancing Engine](#10-rebalancing-engine-🔨-planned)) when drift alerts fire.

### 5.3 Out of Scope
- Multi-account aggregation.
- Non-equity assets (fixed income, ETFs, mutual funds) — may be added later.

---

## 6. Conviction & Thesis Tracking ✅ Active (MVP)

### 6.1 Purpose
Capture *why* each stock was bought and detect when the thesis breaks before it shows up in P&L.

### 6.2 Required Behavior
- When a holding is added (manually or via Kite sync), prompt for / persist:
  - **Thesis** — free text (e.g., "PSU-bank re-rating story, 2-year horizon")
  - **Conviction score** — 1–10 at purchase
  - **Holding horizon** — months/years
  - **Invalidation triggers** — free-text conditions that would break the thesis
- Daily: compute **multibagger composite drift** (today's score − purchase score). Flag:
  - Drift ≥ −15 pts: **thesis-intact** (green)
  - Drift −15 to −25: **under-review** (yellow) — include in weekly report
  - Drift < −25: **thesis-broken** (red) — include in next morning briefing
- **Cross-exchange history resolution** ✅ *(2026-08-28)* — a holding's exchange prefix is not stable (B-061) while screening history is keyed on the NSE symbol, so score history is resolved through `SymbolVariants` (exact → NSE → BSE → bare). Which symbol answered is recorded on the alert as `resolvedSymbol`. Before this, 21 of 33 holdings reported `NO_DATA` for drift while months of scores sat on file under their NSE symbol.
- **Dilution is measured after corporate actions are removed** ✅ *(2026-08-28, B-066)* — a bonus or split multiplies the share count by an exact simple ratio and dilutes nobody, so it is divided out before the growth rate is taken; an `INFO` flag records what was removed. Equity evidence (did new money arrive?) stays authoritative where four unbroken years exist. Ratio tolerance 0.05% — tight enough that a capital raise landing near a simple ratio is still read as a raise.
- **Score-decay alerts** ✅ — sibling of purchase-drift for long-dated holdings whose purchase score is no longer the right baseline. Compares today's composite against a **30-day-ago** and **60-day-ago** score (nearest-match within ±10 days of the window). **The delta is relative to the universe (B-064, 2026-08-28)**: the stock's raw move minus the paired median move of every symbol screened on both dates, so a scoring-engine change or a broad market fall (which moves every score) is never read as one stock's thesis breaking. The universe shift is *not measured* (null, never zero) when fewer than 30 symbols pair up; the raw delta is then used and the reason says so. Grade steps are read on the shift-adjusted 30d-ago score. Both raw and relative deltas are shown. Verdicts (on the relative delta):
  - **Intact** — 30d delta ≥ −5
  - **Watch** — 30d delta ∈ [−10, −5] OR grade dropped 1 level
  - **Decaying** — 30d delta ≤ −10 OR grade dropped ≥ 2 levels
  - **Broken** — 30d delta ≤ −20 OR 60d delta ≤ −25
  - **Stale** — newest score > 14 days old (likely removed from screening universe)
  - **No Data** — holding outside screening universe (no history to read)

  Implemented by [HoldingsDecayService](src/main/java/com/example/trading/holdings/HoldingsDecayService.java); surfaced in the daily + weekly holdings report as a "Thesis Drift Alerts" section (SPEC §21 beginner-friendly gloss), and via `GET /api/trading/holdings/decay`.
- Weekly: re-run deep research (§12.1) on all yellow/red holdings and present to investor for re-underwriting.
- Log every thesis decision with timestamp for audit.

### 6.3 Depends On
- Multibagger screener scoring history (✅ already persisted in `multibagger_scores`).
- Deep research service (✅ `/api/research/{symbol}`).

### 6.4 Out of Scope (Phase 2)
- Persistent "alerted" state to avoid re-alerting the same holding in consecutive reports.
- Morning-briefing integration of the decay list (currently only in holdings report).
- Score-decay for stocks **not** currently held (that's the existing `GET /api/multibagger/trend/{symbol}` use case).

### 6.5 "Still good to buy more?" on holdings ✅ *(new 2026-08-28)*

The same question §37.3 answers for a watched stock, asked of one already owned: **should I add to
this position today?** It deliberately reuses `BuyTimingVerdict` — the identical rule table, the
identical six verdicts (`BUY_NOW` / `ACCUMULATE` / `WAIT_FOR_PULLBACK` / `HOLD_OFF` / `AVOID` /
`NOT_MEASURED`) — because two engines answering one question in one vocabulary is a defect
regardless of which is right (§12.11, B-062).

**Inputs** come from the holding's own row (daily RSI-14, EMA-50, trend, recommendation, from the
10:30/15:15 analysis) plus the latest `multibagger_scores` row for quality, red flags, liquidity
and the DCF verdict, plus the §6.2 decay verdict. "Return since you added it" is the position's
**P&L%** — the move from the price actually paid.

**Four rules that must hold:**
1. **A tracked stock defers to the watchlist**, matched across exchange prefixes. Its verdict is
   returned verbatim with `source = WATCHLIST`, exactly as the screener does.
2. **Quality and score history resolve across exchange prefixes** (`SymbolVariants`). Screening
   runs on NSE symbols and most holdings are BSE-prefixed; without this the column reads "never
   screened" for two-thirds of the portfolio. The symbol actually used is returned as
   `qualityFrom` and shown in the UI when it differs, so a reading can be traced to its source.
3. **A `SELL` / `STRONG_SELL` recommendation is a first-class "no"** (rule 7), never translated
   into a nearest buy-signal and never falling through to `ACCUMULATE`.
4. **Unmeasured is never zero**: a never-screened stock has a null quality score and says so; a
   `0.0` price is "no price" (Gotcha 22) and cannot fire the stretch rule.

**Endpoints**: `GET /api/trading/holdings/buy-timing` (DB-only, safe on page load) returns the
verdict per holding keyed by the holding's symbol. `POST /api/trading/holdings/refresh-one?symbol=`
re-analyses one holding live (~2 paced Kite calls) and is **refused from 14:55 with a 409 carrying
its reason** (B-049), so a per-row refresh can never starve the 15:05–15:28 report jobs.

**Not persisted.** The verdict is computed on read; a stored copy can disagree with the row it
describes after the next analysis.

---

## 7. Diversification Risk Metrics ✅ Active (MVP)

### 6.6 The Signal column defers to the buy-timing verdict ✅ *(new 2026-09-02)*

The portfolio table shows **Signal** and **Still a good time to buy?** side by side, and a reader
takes them as one answer. They were computed by different engines and contradicted each other on
**9 of 32 holdings** — BEL read `BUY` beside `AVOID`, the AVOID being a forensic red flag on cash
conversion (B-069). This is §12.11's "one question, one rule table" rule applied to a surface it
had never covered.

The stored signal comes from `HoldingsAnalysisService.determineRecommendation(overallScore, trend,
rsi, pnlPercent)` — a **momentum rule with no sight of fundamentals, forensic flags or financial
quality**, inherited from the intraday era (B-056). It will call a stock with a red flag on its
books a BUY because the chart looks fine.

`SignalReconciliation` therefore lets a quality problem veto a buy, **at display time**:

| Verdict | Stored signal | Shown |
|---|---|---|
| AVOID | BUY / STRONG_BUY | **HOLD**, with the verdict's reason |
| HOLD_OFF | STRONG_BUY | **BUY** — a caution must not sit beside the strongest endorsement |
| anything else | anything | unchanged |

**Three properties are deliberate.** It can only ever **lower** a signal — a risk control that can
raise a recommendation is not a risk control (Gotcha 42). It **never yields SELL**: "do not add" is
not "get out", exiting costs tax (§9) and §35 exists precisely to stop the investor being shaken out
of good businesses. And an **unmeasured verdict changes nothing** — silence is not a negative
(Gotcha 21), or a data gap would become investment advice.

`holdings.recommendation` is read, never rewritten, so ML labels, stored history and the raw signal
are unaffected — the same contract the core overlay keeps (Gotcha 69). Any adjustment is shown
("adjusted for quality") and carries its reason: a silent downgrade is its own kind of confusion.

**Centralised, not patched per screen (2026-09-02).** Fixing each surface separately is what
produced this bug three times (B-062, B-065, B-069), so the answers are attached to **the holding
itself on every read path** by `HoldingsViewDecorator`: `displaySignal`, `signalNote`,
`buyTimingVerdict`, `buyTimingReason` and the shared entry ladder are `@Transient` fields computed
on read. A screen can then only render what it is given, and a *new* screen inherits the right
answers without knowing they exist. All five holdings read endpoints (`/holdings`, `/{symbol}`,
`/exit-candidates`, `/accumulate-candidates`, `/by-score`) go through it. Verified live: **0
mismatches across 32 holdings** between the list endpoint and the single-stock endpoint.

Two duplicates were removed at the same time: the stock page rendered `recommendation` raw (so BEL
read BUY there while the portfolio said HOLD — an inconsistency the first fix *created*), and the
portfolio computed its own entry price as `suggestedEntry ?? support1` instead of the shared ladder.
A holding already stores `support1` (its 20-day low), `atr14` and `ema50`, so the shared rule needed
no new data and no Kite call.

**Former remaining duplicate — resolved 2026-09-03:** the Market page used to show sector-reversal
picks with `EarlyUpsideScanner`'s own entry (`currentPrice` or EMA-20), a different rule again. It
was tolerated while SECTOR_REVERSAL was non-actionable and suppressed; it went with the engine in
§39. Every surviving surface now defers to `BuyTimingVerdict` (CLAUDE.md Gotcha 85), and §19
forbids adding another.

**The underlying rule is still wrong and is still open as B-056.** This reconciles the two columns;
it does not fix the momentum engine that made the claim. The tell that something was wrong with it
is worth remembering: across 32 holdings it produced **zero SELL** and 23 BUY/STRONG_BUY.

### 7.1 Purpose
Surface concentration risk that P&L alone cannot show.

### 7.2 Required Metrics
- **Herfindahl-Hirschman Index (HHI)** across stocks — flag if > 2 500 (concentrated) or > 4 000 (dangerous).
- **Sector concentration** — max sector weight; alert if any sector > 30%.
- **Market-cap mix** — large/mid/small weights vs target profile.
- **Correlation clusters** — group holdings into clusters using 90-day daily-return correlation ≥ 0.7; highlight clusters with combined weight > 20%.
- **Single-stock cap** — alert if any holding > 15% of total portfolio value.

### 7.3 Delivery
- Computed daily.
- Embedded in morning briefing and holdings report.
- Dedicated endpoint `GET /api/portfolio/risk` returning all metrics as JSON.

---

## 8. Accumulation Planner ✅ Active (MVP)

### 8.1 Purpose
Disciplined, staged accumulation of conviction-grade stocks — so a position is built on a plan the investor wrote down, not on a price the market happened to print.

### 8.2 Required Behavior
- Given a target stock and target allocation (₹ amount or % of portfolio), plan an **N-tranche accumulation**:
  - **SIP mode** — N equal tranches on fixed dates (weekly/monthly).
  - **Price-ladder mode** — N tranches at descending price levels. The §12.12 entry ladder (three ATR-spaced rungs, deepest snapping to the 50-day average when nearer) is the shared rule for what those levels are.
  - **Signal-gated mode — refused on create, retained only so legacy rows read back** *(B-077, closed 2026-09-05)*. `MODE_SIGNAL_GATED` and `triggerSignal` still exist on `accumulation_plan` / `accumulation_tranche`, but the named triggers they expected (BREAKOUT, SECTOR_REVERSAL, market-direction confirmation) were deleted in §39, and `AccumulationReminderService` evaluated only SIP and price-ladder tranches — so a plan in this mode was accepted, persisted, and then **never reminded**, for its whole life. `createPlan` now rejects it with a **422 carrying its reason** (a bare status would say nothing: `server.error.include-message` is `never`), and the reminder service logs a WARN rather than skipping a row inserted directly. A tranche fired by a signal is a buy signal wearing a plan's clothes, which §19 and §20 rule 10 bar; the mode is not to be revived. Verified against the live database at the time of the fix: **zero** plans used it. Pinned by `AccumulationModeTest`.
- The app sends a **reminder email** (10:00 MON-FRI, §14) when a SIP date arrives or a ladder rung is reached. It places no order — SPEC §19; the investor acts.
- Track plan progress: tranches filled, avg cost, remaining tranches.
- Pause plan automatically if conviction score drops into red zone (§6).

### 8.3 Depends On
- The shared entry ladder (§12.12) and buy-timing verdict (§37.3, §6.5) for level selection.
- `MarketDataService` for the daily price check.
- Conviction tracking (§6) for the red-zone pause.

---

## 9. Tax-Lot Tracking ✅ Active (MVP)

### 9.1 Purpose
Minimize tax drag on the portfolio — India has 365-day STCG/LTCG cutoff and different rates.

### 9.2 Required Behavior
- Persist every purchase as a **tax lot**: symbol, quantity, price, date, broker charges.
- On any sale, apply **FIFO matching** by default; allow override to specific-lot or HIFO (highest-in-first-out).
- Per holding, show per-lot:
  - Days held (and days to LTCG cutoff)
  - STCG liability if sold today (15%)
  - LTCG liability if sold today (12.5% above ₹1.25 L/yr threshold under FY26 rules)
- **Harvest suggestions** at quarter-end and year-end: lots in loss that can offset gains; lots approaching LTCG cutoff to hold vs sell.
- Dedicated endpoint `GET /api/portfolio/tax-lots/{symbol}`.

### 9.3 Data Sources
- **Live auto-capture (going forward)** ✅ — `TaxLotAutoCaptureScheduler` runs at **15:28 IST MON-FRI** (within the SPEC §3.4 market window, since Kite Connect's `/trades` endpoint is current-day only and the app shuts down at 15:35). Calls `BrokerClient.getTodayTrades()`, filters NSE/BSE equity, creates a `tax_lot` per BUY and a FIFO-matched `tax_lot_sale` per SELL. Idempotent on broker `trade_id` so manual re-triggers and crash-recovery re-runs never duplicate rows.
- **One-shot CSV backfill (for existing holdings)** ✅ — Zerodha Console → Reports → Tradebook → `POST /api/portfolio/tax-lots/import-zerodha-csv` (multipart `file=` or raw body). Replays trades chronologically with FIFO matching, deduped on `trade_id`. Sells whose originating buy predates the CSV window are logged and skipped rather than aborting the whole import.
- **Manual entry fallback** — `POST /api/portfolio/tax-lots` for off-broker lots (gifts, ESOPs, legacy holdings).

### 9.4 Tax-Aware Holdings Report Integration
The daily holdings report is split into two emails (sent back-to-back at 15:18 IST by `HoldingsReportService.sendDailyHoldingsReport`): **Email 1 — "Action Items"** carries the decisions (Book Profit, Hold for LTCG, Exit Required, thesis-drift / balance-sheet / trend-break attention items, next-steps), and **Email 2 — "Analysis"** carries the deep-dive context (valuation, support/resistance, AI, multibagger, full holdings table). The tax-aware sections below all live in Email 1. The split exists because a single combined email had grown to 14+ sections and was too long to scan in one sitting.

The tax-aware sections gate partial-exit suggestions on holding period to avoid realizing STCG unnecessarily:

- **"Book Profit" section** — only lists holdings with at least one open lot held > 365 days. The "Qty to Book" column shows the LTCG-eligible quantity. Mixed-horizon stocks (some lots LTCG, some STCG) show both bucket sizes plus the days remaining for the short-term portion.
- **"Hold for LTCG" section** — short-term winners (entirely STCG lots) are surfaced separately with `daysUntilNextLtcg` and an explicit "do not book — wait for LTCG" instruction. Stocks within 30 days of the cutoff get an "Almost LTCG — hold" emphasis.
- **"Exit Required" section** — sell-signal exits (recommendation = `SELL` / `STRONG_SELL`) fire **regardless of holding period** to cap further loss; each row is tagged with a horizon badge (LTCG / STCG (Nd) / MIXED) so the user knows the tax character of the realized loss/gain. The section header explains that realized STCL offsets future STCG/LTCG within the same financial year, so cutting a broken thesis early still has tax value.
- **Resolution order** for classification: (1) **ISIN match** when the holding has an ISIN — finds open lots with the same ISIN regardless of exchange prefix, so a `BSE:WAAREEENER` holding correctly aggregates against `NSE:WAAREEENER` lots. (2) **Symbol match** as a fallback for legacy lots without ISIN. (3) `HoldingsEntity.purchaseDate` approximation when no lots exist. (4) UNKNOWN when none of the above resolves.
- Implemented via `TaxLotService.classifyForExit(symbol, isin, qty, purchaseDate)` returning a `TaxAwareExitClassification` DTO. ISIN is populated on tax lots from the Zerodha tradebook CSV's `isin` column; re-uploading a previously imported CSV backfills ISIN onto existing lots without duplicating rows.

---

## 10. Rebalancing Engine ✅ Active (MVP)

### 10.1 Purpose
Periodically move actual weights back toward targets, trade-off minimization, tax-efficient.

### 10.2 Required Behavior
- Triggered on demand or when drift (§5) exceeds threshold.
- Produce a **trade list**: buy/sell quantities per symbol to restore targets.
- Optimizer objectives (in priority order):
  1. Reach target weights within ±1 pp each.
  2. Minimize realized STCG (prefer selling LTCG-eligible lots via §9 suggestions).
  3. Minimize broker/STT/slippage cost.
- Output: suggested trades + estimated cost + estimated tax impact.
- **Never auto-executes** — generates a plan for human review and manual placement (or paper simulation).
- Schedule: quarterly by default (first Saturday of Jan/Apr/Jul/Oct), and ad-hoc via `/api/portfolio/rebalance`.

---

## 11. Dividend Tracking ✅ Active (MVP)

### 11.1 Purpose
Account for cash dividends as real portfolio income and guide reinvestment.

### 11.2 Required Behavior
- Daily fetch of announced dividends for held symbols (source: NSE corporate-actions API via existing `NseDataService`).
- Store: ex-date, record-date, amount/share, type (interim/final/special).
- Track accrued and received dividends per holding and per tax year.
- **Reinvestment suggestion**: when cash balance (manual entry or Kite balance sync) exceeds ₹X, suggest adding to the most under-weighted conviction-intact holding per §5 and §6.
- Include YTD dividend income in monthly portfolio report.

---

## 12. Research & Discovery (Active, Repositioned)

All features below already exist. They are kept and repositioned as **pre-purchase due diligence** tools, not trade triggers. Each one measures one or more of the seven pillars in §1; §40 tabulates which, and which pillars are still unmeasured.

### 12.1 Deep Stock Research ✅
`GET /api/research/{symbol}` — a 20-dimension research note (15 base dimensions: 10 internal + 5 external; plus Financial Quality §12.5, Intrinsic Valuation §12.5, Analyst Signal §24, Wealth Signals §12.7 and Capital Efficiency §12.8) producing a 12-section analyst write-up (verdict, thesis, earnings, technicals, fundamentals, ownership, peers, news, institutional activity, risks, action plan, multibagger potential). Used when evaluating a new candidate or re-underwriting a red-zone holding (§6). Sends an email — never call it from a page load (§27.4).

### 12.2 AI Discovery ✅
`GET /api/research/discover` — AI-driven thematic discovery: emerging themes, overlooked gems, contrarian ideas, portfolio gaps, universe expansion.

### 12.3 Quantitative Discovery ✅
`GET /api/research/discover/quantitative` — data-only (no AI) 5-dimension composite with calculated entry/stop/target levels. Preferred for systematic candidate generation because it is reproducible.

### 12.4 Earnings & Shareholding ✅
`GET /api/research/earnings/{symbol}`, `GET /api/research/shareholding/{symbol}` — quarterly trend with growth verdict (`STRONG_GROWTH` … `DECLINING`) and insider signal (`STRONG_BUY` … `STRONG_SELL`).

### 12.5 Multibagger Screener ✅
**7-dimension composite scoring** (eight until Sector Tailwind was removed on 2026-09-03, §39.2) + bonus adjustments for earnings growth and insider activity. Weekly full-universe scan Saturday 08:00 IST, report emailed Saturday 09:00 (restored to Saturday 2026-08-25 via the §3.4 Saturday window — the Windows tasks now start the app Saturday morning; this also unclogged the Friday 15:15–15:30 close ramp the scan used to saturate).

| # | Dimension | Weight | Source |
|---|---|---|---|
| 1 | Technical Momentum | 18% | Kite daily candles — EMA trend, RSI |
| 2 | Volume Accumulation | 12% | Kite daily candles — volume surge patterns |
| 3 | Relative Strength | 12% | Kite daily candles — outperformance vs Nifty 50 |
| 4 | Price Structure | 12% | Kite daily candles — 52w proximity, higher-lows, base building |
| 5 | Valuation | 13% | Blended 50/50 PE-deviation + reverse-DCF expectation gap (see §12.5 caveat). PE / market cap are **computed from fundamentals**, not the NSE valuation API — NSE bot-walled `/api/quote-equity` (B-018) |
| 6 | Institutional Interest | 10% | Per-stock FII + DII holding trend from NSE shareholding history (revised 2026-04-19 — the prior sector-flow implementation scored ~all stocks at 40 due to sparse bulk-deal data + sector-taxonomy mismatch, producing zero-variance dimension; see §23.2 per-dimension IC) |
| 7 | Sector Tailwind | 8% | Sector rotation + reversal signals |
| 8 | **Financial Quality** ✅ *(new)* | **15%** | NSE quarterly results + shareholding — interest coverage, cash-flow proxy, margin, pledge |

Plus post-composite bonuses: earnings-growth verdict (up to ±8), insider activity (up to ±5), analyst signal (up to ±5, §24), and **wealth signals (−8..+10, §12.7)**. A `HIGH_RISK` financial-quality verdict **hard-caps the composite at 54** regardless of other signals, so structurally fragile stocks can't cross the 65-point recommendation threshold.

> **Cap ordering is load-bearing (B-020).** The HIGH_RISK cap must be applied **after every bonus**, as the last step of `screenStock`. Applied earlier it is not a cap at all — the six bonus stages are worth up to +47 combined, and on the 2026-08-20 run four capped stocks climbed back out (MPHASIS 62, INDIGO 60 with ROE −34%, SHOPERSTOP 59, PVRINOX 55). Any new bonus must be inserted *before* the cap.

> **Unmeasured dimensions are excluded, not neutralised (2026-08-22).** A dimension that cannot be computed for a stock returns `null`, and the composite is a weighted mean over the dimensions that *did* return a score, renormalised by the weight actually present. Previously a missing dimension scored a neutral 50 — which sits *above* what a measured-but-weak dimension scores (Financial Quality bottoms at 24, Institutional Interest at 10), so the engine rewarded stocks it knew nothing about over stocks it had analysed and found wanting. The four fundamental dimensions (Valuation, Institutional Interest, Sector Tailwind, Financial Quality) are nullable; the four price dimensions are always computable from Kite candles.

> **Candidates are gated on percentile AND absolute score (2026-08-22).** A stock must be in the top `trading.multibagger.candidate-top-percentile`% of the screening run *and* clear `min-score-for-candidate`. Rank is what makes the gate robust: every distortion found in the 2026-08 audit was a **uniform** shift — a 1.15 weight sum scaled every score 15% (B-019), a constant Valuation dimension added a fixed offset to all (B-018), the bonus stack added a median +9.8 to 85% of stocks — and all three passed straight through an absolute-only gate, turning a 39%-pass screen into a 69%-pass screen. None of them move a percentile. The absolute floor is retained so a uniformly bad market cannot promote the best of a bad universe. The same gate governs §23 recommendation capture, so the accuracy-tracked pick set stays a consistent slice.

> **The eight weights must sum to 1.0, and all eight must be set in application.yml (B-019).** They are `@ConfigurationProperties`, so a dimension omitted from the yml silently keeps its Java default rather than being disabled. From the 2026-04-19 rebalance until 2026-08-22 the yml listed only the original seven (summing to 1.00) and never added `financial-quality-weight`, so the effective sum was **1.15** and every composite was inflated 15% — a "60" candidate threshold really meant 52, and ~70% of the universe passed it. `MultibaggerConfig.validateWeights()` now fails startup if the sum drifts from 1.0.

> **Growth and ownership are persisted, not discarded (2026-09-09, B-098).** `earnings_growth_verdict`, `yoy_revenue_growth`, `yoy_profit_growth`, `promoter_holding_pct`, `promoter_holding_change_pct`, `fii_holding_pct` and `dii_holding_pct` are written on every row from the earnings and shareholding fetches the bonuses already consume — zero extra NSE calls, seven nullable wrappers, `ENSURE_COLUMNS` entries, and coverage rows `PromoterHolding` / `YoyProfitGrowth` / `EarningsGrowthVerdict` (§38.2). They contribute **nothing new** to the composite (the bonuses that read them are unchanged) and exist so the screener can show the Growth pillar and promoter skin-in-the-game. Rows before 2026-09-09 read "not measured" for all seven. Sector is the screener's own table backed by `universe-sectors.csv` (NSE's index classification, refined so a bank reads Banking), resolved through `SectorMapping` on read; a stock nobody has classified is **null**, never "Other". The 30-day score change on the screener is read against the universe's median move through the same `medianShift` the holdings decay verdict uses (B-064), and is null — never zero — below 30 paired symbols.

**Intrinsic valuation — reverse DCF (sanity check, not price target).** `IntrinsicValuationService` solves the growth rate that would justify the current market cap, given a fixed 10-year DCF with 12% discount, 4% terminal growth, and `FCF proxy = profit + depreciation`. Compares the implied growth against the 2-year profit CAGR to produce an **expectation gap** (implied − historical). Verdicts: `DEEPLY_UNDERVALUED` / `UNDERVALUED` / `FAIRLY_VALUED` / `EXPENSIVE` / `EXTREMELY_EXPENSIVE` / `NOT_APPLICABLE` (loss-making) / `INSUFFICIENT_DATA`. Exposed via `GET /api/research/valuation/{symbol}`; persisted on `multibagger_scores.dcf_*` columns; fed into `StockResearchService` Deep Research as data dimension 17.

**Known caveat:** the 10-year window + 4% terminal growth **systematically under-values long-duration compounders** (IT services, platforms, pharma) — their `EXPENSIVE` / `EXTREMELY_EXPENSIVE` verdict should be read skeptically. Banks, insurers, and commodity cyclicals need residual-income or through-cycle models — this service does not currently distinguish sector, and treats them with the same DCF. Both issues are Phase-2 work.

**Universe coverage** (SPEC §13 below for the tier table):
- `screeningTier` config selects the tier: `LARGE_ONLY` | `LARGE_MID` | `LARGE_MID_SMALL` (default) | `ALL`
- Default `LARGE_MID_SMALL` ≈ 380 stocks: Nifty 50 + Nifty Next 50 + Nifty Midcap 100 + curated Nifty Smallcap list
- `ALL` adds ~60 curated micro-caps (~440 stocks total)

**Tier-aware quality gate** — stricter thresholds for small/micro-cap to keep weekly emails high-signal:
- Small-cap stocks require composite score ≥ 55 (vs default 45 for large/mid)
- Micro-cap stocks require composite score ≥ 60
- Small/micro-cap extra fundamental checks (all must pass):
  - Current price > ₹20 (penny-stock filter)
  - Promoter pledge < 30% (via `NseDataService.fetchShareholdingHistory`)
  - Earnings growth verdict ≠ DECLINING
- All thresholds configurable in `application.yml` under `trading.multibagger.*`

**Weekly email now includes per-tier leaderboard** — Top 5 Small-Cap, Top 5 Mid-Cap, Top 5 Large-Cap — so multibagger candidates from the small/micro tier are visible in one glance.

**On-demand tier scan**: `GET /api/multibagger/screen/tier/{tier}` for ad-hoc scans of any tier.

### 12.6 Peer Comparison ✅
Structured peer metrics (PE, P/B, dividend yield, YoY growth, margins, market cap, RSI) embedded in deep research.

### 12.7 Wealth Signals ✅ *(new 2026-05-23)*
Fundamental quality signals that drive long-term **compounding**, derived entirely from data the app already fetches (no new data source). Computed by `NseDataService.analyzeWealthSignals()`:

| Signal | What it measures | Verdicts |
|---|---|---|
| **Gross margin level + trend** | Profit kept per ₹ of sales before overheads — a pricing-power / moat proxy. COGS = raw material + purchases + inventory change (now captured on `QuarterlyResult.cogs`/`grossMargin`, previously summed away). Null for banks/financials. | EXPANDING / STABLE / CONTRACTING / NA |
| **Earnings-growth consistency** | Volatility of QoQ profit growth + positive-quarter ratio → 0-100 score. Rewards steady compounders over lumpy ones. | VERY_CONSISTENT / CONSISTENT / VARIABLE / ERRATIC / NA |
| **Delivery %** | Share of traded volume taken into demat (genuine accumulation) vs. intraday churn. Latest snapshot only (no trend yet). | STRONG_HANDS / MODERATE / SPECULATIVE / NA |
| **PEG ratio** | PE ÷ profit-growth% (growth-adjusted valuation). Computed in the screener (needs PE). <1 cheap, >2 expensive. | — |

**Integration**: post-composite **Wealth-Signal Bonus** on the Multibagger composite, bounded **−8..+10** (kept a bonus, not a 9th weighted dimension, to avoid re-validating the existing 8-weight blend — can be promoted once per-dimension IC validates it; SPEC §23 "measure first, tune later"). Gross-margin EXPANDING +3 / CONTRACTING −3; consistency ≥70 +3 / <30 −2; delivery STRONG_HANDS +2 / SPECULATIVE −1; PEG <1 +2 / >2 −2.

**Surfaced in**: `multibagger_scores` columns (`gross_margin_percent`, `gross_margin_trend`, `peg_ratio`, `delivery_percent`, `earnings_consistency_score`, all nullable); daily holdings email "Fundamental Wealth Signals" section (beginner-friendly, all-holdings scan); Deep Research data dimension 19.

**Known limitation**: gross margin and PEG are NA for banks/financials (no COGS line); delivery % is a single snapshot, not a trend (a trend would need stored daily snapshots). The balance-sheet wealth metrics (ROCE, ROE, Debt-to-Equity, real cash conversion) are now covered separately in **§12.8** (annual XBRL).

### 12.8 Capital Efficiency ✅ *(new 2026-05-23)*
The balance-sheet ratios that most directly identify long-term compounders, sourced from each company's **latest annual Ind-AS XBRL** filing on NSE — the income-statement-only quarterly feed cannot produce these. Computed by `NseDataService.analyzeCapitalEfficiency(symbol, industryHint)`.

**Data source** (B-017, migrated 2026-05-24): `GET /api/integrated-filing-results?index=equities&symbol=X&period=Quarterly` → pick the latest **March** "Integrated Filing- Financials" record (Consolidated preferred) → fetch its `INTEGRATED_FILING_INDAS`/`_BANKING` XBRL from `nsearchives.nseindia.com` → parse. (The old `corporates-financial-results` endpoint froze at Mar-2024 when NSE migrated to integrated filing ~Jan 2025.) Free, official, no ToS/scraping risk. Cached **7 days**. XBRL uses the SEBI **`in-capmkt`** namespace but identical Ind-AS local element names; the parser is namespace-agnostic. Two taxonomies, detected by filename: **`INDAS`** (non-financials, `Equity`/`Borrowings`/`ProfitLossForPeriod`/`CashFlows…`) and **`BANKING`** (banks, `Capital`+`ReservesAndSurplus`/`ProfitLossForThePeriod`/`Assets`).

**Context-resolution caveat**: the BSE `in-bse-fin` taxonomy crams the current quarter and the full year into contexts whose period `<dates>` can be identical/unreliable. Annual flows are selected by the longest real duration (≥330 days) when dates are trustworthy, else by the BSE `FourD` (year-to-date) context-ID convention; balance-sheet facts use the latest instant context (`OneI`).

| Metric | Formula | Verdict bands |
|---|---|---|
| **ROCE** | (ProfitBeforeTax + FinanceCosts) ÷ (Equity + Total Borrowings) | ≥20 EXCELLENT / ≥15 GOOD / ≥12 AVERAGE / <12 WEAK |
| **ROE** | Net profit ÷ Equity | ≥18 EXCELLENT / ≥15 GOOD / ≥12 AVERAGE / <12 WEAK / <0 LOSS |
| **ROA** | Net profit ÷ Total assets | ≥1.8 EXCELLENT / ≥1.5 GOOD / ≥1.0 AVERAGE / <1.0 WEAK (bank-calibrated; the headline metric for financials) |
| **Debt-to-Equity** | Total Borrowings ÷ Equity | ≤0.3 VERY_LOW / ≤1 MODERATE / ≤2 ELEVATED / >2 HIGH |
| **Cash conversion** | **Real** Operating Cash Flow ÷ Net profit | ≥0.8 STRONG / ≥0.5 ADEQUATE / <0.5 WEAK / <0 NEGATIVE |
| **Dividend payout** | Dividends paid ÷ Net profit | informational |

Overall verdict (net of strengths − red flags): `HIGH_QUALITY_COMPOUNDER` / `SOLID` / `AVERAGE` / `WEAK` / `POOR` / `NA`.

**Integration**: post-composite **Capital-Efficiency Bonus** on the Multibagger composite, bounded **−10..+12** (the widest band of any bonus — these are the strongest fundamental signals). ROCE ≥20 +5 / ≥15 +3 / <10 −3; ROE ≥18 +3 / <8 −2; D/E ≤0.3 +2 / >2 −4; cash-conversion ≥0.8 +2 / <0.5 −2.

**Banks/financials**: ROCE and D/E are **not computed** (left null, verdict `NA_FINANCIAL`) — leverage is structural and ROCE on an equity-only base is misleading. Instead **ROE** and **ROA** (net profit ÷ total assets — the headline metric, ROA ≥1.5 +3 / <0.8 −3 in the bonus) are used. Two detection paths: **banks** parse from the `BANKING_*.xml` taxonomy (`bs.isBanking()` → authoritative); **NBFCs/housing-finance** file under `INDAS_*.xml` and are caught by the industry hint (`bank|financ|nbfc|insur|holding`), which the screener, holdings report, and the `/capital-efficiency` endpoint all supply (the endpoint resolves it via `StockValuationService`). Validated: HDFCBANK ROE 14.1%/ROA 1.59% (bank); BAJFINANCE ROE 18.8%/ROA 3.85% (NBFC, ROCE/D-E suppressed).

**Insurers are not supported — data-availability wall, not a parsing gap.** NSE's `corporates-financial-results` feed returns an empty array for *every* insurer (SBILIFE, HDFCLIFE, ICICIGI, LICI, STARHEALTH…); they report under IRDAI format (solvency ratio, combined ratio, embedded value), which isn't ROCE/ROE/ROA anyway. Insurers are detected by industry and return a clear `NO_DATA` with an insurer-specific reason rather than a generic failure.

**Surfaced in**: `multibagger_scores` columns (`roe_percent`, `roce_percent`, `roa_percent`, `debt_to_equity`, `cash_conversion_ratio`, `capital_efficiency_verdict`, all nullable); daily holdings email "Capital Efficiency (Annual Balance Sheet)" section (beginner-friendly, with an ROA column); Deep Research data dimension 20; `GET /api/research/capital-efficiency/{symbol}`.

**Known limitation**: annual cadence (refreshed once a year per stock — fine for structural metrics). ROE/cash-conversion use total equity/profit (incl. minority interest) for consistency. Validated against RIL FY24: ROE 8.5%, ROCE 10.2%, D/E 0.35, cash-conversion 2.0× — all correct.

### 12.9 Buyability / Liquidity Guard ✅ *(new 2026-08-25)*
An **execution property, not a quality property** — deliberately outside the composite. A micro-cap can be an excellent business and still be impossible for a retail investor to accumulate at sane impact; those are two different facts and conflating them corrupts both.

Computed during screening from candles already fetched, so it costs nothing extra:

| Field | Definition |
|---|---|
| `liquidity_adv_20d` | 20-day average traded value (close x volume), rupees |
| `liquidity_tier` | `LIQUID` (>= Rs 5 cr) / `MODERATE` (Rs 50 L - 5 cr) / `THIN` (< Rs 50 L) / `UNKNOWN` |
| `circuit_days_last_60` | Days in the last 60 with **no intraday range at all** (high == low) — a circuit lock |

**Reported as a number the reader can act on**: *"~N days to build a Rs 1 lakh position"*, assuming no more than 10% participation in daily volume. "THIN" is abstract; "47 days to build a Rs 1 lakh position" is not.

**Null discipline**: liquidity that cannot be measured (short history, no volume data) is `UNKNOWN`, **never `THIN`**. THIN excludes a stock from universe promotion, so letting a measurement gap collapse into THIN would blacklist stocks for having short history rather than for being illiquid. Pinned by `BuyabilityGuardTest`.

**Rules**: THIN stocks are excluded from any future automatic universe promotion and are visually flagged (never hidden) in reports. No composite effect.

### 12.10 Under-Discovery Score ✅ *(new 2026-08-25)*
"Early at low value" made concrete: **small + under-owned + under-followed + quietly accumulating**. The composite already rewards institutional holding that is *rising*; it never rewarded institutional holding that is *low*, which is the precondition for the re-rating that turns a good business into a multibagger.

0-100 from data the screening loop already holds — **zero additional NSE calls**:

| Component | Max | Source |
|---|---|---|
| Low absolute institutional holding (<2% / <5% / <10% FII+DII) | 25 | `fetchShareholdingHistory()` |
| ...and rising **from that low base** (FII up, DII up, both) | 20 | same, QoQ deltas |
| Market-cap tier (micro <= Rs 1,000 cr / small / mid) | 20 | `StockValuationService` |
| Delivery % = `STRONG_HANDS` | 15 | wealth signals (bhavcopy) |
| Volume expanding vs own average | 10 | volume dimension data |
| Media coverage <= 2 articles in 7 days | 10 | `StockNewsService` |

**A lens, not a bonus.** It never enters the composite. Its two largest components are already scored by the Institutional Interest dimension and the small-cap bonus; adding them again would double-count the same facts and silently re-weight the validated 7-dimension blend. It is a **sort key for a report section**, which is what the current evidence supports.

**Gate**: computed only for composite >= 55 and financial quality not `WEAK`/`HIGH_RISK` — under-discovered junk is still junk. Stocks failing the gate score **null, not 0**.

**The low base is a precondition, not a decoration (B-043).** The rising component pays only when combined FII+DII is **below 10%**. Institutions adding to a 35%-owned name is ordinary flow into a stock the market already covers — the opposite of what this lens looks for. Above the ceiling the component is *measured and scores zero*, which is a finding; it is not dropped as unmeasured.

**Both ownership legs or neither.** FII and DII holdings are summed only when both are published. Treating a missing FII figure as 0% let a stock with 1% DII read as "barely institutionally owned" — the strongest finding this lens makes — from a number half of which was never published (§21 rule 7).

**Renormalisation**: the score is computed over whichever components could be measured, exactly as `weightedComposite` handles nullable dimensions. News coverage is passed as null during bulk screening (361 Google-News RSS fetches per run is not a reasonable cost) and is renormalised away rather than guessed at.

**Surfaced in**: `multibagger_scores.under_discovery_score` (nullable Integer); the weekly email's **"Under the Radar"** section (composite >= 65 AND under-discovery >= `under-radar-min-score`, default 60), which carries the Buyability column alongside; `GET /api/multibagger/under-radar`; and as the `Under-Discovery` row in per-dimension IC.

---

### 12.11 "Still good time to buy?" — entry timing on the screener ✅ *(new 2026-08-27)*

A screening row says whether a business is good. It does not say whether **now** is a sensible
moment to buy it, and those are different questions: a great business at a stretched price and a
weak business at a fair price both look like "a number" in a Score column. The screener and
discovery tables therefore carry a second, separate column.

**Computed from the screening row alone** — `weeklyRsi`, `priceVs52WeekHigh`, `weeklyEmaSlope`,
plus the quality fields already stored. No Kite or NSE call, so `/api/dashboard/screener` stays
DB-only and safe on a page load (§27.4). It is computed on read rather than persisted: it is a pure
function of the row, and a stored copy could disagree with the row it describes after a re-screen.

**A sibling rule table, not the watchlist's.** `ScreenerTimingVerdict` shares the `Verdict`
vocabulary of §37.3's `BuyTimingVerdict` so one word means one thing across every screen, but it is
a separate table because it has different inputs. The watchlist rules are calibrated on *daily*
RSI-14 and distance from EMA-50; a screening row has *weekly* RSI and distance from the 52-week
high. Substituting one for the other is the same class of error as filing a quarter as a year
(B-047) or mixing consolidated with standalone (Gotcha 73) — the output looks measured and means
something else. Thresholds are set against the measured distribution of the 2026-08-27 run
(294 stocks: weekly RSI min 25 / median 54 / p90 67 / max 94, only 6% above 70), not a textbook
daily figure.

**Precedence — business facts settle the question before price facts are consulted:**

| Order | Rule | Verdict |
|---|---|---|
| 1 | Financial quality `HIGH_RISK` | AVOID |
| 2 | Forensic flag at `HIGH` severity | AVOID |
| 3 | Liquidity `THIN` (never `UNKNOWN` — §12.9) | AVOID |
| 4 | Composite < 50 | AVOID |
| 5 | Forensic flag at `MEDIUM` severity | HOLD_OFF, flag named |
| 6 | Weekly RSI > 70 | WAIT_FOR_PULLBACK |
| 7 | > 25% below the high **and** trend still falling | HOLD_OFF |
| 8 | Weekly RSI < 38 with no turn | HOLD_OFF |
| 9 | Composite ≥ 65, > 10% below the high, trend not falling | BUY_NOW |
| 10 | otherwise | ACCUMULATE |

**Forensic severity is respected, never flattened.** The forensic screen grades its own flags
`HIGH` / `MEDIUM` / `INFO` and deliberately scores INFO at zero (§32.4). The first implementation
treated any non-blank flag as disqualifying, which sent a 90-score business to AVOID on a medium
receivables note — caught on the live run, not in review. `INFO` is not a stop at all.

**Null discipline.** An unmeasured input never fires the rule it would have fired: a missing RSI is
not "not overbought". With neither quality nor timing readable the verdict is `NOT_MEASURED` and
renders as the striped marker, never as a neutral verdict (Gotcha 21).

**Not a forecast.** No price target, no prediction (§19, §25.3). It reports whether the entry looks
stretched, reasonable or unattractive against what the last screening measured.

**Distance from the high is only half a position** (B-062). "13% below its 12-month high" sounds
like a pullback and is not one on its own: a stock can be 13% off its high and still 53% above its
low, i.e. it fell and has already bounced most of the way back. The first implementation checked
only the distance from the high, and on the live 2026-08-27 run **50 of its 56 BUY_NOW rows sat in
the top half of their 52-week range** — it was calling round-trips pullbacks. `rangePosition`
(0 = at the low, 100 = at the high) now gates BUY_NOW at 70, the measured median of that universe
(p10 17 / median 69 / p90 95). Above it the verdict is ACCUMULATE with the position stated.

**One question gets one answer** (B-062). A stock the investor already tracks shows the
**watchlist's** verdict on the screener and discovery tables too, tagged in the tooltip as coming
from the watchlist. The two engines are both honest and disagree because they measure different
things — this row carries weekly RSI and distance from the 52-week high from the last screening,
the watchlist row carries live daily RSI-14 and distance from EMA-50. Two answers to one question
in one vocabulary is a defect whichever is right, so the better-informed one wins. When the
watchlist read is unavailable the screening verdict is used and the fallback is logged at WARN,
because it can reintroduce the disagreement.

Pinned by `ScreenerTimingVerdictTest` (24 cases, incl. every severity tier, the precedence sweep,
the round-trip-is-not-a-pullback case, and that a null input never fires a rule).

### 12.12 Entry ladder — "how do I buy this?" ✅ *(new 2026-09-01, reworked 2026-09-01)*

The entry-timing verdict (§12.11) says *whether* now is a reasonable moment. This says *how to
buy*. Shown on the screener, the discovery tables and the watchlist from one rule, so a stock
cannot carry two different entry plans on two screens (the B-062 lesson).

**It is a ladder, not a price — and that is the industry-standard answer, not a simplification.**
Over a holding period measured in years the entry tick is second-order: a business that triples in
three years rewards a 5% better entry with roughly 1.7% of extra return, while a dip that never
arrives costs the entire position. The evidence runs the same way as the lump-sum-versus-averaging
literature — markets rise more often than they fall, so waiting loses more often than it wins. The
two *real* reasons to care about entry are **behavioural** (buying a vertical extension makes an
investor likely to sell the first ordinary 20% retrace) and **volatility drag on a fixed budget**.
Neither is answered by a single number, and a single number invites both failures this feature has
already produced: treating a level as a forecast, and contradicting the verdict beside it (B-068).

This is also the shape §8's accumulation planner already endorses — *"price-ladder mode: N tranches
at descending price levels."* The first version of this column reinvented a worse, single-point
form of a problem the app had already solved.

**Three equal tranches.** Equal thirds deliberately: unequal weights would be free parameters tuned
on nothing, which this codebase has learned to refuse (Gotcha 30).

| Verdict | Ladder |
|---|---|
| BUY_NOW / ACCUMULATE | first tranche **at today's price**, then one and two steps down. Not owning a business the app rates highly is the larger risk |
| WAIT_FOR_PULLBACK / HOLD_OFF | **every** tranche below today's price (one, two, three steps down) |
| AVOID | **none.** A price here reads as "buy it cheaper" |
| NOT_MEASURED | none |

**The contradiction is designed out, not guarded.** Because a waiting verdict's ladder begins below
market, it *cannot* quote today's price — B-068 is structurally impossible rather than caught by a
threshold.

**Where the rungs come from.**
- **Step = ATR-14**, the stock's own normal daily range, so the ladder is volatility-normalised and
  a 3% pullback is not treated as identical for a bank and a micro-cap. With no ATR the step falls
  back to 3% and the plan still forms.
- **The deepest rung snaps to the 50-day average** when that sits *nearer* than the ladder would
  reach — because that is the level a "stretched above its average" verdict actually cites. When
  the average is further away it is **not quoted at all**: a level the stock may not revisit for a
  year is not a plan, it is a decision never to buy.
- **Levels round down** to a sensible tick. `424.00` implies a precision nobody has, and rounding
  down (never to nearest) means a rounded level can never drift above the price it came from.

**Every waiting plan carries its fallback** — *"if it has not reached X in about three months and
the score still holds, buying anyway beats waiting indefinitely."* That is the sentence an
experienced investor says and an amateur never writes down; without it a "wait" quietly becomes a
decision never to buy.

**Inputs are free.** `support20d`, `atr14` and `ema50` are computed during the screening run from
candles it already fetches — no extra Kite call — and persisted on `multibagger_scores`. `ema50` is
null below 50 candles rather than a short-window average masquerading as a 50-day one (B-060).

**Invariants.** No rung is ever above today's price, for any verdict or input. Shares always total
100%. A result with no ladder always says why, and an AVOID row having none is a finding, not a gap.

**Coverage note.** Rows screened before these columns shipped have no levels and show the
"not measured" marker; they fill in at the next 14:00 run. The watchlist was complete immediately
because it already stored its own support, ATR and 50-day average.

## 13. Screening Universe ✅ Active

*(Restored 2026-09-05 — this section, §14, §15 and §16's heading were cited throughout the spec but absent from the file; B-076.)*

**Curated tiers** ([Nifty200WatchlistService](src/main/java/com/example/trading/scanner/Nifty200WatchlistService.java)) built from four deduplicated lists — NIFTY 50, NIFTY NEXT 50, NIFTY MIDCAP 100, NIFTY SMALLCAP 250:

| Tier | Contains |
|---|---|
| `LARGE_ONLY` | NIFTY 50 + NEXT 50 |
| `LARGE_MID` | + MIDCAP 100 *(default)* |
| `LARGE_MID_SMALL` | + SMALLCAP 250 |
| `ALL` | everything above |

Each symbol carries a category label (`LARGE_CAP` / `MIDCAP_100` / `SMALLCAP_250`) used by the market-cap bonus (§12.5) and the by-cap endpoint.

**The screener screens two lists, not one** (B-053, CLAUDE.md Gotcha 57). `resolveUniverse()` unions the tier list with `MultibaggerScreenerService.SCREENING_UNIVERSE`, a second hardcoded ~90-name list, and — when `trading.universe.dynamic-expansion.enabled` is on — with symbols promoted by the expansion funnel (§30). Anything asking *"is this stock already covered?"* must union all three via `getScreeningUniverse()`; reading only the tier list is how the funnel came to "discover" a stock with five months of screening history.

**Known coverage gap** (measured 2026-08-26). NSE lists 2,559 securities, 2,291 of them mainboard `EQ`; the curated universe of ~361 names had **never looked at 1,598 of them**. That gap is the reason the Under-Discovery lens (§12.10) found one candidate on its first run — it was pointed at the well-covered part of the market by construction. §30 exists to close it, in observation mode.

**Trading series `BE` / `BZ` are excluded structurally** — trade-to-trade / surveillance names, and Kite tradingsymbols never carry the suffix (B-013). The universe source is `EQUITY_L.csv`, not the Kite instruments dump, because only it carries series and listing date (§30.1, Gotcha 36).

---

## 14. Reports & Alerts ✅ Active

Email is a first-class surface (§21 governs its language); the dashboard (§27) is additive. Every scheduled sender below is listed with its cron in §15.

| Report | When | Sender | Spec |
|---|---|---|---|
| Morning Briefing | 09:30 MON-FRI | `MorningBriefingService` | §4 |
| Portfolio Snapshot (daily) | 09:30 MON-FRI | `PortfolioSnapshotReportService` | §5, §7 |
| FII/DII Activity | 10:00 MON-FRI | `FiiDiiReportService` | §12 |
| Quantitative Discovery | 10:00 MON-FRI | `QuantitativeDiscoveryReportService` | §12.3 |
| Accumulation reminder | 10:00 MON-FRI | `AccumulationReminderService` | §8 |
| Exit-timing alerts | 10:00, 12:00, 14:00 MON-FRI | `ExitTimingAlertService` | §6, §35.5 |
| Watchlist report | 11:00, 13:00, 15:00 MON-FRI | `WatchlistReportService` | §37 |
| Holdings analysis | 11:00, 13:00, 15:00 MON-FRI | `HoldingsReportService` — **two emails** at the 15:00 fire (Action Items, then Analysis) | §6, §9.4 |
| Target-hit alert | 15:05 MON-FRI | `TargetHitAlertService` | §26 |
| Macro & geopolitical events | a section in the 09:30 briefing and in the 15:18 action items; no email of its own | `MacroReportRenderer` | §48 |
| Recommendation accuracy | 15:25 FRI | `RecommendationAccuracyReportService` | §23 |
| Portfolio review (monthly) | 15:25, first FRI of the month | `PortfolioSnapshotReportService` | §5 |
| Rebalance prompt (quarterly) | 15:26, first FRI of Jan/Apr/Jul/Oct | `PortfolioSnapshotReportService` | §10 |
| Multibagger screening | 09:00 SAT | `MultibaggerReportService` | §12.5 |
| Deep research / AI discovery / universe expansion | on demand (`GET /api/research/*`) | `StockResearchController` | §12.1–12.2 |

**Historical senders with no caller.** `EmailNotificationService` still carries intraday-era trade-alert, EOD-summary, stop-loss-hit and target-achieved templates. Nothing calls them; `sendHtmlEmail` is the only live entry point. Listed here so nobody re-wires them — doing so would be a §19 violation, not a restoration.

---

## 15. Scheduled Jobs ✅ Active

Every job below carries `zone = "Asia/Kolkata"` and, unless the Guard column says otherwise, `if (!marketHoursService.isMarketOpen()) return;` as its first statement (§3.4).

| IST | Job | Class | Guard |
|---|---|---|---|
| 09:20 MON-FRI | Holdings sync from broker | `HoldingsScheduler.syncHoldingsFromBroker` | market |
| 09:30 MON-FRI | Morning briefing | `MorningBriefingService.scheduledMorningBriefing` | market |
| 09:30 MON-FRI | Portfolio daily snapshot | `PortfolioReportScheduler.dailySnapshot` | market |
| 09:45 MON-FRI | Kite token refresh (recovery for the startup login) | `TokenManagementService.dailyTokenRefresh` | **none** — in-window; guarding it would defeat its purpose |
| 09:45 MON-FRI | FII/DII fetch | `FiiDiiScheduler.fetchFiiDiiData` | market |
| 10:00 MON-FRI | FII/DII report email | `FiiDiiScheduler.sendFiiDiiReport` | market |
| 10:00 MON-FRI | Quantitative discovery scan | `QuantitativeDiscoveryScheduler.dailyDiscoveryScan` | market |
| 10:00 MON-FRI | Accumulation reminder | `AccumulationReminderService.dailyCheck` | market |
| 10:00, 12:00, 14:00 MON-FRI | Exit-timing alerts | `ExitTimingAlertService.scheduledCheck` | market |
| 10:00–14:00 hourly MON-FRI | Holdings price refresh | `HoldingsScheduler.refreshHoldingsPrices` | market |
| 10:30 MON-FRI | Holdings analysis **+ core-holding classification** (§35.7 — no cron of its own) | `HoldingsScheduler.analyzeHoldings` | market |
| 11:00, 13:00, 15:00 MON-FRI | Holdings report emails | `HoldingsScheduler.sendHoldingsReport` | market |
| 11:00, 13:00, 15:00 MON-FRI | Watchlist analysis + snapshot (§37.4) | `WatchlistScheduler.analyzeAndReportWatchlist` | market |
| 11:30 MON-FRI | Annual-fundamentals backfill batch (§32.6) | `FundamentalsBackfillScheduler.backfillBatch` | market |
| 12:15 MON-FRI | IPO pipeline capture (§45.6) — three NSE list calls, a detail call per issue, one paced Kite quote per listing under a year old | `IpoCaptureScheduler.captureDaily` | market |
| every 15 min 09:15–15:30 MON-FRI | Headline capture, storage only — no classification, no alert, no email (§48.4) | `MarketImpactNewsService.scheduledNewsCheck` | market — the cron `0 */15 9-15` fires at 09:00 and the guard is what stops it (§3.4) |
| 13:20 MON-FRI | Analyst target capture + measurement (§49.6) — the capture half is DB-only; the measurement half is one paced Kite call per stock with an open target, bounded and rotating least-recently-measured first | `AnalystTargetScheduler.captureAndMeasure` | market |
| 14:00 MON-FRI | Daily multibagger screening | `MultibaggerScheduler.dailyQuickScreening` | market |
| 14:45 MON-FRI | Insider disclosure capture (§28) | `InsiderCaptureScheduler.captureDaily` | market |
| 15:05 MON-FRI | Target-hit scan + email | `TargetHitAlertService.scheduledScan` | market |
| 15:22 MON-FRI | Recommendation outcomes at 30/90/180/365d | `RecommendationOutcomeScheduler.updateOutcomesScheduled` | market |
| 15:25 FRI | Accuracy report email | `RecommendationAccuracyReportService.sendWeeklyReport` | market |
| 15:25 first FRI | Monthly portfolio review | `PortfolioReportScheduler.monthlyReview` | market |
| 15:26 first FRI of Jan/Apr/Jul/Oct | Quarterly rebalance prompt | `PortfolioReportScheduler.quarterlyRebalance` | market |
| 15:28 MON-FRI | Tax-lot auto-capture from Kite `/trades` (§9.3) | `TaxLotAutoCaptureScheduler.captureTodayTrades` | market |
| 15:28 MON-FRI | Data cleanup | `DataCleanupScheduler.performDailyCleanup` | market |
| **08:00 SAT** | Weekly full screening + coverage vector + shadow composites + universe expansion Stage A + retirement pass | `MultibaggerScheduler.weeklyFullScreening` | **Saturday carve-out** |
| **09:00 SAT** | Weekly multibagger report email | `MultibaggerScheduler.sendWeeklyReport` | **Saturday carve-out** |

**28 scheduled methods; no job fires before 09:15 on a weekday.** *(Counted from the annotations, not from this table, which had drifted: it read 28 when the true figure was 27. The 12:15 IPO capture arrived 2026-09-09, the 15:20 market-impact summary was deleted 2026-09-12, and the 13:20 analyst target pass was added the same day.)* The Saturday pair is the only exception to §3.4, and only those two may use `isSaturdayScreeningWindow()` (Gotcha 28). The 15:05–15:28 block is deliberately lightweight — a long scan there starves it past 15:30, where the guard aborts silently (B-014).

**Why the 11:30 job is a weekday job and not a third Saturday caller** (2026-09-06). §40.3 originally proposed invoking the backfill from inside `weeklyFullScreening()`. That is right about Gotcha 28 and wrong about capacity: the Saturday window is 07:50–10:35 and that job already carries a full screening run, the coverage vector, shadow composites, the retirement pass and the Stage A coarse scan (measured 11–22 minutes), with the report following at 09:00. Roughly 4,000 paced NSE requests do not fit, and crowding that window pushes the report past the point where its guard aborts it in silence. A weekday job inside the ordinary §3.4 window is **not** the Saturday exception — it uses `isMarketOpen()` and never touches the carve-out — and it runs five times a week instead of once. 11:30 is a genuine gap here, clear of every NSE-heavy job (09:45, 10:00 ×2, 14:00, 14:45), and it uses **no Kite calls at all**, so it never competes for the broker budget that has twice starved the afternoon (Gotcha 23, B-014).

---

## 16. REST API

### Active (✅)
- `GET /api/research/{symbol}` — deep 20-dim research (15 base + Financial Quality + Intrinsic Valuation + Analyst Signal + Wealth Signals + Capital Efficiency). **Sends an email.** A non-symbol path 404s rather than falling through (B-073)
- `GET /api/research/discover` / `/quantitative` / `/universe/expand`
- `GET /api/research/earnings/{symbol}` / `/shareholding/{symbol}` / `/levels/{symbol}`
- `GET /api/research/valuation/{symbol}` — reverse-DCF sanity check (implied growth vs historical, §12.5)
- `GET /api/research/analyst/{symbol}` — analyst signal: earnings trend-break + brokerage-action flow (§24, proxies for paid consensus)
- `GET /api/analyst/track-record` — per-brokerage hit rate, median excess return vs Nifty, time to target, revision rate, with the coverage block and the sample caveat (§49.8). DB-only
- `GET /api/analyst/targets?symbol=` — every recorded target on a stock and what the open ones say. DB-only
- `GET /api/analyst/recent?days=` — recently recorded targets across every stock. DB-only
- `POST /api/analyst/capture` — mine the stored headline feed now (DB-only, but it writes)
- `POST /api/analyst/measure` — measure the open book now; **refused from 14:00 with a 409 carrying its reason**
- `GET /api/multibagger/*` — scores, candidates, holdings, screen, trend, history
- `GET /api/fiidii/*` — flows, deals, sectors, trend
- `POST /api/trading/morning-briefing` — manual trigger
- `POST /api/trading/holdings/exit-alerts` — manual trigger
- `GET /api/trading/holdings/decay` — composite-score decay per holding (§6 thesis drift), universe-relative (B-064)
- `GET /api/trading/holdings/buy-timing` — "still a good time to buy more?" (§6.5). DB-only
- `POST /api/trading/holdings/refresh-one?symbol=` — re-analyse one holding; 409 from 14:55
- `GET /api/accuracy/summary` — calibration metrics across all engines and horizons (§23)
- `GET /api/accuracy/by-source/{source}` — slice for one engine (MULTIBAGGER / QUANT_DISCOVERY; SECTOR_REVERSAL returns **historical rows only** — engine removed §39)
- `GET /api/accuracy/by-symbol/{symbol}` — recommendation history for a stock
- `GET /api/accuracy/dimension-ic?horizon=90&source=MULTIBAGGER` — per-dimension IC (§23.2). **Hits Kite despite being a GET** (§27.4)
- `GET /api/accuracy/coverage` / `/coverage/trend?signal=&days=` — per-signal coverage vector (§38.2). DB-only
- `POST /api/accuracy/refresh-outcomes` — on-demand recompute
- `POST /api/accuracy/retro` — retro-backtest of the composite (§33). Manual only, expensive
- `POST /api/alerts/target-hits/scan` / `/preview` — run the target-hit scan now, or preview the email (§26)
- `POST /api/accuracy/report` — send the weekly accuracy email now
- `/api/macro/*` — macro & geopolitical event exposure (§48). Five DB/classpath GETs safe on page load (`/events`, `/exposure?symbol=`, `/exposure/portfolio`, `/calendar`, `/map`, `/status`); `POST /ingest` and `POST /news/scan` make live calls and are refused 09:40–10:15 and from 14:00 with a 409 carrying its reason; `POST /events/dismiss?id=`. Replaces `/api/performance/news/*`, deleted with the alert email

**Fundamentals, turnarounds & forensics (§32)** — `POST /api/fundamentals/backfill?symbol=&maxYears=` (NSE archive, live calls, 409 during 09:40–10:15), `/backfill-holdings`, `/import-history` (CSV fallback), `GET /history?symbol=`, `/turnaround/{symbol}`, `/turnarounds`, `/forensics/{symbol}?announcements=false`, `DELETE /history?symbol=`.

**Concall & management quality (§34)** — `POST /api/concall/analyze/{symbol}` (live NSE + PDF + AI), `GET /credibility/{symbol}`, `GET /ledger/{symbol}`, `POST /resolve/{id}?met=&actual=` (manual by design).

**Insider Pulse (§28)** — `GET /api/insider/{symbol}`, `GET /api/insider/recent?days=`; `POST /api/insider/capture` runs live NSE fetches and is never wired to a page load.

**Dynamic universe (§30)** — `GET /api/universe/dynamic` (includes retired rows), `GET /api/universe/ipo-watch` (Kite-backed, ~6 min), `POST /api/universe/scan` (~22 min, refused from 13:00), `POST /api/universe/process-queue` (refused from 14:00).

**IPO pipeline (§45)** — `GET /api/ipo/pipeline`, `GET /api/ipo/recent?months=`, `GET /api/ipo/issue?symbol=` (all DB-only, page-load safe); `POST /api/ipo/capture` (live NSE + Kite, refused 09:40–10:15 and 14:00–close with a 409-with-reason), `POST /api/ipo/analyse?symbol=` (one listed issue, ~2 Kite + 3 NSE calls, same refusal). `symbol` is a query parameter.

**Learning substrate (§38.8–§38.10)** — `GET /api/learning/variants` (the six pre-registered weight vectors), `GET /api/learning/shadow`, `POST /api/learning/shadow/backfill` (DB-only), `POST /api/learning/review` (walk-forward + promotion gate; Kite-paced, refused from 14:00), `GET /api/learning/reviews` (persisted review history).

**Dashboard UI (§27) — all read-only, all fast, all DB-only:**
- `GET /api/dashboard/health` — server time, market-open flag, and per-table data-freshness timestamps (drives the freshness strip on every screen)
- `GET /api/dashboard/summary` — landing-page aggregate: portfolio KPIs, risk headline, drift + decay counts, accuracy headline. Pure composition of existing services; no new business logic
- `GET /api/dashboard/series/holding?symbol={sym}&days=180` — per-stock time series from `holdings_history`
- `GET /api/dashboard/series/portfolio?days=180` — portfolio equity curve (invested / value / P&L by date)
- `GET /api/dashboard/series/matrix?days=90` — all symbols in one query, for table sparklines
- `GET /api/dashboard/screener` — the latest screening run that actually **has rows**, with its date. Required because `/api/multibagger/scores` serves an in-memory cache emptied by the daily restart and `/history` defaults to today (empty until the 14:00 run)
- `GET /api/reports/{name}/preview` — returns the *actual* report HTML (`text/html`) for in-page display instead of emailing it; 15-minute server-side cache. Names: `holdings-actions`, `holdings-analysis`, `holdings-weekly`, `morning-briefing`

Note: `symbol` is a **query parameter**, not a path variable, throughout the dashboard API — symbols contain a colon (`NSE:RELIANCE`), which is a legal-but-hazardous path character across Tomcat/Spring/`fetch()`.

### Portfolio modules (✅ — listed as "planned" here until 2026-09-05; all shipped with the 2026-04-18 MVP. Paths corrected against the controllers)
- `GET` / `PUT /api/portfolio/profile` — target allocation profile and weights (§5); `GET /api/portfolio/drift` — actual vs target with alerts; `GET /api/portfolio/sectors/mapping`
- `GET /api/portfolio/risk` — HHI, sector and stock concentration (§7)
- `GET /api/portfolio/conviction/{symbol}`, `PUT /api/portfolio/conviction/bulk` — thesis and conviction per holding (§6)
- `GET /api/portfolio/tax-lots/{symbol}`, `POST /tax-lots`, `/tax-lots/sell`, `/tax-lots/harvest`, `/tax-lots/bulk-import`, `/tax-lots/import-zerodha-csv`, `/tax-lots/capture-today`, `DELETE /tax-lots/all` (destructive, never in the UI) (§9)
- `POST /api/portfolio/accumulate`, `GET /api/portfolio/accumulate`, `GET`/`DELETE /accumulate/{id}`, `POST /accumulate/{planId}/tranche/{trancheId}/fill`, `POST /accumulate/reminder` (§8)
- `POST` / `GET /api/portfolio/rebalance` — non-executing rebalance proposal, computed on request and not persisted (§10)
- `GET /api/portfolio/dividends/summary`, `/dividends/reinvest`, `/dividends/symbol/{symbol}`, `POST /dividends/{id}/received?amount=` (§11)
- **Portfolio truth (§46)** — `GET /api/portfolio/performance?days=365` (TWR vs Nifty 50 / Midcap 150, total return, drawdown, cash, lot coverage; DB-only), `GET /api/portfolio/quality` (value-weighted P/E, ROCE, ROE, profit growth with coverage; DB-only), `POST /api/portfolio/benchmark/backfill?days=400` (two Kite candle calls; **409 with reason 14:00–15:30**)
- `POST /api/portfolio/reports/snapshot` — send the portfolio snapshot email now
- **Core holdings (§35)** — `GET /api/portfolio/core-holdings`, `/core-holdings/history?symbol=&days=`, `POST /core-holdings/override`, `POST /core-holdings/classify` (refused from 14:55 with a 409 carrying its reason)

### Legacy reads over frozen tables
`TradingController` still exposes `/positions/*`, `/trades`, `/pnl/summary`, `/test/margins`, `/config/risk`, `/filter/*`, `/test-mode/config`. They read tables nothing has written since 2026-05-24 (§17) and are **not a surface** — no screen calls them and none should. Listed so a future reader sees dead reads rather than a feature to restore.

### Watchlist Tracking (§37) — `symbol` is always a query parameter
- `GET /api/watchlist/items?includeRemoved=false` — every tracked stock with added date, return since added, vs Nifty, sector, quality + timing scores, trend series, verdict. **DB-only, page-load safe.**
- `GET /api/watchlist/item?symbol=NSE:X` — one row (404 when not tracked). DB-only.
- `POST /api/watchlist/items` body `{symbol, note}` — add: live price + Nifty + technical analysis (~4 paced Kite calls). **Button-only; 409 from 14:55.** 422 when Kite returns no price.
- `POST /api/watchlist/items/refresh?symbol=&quality=false` — re-analyse one row; `quality=true` adds an ad-hoc composite via `evaluateSingleStock` (never `screenSingleStock`). **Button-only; 409 from 14:55.**
- `POST /api/watchlist/items/remove?symbol=` — soft-delete; history kept. DB-only.
- `POST /api/watchlist/items/note?symbol=` body `{note}` — edit the note. DB-only.
- `GET /api/watchlist`, `/buy-signals`, `/strong-buy-signals`, `/config` — legacy raw rows (active only) and config.
- `POST /api/watchlist/analyze`, `/analyze-and-report` — all-symbol re-analysis (+ email). Never from the UI; 409 from 14:55.

---

## 17. Persistence

PostgreSQL on `localhost:5432/tradingdb`. Tables are retained as-is for historical data, with new ones added for portfolio modules.

Table names below are the `@Table(name = …)` values in code (reconciled 2026-09-05, B-052).

### Legacy tables (frozen — never written since 2026-05-24)
| Table | Status |
|---|---|
| `trades`, `active_positions`, `broker_orders` | Intraday-era. Read only by `DataCleanupScheduler` and the legacy `TradingController` reads (§16); never written. |
| `strategy_execution_logs` | Cleanup keeps 30 days; no writer |
| `sector_reversal_signals` | **Engine removed 2026-09-03; rows kept deliberately** — the negative IC measured on them is the evidence for the removal (§39.3). Never delete the record with the producer. |
| `holdings_ml_training`, `holdings_anomalies` | Orphaned 2026-08-28 (ML removed; no writer, no cleanup arm — drop manually if desired) |
| `holdings.ml_*`, `has_anomaly`, `blended_score` columns | Orphaned 2026-08-28; never written or read |

### Core & research tables (✅)
| Table | Purpose / retention |
|---|---|
| `holdings` | Active positions only (B-016); rebuilt from the broker at 09:20 and 15:18 |
| `holdings_history` | Daily per-holding snapshot; **1095 days (3 years)** — raised from 180 on 2026-08-24; this table is the dashboard's portfolio-value time series (§27), and 180 days hard-capped every chart at 6 months |
| `historical_candles` | 365 days |
| `multibagger_scores` | Every screening row, retained forever (trend tracking, IC, shadow reconstruction §38.8). Carries `scoring_version` (§38.1) |
| `screening_coverage` | Per-signal coverage vector per screening run (§38.2) |
| `shadow_composites` | One row per (date × symbol × weight variant), back-filled to April 2026 (§38.8) |
| `weight_reviews` | Every promotion-gate review, whatever it concluded (§38.10) |
| `recommendations` / `recommendation_outcomes` / `recommendation_dimensions` | Issued picks, realized returns at 30/90/180/365 days, and the QUANT_DISCOVERY sub-score sidecar (§23) |
| `pick_performance_snapshots`, `target_hit_events` | Target-hit alert dedup and pick snapshots (§26) |
| `annual_fundamentals` | Multi-year annual P&L / balance sheet / cash flow, two writers — `IMPORT` and `XBRL`, XBRL wins (§32.2) |
| `guidance_items` | Concall guidance ledger; resolution is manual by design (§34.3) |
| `insider_disclosures` | SEBI PIT + bulk/block deals, mode-filtered (§28) |
| `dynamic_universe` | Expansion-funnel rows incl. retired ones (§30) |
| `ipo_issues` | One row per mainboard public issue NSE lists: offer structure, category book, quotas, links, post-listing measurements. Rows are never deleted; `captured_at` is stamped on every row a capture saw (§45) |
| `holding_classification` | Core-holding tier, gates, durability, observed alerts per holding per day (§35.6) |
| `market_impact_news` | 30 days |

### Portfolio tables (✅ — listed as "planned" until 2026-09-05; all have existed since the 2026-04-18 MVP)
| Table | Purpose |
|---|---|
| `portfolio_profile`, `portfolio_target_weight` | Target allocation definitions and individual target rows (sector, cap, symbol) (§5) |
| `holding_conviction` | Thesis text, conviction score, horizon (+ `horizon_stated`, B-057; `thesis_stated`, B-097), invalidation triggers per holding (§6) |
| `benchmark_daily_close` | One close per benchmark index per day (§46.2): written at the 15:00 snapshot, back-filled on demand. Unique on (symbol, close_date) |
| `portfolio_cash_snapshot` | Broker available cash per day, from `/user/margins` at the 15:00 snapshot (§46.4). Unique on date |
| `tax_lot`, `tax_lot_sale` | Per-purchase lots and FIFO/LIFO/HIFO sales, keyed on broker `trade_id` (§9) |
| `accumulation_plan`, `accumulation_tranche` | Multi-tranche accumulation plans (§8) |
| `dividend_event` | Ex-date, amount, type, received status (§11) |
| ~~`rebalance_proposal`~~ | **Never built** — rebalance proposals are computed on request (§10) and not persisted |
| `watchlist` | One row per tracked symbol (§37). Analysis fields overwritten each run; membership fields (`added_on`, `price_at_add`, `nifty_at_add`, `added_note`, `source`, `active`, `removed_on`, `price_at_removal`) written only by add/seed/remove. Soft-delete; retained |
| `watchlist_daily_snapshot` | One row per tracked symbol per day (§37.4): close, Nifty close, timing/quality score, verdict. `watchlist.snapshot-retention-days` (730) |

### LOB columns
All JSON/audit text fields use `@Column(columnDefinition = "TEXT")`, **not** `@Lob String`. Rationale: Hibernate 6 + PostgreSQL maps `@Lob String` to `oid`, requiring an open transaction to stream. `SchemaMigrationRunner` runs idempotent startup conversion of any legacy `oid` columns to `TEXT`.

---

## 18. Non-Functional Requirements

| Requirement | Target |
|---|---|
| Morning briefing generation | < 30 s end-to-end |
| Deep research API | < 20 s (web fetches are the bottleneck) |
| Portfolio drift calculation | < 2 s for ≤ 100 holdings |
| Broker API retry | 3 attempts, exponential backoff (reactive `Retry.backoff(2, 1s..8s)`) |
| AI call retry | Same policy; errors return `""` so reports degrade gracefully |
| Log retention | 10 MB rolling, 100 MB total, 1-day window |
| Token refresh | Automatic via TOTP, zero manual intervention |
| Graceful degradation | AI, NSE, news, global-market sources may each fail independently |
| Time zone | All schedules explicitly `Asia/Kolkata` |
| Java runtime | JDK 21 |
| Dashboard first paint | < 1.5 s |
| Dashboard page-load requests | **No request fired on page load may exceed 2 s, send an email, call an external API (Kite/NSE/AI), or write to the DB.** Anything that does is button-triggered with an explicit cost warning (§27) |

---

## 19. Non-Goals

The system does **not**:

- **Place trades of any kind, or hold positions for minutes/hours with stop-losses.** The intraday engine was deleted (§3.1); nothing calls the broker's order endpoint.
- **Turn news into a directional call on a stock** *(2026-09-12)*. Two engines were deleted on
  2026-09-03 for exactly this (§39.3). §48 is the sanctioned form and the boundary is precise: the
  reader records **what moved and which way** and is never permitted to name a company; which
  businesses that reaches is decided by a rule table the investor can read on screen. A feature
  that scores a headline for a stock, or that reports the market as bullish or bearish, is barred. §49
  is the second sanctioned form and sits the other side of the same line: it reads a headline that
  **itself names a company as the subject of an attributed price target**, records who said what,
  and then measures whether they were right. It forms no view of its own on the stock, scores
  nothing, and exists to grade the forecaster rather than the business (§49.1).

- **Build buy/sell signal engines** *(2026-09-05)*. Reviewer's test: a feature whose output is an instruction to transact now. The existing verdict vocabulary (`BUY_NOW … AVOID` on the watchlist and screener, the reconciled `displaySignal` on holdings, the entry ladder) is **accumulation timing for already-vetted names**, produced by the one shared rule table (`BuyTimingVerdict`, §37.3; CLAUDE.md Gotcha 85). It is frozen: a new surface that touches the question defers to that table, and no new vocabulary, engine or trigger is added. Signal-gated accumulation (§8.2) is the standing example of what this rules out.
- **Optimise for, or accept a signal on, short-term price prediction** *(2026-09-05)*. Reviewer's test: a signal whose only evaluation horizon is under 180 days. The 30- and 90-day IC panels (§23) remain as *early reads*; the acceptance bar for any scoring change is the promotion gate at the mandate's horizons (§38.10, §40).
- **Say "apply" to an IPO, predict listing-day gains, or show grey-market premiums** *(2026-09-09, §45.8)*. Before a company lists the app has no filings to judge it on, so its pre-listing read is of the offer's *structure* only, and it names what to read in the prospectus rather than issuing a verdict. A listing-day pop is a short-term price prediction (the test above) and the grey-market premium is its price.
- Support margin trading, F&O speculation, short selling.
- Support brokers other than Zerodha Kite (pluggable interface; only Kite implemented).
- Trade markets outside NSE/NFO.
- Act as tax-filing software — it suggests tax-aware exits, does not file returns.
- Provide investment advice under SEBI regulations — all outputs are analytical aids for the investor's own decisions.
- Guarantee returns or back-test fidelity — all historical analyses are indicative.

**Previously a non-goal, now a core goal**: holding positions overnight, week, months, or years.

**Previously a non-goal, now a core goal (reversed 2026-08-24)**: a **local read-only web UI** — see [§27 Dashboard UI](#27-dashboard-ui). Rationale: ~21 fast DB-only endpoints already existed with no reader, and email fundamentally cannot show a sortable table or a six-month time series. Email remains a first-class surface; the dashboard is additive, not a replacement.

---

## 20. Change-Control Rules

1. Adding a new portfolio module (§5–§11) — update this SPEC section, add the endpoints to §16, add tables to §17, update CLAUDE.md package structure.
2. Enabling or disabling the intraday trading loop — update the status markers in §3 and §15.
3. Changing a threshold/weight documented here — edit the corresponding section in the same commit as `application.yml`.
4. Adding a new scheduled job — add a row to §15 with cron and status. **Cron must satisfy the market-hours constraint in §3.4** (09:15–15:30 IST, Mon–Fri, `Asia/Kolkata`).
5. Any change that affects §19 Non-Goals — requires explicit decision record in commit message.
6. Scheduler placement — see §3.4. New `@Scheduled` jobs outside the market-hours window are rejected in review.
7. **Dashboard endpoints are read-only.** No `/api/dashboard/*` endpoint may write to the database, call the broker, call an external API, or send email. Any dashboard screen that needs an expensive or side-effecting operation exposes it as an explicit button, never as a page-load fetch. Violations are rejected in review. See §27.
8. **Never add CORS.** The absence of a `WebMvcConfigurer`/`@CrossOrigin` anywhere in the codebase is load-bearing security — see §27.5. Adding one to "make the UI work" is always the wrong fix; the dashboard is same-origin by construction.
9. **Every new research feature declares four things in its SPEC section** *(2026-09-05)*: (a) which §1 pillar it measures, (b) the horizon at which it will be judged, (c) its coverage row in `screening_coverage` (§38.2 — a signal that cannot say how many stocks it measured cannot be evaluated), and (d) whether it ships in shadow mode (CLAUDE.md Gotcha 30 — bonuses do; risk controls do not). A feature missing any of the four is not reviewable and is rejected.
10. **A feature whose output answers "should I buy or sell now" is rejected** unless it is an *input* to `BuyTimingVerdict` (§37.3) and surfaces through it. This is §19's no-signal rule as a review gate. A new output vocabulary for that question — a second enum, a second engine, a trigger — is the B-062/B-069 defect being re-introduced.

---

## 21. Communication Style for Reports ✅ Active

The investor is not a stock-market expert. The app has **two** user-facing surfaces — scheduled **emails** ([§14](#14-reports--alerts)) and the local **dashboard UI** ([§27](#27-dashboard-ui)) — and jargon-heavy output on either one is effectively broken.

**These rules bind both surfaces equally.** A §21 violation on a dashboard screen is a bug on exactly the same footing as one in an email. Where a rule mentions "report" or "email" below, read it as "report, email, or UI screen".

Rules for every report/email/alert/screen the system produces:

1. **Plain-English intro per section.** Each section MUST start with a "💡 What this means" info box (1–2 sentences) describing the section's purpose without technical terms.
2. **Inline glosses.** First mention of any technical term in a section gets a parenthetical explanation. Examples: "HHI (concentration score, 0–10 000, higher = more concentrated)", "STCG (Short-Term Capital Gains, equity held < 365 days, taxed at 20%)", "EMA (Exponential Moving Average — trend indicator)", "PCR (Put-Call Ratio in options)".
3. **Human labels over enums.** Render "Over Tolerance" not `OVER_TOLERANCE`, "Thesis Broken" not `BROKEN`, "Long-Term" not `LONG_TERM` in user-facing output. Enum strings stay as-is in APIs and DB.
4. **Currency + locale.** ₹ symbol with Indian comma-grouping (`₹1,25,000`), not raw doubles or Western grouping.
5. **Second person.** "Your portfolio", "you bought", "you own" — not "the portfolio".
6. **Percentages** with a single decimal and sign (`+3.5%`, `−12.0%`), never scientific notation.
7. **Never render "unmeasured" as a number.** A null score, a null Information Coefficient, or a statistic computed from too few samples MUST read "not measured" / "not enough data yet" — never `0`, never `50`, never a blank cell that looks like a value. Applies especially to the four nullable Multibagger dimensions (B-019) and to `informationCoefficient` / `sampleSize < 10` cells (§23). Showing a confident-looking number for data we do not have is the most damaging §21 violation available, because the investor cannot tell it apart from a real reading.

Applies to new reports AND retrofit of existing ones when touched. Violations are treated as bugs.

---

## 22. Glossary

| Term | Meaning |
|---|---|
| **Conviction score** | 1–10 rating assigned at purchase, representing strength of belief in the thesis |
| **Thesis drift** | Delta between today's multibagger composite score and the score at purchase |
| **Tax lot** | A discrete purchase of shares (qty × price × date) — the atomic unit for tax computation |
| **HHI** | Herfindahl-Hirschman Index — sum of squared portfolio weights; higher = more concentrated |
| **LTCG / STCG** | Long-Term / Short-Term Capital Gains — Indian equity LTCG kicks in after 365 days |
| **Accumulation plan** | Multi-tranche staged purchase of a target stock over time (SIP, price-ladder, or signal-gated) |
| **Drift alert** | Notification when actual portfolio weight deviates from target beyond tolerance |
| **Invalidation trigger** | A pre-declared condition that breaks the investment thesis (e.g., "promoter stake falls below 40%") |
| **Hit rate** | Percentage of past picks that ended positive at a measurement horizon; >50% is the baseline (§23) |
| **Excess return** | A pick's return minus the Nifty 50's return over the same window — measures engine alpha, not just beta (§23) |
| **IC (Information Coefficient)** | Pearson correlation between an engine's score and realized return; +0.10 is the conventional "useful signal" threshold, 0 is random, negative is anti-signal (§23) |
| **Horizon** | Fixed look-forward window in days (30 / 90 / 180 / 365) at which a recommendation's outcome is measured (§23) |
| **Price target** | A level a brokerage says it expects a share to reach, conventionally within twelve months. It is one firm's opinion, not a forecast this app endorses — §49 records them so they can be scored |
| **Superseded target** | A price target the same brokerage replaced before it ran its course. Not counted as a miss (the firm withdrew it) but counted as a revision, so revising just before a deadline is visible (§49.9) |
| **Correlation cluster** | Group of holdings with mutual 90-day return correlation ≥ 0.7, treated as a concentration unit |

---

## 23. Recommendation Accuracy Tracking ✅ Active (MVP)

### 23.1 Purpose
Close the feedback loop on the system's own picks. Every scoring engine publishes a score and a verdict; without outcome measurement, those scores cannot be calibrated, threshold tweaks are guesswork, and no engine's relative quality can be compared. This module records each pick at issue time and measures its realized return at fixed horizons so hit-rate, mean return, excess return vs Nifty, and score-return correlation (IC) can be computed per engine.

### 23.2 Required Behavior

**Capture.** Three scoring engines feed the tracker:

| Source enum | Engine | Capture threshold |
|---|---|---|
| `MULTIBAGGER` | [MultibaggerScreenerService](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java) | composite score ≥ 65 |
| `QUANT_DISCOVERY` | [QuantitativeDiscoveryService](src/main/java/com/example/trading/scanner/QuantitativeDiscoveryService.java) | discovery score ≥ 60 |
| ~~`SECTOR_REVERSAL`~~ | Engine **removed 2026-09-03** (§39). No new picks are captured; the rows already written are kept as the evidence for the removal (§39.3) | was: upside score ≥ 65 |

Each capture records: source, symbol, issue date, issue price, Nifty index at issue, score, grade, verdict, optional target/stop-loss, sector, market-cap category. Upserts on `(symbol, source, issuedDate)` — same-day re-runs overwrite.

`StockResearchService` is intentionally **not** a source in this phase: its AI output is free-text prose and verdict extraction is too brittle to track reliably.

**Outcome measurement.** [RecommendationOutcomeScheduler](src/main/java/com/example/trading/intelligence/recommendation/RecommendationOutcomeScheduler.java) runs at **15:22 IST MON-FRI** (SPEC.md §3.4 compliant). For each horizon in `{30, 90, 180, 365}` days, it finds recommendations whose anniversary has passed with no outcome yet, fetches the current price and Nifty spot, and persists one `recommendation_outcomes` row:
- `returnPercent` = (price_now − price_issued) / price_issued × 100
- `niftyReturnPercent` = same formula on Nifty 50
- `excessReturnPercent` = stock return − Nifty return (null if Nifty at issue wasn't captured)
- `targetHit` / `stopLossHit` = boolean checks against the originally issued levels

One outcome row per `(recommendationId, horizonDays)` — idempotent across reruns.

**Calibration.** [RecommendationAccuracyService](src/main/java/com/example/trading/intelligence/recommendation/RecommendationAccuracyService.java) aggregates outcomes by `(source, horizon)` and returns:
- sample size
- hit rate % (positive returns / total)
- mean return %
- mean excess return % vs Nifty
- **target-hit rate %** — computed only over picks that actually proposed a target price; denominator is the count of picks with non-null `targetPrice`, not the full sample. Null when no picks in the cell carried a target (e.g., MULTIBAGGER doesn't propose per-pick targets).
- **stop-loss-hit rate %** — same shape for stop-loss.
- **target / SL coverage %** — what fraction of the sample proposed a target/SL at all. Tells the reader how representative the hit-rate numbers are.
- **Information Coefficient** — Pearson correlation between the original score and realized return, null when n < 3 or variance is zero

**Reporting.** [RecommendationAccuracyReportService](src/main/java/com/example/trading/intelligence/recommendation/RecommendationAccuracyReportService.java) emails the summary every **Friday 15:25 IST** (SPEC.md §3.4 compliant). The email follows §21 Communication Style — each section opens with a plain-English "what this means" box, and all technical terms (hit rate, IC, excess return, sample) are glossed on first use.

**Per-dimension IC** ✅ — `RecommendationAccuracyService.computeDimensionIC(source, horizonDays)` computes Pearson correlation between each scoring sub-score plus the composite and realized returns, sliced by horizon, **for all three engines**. Realized prices come from `MarketDataService.getRecentCandles` (Kite daily candles), independent of the `recommendation_outcomes` table. Sub-scores are read from wherever each engine already persists them: MULTIBAGGER (7 dims since 2026-09-03; 8 on older rows) from `multibagger_scores`, SECTOR_REVERSAL (4 dims: MACD / RSI / Volume / Price Structure) from `sector_reversal_signals`, and QUANT_DISCOVERY (5 dims: Earnings Growth / Insider Activity / Valuation / Price Momentum / Volume) from the `recommendation_dimensions` sidecar table populated at capture time (see §23.2 Capture). Rows with null sub-scores (e.g., Financial Quality before 2026-04-19) are skipped so per-dimension `sampleSize` varies. Exposed via `GET /api/accuracy/dimension-ic?horizon=90&source=SECTOR_REVERSAL` (`source` defaults to MULTIBAGGER) and rendered in the weekly Friday accuracy email as a per-engine two-column (30d / 90d) IC table with signal-strength colour coding. **Data-availability note**: MULTIBAGGER and SECTOR_REVERSAL produce numbers from existing history immediately; QUANT_DISCOVERY's sidecar starts empty (picks captured from 2026-05-24 onward), so its rows fill in once those picks reach the 30/90-day horizon.

**Sidecar persistence (2026-05-24)**: `RecommendationTracker.record(...)` has an overload accepting an ordered `Map<String,Integer>` of dimension → sub-score, persisted to `recommendation_dimensions` (one narrow row per dimension, unique on `(recommendationId, dimension)`, delete-then-insert on same-day re-run). QUANT_DISCOVERY passes its 5 sub-scores there; MULTIBAGGER and SECTOR_REVERSAL do **not** use the sidecar because their own score tables already carry the sub-scores. Dimension-persistence failures are swallowed — they never lose the parent recommendation.

**Horizon integrity (B-028, 2026-08-24).** An outcome's `horizonDays` is a claim about elapsed time, so the elapsed time is **stored, not inferred**: every row carries `daysElapsed` = `measuredDate − issuedDate`.

- A pick is measurable at a horizon only while it sits inside `horizon + MEASUREMENT_GRACE_DAYS` (**7 days** — absorbs weekends, market holidays and short downtime). Picks that drift beyond the window are **left unmeasured** and counted in a log line, because an honest gap beats a mislabelled number. This also stops permanently unpriceable symbols being retried every day forever (B-027).
- `findBySourceAndHorizon` — the source of every hit rate, mean return, excess return and IC in this section — excludes rows whose `daysElapsed` exceeds `horizon + grace`, and excludes null ones rather than trusting them.
- Why it matters: the scheduler used to select every unmeasured pick *older than* the cutoff and price it with today's price, so a pick issued 200 days ago was filed as a 30-day outcome. **20.8%** of 30d rows and **29.4%** of 90d rows were affected. Any calibration figure quoted from before 2026-08-24 describes a longer holding period than it claims and must be re-read.

**§25.6 Acting on measured signal (2026-08-25).** Two decisions taken from the score-panel study (forward returns of every screened stock, 82 screening dates, independent of `recommendation_outcomes`):

- **SECTOR_REVERSAL is measured but not actionable.** IC is negative at every horizon (**−0.163** @30d, **−0.195** @90d) — a higher upside score has predicted a *worse* realised return — and the independent Sector Tailwind panel IC (−0.009) shows no offsetting signal. At the time only the alert email was suppressed, and the scan continued so the signal kept being scored. **Superseded 2026-09-03**: after a second negative quarter the engine was removed outright (§39.2) — "keep scanning so it carries on being scored" is right for one negative quarter and wrong after two. The rows are kept; there is nothing left to re-enable.
- **A high-conviction tier at composite ≥ 70.** Median 4-month return by band was 8.43% (80+), 7.23% (70-79), 4.71% (65-69), 2.34% (50-64), −0.19% (<50) — monotone, stepping up at 70. Reports lead with this band. It is a **presentation tier, not a filter**: no candidate is discarded, no score changes.

**What was deliberately NOT done.** Per-dimension panel IC over the same window reads Financial Quality +0.042, Volume +0.018, Relative Strength +0.014, Technical Momentum +0.008, Price Structure −0.007, Sector Tailwind −0.009. Several balance-sheet metrics (ROE, ROA, cash conversion, interest coverage) show *negative* IC. **These are not grounds for re-weighting**: the window is one risk-on quarter in which quality factors are expected to lag, there are only ~5 independent 30-day periods, and quality is a multi-year thesis being judged on four months. Re-weighting here would be fitting noise — the same error §25.5 exists to prevent. The Valuation dimension's −0.083 is likewise **not** a verdict on the current engine: it was constant from June to 20 Aug (B-018), so that number describes the retired NSE-PE implementation, and the rebuilt XBRL+DCF version has no forward returns until late September.

### 23.3 Out of Scope (Phase 2)
- Per-dimension IC for QUANT_DISCOVERY and SECTOR_REVERSAL ✅ **shipped 2026-05-24** (no longer Phase 2).
- Feedback loop into scoring weights (adaptive re-weighting). Explicit decision: measure first, tune later.
- Survivorship-bias correction — delisted / merged symbols will silently drop out.
- `StockResearchService` as a source (needs structured verdict output).
- Per-sector and per-market-cap slices of the calibration table.

### 23.4 Depends On
- `MarketDataService.getCurrentPrice(symbol)` — for both the stock and Nifty spot at issue and at each horizon anniversary.
- Hibernate `ddl-auto=update` — the two new tables auto-create on first startup after the entity classes ship. No `SchemaMigrationRunner` change needed.
- `EmailNotificationService.sendHtmlEmail()` + `EmailTemplateService.buildEmailTemplate()`.

---

## 24. Analyst Signal (Proxy) ✅ Active (MVP)

### 24.1 Purpose
Indian equity analyst consensus (EPS estimates, price targets aggregated from 20+ brokerages) is not available for free — paid feeds (Bloomberg, Refinitiv, Trendlyne Pro) cost ₹1–10 lakh/yr and are out of scope. Rather than fake data we don't have, the system computes two **honest proxies** from data we already fetch, exposed as a single aggregate signal.

### 24.2 Required Behavior

**Earnings trend-break** — fit a linear regression on the **3 quarters preceding** the latest reported quarter (revenue and profit), project an expected value for the latest quarter, compare against the actual, and classify:

| Profit surprise (vs trend projection) | Verdict |
|---|---|
| > +30% | `BIG_POSITIVE_BREAK` |
| +15% to +30% | `POSITIVE_BREAK` |
| −15% to +15% | `IN_LINE` |
| −30% to −15% | `NEGATIVE_BREAK` |
| < −30% | `BIG_NEGATIVE_BREAK` |

Profit surprise is primary; revenue surprise is the fallback when profit is flat or loss-making.

**Brokerage-action signal** — scan the last 7 days of Google News RSS headlines for upgrade/downgrade patterns. Strict filter (to cut false positives): a headline only counts if it contains an upgrade/downgrade verb AND either names a brokerage from a curated list (Motilal, Nomura, CLSA, Jefferies, Morgan Stanley, Kotak, etc.) OR carries an explicit "target Rs X" amount. Net score = upgrades − downgrades over the 7d window; bucketed into `POSITIVE_FLOW` / `MIXED_POSITIVE` / `NEUTRAL` / `MIXED_NEGATIVE` / `NEGATIVE_FLOW` / `NO_COVERAGE`.

**Aggregate signal** — [AnalystSignalService](src/main/java/com/example/trading/ai/AnalystSignalService.java) combines the two into a bounded score in `[-10, +10]` (trend-break contributes ±7, brokerage flow ±3) with a final verdict: `STRONG_POSITIVE` / `POSITIVE` / `NEUTRAL` / `NEGATIVE` / `STRONG_NEGATIVE`.

### 24.3 Integrations
- **Multibagger Screener** — post-composite bonus of up to ±5 points (half-scaled from the aggregate score so news noise can't dominate the 7-dimension scoring). Verdict surfaced in bullish/bearish factors.
- **Deep Research** (`/api/research/{symbol}`) — new data dimension 18 "ANALYST SIGNAL" fed to the AI, with a methodology disclaimer that this is NOT paid consensus.
- **Holdings report** — any holding with verdict `NEGATIVE_BREAK` or `BIG_NEGATIVE_BREAK` appears in a new "Earnings Trend-Break Alerts" section (daily + weekly).
- **REST** — `GET /api/research/analyst/{symbol}` returns both proxies + aggregate with methodology note.
- **Analyst target ledger (§49, 2026-09-12)** — the brokerage scan's rupee target used to be counted
  and then discarded. It is now recorded, attributed and measured. The two features share one house
  vocabulary (`Brokerages`) rather than two copies of it, and they divide cleanly: a rating change
  with no figure stays here as flow, a figure with a named house becomes a ledger row there.

### 24.4 Explicit Non-Goals (Phase 2)
- Real paid analyst consensus (EPS estimates, price targets) via Bloomberg / Refinitiv / Trendlyne Pro.
- ~~Price-target-vs-current-price gap analysis.~~ Partly addressed by §49 from headline-quoted
  targets — recorded and measured, never treated as consensus, and thin by construction (§49.7).
- Event-driven alerts on upgrades/downgrades (the signal is batch-aggregated, not real-time).
- Brokerage-reputation weighting (Motilal vs. smaller firms are treated equally).
- Historical back-test of the proxy's IC vs. actual post-earnings drift.

### 24.5 Tradeoffs Flagged to Users
Both the report output and the Deep Research AI prompt call this out explicitly, per §21 Communication Style:
1. Trend-break is **not** a comparison to analyst consensus — it's a deviation-from-own-trend proxy.
2. Keyword-matching news is **noisy** — expect a ~30% miss rate and occasional false positives.
3. Both are **supporting evidence**, never a sole trigger for any action.

---

## 25. Phase-2 Roadmap (Cross-Cutting)

Consolidated index of Phase-2 work items from across the spec, with cross-references. Future sessions should consult this block to avoid re-building something already planned.

### 25.1 Signal Quality & Calibration

| Item | Spec ref | Blocker / Prerequisite |
|---|---|---|
| ~~Per-dimension IC for QUANT_DISCOVERY & SECTOR_REVERSAL~~ | §23.2 | ✅ **Done 2026-05-24** — QUANT sub-scores persisted to `recommendation_dimensions` sidecar; SECTOR read from `sector_reversal_signals`. Source-aware `computeDimensionIC(source, horizon)` + `?source=` endpoint param. |
| Adaptive weight re-balancing from per-dimension IC | §23.3 | Need ≥ ~100 outcomes per (source × horizon × dimension) cell — earliest meaningful runs ~2026-07 |
| Intra-period target/SL hit detection (not just at anniversary) | §23.3 | Needs per-symbol candle scan — reuse the `candleCache` pattern from `computeDimensionIC` |
| Back-fill Institutional Interest on historical `multibagger_scores` rows frozen at 40 by the pre-2026-04-19 bug | §12.5 table footnote | One-time migration script; until then Institutional Interest IC will stay muddy |
| Back-fill `financial_quality_*` and `dcf_*` columns on historical `multibagger_scores` | §12.5, §6 | Re-run screening for past dates, or scripted NSE fetch + column update |
| Survivorship-bias correction for delisted / merged symbols | §23.3 | Currently those drop out of IC silently |
| `StockResearchService` as a recommendation-tracking source | §23.3 | Needs structured-verdict output (currently free-text AI prose) |

### 25.2 Analyst Signal

| Item | Spec ref | Notes |
|---|---|---|
| Real paid analyst consensus (EPS, price targets) | §24.4 | Out of retail budget — Bloomberg / Refinitiv / Trendlyne Pro; proxy stays primary |
| ~~Price-target vs current-price gap~~ | §24.4 | ✅ **Partly done 2026-09-12 as §49** — not from consensus (still unaffordable) but from targets quoted in headlines, recorded and then measured against what the price did. Coverage is thin and §49.7 says how thin |
| Event-driven alerts on upgrades/downgrades | §24.4 | Currently batch-aggregated; would need push/webhook layer |
| Brokerage-reputation weighting | §24.4 | Still equal-weighted in the §24 signal. §49.6's per-house record is the evidence that would justify weighting one house over another — and it withholds a hit rate below five resolved calls, so this is years away, not quarters |
| IC back-test of trend-break proxy vs actual post-earnings drift | §24.4 | Research task to justify the ±5 Multibagger bonus weight |

### 25.3 Intrinsic Valuation

| Item | Spec ref | Notes |
|---|---|---|
| Per-stock WACC (CAPM-driven cost of equity) | §12.5 DCF block | Currently fixed 12% — noisy for retail but worth revisiting |
| Sector-specific models (residual income for banks, through-cycle for commodity cyclicals) | §12.5 DCF block | Current single-DCF mis-values these; flagged in service Javadoc |
| Forward DCF / price-target generation | §12.5 DCF block | Deliberate non-goal (too assumption-sensitive for retail) — keep skipped |

### 25.4 Holdings & Thesis Drift

| Item | Spec ref | Notes |
|---|---|---|
| Persistent "alerted" state to avoid re-alerting same holding each report | §6.4 | Needs new column or audit table; currently every report re-evaluates from scratch |
| Morning-briefing integration of the decay list | §6.4 | Currently only in holdings report |
| Cross-exchange symbol matching (`BSE:NTPC` ↔ `NSE:NTPC` score history) | §6.4 | Currently each exchange is its own bucket |
| Loosen `findClosest()` ±10-day tolerance OR switch to nearest-older-than-target matching | — | Many NO_DATA reasons today are sparse-history; more lenient matching would reduce them |
| Score-decay for non-holdings | §6.4 | Explicit non-goal (use `GET /api/multibagger/trend/{symbol}` for that); revisit only if a real use-case appears |

### 25.5 Feedback Loop (Deliberately Deferred)

From §23.3: **adaptive re-weighting based on IC is blocked until we have ≥ 3 months of outcome data**. Explicit decision: measure first, tune later. Revisit around 2026-07 when:
1. `recommendation_outcomes` has ≥100 rows per (source × horizon) cell
2. Per-dimension IC is stable across two consecutive weekly runs
3. Institutional Interest back-fill is complete so its IC signal isn't distorted by legacy zero-variance rows

**Row count is not sample size (2026-08-29).** Condition 1 now reads 8,933 rows at 30d and 3,007 at 90d for MULTIBAGGER, and it should not be treated as met. Those rows are ~82 screening dates × ~300 stocks: the cross-section moves together with the market and 30-day windows overlap, so the effective independent sample is roughly **five periods**, which is why the engine's own edge measures +1.46pp excess at t≈1.24 (p≈0.28). The horizons that match a 1–3-year mandate are worse off than that — **180d and 365d have no matured rows at all** (first picks were issued Apr-2026; 180d matures ~Oct-2026, 365d ~Apr-2027). A learner fitted today fits one risk-on quarter, which is the failure that ended the previous ML chain.

**Condition 1 restated (2026-09-05)**: not rows, but **≥ 12 independent, embargoed periods at the horizon under review**, exactly as §38.10 codifies it. The three numbered conditions above are superseded by the five in §38.10; they are kept here for the record of how the bar was first written.

**Prerequisites shipped instead (§38).** Scoring provenance and the per-signal coverage vector — the two things that must exist *before* any learner, and the absence of which made the earlier models unauditable after the fact. Neither changes a score.

### 25.6 Infrastructure / Quality of Life

| Item | Rationale |
|---|---|
| ~~JSON-returning dashboard endpoint (`/api/dashboard/summary`)~~ — **✅ Done 2026-08-24**, see §27 | Enabled the dashboard UI without breaking the email-first contract |
| Unit tests — `src/test/java/` is empty | Any scoring-engine change is currently untested; regression risk is the main blocker on weight-tuning |
| Convert stale pre-2026-04-19 `*.md` root files (`BACKTEST_*.md`, `ENHANCEMENTS.md`, etc.) into an `archive/` folder | Reduces noise; SPEC.md + CLAUDE.md are authoritative today |

### 25.7 Not Planned (Explicit No)

These requests have come up or are likely to; recording the decision so they don't get re-proposed:
- ~~**A web UI**~~ — **reversed 2026-08-24.** Now a core goal, specified in [§27](#27-dashboard-ui). The original rationale ("reports via email are the interaction surface") did not survive contact with the fact that ~21 fast DB-only endpoints existed with no reader, and that email cannot render a sortable table or a six-month time series. Scope is deliberately narrow: local, read-only, market-hours-only.
- **Real-time intraday signals** — `§19` non-goal. The mission is long-term portfolio investing, not day trading.
- **Short-term price-prediction models** *(2026-09-05)* — `§19` non-goal. A 30-day direction classifier was exactly the deleted ML chain (§3.1); the mandate is judged at 180/365 days and beyond (§40).
- **Reviving signal-gated accumulation** *(2026-09-05)* — §8.2, B-077. The mode is dead in the data model and stays that way; the fix is to refuse it, not to find it a trigger.
- **Multi-account / multi-user support** — `§2` scope, single retail investor.
- **Broker other than Zerodha Kite** — pluggable interface exists, but no other implementation planned.

---

## 26. Target-Hit Alert ✅ Active (MVP, 2026-05-24)

### 26.1 Purpose
Tell the investor, by email, **which specific stocks have reached their target price** — so a target hit becomes an actionable review prompt (book profit / raise stop / re-check thesis) rather than something buried in the weekly accuracy aggregate.

### 26.2 Required Behavior
**Sources (two):**
- **Recommended picks** — the latest open recommendation per symbol from `QUANT_DISCOVERY` and `SECTOR_REVERSAL` that carries a `targetPrice` (issued within the last 365 days). `MULTIBAGGER` is excluded (it issues no per-pick target).
- **Holdings** — owned stocks (`findActive()`) vs their `suggestedTarget1`.

**Hit test:** live price (recommendations via `MarketDataService.getCurrentPrice`; holdings via the synced `currentPrice`) **≥ target**.

**Newly-hit only:** each target is alerted **once**. Dedup is persisted in `target_hit_events` keyed `source|symbol|targetPrice` (2 dp) — survives the daily restart and re-scans. A genuinely new/higher target for the same symbol re-alerts; a re-issued identical target does not.

**Delivery:** one email, sent **only on days something newly hits** (no email on quiet days). Two sections (holdings first, then picks). Follows §21 — opens with a plain-English "what this means" box; frames a hit as a *review* prompt, not auto-sell advice.

**Schedule:** `0 5 15 * * MON-FRI` (15:05 IST), `MarketHoursService.isMarketOpen()`-guarded per §3.4. Manual trigger: `POST /api/alerts/target-hits/scan`.

### 26.3 Snapshot Semantics & Out of Scope
- Uses the **price at scan time** ("at-or-above target as of today's session"). A target touched intraday but pulled back below by 15:05 is not caught — true **intra-period target/SL hit detection** remains the §25.1 Phase-2 item.
- No auto-execution (long-only, manual decisions — §19).
- Does not replace the §23 accuracy `targetHit` rate (anniversary-based, aggregate); this is the per-stock real-time-ish notification layer on top.

**Files:** [TargetHitAlertService.java](src/main/java/com/example/trading/alerts/TargetHitAlertService.java), [TargetHitController.java](src/main/java/com/example/trading/alerts/TargetHitController.java), [TargetHitEventEntity.java](src/main/java/com/example/trading/persistence/TargetHitEventEntity.java) + repository.

---

## 27. Dashboard UI ✅ Active (Phase 1, 2026-08-24)

### 27.1 Purpose

A **local, read-only web dashboard** that makes the analysis this app already produces explorable. Served by the app itself at `http://localhost:8080/` from `src/main/resources/static/`.

Reverses the §19 non-goal recorded until 2026-08-24 (see §25.7 for the decision record). The driver: ~21 fast DB-only endpoints existed with no reader, and email cannot render a sortable table, a drill-down, or a six-month time series. **Email remains a first-class surface** — the dashboard is additive.

### 27.2 Scope

**Four module groups, seven screens:**

| Screen | Covers |
|---|---|
| `index.html` | Triage — portfolio KPIs, value-over-time, "what needs your attention today" |
| `holdings.html` | Holdings table + sparklines, sector/market-cap mix, HHI, drift, thesis decay, conviction, dividends, accumulation, tax lots |
| `screener.html` | The business first (2026-09-09, B-098): financial quality, ROCE (ROA for a lender), debt/equity, profit growth YoY, promoter holding + change + pledge, valuation (reverse-DCF verdict, P/E vs sector), red flags (forensic, HIGH_RISK, pledge > 20%), market cap in crore, sector from NSE's index classification; plus composite (with grade, rank and the 30-day change *vs the universe shift* folded under it), the compounding gate, the timing verdict and entry ladder, under-radar and buyability. The seven dimension bars are opt-in ("Show the seven scores"). Filters: shortlist (compounder ∧ fair entry ∧ not owned), sector, size, grade, owned, compounding, risk (hide red flags / not yet checked), free-text search. Headline panel is "Compounders at a fair price" with its funnel counts |
| `discovery.html` | Early-stage view: under-the-radar candidates, buyability, insider activity, universe expansion funnel, recent-listing watch (§12.9, §12.10, §28, §30) |
| `accuracy.html` | Source × horizon calibration, IC, per-dimension IC |
| `market.html` | FII/DII summary and watchlist teaser (top 5 by verdict) — loads `/api/fiidii/summary` and `/api/watchlist/items` only. The index-direction/PCR, breakout and sector-reversal panels went with their engines on 2026-09-03 (§39) |
| `ipo.html` | IPO pipeline (§45): open / forthcoming / just-closed issues with the structure read and application sizing, the category guide with the shareholder-quota prerequisites, recent listings with lock-in calendar, cycle stage and on-demand quality analysis. Loads `/api/ipo/pipeline` and `/api/ipo/recent` only |
| `watchlist.html` | Watchlist tracking (§37): add/remove, date added, return since added, vs Nifty, sector, quality + timing, 90-day trend, "still a good time to buy?" verdict, removed-stocks history |
| `stock.html?symbol=` | Per-stock drill-down: price/score history, 7-dimension radar, research endpoints |
| `reports.html` | In-page previews of the actual report HTML (see §27.6) |

### 27.3 Technical constraints (binding)

- **Static files only.** No npm, no build step, no new Maven dependencies, no runtime CDN.
- **Multi-page, not an SPA.** Deliberate: a per-page script loads a bounded, auditable set of requests that can be read top-to-bottom and proven never to touch a forbidden endpoint. An SPA keeps all modules mounted and makes accidental prefetch easy.
- **Components return DOM nodes, never HTML strings.** String-templating in JS would re-weld data to markup at a new layer — the same defect being fixed on the Java side — and node-building gives XSS safety free via `textContent`.
- **CSP meta tag** (`default-src 'self'`) on every page makes "no CDN" structurally enforced rather than promised, and forces all event wiring through `addEventListener`.
- **Charts are hand-rolled SVG** (`js/charts.js`). Data volumes are small (≤ 1095 points/series). No charting library is vendored: a minified blob would carry no lockfile, provenance, or CVE tracking, and SVG round-trips into the HTML emails later where canvas cannot.
- **`symbol` is always a query parameter**, never a path variable — symbols contain a colon (`NSE:RELIANCE`).
- **`spring.web.resources.static-locations` must list `file:./src/main/resources/static/` first.** `start-app.bat` runs `mvn clean compile`, so `classpath:/static/` resolves to `target/classes/static/` and every UI edit would otherwise require a full rebuild + restart.

### 27.4 Endpoint safety — the forbidden list

**These MUST NOT be called on page load.** Reproduced verbatim because the cost of getting it wrong is minutes-to-half-an-hour of broker quota, or an unwanted email:

| Endpoint | Why |
|---|---|
| `GET /api/multibagger/screen/{symbol}` | 5–20 s, writes DB. Button-only, confirm-gated |
| `GET /api/multibagger/screen/tier/{tier}` | 369 symbols, **30+ minutes**. **Not exposed in the UI at all** |
| `GET /api/research/*` | **Sends an email** — except `/levels`, `/analyst`, `/valuation`, `/earnings`, `/capital-efficiency`, `/shareholding`, which are the only six the UI may call, button-only |
| `GET /api/accuracy/dimension-ic` | Hits Kite despite being a GET. Button-only, warned |
| `GET /api/fiidii/report` | Can trigger a live NSE fetch. Use `/api/fiidii/summary` |
| `GET /api/fiidii/debug-raw` | Clears caches. Never called |
| ~~`POST /api/trading/breakout/scan`~~, ~~`POST /api/trading/holdings/train-models`~~ | Endpoints deleted (2026-09-03 and 2026-08-28). Rows kept struck-through so the list is seen to shrink for a reason, not by accident |
| `POST /api/concall/analyze/{symbol}`, `POST /api/insider/capture`, `POST /api/universe/scan`, `POST /api/fundamentals/backfill*`, `POST /api/portfolio/core-holdings/classify`, `POST /api/learning/review` | Live NSE/Kite work from seconds to 22 minutes, several email. Button-only where exposed at all; most carry a 409-with-reason refusal near the 14:00 / 14:55 crunch (B-049) |
| `POST /api/watchlist/analyze`, `/analyze-and-report` | Re-analyses every tracked symbol (2 Kite calls each) and the second one emails. Not exposed |
| `POST /api/watchlist/items`, `/items/refresh` | Live Kite calls (2–4, or 5–20 s with `quality=true`). Button-only via `post()`, never on load; the server refuses them from 14:55 with a 409 |
| `DELETE /api/portfolio/tax-lots/all` | Destructive. **Never rendered in the UI** |

The six-endpoint research allowlist is hard-coded as an array in `page-stock.js` so a future edit cannot casually add an email-sender.

**`POST /api/portfolio/tax-lots/harvest` and `POST /api/portfolio/rebalance` are POSTs with no side effects** — safe to call, but must be labelled "Read-only. Nothing is sold."

### 27.5 Security posture

- **Localhost-bound.** `server.address: 127.0.0.1`. The app previously bound `0.0.0.0`, exposing every endpoint — including account data and the destructive DELETE — to every device on the network. Trade-off accepted: no phone/tablet access.
- **Never add CORS** (§20 rule 8). `DELETE /api/portfolio/tax-lots/all` is unreachable from other origins *only* because a cross-origin `DELETE` triggers a preflight that no CORS config answers. The dashboard is same-origin and needs none.
- **No authentication.** Single user, single machine, localhost-bound, no cross-origin reach. Auth here would be theatre.
- **Residual pre-existing gap:** a `<img src>` or cross-origin form can still trigger an expensive GET/POST server-side (no CORS involved). Mitigated by a `Sec-Fetch-Site` filter rejecting non-same-origin requests to a named list of expensive paths.

### 27.6 Report previews — HTML reuse, not JSON-ification

The richest analysis (portfolio health score, next-steps, action-required, market-intelligence overlay, sector rotation, wealth signals, capital efficiency) is computed *inside* `String.format` argument lists in `HoldingsReportService` (2646 lines) and `MorningBriefingService`, with no DTO surviving the method.

JSON-ifying ~38 `buildXxx()` methods is a multi-week refactor with real regression risk to reports read daily. Instead, `GET /api/reports/{name}/preview` returns the **same HTML the email sends**, produced by the same code path, displayed in an iframe. ~40 lines instead of weeks, and it closes the entire gap.

Structured `compute*() → record` extraction is done **only** where a chart genuinely needs numbers (top movers, sector rotation, score distribution), not as a blanket refactor.

**Preview endpoints are click-triggered and server-side cached 15 minutes** — mandatory, because they make real AI calls.

### 27.7 Availability & freshness

The dashboard is served *by* the app, which Task Scheduler starts at 09:00 and stops at 15:45 on weekdays. **It is therefore reachable ~4% of the week**, and when the app is down the browser gets connection-refused — there is no page on which to show a message.

The UI must distinguish two independent kinds of staleness and never conflate them:

| | Meaning | Source |
|---|---|---|
| **Server reachability** | Is the JVM answering right now? | fetch success |
| **Data freshness** | How old is the data *in the database*? | `GET /api/dashboard/health` |

A live server can serve Friday's prices on Monday morning. Every screen shows "Prices as of…" / "Scores as of…" independent of connection state, amber-tinted past one trading day.

On fetch failure, `api.js` renders the last successful response from `localStorage` with a persistent amber strip naming the timestamp, or a full-page explainer pointing at `start-app.bat` when no cache exists.

### 27.8 Out of scope (Phase 1)

- **`GET /api/dashboard/exit-alerts`** — deferred. `ExitTimingAlertService.evaluateHolding()` mutates `sentAlertsToday` inline, so exposing it read-only requires first lifting that dedup out into `checkExitConditions()` (a `recordDedup` flag). Done carelessly, a dashboard page-load silently empties the next alert email. The Action Items screen currently covers the same ground via `/holdings/exit-candidates` and `/holdings/decay`. Same hazard class, also deferred: `TargetHitAlertService`, `AccumulationReminderService.DueItem`, `SectorReversalPerformanceService` value picks.
- **All writes — with two carve-outs (2026-08-27, 2026-09-09).** No screen mutates data on load. **Exception 1: the watchlist page (§37)** — add, remove, refresh and note-edit are the first sanctioned UI writes, because a watchlist that cannot be edited from the screen is a config file with a viewer. **Exception 2: the portfolio page (§46.7)** — thesis editor, dividend log / mark-received, tranche fill and plan cancel, for the same reason: the page is where the state is read, so it must be where it can be corrected. All go through `api.js post()/put()/del()`, are click-only, and surface the server's refusal reason verbatim.
- Authentication, multi-user, mobile/responsive layout, dark mode.
- Real-time push / websockets — everything is request-response.
- Tier screening and any other multi-minute scan trigger.
- Charts over the narrative report sections (the iframe covers them; §27.6).

### 27.9 Column layout — resize and reorder ✅ *(new 2026-08-28)*

Every dashboard table renders through one helper (`table()` in `ui.js`), so column resizing and
reordering are wired **once** there and all eighteen tables get them. Drag a header to move a
column; drag the right edge of a header to resize it. The arrangement is remembered per table in
that browser's `localStorage` and never reaches the server — it is a display preference, not data.

**Rules that keep it from breaking the tables it decorates:**

1. **A drag must not sort.** Headers sort on click and a click fires after the pointerup that ends
   a drag, so `recentlyDragged()` (300 ms) suppresses it. Without this, every reorder would also
   silently re-sort the table.
2. **Fixed layout is entered on first drag, not at render.** A width is only binding under
   `table-layout: fixed`, which redistributes *every* column — so the first resize measures the
   widths the browser already chose and pins all of them, and only the dragged edge moves. A table
   nobody touches keeps content-driven sizing. Under fixed layout the table sizes to
   `max-content` so the wrapper scrolls rather than squeezing the chosen widths back.
3. **A saved layout never outlives the columns it describes, nor the CSS regime it was measured
   under.** The storage key is derived from the column keys (sorted, so the reader's own order does
   not change it), so adding or removing a column yields a new key and the stale arrangement is
   simply never read. Within a layout, unknown keys are dropped and unmentioned columns appended in
   declared order — a partially stale layout degrades to "mostly yours" rather than losing a column.
   The key also carries a **schema token** (`LAYOUT_SCHEMA`, `v2` as of 2026-09-01), bumped whenever
   the cell-sizing CSS changes. A width is only meaningful under the rules it was measured in:
   widths saved before §27.10 were taken when every cell was `nowrap` and one line long, and because
   a restored layout re-enters `table-layout: fixed` with explicit `<col width>`, those stale widths
   **override the wrapping entirely** and the table scrolls sideways forever. Measured on the
   watchlist: 1950 px needed against 1527 available, overflow +423, with no way for the reader to
   tell why. Bumping the token retires them; the reader's next drag saves a fresh one.
4. **Column identity is fixed from the declared order.** Deriving it from the displayed position
   would rename a keyless column each time it moved.
5. **Storage failure is never a render failure.** Reads and writes are wrapped: private windows
   and blocked site data lose the preference, not the table.

A "Reset to default" control appears under a table only once its layout has been customised.
Opt a table out with `table(cols, rows, { layout: false })`.

### 27.10 Cells wrap; tables do not scroll sideways ✅ *(new 2026-09-01)*

A table that runs off the right of the screen hides its own columns. Every cell therefore wraps
onto a second line rather than widening its column, and the page is sized so the tables fit.
`.table-wrap` keeps `overflow-x: auto` as a backstop — a table that genuinely cannot fit scrolls
rather than clipping — but it is a backstop, not the normal case.

**Rules:**

1. **A cell's minimum width is what actually decides this.** A table can never be narrower than
   the sum of its columns' minimum widths, and `overflow-wrap: break-word` leaves a long word as
   an unbreakable floor. Only `overflow-wrap: anywhere` lowers the minimum. Prose cells therefore
   use `anywhere` — the browser still breaks at spaces first and splits a word only when there is
   no room.
2. **Four things keep an ordinary floor**, because splitting them mid-character destroys them: a
   ticker (`NAVINFLUO / R` is not a stock), a pill, a number (`Rs 1,234.56` broken after the space
   reads as two figures — right-aligned is this codebase's convention for a number), and a header
   label. A handful of honest floors still leaves the table able to fit.
3. **Headers wrap.** A label held on one line was frequently setting the width of a column whose
   data is a short badge — the header, not the data, was what made these tables scroll.
4. **A squeezed marker must stay readable.** The striped "not measured" pill appears in every
   nullable column at once, so it was the widest floor in the screener; it is *trimmed* (smaller,
   no letter-spacing) rather than broken. Allowed to break anywhere it became a vertical stack of
   single letters, which the reader has to decode — worse than the column being wider.
5. **A label set under a number is two or three words.** `SuggestedEntry.basis` is displayed in a
   narrow column; a longer phrase wraps into a stack that makes every row taller. The full
   sentence lives in `reason`, which the cell carries as its tooltip.

**Measured 2026-09-01** at 1920 / 1600 / 1366 px, all 13 tables on 6 pages: everything fits at
1920. The screener was the only table that could not always fit — 23 columns with a hard floor of
1578 px — and it scrolled slightly below ~1630 px of viewport. Reducing that further would mean
breaking tickers or header words mid-character, which rule 2 forbids; the remaining lever is
fewer columns, which is a product decision, not a CSS one.

**Re-measured 2026-09-09** after the business-first rebuild (B-098): the screener's default table
is **19 columns with a floor of 1518 px** — it fits at 1600 and 1920 and scrolls at 1366, as the
23-column table did below ~1630. The product decision was taken: Rank and Grade fold under the
Score, Size under Market cap, Owned under the ticker, and the seven dimension bars are opt-in
(with them shown the table is 26 columns and scrolls on every monitor, documented on the chip).
Three floors were labels rather than data and are worth remembering: the Buyable pill rendered
`MODERATE` as "Moderately Concentrated" (the HHI vocabulary, 105 px — now "Moderate"), the DCF
pill said "Extremely Expensive" (102 px — now "Very dear", full words in the tooltip), and an
unmeasured promoter holding was drawn as nowrap text rather than the striped marker (97 px).
**Re-measure whenever the default column set changes**, headless: `--dump-dom` on a same-origin
throwaway page that iframes the screener and writes `scrollWidth` into the DOM, at 1366 / 1600 /
1920, plus a screenshot read by eye (Gotcha 89) — then delete the throwaway page.

`--page-max` (1700 px) is the single knob for page width and is shared by all four chrome bands
(topbar, freshness strip, banner, main) so they stay aligned.

---

### 27.11 Guide page — "How to use this app" ✅ *(new 2026-09-03)*

A plain-English playbook at `guide.html`, written for someone who is **not** a market expert
(§21): no ratios, no statistics vocabulary, every number in rupees or a simple count. "57 out of
100 picks beat the market" rather than an information coefficient.

It does two things, and the second is what makes it more than an article:

1. **Explains the method** — what to trust, the three things that actually build wealth here
   (hold past one year for the lower tax; avoid companies with problems in their accounts; buy in
   three parts), **what the score is made of** (added 2026-09-06), a monthly/quarterly routine,
   and what to ignore.
2. **Checks the investor's own portfolio against that method**, live — how spread out they are,
   which holdings say "do not add", where the accounts look risky, and how big the top five are.
   Each check is a plain finding plus one "what to do" line.

**The honesty requirement is the point.** The page states plainly that the app's record is five
months long, that it has **no completed 1-year results**, and that a coin toss is right 50 times
out of 100 — so the scores decide *what to look at*, not what to buy. A guide that oversold the
scoring would do more damage than no guide at all.

DB-only on load: holdings (already decorated per §6.6), the accuracy summary and — since
2026-09-09 — the latest screening run (`/api/dashboard/screener`, the identical call the Screener
and Discovery pages make), all three verified DB-only by hand — a page-load `get()` is ungated
(Gotcha 39).

**"What the score is actually made of"** *(added 2026-09-06)*. The screener and stock pages draw
the composite as a seven-sided shape, and nothing explained it. The section names the seven checks
as questions in the reader's words — *"Is it cheap for what it earns?"* rather than "valuation
dimension" — and then says the two things that matter more than the list: a **gap in the shape
means the check could not be measured**, never that the company scored zero (§21 rule 7); and the
**first four checks are all about the share price and together outweigh the three about the
business**, which is the honest reason a high score is a shortlist and not an answer (§40.2). It
closes by noting the eighth check was removed in September 2026 and why, so a reader who
remembers eight is not left wondering.

**"Which page to use, and what for"** *(added 2026-09-09)*. The investor asked what the difference
between the **Screener** and the **Discovery** page actually is, and the guide could not answer:
it named the Screener in the monthly routine and **never mentioned Discovery at all**. The answer
is that both read the *same screening run* and differ only in the question asked of it — the
Screener ranks it, Discovery asks whether a stock can actually be bought, whether the people
running it are buying, whether anything is still overlooked, and separately lists what the §30
funnel found in the wider market (observation mode, and the page says so). Four pages get one line
each on what they answer and what to use them for, the stock page included, because the two
long-horizon records (§42, §43) appear in full nowhere else.

Two live callouts carry the argument, and both are **derived from the run being described** rather
than written into the prose, so they cannot go stale against the page they send the reader to
(Gotcha 98). Measured on 2026-09-09: setting **Compounding → “Compounders only”** and sorting by
score takes **284 screened → 109 at 70+ → 16** that also have a compounding record; and of the
**54** stocks carrying the app's highest verdict, only **10** are compounders while **5** fail that
check outright. That second figure is the honest form of §40.2's warning — the composite is 59%
price behaviour by weight, so its top verdict finds what is rising now, which is a different
question from what is still worth owning in ten years. When no screening run is available both
callouts are **omitted**, never rendered as zeros (§21 rule 7).

**The position-size check is arithmetic, not a market opinion.** A holding worth 2% of the
portfolio can triple and still barely move the year, while it can still go to zero; below roughly
4% a position is not worth the trouble of following. Measured on the live portfolio: 33 holdings on
₹2.5 L is about ₹7,600 each, so the page tells the investor to concentrate new money rather than
buy a 34th name.

### 27.12 Refresh — re-read, never re-run ✅ *(new 2026-09-10)*

Every screen carries one refresh control, at the right-hand end of the freshness strip
(`nav.js`, so eleven pages share one implementation). It re-reads `GET /api/dashboard/health`
and re-runs the page's own DB-only loads, then repaints.

**It never re-runs an analysis, and the placement is the design saying so.** In this API a `GET`
can email a report or start a thirty-minute scan (§27.4); the Kite budget is a single
process-wide ~2.9 req/s gate that a manual job has already been shown to starve the afternoon
schedulers out of (B-049); and the screening these pages read takes 30+ minutes and is refused
inside the crunch windows anyway. So on a dashboard "refresh" can only honestly mean *ask the
database again*. Putting the control beside the timestamps rather than making it a primary
button is what stops it reading as a **Run** button.

**The control must report whether anything actually moved.** Everything on these screens is
written by a scheduled job, so between two runs a refresh returns byte-identical rows. A button
that answers "updated" then is a small lie told several times a day, and the cost is the
investor learning to disbelieve the screen — the same reasoning `api.js` already applies to the
offline banner (B-086). So the freshness stamps for the keys *this page* depends on are compared
across the click, and the result is one of:

| Outcome | Wording |
|---|---|
| A relevant stamp moved | *Updated — new prices.* (green) |
| Nothing moved | *Checked — no new results yet.* + link to `health.html` |
| The page declares no stamps (`health.html`) | *Checked again.* |

Three rules follow. **One key list, two readers** — the strip and the refresh message resolve the
page's freshness keys through the same function, or they can disagree about what the page depends
on (found in review: the stock page was stamped with prices but compared against nothing).
**`initChrome()` is idempotent** — a page registers its whole entry function, so the chrome is
built once and every later call only re-reads health; prepending a second header on each refresh
is how this goes wrong. And **a page registers its reload only after its first load completes**,
or a click landing mid-load starts a second one on top of it.

Two pages opt out, both deliberately. `guide.html` has no stored data behind it, so a control
that would report "nothing new" for ever is worse than no control. `reports.html` registers no
reload: a report is built on demand, cached server-side for 15 minutes, and re-mounting the panel
would only close whichever report is open — so the button there does the honest floor, which is
re-reading the freshness strip.

**Not a substitute for the freshness strip, and not an auto-refresh.** Nothing polls: a timer
that re-fetched every minute would spend the whole session re-reading rows that change eight
times a day, and would make the strip's timestamps — the thing that actually answers "is this
current?" — look redundant.

### 27.13 Filtering — chips first, text second ✅ *(new 2026-09-10)*

**The primary control is a bar of predefined chips**, in `filters.js`. The screener grew a good
one and nothing else could use it; that bar's *mechanism* — state, chips, counts, the Clear
control — now lives in `chipFilters()` and every page declares its own **groups**, because
"narrow this list" means different things on a screening run, a portfolio and a set of new
listings. One mechanism, many vocabularies: the same split as `table()` and `buy-timing.js`, and
the reason a fix here reaches every screen.

Where the bars are, and what they ask:

| Screen | Groups |
|---|---|
| Screener | shortlist · size · grade · owned · compounding · risk · sector · seven-scores toggle |
| Discovery | compounding · owned · size · risk · sector — applied to the **universe** the three stock lanes search, not per lane |
| My Portfolio | tier · signal · compounding · in profit/at a loss · sector |
| Watchlist | timing verdict · quality · since added · sector |
| IPOs | cycle stage · offer structure · against issue price · analysed-only |

Three rules the chips obey.

**Every chip carries its count** — `Compounders only (35)`, `Core (0)`. Without it a chip is a
promise the data may not keep: "Core" on a portfolio whose holdings are all satellite today looks
like a filter that broke, and the reader cannot tell an empty result from a bug. The count also
does the page's own reporting for free, before the click rather than after it.

**A chip never folds "not measured" into a verdict.** "Compounders only" excludes NOT_MEASURED
rather than sweeping in businesses whose accounts could not be read; "Hide red flags" keeps the
UNCHECKED visible; the portfolio's tier group gives UNCLASSIFIED its own chip rather than letting
it fall in with SATELLITE (Gotcha 21, 44, 68). Where the distinction matters the group offers the
third chip explicitly, so the reader can ask for the unexamined ones on purpose.

**A narrowed list says so** — `35 of 279 stocks shown — Clear filters`, and only while something
is actually narrowing it. A count printed on every page load is noise, and noise trains the reader
to stop reading the line that matters when it finally says something.

#### Text filter, second

Every table with **15 or more rows** that no chip bar governs also carries a plain text box, built
into `table()` in `ui.js` alone for the same reason column resize/reorder is (§27.9, Gotcha 87).
A table a chip bar governs passes `filter: false`: its search lives in the bar with the chips it
works alongside, and two boxes on one screen is the same one-question-two-controls confusion that
"Refresh" and "Refresh from NSE now" caused on the IPO page. The 15 was 8 until the chip bars
landed — with a box over every incidental twelve-row list, the discovery page had five of them.

**The haystack is the rendered row text *plus* each declared column's raw field**, and it needs to
be both. Rendered text is what the reader is looking at and typing back ("Wait for a dip",
"Banking"); the raw field is the vocabulary underneath it, which is often the word they actually
know — `compounding` renders as "Yes" and the reader searches "compounder". The raw side must read
`row[col.key]` and **never `col.value(row)`**: `value` is the *sort* accessor and for the
interesting columns it is a rank, so `compoundingRank` would have the filter searching the number
`0` for the word "compounder". Found by running it, not in review. Nested records (the shared
buy-timing verdict, the IPO structure read) are stringified, or they contribute
`"[object Object]"` to every row and match nothing.

**A filtered table must say what it is hiding.** `12 of 284 rows match "bank" — Clear`. A table
narrowed to twelve rows under a heading that says 284, with nothing saying why, is a table quietly
misrepresenting the universe it was built from, and the reader has no way to tell a narrow filter
from a thin market. Same discipline as a coverage line (Gotcha 44).

The wording is deliberately **rows matching a term**, not "showing N of M": the screener already
prints "279 of 279 stocks shown" for its chip filters directly above, and two counts phrased alike
invite the reader to work out which is lying. Neither is — they answer different questions, so
they are worded differently.

Three mechanics. Below 8 rows there is no box (a control above a table the reader can already read
whole is clutter on every page load); `filter: false` suppresses it explicitly. Only `paintBody()`
runs on a keystroke, never `render()` — re-running the latter would re-arm the column-drag handlers
on every character and, under a saved layout, re-enter fixed layout (§27.9). And the box sits in
`.table-block`, **outside** `.table-wrap`, because that element is the horizontal-scroll container
and a filter box inside it slides off-screen on exactly the wide table most likely to be filtered.

### 27.14 Confirm before anything leaves the building ✅ *(new 2026-09-10)*

Every other click on this dashboard is a database read. The IPO page's **"Capture from NSE now"**
is the exception — a few dozen live NSE calls plus a paced broker quote per recent listing,
measured at just over five minutes — so it states its cost and asks, rather than firing and
explaining afterwards.

The panel names four things: what it fetches, that it writes, **what it will not do**, and the
windows in which it is refused. The third is the one that matters. A mid-issue capture updates
subscription figures and prices but cannot make the structure read decide sooner — that waits for
`subscriptionFinal` on purpose, because institutions bid on the last afternoon (§45.3). Without
that sentence the button quietly promises an earlier verdict it cannot deliver.

**It is not called "refresh".** It used to be ("Refresh from NSE now"), which became a defect the
moment §27.12 put a control reading "Refresh" a few centimetres above it — one word, two meanings,
one screen, which is Gotcha 85's failure in miniature. Refresh re-reads; Capture goes out.

Its client timeout is **7 minutes**. It was 4, against a run measured at 5m13s — so a normal
capture aborted in the browser with "that took too long" while the server carried on and finished
it. A timeout shorter than the operation's measured duration is a bug, not a safety margin.

Per-row live actions (the IPO analyse button, the watchlist and holdings re-analyse) keep firing on
one click: they are seconds long and clicked repeatedly, and a confirmation on each would train the
reader to dismiss confirmations — which is what makes the one on the expensive action worthless.

### 27.15 Collapsible sections ✅ *(new 2026-09-10)*

Discovery measured **30,976px — about 31 screens** — and 84% of that was three tables:
Recent Listings 14,017px (238 rows), Universe Expansion 7,261px (120), Insider Activity 4,926px
(60). Every section now folds, via `collapse()` in `ui.js`, and the reader's choice is remembered
per section in that browser. Default height is **4,296px**.

**The count in the heading is what makes folding safe, and it is not optional.** Once sections
fold, the heading row *is* the navigation, and a folded section is one whose findings the reader
cannot see. The count is the only thing between "I chose not to look at this" and "I did not know
there was anything to look at". So `collapse()` is always applied over `withCount()`, and — per
Gotcha 98 — that count must be derived from the list it sits above. Building this exposed a live
instance: Recent Listings printed **54** while its table held **238**, because the pill was fed
`ready.length` (past the six-month mark) rather than `rows.length`. Survivable while the reader
could scroll past it; not survivable once it is the only thing on screen.

**The default is data-driven, not positional.** "All folded except the first" would open *Under
the Radar*, which reads 0 today, while folding the lanes that found something. So the first
section **with a non-zero count** opens and the rest fold. A page whose one open section is the
empty one is the opposite of a contents page.

Two things deliberately not done. **No auto-collapse on scroll or timer** — a section that folds
itself while the reader is reading it is a bug wearing a feature's clothes. And **state is per
section, not per page**: "collapse all" would be one click away from hiding a lane permanently,
and the per-section memory means a reader who opened Insider Activity in March still finds it open
in September.

**Filters follow the same split as §27.13.** The three *stock lanes* keep sharing the one
universe-level bar rather than growing one each: they are three questions asked of a single
screening run, so a chip there is a statement about the universe, and three near-identical bars
would be three things to keep in step. The three tables that are **not** screening rows — insider
filings, the expansion funnel, recent listings — get their own chips, because their rows share no
fields with a screening row and the universe bar cannot reach them. They are also, not
coincidentally, where the length was.

### 27.16 An empty column is a claim, and it has to be one of three things ✅ *(new 2026-09-10)*

Recent Listings carried three blank business columns. Measured on the live feed (2026-09-10),
they were blank for three *different* reasons, and only one of them is a bug:

| Column | Filled | Why |
|---|---|---|
| Fin. quality | **1 of 238** | The field is on the row and null. A company this new has no filings on file; `POST /api/ipo/analyse` fetches them per listing. **Work not done.** |
| Still good to buy? / How to buy? | **3 of 238** | Read off the screening row, and a recent listing is almost never in the screening universe (§30 expansion is in observation mode). **Structurally unanswerable here.** |

Neither is B-098's failure — there is no hidden data on the wire — and the fixes differ
accordingly.

**Work not done gets the control that does it.** `analyseButton` moved to `ipo-analyse.js` and
Discovery's listings table gained the column, so the same control serves both screens from one
implementation (the `watch-button.js` pattern). Verified end to end on NSE:EMCURE: the row went
from every business field null to `HIGH_QUALITY` / composite 95 / `STRONG_GROWTH` / promoter
77.83% / pledge 0.04%. The section states its coverage — *"Business quality has been fetched for
N of 238"* — rather than leaving the reader to infer it from a column of stripes (Gotcha 44).

**Structurally unanswerable gets the column removed.** A column reading "not measured" on 235 of
238 rows is not reporting a gap; it is spending column budget (§27.10) to say nothing 99% of the
time, and it makes a working table look broken. The two shared timing columns stay on the three
screening-row lanes, where they answer, and are gone from this one. The rule: **before adding a
"not measured" cell, ask whether this surface can ever fill it** — if it cannot, the honest render
is no column plus a sentence, not a striped marker implying the measurement is pending.

**Vocabulary belongs in the guide, linked from where it is used.** `HYPE_WINDOW / WASHOUT /
RECOVERING / BASE_FORMING / NOT_MEASURED` is the app's own vocabulary, not market usage — nobody
can guess "base forming", and a filter chip is the worst possible place to meet a word for the
first time. `guide.html#ipo-stages` explains all five with the same badges the tables draw, in the
order a listing travels, and both tables link to it.

**An anchor into an async page needs handling.** The guide builds itself from three fetches, so
the browser resolves `#ipo-stages` against an empty `<main>` and has given up long before the
target exists. `render().then(jumpToHash)` scrolls after mount and flashes the target briefly —
without it every link into the guide lands the reader at the top of a 7,300px page, which is the
friction the link was added to remove.

---

## 28. Insider Pulse (Daily SEBI PIT Disclosures) ✅ Active (MVP, 2026-08-25)

**Purpose.** The existing Insider Activity bonus reads the **quarterly** shareholding pattern — up to three months stale. SEBI PIT Regulation 7(2) filings are published **daily**, and open-market promoter buying is the most reliable early multibagger tell available for free.

### 28.1 Data sources (PIT source migrated 2026-09-08 — B-089)

| Source | Endpoint | Scope |
|---|---|---|
| PIT insider trading (**current**) | `/api/corporates-pit-gg?index=equities` → per-filing XBRL at `xmlFileName` | **all-market, 1 call/day** |
| PIT insider trading (pre-May-2026 archive) | `/api/corporates-pit?index=equities&symbol=X` | per-symbol only, frozen at 2026-05-01 |
| Bulk deals | `https://nsearchives.nseindia.com/content/equities/bulk.csv` | all-market, 1 call/day |
| Block deals | `.../block.csv` | all-market, 1 call/day |

> ⚠️ **The old PIT feed died on 2026-05-01 and nothing noticed for four months.** NSE circular `NSE/CML/2026/11` (04 May 2026) moved Reg 7(2)/7(3) onto the single filing system through API-based integration between exchanges, **effective 05 May 2026**; filings have been published as XBRL under `corporates-pit-gg` ever since, stamped `PIT V2.0 (30-04-2026)`. `corporates-pit` still returns HTTP 200 and still serves its **pre-May archive** correctly — what died is the feed, not the history, exactly as with `corporates-financial-results` in B-017 (§32.5). The two windows abut: the archive ends 2026-05-01, the new feed begins 2026-05-03, so the combined record has no gap.
>
> ⚠️ **The all-market form now works — on the new endpoint only.** This reverses the 2026-08-25 finding, which remains true of the *old* endpoint: `corporates-pit?index=equities` without a symbol returns a silent `{"acqNameList":[],"data":[]}`. `corporates-pit-gg?index=equities` returns the whole market — measured 2026-09-08, **2,381 filings across 495 symbols in one call**. That is what removed the per-symbol budget and its rotation (B-090).
>
> ⚠️ **The index is not the trades.** Each row is a filing header (`appId`, `symbol`, `broadcastDateTime`, `regulation`, `xmlFileName`); the transactions are in the XBRL. Ingestion is keyed on `appId` (unique across the feed), stored on `insider_disclosures.filing_app_id`, so each filing's XBRL is downloaded exactly once ever.

The archives host (`nsearchives`) has never been bot-walled, unlike `www.nseindia.com/api` (B-018), so the deal CSVs are the reliable half of the feed.

**The XBRL states the rupee value, which the old JSON usually did not.** `SecuritiesAcquiredOrDisposedValueOfSecurity` is populated, so the size-based half of the §28.3 verdict — the half that matters, since size relative to the company is the signal — now runs instead of falling back to event counts.

### 28.1a Why this ran undetected for four months, and what now catches it

Every component behaved exactly as specified. The 14:45 job ran daily and succeeded, the endpoint returned 200, `InsiderPulseService.classify` correctly returned `null` (never a fake `NEUTRAL`) for a stock with no filings in window, and the coverage vector recorded 0%. The zero was attributed to B-074, which was the known cause of a zero here — an exception list becoming the place the next regression hides (§44's own warning, one level up).

Two things now make it visible. First, `/api/dashboard/data-health` carries an **insider-feed check** keyed on the newest **PIT-sourced** row: bulk and block deals arrived every trading day throughout the outage, so a table-level freshness check would have read "up to date" on every single day of it — a tripwire watching an aggregate cannot see one contributing feed die. Silence beyond 7 days is a WATCH and beyond 21 days a PROBLEM, thresholds set well past any holiday cluster; on the real outage it would have escalated in the last week of May. Second, the capture logs the newest filing date **on the whole market**, not just for our universe, so a dead feed is visible even in a week when none of our own stocks filed anything.

### 28.2 Mode filtering is the whole feature

Only `MARKET_PURCHASE` / `MARKET_SALE` by `PROMOTER` / `PROMOTER_GROUP` / `DIRECTOR` / `KMP` / `RELATIVE` are scored. Everything else — pledge creation and revocation, ESOP allotments, inter-se promoter transfers, gifts, off-market deals, and bulk/block deals by institutions — is **recorded but never scored**.

This is not fastidiousness. Measured on the first live capture (1,272 rows across 80 symbols): **331 rows are scoreable — 74% of the raw feed is noise.** ESOP allotments alone were 158 rows and inter-se promoter transfers 49. A screen that counts a pledge creation as "promoter buying" is worse than no screen at all: a pledge is a promoter *borrowing* against the company.

### 28.3 Verdicts

Net open-market buy value over a trailing **90 days**, normalised by market cap — size relative to the company is the signal, absolute rupees are not.

| Verdict | Rule |
|---|---|
| `STRONG_ACCUMULATION` | net buying ≥ 0.25% of market cap, **or** ≥3 distinct **people** buying, no selling, aggregating ≥ ₹25 lakh |
| `ACCUMULATION` | net buying ≥ 0.05% |
| `NEUTRAL` | between the bands |
| `DISTRIBUTION` / `STRONG_DISTRIBUTION` | mirror thresholds on the sell side |
| **`null`** | **no disclosures on file, or only non-market ones** |

**The count path needs corroboration and size (B-041).** It counts distinct *people*, not filings — one director staggering a purchase across three days files three rows, and three rows by one person is one person's opinion. Where any value was disclosed it must also clear **₹25 lakh** in aggregate: SEBI PIT Reg 7(2) has no de-minimis threshold, so ₹1,000 filings are routine, and three of them used to return the strongest verdict the system has. Where nothing was disclosed there is no floor to apply, so a cluster of separate insiders remains the only available evidence and may still be strong.

**A blank type is absent, not a buy (B-040).** NSE writes `"-"` where a field is empty; it was read as a real value and, not starting with "s", classified BUY — so a `MARKET_SALE` *added* to net buying. Placeholders defer to the acquisition mode.

`null` ≠ `NEUTRAL`. "Nobody filed anything" is missing data; "insiders traded and it netted flat" is a measurement. When market cap is unknown or the filings omit values, the verdict falls back to **event counts** and says so in a note — unsized rupees never produce a directional verdict, because ₹4 lakh net is a strong signal in a ₹50 cr company and noise in a ₹50,000 cr one.

### 28.4 Shadow mode (the gate this feature ships behind)

**`trading.multibagger.insider-pulse-actionable` defaults to `false`.** The verdict is computed, persisted and measured by per-dimension IC, but contributes **zero points** to the composite.

The "bonus first, dimension later" convention (§12.7) gates *promotion to a weighted dimension* on measured IC — but a bonus changes picks the day it ships, so a new signal would re-rank the portfolio for a quarter before anyone could judge it. Given that the engine's own edge is **+1.54pp/month at t≈1.24 (p≈0.28) over ~5 independent periods** (§25), adding unvalidated free parameters to the score is how a risk-on quarter gets fitted as skill.

Flip to `true` only when §25.5's gate is met: IC over ≥100 matured outcomes for `INSIDER_PULSE`, stable across two consecutive weekly runs.

**Overlap guard.** When actionable, the daily pulse and the quarterly Insider Activity bonus are capped at a **combined ±10** — they observe the same promoter through two windows. On conflict the daily signal wins (fresher) and the disagreement is logged as a data tripwire.

### 28.5 Schedule, storage, API

- **14:45 IST MON-FRI**, `isMarketOpen()`-guarded (§3.4). Clear of the 14:00 screening and the packed 15:00–15:30 ramp.
- Budget: two all-market deal calls + PIT for **at most 80 symbols** (holdings first, then the latest screening's candidates). A full-universe daily sweep would be 361 NSE calls for a feed that moves for a handful of stocks a day.
- `insider_disclosures` table, unique on a natural-key hash — re-runs and crash recovery cannot duplicate. Verified: second capture returned 0 new rows in 291 ms.
- `GET /api/insider/{symbol}` (history + pulse, DB-only), `GET /api/insider/recent?days=N` (DB-only), `POST /api/insider/capture` (**live NSE — never wire into a page load**, §27.4).
- Persisted on `multibagger_scores`: `insider_pulse_verdict`, `insider_net_buy90d_pct`, `insider_pulse_score` (0-100, so per-dimension IC can read it).

### 28.6 Known limitations

1. **Coverage is thin at first.** NSE's per-symbol PIT feed returns roughly the last 20 filings for that symbol; for large caps with little insider activity those can be months old. On the first capture, zero scoreable rows fell inside the 90-day window — the newest were March 2026. The signal populates as fresh filings arrive daily, so expect `null` verdicts to dominate for the first weeks. This is honest sparseness, not a bug.
2. **Bad source dates exist.** Three SOLARINDS rows arrived dated 09-Nov-2026 while disclosed 11-Mar-2026. Future-dated rows never age out of a trailing window, so they fall back to the disclosure date or are dropped with a WARN.
3. Promoter *pledge* stays out of this signal — it belongs to the Financial Quality dimension, and double-counting it here would penalise the same fact twice.

---

## 33. Retro-Backtest of the Composite ✅ Active (one-shot tool, 2026-08-25)

**Purpose.** Forward accuracy tracking (§23) only began Apr-2026 and needs quarters to mature. This answers a question available today: *as of a past date, would our dimensions have separated the eventual winners from their peers?*

**Design.** ~20 acknowledged 2018–2023 multibaggers with the year their run began, paired with ~20 **matched controls** from the same sectors and years. Controls are not garnish: without them, "winners averaged 72 on momentum" means nothing, because the whole market may have averaged 72 that year. Scores are reconstructed from candles up to **31-Dec of the year before** the base year, so the scoring never sees the move it is meant to predict.

**Honesty constraints, stated in the output itself:**
- Only the four **price/volume dimensions** (Technical Momentum, Volume Accumulation, Relative Strength, Price Structure) can be rebuilt as-of a past date. Every fundamental dimension and bonus is listed explicitly as **untested** — no point-in-time fundamentals exist before ~Mar-2025 (B-017). Partial coverage that looks complete is worse than no report.
- Both lists were chosen in 2026 with full knowledge of outcomes. The result can show a dimension was **blind** to the winners; it **cannot** show a dimension works. Output is a hypothesis for the forward IC to test, never grounds for re-weighting (Gotcha 27).
- Cases that cannot be scored are named in a `skipped` list, never silently dropped.

**Reading.** Per dimension: winner mean, control mean, gap, and a verdict — `SEPARATED_WINNERS` (gap ≥ 10), `FAVOURED_CONTROLS` (≤ −10), `BLIND` (in between), `TOO_FEW_CASES` (< 3 either side).

**API.** `POST /api/accuracy/retro` — manual only, no scheduler. Deliberately **not** under `/api/backtest`: that namespace belonged to the intraday `BacktestController` deleted 2026-05-24, and reviving it would imply the intraday engine is back. This is measurement, so it lives with the measurement API.

### 33.1 First run (2026-08-25) — hypothesis, not result

16 of 20 cases scored (6 winners / 10 controls); 4 skipped and named — Kite daily history does not reach back far enough for a 2020 base year on some symbols, and JBCHEPHARM no longer resolves (B-027, since removed from the fixture).

| Dimension | Winners | Controls | Gap | Reading |
|---|---|---|---|---|
| Relative Strength | 44.5 | 13.2 | **+31.3** | SEPARATED_WINNERS |
| Technical Momentum | 60.3 | 48.5 | **+11.8** | SEPARATED_WINNERS |
| Price Structure | 50.8 | 45.7 | +5.1 | BLIND |
| Volume Accumulation | 3.3 | 26.0 | **−22.7** | FAVOURED_CONTROLS |

**Read this as a question, not an answer.** Six winners is a tiny sample, both lists were hindsight-selected, and the four fundamental dimensions plus every bonus were untested. What it is worth following up: Volume Accumulation scored the eventual multibaggers at a mean of **3.3 out of 100** in their base year — near the floor — while scoring the non-performers 26. Either the dimension returns a low default on the data available that far back, or it actively penalises the quiet pre-breakout accumulation it was built to detect. That is a specific, checkable hypothesis for the forward IC (where the same dimension currently reads +0.030 at 90d) — **not** grounds to re-weight anything (Gotcha 27).

**Known coverage limit**: Kite daily history depth varies by symbol, so base years before ~2019 frequently yield "no usable history". Skipped cases are always listed rather than dropped.

---

## 30. Dynamic Universe Expansion & IPO Tracker ✅ Active (observation mode, 2026-08-26)

**Purpose.** The screener works off ~361 hand-curated names. Multibaggers emerge from the ~2,300-stock NSE mainboard while nobody is watching, so a curated list can only ever re-rank stocks somebody already found interesting. This is the funnel that lets the system discover names for itself.

**Why it is the gating feature for early discovery.** The Under-Discovery lens (§12.10) scored its first live run and produced exactly **one** candidate out of 189 measured. That is not a defect — the curated universe is Nifty 200 + midcap 100 + smallcap 250, which is *by construction* the well-covered part of the market. An under-discovery lens over well-covered stocks has almost nothing to find. §12.10 only pays off once this section supplies stocks nobody is following.

### 30.1 Universe source

`https://nsearchives.nseindia.com/content/equities/EQUITY_L.csv` — the NSE mainboard equity list (2,559 rows, of which **2,291 are series `EQ`**). Chosen over the Kite instruments dump because it carries two fields the dump lacks:

- **Trading series** — `BE` (240) and `BZ` (28) are trade-to-trade / surveillance names: 100% delivery, no intraday netting, frequently illiquid, and Kite tradingsymbols never carry the suffix (B-013). Excluded.
- **Date of listing** — drives the IPO tracker (§30.4).

The archives host has never been bot-walled, unlike `www.nseindia.com/api` (B-018). Cached 24 hours.

**SME (NSE EMERGE) listings are not in this file**, so they are excluded *structurally* rather than by a filter someone could later remove. That is the intended behaviour — SME liquidity and disclosure quality are below what this system's guardrails assume.

### 30.2 Two-stage funnel

Deep scoring is expensive (NSE + XBRL per stock), so the funnel narrows before it deepens.

**Stage A — coarse scan.** Price/volume only, one Kite call per symbol, no NSE and no XBRL. Runs weekly **inside `weeklyFullScreening()`**, not from a scheduler of its own — `isSaturdayScreeningWindow()` is documented as having exactly two authorised *scheduler* callers and a third would turn a sanctioned exception into a convention (Gotcha 28). It is also the only slot with room: **measured at 22 minutes** for 1,598 symbols (2026-08-26). Note each symbol costs *two* paced Kite calls — instrument-token resolution plus candles — so the cost is ~3,200 requests, not 1,600. Screening (~13 min) plus the scan (~22 min) finishes around 08:35, comfortably inside the 07:45–10:30 window and clear of the 09:00 report.

Filters, all of which must pass:

| Filter | Threshold | Why |
|---|---|---|
| 20-day average traded value | ≥ ₹50 lakh | Buyability (§12.9). A stock you cannot accumulate is not an opportunity. |
| 6-month relative strength vs Nifty | positive | Strength, not a falling knife |
| Near 52-week high **or** higher-lows base | within 25%, or 3 ascending swing lows | Two shapes of the same thesis at different stages |
| Months since listing | ≥ 6 | Newer names are the IPO tracker's job |

Survivors are ranked and the top 40 queued. **What was dropped is logged** — "40 queued" must never be mistaken for "40 were all that passed" (no-silent-caps rule).

**Stage B — deep scoring.** The weekday 14:00 screening takes up to 10 queued symbols and runs the full 7-dimension composite. Promotion requires composite ≥ 60, financial quality not `WEAK`/`HIGH_RISK`, and liquidity not `THIN`. Everything else is retired with a stated reason.

### 30.3 Promotion, retirement, and what it will never do

- **Curated names are never removed or reordered.** Promoted symbols are *appended*.
- **Retirement needs persistence**: a promoted symbol must score below 45 for **8 consecutive weekly runs**. One bad week drops nothing.
- **Rows are deactivated, never deleted.** A symbol that was promoted and later dropped is evidence about how the funnel behaves; deleting it would make the expansion look better than it was (§25.1 survivorship). The API returns retired rows alongside live ones for the same reason.
- **Hard cap of 100 promoted symbols**, weakest retired first — a universe nobody can review is not an improvement.

### 30.4 IPO / recent-listing tracker

Every mainboard listing inside 36 months is tracked. The **only** setup surfaced is: **≥6 months post-listing, price above the listing-day high, and a base of higher lows** — hype gone, early sellers finished, strength returning.

Everything before that is deliberately not surfaced. A stock two months post-listing trading above issue price is a story, not a signal, and this system has no way to tell the difference. Listings without usable price history are returned as **unmeasured**, never as "no setup".

*Since 2026-09-09 the same rule is stated as a **stage** on the IPO page (§45.4): `HYPE_WINDOW` inside six months, then `WASHOUT` / `RECOVERING` / `BASE_FORMING` — the third being exactly this setup. The IPO tracker is the record of each listing's offer structure and lock-in calendar; this section remains the setup rule.*

### 30.5 Observation mode (how this ships)

> ⚠️ **Observation mode gates the universe merge, not persistence (B-035).** As shipped, Stage B scored queued symbols through `screenSingleStock()`, which writes a `multibagger_scores` row and records a MULTIBAGGER recommendation at ≥ 65 — so unreviewed names reached the dashboard, the morning briefing, `/history` and the **accuracy tracker** while the flag reported the feature as off. Stage B now uses `evaluateSingleStock()`, which scores without persisting; a queued symbol's score lives only on `dynamic_universe.lastCompositeScore` until it is promoted *and* the feature is enabled, at which point normal screening picks it up like any other member. The 19 score rows and 17 recommendations already written were deleted (backup in `logs/`).
>
> The general rule: **compute-to-decide and compute-to-publish are different operations.** Any scoring entry point that persists needs a non-persisting twin before a funnel calls it.

**`trading.universe.dynamic-expansion.enabled` defaults to `false`.** Symbols are discovered, queued, deep-scored and recorded — but do **not** enter the screening universe.

This is the same gate as §28.4 applied to a bigger blast radius. The promotion rules have no track record, and an expanded universe is full of thin, lightly-covered names. Watch the funnel behave for a few weeks; then flip the flag. `scan-enabled` stays `true` while in observation mode, otherwise there would be nothing to observe.

### 30.6 API and surfaces

- `GET /api/universe/dynamic` — everything tracked, grouped by status, including retired. DB-only, dashboard-safe.
- `GET /api/universe/ipo-watch?setupsOnly=` — recent listings with setup evaluation. Kite-backed and **slow: measured 6 minutes for 656 listings** (two paced Kite calls each). On the UI's on-demand allowlist behind an explicit button with a stated cost; never a page-load call.

  First run (2026-08-26): 656 mainboard listings inside 36 months → **115 showing the post-IPO base setup**, 202 still below their listing-day high, 278 with too little history to judge, 48 above the high but without a base, 1 with no broker data. 115 is a large shortlist — the higher-lows test over ~6 months is easy to satisfy in a rising market — so it is research input, not a buy list, and the UI says so.
- `POST /api/universe/scan` — run Stage A now (measured 22 min, ~3,200 paced Kite calls). Never from a page load.

**Manual-run time guards (B-049).** These endpoints are the only way a person can start a long broker sweep, and Kite calls are paced process-wide at ~2.9 req/s (§B-027), so one started late in the session holds that gate through the 15:05–15:28 report jobs — all of which abort *silently* at 15:30 (B-014). The guards are sized to the measured cost:

| Endpoint | Measured cost | May start |
|---|---|---|
| `POST /scan` | 22 min, ~3,200 calls | Saturday window, or a weekday before **13:00** |
| `GET /ipo-watch` | 6 min, ~1,300 calls | Any time before **14:00**, or after the close |
| `POST /process-queue` | minutes, NSE + XBRL | Any time before **14:00**, or after the close |

13:00 for the long scan rather than 14:00 because the daily screening *starts* at 14:00 and is itself a long Kite-heavy scan. A refusal returns **409 with the reason in the body** — `server.error.include-message` defaults to `never`, so a `ResponseStatusException` would have produced a bare 409 and a guard whose explanation never reaches the person it stops is the silent failure this rule exists to prevent.
- `POST /api/universe/process-queue` — run Stage B now.
- Dashboard **Discovery** screen (§27) renders the funnel, including the observation-mode banner and the retired list.

### 30.7 First live run (2026-08-26) — and two problems it exposed

**Stage A**: 2,559 listed securities → 2,291 mainboard EQ → **1,598 the system had never looked at** (421 already curated or tracked). 22 minutes. Of those, **766 passed** the coarse filters, 827 were rejected, 5 had no usable data, and the top 40 were queued (the other 726 logged as deferred, not dropped silently).

**Stage B**: 10 deep-scored in 44 seconds → **9 promoted, 1 rejected**. The rejection was APOLLOPIPE, capped at composite 50 by the `HIGH_RISK` financial-quality guard — that guard fired correctly.

> ⚠️ **Problem 1 — the funnel is circular, and its promotion rate is therefore meaningless.** The nine promoted scored 100, 99, 99, 98, 98, 94, 93, 90 and 70 — eight of nine at `STRONG_MULTIBAGGER`. That is implausible against the curated universe, where 62 of 287 stocks clear the candidate bar at all. The cause is structural: Stage A filters on momentum, relative strength, price structure and volume — which are **54% of the composite by weight**. So Stage A pre-selects for a high composite, and Stage B then "discovers" it. The coarse scan is not an independent pre-screen; it is a proxy for half the score.
>
> Consequences: promotion rate will sit near 90%, the 100-symbol cap fills in about three weeks, and the promoted set is selected on *momentum* rather than on the balance-sheet quality that is supposed to distinguish a long-term hold. **Do not read the 9/10 promotion rate as validation.**
>
> ⚠️ **Problem 2 — the coarse score cannot rank.** 766 survivors, and the top 40 all scored 96–100. "Top 40" is close to arbitrary among them, and at 40/week it would take 19 weeks to work through one scan's survivors.
>
> Both are left unchanged for now, deliberately. Tightening thresholds on a single observation is the mistake this codebase keeps paying for (Gotcha 27), and observation mode means nothing reaches the screening universe meanwhile. The fix wants evidence: let the funnel run a few weeks, then judge whether to make Stage A independent of the composite (e.g. filter on liquidity and listing age only, and let Stage B do all the ranking) or to raise the promotion bar for dynamically-discovered names specifically.

---

## 31. Capex-Cycle Signal (CWIP / Gross Block) ✅ Active (shadow mode, 2026-08-26)

**The gap this closes.** Every other scoring input in this system reads the past. Earnings growth, margins, ROCE, relative strength and momentum all describe what a company has already done — so by the time an expansion shows up in any of them, the capacity has been built, the revenue has arrived, and the stock has moved. Capital work-in-progress is the one line on an Indian balance sheet that shows the money **before** the revenue: plants under construction, committed and paid for, not yet producing.

**No new data source.** The annual Ind-AS XBRL is already fetched and cached for seven days for capital efficiency (§12.8). This is a parser extension over the same document.

### 31.1 What is measured

| Measure | Formula | Reading |
|---|---|---|
| CWIP intensity | CWIP ÷ net block | How large the build is versus the plant already running. >15% is a big build |
| CWIP change | CWIP − prior-year CWIP | Whether the build is starting or finishing |
| Capex proxy | ΔPPE + ΔCWIP + depreciation | Spend inferred from the balance sheet |
| Capex/depreciation | proxy ÷ depreciation | >2 investing for growth, <0.8 harvesting |

Depreciation is added back because net block is reported after it — without that, a company spending exactly its depreciation appears to have spent nothing.

> ⚠️ **The prior year does NOT come from the filing (B-034, measured 2026-08-26).** The plan assumed Ind-AS documents carry the previous year's balance sheet as a comparative column. They do not, in NSE's integrated-filing format: the document declares a prior-year instant context, and a fact count against it returns **exactly 1**. Verified null `cwipPrior` on 7 of 7 non-financials.
>
> The prior year therefore comes from **§32's `annual_fundamentals` table**, which records CWIP per financial year from both the XBRL pipeline and the user's import. `CapexCycleService` does that composition and all four call sites route through it. F4 and F5 turn out to be one feature in two commits.
>
> **Consequence to keep in mind:** until a stock has a second annual filing on record (or an imported history), `EXPANSION_UNDERWAY` cannot fire and the verdict rests on CWIP intensity alone. The reason line says so in the user's own words rather than implying a measurement.

`resolvePriorYearInstantContext` is retained because the filing's own comparative is authoritative **when present** and wins over the reconstruction. It accepts a comparative only if it sits **300–430 days** before the current balance-sheet date: quarterly and half-yearly instants appear in the same documents, and differencing against a 90-day-old balance sheet would report a quarter's capex as a year's.

`priorYearAvailable` means *usable figures exist*, not *a context id resolved* — reporting the latter is what made the gap invisible until a fact count was added.

**Net block is stored per year too (B-048).** B-034 supplied the prior year's CWIP and unlocked `EXPANSION_UNDERWAY`; capex-to-depreciation stayed null because nothing supplied the prior year's **net block**. `annual_fundamentals.net_block` now carries it, written by the XBRL path and by the import, and read back as the prior year — so the ratio becomes computable per stock once two annual filings are on record.

**The CWIP term is measured or the proxy is null.** It previously defaulted to `0.0` whenever the change could not be computed, which understates capex by exactly the amount under construction — the spending this analysis exists to detect — while the ratio still reads as measured. It is now computed only when both years are present, or when neither year reports any construction at all (an asset-light company's delta between two absences really is zero). Anything in between is null.

### 31.2 Verdicts

- `EXPANSION_UNDERWAY` — intensity >15% **and** CWIP rising. The signal this feature exists for.
- `INVESTING` — capex/depreciation >2, or a big build whose direction cannot be seen
- `STEADY` — spending roughly matches depreciation
- `HARVESTING` — capex/depreciation <0.8
- `NA_FINANCIAL` — banks, NBFCs, insurers
- `NO_DATA` — filing does not tag the fields

**A shrinking large build is not an expansion.** Same 30% intensity, CWIP falling, means capacity is being commissioned rather than started — reading that as EXPANSION_UNDERWAY would put the signal exactly one cycle late, which is the failure the feature exists to avoid.

**Banks return null figures, not zeros.** A bank's growth is its loan book; CWIP for a bank is branch fit-outs. Same suppression pattern as ROCE in §12.8.

### 31.3 Scoring — shadow mode

`trading.multibagger.capex-actionable` defaults to **false**. The verdict is computed, persisted to `multibagger_scores.capex_verdict` / `capex_score` / `cwip_intensity_pct` / `capex_to_depreciation`, and measured by per-dimension IC — but contributes **zero points**.

This is the most tempting signal yet added, precisely because it leads rather than lags. That is the argument for measuring it carefully, not for trusting it early: a leading indicator that leads in the wrong direction is worse than a lagging one, and "companies building capacity outperform" has a literature on both sides. Gate: SPEC §25.5.

When enabled: EXPANSION_UNDERWAY +8, INVESTING +4, HARVESTING −3 **only when** the earnings verdict is also STAGNANT or DECLINING — a mature compounder harvesting while earnings grow is fine, and penalising TCS for not building factories would be a bug.

**Interaction guard.** When financial quality is WEAK or HIGH_RISK the bonus is suppressed *entirely*, not reduced. Debt-funded capex on a fragile balance sheet is how a small cap dies, not how it 5x's — paying points for it there inverts the signal. The reader still sees a warning line saying so.

### 31.4 Surfaces

`GET /api/research/capex/{symbol}`; Deep Research dimension 21; a "Building?" column in the holdings email's Capital Efficiency table; `multibagger_scores` columns above. IC dimension name: `Capex Cycle`.

---

## 32. Long-Horizon Fundamentals, Turnarounds & Forensics ✅ Active (2026-08-26)

**The gap this closes.** NSE's integrated-filing feed reaches back to ~Mar-2025 — about five quarters (B-017). Ten-year CAGRs, through-cycle margins, multi-year deleveraging and share-count history are invisible to it. Those are exactly the inputs that identify **turnarounds**, and the **forensic red flags** that widening the universe (§30) makes necessary.

### 32.1 Data source decision

Option (c) from the plan: a user-supplied CSV export, parsed once per stock. Same pragmatic precedent as the Zerodha tradebook backfill (§9.3). Rejected: reviving the frozen pre-2025 NSE endpoint family (B-017), and building a BSE integration from scratch — a new bot-wall surface for one feature.

`POST /api/fundamentals/import-history?symbol=…` accepts a multipart `file=` or a raw `text/csv` body. The parser handles a screener.in "Data Sheet" layout (metrics down, financial years across) and reports what it *could not* read rather than failing silently.

**XLSX is not parsed** — that would pull in a spreadsheet library for one import path, and "Save As CSV" is one step for the user.

> ⚠️ **Derived-metric rows must be rejected before label matching, not by it.** A screener sheet carries `Sales` and `Sales Growth %` as adjacent rows. Prefix matching alone writes the *growth rate* into the sales field — a three-orders-of-magnitude error that then flows into every CAGR, margin and turnaround verdict downstream. `isDerivedMetric()` filters rows containing %, growth, margin, ratio, per share, yield, cagr, roce, roe, days or turnover. Caught by `FundamentalsImportTest.percentageRowsDoNotOverwriteFigures`.

### 32.2 Data model

`annual_fundamentals` — unique on (symbol, fiscalYear). Every figure nullable: different sources carry different lines, and a zero in `borrowings` reads as a debt-free balance sheet.

**Five columns added 2026-09-06 as the schema lock for §32.6.** They landed *before* the universe backfill ran, not after, and the ordering is the point: every year the backfill writes is stamped `source=XBRL`, and the import path then refuses to touch that row (Gotcha 49), so a column added afterwards could only be filled by re-fetching every filing — and the XBRL cache is 7 days *and* process-local against an app that restarts daily, so a re-run pays the full ~4,000-request price a second time.

| Column | Why it must exist before the backfill, not after |
|---|---|
| `available_from` | The date the figures became public. **The only field that makes a fundamentals lens back-testable without look-ahead bias** — see below |
| `available_from_estimated` | Whether that date is filed or assumed. An assumption must never be readable as a fact (§21 rule 7) |
| `dividends_paid` | Retention = 1 − payout, and retention is half the compounding arithmetic in §41.1. Three things needed it: §41.4 names it as the first improvement to the compounding lens, §42 is built on it, and `isBonusOrSplit` documents that it errs toward silence *because* dividends were not on the row |
| `profit_before_tax` | Was read from the filing only to reconstruct `operatingProfit`, then discarded. Storing it makes return on capital computable directly from a history row and the effective tax rate derivable |
| `face_value` | **A change in face value is a stock split.** `shareCount` is already derived as paid-up capital ÷ face value, so this is free at parse time and turns B-066's split detection from an inference into a fact (Gotcha 86's 0.05% tolerance exists only because the field was missing) |

**Why `available_from` is not `fiscalYear` plus a constant, and why it is load-bearing.** A March-2024 year end is not public in March 2024; SEBI LODR Reg 33(3) allows 60 days and companies use them. Scoring a past screening date with figures the market did not yet have is look-ahead bias, and it always biases *in the flattering direction* — the lens "knew" the result before the price moved.

The deeper reason is what it buys. Gotcha 91(b): shadow weight variants back-fill to April 2026 because a variant's composite is *reconstructed* from columns already on historical rows. A **new dimension** has no such column, so its evidence clock normally starts on ship day — and against §38.10's twelve-independent-period bar, that is years. A fundamentals lens is the exception: for any past screening date you can compute what it would have said using only filings public by then, which inherits history back to 2016 instead of starting from nothing. That only works if the row records when the filing became public.

Populated from the archive's own broadcast date where one is carried; otherwise **fiscal year end + 5 months**, deliberately more conservative than the 60 days the regulation allows, with `available_from_estimated = true`. An estimate errs *late* on purpose: erring early is the bias, erring late merely shrinks the usable sample. An unparseable date yields null and falls back to the estimate — never to today's date, which would file a decade-old document as public now.

**Do not add `consolidated` to the unique key.** One row per (symbol, fiscalYear) with the basis recorded is what the §32.5 majority-basis election depends on.

**Two writers.** `source=IMPORT` from the user's export; `source=XBRL` written by the screening run from the annual filing already in cache. **XBRL wins over IMPORT** for the same year — it came from the filing rather than a third-party rendering of it. The import is a one-time bridge; from there the table maintains itself.

**Field by field, not row by row (B-046).** The filing outranks the import *where it has a figure*, and only there. NSE's tagging coverage varies by company and by taxonomy, and a blanket overwrite meant every untagged field arrived as null and erased a real imported value — permanently, because the row is then stamped `XBRL` and the import path refuses to overwrite an XBRL row. The merge writes only non-null values.

**Share count is stored in crore, and the unit is enforced rather than assumed.** The XBRL path derives it exactly (paid-up capital / face value); the import normalises anything above 100,000 — no listed Indian company has 100,000 crore shares — using the same auto-detection as the FII/DII lakhs-vs-crores conversion. A series mixing the two bases steps by 10 million between adjacent years, which the dilution check would read as a colossal buyback, so §32.4 additionally refuses to measure dilution across a >50x step.

**Quarterly blocks are skipped, and the skip is reported (B-047).** A screener "Data Sheet" stacks an annual P&L, a QUARTERS block, then Balance Sheet and Cash Flow, each with its own header. Headers are re-detected as the file is walked — a header naming any month other than March, or repeating a year, is quarterly — and parsing resumes at the next annual header so the sections *after* the quarters are not lost. One quarter written into a year would corrupt every CAGR, margin and turnaround verdict computed from it.

### 32.3 Turnaround detector

Requires **≥4 years**. Flags `TURNAROUND_CANDIDATE` on **≥3 of 4**:

1. Debt-to-equity falling three consecutive years **and** now below 1.0
2. Interest cost falling in absolute rupees two years running
3. Operating margin >2pp above its own three-year average
4. Sales CAGR >8% with profit growing faster (operating leverage)

**Why 3 of 4.** Real recoveries are ragged — a company can deleverage hard while margins lag a year, or inflect margins first and repay later. All four would find almost nothing; one would fire on noise.

**Falling debt alone is not enough.** Criterion 1 requires the absolute level below 1.0; debt dropping from 10× to 7× equity is repair under way, not finished.

**A gap in a series makes the trend unmeasurable, not flat.** `lastN` returns empty unless every year is present. Criteria that could not be evaluated are reported in `notMeasured`, never counted as failed — and signals + notMet + notMeasured always totals 4, so "2 of 4" is auditable.

Shadow mode: `trading.multibagger.turnaround-actionable` defaults **false**. Turnarounds are the category most prone to hindsight — the ones that worked are famous, the ones that kept failing are forgotten, and these criteria were written by reading about the former. Reasons are still surfaced while the score stays untouched. Bonus when enabled: +6, suppressed on a HIGH_RISK balance sheet.

### 32.4 Forensic red-flag layer

**Why this ships alongside §30.** Widening the universe from 361 curated names to the full mainboard removes an accidental but real fraud filter. Removing it without a deliberate replacement is a net downgrade in safety whatever it does for discovery.

| Flag | Trigger | Severity | Penalty |
|---|---|---|---|
| `DILUTION` | share-count CAGR (3y) > 5% | HIGH | −5 |
| `RECEIVABLES` | receivables growth > 1.5× sales growth (2y), sales actually growing | MEDIUM | −3 |
| `CASH_CONVERSION` | cumulative CFO / cumulative profit (3y) < 0.6 | HIGH | −5 |
| `AUDITOR` | resignation / qualified / adverse / disclaimer of opinion in announcements | HIGH | −8 **+ forces HIGH_RISK** |
| `RELATED_PARTY` | related-party / inter-corporate-deposit keywords | INFO | **0** |

> ⚠️ **A bonus issue is not dilution.** Reliance did 1:1 in 2024; the share count doubles, every holder's proportion is unchanged, and the price halves to match. Without a discriminator the dilution flag fires on some of the best companies in the market, and a forensic section that cries wolf gets ignored — taking the real flags down with it. `isBonusOrSplit()` asks **where the money came from**: a genuine issue brings cash in, so net worth rises beyond retained profit; a bonus only moves reserves into share capital and a split changes nothing. If equity grew by no more than the profits earned, no external capital arrived. Reported as *not measured* with the reason, never silently dropped. Known limitation: a company that raises capital *and* pays large dividends could suppress it — the check errs toward silence, which is the right direction for a false accusation.

**Each flag is named, never folded into a number.** A composite that quietly drops five points tells the reader nothing; "share count has grown 9% a year for three years" tells them what to check.

**Where the auditor scan actually runs (B-038).** The screener fetches announcements only for stocks whose pre-cap composite is **≥ 55** — the flag's sole job is the cap at 54, so below that a network call cannot change an outcome, and above it the guarantee has to hold. The holdings email scans all holdings. Everything else (the rest of the universe, the on-demand endpoint's default) does not scan, and reports the auditor check as *not measured* rather than as clean.

> ⚠️ **Two ways this guarantee was inert on arrival.** It shipped with the screener passing `includeAnnouncements=false`, so the cap could never fire on any persisted composite (B-038). And the shared NSE `WebClient` used Spring's 256 KB default buffer, so the announcements feed failed outright for large caps and returned an empty list that read downstream as "filed nothing" (B-054). A safety guarantee that is documented, unit-tested and unreachable in production is worse than none, because it is believed.

**The auditor flag escalates rather than deducts.** It triggers the §12.5 composite cap at 54 independently of the financial-quality verdict — because every quality metric in this system is computed *from* the audited numbers, so a compromised audit makes a clean financial-quality reading meaningless rather than reassuring.

**Related-party is deliberately unscored.** These filings are routine as often as they are worrying. Scoring them before measuring them would add noise to the composite in exchange for nothing.

**Receivables are not checked against flat or falling sales** — the ratio explodes and says nothing.

> ⚠️ **"No flags" is not "clean" when nothing could be checked.** A stock with no imported history produces zero flags and zero information. `notMeasured` carries what could not run, and the holdings email prints an explicit coverage line: *checked N holdings, M could not be checked at all*. Without it a blank bill of health is indistinguishable from a clean one.

**Risk controls ship armed** (`forensic-actionable: true`), unlike the bonuses in §31 and §32.3. The asymmetry is deliberate: an unvalidated *bonus* steers the portfolio on a guess and being wrong costs a missed opportunity; being wrong about serial dilution or an auditor resignation costs capital. The existing HIGH_RISK cap ships armed for the same reason.

### 32.5 Surfaces

`GET /api/fundamentals/history|turnaround/{symbol}|turnarounds|forensics/{symbol}`, `POST /api/fundamentals/import-history`, `DELETE /api/fundamentals/history`. Holdings email: **Forensic Red Flags** section with the coverage line. Persisted: `multibagger_scores.turnaround_verdict`, `forensic_flags`.

Announcements are scanned for holdings (dozens of stocks — an auditor resignation on something you own is worth a network call) but **not** in the screening loop (hundreds of stocks, one live NSE call each).

---

### 32.5 Annual-history backfill from NSE's own archive ✅ Active (2026-08-27)

The multi-year history the turnaround detector, the forensic screen and the core-holding durability
score all need was supposed to arrive as a manual screener.in CSV per stock. It does not have to.

**NSE still serves the archive.** B-017 moved financial-result *discovery* to the integrated-filing
feed because `corporates-financial-results` stopped receiving **new** filings after Dec-2024. The
endpoint was never withdrawn: it returns HTTP 200 with **13–14 annual filings per symbol** going
back to 2011, each carrying its Ind-AS XBRL link. Those filings use the `in-bse-fin` taxonomy with
`FourD`/`OneD`/`OneI` contexts — the taxonomy this parser was originally written for — so the
backfill needed no new parsing, only a different way of listing what to parse.

`POST /api/fundamentals/backfill?symbol=NSE:X` does one stock; `POST /api/fundamentals/backfill-holdings`
does the portfolio. Both are POSTs so no page load can reach them, and both refuse between
**09:40 and 10:15** — the FII/DII fetch at 09:45 and its report at 10:00 share this app's single NSE
session, and a multi-minute backfill across them is the same interference class as a manual Kite
sweep starting at 15:20 (B-049). The refusal is a 409 carrying its reason.

**Three rules the backfill enforces, each from a defect it would otherwise have caused:**

1. **One reporting basis per series.** NSE's archive does not always carry both. Measured on
   RELIANCE: FY2022 exists **only** as a standalone filing while the years either side are
   consolidated, and standalone revenue there is roughly half the group figure. A mixed series
   manufactures a collapse and a recovery that never happened, and every CAGR, margin trend,
   turnaround verdict and dilution check downstream reads it as real. The series settles on the
   majority basis (consolidated wins a tie), and off-basis years are **skipped and counted**, never
   converted. A gap is something the downstream checks already handle; a fabrication is not
   detectable. `annual_fundamentals.consolidated` records the basis per row.
2. **A year that parsed but carried no figures is not stored.** An empty row is worse than a
   missing one: `yearsAvailable` counts it, so the forensic screen and durability believe they have
   history they cannot read — and because the row is stamped `source=XBRL`, the import path can
   never replace it (Gotcha 49). Same failure class as B-046, one layer up.
3. **The context-ID convention is trusted when the declaration is missing.** Older archive filings
   (measured: RELIANCE FY2019–FY2022) tag every fact `contextRef="FourD"` while declaring no
   `<xbrli:context>` with that id at all. The date-driven resolvers find nothing and every figure
   comes back null — which is how the first run produced four financial years of entirely empty
   rows. The fallback fires only when resolution failed *and* facts actually reference the
   conventional id, so a well-formed filing never reaches it.

**Known limit.** Balance-sheet facts (equity, receivables, net block) resolve for recent years but
often not for pre-2022 filings, whose instant context has the same declaration gap. P&L and share
count come through, which is enough for the dilution check and D1/D2; the balance-sheet-dependent
checks stay unmeasured for those years and say so. The CSV import remains the fallback for anything
the archive cannot supply — a delisted predecessor, filings older than 2011, or a figure from
another source.

**Going forward the table still maintains itself** from the integrated filing on every screening run
(Gotcha 49). This is a one-time bridge for the past, which is the role the CSV import was written
for — now automated.

### 32.6 Universe-wide backfill ✅ Active (2026-09-06)

§32.5 automated the archive backfill for **one symbol or the portfolio**. This runs it across the
whole screening universe, which is what §40.3 listed as roadmap item 1 and what every remaining
long-horizon feature was blocked on.

**The measurement that justified it**: multi-year history existed for roughly **19 of 288** screened
stocks (BEL 8 years, Reliance 6, most others 1). §41's compounding lens therefore read the latest
single year and said so; the turnaround detector (≥4 years) and most forensic flags (≥3) reported
"nothing was checked" for most of the universe, which Gotcha 44 warns is routinely misread as
"clean".

**What depth unlocks**, measured from the code rather than assumed:

| Depth | Unlocks |
|---|---|
| 3 years | Receivables and cash-conversion forensic flags |
| 4 years | Turnaround detector in full; the B-066 bonus-vs-dilution discriminator |
| 5+ years | §42 capital allocation, §43 margin stability and earnings steadiness |
| 8–10 years | §43 return-on-capital persistence, incremental return on capital |

Note the fourth row twice over: below 4 years `isBonusOrSplit` returns false, meaning *"not a bonus"*,
which **fires `DILUTION:HIGH`**. Backfilling does not only add true flags — it removes false ones.

#### Cost, and why it is a batch

| Item | Figure |
|---|---|
| Symbols in `resolvedScreeningUniverse()` | 370 configured (288 realised in screening) |
| NSE requests per symbol, cold | ~11 (1 uncached listing + up to 10 XBRL) |
| One-time total | **~4,000 requests** |
| Batch of 30 symbols | ~330 requests, ~6.5 min at 1200 ms pacing |
| Runs to converge | **~13 weekdays** |
| Steady state after that | ~zero — `recordFromXbrl` maintains the newest year each screening run |

`trading.fundamentals.backfill.*` carries `enabled` (**true** — this changes no score and emits no
verdict, so Gotcha 30's shadow rule does not apply), `batch-size`, `pace-ms`, `max-years`,
`max-attempts`, `retry-after-days` and `stop-after`.

⚠️ **`pace-ms: 1200` is cautious, not measured.** The only measured comparable in this repo is
3,200 *paced Kite* calls in 22 minutes (~0.41 s each), and NSE archive XBRL documents are far
heavier while NSE is much the more bot-sensitive host — it has already walled `/api/quote-equity`
permanently (B-018). Lower it only against measurement, and note that the cost of being wrong is
asymmetric: a slow backfill finishes a week later, a walled endpoint never finishes at all.

#### `fundamentals_backfill_status` — why a status table is not optional

Without per-symbol state nothing can tell **"never attempted"** from **"attempted, and this company
genuinely only ever filed three annual results"** — both look like a symbol with three years of
history. A picker ordering by fewest-years-held then returns to the shallowest symbols every day
and never reaches the untouched ones. That is the natural failure of the obvious implementation,
so completion is judged as `yearsWritten + yearsSkippedBasis >= yearsInArchive`, not against a
target depth.

Statuses: `PENDING` / `COMPLETE` / `PARTIAL` / `FAILED` / `UNAVAILABLE`. Two rules carry the weight.
**Years skipped to hold one reporting basis count as seen** — they were deliberately left out
(Gotcha 73) and re-fetching would leave them out again. And **`UNAVAILABLE` is a finding, not a
failure**: the archive genuinely lists no annual filing (a delisted predecessor, a non-March year
end, an insurer behind the same wall that already returns NO_DATA for capital efficiency), and the
CSV import is the fallback for exactly those names. A listing that *errored* is `FAILED`, never
`UNAVAILABLE` — recording a fetch failure as a fact about the business is the B-054 lesson.

#### Expected consequence of the rollout, and how it is observed

**Composites will move while it runs, and that is correct.** `forensic-actionable` defaults `true`
and a HIGH flag forces the composite cap at 54, so as depth arrives some new true flags appear and
some false `DILUTION:HIGH` flags disappear. Do **not** disarm `forensic-actionable` during the
rollout: suppressing it is the removal of a risk control, the wrong direction of Gotcha 42's
asymmetry.

**`scoring_version` is deliberately not bumped.** The engine is unchanged; only the depth of its
inputs grew, which happens on every screening run anyway as new quarters arrive. Bumping would
falsely claim an engine change and split the shadow-reconstruction sample further, when the first
walk-forward run already flagged two pooled versions (§38.9). The instrument is
`GET /api/fundamentals/coverage` plus the existing `screening_coverage` vector (§38.2) — any IC or
coverage panel spanning September–October 2026 must be read with the rollout in mind.

#### Surfaces

- `GET /api/fundamentals/coverage` — depth histogram, status counts, symbols still pending, and the
  `UNAVAILABLE` list. DB-only, dashboard-safe.
- `POST /api/fundamentals/backfill-universe?limit=` — run one batch now. Same 09:40–10:15 refusal.
- `GET /api/fundamentals/history?symbol=` now returns `availableFrom`, `availableFromEstimated`,
  `dividendsPaid`, `profitBeforeTax`, `faceValue`, `netBlock` and `consolidated`.

Pinned by `FundamentalsBackfillTest`: a shallow-but-complete symbol is never re-queued, basis skips
count toward completion, a failed listing is never completion, an unparseable filing date is null
rather than today, and the pacing cannot be configured to zero.

#### Measured on the first live run (2026-09-06)

**The archive carries a real broadcast date.** Measured on BEL: FY2018 filed 2018-06-23, FY2024
filed 2024-05-20, `available_from_estimated` **false** on every backfilled year, with a real lag of
2–3 months after year end. So the point-in-time evaluation this column exists for can be built on
filed dates; the +5-month estimate is the fallback, not the norm.

**The pre-2022 balance-sheet gap is real and larger than the P&L gap.** BEL has 8 years of accounts
and `equity` in only the last two, while `profit_before_tax` resolved for all eight. This is why
§43 is tiered rather than built on return on capital alone.

**Universe depth at ship time**: 366 symbols, **20 at ≥4 years** (5.5% forensic-ready), median
depth **1 year**, all 366 pending. One 10-year symbol costs **12.7 seconds** paced, so a 30-symbol
batch is ~6 minutes as designed.

---

## 42. Capital-Allocation Record ✅ Active (lens, 2026-09-06)

**§20 rule 9 declaration.** (a) Pillar: **management quality** — the one §40.1 recorded as having
*no capital-allocation record at all*. (b) Horizon: **365 days and beyond**; never accepted on a
30- or 90-day panel. (c) Coverage: one `screening_coverage` row per component. (d) **Shadow: yes** —
computed, shown, worth **zero points** (Gotcha 30).

### 42.1 The question

The app could see insider filings and what management *said* on an earnings call. It could not see
what they did with the money. That is the half of the judgement that separates a compounder from a
company that merely grew: profits retained and reinvested badly compound nothing, and a share count
growing as fast as profits hands the owner nothing either.

### 42.2 Five components

| Component | Question the reader sees | Measured from |
|---|---|---|
| `shareCount` | Has your slice been protected? | Dilution CAGR **excluding** bonus issues and splits |
| `payout` | How much profit is kept to reinvest? | Cumulative `dividends_paid` ÷ cumulative `net_profit` |
| `reinvestment` | Is the company still building? | Cumulative capex proxy ÷ cumulative depreciation |
| `debt` | Was growth funded with borrowed money? | `debtToEquity` level **and** direction |
| `incrementalReturn` | Does new money earn a good return? | Δ operating profit ÷ Δ capital employed |

Verdicts: `DISCIPLINED` / `MIXED` / `POOR` / `NOT_MEASURED`. Minimum **5 years** — capital
allocation over three years is one capex decision and one dividend.

### 42.3 What it refuses to do

1. **Never enters the composite** (Gotcha 30, §20 rule 9). Promotion only through §38.10.
2. **A bonus issue is not dilution.** Corporate actions are divided out before any growth rate is
   taken. `face_value` settles a split outright now that §32.6 stores it; the simple-ratio fallback
   keeps B-066's **0.05%** tolerance, because at 0.5% the grid of fractions swallows real raises
   (Gotcha 86).
3. **Fewer than three measured components is `NOT_MEASURED`, never `POOR`.** An unread record is
   not a bad one (Gotcha 44).
4. **`NOT_APPLICABLE` leaves the denominator** (Gotcha 68). A company that never grew its capital
   base has no incremental return to judge — that describes CAMS, CDSL and Oracle Financial, the
   businesses with the *highest* returns on capital, and failing them for not needing factories
   would invert the thing being measured (the §41.4 rejection, applied one level up). A company
   whose filings do not tag dividends has no payout reading at all, which is a different fact from
   one that pays none.

---

## 43. Compounding Track Record ✅ Active (lens, 2026-09-06)

**§20 rule 9 declaration.** (a) Pillar: **competitive advantage** (persistence), business quality
secondary. (b) Horizon: **365 days and beyond**. (c) Coverage: one `screening_coverage` row per
gate. (d) **Shadow: yes** — zero points.

### 43.1 The question §41 could not answer

§41.4 states it plainly: the compounding lens reads the **latest single year**, and whether a 25%
return on capital held for eight years is the strongest compounder evidence there is. That was
unanswerable for ~93% of the universe. §32.6 is what makes it answerable.

### 43.2 The one rule that matters: count years, never average them

A commodity business's return on capital oscillates roughly 5% → 35% → 5% across a cycle. An
**average** clears an 18% bar comfortably; *"held above 18% in seven of ten years"* does not.
Averaging lets one boom year carry a decade, which is exactly the failure a persistence test exists
to catch, and it is the first thing a well-meaning simplification would reintroduce. Every gate is
a count of qualifying years over measured years. `CompoundingPersistenceTest.averagingMustNotRescueACyclical`
fails loudly if that changes.

### 43.3 Six gates, in two tiers

Tiered because the data resolves unevenly: §32.5 records that balance-sheet facts often fail to
resolve for pre-2022 archive filings while the P&L and share count come through. Building every
gate on return on capital would reproduce §41's limitation with more work behind it.

| Tier | Gate | Passes when |
|---|---|---|
| A | `marginStability` | Operating margin within 2pp of its own median in ≥70% of measured years |
| A | `earningsSteadiness` | Profitable in ≥70% of measured years |
| A | `compounding` | Profit CAGR ≥ 10% **and** not lagging sales CAGR by more than 2pp |
| A | `shareCount` | Dilution ≤ 2%/yr excluding corporate actions (delegates to §42) |
| B | `returnPersistence` | ROCE ≥ 18% in ≥70% of measured years |
| B | `leverage` | Debt/equity ≤ 0.5 in ≥70% of measured years — **not applicable** to a lender |

⚠️ **A Tier B gate needs ≥4 measured years or it says nothing** (`MIN_TIER_B_YEARS`). Found on the
first live run, not in review: BEL passed return on capital 3/3 and helped earn a
`PROVEN_COMPOUNDER` badge on three readings, because its balance sheet resolves for only the last
two or three years. Three readings is thinner than the word "persistence" claims, and a gate that
passes on thin evidence while sounding authoritative is Gotcha 68 in its most flattering form.
Four matches `MIN_YEARS_FOR_ANALYSIS`, the bar every other multi-year check already uses. BEL now
reads `PROVEN_COMPOUNDER` on 4 of 4 measured Tier A gates with both Tier B gates `NOT_MEASURED`.

Verdicts: `PROVEN_COMPOUNDER` (all applicable gates passed, on ≥4 of them), `PARTIAL`, `NO`,
`NOT_MEASURED` (fewer than 5 years of accounts, or fewer than 4 applicable gates).

### 43.4 Thresholds, and the one that is not yet measured

The ROCE bar of 18% is §41.3's, set against the live 288-stock cross-section. ⚠️ **`REQUIRED_SHARE`
of 0.7 is judgement, not measurement** — the cross-section it would be calibrated against does not
exist until §32.6 converges. It is deliberately not 1.0 (a business that never once dipped over a
decade has not met a recession, and requiring that selects for short histories over durable ones)
and deliberately not a bare majority (a cyclical spends more than half a cycle in its good half).
**Recalibrate against the measured distribution once the backfill lands**, the §12.11 and §41.3
discipline. Until then this is the one number here quoting a level rather than a percentile.

### 43.5 Coverage and null discipline

A gate is `NOT_MEASURED` when its input is missing and `NOT_APPLICABLE` when the question does not
fit the business; **neither is a pass and neither enters the denominator** (Gotcha 68). Below 5
years the whole lens is `NOT_MEASURED` — a business whose filings could not be read has not failed
(Gotcha 21, 44). Lender detection reads `capexVerdict = NA_FINANCIAL`, the marker that actually
fires, **not** `capitalEfficiencyVerdict`, which looks like the natural field and grades a bank
SOLID on return on equity (Gotcha 88).

### 43.6 Surfaces

**Guide** — *"Has it actually compounded, and what did management do with your money?"* (§27.11),
which explains both sections in plain words, states that "not enough years" is about the app rather
than the company and will fill in as §32.6 converges, and warns explicitly about the divergence case
below. The monthly and quarterly routines now tell the reader when to open them: before buying, and
when reviewing holdings, because a strong record with a weak latest year is the earliest warning
available.

**Stock page** — two sections below the §41 single-year checklist: *"Has it actually compounded?"*
(the six gates, each showing the years it held in over the years measurable) and *"What has
management done with your money?"* (the five components with the figure behind each). Both render
every gate that could not be measured or does not apply, never dropping them (Gotcha 68).

⚠️ **When the two compounding panels disagree, the UI must say so.** §41 answers "can it" from the
latest year and §43 answers "has it" across the record, so a badge reading **No** can sit directly
above one reading **Held up** — as it does on BEL, whose eight-year record is strong while its most
recent year is not. That is not a contradiction, it is the thesis-drift case and it is the most
useful thing on the page, but an unexplained divergence is a §21 defect: the reader is not a
stock-market expert and will read two adjacent badges as the app arguing with itself. A callout
names the gap and what it means, in both directions (strong record with a weak year; strong year
with no record, which is what a cyclical shows at the top of its cycle). This is Gotcha 85's rule
adapted rather than applied — the two panels *should* answer differently, so the fix is to explain
the difference rather than to reconcile it.

`GET /api/fundamentals/long-horizon?symbol=` returns both §42 and §43 with `yearsOfAccounts`, the
resolved symbol (Gotcha 84 — a BSE-held position reads its NSE history and records which answered)
and a coverage note. DB-only, dashboard-safe. Returns a `NOT_MEASURED` record rather than a 404
when there is no history, because *"nobody has backfilled this symbol yet"* and *"this company has
a poor record"* must never render alike (§21 rule 7).

Computed **on read**, never stored — the §12.11 and §41 precedent, and here it matters more than
usual: the next backfill batch deepens the history, so a persisted copy would disagree with the
record it describes within days.

---

## 34. Concall & Management Quality ✅ Active (measurement only, 2026-08-26)

**The gap this closes.** No management input at all: guidance versus delivery, execution history. Half of early-stage conviction is the jockey.

**Why it is cheap.** Earnings-call transcripts are mandatory exchange filings under SEBI LODR Reg 46 and arrive as PDF attachments on the corporate-announcements feed already fetched. No new scraping surface — just the attachment nobody was opening.

### 34.1 Transcript extraction

`fetchAnnouncementRecords()` returns announcements *with* their attachment URLs (the existing `fetchCorporateAnnouncements` flattens them to display strings and loses the link). PDF text via Apache PDFBox 3.0.3 — the one new dependency, text extraction only.

> ⚠️ **An intimation is not a transcript.** "Intimation of earnings conference call to be held on…" announces a call that has not happened. Treating it as a transcript feeds an empty document to the model, which will then extract confident guidance from nothing. `AnnouncementRecord.isTranscript()` requires a `.pdf` attachment and rejects subjects containing *intimation* / *notice of* / *prior intimation*.

A scanned (image) PDF yields no text and returns `NOT_READABLE` — an absence every caller treats as one.

### 34.2 What the AI does and does not do

The model **extracts and summarises**; it never scores. Five fixed sections: GUIDANCE (pipe-delimited, checkable claims only), CAPEX AND EXPANSION (cross-checks §31 — management saying what the balance sheet shows is high-signal), TONE SHIFT, WHAT THEY AVOIDED, PLAIN SUMMARY.

Transcripts run 30–60 pages; text is truncated to 60,000 characters — roughly prepared remarks plus early Q&A, which is where guidance lives. Truncation is reported, not hidden. On demand or for holdings only, never across the screening universe.

### 34.3 The guidance ledger — the actual product

`guidance_items`, unique on (symbol, quarter, metric). Records the claim **when it is made**, fills in the outcome when the period reports, and lets a credibility ratio accumulate.

Nobody keeps this score, because it requires writing the claim down at the time and returning a year later. That is the whole feature.

- A `PENDING` item is **never** counted as met or missed. `dueDate` defaults to one year out; marking something missed before it is due manufactures a bad record.
- The ratio counts **resolved items only** — pending promises must not dilute the denominator, or a company that guides often would score worse than one that says nothing.
- `credibility()` returns `TOO_EARLY` until **≥4** promises have come due. Two-of-two is 100%, and that is exactly the number that would mislead.
- `NO_DATA` is reported as *unknown*, never as a bad record. Absence of a record is not a negative signal; it means nobody has checked.
- Resolution is **manual** (`POST /api/concall/resolve/{id}`). Deciding whether "margins will improve" was met is a judgement from the results; automating it would fill the ledger with confident nonsense.

**Only the measured delivery ratio may ever become a scoring input**, and not before four resolved quarters exist. The AI's reading of transcript tone stays out of Deep Research dimension 22 entirely — feeding an impression into another model's analysis launders it into evidence.

### 34.4 Surfaces

`POST /api/concall/analyze/{symbol}?record=&quarter=` (live NSE + AI — POST precisely so no page load can reach it, and not on the UI on-demand allowlist), `GET /api/concall/credibility/{symbol}`, `GET /api/concall/ledger/{symbol}`, `POST /api/concall/resolve/{id}`. Deep Research dimension 22.


---

## 35. Core Holdings — "which stock should I never sell" ✅ Active (observation mode, 2026-08-26)

Answers the first of the investor's two questions directly. Every ingredient already existed per
holding — capital-efficiency verdict, financial quality, earnings consistency, thesis drift,
forensic flags, insider pulse, conviction record — and nothing combined them. Meanwhile the exit
machinery contradicted the question: `HoldingsAnalysisService.determineRecommendation()` returns
SELL on a technical score under 40, and `ExitTimingAlertService` fires RSI / EMA50 / momentum
alerts on every holding equally, so a compounder in a routine 20% drawdown got a SELL.

Design record and review findings: [CORE_HOLDINGS_RECYCLING_PLAN.md](CORE_HOLDINGS_RECYCLING_PLAN.md).
The capital-recycling half (§36) is **deferred** pending its evidence gate — next review
**2026-11-30**.

### 35.1 Tiers

| Tier | Meaning |
|---|---|
| **CORE** | The *business* has passed every quality check that could be run on it, and the thesis is intact. Price is deliberately not one of the checks. |
| **CORE_WATCH** | Every hard gate passed but one soft signal is off. Treated as CORE by the overlay; surfaced for attention. |
| **SATELLITE** | At least one hard gate failed. Normal exit and alert behaviour. |
| **UNCLASSIFIED** | Not enough evidence to judge. Reported with the missing inputs named. Normal exit behaviour, and **never eligible for a future sale proposal** — we do not suggest selling what we could not measure. |

UNCLASSIFIED is not a weak SATELLITE. SATELLITE is a finding; UNCLASSIFIED is the absence of one.

### 35.2 The seven hard gates, and what "measured" means

G1 capital efficiency (financials judged on ROE ≥ 14% and ROA ≥ 1.0% instead of a ROCE verdict) ·
G2 financial quality ∈ {HIGH_QUALITY, DECENT} · G3 earnings consistency ≥ 60 · G4 zero forensic
flags · G5 thesis intact (decay ∈ {INTACT, WATCH}, purchase drift > −25) · G6 insider pulse ≠
STRONG_DISTRIBUTION · G7 stated horizon ≥ 36 months where a conviction record exists **and the
horizon was actually set by the investor** — `holding_conviction.horizon_stated`. Every
auto-generated record is seeded with 24 months, which is under the bar; reading that as intent
failed 14 of 33 holdings on a number nobody chose (B-057). A seeded horizon is `PASS_NO_DATA`.

A gate has **four** states, not two: `PASS`, `FAIL`, `PASS_NO_DATA` (nothing to look at — passes,
but is not evidence), `UNMEASURED` (the input needed to judge is absent).

- Any `FAIL` → SATELLITE.
- Otherwise **≥ `min-measured-gates` (5) gates carrying real evidence** → CORE, or CORE_WATCH if a
  soft signal is off.
- Fewer than that → UNCLASSIFIED, with each unmeasured gate named.

**Only `PASS` and `FAIL` count toward the quorum.** G4 with no forensic history, G6 with no insider
filings and G7 with no conviction record all pass on absent data — counting those would let a
holding with nothing on file collect three free passes and reach CORE on two real gates. G4's
reasoning ("absence of evidence is not a flag") is right for the pass/fail decision and wrong for
the coverage count. This is the §21 rule 7 discipline applied to a gate rather than to a number.

Soft signals (CORE → CORE_WATCH, never SATELLITE): decay WATCH; insider DISTRIBUTION;
capital-efficiency SOLID with ROCE falling two years running; financial-quality DECENT with
interest cover under 4×. The earnings trend-break signal the design listed is **not** included:
it is not persisted on `multibagger_scores` and would cost a live NSE call per holding inside the
10:30 job. The daily email carries its own trend-break section, so the reader still sees it.

### 35.3 Durability score — "based on past data"

Five components worth 20 points each, **renormalised over the components actually measured**:

| | Component | Needs |
|---|---|---|
| D1 | Return-on-capital persistence — years in the last five clearing 15% ROCE (financials: 1.2% ROA), 4 points each | 4 FYs |
| D2 | Growth consistency — consistency score ×0.2, +4 when sales compound ≥ 10%/yr with profit keeping pace | screening row; CAGR part needs 3+ FYs |
| D3 | Balance-sheet trajectory — leverage flat or falling +10, effectively debt-free +5, no dilution +5 | 3 FYs with debt/equity computable at both ends |
| D4 | Cash discipline — 3-year ΣCFO/Σprofit ≥ 1.0 → 20, ≥ 0.8 → 14, ≥ 0.6 → 8 | 3 FYs |
| D5 | Hiccup recovery — **closed episodes only** | ~3 years of daily closes |

**D5 is the component the review caught running backwards.** As first drafted it counted drawdowns
that had recovered to a new high and deducted for one that had not — which can only be satisfied by
a stock near its high, and downgrades a core holding at exactly the moment the tier exists to hold
it. It now scores **only drawdown episodes that closed at least 18 months ago** (+5 recovered
within 12 months, +3 within 24, capped at 15, plus 5 for a 5-year price CAGR ≥ 12%). The episode a
holding is in *right now* is described in the coverage text and excluded from the score entirely.

`durabilityScore` is **null** below `durability-min-components` (3). A score computed from one
component looks like the same kind of number as a fully-measured one, which is worse than no
number. Coverage text always says what was measured and what was not.

ROCE here is `(net profit + interest) / (equity + borrowings)` — a **post-tax** proxy, because
profit before tax is not stored on `annual_fundamentals`. It reads lower than the pre-tax ROCE on
the §12.8 dimension, so the threshold is conservative rather than generous.

### 35.4 Coverage — what this looks like on real data

Pre-import baseline, measured 2026-08-26: `annual_fundamentals` held 378 symbols with a **maximum
of one financial year each**, so D1–D4 were unbuildable for the whole universe, not merely thin for
the portfolio. 7 of 33 active holdings had no `multibagger_scores` row at all and were therefore
unclassifiable however much history was imported. Both halves are the A0 gate in the plan's §13;
the universe half shipped with this section (those seven were added to the screening universe), and
the history import is the user's.

**Expect roughly half the portfolio to be UNCLASSIFIED at launch.** That is principle 4 working,
not a defect — but each row must say *why*, and the count must fall as history is imported.

**First live run, 2026-08-26**: 19 SATELLITE, 14 UNCLASSIFIED, **0 CORE**. Three gates are
`PASS_NO_DATA` for every holding — G4 (the forensic screen needs ≥ 3 FYs, the maximum on file is 1),
G6 (no per-stock insider disclosures captured), G7 (every conviction record carries a seeded
horizon, B-057). That is a ceiling of four evidence-carrying gates against a quorum of five, so no
holding can reach CORE until the history import runs. **Do not lower `min-measured-gates` to fit
this**: it would trade the only property the tier has — that CORE means something was checked — for
a report that looks complete.

### 35.5 The overlay — what a tier actually changes

Applied at report and alert time only. The stored `holdings.recommendation` column is **never**
modified, so downstream consumers keep reading the raw signal. (The ML training collector this
line originally protected was removed on 2026-08-28; the rule stands for the same reason.)

| Path | With the overlay, for CORE / CORE_WATCH |
|---|---|
| Exit-timing alerts | **Observation mode (default)**: the four technical alerts (NEAR_RESISTANCE, RSI_OVERBOUGHT, BROKE_SUPPORT, MOMENTUM_REVERSAL) still fire and are sent; a footer names those that landed on a core holding. **Suppression mode**: withheld but still named. DEEP_LOSS is retained in both modes, re-titled *"Deep drawdown on a core holding — review the thesis, not the price"* |
| Action Items "Exit Required" | Core rows move to *"Core holdings — hold through the noise"*, with durability and the reason. The signal is shown, not deleted |
| Action Items "Book Profit" | Core rows excluded. The remainder carries the B-056 caveat: the rule behind the table fires on P&L > 50% **and** RSI > 70, a momentum rule inherited from the intraday era that flags the best compounders while they run |
| Displayed recommendation | `HOLD_CORE (SELL)` — the underlying label stays visible in parentheses |

**`suppress-technical-exits` defaults to `false`.** Suppressing an exit alert is the *removal* of a
risk control, and the asymmetry in §12 Gotcha 42 runs the other way: new signals ship shadowed,
risk controls ship armed. The gates that would justify the suppression rest on metrics whose
measured IC panel runs +0.042 down to −0.009, with ROE, ROA and cash conversion negative (§25.4).
With suppression on from day one the counterfactual is never observed and the feature can never be
judged. So the first quarter runs as an observation: alerts fire, and what fired on a core holding
is persisted in `holding_classification.observed_alerts`. **Review 2026-11-30.**

**The dedup constraint (§12 Gotcha 19).** `ExitTimingAlertService.evaluateHolding()` mutated its
dedup set inline, so evaluating an alert consumed the day's slot whether or not it was sent.
Filtering suppressed alerts after evaluation would silently disarm them for the rest of the day.
The `recordDedup` overload CLAUDE.md had told read-only callers to use **did not exist** and now
does. Suppression mode evaluates with dedup off and claims a slot only for what it actually sends;
observation mode keeps the ordinary path, because those alerts really are sent and would otherwise
repeat at 12:00 and 14:00. Filtering the holdings list before evaluation was rejected as an
alternative: the footer must *name* what it withheld, and naming it means evaluating it.

### 35.6 Persistence, hysteresis, override

`holding_classification`, one row per holding per day, unique on (symbol, classified_on). Written
whether or not anything changed — a tier history that records only changes cannot answer "what did
this say about that stock last March". Columns: provisional `tier`, `effective_tier`, nullable
`durability_score`, `durability_coverage`, `gates_json`, `soft_signals`, `missing_inputs`,
`reasons`, `observed_alerts`, `pending_change`, `override_applied`.

**Hysteresis is asymmetric.** Promotion into a protected tier and *soft* demotion out of one need
`hysteresis-runs` (2) consecutive **Friday** anchors to agree, so a single bad XBRL parse or a
one-day NSE outage cannot change exit behaviour; the daily row shows "would become SATELLITE — 1 of
2 confirmations" meanwhile. **A critical trigger demotes the same day**: any forensic flag, an
auditor problem, financial quality HIGH_RISK, decay BROKEN, insider STRONG_DISTRIBUTION, or capital
efficiency falling to WEAK/POOR. A holding that has just tripped a forensic flag must not keep its
exits suppressed for a fortnight — an auditor problem escalates rather than deducts (§32.4).
Re-promotion afterwards goes through the normal two-anchor path. Movement that does not cross the
protected boundary (CORE ↔ CORE_WATCH, SATELLITE ↔ UNCLASSIFIED) takes effect at once.

Manual override on `holding_conviction.core_override` (`FORCE_CORE` / `FORCE_SATELLITE`), applied
after hysteresis, recorded with a note and a timestamp (§6.2 audit line). It changes the tier and
hides nothing: a forensic flag still shows in red in the same email.

BSE holdings have no screening history under their own symbol (§6.4). The classifier falls back to
the same company's `NSE:` line matched on trading symbol, and says so in `missing_inputs` —
`multibagger_scores` carries no ISIN to join on.

### 35.7 Scheduling and surfaces

Runs at the end of the existing `HoldingsScheduler.analyzeHoldings()` (10:30 MON-FRI, already
guarded) — **no new `@Scheduled` method**, per §3.4. D5 costs two paced Kite calls per holding
(instrument-token resolution plus candles, as §30.6 measured), so ~33 holdings is about 25 seconds;
candles are cached for the day inside the classifier.

`GET /api/portfolio/core-holdings` and `GET /api/portfolio/core-holdings/history?symbol=&days=` are
DB-only and dashboard-safe. `POST /api/portfolio/core-holdings/classify` recomputes and is refused
from 14:55 with a 409 carrying its reason; `POST /api/portfolio/core-holdings/override` sets the
manual tier. `symbol` is a query parameter throughout (§27.3).

**Dashboard** (§27, shipped 2026-08-27): the portfolio screen leads its Action Items tab with a
core-holdings section — mode banner, the protected list with durability and coverage, and an
explicit not-measured list naming what was missing; the full holdings table gains a Tier column. The
stock screen carries a "Core status" card rendering the seven gates as a three-state checklist, so
`PASS_NO_DATA` reads as *nothing was checked* rather than as a tick. Both read the two DB-only GETs.
Neither is on the on-demand allowlist because neither is on-demand — but note that a page-load
`get()` is ungated (Gotcha 39), so both were verified DB-only by hand rather than by the allowlist.
The freshness strip gains a `holdingClassification` key.

Config lives under `portfolio.core.*` — every key present in `application.yml`, per the B-019 rule.
`portfolio.core.enabled: false` is the kill switch: no rows written, no email section, exit
behaviour exactly as before.

### 35.8 Non-goals

No price forecasts or targets for core holdings (§25.3 stands). No position sizing — that remains
§8 and §10. No AI in tier assignment; the AI commentary may narrate the output. Nothing enters the
multibagger composite, so no weight validation is touched. Nothing is executed.

---

## 37. Watchlist Tracking ✅ Active (2026-08-27)

**Question it answers:** *"I noted this stock on a given day — how has it done since, and is it still a good time to buy?"*

### 37.1 Definitions

| Term | Meaning |
|---|---|
| **Tracked stock** | A row in `watchlist` with `active = true`. The table is the source of truth; `watchlist.symbols` in application.yml is a **seed list read once** (§37.5). |
| **Added on** | The calendar day the investor put the stock on the list. Seeded rows use the row's `created_at` (first analysis) — an approximation, labelled "config". |
| **Price at add / Nifty at add** | Kite last price and `NSE:NIFTY 50` captured at the moment of adding. **Null for seeded rows** — their return reads *not measured*, never 0 (§21 rule 7). |
| **Return since added** | `(current − priceAtAdd) / priceAtAdd`. Null when either leg is missing or `≤ 0` (Gotcha 22). |
| **vs Nifty** | Return since added minus the Nifty return over the same period, in percentage points. "Nifty now" is the latest `watchlist_daily_snapshot.nifty_close` (labelled *as of {date}*), so the page stays DB-only; a manual Refresh updates it live. |
| **Quality score** | The multibagger composite (0–100) from the **latest** `multibagger_scores` row for the symbol — *is this a business worth owning for years?* Null when never screened. |
| **Timing score** | `watchlist.overall_score` (technical 60 / momentum 40, with a fundamental blend when valuation data exists) — *is today a sensible day to pay this price?* |
| **Verdict** | `BUY_NOW` / `ACCUMULATE` / `WAIT_FOR_PULLBACK` / `HOLD_OFF` / `AVOID` / `NOT_MEASURED` (§37.3). Computed at read time; never stored on the row. |
| **Removed stock** | `active = false` with `removed_on` and `price_at_removal` (the row's last analysed price — no broker call). Shown collapsed with its *return while watched*. Re-adding starts a fresh episode. |

The two scores are **never blended into one number**: a 3-year quality view and a 3-week timing view answer different questions, and blending hides whether a low number means "bad business" or "bad entry point".

### 37.2 Data model

`watchlist` gains nullable columns `added_on`, `price_at_add`, `nifty_at_add`, `added_note` (500), `source` (`MANUAL`/`SEED`), `active`, `removed_on`, `price_at_removal`, `in_holdings`, `adhoc_quality_score`, `adhoc_quality_at`. The analysis path (`WatchlistAnalysisService.analyzeSymbol`) overwrites every analysis field on each run and **never touches the membership fields**. The new columns are in `SchemaMigrationRunner.ENSURE_COLUMNS` (Gotcha 74).

`watchlist_daily_snapshot` (unique `symbol, snapshot_date`): `close`, `nifty_close`, `timing_score`, `quality_score`, `trend_direction`, `verdict`, `return_since_add_pct`. See §37.4.

### 37.3 The verdict — ordered rules, first match wins

Pure function `BuyTimingVerdict.evaluate(input, thresholds)`; thresholds under `watchlist.verdict.*` (every key present, B-019 rule). Pinned by `BuyTimingVerdictTest`.

| # | Condition | Verdict |
|---|---|---|
| 1 | quality **and** timing both unmeasured | `NOT_MEASURED` |
| 2 | financial quality `HIGH_RISK`, or any non-`INFO` forensic flag | `AVOID` |
| 3 | liquidity tier `THIN` (`UNKNOWN` does not fire) | `AVOID` |
| 4 | quality measured and `< quality-min-hold` (50) | `AVOID` |
| 5 | decay `BROKEN` / `DECAYING` (`STALE` / `NO_DATA` never block; `WATCH` only annotates the reason) | `HOLD_OFF` |
| 6 | reverse-DCF `EXTREMELY_EXPENSIVE` | `HOLD_OFF` |
| 7 | entry signal `AVOID` or trend `BEARISH` | `HOLD_OFF` |
| 8 | return since added `> runaway-return-pct` (15) **and** RSI `> runaway-rsi` (60) | `WAIT_FOR_PULLBACK` |
| 9 | RSI `> rsi-overbought` (70) | `WAIT_FOR_PULLBACK` |
| 10 | price `> EMA50 × (1 + stretch-above-ema50-pct/100)` (10%) | `WAIT_FOR_PULLBACK` |
| 11 | quality unmeasured, timing measured | `ACCUMULATE` if signal ∈ {STRONG_BUY, BUY}, else `HOLD_OFF` — reason names "not in the screening universe" |
| 12 | quality `≥ quality-min-buy` (65) and signal ∈ {STRONG_BUY, BUY} | `BUY_NOW` |
| 13 | quality `≥ 65`, signal HOLD or unmeasured | `ACCUMULATE` |
| 14 | quality 50–64 and signal ∈ {STRONG_BUY, BUY} | `ACCUMULATE` (small position) |
| 15 | otherwise | `HOLD_OFF` |

**Null discipline.** An unmeasured input never triggers a rule: a missing forensic column is not a clean bill (rule 2 needs a measured flag), a missing RSI is not "not overbought" (rules 8–9 need a measured RSI), a seeded row's missing return cannot trip rule 8. The result carries `qualityMeasured`, `timingMeasured` and the `notMeasured` list, and the UI renders each as a striped marker or a parenthetical, never as a number.

### 37.4 Daily snapshot & the DB-only page

There is no daily-close table for stocks the investor does not own (`holdings_history` is holdings-only; candles are a live Kite call). The **15:00** fire of the existing 11/13/15 job therefore writes one `watchlist_daily_snapshot` row per active symbol (close, one Nifty quote per run, scores, verdict). That is what lets the page draw a 90-day sparkline, quote "vs Nifty", and keep a verdict history without touching the broker on load.

**Free backfill:** add and Refresh already fetch 300 daily candles for the technical analysis; the last 90 closes are written into the snapshot table from that same payload (no extra call, `nifty_close` null for backfilled days), so the sparkline is populated the moment a stock is added. Under two points the cell reads *"Building history — n of 90 days"*.

### 37.5 Seed, add, refresh, remove

- **Seed** (`WatchlistSeedRunner`, `ApplicationReadyEvent`, idempotent): a YAML symbol with no row is inserted (`source=SEED`, `added_on=today`, no price); a legacy analysis row with no `added_on` is stamped from `created_at`; a stamped row is skipped; **a removed row is never resurrected**. Nothing is analysed at boot (09:00, token may not be valid) — the 11:00 run does it. Logs one line: *"Watchlist seed: N inserted, M stamped, K unchanged"*.
- **Add** (`POST /items`): normalise (`NSE:` default, upper-case, `-BE/-BZ` stripped per B-013); refuse 409 if already active; capture live price — **refuse 422 when Kite returns no price** rather than store a row whose return can never be measured; Nifty captured quietly (null → excess reads not measured); the technical analysis runs immediately, and its failure does not roll back the add. **Quality is never computed on add** — it arrives with the next 14:00 screening, or never if the symbol is outside the universe, and the response says which (`inUniverse`).
- **Refresh** (`POST /items/refresh`): re-analyse one row, backfill, upsert today's snapshot with the live close and Nifty. `quality=true` runs `evaluateSingleStock` (Gotcha 50 — never `screenSingleStock`) and stores it as `adhoc_quality_score`, rendered *"ad-hoc, as of {date}"* and never mixed with the screening composite.
- **Remove** (`POST /items/remove`): soft-delete, no broker call.
- **Guard** (B-049 pattern): add, refresh and the two all-symbol POSTs return **409 with the reason in the body** from 14:55 to the close. The UI disables the form with the same reason from the server clock.

### 37.6 Surfaces

`watchlist.html` (nav "Watchlist"): KPI row · add form · main table (Stock · Added · Sector · Price · Since added · vs Nifty · Quality · Timing · Trend + sparkline · verdict + reason · note · Refresh / + Quality / Remove) · collapsed *Removed stocks* table. `market.html` keeps a five-row teaser. `stock.html` shows an *On your watchlist* card when the symbol is tracked. **`screener.html` and every stock table on `discovery.html`** carry a **"+ Watch"** button beside the stock name (`js/watch-button.js`): one DB-only GET at load learns which symbols are already tracked (rendered "✓ Watching"), and the click is the same `POST /items` write with a note recording where the stock was found. Actions on the watchlist table sit right after the stock name as compact ↻ / ↻Q / ✕ buttons so they never need a horizontal scroll. Freshness keys `watchlistAnalyzed`, `watchlistSnapshot`. The 11/13/15 email gains *Added* and *Since added* columns and a "What this means" box.

### 37.7 Non-goals

No orders. No blended score. No quality computation on page load or on add. Re-adding a removed stock does not preserve the earlier episode (its removed row is overwritten — v1). No AI in the verdict; the rule table is the whole logic.

---

## 38. Learning Substrate — provenance & coverage ✅ Active (2026-08-29)

### 38.1 Purpose

The system is asked regularly whether it can "learn on its own and improve the accuracy of its
picks". It cannot yet, and the blocker is not the algorithm — it is that two prerequisites for
honest learning have been missing, and both are cheap:

1. **Provenance.** A score row records the number, not the engine that produced it. B-019 scaled
   every composite by 15% for months and B-018 added a constant to every stock, so a "63" from
   June and a "63" from September are different measurements filed under one name. Every accuracy
   figure, IC panel and future training set that pools across such a boundary is pooling
   incomparable observations, and afterwards there is no way to tell which rows came from which.
2. **Coverage.** A dimension's IC reads ~0 both when the signal does not predict returns and when
   it was never measured on most of the universe. The system has twice spent months treating the
   second as the first: Institutional Interest scored every stock exactly 40 for over three months
   (bug #9), and monthly RSI returned a constant 50.0 for the entire universe because the 365-day
   fetch window cannot produce the 15 monthly bars RSI-14 needs (B-060).

This section ships both. **It contains no model and changes no score.** SPEC §25.5's gate on
adaptive re-weighting is untouched, and the arithmetic behind it has not changed — the ~8,900
30-day MULTIBAGGER outcomes are ~82 screening dates of a highly correlated cross-section, worth
roughly five independent periods, against a measured edge of +1.46pp at t≈1.24.

### 38.2 What is recorded

**Provenance** — `multibagger_scores.scoring_version` and `recommendations.scoring_version`, format
`<engine><codeRevision>-<6 hex>` (e.g. `mb1-7a1c3e`). The hash covers everything that can move a
composite from configuration: the eight weights (a redistribution still summing to 1.0 is a
different engine) and every shadow-mode flag with its bonus magnitudes. The code revision is a
hand-maintained constant in `ScoringVersion`, bumped when scoring *logic* changes in a way
configuration cannot express. QUANT_DISCOVERY and SECTOR_REVERSAL keep their scoring in code, so
their versions track only the revision constant.

Deliberately **over-sensitive**: a parameter that matters only while a flag is on still enters the
hash. Splitting two runs that in fact scored identically costs a needless partition in a later
analysis; merging two genuinely different engines is unrecoverable. Prefer the recoverable error.

**Coverage** — `screening_coverage`, one row per (screeningDate, signal), written at the end of
every full screening run. Per signal: `measured`, `notApplicable`, `notMeasured`, coverage
percent, cross-sectional mean and standard deviation, and a `collapsed` flag. Each row also
carries the run-level counts — `universeSize`, `screenedCount`, `qualityRejectedCount`,
`failedCount` — which sum to the universe, so a symbol **rejected by the tier gate** (a
decision) is never confused with one that **could not be scored** (a blind spot, Gotcha 34).

`collapsed` is only ever set for signals on the engine's **0-100 scale** (the seven dimensions,
the shadow-mode scores, and the two bounded RSIs). `MIN_HEALTHY_STDDEV = 5.0` is a threshold for
0-100 scores and is meaningless on a ratio: the first live run flagged debt-to-equity as
collapsed at sd 0.5 around a mean of 0.3, which is a *wide* spread. Ratios still record mean and
spread for trending; they get no verdict. A false alarm on the system's loudest channel is how a
real one gets ignored.

### 38.3 Three states, not two

| State | Meaning | In the coverage denominator? |
|---|---|---|
| `MEASURED` | a real value was produced | yes |
| `NOT_APPLICABLE` | the signal legitimately does not apply — ROCE, debt-to-equity, gross margin and capex on a bank (§12.8) | **no** — a bank without a ROCE is not a data gap |
| `NOT_MEASURED` | a genuine gap, including an insurer's `NO_DATA` | yes |

Collapsing NOT_APPLICABLE into either neighbour is the error `GateStatus.PASS_NO_DATA` exists to
prevent one level down (Gotcha 68): a finding and the absence of a finding are different things.
`coveragePercent` is **null**, not 0%, when every stock was not-applicable — a percentage over an
empty denominator is nothing, not zero. `collapsed` is **null**, not false, when the spread could
not be computed.

### 38.4 Where it surfaces

- `GET /api/accuracy/coverage` — latest run that has rows (walks back rather than assuming today,
  Gotcha 20); `?date=` for a specific run. DB-only, dashboard-safe.
- `GET /api/accuracy/coverage/trend?signal=Valuation&days=90` — one signal over time, the view
  that shows a signal degrading long before its IC can say anything. DB-only.
- The screening run's own log: one INFO line naming every signal at or below 50% coverage, and an
  **ERROR** for any collapsed spread — the persisted sibling of `logDimensionVariance`, which
  raises the same condition live. Both matter because the failure mode shared by bug #9 and B-060
  was that the number existed and nobody went looking.
- **Dashboard, `accuracy.html`** — a *"Could the app even measure these signals?"* section sitting
  directly below the per-dimension IC panel, because the two are only meaningful together. Run
  header (universe / retained / tier-rejected / unscoreable / version), an alert naming the
  signals that cannot support a conclusion, and the 36-row table sorted worst-coverage-first.
  Null coverage and null spread render as the striped *not measured* marker, never as 0
  (§21 rule 7); a ratio's blank verdict reads *"Not a 0-100 scale"* rather than passing as healthy.
- **Dashboard, `screener.html`** — the scoring-engine version beside the run date, so a
  rescaling of every score on the page is visible where the scores are.

Both are DB-only and safe on page load — verified by hand, because a page-load `get()` is
ungated and only `getOnDemand()` carries the allowlist (Gotcha 39).

### 38.5 Known gaps (stated, not filled in)

- **`forensicFlags` has no coverage row.** A null there means "clean" or "never checked" and the
  two cannot be told apart from `multibagger_scores` — the distinction lives in
  `ForensicResult.notMeasured`, which is not persisted (Gotcha 44). A guessed coverage number
  would be confidently wrong, which is worse than the absent row. Fixing this means persisting
  the forensic `notMeasured` set, not inferring it.
- **The financial-sector marker is `capexVerdict = NA_FINANCIAL`.** Not
  `capitalEfficiencyVerdict`: NSE's capital-efficiency analysis puts `NA_FINANCIAL` on its
  *sub*-verdicts (`roceVerdict`, `leverageVerdict`), which are not persisted, while the
  persisted composite still grades a bank on ROE/ROA and reads `SOLID` or `AVERAGE`. Measured
  on the first live run: **0 of 295** rows carried `capitalEfficiencyVerdict = NA_FINANCIAL`
  against **24** carrying `capexVerdict = NA_FINANCIAL`, all 24 with a null ROCE. The first
  draft keyed on the wrong field and its not-applicable branch never fired once — every bank
  counted as a ROCE coverage gap. Insurers return `NO_DATA` and remain genuine gaps, which is
  correct: their data is missing, not inapplicable.
- **Coverage is written only by `runFullScreening()`**, never by the single-stock path: a
  one-symbol run would overwrite the day's vector with a sample of one.
- **Ratio-scaled signals carry no collapse verdict at all**, so a genuinely dead ratio would
  show only as a flat `stdDev` trend that someone has to read. Fixing that needs a per-signal
  healthy-spread expectation, not a shared constant.
- **Historical rows carry no version.** They are not back-filled with today's stamp; a null must
  read as unknown provenance.

### 38.6 First live run (2026-08-29) — what the vector immediately found

36 signals over 295 retained stocks (366 universe, 71 tier-rejected, 0 unscoreable),
version `mb1-fc6b23`:

| Finding | Reading |
|---|---|
| **InsiderPulse score & verdict: 0% coverage** | SPEC §28's signal is measured on **no stock at all** in the screening path. It is shadow-mode so nothing is mis-scored, but its IC would have read as "no signal" indefinitely. |
| **MonthlyRsi: 0% coverage** | The correct post-B-060 state — the 365-day window cannot produce 15 monthly bars — and now visible as an explicit gap rather than a constant 50.0. |
| **SectorTailwind: 30% coverage, sd 4.7 (collapsed)** | An **8%-weighted dimension** measured on under a third of the universe and not separating the third it does measure. |
| **TurnaroundVerdict: 7% coverage** | Expected — needs ≥3 years of `annual_fundamentals`, populated only where history has been imported (§32). Now quantified rather than assumed. |

None of these are acted on here. They are the first entries on the list of things worth fixing
**before** any weighting question is reopened — a dimension nobody can measure is not evidence
about that dimension's worth.

### 38.7 What comes next (and what still blocks it)

| Step | State |
|---|---|
| Shadow composites — compute N alternative weight vectors per run, persist all, steer with none | ✅ **Shipped 2026-09-05** (§38.8) |
| Walk-forward harness with purge/embargo the length of the horizon | ✅ **Shipped 2026-09-05** (§38.9) |
| Mechanical promotion gate — k independent periods, IC stable across two reviews, t-statistic deflated for variants tried | ✅ **Shipped 2026-09-05** (§38.10) |
| Constrained adaptive re-weighting — shrink toward current weights, cap movement per quarter, floor at 0, sum to 1 | 🔴 Blocked on 180-day outcomes, first maturing ~Oct-2026 (§25.5), **and** on the gate above actually passing |
| A cross-sectional ranking model | 🔴 Blocked on evidence from the step above that the panel has learnable structure. Must be grouped by date and purged — the deleted chain's random split leaked adjacent days |

Explicitly unchanged: **measure first, tune later** (§23.3, §25.5). Everything shipped below
**changes no score and adjusts no weight**. What it changes is that the weighting question now
accumulates evidence continuously instead of being re-argued from the current quarter's IC panel
each time it is raised.

---

## 38.8 Shadow Composites ✅ Active (2026-09-05)

### The question

"Can the app learn to improve the accuracy of its picks?" The tempting answer is to re-weight the
seven dimensions on the current IC panel. Gotcha 27 forbids exactly that, and rightly: four months
of a risk-on quarter, ~5 independent periods, several balance-sheet metrics reading *negative*.
The honest answer is to record what a different weighting **would** have scored, on every run,
before any outcome is known — and then wait.

### What is recorded

One row per (screening date × symbol × variant) in `shadow_composites`. Six variants: the live
vector plus five **pre-registered** alternatives, each with a written hypothesis fixed before any
result was seen (`WeightVariantRegistry`):

| Variant | Hypothesis it tests |
|---|---|
| `live` | The incumbent, recomputed from configuration so every comparison is against what actually ran |
| `equal` | Weighting adds nothing over not weighting. The null hypothesis the live weights have never faced |
| `quality-tilt` | Over 1–3 years, quality and valuation should dominate the 59% the live vector gives price behaviour |
| `momentum-tilt` | The opposite claim, stated so it can lose |
| `fundamentals-only` | Price dimensions contribute nothing at a multi-year horizon |
| `price-only` | The complement, and the only variant computable for every stock |

Deliberately far apart rather than small perturbations: a perturbation is unmeasurable at this
sample size, so it would cost significance-threshold deflation while carrying no information.

### Reconstruction, not re-screening — and why that matters

A variant differs from live **only** in how the seven sub-scores are combined. Every bonus, the
market-cap adjustment and the HIGH_RISK cap are computed from data that does not depend on the
weights. So everything the engine did after weighting is recovered as one constant —
`storedComposite − liveWeightedBase` — and re-applied identically to every variant.

Two consequences, and the second is the reason this design was chosen:

1. The `live` variant reproduces the stored composite exactly, and each alternative differs from
   it by precisely the reweighting.
2. **The whole history since April 2026 back-fills** from `multibagger_scores`. Without it the
   evidence clock would start on the ship date and the first honest verdict would be a year away.

`reconstruction_exact = false` marks the rows where the arithmetic cannot be faithful — a
composite on the 0/100 clamp, or sitting at the 54 hard cap where `min(x, 54)` is not of the form
`base + constant` for any known `x`. Those rows are excluded from **every** variant alike, so each
date's cross-section stays the same set of stocks whichever variant is scoring it.

### Where it runs

At the end of `runFullScreening()`, after `persistScores()`. Full runs only — the same rule the
coverage vector follows, and for a sharper reason: a cross-sectional rank over one stock is not a
smaller measurement, it is meaningless. Back-fill is `POST /api/learning/shadow/backfill`,
database-only, no Kite or NSE call.

---

## 38.9 Walk-Forward Harness ✅ Active (2026-09-05)

### What it is built to say today

*"There is no significant separation."* That is the correct answer with ~5 independent periods,
and a harness incapable of producing it would be useless — the previous ML chain was deleted
precisely because nothing in it could report its own insignificance.

### Blocks, not rows

Dates are grouped into blocks one horizon wide. Each date yields one **cross-sectional rank
correlation** between a variant's composite and the forward return; those average into one
reading per block; the t-statistic is computed **over blocks**. So the sample size is the number
of non-overlapping periods, not the 8,933 rows — which are a few hundred stocks moving together,
re-measured every few days (§25.5).

Three deliberate choices:

- **Cross-sectional, never pooled across dates.** Pooling lets one strong month dominate, which is
  how a signal comes to look predictive for having been measured mostly during a rally.
- **Rank correlation, not Pearson.** A composite is an ordering device; one stock that trebled
  should not carry the panel. The existing per-dimension panel keeps Pearson and is untouched.
- **Embargo on by default.** Alternate blocks are dropped so no return window starting inside a
  kept block reaches the next. This halves an already small sample, which is the right trade: an
  overlapping sample does not hold more information, it merely reports a smaller standard error
  for the same information.

**The paired comparison is the powerful statistic.** Per block, `IC(variant) − IC(live)`: both see
the same stocks on the same dates, so the market move common to both cancels instead of drowning
the difference.

Cost: roughly one paced Kite call per symbol (~2 min). Refused from **14:00** with a 409 carrying
its reason (B-049 pattern), because the shared ~2.9 req/s gate feeds the 14:00 screening and the
15:05–15:28 jobs that abort silently at 15:30 if starved.

### One shared candle cache

`DailyCandleCache` was extracted from `RecommendationAccuracyService`, which now delegates to it.
Two caches over the same symbols would double the cost of identical information against the one
rate limit — Gotcha 52's lesson, applied before it could repeat. The window is now **part of the
key**: an entry warmed over 400 days is re-fetched when a caller needs 700, rather than silently
answering "no price" for every date beyond it and shrinking the sample at its oldest end.

---

## 38.10 Promotion Gate ✅ Active (2026-09-05)

Codifies §25.5's judgement call as arithmetic, so changing the bar requires editing a constant in
a commit rather than deciding in the moment that the data looks good enough. Prose is not a gate:
it gets reinterpreted, and always in the direction of shipping.

**Five conditions, each with a specific past failure behind it.**

| # | Condition | Why |
|---|---|---|
| 1 | ≥ **12 independent periods** | Row count reached 8,933 while the honest sample was ~5 |
| 2 | Paired t clears **α = 0.05 / variants tried** | Best-of-five beats an incumbent by construction on a short sample |
| 3 | Positive paired effect vs live | Measured on the same stocks and dates, so the common market move cancels |
| 4 | Same challenger leads **2 consecutive reviews** | One review is a result; the same result next quarter is evidence |
| 5 | Coverage ≥ **90%** of live's cross-section | A variant scoring only the well-documented third is answering an easier question, not winning |

Condition 1 is lenient, not strict: detecting a 0.03 rank-correlation improvement with a
between-block spread of 0.05 needs about seventeen periods. Stated plainly — at 30 days with the
embargo on, twelve periods is roughly two years of screening history, and at 180 days it is
several. That is not pessimism. It is how long it takes to learn something durable about a
multi-year holding decision, and the alternative to waiting is not a shortcut but a false answer.

**`passedExceptStability` is why the gate is strict rather than unpassable.** The first qualifying
review necessarily fails condition 4 for want of a predecessor; recording it as an outright
failure would reset the run every time and no variant could ever accumulate two consecutive
passes. So the *other four* conditions are what the next review reads.

**Reviews are persisted** (`weight_reviews`), every time, whatever they conclude. Condition 4 is
unenforceable without a memory: recomputing "what did the last review say" from today's data would
produce today's answer twice and call it agreement. The deleted ML chain had exactly this hole.

**`PromotionGate.AUTOMATIC_ADOPTION_ENABLED` is permanently `false`.** Even a fully passed gate
produces a recommendation for a person to act on, because the failure mode of automatic adoption
is not a bad quarter — it is a scoring engine that silently becomes something nobody chose, whose
history can no longer be pooled with its own past (§38.1). `PromotionGateTest` fails if it moves.

---

## 38.11 Coverage hole closed: Insider Pulse ✅ Fixed (2026-09-05)

§38.6's first finding was **Insider Pulse at 0% coverage** — measured on no stock at all. The
cause was not the shadow-mode flag (which gates only points, correctly) and not a symbol-format
mismatch. It was the capture budget: `InsiderCaptureScheduler` filled all 80 per-run slots from
active holdings plus `findTopCandidates`, a verdict-filtered query, so **roughly 286 of 295
screened stocks were never probed once**. Its IC would have read "no signal" indefinitely while
the truth was "never measured" — the exact confusion §38 exists to expose, found by the instrument
built to find it.

**Fix**: a third fill stage takes a rotating slice of the *whole* screened universe, so every
symbol is probed within about a working week at an identical NSE call budget. Rotation is derived
from the day of the year rather than a stored cursor — no table, cannot drift as symbols come and
go, and a missed day costs one slice rather than permanently shifting the schedule. The slice
**strides** rather than taking a contiguous run: on a score-ordered list a contiguous slice would
probe only strong stocks one day and only weak ones the next, so a verdict's coverage would
correlate with the day it was taken.

Secondary fix in the same pass: the capture passed a reference price of `0`, so every filing that
omits its own consideration amount stored a null `value` — permanently disabling the size-based
half of the verdict and leaving `insiderNetBuy90dPct` null. It now passes the last screened close.

Still open from §38.5: `forensicFlags` has no coverage row, and fixing it means persisting the
forensic `notMeasured` set rather than inferring it.

---

## 39. Removal Pass — cutting what was never measured ✅ Done (2026-09-03)

### 39.1 Why

The investor's brief was direct: *"remove the feature or functionality which is not giving the
desired results — accuracy and quality are more important than quantity."*

The app had accumulated a second, older personality. Alongside the long-horizon portfolio engine
sat a set of **trading**-horizon features inherited from the intraday system retired on 2026-05-24:
signals with a stop-loss and a target, scored on whether they hit either within days. They were
not merely idle. They competed for the same process-wide Kite budget (~2.9 req/s, B-027) whose
exhaustion has twice starved the portfolio's own scheduled jobs (B-014, B-049), and two of them
answered "is now a good time to buy?" with private rule tables, in an app that had just spent
three bug fixes (B-062, B-065, B-069) making that question have exactly one answer.

### 39.2 What was removed, and the evidence for each

| Removed | Lines | Evidence |
|---|---|---|
| **Option-chain analysis** (PCR, max pain, OI) | ~1,100 | Horizon mismatch, not a scoring failure. Never a `RecommendationTracker` source, so never scored. Ran **every 3 minutes** 09:15–15:30 fetching 30 strikes ≈ **3,000 Kite calls/day**. |
| **Hypothetical-signal tracker** (`SignalTrackingService`) | ~340 | Provably dead: both writers had **zero callers** since 2026-05-24, so the `signal` table took no rows for three months while a **30-second** scheduler queried it ~720×/day. |
| **Sector-reversal engine** (`scanner/sector/`) | ~3,580 | Measured **IC −0.163 @30d, −0.195 @90d** — a higher score predicted a *worse* return. Suppressed from all reports since 2026-08-25; three months of scanning to produce output nobody was allowed to see. |
| **Sector Tailwind dimension** (8% of the composite) | — | Its own characterisation tests documented it: every branch additive (no headwind is expressible), a hardcoded 5-sector allow-list worth +10, and **two attainable values** in the common case — 261 stocks at 40, 30 at 50, correlation with the composite **−0.07**. The §38.2 coverage vector agreed independently: **30% coverage, sd 4.7, collapsed**. |
| **Breakout scanner** + `SignalPerformanceTracker` + daily/weekly performance emails | ~2,550 | A **fifth** surface answering the buy-timing question with its own entry/stop/target, outside the §6.6/§12.11/§12.12/§37.3 vocabulary. Measured only by its own private target-vs-stop scoreboard, invisible to §23. Four universe scans a day. |
| **News-keyword suggestion generator** (`MarketIntelligenceService`) | ~350 | Keyword-matched headlines into BUY/EXIT/CAUTION 4×/day; its only consumers were the two deleted emails. A generator with no reader. |

Net: **~7,900 lines, 22 files, 8 scheduled jobs, ~30 endpoints.** Tests 406 → 403 (the three that
went were the SectorTailwind characterisation cases, quoted above so the evidence outlives them).

### 39.3 Rules this pass establishes

1. **A feature that scores itself is not measured.** If a pick is worth showing, it is worth
   registering with `RecommendationTracker` and being judged on excess return over the screening
   universe at 30/90/180/365 days (§23). A private target-vs-stop scoreboard is a feature grading
   its own homework, and it will always report better than the real loop does.
2. **A signal suppressed pending evidence needs a plausible route back.** Keeping a scan alive "so
   it carries on being scored" is right for one quarter and wrong after two negative ones. State
   the route, or remove it.
3. **Removing a dimension is not re-weighting.** Sector Tailwind's 0.08 was redistributed *in
   proportion* to what the other seven already held (0.18/0.92 → 0.20, and so on), never
   reallocated by IC. Removing something that measures nothing is supportable on ~5 independent
   periods; moving weight between things that do measure is not (CLAUDE.md gotcha 27).
4. **A removal that changes the composite bumps `MULTIBAGGER_CODE_REVISION`** (now 2), so a score
   from before and after cannot be compared as though one engine produced both (§38.1).
5. **When a component is kept alive by a note naming its one remaining consumer, that note is a
   removal candidate, not a justification.** `MarketRegimeDetector` carried *"kept — still used by
   Market Direction"* through two prior passes; when Market Direction went, so did it, and so did
   the 15–40 MB instruments CSV downloaded daily behind it (§39.5).
6. **Delete the producer, keep the record.** `sector_reversal_signals` and the historical
   `multibagger_scores.sector_tailwind_score` column are retained. A funnel that deletes its
   losers cannot be judged (§25.1) — and the negative IC quoted above *is* the evidence for §39.2.

### 39.4 Deliberately kept

- ~~`KiteInstrumentsService`~~ — the belief that `NseDataService` needed it was wrong: that file
  only *mentions* it in a comment. See §39.5.
- Zerodha Pulse news — `MarketImpactNewsService` and Market Direction's news-sentiment signal.
- ~~Market Direction~~ — kept for about an hour, then removed on the same ground it was flagged
  for. See §39.5 above.
- `MarketDataService` (quotes, candles, India VIX) and Zerodha Pulse news — both still have real
  consumers.

### 39.5 Market Direction — removed the same day, and what it dragged with it

Listed in §39.4 as "a candidate for the next pass"; the investor took it the same day.

**Why**: it answered the wrong question and nobody was checking the answer.

- **Wrong question.** It predicted STRONGLY_BULLISH…STRONGLY_BEARISH for *today*, twice a day
  (09:30 and 12:30). No 1–3 year holding decision turns on that. Worse, telling a long-term
  investor the market looks bearish is an invitation to do the single thing this app exists to
  prevent — sell a good business because of the weather.
- **Never measured.** It was not a `RecommendationTracker` source, so §23 had no hit rate, no
  excess return and no IC for it, in months of running. Straight application of §39.3 rule 1.

**What it dragged with it — the real finding.** Market Direction turned out to be the last
consumer of a chain nothing else touched:

| Also removed | Because |
|---|---|
| `GlobalMarketDataService` (overnight S&P/Nasdaq/Nikkei/DAX scrape) | Market Direction was its only consumer |
| `MarketNewsSentimentService` (Google-News keyword sentiment) | Market Direction was its only consumer |
| The whole `regime/` package — `MarketRegimeDetector`, `MarketRegime`, its config and snapshot | `MarketRegimeDetector`'s only caller was Market Direction. It survived the 2026-05-24 and 2026-08-28 passes on a note reading *"kept — still used by Market Direction"*: load-bearing on exactly one edge |
| `KiteInstrumentsService` | Its only remaining caller was `MarketRegimeDetector.getNearestFuturesSymbol`. It downloaded a **15–40 MB CSV at every startup and again at 08:30 every day**, with a 50 MB WebClient buffer and a 5-minute 429 back-off, to serve nobody |
| `trading.regime.*` config, and the monthly `benchmark-symbol` NFO-contract chore | Orphaned with the detector. That chore was a standing maintenance trap: an expired contract used to make ADX read 0 and break regime detection silently |

**Scheduler consequence**: `KiteInstrumentsService` 08:30 was the **only** genuinely pre-market
job exempt from §3.4. This section originally said `TokenManagementService` 08:35 remained as a
second one; that was never true — its cron is 09:45 MON-FRI, an in-window recovery for the
startup login (corrected 2026-09-05, B-076). So §3.4 now has **no** weekday exception at all,
only the Saturday carve-out — the rule is that much easier to hold.

**Rule this adds** (§39.3 rule 6): **when a component is kept alive by a note naming its one
remaining consumer, that note is a removal candidate, not a justification.** `MarketRegimeDetector`
carried such a note through two prior removal passes. Check the edge before writing the note.

**Kept**: Zerodha Pulse news (`MarketImpactNewsService` still uses it), India VIX and Nifty candles
via `MarketDataService` (unchanged, used throughout).

**And one defect the removal exposed (B-073)**: deleting `GET /api/research/market-direction` did
**not** make the path 404. `GET /api/research/{symbol}` is a catch-all over the most expensive
operation in the API — full research, an AI call and an **email** (§27.4) — so the retired URL
returned 200 and ran a research pass on a "stock" called `market-direction`. `looksLikeSymbol()`
now refuses a non-symbol path before any work happens, pinned by `ResearchSymbolGuardTest`.
**General rule: before removing an endpoint, check whether a `{pathVariable}` sibling will catch
its path.** The failure is invisible on the way in — the caller gets 200 and the cost is an email.

---

## 40. Compounder Research Charter ✅ Active (2026-09-05)

§1 states the mission. This section is the working record of **how far each pillar is actually
measured**, what a claim has to survive before it changes a score, and what is queued next. It is
where a new research feature is classified before it is designed (§20 rule 9).

### 40.1 Pillar coverage — what is measured, and what is not

| Pillar | Measured by | Honest caveat | Gap |
|---|---|---|---|
| Business quality | Financial Quality (§12.5, 16% of composite); forensic red flags (§32.4) | Forensics need ≥3 years of `annual_fundamentals`; "no flags" usually means *nothing was checked* (Gotcha 44) | Coverage, not method |
| Growth | Earnings analysis (§12.4), wealth signals (§12.7), capex cycle (§31), turnarounds (§32.3) | Integrated-filing history begins ~Mar-2025, so 8-quarter CAGR fills in by ~2027; capex is shadow-mode | Coverage |
| Cash generation | Capital efficiency (§12.8) — ROCE, ROE, ROA, D/E, real operating cash conversion | Banks and NBFCs use a separate taxonomy; ROCE and D/E are deliberately not computed for them | — |
| Management quality | Insider Pulse (§28, shadow); concall guidance ledger (§34) | The ledger's value is in what it refuses to say, and resolution is **manual** by design (§34.3). Insider Pulse scores zero points | **No capital-allocation record** — see 40.3 |
| Competitive advantage | **Compounding lens (§41)** — five gates over return on capital, cash conversion, leverage, steadiness and margin trend | Reads the **latest year only**. Multi-year history exists for ~19 of 288 stocks, so persistence — the actual evidence of a moat — is unmeasured for 93% of the universe | Persistence. Closed by 40.3 item 1 |
| Valuation | Reverse-DCF expectation gap blended 50/50 with PE-vs-sector (§12.5) | Systematically under-values long-duration compounders; the rebuilt XBRL implementation has no forward returns yet (Gotcha 27) | — |
| Risk | Forensics (§32.4), core-holding gates (§35), diversification (§7), thesis drift (§6.2), macro & geopolitical exposure (§48) | Three of seven core gates pass on absent data and never count toward the quorum (§35.2). Macro exposure is judged only where the map has a rule, and a missing rule is a gap in the app rather than an all-clear (§48.6) | Macro exposure is shadowed, worth zero points, and measured at 180/365d (§48.2) |

**Pre-listing companies sit outside every row above.** An IPO has no filings this app can read, so §45's structure read (fresh issue versus offer for sale, who filled the book) is a *management-quality tell* on the offer, not a pillar measurement, and it contributes nothing to any score. Post-listing, a new company enters the ordinary pillars as its filings arrive, on the §30.4 timetable.

**The binding constraint under all of this is `annual_fundamentals` depth.** A decade-long
compounding read needs a decade of rows. The NSE archive reaches 2011 (§32.5) but the backfill is
per-symbol and manual today, so most of the universe has three years or fewer. That is why 40.3
puts backfill first: the two missing pillars are both computed from that table.

### 40.2 The evaluation frame

- **A compounder claim is judged at 180 and 365 days and beyond.** Those horizons have **no
  matured rows yet** — first picks were issued April 2026 (§25.5). The 30- and 90-day panels are
  an early read and are never the acceptance bar (§19).
- **Row count is not sample size.** 8,933 outcome rows are roughly **five independent periods**
  (§38.9). The engine's own measured edge is +1.46pp excess at t≈1.24, p≈0.28 — that is *no
  significant separation*, stated plainly, and it is the correct thing for the harness to say.
- **The only route from evidence to a weight change is §38.9 → §38.10.** Shadow composites record
  what five pre-registered alternative weightings would have scored, back-filled to April 2026;
  the walk-forward harness measures them in blocks with an embargo; the promotion gate demands
  twelve independent periods, a Bonferroni-corrected paired t, a positive paired effect, the same
  challenger leading two consecutive reviews, and 90% coverage. Nothing adopts automatically.
- **The live vector gives 59% of its weight to price behaviour** (momentum 20, volume 13,
  relative strength 13, price structure 13) against 41% to valuation, institutions and financial
  quality. Under this charter that ratio looks wrong, and it is exactly the hypothesis the
  `quality-tilt` and `fundamentals-only` variants are pre-registered to test. **Do not hand-edit
  the weights to fix it** (Gotcha 27) — that is the failure that ended the previous ML chain.
  Argue it through the gate or wait.

### 40.3 Roadmap (🔨)

In order, because each depends on the one before:

1. ~~**Universe-wide annual backfill.**~~ — **shipped 2026-09-06 as §32.6**, with one deliberate
   deviation from what this item proposed: it runs as an **11:30 weekday batch**, not from inside
   `weeklyFullScreening()`. The instinct behind "not a new Saturday scheduler" was right about
   Gotcha 28 and wrong about capacity — ~4,000 paced requests do not fit a window that already
   holds a full screening run plus an 11–22 minute coarse scan and must finish before the 09:00
   report. A weekday job inside the ordinary §3.4 window is not the Saturday exception, and it
   converges five times faster. Reasoning recorded at §15.
2. ~~**Moat lens** (competitive advantage)~~ — **shipped 2026-09-06 as §41** (single-year) and
   **§43** (the multi-year track record: ROCE persistence, margin stability across a cycle,
   earnings steadiness, share-count discipline). **Dividend payout is now persisted** (§32.2), so
   §41.4's note that retention is "computed and discarded" no longer holds.
3. ~~**Capital-allocation record** (management quality)~~ — **shipped 2026-09-06 as §42**: share
   count, dividends and retention, capex against depreciation, debt trajectory and incremental
   return on capital, reusing B-066's corporate-action discriminator — now settled outright by
   `face_value` (§32.2) rather than inferred from a ratio. **Goodwill additions are not covered**;
   that needs a column the archive filings do not reliably tag.

**What remains** (🔨), in order:

4. **Recalibrate §43's `REQUIRED_SHARE`** against the measured cross-section once §32.6 converges.
   It is the one threshold in either new lens set on judgement rather than on a percentile, and
   §43.4 flags it as such.
5. **Point-in-time back-test of §42 and §43.** `available_from` was added specifically to make this
   possible (§32.2) — evaluate both lenses over every screening date since April 2026 using only
   filings public at each date. This is what stops their evidence clock starting at zero, and it is
   the only honest route into §38.10.
6. **Per-signal coverage rows** for the new gates, so a weak reading and an unmeasured one stay
   distinguishable (§38.2, B-074's lesson: read the coverage row before concluding anything).

Each is scoped in its own SPEC section when picked up, not here.

---

## 41. Compounding Quality — "can this business compound?" ✅ Active (lens, 2026-09-06)

### 41.1 The question, and why nothing answered it

§40.1 records that competitive advantage has no dedicated measurement and that the composite gives
**59% of its weight to price behaviour**. So the app could rank what was working and could not say
which businesses compound — the thing §1 exists for. Every input needed was already computed and
persisted per stock; nothing joined them, and the one existing `HIGH_QUALITY_COMPOUNDER` verdict is
a tally of strengths minus red flags that never reached the dashboard.

**The arithmetic.** A business grows intrinsic value at roughly *return on capital × the share of
profit it reinvests*. Earn 25%, retain 60%, and value compounds ~15% a year — about 4x in a decade.
The first check is therefore the return itself; the rest ask whether it is **real** (cash, not
accruals), **self-funded** (not borrowed) and **durable** (steady, margins not competed away).

### 41.2 Five gates, and what each is measured from

| Gate | Question the reader sees | Passes when | Source |
|---|---|---|---|
| `capitalReturn` | Earns well on the money in it | ROCE ≥ 18% (ROA ≥ 1.5% for a lender) | `roce_percent` / `roa_percent` (§12.8) |
| `realCash` | Profit arrives as real cash | cash conversion ≥ 0.8 | `cash_conversion_ratio` |
| `internallyFunded` | Growth funded from its own profits | debt/equity ≤ 0.5 — **not applicable** to a lender | `debt_to_equity` |
| `steady` | Earnings are steady, not lumpy | consistency ≥ 50 | `earnings_consistency_score` (§12.7) |
| `margins` | Margins are holding up | gross-margin trend ≥ 0 — **not applicable** to a lender | `gross_margin_trend` |

**Disqualifiers**, whatever the ratios say: a **HIGH** forensic flag, or a `HIGH_RISK` financial
quality verdict. Every figure above is computed *from* the audited accounts, so a doubt about the
accounts makes a clean reading meaningless rather than reassuring — the §12.5 auditor rule applied
one level up. MEDIUM is a caution and INFO is not a stop (Gotcha 77), read through the shared
`ForensicSeverity` parser.

**Verdicts.** `COMPOUNDER` (every applicable gate passed, and at least four of them — three for a
lender, which has two inapplicable), `PARTIAL`, `NO`, `NOT_MEASURED` (fewer than three applicable).

### 41.3 Thresholds are measured, not assumed

Set against the live 2026-09-05 cross-section of 288 stocks, the discipline §12.11 established:

| Input | n | p10 | median | p75 | p90 | Bar |
|---|---|---|---|---|---|---|
| ROCE | 229 | 7.7 | 16.9 | 24.1 | 31.6 | **18** |
| Cash conversion | 252 | −1.1 | 1.13 | 1.58 | — | **0.8** |
| Debt / equity | 229 | 0.00 | 0.10 | 0.37 | 0.81 | **0.5** |
| Earnings consistency | 280 | 0 | 41 | 60 | — | **50** |
| Gross-margin trend | 261 | −4.4 | 0.00 | 1.27 | — | **0** |

The ROCE bar sits between the median and the upper quartile, comfortably above a ~12% cost of
capital. **Live result: 22 COMPOUNDER, 195 PARTIAL, 39 NO, 32 NOT_MEASURED of 288** — 8%, which is
the right order for a strict badge. The names it picks are the expected ones: CAMS (48% ROCE), CDSL,
Oracle Financial, Divi's, Dr Lal PathLabs, Persistent, KPR Mill.

### 41.4 Three things it deliberately refuses to do

1. **It never enters the composite.** A lens, not a bonus — the §12.10 contract, Gotcha 30, §20
   rule 9. Computed, shown, worth zero points. Promotion only through §38.10.
2. **It does not gate on reinvestment**, though that is half the arithmetic in §41.1. *(Update
   2026-09-06: dividend payout is now persisted on `annual_fundamentals` — §32.2 — and retention
   is measured by **§42**, the capital-allocation record. This lens still does not gate on it, and
   the reasoning below for why gating on `capexVerdict` was rejected stands unchanged.)* Retention
   needs the dividend payout ratio, which `analyzeCapitalEfficiency` computes and **discards**
   rather than persisting. Gating on `capexVerdict` instead was tried and rejected: only 83 of 288
   read `INVESTING`, and many reading `STEADY` are the asset-light businesses with the *highest*
   returns on capital — CAMS, CDSL and Oracle Financial among them. A gate that failed a business
   for not needing factories would invert the thing being measured. Capex is shown as context and
   scored at nothing. **Persisting payout is the first item that would improve this lens.**
3. **It does not claim persistence.** Every gate reads the **latest single year**. *(Update
   2026-09-06: **§43** now answers the persistence question separately, on a count-of-years rule.
   This lens deliberately stays single-year — the two answer different questions and a stock with
   one good year and no track record must be able to read as exactly that.)* Whether a 25%
   return held for eight years is the strongest compounder evidence there is, and it is
   unanswerable for ~93% of the universe: multi-year history exists for roughly **19 of 288**
   stocks (measured — BEL 8 years, Reliance 6, most others 1). `yearsOfAccounts` carries the count
   so every surface can say "one year of accounts, not yet a track record" rather than implying
   more than was checked. §40.3's universe-wide backfill is what closes this.

### 41.5 Coverage and null discipline

Coverage is inherited from the five inputs, each of which already has a `screening_coverage` row
(§38.2): Roce 86.7%, CashConversion 87.5%, DebtToEquity 86.7%, EarningsConsistency 97.2%,
GrossMargin 97.4%. A gate is `NOT_MEASURED` when its input is null and `NOT_APPLICABLE` when the
question does not fit the business; **neither counts as a pass and neither enters the denominator**
(Gotcha 68). `NOT_MEASURED` renders as the striped marker, never as a low grade — a business whose
filings could not be read has not failed (Gotcha 21, 44).

### 41.6 Lenders, and the second source (2026-09-08, B-092)

**A lender is judged on two checks.** Three of the five never apply to it: leverage and gross margin
by design, and **cash conversion**, because a bank's operating cash flow tracks deposits and lending
rather than the quality of its earnings — it is published for only 4 of 24 financials in any case.
Requiring three applicable gates therefore left **every financial in the universe permanently
`NOT_MEASURED`**, HDFCBANK included, which passed both checks it had. The floor for a financial is
now **2**, cash conversion is marked `NOT_APPLICABLE` rather than missing, and a lender's
`COMPOUNDER` reason says in words that it rests on two checks rather than four. Two checks is
genuinely thinner evidence than a non-financial's four; the fix is to state that, not to hide it.

**The gates read a second source when the first is silent.** The screening row's balance-sheet
fields come from the annual XBRL fetched live during screening, which returns nothing for a
sizeable minority of stocks — while the same figures sit in `annual_fundamentals`, written from
NSE's filing archive by the §32.6 backfill. `CompoundingLensService` fills gaps from the latest
stored year using §12.8's formulas unchanged, so a figure means the same thing whichever source
supplied it. Two rules: it **fills only gaps** (a screening-row figure always wins, so it can turn
`NOT_MEASURED` into a verdict and can never change one that existed), and it **never decides which
symbol spelling answers** — selecting on the post-fallback result let an empty BSE row acquire
figures and out-rank the full NSE one, re-creating B-088 on NTPC, NATIONALUM and ABCAPITAL.
Earnings consistency and gross-margin trend have no fallback: both come from the *quarterly* series
and nothing annual can stand in for them (a substituted unit is the B-047 failure).

### 41.6 Surfaces

- **Screener** — a `Can it compound?` column (sortable, compounders first, unmeasured last) and a
  **`Compounders only`** filter chip. The filter deliberately excludes `NOT_MEASURED`.
- **Stock page** — the full five-gate checklist with the figure behind each, including the gates
  that could not be measured or do not apply, plus the track-record caveat and the capex context.
- **Guide** — *"How to spot a business that can compound"* (§27.11), with the one-year limit stated.
- **API** — `GET /api/dashboard/compounding?symbol=` (DB-only, page-load safe; 404 when never
  screened). Screener rows carry the same fields inline.

Computed **on read**, never stored: it is a pure function of the row, and a persisted copy could
disagree with the row it describes after a re-screen (the §12.11 precedent). Symbol resolution goes
through `SymbolVariants` so a BSE-held position reads its NSE screening history (Gotcha 84).

Pinned by `CompoundingQualityTest` (19 cases): the gate arithmetic, that an unmeasured check is
neither a pass nor a fail, that not-applicable leaves the denominator, that three measured checks
is not enough for a non-financial to earn the badge, that a HIGH flag outranks every good ratio
while MEDIUM and INFO do not, and that history length never decides the verdict.

---

### 41.5 On the portfolio (2026-09-07)

The lens is now shown on **four** surfaces: the screener column, the stock page panel, the
portfolio (a `Compounds?` column on *Everything you own* plus a short summary above the table) and
the watchlist (2026-09-09, the same column beside Quality and Timing).
The portfolio is where it earns its keep, because it is the one screen where the question is not
"is this worth buying" but "is this worth continuing to own".

Three constraints, all of which follow from rules that already exist here:

1. **One lookup, not four.** `CompoundingLensService` is the single DB-backed reader; the stock
   page and the screener were re-pointed at it when the portfolio column shipped, and the watchlist
   was wired to the same call rather than reusing its own `latestScore` helper. The rule table
   (`CompoundingQuality`) was already shared, but the *lookup* was not, and a second copy of a
   lookup is how one stock reads Compounder on one screen and "not measured" on the next
   (Gotcha 85). It resolves the whole portfolio in one query - per-row lookups would be ~130
   queries on a page load, since each holding resolves through up to four symbol spellings.
2. **It sits beside the buy/sell signal, and does not reconcile with it.** Unlike the timing
   verdict (Gotcha 85), this is a *different question*, so a holding may legitimately read "No"
   for compounding and "Buy" for signal. That divergence is explained in the section copy rather
   than smoothed away - SPEC 21's reader must not conclude the app is arguing with itself, and
   Gotcha 105 is the precedent.
3. **The one-year caveat is on the screen, not in a footnote.** Every gate reads the latest year
   of accounts, so a pass means "this looks like a good business today", never "this has
   compounded for years". The summary says so in a callout, and points at the stock page for the
   multi-year record (SPEC 43).

The portfolio summary counts never-screened holdings as **their own group**, named, with their
share of portfolio value - never folded in with the ones that fell short. "We could not look" and
"we looked and it is weak" are different findings (Gotcha 21, 44).

On the watchlist the column sits beside the two scores already there, and the three answer
different questions on purpose: **Quality** is the 0-100 composite (59% of which is price
behaviour), **Timing** is whether today is a sensible day to pay this price, and **Compounds?** is
whether the business is worth owning at all. Note one asymmetry worth leaving alone: the watchlist
resolves Quality from the newest screening row for that **exact** symbol at **any** age, while the
lens takes the newest row within 400 days across symbol spellings. They can therefore differ, so
both are stamped - `qualityAsOf` and `compoundingAsOf` - and a difference is legible rather than
mysterious. Unifying them would be a change to what Quality means, not a tidy-up, and belongs in
its own decision.

**Contributes zero points to any score**, on every surface.

---

## 44. Data Health — the app checking its own data ✅ Active (2026-09-06)

### 44.1 Why this exists

The defect this codebase actually suffers from is not a wrong number. It is **a value that was
never measured, rendered as though it were**. Three instances are on record — Institutional
Interest constant at 40 for three months (bug #9), monthly RSI constant at 50.0 across the whole
universe (B-060), and Insider Pulse producing a verdict for no stock at all (B-074). Each ran
undetected for months. **None was found by looking at a screen**, because in every case nothing
threw, nothing logged an error, and every component behaved exactly as specified. All three were
found by asking two questions: *how many stocks was this measured on*, and *did the answer vary*.

This screen asks both, mechanically, every time it loads — plus whether each table is as new as
its own cron says it should be, and whether the annual-accounts backfill is still moving.

### 44.2 The three checks

| Check | Question | Source |
|---|---|---|
| Freshness | Is each table as new as the cron that writes it? | `/api/dashboard/health` freshness map vs `DataHealth.SCHEDULE` |
| Coverage | Was each signal measured, and does it vary? | latest `screening_coverage` rows (§38.2) |
| History | Do the multi-year lenses have accounts, and is the queue moving? | `FundamentalsBackfillService.coverage()` + `MAX(last_attempt_at)` |

`GET /api/dashboard/data-health` — DB-only, read-only, page-load safe. Heavier than its
neighbours (it resolves the whole screening universe for the depth histogram), so it backs one
screen the reader opens deliberately, never a strip on every page.

### 44.3 Four rules the implementation must keep

1. **A run this app cannot distinguish from a market holiday is never a PROBLEM.** There is no
   holiday calendar in the JVM, so one missing session and a closed exchange are the same
   observation. One session behind is a WATCH that says so; two is a problem, because the market
   is rarely shut twice running and the reader can check that in a second. A channel that cries
   wolf several times a year is how the next real alarm gets ignored.
2. **A known cause has an expiry date.** `KNOWN_ZERO` carries the bug id *and* the date its fix
   shipped. A screening run later than the fix that still reads zero is escalated to a PROBLEM
   rather than excused. Without this, the exception list becomes where the next regression hides.
3. **Every check reports, including the ones that pass.** An empty findings list is
   indistinguishable from a list of checks that never ran — Gotcha 44 one level up. The screen
   shows its own denominator, and every verdict carries the figure it was reached on so the
   reader can disagree with it.
4. **The limits are printed beside the all-clear.** Three things this screen cannot check: whether
   a stored figure matches the company's filing (only reading one annual report beside one stock
   page does that), whether a scoring rule is *right* (these checks ask whether a number was
   measured and whether it varies, never whether it is correct), and anything outside a screening
   run. They are rendered in their own section, not a footnote.

### 44.4 Freshness is not stamped twice

`nav.js` stamps a subset of freshness keys on every page. On `health.html` that subset is
deliberately **empty**: this page answers the same question in full, against each job's own cron,
and a shorter answer directly above it is the two-surfaces-one-question failure (Gotcha 85).

### 44.5 What is not automated

The screen reports; it does not alert. There is no scheduler, no email and no threshold that
changes behaviour anywhere else — consistent with §19, since a data-health verdict is not an
investment signal. Whether a PROBLEM should reach the daily emails is deferred until the screen
has run long enough to establish its own false-alarm rate.

Pinned by `DataHealthTest` (21 cases): the Saturday-scores-on-a-Sunday calendar case that a human
reader gets wrong, the holiday ambiguity, the grace period, the sparse-table rule, the expiry on a
known cause, an empty denominator that is not 0%, and the zero-spread case that no other check
reports.

---

---

## 45. IPO Pipeline & Post-Listing Tracker ✅ Active (lens, 2026-09-09)

### 45.1 Purpose, and the classification §20 rule 9 requires

The investor asked for three things: track upcoming IPOs with apply-or-skip guidance, explain the
application categories and the prerequisites for a shareholder quota, and follow recently listed
issues to say whether they are still worth investing in. This section ships all three, with one
deliberate substitution: **the app never says "apply"**, and §19 now records why.

Before a company lists there is nothing for the seven pillars to read — no XBRL, no shareholding
pattern, no cash-flow history, no filings as a listed company. Any "apply" verdict would therefore
rest either on the prospectus (a 400-page PDF this app does not parse) or on the grey-market
premium, which is a price for the listing-day lottery and not information about the business. What
the exchange feed *does* carry, and what most retail investors never look at, is the **structure of
the offer**: how much of the money goes into the company versus to sellers, and — once bidding has
closed — whether the institutions who read the whole prospectus and met the management wanted it.
That is a management-quality tell on the offer, and it is what the app reads.

| §20 rule 9 item | Answer |
|---|---|
| (a) Pillar | Pre-listing: a **management-quality** read on the offer (are the owners raising capital or leaving?), not a pillar measurement — §40.1 says so. Post-listing: the ordinary pillars, as filings arrive |
| (b) Horizon | Post-listing only, via the §30.4 setup at 180/365 days. The pre-listing read is never scored, because a company that has not listed has no forward return to score it on |
| (c) Coverage row | Not a `screening_coverage` signal — the population is NSE's issue list, not the screening universe (the §41 precedent). The page prints its own denominators instead: *structure read for X of Y issues*, *stage judged for X of Y listings*, *quality analysed for X of Y* |
| (d) Shadow mode | A **lens**. Contributes zero points to any composite, feeds no recommendation, and its verdict vocabulary contains no instruction to transact (pinned by `IpoStructureReadTest.noApplyVocabulary`) |

### 45.2 Data sources — four NSE endpoints, none walled

Probed live on 2026-09-09 with the ordinary cookie jar (`NseDataService`, B-018's rule: verify an
endpoint before depending on it).

| Endpoint | What it carries | Quirk |
|---|---|---|
| `/api/all-upcoming-issues?category=ipo` | Every forthcoming and open mainboard issue: symbol, dates, band ("Rs.139 to Rs.146"), shares on offer, `series` | 11 rows on the probe day |
| `/api/ipo-current-issue` | The open ones with the running total subscription | |
| `/api/public-past-issues` | ~1,430 past issues back to 2012 across `EQ`, `SME`, debt and others: final price, band, listing date | `issuePrice` arrives padded (`"   177"`) or as `"-"`; `listingDate` is `"-"` until listed; month names are upper-case on this feed only |
| `/api/ipo-detail?symbol=X&series=EQ` | `issueInfo` (issue-size sentence, face value, bid lot, lead managers, registrar, RHP / ratios / anchor-book links, employee discount) and `bidDetails` (category-wise shares offered, bid and times) | **Works for issues that closed months ago**, which is what makes the final book recoverable after the fact. "Non Institutional Investors" appears three times in one block and only `srNo` (2 / 2.1 / 2.2) tells them apart |

Parsing is pure (`IpoFeedParser`) and pinned by `IpoFeedParserTest` on rows captured that day.
Three rules: a placeholder is null, never a number (Gotcha 61); a category is matched by serial
number where NSE gives one and by name (employee, shareholder) only where it does not; and the
fresh/OFS split is read from prose and left **unmeasured as a whole** when either leg's amount
cannot be read — one half of a ratio is not a ratio, and a defaulted zero would read as "promoters
cashing out entirely", the strongest claim the read can make. That parser's own test found a real
defect before ship: the fresh-issue span could run into the offer-for-sale figure, so
"a fresh issue and an offer for sale of up to 1,000,000 equity shares" read 10 crore fresh. A
tempered lookahead now stops each leg at the other's name.

**SME (EMERGE) issues are dropped at capture and counted in the result**, not silently: the feeds
mix them in, and §30.1 excludes them structurally.

### 45.3 The structure read — one rule table, computed on read

`IpoStructureRead` (pure) answers "what does the offer say?" with `FAVOURABLE / MIXED /
UNFAVOURABLE / NOT_MEASURED` and a reasons list in the reader's words. Never stored; a stored copy
could disagree with the figures beside it (the §12.11 discipline).

| # | Condition | Verdict |
|---|---|---|
| 0 | Fresh share unparsed **and** no final institutional figure | `NOT_MEASURED` |
| 1 | Final QIB < 1.0× | `UNFAVOURABLE` — institutions, with the full prospectus, passed. Outranks everything |
| 2 | Final retail ≥ 10× while final QIB < 2× | `UNFAVOURABLE` — the hype pattern |
| 3 | Fresh share < 25% | `MIXED` — mostly an exit, however strong the book |
| 4 | Fresh share ≥ 50% and (book not final **or** final QIB ≥ 2×) | `FAVOURABLE` |
| 5 | Otherwise | `MIXED` |

Two refusals carry the weight. **A mid-issue subscription never decides anything**: institutions
bid on the last afternoon, so "QIB 0.55×" on day one — the live reading on LCCPROJECT at 11:56 on
its first day — is normal and means nothing; `subscriptionFinal` is true only once the capture
runs on a later day than the last bidding day. And the read stops at "read further" or "stop": the
page states plainly that a favourable structure is permission to spend thirty minutes with the
prospectus, and lists the six things to read there (objects of the issue, who is selling, three
years of profit beside cash flow, the basis-of-price peer table, post-issue promoter holding and
pledges, promoter-linked litigation).

### 45.4 Lock-in calendar and cycle stage

`IpoLockIn` (pure) turns a listing date into the calendar on which the sellers arrive, per SEBI
ICDR: anchor investors may sell half after 30 days and the rest after 90 (2022 amendment);
pre-IPO investors and the promoters' holding above their minimum after 6 months; the promoters'
minimum 20% after 18 months. Dates run from the listing date and are labelled approximate (the
rules count from allotment, two or three days earlier).

The six-month date is why §30.4 refuses to judge a younger listing, and the same rule now reads
as a **stage**, descriptive rather than an instruction: `HYPE_WINDOW` (inside six months — not
judged, deliberately, however good the chart looks; pinned), `WASHOUT` (past six months, at or
below the listing-day high), `RECOVERING` (above the high, no base yet, or too little history to
tell), `BASE_FORMING` (the §30.4 setup), `NOT_MEASURED` (no broker price history — never
`WASHOUT`).

### 45.5 Application categories, and the prerequisites for a shareholder quota

Stated on the page as SEBI's rules, not the app's opinion, with the arithmetic done per issue by
`IpoApplicationMath` (pure) **at the top of the band** — retail bids at cut-off and is allotted at
the discovered price, so sizing at the bottom of the band is how an application overshoots the
ceiling and is rejected outright (pinned: RENTOMOJO's 37-share lot fits 13 lots at ₹404 and 14 at
₹384, and 14 lots at ₹404 exceeds ₹2 lakh).

| Category | Ceiling | How | Allotment |
|---|---|---|---|
| Retail | ≤ ₹2 lakh | Cut-off price; one application per PAN | Equal chance of **one lot** by lottery when oversubscribed, however many lots were bid — so one lot per person, in each family member's own demat and bank account |
| Small NII | > ₹2 lakh to ₹10 lakh | Must name a price; UPI only up to ₹5 lakh, bank ASBA above | Lottery for the minimum lot count; usually the most crowded pool |
| Big NII | > ₹10 lakh | Bank ASBA | Irrelevant to a retail-sized portfolio |
| Employee | ≤ ₹5 lakh (₹2 lakh, plus ₹3 lakh if under-subscribed) | Employee category; often a discount (RENTOMOJO: ₹20) | Much less crowded — but it concentrates income and savings in one company |
| **Shareholder** | ≤ ₹2 lakh for retail-basis allotment | **Hold ≥ 1 share of the parent in demat on the record date — the day the RHP is filed with the Registrar of Companies, about a week before the issue opens.** May apply in this quota **and** in retail with the same PAN; the rules treat them as separate applications | The reserved portion is usually far less oversubscribed, so the odds are several times better. The one structural edge a small investor has — and it has to be planned before the prospectus, not on opening day |

The app flags an employee or shareholder quota from the category book; **the prospectus names the
parent**, and the app does not guess it (a parent inferred from a company-name prefix would be
exactly the kind of confident wrong answer Gotcha 21 exists to prevent).

### 45.6 Schedule and cost

`IpoCaptureScheduler.captureDaily` at **12:15 MON-FRI**, `isMarketOpen()` guard — a genuine gap
clear of the NSE-heavy 09:45 / 10:00 / 11:30 jobs, the 14:00 screening, the 14:45 insider capture
and the close ramp; only the Kite-light 12:00 exit check is near it. Cost: three NSE list calls;
one paced detail call per pipeline issue (~10), per issue whose final book became readable in the
last 10 days, and for a bounded 25 older listings per run until every listing in the 36-month
window has its structure on file (~450 → three to four weeks); one paced Kite quote per listing
under a year old (~150, under a minute). `capturedAt` is stamped on **every** row a feed
returned, so the table's freshness key moves daily even when no issue is open — a quiet feed and
a dead feed must not look alike (§44). A wall-clock stop at 13:45 keeps a slow day from reaching
14:00 (Gotcha 97). When all three feeds return nothing, **nothing is stamped**: the freshness
key goes stale rather than claiming a capture.

### 45.7 Surfaces

- `GET /api/ipo/pipeline` — open, forthcoming and closed-awaiting-listing issues with the
  structure read, sizing, quotas, links and (once final) retail odds. DB-only.
- `GET /api/ipo/recent?months=36` — listings in the window, newest first, with stage, lock-in
  calendar, price versus issue and listing day, and stored analysis. DB-only.
- `GET /api/ipo/issue?symbol=` — one issue, 404 when not tracked. DB-only.
- `POST /api/ipo/capture` — run the capture now. Refused 09:40–10:15 and 14:00–close during
  market hours, **409 with the reason in the body** (B-049).
- `POST /api/ipo/analyse?symbol=` — one listed issue: candles since listing (listing-day
  open/high/close, latest, above-high, base of higher lows at ≥120 candles), first results,
  financial quality and shareholding from NSE, and — past six months — a **compute-to-decide**
  composite via `evaluateSingleStock` (Gotcha 50: nothing is published to `multibagger_scores`
  or the accuracy tracker). Persisted on the row so the page stays DB-only. Same refusal windows.
- Dashboard `ipo.html` (§27.2): the pipeline with a "Refresh from NSE now" button, the category
  guide with the stance box ("apply only if you would buy it at the top of the band on listing
  day and hold five years") and the prospectus checklist, and the recent-listings table with
  per-row Analyse and "+ Watch" buttons. Freshness key `ipoIssues`, on the health screen against
  the 12:15 cron.
- Guide page (§27.11): a "New listings (IPOs)" section and a "Never" line.

### 45.8 Non-goals, stated

- **No apply/skip verdict** (§19). The structure read ends at "read further" or "stop".
- **No grey-market premium**, from any source. It is the price of the listing-day lottery.
- **No SME issues** (§30.1).
- **No prospectus parsing.** The RHP link is on every row; the checklist says what to read.
- **No parent-company inference** for the shareholder quota.
- **No listing-day prediction of any kind.** Subscription figures are shown for what they are
  and read only once final.

### 45.9 First live run (2026-09-09) — what it measured, and three things it corrected before ship

Captured at 12:30 IST on a Wednesday with ten mainboard issues open (the busiest IPO week of the
year so far): **250 mainboard rows** touched — 11 in the pipeline, 241 listed or just closed —
**768 SME rows dropped by design**, 41 detail pages read, 92 prices refreshed, 25 listing-day
candle sets fetched. Wall clock about 90 seconds, all paced.

**The structure read on the open book**, as the page showed it that morning: PRANAV 90% fresh
and QIB 5.8× on day three → favourable; ARCIL entirely an offer for sale → mixed ("not one rupee
goes into the business"); PRASOLCHEM 16% fresh → mixed; RENTOMOJO 12% fresh with the only
employee quota (₹20 discount) → mixed; the day-one issues all reading "subscription is not final
yet" against QIB figures of 0.0–0.55×, which is exactly the refusal §45.3 exists for. MOMSBELIEF,
closed and awaiting listing, carried the first *final* book: QIB 8.2×, retail **110×** → "about
1 in 110" retail odds beside a favourable structure — the honest way to show a popular issue.

**Post-listing, on SHADOWFAX** (listed 2026-01-28, seven months): 154 candles, price ₹248.65
against an issue price of ₹124 and a listing-day high of ₹119.67, higher lows forming →
`BASE_FORMING`; earnings `MODERATE_GROWTH`, accounts `DECENT`, promoters 16.5% with nothing
pledged, compute-to-decide composite 66. Every input measured, and nothing published to the
screener or the accuracy tracker.

Three corrections came out of the run, each now pinned:

1. **MPIMANIPAL's issue-size sentence has no "Rs."** ("fresh issue aggregating up to 3200 million"),
   so its structure read `NOT_MEASURED`. The currency marker is now optional when the unit word
   is present — a number followed by million/lakhs/crores is unambiguous without it.
2. **53 of 96 listings in the 12-month window read `NOT_MEASURED`** because the stage waited for
   an on-demand analysis while the capture already held the two prices it needed (latest price,
   listing-day high). The stage now derives above-listing-high from captured prices when no
   analysis has run; null when either price is missing, so an unpriced listing is still never
   `WASHOUT`. The daily price refresh was widened from 12 to the full 36 months for the same
   reason: the listings past the hype window are the ones whose stage matters. Re-run after the change: 229 prices and 60 listing-day candle sets in one pass, and the
   36-month window read 43 `HYPE_WINDOW` / 34 `RECOVERING` / 35 `WASHOUT` / 1 `BASE_FORMING`
   (SHADOWFAX) / 125 `NOT_MEASURED` — the last group shrinks by 60 a day as listing-day
   candles back-fill.
3. **NSE's past-issues feed never closes out some issues** — it still shows `"-"` for NSDL's
   listing date and price. The listing date now falls back to `EQUITY_L.csv`, accepted only when
   it falls after the issue closed. NSDL itself turned out to be the other case entirely: it is
   **not in NSE's equity list at all**, because it listed on BSE only (NSE is a shareholder). So
   the gap that remains is stated rather than fixed: **a BSE-only listing does not appear here**,
   and this app's price and filing plumbing is NSE-keyed throughout.

A fourth thing was not a correction but a limit worth recording: the past-issues feed lists
~240 mainboard issues in 36 months, while `EQUITY_L.csv` shows ~656 listings in the same window.
The difference is relistings, demergers, partly-paid instruments (ADANIENPP1 appears as `EQ`)
and SME-to-mainboard migrations, which are listings but not IPOs — the tracker follows public
issues, and §30.4's IPO watch (which reads the equity list) remains the broader net.

---

## 46. Portfolio Truth — is the money actually growing? ✅ Active (2026-09-09)

### 46.1 Why this exists

A review of the portfolio page against the live book on 2026-09-09 found that the page could
say a great deal about each stock and nothing reliable about the portfolio. The headline
"total gain" is unrealised P&L over cost, and it cannot move when money is added or withdrawn:
it read **+17.3% on 19 February and +17.3% on 9 September** while invested capital fell by a
tenth — seven flat months, invisible. Three of the page's panels were running on wrong or empty
data (B-096 sector taxonomy, B-097 generated theses, an unpopulated dividend log), and the
concentration gauge read "well spread" on a 29-position book where 14 positions were under 2%.

§46 adds the portfolio-level figures a long-term investor decides on, each with its coverage.
It changes **no score, no weight and no signal** (§19, §20 rule 10).

### 46.2 Time-weighted return against two benchmarks

`GET /api/portfolio/performance?days=365` — DB-only, page-load safe.

- **Time-weighted return (TWR)** chain-links each day's change in market value from
  `holdings_history` with that day's external flow removed, so deposits and withdrawals do not
  move it and it can be set against an index. The flow is the change in cost basis; on a day the
  cost basis fell, the lot-matched realised gain from `tax_lot_sale` is added to the outflow
  where one is on record. Days without one are counted (`flowsUncorrected`) and the response says
  the return reads slightly low on them. Fewer than two snapshots is no return; a span under
  **90 days is not annualised** (a three-week return raised to the power of 17 is noise with a
  percent sign).
- **Benchmarks**: `NSE:NIFTY 50` and `NSE:NIFTY MIDCAP 150` (the book is 41% mid-cap),
  point-to-point over the same dates, from `benchmark_daily_close`. That table is written by the
  **15:00 holdings snapshot** (two paced Kite quotes, riding along with `recordDailySnapshot` —
  no cron of its own, §3.4) and, for history, by `POST /api/portfolio/benchmark/backfill?days=`
  (two daily-candle calls; **refused 14:00–15:30 on a trading day with a 409 carrying its reason**,
  bounded at both ends per Gotcha 97). A true close overwrites a 15:00 price, never the reverse.
- **Drawdown** is measured on the chain-linked index, not on raw value: a withdrawal is not a fall.
- **Total return in rupees**: unrealised + realised STCG/LTCG this financial year (lot-matched only)
  + dividends logged as received. Zero dividends is reported as *unlogged*, not *unpaid*.
- **Cash**: `equity.available.cash` from Kite `/user/margins`, captured at the same 15:00 snapshot
  into `portfolio_cash_snapshot`, shown with its date. The app had been able to call this
  endpoint since day one and never surfaced it.
- **Lot coverage**: how many holdings have an open purchase lot; the tax panel prints it.

Every figure carries a method note and a caveat list. The rule is Gotcha 21's: a missing
benchmark, a short history or an uncovered sale reads "not measured" or is named, never 0.

### 46.3 Size against quality

- **Weight vs quality scatter** (`page-holdings.js`, no new endpoint): share of the book against
  app score, coloured by the §41 verdict, with reference lines at 2% and 65. On the live book the
  three largest positions were 29% of the money in a partial, a not-a-compounder and a
  never-screened stock, while the app's own compounders sat at 5%.
- **Too small to matter**: positions under **2%** of the book are listed beside the HHI gauge with
  their combined weight. HHI cannot see them; a low HHI on such a book is the wrong reassurance.
- **Rupee contribution** chart on the Trends tab: which decisions actually moved the wealth.
- **Portfolio-weighted fundamentals** — `GET /api/portfolio/quality`: value-weighted P/E, ROCE,
  ROE and 2-year profit growth, each with the share of the book it was measured on and the
  holdings count; a metric measured on **under 50% of the money is withheld**. Screening rows are
  resolved across exchange prefixes on the first spelling that carries figures (Gotcha 107).

### 46.4 Cash

See §46.2. Captured, not fetched on load. `PortfolioSnapshotService.captureCash` records
nothing when the payload lacks the equity segment: zero cash is a fact about the account,
absence is a fact about the feed.

### 46.5 Holding period on every row

`HoldingsViewDecorator` attaches `daysHeld`, `firstBuyDate`, `holdingPeriodSource`
(`TAX_LOTS` / `HOLDINGS_PURCHASE_DATE` / `UNKNOWN`), LTCG/STCG quantities and days to the next
LTCG cutoff, from `TaxLotService.holdingPeriod` (ISIN first, then symbol across prefixes, then
the holding's own purchase date). Null days means nothing on file and renders "not measured";
it is **never 0**, which would be a purchase made today.

### 46.6 Young listings on the portfolio page

The same decorator joins each holding to `ipo_issues` by its NSE symbol and attaches the §45.4
stage, the listing date, issue price and the **next lock-in expiry still ahead**. The Action
Items tab lists them with their combined weight. On the live book LGEINDIA (5.6%) was in
`WASHOUT` with the promoter minimum unlocking 2027-04-14 and CPPLUS `RECOVERING` with an unlock
on 2027-02-05; the tracker knew and the portfolio page had never asked. Stage is descriptive;
the supply calendar is the point.

### 46.7 Writes the page may now make (§27.8 carve-out, extended)

Click-only, never on load, each surfacing the server's reason verbatim via `api.js`:
the thesis editor (`PUT /api/portfolio/conviction` — the investor path sets `thesis_stated=true`),
dividend log and mark-received (`POST /api/portfolio/dividends`, `POST /dividends/{id}/received`),
tranche fill and plan cancel (`POST /accumulate/{planId}/tranche/{trancheId}/fill`,
`DELETE /accumulate/{id}`). Rationale as for the watchlist: the page is where the state is
read, so it must be where the state can be corrected. **No write here touches an order, a
score or a weight.**

### 46.8 What was fixed rather than added

- **B-096** — sector taxonomy. Targets `IT/BANKING/METALS`, holdings `Metals/Banking/GENERAL`,
  screener `Energy/Power`: three vocabularies matched case-sensitively, so every target read
  0% and 42% of the book was "Other". `SectorMapping` now resolves all three plus the screener's
  table, placeholders (`GENERAL`, `Other`) are **unclassified and reported by weight and name**,
  never a slice, and the drift panel shows sector *and* market-cap buckets. The sector donut,
  sector-return chart and rebalance note read the resolved sector.
- **B-097** — 45 of 46 theses were app-generated and counted as "intact". `holding_conviction.thesis_stated`
  separates the investor's words from the seed; the report is over active holdings joined across
  exchange prefixes; counts are over stated theses; generated ones are "waiting for your reason".
- The dividend panel says "unlogged, not unpaid" and offers a log form; the "Today" column moved to
  the end of the holdings table, since a one-day move is the least useful number on a 5–10 year
  screen.

### 46.9 Non-goals, stated

No dividend auto-capture (there is no dividend feed in the codebase and the app will not invent
income). No XIRR until lots cover every holding — a money-weighted return on a third of the
flows is a different and wrong number. No per-holding target price or upside (§19). No live
prices or intraday charts on this page.

---

## 47. Good Business, Currently Down — the contrarian lane ✅ Active (lens, 2026-09-09)

### 47.1 The gap, and why every other lane misses it

Every lane on the discovery screen was momentum-positive. The composite rewards proximity to the
52-week high and price structure; §12.10's under-the-radar gate sits on top of that composite;
§30's Stage A filters on momentum, relative strength, price structure and volume — **54% of the
composite's own weight** (Gotcha 37). So the screen could find businesses that were *already
working* and had no way to surface a good business that was **down**, which in the Indian market
is where a large share of multi-year winners have actually been bought.

The measurement makes the point better than the argument. On the run of **2026-09-09**, the names
this lane returns — HINDUNILVR, DABUR, COLPAL, GODREJCP, TATACONSUM, WIPRO, HAVELLS, VOLTAS,
PFIZER, IRCTC — carry composites of **21 to 52**. Not one of them clears a 65 floor. The composite
is 59% price behaviour (§40.2), so a quality business that has fallen scores badly *for having
fallen*, and the screen then hides it for the same reason.

Nothing here is a new measurement. Every figure was already computed, persisted and sent to the
browser; the lane is a different question asked of the same row.

### 47.2 The §20 rule 9 declaration

| §20 rule 9 item | Answer |
|---|---|
| (a) Pillar | **Business quality** and **valuation** — it re-asks §12.5's quality reading and §41's compounding gate of a price the composite has already punished. It measures no new pillar and adds no new signal |
| (b) Horizon | **180 and 365 days and beyond** (§40.2). Neither horizon has a matured row yet, so the lane ships with **no measured track record** and says so on screen |
| (c) Coverage row | Reported inline rather than in `screening_coverage`, because the lane computes nothing of its own: it prints the coverage of each input it leans on. Measured 2026-09-09 — compounding gate judged for 271 of 284, forensic screen run on 87, turnaround verdict on 141, quarterly growth on 0 (the growth columns were added the same day and populate on the next run) |
| (d) Shadow | **Worth zero points.** It never enters the composite, is never persisted, and is computed on read (Gotcha 30, the §12.11 pattern) |

### 47.3 The gate

Three conditions, and the first is the one that matters:

1. **Quality, deliberately not the composite.** `compounding = COMPOUNDER`, or
   `compounding = PARTIAL` **and** `financialQualityVerdict = HIGH_QUALITY`. Gating on the
   composite is precisely what makes the other lanes momentum-positive.
2. **No HIGH forensic flag** — a disqualifier, not a deduction. Every quality figure here is
   computed *from* the audited accounts, so a doubt about the accounts makes a clean reading
   meaningless rather than reassuring (§41.2). MEDIUM cautions; INFO is not a stop (Gotcha 77),
   read through the shared `ForensicSeverity` parser.
3. **Actually down**: `rangePosition52w ≤ 35`.

**That 35 is measured, not assumed** (Gotcha 76, the §12.11 precedent). On the 2026-09-09 run the
284 screened stocks had a median range position of **65.2** and a 25th percentile of **34.6**, so
35 is the bottom quartile of the live cross-section. It is re-derived, not inherited, if the
universe changes shape. On that run the gate names **25 of 284**.

**Distance from the 52-week high is never the test on its own** (B-062): a stock can be 13% off
its high and still 53% above its low — that is a stock that fell and has already bounced. The
range position decides and both figures are shown.

### 47.4 What the lane refuses to do

- **It does not answer "is it a good time to buy".** That question has one rule table on every
  surface (§37.3, Gotcha 85) and this lane renders its verdict unchanged. It expects to read
  *Avoid* or *Hold off* for most rows, because a stock near its low is a stock whose price is
  still weak — and it says so in plain words beneath the table rather than leaving the reader to
  conclude the column is broken. A business worth researching and a price that has finished
  falling rarely arrive on the same day; conflating them is how a cheap stock becomes a cheaper
  one.
- **It does not read an empty red-flag cell as a clean bill of health** (Gotcha 44). The forensic
  screen had run on 87 of 284 stocks on the first live run; the coverage line names that figure.
- **It does not rank.** Rows sort by how far down they are, not by any score, because the lane
  makes no claim about which of them is the better business.
- **It is a research queue, not a shortlist to buy.** A quality business near its low and a
  business in real trouble look identical on price alone. The business columns are there so the
  reader can tell them apart, and the section text says that outright.

## 48. Macro & Geopolitical Event Exposure ✅ Active (lens, 2026-09-12)

### 48.1 The ask, the substitution, and why

The investor asked for a capability that reads the day's news — RBI, the Budget, tariffs, wars,
crude, the rupee, the monsoon, events in India, the neighbourhood, the US and the wider world —
and identifies which stocks in the portfolio, the watchlist and the screening universe are helped
or hurt, now or in the future.

**The literal version of that is what this codebase deleted on 2026-09-03.** Market Direction and
`MarketIntelligenceService` were both news-keyword engines producing a daily directional read, and
both were removed under §39.3 for the same reason: neither was ever a `RecommendationTracker`
source, so after months of running there was no hit rate, no excess return and no information
coefficient for either, and no way to tell whether they had ever been right.

There is also a structural reason sentiment cannot answer this question. **A single event is good
for one business and bad for another at the same moment.** "The rupee fell" is bad news for the
country and good news for an exporter; a rate rise widens a bank's margin and raises an NBFC's
funding cost. A score attached to a headline has nowhere to put that. Only a map of *which
businesses are exposed to which quantity, in which direction, through what channel* can.

So what is built is not a news feed. It is:

- an **exposure map** — a curated table of factor → sector/industry/symbol with a direction, a
  strength, a channel and a one-sentence rationale, readable on screen;
- an **event ledger** — where a reader (a language model, or the app's own keyword rules) extracts
  *what moved and which way*, and is **never permitted to name a company**;
- a **calendar** of dated events ahead, carrying no expected direction and no field in which one
  could be recorded;
- a **pure join** of the three, producing a per-stock reading over quarters, measured at 180 and
  365 days, in shadow mode, worth zero points.

### 48.2 The §20 rule 9 declaration

| §20 rule 9 item | Answer |
|---|---|
| (a) Pillar | **Risk** — "what would make the next few quarters harder for this business". A secondary read on **growth** through the input-cost and demand channels. Never a market-direction call; never a price prediction |
| (b) Horizon | **180 and 365 days**, with 30 and 90 as early reads, via `RecommendationTracker` source `MACRO_EVENT`. A factor whose effect shows up inside a week is not a factor this app should act on |
| (c) Coverage row | `screening_coverage` signal **`MacroExposure`**: measured = the stock has a rule in the map, notMeasured = it does not, **notApplicable never**. No 0-100 value, so `collapsed` stays null (Gotcha 88) |
| (d) Shadow | **`trading.multibagger.macro-exposure-actionable: false`.** Contributes zero points to any score, is never an input to `BuyTimingVerdict`, and has **no companion bonus figure** — choosing the size of an effect before its sign is established is the failure Gotcha 30 exists to prevent. Pinned by `NewSignalShadowModeTest` |

**§20 rule 10**: the vocabulary is `TAILWIND / HEADWIND / MIXED / NOT_EXPOSED / NOT_MEASURED`. It
contains no instruction to transact, and `MacroSurfaceContractTest` fails if one is added.

### 48.3 The exposure map

`src/main/resources/macro-exposure.csv`, **168 rules across all 21 factors**, version `mx1-<hash>`.

Schema `factor,scope,key,onRise,strength,channel,rationale`.

- `scope` ∈ `SYMBOL` / `INDUSTRY` / `SECTOR`, and **that is the precedence order** — the most
  specific rule that mentions a stock wins, one rule per (stock, factor). `MacroExposureMap.Scope`
  relies on its ordinal, which is why the enum order is not cosmetic.
- `onRise` ∈ `HELPED` / `HURT` / `MIXED` — what happens to that kind of business **when the factor
  rises**. Every factor is defined as a quantity with a direction, and `MacroFactor.risesMeans()`
  states it in words (USDINR rising = the rupee *weakening*; MONSOON_DEFICIT rising = rain *below*
  the long-period average; TRADE_BARRIERS_CHINA rising = cheaper Chinese imports, so a safeguard
  duty *imposed* is that factor falling).
- `channel` is the mechanism in three or four words ("input cost", "funding cost", "translation
  gain", "freight rates") and appears verbatim in the reason text on screen.

**Validation is fatal at boot**, naming the offending line: an unknown factor, a `SECTOR` key not
in `SectorMapping.knownBuckets()`, an `INDUSTRY` key not in `UniverseSectors.industries()`, a
malformed symbol, a duplicate (factor, scope, key), a blank channel or rationale. A map that
silently half-loads is a map that silently stops answering for two thirds of the portfolio.

**The map never infers a second-order factor.** A war does not imply crude; a rate rise does not
imply the rupee. The ledger records only what a headline actually reported. Chaining inferences is
how a single story becomes four events and a portfolio appears to be under assault.

### 48.4 The event ledger and the extraction boundary

`macro_events`: factor, direction, magnitude, kind (`SCHEDULED`/`SURPRISE`), geography,
`occurredAt`, headline ids, source urls, summary, extractor, confidence, `dedupKey` (unique),
`dismissed`, `exposureMapVersion`.

**The reader extracts; the map decides.** The system prompt forbids naming a company, a stock, a
price or an action, and lists all 21 factors with their "up means" definitions. This is the same
split `ConcallAnalysisService` already uses — the model reads a document, it never scores a
business. Being strict about it is what makes the map auditable: if the model could name stocks,
the rules on screen would no longer be the rules being applied.

**Dedup is two-layered.** An exact key (factor, direction, day) stops a re-run inserting the same
event twice; a ±`dedup-window-days` scan catches Wednesday's rate cut being written up again on
Friday. A merge keeps the earliest date, the largest magnitude, the highest confidence, and the
union of headline ids and urls — and **re-stamps the dedup key to the merged date**, so a merge
that moved the date earlier cannot leave a second row able to claim the same event tomorrow.

**Future-dated events are dropped on read** (B-031's rule): a scheduled meeting that has not
happened is a calendar entry, not an event.

### 48.5 The keyword fallback, and what it is honestly worth

With no language model configured (`spring.ai.model.chat: none`, the shipped default) the app's
own keyword rules read the headlines. Each rule carries **its own direction** — "rupee falls" is
the currency factor *rising* — so direction is never inferred from sentiment. Confidence is
**always null**, and the screens render that as "not measured" rather than inventing a number: a
reader that cannot score its own certainty is a different thing from one that scored itself badly.

**It makes mistakes, and the first live run proved it.** Of six events extracted on 2026-09-12, one
was an opinion column — *"Why a rate hike could actually be bullish"* — recorded as an actual RBI
rate rise, from which five holdings read a headwind. Two guards followed, both narrow:
`HYPOTHETICAL` gained the **noun** forms ("a rate hike could…", which the existing verb cases could
not reach), and `OPINION_LEDE` rejects a headline that *opens* by asking a question. Anchored at
the start deliberately — an ordinary report containing "why" further along is untouched.

The asymmetry is the justification. Being wrong this way costs **one missed event**, which the next
ingest picks up from a straight report of it. Being wrong the other way **files a reading against
hundreds of stocks on something that never happened**, and a filed reading is never retracted
(§48.7). Pinned by `MacroKeywordExtractorTest`.

### 48.6 The read — a pure join

`MacroExposureRead` takes the stock's map entries and the live events and returns a verdict. In
order:

0. No map entries at all → **`NOT_MEASURED`**. The app has no rule for this business.
1. Drop dismissed, out-of-window and future-dated events.
2. Effect = `onRise` × event direction. A `MIXED` map entry yields a `MIXED` effect.
   **Leverage adjustment**: a *non-lender* carrying debt-to-equity above `1.0` has its strength
   raised to HIGH on rate factors. The `lender` flag is used **only to suppress that adjustment**
   — a bank runs at five to eight times equity by construction — and never to flip a sign, because
   the map encodes the sign more precisely than a balance-sheet heuristic could.
3. Any MIXED effect, or effects in both directions → **`MIXED`**, **never netted**. "No net effect"
   and "pulled hard in both directions" are different situations and must not share a word.
   Mapped, but nothing in the window matched → **`NOT_EXPOSED`**.

**`NOT_MEASURED` and `NOT_EXPOSED` are kept strictly distinct** through the read, the service, the
decorator, the dashboard, the coverage row, the emails and the guide. One is a gap in the app's own
table; the other is a measured all-clear. Collapsing them lets a blind spot render as reassurance,
which is the single most dangerous thing this feature could do.

**Symbol resolution takes the first spelling that can *answer***, not merely the first hit
(Gotcha 107 / B-088), and every reading records which symbol answered.

### 48.7 Measurement — the reason this is not the third deleted news feature

Every **directional** reading (TAILWIND or HEADWIND) is filed as a `RecommendationEntity` with
source `MACRO_EVENT`, score 75 or 25, the verdict stored, and **per-factor sub-scores** signed by
effect and sized by strength (HIGH 3 / MEDIUM 2 / LOW 1). `MIXED`, `NOT_EXPOSED` and
`NOT_MEASURED` are deliberately not filed: there is no directional claim in them to be right or
wrong about, and recording them would dilute the measurement with rows that cannot fail.

The sub-scores are what makes **"which macro factor ever predicted anything"** answerable a year
from now, through the same per-dimension IC panel every other engine uses. A factor that reads near
zero can then be argued out of the map on evidence, which is the only honest route a signal has
here (§38.10).

Three details that carry weight:

- **These rows are measurements, not picks.** A `MACRO_EVENT` row does not say a stock is worth
  owning; it says the app read it as facing a headwind on a given day. Accuracy is scored
  **directionally** — a headwind followed by a fall is a *correct* reading — and every surface that
  shows the investor "the app's picks" filters this source out (`by-symbol` needs
  `includeMacro=true`).
- **One Nifty fetch per ingest, not one per row.** The tracker ordinarily fetches the index level
  per recommendation, which is fine for an engine recording a handful and ruinous for one recording
  three hundred against a broker paced at ~2.9 req/s — a budget whose exhaustion has twice starved
  the afternoon schedulers (B-014, B-049).
- **Dismissing an event does not retract readings already filed.** They were made in good faith on
  the day and the accuracy record has to include them. Dismissal stops an event counting *from now
  on* and keeps it on the ledger, because a ledger that quietly deleted what turned out to be wrong
  could not be judged later (§25.1).

### 48.8 Endpoints

`symbol` is a query parameter throughout — symbols carry a colon.

| Method | Path | Cost |
|---|---|---|
| GET | `/api/macro/events?days=&includeDismissed=` | DB |
| GET | `/api/macro/exposure?symbol=` | DB. **404 = never screened** (the app cannot classify this business at all); **200 carrying `NOT_MEASURED` = screened, no rule**. Different facts, rendered differently |
| GET | `/api/macro/exposure/portfolio` | DB |
| GET | `/api/macro/calendar?days=` | classpath + one holdings read |
| GET | `/api/macro/map?factor=` | classpath |
| GET | `/api/macro/status` | DB |
| POST | `/api/macro/ingest` | live RSS + model or keyword rules |
| POST | `/api/macro/news/scan` | live RSS only |
| POST | `/api/macro/events/dismiss?id=` | DB |

All five GETs are page-load safe (Gotcha 39: a page-load `get()` is ungated).

### 48.9 The ingest control — no cron, and a refusal that explains itself

**Nothing schedules extraction.** `macro.ingest.scheduled` exists, is bound, is logged at boot and
is `false`. An extraction costs a model call, and a standing cost nobody decided to pay is not a
cost worth paying; turning it into a real schedule needs a §15 row and an argument for the slot,
because §3.4 permits nothing outside 09:15–15:30 on a weekday.

The button is the one control on the dashboard that leaves the machine, so it asks first (§27.14,
Gotcha 118) and names what it fetches, what it costs, which reader will run, **and what it will not
do** — it will not name a company and it will not tell you to buy or sell. Measured first live run:
**84 seconds**; the client timeout is 180 s, deliberately above twice that, because a timeout
shorter than the measured run is a bug and not a safety margin.

Refused **09:40–10:15** (the FII/DII fetch and its report share the NSE session) and from
**14:00 to the close** on a trading day (the 14:00 screening, the 14:45 insider capture and the
15:05–15:28 report jobs need the network, and those abort silently at 15:30). Both refusals are
**409 with the reason in the body** — `server.error.include-message` is `never`, so a bare status
would be a guard whose explanation never reaches the person it is guarding (B-049).

### 48.10 Surfaces — one renderer, five screens

`static/js/macro-cells.js` is the only place a reading becomes a badge. The portfolio, the screener,
the watchlist, discovery and the stock page all call `macroExposureCell`, and the column header
string `'Macro'` lives in `macroExposureCol()` alone — a column reading "Macro" on one screen and
"Events" on another invites the reader to ask whether they are the same measurement (Gotcha 85
applied to a heading).

`MacroSurfaceContractTest` pins the five field names and their wrapper types on every surface,
because nothing type-checks the wire: a rename does not fail, it silently draws "not measured" for
ever on a stock the app read perfectly well.

Strength and magnitude get **local** label maps, not `format.js`'s: `LOW` and `MODERATE` already
mean "Well Spread" and "Moderately Concentrated" in the shared table, so a shared lookup would
print a concentration word beside a headwind.

Two email sections (`MacroReportRenderer`, shared with both report previews): the 09:30 briefing
carries recent events plus the next seven days; the 15:18 action items carries the portfolio read.
**The required output is the one that says nothing applies** — when events were recorded and none
touches a holding, the section prints that in as many words. A digest that appears only when it has
something alarming trains the reader to treat its presence as a warning.

Freshness key `macroEvents` is **on-demand**: never marked stale, never a PROBLEM. Amber on that
strip means "a job that should have run has not", and that claim is simply false for a table
nothing schedules (§44).

### 48.11 Non-goals

- **No stock-level sentiment.** The reader never names a company. If it ever does, this becomes the
  feature §39.3 deleted.
- **No market-direction call.** Not bullish, not bearish, not a view on the index.
- **No short-horizon claim.** Nothing here is judged on a result inside 180 days (§19).
- **No inferred second-order factors** (§48.3).
- **No auto-dismissal.** An event is marked noise by a person, on the record.
- **No scheduled AI call** this phase (§48.9).
- **No numeric macro series yet** — USDINR, Brent and G-sec levels are phase 2, each probed for a
  bot-wall first (B-018's rule).
- **Not an input to `BuyTimingVerdict`** until the §38.10 bar is cleared: twelve independent
  periods, which at a 180-day horizon is years, not quarters.

---

## 49. Analyst Target Ledger ✅ Active (lens, 2026-09-12)

### 49.1 The ask, the substitution, and why

The investor asked for analyst target tracking: record every credible price target with the
brokerage, the date, the price then, the target, the rating, the horizon, the **assumptions, the
expected earnings, the valuation multiple used, the catalysts and the risks** — then track what
actually happened to each one, and build a track record per analyst.

**Half of that is a paid feed this app has already declined to buy.** §24.1 settled it: Indian
analyst consensus with estimates and targets is Bloomberg, Refinitiv or Trendlyne Pro at ₹1–10
lakh a year, and §24.4 lists "real paid analyst consensus" and "price-target-vs-current-price gap"
as explicit non-goals. The assumptions, the earnings model and the multiple live inside a PDF sent
to institutional clients. There is no honest way to produce those fields, and inventing them is
the §21 rule 7 failure in its most damaging form — a confident-looking number the investor cannot
tell apart from a real one.

**The other half was already being computed and thrown away.**
`StockNewsService.detectBrokerageActions` has been extracting "*Nomura raises target price to Rs
1,926*" from headlines since §24 shipped: it reads the house, reads the rupee figure, counts the
headline as an upgrade, and **discards the target**. So what is built here is the ledger and, more
importantly, the scoreboard: record the attributed claim, then measure with daily candles whether
the price ever reached it, how long that took, and what the stock did against the Nifty 50 over
the same dates. That last column is the point — a target reached during a rally is beta.

This also delivers the second half of the ask ("evaluate the analysts themselves") in the only
form that is defensible: hit rate, median excess return, time-to-target and revision rate per
house, each with a sample floor below which nothing is reported at all.

**It changes no score anywhere.** An analyst target is a third party's opinion arriving through a
news headline, which is the exact input shape of the two engines deleted on 2026-09-03 — neither
of which ever produced a measured hit rate (§39.3). This ledger is the measurement those two
never had, pointed at somebody else's opinions.

### 49.2 The §20 rule 9 declaration

| §20 rule 9 item | Answer |
|---|---|
| (a) Pillar | **None directly.** It measures *other people's* claims about a business, not the business. Its output is a track record of forecasters, which is evidence about a *source*, not about a pillar. Nearest relation: valuation, as an outside view of what the market is being told a share is worth |
| (b) Horizon | **365 days**, because that is the Indian sell-side convention and what a target actually claims. No 30- or 90-day read is produced at all: a price target is not a short-horizon signal and measuring it as one would be the §19 failure |
| (c) Coverage row | `screening_coverage` signal **`AnalystTarget`**: measured = at least one recorded target for the stock, notMeasured = none, **notApplicable never** (every listed company can be covered, so an absence is our feed's blind spot). No 0-100 value, so `collapsed` stays null (Gotcha 88) |
| (d) Shadow | **`trading.multibagger.analyst-target-actionable: false`**, permanently in this phase, and **no companion bonus figure exists to switch on** — choosing how many points a brokerage upgrade is worth before the measurement has shown it has a sign is Gotcha 30 in miniature. Pinned by `NewSignalShadowModeTest` and `AnalystSurfaceContractTest` |

**§20 rule 10**: the status vocabulary is `PENDING / REACHED / MISSED / SUPERSEDED / UNPRICED`. It
contains no instruction to transact and `AnalystSurfaceContractTest` fails if one is added. The
rating column reports what a house *said* (`BUY / HOLD / SELL / NOT_STATED`) and is never an input
to `BuyTimingVerdict` (§37.3).

### 49.3 What counts as a target

`AnalystTargetParser` (pure) accepts a headline only when it carries **both** a recognised
sell-side house and **a rupee figure with its currency marker**. Either half alone is dropped:

- **No house → dropped**, not filed as "unnamed". A ledger whose purpose is to find out whose
  calls work would otherwise fill with an anonymous bucket standing for nobody, and that bucket
  would rapidly be the largest "analyst" in the record.
- **No figure → dropped.** A rating change with no target is a real event but not a *target*, and
  it is already counted by the §24 brokerage-flow proxy. Recording it here would add rows that can
  never be measured against anything, inflating coverage with unmeasurable claims — the opposite
  of what the coverage row exists to show.

**The currency marker is required, and that is a measured decision.** The first draft made it
optional on the assumption that headlines write "target price 1,200". Run against **162 real
Indian market headlines** collected 2026-09-12, that assumption accepted 14 rows of which **3 were
nonsense**, each of the kind that would dominate every median on the scoreboard:

| Headline | Parsed as | Why it is wrong |
|---|---|---|
| "Bharti Airtel Share Price Target **2026**: Buy for 40% returns" | ₹2,026 | a *year* |
| "…sets **Nifty 50** target at 26,000" | ₹26,000 on Eternal | an *index* target, filed against a company named elsewhere in the same sentence |
| "Nomura raises target price; stock up nearly 200% **in 1 year**" | ₹1 | a duration |

Every genuine target in that sample carried Rs or ₹. Three guards followed, each with its real
case pinned in `AnalystTargetParserTest`: the currency marker, an **index-name lookback** before
the target keyword, and a word boundary so `FY27` is not an amount. After them the same 162
headlines yield **6 clean rows and none wrong** (two being one Ather Energy call reported by two
outlets, merged by the dedup window below).

Two further rules in the same class:

- **A scale word abandons the headline; a percentage does not.** "cuts target price by 20% to Rs
  1,150" has a rejected candidate before the real figure, so the search continues. "revenue target
  of Rs 900 million by 2027" must *not* continue, or it records a ₹2,027 price target from a
  headline about revenue.
- **A rating change is filed under the rating it moved *to*.** Reading the first rating word in
  "upgrades to Buy from Neutral" files every upgrade under the rating it left.

### 49.4 Which company, and the §48 boundary

`HeadlineSubjectResolver` (pure) matches the headline against the company names already in
`universe-sectors.csv` (755 symbols), falling back to a capitalised ticker with a stoplist of
all-caps words that are abbreviations first (`SEBI`, `GST`, `IPO`, …).

**A headline naming two listed companies resolves to neither**, unless one match strictly contains
the other ("HDFC Bank" over "HDFC"), which is one company written two ways. A misattributed target
is not a small error: the ledger's entire output is attribution.

**Why this may name a company when §48's news reader may not.** The macro event extractor is
forbidden from naming a business because that would be *deciding* which companies a general story
affects — a judgement belonging to the exposure map the investor can read on screen. Here the
headline itself names the company, as the stated subject of an attributed price target, and the
resolver only matches that name to a ticker. Reading a name is not inferring a relevance.

**Known gap, stated rather than patched**: headlines routinely use a short form the exchange's
list does not carry — "Samvardhana Motherson" against "Samvardhana Motherson International Ltd.",
"Kirloskar Oil Engines" against "Kirloskar Oil Eng Ltd." Three of nine parsed rows in the live
sample were lost this way. A hand-kept alias table would recover them and would also be a second
name vocabulary to keep in step with the first; until there is evidence about how much is being
missed, the coverage row is the honest answer.

### 49.5 Capture, dedup and measurement

**Two sources, no new network cost.** Both feeds are already fetched for other reasons:

1. the market-wide headline table filled every fifteen minutes (§48.4), where the company must be
   resolved from the text; and
2. **the per-stock brokerage scan the screener already runs on every stock in the universe** for
   the §24 signal — where the symbol is known, so misattribution is structurally impossible, and
   where the rupee figure was previously extracted, counted and discarded.

**Capture never prices a call.** Resolving the issue-date close costs a paced broker call, and
doing that inside a screening loop over three hundred stocks would spend the budget that has twice
starved the afternoon schedulers (B-014, B-049). A new row is written `UNPRICED` and the daily
pass prices it. `UNPRICED` is an honest state — a recorded claim not yet measurable — and it is
excluded from every hit rate until it is priced.

**Dedup is two-layered**, the pattern the macro ledger uses. A unique key (symbol, house, rounded
target, issue date) stops a re-run inserting twice; a ±`dedup-window-days` scan over the same
house and stock catches Wednesday's note reported again on Friday, which the exact key would
happily record as a second call and thereby double that house's apparent output.

**Measurement** (`AnalystTargetOutcomeService`, against `DailyCandleCache` — one cache, because
the rate limit is one budget, Gotcha 97):

- **Reached is measured on the intraday extreme, not the close.** A target is reached the day the
  price first touches it, whether or not it holds. Measuring on closes reports "missed" for every
  call that got there and gave it back, which is a different claim about the analyst.
- **Measurement starts the day after the call.** Counting the call's own day hands a free hit to
  every note published intraday at a level already traded through that morning.
- **A resolved call is measured at its horizon, not at today.** Reading a twelve-month call's
  return eighteen months later measures a holding period nobody claimed.
- **Direction is load-bearing.** A target below the price is reached when the *low* falls to it.
- **Excess return is the neutral column**: stock return minus Nifty 50 return over exactly the
  same dates. A missing index leg leaves it null rather than equal to the raw return.

**A default is not a statement.** A headline that does not state a horizon gets the twelve-month
convention *and* `horizon_stated = false`, so the assumption can never be read back as something
an analyst said. B-057 and B-097 were each this mistake; this is the third time and it is written
up front.

**Rows are never deleted and a resolved row is never rewritten** (§25.1, Gotcha 38). A target
revised before it resolved becomes `SUPERSEDED`, not a miss — but only if it was still open: a
call that already reached its target resolved before it was revised.

### 49.6 The job

| IST | Job | Guard |
|---|---|---|
| 13:20 MON-FRI | `AnalystTargetScheduler.captureAndMeasure` | market |

13:20 is a genuine gap: the 13:00 trio has finished, the 12:15 IPO capture stops itself at 13:45,
and the 14:00 screening — which owns the broker budget for the rest of the afternoon — has not
begun. The capture half is database-only. The measurement half is one paced broker call per stock
with an open target on a cold cache, bounded by `max-measurements-per-run` and rotating
**least-recently-measured first**, so the whole open book is covered over a few days instead of
its head being re-read daily (B-074's lesson). A wall-clock stop at 13:50 keeps a slow day out of
the screening's way — the reason for the guard is contention, so the guard covers the contention
(Gotcha 97, 101).

Freshness key **`analystTargets`** is stamped when the pass last **measured**, not when it last
**recorded**: brokerages do not publish every day, so a capture stamp would go amber on an
ordinary quiet week and train the eye past the colour on the keys where it means something.

### 49.7 What this will and will not cover

**Coverage will be thin, and the screens say so rather than implying otherwise.** Measured on the
first live run: the stored market-wide feed held **36 headlines over seven days and none carried
an attributed rupee target**; a 162-headline sample deliberately searched for target stories
yielded 6 clean rows. The reason is visible in the raw text — most such headlines read "*check
target price*" or "*sees 46% upside*", with the rupee figure in the article body.

Four filters stand between published research and a ledger row, each with a direction:

1. the call reached an English-language Indian news headline (skews to large caps and bold calls);
2. the headline named a house in `Brokerages` (a boutique this list has never heard of reads the
   same as no attribution at all);
3. the headline quoted a rupee figure with its currency (§49.3);
4. the resolver could identify the company (§49.4).

So a house's record here describes its **quotable, published** calls and not its research, and
`AnalystTrackRecord.caveat()` says exactly that in the payload — rendered above every one of these
tables, not tucked into a tooltip.

**A percentage-only claim records nothing.** "Jefferies sees 46% upside" could be turned into a
level by multiplying a close, and that level would be a number nobody published. It is a coverage
gap, not something to derive.

### 49.8 The read, and the screens

- `GET /api/analyst/track-record` — per-house hit rate, median excess return, median days to
  reach, median claimed upside, revision rate, plus the coverage block and the caveat. DB-only.
- `GET /api/analyst/targets?symbol=` — every target on a stock plus what the open ones say.
  DB-only, resolved across exchange prefixes (Gotcha 84) with the answering symbol reported.
- `GET /api/analyst/recent?days=` — everything recently recorded. DB-only.
- `POST /api/analyst/capture` — mine the stored headlines now (DB-only, but it writes, so POST).
- `POST /api/analyst/measure` — measure now; **refused 14:00 onwards on a trading day with a 409
  carrying its reason** (B-049).

Both GETs are read on page load by **Track Record** (`accuracy.html`) — the analysts' record sits
directly beneath the app's own, because it is the same question asked of someone else on the same
yardstick — and by the **stock page**, which shows every target on that company.

**The open targets are never called a consensus.** A consensus is an average over a covered
universe of analysts; this is however many quotable calls happened to reach a headline, which on
most stocks is one or none. The number of firms is printed beside the median so a "median target"
from a single note cannot be mistaken for the market's view.

### 49.9 Refusals — the part that makes it a record

`AnalystTrackRecord` (pure) exists mostly to decline:

- **A pending call is never met or missed.** Marking one missed before its date manufactures a bad
  record; counting pending calls in the denominator penalises a house for publishing often.
- **A revised call is excluded from the hit rate and published as a revision rate.** It is not a
  miss — the house withdrew it — and it is not free either, because a house that revises the week
  before a deadline would otherwise escape every miss it ever made.
- **Below `min-resolved-for-track-record` (5) there is no hit rate at all**, and the status reads
  `TOO_EARLY`. Two-from-two is a hundred per cent, which is exactly the number that misleads. Same
  refusal as the concall credibility gate (Gotcha 47), one observation stricter because this
  sample carries the §49.7 selection on top.
- **Excess return is signed the way the call was made** at the point of aggregation only. The row
  stores what the stock did against the index — one fact with one meaning — but a house is right
  when a stock it said to sell falls, and aggregating raw would score its correct sells as
  failures and cancel them against its correct buys.
- **An unpriced call is neither a hit nor a miss**, and is counted in its own column.

### 49.10 Non-goals

- **No score contribution**, no `BuyTimingVerdict` input, no new verdict vocabulary (§20 rule 10).
- **No derived target from a percentage claim** (§49.7).
- **No consensus figure**, however many targets a stock accumulates (§49.8).
- **No short-horizon read.** A price target is a one-year claim and is measured as one (§19).
- **No research text is warehoused.** §49.11's feed links to each house's own note; the ledger
  stores the link and the published figures, never the document.

### 49.11 The structured research feed (2026-09-12)

**What changed.** §49.1 declined point 8's assumptions, expected earnings and valuation multiples
on the grounds that they live in a note sent to institutional clients and §24.1 had already
declined to buy that feed. The premise was right and the conclusion was wrong: **Moneycontrol
publishes those notes**, and it publishes a structured index of them. A sampled note (Motilal
Oswal on Indraprastha Gas, 10 Sep 2026) carries FY26/FY27E/FY28E sales, EBITDA, PAT and EPS, the
sentence *"we value IGL at 13x Dec'27E SA P/E and add INR44/sh as the value of JVs to arrive at
our TP of INR195/sh"*, the margin assumption behind it, and the named risks. Those are point 8's
four missing fields, in public.

**The index is now the primary capture path.** `api.moneycontrol.com/mcapi/v1/broker-research`
returns, per recommendation: the house, the target, the rating, the note's own date, the
publication date, the price the note quoted, the publisher's internal stock id, the same house's
**previous** target, and a link to the note. Everything §49.3 and §49.4 exist to *infer* is
published here as a field.

**Why it supersedes the headline parser.** Measured on 6,275 historical headlines the parser
produced 611 rows of which **4 were wrong**, and each was a distinct defect the structured feed
cannot express: B-111 (a corrupt thousands separator truncating ₹13,800 to ₹13), B-109 (a company
name resolving to the larger company whose name it contains), B-110 (a commodity contract
resolving to the exchange that lists it). It also removes §49.7's four selection filters: a
comparable 800-row sample of the feed spans **416 companies** where 6,275 headlines spanned 291.
The parser is retained as the **fallback** for a row whose stock id does not resolve.

**Two dates, and the later one wins.** `calledOn` is the date on the note; `issuedOn` is the date
it reached the feed. Measurement runs from the day after `issuedOn`, because crediting a house
with what the price did while its note was private is a look-ahead that flatters the analyst -
the same bias `available_from` exists to prevent (§32.6). The gap is typically one day.

**Stock ids are resolved once, ever.** The feed identifies a company by its own id (`IG04`) and
ships a display label truncated to ~15 characters ("AU Small Financ"), which matches nothing -
6% against the app's own symbol table. The publisher's price feed maps the id to an NSE
tradingsymbol (`IG04` -> `IGL`), and `broker_symbol_map` keeps the answer permanently, **including
a failure**: an id with no NSE listing is stored with a null symbol so it is never re-probed, and
so "never asked" stays distinguishable from "asked, nothing there" (Gotcha 102).

**One house, one name.** The feed lists "Anand Rathi", "AnandRathi" and "Anand Rathi Financial
Services" separately. Unmerged, one firm's record splits across three scoreboard rows, each below
the five-call floor, so a house with a real record reports `TOO_EARLY` for ever. Names are
canonicalised through the existing `Brokerages` vocabulary; an unknown house keeps its published
name rather than being invented into a canonical form.

**Dedup is shared with the headline path, deliberately.** Both write through the same
`record(...)`, so the composite key collapses one note arriving down both paths onto one row. A
second write path with its own key would double a house's apparent output.

**Courtesy is the licence.** This is a third party's public endpoint read for one investor's own
portfolio. Requests are paced process-wide (`feed-pace-ms`, default 400 ms) for the reason the NSE
and Kite gates are (Gotcha 23, 101), the client identifies itself honestly rather than
impersonating a browser, and only published figures and the link are stored - never the note.
`robots.txt` on the site permits the page this API serves; the judgement to use it is recorded
here rather than buried in a client.

**What it still does not deliver.** Point 8's four fields are *reachable* now, not *implemented*:
extracting them means parsing each house's own PDF layout, which is separate work and separately
specified. Until then the ledger records the claim and measures it, exactly as §49.1 says.

**What the first full load actually showed (2026-09-12).** 82 pages, 0 failed, back to 2023-12-29:
**6,972 targets over 719 stocks and 33 brokerages**, of which **4,799 (69%) had been revised** by a
later call from the same house, 1,265 resolved, 872 still running, 36 unpriceable. Overall hit rate
**64.2%**, median excess over the Nifty **+1.6%**.

**The headline finding is about revision, not accuracy.** Across the 16 houses with 15 or more
resolved calls, the correlation between *the share of a house's calls that ever reach their
deadline* and *its hit rate* is **-0.57**. The two best hit rates on file belong to the two houses
that resolve the fewest calls:

| house | calls that ever resolve | hit rate | median vs Nifty |
|---|---|---|---|
| Motilal Oswal | 4.3% | 95.7% | +8.0% |
| Prabhudas Lilladher | 5.1% | 93.8% | +5.3% |
| ICICI Securities | 19.4% | 70.3% | +3.8% |
| Anand Rathi | 45.2% | 40.4% | -4.7% |
| Mirae Asset | 100% | 59.1% | +4.9% |

A revised call is excluded from the hit rate because the house withdrew it, which is right. But it
means a frequently-revising desk's hit rate describes **how often it updates its view**, not how
often it is right — exactly what §49.9 published the revision rate to make visible. This is the
first time that refusal has earned its keep on real data. n=16, so read -0.57 as suggestive.

**Three things the live load broke, all found by looking rather than by a status code.** (1) The
archive walk read a failed page as the end of the archive and silently stopped at 40% — fixed by
giving a page request a result type that distinguishes failure from exhaustion, and by splitting a
failing page rather than abandoning the walk. (2) One firm held two scoreboard rows ("AnandRathi"
beside "Anand Rathi") because a substring match cannot see a missing space. (3) With the ledger
populated, the accuracy page's DOM went from 44 KB to **3 MB**: folding a section hides a list, it
does not avoid building it. `/recent` is capped at 200 and returns `total`, and the heading names
the cap — a count beside a list must describe that list (B-098).

**The caveat had to be rewritten, and that is a rule not an incident.** The shipped wording said the
sample was "calls that reached a headline, named a brokerage this app knows, quoted a rupee target".
None of those filters applies to the primary path. A caveat that describes a different system from
the one running is worse than none — it invites the reader to discount data that is broader than
claimed while hiding the biases that do apply. It now names the real ones: the desks that publish
into this feed, the concentration in a few houses, the four-fifths Buy mix, and the revision effect
above. `caveatBlock` also stopped rendering a hard-coded key list, which would have dropped both new
lines in silence.

**And the sell-side's own bias is on the record.** The sampled 800 rows are **78% BUY, 16% HOLD,
4.6% SELL**. A buy-heavy book has a high hit rate by construction in a rising market, which is
precisely why the yardstick stays excess return over the Nifty across the same dates (§49.5) and
why §49.9's caveat travels with every table.

- **No score contribution**, no `BuyTimingVerdict` input, no new verdict vocabulary (§20 rule 10).
- **No derived target from a percentage claim** (§49.7).
- **No alias table for company names** until the coverage row says how much is being missed.
- **No consensus figure**, however many targets a stock accumulates (§49.8).
- **No short-horizon read.** A price target is a one-year claim and is measured as one (§19).

### 49.12 Where our screening and the analysts overlap (2026-09-14)

**The question.** Are the brokerages quoting targets on the stocks this app itself rates highly,
and where do the two readings point different ways? Asked by the investor after the ledger filled
up, and it is the first question that joins §49 to §12 rather than reading it on its own.

**"Good" is the stored verdict, never a score threshold.** `STRONG_MULTIBAGGER` or
`POTENTIAL_MULTIBAGGER`. The obvious alternative, `min-score-for-candidate`, is only **half** of
`isCandidate()` - the percentile gate is the other half - and the first cut used it, labelling
**145** stocks good where the screener's own verdict says **122**. Two screens, two meanings of one
word, which is Gotcha 85 in the plainest possible form. One question, one definition.

**The measured result (screening run of 2026-09-12).** 278 stocks screened, 216 carrying a live
target. 122 good, **88 of them quoted** (72%), 34 quoted by nobody, and **7 trading above every
target on them**. Correlation between a house's claimed upside and this app's composite:
**-0.322 over 216 stocks.**

**That negative number is mostly mechanical, and saying so is the feature.** About 54% of the
composite's weight is price behaviour - momentum, relative strength, price structure - which
rewards a stock trading near its highs. A large claimed upside is, by construction, a stock
trading far below where the house thinks it belongs. The two are close to measuring opposite
things. **The consequence is the important part: the composite cannot be used to check whether a
target is plausible.** The measurements that can are the fundamental ones - what growth the target
would require against what the business has delivered (§12.5's reverse DCF), whether the accounts
carry a flag (§32.4), whether it has compounded before (§43). A reader handed -0.32 without that
explanation concludes one side is broken, so the explanation is carried in the payload rather than
left to the page.

**A retrospective correlation was attempted and refused.** The natural next question - do this
app's scores predict *which* targets get hit - is not answerable on this data: of 1,265 resolved
calls only **127** have a screening score from before the call was made. That is not a sample, and
publishing a figure from it would be the exact failure §38.9 exists to prevent. Recorded here so
it is not attempted again without new data.

**Agreement is counted in firms, at a threshold set on the cross-section.** Of the 88 good quoted
stocks, **39 are quoted by exactly one house** and the median is two, so a headline "44% upside" is
more often than not one analyst's opinion published once. Three or more independent desks is a
different quality of evidence and narrows 88 rows to about 20, which is why
`MIN_HOUSES_FOR_AGREEMENT` is 3 rather than an assumed level (the §12.11 discipline). It counts
**firms, not notes** - one house staggering three revisions is one opinion, the same rule B-041
applied to a director staggering a purchase across three days.

**The screen ships an action block, and that is a rule learned here.** The first version shipped
the measurements and the explanations and nothing else, and the investor's response was the correct
one: *"not getting the action item"*. Context is not a next step, and a screen that offers only
context is read once. `AnalystOverlap.actions()` names, per list, what a reader can do with it -
and every count in those sentences is passed in from the list it describes rather than recomputed,
so the sentence and the table under it cannot drift apart (B-098). The vocabulary is bounded the
same way every other verdict is (§20 rule 10): an action points at a list to read, never at a
transaction, and `AnalystOverlapTest` fails if the words "buy", "sell" or "exit" appear in any of
them.

**Three refusals hold the screen together.**

- **No verdict on a target.** Nothing here says a target is right, achievable or worth acting on -
  that would be short-term price prediction (§19) and a sixth surface answering "is this a good
  buy" (Gotcha 85).
- **"No target on file" is not "uncovered".** It means no house published one into the feed this
  app reads. A good business nobody quotes is what §12.10's under-the-radar lens exists to find,
  so it is reported as a reading queue rather than as a gap.
- **Trading above every open target is a fact, not a sell signal.** It is the same observation
  whether the houses were too cautious or the market has run ahead, and this app has no way to
  tell those apart. It is a reason to look harder before adding, which is the §12.11 question.

**Where it is.** `GET /api/analyst/overlap`, DB-only and page-load safe; rendered at the foot of
`accuracy.html` under the houses' own record, because it is the same subject and the reader wants
the join before the rows. **Contributes zero points to any score.**

### 49.13 What would have to be true (2026-09-14)

**The investor's actual question, finally answered.** *"I don't want to blindly trust the targets
given by analysts. I want to verify using our engines whether targets are genuine and possible."*
§49.12 answered the adjacent question — who is quoting what — and established that **the composite
cannot be the check**: it correlates **-0.322** with claimed upside because 54% of its weight is
price behaviour. This is the check.

**The method: run §12.5's reverse DCF backwards.** The existing model takes the market price as
given and solves for the growth rate that justifies it. Take the *target* price as given instead
and the same solve yields **what the business would have to deliver for that target to be the fair
value**. That is a statement about earnings, not about price, so §19's bar on short-term price
prediction is respected: nothing here carries a probability, a date, or a view on whether the price
will get there.

**The arithmetic is exact and the cash flow cancels.** `fairValue(fcf, g, r)` is linear in the cash
flow, so `fairValue = fcf × F(g)`. Today gives `fcf × F(g0) = marketCap`, the target gives
`fcf × F(g1) = marketCap × target/price`, and dividing leaves **`F(g1) = F(g0) × target/price`**.
The FCF proxy drops out entirely, so the requirement is recoverable from the stored implied growth
and the price ratio alone — no new fetch, and no risk of re-deriving a figure that disagrees with
the stored one. A round-trip test (target = price must return the stored implied growth to solver
precision) pins that this is the same model rather than a second one that happens to agree, and it
fails if anyone changes the discount rate, terminal growth or forecast window in one place only.

**B-113 — the first build returned one verdict for every stock, and that is the lesson.** The
requirement was compared against `dcf_historical_growth_percent`, which is a **two-year** profit
CAGR. On the first live run it read BRIGADE 37%, SONACOMS 47%, TITAN 63%, DIVISLAB 66%,
GALAXYSURF **109%** — base effects, not records — and against those a 10-26% requirement is always
"below its record". All six stocks returned `BELOW_ITS_RECORD`. **Zero variance is the signature
this codebase has been caught by three times** (Institutional Interest constant at 40 for three
months, monthly RSI constant at 50 across the universe, Insider Pulse with a verdict for no stock
at all), so it is treated as a defect on sight rather than as a finding about analysts being
conservative. Comparing a ten-year requirement against a two-year CAGR is a horizon mismatch of the
same family as B-047 filing a quarter as a year.

**The record now comes from `annual_fundamentals`, and thin history is refused rather than used.**
Profit CAGR across the longest run of annual accounts on file, minimum **four years** — the line
this app already draws for a claim about the past (B-066's bonus discriminator, §32.3's turnaround
detector) — with `yearsOfRecord` carried everywhere the rate is, because a growth rate over four
years and one over ten are different claims wearing the same units (§43's discipline). Measured
depth supports it: median 6 years, 226 of 366 symbols at four or more. Two further refusals: a
**starting year that was loss-making yields no CAGR at all** (a recovery from a loss is genuinely
undefined, and the arithmetic would otherwise produce either a negative rate or a meaningless huge
one for what is good news), and a missing year shortens the window rather than being interpolated.

**After the fix it discriminates.** Over 45 good quoted stocks: `NO_RECORD_TO_COMPARE` 16,
`BELOW_ITS_RECORD` 11, `IN_LINE_WITH_RECORD` 11, `ABOVE_ITS_RECORD` 5, `FAR_ABOVE_ITS_RECORD` 2.
GALAXYSURF's absurd 109% became **6.8% over 8 years**; TITAN, with one year on file, now says so
rather than inventing a comparison.

**Bands are §12.5's, vocabulary is not.** The ±5 and +12 percentage-point boundaries are the
expectation-gap boundaries, because "how much growth does this price assume versus what has been
delivered" is one question and asking it at a target price does not make it a different one
(Gotcha 85). The words differ because the subject does — §12.5 grades a price you could pay, this
grades a claim somebody else published — and the two appear on the same screen, so they must not be
confusable (Gotcha 105).

**Growth is one input, and the panel says so.** The other things that would have to be true — the
balance-sheet quality verdict, any forensic flag, the composite — are shown **raw beside** the
requirement, never blended into a second score (Gotcha 113c). A demanding target on a company with
clean accounts and a long record is a different proposition from the same target on one without,
and that judgement stays with the investor.

**Caveats travel with every reading**, and the first is load-bearing: §12.5 records that this model
systematically **under-values long-duration compounders** — software, platforms, branded pharma —
because ten years plus 4% terminal growth cannot represent a franchise that compounds beyond the
window. For exactly those businesses a `FAR_ABOVE_ITS_RECORD` reading is the model's known bias
showing, and a reader not told that will mistake it for a finding about the analyst. It also does
not fit lenders or commodity cyclicals, which read as not measured rather than being given a number
that would not mean anything.

**Where it is.** `GET /api/analyst/targets?symbol=` carries `plausibility` — one reading per open
target plus a headline at the **median** of them (not the mean: on most stocks there are one or two
calls and an outlier would carry it). Rendered on `stock.html` between the target summary and the
ledger, because a reader who has just seen a median target and an implied upside is at the moment
of deciding whether to believe it. DB-only, page-load safe. **Contributes zero points to any score.**
