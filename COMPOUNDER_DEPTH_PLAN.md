# Compounder Depth — implementation plan

**Status**: ✅ **implemented 2026-09-06** — see §13 for what shipped and what deliberately did not.
**Date**: 2026-09-06.
**Covers**: SPEC §40.3 roadmap items 1–3 (universe-wide annual backfill → capital-allocation
record → multi-year compounding lens).
**Precedent for this document**: [CORE_HOLDINGS_RECYCLING_PLAN.md](CORE_HOLDINGS_RECYCLING_PLAN.md).

---

## 0. The recommendation in one paragraph

Build **one** thing next: a paced, resumable, universe-wide annual-fundamentals backfill, preceded
by a schema lock. It is unglamorous data plumbing and it is the only item on the roadmap that
unblocks the other two. Everything else worth building — capital allocation, moat persistence,
deeper forensics — is computed from `annual_fundamentals`, and that table today holds **multi-year
history for roughly 19 of 288 screened stocks**. No new lens can be honest on that.

The expensive part is a **one-time ~4,000 NSE requests**. The dangerous part is that half of those
requests will be wasted if the schema is not locked first. That is what §4.1 exists for.

---

## 1. What we are actually short of

The seven pillars in SPEC §1 are all *measured* today. Two are measured **once**, on the latest
year, which for a 5–10 year compounding claim is close to not measuring them.

| Pillar | Today | Short of |
|---|---|---|
| Business quality | Financial Quality 16% of composite; forensics | Forensics need 3–4 years — unavailable for most of the universe |
| Growth | Earnings, wealth signals, capex, turnarounds | Turnaround detector needs 4 years; integrated filings start ~Mar-2025 |
| Cash generation | ROCE/ROE/ROA/D-E/cash conversion (§12.8) | Latest year only |
| Management quality | Insider Pulse (shadow), concall ledger | **No capital-allocation record at all** |
| Competitive advantage | Compounding lens (§41) | **Latest year only — persistence unmeasured for ~93%** |
| Valuation | Reverse-DCF + PE-vs-sector | Known bias against long-duration compounders |
| Risk | Forensics, core gates, diversification, drift | Forensic coverage is depth-bound |

Every gap in that right-hand column resolves to the same sentence: **`annual_fundamentals` is
shallow.** That is the whole justification for the ordering below.

### 1.1 What depth unlocks, by year count

Measured from the code, not assumed:

| Depth | Unlocks |
|---|---|
| 3 years | `ForensicScreenService` receivables check (`:416`), cash-conversion check (`:447`) |
| 4 years | Turnaround detector in full (`MIN_YEARS_FOR_ANALYSIS`); the B-066 bonus-vs-dilution discriminator (`ForensicScreenService:386`) |
| 5+ years | Margin stability, earnings steadiness through a cycle, share-count discipline |
| 8–10 years | ROCE persistence, incremental ROCE, capital-allocation record |

Note the fourth row of that table twice over: at fewer than 4 years the discriminator at
`ForensicScreenService:386` returns `false`, meaning *"not a bonus issue"*, which **fires
`DILUTION:HIGH`**. Backfilling therefore does not only add true flags — it **removes false ones**.

---

## 2. Where I disagree with the obvious plan

This section is the point of the document. Each item below is a place where the straightforward
reading of SPEC §40.3, or of my own earlier feature list, is wrong or incomplete.

### 2.1 The Saturday window cannot hold this. Use a weekday job.

SPEC §40.3 item 1 says the backfill should be *"invoked from inside `weeklyFullScreening()` — not a
new Saturday scheduler (Gotcha 28/35)"*. That instinct is right about Gotcha 28 and wrong about
capacity.

The Saturday app window is 07:50–10:35, and `weeklyFullScreening()` already carries a full
screening run, the coverage vector, shadow composites, the retirement pass and the Stage A coarse
scan — Stage A alone is a measured ~11–22 minutes. The 09:00 report follows. A universe backfill is
**~4,000 NSE requests**; paced, that is over an hour. It does not fit, and squeezing it in would
push the weekly report past the window where its guard aborts silently — the B-014 failure.

