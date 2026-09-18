# Bug Tracker

The single source of truth for **bugs found, in-progress, and resolved** in this codebase. Investment decisions are driven by this app's analysis — every bug here may be silently distorting a recommendation, so document the *why* and *blast radius*, not just the fix.

> Older one-off bug fixes (pre-2026-05) are listed in [CLAUDE.md → "Critical Bug Fixes Applied"](CLAUDE.md). New entries go here.

## How to add an entry

1. **Found a bug?** Add to "Open Bugs" with a new ID (`B-NNN`, monotonically increasing). Write the symptom, root cause if known, and blast radius — *which analysis output gets distorted*. Severity follows the rubric below.
2. **Fixing it?** Move to "In Progress", note the assignee/branch.
3. **Fixed?** Move to "Resolved" with the fix summary, files changed, verification command/output, and any caveats. Keep the entry forever — future-you needs the context.
4. **Decided not to fix?** Move to "Won't Fix" with the reason. Don't delete entries.

## Severity rubric

| Severity | Meaning | Examples |
|---|---|---|
| **P0** | Silently corrupts an investment-decision input. Wrong data flowing into multibagger / holdings / DCF / accuracy. | NSE field rename returning all-null; DB cleanup throwing; lookup returning wrong stock |
| **P1** | Degrades a feature noticeably but doesn't poison decisions. User can see the gap. | ML retrain failing weekly; one report section blank; sector trainer not updating |
| **P2** | Cosmetic, intermittent, or affects a non-decision path. Logs noise; rate-limit retries. | 429 retries; duplicate log lines; FII/DII deals returning 0 on a known-empty day |

## Status legend

`OPEN` `IN_PROGRESS` `BLOCKED` `RESOLVED` `WONT_FIX`

---

## Data-loss notice - 2026-09-06

**This ledger was accidentally truncated to zero bytes on 2026-09-06** by a tooling mistake while
adding B-085: a file was opened for writing (which truncates immediately) and the write then threw
on an encoding error, so the open succeeded and the content never arrived. The file was restored
from the last commit (`e6db0fc`). Everything written to BUGS.md **after that commit and before the
truncation is gone** - it was never committed and no editor backup or dangling git blob survived.

**Entries referenced elsewhere in the repo but not present below.** Their substance survives in the
CLAUDE.md gotchas and SPEC sections that cite them; the ledger entry itself does not. Where enough
detail survives to write an accurate entry it has been **reconstructed** and marked as such - a
reconstruction is a summary from cross-references, not the original text, and it will be missing
verification output and file-line links.

| ID | Status | Where its substance survives |
|---|---|---|
| B-074 | Reconstructed below | CLAUDE.md Gotcha 94 |
| B-076 | Reconstructed below | CLAUDE.md (SPEC 13/14/15/16 restoration; TokenManagementService cron) |
| B-077 | Reconstructed below | CLAUDE.md Gotcha 96, SPEC 8.2 |
| B-079 | Reconstructed below | CLAUDE.md Gotcha 98 |
| B-081 | Reconstructed below | SPEC 32.6, CLAUDE.md Gotcha 101 |
| B-082 | Reconstructed below | CLAUDE.md Gotcha 101 |
| B-083 | Reconstructed below | `AnnualFundamentalsEntity` (the fix is in the code) |
| B-084 | Reconstructed below | `FundamentalsHistoryService.copyFigures` (the fix is in the code) |
| B-013, B-023, B-052, B-056, B-068, B-069, B-070 | **Not recovered** | Cited in CLAUDE.md gotchas; may predate this loss |
| B-075, B-078, B-080, C-002 | **Not recovered** | No surviving reference; existence not confirmed |

**The lesson, which belongs in a ledger of mistakes as much as any other bug**: build the whole
byte string first and only then open the target file. `open(path, 'w')` truncates before a single
byte is written, so any failure between the open and the write destroys the file - and an encoding
error is exactly the kind of failure that happens on the write call. Writing to a temporary file
and renaming it over the original is the version of this that cannot lose data.

---

## Open Bugs

| ID | Severity | Title | Discovered | Notes |
|---|---|---|---|---|
| B-100 | P0 | A live-looking OpenAI API key was committed as a config default | 2026-09-12 | `application.yml` carried `ai.api-key: ${AI_API_KEY:sk-proj-...}` - a real-shaped key as the **default** of the env placeholder, present since the baseline commit. Removed from the working tree (`${AI_API_KEY:}`), but **it remains in git history**, which cannot be rewritten safely here. **The only real remedy is for the investor to rotate the key at the provider.** Stays open until that is done. See the full entry below. |
| B-114 | P2 | The app failed to start on two mornings and left no diagnostic trace whatsoever | 2026-09-16 | `start-app.bat` discarded every byte it produced: Steps 1-2 wrote to a console Task Scheduler throws away, and Step 3 redirected `mvn spring-boot:run` to **nul**. The 09:00 `TradingApp-Start` task exited **1** on 2026-09-15 and again on 2026-09-16 (Sep-15 only came up at 10:14, by hand), and there is no record anywhere of why - `logs/trading-app.log` has no entry for either 09:00, because the script aborted before launching the JVM. Exit 1 can only come from Step 1, so `mvn clean compile` failed; the same command succeeded three minutes later and the task itself succeeded on a manual trigger at 09:12, so it is **intermittent**, cause not yet named. Leading hypothesis: the VSCode Java language server (launched ~08:22 both mornings, indexing when the task fires at 09:00) holds handles on `target\classes`, so `mvn clean` cannot delete it - the classic Windows lock. **Fix shipped 2026-09-16**: the script now appends everything to `logs/start-app.log` and sends console output to `logs/app-console.log` instead of nul, so the next failure names itself. Stays open until a captured failure confirms or refutes the hypothesis. |
| B-115 | P1 | The screener page timed out on every load: one query read the whole score table to keep 276 rows | 2026-09-16 | The dashboard showed *"the app is running but did not answer in time"* on the screener. `GET /api/dashboard/screener` measured **31.7 s cold** against `api.js`'s 8 s `TIMEOUT_MS`, so the page fell back to its last saved view every time. Cause: `MacroExposureService.latestScores` (and three siblings) called `findRecentForSymbols`, which loads **every** screening row for the given symbols over a 400-day window and then keeps the newest per symbol in a Java map. On the screener that is 276 symbols x up to four spellings (Gotcha 84) against a table of **27,845 rows / 408 symbols / 112 dates** - effectively the whole table, hydrated as 103-column entities, to use 408 of them. Stack sampling put 11 of 12 in-request samples in that one call. **Fix**: `findLatestForSymbolsSince` returns one row per symbol, resolved in SQL via a grouped max over the existing `(symbol, screening_date)` unique index; three callers with identical semantics switched to it. `EXPLAIN ANALYZE` on the new query: **44 ms**. Screener cold **31.7 s -> 3.2 s**, warm **~0.9 s**; summary 8.9 s -> 1.5 s; watchlist 3.0 s -> 0.7 s. Output verified **byte-identical** across all 276 rows and 103 fields. Resolved 2026-09-16. |
| B-116 | P2 | One free-text field from NSE froze the whole IPO table for two days | 2026-09-16 | `ipo_issues.issue_type` is NSE's own words for the offer structure, stored in a `varchar(32)`. A **further public offer** entered the pipeline reading *"100% Book Building ( Further Public Offer)"* - **42 characters** - and every capture since **2026-09-14** died on `value too long for type character varying(32)`. Two failures in one: the column was sized for the values seen at build time rather than for third-party prose, and `IpoTrackingService` ends its run with a single `repository.saveAll(...)`, so one bad row rolled back **all 255** and discarded ~4 minutes of paced NSE and Kite calls - the same shape as B-049. The scheduler logged the exception and moved on, and the only outward sign was `ipoIssues` sitting at 2026-09-14 on the freshness strip, which `/api/dashboard/data-health` correctly reported as WATCH. **Fix**: column widened to 160 with an explicit `ALTER ... TYPE` in `SchemaMigrationRunner` (Gotcha 74 - `ddl-auto=update` never alters an existing column's type), the value truncated on write so no future length can abort a run, and rows saved one at a time with the failure count reported in the capture note. Verified: capture returns 200, 255 rows touched, 232 prices refreshed, 0 failures; data-health back to OK. Resolved 2026-09-16. |
| B-008 | P2 | Logback `maxHistory` is 1 day — audit windows are tiny | 2026-05-10 | `trading-app.log` rolls daily, only one rolled file kept. A 5-day audit can only see ~2 days of data. Bump retention in `logback-spring.xml`. |
| B-010 | P2 | Depreciation no longer in NSE quarterly JSON | 2026-05-10 | Lives only in XBRL files. `IntrinsicValuationService` falls back to net profit as FCF proxy (conservative). `FinancialQuality` cash-flow ratio treated as missing (neutral 50). Add XBRL parser if precision needed. |
| B-012 | P2 | FII vs DII split is approximated 50/50 in shareholding | 2026-05-10 | Per-record split is in XBRL only. JSON has total promoter % and public %. Trend detection still works (FII and DII change directions are correlated), but absolute split is no longer authoritative. |
| B-023 | P1 | Re-weight the scoring engine once clean IC data exists | 2026-08-22 | Per-dimension IC says the three price dimensions (Technical Momentum, Relative Strength, Price Structure) carry **0.42 of the weight** while being mutually collinear (r = 0.72–0.79) and individually near-zero IC (−0.034 / +0.003 / −0.031 at 30d). Financial Quality is the only dimension above the +0.10 useful-signal bar (+0.110) and carries just 0.15. **Deliberately NOT acted on yet**: that IC was measured on scores from the broken engine (constant Valuation dimension, weights summing to 1.15, absolute-only capture gate), so re-weighting on it would be tuning against corrupted measurements — exactly what SPEC §25.5 warns against. Revisit once ~3 months of post-2026-08-22 outcomes exist and IC is stable across two consecutive weekly runs. Same applies to recalibrating the verdict bands (§12.5), which were designed for a mean-50 distribution. **⚠ Superseded by B-028 (2026-08-24)**: the IC quoted here was computed over outcomes whose horizons were mislabelled (20.8% of 30d rows, 29.4% of 90d rows ran longer than their label), so these figures describe a longer holding period than they claim. Re-read after the next outcome run before drawing any conclusion. |
| B-042 | P2 | PIT endpoint has no circuit breaker; a failed fetch is cached as "no rows" for 6h; B-031 guard misses `txDate > disclosureDate` | 2026-08-26 | `NseDataService.java:1277–1304`: no failure counter (only quote-equity has one), and the empty list is `putCache`d on exception, so a manual `POST /api/insider/capture` retry within 6h silently returns 0. B-031's guard rejects only `txDate > today`, so the SOLARINDS rows (tx Nov-2026, disclosed Mar-2026) re-ingest after 2026-11-09 and count as "recent" for 90 days. **Fix**: reuse the 10-failure/6h breaker; don't cache on exception; also reject `txDate > disclosureDate`. |
| B-056 | P1 | `BOOK_PROFIT` flags the best compounders while they run — a momentum rule inside a long-term portfolio app | 2026-08-26 | `HoldingsAnalysisService.determineRecommendation()` returns `BOOK_PROFIT` on `pnlPercent > 50 && rsi > 70`, i.e. exactly the holdings that are winning and still strong. The mission pivoted to long-term investing on 2026-04-18 but this rule did not: it tells the investor to trim their strongest positions, and the same method returns `SELL` on `overallScore < 40` or a bearish trend, so a quality compounder in a routine 20% drawdown is marked for exit. Fires into the daily 3:18 PM holdings email today. Found during the review of CORE_HOLDINGS_RECYCLING_PLAN.md (§15, R-12); that plan's §5 is the proposed fix (rank by forward weakness per rupee, tax-gated, paired with destinations) but the defect stands on its own. **Fix, part 1 shipped 2026-08-26** (SPEC §35.5): core holdings are excluded from both the Book Profit and Exit Required tables — a technical SELL on one now appears under *"Core holdings — hold through the noise"* with its durability and reason, so the signal is shown rather than deleted — and the Book Profit header carries the caveat naming the rule as a momentum rule from the intraday era, to be read as a prompt to check valuation rather than a sell instruction. **Still open** for non-core holdings: the rule itself is unchanged, and roughly half the portfolio is UNCLASSIFIED at launch (SPEC §35.4) so the caveat is carrying most of the weight. Part 2 is the plan's §5 replacement (rank by forward weakness per rupee, tax-gated, paired with destinations), which is deferred behind its own evidence gate — next review **2026-11-30**. This entry moves to Resolved when that ships, not before. |
| B-055 | P2 | Universe cap eviction ranks on composites frozen at promotion time | 2026-08-26 | `UniverseExpansionService.enforceCap()` evicts the weakest active rows using `lastCompositeScore`, which is written once at promotion and only refreshed for symbols the screener actually re-screens. In observation mode (`dynamic-expansion.enabled=false`) promoted symbols are **not** in the screening universe, so their scores never move — the cap therefore evicts on a stale snapshot that can be months old, and ranks a symbol promoted at 98 above one promoted at 70 regardless of what either has done since. Raised alongside B-036 but not filed at the time. **Fix**: re-score promoted rows (or read their latest `multibagger_scores` row) before eviction; treat a symbol with no recent score as unmeasured rather than ranking it on the old number. |
| B-045 | P2 | Dimension-variance tripwire will raise a standing false ERROR on Insider Pulse; Capex missing from it | 2026-08-26 | `logDimensionVariance` includes `InsiderPulse`, whose service returns NEUTRAL (→50) for most stocks with any filing; once ≥10 have filings, sd < 5 and the log says its "weight is contributing no information to the composite" — for a signal with zero weight. Repeated false ERRORs desensitise the alarm (the whole point of the tripwire). `Capex Cycle` is in the IC dataset but not the tripwire. **Fix**: split shadow/lens signals into a WARN-level check with distinct wording; add Capex for consistency. |
| B-050 | P2 | Retro-backtest fixture is 9 winners / 10 controls (not ~20/~20) and the <200-candle gate drops recently-listed winners only | 2026-08-26 | `RetroBacktestService.java:66–89,157`: KPITTECH, POLYCAB, SONACOMS (listed 2019–2021) skip the gate; no control does. Only 6 winners scored, so the "separation" measures long-listed winners — the opposite of the fresh listings §30 hunts — and one outlier moves the winner mean by ~10 points. Skips are logged but the JSON doesn't attribute them to a group; no spread shown. **Fix**: extend the fixture; attribute skips by group in the output; show per-case scores and stdev; lower the gate for RS/structure dimensions that need fewer bars. |
| B-051 | P2 | Concall: transcript regex accepts presentations/notices; `@Transactional` spans PDF download + AI; guidance dedup key is AI free-text; `quarter` label unjoinable to XBRL | 2026-08-26 | `NseDataService.java:1480–1497` accepts "Investor presentation for Q1 earnings conference call" as a transcript → slide bullets recorded as guidance. `ConcallAnalysisService.java:222` pins a Hikari connection for 45s+AI retries. Dedup on `metric` phrasing (231–238) double-counts re-runs. `quarter` is caller-supplied (`ConcallController.java:40`) while XBRL keys are `"01-Apr-2025 To 31-Mar-2026"`; `sourceDate`/`dueDate` ignore the parsed transcript date/timeframe (250–251). Measurement-only today, so no decision impact. **Fix**: require "transcript" in the title or exclude presentation/notice/schedule/audio; move `@Transactional` to the save loop; normalise metric (lowercase, strip punctuation/FY suffix); derive `quarter` from `transcript.date()` in the XBRL label format. |
| B-052 | P2 | Docs/config drift after F1–F8: SPEC §15/§16/§17 incomplete, B-032 unverified, dashboard hard-codes thresholds | 2026-08-26 | SPEC §15 lacks the 14:45 insider-capture row; §16 lists none of `/api/insider|universe|fundamentals|concall/*`, `/api/multibagger/under-radar`, `/api/research/capex`, `/api/accuracy/retro`; §17 lists none of `insider_disclosures`, `dynamic_universe`, `annual_fundamentals`, `guidance_items`; §30.2 says Stage A = 22 min, §30.6 says ~10; B-032 sits in Resolved with no verification evidence; B-034 says "7 of 7" then lists 8 symbols; CLAUDE.md package counts stale and the "two guard exceptions" sentence (line ~687) names the wrong methods; §27.5's `Sec-Fetch-Site` filter does not exist in code. `page-discovery.js:30–31` hard-codes the 65/60 under-radar gate the backend reads from yml, and Discovery has no freshness stamp for `insider_disclosures`/`dynamic_universe` (SPEC §27.7). **Fix**: one docs pass; expose the threshold + freshness keys from `DashboardService`. |
| B-061 | P3 | Watchlist `riskRewardRatio` is always exactly 2.0 | 2026-08-27 | `WatchlistAnalysisService.generateEntrySignal()` builds stop = entry − 2×ATR and target1 = entry + 2×ATR, then divides one by the other — the column carries no information and the old market-page panel rendered it as "1 : 2.0" for every stock. Blast radius: cosmetic (the column is not used by any decision). Found while building SPEC §37; the new page does not show it. **Fix**: derive the target from the nearest resistance (already computed) so R:R varies, or drop the column. |
| B-062 | P3 | Watchlist `vwap` column is declared but never written | 2026-08-27 | `WatchlistEntity.vwap` exists on the table and is null for every row; nothing computes it. Blast radius: none (unused). **Fix**: remove the field, or compute it if a consumer appears. |
| B-068 | P2 | "Wait for a dip" quoted a dip 0.0% below today's price | 2026-09-01 | Found by the investor on KRBL: watchlist showed **Wait for a Dip** beside a suggested entry of Rs 424.00 against a live price of Rs 424.10, and the tooltip said so in words — *"That is 0.0% below today's Rs 424.10."* Root cause: `SuggestedEntry.compute` gated the waiting branch on `support20d < currentPrice` only, so any gap above zero qualified as a dip. Blast radius: the entry column on the watchlist, screener and all four discovery tables — the investor is told to wait and to buy now in the same row, which is the B-062 contradiction reproduced inside a single cell. Confirmed live on 6 of 16 watchlist rows (SKYGOLD Rs 770.00 vs Rs 770.05, TIIL 0.3%, NITINSPIN 0.9%). **Fixed 2026-09-01**: a dip must clear `max(2% of price, ATR-14)` — one ATR because a level inside a normal day's range is reached on noise, 2% because a smaller entry difference is not worth acting on over a multi-year hold. Below that the cell shows no number and the reason names the measured gap, which is itself the finding. Pinned by `SuggestedEntryTest` (5 new cases incl. the exact KRBL figures + a swept invariant that any quoted dip is >= 2%). **Second defect RESOLVED 2026-09-01** by rebuilding the column as a ladder (SPEC §12.12): the deepest rung now snaps to the 50-day average when that is nearer than the volatility ladder reaches, so the number answers the question the words asked; when the average is further away it is not quoted, because a level the stock may not revisit for a year is a decision never to buy. The 2%/ATR guard from part one is retired as unnecessary — a waiting verdict's ladder now *starts* below market, so quoting today's price is structurally impossible rather than caught by a threshold. Original note kept for the record: the quoted level need not answer the question the verdict asked. KRBL's verdict reason is *"10% above its 50-day average — wait for a dip nearer the average"* (EMA50 = Rs 385.27) while the number offered is the 20-day low. Choosing which level the entry should track is a product decision affecting every surface, so it is raised rather than assumed. |
| B-069 | P1 | Portfolio "Signal" contradicted "Still a good time to buy?" on 9 of 32 holdings | 2026-09-02 | Found by the investor on BEL: **Signal = BUY** printed beside **AVOID**, the AVOID being a forensic red flag on cash conversion. Root cause: two engines answering one question in one vocabulary on one screen — the B-062 failure surviving on a surface Gotcha 85 never covered. `HoldingsAnalysisService.determineRecommendation(overallScore, trend, rsi, pnlPercent)` is a **pure momentum rule** with no sight of fundamentals, forensic flags or financial quality (it is the intraday-era rule B-056 already documents), while the adjacent column runs `BuyTimingVerdict`, which reads all three. Measured blast radius on the live portfolio: **9 of 32 holdings** disagreed — 4 flat contradictions (BEL cash conversion, PNBHOUSING dilution, ABCAPITAL financial quality HIGH_RISK, RATHIST composite 28) and 5 cautions. The signal column also carried **zero SELL across 32 holdings** with 23 BUY/STRONG_BUY, which is what a momentum rule does in a rising market. **Fixed 2026-09-02** (SPEC §6.6): new pure `SignalReconciliation` lets a quality problem veto a buy at **display time** — AVOID + buy → HOLD, HOLD_OFF + STRONG_BUY → BUY, everything else untouched. It can only ever *lower* a signal (a risk control that can raise one is not a risk control, Gotcha 42) and never produces SELL ("do not add" is not "get out" — exiting costs tax, and §35 exists to stop shake-outs). `holdings.recommendation` is **not** rewritten, so ML labels and stored history are unaffected (the Gotcha 69 contract). Pinned by `SignalReconciliationTest` (11 cases incl. BEL's exact pair and a swept never-raises invariant). **Centralised 2026-09-02** after the first patch created a fourth inconsistency of its own (the stock page still rendered `recommendation` raw, so BEL read BUY there while the portfolio said HOLD): `HoldingsViewDecorator` now attaches `displaySignal`/`signalNote`/`buyTimingVerdict`/the shared entry ladder as `@Transient` fields on **all five** holdings read paths, and the portfolio's private entry rule (`suggestedEntry ?? support1`) was replaced by the shared ladder. Verified live: 0 mismatches across 32 holdings between the list and single-stock endpoints. `CrossSurfaceConsistencyTest` pins the property. **Root rule still open as B-056**: the underlying momentum rule is unchanged — this reconciles the columns rather than fixing the engine that made the claim. |
| B-070 | P1 | CSP blocked **every inline style** in the dashboard — score bars drew 100% full for every score | 2026-09-03 | Found while building the Guide page (SPEC §27.11): a styled heading rendered with `weight=400, border=0px` despite carrying the right `style` attribute. Chrome reported `securitypolicyviolation: style-src-attr <- inline`. The CSP meta on all ten pages set `style-src 'self'` with no `'unsafe-inline'`, and CSP L2+ applies `style-src` to **style attributes**, not just `<style>` blocks — so every `el(..., { style })` call in `ui.js` and all eleven page modules has silently done nothing since the dashboard shipped. **Blast radius — this is not cosmetic**: `scoreBar()` encodes the score as `style="width:NN%"` on the fill, so measured live, **a 45 and a 99 drew identically** (99→42px/42px, 65→36/36, 55→36/36 — every bar 100% full). The colour band still varied because that comes from a CSS class, so the result looked plausible and wrong: eight dimension bars per row on the screener, plus under-radar, conveying no information at all. Also silently dead: column min-widths, the "adjusted for quality" note spacing, sparkline sizing, every inline font-size. **Fixed 2026-09-03**: `style-src 'self' 'unsafe-inline'` on all ten pages. This does **not** weaken the no-CDN guarantee — an external stylesheet is still governed by `'self'` — and markup injection is already structurally impossible because `el()` throws on a raw `html` prop and every value is set via `textContent` (SPEC §27.3). Verified after: 99→44/44, 85→37/44, 65→29/44, 55→24/44. **Lesson**: a `style` attribute present in the DOM is not a style that applied — assert on `getComputedStyle`, not on the attribute. |
| B-094 | P1 | Every outbound HTTP call uses Netty's own DNS resolver, not the OS one — the app is offline while the machine is fine | 2026-09-09 | Found from the investor's failed watchlist add of KPRMILL. **Decisive measurement, same machine, same seconds**: at 18:14:07–18:14:25 `InetAddress.getAllByName("api.kite.trade")` succeeded **five times in 13–25 ms**, while the running app logged `Failed to resolve 'api.kite.trade' [A(1)]` and `Failed to resolve 'www.nseindia.com'` throughout the same window. Root cause: every `WebClient` in this codebase is built from the injected `WebClient.Builder` (`KiteBrokerClient`, `KiteAuthService`, `NseDataService`, `StockValuationService`, `MultiSourceNewsFetcher`, `ZerodhaPulseNewsService`), and reactor-netty defaults to Netty's **built-in async DNS resolver**, which bypasses the OS resolver and its cache and queries the configured nameserver (here the router, `192.168.1.1:53`) directly over UDP. On a flaky nameserver that fails while every other program on the machine is fine. Measured on JVM 22392 (started 15:59): **3 successful Kite responses, all at boot, then 65 consecutive DNS failures from 17:27 onward**; 353 resolve failures across the day (api.kite.trade 257, www.nseindia.com 73, kite.zerodha.com 10, nsearchives 1). **Blast radius: everything external, and mostly silently** — `MarketDataService` returns `0.0` on every failure path by contract (Gotcha 22), so a DNS outage and a delisted ticker are the same observation downstream; prices, NSE fundamentals, the insider feed and the token login all degrade to 'no data' rather than 'could not connect'. This is the half of **B-093** that was written off as environmental: the machine's DNS is indeed flaky, but the app is far more fragile than the machine, and that gap is a defect. **Fix**: one `WebClientCustomizer` bean applying `HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE)` so every builder-derived client uses the OS resolver (single point, covers Kite + NSE + news at once). Verify by reproducing the simultaneous test above and seeing the app succeed. |
| B-095 | P2 | Watchlist add blames the symbol for what was a network failure | 2026-09-09 | The investor could not add KPRMILL and was told *"check the symbol (renamed or delisted tickers return nothing) … an empty answer means the symbol is wrong"* — while the log for the same request showed six `Failed to resolve 'api.kite.trade'` retries and `Retries exhausted: 5/5` (B-094). KPRMILL is valid and had scored **91, Grade A+, STRONG_MULTIBAGGER** in that afternoon's own screening run four hours earlier. `WatchlistTrackingService.java:252-258` cannot tell the two apart because `MarketDataService.getCurrentPrice` collapses every failure path to `0.0` (Gotcha 22, deliberately — 113 call sites dereference it), so the refusal asserts the one cause it has no evidence for. Same family as **B-086**, which fixed exactly this confusion in `api.js` ("a request that TIMED OUT and a server that is NOT THERE are different facts") — the lesson was applied to the front end and not to the write path behind it. **Blast radius**: sends the investor to correct a ticker that is already right, and hides a live outage of every external feed. **Fix**: distinguish unreachable from unpriced before composing the message — either a `priceOrReason(symbol)` variant that reports the failure class without changing the `0.0` contract, or a connectivity probe on the refusal path; the 422 should read "could not reach the broker" when nothing was reached, and keep the symbol advice only for an empty-but-successful answer. |
| B-108 | P3 | The per-stock news search is handed an *industry* where it expects a company name | 2026-09-12 | `MultibaggerScreenerService` calls `analystSignalService.analyze(tradingSymbol, companyName)` with `companyName = valuation.getIndustry()` — the comment at the call site calls it a "best available company hint", so it is knowingly approximate, but `StockNewsService.buildSearchQuery` appends it as an OR alternative to the company name: the query for RELIANCE becomes `RELIANCE+stock+India+OR+Refineries`, which broadens the search to an entire industry rather than narrowing it to the company. Google News RSS returns at most 10 items, so every slot an industry story takes is a company story lost. **Blast radius**: dilutes the §24 brokerage-flow bonus input (±3 of a ±5 post-composite bonus) and, since 2026-09-12, the §49 target ledger's main capture source — a missed headline is a target never recorded, which reads downstream as "no analyst published one". Not a wrong number, a thinner feed. **Found** while measuring §49's capture funnel; `UniverseSectors.entryFor(symbol).name()` already carries the real company name for 755 symbols and is the obvious substitute. Separately `buildSearchQuery` appends `"&when:7d"` with a literal `&`, so the date filter lands as a stray URL parameter instead of inside `q=` — the 7-day window has never been applied. Both are one-line fixes in one method; neither is urgent because the feed still returns relevant items. |
| B-109 | P2 | A company name that CONTAINS another company's name resolves to the wrong ticker | 2026-09-12 | Found by running `HeadlineSubjectResolver` over 6,275 historical headlines (SPEC §49.4): *"Buy Larsen and Toubro Finance; target of Rs 260: Motilal Oswal"* resolved to **NSE:LT** (Larsen & Toubro, trading ~₹3,380) instead of **NSE:LTF** (L&T Finance, ~₹260). Root cause is an **alias gap, not a matching-order bug**: `universe-sectors.csv` holds LTF as *"L&T Finance Ltd."* (abbreviated) while the headline spells it *"Larsen and Toubro Finance"*, so LTF never matched at all and LT was the **only** match — the two-companies-resolve-to-neither guard (Gotcha 126c) never fired because there was only one. **Blast radius**: files a ₹260 target against a ₹3,380 stock, which renders as a −92% call on L&T's page and resolves MISSED against a house that never said it. 2 of 611 candidate rows. The same shape threatens every parent/subsidiary pair written long-form (HDFC / HDFC Bank, Bajaj Finance / Bajaj Finserv, L&T / LTTS / LTF). **Fix**: seed long-form aliases for abbreviated index entries, and require that a match consume the *maximal* name span in the headline — if the text continuing past a match is itself part of another known name, refuse rather than take the shorter one. |
| B-110 | P3 | A commodity contract wearing an exchange's name resolves to that exchange's own ticker | 2026-09-12 | *"Motilal Oswal recommends 'buy' on MCX Copper, set target at ₹915"* was filed against **NSE:MCX** (Multi Commodity Exchange, the listed company, ~₹10,000). ₹915 is a copper futures price; the call is not about the share at all. `HeadlineSubjectResolver` matched the token `MCX` via the all-caps ticker fallback and had no way to see that the following word turns it into a contract. **Blast radius**: 1 of 611 rows; a commodity call scored as an equity call, and an absurd −91% target that would resolve MISSED against the house. **Fix**: reject an all-caps ticker match when the next token is a commodity or contract word (copper, gold, silver, zinc, lead, crude, natural gas, futures, options) — the same class of guard as `INDEX_BEFORE`, which already stops a Nifty/Sensex level being read as a share target. |
| B-111 | P3 | A corrupted thousands separator truncates a target by 1000x | 2026-09-12 | *"Buy UltraTech Cement; target of Rs 13\800: Motilal Oswal"* — the Moneycontrol headline carries a literal **backslash** where the thousands comma belongs. `AnalystTargetParser.AMOUNT` matches `13` and stops, so the ledger records **₹13** for a stock trading near ₹12,450. **Blast radius**: 1 of 611 rows, but it inverts the call — a target far *below* the price sets `direction=BELOW`, the price never falls to ₹13, and it resolves MISSED against a house that said ₹13,800. Caught by cross-checking each target against the median of other targets on the same stock (>5x ratio), which is a cheap guard worth keeping. **Fix**: treat `\` between digit groups as a thousands separator like `,`, **or** reject an amount whose immediate next character is a backslash — rejecting is safer, since a truncated target is worse than no target. A plausibility check against `priceAtCall` at measurement time would catch this whole family (units errors, not just this one) and is the stronger general guard. |
| B-120 | P1 | The landing page's "Today's Change" summed a **per-share** price delta and drew it as rupees | 2026-09-18 | Measured on the live book: the Overview's third tile read **-Rs 52.23 (-0.02%)** on a day the portfolio had actually gained **+Rs 4,607.90 (+1.90%)** - wrong sign, wrong by Rs 4,660, 89x the magnitude shown. Root cause: `HoldingsAnalysisService.java:158` stores `dayChange = lastPrice - closePrice`, which is **per share** and is never multiplied by quantity. That is correct for `dayChangePercent` (a ratio is the same either way) but it is not money, and `DashboardService.portfolioKpis` summed it across 30 holdings and divided by portfolio value - rupees-per-share over rupees-of-portfolio. Because per-share deltas are dominated by share price rather than position size and roughly cancel across thirty names, the tile read about zero almost every day, so it could show neither a good day **nor a bad one**: `NSE:CPPLUS` (1 share, Rs 3,375) contributed **-Rs 172** while `BSE:LCCPROJECT` (102 shares, **+Rs 2,728** of real money) contributed **+Rs 26.75**. The same field fed the rupee note under each bar in "Biggest moves", which told the investor a Rs 2,728 gain was Rs 27. Same family as B-047 (a quarter filed as a year) and B-113 (two years compared against ten) - a figure quoted on a scale it was not measured on. **Fixed 2026-09-18**: `HoldingsEntity.getDayChangeValue()` is a derived `@Transient` returning `(currentPrice - closePrice) * quantity`, **null** when either price is absent so an unknown move is never summed as zero (SPEC 21 rule 7); the tile and the per-row note both read it, so they cannot drift (Gotcha 85). Verified live on identical rows: old formula **+Rs 109.65 (+0.04%)**, truth **-Rs 495.21 (-0.19%)** - wrong sign again, independently, on the day of the fix; per-row sum equals the tile exactly. Pinned by `OverviewTruthTest` (4 cases incl. the exact CPPLUS/LCCPROJECT pair and the missing-close null). Found by the SME review of the Overview page. |
| B-121 | P2 | Half the landing page's attention list was permanent noise from allocation targets nobody chose | 2026-09-18 | The "what needs your attention today" list is the app's action surface. Measured live: **20 items, of which 10 were `ALLOCATION_DRIFT`** - every single sector and market-cap bucket over tolerance - against a profile named **"Default Portfolio"**, seeded at first boot. Targets were 20% IT against 0.43% held and 25% Banking against 3.77%, so every bucket breached every day and none could ever clear until the investor edited a profile they do not know exists. A reader shown the same ten alerts daily has been trained to skip the section (the Gotcha 132 lesson, landing on the most-read screen). Worse, one of them was **`OTHER` carrying a 15% target** - a placeholder given an allocation goal, which Gotcha 110 forbids outright, and since `SectorMapping.resolve` now classifies every holding its actual weight is **0.0%**, making that warning unsatisfiable by construction. Second-order finding from the same load: **all 20 items were severity `WARNING`**, so `severityRank` sorted a constant while the section copy promised "most urgent first". **Fixed 2026-09-18**: `portfolio_profile.targets_stated` (nullable `Boolean`, ensured in `SchemaMigrationRunner` per Gotcha 74) records whether the investor set the targets themselves - the same rule as `thesis_stated` (B-097) and `horizon_stated` (B-057): **a default is not a statement** (Gotcha 68). It is set by `AllocationService.replaceTargetWeights`, i.e. the `PUT /api/portfolio/profile` write, and never by the seeder; **null means unknown and is not read as true**. While targets are unstated the attention list raises **one `INFO` row** naming how many buckets breach and what would make the comparison meaningful, instead of one `WARNING` per bucket - the information is reported, not discarded, and the drift table on My Portfolio is untouched. Placeholder sector buckets are dropped from drift entirely. Verified live: attention **20 -> 11** (3 exit, 7 thesis, 1 info), `OTHER` gone, 49 buckets still rendered on the drift table. Pinned by `OverviewTruthTest` (5 cases incl. that a stated profile still raises every breach, and that an exit signal still outranks the info row). |
| B-122 | P2 | The landing page quoted 4,979 overlapping rows as if it were a sample size | 2026-09-18 | The Overview's track-record panel rendered `MULTIBAGGER - 90-day results - from **4,979 picks**`, hit rate **55.5%**, excess **+3.21%**, IC **0.114** - just above the conventional 0.10 "useful signal" line - under a heading saying this is "whether the app's own past picks actually beat the index", with no caveat. That count is ~82 screening dates x ~300 stocks whose returns move together, measured over windows that share most of their days: Gotcha 88 puts the effective independent sample near **five periods** at t=1.24, p=0.28, and the app's **own** recorded walk-forward review for this horizon reads `independentPeriods: 1`. The display gate was `MIN_SAMPLE_FOR_DISPLAY = 10` - a row count, which is precisely the quantity Gotcha 92 says never to read as a sample size - so it cannot catch this. The panel's own code comment warns that "a hit rate from 3 picks looks exactly as authoritative as one from 300" and then enforces the row count. Direction: **flatters the engine**, on the screen that decides whether the investor trusts everything else, which is the failure that ended the previous ML chain (SPEC 25.5). **Partly fixed 2026-09-18**: the three figures stay on screen - they are real, they are merely not yet distinguishable from luck, and saying so is the discipline - beneath a caveat naming the overlap and linking to Track Record. **Still open**: `accuracyHeadline()` filters on horizon only and takes `max(sampleSize)` across **every** `RecommendationEntity.Source`, including `MACRO_EVENT`, whose own javadoc says anything presenting "the app's picks" must filter it out, and the deleted `SECTOR_REVERSAL` engine (IC **-0.167** at 90d, 431 rows). Both have too few matured rows to surface today; neither will stay that way. Fix: restrict the headline to pick sources, and print `independentPeriods` from the latest `weight_reviews` row beside (or instead of) the count. |
| B-123 | P2 | The attention list prints no denominator, and its all-clear covers a third of the book nobody checked | 2026-09-18 | Measured on 30 holdings: thesis drift is computed on **21**, because 9 are `NO_DATA` (SKYGOLD, EMCURE, LCCPROJECT, PREMIERENE, GULPOLY, NITINSPIN, RATHIST, VMM, WAAREEENER - outside the screening universe, or too recently added to measure a 30-day delta). `DashboardService.attention` excludes them, which is **correct** - a gap in our coverage is not a finding about the stock - but nothing on the page says so. When the list is empty the page prints *"Nothing needs your attention today - No exit signals, no thesis drift, and your allocation is within the tolerance you set"*: three specific absences asserted over a book of which 30% could not be checked for the second. Gotcha 44 in the most damaging position available, a clean bill of health on the landing page. Every comparable surface here prints its coverage (the holdings email's forensic line, `CompoundingLensService`, `AnalystTrackRecord.caveat()`, all of SPEC 44); the most-read screen does not. **Fix**: a mandatory coverage line derived from the lists it describes (Gotcha 98) - "3 exit signals from 30 holdings; 7 thesis alerts from 21 checked, 9 could not be" - and an empty state that states the same denominators rather than three absences. Found by the SME review; deliberately left open because the scope agreed was the misleading numbers. |
| B-124 | P2 | The app's only armed risk control never reaches the attention list | 2026-09-18 | `BSE:ABCAPITAL` is held. Its 2026-09-17 screening row carries `forensicFlags = CASH_CONVERSION:HIGH` - the severity tier that disqualifies (Gotcha 77) - plus `financialQualityVerdict = HIGH_RISK` and `compositeScore = 54`, the HIGH_RISK hard cap itself; compounding reads `NO`. It appears **nowhere** on the Overview. `DashboardService.attention` draws from exactly four sources: `holdings.recommendation` in {SELL, STRONG_SELL} (the momentum rule B-069 and B-056 are about), thesis decay, allocation drift and concentration alerts. Forensic flags and the financial-quality verdict are not among them, although both sit on the screening row the same service reads two methods away. A business that is structurally fragile but whose chart is fine is therefore invisible - while the list had room for ten allocation-bucket rows (B-121). **Direction: silently flatters a holding the app itself judged HIGH_RISK.** Forensic is the one signal that ships **armed** (`forensic-actionable: true`, Gotcha 42) precisely because a risk control being wrong costs capital; arming it and then not showing it on the action surface removes the asymmetry it was armed for. **Fix**: a `FORENSIC_FLAG` kind (URGENT for HIGH, WARNING for MEDIUM naming the flag, INFO never raised) and a `QUALITY_RISK` kind for `HIGH_RISK`, both resolved across exchange prefixes via `SymbolVariants` and both carrying the screening date they came from - surfacing verdicts the engine already computes, adding no new vocabulary and no points. |
| B-125 | P2 | My Portfolio's allocation heading reads a green "On target" while the Overview lists the same buckets as drifting | 2026-09-18 | `page-holdings.js:1096-1100` filters `data.drift.buckets` on `b.status === 'OVER_TOLERANCE'`. **`AllocationDto.DriftBucket` has no `status` field** - it has `alertLevel`, which `driftPanel()` itself reads correctly at line 1000. The filter therefore always yields 0, so the folded section heading renders **"On target"** with `{type:'success'}` - a green all-clear - from the same response that produced 10 over-tolerance buckets on the Overview. Under SPEC 27.15 a folded heading is the only thing the reader sees, so a green badge means the section is never opened. Two screens answering one question with different answers (Gotcha 85), and the wrong one is the reassuring one. Adjacent, same file, line 468: the thesis-drift count is `verdict !== 'INTACT'`, which includes the **9 `NO_DATA`** holdings, so that heading reads 16 where the Overview's list has 7 - "could not check" counted as "not intact" (Gotcha 44/68). Both arrived with the uncommitted collapsible-sections work and neither is on the Overview, so both are filed rather than fixed. **Fix**: read `alertLevel`; derive each count from the list it sits above (Gotcha 98); exclude or separately label the unmeasured decay rows. |
| B-126 | P3 | Every section on every page folds by default, including the landing page's action surface | 2026-09-18 | Headless render of `index.html`: all five sections carry `hidden="until-found"`, so a first-time reader's landing page is five collapsed headings - one of which is *"What needs your attention today (11)"*. `ui.js` `foldSections()` calls `collapse(node, { key, open: false })` unconditionally. SPEC 27.15 specifies the opposite: *"The default is data-driven, not positional... the first section **with a non-zero count** opens and the rest fold. A page whose one open section is the empty one is the opposite of a contents page."* Count pills make this safe rather than dangerous - the reader can see there are 11 items - so it is a specification mismatch and a usability regression, not a correctness bug, which is why it is P3. Affects all eleven folding pages; it costs most on the Overview, whose own javadoc says it "answers two questions and then gets out of the way" and which in this state answers neither without a click. **Fix**: open the first section whose heading carries a non-zero `.count` pill, falling back to all-folded when none does, with a reader's stored choice still winning over the default. |
| B-013 | P2 | Stale-symbol replacements pending verification | 2026-05-10 | Removed 23 stale symbols (B-005). 2026-05-23: removed `GSPL` from the Nifty200 pool (Kite `/quote` returns `{status=success, data={}}` — invalid tradingsymbol) and made `KWIL-BE` resolve via NSE trading-series-suffix stripping in `KiteBrokerClient.getQuote` (`-BE/-BZ/-BL/-IL` → plain symbol). TATAMOTORS, LTIM still need verified post-corporate-action tradingsymbols before re-adding. Don't replace by guess — wrong symbol = wrong company analysed. |

## In Progress

*(none)*

---

## Resolved Bugs

### B-119 - The freshness strip aged five tables by up to 15 hours, on every page  `[P2]`  `RESOLVED 2026-09-17`

Found by investigating "I see stale data in my entire app" when every table was in fact current.
`/api/dashboard/data-health` reported **0 problems across 55 checks**, prices were live, and the
14:00 screening had written 274 rows that afternoon - yet the strip at the top of every screen
read **"Scores: 9 hours ago"**.

**Root cause**: `relative()` in `format.js` parsed both freshness shapes with a bare `new Date()`.
The two shapes are parsed by *different rules* in JavaScript. A date-time with no zone
(`2026-09-17T15:15:00`) is read as **local** time, which is correct here. A bare date
(`2026-09-17`) is read as **UTC midnight** by specification - 05:30 IST. Five of the twelve
freshness keys are dates, because the tables behind them are keyed by date and no run time was
ever recorded: `multibaggerScores`, `holdingsHistory`, `recommendationOutcomes`,
`holdingClassification`, `watchlistSnapshot`.

So a screening that finished at 14:00 reported itself as "9 hours ago", and yesterday's rows
aged into "1 day ago" five and a half hours early. `daysAgo()` - which drives the amber `.stale`
class - had the same skew in the forgiving direction, so it flagged a genuinely late table up to
5.5 hours after it was due.

**Blast radius**: the freshness strip is the app's answer to "is what I am looking at current?",
and it renders on all eleven screens (SPEC 27.12, Gotcha 116). Understating freshness there
understates it everywhere at once, which is precisely the "entire app" shape of the complaint. The
cost is the investor's trust in the screen - the same thing Gotcha 116 protects when it insists a
refresh control must never claim an update it cannot demonstrate, and Gotcha 125 protects when it
refuses to mark an on-demand key stale. A strip that cries stale on fresh data trains the eye past
the colour on the keys where it means something.

**Fix**: one `parseStamp()` helper now reports whether a stamp carried a time of day, and the
date-only branch is built at **local** midnight. `relative()` answers a date in whole days -
"today" / "yesterday" / "N days ago" - and never in hours, because **an hours-ago phrasing for a
value with no recorded time is inventing precision nobody has** (SPEC 21 rule 7). `daysAgo()`
counts whole calendar days for the same values. `dateTimeIst()` needed no change: its regex
already declines to match a date-only value and falls back to `shortDate`, which is the same
refusal one function earlier.

**Verification**: headless render of all five affected pages before and after, reading the strip
out of the DOM. Before - `Scores: 9 hours ago`, `History: 9 hours ago`, `Outcomes: 1 day ago`.
After - `Scores: today`, `History: today`, `Outcomes: today`, with `Prices: 7 min ago` unchanged
(it carries a real timestamp) and `Analyst targets: 1 day ago` correctly left alone, because that
one genuinely had not advanced.

**Prevention**: `relative()` had exactly one caller and no test, which is how a pure display
function that every screen depends on went unexamined. The general rule, and the reason this is
filed rather than quietly patched: **a value stored as a date must be reported as a date.** The
moment it is rendered in hours, something has assumed a time of day - and here the assumption was
not even midnight local, it was another timezone's midnight. Sibling of B-047 (a quarter filed as
a year) and B-060 (an RSI whose window could not reach its period): all three are a figure quoted
on a scale it was never measured on.


### B-118 - A ternary unboxed a null and took down the long-horizon panel  `[P2]`  `RESOLVED 2026-09-17`

Found in `logs/trading-app.log` during the same review - a 500 out of
`GET /api/fundamentals/long-horizon`, which is the SPEC 42 capital-allocation record and the
SPEC 43 compounding track record on the stock page.

**Root cause**: `CompoundingPersistence.roce()` chose its EBIT figure like this -

```java
Double ebit = r.getProfitBeforeTax() != null && r.getInterestCost() != null
        ? r.getProfitBeforeTax() + r.getInterestCost()
        : r.getOperatingProfit();
if (ebit == null) return null;
```

The true branch is `Double + Double`, which is a **primitive** `double`. Java's conditional
operator applies binary numeric promotion when one branch is primitive and the other boxed, so the
whole expression is typed `double` and `getOperatingProfit()` is **unboxed before it can be
tested**. The `if (ebit == null)` on the very next line - written precisely to handle this case -
is unreachable on that path. Any year carrying neither pair threw
`NullPointerException: Cannot invoke "java.lang.Double.doubleValue()"`.

**Blast radius**: a 500, not a degraded reading - so both long-horizon panels vanished for the
affected stocks rather than saying "not enough years". That inverts the discipline these panels
exist to uphold: SPEC 32.5 records that NSE's older archive filings often carry the profit and
loss account but not the balance sheet, and `returnPersistence` already has an unmeasured branch
with wording for exactly that gap. The code was written to report the absence and crashed on it
instead.

**Fix**: an explicit `if`/`else` assigning to `Double`, so both branches stay boxed and the
existing null check does the job it was written for. A comment records why it must not be folded
back into a ternary.

**Verification**: `CompoundingPersistenceTest.missingEbitFiguresAreUnmeasuredRatherThanAnException`
builds ten years with `profitBeforeTax` and `operatingProfit` both null, asserts `roce()` returns
null and that `analyse()` returns a verdict. Confirmed to pin the defect rather than merely pass -
reverted to the ternary and the test failed with the identical production stack trace
(`NullPointerException ... getOperatingProfit() is null`), then passed again on restore. Suite:
10/10 green.

**Prevention**: grepped the `fundamentals` package for the same shape - this was the only
instance; the neighbouring `borrowings` line uses `== null ? 0 :` on a primitive target, which is
safe and intentional. The rule worth carrying: **a ternary whose branches mix a primitive
arithmetic result with a boxed fallback silently unboxes the fallback**, so a null guard placed
after it never runs. Where a null is a meaningful outcome rather than an error - which is most of
this codebase (Gotcha 21, 44, 68) - assign it with an `if`/`else` and keep the declared type
boxed.


### B-117 - The analyst coverage panel called three covered holdings "no target on file"  `[P3]`  `RESOLVED 2026-09-17`

Found by rendering the new SPEC §49.14 panel against the live book before shipping it, not in
review and not by any check.

**Root cause**: the panel split holdings into `covered` (`analystHouses > 0`) and `uncovered`
(everything else), collapsing the two zero states that the whole feature exists to keep apart. A
summary tile then read **"Nothing on file — 9"** with the sub-line *"no target reached our feeds"*,
and the sentence beneath the table named all nine by ticker under **"No target on file:"**.

Three of those nine — NITINSPIN, MARKSANS and NATIONALUM — have targets on file. NATIONALUM
carries **ten**, from Emkay, ICICI Securities and Motilal Oswal. What is true of them is that
nothing is *running*: every call has resolved or been revised away. The panel stated the opposite,
in plain English, about a third of the group it named.

**Blast radius**: the screen built to answer *"who is watching the stocks I own"* would have told
the investor that three holdings are uncovered when the desks had covered them and gone quiet —
which is a signal in itself, and the more interesting of the two states. It would also have
silently deflated the ledger's apparent reach on exactly the screen used to judge it.

**Why it happened**: the cell and the column got this right — `analystCoverageCell` branches on
`analystHousesEver` and draws "None running" with the firms named. The *panel* was written after
and re-derived its own grouping from `analystHouses` alone. Two pieces of arithmetic over one
question, in one file, written twenty minutes apart: Gotcha 85's failure at the smallest possible
scale, and proof it does not need two teams or two screens to happen.

**Fix**: `quiet` (`houses == 0 && housesEver > 0`) and `never` (`houses == 0 && !housesEver`) are
separate groups. The tile is now *"No live target — 9"* with the sub-line *"6 never quoted, 3
covered before"*, and the two groups get their own sentences, the first saying what a desk going
quiet on a holding means.

**Verification**: headless render of `holdings.html#analysis` against the live book — the tile
reads `9 / 6 never quoted, 3 covered before`; *"Covered before, nothing running now: NITINSPIN,
MARKSANS, NATIONALUM"*; *"No target on file: LCCPROJECT, SKYGOLD, GNFC, SCI, RATHIST, GULPOLY"*.
0 skeleton elements, no console error.

**Prevention**: this is the same family as B-098 (a count rendered beside a list must be derived
from that list) and Gotcha 121 (not-measured and nothing-applies must never render alike). The
rule that would have caught it earlier: **when a renderer already distinguishes N states, a
summary over the same rows must distinguish the same N states** — deriving the summary from a
narrower predicate is how a carefully-kept distinction gets thrown away in the one place the
reader actually looks. Neither the syntax checker nor any test can see this; it took reading the
rendered sentence against the data.


### B-116 - One free-text field from NSE froze the whole IPO table for two days  `[P2]`  `RESOLVED 2026-09-16`

Found while checking why the dashboard looked stale. Almost everything was simply not due yet - the
screening runs at 14:00, holdings analysis at 15:15 - but `/api/dashboard/data-health` flagged the IPO
pipeline as one session behind, and a manual `POST /api/ipo/capture` returned **500**.

**Root cause**: `ipo_issues.issue_type` carries NSE's own description of the offer structure and was
declared `@Column(length = 32)`. Every value seen when the feature shipped fitted ("100% Book Building"
is 18, "Book Building" is 13). Then a **further public offer** entered the pipeline reading
*"100% Book Building ( Further Public Offer)"* - **42 characters** - and every capture from 2026-09-14
died on `value too long for type character varying(32)`.

**The second half is the one that matters.** `IpoTrackingService.capture` ends with a single
`repository.saveAll(touched.values())`, which is one transaction, so that one row rolled back **all
255** - together with ~4 minutes of paced NSE and Kite calls already spent on prices and listing
candles. A field nothing scores on, describing an issue the investor had not asked about, silently
discarded the entire day's IPO data. B-049 is the same shape (`queue()` outside the per-symbol `try`,
one unique-constraint collision discarding twenty minutes of completed scan), and it was not applied
here.

