# Failure taxonomy — the fifteen ways a feature here lies to the investor

Every class below is a real, repeated defect from this repo's own history, not a generic code smell.
Each has a **tell** (what it looks like from the outside), a **probe** (how to find it without
guessing) and the **precedent** (where to read the full story before writing it up as new).

The unifying property: **all of them pass every server-side check.** Nothing throws, every component
behaves exactly as specified, HTTP is 200, the tests are green, and the screen renders. That is why
they run for months, and why a reviewer who only reads code and status codes finds none of them.

---

## 1. Zero variance — the value that was never really measured

**Tell.** One value repeated across the whole universe. Institutional Interest read exactly 40 for
three months; monthly RSI read exactly 50.0 on all 294 rows; Insider Pulse produced a verdict for
**no stock at all**.

**Probe.** `screening_coverage` for the signal: `measured`, `coveragePercent`, `stddev`, `collapsed`.
Or directly: `SELECT COUNT(*), COUNT(DISTINCT col), MIN(col), MAX(col), STDDEV(col) FROM
multibagger_scores WHERE screening_date = (SELECT MAX(screening_date) FROM multibagger_scores)`.
A `COUNT(DISTINCT)` of 1 or 2 on a 0–100 dimension is the signature.

**Two causes, and the obvious one is not always right.** *We never measured it* (B-074: the capture
budget never reached 286 of 295 screened stocks) or *nobody published it* (B-089: NSE had moved the
insider feed four months earlier and the old endpoint still returned HTTP 200 serving its pre-May
archive). Check the feed's newest row before concluding the first.

**Precedent.** bug #9, B-060, B-074, B-089, B-090. Gotcha 88, 94.

---

## 2. Null rendered as a number

**Tell.** A missing measurement displayed as `0`, `50`, `50.0%`, or an empty cell that reads as a
value. SPEC §21 rule 7 calls this the most damaging violation available, because the investor cannot
tell it from a real reading.

**Probe.** `grep -rn "!= null ? .* : 0\|orElse(0\|:\s*50\b\|?? 0\|?: 0" src/main/java` on the
feature's files, and in `static/js` check the cell goes through `format.js`'s `NOT_MEASURED` path.
Then look at the rendered page, not the DOM.

**Sub-classes seen here.** A neutral default returned when the window cannot reach the indicator's
period (B-060); a zero substituted for one leg of a two-leg sum so the total is a strong claim built
half from nothing (B-063); an unknown delta defaulted to 0.0 while the rest of the formula still
computes, so the ratio *looks* measured (B-048).

**Precedent.** B-019, B-048, B-060, B-063. Gotcha 21, 33, 67.

---

## 3. Absence rendered as an all-clear

**Tell.** "No red flags", "no issues found", an empty list, a clean badge — where the truth is that
nothing was checked. Forensic flags need ≥3 years of `annual_fundamentals`; most of the universe has
fewer.

**Probe.** Does the result type carry a `notMeasured` / coverage field, and does the surface actually
print it? A feature with a coverage field that no screen renders fails this just as hard.

**Precedent.** Gotcha 44, 68, 106(c). SPEC §44.

---

## 4. Two surfaces, one question, two rule tables

**Tell.** The same six words meaning different things on two screens — the investor finds it before
any check does. "Is it still a good time to buy?" was answered by four separate engines: the
watchlist (daily RSI-14, EMA-50), the screener (weekly RSI, distance from the 52-week high), the
holdings "Signal" column (a momentum rule with no sight of fundamentals — it printed BUY beside
AVOID on BEL and disagreed on 9 of 32 holdings), and the sector-reversal entry.

**Probe.** Grep the verdict vocabulary across packages: if two enums share values, or two classes
compute the same user-facing word from different inputs, that is the defect. The fix is always
centralisation, never another patch — patching surfaces one at a time is what produced it three times.