**A weekday job is not the Saturday exception.** Gotcha 28 protects
`isSaturdayScreeningWindow()` from acquiring a third caller. A job at **11:30 MON-FRI** with the
ordinary `isMarketOpen()` guard is squarely inside SPEC §3.4's window and touches the carve-out not
at all. It also runs **five times a week instead of one**, which is the difference between
converging in three weeks and converging in four months.

11:30 is chosen because it is a genuine gap in the §15 schedule between the 11:00 pair and the
12:00 exit alerts, and because the NSE-heavy jobs (09:45 FII/DII fetch, 10:00 report, 10:00 quant
discovery, 14:45 insider capture, 14:00 screening) are all clear of it. The backfill uses **no Kite
calls at all**, so it does not compete for the ~2.9 req/s process-wide budget (Gotcha 23).

*Cost accepted*: a 27th scheduled method. That is what §3.4 permits; the rule it enforces is about
firing outside market hours, which this does not do.

### 2.2 It must be a resumable queue with per-symbol status, not a long-running endpoint

A "run it across the universe" call that takes an hour and dies at minute 40 leaves no way to know
what was done. Worse, without status you cannot distinguish **"never attempted"** from
**"attempted, and this company genuinely only has three years in the archive"**. A batch picker
that sorts by fewest-years-held will then loop on the shallowest symbols forever and never reach
the untouched ones. That is not a hypothetical; it is the natural failure of the obvious
implementation.

So: a small status table, and a batch that converges. It also makes *"how deep is the universe?"* a
query rather than a guess, which is what makes the rollout observable (§9).

### 2.3 Lock the schema before spending the 4,000 requests, or pay them twice

This is the highest-leverage decision in the plan and it is easy to miss.

Every year the backfill writes is stamped `source=XBRL`, and the CSV import path **refuses to
overwrite an XBRL row** (Gotcha 49). Re-running to capture a column added later means re-fetching
every filing. The XBRL cache is 7 days *and process-local*, and this app restarts daily, so there
is no cache to lean on across a re-run.

Five columns that Phases 2 and 3 need are **not on the entity today** and must land first (§4.1).
The most important is a point-in-time availability date — see the next item.

### 2.4 Without a filing date, the multi-year lens can never be back-tested — and its evidence clock starts at zero

Gotcha 91(b): the shadow weight variants back-fill to April 2026 because a variant's composite is
*reconstructed* (`storedComposite − liveWeightedBase`) from columns already on historical rows. A
**new dimension** has no such column, so it cannot be reconstructed, and its evidence clock starts
on ship day. Against the §38.10 promotion gate — twelve independent periods — that is years.

A fundamentals-based lens is the exception. It **is** reconstructible point-in-time, because annual
accounts are dated: for any past screening date you can compute what the lens would have said using
only filings that were public by then. That inherits history back to 2016 instead of starting from
nothing.

It works **only if each row records when the filing became public.** `annual_fundamentals` has
`fiscalYear`, `createdAt` and `updatedAt` — none of which is the publication date. Using
`fiscalYear` alone leaks the future by up to five months, which is exactly the look-ahead bias that
makes a backtest lie in the optimistic direction.

**Therefore**: add `available_from` in Phase 1. Capture the real date from the archive listing if it
carries one (Phase 0 verifies this); otherwise store a conservative proxy of fiscal-year-end plus
five months — SEBI LODR Reg 33 requires audited annual results within 60 days — and set
`available_from_estimated = true` so no analysis can mistake the assumption for a fact. That is the
same null-discipline the codebase already applies everywhere else (Gotcha 21, 33, 68).

### 2.5 The pre-2022 balance-sheet gap may invert the roadmap ordering — measure before designing

SPEC §32.5 records a "Known limit": balance-sheet facts (equity, receivables, net block) resolve for
recent years but **often not for pre-2022 filings**, whose instant context has the same declaration
gap the `FourD` fallback was written for. P&L and share count come through.

ROCE = (PBT + finance costs) / (equity + total borrowings). If equity does not resolve pre-2022,
then **ROCE persistence — the headline gate of the multi-year moat lens — is unobtainable for
precisely the years that would prove durability.** Shipping that lens blind would reproduce §41's
own limitation with more work behind it.

