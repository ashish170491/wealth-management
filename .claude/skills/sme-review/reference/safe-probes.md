# Safe probes — what the reviewer may run, and what it must never run

In this API a `GET` can email a report, spend half an hour, or write a score row. There is no way to
tell from the URL shape. This file is the allowlist; treat anything not on it as unsafe until you
have read its controller method.

---

## 0. Is the app even up?

```bash
curl -s -o /dev/null -w "http=%{http_code}\n" -m 8 http://localhost:8080/api/dashboard/health
```

`http=000` means nothing is listening. The app runs ~09:00–15:45 IST on weekdays plus the Saturday
07:50–10:35 window, so outside those hours *down is the normal state* — degrade to code, SPEC, BUGS
and direct SQL rather than starting it. If the user wants it started, that is `/deploy-verify`, and
it is their call, not yours.

Two traps when you do measure against a running app:

- **First request after boot is slow by design** — the DispatcherServlet used to initialise lazily
  and the first request paid ~12 s (B-086). Warm it before timing anything.
- **A responding endpoint is not proof it is your build** (Gotcha 128). Two restarts once appeared to
  succeed while port 8080 was still served by an hour-old process, and JIT warm-up (23 s → 6.5 s →
  4.9 s) read exactly like a fix landing. Compare the process start time against the edit time, or
  find a fresh `Started IntradayApplication` line, before believing any before/after number.

---

## 1. Safe GETs — DB-only, no email, no broker, page-load safe

These are what the dashboard itself calls on page load, so they are safe by construction.

```bash
B=http://localhost:8080

curl -s $B/api/dashboard/data-health          # every automated check the app runs on itself (SPEC 44)
curl -s $B/api/dashboard/health               # server time, market flag, per-table freshness
curl -s $B/api/dashboard/summary              # landing aggregate
curl -s $B/api/dashboard/screener             # latest screening run that actually has rows
curl -s "$B/api/dashboard/compounding?symbol=NSE:RELIANCE"
curl -s "$B/api/dashboard/series/matrix?days=90"

curl -s $B/api/accuracy/summary               # hit rate, excess return, IC per source x horizon
curl -s $B/api/accuracy/coverage              # PER-SIGNAL COVERAGE VECTOR - start here
curl -s "$B/api/accuracy/coverage/trend?signal=Valuation&days=90"
curl -s "$B/api/accuracy/by-source/MULTIBAGGER"

curl -s $B/api/fundamentals/coverage          # how deep the annual history is
curl -s "$B/api/fundamentals/long-horizon?symbol=NSE:RELIANCE"
curl -s "$B/api/fundamentals/history?symbol=NSE:RELIANCE"
curl -s "$B/api/fundamentals/turnarounds"

curl -s $B/api/learning/variants               # the six pre-registered weightings
curl -s "$B/api/learning/shadow"
curl -s "$B/api/learning/reviews?horizon=90"   # recorded promotion-gate verdicts

curl -s $B/api/portfolio/core-holdings
curl -s "$B/api/portfolio/performance?days=365"
curl -s $B/api/portfolio/quality
curl -s "$B/api/watchlist/items"
curl -s $B/api/multibagger/under-radar
curl -s $B/api/universe/dynamic
curl -s "$B/api/insider/recent?days=30"
curl -s $B/api/ipo/pipeline
curl -s "$B/api/ipo/recent?months=36"
curl -s $B/api/macro/exposure/portfolio
curl -s "$B/api/macro/events?days=30"
curl -s $B/api/macro/status
curl -s $B/api/analyst/track-record
curl -s $B/api/analyst/overlap
curl -s "$B/api/analyst/targets?symbol=NSE:RELIANCE"
curl -s $B/api/trading/holdings/decay
curl -s $B/api/trading/holdings/buy-timing
```

**Slow but safe** (seconds to a minute, no email, no write) — the dashboard's own `SLOW_BUT_SAFE`
allowlist in `static/js/api.js`, and the authoritative copy:

```
/api/research/levels/{sym}   /api/research/analyst/{sym}   /api/research/valuation/{sym}
/api/research/earnings/{sym} /api/research/capital-efficiency/{sym}
/api/research/shareholding/{sym}
/api/accuracy/dimension-ic?horizon=90&source=MULTIBAGGER   # hits Kite - not 14:00-15:30
```

---

## 2. Never call these