**Note the exception.** Two *genuinely different* questions must keep different vocabularies and
**explain** their disagreement rather than being merged (§41 "can it compound" vs §43 "has it
compounded"). Merging those destroys the distinction the second panel exists to draw.

**Precedent.** B-062, B-065, B-069. Gotcha 76, 81, 85, 105.

---

## 5. Scale and unit mismatch

**Tell.** Two rates compared that were never measured over comparable spans. A quarter filed as a
year (~4× error into every CAGR, margin and turnaround verdict). A percentage row written into a
figure field (~1000× error). A two-year profit CAGR used as the benchmark for a ten-year requirement
— which returned **one verdict for every stock** and looked fine because the arithmetic was right.

**Probe.** For any comparison of two rates, ask what span each was measured over. For any parsed
figure, ask what unit the source publishes in. For any indicator, ask whether the fetch window can
produce its period.

**Precedent.** B-047, B-048, B-060, B-065, B-113. Gotcha 48, 65, 66, 78, 103.

---

## 6. Look-ahead — knowing the filing before it was public

**Tell.** A back-test or point-in-time evaluation keyed on `fiscalYear` rather than `available_from`.
A March year-end is not public in March — SEBI LODR Reg 33(3) allows 60 days — so using the year end
leaks up to five months **in the flattering direction**, because the lens "knew" the result before
the price moved. Same class: measuring an analyst call from the note's own date rather than the day
it became public, which credits the house with what the price did while its note was private.

**Probe.** Any evaluation over historical dates: which column decides what was knowable? If it is not
an availability/publication date, it is a look-ahead.

**Precedent.** Gotcha 100, 126(f). SPEC §32.2, §49.11.

---

## 7. A feature that scores itself

**Tell.** A pick shown to the investor that is not registered with `RecommendationTracker`, or is
graded by its own private board. The breakout scanner was graded on whether it hit *its own* target
before *its own* stop — a scoreboard that always reads better than the real loop. Both predecessor
news features were deleted for producing no measured hit rate at all.

**Probe.** Is the feature a `RecommendationTracker` source? If it shows a pick and is not, that is
the finding. Note the verdict-aware exception: a `MACRO_EVENT` HEADWIND followed by a fall is a
*correct* reading, so accuracy branches on the source.

**Precedent.** SPEC §39.3, §25. Gotcha 25, 124.

---

## 8. Silent degradation to an empty collection

**Tell.** A `catch` that returns `List.of()`, `""`, `null` or `0.0` and logs at DEBUG. One shared
`WebClient` inheriting Spring's 256 KB buffer made the corporate-announcements feed return empty for
large caps — and every consumer read that as "this company filed nothing", killing Deep Research
dimension 14, the forensic auditor scan and transcript discovery at once.

**Probe.** `grep -rn "catch" -A 4 src/main/java/<package> | grep -n "return \(List.of()\|\"\"\|null\|0\.0\|Collections.empty\)"`. Every such catch must log at **WARN** and say what the
emptiness will be mistaken for. Second half: an empty list that means two different things (no data /
the fetch failed) and a caller that cannot tell them apart — that is how an archive walk read a
failed page as the end of the archive and stopped at 40% while logging success.

**Precedent.** B-054, B-027, B-089, Gotcha 126(k). Gotcha 22, 52.

---

## 9. A placeholder treated as a value

**Tell.** `"-"`, `"Other"`, `"GENERAL"`, `"N/A"`, `0`, or an empty string flowing into logic as
though it were real. NSE writes `"-"` for an absent field, and `normaliseType()` turned it into
**BUY** — a `MARKET_SALE` added to net insider buying, failing in the one direction a promoter-
accumulation signal must never fail in. A hand map defaulting to `"Other"` presented an
unclassified sector as a classification for two-thirds of the universe.

**Probe.** What does the source write when it has nothing? Grep for the default branch of every
normaliser and every sector/industry/category mapping.

**Precedent.** B-040, B-096, B-098(a). Gotcha 61, 110.

---

## 10. Negation swallowed by substring matching

**Tell.** `"unqualified opinion"` contains `"qualified opinion"` — so the system's most serious flag
(−8 plus a forced HIGH_RISK cap at 54) fired on the routine LODR filing that exists to say nothing is
wrong. Same family: a hedge word that follows rather than precedes the event, so *"a rate hike
could…"* files as an actual rate hike.

**Probe.** For every negative keyword match, ask whether the *negation* of that phrase contains it,
and whether the hedge can appear in noun position. Clean markers must be checked **first**.

**Precedent.** B-037, B-107. Gotcha 53, 61, 123.

---

## 11. Row count quoted as sample size

**Tell.** "n=8,933" for what is ~82 screening dates × ~300 stocks moving together over overlapping
windows — roughly **five** independent periods. Anyone reading the row count as "the gate is met"
will fit one risk-on quarter and call it skill, which is the exact failure that ended the previous
ML chain.

**Probe.** Read `independentPeriods` from the walk-forward report. For any t-statistic, ask what the
blocks were. For any correlation, ask whether it is cross-sectional per date or pooled across dates
(pooling lets one strong month dominate).

**Precedent.** SPEC §38.9, §40.2. Gotcha 88, 92, 93.

---

## 12. A shadow default flipped, or a risk control shipped disarmed

**Tell.** `*-actionable` true for a new bonus, or false for a risk control. The asymmetry is
deliberate: a bonus claims a stock will go up and being wrong costs a missed opportunity; a risk
control being wrong costs capital. `NewSignalShadowModeTest` fails if a default moves, so the change
has to be argued rather than slipped in.

**Probe.** `grep -rn "actionable" src/main/resources/application.yml` and compare against the test.

**Precedent.** Gotcha 30, 42, 43, 69.

---

## 13. The `enabled` flag that gates less than its name suggests

**Tell.** "Observation mode" that still writes. `dynamic-expansion.enabled` gated only the merge into
the screening universe — discovery, queueing, deep scoring and persistence all ran regardless, which
is how 19 unreviewed names reached the dashboard, the morning briefing and the accuracy tracker while
the flag read `false`.

**Probe.** Trace **every write** the disabled path still performs. And distinguish
*compute-to-decide* from *compute-to-publish*: `screenSingleStock()` writes a score row and records a
recommendation; `evaluateSingleStock()` does not.

**Fix shape.** When you find one, the fix is two things — a guard so it stops, **and** removal of what
was already written. A guard alone leaves the contamination measuring for a year.

**Precedent.** B-035. Gotcha 50, 51.

---

## 14. The front end drifting from the wire

**Tell.** A renamed or deleted field that the client still dereferences — it finds `undefined` and
draws "not measured" for ever, on a stock the app measured perfectly well. Or a deleted dimension
still listed, so the radar draws eight spokes with one permanently empty axis captioned "7 of 8
measured". There is **no build step** for `static/js`, so nothing type-checks the wire and nothing
parses the modules before a browser does.

**Probe.** Run `python scripts/check_js_syntax.py`, then the surface-contract tests
(`*SurfaceContractTest`), then **load the page** — the checker catches brackets, commas and import
placement, but a runtime `ReferenceError`, a missing CSS class or a CSP-blocked style attribute only
shows up in a browser. Verify styling with `getComputedStyle`, never by reading back the attribute.
A count rendered beside a list must be derived from that list, never a literal.

**Signature.** HTML 200 + JS 200 + blank body = module load failure, not a server problem.

**Precedent.** B-070, B-079, B-098, B-104, B-112. Gotcha 41, 82, 90, 98, 104.

---

## 15. A bulk save at the end of an expensive paced run

**Tell.** A loop that spends minutes of paced NSE/Kite calls and ends with one `saveAll(...)`. One
row too long for its column rolled back **all 255** plus four minutes of calls; one unique-constraint
collision discarded twenty minutes of completed scan. Save per row and report the count that failed.

**Probe.** `grep -rn "saveAll" src/main/java` and check what precedes it. Related: a column width or
type chosen from today's feed is an assumption about a third party's free text — widen *and*
truncate, and remember `ddl-auto=update` adds columns but never alters an existing one's type.

**Precedent.** B-049, B-116. Gotcha 74, 129.

---

## Cross-cutting: what actually finds these

In order of historical yield:

1. **Look at the rendered screen**, not the DOM and not the JSON. B-070 (every score bar drawing
   100% full), B-089's shredded text, B-112's unstyled callout and B-119's 9-hours-ago stamp were all
   found by looking.
2. **Check the distribution before shipping a verdict.** Run it over real rows and look at the
   spread. Four defects produced the same answer for everything; only the fourth was caught before
   the investor saw it (B-113).
3. **Read the coverage row before concluding anything about a signal's IC.** A dimension reads ~0
   both when the signal is weak and when it was never measured.
4. **Compare the process start time against the edit time** before believing a before/after
   measurement. A responding endpoint proves something is listening, never that it is your build
   (B-115, Gotcha 128).
