# Feature inventory — what exists, what it claims, and where its evidence lives

Scope a sweep from this file. Verify the *current* state against `SPEC.md` before quoting anything
here as fact — this is a map, not a source of truth, and the app changes weekly.

**Review priority is a function of consequence, not of size:**

- **Tier 1 — changes a number the investor acts on.** The seven weighted dimensions, the five
  bonuses, the HIGH_RISK cap, the candidate gate, and the forensic screen (the one risk control that
  ships armed). A defect here moves rankings across the whole universe.
- **Tier 2 — changes a verdict the investor reads.** Buy-timing, holdings signals, core tiers, decay,
  the lenses that print a badge. Zero points, but the words are acted on.
- **Tier 3 — records, ledgers and instruments.** Accuracy, coverage, data health, the learning
  substrate, portfolio truth. A defect here hides other defects, which is why it is not tier 4.

---

## Tier 1 — contributes points to the composite

| Feature | SPEC | Pillar | Weight / effect | Known caveat to check against |
|---|---|---|---|---|
| Technical momentum | §12.5 | (price) | **0.20** | Price behaviour is 59% of the composite; §40.2 calls that ratio into question and pre-registers variants to test it. **Do not hand-edit** (Gotcha 27) |
| Volume accumulation | §12.5 | (price) | **0.13** | |
| Relative strength | §12.5 | (price) | **0.13** | Fell back to *absolute* return when the Nifty series was missing — in a rising market that passes nearly everything (B-049) |
| Price structure | §12.5 | (price) | **0.13** | Distance from the 52-week high is half a position; needs `rangePosition52w` too (B-062) |
| Valuation | §12.5 | Valuation | **0.14** | 50/50 PE-vs-sector and reverse-DCF gap. Systematically under-values long-duration compounders. The −0.083 IC describes the **retired** NSE-PE implementation, not the rebuilt XBRL one |
| Institutional interest | §12.5 | Management | **0.11** | Read a constant 40 for three months (bug #9). Check its coverage row first, always |
| Financial quality | §12.5 | Business quality | **0.16** | Missing data scores neutral 50 — check `debtDataAvailable` / `cashFlowDataAvailable` |
| Earnings-growth bonus | §12.4 | Growth | +8 / −5 | Integrated-filing history starts ~Mar-2025; 8-quarter CAGR fills in ~2027 |
| Insider-activity bonus | §12.6 | Management | ±5 | Distinct from Insider Pulse (§28), which is shadowed. Do not conflate |
| Analyst-signal bonus | §24 | — | ±5 | A *proxy* for consensus, not consensus. Keyword-matched news, ~30% miss rate |
| Wealth-signal bonus | §12.7 | Growth | −8..+10 | Gross margin null for banks by design |
| Capital-efficiency bonus | §12.8 | Cash generation | −10..+12 | ROCE and D/E deliberately **not computed** for lenders; ROE/ROA instead. Insurers return `NO_DATA` (IRDAI format, a data wall) |
| **HIGH_RISK cap at 54** | §12.5 | Risk | hard cap | Must run **last**, after every bonus (B-020) |
| **Forensic screen** | §32.4 | Risk | `forensic-actionable: **true**` | The one signal that ships armed — a risk control, Gotcha 42's asymmetry. Severity must be *read*, not tested for presence: HIGH disqualifies, MEDIUM cautions, INFO is not a stop (B-065, B-092) |
| Candidate gate | §12.5 | — | percentile **and** absolute | `isCandidate()` needs top 20% **and** `min-score-for-candidate: 60`. A screen using only the threshold disagrees with the screener (Gotcha 126(m)) |

---

## Tier 2 — shadow signals and lenses (zero points, but the words are read)

| Feature | SPEC | Shadow flag | What to check |
|---|---|---|---|
| Insider Pulse | §28 | `insider-pulse-actionable: false` | Coverage. It measured **no stock at all** for months (B-074), then the feed itself had moved (B-089). 74% of the raw feed is noise; the mode filter is the whole feature |
| Capex cycle | §31 | `capex-actionable: false` | A shrinking large build looks identical to a growing one on intensity alone. Prior year must come from `annual_fundamentals`, never the filing (B-034) |
| Turnaround detector | §32.3 | `turnaround-actionable: false` | signals + notMet + notMeasured must total 4, so "2 of 4" is auditable |
| Macro exposure | §48 | `macro-exposure-actionable: false` | `NOT_MEASURED` (no rule in the map) and `NOT_EXPOSED` (checked, nothing applies) must render differently (Gotcha 121). The reader may **never name a company** (Gotcha 122) |
| Analyst target ledger | §49 | `analyst-target-actionable: false` | Refusals are the feature: pending ≠ miss, revised = `SUPERSEDED`, no hit rate below 5 resolved calls. 69% of targets are revised before resolving, and resolve-rate correlates **−0.57** with hit rate |
| Under-discovery lens | §12.10 | never enters composite | Needs the institutional **level** check, not just the direction (B-063) |
| Compounding lens (single-year) | §41 | lens | Latest year only; `yearsOfAccounts` must travel with every verdict. A lender has two applicable gates, so its badge rests on thinner evidence — and the text must say so |
| Capital-allocation record | §42 | lens | Bonus/split divided out at 0.05% tolerance; where equity cannot vouch that new money arrived, dilution is MEDIUM not HIGH (B-092) |
| Compounding persistence | §43 | lens | **Count years, never average them.** `REQUIRED_SHARE = 0.7` is the one judgement threshold, flagged for recalibration. Needs ≥4 measured years |
| Core holdings | §35 | `suppress-technical-exits: false` | `PASS_NO_DATA` passes the decision but never counts toward the quorum. Review date **2026-11-30** |
| Buy timing | §37.3, §6.5, §12.11 | frozen | **One question, one rule table, four surfaces.** A fifth defers. This is where a second vocabulary gets reintroduced |
| Thesis drift / decay | §6.2 | — | Must classify on `relativeDelta30d`, not the raw delta (B-064) |
| IPO pipeline | §45 | lens | Never says "apply". Mid-issue subscription decides nothing. `HYPE_WINDOW` is a stage, not a filter |
| Contrarian lane | §47 | lens | Gates on the **business**, not the composite — the composite is why a fallen good business is invisible |
| Universe expansion | §30 | `dynamic-expansion.enabled: false` | The funnel is **circular** — Stage A filters on 54% of the composite's own weight, so promotion rate is not validation (Gotcha 37) |

---

## Tier 3 — instruments and records

| Feature | SPEC | What it is for | What to check |
|---|---|---|---|
| Recommendation accuracy | §23 | the only engine scoreboard | 180d/365d have **no matured rows**. Horizon is stored, not inferred (B-028) |
| Per-signal coverage | §38.2 | was it measured, does it vary | `collapsed` only applies on the 0–100 scale; a ratio gets spread with no verdict |
| Scoring version | §38.1 | provenance | Deliberately over-sensitive. Pooling two engines under one name is unrecoverable |
| Shadow composites | §38.8 | six pre-registered weightings | Reconstruction, not re-screening; `reconstruction_exact=false` excluded from **all** variants alike |
| Walk-forward harness | §38.9 | out-of-sample evaluation | Read `independentPeriods` — 3 at 30d, 1 at 90d on the first live run |
| Promotion gate | §38.10 | the only route to a weight change | `AUTOMATIC_ADOPTION_ENABLED` is permanently false. Reads `passedExceptStability` |
| Data health | §44 | the app checking itself | Reports passes too; one session behind is never a PROBLEM (no holiday calendar in this JVM) |
| Portfolio truth | §46 | is the money growing | TWR removes flows; drawdown on the chain-linked index; no annualising under 90 days |
| Fundamentals backfill | §32.6 | the binding constraint on everything long-horizon | `UNAVAILABLE` is a finding, not a failure. Completion = archive depth, not "enough years" |

---

## Portfolio modules (SPEC §5–§11)

Goals/allocation (§5), conviction & thesis (§6), diversification HHI (§7), accumulation planner
(§8), tax lots (§9), rebalancing (§10), dividends (§11). All MVP, all active. The live ones to watch:

- **§8** — `SIGNAL_GATED` is **refused on create** (422). A tranche fired by a signal is a buy signal
  wearing a plan's clothes (B-077).
- **§9** — tax-aware exits gate on LTCG eligibility; horizon resolves by ISIN first, then symbol.
- **§6** — a generated thesis is not a thesis; `thesis_stated` separates the investor's record from
  the app agreeing with itself (B-097). Same for `horizon_stated` (B-057).

---

## Surfaces

- **Emails** (§14) — morning briefing 09:30, FII/DII 10:00, exit alerts 10/12/14, holdings 15:18
  (two emails), multibagger Saturday 09:00, accuracy Friday 15:25.
- **Dashboard** (§27) — eleven pages, read-only, no build step, no CDN, same-origin by construction.
  **Never add CORS** (§20 rule 8).

Both surfaces are bound equally by §21. A §21 violation on a dashboard screen is a bug on exactly the
same footing as one in an email.

---

## Where the gaps already are (SPEC §40.3) — do not "discover" these

1. ~~Universe-wide annual backfill~~ — shipped as §32.6, still converging.
2. ~~Moat lens~~ — shipped as §41 / §43.
3. ~~Capital-allocation record~~ — shipped as §42. **Goodwill additions are not covered.**
4. 🔨 Recalibrate §43's `REQUIRED_SHARE` against the measured cross-section once §32.6 converges.
5. 🔨 Point-in-time back-test of §42 and §43 using `available_from` — the only honest route into §38.10.
6. 🔨 Per-signal coverage rows for the new gates.

A suggestion that restates one of these adds nothing. A suggestion that *unblocks* one — with a
method — is the most valuable thing this review can produce.