| Path | What it actually does |
|---|---|
| `GET /api/research/{symbol}` | Full 15-dimension research **plus an email**. Catch-all — any unlisted `/api/research/*` path lands here |
| `GET /api/research/discover`, `/discover/quantitative`, `/universe/expand` | AI + **email** |
| `GET /api/multibagger/screen/{symbol}` | 5–20 s **and writes a `multibagger_scores` row** and may record a recommendation (Gotcha 50) |
| `GET /api/multibagger/screen/tier/{tier}` | Screens 369 symbols — **30+ minutes** |
| `GET /api/fiidii/report` | Live NSE fetch |
| `GET /api/fiidii/debug-raw` | **Clears caches** |
| `GET /api/universe/ipo-watch` | Kite-backed, measured 6 minutes |
| **Any `POST`** | Without exception. They write, email, or spend the broker budget |

If you need to know what an endpoint does and it is not listed here, read its controller method
before calling it — do not infer from the path.

---

## 3. Contention windows

Kite is paced process-wide at ~2.9 req/s, and that single budget has starved the afternoon
schedulers twice (B-014, B-049). On a **trading day**:

- **14:00–15:30** — the daily screening owns the broker budget. Nothing Kite-backed.
- **09:40–10:15** — the FII/DII jobs own the NSE session. Nothing NSE-backed.
- Outside market hours the contention does not exist, and the guards that read "from 14:00" were
  written when the app only ran in-window (Gotcha 97). Use judgement: the reason for the guard is
  contention, so the guard covers exactly the contention.

---

## 4. Direct SQL — SELECT only

No `psql` client is installed. The Postgres JDBC driver is in `~/.m2` and Java 21 runs single-file
source. DB is `tradingdb` on `localhost:5432`, user `root`, password empty by default
(`application.yml` lines 19–23).

Write the throwaway to the **scratchpad**, never the repo:

```java
// %SCRATCH%/Q.java
import java.sql.*;
public class Q {
  public static void main(String[] a) throws Exception {
    String sql = String.join(" ", a);
    if (!sql.trim().toLowerCase().startsWith("select")) throw new IllegalArgumentException("SELECT only");
    try (Connection c = DriverManager.getConnection(
            "jdbc:postgresql://localhost:5432/tradingdb?sslmode=disable", "root", "");
         Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
      ResultSetMetaData m = r.getMetaData();
      int n = m.getColumnCount();
      for (int i = 1; i <= n; i++) System.out.print(m.getColumnLabel(i) + (i < n ? "\t" : "\n"));
      while (r.next()) for (int i = 1; i <= n; i++) System.out.print(r.getString(i) + (i < n ? "\t" : "\n"));
    }
  }
}
```

```bash
PG=$(ls ~/.m2/repository/org/postgresql/postgresql/*/postgresql-*.jar | tail -1)
java -cp "$PG" "$SCRATCH/Q.java" "select ..."
```

The app reads the DB live and this is read-only, so no restart and no lock risk. It also works when
the app is **down**, which is most of the day — prefer it over starting the app.

### Queries that earn their keep

**Coverage and variance for every dimension on the latest run** — the single highest-yield probe:

```sql
select signal_name, measured, not_applicable, not_measured, coverage_percent,
       cross_sectional_mean, cross_sectional_stddev, collapsed
from screening_coverage
where screening_date = (select max(screening_date) from screening_coverage)
order by coverage_percent asc
```

**Is a column actually varying, or constant across the universe?**

```sql
select count(*) rows, count(col) non_null, count(distinct col) distinct_vals,
       min(col), max(col), round(stddev(col)::numeric, 3) sd
from multibagger_scores
where screening_date = (select max(screening_date) from multibagger_scores)
```

**Is a feed still delivering, or did it die while returning HTTP 200?** (B-089 ran four months.)

```sql
select source, max(disclosure_date) newest, count(*) rows
from insider_disclosures group by source order by newest desc
```

**Scoring-version pooling** — two different engines filed under one name make any panel meaningless:

```sql
select scoring_version, count(*), min(screening_date), max(screening_date)
from multibagger_scores group by scoring_version order by 3
```

**Does the schema actually have the column the entity declares?** (`ddl-auto=update` does not always
add one, and never alters a type — Gotcha 74, 129.)

```sql
select column_name, data_type, character_maximum_length, is_nullable
from information_schema.columns where table_name = 'multibagger_scores' order by column_name
```

**Annual-history depth, which gates every long-horizon lens:**

```sql
select years, count(*) symbols from (
  select symbol, count(*) years from annual_fundamentals group by symbol
) t group by years order by years
```

---

## 5. Static checks

```bash
python scripts/check_js_syntax.py                 # brackets, commas, import placement - not names
mvn -q test -Dtest='*SurfaceContractTest'         # wire field names the JS dereferences
mvn -q test -Dtest='*CoverageTest,DataHealthTest,NewSignalShadowModeTest'
mvn test                                          # 813 tests; a scoring change should fail one
```

Several tests deliberately document **known defects** and say so in a comment — a failure there may
be the intended fix. Read the comment before calling it a regression.
