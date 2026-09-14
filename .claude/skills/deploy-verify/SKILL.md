---
name: deploy-verify
description: Rebuild and restart the trading app via start-app.bat and verify it came up cleanly (scheduler pool, ML models loaded, no errors). Use after making code changes, or when the user asks to restart/redeploy/"start the app" and confirm it's healthy. Knows the build-vs-restart timing gotcha that causes false "it's up" readings.
---

# Deploy & Verify

`start-app.bat` does: clean compile → kill whatever's on port 8080 → start the app in the background (logs to `logs/trading-app.log`). Per project convention, run it automatically after code edits.

## 1. Validate the build FIRST (avoid the incremental-compile trap)
```bash
mvn clean compile 2>&1 | grep -E "BUILD SUCCESS|BUILD FAILURE|\.java:\[" | head
```
Use `mvn clean compile`, **not** plain `mvn compile` — incremental compilation can report SUCCESS while skipping a changed file, so a real error (e.g. a controller missing `@Slf4j` for a `log.` call) only surfaces under `clean`. Pre-existing `@Builder.Default` warnings in `BacktestEngine` are harmless.

## 2. Restart
```bash
./start-app.bat    # run via the Bash tool with run_in_background: true
```
Must invoke as `./start-app.bat` from the Bash tool (a bare `&` background from a one-off shell does NOT reliably keep it running). It returns exit 0 only on a successful build; exit 1 means compile failed — read its output file for the `[ERROR] ...java:[line]` line.

## 3. Wait for the NEW instance (critical timing gotcha)
The clean build takes ~50s, during which **the OLD instance still serves :8080**. So an early `curl` returns 200 from stale code and lies. Don't trust readiness until start-app has (a) printed "Stopping it..." for the old PID and (b) the new process logged `Started IntradayApplication`. Wait on the log, e.g. with a Monitor/until-loop:
```bash
until grep -qE "Started IntradayApplication|APPLICATION FAILED|Error creating bean" logs/trading-app.log && [ "$(stat -c %Y logs/trading-app.log)" -gt "$(( $(date +%s) - 90 ))" ]; do sleep 3; done
```

## 4. Verify clean boot
```bash
grep -E "Scheduler pool initialized|Started IntradayApplication|Holdings ML Models loaded|APPLICATION FAILED|Error creating bean" logs/trading-app.log | tail -6
laststart=$(grep -n "Started IntradayApplication" logs/trading-app.log | tail -1 | cut -d: -f1); tail -n +${laststart:-1} logs/trading-app.log | grep -c " ERROR "
```
Pass = all present, 0 ERRORs since startup:
- `Scheduler pool initialized: 4 threads (prefix 'sched-')` (B-014 multi-threaded scheduler)
- `Holdings ML Models loaded - Price: true, Recommendation: true, Risk: true`
- `Started IntradayApplication in N seconds`

A `TokenException` on a manual broker sync is expected outside market hours (no token auto-refresh) — not a boot failure. If the app started after 09:15 IST on a weekday, note that the morning scheduled batch (09:20–09:45) was missed.
