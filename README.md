# wealth-management

A long-term portfolio research platform for the Indian equity market, built as a Spring Boot
application for a single retail investor.

It is **not** a trading application. There is no intraday loop, no buy/sell signal engine and no
short-term price prediction — those were deliberately removed (see `SPEC.md` §19, §39). The
question it exists to answer is whether a listed Indian business can compound earnings and capital
over 5–10+ years, judged on seven pillars: business quality, growth, cash generation, management
quality, competitive advantage, valuation and risk.

## What it does

- **Multibagger screening** — a 7-dimension composite (momentum, volume, relative strength, price
  structure, valuation, institutional interest, financial quality) over a ~370-name universe.
- **Fundamentals** — annual Ind-AS XBRL pulled straight from NSE's filing archive: ROCE, ROE,
  debt/equity, real cash conversion, capex cycle, forensic red flags, turnaround detection.
- **Compounding lenses** — "can this business compound?" (latest year) and "has it compounded?"
  (the multi-year record), reported separately and never merged.
- **Portfolio** — goal-based allocation and drift, tax-aware FIFO/LIFO/HIFO lot tracking,
  diversification risk (HHI, concentration), conviction/thesis tracking, rebalancing proposals,
  accumulation planning, dividends, and a time-weighted return that removes deposits and withdrawals.
- **Analyst target ledger** — records who said what about a stock and then measures whether they
  were right, against the Nifty over the same dates.
- **Macro exposure** — maps published macro and geopolitical events onto held businesses through a
  curated, readable rule table. The model extracts what moved; the map decides who is exposed.
- **Self-measurement** — every pick is scored at 30/90/180/365 days, every scoring signal reports
  its own coverage and spread, and a walk-forward harness refuses to promote a weighting that
  cannot clear a significance gate.

Reports go out by email during market hours. A read-only dashboard (`/index.html`) renders the
same data.

## Design commitments

These are unusual enough to be worth stating up front, and they are enforced by tests:

- **A null is never a zero.** "Not measured" and "measured and bad" are different findings and are
  rendered differently everywhere.
- **A new scoring signal ships in shadow mode** — computed, persisted and measured, contributing
  zero points — until its information coefficient earns promotion.
- **A feature that scores itself is not measured.** Anything that makes a claim registers with the
  accuracy tracker and is graded against excess return over the universe.
- **One question, one rule table.** "Is it still a good time to buy?" is answered by a single shared
  rule table on every screen, so two surfaces cannot disagree in the same six words.

`SPEC.md` is the authoritative specification. `BUGS.md` is the full defect ledger, including the
reasoning behind fixes. `CLAUDE.md` carries the working notes and the accumulated gotchas.

## Stack

Spring Boot 3.4 · Java 21 · PostgreSQL · Zerodha Kite Connect · Apache PDFBox · Spring AI
(model-optional). Runs on Windows in paper-simulator mode.

## Setup

```bash
# 1. Credentials -- copy the template OUTSIDE the repo and fill it in.
mkdir -p ~/.intraday && cp config/application.yml.example ~/.intraday/application.yml
export SPRING_CONFIG_ADDITIONAL_LOCATION=file://$HOME/.intraday/
# Windows: start-app.bat sets this automatically from %USERPROFILE%\.intraday\

# 2. PostgreSQL
createdb tradingdb

# 3. Build and run
mvn clean compile
mvn spring-boot:run          # or: start-app.bat  (Windows: clean, compile, free :8080, run)
```

The credentials file lives **outside the working tree**, loaded via
`SPRING_CONFIG_ADDITIONAL_LOCATION`, and overrides the matching `${VAR:}` placeholder in
`src/main/resources/application.yml`. That placement is deliberate: it means no `.gitignore` rule
is load-bearing, so no `git add` -- accidental or forced -- can reach a secret. The tracked config
contains none. Every value can equally be supplied as an environment variable
(`DB_PASSWORD`, `MAIL_PASSWORD`, `KITE_API_KEY`, `KITE_API_SECRET`, `KITE_USER_ID`,
`KITE_PASSWORD`, `KITE_TOTP_SECRET`).

Two setup notes that cost real time if missed:

- `totp-secret` must be the **BASE32 seed** from Zerodha's "Can't scan?" link, not the 6-digit code.
- The mail password is a Gmail **app password**, and `spring.mail.username` is also the report
  recipient.

```bash
mvn test      # 805 tests; change a scoring rule and one should fail
```

## Scope

The app runs only during market hours (09:15–15:30 IST on weekdays) plus a Saturday 07:50–10:35
window for the weekly screening. Every scheduled job is guarded at runtime, not just by its cron.

Personal data — broker tradebook exports, credentials, local config — is gitignored and is not part
of this repository.

## Disclaimer

This is a personal research tool, not investment advice, and it is not a product. Nothing it
produces is a recommendation to buy or sell any security.