**Fix**: three parts, because the defect has three independent halves.
1. `issue_type` widened to 160, with an explicit `ALTER TABLE ... ALTER COLUMN ... TYPE` in a new
   `WIDEN_COLUMNS` block in `SchemaMigrationRunner` - `ddl-auto=update` adds columns but never alters
   an existing one's type (Gotcha 74), so changing the annotation alone would have done nothing.
2. The value is **truncated on write** (`fit(...)`). Widening alone just moves the cliff; a length
   chosen from today's feed is the assumption that failed in the first place.
3. `saveEachSeparately` replaces both `saveAll` calls and **returns the failure count**, which the
   capture note reports. One unstorable row now keeps its previously stored values and says so at
   WARN, naming what the reader will see; every other row in the run is kept.

**Verification**: `POST /api/ipo/capture` -> 200 in 240 s, "255 mainboard issues touched, 33 detail
pages read, 232 prices refreshed, 4 listing-day candles fetched", no failure clause. The stored
`issue_type` is now the full 42-character string. `/api/dashboard/data-health` moved the IPO pipeline
from WATCH to **OK (up to date 2026-09-16)** and reports 0 problems across 55 checks.

**Worth noting about the detection.** Nothing alerted. The scheduler caught the exception and carried
on, so the app looked healthy for two days; what surfaced it was the freshness strip showing a date
that had stopped moving, and the data-health screen saying which job owned that date (SPEC 44). That is
exactly what that screen was built for, and this is the first time it has paid.

### B-115 - The screener page timed out on every load  `[P2]`  `RESOLVED 2026-09-16`

The investor reported the dashboard showing *"The app is running but did not answer in time, so this
page is showing your last saved view. That usually means it is still warming up."* It was not warming
up. `GET /api/dashboard/screener` measured **31.7 s on a fresh JVM** and 19-27 s after, against the 8 s
`TIMEOUT_MS` in `static/js/api.js`, so `paintOffline(false, 'timeout')` fired on every load and the
page rendered stale content. `/api/dashboard/summary` sat at 5.4-8.9 s, straddling the same limit.

**Root cause**: `MultibaggerScoreRepository.findRecentForSymbols` selects every row for a symbol list
inside a date window, and all four of its callers used it to build a "newest row per symbol" map by
writing each row into a `Map` and letting the last write win. Correct, and enormously wasteful: the
macro reading on the screener asks for 276 symbols, `SymbolVariants.candidates` expands each to up to
four spellings (Gotcha 84), and `SCORE_LOOKBACK_DAYS` is **400**. Measured against the live table -
**27,845 rows, 408 symbols, 112 screening dates** - that is essentially the entire table hydrated into
103-column entities, to keep 408 of them. Stack sampling during a request put **11 of 12** in-request
samples inside `MacroExposureService.latestScores`.

**Fix**: `findLatestForSymbolsSince` asks the database the question the Java code was asking:

    WHERE (m.symbol, m.screeningDate) IN (
        SELECT m2.symbol, MAX(m2.screeningDate) FROM ... WHERE m2.symbol IN :symbols
        AND m2.screeningDate >= :fromDate GROUP BY m2.symbol)

The `(symbol, screeningDate)` unique constraint already on the table guarantees one row per symbol and
supplies the index; `EXPLAIN ANALYZE` reports **44 ms** with a 1,173-element IN list. Three callers with
identical semantics moved onto it: `MacroExposureService.latestScores`, `CompoundingLensService
.latestBySymbol`, `PortfolioPerformanceService`. **`MacroMeasurementService` deliberately did not**: it
keeps the newest row *with a positive price*, so an older row can legitimately answer where the newest
cannot, and narrowing it to one row per symbol would silently drop prices.

**Measured, fresh JVM, same data:**

| Endpoint | Before | After |
|---|---|---|
| `/api/dashboard/screener` (first call) | 31.7 s | 3.2 s |
| `/api/dashboard/screener` (warm) | 19-27 s | 0.83-1.0 s |
| `/api/dashboard/summary` | 5.4-8.9 s | 1.5-2.2 s |
| `/api/watchlist/items` | 3.0 s | 0.67 s |
| `/api/trading/holdings/buy-timing` | 3.0 s | 0.78 s |
| `/api/portfolio/performance` | 0.95 s | 0.23 s |

**Verification**: the full screener payload was captured before and after and compared field by field -
276 rows, 103 fields, **zero differences**, macro readings and compounding verdicts present on the same
rows as before. 805 tests pass.

**A process note worth keeping.** The first "after" numbers I took were wrong, and they were wrong in
the flattering direction. Two `./start-app.bat` runs appeared to succeed while the app on port 8080 was
still PID 30612 from 09:14 - the restarts never happened, the health check answered from the old
process, and 23 s -> 6.5 s -> 4.9 s was the *old* code warming up under JIT, which reads exactly like a
fix working. It was caught by checking the process start time against the edit time. **Before believing
a performance measurement, prove the process you measured contains the change** - a responding health
endpoint proves only that something is listening.

### B-114 - The app failed to start on two mornings and left no diagnostic trace  `[P2]`  `OPEN`

The investor reported the app was not starting. `TradingApp-Start` (09:00 MON-FRI) had
`LastTaskResult = 1` for 2026-09-16, and the 2026-09-15 session only begins at **10:14** - a manual
start hours after its scheduled one. `logs/trading-app.log` contains nothing at all for either
09:00: the script aborted before the JVM launched.

**Root cause of the blindness** (which is the part that is certain): `start-app.bat` threw away
every byte it produced. Steps 1 and 2 printed to a console that Task Scheduler discards, and Step 3
ran `start /B mvn spring-boot:run > nul 2>&1`. So a build failure, a port collision and a Spring
boot failure were all indistinguishable from each other and from a healthy run - exactly the silent
degradation this codebase documents repeatedly (B-054, Gotcha 94). Two consecutive failed mornings
produced zero evidence.

**Root cause of the failure itself**: not yet named. `exit /b 1` is reachable only from Step 1, so
`mvn clean compile` failed. The same command succeeded at 09:03 (BUILD SUCCESS, 58 s) and the task
itself succeeded on a manual trigger at 09:12 (`LastTaskResult = 0`, app up, 0 errors), so it is
intermittent rather than a broken environment - `mvn`, `JAVA_HOME` and disk are all fine, and the
task action, working directory and triggers are correct. Leading hypothesis, unproven: the VSCode
Java language server starts ~08:22 on both failing mornings and holds file handles on
`target\classes` while it indexes, so `mvn clean` cannot delete the directory; by 09:03 it has let
go. Sep-14, when the 09:00 task did work, would then be a morning the editor was not yet open.

**Blast radius**: a missed trading day of every scheduled job - the 14:00 screening, 15:18 holdings
analysis, 15:22 outcome measurement, 15:28 tax-lot capture, and all four report emails. The
freshness strip and `/api/dashboard/data-health` would show the gap the next day, but nothing
alerts at 09:00, so the app being down is only noticed by a human opening the dashboard.

