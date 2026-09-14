---
name: holdings-hygiene
description: Detect and clean exited / zero-quantity stocks lingering in the holdings table so they stop appearing in email reports. Use when the user says reports show "stocks I don't own", "zero quantity", "exited/sold stocks", "stale holdings", or asks to reconcile/sync holdings. Codifies the B-016 fix.
---

# Holdings Hygiene (exited / zero-quantity cleanup)

The `holdings` table (JPA `HoldingsEntity`, `@Table(name = "holdings")`) must contain only currently-held stocks. Exited stocks used to linger because the sync only upserted broker rows and never removed vanished ones (B-016). Reports now use `findActive()` (`quantity > 0`), and `syncHoldingsFromBroker()` reconciles, but verify and clean as needed.

## 1. Diagnose
```bash
curl -s -m 15 "http://localhost:8080/api/trading/holdings" | python -c "import json,sys; r=json.load(sys.stdin); z=[h for h in r if (h.get('quantity',0) or 0)<=0]; print('rows:',len(r),'| zero-qty:',len(z)); [print(' ',h['symbol'],h['quantity'],h.get('lastSyncedAt')) for h in z]"
```
This endpoint already filters `quantity > 0`, so a zero-qty count of 0 here means **reports are clean**. To see the raw table (incl. dead rows), query the DB (step 3). Also watch for **stale non-zero** rows — a real-looking qty but `lastSyncedAt` weeks behind the others = an exited/renamed position the quantity filter can't catch.

## 2. Preferred fix — broker reconciliation (needs a valid Kite token)
```bash
curl -s -m 60 -X POST "http://localhost:8080/api/trading/holdings/sync" -o /dev/null -w "%{http_code}\n"
grep -E "Fetched .* holdings|Removing exited|Reconciliation removed|Broker returned no holdings" logs/trading-app.log | tail
```
`syncHoldingsFromBroker` skips zero-qty broker rows and **deletes** local rows the broker no longer returns. It is **self-healing** (a wrongly-removed row reappears next sync if still held) and **guarded** — an empty/failed fetch (e.g. `TokenException`, common on weekends) skips reconciliation rather than wiping the table. If you see "Broker returned no holdings", the token is stale; the live token refreshes Mon 08:35.

## 3. Immediate fix when the broker is unavailable — direct JDBC delete
No `psql`/Docker client is installed, but the Postgres JDBC driver is in `~/.m2` and `java` 21 runs single-file source. Always **back up first**, wrap in a transaction, and abort if the backup count != target count. Pattern (creds in [application.yml](src/main/resources/application.yml) — `tradingdb`, user `root`):
```java
// DbCleanup.java (throwaway — DELETE after running; it contains the DB password)
String filter = "quantity <= 0 OR symbol = 'NSE:KWIL-BE'"; // adjust target
// 1) SELECT+print rows  2) CREATE TABLE holdings_bak_<date> AS SELECT...  3) assert bak==target
// 4) DELETE  5) verify counts  6) commit
```
Run: `java -cp "C:/Users/<user>/.m2/repository/org/postgresql/postgresql/42.7.7/postgresql-42.7.7.jar" DbCleanup.java`, then `rm DbCleanup.java`. Restore from the backup table with `INSERT INTO holdings SELECT * FROM holdings_bak_<date> WHERE ...`. The app reads the DB live, so no restart is needed; re-check step 1.

## Guardrails
- A direct DB delete is destructive — confirm intent and never delete without the backup table + count assertion.
- Only `quantity <= 0` is *definitively* safe to delete. A stale non-zero row is a judgement call; rely on its self-healing property (broker sync re-adds if real).
- Don't add a `quantity > 0` filter to the reconciliation query itself — it must see the dead rows to delete them.