The capital-allocation record (roadmap item 3), by contrast, runs mostly on share count, dividends,
profit and interest cost — the fields that **do** resolve. So it is plausible that item 3 should
ship before item 2.

I am not asserting that. I am saying **the ordering of Phases 2 and 3 is a decision that Phase 0
must make with a measurement**, not one to commit to now. That is Probe A.

### 2.6 A persistence gate must be "N of M years", never an average

A commodity company's ten-year ROCE oscillates roughly 5% → 35% → 5%. An average clears an 18% bar;
*"held ≥ 18% in 7 of 10 years"* does not. Averaging lets one boom year carry a decade, which is the
exact failure a persistence test exists to catch. Every Phase 3 gate is a count of qualifying years
over measured years, with the year count carried alongside.

And the K/M bar itself is set from the **measured cross-section after backfill**, the §12.11 / §41.3
discipline — not guessed at 7-of-10 today.

### 2.7 Depth before breadth — do not widen the universe first

An earlier draft of this advice put universe breadth high. That is wrong right now. Gotcha 37
records that the expansion funnel is **circular**: Stage A filters on momentum, relative strength,
price structure and volume, which are 54% of the composite by weight, so Stage B then "discovers" a
high composite it pre-selected. The first live run promoted 9 of 10, eight at 90+.

Promoting more names before fundamental depth exists means adding stocks that can only be judged on
price behaviour — into a platform whose stated problem (SPEC §40.2) is that 59% of its weight is
already price behaviour. Depth first. `dynamic-expansion.enabled` stays `false` throughout.

### 2.8 Run the holdings backfill on day one; it already exists

`POST /api/fundamentals/backfill-holdings` is built and unused — the log window back to 2026-09-01
contains zero `Annual archive` lines, so it has not been run. Roughly 33 holdings at ~11 requests
each is ~360 requests. That is the portfolio, where a wrong read costs real money, and it is one
afternoon of work with an endpoint that already exists.

Do that before writing any new code, so Phase 0's probes have real data to measure and the core
holdings get their durability depth immediately. **Add pacing first** (§7.1) — it is currently
unpaced despite its own javadoc claiming otherwise.

---

## 3. Phase 0 — measure before building

Four probes. Roughly a day. Every one exists to stop a later phase being designed on an assumption.

**Probe A — per-field resolution rate by year.** Backfill ~20 symbols spanning large, mid and small
caps plus one bank, then report, per fiscal year, the percentage of rows where each of `equity`,
`borrowings`, `netBlock`, `receivables`, `operatingCashFlow`, `shareCount`, `sales` and `netProfit`
is non-null. *Decides*: whether Phase 3's ROCE-persistence gate is buildable, and therefore whether
Phase 2 or Phase 3 goes first (§2.5).

**Probe B — does the archive listing carry a publication date?** Log one raw
`corporates-financial-results?period=Annual` payload and inspect its fields for a broadcast or
dissemination timestamp. *Decides*: whether `available_from` is real or estimated (§2.4).

**Probe C — measured pacing floor.** Time 200 XBRL downloads at 500 ms, 1 s and 1.5 s spacing and
record failures and 4xx/5xx responses. The repo has **no measured NSE throughput number** — the
only comparable is 3,200 *paced Kite* calls in 22 minutes (~0.41 s/request), and NSE archive XBRL
files are far heavier and NSE is the more bot-sensitive host. *Decides*: the batch size in §4.3.

**Probe D — taxonomy break year.** Ind-AS adoption in India was ~FY2017. SPEC §32.5 says the
archive reaches 2011 "each carrying its Ind-AS XBRL link", which cannot be literally true for a
2011 filing. Parse the oldest three filings for two symbols and see what actually comes back.
*Decides*: the real `maxYears` ceiling, so we do not pay 3 wasted requests per symbol across 370.

**Exit criterion**: a one-page findings note appended to this document. No Phase 1 code before it
exists.

---

## 4. Phase 1 — schema lock, then the paced resumable backfill

### 4.1 Schema lock (do this first, in its own commit)

Five columns on `annual_fundamentals`, each with a matching `SchemaMigrationRunner.ENSURE_COLUMNS`
entry — Gotcha 74: `ddl-auto=update` does not reliably add a declared column, and the failure mode
is a feature that writes nothing while looking like it ran.