**Fix shipped 2026-09-16** (partial - observability only): [start-app.bat](start-app.bat) logs its
build and port-kill phases to `logs/start-app.log` (appended, with a timestamp and `JAVA_HOME` per
run). **Corrected the same day**: the first cut wrapped the *whole* script in one redirect, and
`start` then handed that inherited handle down the chain cmd -> mvn -> the app JVM, which holds
`logs/start-app.log` open for as long as the app runs. The next run then died at the redirect with
"the process cannot access the file", printing nothing - the identical silent failure this logging
exists to prevent, now triggered on every restart. Proven directly: with the app up, `echo >> logs
\start-app.log` from a fresh shell fails, and it succeeds the moment the app is stopped. Each phase
now closes its own redirect, the launch runs outside all of them, a locked log falls back to
`start-app-alt-<n>.log` rather than aborting the start, and Step 2 clears the leftover Maven
launcher and cmd wrapper that outlive the app and hold `target\` (that filter is restricted to
`cmd.exe`/`java.exe`: a broader `CommandLine -like '*spring-boot:run*'` match also kills the shell
that typed the command, which it did to mine). The JVM console goes back to nul - capturing it needs
a second lockable file for little gain, since logback initialises early enough that boot failures
reach `trading-app.log`. The
`exit /b 1` on a failed `taskkill` inside the Step-2 `for` loop was also removed: `%ERRORLEVEL%`
there expands at parse time, before the loop body ever runs, so the check never tested what it
claimed to - and `netstat` legitimately lists one PID on several sockets, so the second `taskkill`
reports "process not found" on a perfectly healthy run (observed at 09:12).

**Verification**: triggered `TradingApp-Start` by hand at 09:12 - result 0, `logs/start-app.log`
captured the full Maven run and both `taskkill` lines, app reached `Started IntradayApplication`
with Tomcat on 8080 and 0 ERROR lines, `/api/dashboard/health` answered.

**Stays open** until a captured 09:00 failure names the cause. If the language-server hypothesis
holds, the fix is to retry the `clean` rather than abort on it.

### B-113 - The plausibility panel returned one verdict for every stock  `[P2]`  `RESOLVED 2026-09-14`

Found by running the new SPEC §49.13 panel over six live stocks before shipping it. Every one read
`BELOW_ITS_RECORD`.

**Root cause**: the ten-year growth requirement was compared against
`multibagger_scores.dcf_historical_growth_percent`, which is a **two-year** profit CAGR. Live
values: BRIGADE 37%, TECHM 32%, SONACOMS 47%, TITAN 63%, DIVISLAB 66%, GALAXYSURF **109%**. Those
are base effects off a depressed year, not records of compounding, and against them a requirement of
10-26% a year is always below the record. The inversion arithmetic was correct throughout — the
round-trip test passed — so nothing looked broken; the **benchmark** was the wrong measurement.

**Blast radius**: had it shipped, the one screen built to let the investor question an analyst's
target would have endorsed every target on the board, in confident plain English, while appearing to
have checked something. That is worse than not building it at all.

**Why it was caught**: zero variance across a handful of stocks is a known signature here —
Institutional Interest sat constant at 40 for three months, monthly RSI constant at 50 across the
whole universe (B-060), Insider Pulse produced a verdict for no stock at all (B-074). The rule from
Gotcha 106 is two questions asked mechanically: *measured on how many*, and *did it vary*.

**Fix**: the record now comes from `annual_fundamentals` — profit CAGR over the longest run of
annual accounts, minimum 4 years, with `yearsOfRecord` carried beside it. Fewer than 4 years is
`NO_RECORD_TO_COMPARE`, not a short record used anyway; a loss-making first or last year yields no
CAGR at all rather than a negative or absurd one.

**Verification**: over 45 good quoted stocks the verdict now distributes 16 / 11 / 11 / 5 / 2 across
the five values. GALAXYSURF reads 6.8% over 8 years where the two-year figure said 109%, and TITAN
(1 year on file) reports that it cannot compare rather than inventing one.
`AnalystTargetPlausibilityTest` pins the 4-year floor, the loss-base refusal, and that a bad year in
the middle is not smoothed away.

**Prevention**: a horizon mismatch (ten-year requirement, two-year benchmark) is the same family as
B-047 filing a quarter as a year and B-060's RSI window that could not reach its period. **Before
comparing two rates, check they are measured over comparable spans** — and run any new verdict over
a handful of real rows and look at the distribution before shipping it.

### B-112 - The overlap screen's explanation rendered unstyled, because both its CSS classes are undefined  `[P3]`  `RESOLVED 2026-09-14`

Found while adding the action block asked for in SPEC §49.12. `overlapNotes()` in
`page-accuracy.js` built its block as `div.callout` containing `ul.plain`. **Neither class exists
in `app.css`** — there is no `.callout` and no `.plain` anywhere in the one stylesheet this
dashboard has. The five paragraphs that exist to stop a reader concluding that a -0.32 correlation
means one of the two systems is broken therefore rendered as plain body text with default bullets,
visually indistinguishable from filler, directly under a KPI showing that -0.32.

**Blast radius**: no wrong number, and nothing missing from the DOM — which is exactly why it
survived a render check. The content was present and correct; only its visual weight was absent, on
the one block whose whole job is to be read *before* the number above it. This is Gotcha 104 (a CSS
*variable* that does not exist fails silently with the attribute present) one level up: a class name
has the same failure mode, and neither `check_js_syntax.py` nor a DOM dump can see it.

**Fix**: `div.info-box` — the SPEC §21 "what this means" box, and the class that actually
exists — with a plain `ul`. Verified with `getComputedStyle` on the rendered page rather than by
reading the class attribute back (the B-070 rule).

**Prevention**: grep `app.css` for a class name before using it, the same way Gotcha 104 requires
for a variable. The palette of prose containers is small: `.info-box`, `.card`, `.alert`,
`.cost-note`, `.empty`.

### B-100 - A live-looking OpenAI API key was committed as a config default  `[P0]`  `OPEN - NEEDS THE INVESTOR 2026-09-12`

Found while wiring Spring AI for SPEC §48. `application.yml` carried
`ai.api-key: ${AI_API_KEY:sk-proj-...}` — a real-shaped key as the **default** of the environment
placeholder, present since the baseline commit. Anyone with read access to the repository, at any
point in its history, has the key.

**Blast radius**: not an analysis defect. It is a credential leak, and the exposure is billing and
quota on the investor's own provider account.

**Fix (partial).** The default is now empty (`${AI_API_KEY:}`), so the working tree is clean and a
fresh clone leaks nothing. **The key remains in git history**, which cannot be rewritten safely
here, so the only real remedy is for the investor to **rotate it at the provider**. Until that is
done this entry stays open. Scrubbing history afterwards is optional; rotation is not.

---

### B-101 - The app emailed intraday trading instructions to a long-term investor every 15 minutes  `[P1]`  `RESOLVED 2026-09-12`

`MarketImpactNewsService.buildAlertContent` sent, on every 15-minute scan that matched a keyword:
*"Consider hedging positions… Adjust stop-losses… Check option chain for hedging opportunities."*
There was also a 15:20 daily summary of the same material.

This is a direct SPEC §19 violation — a feature whose output is an instruction to transact now, on
a platform whose stated mission is 5–10 year compounding — and it survived the 2026-09-03 removal
pass that deleted two neighbouring features for less. It kept running because nothing read its
output: the classifier's stock matching used a hard-coded 46-name regex never joined to `holdings`,
so the alerts named stocks the investor did not own.

**Blast radius**: the investor's inbox and, worse, their behaviour — the one thing this app exists
to prevent. No score was affected.

**Fix.** Both emails and their HTML builders deleted, along with `extractSymbols`, the stock regex
and `analyzeSentiment`. What survives is headline **capture only**: `scanOnce()` stores what
`MacroKeywordExtractor.touchesAnyFactor` recognises, and the digests in the 09:30 and 15:18 emails
(SPEC §48.10) are the only place events now reach the reader.

---

### B-102 - The market-impact news config block was read by nothing  `[P2]`  `RESOLVED 2026-09-12`

`trading.market-impact-news.*` sat in `application.yml` with no `@ConfigurationProperties` class
behind it. Every value in it — including a declared retention — was decoration: editing the yml
changed nothing, which is the most misleading state a config key can be in.

**Fix.** `MarketImpactNewsConfig` binds `enabled` and `max-lookback-hours`, and both are read.

---

### B-103 - Two tables declared a retention and had no cleanup arm  `[P2]`  `RESOLVED 2026-09-12`

`market_impact_news` declared 30 days in the yml and was never pruned; `macro_events` was about to
ship in the same state.

**Fix.** `DataCleanupConfig` gains `marketImpactNewsRetentionDays` (30) and
`macroEventsRetentionDays` (730), and `DataCleanupScheduler` gains an arm for each with its own
stats entry. The macro retention is deliberately long — an event's whole purpose is to be checked
against a 365-day outcome, so pruning at a year would delete the evidence the day before it
matured.

---

### B-104 - SPEC and CLAUDE.md both said the news scanner used Zerodha Pulse. It never did.  `[P2]`  `RESOLVED 2026-09-12`

§39.4/§39.5 and CLAUDE.md recorded Zerodha Pulse as the news source kept alive through the removal
pass. `MarketImpactNewsService` injects `MultiSourceNewsFetcher` (four RSS feeds);
`ZerodhaPulseNewsService` had exactly one caller, a diagnostic endpoint on `TradingController`.

The documentation was load-bearing in the wrong direction: it justified keeping an orphan.

**Fix.** `ZerodhaPulseNewsService`, `PulseNewsDTO` and the diagnostic block deleted; the SPEC and
CLAUDE.md claims corrected to name the real fetcher.

---

### B-105 - Overnight news was invisible, and one story from four feeds counted as four  `[P2]`  `RESOLVED 2026-09-12`

Two defects in one method. The scan window was a hard-coded **two hours**, so at the 09:15 fire
nothing published overnight — which is when policy decisions, US data and most geopolitical news
land for an Indian reader — was ever stored. And dedup hashed the title verbatim, while the four
feeds render the same story as `"RBI hikes repo rate"`, `"RBI hikes repo rate - Economic Times"`
and so on, so one event arrived as several rows.

**Blast radius**: with §48 on top, both would reach the investor — the first as a permanently quiet
macro ledger, the second as an event whose apparent corroboration was four renderings of one wire
story.

**Fix.** The window runs from the newest stored headline, capped at `max-lookback-hours` (36), so a
restart or a quiet weekend cannot open a gap. `hashTitle` strips a trailing `" - Publisher"`,
lower-cases and drops punctuation before hashing. Pinned by `MarketImpactNewsDedupTest`.

---

### B-106 - `truncate()` stripped every non-ASCII character from stored headlines  `[P3]`  `RESOLVED 2026-09-12`

The same class of defect as B-085: a headline containing `₹` or a typographic quote lost those
characters on the way into the database, so `"₹2,000 crore"` stored as `"2,000 crore"`.

**Fix.** `truncate` is now code-point aware and only shortens.

---

### B-107 - The keyword reader filed an opinion column as a rate rise that had not happened  `[P2]`  `RESOLVED 2026-09-12`

Found on the **first live ingest**, not in review. Of six events extracted on 2026-09-12, one came
from *"Why a rate hike could actually be bullish"* — a columnist's argument — and was recorded as
`INTEREST_RATES UP MODERATE`. Five holdings read a headwind off it (ABCAPITAL, STARHEALTH, TMPV,
SKYGOLD) and one a tailwind (HDFCBANK), and **136 readings were filed for measurement** on a ledger
containing it.

`HYPOTHETICAL` already rejected *"may hike"*, *"could cut"* and *"expected to"* — the **verb**
forms. It had no reach into the **noun** form, where the hedge word follows the event: *"a rate
hike could…"*.

**Blast radius**: the macro reading on every stock exposed to the factor, and — more seriously —
the accuracy record, because a filed reading is never retracted (SPEC §48.7). Caught on day one,
before any outcome matured.

**Fix.** Two narrow guards. `HYPOTHETICAL` gains `(hike|cut|rise|fall)s? (would|could|might|may)`.
`OPINION_LEDE` rejects a headline that **opens** by asking a question (`why `, `should `,
`what if`, `is it time`, `how to`, `explained:`, `opinion:`, `analysis:`, `view:`) — anchored at the
start deliberately, so an ordinary report containing "why" further along is untouched.

The asymmetry is the justification and is written into the code comment: being wrong this way costs
**one missed event**, which the next ingest picks up from a straight report of it; being wrong the
other way files a reading against hundreds of stocks on something that never happened. Pinned by
two cases in `MacroKeywordExtractorTest` — the real headline, and a report that must still be read.

**Not yet done**: the 136 contaminated readings from that first ingest are still in
`recommendations`. Nothing has matured (0 outcome rows), so deleting them costs no history, but the
purge was blocked by a safety classifier and needs the investor's go-ahead.

---

### B-099 - The discovery page told the reader that institutional block deals were "gifts and pledges", and B-098's two fixes stopped at the screener  `[P2]`  `RESOLVED 2026-09-09`

Found during the 2026-09-09 expert review of `discovery.html`. Three defects, one page, all of
them a fix that had been applied one screen over and not here.

**(1) A false statement about the most-followed feed in the market.**
`InsiderDisclosureService.recordBulkBlock` stores exchange bulk and block deals with
`personCategory = "INSTITUTION"`, `mode = "BULK_DEAL"` and `personName` set to the actual buyer.
`isSignalRow` in `page-discovery.js` rejects both fields — correctly, they are not insider trades
— but they then fell into the `noise` count, and the footer stated that those filings "were share
grants, pledges, gifts or promoter-to-promoter transfers". A named fund taking a 1–2% block of a
small cap is close to the opposite of a pledge. The empty-state message carried the same claim
about *all* captured filings. **Blast radius**: no score, and no email — the discovery page only.
But it is a factual misstatement to the reader about the one feed here that has never gone quiet
(the PIT feed died for four months undetected, B-089), and it made the noise count wrong.
**Measured after the fix, on a 45-day window: 2,202 of 2,618 captured filings are bulk or block
deals** — 84% of the feed, against 63 genuine open-market insider trades. The sentence was wrong
about the overwhelming majority of what it described, not about an edge case.

**(2) B-098's placeholder-as-classification defect, still live.** The page rendered `r.industry`
verbatim, which is the 89-entry hand map that defaults to the literal `"Other"` — measured **189
of 284 rows** on the 2026-09-09 run, while the shared `sector` key classified **284 of 284**.
B-098 fixed exactly this on the screener the same day and the discovery page was not touched, so
the Gotcha 110 rule (a placeholder is unclassified, never a bucket) was being broken on a live
screen.

**(3) B-098's undrawn-business-numbers defect, still live.** `DashboardService.withBuyTiming`
builds an ~85-field map per stock and its javadoc says it is shaped that way "for the screener
**and discovery**". Discovery rendered **ten** of those fields. ROCE, debt-to-equity, cash
conversion, the five-gate compounding verdict, forensic flags, promoter holding, FII/DII holding,
`turnaroundVerdict`, `capexVerdict`, the DCF verdict and the 30-day move relative to the universe
were all in the browser and undrawn, while the table's only business fact was a composite that is
59% price behaviour by weight.

**Also fixed here (not a defect, a duplicate engine).** The Recent Listings section ran
`/api/universe/ipo-watch` — Kite-heavy, measured at 6 minutes, behind a button, answering with a
boolean `setupPresent` — while SPEC §45 had shipped `/api/ipo/recent` the same day: DB-only, and
answering the same question in a four-value cycle-stage vocabulary with the lock-in calendar
behind it. Two rule tables for one question in one vocabulary is Gotcha 85's failure, so the
newer surface wins.

**Fix.** (1) Bulk/block deals are counted and described separately from true noise in both the
footer and the empty state; the lane itself was deliberately not built, so the wording says they
are captured and kept but not shown here rather than claiming otherwise. (2) `sectorCell`
replaces the raw `industry` render. (3) The screener's own renderers are reused verbatim —
`roceCell`, `leverageCell`, `growthCell`, `redFlagsCell`, `finQualityCell`, `valuationCell`,
`marketCapCell`, `sectorCell`, `ownedLine` from `fundamentals-cells.js` and `compoundingCell` from
`compounding.js` — with **the screener's column labels character for character**, because a column
reading "ROCE" on one screen and "Return on capital" on the next invites the reader to ask whether
they are the same measurement. A divergence callout names the rows where the composite and the
compounding gate disagree, in both directions (Gotcha 105's pattern — the vocabularies stay
distinct and the gap is explained, not reconciled). `ipoSection` reads `/api/ipo/recent` on page
load, and the now-uncalled `/api/universe/ipo-watch` entry was removed from `SLOW_BUT_SAFE` in
`api.js`; `IpoWatchService` is a removal candidate, left in place.

**Files**: [page-discovery.js](src/main/resources/static/js/page-discovery.js),
[api.js](src/main/resources/static/js/api.js#L255).

**Verification.** `python scripts/check_js_syntax.py` clean (24 modules). Rendered
`discovery.html` in headless Chrome against the live app: all six sections present, no console
errors, `industry`-sourced "Other" gone. Measured with `getComputedStyle` in a same-origin iframe
— the divergence callout resolves to `borderLeft 3px rgb(25,118,210)`, `background
rgb(227,242,253)` (Gotcha 90/104: a `style` attribute present in the DOM is not a style that
applied, and there is no `--accent`). Table overflow measured at 1440px: the 16-column table
reports `scrollWidth 1329` against `clientWidth 1329`, no horizontal scroll on any of the six
tables. Screenshot inspected as well as measured (Gotcha 89) — which is what caught the
`RETURN ON CAPITAL` header wrapping to three lines and setting that column's shape, fixed by
adopting the screener's shorter labels.

**Not a defect, checked and cleared.** `yoyProfitGrowth`, `yoyRevenueGrowth`,
`earningsGrowthVerdict`, `promoterHoldingPct` and the FII/DII holding levels read null on all 284
rows of the 2026-09-09 run, which has the exact signature of Gotcha 74 (a declared column Hibernate
never added). It is not that: those columns are B-098's, added the same day, and the app restarted
at 20:30 — after the 14:00 screening. `SchemaMigrationRunner.ENSURE_COLUMNS` already carries all
seven with an idempotent `ADD COLUMN IF NOT EXISTS`, and the 22:25 boot logged no error, so they
populate on the next run. The Growth column will read "not measured" on every row until then, and
the lane's coverage line states that figure rather than letting it look broken.

### B-098 - The screener's Industry column read "Other" for ~two-thirds of the universe, and the business numbers it received were never drawn  `[P2]`  `RESOLVED 2026-09-09`

Found during the 2026-09-09 review of `screener.html` against SPEC §1. Two defects, one page.

**(1) A placeholder presented as a classification.** `screenStock()` set
`sector = STOCK_SECTOR_MAP.getOrDefault(symbol, "Other")` from an 89-entry hand-kept map against a
~370-stock universe, and the page rendered `industry` verbatim — so "Other" appeared as the
sector of most rows, there was no sector filter, and sector diversification (the primary
portfolio-construction lens) could not be applied on the one screen that lists the universe. The
row never passed through `SectorMapping.resolve`, so a stock could be grouped one way on the
screener and another on the portfolio (the B-096 rule, un-applied). A second, divergent copy of
the map in `MultibaggerReportService` grouped the weekly email by yet another table.

**(2) Forty-eight fields sent and never drawn.** Each screener row is the whole
`MultibaggerScoreEntity`; ROCE, ROE, debt-to-equity, cash conversion, the DCF verdict and
expectation gap, P/E deviation, forensic flags, promoter pledge, market cap in crore and the
financial-quality verdict all arrived on every row and the table showed seven score bars instead —
four of them the price dimensions B-023 records as collinear (r 0.72–0.79) with near-zero IC.
Growth (`yoyRevenueGrowth`, `yoyProfitGrowth`, `earningsGrowthVerdict`) and the promoter/FII/DII
holding levels were computed on every run, consumed by the bonuses and **discarded** — the Growth
pillar and promoter skin-in-the-game had no column at all. **Blast radius**: the screener page
only; no score, weight or email changed. But it is the page the investor picks businesses from,
and it was a momentum screener wearing a compounder's badge.

**Fix.** (1) `universe-sectors.csv` on the classpath, seeded offline from NSE's index constituent
lists (Nifty Total Market + Microcap 250, 755 symbols with an `Industry` column) — no runtime
download, no scheduler; `UniverseSectors` reads it once and refines banks out of "Financial
Services" by name; `sectorFor()` keeps the hand map as override and falls back to the file; unknown
is **null**, never "Other". `DashboardService.screener()` attaches `sector` via
`SectorMapping.resolve`, the second map is deleted, and the page gains sector chips + search.
(2) Seven nullable columns persisted from values the run already holds (zero extra NSE calls):
`earnings_growth_verdict`, `yoy_revenue_growth`, `yoy_profit_growth`, `promoter_holding_pct`,
`promoter_holding_change_pct`, `fii_holding_pct`, `dii_holding_pct`, with `ENSURE_COLUMNS` entries
(Gotcha 74) and coverage rows (§38.2). The default table now shows Fin. quality, ROCE (ROA for a
lender), Debt/equity, Profit growth, Promoter holding, Valuation, Red flags, Market cap and Sector;
the seven dimension bars sit behind a "Show the seven scores" chip; Rank, Grade and Size fold into
the Score and Market-cap cells; the Score cell carries the 30-day change **relative to the
universe shift** (B-064, via the shared `HoldingsDecayService.medianShift`). The headline panel is
"Compounders at a fair price" with its funnel counts, and `ScreenerTimingVerdict.Result` now
carries the 52-week range position it used to compute and discard.
Files: [UniverseSectors.java](src/main/java/com/example/trading/multibagger/UniverseSectors.java),
[universe-sectors.csv](src/main/resources/universe-sectors.csv),
[MultibaggerScreenerService.java](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java),
[MultibaggerScoreEntity.java](src/main/java/com/example/trading/persistence/MultibaggerScoreEntity.java),
[SchemaMigrationRunner.java](src/main/java/com/example/trading/persistence/SchemaMigrationRunner.java),
[DashboardService.java](src/main/java/com/example/trading/dashboard/DashboardService.java),
[SectorMapping.java](src/main/java/com/example/trading/portfolio/SectorMapping.java),
[ScreenerTimingVerdict.java](src/main/java/com/example/trading/multibagger/ScreenerTimingVerdict.java),
[fundamentals-cells.js](src/main/resources/static/js/fundamentals-cells.js),
[page-screener.js](src/main/resources/static/js/page-screener.js). Pinned by
`UniverseSectorsTest`, `ScreenerSurfaceContractTest`, `ScreenerScoreChangeTest`.

**Verification.** After restart: `GET /api/dashboard/screener` rows carry `sector` (no "Other"
anywhere), `scoreDelta30dRelative`, `rangePosition52w`; the seven new columns exist in
`information_schema.columns` and fill on the next 14:00 run; `screener.html` measured headless at
1366 / 1600 / 1920 px (see SPEC §27.10 for the re-measured floor).

### B-096 - Three sector vocabularies matched case-sensitively, so every allocation target read 0% and 42% of the book was "Other"  `[P1]`  `RESOLVED 2026-09-09`

Found by reading the live drift table during the 2026-09-09 portfolio-page review. **Every**
sector target read *actual 0%, Over Tolerance* and **every** real sector read *No Target*; the
metals overweight the table exists to flag (HINDALCO, NATIONALUM, NMDC: 16.7% against a 10% target)
was invisible, and `GENERAL` + `Other` held 42% of the money.

**Cause.** Three vocabularies for one thing, matched exactly and case-sensitively in
`SectorMapping.normalize`: the profile seed writes `IT / BANKING / METALS`; `holdings.industry` is
written from `StockValuationService.getIndustryForSymbol`, a ~20-name table emitting `BANKS`,
`REFINERIES` and, for everything else, `"GENERAL"`; and the screener's `STOCK_SECTOR_MAP` spells the
same sectors `Metals / Banking / Energy / Power`. The normaliser's table only knew NSE's long names
(`"Private Sector Bank"`), which no holding actually carried. `computeActualWeights()` also read
`findAll()` rather than `findActive()` (Gotcha 16). **Blast radius**: the drift table, the sector
donut, the sector-returns chart, the rebalance proposal and the dividend reinvestment suggestions
all inherited it; the market-cap buckets — the only ones that worked — were filtered out of the
panel behind the broken sector rows.

**Fix.** `SectorMapping` now matches case-insensitively over an alias table covering every spelling
the codebase emits, treats `GENERAL / Other / blank` as **unclassified** (reported by weight and
names in `DriftResponse.unclassifiedWeightPercent/unclassifiedSymbols`, never bucketed), and
`resolve(symbol, industry)` falls back to `MultibaggerScreenerService.sectorFor(symbol)` when the
industry is a placeholder. Target keys are normalised through the same table. The valuation fallback
consults the screener's table before defaulting to `GENERAL`. The decorator attaches the resolved
`sector` to every holdings row so every screen groups the same way. Drift panel shows sector *and*
market-cap groups with a coverage warning.
Files: [SectorMapping.java](src/main/java/com/example/trading/portfolio/SectorMapping.java),
[AllocationService.java](src/main/java/com/example/trading/portfolio/AllocationService.java),
[AllocationDto.java](src/main/java/com/example/trading/portfolio/AllocationDto.java),
[MultibaggerScreenerService.java](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java) (`sectorFor`),
[StockValuationService.java](src/main/java/com/example/trading/holdings/StockValuationService.java),
[page-holdings.js](src/main/resources/static/js/page-holdings.js). Pinned by `SectorMappingTest`.

**Verification.** After restart, `GET /api/portfolio/drift` on the live book: see the session recap
(metals target row carries its real actual; unclassified weight named).

### B-097 - 45 of 46 theses were written by the app and counted as "thesis intact"  `[P2]`  `RESOLVED 2026-09-09`

Found in the same review. The conviction panel read *42 intact, 2 under review, 0 broken* over
46 rows — for 29 holdings. 45 of the 46 theses began "Selected via app's …", every horizon was the
24-month seed, 17 rows were for stocks already sold, and the one real thesis (HINDALCO) was filed
under `NSE:` while the holding is `BSE:`, so the panel also listed HINDALCO as "missing a thesis".

**Cause.** `ConvictionService.getReport()` reported every row in the table, counted a generated
thesis as a thesis, and matched holdings to records by exact symbol. A default is not a statement
(Gotcha 68, B-057) — the same fault B-057 fixed for the horizon, on the text beside it.
**Blast radius**: the "why you own what you own" panel and the portfolio snapshot email both
presented the app agreeing with itself as the investor's conviction record; the core-holding gate
G7 reads the same table.

**Fix.** `holding_conviction.thesis_stated` (ENSURE_COLUMNS, Gotcha 74): the investor path sets
true, the seeder sets false and never downgrades a stated one; a legacy null is derived from the
seeder's text prefix and the seeded-horizon marker (`ConvictionService.isStated`). The report is
over **active** holdings joined across exchange prefixes; intact/review/broken count **stated**
theses only; generated ones are reported as `seededCount` / "waiting for your reason". The page
gained a thesis editor (`PUT /api/portfolio/conviction`, SPEC §46.7).
Files: [ConvictionService.java](src/main/java/com/example/trading/portfolio/conviction/ConvictionService.java),
[ConvictionDto.java](src/main/java/com/example/trading/portfolio/conviction/ConvictionDto.java),
[HoldingConvictionEntity.java](src/main/java/com/example/trading/portfolio/conviction/HoldingConvictionEntity.java),
[SchemaMigrationRunner.java](src/main/java/com/example/trading/persistence/SchemaMigrationRunner.java).
Pinned by `ConvictionStatedTest`.

**Verification.** `GET /api/portfolio/conviction` after restart: `totalHoldings` equals the active
count, `statedCount` 1, `seededCount` + `holdingsMissingThesis` cover the rest, HINDALCO resolved.

### B-093 - A boot-time DNS outage cost the whole trading day, and six auth paths turned a failed login into a silent success  `[P2]`  `RESOLVED 2026-09-09`

Found by reading the startup errors from the 09:02 launch on 2026-09-09. The **trigger** was
environmental and not a defect: the Windows scheduled task starts the app at 09:00, before the
machine's network was up, so `kite.zerodha.com` did not resolve (`Failed to resolve 'kite.zerodha.com'
[A(1), AAAA(28)]`) and `smtp.gmail.com:587` refused connection. DNS resolves normally now, and the
restart at 10:41 logged in cleanly on the first attempt. What the outage **exposed** is three real
defects in the auth path.

**Cause 1 - the failure was reported twice, the second time as a raw stack trace.**
`TokenManagementService.refreshAccessToken` logged a readable message in `doOnError` and then called
`.subscribe()` with **no error consumer**, so the same error reached Reactor's default handler and
dumped `Operator called default onErrorDropped` plus a 40-frame stack trace at ERROR immediately
after. Any log review reads that as a second, unexplained failure.

**Cause 2 - six auth paths complete *empty* instead of erroring, which reads as a successful login
holding a null token.** `response.bodyToMono(...)` completes empty when the body is empty, so the
`flatMap`/`map` that was supposed to raise the error never runs and **nothing at all is signalled**.
Affected: login step 1's error branch and its success branch, the TOTP step-2 error branch,
`followRedirects`' 2xx branch and its fall-through (which also catches a 3xx carrying no `Location`
header - and a redirect body is normally empty, so this one never fired), and
`exchangeRequestToken`. Consequences: `retryWhen` sits upstream of nothing it can see, so **no retry
fires**; and `doOnSuccess(token -> token.substring(...))` is then invoked with `token == null`,
turning a clean auth failure into an NPE reported under the wrong cause. This is Gotcha 15/52's rule
- a failure path must never degrade silently into a null a caller scores as normal - in the one
place a null costs the whole day's broker access.

**Cause 3 - `loginInProgress` could latch true for the life of the JVM.** It was reset inside
`doOnSuccess` and `doOnError` only. On the empty-completion path neither necessarily completes
normally, and a cancelled subscription resets nothing; the flag then rejects every later refresh
with *"Login already in progress"* and the app never re-authenticates.

**Fix** ([TokenManagementService.java](src/main/java/com/example/trading/broker/kite/TokenManagementService.java),
[KiteAuthService.java](src/main/java/com/example/trading/broker/kite/KiteAuthService.java)):
`switchIfEmpty` upstream of `retryWhen` turns an empty completion into a retried error;
`defaultIfEmpty` on each error-body read guarantees the intended exception is actually raised;
`doFinally` replaces the two flag resets; and `subscribe` now takes both consumers so the readable
message is the only thing logged. No behaviour changes on the success path.

**Blast radius**: broker authentication. With no token, `syncHoldingsFromBroker` fails
(`Retries exhausted: 5/5`, observed 09:21 and 10:01), every Kite-backed price lookup returns `0.0`
(Gotcha 22) and the holdings, exit-timing and target-hit outputs run on stale data - silently,
because the token failure and the downstream damage are logged in unrelated places.

**Verification** (2026-09-09): `mvn compile` clean; `mvn test` 556/556 pass; the running instance
(PID 30292) completed the full login flow with zero ERROR or WARN lines from the auth path.

**Still open - not a code defect, needs a decision.** After the startup login fails, the only other
attempt is the 09:45 `dailyTokenRefresh` cron. On 2026-09-09 DNS was still down at 09:46, so that
failed too and **there was no further recovery** - the app held an invalid token until the investor
restarted it manually at 10:41. Closing that gap means either a bounded retry scheduler or a lazy
re-login on first use, both of which are SPEC §3.4 / §20 changes. Recorded here rather than fixed
unilaterally.

### B-092 - Every lender read "not measured", and a bonus, split and merger read as dilution  `[P2]`  `RESOLVED 2026-09-08`

Found by the investor asking why the portfolio's **Compounds?** column said *not measured* on 11 of
31 holdings. Three unrelated causes sat behind one word, which is itself the finding - the column
could not distinguish them.

**Cause 1 - a whole business class was unjudgeable.** The compounding lens (SPEC §41) needs 3
applicable gates. Three of the five never apply to a lender: leverage and gross margin by design,
and cash conversion because a bank's operating cash flow tracks deposits and lending rather than
earnings quality - it is published for only **4 of 24 financials** anyway. So every financial in the
universe sat at 2 applicable against a floor of 3 and read NOT_MEASURED **permanently**. HDFCBANK
passed both checks it had (ROA 1.5%, consistency 60) and was still reported as unmeasurable. Fixed
with the investor's decision: cash conversion is now `NOT_APPLICABLE` for lenders rather than
missing, the floor for a financial is 2, and the verdict text says the reading rests on two checks
rather than four - the thinner basis is stated, not hidden behind an identical badge.

**Cause 2 - the lens could not see accounts it already had.** The gates read the screening row,
whose balance-sheet fields come from the annual XBRL fetched live during screening. That fetch
returns nothing for a sizeable minority of stocks while the same figures sit in
`annual_fundamentals`, written from NSE's filing archive by the §32.6 backfill - a different
endpoint that succeeds where the other does not. `CompoundingLensService` now fills gaps from the
latest stored year using §12.8's formulas unchanged. It fills only gaps: a screening-row figure
always wins, so this can turn NOT_MEASURED into a verdict and can never change one that existed.

> **Regression caught during verification, and worth recording.** The first cut selected the
> symbol spelling by the post-fallback result, which let an empty BSE row acquire figures and
> out-rank the full NSE one - **re-creating B-088 exactly**. Measured: NTPC fell PARTIAL to
> NOT_MEASURED, NATIONALUM COMPOUNDER to PARTIAL, ABCAPITAL NO to NOT_MEASURED. The rule is now
> explicit: the fallback fills gaps in the row we chose, it never chooses the row.

**Cause 3 - `DILUTION:HIGH` on three real corporate actions.** Surfacing cause 1 revealed it: with
the floor fixed, HDFCBANK reached the disqualifier check and read **NO** - "a serious question about
the accounts". Its share count went 272 to 548 crore (2019 1:2 split), 558 to 760 (the HDFC Ltd
merger) and 760 to 1539 (the 2025 1:1 bonus). Three shareholder-neutral events, each landing just
off an exact ratio because ESOPs were issued in the same year, so the deliberately tight 0.05%
tolerance (Gotcha 86) could not divide them out - and equity is null for 2019-2023, so the
bonus-vs-raise discriminator could not check. B-066 applied the "cannot tell is not the same as not
a bonus" rule to the branch where the adjustment brings growth under the bar, and **not** to the
branch where growth survives, which fell straight through to the flag.

**Fix**: severity now follows the strength of the evidence, not the size of the number. `HIGH` says
*new money was raised and your stake was watered down* and is only sayable when equity vouches for
it - HIGH disqualifies a stock outright (Gotcha 77) and caps the composite at 54, so it must not
rest on an absence. Where net worth is not on file for enough years the finding is still reported,
as `MEDIUM` with the caveat in its own message. Suppressing the flag entirely was tried and
rejected: it would have silenced genuine serial dilution for every stock without equity data.

**Verification** (2026-09-08): re-screened, HDFCBANK and BANKINDIA now carry `DILUTION:MEDIUM`.
Across the portfolio the column went from **11 of 31 not measured to 6** - HDFCBANK and GULPOLY to
COMPOUNDER, SKYGOLD and NITINSPIN from no reading at all to PARTIAL, BANKINDIA to a real NO. The
remaining 6 (EMCURE, STARHEALTH, RATHIST, PREMIERENE, WAAREEENER, VMM) are recent listings for which
NSE's archive returns `NO_DATA`; "not measured" is the correct answer there and no code change can
alter it. Pinned by 5 new `CompoundingQualityTest` cases and 2 in `ForensicScreenTest`; 556 tests
pass.

**Also fixed alongside**: `FundamentalsBackfillService.nextSymbols` ordered by depth then
alphabetically, so the queue was walked essentially A-to-Z and a holding could wait a fortnight
behind the universe while the portfolio page - the one screen opened daily - read "not measured".
Holdings now lead the queue.

---

### B-089 - NSE's insider feed moved to a new endpoint and the old one kept answering, so Insider Pulse was unmeasurable for four months  `[P1]`  `RESOLVED 2026-09-08`

**Symptom**: `/api/dashboard/data-health` reported `InsiderPulseScore` and `InsiderPulseVerdict` at
**0 of 290 stocks** and, per its `KNOWN_ZERO` expiry rule, escalated it to a PROBLEM saying the
B-074 fix "did not take". The B-074 fix had taken. The cause was upstream and four months old.

**Root cause**: NSE circular `NSE/CML/2026/11` (04 May 2026) moved SEBI PIT Reg 7(2)/7(3) onto the
single filing system through API-based integration between exchanges, **effective 05 May 2026**.
Filings have been published as XBRL under `corporates-pit-gg` ever since - the instance documents
are stamped `PIT V2.0 (30-04-2026)`. The old `corporates-pit` endpoint kept returning HTTP 200 with
its correct pre-May **archive**, so every component reported success while the newest filing in the
database aged past the 90-day pulse window. Same shape as B-017 / Gotcha 72: what died was the
*feed*, not the *history*.

**Measured before the fix**: across all 290 screened stocks, **zero** PIT rows fell inside the
90-day window, though 84 symbols held PIT rows and 887 of those were open-market trades - all older
than the window. Newest insider filing anywhere: `LALPATHLAB 2026-04-30`. Confirmed independently of
the app on a fresh NSE cookie jar: `corporates-pit?symbol=RELIANCE` with `from_date=01-01-2026&
to_date=28-02-2026` returned 4 rows, the identical query for `01-05-2026..08-09-2026` returned 0,
and the newest `intimDt` across 8 probed symbols was `01-May-2026`.

**Blast radius**: the Insider Pulse verdict and score (SPEC §28) were null for every stock from
~2026-06-01. Insider Pulse is shadow-mode (`insider-pulse-actionable: false`), so **no composite was
distorted** - the cost was a dead signal presented as a live one, an IC that would have read "no
signal" indefinitely, and 80 wasted NSE calls a day re-fetching a frozen archive.

**Fix**: ingestion repointed at the PIT V2.0 all-market filing index.
`NseDataService.fetchPitFilingIndex()` reads `corporates-pit-gg?index=equities` (one call, the whole
market) and `fetchPitFilingDisclosures(xmlUrl)` parses each filing's XBRL namespace-unaware,
grouping facts by `contextRef` and treating a context as a transaction when it carries a fact only a
transaction has - rather than hard-coding the `MainI` / `Disclosure1` ids, which are a convention and
not a schema guarantee. The existing `normaliseMode` / `normaliseCategory` / `normaliseType` are
reused because the XBRL spells the same concepts the same way, so one vocabulary covers both eras
and a mode filter fixed once stays fixed in both. The row hash is unchanged, which lets the pre-May
archive and post-May feed share a table without double-counting. Filings are keyed on `appId`
(unique across 2,381 rows), stored on `insider_disclosures.filing_app_id` with a
`SchemaMigrationRunner` entry per Gotcha 74, so each XBRL is downloaded once ever.

**Bonus recovered**: the XBRL states `SecuritiesAcquiredOrDisposedValueOfSecurity` in rupees, which
the old JSON usually omitted - so the size-based half of the verdict runs instead of falling back to
event counts. The two windows also abut exactly (archive ends 2026-05-01, feed begins 2026-05-03),
so the combined record has no gap.

**The check that was missing, now added**: `/api/dashboard/data-health` gained an insider-feed
staleness check keyed on the newest **PIT-sourced** row. It must be PIT-specific: bulk and block
deals arrived every trading day throughout the outage, so a table-level freshness check would have
read "up to date" on every single day of it - **a tripwire watching an aggregate cannot see one
contributing feed die**. WATCH at 7 days of silence, PROBLEM at 21, both well past any holiday
cluster; on the real outage it would have escalated in the last week of May. The capture also now
logs the newest filing date across the **whole market**, so a dead feed is visible even in a week
when none of our own stocks filed.

**Verification** (2026-09-08, after deploy): capture reported `feed has 2383 filings, 456 for our
299 tracked symbols, 1090 new PIT rows, newest filing on the market 2026-09-08`; a second pass took
the remaining 95 filings with `truncated: false` and 285 more rows. **1,375 PIT rows ingested, zero
XBRL parse failures.** Pulse verdicts went from **0 of 290** stocks to **23** - 4
`STRONG_ACCUMULATION` (IRB 0.37% of market cap on 4 buyers, NCC 0.26% on 6, BERGEPAINT 5 promoters,
THANGAMAYL 6), 2 `ACCUMULATION`, 11 `NEUTRAL`, 6 distribution. Data-health went from **2 problems to
0**, with the new feed row reading `Filings still arriving - newest filing 2026-09-07 (1 days ago)`.
Pinned by `InsiderPitFilingTest` (7 cases on real BERGEPAINT facts) and 4 new `DataHealthTest` cases.

**Note on coverage**: the signal will read roughly **7% coverage**, not ~100%, and that is the
honest answer rather than a residual bug - only 54 of 290 tracked stocks had *any* insider filing in
the trailing 90 days, and `null` correctly means "nobody filed", never `NEUTRAL`. Whether a stock
with no filings should leave the coverage *denominator* as not-applicable (Gotcha 88's rule) is a
real question and is deliberately **not** changed here.

---

### B-090 - The B-074 rotation was handed zero slots whenever holdings and candidates filled the budget  `[P2]`  `RESOLVED 2026-09-08`

**Root cause**: `InsiderCaptureScheduler.resolveTargets()` filled the 80-symbol PIT budget in three
stages, and stage 2 (`findTopCandidates`) was allowed to run right up to the cap. Stage 3 - the
rotating universe slice added *for B-074* - was then invoked with `PIT_SYMBOL_BUDGET -
targets.size()`, which is **0**, and `rotatingSlice` returns empty for a non-positive capacity.

**Observed** on the only run since the fix shipped with its new logging (2026-09-08 14:45): *"80
targets (80 from holdings and candidates, **0 from the rotating universe slice**; universe 290
symbols, full cycle ~1 runs)"* - 31 active holdings plus 49 candidates exhausted the budget. The
~286 never-probed stocks B-074 was written to reach were still not reached, on exactly the input
shape that produced B-074. The trailing claim was wrong too: `rotationGroups(290, 0)` hits its
`capacity <= 0` guard and returns 1, so the log asserted a full universe cycle every run while
nothing rotated - **a coverage bug reporting full coverage**.

**Fix**: not a bigger budget or a fairer rotation - both deleted. B-089's all-market filing index
means one call covers every symbol, so there is no budget to ration and no ordering to be fair
about; `PIT_SYMBOL_BUDGET`, `resolveTargets`, `rotatingSlice` and `rotationGroups` are gone.
`MAX_FILINGS_PER_RUN` and a wall-clock deadline remain, but they are **cost ceilings, not sampling**:
anything not reached is picked up by the next run because ingestion is keyed on `appId`. That is the
difference between a cap and a keyhole, and it is why this class of bug cannot recur here.

**Verification**: the first live run hit the cap (400 of 456 filings) and the second took the
remaining 95 with `truncated: false` - work deferred and then completed, not skipped.
`InsiderCaptureRotationTest` was deleted with its subject and replaced by `InsiderPitFilingTest`.

---

### B-091 - The fundamentals backfill's deadline was bounded at one end, so every out-of-hours catch-up silently did nothing  `[P2]`  `RESOLVED 2026-09-08`

**Root cause**: `FundamentalsBackfillService.pastDeadline()` read `now.isAfter(stop)` against
`stop-after: "13:30"`, which is true for the entire rest of the day. `POST
/api/fundamentals/backfill-universe` at 17:00 therefore returned a **successful batch of zero
symbols**, and a missed 11:30 fire could not be made up at all. Found while acting on the missed run
of 2026-09-08, when the app started at 12:42.

The guard's own comment states its purpose: the 14:00 screening and 14:45 insider capture share the
single NSE session. That is a **window**, and after it closes there is nothing to yield to. **This is
B-082 in mirror image** - there the NSE-crunch guard checked only the *start* time and let a run sail
into the window it protected; here it checked only that the window had opened and never that it had
closed. Both come from writing a guard for an app that only ran during market hours.

**Blast radius**: history depth, which gates the turnaround detector, the receivables and
cash-conversion forensic flags, and the B-066 bonus-vs-dilution discriminator. Measured at the time:
44 of 366 stocks at 4+ years, median 1 year, with the batch having run exactly once ever
(2026-09-07). Every missed 11:30 fire was unrecoverable.

**Fix**: the blocked period is now `[stop-after, 15:45)`, 15:45 being the app's scheduled shutdown
and therefore the last moment an NSE-heavy job can fire. The 11:30 batch still stops on reaching
13:30, so the original intent is intact. The rule is extracted as the pure
`inContentionWindow(now, stop)` so it can be tested without the wall clock.

**Verification**: a batch triggered at 17:20 - which previously did nothing - wrote **182 years
across 30 symbols**, `stoppedOnDeadline: false`, pending 336 → 306. Universe depth at 4+ years moved
from 44 of 366 (12%) to **160 of 366 (43.7%)** over four batches run that evening, and the pending
queue from 336 to 216 - the daily 11:30 job converges the rest. Pinned by
`FundamentalsBackfillTest.deadlineBlocksOnlyTheContentionWindow`.

---

### B-088 - An empty BSE screening row shadowed the full NSE one, so five holdings read "not measured" while the screener showed real verdicts  `[P2]`  `RESOLVED 2026-09-07`

**Symptom**: the compounding column added to the portfolio (SPEC 41) read NOT_MEASURED for 16 of
32 holdings, including RELIANCE, NTPC, HDFCBANK and NATIONALUM. The screener, on the same day's
screening run, showed `PARTIAL` for RELIANCE and NTPC and `COMPOUNDER` for NATIONALUM. Two screens,
one lens, opposite answers.

**Root cause**: symbol resolution followed Gotcha 84 - exact, then NSE, then BSE, then bare, first
hit wins - and stopped at the first spelling that had a row at all. Some BSE-keyed rows exist
(written by paths that screen a holding rather than the universe) carrying no balance-sheet figures
whatsoever: no ROCE, no cash conversion, no debt-to-equity. Those rows won the race against the
full NSE row and the lens dutifully reported that nothing could be judged.

A second instance of the same shape: history depth was looked up under the *screening row's* symbol
rather than across spellings, so KAJARIACER claimed one year of accounts when `annual_fundamentals`
holds seven under its NSE symbol.

**Blast radius**: half the portfolio's quality column blank, on the screen the investor was about
to start making decisions from. Worse than merely unhelpful - it was actively misleading in the
direction this codebase cares most about, because "not measured" is the app's way of saying *we
could not look*, and here it was said about businesses that had been looked at that morning.

**Fix**: the first spelling with *figures to judge* wins, in
[CompoundingLensService](src/main/java/com/example/trading/multibagger/CompoundingLensService.java).
Exact-first still decides between two rows that can both answer, so the Gotcha 84 order is intact;
only rows that cannot answer are skipped. If no spelling can answer, the honest NOT_MEASURED is
still reported with a traceable symbol - "screened but unjudgeable" and "never screened" stay
distinct. `depthFor` resolves history depth across the same spellings.

This is not cherry-picking a flattering verdict, and the distinction matters: NOT_MEASURED is the
absence of a verdict, not a bad one, so there is no better answer being selected for. A test pins
that a BSE row which *can* answer is still preferred over the NSE one.

**Verification**: on the live portfolio the counts moved from
`{COMPOUNDER 2, PARTIAL 10, NO 4, NOT_MEASURED 16}` to
`{COMPOUNDER 3, PARTIAL 13, NO 5, NOT_MEASURED 11}` - five holdings recovered a real verdict, and
RELIANCE/NTPC/NATIONALUM now match the screener exactly. KAJARIACER's depth went 1 -> 7 years.
`CompoundingLensResolutionTest` (7 cases) pins all of it.

**Lesson**: "first hit wins" needs to be "first hit that answers the question wins" whenever the
hit can be empty. The same trap is latent anywhere else `SymbolVariants.candidates()` is used to
pick a row rather than merely to find one - worth checking before the next surface reuses it.

---


### B-087 - The data-health screen read each job's schedule from the annotation literal, which for half of them is only a fallback  `[P2]`  `RESOLVED 2026-09-07`

**Symptom**: on its first weekday the new data-health screen (SPEC 44) reported *"Holdings
analysis - one session behind"*, and the same for *Core tiers* and *Holdings history*, at noon on
a Monday. All three were perfectly current. The freshness strip agreed with the screen, showing
*"History: 3 days ago"* in amber.

**Root cause**: `DataHealth.SCHEDULE` carried the times copied out of the `@Scheduled`
annotations. Several of those annotations are property placeholders with an inline default:

```java
@Scheduled(cron = "${holdings.scheduler.analysis-cron:0 30 10 * * MON-FRI}")
public void analyzeHoldings() { ... }
```

The literal in the source is the **fallback**, not the schedule. `application.yml` sets
`analysis-cron: "0 15 15 * * MON-FRI"` - the job runs at **15:15**, not 10:30. The screen
therefore believed a run was due by noon, found the newest row was Friday's, and called three
healthy tables late. Same class of error for the watchlist, which fires at 11:00, 13:00 and 15:00
and is only final after the last of them.

The irony is on the record: the method that built the table carried the comment *"Times are the
cron, taken from the `@Scheduled` methods themselves rather than from the summary in CLAUDE.md -
the summary has drifted before."* Avoiding one stale source by reading a different one that is
also not authoritative.

**Blast radius**: three false alarms every weekday morning, on the one screen whose entire value
is that its alarms are worth believing. Exactly the failure the holiday rule and the grace period
were written to avoid, arriving through a door neither of them watches. No stored data affected -
this was a reporting defect only.

**Fix**: the schedule is now read from the configuration that decides it.

- `ScheduleSpec` gains `cronProperty`; `ScheduleSpec.fixed(...)` marks the two jobs whose crons
  genuinely are literals (`MultibaggerScheduler`, `RecommendationOutcomeScheduler`).
- `DataHealth.resolve(spec, cron)` parses a Spring 6-field cron and returns the spec with the real
  time. A multi-hour field resolves to its **last** fire, because that is when the table is final.
  An absent, blank or unparseable cron leaves the spec untouched - guessing would produce
  confident wrong alarms, which is worse than the bug being fixed.
- `DashboardService.resolveCron` looks the property up in Spring's `Environment`, so the screen
  and the scheduler can no longer disagree.

**Verification**: `DataHealthTest` grew six cases, including `theActualRegression` - holdings
analysis due at 15:15, newest row Friday, asked at noon on Monday, must read OK, and must read
WATCH by Tuesday noon. Also pinned: exactly two specs may be hard-coded, so a third one copied out
of a source file fails the build.

**Lesson**: when a value can be configured, read it from the configuration - not from the default
beside it. A default is not a statement of what is true, in the same way a null is not a zero
(Gotcha 68) and a `style` attribute is not a style that applied (B-070). Worth noting too that
three existing tests broke on this fix because they used a real job's spec as a convenient
fixture for a rule test; they now pin an explicit cron, so correcting a schedule can never again
look like a rule regression.

---

### B-086 - The first dashboard load of every day told the investor the app was not running, while it was running  `[P1]`  `RESOLVED 2026-09-07`

**Symptom**: opening the dashboard showed the offline banner - *"The app is not running right now,
so this page is showing your last saved view... run start-app.bat"* - even though the app was up,
serving, and had been running its schedulers for hours. Reloading a minute later worked fine.

**Root cause**: two independent defects, one on each side, and it took both to produce the message.

1. **Server.** Spring registers the `DispatcherServlet` lazily by default, so the entire MVC stack
   is built on the *first* HTTP request instead of at startup. Measured from
   `logs/trading-app.log` on 2026-09-07:

   ```
   09:03:34  Started IntradayApplication in 37.493 seconds
   11:44:11  Initializing Spring DispatcherServlet 'dispatcherServlet'   <- first request of the day
   11:44:26  Completed initialization in 12396 ms
   ```

   The app had been up for two and a half hours; nothing had made an HTTP request, because the
   schedulers run on `sched-` threads and never touch the servlet. The investor's first page load
   paid the whole 12.4 s, and every parallel fetch on that page queued behind it.

2. **Client.** `api.js` timed out at 8 s and then treated the abort exactly like an unreachable
   server: `const unreachable = err.name === 'AbortError' || err instanceof TypeError`. Both
   produced "the app is not running". The file's own comment two lines above already articulates
   why that is wrong - *"conflating 'endpoint is broken' with 'app is down' sends the user to
   start-app.bat for a problem that restarting will not fix"* - but it was written for the 4xx/5xx
   branch only, and the timeout path never got the same care.

**Blast radius**: every weekday. A Windows scheduled task restarts the app at 09:00, so the first
person to open the dashboard each day got the banner, saw stale cached numbers behind it, and was
told to run a script that would have made things worse (a restart re-arms the same cold start).
Worse than a plain outage: the app looked broken while working, which is exactly the kind of thing
that trains someone to stop trusting the freshness strip they are supposed to read first.

**Fix**:

- `spring.mvc.servlet.load-on-startup: 1` in [application.yml](src/main/resources/application.yml).
  The initialisation cost moves into startup, which happens unattended at 09:00 with nobody
  waiting on it. Startup goes from ~37 s to ~50 s; no user ever sees that.
- `setOnline(next, reason)` in [api.js](src/main/resources/static/js/api.js) now carries
  `'timeout'` or `'unreachable'`, and every call site is labelled - including the POST path, which
  had the same conflation.
- [nav.js](src/main/resources/static/js/nav.js) renders the two cases differently. A timeout now
  reads *"The app is running but did not answer in time... it is still warming up"* and does not
  mention start-app.bat.

**Verification**: after the fix, `Completed initialization` appears in the startup sequence rather
than against an `http-nio-8080-exec` thread, and the first HTTP request made after boot returns in
well under the 8 s client timeout.

**Why it went unnoticed**: it needed the app to sit idle after starting, which only happens on a
real morning - during development something always hits the server within seconds of boot, warming
the servlet before anyone notices. It is also self-concealing: by the time you retry, it is fixed,
so the natural reading is "it was starting up" rather than "there is a defect here". Same family as
B-085 and B-070 - a fault that presents as *plausible-looking output* rather than an exception, and
survives because every layer reports success.

---

### B-085 - The build set no source encoding, so every non-ASCII character in a Java string literal compiled to a replacement character  `[P2]`  `RESOLVED 2026-09-06`

**Symptom**: a new endpoint returned `Measured on no stock <U+FFFD> cause known (B-060)` over HTTP.
The em dash had become a replacement character. Reading the response bytes as UTF-8 yielded U+FFFD
directly, so the corruption was in the class file, not in the transport or the terminal.

**Root cause**: [pom.xml](pom.xml) had no `project.build.sourceEncoding`, so javac read sources in
the platform default - windows-1252 on this machine - while all 304 source files are UTF-8 on disk.
Maven prints a warning about exactly this, and it had been scrolling past in every build.

**Blast radius**: 89 string literals across `src/main/java`, and they are not decoration. They
include email **subject lines** (`TargetHitAlertService`'s target-hit subject leads with an emoji
and carries an em dash), the arrows and bullets in `StockResearchService`'s report bodies, and
`StockResearchController`'s research-email subject. Every one has been reaching the investor's
inbox with replacement characters in it. No number is affected - nothing the app *decides* changes
- but every report has been quietly disfigured for months.

**Fix**: set `project.build.sourceEncoding` and `project.reporting.outputEncoding` to UTF-8 in
[pom.xml](pom.xml). Verified first that all 304 `.java` files decode as valid UTF-8, so turning the
setting on cannot re-interpret an existing file - had any file genuinely been saved as cp1252, this
change would have corrupted it in the other direction.

**Verification**: after a clean rebuild, `GET /api/dashboard/data-health` returns a real em dash and
a UTF-8 decode of the response round-trips with no replacement characters.

**Why it went unnoticed**: nobody reads their own email subject line character by character, and a
lone box-shaped glyph in an HTML report looks like a font problem rather than a build problem. Same
family as B-070: a defect that renders as *slightly wrong output* rather than an exception survives
indefinitely, because every layer reports success. New code in `com.example.trading.integrity` is
deliberately pure ASCII anyway - a build setting is the right fix, but not worth depending on twice.

---

### B-084 - Re-importing a narrower CSV blanked every figure the second sheet omitted  `[P1]`  `RESOLVED 2026-09-06`  `RECONSTRUCTED`

> Reconstructed after the 2026-09-06 truncation. The fix is in the code; the original entry's
> verification output and line links are lost.

**Root cause**: the CSV history import copied figures with unconditional setters, so a column absent
from the second file arrived as `null` and erased a real figure already stored.

**Fix**: `FundamentalsHistoryService.copyFigures` uses `setIfPresent` throughout - the B-046 fix
applied to the import path as well as the XBRL path. The import can therefore add but never
overwrite, which is why `DELETE /api/fundamentals/history` is now the only way to correct a wrong
imported figure.

---

### B-083 - A `@Column` annotation sat above the wrong field, so Hibernate applied `source`'s constraints to a `Boolean`  `[P3]`  `RESOLVED 2026-09-06`  `RECONSTRUCTED`

> Reconstructed after the 2026-09-06 truncation.

**Root cause**: in `AnnualFundamentalsEntity` a `@Column(nullable = false, length = 16)` intended for
`source` was placed above `consolidated`, binding a string's constraints to a boolean.

**Fix**: the annotation moved to `source` and deliberately *without* `nullable = false` - B-026's
lesson in reverse, since `ddl-auto=update` never relaxes a constraint it once created.

---

### B-082 - The NSE-crunch guard checked only the start time, so a run begun at 09:35 sailed through the window it was written to protect  `[P2]`  `RESOLVED 2026-09-06`  `RECONSTRUCTED`

> Reconstructed after the 2026-09-06 truncation.

**Root cause**: `requireOutsideNseCrunch` tested only the moment the caller asked. A backfill started
at 09:35 therefore ran straight through the 09:45 FII/DII fetch and the 10:00 report - the exact
interference the guard existed to prevent.

**Fix**: bulk loops check the deadline **between symbols** and return what they have. The general
rule is Gotcha 101's: the reason for a guard is contention, so the guard must cover the contention,
not the moment of asking.

---

### B-081 - The annual-archive backfill said "it is paced" and there was no pacing anywhere in the path  `[P1]`  `RESOLVED 2026-09-06`  `RECONSTRUCTED`

> Reconstructed after the 2026-09-06 truncation.

**Root cause**: `/backfill-holdings` documented itself as paced while both the per-year archive loop
and the per-symbol holdings loop were tight - roughly 360 unthrottled requests per run against the
host that has already permanently walled `/api/quote-equity` (B-018). The only `Thread.sleep` in
`src/main/java` was in `DailyCandleCache`, and that is for Kite.

**Fix**: `NseDataService.pace()`, a **static** process-wide gate, for the same reason the Kite gate
is static (Gotcha 23): two independently-paced loops on two scheduler threads each respect their own
budget and together break the real one. A cached filing skips the delay; single interactive calls
are not gated.

---

### B-079 - A removed scoring dimension stayed on the dashboard, so every radar drew an empty axis and claimed the analysis was incomplete  `[P2]`  `RESOLVED 2026-09-05`  `RECONSTRUCTED`

> Reconstructed from CLAUDE.md Gotcha 98 after the 2026-09-06 truncation.

**Root cause**: Sector Tailwind left the composite on 2026-09-03; the weights, entity and docs
followed, the front end did not. `DIMENSIONS` in `page-screener.js` and `page-stock.js` still listed
`sectorTailwindScore`.

**Blast radius**: for three days every stock drew an eight-spoke radar with one permanently empty
axis, captioned "7 of 8 dimensions measured" - including stocks where all seven *were* measured,
because the caption's `8` was a literal so `measured < 8` was always true. This **inverts** the rule
the UI exists to enforce (SPEC 21 rule 7): a null must read as *could not measure*, and here a
*deleted* dimension was shown as an unmeasured one, telling the investor the analysis was incomplete
when it was complete.

**Fix**: dimension removed from both front-end lists; the count derives from `DIMENSIONS.length`
rather than a literal.

---

### B-077 - A signal-gated accumulation plan was accepted and could never fire  `[P2]`  `RESOLVED 2026-09-05`  `RECONSTRUCTED`

> Reconstructed from CLAUDE.md Gotcha 96 and SPEC 8.2 after the 2026-09-06 truncation.

**Root cause**: `SIGNAL_GATED` was accepted and persisted while `AccumulationReminderService`
evaluated only SIP dates and price-ladder rungs, so the plan never reminded and nothing said so.

**Fix**: **refuse the mode, not wire it a trigger** - a tranche fired by a signal is a buy signal
wearing a plan's clothes, which SPEC 19 and SPEC 20 rule 10 bar. The refusal returns **422 with the
reason in the body** (a thrown status alone arrives bare - the B-049 lesson); the reminder service
**WARNs** on a signal-gated row rather than skipping it; and `MODE_SIGNAL_GATED` is kept so a plan
written before the guard still reads back. Pinned by `AccumulationModeTest`.

---

### B-076 - SPEC sections cited across the repo were missing from SPEC.md, and a scheduler was documented with the wrong cron  `[P2]`  `RESOLVED 2026-09-05`  `RECONSTRUCTED`

> Reconstructed from CLAUDE.md after the 2026-09-06 truncation.

**Root cause**: SPEC 13 (universe), 14 (reports), 15 (the schedule) and 16's heading were cited by
SPEC 3.4, 20, 21 and CLAUDE.md while absent from SPEC.md, so a reader had to re-derive the schedule
from the code. Separately, CLAUDE.md claimed `TokenManagementService` ran at 08:35 as a pre-market
scheduler; its cron is `0 45 9 * * MON-FRI`, an in-window recovery for the startup login.

**Fix**: sections restored; the cron claim corrected. The 08:35 error mattered because it made
CLAUDE.md say a pre-market scheduler still existed after the last one was deleted.

---

### B-074 - Insider Pulse produced a verdict for no stock at all  `[P1]`  `RESOLVED 2026-09-05`  `RECONSTRUCTED`

> Reconstructed from CLAUDE.md Gotcha 94 after the 2026-09-06 truncation.

**Root cause**: the daily capture filled its 80-symbol budget from holdings plus `findTopCandidates`
- a **verdict-filtered** query - leaving ~286 of 295 screened stocks never probed.

**Blast radius**: 0% coverage, so the signal's Information Coefficient would have read "no signal"
indefinitely. Nothing threw, nothing logged an error, and every component behaved exactly as
specified: the per-stock failure sat at `log.debug` and `InsiderPulse.toScore()` correctly mapped
null to null. Third instance of one bug - Institutional Interest (constant 40, three months) and
monthly RSI (constant 50.0, whole universe) were the first two.

**Fix**: rotate a slice of the **whole** universe daily, derived from the day of the year rather
than a stored cursor, and **stride** through the score-ordered list rather than taking a contiguous
run - a contiguous slice would probe strong stocks one day and weak ones the next, making a
verdict's coverage correlate with the day it was taken. Pinned by `InsiderCaptureRotationTest`.

**Follow-up**: this is the bug that motivated the data-health screen (SPEC 44) - the coverage check
now reports a zero-coverage signal on sight, and `KNOWN_ZERO` escalates it back to a PROBLEM if a
screening run later than this fix still reads zero.

---

### B-073 — Removing an endpoint under `/api/research` did not 404 — it silently ran the expensive one  `[P2]`  `RESOLVED 2026-09-03`

**Symptom**: right after `GET /api/research/market-direction` was deleted (B-072), calling it
returned **200**, not 404. The live log showed why:

```
API: Deep stock research requested for market-direction
Stock Research: AI service ... unavailable
```

`GET /api/research/{symbol}` is a **catch-all** over the most expensive operation in the API — a
15-dimension research pass plus an AI call plus an **email** (CLAUDE.md gotcha 17). Spring routed
the retired path straight into it, so a stale link or a typo ran a full research pass on a "stock"
named `market-direction`. It degraded quietly here only because `AI_API_KEY` was unset; with AI
configured it would have spent an AI call and emailed a research note about a company that does
not exist.

**Why this class matters**: the failure is *invisible on the way in*. Nothing errors, the caller
gets 200, and the cost is paid in an outbound email and an API bill. Every removal under a path
that has a `{pathVariable}` sibling has this shape — it is not specific to market-direction.

**Fix**: `StockResearchController.looksLikeSymbol()` runs **before any work**, and a non-symbol
path gets a 404 whose body says what was expected. Lower-case is the discriminator: every real
tradingsymbol is upper-case (`NSE:RELIANCE`, `BAJAJ-AUTO`, `M&M`, `NSE:NIFTY 50`), while every
sibling path under `/api/research` is lower-case kebab (`market-direction`, `capital-efficiency`,
`shareholding`). Explicit mappings still win over the catch-all, so `/discover` and
`/levels/{symbol}` are unaffected — verified live, both still 200.

**Pinned by** `ResearchSymbolGuardTest` (3 cases): every symbol shape the app uses is accepted;
the actual bug plus all ten current and plausible sibling paths are refused; blank / letterless /
oversized input is refused.

**Verification**: `GET /api/research/market-direction` → **404** (was 200);
`/api/research/discover` → 200; `/api/research/levels/NSE:RELIANCE` → 200. 406 tests green.

---


### B-072 — Market Direction predicted the day for a decade-horizon portfolio, and was never scored  `[P2]`  `RESOLVED 2026-09-03` (by removal)

**Symptom**: two emails a day (09:30 and 12:30) declaring the market STRONGLY_BULLISH through
STRONGLY_BEARISH with a confidence percentage, in an app whose mandate is 1–3 year holdings. Two
faults:

1. **Never measured.** It was not registered as a `RecommendationTracker` source, so SPEC §23 had
   no hit rate, no excess return and no Information Coefficient for it after months of running.
   Same class as B-071's breakout scanner, minus even the private scoreboard.
2. **Actively counter-productive framing.** Telling a long-term investor the market looks bearish
   invites the one behaviour §35 (core holdings) and B-056 exist to suppress — selling a sound
   business because of near-term weather.

**Found while removing it — the more useful defect**: Market Direction was the last consumer of a
chain that had already been dead in every meaningful sense.

- `GlobalMarketDataService` and `MarketNewsSentimentService`: no other caller.
- The entire `regime/` package: `MarketRegimeDetector`'s only caller was Market Direction. It had
  survived the 2026-05-24 and 2026-08-28 removal passes on an explicit note — *"kept — still used
  by Market Direction"* — which was true, and load-bearing on exactly one edge.
- `KiteInstrumentsService`: its only remaining caller was
  `MarketRegimeDetector.getNearestFuturesSymbol`. It downloaded a **15–40 MB instruments CSV at
  every application start and again at 08:30 every day**, needing a 50 MB WebClient buffer and a
  5-minute 429 back-off, to serve one method that served one service that nobody measured. The
  earlier belief that `NseDataService` also needed it was wrong: that file only *mentions* it in a
  comment.

**Fix**: removal (user decision, 2026-09-03). Deleted `intelligence/MarketDirection{Service,
ReportService,Scheduler}`, `intelligence/GlobalMarketDataService`,
`intelligence/MarketNewsSentimentService`, the whole `regime/` package (4 files),
`broker/kite/KiteInstrumentsService`, `GET /api/research/market-direction`, and the
`trading.regime.*` config block. **10 files, ~1,680 lines, 2 scheduled jobs.**

**Two side benefits worth recording**:
- The monthly `benchmark-symbol` chore is gone. An expired NFO contract used to make ADX read 0 and
  break regime detection silently (legacy gotcha 6) — a standing maintenance trap, now unreachable.
- `KiteInstrumentsService` 08:30 was one of **two** jobs grandfathered out of the SPEC §3.4
  market-hours rule. Only `TokenManagementService` 08:35 remains, so the rule now has a single
  exception instead of a pair.

**Rule added** (SPEC §39.3 rule 5): *when a component is kept alive by a note naming its one
remaining consumer, that note is a removal candidate, not a justification.*

**Verification**: 403 tests green; clean boot, 0 errors; `GET /api/research/market-direction` → 404;
no instruments CSV download in the startup log.

---


### B-024 / B-025 — The sector-reversal engine and the Sector Tailwind dimension it fed  `[P2]`  `RESOLVED 2026-09-03` (by removal)

**Symptom (B-024)**: SECTOR_REVERSAL's Information Coefficient was **negative at every horizon** —
−0.163 @30d, −0.195 @90d on horizon-accurate outcomes (post-B-028). A higher upside score predicted
a *worse* realised return. Suppressed from the alert email on 2026-08-25 (`actionable: false`) while
the scan, DB writes and accuracy capture carried on so the engine kept being scored.

**Symptom (B-025)**: the 8%-weighted Sector Tailwind dimension emitted **two distinct values** over
the 82 stocks its hardcoded sector map covered — 40 (52 stocks) and 50 (30) — sd 4.82, below the
collapse threshold, with panel IC −0.009 and correlation to the composite −0.07. The §38.2 coverage
vector, computed independently, agreed: 30% coverage, sd 4.7, `collapsed = true`.

**Why they close together**: they were one feature. `scoreSectorTailwind` awarded +30 for
"sector is reversing", read straight from `sector_reversal_signals` — the table the negative-IC
engine wrote. The dimension's largest single input was the engine measured to be counter-predictive.

**Fix**: removal rather than repair (user decision, 2026-09-03 — *"remove the feature which is not
giving the desired results"*). Deleted `scanner/sector/` (8 files, 3,582 lines), `SectorScannerController`,
the sector-picks performance report, the Market page panel, Deep Research dimension 7, the sector arm
of `SignalPerformanceTracker`, the `trading.scanner.sector-reversal.*` config block, and the whole
Sector Tailwind dimension (`scoreSectorTailwind`, `fetchSectorReversalStatus`, `mapSectorName`, the
`sectorReversals` parameter, the weight key, its `Dim` entries in the variance tripwire and the
coverage vector). See [SPEC.md §39](SPEC.md).

**The weight**: 0.08 redistributed **in proportion** to what the other seven dimensions already held
(0.18/0.92 → 0.20, 0.12/0.92 → 0.13, 0.13/0.92 → 0.14, 0.10/0.92 → 0.11, 0.15/0.92 → 0.16; sum
exactly 1.00), never reallocated by IC. B-025's own closing line said *"do not reallocate the weight
on this evidence alone (Gotcha 27)"* — removing a dimension that measures nothing is a different
decision from moving weight between dimensions that do, and only the first is supportable on ~5
independent periods. **B-023 stays open and unaffected.**

**Kept deliberately**: `SectorReversalEntity`/`Repository` and every row already written. The negative
IC above is the evidence for this entry; a funnel that deletes its losers cannot be judged (SPEC §25.1).
`multibagger_scores.sector_tailwind_score` is likewise retained and simply stops being written —
historical rows keep their values, new rows are null, which is the honest reading (Gotcha 21).

**Verification**: `MULTIBAGGER_CODE_REVISION` → 2, so pre- and post-removal composites cannot be
compared as one engine (SPEC §38.1). Boot log confirms
`Multibagger scoring weights validated: sum = 1.000 across 7 dimensions`. 403 tests green
(the 3 SectorTailwind characterisation cases went with the dimension; the defect they documented is
quoted in SPEC §39.2 so the evidence outlives the code). `/api/sector-scanner/latest-results` → 404.

---

### B-071 — The breakout scanner graded its own homework, and was a fifth answer to the buy-timing question  `[P1]`  `RESOLVED 2026-09-03` (by removal)

**Symptom**: two distinct defects in one chain, found while auditing what the app measures.

1. **A private scoreboard.** `SignalPerformanceTracker` scored breakout picks on whether they hit a
   target or a stop within days. The breakout scanner was **never registered as a
   `RecommendationTracker` source**, so SPEC §23 — the sanctioned loop, which measures excess return
   over the screening universe at 30/90/180/365 days and reports an IC — had *nothing* on it in four
   months of running. The number the app displayed for it came from a scheme it wrote for itself,
   with a holding period of days inside an app whose mandate is 1–3 years.
2. **A fifth buy-timing surface.** The Market page's breakout panel printed its own entry,
   stop-loss and target beside a screener row that could say AVOID — exactly the shape of B-062,
   B-065 and B-069, on the one surface the 2026-09-02 centralisation (`HoldingsViewDecorator`,
   Gotcha 85) did not cover.

**Related dead code found in the same audit**: `SignalTrackingService` — its two writers
(`recordExecutedSignal`, `recordRejectedSignal`) had had **zero callers since 2026-05-24**, when
`TradingScheduler` and `OrderExecutionService` were deleted. The `signal` table therefore took no
rows for three months while `updateHypotheticalPnL` ran **every 30 seconds** (~720 transactional
queries a trading day against a permanently empty table) and six endpoints served summaries of it.
And `MarketIntelligenceService` keyword-matched Pulse headlines into BUY/EXIT/CAUTION suggestions
4×/day whose **only** consumers were the two emails being deleted.

**Fix**: removal (user decision, 2026-09-03). Deleted `scanner/Breakout{ScannerService,
ScannerScheduler,ReportService}`, `persistence/BreakoutSignal{Entity,Repository}`, the entire
`analytics/` package (`SignalTrackingService`, `SignalPerformanceTracker`, `SignalTrackingIntegrator`,
`PerformanceReportingService`, entity + repository), `intelligence/MarketIntelligenceService` +
`MarketSuggestion{Entity,Repository}`, `persistence/Signal{Entity,Repository}`, 10
`/api/trading/breakout/*` endpoints, 6 `/api/trading/analytics/*` endpoints, 14 of 17
`/api/performance/*` (survivors became `MarketNewsTriggerController`), the morning briefing's
breakout + tracked-signal sections and their AI-context arms, Deep Research dimension 6, the Market
page breakout panel, the Accuracy page "signals being tracked" strip, three `DataCleanupScheduler`
arms with their retention keys, and the breakout / analytics / intelligence config blocks.

**Blast radius before the fix**: the investor could read a breakout entry price that contradicted
the screener's verdict on the same stock, and a "performance" email whose numbers had no relationship
to the accuracy report sent three days later.

**Rule added** (CLAUDE.md gotcha 25, SPEC §39.3): *a feature that scores itself is not measured.* If
a pick is worth showing, register it with `RecommendationTracker`.

**Verification**: 403 tests green; clean boot, 0 errors, startup 26.4 s → 18.6 s; six scheduled jobs
fewer; all 10 dashboard pages render with content (headless DOM check — HTML 200 alone is not
evidence, Gotcha 41); `/api/trading/breakout/summary`, `/api/performance/summary` and
`/api/trading/analytics/summary` all → 404.

---


### B-067 — Holdings ML emitted SELL/STRONG_SELL for every holding it did not discard, and nothing could have caught it  `[P1]`  `RESOLVED 2026-08-28` (by removal)

**Symptom**: On 2026-08-28 the recommendation model produced raw scores 0.029–0.257 across seven holdings. Three fell below the 0.10 "uncalibrated" guard and silently took the rule-based value; the other four passed the guard and hit the fixed cuts in `HoldingsMLService` (≤0.2 → STRONG_SELL, ≤0.4 → SELL): INFY 0.106 and HINDALCO 0.127 → **STRONG_SELL**, GRANULES 0.216 and VMM 0.257 → **SELL**, while the rule engine said BUY/STRONG_BUY for the same stocks. The daily "ML Insights" email then presented these as an "ML/Rule Divergence" table.

**Root cause**: (1) `recommendation.json` was `binary:logistic` ("BUY-or-not") re-expanded into six classes by probability cuts; (2) the fallback path still set `available=true`, so `mlRecommendation` silently equalled the rule-based value; (3) no component measured ML accuracy — `RecommendationTracker` never covered it, and the only validation was the Python script's random `train_test_split` on a 1,694-row CSV with **no symbol/date columns** (daily snapshots of ~33 holdings → adjacent-day leakage), whose printed metrics rolled out of the 1-day log (B-008); (4) rule-engine outputs (`current_overall_score`, `current_recommendation`) were model *inputs*; (5) the 30-day ±4% label is the wrong horizon for a 1–3-year mandate; (6) the retrain counter was in-memory and reset at the daily 09:00 restart, so the models were retrained every day regardless.

**Blast radius**: the "ML Insights" section of the daily Analysis email (every holding shown as high-risk / diverging), the `ML: …` suffix in `holdings.analysis_notes`, `holdings.blended_score` (60% weight to the ML value, fed into the Deep Research prompt as fact). The stored `holdings.recommendation` was never overridden, so exit alerts, core-holdings gates and tax-aware tables were not affected.

**Fix**: removal rather than repair (user decision, 2026-08-28). Deleted `src/main/java/com/example/trading/ml/` (parser, `HoldingsMLService`, feature extractor, anomaly detector, training collector, retrain service, two entities + repositories), `scanner/sector/ml/` (the sector-reversal success model — its label was the suppressed B-024 engine's own target hit), `models/`, `training_data/`, `scripts/train_*.py` + generators, `.claude/skills/ml-retrain-verify`. Stripped callers: [HoldingsAnalysisService.java](src/main/java/com/example/trading/holdings/HoldingsAnalysisService.java) (ML block + `applyMLPredictions`), [HoldingsReportService.java](src/main/java/com/example/trading/holdings/HoldingsReportService.java) (`buildMLInsightsSection` + divergence helpers, both email paths), [StockResearchService.java](src/main/java/com/example/trading/ai/StockResearchService.java) (ML lines in the holdings prompt block), [TradingController.java](src/main/java/com/example/trading/api/TradingController.java) (`/holdings/relabel-training`, `/holdings/train-models`), [HoldingsEntity.java](src/main/java/com/example/trading/persistence/HoldingsEntity.java) (11 `ml*`/anomaly/blended fields — DB columns left orphaned, `ddl-auto=update` never drops), [SectorReversalScheduler.java](src/main/java/com/example/trading/scanner/sector/SectorReversalScheduler.java) + [EarlyUpsideScanner.java](src/main/java/com/example/trading/scanner/sector/EarlyUpsideScanner.java) (prediction fields, ML table variant), [DataCleanupScheduler.java](src/main/java/com/example/trading/scheduler/DataCleanupScheduler.java) / `DataCleanupConfig` (two cleanup arms + three retention keys), `application.yml` (`trading.ml.*` incl. the dead trap block, `trading.scanner.sector-reversal.ml.*`, three cleanup keys).

**Verification**: `mvn compile` clean; `mvn test` green; app boots with no `ml`/`XGBoost` log lines and the 15:18 Analysis email has no "ML Insights" section. Grep `getMl|setMl|trading\.ml|ml\.holdings|sector\.ml` over `src/` returns nothing.

**Supersedes**: B-002, B-007, B-015 remain as history of why the models were never trustworthy. If ML is ever revisited, SPEC §25.5 governs: target = 180/365-day excess return on the screening universe, walk-forward validation, IC-measured in shadow mode.

### B-066 — A bonus issue was reported as serial dilution, forcing AVOID on three holdings  `[P1]`  `RESOLVED 2026-08-28`

**Symptom.** The investor spotted it: the new "Still good to buy?" column said **AVOID** while the
Signal column said **BUY / STRONG_BUY** on the same row. Four holdings carried
`DILUTION:HIGH` — BEL, MAZDOCK, NMDC and BANKINDIA. Three of the four were bonus issues:
BEL 243.66 → 730.98 (**exactly 3.0000x**, a 1:2 bonus), MAZDOCK 20.169 → 40.338 (**exactly
2.0000x**, 1:1), NMDC 293.07 → 879.18 (3x to 0.003%). A bonus dilutes nobody — every holder's
proportion is unchanged and the price adjusts to match.

**Root cause — an inverted default on missing data.** `checkDilution` computed a share-count CAGR
on the raw series and then asked `isBonusOrSplit()` whether a bonus explained it. That test asks
the right question (did new money arrive?) but needs **four years of equity and profit**, and
`series()` returns an empty list when *any* single year is null. BEL's oldest equity year is null,
so the test returned `false` — whose comment read *"cannot tell — let the flag run"*. "Cannot tell"
was thereby converted into "not a bonus", and the screen's most serious flag fired on the strength
of absent evidence. This is the exact inversion Gotcha 44/68 exists to prevent, reaching the
investor as a recommendation rather than as a blank.

**Blast radius.** `DILUTION:HIGH` carries a −5 penalty on the composite, and after B-065 a HIGH
forensic flag forces `AVOID` in the buy-timing column on **every** surface — holdings, watchlist
and screener. Three sound PSU holdings were being told "avoid, the accounts have a red flag" on a
shareholder-friendly corporate action. It also depressed their composite for as long as the flag
has been stored.

**Fix.** Corporate actions are now divided out of the share series *before* any growth rate is
taken (`adjustForCorporateActions`), the same adjustment one makes to a price series. The
discriminator is arithmetic rather than accounting, so it needs no equity data: **a bonus or split
multiplies the count by an exact simple ratio** (2, 3, 3/2, 5/4) because it is defined as a ratio
of shares, whereas money raised by selling stock lands on an arbitrary number. Equity remains
authoritative wherever it can be answered — if net worth grew by more than retained profit, cash
did arrive and the step is not treated as a bonus. When an action is found and the adjusted series
is flat, an **INFO** flag (`CORPORATE_ACTION`, penalty 0, a stop on no screen) records what was
removed, so a reader can distinguish an adjusted series from one that never moved (Gotcha 44).

**The tolerance is the load-bearing parameter, and the first cut got it wrong.** At 0.5% the grid
of simple ratios is dense enough to swallow real capital raises — BANKINDIA's government infusion
and QIP (327.766 → 410.431 → 455.341) sit **0.18% from 5/4 and 0.15% from 10/9** and were cleared
as bonus issues on the first run of the fix, which would have reported a genuinely diluting bank as
clean. A true bonus is exact to the precision stored (NMDC deviates 0.003%), so the tolerance is
**0.05%**: 15x inside for the real actions, 3x outside for those raises. BANKINDIA correctly keeps
its `DILUTION:HIGH` flag — of the four, three were false positives and one was right.

Files: [ForensicScreenService.java](src/main/java/com/example/trading/fundamentals/ForensicScreenService.java).
Pinned by `ForensicScreenTest`: the BEL null-equity case, the near-miss BANKINDIA ratios, NMDC's
rounding, a buyback never being rewritten, and a bonus not laundering dilution alongside it.

**Verification.** `GET /api/fundamentals/forensics/{symbol}` after redeploy: BEL, MAZDOCK and NMDC
report `CORPORATE_ACTION:INFO` naming the multiple and **no** DILUTION flag; BANKINDIA still
reports `DILUTION:HIGH`. The stored `multibagger_scores.forensic_flags` refresh at the next 14:00
screening, at which point the holdings column stops saying AVOID for the three.

**Lesson.** Two of them. A guard that cannot run must not hand its caller a boolean that reads as
"no problem found" — `isBonusOrSplit` returning `false` meant both "I checked, it is not a bonus"
and "I could not check", and only one of those justifies a HIGH flag. And when a heuristic replaces
missing evidence, its tolerance decides what it silently absorbs: the difference between catching
three false positives and manufacturing a fourth was 0.15 of a percent.

### B-065 — The watchlist verdict flattened forensic severity, so a medium note read as "avoid"  `[P2]`  `RESOLVED 2026-08-28`

**Symptom.** Found the moment the same rule table was pointed at the portfolio (SPEC §6.5): **15 of
31 holdings** came back `AVOID`, and three of them — INFY, BHARTIARTL, HINDALCO (composite 71) —
on nothing worse than `receivables:medium`. The screener called those same stocks a caution on the
same flag string, on the same day.

**Root cause.** Gotcha 77 was fixed in `ScreenerTimingVerdict` in August and never applied to
`BuyTimingVerdict`. Its `firstRedFlag()` skipped only `INFO`/`NONE`/`CLEAN` and treated everything
else as disqualifying, so `CODE:MEDIUM` triggered the same `AVOID` as `CODE:HIGH`. Two engines had
their own copy of the severity parser; one got fixed and the other did not — the duplication *was*
the bug, not an aggravating factor.

**Blast radius.** Every watchlist row since SPEC §37 shipped (2026-08-27) and, for one day, every
holding: a stock with a medium-severity note was told "avoid", the strongest word the vocabulary
has, while the screener said "worth a look". The investor sees both screens.

**Fix.** New `fundamentals/ForensicSeverity` is the **single** parser both engines call. HIGH →
`AVOID`; MEDIUM → `HOLD_OFF` naming the flag ("worth checking before you add, though it is not
disqualifying on its own"); INFO → not a stop. `ScreenerTimingVerdict`'s private copies now
delegate to it. One deliberate strengthening: a token with **no parseable severity** grades
`UNKNOWN` and cautions, where the screener's old parser silently downgraded it to INFO —
unknown is not "nothing" (Gotcha 21/44/68).
Files: [ForensicSeverity.java](src/main/java/com/example/trading/fundamentals/ForensicSeverity.java),
[BuyTimingVerdict.java](src/main/java/com/example/trading/watchlist/BuyTimingVerdict.java),
[ScreenerTimingVerdict.java](src/main/java/com/example/trading/multibagger/ScreenerTimingVerdict.java).

**Verification.** After redeploy the same 31 holdings return 9 AVOID (all `:high` flags or a
composite under 50) and the three medium-flag holdings read `HOLD_OFF` naming the flag.
`BuyTimingVerdictTest` gained the MEDIUM, UNKNOWN and worst-wins cases; the pre-existing
"a forensic flag beats a perfect chart" case was **deliberately changed** to use `DILUTION:HIGH` —
the old assertion pinned the defect.

**Lesson.** Two copies of a parsing rule are two places for it to be wrong, and only one of them
gets fixed when someone finds the bug. The tell was available before the symptom: Gotcha 77 ends
with "anything else reading `forensicFlags` should check what it is doing with severity", and
there was exactly one other reader.

### B-064 — Thesis decay read a universe-wide score shift as a per-stock thesis break  `[P2]`  `RESOLVED 2026-08-28`

**Symptom.** The investor found it: ASIANPAINT showed *Good entry* on the screener, and the moment
it was added to the watchlist the verdict was `HOLD_OFF — Thesis is decaying: composite moved -11
in 30 days`. The stock's own history (85 on 20 Aug → 70 on 27 Aug) supported the reading; the
universe did not — RELIANCE 53→22, TCS 63→44, INFY 70→57, TATAPOWER 42→29 over the same dates, and
the **universe median fell 74 → 65.5**. Every stock "decayed" at once because the *scale* moved
(the 22–26 Aug run of scoring changes: B-018/B-019, the shadow signals, the forensic cap), not
because any thesis did.

**Root cause.** `HoldingsDecayService` compared a stock's composite only to its own value 30/60
days earlier. A composite is not a fixed scale — a scoring-engine change or a broad market fall
moves every score on the same day — so an absolute delta cannot tell "this business weakened"
from "the ruler changed". The grade-step rule had the same defect (A+ → B+ counted as two steps
when the whole universe slid one band).

**Blast radius.** Everything that reads the decay verdict: the watchlist buy-timing rule 5
(`HOLD_OFF`), the daily/weekly holdings email "Thesis Drift Alerts" table, the holdings dashboard
table, the `/api/dashboard/summary` decay count, and the **core-holdings G5 gate** (decay must be
`INTACT`/`WATCH`) — so since 23 Aug a compounder could have been soft-demoted from CORE for a
number that described the engine, not the company.

**Fix.** Decay is now classified on the stock's move **minus the paired median move of every
symbol screened on both dates** (`relativeDelta30d/60d`; `universeDelta30d/60d` reported alongside
the raw delta). Grade steps are read on the shift-adjusted 30d-ago score. When fewer than
`MIN_PAIRED_SYMBOLS` (30) symbols pair up the shift is **null — not zero** — and the raw delta is
used with the reason saying "universe shift not measured". Reason lines now quote all three numbers
("Score −3 vs peers over 30d (raw −11, universe −8)"), and an INTACT verdict whose raw delta fell
explains why it is still INTACT. Consumers: the watchlist passes the relative delta into
`BuyTimingVerdict`, the email and `page-holdings.js` gained a "Δ 30d vs peers" column and sort on it.
Files: [HoldingsDecayService.java](src/main/java/com/example/trading/holdings/HoldingsDecayService.java),
[WatchlistTrackingService.java](src/main/java/com/example/trading/watchlist/WatchlistTrackingService.java),
[BuyTimingVerdict.java](src/main/java/com/example/trading/watchlist/BuyTimingVerdict.java),
[HoldingsReportService.java](src/main/java/com/example/trading/holdings/HoldingsReportService.java),
[page-holdings.js](src/main/resources/static/js/page-holdings.js). Pinned by `HoldingsDecayRelativeTest`.

**Verification.** After redeploy `GET /api/watchlist/item?symbol=NSE:ASIANPAINT` reports
`decayVerdict` ≠ `DECAYING` with `universeDelta30d` ≈ −8 and the verdict no longer citing decay;
`GET /api/trading/holdings/decay` shows the `relativeDelta30d` column populated for every holding
in the universe.

**Lesson.** A delta on a score that the engine itself can re-scale is only meaningful against the
same delta for the population. Same family as B-047 (a quarter filed as a year) and Gotcha 78 (a
constant read as a measurement): the number looked exact and described the wrong thing.

### B-062 — The screener called round-trips pullbacks, and disagreed with the watchlist  `[P2]`  `RESOLVED 2026-08-28`

**Symptom.** Reported by the investor: a stock showing **"Good entry"** on the screener said
**"wait for a dip"** the moment it was added to the watchlist. The stock was GALAXYSURF; GNFC
disagreed in the other direction.

**Two defects, one report.**

*(a) The rule was miscalibrated.* The screener's BUY_NOW fired on "quality ≥ 65, more than 10%
below the 12-month high, trend not falling" and never asked **where in its range** the price sat.
GALAXYSURF was 13% below its high *and* 52.6% above its low — 80% of the way up its 52-week range.
It had fallen and already bounced back. On the live 2026-08-27 run **50 of 56 BUY_NOW rows were in
the top half of their range**, so this was the normal case, not an edge. `priceVs52WeekLow` was
already on the Input record and was never read — an unused input that should have been a smell.

*(b) Two engines answered one question in one vocabulary.* The watchlist verdict is computed from
live **daily** RSI-14 and distance from **EMA-50**; the screener from **weekly** RSI and distance
from the 52-week high, as of the last screening. GALAXYSURF was 13% below its 12-month high and 11%
above its 50-day average — both readings correct, describing different timeframes. Correct on both
sides is still a defect when the investor is shown contradictory answers in identical words.

**Blast radius.** The column's whole purpose is entry timing, and it was systematically optimistic:
89% of its strongest verdict went to stocks that had already run. A beginner acting on "Good entry"
would have been buying extensions, which is the precise failure the column was added to prevent —
and the same family as B-056, where a momentum rule flagged compounders while they ran.

**Fix.** (a) `ScreenerTimingVerdict.rangePosition(aboveLow, belowHigh)` gates BUY_NOW at 70, the
measured median of the 294-stock universe rather than an assumed level; above it the verdict is
ACCUMULATE and the reason states the position. BUY_NOW went 56 → 28. (b) `DashboardService`
prefers the watchlist verdict for any tracked symbol, tagged `buyTimingSource=WATCHLIST` and
surfaced in the cell tooltip; the fallback path logs at WARN and names what it will look like.

**Verification.** `ScreenerTimingVerdictTest` grew 4 cases: the GALAXYSURF numbers verbatim, the
same distance-from-high *low* in the range still reading BUY_NOW, the endpoints of the arithmetic,
and that a missing low leg never fires the gate (an unmeasured input must not block a verdict).
329 tests green. Live: GALAXYSURF now reads the same on both screens.

**Process note.** Found by the investor, not by me — after I had checked the distribution of
verdicts but never the distribution of the *inputs behind* the strongest verdict. Reviewing what a
rule fires on is not the same as reviewing what it fires on it for.


### B-061 — Classification rows for re-prefixed symbols showed each holding twice  `[P2]`  `RESOLVED 2026-08-27`

**Symptom.** `GET /api/portfolio/core-holdings` returned **49 rows against 33 active holdings**.
The 16 extras were stocks genuinely held — INFY, RELIANCE, BHARTIARTL — listed a second time under
the other exchange prefix.

**Root cause.** `holding_classification` is keyed (symbol, classified_on), and a holding's symbol
is **not stable within a day**. The 15:18 broker sync re-prefixed all 33 holdings as `BSE:`, while
the classification runs earlier that morning had written the then-current `NSE:` symbols. Both sets
satisfy the unique constraint on the same date, so both persisted. The writer was correct
throughout — `classifyAll()` uses `findActive()` and persists `h.getSymbol()`; it was the *read*
that had no filter.

**Blast radius.** The double-count was the visible half. The dangerous half is `effectiveTiers()`,
built from the same list and consulted by `CoreOverlayService` to decide which holdings get core
protection — a stale row could grant protection to a symbol the investor no longer holds under that
prefix, suppressing exit alerts (once §35's flag is on) for a position that is not there. It also
made the dashboard's Tier column unreliable for the 22 BSE-prefixed holdings, which is most of the
portfolio.

**Fix.** `CoreClassificationService.latestClassifications()` now intersects the snapshot rows with
`holdingsRepository.findActive()` via a pure `retainActive(rows, activeSymbols)` helper. This is
Gotcha 16's rule ("reports use `findActive()`, never `findAll()`") applied to the read side.
`effectiveTiers()` is built from the filtered list, so the overlay inherits the fix.

**Cleanup.** Per Gotcha 50 a guard alone leaves the contamination measuring, so the orphan rows were
deleted as well: **32 rows** (16 on 2026-08-27, 16 on 2026-08-26). The 26 Aug rows were not causing
the visible symptom — only the latest date surfaces — but they were duplicates under a stale prefix
and would have distorted `history()`. That deletion is the one judgement call here and is recorded
rather than silent.

**Verification.** `CoreStaleRowTest` (5 cases) pins the re-prefixed-duplicate case, order
preservation, and that an empty active set keeps nothing rather than falling back to everything.
Live: the endpoint now returns 33 rows for 33 holdings.

### B-060 — `monthlyRsi` was a constant 50.0 on every screening row  `[P2]`  `RESOLVED 2026-08-27`

**Symptom.** `multibagger_scores.monthly_rsi` read exactly `50.0` for all 294 rows of the
2026-08-27 screening — zero variance — while `weekly_rsi` on the same rows was healthy
(min 25 / median 54 / p90 67 / max 94).

**Root cause — structural, not thin data.** RSI-14 on monthly bars needs 15 bars. `aggregateToMonthly`
buckets 22 daily candles per bar, so 15 bars ≈ 330 trading days. `history-days: 365` is 365
*calendar* days ≈ 276 candles ≈ **12 bars**. The threshold was never reachable for any stock, and
`calculateRSI` returns `50.0` as a "neutral default" when short of data — so every row got the
default. Weekly RSI buckets 5 days per bar (~55 bars) and was never affected.

**Blast radius.** Bounded, because nothing consumed it: the §12.11 buy-timing column deliberately
uses `weeklyRsi` only, and no dimension reads the monthly value. The real exposure was forward —
the next feature reaching for "monthly RSI" would have received a constant with no warning, which
is exactly how the Institutional-Interest dimension scored every stock at 40 for three months.

**Fix.** New `calculateRsiOrNull(data, period)` returns **null** when there are not enough bars;
`monthlyRsi` uses it and is persisted as null. Reporting it unmeasured is correct (Gotcha 21).
Widening the fetch window to make it genuinely computable would change the input window of every
other calculation in the screener and cost ~300 extra paced Kite calls per run — a separate
decision, deliberately not taken here.

**Verification.** `MonthlyRsiNullabilityTest` (5 cases) pins the null return, the period+1
boundary, agreement with the primitive when computable, and the window arithmetic that made it
structural. Found while calibrating §12.11 thresholds against the live distribution.


### B-063 — Every watchlist row carried `currentPrice = null` because the Kite quote wrapper was never unwrapped  `[P2]`  `RESOLVED 2026-08-27`

**Symptom.** Found on the first live add under SPEC §37: `NSE:TATAPOWER` was priced at ₹350.05 on
add, yet `returnSinceAddPct` came back null and the `/items` payload showed `currentPrice: null`
for all 14 seeded rows too. The 11/13/15 email had been printing "₹-" in its Price column.

**Root cause.** `WatchlistAnalysisService.fetchCurrentQuote()` called `getDouble(quote, "last_price")`
on the raw `KiteBrokerClient.getQuote()` response, which is `{status, data: {"NSE:X": {last_price…}}}`.
The lookup returned null and the code **assigned it unconditionally**, so a real price written a
moment earlier (by the add path) was overwritten with null on the very next line. `MarketDataService`
had unwrapped `data.<symbol>` correctly since 2026-02 — the lesson was learned on one caller and
not applied to the other (same shape as B-054).

**Blast radius.** Watchlist-only: `dayChangePercent` was null so the momentum score lost its
day-change term, the email price column was blank, and under §37 every return-since-added would
have read "not measured" forever. No holding, screening or recommendation figure was touched.

**Fix.** `unwrapQuote()` resolves `data.<symbol>` (single-entry fallback), and a missing or `≤ 0`
last price now **keeps the previous price and logs at WARN** instead of nulling the row.
[WatchlistAnalysisService.java](src/main/java/com/example/trading/watchlist/WatchlistAnalysisService.java).

**Verification.** After redeploy, `POST /api/watchlist/items/refresh?symbol=NSE:TATAPOWER` returns
`currentPrice` ≈ 350 and a measured `returnSinceAddPct`; the seeded rows show a price after the
next scheduled run.

### B-060 — Watchlist add/remove lived in memory and a dead JPQL delete would have thrown  `[P2]`  `RESOLVED 2026-08-27`

**Symptom.** `POST/DELETE /api/watchlist/symbols/{symbol}` mutated `WatchlistConfig.symbols` in
memory only, so every addition vanished at the 09:00 restart (the market page said so out loud).
Nothing ever deleted a watchlist row either: `WatchlistRepository.deleteBySymbolNotIn` was a JPQL
`DELETE` with no `@Modifying`/`@Transactional` — it had zero callers and would have thrown on first
use — so a symbol removed from YAML kept appearing in the 11/13/15 email forever. There was no
"added on" date and no price-at-add, so "how has it done since I noted it" was unanswerable.

**Root cause.** The watchlist was a YAML-driven analysis cache, never a tracked list: the table
row was an analysis snapshot overwritten each run, and membership had no persistence at all.

**Fix (SPEC §37).** The table is now the source of truth with soft-delete (`active`,
`removedOn`, `priceAtRemoval`) and membership columns (`addedOn`, `priceAtAdd`, `niftyAtAdd`,
`addedNote`, `source`); YAML is a one-time seed via `WatchlistSeedRunner` that never resurrects a
removed row; the path-variable endpoints are replaced by `POST /api/watchlist/items` (+ `/remove`,
`/refresh`, `/note`) with `symbol` as a query parameter; the dead delete is removed; the email and
analysis loop read `findActiveOrderBy…` so removed rows never reach them. Files:
[WatchlistEntity.java](src/main/java/com/example/trading/watchlist/WatchlistEntity.java),
[WatchlistRepository.java](src/main/java/com/example/trading/watchlist/WatchlistRepository.java),
[WatchlistTrackingService.java](src/main/java/com/example/trading/watchlist/WatchlistTrackingService.java),
[WatchlistSeedRunner.java](src/main/java/com/example/trading/watchlist/WatchlistSeedRunner.java),
[WatchlistController.java](src/main/java/com/example/trading/watchlist/WatchlistController.java).

**Blast radius.** The watchlist email and market-page panel showed a list the investor could not
edit durably, with no return-since-added; no scoring engine reads the table, so no composite or
recommendation was distorted.

**Verification.** `WatchlistSeedTest` pins INSERT/STAMP/SKIP and the never-resurrect rule;
`mvn test` green (257 → 291 tests). Live: after `start-app.bat` the log prints "Watchlist seed: N
inserted, M stamped, K unchanged" and a second boot prints all-unchanged; `POST /items` followed by
`POST /items/remove` leaves `active=false` with `price_at_removal` set and the row absent from
`GET /items` but present under `?includeRemoved=true`.

### B-059 — The holdings email said no stock cleared the core gates while one had  `[P2]`  `RESOLVED 2026-08-27`

**Symptom.** The Core Holdings section of the Action Items email read *"No holding currently clears
every core gate."* At that moment NATIONALUM was passing **all five** evidence-carrying gates —
capital efficiency (ROCE 36.4%), financial quality, earnings consistency 72/100, a clean forensic
screen and an intact thesis — with a durability of 93, the highest in the portfolio.

**Root cause.** The section partitions holdings on `effectiveTier` and the empty-case sentence was
written about *gates*. Those are different things by design: promotion to CORE requires the same
result on two weekly readings (SPEC §35.6) so that one bad XBRL parse cannot change how a holding's
alerts behave. NATIONALUM's provisional `tier` was CORE, its `effectiveTier` was still UNCLASSIFIED,
and the sentence collapsed the distinction. The classifier had already computed the explanation —
`pendingChange: "would become CORE - 0 of 2 weekly confirmations"` — and nothing rendered it.

**Blast radius.** The reader is told their portfolio contains no core-quality business when it
contains one, and is given no signal that a promotion is two weekly runs away. It also hides the
single most reassuring output the feature produces, at the point where the report is otherwise all
negatives — precisely the "which stock should I never sell" question this module exists to answer.
Same family as Gotcha 44 ("no forensic flags" is usually "nothing was checked"): a message must
describe what was actually determined, not a stronger claim that happens to render.

**Fix.** [HoldingsReportService.java](src/main/java/com/example/trading/holdings/HoldingsReportService.java)
now collects a third bucket — holdings whose provisional tier is protected but whose effective tier
is not — and renders a "Passing every gate, awaiting confirmation" callout naming each one and its
confirmation count. The empty-case sentence became *"No holding is a core holding yet"*, which is
true of the effective tier it is actually reporting. The bucket is deliberately separate from
`core`: these holdings are **not** protected and the overlay must keep treating them normally.

A second defect surfaced when the fix was rendered: NATIONALUM appeared in the new callout **and**
in "Not enough data to judge", because the unmeasured bucket keys on `effectiveTier` alone. A stock
that passed every gate is the opposite of one that could not be judged, and the section contradicted
itself. The three buckets are now mutually exclusive.

**Verification.** `CoreHysteresisTest.pendingPromotionIsAnnounced` pins that a first-time CORE
reading yields a non-protected effective tier *and* a non-null `pendingChange` naming CORE — the
channel the email depends on. Rendered live: the callout names NATIONALUM with "0 of 2 weekly
confirmations", and NATIONALUM no longer appears in the unmeasured list. Found by inspection while
testing the classifier end-to-end, not by a user report.


### B-046 — The XBRL writer blanked imported figures, and never wrote the two fields the forensic screen depends on  `[P1]`  `RESOLVED 2026-08-26`

**Symptom.** "No forensic flags" on stocks that had a full imported history. The dilution and receivables checks reported *not measured* for every screened stock, so the forensic section was running on the auditor and cash-conversion checks alone — half the screen, presented as the whole of it.

**Root cause — two faults with the same consequence.**

1. **Blanket overwrite.** `recordFromXbrl` used unconditional setters. NSE's tagging coverage varies by company and by taxonomy, so any field the filing does not tag arrived as `null` and erased a real imported figure. The erasure was permanent by construction: the row is stamped `source=XBRL` either way, and the import path deliberately refuses to overwrite an XBRL row (§32.1, Gotcha 49) — so nothing could ever put the value back.
2. **Two fields nobody wrote.** `receivables` and `shareCount` were never read from the annual filing at all. A comment claimed the share count was omitted on purpose because "a guessed share count would corrupt the dilution flag" — but it does not have to be guessed: paid-up capital ÷ face value is exact, present in both the Ind-AS and BANKING taxonomies, and the *quarterly* parser had been computing it that way for months.

**Fix.** A `setIfPresent` merge — the filing outranks the import where it has a figure, and only there. `BalanceSheetData` now carries `tradeReceivables` (current + non-current) and `sharesOutstandingCr`, both parsed from the annual XBRL, and both written through.

**The unit trap this opened.** The import stores whatever unit the user's sheet used; the XBRL path stores crore. A series mixing the two steps by 10 million between adjacent years, which the dilution check would have read as a colossal buyback (and silently *missed* real dilution). Two defences: the import normalises anything above 100,000 to crore — no listed Indian company has 100,000 crore shares, the same auto-detection the FII/DII lakhs-vs-crores conversion uses — and `checkDilution` refuses to measure across a >50× step, reporting "not on a common basis" rather than inventing a corporate event.

**Files.** [FundamentalsHistoryService.java](src/main/java/com/example/trading/fundamentals/FundamentalsHistoryService.java) (`recordFromXbrl`, `setIfPresent`, `shareCountSetter`), [NseDataService.java](src/main/java/com/example/trading/ai/NseDataService.java) (`fetchAnnualFinancials`, `BalanceSheetData`), [ForensicScreenService.java](src/main/java/com/example/trading/fundamentals/ForensicScreenService.java) (`hasImplausibleStep`), [AnnualFundamentalsEntity.java](src/main/java/com/example/trading/fundamentals/AnnualFundamentalsEntity.java).

**Verification.** New `FundamentalsTwoWriterTest` runs the merge both ways with a mocked repository: a filing reporting only revenue and profit updates those two and leaves borrowings, receivables, share count and CFO intact; a filing carrying receivables/shares/net block writes all three. Plus two import tests for unit normalisation and one forensic test for the mixed-basis refusal. 214 tests green.

**Blast radius, measured.** `annual_fundamentals` holds **378 rows, every one `source=XBRL`, with 0 receivables, 0 share counts and 0 net blocks**. Both forensic checks that depend on those fields had nothing to read for any stock in the table — which is why the screen has been reporting itself clean. Live: `GET /api/fundamentals/forensics/NSE:RELIANCE` correctly names all four gaps in `notMeasured` rather than returning an empty flag list as a pass (Gotcha 44).

**No cleanup script needed, unlike B-040.** `recordFromXbrl` updates the existing row for the same financial year, so the next screening run backfills the three fields on all 378 rows. And because there are **zero `IMPORT` rows today**, the overwrite half of this bug never destroyed user-supplied data — it would have, on the first import.

---

### B-047 — A quarterly block was read as annual data, so one quarter could be filed as a year  `[P1]`  `RESOLVED 2026-08-26`

**Symptom.** Latent — no live import had used a full screener export yet. The bug would have been almost invisible if it had: the years still parse, the rows still populate, and the figures are simply wrong by roughly 4×.

**Root cause.** A screener.in "Data Sheet" is several stacked sections — annual P&L, a **QUARTERS** block, then Balance Sheet and Cash Flow — each with its own header row. The parser located the *first* header and applied that column→year map to every row below it, so the quarterly block's `Sales` and `Net profit` overwrote the annual figures of whatever years those columns mapped to. Every CAGR, margin, turnaround verdict and forensic ratio downstream would then have run on a single quarter labelled as a year.

**Fix.** Headers are re-detected as the file is walked. A header is *quarterly* if any column names a month other than March, or if a year appears twice — Indian annual reporting ends 31 March, so an annual header is all-March or carries no month at all. The parser deactivates inside a quarterly block and resumes at the next annual header, which is what keeps the Balance Sheet and Cash Flow sections (they sit *after* the quarters) from being lost. Ambiguous headers are treated as annual: skipping an annual block silently loses data the user supplied.

**No silent drop.** The number of quarterly blocks skipped is returned as an import warning. A section dropped without comment reads as "the file did not contain that data" (§21 rule 7).

**Files.** [FundamentalsHistoryService.java](src/main/java/com/example/trading/fundamentals/FundamentalsHistoryService.java) (`parse`, `yearColumns`, `isAnnualHeader`, `monthOf`).

**Verification.** `quarterlyBlockIsNotReadAsAnnual` uses a fixture with the real three-section layout: Mar-2025 sales stay 1,450 (annual) rather than 470 (the quarter), the balance sheet *after* the quarterly block is still read, and the warning names what was skipped. 214 tests green.

---

### B-048 — Capex-to-depreciation was unreachable, and an unknown CWIP change was quietly treated as zero  `[P2]`  `RESOLVED 2026-08-26`

**Symptom.** The capex verdict could only ever speak about CWIP intensity. B-034 unlocked `EXPANSION_UNDERWAY` by supplying the prior year's CWIP from history; the ratio stayed null because nothing supplied the prior year's **net block**.

**Root cause.** `annual_fundamentals` had no column for net block, so `CapexCycleService` passed `priorPpe = null` on every call — with a comment correctly saying the ratio would stay null as a result. Separately, inside `classifyCapexCycle` the CWIP term defaulted to `0.0` whenever the change could not be computed. That is the null-as-zero failure in its most damaging form: it understates capex by exactly the amount under construction — the spending the whole analysis exists to detect — and the ratio still comes out looking measured.

**Fix.** A nullable `netBlock` column, written by the XBRL path and carried by the import, and read back as `priorPpe`. The CWIP term is now measured (both years present) or provably irrelevant (neither year reports any construction) — otherwise the capex proxy is null. An asset-light company with no CWIP in either year still gets a ratio; a company carrying ₹500 cr of construction with an unknown prior year does not.

**Third fault, same entry.** `isBonusOrSplit` ran *before* the growth threshold, so a company whose share count had never moved was told "this looks like a bonus issue or stock split rather than dilution" — an explanation offered for something that did not happen, in the beginner-facing wording the user reads.

**Files.** [AnnualFundamentalsEntity.java](src/main/java/com/example/trading/fundamentals/AnnualFundamentalsEntity.java) (`netBlock`), [CapexCycleService.java](src/main/java/com/example/trading/fundamentals/CapexCycleService.java), [NseDataService.java](src/main/java/com/example/trading/ai/NseDataService.java) (`classifyCapexCycle`), [ForensicScreenService.java](src/main/java/com/example/trading/fundamentals/ForensicScreenService.java) (`checkDilution` ordering).

**Verification.** Three capex tests (unknown CWIP change → null ratio; absent-in-both-years → real ratio; history-supplied prior net block unlocks it) and one forensic test that a flat share count draws no bonus-issue explanation. 214 tests green.

**Note on data.** The ratio becomes computable per stock only once **two** annual filings have been recorded, so it stays null for a stock whose history begins with this fix. That is the same maturation B-034 described, not a defect.

---

### B-049 — Two manual endpoints could monopolise the broker through the afternoon report window, and Stage A leaked every candle series it fetched  `[P1]`  `RESOLVED 2026-08-26`

**Symptom.** None observed — a defect found by reading, before it cost anything. `POST /api/universe/scan` and `GET /api/universe/ipo-watch` had no time guard at all.

**Root cause — three independent faults behind one entry.**

1. **No runway check.** Kite calls are paced process-wide at ~2.9 req/s (B-027). The coarse scan is a measured ~20 minutes over ~1,600 symbols, each costing two paced calls. Started at 15:10 it holds that gate straight through the 15:05–15:28 report jobs — target-hit, holdings, recommendation outcomes, accuracy email, tax capture — every one of which aborts *silently* once `isMarketOpen()` goes false at 15:30. That is the B-014 starvation failure, reachable from a button.
2. **A write-only cache.** `MarketDataService.getRecentCandles` stored every fetch in an unbounded map annotated "update cache for reference (not used for real-time data)" — and nothing ever read it. Stage A therefore retained ~1,600 symbols × ~400 daily candles as boxed maps for the life of the JVM.
3. **Relative strength that stopped being relative.** `relativeStrength()` returned the raw stock return when the Nifty series was unavailable. In a rising market that passes nearly everything, so the promotion filter would have silently lost its benchmark at the exact moment the benchmark fetch failed.

Two smaller faults sat alongside: `queue()` ran outside the per-symbol `try`, so one unique-constraint collision from an overlapping manual scan discarded all 20 minutes of completed work; and blacklisted symbols were filtered only downstream at the merge, after a Kite call, a queue row and a Stage B deep score had already been spent on them.

**Fix.** Two guards, sized to what each endpoint actually costs — the long scan needs the Saturday window or a weekday before 13:00; `ipo-watch` and `process-queue` only have to stay out of the 14:55–close crunch. Both refuse with **409 and a readable reason**, not a silent no-op. The dead candle cache is deleted rather than bounded (a cache nothing reads is not a cache). `relativeStrength` returns null without a benchmark, and `runCoarseScan` checks the Nifty series **once, up front**, aborting the run rather than reporting ~1,600 "no data" rows — a total failure that would otherwise look like a scan that found nothing. Queue writes are individually isolated and counted; blacklisted names never enter the target list.

**Deviation from the review.** It proposed 14:00 as the weekday cutoff. The daily multibagger screening fires *at* 14:00 and is itself a long Kite-heavy scan, so a sweep launched at 13:59 would run straight into it behind the same pacing gate. 13:00.

**Files.** [UniverseController.java](src/main/java/com/example/trading/universe/UniverseController.java) (`requireRunwayForLongSweep`, `requireOutsideAfternoonCrunch`), [UniverseExpansionService.java](src/main/java/com/example/trading/universe/UniverseExpansionService.java) (`runCoarseScan`, `relativeStrength`), [MarketDataService.java](src/main/java/com/example/trading/marketdata/MarketDataService.java) (candle cache removed).

**Verification.** `UniverseExpansionTest.relativeStrengthIsNullWithoutTheBenchmark` replaces `relativeStrengthFallsBackToAbsolute`, which had pinned the defect as intended behaviour. Live: `POST /api/universe/scan` at 18:5x IST returns 409 with the reason; `GET /api/universe/dynamic` still 200. 203 tests green.

---

### B-043 — Under-Discovery paid its "institutions are arriving" bonus to stocks institutions had already arrived at  `[P1]`  `RESOLVED 2026-08-26`

**Symptom.** A lens built to find stocks nobody follows was scoring well-followed stocks.

**Root cause.** Two null-discipline failures in one block. The +20 "rising from a low base" component checked only that the FII/DII *deltas* were positive — never the *level* — so a 35%-institutionally-owned stock attracting more institutional money collected the full 20 points. That is ordinary flow into a name the market already covers, which is the opposite of the thing being measured. Separately the level itself was `(fii ?: 0) + (dii ?: 0)`, so a stock with 1% DII and an unpublished FII figure read as "barely institutionally owned" — the strongest finding this lens makes — from a number half of which had never been published (Gotcha 21).

**Fix.** The rising component is gated on `inst < 10`; above that it is *measured and scores zero*, which is a finding rather than a gap. The level requires both legs non-null or drops out of `available` entirely and is renormalised away, exactly as the composite treats its nullable dimensions.

**Files.** [UnderDiscoveryService.java](src/main/java/com/example/trading/multibagger/UnderDiscoveryService.java) (`LOW_BASE_CEILING`, `compute`).

**Verification.** Three new tests: rising-from-a-high-base earns nothing and emits no "low base" driver; rising-from-a-low-base still scores 100; a null FII leg yields null rather than a false "barely owned". 203 tests green.

**Historical rows left as they are.** 189 `multibagger_scores` rows carry an `under_discovery_score` computed under the old rule (range 0-61; nothing maxed out). They are per-date snapshots and recomputing them from today's shareholding would be fabricating a past reading, so they stand; each screening run writes a fresh row, so the series self-corrects forward. Nothing downstream is distorted meanwhile — the lens never enters the composite (SPEC §12.10).

---

### B-041 — Rs 3,000 of insider buying returned STRONG_ACCUMULATION, the strongest verdict the system has  `[P1]`  `RESOLVED 2026-08-26`

**Symptom.** Three ₹1,000 purchases in a ₹50,000 cr company scored 100. The test `repeatedBuyingIsStrong` asserted this was correct.

**Root cause.** The event-count path exists for filings that cannot be sized — no market cap, or values omitted. But its escape hatch (`buys >= 3 && sells == 0`) was also reachable on the *sized* path, where the value was known and trivially small. It counted **filings**, not people, so one director staggering a purchase across three days produced three "corroborating" events from a single decision. SEBI PIT Reg 7(2) has no de-minimis threshold, so tiny filings are routine.

**Fix.** The cluster test requires three distinct **people** and, whenever any value was disclosed, an aggregate clearing ₹25 lakh. Where nothing was disclosed there is no floor to apply, so a cluster of separate insiders remains the only evidence available and is still allowed to be strong.

**Files.** [InsiderPulseService.java](src/main/java/com/example/trading/insider/InsiderPulseService.java) (`MIN_MATERIAL_VALUE_RUPEES`, `personKey`, `verdictFor`).

**Verification.** `repeatedBuyingIsStrong` is replaced by three tests: the ₹3,000 case is NEUTRAL, three different insiders at ₹30 lakh is STRONG, one person filing three times is NEUTRAL. 203 tests green.

---

### B-040 — NSE's `"-"` placeholder made every blanked disclosure a BUY, including outright sales  `[P1]`  `RESOLVED 2026-08-26`

**Symptom.** A `MARKET_SALE` with a blank transaction-type field *added* to net insider buying.

**Root cause.** `normaliseType()` treated any non-blank string as a real value and classified it SELL only if it started with "s". `"-"` is non-blank and does not start with "s", so it became BUY. The same class had already been handled correctly one method away: `num()` has always read `"-"` as null.

**Why it matters more than a parsing slip.** The error ran in exactly the one direction this signal must never fail in — the pulse exists to spot promoters accumulating, and this let promoters *selling* read as promoters buying. Shadow mode meant no composite impact, but the persisted verdict and its IC measurement were wrong, and that IC is what the promotion decision will eventually rest on.

**Fix.** Placeholders (`-`, `--`, `NA`, `N/A`, blank) are absent, and an absent type defers to the acquisition mode, which is the more reliable field.

**Data repair — a guard alone was not enough (B-035 lesson).** Three stored rows carried the impossible combination `mode=MARKET_SALE, transaction_type=BUY`, including a **Rs 58.5 crore KMP sale at COFORGE**. They could not simply be flipped: the disclosure hash includes the type, so an updated row would no longer match what the ingest path computes and the next capture would insert a *second* copy of the same filing — one BUY, one SELL, for one trade. The repair recomputes the hash with the ingest key format, verifies that recomputing the *current* key reproduces the stored hash (all three did) and then, per row, either deletes it as a duplicate of an already-correct twin (MARKSANS 1683 → 1684) or corrects it in place (PREMIERENE, COFORGE). Backed up to `logs/b040-cleanup-backup-2026-08-26.txt` first.

**Files.** [InsiderDisclosureService.java](src/main/java/com/example/trading/insider/InsiderDisclosureService.java) (`normaliseType`, `isMeaningful`).

**Verification.** `placeholderTransactionTypeDefersToMode` covers all four placeholder forms plus both directions of the mode fallback. Post-repair: **0** rows with `MARKET_SALE`+`BUY`; 1,602 rows, 1,602 distinct hashes. `multibagger_scores.insider_pulse_verdict` is null on all 22,677 rows, so no persisted verdict was ever computed from the bad rows — the damage was confined to the ledger and is now out of it. 203 tests green.

---

### B-058 — The observation trail overwrote itself, deleting the morning's evidence  `[P2]`  `RESOLVED 2026-08-27`

**Symptom.** Latent — no core holdings exist yet, so no observation had been recorded to lose. It
would have surfaced as an under-count in the one dataset the suppression decision rests on, with
nothing to indicate anything was missing.

**Root cause.** `recordObservedAlerts` merged the incoming alert types into an in-memory day buffer
and then wrote **that buffer** onto today's `holding_classification` row. The buffer is process
state: it is empty after any restart, and this app restarts every morning and is restarted by hand
whenever code changes. So a 12:00 alert arriving after a restart would write a row containing only
the 12:00 alert, silently deleting the 10:00 one already stored there. `classifyAndPersist` had the
same shape when seeding a row from the buffer.

The trail exists to answer one question at the end of the observation quarter — *what would
suppressing these alerts have saved or cost?* — and an under-count biases that answer toward "not
many alerts fired, so suppression is harmless", which is the direction that argues for switching a
risk control off.

**Fix.** `unionAlerts(existing, incoming)` merges rather than replaces, preserving order and
dropping duplicates, and both write paths go through it. An empty incoming batch can no longer
blank a populated row.

**Blast radius.** The R-1 evidence base for `portfolio.core.suppress-technical-exits` — i.e. whether
exit alerts get switched off for core holdings on 2026-11-30.

**Files.** [CoreClassificationService.java](src/main/java/com/example/trading/portfolio/core/CoreClassificationService.java)
(`unionAlerts`, `recordObservedAlerts`, `classifyAndPersist`).

**Verification.** `CoreHysteresisTest.observedAlertsAreUnioned` — a second alert appends rather than
replaces, a repeat of the same alert stays one observation, a null existing value is handled, and an
empty batch leaves the stored value intact. 259 tests green.

**Found by**: the R-11 review pass on Phase A, the day after it shipped. Two entries from that pass
now (B-057, B-058), which is roughly the rate the plan's §15.2 predicted and the reason the pass was
budgeted rather than assumed.

---

### B-057 — A seeded default was read as the investor's stated intent, and demoted 14 holdings  `[P2]`  `RESOLVED 2026-08-26`

**Symptom.** The first live run of the core-holding classifier (SPEC §35) produced **zero** CORE
holdings and 24 SATELLITE. The gate breakdown showed `G7 FAIL` on 14 of 33 — every holding that had
a conviction record.

**Root cause.** G7 reads `holding_conviction.holding_horizon_months` and requires ≥ 36 months,
described in the design as "the investor's own stated intent". But **all 46 conviction records
carry exactly 24 months**, because `ConvictionService` writes that value as a seed on every
auto-generated record (`24, // default 2-year horizon for multibagger plays`). The investor never
chose it. The gate was failing holdings on a number nobody decided, and doing so in the one
direction that matters — SATELLITE means normal exit behaviour, so a genuine compounder would keep
getting technical SELL signals because of a constructor argument.

Same family as B-019 and Gotcha 21: **a default is not a statement, in the same way that a null is
not a zero.** Both are the absence of information wearing the costume of a measurement.

**Fix.** A nullable `horizon_stated` flag on `holding_conviction`, set true only when the horizon
arrives through `POST /api/portfolio/conviction` and false on the two auto-generation paths (an
existing stated horizon is never downgraded by a later seeding pass). G7 now returns
`PASS_NO_DATA` for a seeded horizon, with the reason telling the reader it is a system default and
how to make the gate count. Because `PASS_NO_DATA` carries no evidence toward the quorum (SPEC
§35.2), the affected holdings become UNCLASSIFIED — "we found nothing wrong but could not check
enough to call this core" — rather than SATELLITE, which is a finding.

**Blast radius.** Which stocks the daily Action Items email holds through a technical sell signal,
and which it lists under "Exit Required". Caught by the review pass on the day the feature shipped,
before any scheduled run, so the only affected rows were the smoke test's — overwritten by the
re-run.

**Files.** [HoldingConvictionEntity.java](src/main/java/com/example/trading/portfolio/conviction/HoldingConvictionEntity.java) (`horizonStated`),
[ConvictionService.java](src/main/java/com/example/trading/portfolio/conviction/ConvictionService.java) (`upsert` overload; both seeding callers),
[CoreHoldingService.java](src/main/java/com/example/trading/portfolio/core/CoreHoldingService.java) (`g7Horizon`),
[CoreClassificationService.java](src/main/java/com/example/trading/portfolio/core/CoreClassificationService.java) (evidence wiring).

**Verification.** `CoreHoldingGatesTest.g7DoesNotFailOnASeededDefault` — a seeded 24-month horizon
is `PASS_NO_DATA`, does not count toward the quorum (4 evidence gates, so UNCLASSIFIED), and the
identical number marked as stated is still a real FAIL. 258 tests green. Re-ran classification
live: the 14 G7 failures became `PASS_NO_DATA`.

---

### B-054 — Every NSE response over 256 KB failed silently and read downstream as "no data"  `[P1]`  `RESOLVED 2026-08-26`

**Symptom.** `GET /api/fundamentals/forensics/NSE:RELIANCE?announcements=true` reported *"Auditor check — no corporate announcements available"* for a company that files constantly. The log showed:

```
Announcement records fetch failed for RELIANCE: 200 OK from GET .../api/corporate-announcements,
but response failed with cause: DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144
```

**Root cause.** `NseDataService.getWebClient()` builds a single shared `WebClient` with no codec configuration, so it inherits Spring's **256 KB** default buffer. NSE's corporate-announcements feed for an active large cap exceeds that. The HTTP call succeeded (200); decoding it threw.

**Blast radius — wider than the feature that exposed it.** That one client serves **nine** call sites, including shareholding, integrated filings and FII/DII. Any NSE payload over 256 KB has been failing for the entire life of the service. Confirmed dead paths:

- Deep Research dimension 14 (corporate announcements) — empty for exactly the large caps most likely to be researched.
- The forensic **auditor** and **related-party** scans (SPEC §32.4) — the auditor flag is the one that forces the HIGH_RISK cap, so the system's strongest safety guarantee was unreachable for big companies even after B-038 wired it in.
- F8 transcript discovery (SPEC §34) — a transcript that cannot be listed cannot be downloaded.

**The failure was invisible by construction.** The exception was caught and logged at **DEBUG**, and the method returned an empty list. Downstream, "empty" is indistinguishable from "this company filed nothing" — so every consumer rendered a confident negative. This is the B-027 lesson repeating on a different client: a failure path that degrades to a plausible-looking empty value will not be noticed.

**Fix.** (1) `maxInMemorySize` set to 16 MB on the shared client, so every existing and future call site inherits it — far above any observed NSE JSON while still bounding a runaway response. (2) The announcement-fetch failure now logs at **WARN**, names the cause, and states explicitly that downstream will misread it as "no announcements".

CLAUDE.md already documented this exact failure mode for the Kite instruments CSV (50 MB buffer). The lesson had been learned on one client and not applied to the other.

**Files.** [NseDataService.java](src/main/java/com/example/trading/ai/NseDataService.java) (`getWebClient`, `NSE_MAX_RESPONSE_BYTES`, `fetchAnnouncementRecords`).

**Verification.** Before: forensics on RELIANCE reported "Auditor check — no corporate announcements available". After: the auditor check **runs** (no `notMeasured` entry) and correctly raises no flag — which also exercises B-037's clean-opinion guard against real NSE filing text rather than fixtures. 197 tests green, clean boot, 0 errors.

**Follow-up.** `POST /api/concall/analyze/NSE:RELIANCE` still returns `NO_TRANSCRIPT` with the feed now readable, which points at the transcript-matching regex rather than the fetch — that is B-051, already open.

---

### B-037 — `"unqualified opinion"` contains `"qualified opinion"`, so SEBI's mandatory clean-audit filing fired the auditor red flag  `[P1]`  `RESOLVED 2026-08-26`

**Symptom.** The forensic auditor flag — the only flag armed by default, worth −8 and a forced HIGH_RISK composite cap at 54 — matched on announcements that say the audit was *clean*.

**Root cause.** `AUDITOR_KEYWORDS` contained `"qualified opinion"` and matching was a bare `lower.contains(kw)`. SEBI LODR Reg 33(3)(d) **requires** every listed company to file a declaration that its audit report carries an unmodified opinion; that text contains "unqualified opinion", which contains the substring "qualified opinion". Hyphenated forms ("un-qualified", "un-modified") are common in real filings and would have defeated a naive `!contains("unqualified")` guard too.

**Blast radius.** The routine filing that exists to say *nothing is wrong* triggered the most serious flag in the system. Because the flag forces the HIGH_RISK cap, a false positive does not merely deduct points — it makes the stock structurally unrecommendable (capped below the 65 threshold) and prints an alarming "treat all the numbers as unverified" line in the holdings email. At the time of discovery the screener never fetched announcements (B-038), so the damage was confined to the daily holdings email; fixing B-038 first would have spread it across the whole universe.

**Fix.** `CLEAN_OPINION_MARKERS` is checked **before** the problem keywords and skips the announcement entirely. Matching runs on a hyphen-stripped, lower-cased copy so "un-modified" and "unmodified" behave identically. Erring toward a false negative is deliberate and documented: a false positive suppresses a genuine candidate outright, while a missed one leaves the stock where the other measures already put it.

**Files.** [ForensicScreenService.java](src/main/java/com/example/trading/fundamentals/ForensicScreenService.java) (`CLEAN_OPINION_MARKERS`, `normaliseAnnouncement`, `isCleanOpinionDeclaration`, `checkAuditor`).

**Verification.** Three new tests: five real-world clean-declaration phrasings (including both hyphen forms and "without qualification") raise no flag; three genuine problems (qualified / adverse / disclaimer of opinion) still force HIGH_RISK; hyphen normalisation asserted directly. 189 tests green.

---

### B-044 — ADV20 divided by traded days, inflating exactly the illiquid stocks the guard exists to catch  `[P1]`  `RESOLVED 2026-08-26`

**Symptom.** `calculateAdv20` returned `sum / counted` where `counted` was the number of candles that actually traded, not the 20-day window.

**Root cause.** Days with no trade were skipped in both numerator and denominator, turning "average daily traded value" into "average value on days it traded".

**Blast radius.** The error scales with illiquidity, so it is largest precisely where the measurement matters. A stock trading 3 days out of 20 read **6.7× too liquid** — enough to lift it from THIN into MODERATE, which (a) removes the "thin liquidity" warning from the report, (b) makes `daysToBuild` understate the accumulation time by the same factor, and (c) clears the THIN exclusion that SPEC §30.2 relies on to keep un-buyable names out of universe promotion. The buyability guard was shipped explicitly as a prerequisite for widening the universe (plan §9, "mandatory before F3"), and this defect disarmed it for the exact population it was built to stop.

**Fix.** Divide by `LIQUIDITY_LOOKBACK_DAYS`. A day the stock did not trade is a day you could not buy, so it belongs in the denominator. Null is still returned when nothing traded at all.

**Files.** [MultibaggerScreenerService.java](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java) (`calculateAdv20`).

**Verification.** New test: 3 of 20 days at ₹20 lakh each yields ₹3 lakh/day (not ₹20 lakh) and classifies THIN; a fully-traded window is unchanged at ₹10 lakh/day. 189 tests green.

---

### B-035 — Universe expansion persisted scores and recommendations while reporting itself as "observation mode"  `[P0]`  `RESOLVED 2026-08-26`

**Symptom.** With `trading.universe.dynamic-expansion.enabled: false`, Stage B still wrote `multibagger_scores` rows under today's date and recorded MULTIBAGGER recommendations for anything scoring ≥ 65.

**Root cause.** `UniverseExpansionService.processQueue()` called `MultibaggerScreenerService.screenSingleStock()`, which unconditionally calls `persistScores()` — and `persistScores` also records recommendations above the threshold. The `enabled` flag gates only the *merge into the screening universe* (`activeDynamicSymbols()`), which is a much narrower thing than "observation mode" implies.

**Blast radius.** Everything that walks `findScreeningDates()` — the dashboard screener view, the morning briefing, `/api/multibagger/history`, the insider-capture symbol budget — silently absorbed unreviewed names from a funnel documented as inert. Worse, the accuracy tracker did: 17 MULTIBAGGER recommendations were issued for stocks the engine never surfaced through its normal path, most of them 90+ momentum names, which would have been measured at 30/90/180/365 days and folded into the engine's own IC. SPEC §30.7 already warns that the funnel is circular and its promotion rate must not be read as validation; this defect fed that circularity straight into the measurement system meant to judge it.

**Fix.** Split the entry point: `evaluateSingleStock()` scores without persisting, `screenSingleStock()` retains the publishing behaviour, both delegating to `screenOne(symbol, persist)`. Stage B uses the evaluating form; a queued symbol's score lives on `dynamic_universe.lastCompositeScore` until it is promoted *and* the feature is enabled, at which point the normal daily screening picks it up like any other universe member. No separate publish path is needed or wanted.

**Data cleanup.** The rows already written were removed, not just prevented: 19 `multibagger_scores` rows and 17 `recommendations` on 2026-08-26 (0 outcomes had been computed yet). Backed up to `logs/b035-cleanup-backup-2026-08-26.txt` before deletion.

> **NSE:PAYTM was deliberately spared.** It appeared in `dynamic_universe` but had 91 rows of legitimate screening history going back to 2026-03-18 — it was already a screened stock and should never have entered the funnel at all. That is a separate defect (B-053); deleting its rows would have destroyed five months of real history to clean up one day of contamination.

**Files.** [MultibaggerScreenerService.java](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java) (`evaluateSingleStock`, `screenOne`), [UniverseExpansionService.java](src/main/java/com/example/trading/universe/UniverseExpansionService.java) (`processQueue`).

**Verification.** Post-cleanup counts: 0 `multibagger_scores` and 0 `recommendations` rows for any funnel symbol except PAYTM; PAYTM's 92 rows intact; the day's curated screening still holds 289 rows. Re-running `POST /api/universe/process-queue` leaves the row count unchanged.

---

### B-053 — The funnel re-discovered stocks the screener was already screening daily  `[P1]`  `RESOLVED 2026-08-26`

**Symptom.** NSE:PAYTM was coarse-scanned, queued, deep-scored and promoted on 2026-08-26 as a newly discovered stock — while carrying 91 `multibagger_scores` rows dating back to 2026-03-18.

**Root cause.** The screened universe is `Nifty200WatchlistService` tiers **merged with** `MultibaggerScreenerService.SCREENING_UNIVERSE`, a second hardcoded ~90-name list (`resolveUniverse()`, line 342). `UniverseExpansionService.knownSymbols()` read only the first of the two, so every stock present in the legacy list but absent from the tier list looked unseen.

**Blast radius.** Three effects. (1) Deep-scoring budget — capped at 10 symbols/day — was spent re-analysing stocks already analysed that morning. (2) A stock could hold both curated and dynamic membership, with `enforceCap()` able to "retire" something that is not actually in the funnel's gift to remove. (3) It quietly overstates SPEC §30.7's headline: some of the "1,598 symbols the screener had never looked at" were being looked at every day. Discovered while scoping the B-035 cleanup — the pre-existing history is what made PAYTM stand out from the other 19 funnel symbols.

**Fix.** `knownSymbols()` now unions both lists via `screenerService.getScreeningUniverse()`.

**Files.** [UniverseExpansionService.java](src/main/java/com/example/trading/universe/UniverseExpansionService.java) (`knownSymbols`).

**Verification.** `UniverseFunnelStateTest.screeningUniverseIsAlsoKnown` pins that a symbol present only in the screener's own list is treated as known. The stale PAYTM funnel row remains in `dynamic_universe` as a record of what happened (rows are retired, never deleted — SPEC §30.3).

---

### B-036 — The retirement rule had no caller, and the obvious fix would have made it cosmetic  `[P1]`  `RESOLVED 2026-08-26`

**Symptom.** `UniverseExpansionService.retireWeakSymbols()` was referenced nowhere in `src/main`. SPEC §30.3's "below 45 for 8 consecutive weekly runs → retire" never executed.

**Root cause.** The method was written and tested against the spec but never wired into the Saturday run. A promoted symbol could therefore only ever leave the universe via `enforceCap()`, which evicts on `lastCompositeScore` — a value frozen at promotion time for anything never re-screened, so the cap was ranking stale numbers.

**Blast radius.** Latent rather than active: with `enabled: false` nothing had reached the screening universe yet. Had the flag been flipped, the dynamic universe would have grown monotonically to its 100-symbol cap and then evicted on stale scores, with no quality-based ageing at all.

**Fix.** Called at the end of `weeklyFullScreening()` with that run's composites. An empty score map (a failed run) **skips** the pass with a WARN rather than counting a weak week against every promoted symbol — eight failed Saturdays would otherwise retire the entire dynamic universe for a reason unrelated to the stocks.

> **The review's proposed fix needed a second half.** It suggested building `knownSymbols` from *active* rows only, which fixes the shrinking-pool problem but makes retirement pointless: `runCoarseScan` skips anything in `known`, so a symbol retired on Saturday is re-discovered by the very next scan on the same price/volume filters that promoted it, and put straight back. Added `retirement-cooloff-months` (default 6): retired rows stay excluded for the cool-off, then become eligible again. Neither permanent exclusion (pool shrinks forever) nor immediate re-entry (retirement is theatre) is correct.

**Files.** [MultibaggerScheduler.java](src/main/java/com/example/trading/multibagger/MultibaggerScheduler.java) (`weeklyFullScreening`), [UniverseExpansionService.java](src/main/java/com/example/trading/universe/UniverseExpansionService.java) (`knownSymbols`), [UniverseConfig.java](src/main/java/com/example/trading/universe/UniverseConfig.java) (`retirementCooloffMonths`).

**Verification.** `UniverseFunnelStateTest` covers the cool-off in both directions (3 days retired → still known; 9 months retired → eligible), promoted-always-known, and the empty-score guard. First live exercise is the Saturday 2026-08-29 run.

---

### B-038 — The auditor → HIGH_RISK cap was unreachable in the screener, and the announcement feed was truncated to 5 items  `[P1]`  `RESOLVED 2026-08-26`

**Symptom.** SPEC §32.4 states that an auditor problem forces the composite cap at 54. In the screening run — the only place that decides what gets recommended — it could not fire: `forensicScreenService.screen(symbol, false)` never fetched announcements.

**Root cause.** Two layers. The screener passed `includeAnnouncements=false` to avoid one live NSE call per stock across ~290 stocks. And `fetchCorporateAnnouncements()` truncates to the **last 5 filings**, which for an active company can be a fortnight — so even where the scan did run (the holdings email), an auditor resignation aged out of view within days of being filed.

**Blast radius.** The system's strongest safety guarantee was documented, tested in isolation, and inert in production. A company whose auditor had resigned could score 90 and be recommended, with the flag never consulted.

**Fix.** Two changes. (1) The screener now scans announcements for stocks whose pre-cap composite is **≥ 55** — the auditor flag's only job is the cap at 54, so below that a network call cannot change an outcome, and above it the guarantee has to hold. This is naturally bounded (~60–100 of ~290 stocks) and adds one NSE call to a per-stock workup that already makes several. (2) The forensic scan reads `fetchAnnouncementRecords(symbol, 40)` instead of the 5-item helper.

**Ordering note.** This fix was applied **after** B-037. Doing it first would have spread the "unqualified opinion" false positive from ~35 holdings to the whole universe, where it forces the cap — suppressing genuine candidates system-wide.

**Files.** [ForensicScreenService.java](src/main/java/com/example/trading/fundamentals/ForensicScreenService.java) (`ANNOUNCEMENT_SCAN_LIMIT`, `AUDITOR_SCAN_MIN_COMPOSITE`, `screen`), [MultibaggerScreenerService.java](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java).

**Verification.** 189 tests green including the B-037 pair that guard against the false positive this fix would otherwise amplify. Screening wall-clock to be measured on the next full run.

---

### B-039 — `GET /api/insider/{symbol}` was documented DB-only but hit XBRL and Kite on every cold cache  `[P2]`  `RESOLVED 2026-08-26`

**Symptom.** The endpoint's Javadoc, CLAUDE.md and SPEC §28.5 all describe it as DB-only and dashboard-safe. It called `StockValuationService.getValuationData()` to size the insider pulse against market cap — an XBRL fetch plus a Kite quote on a cold cache.

**Root cause.** The valuation cache is 30 minutes and this app restarts daily, so the first call of each day always paid the full cost.

**Blast radius.** No user-visible symptom yet — the endpoint is not wired into any page. The hazard was the documentation: Gotcha 17 exists because in this API a GET can email a report or start a 30-minute scan, and `SLOW_BUT_SAFE` in `api.js` only protects the on-demand path. A "DB-only" label is what the next person adding it to `stock.html` would rely on.

**Fix.** Market cap now comes from the most recent `multibagger_scores` row (same unit — crores). At most a day stale, which is immaterial for a percent-of-market-cap figure, and null for a never-screened stock — in which case the pulse reports event counts without a size verdict rather than inventing one. The `StockValuationService` dependency is gone from the controller.

**Files.** [InsiderController.java](src/main/java/com/example/trading/insider/InsiderController.java).

**Verification.** No `StockValuationService` reference remains in the insider package; 189 tests green.

---

### B-034 — NSE filings carry no comparative balance sheet, so the capex delta was unmeasurable and STEADY asserted a ratio it never computed  `[P2]`  `RESOLVED 2026-08-26`

**Symptom.** On the first live run of the capex-cycle signal (SPEC §31), `cwipPrior` came back null for **7 of 7** non-financial stocks tested (RELIANCE, TATASTEEL, JSWSTEEL, ULTRACEMCO, GRASIM, NTPC, TCS, ADANIPORTS) while `priorYearAvailable` reported **true**. `capexToDepreciation` was null in every case.

**Root cause.** The implementation plan states that "Ind-AS XBRL filings carry previous-FY comparatives in the same document, so ΔCWIP is computable from a single filing today". Measured against live data, that is **false for NSE's integrated-filing format**. The document declares a prior-year instant context, and `resolvePriorYearInstantContext` correctly resolved it — but a fact count against that context returned **exactly 1**. There is no comparative balance sheet to read.

**Blast radius.** Three layers, worst last.
1. `EXPANSION_UNDERWAY` requires rising CWIP and could therefore **never fire** — the headline verdict of the whole feature was dead code, and the feature degraded to a bare "CWIP intensity > 15%" threshold.
2. `capexToDepreciation` needs ΔPPE, so the INVESTING / HARVESTING bands were also unreachable; every stock resolved through the two fallback branches only.
3. Worst: the `STEADY` reason line read *"Capital spending is roughly in line with depreciation — maintaining capacity, not expanding it"* — a claim about a ratio that was **null**. The feature was printing an unmeasured quantity as a finding, in the beginner-facing wording the report shows the user. Same class as B-019 and Gotcha 21: a missing measurement rendered as a real one.

`priorYearAvailable: true` was itself part of the defect — it reported that a *context id* had resolved, not that any figures were usable, which is what made the gap invisible from the outside.

**Fix.**
1. `classifyCapexCycle` gained an overload accepting prior-year figures from outside the filing, and new `CapexCycleService` (package `fundamentals`) supplies them from **F5's `annual_fundamentals` table** — which records CWIP per financial year from both the XBRL pipeline and the user's history import. F4's data gap is closed by F5's table; the two features turn out to be one feature in two commits. All four call sites (screener, holdings report, Deep Research, endpoint) route through it.
2. The filing's own comparative still wins when present — it is the primary source; history is a reconstruction.
3. `priorYearAvailable` now means *usable figures exist*, not *a context resolved*.
4. `STEADY` states only what was measured: with no prior year it says the build is small and that spend "cannot be measured until a second year of accounts is on file".
5. `priorYearFactCount` is exposed on the capex endpoint permanently, so "comparative declared but empty" stays distinguishable from a parsing bug without another investigation.

**Files.** [NseDataService.java](src/main/java/com/example/trading/ai/NseDataService.java) (`classifyCapexCycle` overload, `priorYearFactCount`, STEADY wording), [CapexCycleService.java](src/main/java/com/example/trading/fundamentals/CapexCycleService.java) (new), [MultibaggerScreenerService.java](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java), [HoldingsReportService.java](src/main/java/com/example/trading/holdings/HoldingsReportService.java), [StockResearchService.java](src/main/java/com/example/trading/ai/StockResearchService.java), [StockResearchController.java](src/main/java/com/example/trading/api/StockResearchController.java).

**Verification.** `priorYearFactCount` measured as 1 on RELIANCE / TATASTEEL / NTPC, confirming the filing genuinely carries no comparative. Four new tests in `CapexCycleTest` pin the fix: the history fallback unlocking `EXPANSION_UNDERWAY`, the filing's comparative outranking history, `priorYearAvailable` reflecting usable data, and STEADY refusing to quote an unmeasured ratio. **Caveat carried forward**: until a second annual filing lands (or a history import supplies one), most stocks still have no prior year and will read INVESTING or STEADY on intensity alone. That is now stated in the reason line the user sees, rather than hidden.

---

### B-033 — Insider disclosure hash overflowed varchar(128) on long entity names; the fallback path hid the loss  `[P2]`  `RESOLVED 2026-08-25`

**Symptom.** 48 `ERROR: value too long for type character varying(128)` in one capture run, each followed by `Insider capture: batch save failed ... falling back to per-row`.

**Root cause.** `disclosure_hash` was built as the **raw concatenated natural key**, which includes `person_name`. Real NSE filings carry long entity names ("Sohan Devi Nand Lal Nuwal Family Trust ... acting through its trustee ..."), and `person_name` is itself `varchar(256)` — so the key routinely exceeded the 128-char hash column, failing the whole batch insert.

**Blast radius.** Two layers. The overflow dropped rows; the per-row fallback then caught every exception into `catch (Exception ignored)` on the reasoning that a unique-constraint race is harmless — which meant a genuinely failing insert was **indistinguishable from a duplicate** and the capture reported success. The endpoint returned `newPitRows: 1116` while silently losing an unknown number. Same failure shape as B-026: a bare success count cannot reveal a partial failure.

**Fix.** (1) The hash is now a **SHA-256 hex digest** — always 64 characters regardless of input length. (2) The per-row fallback counts failures and logs an ERROR naming the count and first cause, so a partial persist can never again look like a clean run. The table was truncated and re-captured, since the old rows carry the pre-fix hash format and would not dedupe against new ones.

**Files.** [InsiderDisclosureService.java](src/main/java/com/example/trading/insider/InsiderDisclosureService.java) (`hash`, `persistNew`).

**Verification.** Re-capture on a clean table completes with 0 ERRORs since boot; three tests pin the hash contract (fixed 64 chars, long-entity-name safety, distinct filings hash distinctly).

---

### B-030 — New MULTIBAGGER signals were unmeasurable: `computeDimensionIC` never reads the sidecar  `[P1]`  `RESOLVED 2026-08-25`

**Symptom.** The early-discovery plan specified, for four separate features, "record the sub-score into the `recommendation_dimensions` sidecar so `computeDimensionIC` can judge it." That does nothing for MULTIBAGGER picks.

**Root cause.** `RecommendationAccuracyService.multibaggerDataset()` builds its dimension list as a **hardcoded 8-element `List.of(...)`** read off `multibagger_scores` columns. The `recommendation_dimensions` sidecar exists only for QUANT_DISCOVERY, which has no score table of its own. Sidecar rows written against a multibagger recommendation are silently ignored — no error, no warning, no row in the IC output. Compounding it, the dims map is `Map<String,Integer>`, so a `String` verdict column or a `Double` percentage is not usable as a sub-score even if the plumbing had worked.

**Blast radius.** Every new scoring signal would have shipped believing it was being measured, while producing no IC rows at all. The entire safety argument for adding signals — "measure it before you trust it" (SPEC §25.5) — was unenforceable. This is precisely how the Institutional Interest dimension sat at a constant 40 for three months without anyone noticing.

**Fix.** Added `Under-Discovery` and `Insider Pulse` to the dimension list and to the sample map, with a class-level note on `multibaggerDataset` stating the two-step requirement for any future signal: a nullable `Integer` column on `MultibaggerScoreEntity` **and** an entry in the list. New signals are measured from the day they ship even while contributing zero points to the composite — which is the point of shadow mode (SPEC §28.4).

**Files.** [RecommendationAccuracyService.java](src/main/java/com/example/trading/intelligence/recommendation/RecommendationAccuracyService.java) (`multibaggerDataset`), [MultibaggerScoreEntity.java](src/main/java/com/example/trading/persistence/MultibaggerScoreEntity.java) (`insiderPulseScore`, `underDiscoveryScore`).

**Verification.** Both columns exist and are nullable in `information_schema`; `Under-Discovery` and `Insider Pulse` now appear as rows in `GET /api/accuracy/dimension-ic` (with `sampleSize` 0 until the first screening writes them, which is correct — an empty cell is not a fabricated one).

---

### B-031 — NSE publishes insider filings dated in the future; they would never age out of a rolling window  `[P2]`  `RESOLVED 2026-08-25`

**Symptom.** First live insider capture stored three SOLARINDS rows with `transaction_date = 2026-11-09` — after today (2026-08-25) **and after their own `disclosure_date` of 2026-03-11**. A transaction cannot be disclosed eight months before it happens.

**Root cause.** Bad data in NSE's PIT feed (`acqfromDt`), not a parsing error — the surrounding fields parse correctly and the disclosure date is sane. The ingestion accepted whatever date the filing carried.

**Blast radius.** The Insider Pulse verdict is computed over a trailing 90-day window. A future-dated row satisfies `transaction_date >= today - 90` **permanently**, so it would be counted as "recent insider activity" forever, inflating event counts and net value indefinitely. In this instance the three rows were `GIFT` mode and therefore excluded from scoring anyway, so no verdict was distorted — but nothing prevented the next such row from being a `MARKET_PURCHASE`.

**Fix.** `InsiderDisclosureService.toEntity` now rejects future transaction dates: falls back to the disclosure date when that is usable, otherwise drops the row with a WARN naming the symbol and the bad date. The three stored rows were deleted.

**Files.** [InsiderDisclosureService.java](src/main/java/com/example/trading/insider/InsiderDisclosureService.java).

**Verification.** `SELECT count(*) FROM insider_disclosures WHERE transaction_date > CURRENT_DATE` returns 0. Two tests pin the behaviour (`futureTransactionDateFallsBackToDisclosure`, `futureDatedWithNoFallbackIsDropped`).

---

### B-032 — Saturday screening had no broker-token safety net  `[P2]`  `RESOLVED 2026-08-25`

**Symptom.** Latent, found by review rather than failure. The weekly full screening moved to 08:00 SAT (SPEC §3.4 carve-out). `TokenManagementService.dailyTokenRefresh` is `MON-FRI`, so on Saturday the *only* login attempt is the fire-and-forget one at app startup (07:50), with 3 retries.

**Blast radius.** If those retries all failed, the 08:00 screening would run with a placeholder token, every Kite call would return `TokenException`, and a full run of garbage scores would be persisted looking like a completed screening. On weekdays the 09:45 cron would have recovered it; Saturday has no second chance. A fabricated run silently poisons the score history *and* the accuracy tracker — the same failure shape as B-026, where a bare success count concealed a partial failure.

**Fix.** `weeklyFullScreening()` aborts with an ERROR naming the cause if `hasValidToken()` is false. A missing weekly run is recoverable by re-running `POST /api/multibagger/screen`; a fabricated one is not.

**Files.** [MultibaggerScheduler.java](src/main/java/com/example/trading/multibagger/MultibaggerScheduler.java).

---

### B-028 — Outcomes measured late were stored as on-time results; 20.8% of 30d and 29.4% of 90d rows carried a wrong horizon  `[P1]`  `RESOLVED 2026-08-24`

**Symptom**: found while auditing B-027's call sites, not by any alert. `recommendation_outcomes` rows labelled `horizon_days = 30` had actually run **30–124 days** (avg 36); `horizon_days = 90` rows ran 90–124.

```
horizon_days | outcomes | min_actual | avg_actual | max_actual
          30 |     9956 |         30 |         36 |        124
          90 |     3249 |         90 |         94 |        124
```

**Root cause**: `findDueForOutcome` selects every pick *older than* the cutoff that has no outcome row — not picks sitting on their anniversary. `RecommendationOutcomeScheduler` then priced them with `LocalDate.now()` and wrote `horizonDays = 30`. A pick issued 200 days ago therefore produced a 200-day return filed as a 30-day one. B-022's backfill amplified it: 1,247 previously-unmeasurable picks all became due at once and were measured on a single day.

**Blast radius**: every SPEC §23 calibration number computed from `findBySourceAndHorizon` — hit rate, mean return, excess vs Nifty, and per-dimension Information Coefficient — silently described a longer holding period than it claimed, for **20.8%** of 30d rows and **29.4%** of 90d rows. This includes the IC figures quoted in B-023 and B-024, so **those two must be re-read after this fix lands**; do not act on the pre-2026-08-24 IC values.

**Fix**:
- New `days_elapsed` column on `RecommendationOutcomeEntity` records how long the return really ran, independent of the label it is filed under. ([RecommendationOutcomeEntity.java](src/main/java/com/example/trading/persistence/RecommendationOutcomeEntity.java))
- `RecommendationOutcomeScheduler` populates it on write and now measures a pick only inside `horizon + MEASUREMENT_GRACE_DAYS` (7 days — enough for weekends, holidays and short downtime). Picks that drift beyond it are left unmeasured and counted in a new log line: an honest gap beats a mislabelled number. ([RecommendationOutcomeScheduler.java](src/main/java/com/example/trading/intelligence/recommendation/RecommendationOutcomeScheduler.java))
- `findBySourceAndHorizon` excludes rows whose `days_elapsed` exceeds `horizon + grace`, and excludes null ones rather than trusting them. ([RecommendationOutcomeRepository.java](src/main/java/com/example/trading/persistence/RecommendationOutcomeRepository.java))
- `SchemaMigrationRunner.backfillOutcomeDaysElapsed()` backfills existing rows from `measured_date - issued_date` and logs how many are drifted. Rows are **preserved, not deleted** — the price and date on them are real, only the label was wrong, so they stay available for a longer-horizon analysis.

**Lesson**: a horizon is a claim about elapsed time, so store the elapsed time rather than inferring it from the label. The same rule as B-026: record what actually happened next to what was intended, and reconcile the two in one place.

---

### B-027 — Price failures were silent, and 6 dead tickers burned 152 lookups per outcome run forever  `[P1]`  `RESOLVED 2026-08-24`

**Symptom**: the per-source tally added for B-022 immediately earned its keep — the 2026-08-24 15:22 run logged `MULTIBAGGER -> persisted=156, skipped=154`, a 50% skip rate the old aggregate-only line would have hidden. The 154 were not 154 broken picks but **6 delisted/renamed tickers repeated across ~25 historical screening dates**: `BLISSGVS` (43), `MTARTECH` (41), `PPAP` (32), `JBCHEPHARM` (18), `ONMOBILE` (16), `GSPL` (2) = 152, plus 2 MTARTECH rate-limit failures.

**Root cause (three separate defects)**:
1. `MarketDataService.fetchLatestPrice` fell through to `return 0.0` in **total silence** when Kite answered `{status=success, data={}}`. Only the `catch` branch logged, so a permanently dead symbol was indistinguishable from a transient blip — a direct violation of CLAUDE.md gotcha #15 sitting in the price path.
2. The six tickers were still in `Nifty200WatchlistService`'s pool, and because no outcome row is ever written for an unpriceable pick, `findDueForOutcome` re-selected them **every single day, forever**.
3. Kite bursts were only handled reactively. `executeWithRetry` already retried 429s (5 attempts, exponential backoff) yet those retries were **exhausted 31 times** on 2026-08-24 — 17 at 09:30, 10 at 15:00, 4 at 15:22 — each costing a real quote. The bursts come from the 4-thread scheduler pool firing several Kite-heavy jobs on the same cron minute, contradicting CLAUDE.md's claim that a pool of 4 is "small enough that several Kite-heavy scans never run in parallel and trip the broker 429 limit".

**Blast radius**: ~152 wasted Kite `/quote` calls per outcome run feeding the 429 pressure that *did* destroy real data elsewhere; a permanently inflated `skipped` count that would have masked the next genuine price-fetch regression; and at 09:30 the losses hit the morning briefing's inputs. MULTIBAGGER's measured outcomes were themselves unaffected — the 156 that persisted are correct.

**Fix**:
- Every failure path in `fetchLatestPrice` now logs at WARN and distinguishes empty payload / wrong key / missing `last_price`. ([MarketDataService.java](src/main/java/com/example/trading/marketdata/MarketDataService.java))
- The six tickers are removed from the pool with a comment naming the reason, per gotcha #14 (never guess a replacement). ([Nifty200WatchlistService.java](src/main/java/com/example/trading/scanner/Nifty200WatchlistService.java))
- A process-wide pacing gate (`reserveSlot`, 350 ms ≈ 2.9 req/s) now spaces every outbound Kite request at the single `executeWithRetry` choke point, so concurrent scheduler threads queue instead of bursting. Non-blocking by design — implemented with `delaySubscription` inside a `Mono.defer` so each retry claims its own slot and no scheduler thread sleeps. Covered by `KiteRequestPacingTest` (4 tests, incl. a 12-thread race asserting no two callers share a slot). ([KiteBrokerClient.java](src/main/java/com/example/trading/broker/kite/KiteBrokerClient.java))
- The B-028 grace window independently stops unpriceable picks being retried forever, so this cannot silently recur with a different symbol.

**Revised from the original write-up**: the first draft of this entry prescribed returning `null` instead of `0.0`. Auditing the call sites showed **113 of 125** `getCurrentPrice` callers dereference the result directly and would NPE, so changing the contract is a separate refactor, not a bug fix. The `0.0` sentinel is kept and now **documented on the method** as a hard contract (`<= 0` means "no price, never a real quote"); the silence, which was the actual defect, is fixed.

**Verification**: `mvn test` → 64 tests pass. Post-restart, watch for `No price for NSE:X: broker returned an empty quote payload` (previously absent entirely) and for the 09:30 / 15:00 429 clusters to disappear from the next trading day's log.

---

### B-026 — Nullable dimensions hit legacy NOT NULL constraints; 210 of 288 scores silently failed to persist  `[P0]`  `RESOLVED 2026-08-23`

**Symptom**: found while verifying the previous day's fixes, not by any alert. The 2026-08-22 17:59 screening logged `Screened: 288` and `Persisted 78 scores to database` — a **210-row shortfall** — with the only other evidence being 47 `SqlExceptionHelper` lines:

```
ERROR: null value in column "sector_tailwind_score" of relation "multibagger_scores" violates not-null constraint
```

`GET /api/multibagger/history?date=2026-08-22` returned 86 rows for a 288-stock run.

**Root cause**: introduced by the same-day change making the four fundamental dimensions nullable (`int` → `Integer`, so an unmeasurable dimension is excluded from the composite rather than scored a fake neutral). Hibernate `ddl-auto=update` **adds** columns but never **relaxes** an existing constraint, so `valuation_score`, `institutional_interest_score` and `sector_tailwind_score` kept the NOT NULL they were created with as primitives. Postgres then rejected every row carrying a null dimension — which, after the Sector Tailwind fix, was 206 of 288 stocks.

This is the exact mirror of the trap already recorded in CLAUDE.md ("Integer-Not-Null Migration Bug"): adding a primitive column to a populated table fails, and so does relaxing one. The existing note covered only the first direction.

**Fix**:
- `SchemaMigrationRunner.dropObsoleteNotNullConstraints()` issues `ALTER COLUMN ... DROP NOT NULL` for the three columns, guarded by an `information_schema` check so it is idempotent. ([SchemaMigrationRunner.java](src/main/java/com/example/trading/persistence/SchemaMigrationRunner.java))
- `persistScores` now logs at **ERROR with the shortfall and the first exception message** when any row fails. It previously logged failures at DEBUG and reported only the success count, which is why a 73% data-loss event read as a normal run. ([MultibaggerScreenerService.java](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java))

**Verification**: on restart —
```
Schema migration: dropped NOT NULL on multibagger_scores.valuation_score
Schema migration: dropped NOT NULL on multibagger_scores.institutional_interest_score
Schema migration: dropped NOT NULL on multibagger_scores.sector_tailwind_score
```

Then a full re-run of the screening on 2026-08-23 17:25 —
```
Multibagger Screening: Persisted 288 scores to database
Multibagger Screening: Completed. Screened: 288, Quality-rejected: 73, Failed: 6,
                       Candidates (top 20.0% and score>=60): 64 (22% of screened)
```
`Screened == Persisted == 288` (was 288 vs 78), **0** `violates not-null` lines in the whole run (was 47), and `GET /api/multibagger/history?date=2026-08-23` returns **288** rows (was 86 for the same universe). Crucially the rows carry the nulls that used to be rejected — 206 null `sector_tailwind_score`, 13 null `valuation_score` — proving the constraint drop, not merely that fewer nulls occurred.

**Blast radius pre-fix**: one screening run's worth of data (2026-08-22) is 73% incomplete in `multibagger_scores`. That table feeds thesis-drift/score-decay trends, per-dimension IC, and the §23 accuracy loop — so the gap is real but confined to a single run, and the affected day was a Saturday manual run rather than a scheduled screening.

**Lesson**: "screened N" and "persisted N" are different numbers and must be reconciled in the same log line. A success count alone cannot reveal a partial failure.

---

### B-022 — SECTOR_REVERSAL recorded 1,247 picks but produced ZERO measured outcomes for four months  `[P0]`  `RESOLVED 2026-08-22`

**Symptom**: `GET /api/accuracy/by-source/SECTOR_REVERSAL` returned `sampleSize: 0` at every horizon. One of the three recommendation engines was completely unmeasured — no hit rate, no IC, absent from the weekly accuracy email — while MULTIBAGGER had 8,261 outcomes at 30d.

**Root cause**: a **symbol-format mismatch**, not a capture failure. Capture worked fine (1,247 picks recorded since 2026-04-20), but `EarlyUpsideScanner` sets the **bare** symbol on its DTO (`AXISBANK`) while MULTIBAGGER and QUANT_DISCOVERY pass exchange-prefixed symbols (`NSE:AXISBANK`). Kite's `/quote` returns `{status=success, data={}}` for a bare symbol, so `RecommendationOutcomeScheduler` got a null price and hit `skipped++; continue;` for every single pick.

Two things kept it invisible for four months:
1. The scheduler logged only an **aggregate** `persisted=578, skipped=1499` — a plausible-looking ratio that never revealed one source failing 100% of the time.
2. `MarketDataService.getCurrentPrice` used `priceCache.computeIfAbsent`, and `fetchLatestPrice` returns `0.0` on failure — so the failed lookup was **cached as 0.0 for the JVM's lifetime**. Once any sector scan touched a bare symbol earlier in the day, the 15:22 outcome run got the poisoned 0.0 with no broker call and no log line at all.

**Fix**:
- Normalise at the single capture choke point — [RecommendationTracker.java](src/main/java/com/example/trading/intelligence/recommendation/RecommendationTracker.java) prefixes `NSE:` when no exchange is present. Fixes all current and future engines without touching the DTOs and email templates that legitimately use bare names.
- One-shot backfill of the 1,247 existing rows in [SchemaMigrationRunner.java](src/main/java/com/example/trading/persistence/SchemaMigrationRunner.java) (idempotent — matches only unprefixed rows).
- [MarketDataService.java](src/main/java/com/example/trading/marketdata/MarketDataService.java) no longer caches a non-positive price, so a failed lookup is retried instead of poisoning the cache permanently.
- [RecommendationOutcomeScheduler.java](src/main/java/com/example/trading/intelligence/recommendation/RecommendationOutcomeScheduler.java) now tallies per source and emits a **WARN when a source skips 100% of its due picks**. Also split the market-hours guard onto the `@Scheduled` entry point so the manual `POST /api/accuracy/refresh-outcomes` actually works — it previously returned `{"status":"ok"}` having silently no-opped outside 09:15–15:30.

**Verification** (live):
```
Schema migration: prefixed 1247 bare recommendation symbols with 'NSE:' (B-022)
Recommendation outcomes: persisted=2185, skipped=151
Recommendation outcomes: SECTOR_REVERSAL -> persisted=1435, skipped=0
Recommendation outcomes: MULTIBAGGER     -> persisted=737,  skipped=151
Recommendation outcomes: QUANT_DISCOVERY -> persisted=13,   skipped=0
```
`by-source/SECTOR_REVERSAL` now returns sampleSize **1114** @30d (hit rate 53.9%, IC −0.061) and **321** @90d (54.8%, IC −0.047).

**Blast radius pre-fix**: a third of the recommendation engine was flying blind. Worse, the newly-visible numbers show SECTOR_REVERSAL's IC is **negative at both horizons** — its score is mildly *inversely* related to realised return, so it was actively unhelpful and nobody could have known.

---

### B-021 — Delivery-% wealth signal dead for the entire universe  `[P1]`  `RESOLVED 2026-08-22`

**Symptom**: `deliveryPercent` null for **287/287** screened stocks, disabling the delivery-% arm of the wealth-signal bonus (SPEC §12.7: STRONG_HANDS +2 / SPECULATIVE −1) and the "Delivery %" line in Deep Research.

**Root cause**: sourced from `/api/quote-equity?section=trade_info`, which NSE bot-walled (B-018). It also burned **722 doomed HTTP calls per screening run** (two per stock).

**Fix**: new [NseDeliveryDataService](src/main/java/com/example/trading/ai/NseDeliveryDataService.java) reading NSE's daily full bhavcopy at `nsearchives.nseindia.com/products/content/sec_bhavdata_full_DDMMYYYY.csv`, field `DELIV_PER`. Strictly better than what it replaces:
- **One request covers the whole market** (~3,470 symbols) instead of ~300 per-stock calls — a full run costs a single ~400ms fetch.
- No cookie jar or session warm-up; the archive host needs only a browser User-Agent.
- Averages over 5 sessions, smoothing the single-day noise that could flip a STRONG_HANDS/SPECULATIVE verdict.

**Gotchas encoded in the implementation**:
- `BE`/`BZ` trade-to-trade rows carry a literal `-` for delivery — parsed as null, not zero.
- **HTTP 200 does not imply fresh data**: Sunday 16-Aug-2026 returns a complete CSV whose `DATE1` is `14-Aug` — a silently stale copy of the prior Friday. The parser validates `DATE1` against the requested date and skips mismatches, otherwise a session would be double-counted into the average.
- The file publishes after close, so a same-day fetch during market hours 404s; data is T-1 onward.

**Verification**: `Delivery data: loaded 2647 symbols averaged over 5 session(s)`; RELIANCE 57.49% (single-day 20-Aug value 47.55% cross-checked identical to the per-symbol `historicalOR` feed).

---

### B-019 — Multibagger scoring weights sum to 1.15, inflating every composite by 15%  `[P0]`  `RESOLVED 2026-08-22`

**Symptom**: the daily screen flagged **201 of 291 stocks (69%)** as candidates on 2026-08-20 and 211 of 296 (71%) on 2026-08-18. A screen that passes ~70% of its universe has no selectivity. 41% of the universe earned `STRONG_MULTIBAGGER`; 30% earned grade A+.

**Root cause**: the eight scoring weights are `@ConfigurationProperties`, so [application.yml](src/main/resources/application.yml) overrides them **individually** — any dimension omitted from the yml silently keeps its Java default. The yml block was written before the Financial Quality dimension was added (SPEC §12.5 rebalance, 2026-04-19) and listed only the original seven weights, which sum to exactly 1.00. `financial-quality-weight` was never added, so it kept `MultibaggerConfig`'s default of `0.15`.

Effective sum = **1.15**, i.e. `composite = 1.15 × (weighted mean of the 8 dimensions)`. The yml's own comment said "must sum to 1.0", which is what made it invisible — the seven listed values do.

Consequence: every threshold meant something 15% looser than it read.

| stated threshold | actual dimension-average required |
|---|---|
| 60 (candidate) | 52.2 |
| 65 (§23 recommendation capture) | 56.5 |
| 80 (`STRONG_MULTIBAGGER`) | 69.6 |

**Fix**: set all eight weights explicitly in the yml, matching the SPEC §12.5 rebalance (0.18/0.12/0.12/0.12/0.13/0.10/0.08/0.15 = 1.00), and added a `@PostConstruct` guard in [MultibaggerConfig.java](src/main/java/com/example/trading/multibagger/MultibaggerConfig.java) that **refuses to boot** if the sum drifts more than 0.001 from 1.0. Failing fast is deliberate — a silently mis-scaled score produces plausible-looking but systematically wrong recommendations, and the §23 accuracy loop cannot detect a uniform scale factor (it shifts every score equally, so relative ranking looks fine).

**Blast radius pre-fix**: every `multibagger_scores` row ever written carries an inflated composite. The `recommendations` table captured picks at an effective threshold of 56.5 rather than 65, so the §23 hit-rate and IC statistics were computed over a far less selective pick set than intended — which is consistent with the measured 30-day IC of 0.075, below the 0.10 "useful signal" bar. Historical scores are not retroactively comparable to post-fix ones.

---

### B-020 — HIGH_RISK composite cap applied before the bonuses it is meant to gate  `[P1]`  `RESOLVED 2026-08-22`

**Symptom**: SPEC §12.5 guarantees a `HIGH_RISK` financial-quality stock is "hard-capped at 54, so structurally fragile balance sheets can never cross the 65-point recommendation threshold". On the 2026-08-20 run, 4 of 15 HIGH_RISK stocks finished **above** the cap: MPHASIS 62, INDIGO 60 (ROE −34%), SHOPERSTOP 59 (ROE −12%), PVRINOX 55.

**Root cause**: the cap ran at the top of the bonus chain, before six post-composite adjustments worth up to **+47** (market cap +5, earnings +13, insider +7, analyst +5, wealth +10, capital efficiency +12). A stock capped to 54 could immediately claw back past the threshold. `QuantitativeDiscoveryService` already applies its equivalent cap after all scoring — the two engines disagreed.

**Fix**: moved the cap to after the entire bonus chain in [MultibaggerScreenerService.java](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java), with a comment at the old site explaining why it must stay last.

**Blast radius pre-fix**: fragile balance sheets could be emitted as candidates. None crossed 65 on the sampled run, so §23 capture was not yet polluted — but MPHASIS at 62 was three points away, and the guarantee SPEC advertises was simply not true.

---

### B-018 — NSE `quote-equity` 403s for every stock, nulling PE / market cap / industry and killing the reverse-DCF outright  `[P0]`  `RESOLVED 2026-08-22`

**Symptom**: `Using hardcoded Sector PE fallback for {symbol}` WARN for **all 374 distinct symbols** in the 18–20 Aug audit window. Underneath it, at DEBUG level: `NSE API call failed for {symbol}: 403 Forbidden from GET https://www.nseindia.com/api/quote-equity` — **1,898 calls, 1,898 failures, zero successes**. Also visible as `MarketCap=null Cr` ×104.

The warning badly under-sold the damage. It reads like a minor sector-PE degradation, but `fetchFromNSE` returns null *as a whole* on failure, so **stock PE, market cap, EPS and industry were all null for every stock**, for an unknown period before 18 Aug.

**Root cause**: NSE put `/api/quote-equity` behind Akamai bot protection. This is endpoint-specific, not a session problem — verified empirically: with one warmed cookie jar, `/api/fiidiiTradeReact` returns 200 while `/api/quote-equity` returns 403 on the very next request. Sibling endpoints `equity-meta-info`, `equity-stockIndices` and `search/autocomplete` now 404 (NSE restructured their API surface). Header/referer/`Sec-Fetch-*` tuning and warming `get-quotes/equity?symbol=X` first were all tried and all still 403.

**Fix**: stop depending on a hostile endpoint. Valuation is now **computed from data the app already fetches reliably**:
- Shares outstanding = `PaidUpValueOfEquityShareCapital / FaceValueOfEquityShareCapital`, read from the integrated-filing XBRL (B-017). Present in **both** the `INDAS` and `BANKING` taxonomies. Exact, and immune to exceptional items — unlike back-solving shares from profit÷EPS, which overstated Reliance's market cap by 18% (₹21.0 L cr vs the correct ₹17.8 L cr).
- `marketCapCr = price × sharesCr`, price from Kite via `MarketDataService`.
- `stockPe = marketCapCr / ttmProfit` — deliberately *not* `price / EPS`, so PE still resolves for filers whose EPS element we can't parse.
- Added the BANKING taxonomy's EPS aliases (`BasicEarningsPerShareAfterExtraordinaryItems`, `...BeforeExtraordinaryItems`). Without these, EPS was null for **every bank**.
- Out-of-bounds guard (PE ≤0 or >500, mcap <₹10 cr or >₹30 L cr) discards implausible results rather than letting them poison a score.
- **Circuit breaker** on the NSE call: opens after 10 consecutive failures, re-arms after 6h. Stops ~1,100 doomed HTTP calls per screening run while still auto-recovering if NSE ever unblocks.
- Sector PE can now be a **live median of peer PEs** when the caller supplies a sector hint (`getValuationData(symbol, sectorHint)`), falling back to the hardcoded table below 5 samples. Bucketing and lookup use the same taxonomy by construction, avoiding the mismatch behind CLAUDE.md "Critical Bug Fixes" #9.

Files: [StockValuationService.java](src/main/java/com/example/trading/holdings/StockValuationService.java), [NseDataService.java](src/main/java/com/example/trading/ai/NseDataService.java) (share count + bank EPS aliases), [MultibaggerScreenerService.java](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java) (passes sector hint).

**Verification** (live, post-restart — share counts cross-checked against real outstanding shares):

| Symbol | Shares (cr) | Price | Market cap | PE | Was |
|---|---|---|---|---|---|
| RELIANCE | 1,353.3 | 1,316.0 | ₹17,80,943 cr | 20.2 | null |
| HDFCBANK | 1,540.1 | 726.95 | ₹11,19,598 cr | 13.8 | null (no bank EPS) |
| ICICIBANK | 717.5 | 1,420.0 | ₹10,18,807 cr | 17.1 | null |
| SBIN | 923.1 | 1,048.7 | ₹9,68,013 cr | 11.0 | null |
| ITC | 1,253.0 | 269.4 | ₹3,37,545 cr | 16.7 | null |

`GET /api/research/valuation/NSE:RELIANCE` returns a real verdict again instead of `INSUFFICIENT_DATA` — the reverse-DCF had been dead for every stock in the universe (it hard-bails on a null market cap at [IntrinsicValuationService.java:62](src/main/java/com/example/trading/ai/IntrinsicValuationService.java#L62)).

**Blast radius pre-fix** — comparable to B-003 in severity:
- **Reverse-DCF returned `INSUFFICIENT_DATA` for 100% of stocks**, so the Valuation dimension's DCF half (50% of a 13%-weight dimension) contributed nothing and `multibagger_scores.dcf_*` were all null
- PE-deviation — the other half of that dimension — was also null
- Market-cap category wrong for everything, so the small-cap +5 / large-cap −3 bonus misfired universe-wide
- PEG (wealth-signal bonus) uncomputable
- `valuation.getIndustry()` null broke the NBFC/financial industry hint that `analyzeCapitalEfficiency` relies on to suppress misleading ROCE/D-E for lenders
- Peer-comparison PE/market-cap columns blank in Deep Research

**Caveats**:
- PE uses trailing twelve months including exceptional items — standard for trailing PE, but a demerger/one-off will distort it for a quarter.
- Loss-making companies return null (PE meaningless); the DCF already treats them as `NOT_APPLICABLE`.
- Sector PE stays hardcoded for callers that pass no sector hint (e.g. the single-symbol research endpoints). Only the screener passes one today. The hardcoded table can be badly wrong — ITC computes PE 16.7 against a hardcoded FMCG sector PE of 50, implying a −67% "cheap" deviation that the live median corrects.

### B-001 — `DataCleanupScheduler` throws every day at 15:28 IST  `[P0]`  `RESOLVED 2026-05-10`

**Symptom**: Daily scheduled cleanup at 15:28 IST aborted with `InvalidDataAccessApiUsageException: Argument [LocalDateTime] did not match parameter type [LocalDate]`. Caught in May 8 logs at line 19047. Cleanup of `signal_performance_tracking` and any subsequent table never ran.

**Root cause**: `SignalPerformanceEntity.signalDate` is `LocalDate`, but the JPA delete query bound the parameter as `LocalDateTime`. Hibernate refused to coerce.

**Fix**: Repository signature changed to `LocalDate`, caller updated. ([SignalPerformanceRepository.java:86](src/main/java/com/example/trading/analytics/SignalPerformanceRepository.java#L86), [DataCleanupScheduler.java:214-220](src/main/java/com/example/trading/scheduler/DataCleanupScheduler.java#L214))

**Verification**: Compiles cleanly. Empirical proof = next 15:28 IST Mon-Fri scheduler run with no exception.

**Blast radius pre-fix**: Tables grew unbounded — `signal_performance_tracking`, plus any cleanup steps that ran after `cleanupSignalPerformance()` in `performDailyCleanup()`. Not directly affecting investment decisions, but DB health was degrading silently.

---

### B-002 — Sector Reversal weekly model retrain fails on save `[P1]`  `RESOLVED 2026-05-10`

**Symptom**: Friday 15:20 IST trainer ran end-to-end, evaluated successfully (Acc=0.79, AUC=0.75), then died at the save step with `TypeError: _estimator_type undefined. Please use appropriate mixin to define estimator type.` Java logged ERROR + script exit code 1, then misleadingly emitted `=== SECTOR REVERSAL MODEL TRAINING COMPLETED ===` from a `finally` block. Model on disk never updated.

**Root cause**: Python 3.14 + newer xgboost dropped a sklearn-mixin attribute that `XGBClassifier.save_model()` relies on. The other ML scripts (`train_holdings_models.py`, `train_holdings_simple.py`) already used the booster path (`model.get_booster().save_model(...)`) and were unaffected.

**Fix**: One-line change to use the booster API. ([scripts/train_sector_reversal_model.py:212](scripts/train_sector_reversal_model.py#L212))

**Verification**: Reproduced the same call pattern in a Python REPL — `xgb.XGBClassifier(...).fit(X,y).get_booster().save_model('/tmp/test_xgb.json')` succeeds on the same Python 3.14 install.

**Blast radius pre-fix**: Sector reversal model was stuck on whatever was on disk before the breakage (likely from when older xgboost was installed). Sector reversal predictions stayed on a frozen model — degraded but not actively wrong.

---

### B-003 — NSE quarterly-results endpoint silently returned `INSUFFICIENT_DATA` for 100% of stocks `[P0]`  `RESOLVED 2026-05-10`

**Symptom**: Every `Earnings growth analysis for {symbol}: verdict=INSUFFICIENT_DATA, QoQ revenue=null%, YoY profit=null%` — 516 of 516 calls in the May 8 audit window. Even for stocks where 8 quarters were "fetched" successfully.

**Root cause**: NSE changed their schema (~2026-04). The legacy endpoint `/api/corporates-financial-results?index=equities&symbol=...` now returns only filing **metadata** (filing date, format, broadcast time, XBRL link) — the actual numeric fields (`revenueOperations`, `proLossAftTax`, `basicEPS`, etc.) were removed and the numbers moved into separate XBRL files.

**Fix**: Switched to `/api/results-comparision?index=equities&symbol=...` which returns 5 quarters of comparison data with field names like `re_net_sale`, `re_con_pro_loss`, `re_basic_eps_for_cont_dic_opr`, `re_int_new`, `re_oth_exp`, `re_staff_cost`, `re_rawmat_consump`. Values are in **lakhs** — converted to **crore** (÷100) before returning, since `IntrinsicValuationService` and the multibagger pipeline expect crore. Also case-insensitive `dd-MMM-yyyy` parser to handle `"31-DEC-2024"` (uppercase). ([NseDataService.java:108-200](src/main/java/com/example/trading/ai/NseDataService.java#L108))

**Verification (real numbers cross-checked against company reports)**:
- `GET /api/research/earnings/NSE:RELIANCE` → revenue=₹1,28,260 cr, profit=₹8,721 cr, EPS=6.44 (matches Reliance Q3 FY25 standalone)
- `GET /api/research/earnings/NSE:TCS` → YoY revenue +5.98%, YoY profit +10% (matches TCS Q3 FY25)
- `GET /api/research/earnings/NSE:INFY` → YoY revenue +7.46%, latest net margin 18.2% (matches Infosys reporting)

**Blast radius pre-fix** — *this was the most damaging bug*:
- Multibagger Earnings Growth Bonus (up to +8 pts) always neutralised
- Holdings report's "Earnings Trend-Break Alerts" never fired
- Deep Research dimension 12 (Earnings) was empty
- DCF intrinsic valuation lacked the historical-CAGR reference, treating all stocks as INSUFFICIENT_DATA for comparison
- **Investment shortlist was ranking on partial signals for ~3 months**

**Caveats** (also tracked as B-009, B-010):
- Banks: `re_net_sale` not populated; profit growth works, revenue is N/A
- Depreciation: not in JSON, falls back to net-profit-only FCF proxy in DCF

---

### B-004 — NSE shareholding endpoint 404 for every stock (1,253 calls, 0 successes) `[P0]`  `RESOLVED 2026-05-10`

**Symptom**: `Shareholding history fetch failed for {symbol}: 404 Not Found from GET https://www.nseindia.com/api/corporate-shareholding`. 923 occurrences May 8 + 330 May 10. Across 383 distinct symbols. Zero successful fetches.

**Root cause**: NSE deprecated `/api/corporate-shareholding` in favour of `/api/corporate-share-holdings-master?index=equities&symbol=...`. The new endpoint returns a list of filing records with summary fields (`pr_and_prgrp` for promoter %, `public_val` for public %, `date`) and an XBRL link for granular data.

**Fix**: Switched to the new endpoint in both `fetchShareholdingHistory` (used by multibagger Insider Activity Bonus + Institutional Interest dimension + thesis-drift) and the legacy `fetchShareholding` method. ([NseDataService.java:732, 263](src/main/java/com/example/trading/ai/NseDataService.java#L732))

**Verification**:
- `GET /api/research/shareholding/NSE:RELIANCE` → 8 quarters tracked, promoter change −0.24%, insider signal SELL (Reliance promoters did slightly trim — plausible)
- `GET /api/research/shareholding/NSE:HDFCBANK` → 8 quarters, promoter 0% (correct — HDFC Bank has no promoter holding, fully institutional)

**Blast radius pre-fix**:
- Multibagger Institutional Interest dimension (10% weight) was stuck at neutral 40 for every stock
- Multibagger Insider Activity Bonus (up to ±5 pts) always neutral
- Deep Research dimension 13 (Shareholding) was empty
- HoldingsDecayService and thesis-drift had no insider trigger
- Pledge tracking: silently absent — HIGH_RISK cap couldn't fire on pledge alone

**Caveats** (tracked as B-011, B-012):
- FII/DII split is now approximated 50/50 from the public bucket (XBRL has the real split — not parsed yet)
- Pledge % is `null` (XBRL has the boolean — not parsed yet)

---

### B-006 — FII/DII bulk/block deals parser missed the new `watp` price field `[P2 → effectively P1]`  `RESOLVED 2026-05-11`

**Symptom**: `Parsed 0 institutional BULK/BLOCK deals` on every run, going back at least to the May 8 audit. Sector-flow analysis silently degraded — the multibagger Institutional Interest dimension lost half its data, and the daily FII/DII email's "Bulk/Block Deals" section was empty.

**Root cause**: NSE's `/api/snapshot-capital-market-largedeal` endpoint changed the price field name. Code looked for `tradedPrice`, `tradePrice`, `wghtAvgPrice` — none present. The current key is `watp` (weighted-average traded price). Missing field → price=0 → value=0 → `if (value < 10) continue;` filter dropped every single deal.

**Fix**: Added `watp` to the price-field fallback chain in `parseDeals`. ([FiiDiiDataService.java:507-513](src/main/java/com/example/trading/fiidii/FiiDiiDataService.java#L507))

**Verification**: Endpoint now returns `Parsed 1 institutional BULK deals` + `Parsed 45 institutional BLOCK deals` for 2026-05-08 (from a raw 78 bulk + 106 block deals; the rest correctly filtered out as non-institutional client names like "JUNOMONETA FINSOL"). Trigger: `POST /api/fiidii/trigger-report` returns `fiiNet=-4110.6, diiNet=+6748.13` cr.

**Blast radius pre-fix**: Multibagger Institutional Interest dimension lost the bulk-deal signal, sector-flow analysis ran on empty data, FII/DII email's deal sections were blank. The same NSE schema flip that broke quarterly results (B-003) also broke this — it's worth grepping for more `tradedPrice`/legacy field references during the next audit.

---

### B-009 — Bank stocks silently lost YoY revenue growth signal `[P2 → effectively P1]`  `RESOLVED 2026-05-11`

**Symptom**: HDFCBANK, ICICIBANK, SBIN, and other banks returned `yoyRevenueGrowth: N/A` from `/api/research/earnings/...`, biasing their multibagger composite scores downward. Profit growth and EPS worked, so the composite wasn't fully broken — just systematically penalised banks' Earnings Growth dimension.

**Root cause**: NSE's `results-comparision` populates **different fields by industry**. Corporates use `re_net_sale`; banks use `re_int_earned` (interest income) + `re_oth_inc` (other income), aggregated as `re_tot_inc`. The parser only read `re_net_sale`.

**Fix**: Added `re_tot_inc` as the fallback when `re_net_sale` is null. Also added `re_net_profit` as profit fallback (banks populate `re_net_profit` directly when `re_con_pro_loss` is missing) and `re_oper_exp_bef_pro_cont` as the bank-specific operating-profit field (Indian banks' Pre-Provision Operating Profit). ([NseDataService.java:174-217](src/main/java/com/example/trading/ai/NseDataService.java#L174))

**Verification**:
- HDFCBANK: `yoyRevenueGrowth: 7.02%`, `yoyProfitGrowth: 2.22%`, `latestNetMargin: 19.13%` (was N/A). Matches HDFC Bank's reported Q3 FY25 standalone total income of ₹87,460 cr vs prior-year ₹81,720 cr ≈ 7% growth.
- ICICIBANK: `yoyRevenueGrowth: 13.03%`, `yoyProfitGrowth: 14.81%`, `growthVerdict: MODERATE_GROWTH`. Matches their reported Q3 FY25.

**Blast radius pre-fix**: All bank holdings (HDFCBANK, ICICIBANK, SBIN, KOTAKBANK, AXISBANK, INDUSINDBK, IDFCFIRSTB, FEDERALBNK, BANDHANBNK, BANKBARODA, CANBK, PNB) had their multibagger composite score under-weighted on the Earnings Growth dimension. With YoY revenue missing, the verdict defaulted toward STAGNANT — a real systematic bias in the recommendation engine for the financial sector.

---

### B-011 — Pledge % silently absent; HIGH_RISK promoter pledge flag never fired `[P2 → effectively P1]`  `RESOLVED 2026-05-11`

**Symptom**: `pledgePercent` was always null on every stock's shareholding history. `FinancialQuality.scoreLeverage` and `scorePledgeAndTrend` (which check `pledge > 50` and `< 50`) silently fell through, treating every stock as "no pledge". Promoter-pledged companies — the textbook profile of debt-trapped Indian small/mid-caps — could cross the 65-point multibagger threshold unchallenged.

**Root cause**: NSE's new shareholding endpoint (`/api/corporate-share-holdings-master`) doesn't include pledge in the JSON. The numeric percentage lives in a per-record XBRL file at `<EncumberedShareUnderPledgedAsPercentageOfTotalNumberOfShares contextRef="ShareholdingOfPromoterAndPromoterGroup_ContextI">`. The old code returned null for pledge unconditionally.

**Fix**: Added `fetchPledgePercentFromXbrl(xbrlUrl)` that:
1. Fetches the XBRL file (~300 KB; required a 2 MB WebClient buffer — same DataBufferLimitException class as the Kite instruments CSV fix)
2. Reads `WhetherAnySharesHeldByPromotersAreEncumberedUnderPledgedForPromoterAndPromoterGroup`. If `false`, returns 0.0 (explicit "no pledge").
3. Otherwise parses `EncumberedShareUnderPledgedAsPercentageOfTotalNumberOfShares` with the promoter context, multiplies by 100 (XBRL stores ratios, system uses percent).
4. Called only for the latest quarter to keep network cost bounded — one XBRL fetch per stock per 30-min cache TTL.

[NseDataService.java:303-417](src/main/java/com/example/trading/ai/NseDataService.java#L303)

**Verification**:
- ADANIENT: `pledgePercent: 0.8` (matches probed XBRL value of 0.008 ratio × 100)
- RELIANCE: `pledgePercent: 0.0` (boolean=false → explicit zero, not null)
- ZEEL: `pledgePercent: 5.38` (a real, non-zero value on a stock known to have promoter pledge issues)

**Known caveat**: The XBRL value is "pledged as % of TOTAL shares", not "as % of promoter holding". For ADANIENT (promoter=74.67%), 0.8% of total ≈ 1.07% of promoter holding. FinancialQuality's `pledge > 50` check is therefore now a stricter threshold than originally intended — it will only fire at extreme pledge levels (50% of total shares = ~100% of a 50%-promoter holding). Tracked as a follow-up calibration; the immediate win is that pledge is no longer invisible.

**Blast radius pre-fix**: Any stock with high promoter pledge could quietly cross the multibagger recommendation threshold. This is the classic profile of speculative small/mid-caps where the promoter has secured loans against equity — exactly the stocks the HIGH_RISK cap was built to filter out.

---

### B-005 — 23 stale stock symbols polluting multibagger screening `[P1]`  `RESOLVED 2026-05-10`

**Symptom**: Multibagger run summary `Screened: 317, Failed: 23` — about 7% of the universe failing token resolution every run. ERROR-level log spam: `Token resolution failed. Available keys in response: []` for `NSE:LTIM, NSE:KWIL-BE, NSE:LAKSHMIMACH, NSE:AEGISCHEM, NSE:LAXMIORG, NSE:MINDAIND, NSE:RANEENGINE, NSE:TVSSUPRA, NSE:WABCOINDIA, NSE:SEQUENT, NSE:STRIDES, NSE:SHANKARA, NSE:V-MART, NSE:VARUNBEV, NSE:WELSPUNIND, NSE:SUBEX, NSE:EQUITAS, NSE:IIFLWAM, NSE:MAS, NSE:ERAMAT, NSE:MAITHAN, NSE:TATAMETALI, NSE:RHI, NSE:GMRINFRA, NSE:TATAMOTORS, NSE:PGHH`.

**Root cause**: Mix of corporate actions (TATAMOTORS demerger → TMPV, GMRINFRA → GMRAIRPORT, IIFLWAM → 360ONE, VARUNBEV → VBL, EQUITAS → EQUITASBNK), renames (LAKSHMIMACH → LMW, MINDAIND → UNOMINDA, RHI → RHIM, WABCOINDIA → ZFCVINDIA), series moves (KWIL-BE), and possibly delisted (SUBEX, ERAMAT). For some, the new symbols already existed in another tier (NIFTY_NEXT_50 / NIFTY_MIDCAP_100).

**Fix (conservative)**: Removed all 23 from the universe lists. *No replacements added* — wrong replacement would mean analysing the wrong company. ([Nifty200WatchlistService.java:96-151](src/main/java/com/example/trading/scanner/Nifty200WatchlistService.java#L96), [MultibaggerScreenerService.java:85, 136](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java#L85), [SectorReversalConfig.java:37-40](src/main/java/com/example/trading/scanner/sector/SectorReversalConfig.java#L37))

**Verification**: End-to-end multibagger screening run on 2026-05-10 18:49 IST: `Screened: 286, Quality-rejected: 82, Failed: 0, Candidates (score>=60): 161`. Compared to baseline May 8: `Screened: 317, Quality-rejected: 52, Failed: 23, Candidates: 182`.

**Blast radius pre-fix**: Each run wasted ~30 seconds on dead Kite calls (incidentally adding to 429 risk on adjacent stocks). Failed stocks were silently absent from screening — an investor scanning the candidate list would never know that 23 stocks weren't even evaluated.

**Follow-up** (tracked as B-013): Re-add verified replacements after confirming each against the Kite instruments cache.

---

## Coverage Improvements (not bugs, but tracked for history)

### C-001 — Added Chemicals and Housing sector tracking `2026-05-11`

**What changed**: Added `NIFTY CHEMICALS` and `NIFTY HOUSING` to the sector reversal scanner ([SectorReversalConfig.java](src/main/java/com/example/trading/scanner/sector/SectorReversalConfig.java)). Previously the scanner tracked only 10 sectors; chemical stocks (PI Industries, Deepak Nitrite, SRF, etc.) and housing-finance stocks (PNB Housing, LIC Housing, Aptus, SBFC, etc.) were ignored at the sector level — they could only surface via the multibagger universe.

**Verified live**: First scan after deployment produced real picks — NIFTY HOUSING surfaced SBFC (STRONG_BUY, score 80), PNBHOUSING (BUY, 66), APTUS (BUY, 66). NIFTY CHEMICALS surfaced PIIND (BUY, 69), DEEPAKNTR (BUY, 66), SRF (BUY, 66).

**Side fixes**:
- Added proxy stocks (PIIND, LICHSGFIN) in [NiftySectorService.getProxySymbol](src/main/java/com/example/trading/scanner/sector/NiftySectorService.java) so sector-level reversal analysis still works when the Kite index quote endpoint is rate-limited.

**Still open** (intentionally not addressed in this change): the multibagger `STOCK_SECTOR_MAP` and `FiiDiiConfig.sectorMapping` still under-tag chemical and HFC stocks (only 3 chemicals + 0 HFCs are sector-tagged). This degrades the Sector Tailwind dimension of the multibagger composite for those stocks. Tracked as a separate follow-up — ask if needed.

---

### B-014 — Single-threaded scheduler starves late-afternoon jobs (recommendation outcomes, weekly accuracy email, tax-lot capture) `[P1]`  `RESOLVED 2026-05-23`

**Symptom**: On Fri 2026-05-22, three scheduled jobs failed to run despite the app being up until 15:59:
- 15:22 recommendation-outcome update — no log line all day (SPEC §23 calibration data not updated)
- 15:25 weekly recommendation-accuracy email — never sent
- 15:28 tax-lot auto-capture — slipped to 15:31:19 then aborted with `"TaxLot auto-capture: market closed — skipping"`, so that day's BUY/SELL fills were never captured into the tax-lot ledger (SPEC §9.3).

Found while auditing 5 days of logs. The `scheduling-1` thread logged 580–740 lines/second *continuously* from 15:17 through 15:31 — 100% saturated by a multibagger screen the entire window.

**Root cause**: Spring's default `@Scheduled` executor is **single-threaded** (one `scheduling-1` thread for all ~50 jobs — no `TaskScheduler` bean was defined). The 15:00–15:30 IST window is densely packed (15:00 breakout scan → 15:15 holdings ×2 → multibagger screen → 15:22 recommendation outcome → 15:25 accuracy email → 15:28 tax capture). A long-running scan (multibagger/holdings can run 14+ min) serialises every later job behind it. Because every job's first line is the mandatory `MarketHoursService.isMarketOpen()` guard (CLAUDE.md), a job pushed past the 15:30 close then **silently self-aborts** — the lateness converts into a skip. Hits the Friday accuracy report *every* week, and the 15:22/15:28 jobs on any day the afternoon scan runs long.

**Fix**: Added a multi-threaded `TaskScheduler` bean (pool size 4, `sched-` prefix, wait-for-completion-on-shutdown 30s) in [SchedulingConfig.java](src/main/java/com/example/trading/config/SchedulingConfig.java). The lightweight, time-critical afternoon jobs (recommendation outcome, tax capture, accuracy email — all quick DB / single-price reads) now get a thread even while a heavy Kite-bound scan occupies another. Pool deliberately kept small (4, not large) so several Kite-heavy scans never run in parallel and trip the broker 429 limit. `@Async` was intentionally NOT enabled (no `@EnableAsync` exists; enabling it would silently change `SignalTrackingIntegrator`'s two `@Async` methods from sync to async).

**Verification**: `mvn compile` → BUILD SUCCESS. On restart, expect `Scheduler pool initialized: 4 threads (prefix 'sched-')` and scheduled work now logging on `sched-1..4` threads instead of `scheduling-1`. Empirical proof = next Fri 15:22 recommendation-outcome update **and** 15:25 accuracy email both fire, and tax-lot capture completes at ~15:28 (not skipped) on a day with a long afternoon scan.

**Blast radius pre-fix**: SPEC §23 recommendation-accuracy calibration silently stopped updating on busy afternoons (skewing hit-rate / IC history); the weekly accuracy email was missed every Friday it coincided with a long scan; and tax-lot capture gaps distort the LTCG-aware profit-booking suggestions in the holdings email (SPEC §9.4) — the user could be told to "book now" on a lot whose true holding period was never recorded.

---

### B-015 — XGBoost parser applied sigmoid to the regression risk model (constant 0.5000) + synthetic MAE target `[P2]`  `RESOLVED 2026-05-23` (code) / forward-only (data)

**Symptom**: Holdings risk model emitted exactly `0.5000` for every holding (350× over 4 days), surfacing as a uniform MEDIUM risk for the whole portfolio. Found while digging into B-007.

**Root cause** (two stacked bugs):
1. **Parser objective-blind** — [XGBoostModelParser.predict()](src/main/java/com/example/trading/ml/XGBoostModelParser.java) applied `sigmoid()` to *every* model and never added `base_score` into the margin (only used it as a no-trees fallback). That's fine for `binary:logistic` (price/recommendation/sector-reversal/trap-detector all have `base_score=0.5` → `logit(0.5)=0`), but **wrong for the `reg:squarederror` risk model**: a regressor's tree margins centre near 0, so `sigmoid(Σtrees) ≈ sigmoid(0) = 0.5000` for every input.
2. **Synthetic risk target** — [HoldingsTrainingDataCollector.labelRecord()](src/main/java/com/example/trading/ml/holdings/HoldingsTrainingDataCollector.java) set the `mae` (max adverse excursion) label to a placeholder `-|actualReturn|/2` ("simplified without intraday data"). That target has near-zero variance, so XGBoost found no split worth making and the risk model collapsed to single-leaf stumps (`risk_assessment.json` tree 0 = 1 node, leaf `-0.0`). Even with a correct parser it would predict the constant `base_score` (0.0085).

**Fix**:
- Parser is now objective-aware: `logistic` objectives return `sigmoid(logit(base_score) + Σtrees)`, regression objectives return `base_score + Σtrees` raw. Binary models with `base_score=0.5` are byte-identical (verified). [XGBoostModelParser.java](src/main/java/com/example/trading/ml/XGBoostModelParser.java)
- MAE label now computes the real deepest daily-close drawdown from entry over the observation window via `findBySymbolAndRecordDateBetween`, falling back to the old estimate only if history is missing. [HoldingsTrainingDataCollector.java](src/main/java/com/example/trading/ml/holdings/HoldingsTrainingDataCollector.java)

**Verification**: `mvn compile` BUILD SUCCESS. Python cross-check of the corrected math on the real JSONs: `risk_assessment` raw output `0.5000 → 0.00855` (true regression value); `recommendation`/`price_movement` unchanged (`0.072`/`0.046`), confirming binary models are unaffected. The corrected risk value (0.0085) is `< 0.02`, so `HoldingsMLService`'s degenerate-output guard now routes risk to the (informative) rule-based path via the correct branch. **Caveat**: the MAE label fix is *forward-only* — existing CSVs keep the placeholder target, so the risk regressor stays degenerate until enough new labels accumulate. Empirical proof of a non-degenerate risk model requires re-collection + retrain over coming weeks.

**Blast radius pre-fix**: every holding shown as MEDIUM risk in the daily holdings email regardless of true risk; the ML risk score, optimal-stop-loss, and drawdown-probability outputs were all meaningless. Did not corrupt other engines (risk model is holdings-only).

**Related**: B-007 (the label-imbalance problem, now resolved below).

---

### B-007 — Holdings price/recommendation models collapse to majority class (label imbalance) `[P2]`  `RESOLVED 2026-05-23`

**Symptom**: Every holding fell back to rule-based for ML recommendation (raw probabilities all <0.10). Open since 2026-05-08; workaround was just "retrain", which didn't help because the data was the problem.

**Root cause**: Outcome labels used a ±5% move over a **5-day** horizon. For a buy-and-hold portfolio that yields **92% NEUTRAL / 88% HOLD** — the binary models correctly learn the ~3% base rate and emit uniformly low probabilities, which `HoldingsMLService`'s `<0.10` uncalibrated-guard then routes to rule-based for everyone. (Feature alignment was *not* the issue — the 48 features match `toFloatArray`; the stale 18-entry `feature_names.json` is unused.)

**Fix** (Option A — longer horizon + tighter thresholds, chosen by the user):
- Labeling horizon **5d → 30d**, thresholds **±5% → ±4%**, in both [application.yml](src/main/resources/application.yml) (`trading.ml.holdings.label-after-days`, `price-movement.up/down-threshold`) and the `HoldingsMLConfig` Java defaults. (The yml overrides the Java defaults — both were changed.)
- Labeler now reads the close **at the horizon** (recordDate+30) rather than the latest close, so labels are horizon-accurate and historical re-labeling is correct. New `relabelAllRecords()` + `POST /api/trading/holdings/relabel-training`. [HoldingsTrainingDataCollector.java](src/main/java/com/example/trading/ml/holdings/HoldingsTrainingDataCollector.java)
- Recommendation label boundaries (BUY/SELL) now track the ±4% thresholds; STRONG_* reserved for ±10% monthly moves.

**Verification** (empirical, 2026-05-23): re-labeled 1,639 historical records → class balance went from 92% NEUTRAL to **47% NEUTRAL / 29% UP / 24% DOWN** (recommendation: 88% HOLD → **47% HOLD**, 29% BUY-side, 24% SELL-side). Retrained on the balanced data: price-movement probability spread **std 0.35** (min 0.009 / max 0.972), recommendation spread **std 0.34** with predictions in every probability bucket (was all <0.10). Cross-check through the corrected parser on 6 varied inputs: recommendation 0.04–0.59, price-UP 0.06–0.70 — i.e. many holdings now cross the 0.10 guard and use ML instead of falling back. (Risk model's 100-tree non-degeneracy is under B-015.)

**Blast radius pre-fix**: holdings ML recommendation/price contributed nothing — every holding silently used the rule-based path, so the "ML-enhanced" holdings analysis and blended score were rule-based-only. No corruption (graceful fallback), just an unrealised feature.

**Caveat / follow-up**: balance is verified on re-labeled history; the *forward* labeler now needs ~30 days to accrue fresh labels per record. Whether the ML actually beats the rule-based engine is a separate quality question (measure via the holdings analysis once it runs on a market day), not a bug.

---

### B-016 — Exited / zero-quantity stocks appear in email reports `[P1]`  `RESOLVED 2026-05-23`

**Symptom**: Daily holdings email, morning briefing, exit alerts, etc. listed stocks the user no longer owns. Live DB had **17 of 51 holdings rows at quantity 0** (e.g. NSE:TATATECH, BSE:CIPLA — frozen at old `lastSyncedAt` dates Mar–May while real holdings synced 2026-05-22), plus a **stale non-zero phantom** `NSE:KWIL-BE qty=8` last synced 2026-02-27 (position exited / moved series, row never cleaned).

**Root cause** (two compounding gaps):
1. `HoldingsAnalysisService.syncHoldingsFromBroker()` only **upserted** the symbols the broker currently returns — it never removed local rows for stocks that fully exited (vanish from the broker list) or came back with quantity 0 on exit day. So exited holdings lingered forever.
2. Every report/analysis query (`findAll`, `findAllOrderByScoreDesc`, …) had **no `quantity > 0` filter**, so those lingering rows flowed straight into 20+ consumers (holdings report, morning briefing, exit alerts, allocation, conviction, multibagger, decay).

**Fix**:
- **Reconciliation** in `syncHoldingsFromBroker` ([HoldingsAnalysisService.java](src/main/java/com/example/trading/holdings/HoldingsAnalysisService.java)): skip zero-qty broker rows, track live (qty>0) symbols, and delete local holdings the broker no longer reports. **Guarded**: if the broker fetch is empty/throws, the whole sync is skipped — a token/API failure can never wipe the table. (Proven live: a `TokenException` during testing correctly left all rows intact.)
- **Active-only queries** `findActiveOrderByScoreDesc()` / `findActive()` ([HoldingsRepository.java](src/main/java/com/example/trading/persistence/HoldingsRepository.java)), wired into the report/analysis consumers (HoldingsReportService, MorningBriefingService, ExitTimingAlertService, HoldingsDecayService, HoldingsAnalysisService.analyzeAllHoldings/recordDailySnapshot, GET /holdings). Defense-in-depth that hides zero-qty rows **immediately, without a broker round-trip**.

**Verification**: `GET /api/trading/holdings` (same query the daily email uses) went from **51 rows / 17 zero-qty → 34 rows / 0 zero-qty** after the query-filter deploy. The reconciliation *delete*-path could not run live on 2026-05-23 because the Kite token had expired over the weekend (no auto-refresh outside market hours) — proving the empty-fetch guard (it left all rows intact). To tidy the table immediately, the 17 zero-qty rows **plus** the `NSE:KWIL-BE qty=8` stale phantom (−42.8%, last synced Feb 27) were purged directly via a transaction-wrapped JDBC delete (backed up to `holdings_bak_20260523` first; backup count verified == target before delete). Final state: **33 holdings, 0 zero-qty**. The reconciliation path itself will be exercised on the next successful broker sync (Mon) and keeps the table clean going forward; it is self-healing (re-adds any row that turns out to still be held).

**Blast radius pre-fix**: portfolio value/holding counts in every email were inflated by phantom rows; allocation/diversification (HHI) and conviction analysis computed over non-holdings; exit alerts and decay alerts could fire on stocks already sold. Aggregates (`SUM`/`COUNT` of value/PnL) were unaffected — zero-qty rows contribute 0.

---

### B-017 — NSE froze `results-comparision` + `corporates-financial-results` at Dec-2024; all fundamentals ~17 months stale `[P1]`  `RESOLVED 2026-05-24`

**Symptom**: User noticed the holdings email's Capital Efficiency section showed FY2023-24 on 24-May-2026. Investigation found *all* fundamental data was ~17 months stale: `results-comparision` (quarterly earnings) returned latest `re_to_dt=31-DEC-2024` (created 16-Jan-2025); `corporates-financial-results` (annual XBRL) topped out at 31-Mar-2024. Independent live sources confirmed the real date (Google News 23-May-2026, NSE FII/DII 22-May-2026) — so it was endpoint-specific, not a clock or global-outage issue.

**Root cause**: NSE migrated financial results to the **integrated-filing** system (~Jan 2025). The two endpoints we used still return HTTP 200 but serve frozen pre-migration data.

**Fix**: Migrated both NSE fundamental fetchers in [NseDataService.java](src/main/java/com/example/trading/ai/NseDataService.java) to `/api/integrated-filing-results?index=equities&symbol=X&period=Quarterly` (latest qe_Date now `31-MAR-2026`).
- New `fetchIntegratedFilings()` + `financialFilings(symbol, annualOnly)` (dedupe by quarter, Consolidated preferred, newest first).
- New reusable `fetchAndParseXbrl(url)` → `XbrlDoc` (facts map + span-resolved quarter/annual/instant contexts), cached per-URL 7 days. The integrated XBRL uses the SEBI **`in-capmkt`** namespace (was BSE `in-bse-fin`) but **identical Ind-AS local element names**; the parser is namespace-agnostic (matches local name) so it works for both, and the integrated XBRL has **reliable context dates** (OneD=89d quarter, FourD=364d year) so the span-based resolvers are exact.
- `fetchQuarterlyResults` rewritten to parse one integrated-filing XBRL per quarter (OneD context). Units changed lakhs→**rupees** (`crore()` = ÷1e7). Bonus: depreciation IS now exposed (`DepreciationDepletionAndAmortisationExpense`) — partially un-blocks B-010.
- `fetchAnnualFinancials` rewritten to pick the latest March integrated filing (FourD annual + OneI balance sheet). Banks detected via `INTEGRATED_FILING_BANKING` filename.

**Verification** (empirical, 2026-05-24, post-deploy): `GET /api/research/capital-efficiency/NSE:RELIANCE` → `financialYear: 01-Apr-2025 To 31-Mar-2026` (was 2023-24), ROE 8.82/ROCE 10.29/D-E 0.34/cash-conv 2.01 from FY26 statements (Equity ₹10.86 lakh cr). Earnings YoY revenue +12.9% from recent quarters. HDFCBANK → FY26, ROE 13.07%/ROA 1.55%. Multibagger screen surfaces fresh gross margin 32.7% / consistency from current quarters. No startup/DDL errors.

**Blast radius pre-fix**: every fundamental output (earnings growth, margins, Financial Quality, Wealth Signals, Capital Efficiency, reverse-DCF, multibagger Valuation/Institutional dimensions, holdings email sections, deep-research dims 12-20) ran on ~17-month-old financials — wrong buy/hold/sell inputs. **Caveat**: the integrated system began ~Mar-2025, so only ~5 quarters of history exist today — 8-quarter CAGR (`revenueCAGR`/`profitCAGR`) returns null until ~mid-2027; QoQ/YoY work now.

## Won't Fix

*(none)*

---

## Tracking conventions

- IDs are monotonically increasing across all states (Open / Resolved / Won't Fix). Once assigned, an ID never changes.
- Use `[P0]`, `[P1]`, `[P2]` tags in commit messages and PR titles when fixing — easier to grep history.
- A fix isn't "Resolved" until it's verified empirically — either by re-running the affected pipeline and observing the symptom is gone, or by reproducing the original failure conditions and confirming they no longer trigger.
- Append new bugs at the end of "Open Bugs". Don't reorder by severity — chronology is part of the audit trail.
