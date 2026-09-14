---
name: log-health-review
description: Review the trading app's logs over the last N days to confirm every scheduled job fired and every email report sent, and to surface real errors vs known noise. Use when the user asks to "review the logs", "check the last N days", "is everything running / healthy", "did the reports go out", "are the scheduled jobs firing", or wants a post-mortem of recent app behaviour. Read-only; makes no code or DB changes.
---

# Log & Health Review

Audit `logs/` to verify the long-term-portfolio app behaved as expected. Default window: **last 5 days** (override if the user gives a range). The app only runs ~09:00–15:45 IST on weekdays, so weekends/nights having no activity is normal.

## 1. Assemble the window
Logs roll daily: current `logs/trading-app.log` plus gzipped `logs/trading-app.log.YYYY-MM-DD.N.gz`.
```bash
cd logs && mkdir -p /tmp/logreview && for f in trading-app.log.2026-05-*.gz; do zcat "$f"; done > /tmp/logreview/all.log 2>/dev/null && cat trading-app.log >> /tmp/logreview/all.log && wc -l /tmp/logreview/all.log
```
(Adjust the glob to the requested dates. Note B-008: log retention is short — you may only get ~2 trading days of `.gz` history.)

## 2. Aggregate errors & warnings (normalise so they group)
```bash
cd /tmp/logreview && grep ' ERROR ' all.log | sed -E 's/^[0-9-]+ [0-9:.]+//; s/\[[^]]*\]//g; s/[0-9]+/N/g' | sort | uniq -c | sort -rn | head -40
grep ' WARN ' all.log | sed -E 's/^[0-9-]+ [0-9:.]+//; s/\[[^]]*\]//g; s/[0-9]+/N/g' | sort | uniq -c | sort -rn | head -40
```

## 3. Scheduler firing matrix (per day)
Confirm each scheduled job ran on each trading day. Grep banners like `=== ... ===`, `Starting`, `Running scheduled`, `firing`:
```bash
for d in 2026-05-19 2026-05-20 2026-05-21 2026-05-22; do echo "--- $d ---"; grep "^$d" all.log | grep -oE "c\.e\.[a-z.]+[A-Za-z]+ +: .{0,40}" | sort -u | head -60; done
```
Expected daily: MorningBriefing (9:30), FiiDii (9:45/10:00), MarketDirection (9:30/12:30), BreakoutScanner (9:30/11:30/13:30/15:00), SectorReversal, QuantitativeDiscovery (10:00), Multibagger (14:00), Holdings (15:15–15:18, **two emails**: Action Items + Analysis), RecommendationOutcome (15:22), TaxLotAutoCapture (15:28). Weekly: PerformanceReport, RecommendationAccuracy email (Fri 15:25).

## 4. Verify reports actually sent
```bash
grep -iE "sent successfully|report sent|email sent|Email sent" all.log | sed -E 's/^([0-9-]+)T.*: /\1 /' | sort | uniq -c
```

## 5. What to flag (vs known noise)
- **Scheduler starvation (B-014)** — the single biggest historical issue: afternoon jobs (15:22 recommendation outcome, 15:25 accuracy email, 15:28 tax capture) starved by a long multibagger/holdings scan, then silently skipped past 15:30. Check those three fired on time; if a job slipped past 15:30 and self-aborted ("market closed — skipping"), flag it. (A 4-thread `TaskScheduler` now mitigates this — confirm `Scheduler pool initialized: 4 threads` at startup.)
- **Missed morning batch** — if the app started after 09:15 (`Started IntradayApplication` timestamp), all 09:xx jobs were missed that day.
- **ML uncalibrated warnings (B-007/B-015)** — `ML model appears uncalibrated` / `Using rule-based`. Some fallback is acceptable; a flood for *every* holding means the models regressed — see /ml-retrain-verify.
- **NSE/Kite failures** — `TokenException` (expected on weekends, no auto-refresh), `data={}` for a symbol (stale symbol, B-013), GZIP/binary FII garbage (B-003-style).

## 6. Cross-check & report
Search `BUGS.md` before declaring something new — the same root cause may already be documented. Then summarise: a verdict line, a "working as expected" list, and severity-ranked issues with timestamps/evidence. Offer to file new findings in `BUGS.md` (fresh `B-NNN`).