| Column | Type | Why it must be here and not later |
|---|---|---|
| `available_from` | `date` | Point-in-time reconstruction (§2.4). Without it Phase 3 cannot be back-tested at all |
| `available_from_estimated` | `boolean` | An assumed date must never be readable as a filed one |
| `dividends_paid` | `double precision` | Retention = 1 − payout. `BalanceSheetData` already carries it and `recordYear` drops it. Two payoffs beyond Phase 2: it is the single input §41.4 names as the first thing that would improve the compounding lens, **and** `isBonusOrSplit`'s own javadoc (`ForensicScreenService:378-381`) records that it errs toward silence precisely because *"this system does not carry dividends per year on the history row"* — dividends suppress equity growth and the discriminator cannot tell that apart from a raise |
| `profit_before_tax` | `double precision` | Used today only to reconstruct `operatingProfit`, then discarded. Storing it makes ROCE direct and the effective tax rate derivable |
| `face_value` | `double precision` | A face-value change **is** a split. `shareCount` is already derived from paid-up capital ÷ face value, so this is free at parse time and makes B-066 split detection exact rather than ratio-inferred (Gotcha 86's 0.05% tolerance) |

`recordYear` writes all five through `setIfPresent`, preserving the B-046 non-null merge. `source`
and `consolidated` keep their unconditional set.

**Do not add `consolidated` to the unique key.** One row per (symbol, fiscalYear) with the basis
recorded is correct for a single series, and the §32.5 majority-basis election depends on it.

### 4.2 Backfill status table

`fundamentals_backfill_status`, one row per symbol:

| Column | Purpose |
|---|---|
| `symbol` (unique) | |
| `status` | `PENDING` / `COMPLETE` / `PARTIAL` / `FAILED` / `UNAVAILABLE` |
| `last_attempt_at`, `attempts` | Retry policy and the §2.2 loop guard |
| `years_in_archive`, `years_written`, `years_skipped_basis` | `PARTIAL` vs `COMPLETE` is `years_written` against `years_in_archive`, so a company with only 3 filed years settles at `COMPLETE` rather than being retried forever |
| `basis` | consolidated or standalone, echoing the elected series basis |
| `last_error` | Named, not swallowed |

`UNAVAILABLE` means the listing returned nothing — a delisted predecessor, a non-March fiscal year
end (currently dropped outright in `archiveFilingsByYear`), or an insurer behind the same data wall
that already returns `NO_DATA` for capital efficiency. It is a **finding**, not a failure, and the
CSV import remains its fallback.

### 4.3 The scheduled batch

```java
@Scheduled(cron = "0 30 11 * * MON-FRI", zone = "Asia/Kolkata")
public void backfillBatch() {
    if (!marketHoursService.isMarketOpen()) return;   // SPEC §3.4
    // ... paced batch
}
```

- Picks the next `batch-size` symbols from `resolveUniverse()` whose status is `PENDING`, or
  `FAILED` with `attempts < max-attempts` and `last_attempt_at` older than 7 days.
- Paced at the interval Probe C measures, with the pacing helper **shared with the two existing
  endpoints** — one budget, one implementation, which is Gotcha 97's rule applied to NSE.
- Refuses to start inside 09:40–10:15 as the endpoints do, and additionally **stops** at a
  wall-clock deadline rather than only checking its start time (§7.2).
- Config under `trading.fundamentals.backfill.*`: `enabled` (default **true** — this is data
  capture, not a scoring signal, so Gotcha 30's shadow rule does not apply), `batch-size`,
  `pace-ms`, `max-years`, `max-attempts`.

### 4.4 Cost and convergence

| Item | Figure |
|---|---|
| Symbols in `resolveUniverse()` today | 370 configured (288 realised in screening) |
| NSE requests per symbol, cold | ~11 (1 uncached listing + up to 10 XBRL) |
| One-time total | **~4,000 requests** |
| At 1.2 s pacing | ~80 minutes of wall clock, spread across batches |
| Batch of 30 symbols | ~330 requests, ~6.5 min per run |
| Runs to converge | **~13 weekdays, about three weeks** |
| Steady state after that | ~zero — `recordFromXbrl` maintains the newest year on every screening run (Gotcha 49) |

The pacing figure is a placeholder until Probe C replaces it. Everything else is derived from the
call graph rather than estimated.

### 4.5 Surfaces

- `GET /api/fundamentals/coverage` — DB-only, dashboard-safe: symbols by status, the year-depth
  histogram, and how many are ≥3, ≥4 and ≥8 years deep. This is the rollout's instrument (§9).
- `POST /api/fundamentals/backfill-universe?limit=` — manual batch trigger, same guards.

---

## 5. Phase 2 — capital-allocation record (management quality)

The pillar SPEC §40.1 marks as having **no measurement**. This is the Jhunjhunwala half of the
brief: judge promoters by what they did with the cash, not by what they said on the call.

Computed over the deepest available window, each component independently nullable:

| Component | From | Note |
|---|---|---|
| Share-count discipline | `share_count`, `face_value` | Dilution CAGR **ex** bonus and split, reusing the B-066 discriminator. A buyback is a distinct finding, not a negative dilution |
| Payout and retention | `dividends_paid`, `net_profit` | Cumulative over the window, not a single year |
| Reinvestment intensity | `net_block`, `capital_work_in_progress`, `depreciation` | Cumulative capex proxy against cumulative depreciation. Honours B-048: an unknown CWIP delta is null, never 0 |
| Debt trajectory | `borrowings`, `equity` | Level and direction, not direction alone (§32.3 criterion 1's rule) |
| Incremental return on capital | Δ operating profit ÷ Δ capital employed | **The Buffett metric.** Balance-sheet dependent, so gated on Probe A |

**§20 rule 9 declaration**: (a) pillar — management quality; (b) horizon — 365 days and beyond,
never accepted on a 30/90 panel; (c) coverage — one `screening_coverage` row per component;
(d) shadow — **yes, zero points**, per Gotcha 30 and §20 rule 9.

**Refusals it must make**, in the house style: a window shorter than 5 years yields `NOT_MEASURED`,
never a weak grade; a company that has never paid a dividend is `NOT_APPLICABLE` for payout and
leaves the denominator (Gotcha 68); a share-count step above 50× refuses to measure rather than
reporting a corporate event that never happened (Gotcha 65).

---

## 6. Phase 3 — the multi-year compounding lens (persistence)

Extends §41 from one year to a track record. It replaces nothing: §41's single-year form stays as
the current read, and this answers *"has it held?"*.

Gates are **counts of qualifying years over measured years**, per §2.6, tiered by what the data
actually supports:

**Tier A — P&L and share count** (resolves broadly, per §32.5):

| Gate | Passes when |
|---|---|
| Margin stability | Operating margin within a measured band in K of M years |
| Earnings steadiness | Profit positive and non-collapsing across the window — a real cycle, not §12.7's one-year consistency score |
| Compounding record | Revenue and profit CAGR over the window, with profit not lagging revenue |
| Share-count discipline | No dilution beyond the measured bar, excluding corporate actions |

**Tier B — balance sheet** (gated on Probe A):

| Gate | Passes when |
|---|---|
| Return persistence | ROCE ≥ 18% in K of M years (ROA for a lender) |
| Leverage discipline | Debt to equity ≤ 0.5 in K of M years |

`yearsOfAccounts` is carried through, as §41 already does, so every surface can say *"four years,
not yet a track record"*. `M < 5` makes the whole lens `NOT_MEASURED`.

**§20 rule 9 declaration**: (a) pillar — competitive advantage, with business quality secondary;
(b) horizon — 365 days and beyond; (c) coverage — a row per gate; (d) shadow — **yes, zero points**.

Computed **on read**, like §41 and §12.11 — a stored copy can disagree with the row it describes
after a re-screen.

**The back-test that `available_from` buys**: once shipped, the lens can be evaluated over every
screening date since April 2026 using only filings public at each date. That is the difference
between entering the §38.10 gate with three years of runway and entering it with none.

---

## 7. Defects found while planning

Each needs a `B-NNN` in BUGS.md. None is a blocker; all are in code this plan touches.

### 7.1 `/backfill-holdings` claims to be paced and is not
`FundamentalsController:177-179` javadoc says *"it is paced"*. There is no pacing anywhere in the
path — the only `Thread.sleep` in `src/main/java` is in `DailyCandleCache:117`, for Kite. Both the
per-year loop (`NseDataService:471-485`) and the per-symbol loop (`FundamentalsController:190-203`)
are tight. Blast radius: a 33-symbol run is ~360 unthrottled requests at NSE, the host that has
already bot-walled `/api/quote-equity` (B-018). Fix before running §2.8.

### 7.2 The NSE-crunch guard checks the start time only
`requireOutsideNseCrunch()` returns early if *now* is outside 09:40–10:15. A backfill started at
09:35 runs straight through the 09:45 FII/DII fetch and the 10:00 report — the interference the
guard was written to prevent. It needs a deadline checked inside the loop, not only at entry. Same
shape as the bounded guard Gotcha 97 describes on the review endpoint.

### 7.3 `@Column(nullable = false, length = 16)` binds to the wrong field
In `AnnualFundamentalsEntity:96-111` the annotation intended for `source` is separated from it by
the `consolidated` javadoc and field, so it binds to `consolidated` — a `Boolean` declared
`nullable=false, length=16` — and `source` gets defaults. Cosmetic under `ddl-auto=update`, but the
declared intent and the schema disagree, and Gotcha 74 is precisely about not trusting that
mechanism.

### 7.4 CSV re-import blanks omitted fields
`copyFigures` (`FundamentalsHistoryService:494-509`) is a wholesale copy **including nulls**, unlike
the XBRL path's `setIfPresent`. Re-importing a narrower CSV over an `IMPORT` row erases figures the
first import supplied. This is B-046 in the one path that did not get the fix.

### 7.5 Non-March fiscal year ends are dropped silently
`archiveFilingsByYear` skips any filing whose `toDate` does not start `31-MAR`, with no counter. A
company that changed its year end vanishes from the archive with no record of why. It should land
as `UNAVAILABLE` with a reason (§4.2), not as an absence.

---

## 8. What we deliberately do not build

- **No new buy/sell vocabulary.** SPEC §20 rule 10. Nothing here answers "should I buy now"; the
  one shared rule table (Gotcha 85) stays the only answer to that question.
- **No promotion into the composite.** Both new lenses ship at zero points (Gotcha 30, §20 rule 9).
  Promotion only ever through §38.10.
- **No hand-editing of weights** to correct the 59% price tilt, however wrong it looks under this
  charter. That is Gotcha 27 and SPEC §40.2, and it is the failure that ended the previous ML chain.
  The `quality-tilt` and `fundamentals-only` variants are pre-registered to argue it.
- **No new shadow weight variants.** Every addition permanently raises the significance bar for all
  six via `PromotionGate`'s deflation by `WeightVariantRegistry.count()` (Gotcha 91a).
- **No universe expansion** until depth lands (§2.7). `dynamic-expansion.enabled` stays `false`.
- **No revival of the ML question.** §38 is the answer and it correctly refuses.

---

## 9. Expected consequences, and how they are observed

**Composites will move during the rollout, and that is correct.** `forensic-actionable` defaults
`true` and a HIGH flag forces the composite cap at 54. As depth arrives, forensic checks that
currently report *"nothing was checked"* (Gotcha 44) start reporting. Two effects run in opposite
directions:

- New **true** flags appear on companies whose history could not previously be read, so some
  composites get capped.
- **False** `DILUTION:HIGH` flags disappear as the B-066 discriminator reaches its 4-year minimum.

Do **not** disarm `forensic-actionable` during the rollout. Suppressing it would be removing a risk
control, which is the wrong direction of Gotcha 42's asymmetry.

**Do not bump `scoring_version`.** The engine is unchanged; only the depth of its inputs grew — and
that grows on every screening run anyway as new quarters arrive. Bumping would falsely claim an
engine change and split the shadow-reconstruction sample further, when the first walk-forward run
already flagged two pooled scoring versions (SPEC §38.9).

**Instrument it instead.** `screening_coverage` (§38.2) is the right tool and already exists. Record
the rollout window in SPEC §32.5 so any IC or coverage panel spanning it is read with that caveat,
and log a daily flag count by code so the distribution shift is attributable rather than mysterious.

---

## 10. Acceptance criteria

**Phase 0** — a findings note appended here answering all four probes, with the Phase 2/3 ordering
decided and the pacing figure measured.

**Phase 1** — all five columns present in `information_schema.columns` (Gotcha 74, verified rather
than assumed); the status table converging with no symbol retried more than `max-attempts`; the
coverage endpoint showing the year-depth histogram climbing week over week; `≥4 years` coverage
above 80% of `resolveUniverse()` at convergence, or a named reason per shortfall. Tests: pacing is
applied on every path; a `COMPLETE` symbol with 3 archive years is never re-queued; an estimated
`available_from` is never readable as a filed one.

**Phase 2** — a component with a window under 5 years reads `NOT_MEASURED`, never a weak grade; a
never-paying company is `NOT_APPLICABLE` for payout and leaves the denominator; contributes zero
points, pinned by a test in the `NewSignalShadowModeTest` family.

**Phase 3** — every gate is a count over measured years, never an average; K/M bars cite the
measured cross-section; the point-in-time back-test runs over screening dates since April 2026 and
its report states its independent-period count, not its row count (Gotcha 92).

---

## 11. Documentation changes required

Per SPEC §20, in the same commits:

- **SPEC §32.2** — the five new columns and what each is for.
- **SPEC §32.5** — the universe backfill: status table, batch schedule, the rollout window and its
  effect on forensic coverage (§9).
- **SPEC §15** — one new row, `11:30 MON-FRI | Annual-fundamentals backfill batch |
  FundamentalsBackfillScheduler.backfillBatch | market`, and the count 26 → 27.
- **SPEC §40.3** — item 1 marked in progress; the §2.1 deviation from "inside
  `weeklyFullScreening()`" recorded with its reason; the Phase 2/3 ordering updated once Probe A
  reports.
- **SPEC §41.4** — once `dividends_paid` is persisted, the note that payout is "computed and
  discarded" is no longer true.
- **New SPEC sections** for Phases 2 and 3, each carrying its four §20-rule-9 declarations.
- **CLAUDE.md** — endpoints, the new scheduler, package file counts, and a Gotcha for §2.4 (a
  fundamentals lens is back-testable only if the row records when the filing became public).
- **BUGS.md** — the five entries in §7.

---

## 12. The honest statement of payoff

This plan does not make next quarter's picks better. Nothing here changes a score, and the §38.10
gate means none of it can for a long time, by design.

What it buys is that the research stops guessing. Today the platform can say a business earned 25%
on capital *last year*; afterwards it can say whether it has done so for eight. It can say a
promoter's stake rose; afterwards it can say what that promoter did with a decade of retained
earnings. Those are the two questions that separate a compounder from a good year, and they are the
two the app currently cannot answer.

The edge being built here is not prediction. It is refusal to fool oneself — which is the only edge
in this business that compounds.

---

## 13. What shipped, 2026-09-06

All three phases, plus the five defects. Full suite green at **499 tests**, up from 470.

### Shipped

| Plan item | Landed as |
|---|---|
| §4.1 schema lock | Five columns on `annual_fundamentals` + `SchemaMigrationRunner` entries (SPEC §32.2) |
| §4.2 status table | `fundamentals_backfill_status` + repository |
| §4.3 scheduled batch | `FundamentalsBackfillScheduler` at 11:30 MON-FRI, `FundamentalsBackfillService`, `FundamentalsBackfillConfig` (SPEC §32.6, §15) |
| §4.5 surfaces | `GET /api/fundamentals/coverage`, `POST /api/fundamentals/backfill-universe` |
| §5 capital allocation | `CapitalAllocationRecord` — pure, five components, zero points (SPEC §42) |
| §6 multi-year lens | `CompoundingPersistence` — pure, six gates in two tiers, zero points (SPEC §43) |
| Both, joined to the DB | `LongHorizonRecordService`, `GET /api/fundamentals/long-horizon` |
| §7.1–§7.5 defects | All five fixed; see BUGS.md |

### Changed from the plan

**Phase 0's probes became instruments rather than a separate step.** Probe B answered itself
statically: `archiveFilingsByYear` already reads a `filingDate` key from the listing, so the
plumbing existed and the estimate path covers the case where NSE does not populate it. Probes A
and D are now answerable from `GET /api/fundamentals/coverage` and the history endpoint's new
per-field output once the first batches run, which is better than a throwaway script. Probe C —
the measured pacing floor — has **not** been run; `pace-ms: 1200` is cautious and SPEC §32.6 flags
it as unmeasured.

**Phase 3 did not wait on Phase 0's ordering decision (§2.5).** Rather than sequencing behind a
measurement, the lens is **tiered**: Tier A runs on the profit-and-loss lines and share count that
SPEC §32.5 says resolve, Tier B on the balance sheet where it is there. A missing balance sheet
leaves those gates `NOT_MEASURED` rather than blocking the feature — which is what the tiering
exists for, and it is pinned by a test.

### Deliberately not built

- **Goodwill additions** in the capital-allocation record. The archive filings do not reliably tag
  it and inventing a proxy would be the opposite of §42's contract.
- **Recalibrating §43's `REQUIRED_SHARE`** against the measured cross-section. That distribution
  does not exist until the backfill converges; 0.7 is judgement and SPEC §43.4 says so out loud.
  This is the one number in either lens quoting a level rather than a percentile.
- **The point-in-time back-test.** `available_from` was added specifically to make it possible, but
  running it needs the history first. It is now roadmap item 5 in SPEC §40.3, and it is the only
  honest route from either lens into the §38.10 promotion gate.
- **Widening `QuantitativeDiscoveryService`'s candidate set** to the true 370-symbol union. It
  reads the ~89-name legacy list; changing that would quadruple a daily Kite-heavy scan, which is a
  cost decision rather than a bug fix. Flagged in BUGS.md, not changed.

### Verified live, 2026-09-06

Run against the real NSE archive on `NSE:BEL` after deploying, not just against tests.

**Probe B answered, and the answer is the good one.** NSE's archive **does** carry a real broadcast
date, and it parses: FY2018 filed 2018-06-23, FY2024 filed 2024-05-20, `availableFromEstimated`
**false** on all seven backfilled years. So the point-in-time back-test of §42 and §43 can be built
on filed dates rather than on the +5-month assumption. The real lag is 2–3 months after year end,
comfortably inside the conservative estimate, which stays as the fallback.

**Probe A answered, and it confirms the risk §2.5 flagged.** BEL has 8 years of accounts and
`equity` in only the last two. That is SPEC §32.5's pre-2022 balance-sheet gap, measured. It is
exactly why Phase 3 shipped tiered rather than sequenced behind a measurement — Tier A gave four
real gates on a company whose balance sheet mostly does not resolve.

**One calibration defect found by looking at output rather than at tests.** Return on capital was
passing 3/3 and helping earn a `PROVEN_COMPOUNDER` badge on three readings — thinner than the word
"persistence" claims, and Gotcha 68's failure in its most flattering form. `MIN_TIER_B_YEARS` is
now 4, matching `MIN_YEARS_FOR_ANALYSIS`. BEL now reads `PROVEN_COMPOUNDER` on **4 of 4 measured
Tier A gates** with both Tier B gates honestly `NOT_MEASURED`. Pinned by a test.

**The other columns landed as intended.** `faceValue` 1.0 (BEL's actual face value),
`profitBeforeTax` on all years, `dividendsPaid` from FY2022 where the filings tag it and null
before that. Capital allocation now reads `DISCIPLINED` on a measured 39.8% payout, a flat share
count with **one bonus issue correctly divided out** — B-066's exact case, working on live data —
and zero debt.

Cost measured: **12.7 seconds for one 10-year symbol**, paced, which puts a 30-symbol batch at
roughly 6 minutes as estimated.

### The number that matters now

Nothing above changes a score. The thing to watch is `GET /api/fundamentals/coverage`: the
universe measured **20 of 366 symbols at 4+ years** on the day this shipped (5.5% forensic-ready,
median depth 1 year, all 366 pending), and the batch should carry
`atLeast4Years` — the depth at which the turnaround detector and the bonus-versus-dilution
discriminator start working — from near zero toward the whole universe over about thirteen weekday
runs.
