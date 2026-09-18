# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Mission pivoted 2026-04-18**: This was originally an automated intraday trading system. It is now a **long-term portfolio investment platform** for the Indian equity market. Intraday trading code remains in the repo but is **disabled by default** via `trading.loop.enabled: false` in [application.yml](src/main/resources/application.yml).

The current purpose is to help a single retail investor build a healthy long-term portfolio through:
- Fundamentals-driven selection (earnings, insider activity, valuation, institutional flows) — ✅ active
- Goal-based allocation + drift alerts (SPEC §5) — ✅ active
- Tax-aware lot tracking with FIFO/LIFO/HIFO matching + harvest suggestions (SPEC §9) — ✅ active
- Diversification risk metrics: HHI, sector & stock concentration (SPEC §7) — ✅ active
- Conviction & thesis tracking via multibagger-score drift (SPEC §6) — ✅ active
- Rebalancing engine with non-executing trade proposals (SPEC §10) — ✅ active
- Accumulation planner: SIP / price-ladder / signal-gated tranches (SPEC §8) — ✅ active
- Dividend tracking, YTD summary, reinvestment suggestions (SPEC §11) — ✅ active

**Mission, as re-stated 2026-09-05 (SPEC §1, §19, §40)**: identify Indian listed businesses that can **compound earnings and capital over 5–10+ years**, judged on seven pillars — business quality, growth, cash generation, management quality, competitive advantage, valuation, risk. This is a research platform, **not** a trading application: no buy/sell signal engines, no intraday, and no signal accepted on a short-term result. The surviving timing verdicts are accumulation timing for already-vetted names, answered by one shared rule table (Gotcha 85) and frozen. **SPEC §40 is where a new research feature is classified** — pillar, horizon, coverage row, shadow-or-not — before it is designed.

**Implementation status**: research, discovery, multibagger screening, holdings analysis, FII/DII tracking, and all report emails are ✅ active. All 7 portfolio modules (SPEC §5–§11) shipped as MVP (2026-04-18). See [SPEC.md](SPEC.md) for the status marker and known caveats per module.

**New package `com.example.trading.portfolio`** contains the 7 modules plus sub-packages `conviction/`, `accumulation/`, `dividend/`, `rebalance/`, `risk/`, `tax/`. Endpoints live under `/api/portfolio/*` — see SPEC §16.

Tech: Spring Boot 3.4 + Java 21, PostgreSQL, Zerodha Kite Connect, Apache PDFBox 3.0.3 (transcript text extraction, SPEC §34). Runs on Windows in paper-simulator mode.

**Legacy intraday code REMOVED (2026-05-24)**: The old intraday trading machinery — previously disabled via `trading.loop.enabled=false` — has now been **deleted** from the repo (~48 files). Removed: the 1-minute `TradingScheduler` loop; all trading strategies + `StrategyConfig`/`TradingSignal`/`TradingStrategy` (kept `strategy/StrategyUtils`, a shared indicator helper); `filters/` (signal confirmation, regime filter); `regime/StrategyWeightCalculator` (kept `MarketRegimeDetector` — still used by Market Direction); `execution/`, `monitoring/`, `position/`, `sync/`, `risk/RiskManagementService` (kept `risk/RiskConfig`); the **ML trap-detection** chain (`MLTrapDetectionService`, `TrainingDataCollector`, trap `FeatureExtractor`, `MLController`, `AutomatedModelTrainingService`, etc. — the parser and `ml/holdings/` followed on 2026-08-28, see below); intraday **backtesting** (`backtest/` + `/api/backtest`); and the intraday **dynamic watchlist** (`DynamicWatchlistService`/`Scheduler`). Legacy endpoints on `TradingController` (positions/PnL/close/sync/validate-trade/eod-email/watchlist-dynamic) and the trap/backtest cleanup arms of `DataCleanupScheduler` were removed with them. The `trade`/`position`/`signal` JPA entities + repositories were **kept** (still read by analytics/cleanup; simply never written now). Sections further down describing the trading loop, signal confirmation, position monitoring, strategies, trap ML, and backtesting are **historical** — that code no longer exists.

**ML analysis REMOVED (2026-08-28)**: the holdings ML chain (`ml/` package in full — `XGBoostModelParser`, `HoldingsMLService`, `HoldingsFeatureExtractor`, `HoldingsAnomalyDetector`, `HoldingsTrainingDataCollector`, `HoldingsModelTrainingService`, the `holdings_ml_training`/`holdings_anomalies` entities + repositories) and the sector-reversal ML chain (`scanner/sector/ml/`) were **deleted**, along with `models/`, `training_data/`, the Python training scripts and the `/ml-retrain-verify` skill. Why: no component ever measured the models (`RecommendationTracker` never covered ML); the recommendation model was `binary:logistic` re-expanded into six classes by fixed cuts and on the last live day emitted SELL/STRONG_SELL for every holding it did not discard as "uncalibrated"; the training set had no symbol/date so its random split leaked; and the 30-day ±4% target was the wrong question for a 1–3-year portfolio. The `ML Insights` email section, the `ML: …` note in `analysisNotes`, the `holdings.ml_*`/`has_anomaly`/`blended_score` columns (now orphaned in the DB, like the trade tables — never written or read), `POST /api/trading/holdings/relabel-training|train-models`, the `trading.ml.*` and `trading.scanner.sector-reversal.ml.*` config blocks and the two ML cleanup arms went with it. `MarketRegimeDetector` stays (Market Direction). Any future ML must be built on `recommendation_outcomes` (180/365-day excess return over the screening universe), walk-forward validated, IC-measured in shadow mode — SPEC §25.5.

**Trading-horizon features REMOVED (2026-09-03)** — SPEC §39. Six features, ~7,900 lines, 22 files, 8 scheduled jobs and ~30 endpoints, cut on one brief: *accuracy and quality over quantity*. Gone: the **option-chain analyser** (PCR / max pain / OI — every 3 min, ~3,000 Kite calls/day, never a `RecommendationTracker` source); `SignalTrackingService` (**provably dead** — both writers had zero callers since 2026-05-24, so a 30-second scheduler queried an empty table ~720×/day); the **sector-reversal engine** (`scanner/sector/`, IC **−0.163 @30d / −0.195 @90d**, suppressed since 2026-08-25); the **Sector Tailwind** dimension (8% of the composite, **two attainable values**, correlation with the composite −0.07, 30% coverage, sd 4.7); the **breakout scanner** + `analytics/` in full (a *fifth* surface answering the buy-timing question with its own entry/stop/target, scored only by its own private target-vs-stop board); and `MarketIntelligenceService` (news-keyword BUY/EXIT suggestions whose only readers were the two deleted emails). Multibagger is now **7 dimensions**; Sector Tailwind's 0.08 was redistributed *in proportion*, never by IC (gotcha 27), and `MULTIBAGGER_CODE_REVISION` → 2. **Kept**: `SectorReversalEntity`/`Repository` + the rows already written (delete the producer, keep the record — SPEC §25.1), `KiteInstrumentsService`, the RSS news scanner (**not** Zerodha Pulse - that claim was false and `ZerodhaPulseNewsService` was an orphan with one diagnostic caller; both the claim and the class went on 2026-09-12, B-104), `MarketRegimeDetector`, and Market Direction on 5 signals with a **normalised** composite. Sections below describing the option chain, the breakout scanner, sector reversal, signal-performance tracking, market direction, market-regime detection, the Kite instruments cache or an 8th dimension are **historical**.

**Market Direction REMOVED (2026-09-03, same pass)** — SPEC §39.5. Two emails a day predicting STRONGLY_BULLISH…STRONGLY_BEARISH from 5 signals, and **never measured by anything**: it was not a `RecommendationTracker` source, so in months of running it produced no hit rate and no IC. It is also the wrong question — a 1–3 year holding decision does not turn on whether the Nifty is bullish this morning, and telling an investor the market looks bearish is an invitation to do the one thing this app exists to prevent. Removing it made **three more things dead**, which is the real finding: `GlobalMarketDataService` + `MarketNewsSentimentService` (its only consumers), the whole `regime/` package (`MarketRegimeDetector`'s only caller was Market Direction — the note that kept it alive on 2026-05-24 and 2026-08-28 was load-bearing on exactly one edge), and **`KiteInstrumentsService`**, whose remaining caller was `MarketRegimeDetector.getNearestFuturesSymbol`. That last one downloaded a **15–40 MB CSV at every startup and again at 08:30 daily**, with a 50 MB WebClient buffer and a 5-minute 429 back-off, for nothing. Gone with it: the `trading.regime.*` config block, the monthly `benchmark-symbol` NFO-contract maintenance chore (legacy gotcha 6), and **the last genuinely pre-market scheduler**. This paragraph originally said `TokenManagementService` 08:35 remained as a second one; that was never true — its cron is `0 45 9 * * MON-FRI`, an in-window recovery for the `ApplicationReadyEvent` login (corrected 2026-09-05, B-076). **No `@Scheduled` method now fires before 09:15 on a weekday**; the Saturday multibagger pair is the only exception left.

## Working with Features & Requirements

**Always consult [SPEC.md](SPEC.md) before proposing or implementing any new feature or requirement.** SPEC.md is the authoritative source for system behavior, business rules, thresholds, schedules, reports, and non-goals.

For every new feature/requirement request:
1. **Read SPEC.md first.** Check whether the behavior is already specified, conflicts with existing rules, or is explicitly listed as a non-goal (§19).
2. **Cite the relevant section** (e.g., "SPEC.md §3.4 forbids a scheduler outside 09:15–15:30") when discussing scope, constraints, or trade-offs with the user.
3. **If the change is material** — new scoring dimension, new threshold, new report, new schedule, new data source — update SPEC.md in the same commit per §20 Change-Control Rules.
4. **If the request conflicts with a non-goal in §19**, flag it to the user before implementing. Since 2026-09-05 §19 also bars **buy/sell signal engines** and **short-term price prediction**; §20 rules 9 and 10 are the review gates.
5. **Classify a research feature in §40 before designing it** — which of the seven pillars it measures, the horizon it will be judged at, its coverage row (§38.2), and whether it ships in shadow mode.

**§13 (universe), §14 (reports), §15 (the 26-row schedule) and §16's heading were restored 2026-09-05** (B-076) — they were cited by §3.4, §20, §21 and this file while missing from SPEC.md. Cite them rather than re-deriving a schedule or endpoint list from the code.

If the user's request is vague, use SPEC.md to ground the conversation rather than guessing.

## Working with Bugs

**Always consult [BUGS.md](BUGS.md) when investigating a problem, fixing a defect, or auditing the system.** BUGS.md is the running ledger of every bug found in this codebase — open, in-progress, resolved, and won't-fix — with severity, root cause, fix, blast radius (which investment-decision input gets distorted), and verification proof.

For every bug-related action:
1. **Found a bug?** Add an entry to BUGS.md "Open Bugs" with a fresh `B-NNN` ID before (or alongside) writing any fix. The blast-radius field matters most: name the analysis output that gets distorted.
2. **Fixing one?** Move the entry to "In Progress", then to "Resolved" with the fix summary, files changed (with line links), and an empirical verification step. Don't mark resolved on compile success alone — re-run the affected pipeline and observe the symptom is gone.
3. **Investigating a regression or odd behaviour?** Search BUGS.md first — the same root cause may already be documented (e.g., "NSE field renames silently null out earnings analysis" → B-003). Don't re-diagnose what's already known.
4. **Touching code that fixed a past bug?** Read the resolved entry to understand *why* the fix was written that way before changing it. The "Blast radius" line tells you what regresses if you get it wrong.

The legacy "Critical Bug Fixes Applied" section further down in this file is a historical record (pre-2026-05) and is not maintained anymore — new entries go in BUGS.md exclusively.

## Maintenance Skills

Recurring operational tasks are codified as Claude Code skills in `.claude/skills/` (invoke as `/<name>`):
- **`/log-health-review`** — audit the last N days of logs: ERROR/WARN aggregation, scheduler firing matrix, report-send verification, vs known noise.
- **`/holdings-hygiene`** — detect/clean exited & zero-quantity holdings (sync reconciliation, or guarded direct JDBC delete). B-016.
- **`/deploy-verify`** — `mvn clean compile` + `start-app.bat` + verify clean boot (scheduler pool, 0 errors); knows the build-vs-restart timing trap.

## Build & Run Commands

```bash
# Quick start (clean, compile, kill port 8080, start in background)
start-app.bat  # Logs: logs\trading-app.log (app) and logs\start-app.log (the script's own
               # run: Maven output, port kill, launch - appended per run). The script used to
               # discard both, so a failed 09:00 scheduled start left no trace at all (B-114).
               # Each phase closes its own redirect: wrapping the whole script in one lets the
               # app JVM inherit the handle and hold the log open for its whole life, which
               # makes the NEXT run die at the redirect, silently.

# Stop the running application
stop-app.bat

# Compile
mvn compile

# Run application (default profile, uses PostgreSQL)
mvn spring-boot:run

# Run with environment overrides
KITE_ACCESS_TOKEN=your_token mvn spring-boot:run

# Build JAR
mvn package  # Output: target/intraday-app-0.0.1-SNAPSHOT.jar

# Run tests (note: no unit tests exist yet - test directory is empty)
mvn test

# Run a single test class (when tests are added)
mvn test -Dtest=ClassName

# Run a single test method
mvn test -Dtest=ClassName#methodName
```

**Database Access**:
- PostgreSQL: `jdbc:postgresql://localhost:5432/tradingdb`
- Credentials in [application.yml](src/main/resources/application.yml:7-10)

**Important**: Application runs ONLY during market hours (9:15 AM - 3:30 PM IST weekdays), plus a **Saturday 07:50–10:35 IST window** (2026-08-25) in which the weekly multibagger screening (08:00 SAT) and report email (09:00 SAT) run — both Windows start/stop tasks have Saturday triggers for this (see `setup-scheduled-tasks.ps1`). Scheduler automatically stops outside these hours.

### PowerShell Test Scripts

```powershell
.\test-all-strategies.ps1       # Backtest all strategies Oct-Dec 2024
.\test-backtest.ps1             # Custom backtest with JSON config
.\test-extended-backtest.ps1    # Extended backtest
.\test-historical-2024.ps1      # 2024 historical data test
.\test-nifty-options.ps1        # Options analysis
.\test-nifty-options-v2.ps1     # Options analysis v2
.\test-professional-strategies.ps1  # Professional strategy tests
.\test-sector-email.ps1         # Sector email report test
.\check-strategies.ps1          # Strategy diagnostics
```

## Core Architecture

### 1. Trading Loop Orchestration (Every Minute)

**Flow**: `TradingScheduler` → Market Data → ALL Strategies → Regime Weighting → Signal Confirmation → Execution

```
TradingScheduler.runTradingLoop() [@Scheduled(cron = "0 * * * * *")]
├─ Check market hours (MarketHoursService)
├─ Check token validity (TokenManagementService)
├─ Check market regime filters (shouldAllowTrading, isInSafeTradingHours)
├─ Check early signal window (9:15-9:30: signals generated, orders BLOCKED)
├─ Check daily profit target (halt all new trades if target reached)
├─ Detect market regime ONCE per loop (MarketRegimeDetector)
│  └─ Returns: TRENDING_UP, TRENDING_DOWN, RANGING, HIGH_VOLATILITY, NEUTRAL
├─ For each symbol in watchlist:
│  ├─ Fetch current price + 24h of 1-min candles
│  ├─ Check pending signals (pullback/confirmation) → ATR-based SL if confirmed
│  ├─ Evaluate ALL 4 active strategies (stateless, concurrent-safe):
│  │  ├─ EMA Crossover (20/50 with 0.05% trend filter, 2.5x ATR stops)
│  │  ├─ VWAP Crossover (1.0x volume, 0.25% min distance)
│  │  ├─ Opening Range Breakout (15-min range, 9:30-10:30 window)
│  │  └─ RSI Mean Reversion (14-period, dual mode: mean-reversion + trend-pullback)
│  ├─ Apply regime weight to each signal (StrategyWeightCalculator)
│  ├─ Block counter-trend trades (e.g., no SELL in TRENDING_UP)
│  ├─ Check volume confirmation (relaxed 0.3x for RSI, strict 0.5x+ for others)
│  ├─ Apply multi-strategy confirmation bonus (+10% if 2+ agree)
│  ├─ Apply lunch hour filter (12:30-14:00: higher confidence required)
│  ├─ Check EMA50 filter (BUY only if price > EMA50, SELL only if price < EMA50)
│  ├─ Calculate regime-aware stop-loss (0.5% base × regime multiplier)
│  ├─ Adjust target based on regime (1% RANGING, 2% TRENDING, 1.5% default)
│  ├─ ML trap detection (block if >80% trap probability)
│  ├─ Signal confirmation checks (pullback, confirmation candles, strict volume)
│  └─ Execute if all checks pass
```

**Critical Design Decisions**:
- **Stateless strategies**: Accept symbol, price, history as parameters; no instance state
- **Regime detection cached**: 60 seconds to avoid repeated ADX/VIX calculations
- **Multi-strategy**: All strategies run every minute; highest weighted confidence wins
- **Early signal window**: 9:15-9:30 generates signals but blocks orders (Opening Range period)
- **Lunch hour filter**: 12:30-14:00 requires higher confidence (low volume, choppy action)
- **Dual stop-loss modes**: ATR-based for confirmed pending signals, percentage-based for new signals
- **Correlation ID**: Format `{8-char-uuid}-{SYMBOL}` injected via `TradeContext` (search logs with this)
- **7 strategy classes exist, 4 active**: VwapPullbackStrategy, PrevDayHighLowStrategy, SimpleMovingAverageStrategy are NOT registered as beans in StrategyConfig

### 2. Market Regime Detection

**Purpose**: Adjust strategy weights, stop-loss, and targets based on market conditions.

**Regimes**:
- `HIGH_VOLATILITY`: VIX > 22, wider stops (2.5×), higher confidence required (0.62)
- `TRENDING_UP/DOWN`: ADX > 25 + EMA slope, wider stops (2.0×), extended targets (2%)
- `RANGING`: ADX < 20, tighter stops (1.2×), reduced targets (1%), lower confidence (0.50)
- `NEUTRAL`: Default, moderate stops (1.5×), normal targets (1.5%)

**Files**: [MarketRegimeDetector.java](src/main/java/com/example/trading/regime/MarketRegimeDetector.java), [StrategyWeightCalculator.java](src/main/java/com/example/trading/regime/StrategyWeightCalculator.java)

### 3. Signal Confirmation Pipeline

Signals don't execute immediately. They go through:
1. **Pullback Entry**: Wait for 0.15% price retrace (reduces trap risk)
2. **Confirmation Candles**: Wait 1 candle in signal direction
3. **Strict Volume Filter**: Volume > 1.2× 20-candle average
4. **ML Trap Detection**: Block if trap probability > 80%
5. **Option Chain Filter**: Block if conflicts with Nifty market direction (PCR analysis)
6. **Fade Trapped Signals**: Generate contrarian signal if price moves 0.3% against original signal

**File**: [SignalConfirmationService.java](src/main/java/com/example/trading/filters/SignalConfirmationService.java)

### 4. Zerodha Kite Authentication (100% Automated)

**Critical Setup Requirements**:
1. `totp-secret` in config must be **BASE32 secret** (from Zerodha's "Can't scan?" link), NOT the 6-digit code
2. GoogleAuthenticator generates TOTP every 30 seconds automatically
3. Token auto-refreshes daily at 08:30 IST before market open

**Authentication Flow**:
```
TokenManagementService (@Scheduled daily 8:30 AM IST)
└─ KiteAuthService.performFullLogin()
   ├─ Generate TOTP from totp-secret
   ├─ Simulate browser login: POST credentials + TOTP
   ├─ Extract request_token from redirect URL
   ├─ Exchange for access_token: POST /session/token with SHA256 checksum
   └─ Store token for day's trading
```

**Kite API Details**:
- Base URL: `https://api.kite.trade`
- Headers: `X-Kite-Version: 3`, `Authorization: token API_KEY:ACCESS_TOKEN`
- Symbol formats: `NSE:RELIANCE` (equity), `NFO:NIFTY26JANFUT` (futures)
- Historical data: `[timestamp, open, high, low, close, volume]` arrays
- Uses **WebFlux WebClient** with `@Retryable` (3 attempts, exponential backoff)

**Files**: [KiteAuthService.java](src/main/java/com/example/trading/broker/kite/KiteAuthService.java), [KiteBrokerClient.java](src/main/java/com/example/trading/broker/kite/KiteBrokerClient.java)

### 5. Dynamic Watchlist (Stock Selection) — REMOVED 2026-05-24. Historical.

**Purpose**: Automatically select 10-15 best stocks each morning based on market conditions, replacing the hardcoded watchlist.

**Modes**:
- **Test mode** (`trading.test-mode.enabled: true`): Uses fixed test-stocks list from config
- **Dynamic mode** (test mode disabled): DynamicWatchlistService scores and selects stocks at 9:20 AM

**Data Sources for Scoring** (weighted):
1. **Breakout Scanner** (35%): Technical score, volume ratio, trend direction, R:R ratio
2. **Sector Reversal** (30%): Upside score, recommendation strength, sector reversing status
3. **FII/DII Flows** (20%): Institutional money flow by sector (buy/sell net)
4. **Liquidity** (15%): Known high-volume stocks get a base boost

**Selection Process** (runs at 9:20 AM IST):
1. Score all ~65 stocks in pool from 4 data sources
2. Filter: minimum score ≥ 30, not blacklisted
3. Sector diversification: max 3 stocks per sector
4. Select top 15 by score (min 5, with high-liquidity fallbacks)
5. Mid-morning refresh at 11:00 AM picks up new breakout signals

**Files**: [DynamicWatchlistService.java](src/main/java/com/example/trading/watchlist/DynamicWatchlistService.java), [DynamicWatchlistScheduler.java](src/main/java/com/example/trading/watchlist/DynamicWatchlistScheduler.java)

**Endpoints**:
- `GET /api/trading/watchlist/dynamic` - View today's selected stocks with scores
- `POST /api/trading/watchlist/dynamic/refresh` - Force re-selection

### 6. Position Management (Every 30 seconds)

**PositionMonitoringService** runs scheduled checks for:
- Stop-loss triggers (original, breakeven, trailing)
- Take-profit targets (partial booking at 0.5%, full at 1%)
- Time-based exit (360 min with <0.5% profit)
- Trend reversal exit (price crosses EMA20)
- EOD square-off (15:25 IST - 5 min before close)

**Breakeven/Trailing Logic**:
- **Breakeven**: Move SL to entry+0.15% after 0.4% profit
- **Trailing**: Activate at 0.5% profit, trail 0.4% from peak
- **Partial Booking**: Exit 50% at 0.5% profit, remaining 50% at 1% (SL moved to breakeven after partial)

**File**: [PositionMonitoringService.java](src/main/java/com/example/trading/monitoring/PositionMonitoringService.java)

## Critical Configuration Patterns

### Test Mode vs Production Mode

**Test Mode** (enabled in config): Trades only stocks in `trading.test-mode.test-stocks` list
```yaml
trading:
  test-mode:
    enabled: true  # Set to false to activate dynamic watchlist mode
    test-stocks:
      - NSE:JSWSTEEL
      - NSE:VEDL
```

**Paper Trading vs Live**:
```yaml
trading:
  execution:
    paper-trading-mode: false  # Set to true for simulation
```

**Always test with paper-trading-mode: true before live deployment.**

### Key Configuration Files

All modules use `@ConfigurationProperties` pattern:
- `KiteConfig` - broker.kite.*
- `RiskConfig` - trading.risk.* (position size, daily loss limits)
- `ExecutionConfig` - trading.execution.* (paper vs live, instrument type)
- `MarketRegimeConfig` - trading.filters.* (VIX, ADX thresholds)
- `OptionChainConfig` - trading.execution.options.analysis.* (PCR thresholds)
- `AiConfig` - ai.* (provider, model, API key, temperature)

**Secrets**: Use environment variables for production: `${KITE_ACCESS_TOKEN}`, `${DB_PASSWORD}`, `${AI_API_KEY}`

## Common Development Tasks

### Adding a New Strategy

1. Implement `TradingStrategy` interface in `strategy/` package (methods: `getName()`, `evaluate()`)
2. Must be **stateless** - accept symbol, currentPrice, history as parameters
3. Use TA4J for indicators or custom logic on `List<Map<String, Object>> history`
4. History format: `{timestamp, open, high, low, close, volume}` - values may be Integer or Double
5. Parse timestamps as `ZonedDateTime` in IST timezone
6. Include trend confirmation (e.g., only BUY if price > EMA50)
7. Return descriptive `TradingSignal.hold()` when conditions not met
8. Register as `@Bean` in [StrategyConfig.java](src/main/java/com/example/trading/strategy/StrategyConfig.java) to activate
9. **Note**: 3 inactive strategies exist as reference: `VwapPullbackStrategy`, `PrevDayHighLowStrategy`, `SimpleMovingAverageStrategy`

**Example Pattern**:
```java
@Override
public TradingSignal evaluate(String symbol, double currentPrice, List<Map<String, Object>> history) {
    if (history.size() < 50) {
        return TradingSignal.hold(symbol, "Insufficient history");
    }

    BaseBarSeries series = convertToBarSeries(history, symbol);
    ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
    EMAIndicator ema50 = new EMAIndicator(closePrice, 50);

    // Only BUY if above EMA50 (trend confirmation)
    if (currentPrice > ema50.getValue(series.getEndIndex()).doubleValue()) {
        return TradingSignal.buy(symbol, 0.75, "Above EMA50");
    }

    return TradingSignal.hold(symbol, "Waiting for trend");
}
```

### Testing Strategies

**Backtest via REST API**:
```bash
# Backtest specific strategy with date range
curl -X POST http://localhost:8080/api/backtest/run \
  -H "Content-Type: application/json" \
  -d '{
    "symbol": "NSE:RELIANCE",
    "strategy": "EMA Crossover",
    "from": "2024-10-01",
    "to": "2024-12-31",
    "initialCapital": 50000,
    "lookbackPeriod": 100
  }'
```

**PowerShell Scripts** (in repo):
- `test-all-strategies.ps1` - Runs backtest for all strategies Oct-Dec 2024
- `test-backtest.ps1` - Custom backtest with JSON config

**Good Metrics**: Win rate > 50%, Sharpe > 1.0, Max drawdown < 10%

### ML Models — REMOVED 2026-08-28

The subsections below are **historical**. All three model chains (trap, holdings, sector reversal) are deleted; see "ML analysis REMOVED" at the top of this file. B-007 / B-015 document why the holdings models were never trustworthy.

All models were XGBoost JSON format in the `models/` directory:

**Trap Detection** (`models/trap_detector.json`):
- Binary classifier blocking signals with >80% trap probability
- Features (20 candles required): price action, RSI, ADX, volume surge, EMA distances
- Flow: `TrainingDataCollector` logs features → Python training (`scripts/train_trap_detector.py`) → Deploy JSON → `MLTrapDetector` blocks/adjusts signals
- Files: [MLTrapDetector.java](src/main/java/com/example/trading/ml/MLTrapDetector.java), [TrainingDataCollector.java](src/main/java/com/example/trading/ml/TrainingDataCollector.java)

**Holdings Analysis** (`models/holdings/`):
- `price_movement.json` - ~1-month (30-day) price direction prediction
- `recommendation.json` - Buy/sell recommendation scoring
- `risk_assessment.json` - Risk-level regression (predicts drawdown; `reg:squarederror`, NOT logistic — see parser note below)
- Automated retraining: 2:30 PM weekdays if sufficient new data
- Training: `scripts/train_holdings_models.py`
- **Labeling horizon (B-007, 2026-05-23)**: outcome labels use a **30-day horizon with ±4% thresholds** (`trading.ml.holdings.label-after-days` + `price-movement.up/down-threshold` in [application.yml](src/main/resources/application.yml)). A prior 5-day/±5% scheme produced 92% NEUTRAL / 88% HOLD labels → models collapsed to the majority class. After re-labeling, classes are ~47% NEUTRAL / 29% UP / 24% DOWN and the models discriminate (prob std ~0.34). **Gotcha**: these thresholds live in application.yml and *override* the `@ConfigurationProperties` Java defaults in `HoldingsMLConfig` — change both or the yml wins.
- **Parser objective-awareness (B-015)**: [XGBoostModelParser](src/main/java/com/example/trading/ml/XGBoostModelParser.java) applies `sigmoid()` only for `logistic` objectives and returns `base_score + Σtrees` raw for regression (the risk model). Applying sigmoid to the regressor previously pinned every holding's risk to exactly 0.5.
- **Re-label after a labeling-scheme change**: `POST /api/trading/holdings/relabel-training` re-labels all stored records with the current horizon/thresholds (returns the new class distribution), then `POST /api/trading/holdings/train-models` retrains. The daily labeler ([HoldingsTrainingDataCollector](src/main/java/com/example/trading/ml/holdings/HoldingsTrainingDataCollector.java)) now reads the close *at* the horizon (not the latest close), so labels stay horizon-accurate and re-labeling is correct.

**Sector Reversal** (`models/sector_reversal/reversal_success.json`):
- Predicts sector reversal success probability
- Training: `scripts/train_sector_reversal_model.py`

### Option Chain Analysis — REMOVED 2026-09-03 (SPEC §39). Historical.

**Purpose**: Predict Nifty/BankNifty direction using options Open Interest (OI) data

**Metrics**:
- **PCR (Put-Call Ratio)**: > 1.2 = bullish, < 0.8 = bearish
- **Max Pain**: Strike with minimum option writer loss (price gravitates here)
- **Support/Resistance**: Highest Put OI = support, Highest Call OI = resistance

**Symbol Resolution via Kite Instruments CSV**:
- `KiteInstrumentsService` downloads the full instruments CSV from `https://api.kite.trade/instruments` at startup and daily at 8:30 AM
- CSV is ~15-40MB; WebClient buffer set to 50MB (`.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))`)
- Instruments cached in `ConcurrentHashMap` with composite key: `NFO:NIFTY:2026-02-24:25000:CE`
- `getNearestExpiry(index)` finds the nearest future expiry from the cache (more reliable than computing expiry manually)
- Rate limit protection: 5-minute retry interval between download attempts to avoid Kite 429 errors
- **Critical**: Symbols must include `NFO:` exchange prefix when calling Kite quotes API (e.g., `NFO:NIFTY26FEB25050CE`, not `NIFTY26FEB25050CE`)

**Integration**:
- `OptionChainAnalysisService` fetches 30 strikes around ATM every 3 minutes (cached)
- Uses `KiteInstrumentsService` to look up exact tradingsymbols for each strike/expiry
- `SignalConfirmationService` blocks trades conflicting with Nifty direction
- Hourly email reports during market hours (9 AM - 3 PM)

**Endpoint**: `POST /api/trading/options/send-report` (manual trigger)

**Files**: [OptionChainAnalysisService.java](src/main/java/com/example/trading/options/OptionChainAnalysisService.java), [KiteInstrumentsService.java](src/main/java/com/example/trading/broker/kite/KiteInstrumentsService.java)

### 7a. Spring AI — structured extraction (SPEC §48, 2026-09-12)

The stack in §7 below is **prose-only**: `AiService.analyze()` returns text for an email section. The
macro event reader needed something that stack cannot do — a **typed object** — so Spring AI was
added alongside it rather than through it.

```yaml
spring:
  ai:
    model: { chat: none, embedding: none, image: none, moderation: none, audio: {...} }
    chat.client.enabled: false     # MacroEventExtractor builds ChatClient.create(model) itself
    openai:    { api-key: ${OPENAI_API_KEY:},    base-url: ${OPENAI_BASE_URL:https://api.openai.com} }
    anthropic: { api-key: ${ANTHROPIC_API_KEY:} }
```

Five things to know before touching it:

1. **`spring.ai.model.chat` must be explicit, and `none` is the shipped default.** Each provider's
   autoconfig is match-if-missing, so with both starters on the classpath and no property you get
   **two** `ChatModel` beans and an ambiguous injection. A provider bean also fails at construction
   on a blank key — so `none` is the only valid state without one. Set it to `openai` or `anthropic`
   to turn the model reader on; nothing else changes.
2. **Version 1.1.8, pinned via the BOM. Do not move to 2.0.x** — it requires Spring Boot 4 and this
   app is on 3.4.
3. **`ObjectProvider<ChatModel>`, never a hard dependency.** `MacroEventExtractor` must start and
   work with no model at all; it falls back to the keyword rules and records the extractor as
   `KEYWORD(fallback:<modelId>)` so the ledger says which reader produced each row.
4. **The wire records are all-String** (`ExtractedEvent`, `EventBatch`) with
   `@JsonProperty(required = true)` and `@JsonPropertyDescription`. Deliberate: one hallucinated
   enum value then drops **one event** at validation instead of failing the whole batch. A
   future date is clamped to today; an event citing no held headline is rejected.
5. **The prompt forbids naming a company** (Gotcha 122). That boundary is the feature, not a
   nicety.

The existing `AiService` / `OpenAiService` / `ClaudeAiService` stack is untouched and still serves
every report section. Migrating it onto Spring AI is a recorded follow-up, not done.

### 7. AI-Powered Report Enrichment

**Purpose**: Provider-agnostic AI service that enriches trading reports with intelligent analysis using LLM APIs (OpenAI, Claude, or any compatible provider).

**Architecture**:
- `AiService` interface with `analyze(systemPrompt, userPrompt)` and `analyzeForReport(context)` methods
- `OpenAiService` — OpenAI Chat Completions API (`/v1/chat/completions`), also compatible with any OpenAI-compatible endpoint via `baseUrl` override
- `ClaudeAiService` — Anthropic Messages API (`/v1/messages`)
- `NoOpAiService` — Fallback when AI is disabled, returns empty strings for graceful degradation
- `AiServiceConfig` — Bean factory that selects implementation based on `ai.provider` config
- No external SDK dependencies — uses Spring WebFlux WebClient for all API calls

**Configuration** (`application.yml`):
```yaml
ai:
  enabled: true
  provider: openai                    # openai, claude, none
  api-key: ${AI_API_KEY:}             # Set via environment variable
  model: gpt-4o-mini                  # Cost-effective for report enrichment
  timeout-seconds: 30
  max-tokens: 1024
  temperature: 0.7
```

**Switching Providers**: Change `ai.provider` and `ai.model` in config. No code changes needed.
- OpenAI: `provider: openai`, `model: gpt-4o-mini` (or `gpt-4o`, `gpt-4-turbo`)
- Claude: `provider: claude`, `model: claude-sonnet-4-20250514` (or `claude-haiku-4-5-20251001`)
- Disable: `enabled: false` or `provider: none`

**Integration Points** (6 live report services; items 5, 7, 8 and 10 below were removed 2026-09-03 — SPEC §39 — and are kept here only so the numbering in older commits still resolves):
1. **Morning Briefing** (9:30 AM): AI Market Summary — synthesizes FII/DII, options, portfolio, breakouts into trading plan
2. **FII/DII Report** (10:00 AM): AI-enriched trading insights appended to rule-based insights
3. **Holdings Report** (3:18 PM): AI Portfolio Analysis — health assessment, attention items, risks
4. **Exit Timing Alerts** (10 AM, 12 PM, 2 PM): AI Exit Guidance — prioritized exit strategies
5. ~~**Performance Report** (3:10 PM daily, 3:12 PM weekly)~~ — REMOVED 2026-09-03
6. **Multibagger Report** (Saturday 9 AM): AI Multibagger Analysis — conviction picks and red flags
7. ~~**Breakout Scanner** (9:30, 11:30, 13:30, 15:00)~~ — REMOVED 2026-09-03
8. ~~**Option Chain** (hourly 9-15)~~ — REMOVED 2026-09-03
9. **Watchlist Report**: AI Watchlist Insights — top entry setups, traps to avoid
10. ~~**Sector Reversal Tracker** (9:30 AM)~~ — REMOVED 2026-09-03
11. **Market Impact News** (3:20 PM): AI News Impact Analysis — how events affect trading

**Reusable AI HTML Builder**: `AiService.buildAiHtmlSection(title, systemPrompt, userPrompt)` — returns styled HTML block with gradient border and "Powered by AI Analysis" label, or empty string if AI unavailable. Overload `buildAiHtmlSection(title, systemPrompt, userPrompt, modelOverride)` supports per-call model selection (e.g. use `gpt-4o` for deep research, `gpt-4o-mini` for quick summaries). `AiService.formatAiResponseAsHtml(title, response)` — static utility for converting AI text to email HTML.

**Reliability**:
- Transient failures (HTTP 429, 502, 503, 504, 529, `TimeoutException`, `ConnectException`) are retried with exponential backoff via Reactor `Retry.backoff(2, 1s)` — 3 total attempts, max 8 s backoff.
- `WebClientResponseException` is caught separately from generic exceptions so the full response body is logged (`getResponseBodyAsString()`), not just the message.
- All failure paths return `""` so report services degrade gracefully (SPEC.md §12.2).

**Model Family Handling**: `OpenAiService` auto-detects reasoning models by prefix (`o1`, `o3`, `o4`, `gpt-5`). For those, it sends `max_completion_tokens` and omits `temperature` (reasoning models fix temperature at 1.0). Regular chat models (`gpt-4o`, `gpt-4o-mini`, etc.) use `max_tokens` + `temperature`.

**Files**: [AiService.java](src/main/java/com/example/trading/ai/AiService.java), [OpenAiService.java](src/main/java/com/example/trading/ai/OpenAiService.java), [ClaudeAiService.java](src/main/java/com/example/trading/ai/ClaudeAiService.java), [NoOpAiService.java](src/main/java/com/example/trading/ai/NoOpAiService.java), [AiServiceConfig.java](src/main/java/com/example/trading/ai/AiServiceConfig.java), [AiConfig.java](src/main/java/com/example/trading/ai/AiConfig.java)

### 8. NSE Fundamental Data Service (Enhanced)

**Purpose**: Fetches and analyzes fundamental data from NSE India API — quarterly financials, shareholding patterns, and corporate actions. All data cached for 30 minutes.

**Data source (B-017, 2026-05-24)**: quarterly + annual financials now come from NSE's **integrated-filing** feed (`/api/integrated-filing-results`), parsed from `INTEGRATED_FILING_INDAS`/`_BANKING` XBRL (SEBI `in-capmkt` namespace). The legacy `results-comparision` (quarterly `re_*` JSON) and `corporates-financial-results` (annual) endpoints **froze at Dec-2024** when NSE migrated — do not use them. `fetchQuarterlyResults` fetches one filing's XBRL per quarter (OneD context, up to 8); `fetchAnnualFinancials` uses the latest March filing (FourD annual + OneI balance sheet). XBRL values are in rupees (÷1e7 = crore). Caveat: integrated system began ~Mar-2025, so ~5 quarters exist today (QoQ/YoY work; 8-quarter CAGR fills in by ~2027).

**Earnings Growth Analysis** (`NseDataService.analyzeEarningsGrowth(symbol)`):
- Multi-quarter revenue/profit/EPS trends (up to 8 quarters)
- QoQ and YoY growth calculations
- Revenue & Profit CAGR (2-year, when 8 quarters available)
- Operating margin, net margin, margin trend (expanding/contracting)
- Earnings acceleration detection (is growth rate itself increasing?)
- Consecutive growth quarters count
- Growth verdict: `STRONG_GROWTH`, `MODERATE_GROWTH`, `STAGNANT`, `DECLINING`

**Shareholding History** (`NseDataService.fetchShareholdingHistory(symbol)`):
- Multi-quarter shareholding pattern from NSE (promoter, FII, DII, public)
- Quarter-over-quarter change tracking for promoter, FII, DII
- Pledge data tracking (promoter pledged shares %)
- Insider signal generation: `STRONG_BUY` (promoter +>1%), `BUY` (+0-1%), `NEUTRAL`, `SELL` (-0-1%), `STRONG_SELL` (-<1%)

**Caching**: 30-minute TTL on all NSE data fetches via `ConcurrentHashMap<String, CachedData<?>>`

**Files**: [NseDataService.java](src/main/java/com/example/trading/ai/NseDataService.java)

### 9. Deep Stock Research Agent (15 Data Dimensions — Enhanced)

**Purpose**: AI-powered comprehensive stock analysis that gathers ALL available data — internal services + external web research (news, quarterly results, shareholding) — and produces a 12-section investment research note.

**Data Sources Aggregated** (15 dimensions per stock):

*Internal Data (10 dimensions):*
1. Current price (MarketDataService)
2. Holdings data — P&L, scores, ML predictions, support/resistance (HoldingsRepository)
3. Multibagger 7-dimension screening — momentum, volume, RS, structure, valuation, institutional, financial-quality (MultibaggerScreenerService). *Sector Tailwind was the 8th until 2026-09-03 (SPEC §39).*
4. Valuation — PE, PB, EPS, dividend yield, market cap (StockValuationService)
5. Watchlist — entry signals, confidence, R:R ratios (WatchlistRepository)
6. Breakout signals — last 7 days of breakout detections (BreakoutSignalRepository)
7. Sector reversal signals — historical reversal picks (SectorReversalRepository)
8. Multibagger score trend — historical score trajectory (MultibaggerScoreRepository)
9. FII/DII sector flows — institutional money flows for stock's sector (FiiDiiSectorAnalysisService)
10. 6-month price history — returns, volume analysis, higher lows pattern (MarketDataService)

*External Web Research (5 dimensions — enhanced):*
11. Recent news — last 7 days headlines from Google News RSS (StockNewsService)
12. Quarterly financial results + **Earnings Growth Analysis** — revenue, profit, EPS trends, CAGR, margin analysis, earnings acceleration detection, growth verdict (NseDataService)
13. Shareholding pattern + **Historical Trend** — promoter, FII, DII, public holdings with quarter-over-quarter changes, pledge data, insider signal (STRONG_BUY to STRONG_SELL) (NseDataService)
14. Corporate announcements — recent filings and announcements from NSE (NseDataService)
15. Peer comparison + **Deep Metrics** — stock vs sector peers on PE, P/B, dividend yield, YoY revenue/profit growth, net margin, market cap, RSI (PeerComparisonService)

**AI Analysis Output** (12 sections):
1. Verdict: Strong Buy / Buy / Hold / Reduce / Sell with conviction
2. Investment Thesis: Core story — what makes this stock special
3. Earnings & Growth: Revenue/profit trends, QoQ and YoY growth rates
4. Technical Picture: Key levels, trend, momentum, volume, chart patterns
5. Fundamental View: Valuation (PE vs sector, PB), earnings quality
6. Ownership Analysis: Promoter trend, institutional interest, insider signals
7. Peer Comparison: Stock vs sector peers — better/worse on what metrics
8. News & Catalysts: Recent news impact, upcoming catalysts, corporate actions
9. Institutional Activity: FII/DII sector flows, smart money signals
10. Risk Factors: 3-4 specific risks, quantified where possible
11. Action Plan: Entry zone, stop loss, 3 targets (short/medium/long term)
12. Multibagger Potential: Score 1-10, catalysts for 3-5x, timeline

### 10. AI Stock Discovery & Universe Expansion Service

**Purpose**: AI discovers new investment opportunities beyond the existing ~90 stock universe. Analyzes portfolio gaps, emerging themes, overlooked gems, and contrarian turnaround candidates.

**Discovery Output** (6 sections):
1. Emerging Themes: 3-4 sector themes with 2-3 specific stock picks each
2. Overlooked Gems: 5 mid/small-cap stocks NOT in our universe with catalysts
3. Contrarian Ideas: 3 beaten-down stocks with reversal catalysts to watch
4. Portfolio Gaps: Underrepresented sectors with specific stocks to fill gaps
5. Risk Alert: Concentration risks and macro threats to current portfolio
6. Universe Expansion: 10 specific NSE stocks to add to screening universe

**Discovery Modes**:
- **AI Discovery**: `discoverOpportunities()` — AI-powered thematic analysis
- **Systematic Discovery**: `discoverSystematically()` — finds holdings not in screening universe
- **Universe Expansion**: `suggestUniverseExpansion()` — combines AI + systematic + sector gap analysis
- **Sector Gap Analysis**: Compares 27 Indian market sectors against screening universe coverage

**Context Provided to AI**: Current portfolio (holdings, sector allocation, P&L), screening universe overview (top stocks, sector distribution), so AI can suggest complementary opportunities.

**REST API Endpoints**:
- `GET /api/research/{symbol}` — Deep 15-dimension research (e.g., `/api/research/NSE:RELIANCE`)
- `GET /api/research/discover` — AI discovers new investment opportunities
- `GET /api/research/discover/quantitative` — **Data-driven discovery scan** (no AI, pure quantitative scoring with entry/exit levels, sends email)
- `GET /api/research/levels/{symbol}` — **Entry/exit levels** for a single stock (support/resistance, EMAs, RSI, ATR, targets, stop loss)
- `GET /api/research/universe/expand` — Universe expansion analysis (new stocks & sector gaps)
- `GET /api/research/earnings/{symbol}` — Earnings growth analysis (CAGR, margins, verdict)
- `GET /api/research/shareholding/{symbol}` — Shareholding history & trend (promoter/FII changes, insider signal)

**Integration**:
- **Multibagger Weekly Report**: Top 3 candidates get deep research in the Saturday email
- **Multibagger Scoring**: Earnings growth verdict and insider activity signal contribute bonus points to composite score
- **Programmatic**: `StockResearchService.researchStock(symbol)`, `StockDiscoveryService.discoverOpportunities()`, `StockDiscoveryService.suggestUniverseExpansion()`

**Enhanced Peer Comparison** (`PeerComparisonService.compareWithMetrics(symbol, sector)`):
- Compares target stock vs up to 8 sector peers on: PE, P/B, dividend yield, YoY revenue growth, YoY profit growth, net margin, market cap, RSI, composite score, grade
- Highlights sector average PE, best grower, most undervalued stock
- Returns structured `PeerComparisonResult` DTO (not just text)

### 11. Quantitative Stock Discovery & Entry/Exit Analysis

**Purpose**: Data-driven stock discovery — NO AI opinions. Scans the full universe using real NSE data (earnings, shareholding, valuation) and Kite price data. Calculates optimal entry/exit levels for each opportunity.

**5-Dimension Scoring** (0-100 composite):
1. **Earnings Growth** (0-25) — YoY revenue/profit growth, CAGR, margin, earnings acceleration
2. **Insider Activity** (0-20) — Promoter buying/selling, FII trend, pledge risk
3. **Valuation** (0-20) — PE vs sector PE discount/premium, dividend yield
4. **Price Momentum** (0-20) — 1M/3M returns, higher-lows pattern
5. **Volume** (0-15) — Recent vs 60-day average volume surge

**Entry/Exit Levels** (calculated per stock):
- **Support/Resistance**: From swing highs/lows in 6-month price history
- **EMAs**: EMA20, EMA50, EMA200 as dynamic support/resistance
- **Entry Zone**: Near support with ATR buffer
- **Stop Loss**: Below support or 2x ATR below entry
- **Targets**: 1.5R, 2.5R, 4R from risk distance
- **Entry Signal**: OVERSOLD / EMA50_PULLBACK / NEAR_SUPPORT / UPTREND / DOWNTREND / NEUTRAL
- **Exit Signal**: RSI_OVERBOUGHT / NEAR_RESISTANCE / BELOW_EMA50 / HOLD

**Email Report**: Score-ranked table (top 20) + detailed cards for top 10 with full entry/exit levels, triggers, risks, and methodology.

**Files**: [QuantitativeDiscoveryService.java](src/main/java/com/example/trading/scanner/QuantitativeDiscoveryService.java), [QuantitativeDiscoveryReportService.java](src/main/java/com/example/trading/scanner/QuantitativeDiscoveryReportService.java), [StockResearchController.java](src/main/java/com/example/trading/api/StockResearchController.java)

**Files (all research)**: [StockResearchService.java](src/main/java/com/example/trading/ai/StockResearchService.java), [StockNewsService.java](src/main/java/com/example/trading/ai/StockNewsService.java), [NseDataService.java](src/main/java/com/example/trading/ai/NseDataService.java), [PeerComparisonService.java](src/main/java/com/example/trading/ai/PeerComparisonService.java), [StockDiscoveryService.java](src/main/java/com/example/trading/ai/StockDiscoveryService.java), [StockResearchController.java](src/main/java/com/example/trading/api/StockResearchController.java)

### 12. Market Direction Prediction — REMOVED 2026-09-03 (SPEC §39.5). Historical.

**Purpose**: Predicts market direction by combining 6 quantitative data signals — NO AI opinions. Uses options PCR, institutional flows, VIX, global indices, news sentiment scoring, and market regime detection.

**6 Signal Sources** (composite -100 to +100):
1. **Options PCR** (+/-20) — Nifty Put-Call Ratio from Kite options chain
2. **FII/DII Flows** (+/-20) — Institutional money direction from NSE (FII >2000Cr = strong bullish)
3. **India VIX** (+/-15) — Fear/greed gauge (VIX <12 = bullish, >28 = extreme fear)
4. **Global Markets** (+/-20) — Overnight changes: S&P 500, Nasdaq, Dow, Nikkei, Hang Seng, DAX, FTSE (Google Finance)
5. **News Sentiment** (+/-15) — Keyword-scored Google News RSS headlines (bullish/bearish keyword frequency)
6. **Market Regime** (+/-10) — ADX/EMA trend direction (TRENDING_UP, TRENDING_DOWN, RANGING, HIGH_VOLATILITY)

**Output**: STRONGLY_BULLISH / BULLISH / NEUTRAL / BEARISH / STRONGLY_BEARISH with confidence % and trading recommendation.

**News Sentiment Scoring**: No AI — pure keyword matching with weighted scores. Strong keywords (rally, crash, plunge = 2 pts) vs moderate (gain, fall, rise = 1 pt). Net sentiment calculated as (bullish - bearish) / total * 100.

**Email Report**: Direction score header, signal breakdown table with score bars, global markets table, news headlines with matched keywords.

**REST API**: `GET /api/research/market-direction` — returns composite score, direction, signals, and sends email report.

**Files**: [MarketDirectionService.java](src/main/java/com/example/trading/intelligence/MarketDirectionService.java), [MarketDirectionReportService.java](src/main/java/com/example/trading/intelligence/MarketDirectionReportService.java), [GlobalMarketDataService.java](src/main/java/com/example/trading/intelligence/GlobalMarketDataService.java), [MarketNewsSentimentService.java](src/main/java/com/example/trading/intelligence/MarketNewsSentimentService.java)

## Important Scheduled Tasks

All use `zone = "Asia/Kolkata"` for IST execution:

```java
// Core trading loop
@Scheduled(cron = "0 * * * * *")  // Every minute

// Position monitoring (SL/target checks)
@Scheduled(cron = "30 * * * * *")  // Every 30 seconds

// Token refresh (also runs at startup via ApplicationReadyEvent — that is the primary path).
// The ONLY @Scheduled method without an isMarketOpen() guard, and it is in-window anyway.
@Scheduled(cron = "0 45 9 * * MON-FRI", zone = "Asia/Kolkata")  // 9:45 AM IST, mid-day recovery only

// Position sync with broker
@Scheduled(cron = "0 0/2 9-15 * * MON-FRI", zone = "Asia/Kolkata")  // Every 2 min during market

// Dynamic watchlist stock selection
@Scheduled(cron = "0 20 9 * * MON-FRI", zone = "Asia/Kolkata")  // 9:20 AM IST (morning selection)
@Scheduled(cron = "0 0 11 * * MON-FRI", zone = "Asia/Kolkata")  // 11:00 AM IST (mid-morning refresh)

// Holdings analysis
@Scheduled(cron = "0 15 15 * * MON-FRI")  // 3:15 PM (before server stops)

// Core-holding classification (SPEC §35) — NO cron of its own. It runs at the end of
// HoldingsScheduler.analyzeHoldings() (0 30 10 * * MON-FRI), which is already guarded.
// It needs that morning's refreshed prices and the previous day's screening scores.

// FII/DII data fetch and report
@Scheduled(cron = "0 45 9 * * MON-FRI", zone = "Asia/Kolkata")  // 9:45 AM (fetch previous day data)
@Scheduled(cron = "0 0 10 * * MON-FRI", zone = "Asia/Kolkata")  // 10:00 AM (send email report)

// Morning Briefing Email (consolidated pre-market report)
@Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Kolkata")  // 9:30 AM IST

// Exit Timing Alerts (holdings exit condition checks)
@Scheduled(cron = "0 0 10,12,14 * * MON-FRI", zone = "Asia/Kolkata")  // 10 AM, 12 PM, 2 PM IST

// Multibagger Screening (daily + weekly full scan in the Saturday carve-out)
@Scheduled(cron = "0 0 14 * * MON-FRI", zone = "Asia/Kolkata")   // 2:00 PM daily screening (isMarketOpen guard)
@Scheduled(cron = "0 0 8 * * SAT", zone = "Asia/Kolkata")        // 8:00 AM Saturday full weekly screening (isSaturdayScreeningWindow guard)
@Scheduled(cron = "0 0 9 * * SAT", zone = "Asia/Kolkata")        // 9:00 AM Saturday weekly email report (same guard)

// Annual-fundamentals backfill batch (SPEC §32.6) — converges the universe's
// multi-year history over ~13 weekday runs, then costs ~nothing. NSE-only, no Kite calls.
@Scheduled(cron = "0 30 11 * * MON-FRI", zone = "Asia/Kolkata")  // 11:30 AM — a genuine gap, clear of every NSE-heavy job

// Analyst target ledger (SPEC §49) — capture from feeds another job already fetched
// (DB-only), then measure the open book against daily candles. Bounded and rotating
// least-recently-measured first; stops at 13:50 so the 14:00 screening owns the broker budget.
@Scheduled(cron = "0 20 13 * * MON-FRI", zone = "Asia/Kolkata")  // 1:20 PM — gap between the 13:00 trio and the 14:00 screening

// Insider disclosure capture (SPEC §28) — PIT + bulk/block deals
@Scheduled(cron = "0 45 14 * * MON-FRI", zone = "Asia/Kolkata")  // 2:45 PM — clear of the 14:00 screening and the 15:00-15:30 ramp

// IPO pipeline capture (SPEC §45) — three NSE list calls + a detail call per issue + one paced
// Kite quote per listing under a year old. NSE-light, Kite-light, and in a genuine gap.
@Scheduled(cron = "0 15 12 * * MON-FRI", zone = "Asia/Kolkata")  // 12:15 PM — clear of 09:45/10:00/11:30 NSE jobs and the 14:00 screening

// Quantitative Stock Discovery Scan
@Scheduled(cron = "0 0 10 * * MON-FRI", zone = "Asia/Kolkata")   // 10:00 AM daily discovery

// Recommendation Accuracy Tracking (SPEC.md §23)
@Scheduled(cron = "0 22 15 * * MON-FRI", zone = "Asia/Kolkata")  // 3:22 PM — update realized-return outcomes at 30/90/180/365d horizons
@Scheduled(cron = "0 25 15 * * FRI", zone = "Asia/Kolkata")      // 3:25 PM Friday — email weekly accuracy report

// Tax-Lot Auto-Capture (SPEC.md §9.3) — feeds the LTCG-aware holdings email
@Scheduled(cron = "0 28 15 * * MON-FRI", zone = "Asia/Kolkata")  // 3:28 PM — capture today's BUY/SELL trades from Kite /trades

// Target-Hit Alert (SPEC.md §26) — emails which picks/holdings reached their target
@Scheduled(cron = "0 5 15 * * MON-FRI", zone = "Asia/Kolkata")   // 3:05 PM — newly-hit only (dedup persisted in target_hit_events)
```

**The full 28-row schedule lives in SPEC §15** (restored 2026-09-05 alongside §13, §14 and §16's heading — they were cited everywhere and absent from the file, B-076). The block above is a summary; §15 is authoritative.

**Scheduler placement rule (SPEC.md §3.4)**: Every new `@Scheduled` job must fire within `09:15–15:30 IST MON-FRI` with `zone = "Asia/Kolkata"`. Pre-market, post-market, and weekend crons are rejected in review — the app is not running outside those hours. **One sanctioned exception (2026-08-25)**: the two weekly multibagger jobs fire Saturday 08:00/09:00 inside a dedicated Saturday 07:50–10:35 app window and guard with `MarketHoursService.isSaturdayScreeningWindow()` (07:45–10:30 SAT) instead of `isMarketOpen()`. No other scheduler may use that guard without a SPEC §3.4 exemption (read-only consumers such as `UniverseController` are not schedulers and are exempt from the restriction).

**Scheduler runtime guard (mandatory)**: Cron expressions like `0 0 9-15 * * MON-FRI` quietly fire at 09:00, which is **outside** the 09:15-15:30 window. Cron alone is not a safe enforcer. Every `@Scheduled` method MUST also call `MarketHoursService.isMarketOpen()` as its very first guard, returning early if it returns false. This is defense-in-depth — if a cron is ever wrong, the runtime check catches it.

```java
@Scheduled(cron = "...", zone = "Asia/Kolkata")
public void scheduledThing() {
    if (!marketHoursService.isMarketOpen()) {
        return; // Outside 09:15-15:30 IST window — SPEC §3.4.
    }
    // ... actual work
}
```

Codebase-wide audit on 2026-05-10 and 2026-05-11 added this guard to **every** `@Scheduled` method in the codebase. **28** remain — counted from the annotations themselves rather than from this list, which had drifted by one in each direction (SPEC §15 said 28 when the true figure was 27, this file said 29). The passes since: the three removals, the 2026-09-06 addition of the 11:30 fundamentals backfill, the 2026-09-09 addition of the 12:15 IPO capture, the 2026-09-12 deletion of the 15:20 market-impact summary and the 2026-09-12 addition of the 13:20 analyst target pass (SPEC §15 tabulates them all). Macro event extraction (SPEC §48) adds **none**: it runs only when the investor presses the button, and `macro.ingest.scheduled` is bound, logged at boot and false. The one method without the guard is `TokenManagementService.dailyTokenRefresh` at **09:45 MON-FRI** — in-window, and guarding it would stop the only thing it exists to do (recover a failed startup login). `KiteInstrumentsService` 08:30 was the codebase's last true pre-market job and was deleted on 2026-09-03. Defense in depth: the server itself only runs during market hours, so this guard catches (a) the shutdown ramp (15:30-15:45 when schedulers can still fire), (b) startup before 09:15, and (c) anyone who later changes a cron by mistake. When adding a new scheduler, follow the same pattern. Never inline the time check — always delegate to `MarketHoursService` so the rule lives in one place.

**Scheduler thread pool (B-014, 2026-05-23)**: `@Scheduled` jobs run on a multi-threaded `TaskScheduler` (pool size 4, `sched-` prefix) defined in [SchedulingConfig.java](src/main/java/com/example/trading/config/SchedulingConfig.java) — **not** Spring's single-threaded default. Why: the 15:00–15:30 window is densely packed and a long multibagger/holdings scan on one thread used to serialise the lightweight 15:22 recommendation-outcome / 15:25 accuracy-email / 15:28 tax-capture jobs behind it, pushing them past 15:30 where the `isMarketOpen()` guard silently aborts them (on Fri 2026-05-22 all three were starved). The pool is kept **small (4)** on purpose: enough to keep time-critical jobs from starving, small enough that several Kite-heavy scans never run in parallel and trip the broker 429 limit. Note: this does NOT enable `@Async` (there is no `@EnableAsync` — `SignalTrackingIntegrator`'s two `@Async` methods run synchronously by design; don't add `@EnableAsync` without auditing them). Concurrent scheduled execution means new shared mutable state must be thread-safe.

## Critical Gotchas

1. **Symbol Format Mismatch**: Use `NSE:NIFTY 50` not `NSE:NIFTY` for index (Kite API requirement)
2. **Option Symbol Format**: `NFO:NIFTY26FEB25100CE` for weekly expiry — always use `KiteInstrumentsService` to look up exact tradingsymbols (never construct manually)
3. **TOTP Secret**: Must be BASE32 from Zerodha, not the 6-digit code
4. **Stop Loss Direction**: BUY positions need SL < entry, SELL positions need SL > entry
5. **Paper vs Live Detection**: Paper orders get `PAPER-{UUID}` prefix; search logs for "PAPER TRADING:"
6. **History Data Array Format**: Kite returns `[timestamp, open, high, low, close, volume]` - mapped to `Map<String, Object>` by `MarketDataService`
7. **Correlation ID Search**: Format `{uuid}-{SYMBOL}` (e.g., `5a2b1c3d-NSE:RELIANCE`) for end-to-end tracing
8. **EOD Square-Off**: All positions closed at 15:25 IST (5-min buffer) - check logs for "EOD Square-Off"
9. **Performance**: 50 symbols × 5 strategies = 250 evaluations/min - TA4J calculations must be <1 sec overhead
10. **Index Mapping**: Use `getIndexSymbol()` helper in options services to map NIFTY → NSE:NIFTY 50
11. **Instruments CSV Field Mapping**: CSV fields: `instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange` — `instrument_type` is index 9 (CE/PE/FUT), NOT index 10 (segment)
12. **Instruments CSV Quoting**: CSV fields may be quoted (`"NIFTY"`) — always strip quotes with `.replace("\"", "")`
13. **Kite Quotes API NFO Prefix**: Symbols sent to `/quote` must include exchange prefix (`NFO:NIFTY26FEB25050CE`), otherwise API returns `{data: {}}`
14. **NSE Series Suffix Stripping** (B-013): Kite tradingsymbols never carry the NSE trading-series suffix (`-BE`/`-BZ`/`-BL`/`-IL` = trade-to-trade / illiquid). A holding like `NSE:KWIL-BE` returns `{status=success, data={}}` from `/quote`. `KiteBrokerClient.getQuote()` now strips the suffix before the call and aliases the response back to the original key, so token/price resolution works for these holdings transparently. A symbol that returns empty `data={}` even after stripping (e.g. `NSE:GSPL`) is genuinely invalid (renamed/delisted) — remove it from the pool, don't guess a replacement.
15. **NSE `/api/quote-equity` is bot-walled — don't try to "fix" it with headers** (B-018): it returns 403 for every request while sibling endpoints (`fiidiiTradeReact`, `integrated-filing-results`, `corporate-share-holdings-master`) return 200 on the *same* cookie jar. Full browser header sets, `Sec-Fetch-*`, and warming `get-quotes/equity?symbol=X` first were all tried — still 403. `equity-meta-info`, `equity-stockIndices` and `search/autocomplete` now 404 (NSE restructured). PE/market cap are computed from XBRL fundamentals instead; a circuit breaker stops retrying after 10 consecutive failures and re-arms every 6h. **Before adding any new NSE endpoint dependency, verify it isn't in the walled set** — and never let a failure path silently degrade to a null that downstream code scores as neutral.
16. **Holdings are active-only** (B-016): the `holdings` table must contain only currently-held stocks. `syncHoldingsFromBroker()` skips zero-qty broker rows and **reconciles** — deletes local rows the broker no longer returns (exited stocks) — guarded so an empty/failed broker fetch never wipes the table. Reports/analysis use `findActive()` / `findActiveOrderByScoreDesc()` (`quantity > 0`), NOT `findAll()`, so exited/zero-qty rows never reach an email even before the next sync reconciles them. The one query that must keep `findAll()` is the reconciliation itself (it needs to see the rows it deletes).
17. **A dashboard page-load must never touch an expensive or email-sending endpoint** (SPEC §27.4). In this API a `GET` can cost half an hour or send mail: `GET /api/multibagger/screen/tier/{tier}` screens 369 symbols (**30+ min**), `GET /api/multibagger/screen/{symbol}` takes 5-20s **and writes a score row**, `GET /api/accuracy/dimension-ic` hits Kite, `GET /api/fiidii/report` triggers a live NSE fetch, `/api/fiidii/debug-raw` clears caches, and **every `GET /api/research/*` sends an email except** `/levels`, `/analyst`, `/valuation`, `/earnings`, `/capital-efficiency`, `/shareholding`. That six-endpoint allowlist is hard-coded in both `static/js/api.js` (`SLOW_BUT_SAFE`) and `page-stock.js` — `api.js` throws rather than fetch anything off-list. Never add a research path without re-checking its side effects. **And never *remove* one without checking whether `GET /api/research/{symbol}` will catch it** (B-073): that mapping is a catch-all over full research + AI + email, so deleting `/market-direction` on 2026-09-03 left the old URL returning **200** and researching a "stock" named `market-direction`. `looksLikeSymbol()` now 404s a non-symbol path before any work; lower-case is the discriminator, since every tradingsymbol is upper-case and every sibling path is kebab-case.
18. **Static resources are served from `target/classes`, so UI edits need a rebuild — unless the `file:` location wins.** `start-app.bat` runs `mvn clean compile` then `mvn spring-boot:run`, so `classpath:/static/` resolves into `target/classes/static/`. `spring.web.resources.static-locations` therefore lists `file:./src/main/resources/static/` **first**, making a browser refresh enough. Spring skips missing locations silently, so a packaged JAR still works. Don't remove it or every CSS tweak costs a 50-second rebuild.
19. **`ExitTimingAlertService.evaluateHolding()` mutates dedup state inline.** It calls `sentAlertsToday.add(alertKey)` inside its own condition, so any read-only caller that invokes it **consumes the day's alert slots and the next 10am/12pm/2pm email goes out empty** — silently, for weeks. Any API/dashboard exposure must go through a dedup-free path (`evaluateHolding(h, recordDedup=false)`), never the method as-is. Same hazard class in `TargetHitAlertService`, though its dedup is DB-persisted in `target_hit_events`.
20. **Two "latest data" endpoints are empty in normal use.** `GET /api/multibagger/scores` serves an **in-memory cache** that is empty after every restart (and this app restarts daily at 09:00), and `GET /api/multibagger/history` defaults to **today**, which has no rows until the 14:00 screening. Likewise `/api/fiidii/summary` and `/api/sector-scanner/latest-results` serve in-memory scan state. Anything needing "the most recent real screening" must walk back through `findScreeningDates()` — that is what `DashboardService.screener()` does.
21. **Null is not zero, in the UI too** (B-019 restated as a rendering rule, SPEC §21 rule 7). Four multibagger dimensions are nullable `Integer` meaning *could not measure*; a null `informationCoefficient` and a `sampleSize < 10` cell mean *no conclusion available*. The dashboard renders all of these as an explicit striped "not measured" marker or a radar **gap**, never as `0`, `50`, or a blank cell. `format.js` returns the `NOT_MEASURED` string for any missing value so this is the default rather than something to remember.

22. **A price of `0.0` is "no price", never a quote.** `MarketDataService.getCurrentPrice` / `fetchLatestPrice` return `0.0` on every failure path — never `null` — because 113 of the 125 call sites dereference the result directly and would NPE if the contract changed (B-027). Callers must test `<= 0`. Every failure now logs at WARN and names the cause (empty payload / wrong key / missing `last_price`); before that, Kite's `{status=success, data={}}` fell through to `return 0.0` in total silence, so six delisted tickers burned 152 lookups per outcome run for months undetected.
23. **Kite calls are paced process-wide at ~2.9 req/s** (B-027). `KiteBrokerClient.reserveSlot()` gates every request at the single `executeWithRetry` choke point, using `delaySubscription` inside a `Mono.defer` so each retry claims its own slot and **no scheduler thread blocks**. Retry-with-backoff alone was insufficient — its 429 retries were exhausted 31 times on 2026-08-24 because the 4-thread pool fires several Kite-heavy jobs on the same cron minute. Don't add a Kite call that bypasses `executeWithRetry`, and don't `Thread.sleep` inside the reactive chain.
24. **An outcome's horizon is stored, not inferred** (B-028). `recommendation_outcomes.days_elapsed` records how long a return actually ran; `horizon_days` is only the label it is filed under. The scheduler measures a pick only within `horizon + MEASUREMENT_GRACE_DAYS` (7) and leaves later ones unmeasured. Accuracy queries exclude drifted rows **and** null `days_elapsed`. Any IC / hit-rate figure quoted from before 2026-08-24 was computed over mislabelled horizons (20.8% of 30d rows, 29.4% of 90d) — re-read it, don't reuse it.

25. **A feature that scores itself is not measured** (2026-09-03, SPEC §39.3). SECTOR_REVERSAL was the honest case: registered with `RecommendationTracker`, measured against excess return over the universe, and it read **−0.163 @30d / −0.195 @90d** — a higher upside score predicted a *worse* return. It was suppressed for a quarter and then removed, because "keep scanning so it carries on being scored" is right for one negative quarter and wrong after two. The breakout scanner was the dishonest case: never registered, so §23 had nothing on it, while `SignalPerformanceTracker` graded it on whether it hit a target or a stop within days — a private scoreboard that always reads better than the real loop. **If a pick is worth showing, register it with `RecommendationTracker`.** Both are gone; `sector_reversal_signals` rows are kept because the negative IC above is the evidence for the removal.

26. **High-conviction tier = composite ≥ 70, and it is a presentation tier, not a filter.** Measured median 4-month return by band: 8.43% (80+), 7.23% (70-79), 4.71% (65-69), 2.34% (50-64), −0.19% (<50). `MultibaggerScreenerService.isHighConviction()` reorders what reports lead with; `isCandidate` still governs who qualifies. `MultibaggerHighConvictionTest` fails if anyone turns it into a filter — the supporting evidence is one 4-month window, enough to rank on, not enough to discard on.
27. **Do not re-weight dimensions on the current IC.** Panel IC over 2026-04→08 reads Financial Quality +0.042 down to Sector Tailwind −0.009, with several balance-sheet metrics (ROE, ROA, cash conversion) *negative*. That is one risk-on quarter with ~5 independent 30-day periods, judging multi-year quality factors on four months. Valuation's −0.083 describes the **retired** NSE-PE implementation (constant June→20 Aug per B-018), not the rebuilt XBRL+DCF one, which has no forward returns until late September. B-023 stays gated.

28. **The Saturday window is a carve-out with exactly two authorised *scheduler* callers.** `MarketHoursService.isSaturdayScreeningWindow()` (07:45-10:30 SAT) is the *only* sanctioned exception to the SPEC §3.4 market-hours rule, and the Windows tasks start the app 07:50 / stop it 10:35 to match. Only `MultibaggerScheduler.weeklyFullScreening()` (08:00) and `sendWeeklyReport()` (09:00) may use it — every other `@Scheduled` method uses `isMarketOpen()`, which returns false on Saturday. A third *scheduled job* turns an exception into a convention; reject it in review. A **read-only consumer is not a scheduler** and is allowed — `UniverseController` reads the window to decide whether a manual Kite sweep may proceed (B-049). The rule stops work being scheduled outside market hours, not anyone asking what time it is. **Saturday also has no token safety net** — `dailyTokenRefresh` is MON-FRI, so the only login is the fire-and-forget startup attempt, which is why `weeklyFullScreening` aborts on `!hasValidToken()` rather than persisting a run of `TokenException` garbage (B-032).

29. **A new MULTIBAGGER sub-score needs a column AND a list entry, or it is silently unmeasured** (B-030). `RecommendationAccuracyService.multibaggerDataset()` reads sub-scores from **hardcoded `multibagger_scores` columns**, not from the `recommendation_dimensions` sidecar — the sidecar serves QUANT_DISCOVERY only, which has no score table. Writing sidecar rows for a multibagger pick does nothing at all: no error, no IC row. So every new signal needs (a) a nullable `Integer` column on `MultibaggerScoreEntity` and (b) an entry in that dimension list. Sub-scores are `Map<String,Integer>` — a `String` verdict or `Double` percentage cannot be measured, which is why `InsiderPulse.toScore()` exists.

30. **New scoring signals ship in shadow mode, not straight into the composite.** "Bonus first, dimension later" gates *promotion to a weighted dimension* on IC — but a bonus re-ranks picks the day it ships, so an unvalidated signal would steer the portfolio for a quarter before it could be judged. `trading.multibagger.insider-pulse-actionable: false` is the pattern: compute, persist, IC-measure, contribute **zero points**. The reason is arithmetic — the composite's own edge is +1.54pp/month at **t≈1.24, p≈0.28** over ~5 independent periods, and the existing bonus chain already spans roughly −31..+52 on a 0-100 blend. Every new free parameter added to a score whose edge is not established is a chance to fit a risk-on quarter as skill. Two signals shipped 2026-08-25 under this rule: Insider Pulse (shadow bonus) and Under-Discovery (a lens that never enters the composite at all).

31. **The insider feed moved on 2026-05-05, the old endpoint kept answering, and nothing noticed for four months** (B-089, SPEC §28.1). NSE circular `NSE/CML/2026/11` moved SEBI PIT Reg 7(2)/7(3) onto the single filing system through API-based integration between exchanges. Since then filings are published as XBRL under **`corporates-pit-gg`**; the old `corporates-pit` still returns HTTP 200 and still serves its **pre-May archive** correctly, so what died is the *feed* and not the *history* — Gotcha 72 exactly, second instance. The two windows abut (archive ends 2026-05-01, new feed begins 2026-05-03), so the combined record has no gap. **The all-market form now works, on the new endpoint only**: `corporates-pit-gg?index=equities` returned **2,381 filings across 495 symbols in one call**, which is what let the 80-symbol budget and its rotation be deleted (B-090). The old caution still holds for the *old* endpoint — `corporates-pit?index=equities` without a symbol is a silent `{"data":[]}` — so read the two endpoints as different things, not as a renamed one. The index row is a filing *header*; the trades are in the XBRL at `xmlFileName`, ingested once ever and keyed on `appId` (`insider_disclosures.filing_app_id`). The bulk/block CSVs on `nsearchives` were always all-market and never walled — **and that is why this was invisible**: they kept arriving daily, so `insider_disclosures` looked fresh every day of the outage. **A freshness check on a table cannot see one of its feeds die**; `/api/dashboard/data-health` now checks the newest **PIT-sourced** row specifically (WATCH at 7 days, PROBLEM at 21), which would have escalated in late May.
32. **74% of the raw insider feed is noise, and the mode filter is the entire feature.** Measured on the first live capture (1,272 rows, 80 symbols): only 331 are open-market trades by promoters/directors/KMP. ESOP allotments alone were 158 rows, inter-se promoter transfers 49, pledges and gifts more. Only `MARKET_PURCHASE`/`MARKET_SALE` by a signal category are scored — a pledge creation is a promoter **borrowing** against the company, close to the opposite of conviction buying. NSE also publishes occasional **future-dated** filings, which would never age out of a trailing window (B-031).

33. **UNKNOWN liquidity is not THIN, and an unmeasured lens is not a zero.** `classifyLiquidity(null)` returns `UNKNOWN` because `THIN` excludes a stock from universe promotion — letting a measurement gap collapse into THIN would blacklist stocks for having short price history rather than for being illiquid. Same rule for `underDiscoveryScore`: null means the quality gate rejected it or shareholding data was missing, never "scored zero". `daysToBuild` returns null rather than 0 so a report can never render "0 days to build" for a stock nobody can buy.

34. **The screener was seeing ~20% of the market it claims to search.** Measured 2026-08-26: NSE lists 2,559 securities, 2,291 of them mainboard `EQ`, and **1,598 had never been looked at** by the curated 361-name universe. That gap is why the Under-Discovery lens (SPEC §12.10) found exactly *one* candidate on its first live run — a lens for spotting overlooked stocks, pointed at Nifty 200 + midcap + smallcap 250, which is by construction the well-covered part of the market. F2 only pays off once §30 supplies names nobody is following; the two features are one feature in two commits.

35. **Universe expansion ships in observation mode and Stage A has no scheduler of its own.** `trading.universe.dynamic-expansion.enabled: false` means symbols are discovered, queued, deep-scored and recorded but never reach the screening universe — same gate as Insider Pulse, bigger blast radius. Stage A is invoked from **inside `weeklyFullScreening()`**, not from a new `@Scheduled` method with the Saturday guard (Gotcha 28: a third caller turns the exception into a convention). It is also the only slot with room — **measured 22 minutes** for 1,598 symbols, because each symbol costs *two* paced Kite calls (token resolution + candles), not one.

36. **`EQUITY_L.csv`, not the Kite instruments dump, is the universe source.** It carries the two fields the dump lacks: **trading series** (BE/BZ are trade-to-trade — excluded, and Kite tradingsymbols never carry the suffix, B-013) and **date of listing** (drives the IPO tracker). `KiteInstrumentsService` retains only NFO options anyway. SME/EMERGE listings are absent from this file, so they are excluded *structurally* rather than by a filter someone could delete.

37. **The expansion funnel is circular — do not read its promotion rate as validation** (SPEC §30.7). Stage A filters on momentum, relative strength, price structure and volume, which are **54% of the composite by weight**, so Stage B then "discovers" a high composite it effectively pre-selected. First live run: 9 of 10 promoted, eight at 90+ (`STRONG_MULTIBAGGER`), against a curated universe where only 62 of 287 clear the candidate bar at all. The coarse score also saturates — 766 survivors, top 40 all scoring 96-100, so "top 40" is near-arbitrary. Left unchanged pending evidence (Gotcha 27); observation mode means nothing reaches screening meanwhile.

38. **The funnel retires rows, it does not delete them** — and the API returns retired rows alongside live ones. A funnel that only shows its winners cannot be judged (SPEC §25.1). Retirement also requires **8 consecutive** weak weekly runs; one bad week drops nothing, and curated names are never touched either way.

39. **Dashboard `get()` is ungated — only `getOnDemand()` has the allowlist.** In this API a GET can email a report or start a 30-minute scan (Gotcha 17), and `SLOW_BUT_SAFE` in `static/js/api.js` only protects the on-demand path. Anything added to a page-load `get()` must be verified DB-only by hand. `/api/universe/ipo-watch` is on the on-demand allowlist because it is Kite-backed and slow; `/api/universe/dynamic` and `/api/insider/recent` are DB-only and safe on load.

40. **NSE's integrated filing carries NO comparative balance sheet — the prior year comes from `annual_fundamentals`** (B-034). The document *declares* a prior-year instant context, so a resolver finds one and everything looks fine; a fact count against it returns **exactly 1**. Measured null `cwipPrior` on 7 of 7 non-financials. Any year-on-year balance-sheet delta must come from §32's history table via `CapexCycleService`, never from `doc.priorInstantCtx` alone. Two rules follow: an "available" flag must mean *usable figures exist*, not *a context id resolved*; and a verdict's reason line must never quote a ratio that is null — the STEADY branch said "spending is roughly in line with depreciation" from a number that had never been computed, in the beginner-facing wording the user reads.

41. **A JS syntax error in a shared module blanks EVERY dashboard page, and every server-side check still passes.** There is no build step, so nothing parses `static/js/*.js` before a browser does. One missing comma in a `glossary.js` entry took the whole UI down on 2026-08-26 while `/`, `/index.html`, all 14 `.js` files and `/api/dashboard/health` returned 200 — the page-level `render().catch()` never runs because the module fails at *load*, so even the error fallback is invisible. Run `python scripts/check_js_syntax.py` after touching any file under `static/js/` (bracket balance with string/comment/regex awareness, plus the missing-comma pattern specifically). Note the failure signature: **HTML 200 + JS 200 + blank body = module load failure**, not a server problem.

42. **Shadow mode now covers three signals, and the exception is deliberate.** `capex-actionable`, `turnaround-actionable` and `insider-pulse-actionable` all default **false** (Gotcha 30). `forensic-actionable` defaults **true** — the asymmetry is the point: a bonus claims a stock will go up and being wrong costs a missed opportunity, while a risk control being wrong costs capital. The existing HIGH_RISK composite cap ships armed for the same reason. `NewSignalShadowModeTest` fails if anyone flips a default, so the change has to be argued rather than slipped in.

43. **Capex is the most tempting signal in the system and that is why it is shadowed.** It genuinely leads the P&L instead of following it — which is an argument for measuring it carefully, not for trusting it early. A leading indicator that leads in the *wrong* direction is worse than a lagging one, and a shrinking large build (capacity being commissioned) looks identical to a growing one on intensity alone. `EXPANSION_UNDERWAY` deliberately requires the direction, so it stays silent rather than guessing.

44. **"No forensic flags" is not "clean" — it is usually "nothing was checked".** The flags need ≥3 years in `annual_fundamentals`, which is populated only for stocks whose history has been imported. `ForensicResult.notMeasured` carries what could not run, and the holdings email prints an explicit coverage line naming how many holdings could not be checked at all. Never render an empty flag list as a clean bill of health. Same rule for `TurnaroundResult`: signals + notMet + notMeasured always totals 4, so "2 of 4" is auditable.

45. **An auditor problem escalates rather than deducts.** A resignation or qualified opinion forces the §12.5 composite cap at 54 independently of the financial-quality verdict — because every quality metric in this system is computed *from* the audited numbers, so a compromised audit makes a clean financial-quality reading meaningless rather than reassuring. Don't "simplify" it into a points penalty.

46. **An "intimation of conference call" is not a transcript.** It announces a call that has not happened and its PDF contains no discussion; feeding it to the model produces confident guidance extracted from nothing. `AnnouncementRecord.isTranscript()` requires a `.pdf` attachment and rejects *intimation* / *notice of* / *prior intimation*. Scanned image PDFs yield no text and return `NOT_READABLE` — an absence, never an empty analysis.

47. **The guidance ledger's value is entirely in what it refuses to say.** A `PENDING` promise is never counted as met or missed (marking one missed before its due date manufactures a bad record); the ratio counts resolved items only (pending items in the denominator would penalise a company for guiding often); `credibility()` returns `TOO_EARLY` below 4 resolved items (two-of-two is 100%, exactly the number that misleads); and `NO_DATA` means *unknown*, never *bad*. Resolution is manual on purpose — deciding whether "margins will improve" was met is a judgement, and automating it fills the ledger with confident nonsense. Only the measured ratio may ever be scored; the AI's read of transcript tone stays out of Deep Research entirely.

48. **A screener-export row label must be rejected before matching, not by it.** `Sales` and `Sales Growth %` are adjacent rows and the second starts with the first, so prefix matching writes a *percentage* into the sales field — a three-orders-of-magnitude error that flows into every CAGR, margin and turnaround verdict downstream. `isDerivedMetric()` drops rows containing %, growth, margin, ratio, per share, yield, cagr, roce, roe, days or turnover. Pinned by `FundamentalsImportTest.percentageRowsDoNotOverwriteFigures` — the bug was real, caught by the test, not hypothetical.

49. **`annual_fundamentals` has two writers and XBRL wins.** `source=IMPORT` comes from the user's CSV; `source=XBRL` is written by every screening run from the annual filing already in cache (free — it is cached 7 days). An import never overwrites an XBRL row for the same year: the filing outranks a third-party rendering of it. The import is a one-time bridge for the pre-2025 past; after that the table maintains itself, which is also what eventually makes the capex delta (Gotcha 40) measurable.

50. **Compute-to-decide and compute-to-publish are different operations** (B-035). `screenSingleStock()` writes a `multibagger_scores` row under today's date **and** records a MULTIBAGGER recommendation at ≥ 65. Calling it to *evaluate* a candidate therefore publishes that candidate — which is how universe expansion fed 19 unreviewed names into the dashboard, the morning briefing and the accuracy tracker while `dynamic-expansion.enabled` was `false`. Use `evaluateSingleStock()` for anything that is deciding rather than publishing. When you find a defect like this, the fix is two things: a guard so it stops, **and** removal of what was already written — a guard alone leaves the contamination measuring for a year.

51. **The `enabled` flag on a funnel gates less than its name suggests.** `trading.universe.dynamic-expansion.enabled` gates only `activeDynamicSymbols()`, i.e. the merge into the screening universe. Discovery, queueing, deep scoring and (until B-035) persistence all ran regardless. Before trusting any "observation mode", trace every write the disabled path still performs.

52. **A shared `WebClient` inherits Spring's 256 KB buffer, and NSE payloads exceed it** (B-054). One client in `NseDataService` serves nine call sites; the corporate-announcements feed for a large cap blew the limit, the exception was caught at DEBUG, and the method returned an empty list that every consumer read as "this company filed nothing". Dead paths included Deep Research dimension 14, the forensic auditor scan (the flag that forces the HIGH_RISK cap) and F8 transcript discovery. `NSE_MAX_RESPONSE_BYTES` is now 16 MB on the shared builder so new call sites inherit it. CLAUDE.md had documented this exact failure for the Kite instruments CSV since 2026-02 — the lesson was learned on one client and not applied to the other. **Any catch that degrades to an empty collection must log at WARN and say what the emptiness will be mistaken for.**

53. **`"unqualified opinion"` contains `"qualified opinion"`** (B-037). SEBI LODR Reg 33(3)(d) requires every listed company to file a declaration that its audit opinion is unmodified, so a substring match on the auditor keyword fired the system's most serious flag — −8 plus a forced HIGH_RISK cap at 54 — on the routine filing that exists to say nothing is wrong. `CLEAN_OPINION_MARKERS` is checked first and skips the announcement; matching runs on a hyphen-stripped copy because "un-modified" and "un-qualified" both appear in real filings. The general rule: **before substring-matching a negative keyword, check whether its negation contains it.**

54. **Fix B-037 before B-038, and generally: fix the false positive before you widen its reach.** The screener not scanning announcements (B-038) was the only thing containing the unqualified-opinion false positive to the ~35-stock holdings email. Enabling the scan first would have spread it to every stock scoring ≥ 55 and forced the HIGH_RISK cap on clean candidates across the universe. When two defects interact so that one masks the other, the masked one is the prerequisite.

55. **`calculateAdv20` divides by the window, not by traded days** (B-044). "Average daily traded value" answers "how much can I buy per day", and a day the stock did not trade is a day you could not buy. Dividing by traded days inflated exactly the stocks the buyability guard exists to catch — a 3-of-20-day trader read 6.7× too liquid, enough to escape THIN and clear the F3 promotion filter that depends on it.

56. **Universe retirement needs a cool-off, or it is theatre** (B-036). `retireWeakSymbols()` had no caller at all until 2026-08-26; it now runs at the end of `weeklyFullScreening()` with that run's composites, and an empty score map **skips** the pass rather than counting a weak week against everything (eight failed Saturdays would otherwise retire the whole dynamic universe). The subtle half: `runCoarseScan` skips anything in `knownSymbols()`, so excluding only *active* rows means a symbol retired on Saturday is re-discovered by the next scan on the same filters that promoted it. `retirement-cooloff-months` (default 6) sits between the two failure modes — permanent exclusion shrinks the pool forever, immediate re-entry makes retirement meaningless.

57. **The screener screens two universe lists, not one** (B-053). `resolveUniverse()` merges the `Nifty200WatchlistService` tiers with `MultibaggerScreenerService.SCREENING_UNIVERSE`, a second hardcoded ~90-name list. Anything reading only the tier list sees a partial picture: the expansion funnel "discovered" and promoted PAYTM despite 91 rows of screening history going back five months. Any code asking "is this stock already covered?" must union both, via `getScreeningUniverse()`.

58. **A "DB-only" Javadoc is load-bearing — verify it rather than inheriting it** (B-039). `GET /api/insider/{symbol}` was labelled dashboard-safe in three places while calling `StockValuationService.getValuationData()`, which on a cold cache is an XBRL fetch plus a Kite quote — and this app restarts daily, so every first call of the day paid it. Nothing broke only because no page called it yet. Market cap now comes from the latest `multibagger_scores` row (same unit, crores), which is at most a day stale and null for a never-screened stock — reported as "no size verdict" rather than invented.

59. **A write-only cache is a leak, and this one was filled 1,600 symbols at a time** (B-049). `MarketDataService.getRecentCandles` stored every fetch in an unbounded map labelled "update cache for reference (not used for real-time data)" — nothing ever read it. Stage A of the universe scan therefore retained ~1,600 x ~400 daily candles for the life of the JVM. It is deleted, not bounded. The same entry fixed two adjacent faults: `relativeStrength()` fell back to the stock's *absolute* return when the Nifty series was missing (in a rising market that passes nearly everything, so the promotion filter silently stopped being relative at the exact moment the benchmark failed), and `queue()` sat outside the per-symbol `try` so one unique-constraint collision discarded 20 minutes of completed scan.

60. **A manual endpoint can starve a scheduled job, because the rate limit is shared** (B-049). Kite is paced process-wide at ~2.9 req/s (Gotcha 23), and the 15:05-15:28 jobs abort *silently* at 15:30 (B-014). `POST /api/universe/scan` (measured 22 min) may start only in the Saturday window or before **13:00** on a weekday; `GET /api/universe/ipo-watch` (measured 6 min — not the quick GET its shape suggests) and `POST /process-queue` are refused from **14:00**, when the daily screening starts. Refusals are **409 with the reason in the body**: `server.error.include-message` defaults to `never`, so `ResponseStatusException` would have produced a bare 409 — a guard whose explanation never reaches the caller is the same silent failure it exists to prevent.

61. **Before substring-matching a placeholder, check what the placeholder is not** (B-040). NSE writes `"-"` for an absent field. `normaliseType()` treated any non-blank string as real and picked SELL only for a leading "s", so `"-"` became **BUY** — a `MARKET_SALE` *added* to net insider buying, failing in the one direction a promoter-accumulation signal must never fail in. `num()` had handled `"-"` correctly one method away for months. Sibling of Gotcha 53.

62. **Count people, not filings, and put a floor under the count** (B-041). Three Rs 1,000 buys returned `STRONG_ACCUMULATION` — the system's strongest verdict — because the event-count escape hatch fired even when the value was known and trivially small, and because one director staggering a purchase across three days files three rows. The cluster test now needs three distinct **people** plus Rs 25 lakh aggregate wherever any value was disclosed. SEBI PIT Reg 7(2) has no de-minimis threshold, so tiny filings are routine, not evidence.

63. **A "rising" signal without a level check rewards exactly what it was built to look past** (B-043). Under-Discovery paid its full +20 "institutions are arriving" component to a 35%-owned stock attracting more institutional money. It is now gated on combined FII+DII **< 10%**; above that the component is *measured and scores zero*, which is a finding, not a gap. And the level requires **both** legs published — summing `(fii ?: 0) + (dii ?: 0)` let a stock with 1% DII and no FII figure read as "barely institutionally owned", the strongest claim the lens makes, from a number half of which never existed (Gotcha 21).

64. **The XBRL writer must merge field by field, not row by row** (B-046). `recordFromXbrl` used unconditional setters, so any field NSE did not tag arrived as `null` and erased a real imported figure — **permanently**, because the row is then stamped `source=XBRL` and the import path deliberately refuses to overwrite an XBRL row (Gotcha 49). Two fields were never written at all: `receivables` and `shareCount`, which are exactly the inputs the dilution and receivables forensic checks need — so both reported "not measured" for every screened stock while the section presented itself as a completed screen (Gotcha 44). The share count was not unknowable: paid-up capital / face value is exact and the *quarterly* parser had been computing it for months.

65. **`shareCount` is in crore, and the unit is enforced on write.** The import normalises anything above 100,000 (no listed Indian company has 100,000 crore shares — same auto-detection as the FII/DII lakhs-vs-crores conversion); the XBRL path divides by 1e7. A series mixing an absolute count with a crore count steps by 10 million between adjacent years, which reads as a colossal buyback and *hides* real dilution, so `checkDilution` additionally refuses to measure across a >50x step rather than reporting a corporate event that never happened.

66. **A screener CSV is several stacked tables, and one of them is quarterly** (B-047). The "Data Sheet" layout is annual P&L → **QUARTERS** → Balance Sheet → Cash Flow, each with its own header row. Detecting the header once and reusing its column->year map wrote a single quarter into an annual field — roughly a 4x error that flows into every CAGR, margin, turnaround and forensic ratio. Headers are re-detected while walking; a header naming any month other than March, or repeating a year, is quarterly. Parsing must *resume* at the next annual header, not stop — the balance sheet sits after the quarters.

67. **An unknown delta is not a zero, even when the rest of the formula is computable** (B-048). The capex proxy defaulted its CWIP term to `0.0` when the prior year was missing, which understates capex by exactly the amount under construction — the spending the signal exists to detect — while the ratio still comes out looking measured. Now: both years present, or neither year reports construction (a genuine zero), or the proxy is null. And `capexToDepreciation` needed `annual_fundamentals.net_block` to exist at all; B-034 had only unlocked `EXPANSION_UNDERWAY`.

68. **A gate that passes on absent data is a pass, not evidence — and it never counts toward the CORE quorum** (SPEC §35.2). Three of the seven core gates pass when there is nothing to look at: G4 with no forensic history, G6 with no insider filings, G7 with no conviction record. Counting those as "measured" lets a holding with nothing on file collect three free passes and reach CORE on two real gates. `GateStatus.PASS_NO_DATA` exists so the fail/pass decision and the coverage count can disagree, which is the same discipline as Gotcha 44 ("no forensic flags" is usually "nothing was checked") applied to a gate rather than to a number. Same family: `UNCLASSIFIED` is never `SATELLITE` — one is the absence of a finding, the other is a finding. And the same trap one level down (B-057): G7 reads the investor's *stated* horizon, but every auto-generated conviction record is seeded with 24 months, so reading the column directly failed 14 of 33 holdings on a number nobody chose. `holding_conviction.horizon_stated` separates the two. **A default is not a statement, in the same way a null is not a zero.**

69. **`suppress-technical-exits` ships OFF, and it changes only what emails say — never what is stored.** Being CORE alters the displayed recommendation and which table a row appears in; `holdings.recommendation` is untouched, so ML labels and the raw signal are unaffected. The flag itself is the one behavioural switch, and it defaults `false` because suppressing an exit alert is the *removal* of a risk control — Gotcha 42's asymmetry, in the direction it was written for. The first quarter is an observation: alerts fire and `holding_classification.observed_alerts` records which of them landed on a core holding, so the flip is argued from evidence. Review **2026-11-30**. `CoreOverlayTest` fails if the default moves.

70. **The dedup-free `evaluateHolding` overload finally exists, and suppression is built on it** (Gotcha 19). `evaluateHolding(h, recordDedup=false)` reports every triggered condition and claims nothing; the caller then calls `markSent` for what it actually sends. Suppression mode needs it because the footer must *name* the alerts it withheld, and naming them means evaluating them — filtering the holdings list first yields a tier, not a list. **Observation mode must keep the ordinary path**: those alerts really are sent, so suppressing their dedup would re-send the 10:00 alert at 12:00 and 14:00. "`sentAlertsToday` holds no key for a core holding" is therefore a suppression-mode assertion only; asserting it in both modes pins a bug.

71. **A core holding's *current* drawdown must never lower its durability score** (SPEC §35.3, D5). The first draft counted falls that had recovered to a new high and deducted for one that had not — a rule only a stock near its high can satisfy, which downgrades a compounder at precisely the moment the tier exists to hold it. D5 now scores **only episodes that closed ≥ 18 months ago**; the open one is described in the coverage text and excluded. `DurabilityScoreTest` pins it by deepening the open drawdown and asserting the score does not move.

72. **"Frozen" meant no new filings, not no history — NSE's annual archive is still live** (SPEC §32.5). B-017 migrated financial-result *discovery* to the integrated-filing feed because `corporates-financial-results` stopped receiving new filings after Dec-2024, and the endpoint was then treated as dead. It is not: it returns 200 with **13–14 annual filings per symbol** back to 2011, each with an Ind-AS XBRL link, in the `in-bse-fin` taxonomy this parser was originally written for. That archive is what `POST /api/fundamentals/backfill` reads, and it removes the manual screener.in CSV step for the common case. Before writing off any endpoint as dead, check whether what died was the *feed* or the *archive*.

73. **A multi-year fundamentals series must never mix consolidated and standalone.** NSE's archive does not always carry both: measured on RELIANCE, **FY2022 exists only as a standalone filing** while the years either side are consolidated, and standalone revenue there is roughly half the group figure. Mixing them manufactures a collapse and a recovery that never happened, and every CAGR, margin trend, turnaround verdict and dilution check reads it as real — undetectably. The backfill settles on the majority basis (consolidated wins a tie) and **skips** off-basis years rather than converting them; `annual_fundamentals.consolidated` records the basis per row. A gap is something the downstream checks already handle. Sibling of B-047 (a quarter filed as a year).

74. **Hibernate `ddl-auto=update` does not always add a declared column, and the failure is silent-ish.** The boolean `consolidated` on `annual_fundamentals` was on the entity and never appeared in the table; every write then failed with "column does not exist" — caught, logged at DEBUG — so the backfill reported *zero years written* while looking like it had run. `SchemaMigrationRunner.ENSURE_COLUMNS` now issues an idempotent `ADD COLUMN IF NOT EXISTS` for such columns. Companion to the B-026 rule about update never *relaxing* a constraint: it does not reliably *add* one either. When a new column's feature writes nothing, check `information_schema.columns` before debugging the logic.

75. **`watchlist.symbols` is a one-time seed — the `watchlist` table is the source of truth** (SPEC §37.5). `WatchlistSeedRunner` inserts a YAML symbol that has no row, stamps a legacy row's `addedOn` from `createdAt`, and **never resurrects a row the investor removed** — a seed that did would be the old in-memory add/remove bug in a new coat. Seeded rows have no `priceAtAdd`, so their return since added is *not measured*, never `0.0%`; the UI tags them "config". Editing the YAML after first boot only adds.

76. **The watchlist page must stay DB-only, and quality is never computed on add.** `GET /api/watchlist/items` reads `watchlist`, `watchlist_daily_snapshot`, the latest `multibagger_scores` row and the decay trend — nothing else. "Nifty now" is the latest snapshot's `niftyClose` (labelled *as of {date}*), not a quote. A stock outside the screening universe shows "never screened", not a 50, and the verdict then leans on timing alone with the reason saying so. `refresh?quality=true` is the only path that computes a composite, via `evaluateSingleStock` (Gotcha 50), and stores it as `adhocQualityScore` so it can never be mistaken for a screening row. Add/refresh are refused with a 409 + reason from 14:55 (B-049); `api.js post()` throws that reason verbatim so the user sees why.

76. **The screener's "Still good time to buy?" column shares a vocabulary with the watchlist, not a rule table** (SPEC §12.11). `ScreenerTimingVerdict` reuses `BuyTimingVerdict.Verdict` so one word means one thing on every screen, but its rules are separate because its inputs are: the watchlist is calibrated on **daily** RSI-14 and distance from EMA-50, a screening row carries **weekly** RSI and distance from the 52-week high. Feeding one into the other is a units substitution — the same class as a quarter filed as a year (B-047). Thresholds are set against the measured live distribution (294 stocks: weekly RSI median 54, only 6% above 70), and the verdict is computed on read in `DashboardService.withBuyTiming`, never stored — a persisted copy can disagree with the row it describes after a re-screen.

77. **Forensic severity must be read, not just tested for presence.** Flags persist as `CODE:severity` with three tiers, and the forensic screen scores `INFO` at zero on purpose (Gotcha 44's sibling). The first cut of the buy-timing column treated any non-blank `forensicFlags` as disqualifying and sent a 90-score business to AVOID on a `RECEIVABLES:MEDIUM` note — found on the live run, not in review. HIGH disqualifies, MEDIUM cautions and names the flag, INFO is not a stop. Anything else reading `forensicFlags` should check what it is doing with severity — and read it through **`ForensicSeverity`**, the single shared parser (B-065): the watchlist engine kept its own copy, never received this fix, and sent three holdings to AVOID on a medium receivables note while the screener called them a caution. An unparseable severity grades `UNKNOWN` and cautions; it is never downgraded to INFO.

78. **A derived indicator whose window cannot reach its period returns a constant, not a measurement** (B-060). `monthlyRsi` read exactly 50.0 on all 294 rows because RSI-14 on monthly bars needs 15 bars ≈ 330 trading days while `history-days: 365` yields ~276 candles ≈ 12 bars — unreachable for every stock, so `calculateRSI`'s 50.0 "neutral default" was returned every time. Zero variance, the same signature as the Institutional-Interest bug that ran three months undetected. `calculateRsiOrNull` now returns null instead; `weeklyRsi` (5-day bars, ~55 of them) was never affected. **Before trusting any aggregated indicator, check that the fetch window can actually produce its period** — and prefer the nullable helper anywhere the result is persisted or displayed.

79. **A holding's symbol is not stable within a day** (B-061). The 15:18 broker sync re-prefixes `NSE:INFY` as `BSE:INFY` (22 of 33 holdings are BSE-prefixed), so anything keyed (symbol, date) can hold two rows for one stock on the same date — which is how `core-holdings` returned 49 rows for 33 holdings. `latestClassifications()` now intersects with `findActive()`, Gotcha 16's rule applied to the read side. This matters most for `effectiveTiers()`: a stale row must never be able to grant core protection to a position that is not held under that prefix.

80. **Distance from the 52-week high is half a position, not a position** (B-062). A stock can be 13% below its high and still 53% above its low — it fell and already bounced. The screener's "good entry" rule checked only the high and, on the live run, **50 of its 56 strongest verdicts sat in the top half of the 52-week range**: it was selling round-trips as pullbacks. `ScreenerTimingVerdict.rangePosition` gates it at 70 (the measured median of the universe, not an assumed level). `priceVs52WeekLow` had been on the Input record, unread, the whole time — **an unused input on a scoring record is a smell, not a spare part.**

81. **Two engines must not answer one question in one vocabulary** (B-062). The watchlist verdict uses live **daily** RSI-14 and EMA-50; the screener uses **weekly** RSI and distance from the 52-week high as of the last screening. Both were right and they contradicted each other in identical words on the same stock, which the investor found before any check did. A tracked symbol now shows the watchlist's verdict everywhere, tagged `buyTimingSource=WATCHLIST`. If you add a third surface answering "is it a good time to buy", it defers too — sharing the `Verdict` enum is what makes the disagreement legible, not acceptable.

90. **A `style` attribute in the DOM is not a style that applied — and CSP is why** (B-070). The dashboard's CSP set `style-src 'self'` with no `'unsafe-inline'`, and CSP L2+ applies `style-src` to **style attributes**, not only `<style>` blocks. So every `el(..., { style })` call across `ui.js` and eleven page modules silently did nothing from the day the dashboard shipped. It was not cosmetic: `scoreBar()` encodes the score as `width:NN%` on the fill, so **every bar drew 100% full** — a 45 and a 99 were visually identical — while the colour band (a CSS class) still varied, making it look plausible. Found only because a new page's heading rendered `weight=400` with the correct attribute present; Chrome's `securitypolicyviolation` event names the directive (`style-src-attr`). Two rules: **verify styling with `getComputedStyle`, never by reading back the attribute** (a screenshot alone also missed it for months, because full bars look like bars), and adding `'unsafe-inline'` to *style-src* does not weaken the no-CDN guarantee — external stylesheets are still governed by `'self'`, and `el()` already refuses raw html so markup injection is structurally impossible.

82. **`scripts/check_js_syntax.py` now checks import placement, because balanced braces are not a loading module** (Gotcha 41's third incident). An `import` inside another import block —

    ```js
    import {
    import { x } from './y.js';   // <- SyntaxError, module never loads
      el, section,
    } from './ui.js';
    ```

    is perfectly balanced, so bracket counting passes while the page renders blank and the server returns 200 for the HTML and every `.js` file. This came from an edit script appending after "the last import line" and landing inside one. Two rules follow: **never insert an import by seeking the last `import` occurrence** (seek the end of the last import *block*, or the first blank line after the import section), and after touching `static/js/**` run the checker and confirm the page actually renders — the checker catches placement, bracket balance and missing commas, but a runtime `ReferenceError` (a `const` used across function boundaries, B-059's sibling) still only shows up in a browser.

83. **A score delta means nothing until you subtract the universe's delta** (B-064). The composite is re-scaled by every scoring change and by every broad market move — between 20 and 27 Aug 2026 the universe median fell 74 → 65.5 and *every* stock read `DECAYING` on its own history. `HoldingsDecayService` now classifies on `relativeDelta30d` = raw − paired-median move of all symbols screened on both dates; the shift is null (never zero) below 30 paired symbols and the raw delta is then used with the reason saying so. That verdict feeds the watchlist rule 5, both holdings emails, the dashboard and the core-holdings G5 gate, so any new consumer of a score *change* must use the relative field, and anything that compares two screening runs (IC panels, promotion filters, "improved since" columns) should ask the same question first.

84. **A holding's exchange prefix is not its identity, and three surfaces used to forget that** (B-061 follow-up, 2026-08-28). Screening, decay and quality history are all keyed on the **NSE** symbol; 22 of 33 holdings are **BSE**-prefixed. Anything joining a holding to analytical history must go through `SymbolVariants.candidates()` (exact → NSE → BSE → bare, first hit wins, never a blend) and must **record which symbol answered** — `DecayAlert.resolvedSymbol`, `HoldingBuyTiming.qualityFrom` — so a reading can be traced rather than assumed. Reusing NSE history for a BSE-held position is correct, not approximate: the composite describes the company, not the listing venue. Before this, thesis drift read `NO_DATA` for 21 of 33 holdings that had months of scores on file, and G5 of the core-holdings gate scored them `UNMEASURED` as a result.

85. **One question, one rule table, three surfaces.** "Is it still a good time to buy?" is answered by `BuyTimingVerdict` for the watchlist (SPEC §37.3), for holdings (§6.5, "should I add more?") and, by deferral, on the screener (§12.11). A tracked stock's watchlist verdict wins on every surface. If you add a fourth surface it defers too — and if you need a new input, add it to the shared rule table rather than writing a parallel one, because the failure mode is not a wrong answer but **two different answers in the same six words** (B-062). **The fourth surface already existed and nobody noticed: the portfolio's own "Signal" column** (B-069). `HoldingsAnalysisService.determineRecommendation` is a momentum rule — score, trend, RSI, P&L, and *no sight of fundamentals, forensic flags or financial quality* — so it printed BUY next to AVOID on BEL, and disagreed on **9 of 32 holdings**. It is reconciled at display time by `SignalReconciliation`: a quality problem vetoes a buy (AVOID+buy → HOLD), it may only ever **lower** a signal, and it never yields SELL because "do not add" is not "get out". `holdings.recommendation` stays untouched (Gotcha 69's contract). When auditing for this class of bug, the tell is a column whose distribution is impossible: that signal read **zero SELL across 32 holdings**. **The fix is centralisation, not another patch** — patching surfaces one at a time is what produced this three times, and the B-069 patch itself immediately created a fourth inconsistency (the stock page still rendered `recommendation` raw, so BEL read BUY there while the portfolio said HOLD). `HoldingsViewDecorator` now attaches the canonical answers — `displaySignal`, `signalNote`, `buyTimingVerdict`, the shared entry ladder — as `@Transient` fields on **every holdings read path**, so a screen can only render what it is given and a new screen inherits them for free. `CrossSurfaceConsistencyTest` pins the property rather than any screen's output (idempotent reconciliation, one ladder per input, no buy beside AVOID). **The last duplicate is gone**: the Market page's sector-reversal entry used `EarlyUpsideScanner`'s own rule, and the breakout panel printed a third — both removed with their engines on 2026-09-03 (SPEC §39). Every surviving surface now goes through the shared table, which is why that removal is part of this rule and not a separate story. Note the vocabulary detail: the table understands the watchlist's `AVOID` *and* a holding's `SELL`/`STRONG_SELL`, deliberately, so no caller has to translate — a mapping step is where a SELL quietly becomes a HOLD.

86. **A bonus issue is not dilution, and the share count alone cannot tell you which you are looking at** (B-066). Corporate actions are divided out of `share_count` before any growth rate is taken, because a bonus or split multiplies the count by an **exact simple ratio** (BEL 3.0000x, MAZDOCK 2.0000x) while money raised lands on an arbitrary one. Two traps here. First, the equity discriminator (`isBonusOrSplit` — did new money arrive?) needs four unbroken years and `series()` returns empty if *any* year is null, so its `false` meant both "not a bonus" and "could not check" — the second reading fired `DILUTION:HIGH` on BEL's 1:2 bonus and, after B-065, forced AVOID on every screen. Second, the ratio tolerance is load-bearing: at 0.5% the grid of simple ratios swallows real raises (BANKINDIA's infusion+QIP sit 0.18% from 5/4 and 0.15% from 10/9), so it is **0.05%**. Any new check that substitutes a heuristic for missing evidence should state what its tolerance absorbs. **And severity must follow the strength of the evidence, not the size of the number (B-092):** that tight tolerance means a real bonus or split with ESOPs issued in the same year lands *just* off an exact ratio and survives the adjustment — HDFCBANK's 1:2 split, HDFC Ltd merger and 1:1 bonus all did — so where equity cannot vouch that new money arrived, `DILUTION` is a `MEDIUM` caution and not the `HIGH` that disqualifies a stock and caps it at 54. B-066 applied the "cannot tell is not the same as not a bonus" rule to the branch where the adjustment clears the bar and missed the branch where growth survives it.

87. **Column resize/reorder lives in `table()` alone, and the two traps are sorting and fixed layout** (SPEC §27.9). All eighteen dashboard tables share one renderer, so `static/js/table-layout.js` wires the behaviour once. (a) A click fires after the pointerup that ends a drag — `recentlyDragged()` suppresses it, or every reorder also re-sorts the table. (b) `table-layout: fixed` is entered on the *first drag*, after measuring and pinning every current column width: switching to it redistributes all columns, so doing it at render time would reshuffle columns nobody touched. Under fixed layout the table must be `width: max-content` or the browser silently overrides the dragged width. (c) The `localStorage` key is derived from the sorted column keys, so changing a table's columns invalidates old layouts automatically — never hand-assign a table id, and never key a column by its displayed index. It also carries a **schema token** (`LAYOUT_SCHEMA`): **bump it whenever the cell-sizing CSS changes**, because a saved width is only meaningful under the rules it was measured in. Widths saved before SPEC §27.10 were taken while cells were `nowrap`, and a restored layout re-enters `table-layout: fixed` with explicit `<col width>` — which **overrides the wrapping entirely**, so the table scrolls sideways forever and no CSS fix can reach it (measured on the watchlist: 1950px needed vs 1527 available). Note the trap for diagnosis: this reproduces only for a reader who has a saved layout, so a clean profile — and every measurement taken in one — says the table fits.

88. **8,933 outcomes is about five observations, and that is the whole reason there is no learner** (SPEC §38, §25.5). The MULTIBAGGER accuracy cell reads n=8,933 at 30d — which is ~82 screening dates × ~300 stocks whose returns move together, over overlapping windows, so the effective independent sample is roughly **five periods**; the measured edge is +1.46pp excess at **t≈1.24, p≈0.28**. The horizons that match the mandate are emptier still: **180d and 365d have no matured rows at all** (first picks Apr-2026). Anyone reading the row count as "≥100 per cell, the §25.5 gate is met" will fit one risk-on quarter and call it skill — the exact failure that ended the previous ML chain. What shipped instead is the substrate: `scoring_version` on `multibagger_scores` and `recommendations` (a "63" from June and a "63" from September were different engines filed under one name — B-018, B-019), and the `screening_coverage` vector (a dimension's IC reads ~0 both when the signal is weak and when it was never measured — bug #9 and B-060 were each mistaken for the former for months). Neither changes a score. Two rules for extending them: a **not-applicable** stock leaves the coverage denominator rather than counting against it (a bank without a ROCE is not a gap, Gotcha 68's rule one level up), and the version hash is deliberately **over-sensitive** — splitting two identical runs is recoverable, merging two different engines is not. Third rule, learned on the first live run: **verify that a not-applicable marker actually fires.** The bank marker is `capexVerdict = NA_FINANCIAL` (24 of 295 rows), NOT `capitalEfficiencyVerdict` — that one puts NA_FINANCIAL on unpersisted sub-verdicts and grades a bank `SOLID` on ROE/ROA, so the first draft's not-applicable branch fired 0 times and every bank read as a ROCE gap. Fourth: **a collapse threshold belongs to a scale.** `MIN_HEALTHY_STDDEV = 5.0` is a 0-100-score threshold, and applying it to ratios raised an ERROR on debt-to-equity at sd 0.5 around a mean of 0.3 — a wide spread — so only score-scaled signals get a `collapsed` verdict and ratios record spread with none. That same run found **InsiderPulse at 0% coverage** (measured on no stock at all) and **SectorTailwind at 30% coverage with sd 4.7** — an 8%-weighted dimension that is neither widely measured nor separating what it does measure. Read any dimension IC against its coverage row before concluding anything about the signal.

83. **The entry answer is a ladder, not a price — and the contradiction is designed out, not guarded** (SPEC §12.12). Two goes at this. The first quoted one number and had to be policed: "Wait — running hot" beside an entry equal to today's price tells the reader to wait and to buy now in one row, so `compute` took the verdict and AVOID got no number at all. That guard then failed anyway (B-068) — KRBL read *Wait for a Dip* beside a level **0.0% below** the live price, because the check was `support < currentPrice` and a 10-paise gap passes. The rebuild removes the class of bug instead: **three equal tranches**, and a waiting verdict's ladder *starts below market*, so it cannot quote today's price. Why a ladder is also the right answer on the merits: over a multi-year hold a 5% better entry is worth ~1.7% of a 3x, while a dip that never comes costs the whole position — entry discipline buys *conviction* (not selling the first 20% retrace) and limits volatility drag, neither of which a single number delivers. SPEC §8's accumulation planner already said "price-ladder mode"; the first version reinvented a worse form of a solved problem. Three details carry the weight: the step is **ATR** (so the same ladder means the same thing for a bank and a micro-cap), the deepest rung snaps to the **50-day average only when that is nearer than the ladder reaches** (it is the level the verdict's own words cite — but a level the stock may not revisit for a year is a decision never to buy, not a plan), and every waiting plan carries a **fallback sentence** — the thing an experienced investor says and an amateur never writes down. Levels round **down**, never to nearest, so rounding cannot lift one above the price it came from.

89. **`overflow-wrap: break-word` does not let a table get narrower; only `anywhere` does.** A table can never be narrower than the sum of its columns' *minimum* widths, and under `break-word` a single long word is an unbreakable floor. On the 23-column screener those floors summed to **1794px** — wider than the screen — so it scrolled sideways no matter how the columns were arranged, and no amount of `white-space: normal` fixed it. Switching prose cells to `anywhere` (which *does* lower min-content) took it to 1578. Four things keep an ordinary floor because splitting them mid-character destroys them: a ticker, a pill, a number, a header label (SPEC §27.10). Two lessons beyond the CSS. **Headers, not data, were setting most column widths** — "Still good time to buy?" on one line above a short badge. And **measurement alone was not enough**: `scrollWidth` said the table now fitted, while a screenshot showed "not measured" shredded into `not/mea/sure/d` and the entry price run together with its label. A layout change needs a look, not just a number. (Headless Chrome does both: `--screenshot` for the look, and `--dump-dom` on a throwaway page that iframes the real one and writes `scrollWidth` into the DOM for the numbers.)

91. **The app now learns in shadow, and "shadow" is the entire safety property** (SPEC §38.8–§38.10, 2026-09-05). Six weight vectors are scored on every screening run — the live one plus five **pre-registered** alternatives with written hypotheses — and persisted to `shadow_composites`. None of them steers anything: no report reads that table, no recommendation comes from it, no threshold consults it. Three things are easy to get wrong here. **(a) The variant set is fixed in advance and adding to it is not free.** Every addition raises the significance bar for *every* variant permanently, because `PromotionGate` deflates alpha by `WeightVariantRegistry.count()`. A vector chosen by trying candidates until one wins is a multiple-testing machine whose winner's t-statistic means nothing. **(b) A variant's composite is *reconstructed*, not re-screened**: `storedComposite − liveWeightedBase` recovers everything the engine did after weighting (market-cap adjustment, eleven bonuses, the hard cap), and that constant is re-applied to every variant — correct because no bonus depends on the weights. This is what let the whole history since April 2026 back-fill instead of the evidence clock starting on the ship date. **(c) `reconstruction_exact=false` rows are excluded from every variant alike**, not per-variant: a composite on the 0/100 clamp or at the 54 cap is not `base + constant` for any base, and excluding per-variant would compare rankings over different universes, which is not a comparison.

92. **Read `independentPeriods`, never the row count.** `WalkForwardHarness` groups screening dates into blocks one horizon wide, computes one **cross-sectional rank correlation** per date, averages to one reading per block, and takes the t-statistic **over blocks**. Measured on the first live run: 104 screening dates and 153,552 shadow rows reduce to **3 independent periods at 30 days and 1 at 90 days**. The live vector's own rank IC was 0.028 at t=0.61; the best challenger led by 0.008 at t=0.47 against 9.93 required. Everything was refused, which is the correct answer and the reason the harness exists — the deleted ML chain was deleted because nothing in it could report its own insignificance. Three deliberate choices that must survive future edits: **cross-sectional, never pooled across dates** (pooling lets one strong month dominate, which is how a signal looks predictive for having been measured during a rally); **rank, not Pearson** (a composite is an ordering device and one stock that trebled must not carry the panel — the existing per-dimension panel keeps Pearson and is untouched); and **the embargo defaults on** (an overlapping sample holds no more information, it merely reports a smaller standard error for the same information).

93. **`PromotionGate.AUTOMATIC_ADOPTION_ENABLED` is permanently false, and `passedExceptStability` is why the gate is strict rather than unpassable.** Five conditions: ≥12 independent periods, paired t clearing `0.05 / variantsTried`, a positive paired effect, the same challenger leading **two consecutive** reviews, and coverage ≥90% of live's cross-section (a variant scoring only the well-documented third is answering an easier question, not winning). The stability condition is unenforceable without a memory — recomputing "what did the last review say" from today's data produces today's answer twice and calls it agreement — so `weight_reviews` records every verdict, refusals included. And it reads `passedExceptStability`, not `eligible`: the first qualifying review necessarily fails stability for want of a predecessor, so scoring it as an outright failure would reset the run forever and no variant could ever accumulate two passes. Condition 1 is **lenient** at twelve (detecting a 0.03 improvement against a 0.05 between-block spread needs about seventeen), which means at 30 days it is roughly two years of history and at 180 days several. That is not pessimism; it is how long a multi-year holding decision takes to validate, and the alternative to waiting is a false answer rather than a faster one.

94. **A signal at 0% coverage is the third instance of one bug, and only an instrument found it** (B-074). Insider Pulse produced a verdict for **no stock at all** because the daily capture filled its 80-symbol budget from holdings plus `findTopCandidates` — a verdict-filtered query — leaving ~286 of 295 screened stocks never probed. Nothing threw, nothing logged an error, and every component behaved exactly as specified; the per-stock failure sat at `log.debug` and `InsiderPulse.toScore()` correctly mapped null to null. Its IC would have read "no signal" indefinitely. This is Institutional Interest (constant 40, three months) and monthly RSI (constant 50.0, whole universe) for a third time. The fix rotates a slice of the **whole** universe daily, derived from the day of the year rather than a stored cursor, and **strides** through the score-ordered list rather than taking a contiguous run — a contiguous slice would probe strong stocks one day and weak ones the next, making a verdict's coverage correlate with the day it was taken. **Before concluding anything from a dimension's IC, read its coverage row.** **And then check the feed underneath it (B-089):** the rotation shipped, coverage still read 0%, and the reason was that NSE had stopped publishing to that endpoint four months earlier. Two further faults hid behind the first — the rotation was handed `BUDGET - alreadyChosen` slots, i.e. **zero** whenever holdings and candidates filled the cap, so it contributed nothing while logging "full cycle ~1 runs" (B-090). Both are gone: the all-market index means there is no budget to ration and every tracked symbol is checked every run. The general rule is that a zero-coverage signal has *two* candidate causes - we never measured it, or nobody published it - and the known-cause list will confidently offer you the first one.

99. **The compounding lens is a lens, and three refusals are what make it honest** (SPEC §41, 2026-09-06). Five gates over return on capital, cash conversion, leverage, earnings steadiness and margin trend answer "can this business compound?" — the question the composite is worst at, since 59% of that weight is price behaviour. It **never enters the composite** (Gotcha 30), and it is computed **on read** like the §12.11 timing verdict, never stored. Three things to preserve if you touch it. **(a) It does not gate on reinvestment**, though that is half of *ROCE × retention*: payout is computed by `analyzeCapitalEfficiency` and thrown away rather than persisted, and gating on `capexVerdict` instead was tried and rejected — only 83 of 288 stocks read `INVESTING`, and many reading `STEADY` are the asset-light businesses with the **highest** ROCE (CAMS 48%, CDSL, Oracle Financial). A gate that fails a business for not needing factories inverts the thing being measured. **(b) It claims no persistence**: every gate reads the latest single year, and multi-year history exists for ~19 of 288 stocks, so `yearsOfAccounts` is carried through so every surface can say "one year, not yet a track record". **(c) NOT_APPLICABLE is not a pass** — leverage, gross margin **and cash conversion** do not apply to a lender, and all three leave the denominator (Gotcha 68). That leaves a financial exactly **two** applicable gates, which is why its floor is 2 and not 3: requiring 3 left every one of the 24 financials in the universe permanently `NOT_MEASURED`, HDFCBANK included, which passed both checks it had (B-092). A lender's badge therefore rests on thinner evidence than an ordinary business's, and the verdict text says so rather than hiding it. **(d) The gates read `annual_fundamentals` when the screening row is silent** — same formulas, gaps only, and **never** as the thing that decides which symbol spelling answers: selecting on the post-fallback result let an empty BSE row acquire figures and out-rank the full NSE one, re-creating B-088 on NTPC, NATIONALUM and ABCAPITAL. Thresholds were set against the measured cross-section (ROCE median 16.9, p75 24.1 → bar 18), which is why it names 22 of 288 rather than half the universe.

96. **`SIGNAL_GATED` accumulation is refused on create, and the constant survives only so legacy rows read back** (B-077, SPEC §8.2). It used to be accepted and persisted while `AccumulationReminderService` evaluated only SIP dates and price-ladder rungs, so the plan never reminded — an investor holding one waited on a tranche that could not fire, and nothing said so. **The fix was to refuse the mode, not to wire it a trigger**: a tranche fired by a signal is a buy signal wearing a plan's clothes, which SPEC §19 and §20 rule 10 bar. Three details worth keeping: the refusal returns **422 with the reason in the body** (a thrown status alone arrives bare — `server.error.include-message` is `never`, the B-049 lesson); the reminder service **WARNs** on a signal-gated row rather than skipping it, because a row inserted directly would otherwise reproduce the original silent skip; and the `MODE_SIGNAL_GATED` constant is **kept**, since deleting it would break reading a plan written before the guard. Same family as Gotcha 85 — one question, one rule table — a level up: the *plan* may schedule a purchase, the *signal* may not. `AccumulationModeTest` fails if the mode is accepted again.

98. **Removing a scoring dimension is a full-stack change, and the front end is the half that gets forgotten** (B-079). Sector Tailwind left the composite on 2026-09-03; the weights, the entity and the docs moved, and the dashboard did not. `DIMENSIONS` in `page-screener.js` and `page-stock.js` still listed `sectorTailwindScore`, so for three days every stock drew an **eight**-spoke radar with one permanently empty axis, captioned *"7 of 8 dimensions measured"* — including stocks where all seven were measured, because the caption's `8` was a literal and `measured < 8` was therefore always true. The screener also rendered a dead `Sec` column striped "not measured" on all 288 rows. **This inverts the rule the UI exists to enforce** (Gotcha 21, SPEC §21 rule 7): a null must read as *could not measure*, and here a *deleted* dimension was shown as an unmeasured one — telling the investor the analysis was incomplete when it was complete. Two rules: **a count rendered beside a list must be derived from that list** (`DIMENSIONS.length`, never a literal), and when a dimension goes, grep the front end for its key *and* for the spelled-out count ("eight dimensions" appeared in four blurbs, one of which named *sector* among them). Note also `WEIGHTED` in `page-accuracy.js` — leaving a retired signal in it flags its historical coverage gap as serious when it now carries no weight.

97. **One candle cache, because the rate limit is one budget** (Gotcha 52, applied before it could repeat). `DailyCandleCache` was extracted from `RecommendationAccuracyService`, which now delegates; both the per-dimension IC panel and the walk-forward harness go through it. Two caches over the same symbols would double the cost of identical information against the single ~2.9 req/s gate whose exhaustion has twice starved the afternoon jobs. **The fetch window is part of the key**: an entry warmed over 400 days is re-fetched when a caller needs 700, rather than answering "no price" for every date beyond it — which would shrink the sample silently and at its *oldest* end, exactly the periods a block split depends on. Note the guard shape on the review endpoint: it refuses only **14:00–15:30**, bounded at both ends, unlike the open-ended "from 14:00" guards elsewhere. Those were written when the app only ran inside market hours, so "after 14:00" and "during contention" were the same thing. They are not the same thing in an out-of-hours session, and the reason for the guard is contention, so the guard covers exactly the contention.


100. **A fundamentals lens is back-testable only if the row records when the filing became public — and that column has to exist before the fetch, not after** (SPEC §32.6, 2026-09-06). Two rules in one. **(a)** Gotcha 91(b) says a shadow weight variant back-fills because its composite is *reconstructed* from columns already on historical rows, while a **new dimension** cannot be and so starts its evidence clock on ship day — against §38.10's twelve independent periods, that is years. A fundamentals lens escapes this: for any past screening date you can compute what it would have said from filings public by then, inheriting history to 2016. But only if `available_from` is on the row. `fiscalYear` is not that date — a March-2024 year end is not public in March 2024, SEBI LODR Reg 33(3) allows 60 days, and using the year end leaks up to five months of look-ahead **in the flattering direction**, because the lens "knew" the result before the price moved. An estimate is stored when the archive carries no broadcast date, at year-end **+5 months** (deliberately later than the regulation requires: erring early is the bias, erring late only shrinks the sample) with `available_from_estimated` alongside it so an assumption can never be read as a fact. An unparseable date is null and falls back to the estimate — never to today, which would file a decade-old document as public now. **(b)** The reason all five §32.2 columns landed *before* the ~4,000-request backfill ran is that every year it writes is stamped `source=XBRL` and the import path then refuses to touch that row (Gotcha 49), so a column added afterwards can only be filled by re-fetching every filing — and the XBRL cache is 7 days *and process-local* against an app that restarts daily, so there is no cache to lean on. **When an expensive one-time pass is about to write a table, lock the schema for everything that will read it.**

101. **NSE was never paced at all, and one javadoc had been claiming otherwise for months** (SPEC §32.6). The only `Thread.sleep` in `src/main/java` was in `DailyCandleCache`, for Kite. Both the per-year archive loop and the per-symbol holdings loop were tight, while `/backfill-holdings` documented itself as "paced" — ~360 unthrottled requests per run at the host that has already **permanently** walled `/api/quote-equity` (B-018). `NseDataService.pace()` is now a **static** process-wide gate for the bulk archive paths, for the same reason the Kite gate is (Gotcha 23): two independently-paced loops on two of the four scheduler threads each respect their own budget and together break the real one. Three details. A **cached** filing skips the delay, or re-running a symbol already fetched this session costs full wall clock for zero requests. Single interactive calls are **not** gated — they are not the volume problem and delaying them slows a page someone is waiting on. And the guard is bounded at **both** ends: `requireOutsideNseCrunch` only ever checked the *start* time, so a run begun at 09:35 went straight through the 09:45 FII/DII fetch and the 10:00 report — the exact interference it existed to prevent. Loops now check the deadline **between symbols** and return what they have. Gotcha 97's rule, restated: the reason for a guard is contention, so the guard must cover the contention, not the moment of asking.

102. **A backfill queue converges only if "complete" means "everything the archive holds", not "enough years"** (SPEC §32.6). Without per-symbol status nothing can tell **"never attempted"** from **"attempted, and this company has only ever filed three annual results"** — both look like a symbol with three years. A picker ordering by fewest-years-held then returns to the shallowest names every day and never reaches the untouched ones, which is the natural failure of the obvious implementation. So completion is `yearsWritten + yearsSkippedBasis >= yearsInArchive`. Two sub-rules carry weight: **years skipped to hold one reporting basis count as seen** (they were deliberately left out per Gotcha 73 and re-fetching would leave them out again), and **`UNAVAILABLE` is a finding, not a failure** — the archive genuinely lists nothing (delisted predecessor, non-March year end, insurer behind the wall), and the CSV import is the fallback for exactly those names. A listing that *errored* is `FAILED`, never `UNAVAILABLE`: recording a fetch failure as a fact about the business is the B-054 lesson. **Also: deepening history moves composites, and that is correct.** `forensic-actionable` is `true` and a HIGH flag caps at 54, so the rollout adds true flags *and* removes false `DILUTION:HIGH` ones as the B-066 discriminator finally gets its four years. Do not disarm it meanwhile (Gotcha 42's asymmetry, wrong direction), and do **not** bump `scoring_version` — the engine did not change, only its input depth, which changes every run anyway; a bump would falsely split a shadow-reconstruction sample that already pools two versions.

103. **Count years, never average them** (SPEC §43.2). A commodity business's return on capital oscillates roughly 5% → 35% → 5%; an **average** clears an 18% bar that no single bad year clears, so averaging lets one boom year carry a decade — the exact failure a persistence test exists to catch, and the first thing a well-meaning simplification reintroduces. Every §43 gate is a count of qualifying years over measured years, at a 70% required share: not 100% (a business that never once dipped has not met a recession, and requiring that selects for short histories over durable ones) and not a bare majority (a cyclical spends more than half a cycle in its good half). That 0.7 is the one threshold in §42/§43 set on **judgement rather than a measured percentile**, because the cross-section does not exist until the backfill converges — §43.4 flags it for recalibration, the §12.11 discipline deferred rather than skipped. **And a Tier B gate needs ≥4 measured years or it says nothing** (`MIN_TIER_B_YEARS`) — found by running it, not in review: BEL has 8 years of accounts but a balance sheet in only the last two or three, so return on capital passed 3/3 and helped earn a `PROVEN_COMPOUNDER` badge. A gate that passes on thin evidence while sounding authoritative is Gotcha 68 in its most flattering form. Related: `getScreeningUniverse()` returns only the **~89-name legacy list**, not the ~370 union, despite Gotcha 57 describing it as the union — use **`resolvedScreeningUniverse()`** to size a universe-wide job. (`UniverseExpansionService.knownSymbols()` is fine: it separately unions the full tier list, so B-053 is genuinely fixed there.)

104. **A CSS variable that does not exist fails exactly like Gotcha 90's blocked style attribute — silently, with the attribute present.** The §43 divergence callout was written with `var(--accent)` and `var(--surface-2)`; `css/app.css` defines neither, so the border and background were simply dropped and the callout rendered as plain body text. The DOM contained the full `style` string, so reading the attribute back would have confirmed it "worked". It was caught by looking at a screenshot, then confirmed with `getComputedStyle` (`borderLeftWidth: 3px`, `backgroundColor: rgb(255,243,224)`) — which is Gotcha 90's rule exactly, one layer up from CSP. **Check a variable name against `app.css` before using it**; the palette is `--ink/--ink-muted/--ink-faint`, `--surface/--surface-alt`, `--rule`, `--navy/--navy-light/--navy-tint`, `--profit/--loss/--warn/--info` each with `-bg` and `-deep`, plus `--radius`, `--gap`, `--shadow`. There is no `--accent`. **To screenshot one section of a long page**, drop a throwaway `_crop.html` into `static/`, iframe the real page **same-origin** (a `file://` parent is blocked), and offset the iframe by the target heading's `offsetTop` — then delete it.

105. **Two panels answering different questions must explain their disagreement, not hide it.** §41 ("can it compound", latest year) sits directly above §43 ("has it compounded", the record), and on BEL the first reads **No** while the second reads **Held up** — an eight-year compounder having a weak year. Both are right and the *gap* is the finding: that is what thesis drift looks like before the price reacts. But SPEC §21's reader is not an expert and will read adjacent contradictory badges as the app arguing with itself, so a callout names the divergence in both directions. Note how this differs from Gotcha 85: there, one question answered by two engines in one vocabulary was the **bug**, fixed by centralising. Here the two questions are genuinely different, the vocabularies are deliberately distinct (`Compounder/Partly/No` vs `Held up/Mixed/Did not hold`), and the fix is to **explain** rather than reconcile. Merging them would destroy the distinction the second panel exists to draw.

106. **The screen that checks the data must report its passes, its denominators and its blind spots — or it becomes the thing that hides the next bug** (SPEC §44, 2026-09-06). Three real defects here were a value that was *never measured* being drawn as though it were: Institutional Interest constant at 40 for three months, monthly RSI constant at 50.0 across the whole universe (B-060), Insider Pulse with a verdict for **no stock at all** (B-074). Every one ran for months; every one looked fine on screen, because nothing threw and every component behaved as specified. They are found by two questions — *measured on how many stocks*, and *did it vary* — which `/api/dashboard/data-health` now asks mechanically. Four properties carry the weight and are pinned by `DataHealthTest`. **(a) One session behind is never a PROBLEM**: there is no market-holiday calendar in this JVM, so a closed exchange and a missed job are literally the same observation; two sessions is a problem because the market is rarely shut twice running. **(b) A known cause expires with its fix** — `KNOWN_ZERO` stores the bug id *and* the fix date, and a screening run later than the fix that still reads zero escalates rather than being excused, or the exception list becomes where the next regression hides. **(c) Every check reports, passes included**, because an empty findings list is indistinguishable from a list of checks that never ran (Gotcha 44, one level up), and every verdict carries the figure it was reached on so the reader can disagree with it. **(d) The limits are printed beside the all-clear** — it cannot tell you a stored figure matches the filing, and it never asks whether a scoring rule is *right*, only whether its output was measured and varies. Note the calendar case is not hypothetical: reading the freshness strip by hand during the audit that produced this screen, I concluded Saturday's screening had been missed. It had not — the day was a Sunday. That is precisely why the schedule is now encoded rather than held in a reader's head. And `nav.js` gives `health.html` an **empty** freshness subset on purpose: a shorter answer to the same question directly above the full one is Gotcha 85's failure.

107. **"First hit wins" must be "first hit that answers wins" wherever the hit can be empty** (B-088, 2026-09-07). `SymbolVariants.candidates()` resolves a holding's analytical history across exchange prefixes — exact → NSE → BSE → bare, first hit wins, never a blend (Gotcha 84). That is right for *finding* a row and wrong for *picking* one, because some BSE-keyed `multibagger_scores` rows exist carrying **no balance-sheet figures at all** (written by paths that screen a holding rather than the universe). Adding the compounding lens to the portfolio surfaced it immediately: 16 of 32 holdings read `NOT_MEASURED`, including RELIANCE, NTPC and NATIONALUM — while the screener, on the *same day's run*, showed `PARTIAL`, `PARTIAL` and `COMPOUNDER`. The empty exact-match row had won the race against the full NSE row. `CompoundingLensService` now takes the first spelling with **figures to judge**; exact-first still decides between two rows that can both answer, so Gotcha 84's order is intact and a capable BSE row is still preferred (pinned by `CompoundingLensResolutionTest`). Note why this is not cherry-picking: `NOT_MEASURED` is the *absence* of a verdict, not a bad one, so there is no flattering answer being selected for — and when no spelling can answer, the honest `NOT_MEASURED` is still reported with a traceable symbol, because "screened but unjudgeable" and "never screened" must stay distinct. The same fix applies one level down to the **depth** lookup: years of accounts were read under the screening row's own symbol, so KAJARIACER claimed 1 year when `annual_fundamentals` holds 7 under its NSE symbol. **Anywhere else this helper picks a row rather than merely finding one is carrying the same latent bug.**

109. **A headline gain on cost cannot tell you whether the money is growing** (SPEC §46, 2026-09-09). The portfolio's "+17.3%" read the same on 19 February and 9 September while invested capital fell a tenth: unrealised P&L over cost does not move when money comes in or goes out, so seven flat months were invisible. The time-weighted return in `PerformanceMath` removes the day's flow before chain-linking. Two details carry it: the flow is the **cost-basis change**, and on a sale day that omits the realised gain, so the lot-matched gain from `tax_lot_sale` is added where one is on record and the day is **counted as uncorrected** where it is not — the response says how many, rather than quietly reading low. And **drawdown is on the chain-linked index**, never on raw value: a withdrawal is not a fall. Annualising under 90 days is refused (a rate from three weeks is noise with a percent sign). No XIRR until lots cover every holding — a money-weighted return on a third of the flows is a different and wrong number.

110. **Three vocabularies for one sector, matched exactly, made every allocation target read 0%** (B-096). Targets `BANKING`, holdings `Banks`/`GENERAL`, screener `Banking` — case-sensitive exact matching on NSE long names that no holding carried. The fix is an alias table folded to upper case **plus** a rule that a placeholder (`GENERAL`, `Other`) is *unclassified, reported by weight and name*, never a bucket: an "Other" slice that is 42% of the book tells the reader the opposite of the truth. `SectorMapping.resolve` is the one entry point and the decorator attaches the resolved `sector` to every holdings row, so the donut, the drift table, the rebalance note and the sector-returns chart cannot group differently. When adding a sector source, add its spellings to `ALIASES`, not a second normaliser.

111. **A generated thesis is not a thesis, and it needs a column to say so** (B-097). 45 of 46 conviction rows were seeded by the app and every one counted as "intact" — the app agreeing with itself, presented as the investor's record. B-057 had fixed exactly this for the horizon (`horizon_stated`) and left the text beside it; `thesis_stated` closes it. Rules: the investor write path sets it true and the seeder never downgrades it; a legacy null is derived from the seeder's own fingerprint; the report is over **active** holdings joined across exchange prefixes (a `BSE:` holding was listed as "missing a thesis" while its thesis sat under `NSE:`); and counts are over stated theses only. Same family as Gotcha 68: a default is not a statement.

112. **Benchmark closes and cash ride the 15:00 snapshot; they are not a scheduler** (SPEC §46.2, §3.4). `PortfolioSnapshotService.captureDailyExtras()` is called from `recordDailySnapshot()`, so two Kite quotes and one margins call land beside the holdings rows they describe with no new cron row. A 15:00 price is stored as `SNAPSHOT` and a true close from the backfill as `BACKFILL`; the close wins once known and a snapshot never overwrites it. Cash records **nothing** when the margins payload lacks the equity segment: zero cash is a fact about the account, absence is a fact about the feed.

108. **An IPO is the one stock the app cannot judge, and the design is to say so rather than fake it** (SPEC §45, 2026-09-09). Before listing there are no filings — no XBRL, no shareholding pattern, no cash-flow history — so every pillar is silent, and any "apply / skip" verdict would rest on the prospectus PDF (not parsed) or the grey-market premium (the price of the listing-day lottery, deliberately absent). What the exchange feed does carry is the **structure** of the offer, and `IpoStructureRead` reads exactly that: fresh issue versus offer for sale, and who filled the book. Five things to preserve. **(a) A mid-issue subscription never decides anything.** Institutions bid on the last afternoon — LCCPROJECT read QIB 0.55× at 11:56 on day one, which is normal — so the read waits for `subscriptionFinal`, set only when the capture runs on a later day than the last bidding day. **(b) One half of a ratio is not a ratio.** The fresh/OFS split is parsed from prose; when the sentence names a leg whose amount cannot be read, the split is null, because a defaulted zero would read as "promoters cashing out entirely" — the strongest claim the read can make. The parser's own test caught the fresh-issue span running into the OFS figure before ship; the spans are tempered with a lookahead for the other leg's name. **(c) The vocabulary contains no instruction to transact** (`FAVOURABLE / MIXED / UNFAVOURABLE / NOT_MEASURED`, pinned) — §19 now bars "apply" explicitly, and the page's stance box says the only honest reason to apply is that you would buy it at the top of the band on listing day and hold five years. **(d) The six-month rule is a stage, not a filter.** `HYPE_WINDOW` is reported for a young listing however good its chart looks, because the sellers arrive on SEBI's lock-in timetable (anchors 30/90 days, pre-IPO holders and excess promoter stake 6 months, promoter minimum 18) and the page shows those dates; `NOT_MEASURED` is never collapsed into `WASHOUT`. **(e) NSE's past-issues feed is not closed out for every issue** — NSDL, listed 2025-08-06, still reads `"-"` for both listing date and issue price — so the listing date falls back to `EQUITY_L.csv`, accepted only when it falls after the issue closed (a re-used ticker would otherwise inherit a decade-old date). Measured on the first capture: 250 mainboard rows, **768 SME rows dropped by design**, 41 detail pages, 92 prices; and 53 of 96 listings read `NOT_MEASURED` until the stage was allowed to use the two prices the capture already held rather than waiting for an analysis. **Sizing is at the top of the band**: RENTOMOJO's 37-share lot fits 13 lots at ₹404 and 14 at ₹384, and 14 lots at ₹404 exceeds the ₹2 lakh retail ceiling — the application that is rejected outright.

113. **The screener was a momentum screener wearing a compounder's badge, and the fix was a rendering decision, not a data one** (B-098, 2026-09-09). Each row already arrived as the whole `MultibaggerScoreEntity` — ROCE, D/E, cash conversion, DCF verdict, forensic flags, pledge, market cap — and the page drew seven score bars, four of them the price dimensions B-023 calls collinear with near-zero IC. Growth and promoter holding were computed every run and **discarded** (DTO fields with no column). Sector came from an 89-entry hand map defaulting to the literal `"Other"`, which the Industry column then presented as a classification for ~two-thirds of the universe. Rules that came out of it: **(a)** a placeholder is never a bucket — `sectorFor()` returns null, `universe-sectors.csv` (NSE's index lists, seeded offline, no scheduler) fills what the hand map does not, and the hand map stays the override because "Banking" is finer than NSE's "Financial Services"; **(b)** a column budget is paid for, not exceeded — 23 columns became 20 by folding Rank/Grade into Score and Size into Market cap and putting the seven bars behind a chip, and §27.10 must be re-measured whenever the default set changes; **(c)** a business figure is shown raw beside the compounding gate, never blended into a second score (Gotcha 85); **(d)** `ScreenerSurfaceContractTest` pins every field name `fundamentals-cells.js` dereferences, because a rename would draw "not measured" for ever on a stock the app measured (the `CompoundingSurfaceContractTest` failure shape); **(e)** "Hide red flags" keeps the *unchecked* visible — a stock nobody examined is not a stock that passed (Gotcha 44); and **(f)** a lender's ROCE / D/E read "n/a", not "not measured", so a bank is not mistaken for a company whose accounts could not be read.

114. **A fix that lands on one screen has to be walked to every screen that shares the data** (B-099, SPEC §47, 2026-09-09). B-098 fixed two defects on the screener; the discovery page shared the same row and got neither, so for a day it rendered `industry` — the hand map's literal `"Other"`, measured on **189 of 284 rows** while `sector` classified 284 — and drew ten of the ~85 fields `DashboardService.withBuyTiming` sends, a map whose javadoc says it is built "for the screener **and discovery**". The lesson is not "remember the other page": it is that **a shared payload needs a shared renderer**, so `fundamentals-cells.js` and `compounding.js` are now used verbatim on both, **with the screener's column labels character for character** — a column reading "ROCE" here and "Return on capital" there invites the reader to ask whether they are the same measurement (Gotcha 85 applied to a heading), and two-word headers are also what keeps a column narrow, since "Return on capital" wrapped to three lines and set that column's shape (Gotcha 89). Same pass, same page: bulk and block deals are stored with `personCategory="INSTITUTION"` / `mode="BULK_DEAL"`, so the insider filter correctly excluded them and the footer then **described them as "share grants, pledges, gifts or promoter-to-promoter transfers"** — a false statement about the one feed here that has never gone quiet (contrast B-089). Count a third bucket rather than letting an exclusion inherit the wrong caption.

117. **Filtering is chips first, text second — and every chip must carry its count** (SPEC 27.13, 2026-09-10). The screener grew a good filter bar and nothing else could use it; `filters.js` now holds the *mechanism* (`chipFilters()` — state, chips, counts, Clear) and every page declares its own **groups**, because "narrow this list" means different things on a screening run, a portfolio and a set of new listings. Bars now on screener, discovery, portfolio, watchlist and IPOs. Three rules. **(a) Counts on every chip** (`Compounders only (35)`, `Core (0)`): without them a chip is a promise the data may not keep — "Core" on a portfolio that is all satellite today looks like a filter that broke, and the reader cannot tell an empty result from a bug. Found by building it: the holdings tier chip matched nothing and it took a query to prove that was *correct*. **(b) A chip never folds "not measured" into a verdict** — "Compounders only" excludes NOT_MEASURED, "Hide red flags" keeps the unchecked visible, and the tier group gives UNCLASSIFIED its own chip rather than letting it fall in with SATELLITE (Gotcha 21, 44, 68). **(c) A narrowed list says so**, and only while narrowed: a count on every page load is noise, and noise trains the reader past the line that matters. One subtlety in the mechanism: `anyActive` tests `Boolean(test)` rather than `test !== null`, so a **display-only** toggle (the screener's "show the seven scores", which changes columns rather than rows) can live in the bar without making the page claim it is filtered.

    The secondary control is a plain text box in `table()`, on any table of 15+ rows that no chip bar governs — a governed table passes `filter: false`, because its search belongs in the bar with the chips (two boxes on one screen is Gotcha 85 again). Its haystack is the row's **rendered text** plus **`row[col.key]`**, and both halves are load-bearing: rendered text is what the reader types back ("Banking"), the raw field is the word they actually know (`compounding` renders as "Yes" and they search "compounder"). The trap, found by running it: the first cut used `valueOf(col,row)` — but `col.value` is the **sort accessor** and for the interesting columns it is a rank, so `compoundingRank` had the filter searching the number `0` for the word "compounder"; rendered-text searches worked and vocabulary searches silently returned nothing, the most misleading possible half-failure. Nested records must be stringified or they contribute `"[object Object]"` to every row. The threshold was 8 until the chip bars landed, which put five search boxes on the discovery page.

121. **"Not measured" and "nothing applies" must never render alike, and macro exposure is where that distinction earns its keep** (SPEC §48, 2026-09-12). `NOT_MEASURED` means the exposure map has no rule for this business — the app's own gap. `NOT_EXPOSED` means it checked the business against every event on record and none applies — a finding, and the ordinary answer on most days. Collapsing them lets a blind spot render as an all-clear, which is the most dangerous thing this feature could do; it is Gotcha 44 ("no forensic flags" is usually "nothing was checked") with an explicit second value so the two cannot be confused. The distinction is carried through `MacroExposureRead.Verdict`, the service, the decorator, the dashboard cell, the `MacroExposure` coverage row, both emails and the guide, and `macro-cells.js` draws them as two visibly different things: a striped marker and a neutral badge with a tooltip that says what was checked. The same rule one level up decides the coverage row: `MacroExposure` is **never** `NOT_APPLICABLE`, because every listed business is exposed to something, so a missing rule counts *against* coverage — otherwise the map could stay a fifth written and report full coverage for ever (B-074 inverted).

122. **The model extracts, the map decides — and the boundary is what makes the feature auditable** (SPEC §48.4). The reader (a language model, or the keyword rules) records only *what moved and which way*, and is forbidden by its system prompt from naming a company, a stock, a price or an action. Which businesses that reaches is decided afterwards by `macro-exposure.csv`, which is on screen and can be argued with. This is the same split `ConcallAnalysisService` uses, and being strict about it is not fastidiousness: if the model could name stocks, the rules the investor reads would no longer be the rules being applied, and the feature would be the news-sentiment engine SPEC §39.3 deleted twice. Related: **the map never infers a second-order factor** — a war does not imply crude, a rate rise does not imply the rupee. Chaining inferences is how one wire story becomes four events and a portfolio appears to be under assault.

123. **A keyword reader cannot tell a columnist from a reporter, and the fix belongs in the guard rather than in the rules** (B-107). The first live ingest filed *"Why a rate hike could actually be bullish"* as an actual RBI rate rise, and five holdings read a headwind off it. The existing `HYPOTHETICAL` pattern covered the **verb** forms (*may hike*, *could cut*, *expected to*) and had no reach into the **noun** form, where the hedge word follows the event: *"a rate hike could…"*. Two narrow guards followed, the second being `OPINION_LEDE` — a headline that *opens* by asking a question, anchored at the start so an ordinary report containing "why" later is untouched. **The asymmetry is the justification, and it is the opposite of Gotcha 42's**: being over-strict costs one missed event, which the next ingest picks up from a straight report of it; being under-strict files a reading against hundreds of stocks on something that never happened, and **a filed reading is never retracted** (SPEC §48.7). Confidence from the keyword reader is always null and renders as "not measured", never as a low number — a reader that cannot score its own certainty is a different thing from one that scored itself badly.

124. **`MACRO_EVENT` rows are measurements, not picks, and one index quote per ingest is the difference between a feature and an outage.** Every directional reading is filed with `RecommendationTracker` so it is scored at 30/90/180/365 days — the exact thing whose absence killed both predecessor news features (SPEC §39.3 rule 1). Three consequences worth keeping. Accuracy is **verdict-aware**: a HEADWIND followed by a fall is a *correct* reading, so `RecommendationAccuracyService` branches on `source == MACRO_EVENT` rather than assuming higher-is-better. Every surface showing "the app's picks" **filters this source out** (`/api/accuracy/by-symbol` needs `includeMacro=true`), because a reading that a business faces a costlier input is not a suggestion to own it. And the tracker's ordinary path fetches the Nifty level **per row**, which is fine for an engine recording a handful and ruinous for one recording three hundred against a broker paced process-wide at ~2.9 req/s — a budget whose exhaustion has twice starved the afternoon schedulers (B-014, B-049); hence the overload taking one pre-fetched level for the whole pass.

125. **A freshness key written only by a button must never be marked stale.** Amber on the `nav.js` strip means "a job that should have run has not", and that claim is simply false for a table nothing schedules: `macroEvents` is as fresh as the last time somebody chose to read the news, and a fortnight-old stamp on it is a fact about the reader's habits rather than a fault. `ON_DEMAND` in `nav.js` and `DataHealth.OnDemandSpec` both exist for this, and the latter can return WATCH but **never PROBLEM**. The reason is the same one behind Gotcha 106(b): a channel that cries wolf on a key where the colour means nothing is a channel the eye stops reading on the keys where it means something.

120. **An empty column is a claim — work not done, or a question this surface cannot answer — and the two get opposite fixes** (SPEC 27.16, 2026-09-10). Discovery's Recent Listings had three blank business columns and the reflex is B-098 ("the data is on the wire, draw it"). It was not. Measured: **Fin. quality filled on 1 of 238** because the field is genuinely null until `POST /api/ipo/analyse` fetches a listing's first filings — *work not done*; **buy-timing and entry filled on 3 of 238** because they read off the screening row and a recent listing is almost never in the screening universe with §30 expansion in observation mode — *structurally unanswerable here*. **Work not done gets the control that does it**: `analyseButton` moved to `ipo-analyse.js` so both screens share one implementation (the `watch-button.js` pattern), and the section prints its own coverage rather than leaving stripes to imply it (Gotcha 44). Verified on NSE:EMCURE — every business field null → `HIGH_QUALITY`, composite 95, `STRONG_GROWTH`, promoter 77.83%, pledge 0.04%. **Structurally unanswerable gets the column deleted**: "not measured" on 235 of 238 rows is not reporting a gap, it is spending column budget (SPEC 27.10) to say nothing 99% of the time and making a working table look broken. The two timing columns stay on the three screening-row lanes where they answer. **Before adding a "not measured" cell, ask whether this surface can ever fill it** — if not, the honest render is no column plus a sentence, because a striped marker implies a measurement is pending when none ever will be.

    Same pass: **the app's own vocabulary belongs in the guide, linked from where it is used.** `HYPE_WINDOW / WASHOUT / RECOVERING / BASE_FORMING / NOT_MEASURED` is not market usage — nobody guesses "base forming" — and a filter chip is the worst place to meet a word for the first time. `guide.html#ipo-stages` explains all five with the same badges the tables draw. **And an anchor into an async page needs code**: the guide builds from three fetches, so the browser resolves the hash against an empty `<main>` and gives up long before the target exists — `render().then(jumpToHash)` scrolls after mount, or every link lands the reader at the top of a 7,300px page.

119. **A collapsible section is only safe if its heading carries a count, and that count must describe the list underneath it** (SPEC 27.15, 2026-09-10). Discovery measured **30,976px, about 31 screens**, and 84% of it was three tables — Recent Listings 14,017px/238 rows, Universe Expansion 7,261px/120, Insider Activity 4,926px/60. Folding every section via `collapse()` in `ui.js` took the default to **4,296px**. The rule that makes it safe: once sections fold, **the heading row is the navigation**, so a folded section is one whose findings the reader cannot see, and the count is the only thing separating "I chose not to look" from "I did not know there was anything to look at". `collapse()` therefore always wraps `withCount()`. Building it surfaced a live instance of Gotcha 98: Recent Listings printed **54** in its pill while the table held **238**, because the pill was fed `ready.length` (past the six-month mark) instead of `rows.length` — harmless while you could scroll past it, not once it is the only thing on screen. Second rule: **the default open section is data-driven, not positional.** "All folded except the first" opens *Under the Radar*, which reads 0, while folding the lanes that found something — so the first section with a non-zero count opens instead. State is per section in `localStorage`, never a page-level "collapse all", and nothing ever auto-folds on scroll or a timer. Filter placement follows Gotcha 117's split: the three *stock lanes* keep sharing one universe-level bar (three questions, one screening run — three near-identical bars would be three things to keep in step), while the three tables that are **not** screening rows get their own chips, since their rows share no fields with a screening row. Those three were also 84% of the length, which is not a coincidence.

    **Extended to all thirteen pages on 2026-09-18, and two of its own rules moved.** The default
    is now **everything folded on every page** — the "first section with findings" rule existed to
    avoid opening an empty section while folding the ones that found something, and opening
    nothing satisfies that equally while giving the reader a page that starts as one screen of
    headings. Measured expanded→folded at 1600px: screener 34,783→900px, discovery 25,614→985,
    accuracy 21,502→1,333, guide 8,032→1,389. The storage prefix moved to `dash:collapse:v2:`,
    because a value stored under "open unless told otherwise" means the opposite under the new
    default. And **a heading may now carry a verdict instead of a count** — `withSummary()` beside
    `withCount()` — because half the sections on the stock and portfolio screens are single-verdict
    panels where a count of `1` says nothing: *Can this business compound? [Partial]*, *What did it
    report last quarter? [Weak]*. It routes through the existing `badge()` so a missing value draws
    the striped "not measured" marker rather than a blank (§21 rule 7); a folded heading is the
    last place an unmeasured value may look measured. The third case is the limit of the rule and
    is deliberate: **prose, a control or a link list gets nothing**, because it hides no finding —
    which is what lets the guide's thirteen prose sections fold at all.

    Five things that are load-bearing in the implementation. **The fold is applied inside
    `mount()`**, once, not at ~87 call sites — every page calls `mount(view, …)` and nothing else,
    so no page module needed an import, and an import nobody has to add cannot be got wrong
    (Gotcha 41/82: a missed import is a `ReferenceError` the page's own `.catch()` renders as a
    tidy error box, so every file still returns 200 and only a browser shows it). **`collapse()`
    now self-guards** on `.collapsible`, because it is destructive — a second pass nests a second
    `.section-body` — and that guard is what leaves the hand-written keys on discovery, accuracy
    and macro un-orphaned. **Keys come from the title alone**, stamped by `section()` into
    `dataset.foldKey`; deriving them from the heading's `textContent` later would fold the count
    pill into the key and lose the reader's choice whenever a row count changed. **The store is
    mirrored in memory**, which is a correctness fix rather than an optimisation: the filter boxes
    on the screener, watchlist, portfolio and macro pages live *inside* a section and every
    keystroke re-mounts it, so with localStorage unavailable (private window, blocked site data)
    the section would re-fold on the first keystroke and take the cursor with it. **And the control
    is a `<button>` inside the `<h2>`**, not `role="button"` on the heading — with everything
    folded the headings are the page's only structural navigation, and `role="button"` on an `h2`
    removes it from the accessibility tree.

    Two traps found by running it rather than in review. `hidden="until-found"` keeps Ctrl+F
    working, but **Chrome removes the attribute itself** on a match, so the `beforematch` handler
    must sync the caret and `aria-expanded` *without* touching the attribute — otherwise the
    section is open while its heading says closed and the reader's next click appears to do
    nothing. (Also: never assign `body.hidden = true` again; the IDL setter writes `hidden=""`,
    which is plain `display:none`, and the downgrade is invisible unless you read the attribute's
    *value*.) And **a deep link must open its section before scrolling** — `scrollIntoView` into a
    hidden subtree silently does nothing and gets no `beforematch` rescue — hence `revealSection()`
    in `page-guide.js` and before the portfolio's thesis-editor scroll. Adding the chips *before*
    turning folding on was also deliberate and paid immediately: with the tables still visible you
    can check a pill against the list beneath it, which is how the Overview's "Today's biggest
    moves" was caught reading **30** above a chart of six (Gotcha 98 again). Afterwards you would
    have to open ninety-nine sections to find it.

    Process note from the same pass: a `.replace()` without an assert silently did nothing when the `ui.js` import line did not match the shape I assumed, and the page died with `collapse is not defined` — HTTP 200 on every file, error only in a browser (Gotcha 41/82). **Assert on every scripted edit**, and load the page after touching `static/js/**` rather than trusting `check_js_syntax.py`, which balances brackets but cannot see an unresolved name.

118. **A control that leaves the building asks first, and a timeout shorter than the measured run is a bug** (SPEC 27.14, 2026-09-10). "Capture from NSE now" on the IPO page is the one dashboard click that is not a database read — dozens of live NSE calls plus a paced broker quote per recent listing, measured at 5m13s — so it shows what it costs and waits. Its panel names what it fetches, that it writes, the refusal windows, **and what it will not do**: a mid-issue capture updates subscription figures and prices but cannot make the structure read decide sooner, because that waits for `subscriptionFinal` by design (Gotcha 108a). Without that line the button silently promises an earlier verdict. Two defects fixed with it. It was called **"Refresh from NSE now"**, which became wrong the moment Gotcha 116 put a control reading "Refresh" just above it — one word, two meanings, one screen, Gotcha 85 in miniature; refresh re-reads, capture goes out. And its client timeout was **4 minutes against a 5m13s run**, so an ordinary capture aborted in the browser with "that took too long" while the server finished it regardless — now 7. Per-row live actions (IPO analyse, watchlist/holdings re-analyse) deliberately keep firing on one click: they are seconds long and clicked repeatedly, and confirming each one trains the reader to dismiss confirmations, which is what makes the one on the expensive action worthless.

116. **A refresh button on a dashboard can only honestly mean "re-read", and it has to say whether anything moved** (SPEC 27.12, 2026-09-10). Every screen here is written by a scheduled job — the screening at 14:00, holdings prices on the hour, the IPO capture at 12:15 — so between two runs a refresh returns byte-identical rows. A control that answers "updated" then tells a small lie several times a day, and what it costs is the investor's belief in the screen, which is the same reasoning `api.js` already applies to the offline banner (B-086). So the control lives in the **freshness strip** in `nav.js` (one implementation, eleven pages), compares the freshness stamps for the keys *that page* declares across the click, and reports *"Updated — new prices"* or *"Checked — no new results yet"*. It never re-runs an analysis and its placement is what stops it reading as a Run button: a GET here can email a report or start a thirty-minute scan (Gotcha 17), and the Kite budget is one process-wide gate a manual job has already starved the afternoon schedulers out of (B-049). Three traps found building it. **One key list, two readers** — the strip and the message must resolve the page's keys through the same function, or they disagree (the stock page was stamped with prices and compared against nothing). **`initChrome()` must be idempotent**, because a page registers its whole entry function as the reload, so the second call must not prepend a second header — and `paintFreshness` rebuilds the strip wholesale, so the button has to be re-appended rather than left in the DOM, including on the offline path where the investor most wants to retry. **Register the reload only after the first load resolves** (`boot().then(() => registerRefresh(boot))`), or a click landing mid-load starts a second load on top of it. Two pages opt out on purpose: `guide.html` is prose with no data behind it, and `reports.html` builds its content on demand behind a 15-minute server cache, so re-mounting would only close whichever report is open — it falls back to re-reading the strip, which is the honest floor `nav.js` gives any page that registers nothing.

115. **The discovery page could only find what was already working, and the fix was to stop gating on the score** (SPEC §47). Every lane was momentum-positive — the composite rewards proximity to the 52-week high, §12.10 gates on that composite, and §30's Stage A filters on **54% of the composite's own weight** (Gotcha 37) — so a quality business that had fallen was invisible *because* it had fallen: on 2026-09-09 the names the new lane returns (HINDUNILVR, DABUR, COLPAL, WIPRO, HAVELLS) score **21 to 52** against a 65 floor. The lane therefore gates on the **business** (`COMPOUNDER`, or `PARTIAL` with `HIGH_QUALITY`), disqualifies on a HIGH forensic flag, and takes `rangePosition52w ≤ 35` — the **measured** bottom quartile of the live cross-section (median 65.2, p25 34.6 on 284 stocks), not an assumed level (Gotcha 76). Three rules travel with it. **Distance from the 52-week high is never the test alone** (B-062) — both figures are shown and the range position sorts. **The entry column will read "Avoid" for most of the list and that is correct**: it answers "has the fall stopped?", a different question with one shared rule table (Gotcha 85), so the disagreement is *explained beneath the table*, never re-written into a second engine. And the **coverage line is mandatory** — the forensic screen had run on 87 of 284 stocks and quarterly growth on 0, so an empty red-flag cell means nobody looked (Gotcha 44).

126. **The app now keeps a scoreboard on the analysts, and the currency symbol is what makes it honest** (SPEC §49, 2026-09-12). The ask was full analyst-target tracking — brokerage, target, rating, **assumptions, expected earnings, valuation multiple, catalysts**. The last four are inside a PDF sent to institutional clients and §24.1 already declined to buy the feed; inventing them is SPEC §21 rule 7 at its most damaging. What *was* available had been computed and thrown away since §24 shipped: `detectBrokerageActions` reads "Nomura raises target price to Rs 1,926" out of a headline, extracts the rupee figure, counts the headline as an upgrade and **discards the target**. So the ledger records the attributed claim and then measures it — did the price ever touch the level, how long it took, and **what the stock did against the Nifty over the same dates**, because a target reached in a rally is beta. It contributes **zero points** and has no bonus figure to switch on: a third party's opinion arriving through a news headline is the exact input shape of the two engines deleted on 2026-09-03, neither of which ever produced a measured hit rate (§39.3). Five things to preserve. **(a) The currency marker is required, and that is measured rather than cautious.** Making it optional (headlines "write target price 1,200", I assumed) accepted **14 rows from 162 real headlines of which 3 were nonsense** — *"Share Price Target **2026**"* → ₹2,026 from a year, *"sets **Nifty 50** target at 26,000"* → ₹26,000 filed against Eternal because that company was named in the same sentence, *"up 200% **in 1 year**"* → ₹1. Every genuine target in the sample carried Rs or ₹; with the marker plus an index-name lookback and a word boundary (so `FY27` is not an amount), the same 162 headlines give **6 rows and none wrong**. The invented test cases had all passed. **(b) A scale word abandons the headline, a percentage does not** — "target price by 20% to Rs 1,150" has a rejected candidate before the real one, but "revenue target of Rs 900 million by 2027" must not fall through to ₹2,027. **(c) It may name a company where §48's reader may not**, and the line is exact: the macro extractor would be *deciding* which businesses a general story touches, while here the headline itself names the company as the subject of an attributed target and the resolver only matches that name to a ticker. Two companies named → neither, unless one match contains the other ("HDFC Bank" over "HDFC"). **(d) The refusals are the feature** — a pending call is never a miss, a target revised before it resolved is `SUPERSEDED` (not a miss, the house withdrew it; but its revision rate is published, or revising the week before a deadline escapes every miss ever made), below 5 resolved calls there is no hit rate at all, and excess return is signed the way the call was made *only at aggregation*, so a house's correct Sell calls do not cancel its correct Buys. **(e) Coverage is thin and every screen says so**: the stored market-wide feed held 36 headlines in 7 days and **none** carried a target, because most read *"check target price"* or *"sees 46% upside"* with the figure in the article body. Deriving a level from a percentage and a close would be publishing a number nobody said. Four selection filters stand between research and a ledger row, so `AnalystTrackRecord.caveat()` travels with every table — a house's record here describes its **quotable** calls, not its research.

    **Superseded in part, 2026-09-12 — and the correction is the lesson** (SPEC §49.11). The claim above that assumptions, expected earnings and valuation multiples are unreachable was **wrong**. They are in a PDF sent to institutional clients, as stated — and **Moneycontrol publishes those PDFs**, along with a structured JSON index of them. One sampled note carries FY26/FY27E/FY28E sales, EBITDA, PAT and EPS plus the sentence *"we value IGL at 13x Dec'27E SA P/E and add INR44/sh … to arrive at our TP of INR195/sh"*. **Where a datum lives is not the same question as whether it is reachable, and I answered the second by assuming the first settled it.** The structured index is now the **primary** capture path and the headline parser is the fallback: it publishes the house, target, rating, both dates, the price the note quoted, a stock id and the house's *previous* target, which removes B-109/B-110/B-111 outright (they are all misreadings of prose, and there is no prose here) and lifts coverage from 291 companies per 6,275 headlines to 416 per 800 feed rows. Four further rules travel with it. **(f) The later of the two dates wins** — `calledOn` is the note's own date, `issuedOn` is when it reached the feed, and measuring from the former credits a house with what the price did while its note was private (Gotcha 100's look-ahead, in the flattering direction). **(g) A stock id maps to a ticker once, ever, and a failed lookup is stored** — the feed's display label is truncated to ~15 characters and matches nothing (6% against the app's own table), so `broker_symbol_map` is the only bridge; caching the misses is what stops every run re-probing dead ids (Gotcha 102). **(h) One house, one name** — the feed lists "Anand Rathi", "AnandRathi" and "Anand Rathi Financial Services" separately, and unmerged a real record splits into three sub-floor rows that each report TOO_EARLY for ever. **(i) Courtesy is the licence** — a third party's public endpoint, read for one investor's own portfolio, paced process-wide, identifying itself honestly rather than impersonating a browser, storing the published figures and the link but never the note. And the sell-side's own bias is now on the record: the sample is **78% BUY / 4.6% SELL**, which is exactly why the yardstick stays excess return over the Nifty across the same dates rather than a raw hit rate. **(j) The first full load found the real headline, and it is about revision rather than accuracy.** 6,972 targets, 719 stocks, 33 houses, back to Dec-2023 — and **4,799 of them (69%) had already been revised** by a later call from the same house. Across the 16 houses with 15+ resolved calls, the correlation between *the share of a house's calls that ever reach their deadline* and *its hit rate* is **-0.57**: Motilal Oswal resolves **4.3%** of its calls and hits 95.7%; Mirae Asset resolves **100%** and hits 59.1%. Excluding a revised call from the hit rate is correct (the house withdrew it) but it means a frequently-revising desk's hit rate measures its *update cadence*. §49.9 published the revision rate for exactly this reason; this is the first time that refusal paid. **(k) Three defects, all found by looking rather than by a status code**: the archive walk read a **failed page as the end of the archive** and stopped at 40% while logging success (an empty list meant two things and the caller could not tell — B-054's rule one level up, in *my own* new code); one firm held two scoreboard rows because a substring match cannot see a missing space; and the populated ledger took the accuracy page's DOM from 44 KB to **3 MB**, because folding a section hides a list without avoiding building it. **(l) A caveat that describes a different system from the one running is worse than none.** The shipped wording still said the sample was "calls that reached a headline … quoted a rupee target"; none of those filters applies to the primary path any more. It now names the biases that *do* apply. `caveatBlock` was iterating a hard-coded key list and would have dropped both new lines silently — it renders whatever the server sends now, which is the structural fix rather than adding two strings.

    **(m) The overlap screen, and the rule the investor taught it** (SPEC §49.12, 2026-09-14).
    Joining the ledger to this app's own screening answers "is anyone quoting the stocks we rate?"
    — 122 good stocks, 88 quoted, 34 quoted by nobody, 7 already trading above every target on
    them, and a correlation between claimed upside and our composite of **-0.322**. Four things
    worth keeping. **"Good" is the stored verdict, never `min-score-for-candidate`**, which is only
    half of `isCandidate()` and had this screen calling 145 stocks good while the screener said 122
    — Gotcha 85 in its plainest form, two screens disagreeing about one word. **The negative
    correlation ships with its explanation in the payload**, because 54% of the composite is price
    behaviour and a big claimed upside is by construction a stock far below where the house thinks
    it belongs: the two measure near-opposite things, so the composite cannot verify a target and
    the *fundamental* checks are the ones that can. **Agreement is counted in firms at a threshold
    set on the cross-section** — 39 of the 88 are quoted by exactly one house and the median is
    two, so `MIN_HOUSES_FOR_AGREEMENT = 3` is measured rather than assumed, and it counts firms not
    notes (one house revising three times is one opinion, B-041's rule). And the retrospective
    question — do our scores predict which targets get hit — **was refused**: 127 of 1,265
    resolved calls have a pre-call score, which is not a sample.

    Then the lesson that generalises: the first cut shipped the measurements and the explanations
    and stopped, and the investor's reply was *"not getting the action item"*. **Context is not a
    next step.** A screen carrying five careful paragraphs about why two numbers disagree, with
    nothing a reader can do afterwards, is read once and never returned to — which is the same
    waste as not building it. `AnalystOverlap.actions()` now names, per list, what to do with it,
    with every count passed in from the list it describes (B-098) and the vocabulary bounded the
    same way every verdict is: an action points at a list to read, never at a transaction, pinned
    by a test that fails on "buy", "sell" or "exit". **(n) A CSS class that does not exist fails
    exactly like Gotcha 104's missing variable** — silently, with the attribute present. The
    explanation block asked for `div.callout` and `ul.plain`; **neither is defined in `app.css`**,
    so the most important prose on the screen rendered as unstyled body text and read as filler.
    `.info-box` is the SPEC §21 "what this means" box and is the class that exists. Grep the
    stylesheet for a class name before using it, the same way you would a variable.
    **(o) The check the composite could never be** (SPEC §49.13, 2026-09-14). §49.12 ends by saying
    the composite cannot verify a price target; this is the thing that can. §12.5's reverse DCF is
    run **backwards from the target** — take the target price as given and solve for the growth the
    business would have to deliver for it to be the fair value — which is a claim about earnings and
    therefore comparable against the record, where a price-behaviour score is not. Three things to
    keep. **The cash flow cancels**: `fairValue` is linear in it, so `F(g1) = F(g0) × target/price`
    and the requirement comes out of the stored implied growth and the price ratio alone, with no
    fetch and no second derivation that could disagree with the stored figure. **The round-trip test
    is the alarm**: target = price must return the stored implied growth exactly, so changing the
    discount rate, terminal growth or forecast window in `IntrinsicValuationService` without changing
    them here fails a test instead of silently inverting stored figures under different assumptions.
    And **the §12.5 band boundaries are reused while the vocabulary is not** — same question asked at
    a different price (Gotcha 85), but both readings appear on one screen, so the words must differ
    (Gotcha 105).

    **(p) The benchmark was the bug, and zero variance is what found it** (B-113). The first build
    compared a ten-year requirement against `dcf_historical_growth_percent` — a **two-year** profit
    CAGR reading 37%, 47%, 63%, 66% and **109%** on the live rows, which are base effects rather than
    records. Against those, a 10–26% requirement is always below the record, and all six stocks
    returned one verdict. The inversion was correct the whole time and the round-trip test passed, so
    nothing looked broken; only the distribution gave it away. **Run any new verdict over a handful
    of real rows and look at the spread before shipping it** — this is the fourth instance of a
    measurement that produced the same answer for everything (Institutional Interest at 40, monthly
    RSI at 50 per B-060, Insider Pulse on no stock at all per B-074), and the first one caught before
    it reached the investor. The blast radius had it shipped is the sharp part: the one screen built
    to let the investor *question* an analyst would have endorsed every target on the board, in
    confident plain English, while appearing to have checked something. **Before comparing two rates,
    check they are measured over comparable spans** — B-047 filed a quarter as a year, B-060 read an
    RSI whose window could not reach its period, this compared two years against ten. The fix is
    `annual_fundamentals` with a **4-year floor**, thin history refused outright rather than used
    anyway, and a loss-making first or last year yielding no CAGR at all — a recovery from a loss is
    genuinely undefined, and both the negative and the enormous answer the arithmetic would otherwise
    give are wrong about good news.

127. **A "newest row per symbol" map built in Java reads the whole table** (B-115). `findRecentForSymbols`
    selects every screening row for a symbol list inside a window, and four callers used it only to keep the
    last write per symbol. On the screener that is 276 symbols x up to four spellings (Gotcha 84) x a
    **400-day** window against 27,845 rows — essentially the entire table hydrated as 103-column entities to
    use 408 of them, and it put `GET /api/dashboard/screener` at **31.7 s cold** against `api.js`'s 8 s
    timeout, so the dashboard showed its "did not answer in time" banner on every load. `findLatestForSymbolsSince`
    asks the database instead (grouped max over the existing `(symbol, screening_date)` unique index): **44 ms**,
    output byte-identical. Two rules. **A window plus an IN list is not a bounded query** — it is bounded by
    how long the app has been screening, so it gets slower every week and nothing fails. And **`MacroMeasurementService`
    deliberately still uses the old method**, because it keeps the newest row *with a positive price*: when a
    caller's "last write wins" carries an extra condition, it is not the same question, and switching it would
    silently drop data. Check what the loop body does before assuming two callers want the same rows.

128. **Prove the process you measured contains the change** (B-115, process note). Two `start-app.bat` runs
    appeared to succeed while port 8080 was still served by the process from an hour earlier: the health check
    answered, so everything looked restarted, and the old code's 23 s -> 6.5 s -> 4.9 s **JIT warm-up** read
    exactly like a fix landing. Caught only by comparing the process start time against the edit time. A
    responding endpoint proves something is listening, never that it is your build — check `StartTime`, or a
    fresh `Started IntradayApplication` line, before believing a before/after number.

129. **A third party's free text does not fit a column you sized from today's feed, and `saveAll` turns
    that into a total loss** (B-116). `ipo_issues.issue_type` is NSE's own wording, capped at
    `varchar(32)` because every value seen at build time was under 20 characters. A **further public
    offer** then arrived reading *"100% Book Building ( Further Public Offer)"* — 42 — and every IPO
    capture from 2026-09-14 died. The width was half the bug; the other half is that
    `IpoTrackingService` ended its run with one `repository.saveAll(...)`, so that row rolled back
    **all 255** plus four minutes of paced NSE and Kite calls. **A bulk save at the end of an expensive
    paced run is a single point of total failure** — save per row and report the count that failed
    (B-049 is the same shape, and its lesson had not been carried here). Two rules beside it: widen
    *and* truncate, because a length chosen from the current feed is the assumption that just failed;
    and widening needs an explicit `ALTER ... TYPE` in `SchemaMigrationRunner`, since `ddl-auto=update`
    adds columns but never alters an existing one's type (Gotcha 74, third face). **What found it was
    the freshness strip**: a stamp that had stopped moving, and `/api/dashboard/data-health` naming the
    job that owned it (SPEC §44). Nothing alerted — the scheduler caught the exception and carried on.

130. **A value stored as a date must be reported as a date — and JavaScript parses the two shapes in
    different timezones** (B-119). `new Date("2026-09-17T15:15:00")` has no zone and is read as
    **local** time; `new Date("2026-09-17")` is read as **UTC midnight**, which is 05:30 IST. Five of
    the twelve freshness keys are bare dates, because the tables behind them are keyed by date and no
    run time was ever recorded (`multibaggerScores`, `holdingsHistory`, `recommendationOutcomes`,
    `holdingClassification`, `watchlistSnapshot`). `relative()` ran both through one `new Date()`, so
    the 14:00 screening announced itself as **"9 hours ago"** on the strip at the top of *every*
    screen, and rows aged into "1 day ago" five and a half hours early. That is the whole app
    appearing stale while `/api/dashboard/data-health` reports 0 problems across 55 checks — which is
    exactly how it presented. Two rules. **Answer a date in days** ("today" / "yesterday" / "N days
    ago"): an hours-ago phrasing for a value with no recorded time invents precision nobody has, and
    the invented figure here was not even midnight local but another timezone's midnight. And
    **build a date-only stamp at local midnight** before any arithmetic — `daysAgo()` drives the
    amber `.stale` class, so the same skew delayed a genuine late-table warning by 5.5 hours.
    `dateTimeIst()` was already correct and is the model: its regex declines to match a date-only
    value and falls back to `shortDate`, refusing to name an hour it was not given. Note what did
    **not** find this: every server-side check passed, the syntax checker passed, and the pages
    rendered — it took reading the strip's own words against the run time in the log. Sibling of
    B-047 (a quarter filed as a year) and B-060 (an RSI whose window could not reach its period):
    a figure quoted on a scale it was never measured on.

131. **The app was judging a thesis on a number that is 59% share price, and the one piece of
    evidence that could have contradicted it was fetched and thrown away every day** (SPEC §50,
    2026-09-17). Thesis drift (§6.2) is a drift in the multibagger composite, and SPEC §40.2
    records that the live weight vector gives **59% of its weight to price behaviour** - so the
    thesis alarm was largely a price alarm wearing a fundamentals badge, which is why B-064 had to
    subtract the universe's own move from it to stop broad selloffs reading as thesis failure.
    Meanwhile the screening fetched every company's quarterly filings for the §12.4 earnings bonus
    and discarded them: no `quarterly_results` table, nothing that could say what a company
    reported or notice a result *landing*, and the one surviving read (`analyzeEarningsTrendBreak`)
    recomputed live on every email, holdings-only, negative-only, and **absent from all 39
    `screening_coverage` signals** - which by §38.2's own standard means it had never been measured
    on anything. Third instance of one shape: §49.11 found analyst targets parsed and discarded
    since §24, B-098 found growth and promoter holding computed every run and dropped for want of a
    column. **When a feature recomputes something expensive on every run, check whether anything
    keeps it.** Six rules travel with the fix. **(a) Count the checks, never average them** - the
    first live run read RELIANCE Q1 FY27 at **sales +25.4%, profit -24.6%, margin -4.9pp**, which
    counting calls WEAK and averaging calls fine (Gotcha 103, one scale down). **(b) A loss
    outranks the count**, because swinging from profit to loss while revenue grows is precisely the
    case a growth-weighted count misses. **(c) Year-on-year decides and quarter-on-quarter is shown
    but is never a signal** - Indian businesses are seasonal, so a QoQ fall is usually the calendar.
    **(d) A comparison across reporting bases is refused, not converted** (Gotcha 73): the leg reads
    NOT_MEASURED and names both bases, and an *unstated* basis is assumed comparable while saying so.
    **(e) `available_from` is the filing's own `broadcast_Date`, not the quarter end** - it was on
    the integrated-filing index the whole time alongside `consolidated`, `audited`, `seq_Id` and
    `revised_Date`, all discarded; using the period end would leak up to six weeks of look-ahead in
    the flattering direction (Gotcha 100), and this column is the only reason a future result-based
    signal can ever be back-tested. Note the estimate deliberately does **not** copy
    `annual_fundamentals`' err-five-months-late rule: quarter ends are ~91 days apart and filings
    land near day 45, so an estimate past the deadline would place one quarter's assumed
    publication *after* the next quarter's real one and silently reorder the series. **(f) The
    window is a window.** `EarningsCalendar` answers when the next result is due as a range built
    from the company's own median filing lag, because the board-meeting date is announced days
    ahead and this app does not read that feed - and `PAST_DUE` says in words that it cannot tell a
    late company from an uncaptured result. **The live run caught the one real defect**, which is
    the argument for running a new verdict over real rows before shipping it (Gotcha 126p): a
    25-day population floor was being applied to a *measured* habit, so Infosys - which files
    around day 20 - got a window opening four days after it would already have reported, an
    estimate contradicted by the record it was built from. A measured habit outranks a population
    default, which is the whole reason for measuring it.

132. **The email section that re-announces the same thing for six weeks is worse than no section.**
    The old trend-break block made one live NSE request *per holding* on every send and had no
    memory, so a bad quarter was reported daily until it aged out - and a reader shown the same
    alert forty times has been trained to skip the section that matters. `quarterly_results.
    announced_at` is the persisted dedup SPEC §6.4 listed as out of scope, closed for this surface,
    and **only rows the email actually showed are stamped** - marking anything else as reported is
    the silent-skip bug in the other direction. A restatement clears the stamp **once, on the
    transition**, because a company changing figures it already published is news, while a row
    already known to be revised is not news again every run.

133. **A per-share delta is not money, and summing thirty of them is a number in no unit at all**
    (B-120, SPEC §27.2). The Overview's "Today's Change" tile read **-Rs 52.23 (-0.02%)** on a day the
    portfolio had gained **+Rs 4,607.90 (+1.90%)** - wrong sign, 89x the magnitude - because
    `holdings.day_change` is `lastPrice - closePrice`, **per share**, and `portfolioKpis` summed it
    and divided by portfolio value. It is the right field for `dayChangePercent` (a ratio is the same
    per share or per position) and the wrong one for rupees. Two things made it invisible for months.
    Per-share deltas are dominated by **share price rather than position size** and roughly cancel
    across thirty names, so the tile read about zero almost every day - which means it could show
    neither a good day nor a bad one: a 1-share CPPLUS position contributed **-Rs 172** while 102
    shares of LCCPROJECT, **+Rs 2,728** of real money, contributed **+Rs 26.75**. And the percentage
    beside it was independently correct, so the pair looked consistent. `getDayChangeValue()` is now a
    derived `@Transient` on the entity - **one definition, read by both the portfolio total and the
    per-row note**, so the tile and the bar chart under it cannot drift (Gotcha 85) - and it returns
    **null, not zero**, when there is no previous close, because an unknown move summed as zero drags
    a total toward nothing while looking measured. Same family as B-047 (a quarter filed as a year),
    B-060 (an RSI whose window could not reach its period) and B-113 (two years against ten): a figure
    quoted on a scale it was not measured on. **Before summing a stored field across a portfolio, ask
    what one row of it is denominated in.**

134. **A default is not a finding, and the landing page is where that costs most** (B-121, SPEC §27.2a).
    Gotcha 68's rule - a default is not a statement - applied one level up, to what the app is willing
    to *raise an alarm about*. The attention list is the action surface, and **10 of its 20 items were
    allocation-drift warnings against a profile named "Default Portfolio"** that the investor had never
    opened: 20% IT against 0.43% held, 25% Banking against 3.77%, every bucket breaching every day and
    none able to clear. One of them, `OTHER` at a 15% target, was **unsatisfiable by construction** -
    a placeholder given an allocation goal (Gotcha 110), whose actual weight is 0.0% now that
    `SectorMapping.resolve` classifies every holding. Ten alerts that fire daily and can never clear
    are what train a reader to skip the section on the day it matters (Gotcha 132, on the most-read
    screen). `portfolio_profile.targets_stated` is the `thesis_stated` / `horizon_stated` pattern for
    allocation: set by the investor's own `PUT /api/portfolio/profile` write, never by the seeder, and
    **null means unknown, never stated**. Unstated targets raise **one INFO row** saying how many
    buckets breach and what would make the comparison mean anything - reported, not discarded - while
    the drift table on My Portfolio renders unchanged. Two more findings from the same load, both filed
    rather than fixed: **all 20 items were severity `WARNING`**, so `severityRank` sorted a constant
    while the copy promised "most urgent first" (B-121); and the list draws from four sources that do
    **not** include forensic flags or financial quality, so a holding reading `CASH_CONVERSION:HIGH`
    and `HIGH_RISK` with the composite pinned at the 54 cap appears nowhere on it (B-124) - the one
    signal that ships *armed* (Gotcha 42) is absent from the surface it was armed for.

135. **A row count is not a sample size, and the landing page is the last place to forget it**
    (B-122). The Overview's track-record panel printed `from 4,979 picks`, hit rate 55.5%, excess
    +3.21%, IC 0.114 - just over the conventional 0.10 line - with no caveat, while the app's **own**
    recorded walk-forward review for that same horizon reads `independentPeriods: 1`. The gate was
    `MIN_SAMPLE_FOR_DISPLAY = 10`, a row count, which is exactly the quantity Gotcha 92 says never to
    read as a sample size; the panel's own comment warns that "a hit rate from 3 picks looks exactly as
    authoritative as one from 300" and then enforces the row count. The figures stay on screen with a
    caveat naming the overlap - **they are real, they are merely not yet distinguishable from luck, and
    saying so is the discipline**; hiding them would teach the opposite lesson. Still open in the same
    method: `accuracyHeadline()` takes `max(sampleSize)` across **every** `Source`, so `MACRO_EVENT` -
    whose own javadoc forbids presenting it as a pick (Gotcha 124) - and the deleted `SECTOR_REVERSAL`
    engine (IC **-0.167** at 90d) are both eligible to headline it as soon as their rows mature.

136. **Two numbers answering two questions must say so, and one benchmark is a framing choice the
    reader cannot see** (SPEC §27.2). The Overview headlined **+14.4%** gain-on-cost directly above a
    **-4.7%** time-weighted return - both correct, 19 pp apart, opposite signs, unexplained. That gap
    *is* the finding (money moved in or out), so it is named in a callout rather than reconciled, which
    is Gotcha 105's rule reused verbatim. Beside it, the strip drew only the Nifty 50, which fell 8.52%
    over the window - **+3.81 pp ahead** - while the Nifty Midcap 150 rose 3.47%, putting a mid/small-
    tilted book **8.18 pp behind**. `excessVsMidcap150Pp` was computed and dropped, so the page showed
    the benchmark it beat. Both are drawn now. Same pass: `caveats[]` was on the wire and unrendered
    while the section's prose claimed deposits and withdrawals were removed - **11 of 24 flow days
    could not be corrected** - and the curve's "Value moved -Rs 11,730" headline is a flow-contaminated
    figure printed above the four tiles that exist to replace it, now labelled as market value with the
    money paid in named beside it. **When a payload carries a coverage or caveat field, rendering it is
    not optional** - it was written because the figure above it is incomplete without it.

## REST API Endpoints

**Dashboard UI** (SPEC §27 — all read-only, fast, DB-only; safe to call on page load):
- `GET /api/dashboard/compounding?symbol={sym}` - **"Can this business compound?"** (SPEC §41): the five-gate verdict, every gate with its figure, and how many years of accounts back it. DB-only. 404 when the stock has never been screened - the caller renders "never screened", never a zero. Since 2026-09-07 this and the screener column both read through **`CompoundingLensService`**, which also decorates every holdings row (`compounding*` `@Transient` fields) and fills the same five components on `WatchlistItemView` (2026-09-09) — one lookup, **four** surfaces, one renderer (`compoundingCell`), nothing to drift (SPEC §41.5). The field names are a wire contract with no compiler behind it, so `CompoundingSurfaceContractTest` asserts them
- `GET /api/dashboard/data-health` - **every automated check the app runs on itself** (SPEC §44): is each table as new as its own cron says, was each scoring signal actually measured and does its answer vary, **is the insider feed still delivering** (B-089), and is the annual-accounts backfill still moving. Returns every check including the ones that passed, plus the three things it cannot check. DB-only but heavier than its neighbours (resolves the whole screening universe) - one deliberate screen, `health.html`, never a strip.
- `GET /api/dashboard/health` - server time, market-open flag, per-table data freshness (drives the freshness strip on every screen, and the **refresh control** sitting in it — SPEC 27.12, Gotcha 116: it re-reads, never re-runs, and reports whether any stamp actually moved)
- `GET /api/dashboard/summary` - landing-page aggregate: portfolio KPIs, risk headline, accuracy headline, merged attention list
- `GET /api/dashboard/screener` - **latest screening run that actually has rows**, with its date. Use this instead of `/api/multibagger/scores` (in-memory cache, empty after the daily restart) or `/history` (defaults to today, empty until the 14:00 run) — see Gotcha 20 Since 2026-09-09 each row also carries `sector` (shared vocabulary, null when unclassified — never "Other"), `scoreDelta30d` / `scoreDelta30dRelative` / `universeShift30d` / `scoreDelta30dFrom` (one prior-run query per page, B-064 rule via `HoldingsDecayService.medianShift`) and `rangePosition52w`. Still DB-only.
- `GET /api/dashboard/series/holding?symbol={sym}&days=180` - per-stock time series from `holdings_history`
- `GET /api/dashboard/series/portfolio?days=180` - portfolio equity curve (invested / value / P&L per date)
- `GET /api/dashboard/series/matrix?days=90` - every symbol in one query, for table sparklines
- `GET /api/reports/{name}/preview` - returns the **actual report HTML** (`text/html`) for on-screen display instead of emailing it; 15-minute server-side cache. Names: `holdings-actions`, `holdings-analysis`, `holdings-weekly`, `morning-briefing`
- Note: `symbol` is a **query parameter** throughout, never a path variable — symbols contain a colon (`NSE:RELIANCE`), which is a legal-but-hazardous path character across Tomcat/Spring/`fetch()`

**Trading**:
- `GET /api/trading/positions` - View active positions
- `GET /api/trading/pnl` - Daily P&L summary
- `GET /api/trading/watchlist/dynamic` - Today's dynamically selected stocks with scores
- `POST /api/trading/watchlist/dynamic/refresh` - Force re-select stocks

**Tax-Lot Tracking** (SPEC §9):
- `GET /api/portfolio/tax-lots/{symbol}` - Per-lot view with days held, projected STCG/LTCG tax
- `POST /api/portfolio/tax-lots` - Manually create one lot
- `POST /api/portfolio/tax-lots/sell` - Record a sale, FIFO/LIFO/HIFO match
- `POST /api/portfolio/tax-lots/harvest` - Loss-harvest + LTCG-eligible + approaching-cutoff lots
- `POST /api/portfolio/tax-lots/bulk-import` - Bulk-create lots from a JSON list
- `POST /api/portfolio/tax-lots/import-zerodha-csv` - Backfill from Zerodha tradebook export (multipart `file=` or raw CSV body)
- `POST /api/portfolio/tax-lots/capture-today` - Manually trigger today's broker-trade auto-capture (otherwise runs at 15:28 IST MON-FRI)
- `DELETE /api/portfolio/tax-lots/all` - Destructive wipe (lots + sales)

**FII/DII Data**:
- `GET /api/fiidii/report` - Latest FII/DII comprehensive report
- `GET /api/fiidii/daily?date=2026-02-13` - Daily activity for specific date
- `GET /api/fiidii/trend?days=5` - Historical trend analysis
- `GET /api/fiidii/sectors` - Sector-wise institutional flows
- `GET /api/fiidii/deals` - Bulk/block deals by institutions
- `GET /api/fiidii/summary` - Quick summary (FII/DII net, sentiment)
- `POST /api/fiidii/trigger-report` - Manually generate and email report
- `POST /api/fiidii/update-data` - Manually update real data (when NSE blocked)
- `GET /api/fiidii/debug-raw` - Debug endpoint (logs raw NSE API response)
- `POST /api/fiidii/refresh` - Clear caches, force fresh fetch

**Market-Impact News Triggers** (all that survives of `/api/performance/*`, SPEC §39):
- `POST /api/performance/news/check` - Run the impact-news scan and alert now
- `POST /api/performance/news/daily-summary` - Send the daily impact-news summary now

**Multibagger Screening**:
- `GET /api/multibagger/scores` - Latest screening results (all stocks)
- `GET /api/multibagger/candidates` - Top multibagger candidates (score >= threshold)
- `GET /api/multibagger/holdings` - Multibagger scores for holdings only
- `POST /api/multibagger/screen` - Run full screening now (manual trigger)
- `GET /api/multibagger/screen/{symbol}` - Screen a single stock (e.g., `/api/multibagger/screen/NSE:RELIANCE`)
- `POST /api/multibagger/report` - Send multibagger report email now
- `GET /api/multibagger/trend/{symbol}?days=30` - Score trend history for a stock
- `GET /api/multibagger/history?date=2026-03-15` - Historical screening results for a date
- `GET /api/multibagger/by-cap?category=SMALL_CAP` - Filter by market cap (SMALL_CAP, MID_CAP, LARGE_CAP)
- `GET /api/multibagger/under-radar` - **Under-the-radar candidates** (SPEC §12.10): composite >= 65 AND under-discovery >= threshold, from the latest screening date that actually has rows. DB-only, dashboard-safe. Reports `notMeasured` alongside the list so a null score is never mistaken for a zero.

**Dynamic Universe** (SPEC §30):
- `GET /api/universe/dynamic` - Everything the expansion funnel tracks, grouped by status **including retired**. DB-only, dashboard-safe.
- `GET /api/universe/ipo-watch?setupsOnly=false` - Recent mainboard listings with post-IPO base evaluation. Kite-backed and slow (measured 6 min). **No caller since 2026-09-09** — the discovery page now defers to `/api/ipo/recent` (SPEC §45's four-value cycle stage, DB-only) rather than running a second engine on the same question (Gotcha 85), and the entry was dropped from `SLOW_BUT_SAFE` in `api.js`. `IpoWatchService` is a removal candidate.
- `POST /api/universe/scan` - Run Stage A coarse scan now (~1,600 Kite calls, ~10 min). Never from a page load.
- `POST /api/universe/process-queue` - Deep-score the next batch of queued symbols now.

**IPO Pipeline & Post-Listing Tracker** (SPEC §45) — `symbol` is always a query parameter:
- `GET /api/ipo/pipeline` - Open, forthcoming and closed-awaiting-listing mainboard issues with the **structure read** (`FAVOURABLE / MIXED / UNFAVOURABLE / NOT_MEASURED` — never "apply"), application sizing at the top of the band, quotas, links and (once final) retail odds. DB-only, dashboard-safe.
- `GET /api/ipo/recent?months=36` - Listings in the window with cycle stage (`HYPE_WINDOW / WASHOUT / RECOVERING / BASE_FORMING / NOT_MEASURED`), lock-in calendar, price vs issue and listing day, and any stored analysis. DB-only.
- `GET /api/ipo/issue?symbol=NSE:X` - One issue; 404 when not tracked. DB-only.
- `POST /api/ipo/capture` - Run the 12:15 capture now (live NSE + Kite). **Refused 09:40–10:15 and 14:00–close during market hours with a 409 carrying its reason.**
- `POST /api/ipo/analyse?symbol=NSE:X` - Post-listing analysis of one listed issue (~2 Kite + 3 NSE calls; a compute-to-decide composite past six months, Gotcha 50). Same refusal. Persists on the row so the page stays DB-only.
- Dashboard: `ipo.html` (loads the two GETs only); freshness key `ipoIssues` against the 12:15 cron on `health.html`.

**Macro & Geopolitical Event Exposure** (SPEC §48) — `symbol` is always a query parameter. Nothing here changes a score:
- `GET /api/macro/exposure/portfolio` - every active holding with its reading (`TAILWIND / HEADWIND / MIXED / NOT_EXPOSED / NOT_MEASURED`), plus how much of the portfolio has a rule at all. DB-only.
- `GET /api/macro/exposure?symbol=NSE:X` - one stock, with the channel each reason works through. **404 = never screened** (the app cannot classify this business); **200 carrying `NOT_MEASURED` = screened, no rule in the map**. Different facts, rendered differently (Gotcha 121).
- `GET /api/macro/events?days=&includeDismissed=` - the event ledger, with which of your stocks each event touches. DB-only.
- `GET /api/macro/calendar?days=` - dated events ahead. **No direction field exists**, by design.
- `GET /api/macro/map?factor=` - the rule table itself, so the rules can be read rather than trusted. Classpath only.
- `GET /api/macro/status` - last ingest, configured reader, map version, what a run would cost. DB-only.
- `POST /api/macro/ingest` - fetch the feeds and extract. **Live calls; refused 09:40-10:15 and from 14:00 to the close with a 409 carrying its reason.** Measured 84 s on the keyword reader.
- `POST /api/macro/news/scan` - the feed scan alone. Replaces `POST /api/performance/news/check`, deleted with the alert email (B-101).
- `POST /api/macro/events/dismiss?id=` - mark an event as noise. It stops counting and stays on the record; **readings already filed are deliberately left alone** (SPEC §48.7).
- Dashboard: `macro.html` ("Events" in the nav, loads the five GETs only); `Macro` column on portfolio / screener / watchlist / discovery and a panel on the stock page, all through `static/js/macro-cells.js`; freshness key `macroEvents`, which is **on-demand and never marked stale** (Gotcha 125).

**Long-Horizon Fundamentals, Turnarounds & Forensics** (SPEC §32):
- `POST /api/fundamentals/backfill?symbol=NSE:X&maxYears=10` - **Backfill annual history from NSE's own filing archive** (SPEC §32.5) — no CSV needed. Live NSE calls (one listing + one XBRL per year); refused 09:40–10:15 with a 409 because the FII/DII jobs share the NSE session.
- `POST /api/fundamentals/backfill-holdings?maxYears=10` - The same for every active holding. Several minutes; same guard.
- `POST /api/fundamentals/import-history?symbol=NSE:X` - One-time history import from a screener-style CSV (multipart `file=` or raw `text/csv`). XLSX is not parsed — save as CSV. Now the *fallback* for what the archive cannot supply, not the primary path.
- `GET /api/fundamentals/history?symbol=NSE:X` - Stored annual rows, oldest first. DB-only.
- `GET /api/fundamentals/turnaround/{symbol}` / `GET /api/fundamentals/turnarounds` - Turnaround verdict, or every candidate. DB-only.
- `GET /api/fundamentals/forensics/{symbol}?announcements=false` - Red flags. `announcements=true` adds one live NSE call — off by default so a page load can never trigger it.
- `DELETE /api/fundamentals/history?symbol=NSE:X` - Wipe a symbol's imported history for re-import. **Now the only way to correct a wrong imported figure**: the CSV re-import merges non-null only, so it can add but never overwrite (the B-046 fix applied to the import path).
- `GET /api/fundamentals/long-horizon?symbol=NSE:X` - **The capital-allocation record (SPEC §42) and the compounding track record (SPEC §43)** in one response, with `yearsOfAccounts`, the resolved symbol and a coverage note. DB-only, dashboard-safe. Returns `NOT_MEASURED` rather than 404 when there is no history — "not backfilled yet" and "poor record" must never render alike. **Contributes zero points to any score.**
- `GET /api/fundamentals/coverage` - **How deep the universe's annual history is** (SPEC §32.6): depth histogram at ≥3/4/5/8 years, status counts, symbols still pending, and the `UNAVAILABLE` list that needs the CSV fallback. DB-only. This is the instrument for the rollout — composites move while it runs.
- `POST /api/fundamentals/backfill-universe?limit=` - Run one backfill batch now instead of waiting for 11:30. Live NSE calls, same 09:40-10:15 refusal, and it stops at its own `stop-after` deadline regardless of `limit`.

**Concall & Management Quality** (SPEC §34):
- `POST /api/concall/analyze/{symbol}?record=&quarter=` - Read the latest transcript (live NSE + PDF download + AI). POST precisely so no page load can reach it; not on the on-demand allowlist.
- `GET /api/concall/credibility/{symbol}` - Measured guidance-met ratio, or `TOO_EARLY` / `NO_DATA`. DB-only.
- `GET /api/concall/ledger/{symbol}` - Every promise recorded. DB-only.
- `POST /api/concall/resolve/{id}?met=true&actual=…` - Record what actually happened. Manual by design.

**Portfolio Truth** (SPEC §46) — nothing here changes a score, a weight or a signal:
- `GET /api/portfolio/performance?days=365` - Time-weighted return (deposits/withdrawals removed) vs Nifty 50 and Nifty Midcap 150 over the same dates, annualised only past 90 days, drawdown on the flow-free index, total return in rupees (unrealised + FY realised + logged dividends), broker cash with its date, and how many holdings have a purchase lot. DB-only, page-load safe. Every figure carries its method and caveats.
- `GET /api/portfolio/quality` - Value-weighted P/E, ROCE, ROE, 2-year profit growth, each with the share of the book it was measured on; withheld under 50% coverage. DB-only.
- `POST /api/portfolio/benchmark/backfill?days=400` - Fill `benchmark_daily_close` from Kite daily candles (two paced calls). **Refused 14:00-15:30 on a trading day with a 409 carrying its reason.** Run once after deploy; the 15:00 snapshot keeps it current.
- Dashboard writes sanctioned by §46.7 (click-only): `PUT /api/portfolio/conviction` (thesis editor), `POST /api/portfolio/dividends` + `POST /dividends/{id}/received`, `POST /accumulate/{planId}/tranche/{trancheId}/fill`, `DELETE /accumulate/{id}`.

**Core Holdings** (SPEC §35):
- `GET /api/portfolio/core-holdings` - Latest tier, gates, durability and coverage per holding, plus the current mode (OBSERVATION / SUPPRESSION). DB-only, dashboard-safe.
- `GET /api/portfolio/core-holdings/history?symbol=NSE:X&days=180` - Tier and durability over time. DB-only.
- `POST /api/portfolio/core-holdings/override` - `{symbol, override: FORCE_CORE|FORCE_SATELLITE|null, note}`. Requires an existing conviction record — the override belongs beside the thesis it overrides.
- `POST /api/portfolio/core-holdings/classify` - Recompute now (~2 paced Kite calls per holding). **Refused from 14:55 with a 409 carrying its reason** (B-049 pattern); never wire into a page load.
- Dashboard: both GETs are read on page load by `page-holdings.js` (core section + Tier column) and `page-stock.js` (Core status gate checklist). Verified DB-only by hand — `get()` on a page load is ungated, only `getOnDemand()` has the allowlist (Gotcha 39).

**Watchlist Tracking** (SPEC §37) — `symbol` always a query parameter:
- `GET /api/watchlist/items?includeRemoved=false` - Tracked stocks with added date, return since added, vs Nifty, sector, quality + timing, trend series, verdict. DB-only, dashboard-safe.
- `GET /api/watchlist/item?symbol=NSE:X` - One row, 404 when not tracked. DB-only.
- `POST /api/watchlist/items` `{symbol, note}` - Add: prices it live, captures Nifty, runs the technical analysis. **409 from 14:55**, 422 when Kite has no price.
- `POST /api/watchlist/items/refresh?symbol=&quality=false` - Re-analyse one row; `quality=true` adds an ad-hoc composite (5–20 s). 409 from 14:55.
- `POST /api/watchlist/items/remove?symbol=` - Soft-delete; history kept. DB-only.
- `POST /api/watchlist/items/note?symbol=` `{note}` - Edit the note.
- `GET /api/watchlist` / `/buy-signals` / `/strong-buy-signals` / `/config` - Legacy raw rows (active only).
- `POST /api/watchlist/analyze` / `/analyze-and-report` - All-symbol re-analysis (+ email). Never from the UI.

**Analyst Target Ledger** (SPEC §49) — who said what, and whether they were right. Contributes **zero points** to any score:
- `GET /api/analyst/track-record` - per-brokerage hit rate, median excess return vs Nifty, median days to target, median claimed upside, revision rate — each withheld below 5 **resolved** calls (`TOO_EARLY`, never a bad record). Carries the coverage block and the sample caveat, which the UI renders above the table rather than in a tooltip. DB-only.
- `GET /api/analyst/targets?symbol=NSE:X` - every recorded target on a stock plus what the open ones say. DB-only; resolves across exchange prefixes and reports which symbol answered (Gotcha 84). The open-target summary is **never called a consensus** — the number of firms is printed beside the median, because on most stocks it is one or none.
  Since 2026-09-14 each response also carries **`plausibility`** (SPEC 49.13): this app'"'"'s own
  reverse DCF run **backwards from the target**, giving the profit growth the business would have to
  deliver for that target to be the fair value, beside what the price already assumes and what the
  company has actually delivered over its annual accounts. It is a statement about earnings, never a
  prediction that the price will get there (19). The benchmark is a **multi-year** CAGR from
  `annual_fundamentals`, minimum 4 years - using the stored two-year figure returned ONE verdict for
  every stock (B-113).
- `GET /api/analyst/recent?days=90` - recently recorded targets across every stock. DB-only.
- `GET /api/analyst/overlap` - **where this app’s own screening and the brokerages agree, and where they do not** (SPEC §49.12). "Good" is the stored *verdict* (Potential/Strong), never a score threshold - `min-score-for-candidate` is only half of `isCandidate()` and using it made this screen call 145 stocks good where the screener says 122. Carries the band-coverage table, four lists (both sides rate it / the full quoted set / already past every target / we rate it and nobody quotes it), the claimed-upside-vs-composite correlation **with the explanation of why it is negative**, and an **action block** saying what to do with each list. DB-only, page-load safe. Zero points to any score.
- `POST /api/analyst/capture` - mine the stored headline feed now. DB-only, but it writes, so POST.
- `POST /api/analyst/measure` - measure the open book now (~1 paced Kite call per stock with an open target). **Refused from 14:00 on a trading day with a 409 carrying its reason.**
- `POST /api/analyst/feed/capture` - pull the latest notes from the **structured broker-research feed** (SPEC §49.11), the primary capture path since 2026-09-12. Reads from the top and stops once a page adds nothing - one page on an ordinary day. Different host from Kite/NSE, so not refused during the broker crunch.
- `POST /api/analyst/feed/backfill?maxPages=90` - walk the research archive (back to Jan 2024, ~85 pages, several minutes, paced). Writes only what is not on file, so re-running is safe. **Refused from 14:00 on a trading day** - not for its own cost but because the pass that *prices* what it writes shares the broker budget.
- **Portfolio coverage (SPEC 49.14, 2026-09-17)**: `HoldingsViewDecorator` attaches thirteen
  `@Transient` `analyst*` fields to **every** holdings read path (SPEC 6.6), so `portfolio.html`
  carries an **Analysts** column (how many firms, named in the tooltip, upside to the median)
  and a *"Who else is covering what you own"* section naming the firms per holding. One bulk
  query for the table - per row it is ~120 lookups. `liveSummary` now **delegates** to the same
  `Coverage` record, so the stock page and the portfolio cannot count houses differently
  (Gotcha 85). Measured on the live book: 24 of 30 holdings have a target on file, 21 live,
  and **15 answer under a different exchange prefix** from the one they are held under.
  `analystHouses` is a nullable `Integer` because **null is "did not look" and 0 is "looked,
  found none"** - and a 0 here is a fact about what reaches the feeds, never about whether the
  company is covered (SPEC 49.7), which the mandatory coverage line says in words.
  **B-117, caught by rendering it:** the panel first grouped every zero into one bucket and told
  the investor three *covered* holdings had no target on file - NATIONALUM has ten, from three
  firms, none still running. The cell already drew that state correctly; the summary beside it
  re-derived its own grouping from a narrower field. **When a renderer distinguishes N states, a
  summary over the same rows must distinguish the same N** (B-098 + Gotcha 121).
- Dashboard: both GETs load on `accuracy.html` (the analysts' record sits under the app's own — same question, same yardstick) and on `stock.html`. Freshness key `analystTargets`, stamped when the pass last **measured**, not when it last recorded (brokerages do not publish daily, and an amber strip on a quiet week trains the eye past the colour).

**Quarterly Results** (SPEC §50) - `symbol` is always a query parameter. Nothing here changes a score:
- `GET /api/earnings/result?symbol=NSE:X` - what the company reported last quarter, judged against **the same quarter a year earlier** (`STRONG / IN_LINE / WEAK / CONCERNING / NOT_MEASURED`), all four checks including the ones that could not be made, every filed quarter on record, and the next-result **window**. **200 carrying `NOT_MEASURED`, never 404** - "no filed quarter captured" and "reported badly" must not render alike. DB-only.
- `GET /api/earnings/portfolio` - every active holding's read plus the mandatory coverage line. DB-only.
- `GET /api/earnings/recent?days=30` - results published across the book, ordered by the day the **company** published, not the day this app read it (a backfill of old quarters must never present itself as a week of fresh results). DB-only.
- `GET /api/earnings/coverage` - how many companies have a captured quarter at all. DB-only.
- `POST /api/earnings/capture?symbol=NSE:X` - live NSE. **Refused 09:40-10:15 and from 14:00 to the close with a 409 carrying its reason.** Rarely needed: the screening captures these anyway.
- Dashboard: `Result` column + filter chips + coverage line on the portfolio, a panel and a quarter-by-quarter table on the stock page, all through `static/js/earnings-cells.js`; freshness key `quarterlyResults`, stamped when the **capture** last ran rather than when a company last published.

**Insider Pulse** (SPEC §28):
- `GET /api/insider/{symbol}` - Disclosure history + rolling 90-day pulse verdict. DB-only, dashboard-safe.
- `GET /api/insider/recent?days=30` - All-market disclosures captured recently. DB-only.
- `POST /api/insider/capture` - Run a capture pass now (**live NSE fetches — never wire into a page load**)

**AI Deep Stock Research**:
- `GET /api/research/{symbol}` - AI-powered 15-dimension deep research (e.g., `/api/research/NSE:RELIANCE`)
- `GET /api/research/discover` - AI discovers new investment opportunities (themes, gems, contrarian ideas)
- `GET /api/research/discover/quantitative` - Data-driven discovery scan with entry/exit levels (no AI, sends email)
- `GET /api/research/levels/{symbol}` - Entry/exit levels for a single stock (support, resistance, EMA, RSI, targets)
- `GET /api/research/universe/expand` - AI universe expansion analysis (new stocks & sector gaps, sends email)
- `GET /api/research/earnings/{symbol}` - Earnings growth analysis (CAGR, margins, growth verdict)
- `GET /api/research/shareholding/{symbol}` - Shareholding history & trend (promoter/FII changes, insider signal)
- `GET /api/research/valuation/{symbol}` - Reverse-DCF sanity check; returns implied growth, historical growth, expectation gap, sensitivity band, and verdict
- `GET /api/research/capital-efficiency/{symbol}` - ROCE / ROE / Debt-to-Equity / real cash conversion / dividend payout from the latest annual Ind-AS XBRL balance sheet (SPEC §12.8)
- `GET /api/research/capex/{symbol}` - Capex cycle (SPEC §31): CWIP intensity, capex-to-depreciation, verdict. Prior year comes from `annual_fundamentals`, not the filing (B-034, Gotcha 40)
- `GET /api/research/analyst/{symbol}` - Analyst signal (proxy for paid consensus, SPEC §24): earnings trend-break vs 3-quarter linear projection + brokerage-action keyword scan from last 7 days of news
- Financial-quality analysis is surfaced inline inside the Deep Research endpoint (`/api/research/{symbol}` → "FINANCIAL QUALITY" section) and on multibagger scores via `getFinancialQualityScore()` / `getFinancialQualityVerdict()`. Reverse-DCF output is in the Deep Research "INTRINSIC VALUATION" section (data dimension 17) and on `multibagger_scores.dcf_*` columns.

**Recommendation Accuracy Tracking** (SPEC.md §23):
- `GET /api/accuracy/summary` - Calibration metrics across all engines and horizons (hit rate, mean return, excess vs Nifty, Information Coefficient)
- `GET /api/accuracy/by-source/{source}` - One engine: MULTIBAGGER / QUANT_DISCOVERY / SECTOR_REVERSAL
- `GET /api/accuracy/by-symbol/{symbol}` - Recommendation history for a stock (e.g., `/api/accuracy/by-symbol/NSE:RELIANCE`)
- `GET /api/accuracy/coverage` - **Per-signal coverage vector** for the latest screening run that has rows (SPEC §38.2): measured / not-applicable / not-measured, coverage %, cross-sectional mean & spread, collapsed flag. Answers what an IC table cannot — was the signal weak, or never measured? DB-only, dashboard-safe; rendered on `accuracy.html` below the dimension-IC panel. `?date=` for a specific run.
- `GET /api/accuracy/coverage/trend?signal=Valuation&days=90` - One signal's coverage over time. DB-only.
- `POST /api/accuracy/refresh-outcomes` - On-demand recompute of all due outcomes
- `POST /api/accuracy/report` - Send the weekly accuracy email now
- `POST /api/accuracy/retro` - **Retro-backtest** (SPEC §33): score ~20 known 2018-2023 multibaggers and ~20 matched controls as-of their base year, using only the four price/volume dimensions. Manual only, expensive. Deliberately NOT under `/api/backtest` (that namespace died with the intraday engine on 2026-05-24).

**Learning Substrate** (SPEC §38.7-§38.11) — nothing here changes a score or a weight:
- `GET /api/learning/variants` - The six pre-registered weightings with their hypotheses and weights. No I/O at all.
- `GET /api/learning/shadow?date=&variant=` - Shadow composites for a date (walks back to the latest date with rows when omitted). DB-only, dashboard-safe.
- `POST /api/learning/shadow/backfill?force=false` - Reconstruct shadow composites for every past screening date that has none. DB-only but thousands of writes, so POST — no page load can reach it. `force=true` after a variant-set change.
- `POST /api/learning/review?horizon=90&embargo=true&record=true` - Walk-forward evaluation + promotion-gate verdict, recorded to `weight_reviews`. **~1 paced Kite call per symbol (~2 min); refused 14:00-15:30 with a 409 carrying its reason.** `record=false` for an exploratory run — a review not meant to count must not satisfy a stability condition.
- `GET /api/learning/reviews?horizon=` - Recorded verdicts, most recent first. DB-only.

**Holdings Buy Timing** (SPEC §6.5):
- `GET /api/trading/holdings/buy-timing` - "Is it still a good time to buy more?" per holding, keyed by the holding's symbol. Shares the watchlist's rule table and **defers to the watchlist's verdict** for a tracked stock. DB-only, dashboard-safe.
- `POST /api/trading/holdings/refresh-one?symbol=` - Re-analyse one holding now (~2 paced Kite calls). **Refused from 14:55 with a 409 carrying its reason** (B-049). Never wire into a page load.

**Holdings Drift & Decay**:
- `GET /api/trading/holdings/decay` - Per-holding composite-score decay (30d + 60d windows, verdicts BROKEN/DECAYING/WATCH/INTACT/STALE/NO_DATA). SPEC §6.2 thesis-drift.

**Report Triggers**:
- `POST /api/trading/morning-briefing` - Manually trigger morning briefing email
- `POST /api/trading/holdings/exit-alerts` - Manually trigger exit timing alerts check
- `POST /api/alerts/target-hits/scan` - Run the target-hit scan now; emails picks/holdings that newly reached their target (SPEC §26)

## Debugging & Monitoring

**Logs**: `logs/trading-app.log` (10MB rolling, 100MB cap, 1 day retention)
- Search by correlation ID: `grep "5a2b1c3d-NSE:RELIANCE" logs/trading-app.log`
- Filter by level: `grep "ERROR\|WARN" logs/trading-app.log`

**Database**: PostgreSQL on localhost:5432
- Database: `tradingdb`
- Tables: `trade_entity`, `position_entity`, `strategy_execution_log_entity`, `breakout_signal_entity`
- Use DBeaver or psql for queries

**Email Reports**:
- Morning briefing: 9:30 AM daily (consolidated: FII/DII + portfolio summary + holdings near key levels + multibagger radar)
- Exit timing alerts: 10 AM, 12 PM, 2 PM daily (resistance hits, RSI overbought, support breaks, deep losses, momentum reversals)
- Holdings analysis: 3:18 PM daily — split into **two emails** sent back-to-back so each one is scannable: (1) *Action Items* — portfolio summary, next-steps, exits/profit-booking (tax-aware per SPEC §9.4), thesis-drift / balance-sheet / trend-break attention; (2) *Analysis* — Market Intelligence Overlay, valuation, support/resistance, AI insights, multibagger assessment, full holdings table. Weekly Saturday email is still single-shot.
- FII/DII activity: 10:00 AM daily
- Multibagger screening: Saturday 9:00 AM weekly (full screening report with candidates, sector breakdown, market cap analysis)
- Recommendation accuracy: Friday 3:25 PM (hit rate, excess return vs Nifty, per-dimension IC — the *only* engine scoreboard, SPEC §23)

## FII/DII Data Integration

### NSE API Integration

The system fetches real-time FII/DII (Foreign & Domestic Institutional Investor) data from NSE India API for market sentiment analysis and email reports.

**API Endpoint**: `https://www.nseindia.com/api/fiidiiTradeReact`

**Critical Implementation Details**:

1. **GZIP Decompression** (Fixed Issue):
   - NSE returns GZIP compressed responses
   - **DO NOT manually set `Accept-Encoding` header** - this prevents Spring WebClient's auto-decompression
   - WebClient handles decompression automatically when header is not manually set
   - Bug symptom: Binary data (�) instead of JSON

2. **Response Format**:
   ```json
   [
     {
       "category": "DII",
       "date": "13-Feb-2026",
       "buyValue": "20605.87",
       "sellValue": "15051.91",
       "netValue": "5553.96"
     },
     {
       "category": "FII/FPI",
       "date": "13-Feb-2026",
       "buyValue": "14586.73",
       "sellValue": "21982.14",
       "netValue": "-7395.41"
     }
   ]
   ```

3. **Unit Handling** (Lakhs vs Crores):
   - NSE API typically returns values in **Crores**
   - Automatic detection: if values > 100,000, they're in Lakhs and converted to Crores (÷100)
   - Typical range: 10,000-30,000 Crores daily

4. **Session Management**:
   - NSE requires valid session cookies
   - Cookies refresh by visiting homepage first
   - Cookie expiry: 3 minutes
   - Force refresh on each API call for reliability

**Files**: [FiiDiiDataService.java](src/main/java/com/example/trading/fiidii/FiiDiiDataService.java:128-145), [FiiDiiReportService.java](src/main/java/com/example/trading/fiidii/FiiDiiReportService.java)

**Debug Endpoint**: `GET /api/fiidii/debug-raw` - Triggers fresh fetch and logs raw NSE response

**Scheduled Reports**:
- Fetch data: 9:45 AM IST (after NSE publishes previous day's data)
- Send email: 10:00 AM IST with comprehensive analysis
- Manual trigger: `POST /api/fiidii/trigger-report`

## Report & Alert Services

### 1. Morning Briefing Email (9:30 AM IST)

Consolidated pre-market email combining data from multiple services into a single actionable report.

**Sections**: Market Pulse (FII/DII summary), NIFTY/BANKNIFTY Direction (options PCR + max pain), Portfolio Summary (holdings count, total value, avg P&L), Holdings Near Key Levels (within 2% of resistance/support), Active Breakouts (from breakout scanner), Tracked Signals (pending signal performance).

**Dependencies**: `FiiDiiDataService`, `OptionChainAnalysisService`, `HoldingsRepository`, `BreakoutSignalRepository`, `SignalPerformanceRepository`

**File**: [MorningBriefingService.java](src/main/java/com/example/trading/notification/MorningBriefingService.java)
**Manual trigger**: `POST /api/trading/morning-briefing`

### 2. Cross-Report Correlation (Holdings Report Enhancement)

Market Intelligence Overlay added to the holdings analysis email, correlating portfolio stance with external signals.

**Features**:
- **Portfolio-Market Alignment**: Compares portfolio bullish/bearish ratio against NIFTY options direction (PCR). Alerts if conflicting (e.g., portfolio 70% bullish but options say BEARISH)
- **FII/DII Sector Flow Warnings**: Cross-references institutional flows with holdings sectors

**File**: [HoldingsReportService.java](src/main/java/com/example/trading/holdings/HoldingsReportService.java) — `buildMarketIntelligenceOverlay()` method

### 2a. Tax-Aware Profit Booking (Holdings Report — SPEC §9.4)

The daily holdings email gates partial-exit suggestions on **LTCG eligibility** (>365 days held) so the user does not realize 20% STCG when a lower-tax option exists.

**Behavior**:
- **Core holdings are excluded from both tables** (SPEC §35.5, 2026-08-26). A technical SELL on a core holding moves to *"Core holdings — hold through the noise"* with its durability and reason — shown, not deleted. The Book Profit table now also carries the **B-056** caveat, because the rule that populates it is `P&L > 50% AND RSI > 70`: a momentum rule from the intraday era that flags the best compounders precisely while they run. Phase B replaces the table outright; until then the caveat is the fix.
- **"Book Profit" table** — only lists stocks with LTCG-eligible quantity (held >365 days). The "Qty to Book" column reflects the LTCG-eligible portion only. Mixed-horizon stocks show LTCG/STCG split with `daysUntilNextLtcg`.
- **"Hold for LTCG" callout** (new) — short-term winners (P&L > 20% but entirely <365 days) are surfaced separately as "wait N days for LTCG" instead of "book now". Stocks within 30 days of cutoff get "Almost LTCG — hold" emphasis.
- **"Exit Required" table** — sell-signal exits (`SELL` / `STRONG_SELL`) still fire regardless of horizon (cut-loss takes precedence over tax optimization). New **Horizon column** tags each row LTCG / STCG (Nd) / MIXED. Header notes that realized STCL offsets STCG/LTCG within the same FY, so short-term losses still have tax value.
- **Next-Step Summary** — splits the old "BOOK PROFIT on N winners" item into two: "BOOK PROFIT on N long-term winners" + "HOLD N short-term winners for LTCG" (with days-to-cutoff per stock).
- **Tax horizon resolution**: (1) **ISIN match** — finds open lots by `holding.isin` regardless of exchange prefix, so a `BSE:WAAREEENER` holding correctly aggregates against `NSE:WAAREEENER` lots (same ISIN); (2) **symbol match** as fallback for legacy lots without ISIN; (3) `HoldingsEntity.purchaseDate` approximation; (4) UNKNOWN. ISIN is captured from the Zerodha tradebook CSV's `isin` column on import — re-uploading a previously imported CSV backfills ISIN onto existing lots without duplicating rows.

**Files**: [TaxLotService.java](src/main/java/com/example/trading/portfolio/tax/TaxLotService.java) (`classifyForExit`), [TaxLotDto.java](src/main/java/com/example/trading/portfolio/tax/TaxLotDto.java) (`TaxAwareExitClassification` record), [HoldingsReportService.java](src/main/java/com/example/trading/holdings/HoldingsReportService.java) (helpers `classify`, `horizonBadge`, `buildTaxHorizonGloss`).

### 2b. Tax-Lot Auto-Capture & Zerodha Backfill (SPEC §9.3)

To eliminate the "Holding period unknown" fallback in the tax-aware report, the system now seeds and maintains the tax-lot ledger automatically.

**Live auto-capture** ([TaxLotAutoCaptureScheduler.java](src/main/java/com/example/trading/portfolio/tax/TaxLotAutoCaptureScheduler.java))
- Schedule: **15:28 IST MON-FRI** (within SPEC §3.4 market window — Kite Connect's `/trades` is current-day only and the app stops at 15:35).
- Calls `BrokerClient.getTodayTrades()` → filters NSE/BSE equity → BUY → `TaxLotService.createLot`, SELL → `TaxLotService.recordSale` (FIFO).
- Idempotent on broker `trade_id`: re-runs and crash recovery never duplicate. Stored in new nullable `tax_lot.trade_id` and `tax_lot_sale.trade_id` columns (indexed).
- Manual trigger: `POST /api/portfolio/tax-lots/capture-today`.

**One-shot CSV backfill** ([ZerodhaTradebookImportService.java](src/main/java/com/example/trading/portfolio/tax/ZerodhaTradebookImportService.java))
- Endpoint: `POST /api/portfolio/tax-lots/import-zerodha-csv` — accepts multipart `file=` or raw `text/csv` body.
- Parses Zerodha Console → Reports → Tradebook export. Required columns: `symbol`, `trade_date`, `exchange`, `trade_type`, `quantity`, `price`, `trade_id`.
- Replays trades chronologically with FIFO matching. Sells whose buy predates the export window are logged as `sellsUnmatched` and skipped (don't fail the import). Returns summary: `rowsParsed`, `buysCreated`, `buysSkippedDuplicate`, `sellsRecorded`, `sellsSkippedDuplicate`, `sellsUnmatched`, `netQuantityBySymbol`.

**Why a scheduler at 15:28 instead of after-close**: Kite Connect API has no historical-trades endpoint — `/trades` only returns today. Combined with the app shutting down at 15:35, the only safe capture window is during market hours. 15:28 gets ~all of the day's fills while leaving 2 minutes for slow API responses; trades placed in the last 2 minutes of the session are picked up by the next CSV re-import (rare for a long-term portfolio).

**Files**: [TaxLotAutoCaptureScheduler.java](src/main/java/com/example/trading/portfolio/tax/TaxLotAutoCaptureScheduler.java), [ZerodhaTradebookImportService.java](src/main/java/com/example/trading/portfolio/tax/ZerodhaTradebookImportService.java), [TaxLotEntity.java](src/main/java/com/example/trading/portfolio/tax/TaxLotEntity.java) / [TaxLotSaleEntity.java](src/main/java/com/example/trading/portfolio/tax/TaxLotSaleEntity.java) (`tradeId` column + index), [BrokerClient.java](src/main/java/com/example/trading/broker/BrokerClient.java) / [KiteBrokerClient.java](src/main/java/com/example/trading/broker/kite/KiteBrokerClient.java) (`getTodayTrades` → `GET /trades`).

### 3. Performance Report Enhancements — REMOVED 2026-09-03 (SPEC §39). Historical.

Added detailed analytics sections to the weekly performance email.

**New Sections**:
- **Strategy Breakdown**: Per-strategy win rate, avg return, trade count
- **Benchmark Comparison**: Portfolio returns vs NIFTY 50 index
- **Risk Metrics**: Sharpe ratio, max drawdown, average win/loss ratio

**File**: [PerformanceReportingService.java](src/main/java/com/example/trading/analytics/PerformanceReportingService.java)

### 4. Weekly Portfolio Review Enhancement

Added trend analysis sections to the weekly holdings review email.

**New Sections**:
- **Week-over-Week Changes**: Price movement, P&L change per holding
- **Top Movers**: Best/worst 3 performers with percentage moves
- **Sector Rotation**: Sector-wise performance aggregation

**File**: [HoldingsReportService.java](src/main/java/com/example/trading/holdings/HoldingsReportService.java) — `buildWeekOverWeekChanges()`, `buildTopMovers()`, `buildSectorRotation()` methods

### 5. Exit Timing Alerts (10 AM, 12 PM, 2 PM IST)

Proactive alerts when holdings approach exit conditions during market hours.

**5 Exit Conditions**:
1. **Near Resistance**: Price within 1% of 52-week high or target price
2. **RSI Overbought**: RSI > 75 (potential reversal zone)
3. **Broke Support**: Price dropped below EMA50 or key support level
4. **Deep Loss**: Unrealized loss > 10% (cut-loss candidate)
5. **Momentum Reversal**: Trend direction changed from BULLISH to BEARISH

**File**: [ExitTimingAlertService.java](src/main/java/com/example/trading/holdings/ExitTimingAlertService.java)
**Manual trigger**: `POST /api/trading/holdings/exit-alerts`

### 6. Multibagger Screening & Scoring Module

Identifies potential multibagger stocks using a **7-dimension scoring engine** (0-100 composite score) plus **2 bonus adjustments** from NSE fundamental data and a **HIGH_RISK balance-sheet cap**. Screens ~90 stocks from the universe using Kite daily candle data, NSE valuation data, earnings/shareholding history, and existing FII/DII infrastructure.

**7 Scoring Dimensions** (weighted — rebalanced 2026-04-19 for Financial Quality, and again 2026-09-03 when Sector Tailwind was removed and its 0.08 redistributed *in proportion*, SPEC §39).

> ⚠️ **All seven `trading.multibagger.*-weight` keys must be set in [application.yml](src/main/resources/application.yml) and sum to 1.0** (B-019). They are `@ConfigurationProperties`, so a weight omitted from the yml keeps its Java default instead of being disabled. The yml listed only the original seven from 2026-04-19 until 2026-08-22, making the effective sum **1.15** — every composite inflated 15%, a "60" threshold really meaning 52, and ~70% of the universe passing as candidates. `MultibaggerConfig.validateWeights()` now refuses to boot if the sum drifts.
>
> ⚠️ **The HIGH_RISK cap runs last, after every bonus** (B-020). Applied before them it isn't a cap — the bonus chain is worth up to +47.
>
> ⚠️ **Unmeasured ≠ neutral.** The four fundamental dimensions return `Integer` and may be `null`; `weightedComposite()` drops nulls and renormalises the remaining weight. Never substitute a neutral score for missing data — that made the engine rank stocks it couldn't analyse *above* stocks it measured and found weak. `MultibaggerScore`/`MultibaggerScoreEntity` fields for those four are `Integer`, not `int` (a null unboxes to NPE — this is how the first post-fix screening run failed 289 of 367 stocks).
>
> ⚠️ **Candidates are gated on percentile AND absolute score.** `isCandidate()` requires top `candidate-top-percentile`% (default 20) *and* `min-score-for-candidate`. Percentile rank is immune to uniform score-scale drift, which is what B-018/B-019/the bonus stack all were. `assignPercentileRanks()` runs after the sort; ties share the best rank.
1. **Technical Momentum** (20%) — Weekly EMA-12/26 trend, RSI positioning (sweet spot 50-75)
2. **Volume Accumulation** (13%) — Smart money patterns, up-day volume bias, volume trend growth
3. **Relative Strength** (13%) — 3M/6M/1Y outperformance vs Nifty 50 index
4. **Price Structure** (13%) — 52-week high proximity, higher-lows pattern, base building detection
5. **Valuation** (14%) — Blended 50/50 of PE-deviation and **reverse-DCF expectation gap** (from `IntrinsicValuationService`). **PE / market cap / EPS are computed from fundamentals (B-018, 2026-08-22), not fetched** — NSE bot-walled `/api/quote-equity` (403 on 100% of calls) which had silently nulled stock PE, market cap and industry for every stock and killed the reverse-DCF outright. `StockValuationService` now derives: shares = `PaidUpValueOfEquityShareCapital / FaceValueOfEquityShareCapital` from the integrated-filing XBRL (works for both `INDAS` and `BANKING` taxonomies), market cap = price × shares (Kite price), PE = market cap / TTM profit. Sector PE is a **live median of peer PEs** when the caller passes a sector hint via `getValuationData(symbol, sectorHint)`, else the hardcoded table. DCF solves the growth rate implied by today's price using a 10-year, 12%-discount, 4%-terminal model with FCF proxy = profit + depreciation; compares against the 2-year profit CAGR. Verdict maps DEEPLY_UNDERVALUED→95, UNDERVALUED→80, FAIRLY_VALUED→50, EXPENSIVE→25, EXTREMELY_EXPENSIVE→5; NOT_APPLICABLE (loss-making) or INSUFFICIENT_DATA falls back to PE-only. Known bias: under-values long-duration compounders.
6. **Institutional Interest** (11%) — Per-stock FII + DII holding trend from `NseDataService.fetchShareholdingHistory()` (revised 2026-04-19). Scores FII change ±15/25 pts, DII change ±3/10 pts, both-on-same-side ±5 confluence bonus, sector-flow tilt ±5. Previous sector-flow-only implementation scored ~all stocks at 40 due to sparse NSE bulk-deal data + sector-taxonomy mismatch — per-dimension IC surfaced the zero-variance. Promoter pledge is deliberately NOT part of this dimension (lives in the Insider Activity bonus to avoid double-counting).
7. **Financial Quality** (16%) — Balance-sheet & cash-flow depth via `NseDataService.analyzeFinancialQuality()`: interest coverage (operating profit / finance cost), cash-flow-to-profit ratio (profit + depreciation) / profit, net-margin level & trend, earnings consistency, promoter pledge %. Verdicts: `HIGH_QUALITY` / `DECENT` / `AVERAGE` / `WEAK` / `HIGH_RISK`.

**Post-Composite Bonus Adjustments**:
- **Earnings Growth Bonus** (up to +8 pts) — YoY revenue/profit growth verdict from NSE quarterly results via `NseDataService.analyzeEarningsGrowth()`. STRONG_GROWTH +8, MODERATE_GROWTH +4, DECLINING -5. Extra +3 if accelerating, +2 if net margin >15%.
- **Insider Activity Bonus** (up to +5 pts) — Promoter/FII shareholding changes from NSE via `NseDataService.fetchShareholdingHistory()`. STRONG_BUY +5, BUY +2, SELL -2, STRONG_SELL -5. Extra -3 if pledge >20%, +2 if FII increasing.
- **Analyst Signal Bonus** (up to ±5 pts) — Aggregate from `AnalystSignalService`: earnings trend-break (latest quarter vs 3-quarter linear projection, ±7 weight) + brokerage upgrade/downgrade flow from last 7d news (±3 weight). Bounded to -10..+10 then half-scaled to ±5 for the composite to avoid news-noise dominance. SPEC §24.
- **Wealth-Signal Bonus** (−8..+10 pts, SPEC §12.7) — Long-term compounding quality from `NseDataService.analyzeWealthSignals()`: gross-margin trend (EXPANDING +3 / CONTRACTING −3, pricing-power proxy; null for banks), earnings-growth consistency (0-100: ≥70 +3 / <30 −2, rewards steady over lumpy), delivery % (STRONG_HANDS +2 / SPECULATIVE −1, genuine accumulation vs churn), PEG = PE/profit-growth% (<1 +2 / >2 −2). Kept a bonus (not a 9th weighted dimension) to avoid re-validating the weight blend — promote once per-dimension IC validates it. Persisted on `multibagger_scores` columns `gross_margin_percent`, `gross_margin_trend`, `peg_ratio`, `delivery_percent`, `earnings_consistency_score` (all nullable wrappers). Surfaced in Deep Research dimension 19 + holdings email "Fundamental Wealth Signals" section. **COGS capture**: `QuarterlyResult.cogs`/`grossMargin` now retain raw-material + purchases + inventory-change before they collapse into `expenses`.
- **Capital-Efficiency Bonus** (−10..+12 pts, SPEC §12.8) — The balance-sheet wealth metrics from each company's **latest annual Ind-AS XBRL** on NSE, via `NseDataService.analyzeCapitalEfficiency(symbol, industryHint)` (which calls `fetchAnnualFinancials()`). **ROCE** = (PBT+FinanceCosts)/(Equity+TotalBorrowings), **ROE** = NetProfit/Equity, **Debt-to-Equity** = TotalBorrowings/Equity, **cash conversion** = real OperatingCashFlow/NetProfit (not the profit+depreciation proxy). Bonus: ROCE ≥20 +5 / ≥15 +3 / <10 −3; ROE ≥18 +3 / <8 −2; D/E ≤0.3 +2 / >2 −4; cash-conv ≥0.8 +2 / <0.5 −2 — widest band because these are the strongest signals. **Banks/financials** are parsed from a separate **`BANKING_*.xml`** taxonomy (vs `INDAS_*.xml` for non-financials — detected by XBRL filename): different element names (`Capital`+`ReservesAndSurplus` for equity, `ProfitLossForThePeriod` for net profit, total `Assets`). For financials, ROCE & D/E are **not computed** (null, verdict `NA_FINANCIAL` — ROCE on an equity-only base is misleading for them); instead **ROE** and **ROA** (net profit ÷ total assets — the headline metric, ROA ≥1.5 +3 / <0.8 −3) are used. Detection: **banks** via `bs.isBanking()` (BANKING taxonomy, authoritative); **NBFCs/housing-finance** file under INDAS so they need the industry hint (`bank|financ|nbfc|insur|holding`) — supplied by the screener (valuation.getIndustry()), holdings report (h.getIndustry()), and the endpoint (resolves via StockValuationService). **Insurers**: NSE's financial-results feed returns empty for ALL insurers (data wall, not parsing) — they report under IRDAI format; detected by industry and return `NO_DATA` with an insurer-specific reason. Validated: HDFCBANK ROE 14.1%/ROA 1.59%; BAJFINANCE ROE 18.8%/ROA 3.85% (ROCE/D-E suppressed); SBILIFE NO_DATA (insurer). Data source (B-017, migrated 2026-05-24): `/api/integrated-filing-results?index=equities&symbol=X&period=Quarterly` → latest **March** "Integrated Filing- Financials" record (Consolidated preferred) → `INTEGRATED_FILING_INDAS`/`_BANKING` XBRL from nsearchives (reuses pledge-XBRL plumbing), cached 7 days. The old `corporates-financial-results`/`results-comparision` endpoints froze at Dec-2024 when NSE migrated to integrated filing. New XBRL is SEBI **`in-capmkt`** namespace (identical Ind-AS local names → namespace-agnostic parser unchanged) with **reliable context dates** (OneD=89d quarter, FourD=364d year, OneI=year-end instant), so the span-based resolvers (`resolveQuarterContext`/`resolveAnnualContext`/`resolveLatestInstantContext`) are exact; banking detected by `INTEGRATED_FILING_BANKING` filename. Values are in **rupees** (`crore()`=÷1e7). Persisted on `multibagger_scores`: `roe_percent`, `roce_percent`, `roa_percent`, `debt_to_equity`, `cash_conversion_ratio`, `capital_efficiency_verdict`. Surfaced in Deep Research dimension 20, holdings email "Capital Efficiency" section (incl. ROA column), and `GET /api/research/capital-efficiency/{symbol}`. Validated: RIL ROE 8.5%/ROCE 10.2%/D-E 0.35/cash-conv 2.0×; TCS ROCE 68.7% (compounder); HDFCBANK ROE 14.1%/ROA 1.59%.
- **HIGH_RISK cap** — any stock with financial-quality verdict `HIGH_RISK` has its composite hard-capped at 54, so structurally fragile balance sheets can never cross the 65-point recommendation threshold. Critical flags (loss-making, very high pledge, interest-coverage danger zone, weak cash conversion) force the verdict.

**Verdicts**: STRONG_MULTIBAGGER (80+), POTENTIAL_MULTIBAGGER (65+), WATCHLIST (50+), MONITOR (35+), AVOID (<35)
**Grades**: A+ (85+), A (75+), B+ (65+), B (55+), C+ (45+), C (35+), D (<35)
**Market Cap Bonus**: Small-cap stocks (+5 pts), Large-cap stocks (-3 pts)

**Integrations**:
- **Morning Briefing Email**: Top 5 multibagger candidates shown in daily 9:30 AM email
- **Holdings Report**: Multibagger assessment section added to daily holdings analysis email
- **Standalone Weekly Report**: Full screening report emailed every Saturday 9:00 AM

**Schedule**:
- Daily screening: 2:00 PM IST (weekdays)
- Weekly full screening: Saturday 8:00 AM IST
- Weekly email report: Saturday 9:00 AM IST

**Files**: [MultibaggerScreenerService.java](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java), [MultibaggerReportService.java](src/main/java/com/example/trading/multibagger/MultibaggerReportService.java), [MultibaggerScheduler.java](src/main/java/com/example/trading/multibagger/MultibaggerScheduler.java), [MultibaggerConfig.java](src/main/java/com/example/trading/multibagger/MultibaggerConfig.java), [MultibaggerController.java](src/main/java/com/example/trading/api/MultibaggerController.java)

**Database**: `multibagger_scores` table (persists scores over time for trend tracking)

**Manual triggers**:
- `POST /api/multibagger/screen` — Run full screening
- `POST /api/multibagger/report` — Send email report
- `GET /api/multibagger/screen/NSE:RELIANCE` — Screen single stock

### 7. Recommendation Accuracy Tracking (SPEC.md §23)

Closes the feedback loop on the system's own picks. Each scoring engine records every pick that crosses its recommendation threshold, and a daily scheduler measures realized returns at fixed horizons (30 / 90 / 180 / 365 days) so hit rate, mean return, excess return vs Nifty, and score-return Information Coefficient can be computed per engine.

**Sources captured** (Phase 1):
- `MULTIBAGGER` — composite score ≥ 65 (hooked inside `persistScoresToDatabase()` in [MultibaggerScreenerService.java](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java))
- `QUANT_DISCOVERY` — discovery score ≥ 60 (hooked at end of `runDiscoveryScan()` in [QuantitativeDiscoveryService.java](src/main/java/com/example/trading/scanner/QuantitativeDiscoveryService.java))
- `SECTOR_REVERSAL` — upside score ≥ 65 (hooked inside `saveToDatabase()` in [SectorReversalScheduler.java](src/main/java/com/example/trading/scanner/sector/SectorReversalScheduler.java))
- `StockResearchService` deliberately **not** included: AI prose output makes verdict extraction unreliable. Phase 2 once output is structured.

**Schedulers** (both inside the SPEC.md §3.4 market-hours window):
- **15:22 IST MON-FRI** — [RecommendationOutcomeScheduler](src/main/java/com/example/trading/intelligence/recommendation/RecommendationOutcomeScheduler.java) — updates realized-return outcomes for picks hitting their horizon anniversary. Uses `MarketDataService.getCurrentPrice()`; 1-minute precision is immaterial over 30+ day horizons.
- **15:25 IST FRI** — [RecommendationAccuracyReportService](src/main/java/com/example/trading/intelligence/recommendation/RecommendationAccuracyReportService.java) — beginner-friendly email with plain-English glosses for hit rate, excess return, IC.

**Metrics computed** (per source × horizon):
- Hit rate %, mean return %, mean excess return % vs Nifty 50
- Target-hit rate %, stop-loss-hit rate %
- **Information Coefficient** (Pearson correlation between score and realized return) — null when n < 3; >= +0.10 is the conventional "useful signal" threshold.

**Data model** (Hibernate auto-creates via `ddl-auto=update`; no migration runner change):
- `recommendations` — one row per (symbol, source, issuedDate), upsert on re-run
- `recommendation_outcomes` — one row per (recommendationId, horizonDays)

**Per-dimension IC (all 3 engines — 2026-05-24)** — `RecommendationAccuracyService.computeDimensionIC(source, horizonDays)` breaks an engine's score open and returns Pearson correlation of each sub-score (plus the composite for reference) against realized return. Realized prices come from `MarketDataService.getRecentCandles`, independent of `recommendation_outcomes`. Sub-scores read from wherever each engine already persists them: MULTIBAGGER (7 dims since 2026-09-03; 8 on older rows) from `multibagger_scores`, SECTOR_REVERSAL (4: MACD/RSI/Volume/Price) from `sector_reversal_signals`, QUANT_DISCOVERY (5: Earnings/Insider/Valuation/Momentum/Volume) from the new `recommendation_dimensions` sidecar. A shared IC core (`computeIcFromSamples`) takes per-source `DimensionSample` lists so the candle-cache + Pearson logic isn't duplicated. **Gotcha**: MULTIBAGGER & SECTOR produce numbers from existing history today; QUANT's sidecar is only populated for picks captured from 2026-05-24 onward (via the `record(...)` overload taking a `Map<String,Integer>` of dims), so its IC rows stay empty until those picks reach the horizon. The no-arg `computeDimensionIC(horizonDays)` overload is kept (defaults to MULTIBAGGER) for back-compat. Rows with null sub-scores are skipped, so per-dimension `sampleSize` varies.

**REST API**:
- `GET /api/accuracy/summary` — full calibration table
- `GET /api/accuracy/by-source/{source}` — slice for MULTIBAGGER / QUANT_DISCOVERY / SECTOR_REVERSAL
- `GET /api/accuracy/by-symbol/{symbol}` — pick history for a single stock
- `GET /api/accuracy/dimension-ic?horizon=90&source=MULTIBAGGER` — per-dimension IC for an engine (`source` defaults to MULTIBAGGER; also QUANT_DISCOVERY / SECTOR_REVERSAL; horizon default 90d, accepts 30/90/180)
- `POST /api/accuracy/refresh-outcomes` — on-demand recompute
- `POST /api/accuracy/report` — send the weekly email now (now includes a "Which scoring dimensions are working?" table with 30d/90d IC columns)

**Files**: [RecommendationEntity.java](src/main/java/com/example/trading/persistence/RecommendationEntity.java), [RecommendationOutcomeEntity.java](src/main/java/com/example/trading/persistence/RecommendationOutcomeEntity.java), [RecommendationDimensionEntity.java](src/main/java/com/example/trading/persistence/RecommendationDimensionEntity.java) + [RecommendationDimensionRepository.java](src/main/java/com/example/trading/persistence/RecommendationDimensionRepository.java) (QUANT sub-score sidecar), [RecommendationTracker.java](src/main/java/com/example/trading/intelligence/recommendation/RecommendationTracker.java), [RecommendationOutcomeScheduler.java](src/main/java/com/example/trading/intelligence/recommendation/RecommendationOutcomeScheduler.java), [RecommendationAccuracyService.java](src/main/java/com/example/trading/intelligence/recommendation/RecommendationAccuracyService.java), [RecommendationAccuracyReportService.java](src/main/java/com/example/trading/intelligence/recommendation/RecommendationAccuracyReportService.java), [RecommendationAccuracyController.java](src/main/java/com/example/trading/api/RecommendationAccuracyController.java)

**Out of scope (Phase 2)**: adaptive weight feedback (the actual auto-re-weighting from IC — still deferred per SPEC §25.5 until ≥3 months of outcome data); survivorship-bias correction; sector / market-cap slices; `StockResearchService` as a source. Explicit non-goal: measure first, tune later. *(Per-dimension IC for QUANT_DISCOVERY & SECTOR_REVERSAL — formerly here — shipped 2026-05-24.)*

### 8. Financial Quality Dimension (SPEC.md §12.5, §6)

8th Multibagger scoring dimension (15% weight), also surfaced as a standalone holdings attention item. Computes a balance-sheet / cash-flow quality verdict per stock from existing NSE quarterly filings — no new data source needed; extracts previously-ignored `financeCost`, `depreciation`, `tax`, `totalExpenses` fields from the already-fetched quarterly JSON.

**Signals computed** by `NseDataService.analyzeFinancialQuality()`:
- **Interest coverage** = Operating profit / Finance cost (>3 healthy, <1.5 stressed)
- **OCF-to-profit ratio** ≈ (profit + depreciation) / profit (>=1 healthy, <0.7 accounting-quality concern)
- **Net margin & trend** (from existing EarningsGrowthData)
- **Promoter pledge %** (from existing ShareholdingHistory)
- **Growth consistency** (via earnings verdict)

**Verdicts**: `HIGH_QUALITY` (80+) / `DECENT` (60+) / `AVERAGE` (40+) / `WEAK` (20+) / `HIGH_RISK` (<20 OR critical-flag triggered). Critical flags (loss-making, pledge >50%, interest-cover <1.5, OCF/profit <0.7) force HIGH_RISK regardless of numeric score.

**HIGH_RISK composite cap**: any multibagger composite from a HIGH_RISK stock is hard-capped at 54, so structurally fragile balance sheets can never cross the 65-point recommendation threshold. Also caps QUANT_DISCOVERY at 49.

**Integrations**:
- Multibagger 8th dimension (15% weight, rebalanced other dims 2026-04-19)
- Persisted on `multibagger_scores` columns: `financial_quality_score` (nullable Integer), `financial_quality_verdict`, `interest_coverage`, `ocf_to_profit_ratio`, `promoter_pledge_percent`
- Deep Research dimension 16: "FINANCIAL QUALITY" block in AI prompt
- Quantitative Discovery: `DiscoveredOpportunity.financialQualityScore` + verdict
- Holdings report: new "Balance-Sheet Attention Items" section flags any WEAK / HIGH_RISK holding

**Files**: [NseDataService.java](src/main/java/com/example/trading/ai/NseDataService.java) (`analyzeFinancialQuality`, `FinancialQualityData`), [MultibaggerScreenerService.java](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java) (dimension integration + HIGH_RISK cap), [HoldingsReportService.java](src/main/java/com/example/trading/holdings/HoldingsReportService.java) (`buildFinancialQualityAttention`), [MultibaggerScoreEntity.java](src/main/java/com/example/trading/persistence/MultibaggerScoreEntity.java) (5 new columns)

**Known limitation**: NSE's quarterly JSON coverage varies per company — expect ~70–90% on debt fields, ~60–80% on cash-flow fields. Missing data scores as neutral 50 (never penalizes a stock for NSE's publishing gaps). The `FinancialQualityData` DTO exposes `debtDataAvailable` / `cashFlowDataAvailable` booleans so callers can gate downstream logic.

### 9. Intrinsic Valuation / Reverse DCF (SPEC.md §12.5)

Reverse-DCF sanity check: takes the current market cap as given, solves for the growth rate that would justify today's price, compares against the company's actual historical growth. Output is an **expectation gap** (implied − historical CAGR) — not a price target.

**Fixed model parameters**:
- Discount rate: **12%** (Indian equity cost-of-capital baseline)
- Terminal growth: **4%** (India long-run nominal GDP floor)
- Forecast window: **10 years**
- FCF proxy: `profit + depreciation` (crude but computable from existing NSE quarterly data)
- Solver: bisection in `[-50%, +60%]`, 60 iterations, ₹1 cr tolerance
- Sensitivity band: ±200 bps on discount rate

**Verdicts**: `DEEPLY_UNDERVALUED` (implied growth < −5%) / `UNDERVALUED` (gap < −5pp vs historical) / `FAIRLY_VALUED` (gap within ±5pp) / `EXPENSIVE` (gap 5–12pp) / `EXTREMELY_EXPENSIVE` (gap >12pp or implied >30%/yr) / `NOT_APPLICABLE` (loss-making — DCF doesn't apply) / `INSUFFICIENT_DATA`.

**Integrations**:
- Multibagger Valuation dimension (13% weight) is blended **50/50** PE-deviation + DCF-verdict-score. `INSUFFICIENT_DATA`/`NOT_APPLICABLE` falls back to PE-only.
- Quantitative Discovery: HIGH_RISK DCF caps discovery score at 49.
- Deep Research dimension 17: "INTRINSIC VALUATION" block in AI prompt with caveat that this is a sanity check, not a price target.
- Persisted on `multibagger_scores`: `dcf_verdict`, `dcf_implied_growth_percent`, `dcf_historical_growth_percent`, `dcf_expectation_gap_percent` (all nullable).
- Endpoint: `GET /api/research/valuation/{symbol}` returns full DTO + methodology note.

**Known limitation (documented to users)**: the 10-year window + 4% terminal growth **systematically under-values long-duration compounders** (IT services, platforms, pharma) — their EXPENSIVE verdict should be read skeptically. Banks, insurers, commodity cyclicals need residual-income or through-cycle models — not currently distinguished. Both flagged explicitly in service Javadoc, DTO `caveat` field, AI system prompt, SPEC §12.5, and endpoint methodology note.

**Files**: [IntrinsicValuationService.java](src/main/java/com/example/trading/ai/IntrinsicValuationService.java), [StockResearchController.java](src/main/java/com/example/trading/api/StockResearchController.java) (endpoint), integration in [MultibaggerScreenerService.java](src/main/java/com/example/trading/multibagger/MultibaggerScreenerService.java) (`scoreFromDcfVerdict`) and [QuantitativeDiscoveryService.java](src/main/java/com/example/trading/scanner/QuantitativeDiscoveryService.java).

### 10. Analyst Signal (Trend-Break + Brokerage Actions) (SPEC.md §24)

Two honest proxies for paid analyst consensus (which we don't have access to):

**(a) Earnings Trend-Break** (`NseDataService.analyzeEarningsTrendBreak`):
- Linear regression on 3 quarters preceding the latest, projects expected Q0, compares against actual
- Profit surprise is primary; revenue fallback when profit is flat/loss-making
- Verdicts: `BIG_POSITIVE_BREAK` (>+30%) / `POSITIVE_BREAK` (+15 to +30%) / `IN_LINE` (±15%) / `NEGATIVE_BREAK` (−15 to −30%) / `BIG_NEGATIVE_BREAK` (<−30%)

**(b) Brokerage Action Signal** (`StockNewsService.detectBrokerageActions`):
- Scans last 7 days of Google News RSS for upgrade/downgrade patterns
- **Strict filter** (cuts false positives): headline must match upgrade/downgrade verb AND either name a brokerage from a curated list (~40 major Indian sell-side firms: Motilal, Nomura, CLSA, Jefferies, Morgan Stanley, Kotak, etc.) OR contain an explicit "target Rs X" amount
- Verdicts: `POSITIVE_FLOW` (net ≥ +2) / `MIXED_POSITIVE` (+1) / `NEUTRAL` (0) / `MIXED_NEGATIVE` / `NEGATIVE_FLOW` / `NO_COVERAGE`

**Aggregator** (`AnalystSignalService`): combines both into `[-10, +10]` score — trend-break contributes ±7, brokerage flow ±3. Aggregate verdict: `STRONG_POSITIVE` / `POSITIVE` / `NEUTRAL` / `NEGATIVE` / `STRONG_NEGATIVE`.

**Integrations**:
- Multibagger post-composite bonus: ±5 points (half-scaled from aggregate to keep news-noise from dominating)
- Deep Research dimension 18: "ANALYST SIGNAL" block in AI prompt
- Holdings report: **superseded 2026-09-17 by SPEC §50.** The "Earnings Trend-Break Alerts" section (live NSE fetch per holding, negative breaks only, no dedup) was replaced by "Latest Quarterly Results", which reads the stored ledger, makes no network calls, shows four checks rather than one, and flags a result as new exactly once. The trend-break itself survives as one of those four checks and as the `AnalystSignalService` bonus below; its threshold table now lives in the shared `earnings/TrendBreak` class, which `NseDataService` delegates to (Gotcha 85 — two copies of one rule table are free to drift)
- Endpoint: `GET /api/research/analyst/{symbol}`

**Known limitations (surfaced everywhere the signal appears)**:
1. Trend-break is **not** analyst consensus — it's a deviation-from-own-trend proxy
2. Keyword-matching news is noisy — expect ~30% miss rate + occasional false positives
3. Both are **supporting evidence** only, never a sole trigger

**Files**: [NseDataService.java](src/main/java/com/example/trading/ai/NseDataService.java) (`analyzeEarningsTrendBreak`, `EarningsTrendBreakData`), [StockNewsService.java](src/main/java/com/example/trading/ai/StockNewsService.java) (`detectBrokerageActions`, `BrokerageActionData`, BROKERAGES list + regex patterns), [AnalystSignalService.java](src/main/java/com/example/trading/ai/AnalystSignalService.java).

### 14. Self-Learning Substrate — shadow weights, walk-forward, promotion gate (SPEC §38.7-§38.11)

Answers "can the app learn to improve the accuracy of its picks?" — **yes, and the honest form of
that is slow.** Six weight vectors are scored on every screening run (the live one plus five
pre-registered alternatives), a walk-forward harness evaluates them out of sample block by block,
and a mechanical gate decides whether any of them may replace the live weights. **Nothing here
changes a score or adjusts a weight.**

**First live run (2026-09-05)**: 104 screening dates back-filled, 153,552 shadow rows. At a 30-day
horizon that is **3 independent periods**; at 90 days it is **1**. The live vector's own rank
Information Coefficient was 0.028 (t=0.61) and the best challenger led by 0.008 (t=0.47) against
9.93 required. Every variant was refused, at both horizons, and the harness flagged that two
scoring versions are pooled in the sample. That refusal is the feature working, not failing.

**The three things most likely to be got wrong when touching this** are Gotchas 91-93. In short:
the variant set is pre-registered and every addition permanently raises the bar for all of them;
`independentPeriods` is the sample size and the row count is not; and `passedExceptStability` — not
`eligible` — is what the next review reads, or the consecutive-review requirement can never be met.

**Files**: [WeightVariant.java](src/main/java/com/example/trading/learning/WeightVariant.java) (pure),
[WeightVariantRegistry.java](src/main/java/com/example/trading/learning/WeightVariantRegistry.java),
[ShadowCompositeService.java](src/main/java/com/example/trading/learning/ShadowCompositeService.java),
[Statistics.java](src/main/java/com/example/trading/learning/validation/Statistics.java) (pure),
[DailyCandleCache.java](src/main/java/com/example/trading/learning/validation/DailyCandleCache.java),
[WalkForwardHarness.java](src/main/java/com/example/trading/learning/validation/WalkForwardHarness.java),
[PromotionGate.java](src/main/java/com/example/trading/learning/validation/PromotionGate.java) (pure),
[WeightReviewService.java](src/main/java/com/example/trading/learning/validation/WeightReviewService.java),
[LearningController.java](src/main/java/com/example/trading/learning/LearningController.java).

### 12. Core Holdings — the "never sell" tier (SPEC.md §35)

Combines the per-holding quality evidence the app already computed but never joined, and answers
*"which stock should I never sell"* directly. Seven hard gates decide the tier; a five-component
durability score ranks within it; a behavioural overlay decides what the emails say.

**The three things most likely to be got wrong when touching this:**

1. **`GateStatus.PASS_NO_DATA` is not `PASS`.** It passes the fail/pass decision (absence of
   evidence is not a flag) but never counts toward the five-gate quorum. Collapse the two and a
   holding with no forensic history, no insider filings and no conviction record reaches CORE on
   two real gates. See Gotcha 68.
2. **`suppress-technical-exits` is off and the observation line is the point.** It ships `false`
   because switching it on removes a risk control (Gotcha 42's asymmetry). Flip it only after the
   2026-11-30 review of `holding_classification.observed_alerts`. See Gotcha 69.
3. **The dedup contract is mode-specific.** Observation mode uses the ordinary path and *does*
   claim dedup keys; suppression mode uses `evaluateHolding(h, false)` and claims a key only for
   what it sends. See Gotcha 70.

**Hysteresis is asymmetric** — two Friday anchors to promote or soft-demote, but a forensic flag,
auditor problem, HIGH_RISK, BROKEN or STRONG_DISTRIBUTION demotes the same day. A holding that has
just tripped a red flag must not keep its exits suppressed for a fortnight.

**Durability is null below three measured components**, and D5 scores only drawdown episodes that
closed ≥ 18 months ago — the fall a holding is in today is described, never scored (Gotcha 71).

**Files**: [CoreHoldingService.java](src/main/java/com/example/trading/portfolio/core/CoreHoldingService.java) (pure gates),
[DurabilityScorer.java](src/main/java/com/example/trading/portfolio/core/DurabilityScorer.java) (pure D1-D5),
[CoreHysteresis.java](src/main/java/com/example/trading/portfolio/core/CoreHysteresis.java) (pure),
[CoreClassificationService.java](src/main/java/com/example/trading/portfolio/core/CoreClassificationService.java) (the only class here touching a repo or the broker),
[CoreOverlayService.java](src/main/java/com/example/trading/portfolio/core/CoreOverlayService.java),
[CoreHoldingController.java](src/main/java/com/example/trading/portfolio/core/CoreHoldingController.java),
[CoreHoldingSnapshotEntity.java](src/main/java/com/example/trading/portfolio/core/CoreHoldingSnapshotEntity.java).
Plan and review record: [CORE_HOLDINGS_RECYCLING_PLAN.md](CORE_HOLDINGS_RECYCLING_PLAN.md).

**Deferred**: the Capital Recycling half (SPEC §36 / plan §5) is not built. Its uplift figures rest
on one 4-month window that Gotcha 26 explicitly calls insufficient for discarding on, and recycling
discards — paying real tax to do it. Next evidence review **2026-11-30**.

### 13. Watchlist Tracking — "how has it done since I noted it, and is it still a good time to buy?" (SPEC.md §37)

The `watchlist` table is the **source of truth** (2026-08-27); `watchlist.symbols` in application.yml is a
seed list read once by `WatchlistSeedRunner` (never resurrects a row the investor removed). Each
tracked stock records `addedOn`, `priceAtAdd`, `niftyAtAdd` and a note; the dashboard page
`watchlist.html` shows date added, return since added, vs Nifty, sector, **two scores** (Quality =
multibagger composite from the latest `multibagger_scores` row; Timing = `watchlist.overall_score`),
the trend with a 90-day sparkline, and a deterministic verdict from `BuyTimingVerdict` (15 ordered
rules, SPEC §37.3, pinned by `BuyTimingVerdictTest`): `BUY_NOW / ACCUMULATE / WAIT_FOR_PULLBACK /
HOLD_OFF / AVOID / NOT_MEASURED`.

**The three things most likely to be got wrong when touching this:**

1. **Page load is one DB-only request** (`GET /api/watchlist/items`). Returns, sparklines and "Nifty
   now" come from `watchlist_daily_snapshot`, written by the 15:00 fire of the 11/13/15 job and
   backfilled for free from the candles a manual add/refresh already fetches. Never put a Kite call
   in `WatchlistTrackingService.buildView`.
2. **Seeded rows have no `priceAtAdd`, so their return is "not measured" — never 0.** Same for the
   verdict: an unmeasured input never triggers a rule (a missing RSI is not "not overbought").
3. **Quality is never computed on add or on page load.** It arrives with the 14:00 screening, or
   never if the symbol is outside the universe — `inUniverse` says which, and the UI renders "never
   screened" rather than a 50. `refresh?quality=true` uses `evaluateSingleStock` (Gotcha 50) and
   stores an `adhocQualityScore` that is labelled ad-hoc and never mixed with the screening row.

`static/js/watch-button.js` puts a "+ Watch" button on the screener and every discovery table
(`loadWatchedSet()` is one DB-only GET at load; the click is the add write). Add and refresh are the
**first sanctioned UI writes** (SPEC §27.8 carve-out) via `api.js post()`,
which throws the server's `reason` so the 14:55 crunch refusal (409) and the "Kite returned no price"
refusal (422) reach the user verbatim. `WatchlistAnalysisService.analyzeSymbol` overwrites every
analysis field each run and must never touch the membership fields.

**Files**: [WatchlistTrackingService.java](src/main/java/com/example/trading/watchlist/WatchlistTrackingService.java),
[BuyTimingVerdict.java](src/main/java/com/example/trading/watchlist/BuyTimingVerdict.java) (pure),
[WatchlistReturnMath.java](src/main/java/com/example/trading/watchlist/WatchlistReturnMath.java) (pure),
[WatchlistSeedRunner.java](src/main/java/com/example/trading/watchlist/WatchlistSeedRunner.java),
[WatchlistSnapshotEntity.java](src/main/java/com/example/trading/watchlist/WatchlistSnapshotEntity.java),
[WatchlistController.java](src/main/java/com/example/trading/watchlist/WatchlistController.java),
[page-watchlist.js](src/main/resources/static/js/page-watchlist.js).

### 11. Thesis Drift Alerts (Score-Decay on Holdings) (SPEC.md §6.2)

Flags owned holdings whose Multibagger composite score has dropped meaningfully in the last 30 or 60 days — early thesis-breakdown warning, complements SPEC §6 purchase-drift (sibling: drift-from-recent, not drift-from-purchase).

**Detection** (`HoldingsDecayService.detectDecay`):
- Loads per-symbol score history via `MultibaggerScoreRepository.findTrend`
- Picks the nearest screening entry within ±10 days of the 30d and 60d windows
- Classifies:
  - `INTACT` — 30d delta ≥ −5
  - `WATCH` — 30d delta in [−10, −5] OR grade dropped 1 level
  - `DECAYING` — 30d delta ≤ −10 OR grade dropped ≥ 2 levels
  - `BROKEN` — 30d delta ≤ −20 OR 60d delta ≤ −25
  - `STALE` — newest history entry > 14 days old (stock likely removed from universe)
  - `NO_DATA` — holding outside screening universe OR history too sparse to compute 30d/60d delta (reason message distinguishes the two cases)

**Integrations**:
- Holdings report daily + weekly: "Thesis Drift Alerts" section — portfolio-health summary (counts per verdict) + table of non-INTACT holdings worst-first, with P&L context and action guidance
- Endpoint: `GET /api/trading/holdings/decay` returns full array sorted worst-first

**Real finding from first live run (2026-04-19)**: BHARTIARTL flagged `WATCH` (score 31→25 over 30d) while +15.7% on P&L — exactly the "thesis decay front-runs P&L" signal the feature was built to catch.

**Out of scope (Phase 2)**: persistent alerted-state (same holding re-alerted each report), morning-briefing integration, score-decay for non-holdings (use `GET /api/multibagger/trend/{symbol}` for that). *(Cross-exchange matching — formerly here — shipped 2026-08-28 via `SymbolVariants`; a BSE-held position now reads its NSE screening history and the alert records which symbol answered.)*

**Files**: [HoldingsDecayService.java](src/main/java/com/example/trading/holdings/HoldingsDecayService.java), integration in [HoldingsReportService.java](src/main/java/com/example/trading/holdings/HoldingsReportService.java) (`buildThesisDriftSection`), endpoint on [TradingController.java](src/main/java/com/example/trading/api/TradingController.java).

## Expert Trading Enhancements

### Pre-Trade Analysis Agent (Phase 4)

Every signal passes through a "Senior Trader" AI agent before execution. Scores trades across 8 dimensions (100 points, minimum 55 to trade):

1. **Trend Alignment** (0-15) — Signal vs market regime direction + EMA50 position
2. **Volume Quality** (0-15) — Current volume vs 20-candle average ratio
3. **Risk/Reward** (0-15) — R:R ratio + absolute risk percentage validation
4. **Market Context** (0-10) — Time of day, VIX level, ADX trend strength
5. **Price Structure** (0-10) — Support/resistance proximity + higher-low/lower-high patterns
6. **Momentum** (0-10) — Directional candle count + accelerating body sizes
7. **Signal Confluence** (0-15) — Confidence level + strategy type quality
8. **Risk Management** (0-10) — Consecutive losses, day-of-week bias, time risk

**File**: [PreTradeAnalysisAgent.java](src/main/java/com/example/trading/intelligence/PreTradeAnalysisAgent.java)

### Strategy-Level Expert Filters

All 4 active strategies enhanced with:
- **ADX Filter**: EMA/ORB require ADX ≥ 20, VWAP requires ADX ≥ 18 (blocks choppy markets)
- **Improved R:R**: EMA 2.5:1, VWAP 2.5:1, ORB 3:1, RSI 2.5:1
- **Stricter Volume**: EMA 1.0x, VWAP 1.2x, ORB 0.5-0.8x (dynamic), RSI 0.8x
- **ORB Window**: Tightened to 10:30 AM (was 11:00). Breakout buffer 0.5% (was 0.3%)
- **EMA Trend Filter**: Tightened to 0.08% slope (was 0.05%)

### Risk Management Enhancements

- **Consecutive Loss Circuit Breaker**: Trading pauses after 3 consecutive losses, resumes on next profit or daily reset
- **Dynamic Cooldown**: Loss-proportional cooldowns (5 min for <0.3%, 10 min for 0.3-0.7%, 20 min for >0.7%)
- **Regime-Aware Lunch Hour**: RSI gets only 30% of lunch penalty (mean-reversion works in chop), trend strategies get full penalty
- **Immediate Execution for Momentum**: High-confidence signals (≥0.88) in trending regimes skip pullback wait; very high confidence (≥0.90) always skips pullback
- **Structure-Aware SL**: Uses recent swing high/low for SL placement instead of arbitrary percentages, with 2.5% max distance cap

### Critical Bug Fixes Applied

1. **SL Overwrite Fix**: OrderExecutionService no longer overwrites regime-aware SL with hardcoded 0.5% — preserves the calculated values and only shifts proportionally for limit order price differences
2. **Pullback Queue Fix**: SignalConfirmationService no longer queues ALL signals — high-confidence + trending signals execute immediately
3. **VIX Default Fix**: MarketRegimeDetector defaults VIX to 22.0 (cautious) instead of 15.0 when data unavailable
4. **Confidence Weighting Fix**: Changed from pure multiplication (`confidence * weight`) to blended approach (`confidence * (0.5 + 0.5 * weight)`) in StrategyWeightCalculator to prevent signal crushing (e.g., 0.82 * 0.60 = 0.49 became 0.82 * 0.80 = 0.66)
5. **Lunch Hour Boost Bug**: MarketRegimeConfig had `lunchHourConfidenceBoost = 15.0` (percentage) added directly to decimal threshold (0.62 + 15.0 = 15.62, blocking all lunch trades). Fixed to `0.15` (decimal form)
6. **Benchmark Symbol Expiry**: Must update NFO futures benchmark symbol monthly (e.g., `NFO:NIFTY26JANFUT` → `NFO:NIFTY26MARFUT`). Expired symbol causes ADX=0, breaking regime detection
7. **HIGH_VOLATILITY Threshold**: Lowered from 0.70 to 0.62 to work with blended weighting formula
8. **Paper Trade Position Sync Kill Bug**: `PositionSyncService` was closing paper trade positions after ~60-80 seconds because they don't exist at the broker. Added paper trading mode check to skip broker sync entirely, plus safety check on `PAPER-` tradeId prefix in reconciliation
9. **Institutional Interest Zero-Variance Bug** (discovered 2026-04-19 via per-dimension IC): The 6th Multibagger dimension scored every stock at exactly 40 for 3+ months because its upstream data source (`FiiDiiSectorAnalysisService.analyzeSectorFlows`) depends on sparse NSE bulk/block deals AND uses a sector taxonomy that doesn't match `STOCK_SECTOR_MAP` in `MultibaggerScreenerService`. Fixed by pivoting the scoring to **per-stock FII+DII holding trend** from `NseDataService.fetchShareholdingHistory` (already fetched reliably for the Insider Activity bonus). Also deduped the shareholding fetch (one NSE call per stock instead of two). See SPEC.md §12.5 and §23.2 per-dimension IC finding.
10. **Level-Calibration Denominator Bug**: `targetHitRatePercent` / `stopLossHitRatePercent` in `RecommendationAccuracyService.computeFor()` used `total_outcomes` as denominator. MULTIBAGGER picks always have `targetPrice: null` so their outcomes dragged the rate toward zero regardless of how the QUANT_DISCOVERY and SECTOR_REVERSAL engines' real targets performed. Fixed: denominator is now `count of outcomes whose parent recommendation had a non-null target/SL`. Returns null (distinct from 0%) when no picks in the cell proposed a level. New `picksWithTarget` / `picksWithStopLoss` / `targetCoveragePercent` / `stopLossCoveragePercent` fields on `SourceHorizonStats` surface the denominator context.
11. **Integer-Not-Null Migration Bug** (lesson, both directions): Adding a primitive `int` field to an existing populated JPA entity fails Hibernate `ddl-auto=update` because it tries to add the column as `NOT NULL` to rows that have no value. Always use the wrapper type (`Integer`) when extending an entity whose table already has rows. Learned from `financialQualityScore` on `MultibaggerScoreEntity`. **The reverse also fails (B-026, 2026-08-23)**: changing an existing `int` field to `Integer` does NOT drop the column's existing `NOT NULL` — `ddl-auto=update` adds columns but never relaxes constraints. Postgres then rejects every row with a null, which silently dropped 210 of 288 screening rows. Add an explicit `ALTER COLUMN ... DROP NOT NULL` to `SchemaMigrationRunner` whenever a field becomes nullable, and **always log persisted-vs-attempted counts together** — a bare success count cannot reveal a partial failure.

### Files Modified/Created

- [PreTradeAnalysisAgent.java](src/main/java/com/example/trading/intelligence/PreTradeAnalysisAgent.java) — NEW: AI pre-trade scoring
- [StructureAwareSL.java](src/main/java/com/example/trading/strategy/StructureAwareSL.java) — NEW: Swing-based SL calculator
- [OrderExecutionService.java](src/main/java/com/example/trading/execution/OrderExecutionService.java) — FIX: SL preservation
- [SignalConfirmationService.java](src/main/java/com/example/trading/filters/SignalConfirmationService.java) — FIX: Immediate execution for momentum
- [MarketRegimeDetector.java](src/main/java/com/example/trading/regime/MarketRegimeDetector.java) — FIX: VIX default 22.0
- [EmaCrossoverStrategy.java](src/main/java/com/example/trading/strategy/EmaCrossoverStrategy.java) — ADX filter, tighter trend threshold
- [VwapStrategy.java](src/main/java/com/example/trading/strategy/VwapStrategy.java) — ADX filter, better R:R
- [OpeningRangeBreakoutStrategy.java](src/main/java/com/example/trading/strategy/OpeningRangeBreakoutStrategy.java) — ADX filter, tighter window/volume
- [StrategyConfig.java](src/main/java/com/example/trading/strategy/StrategyConfig.java) — Updated parameters
- [RiskManagementService.java](src/main/java/com/example/trading/risk/RiskManagementService.java) — Circuit breaker, dynamic cooldown
- [MarketRegimeFilter.java](src/main/java/com/example/trading/filters/MarketRegimeFilter.java) — Strategy-aware lunch hour
- [TradingScheduler.java](src/main/java/com/example/trading/scheduler/TradingScheduler.java) — PreTradeAgent integration
- [StrategyWeightCalculator.java](src/main/java/com/example/trading/regime/StrategyWeightCalculator.java) — FIX: Blended confidence weighting
- [PositionSyncService.java](src/main/java/com/example/trading/sync/PositionSyncService.java) — FIX: Skip broker sync in paper trading mode
- [MarketRegimeConfig.java](src/main/java/com/example/trading/filters/MarketRegimeConfig.java) — FIX: Lunch hour boost decimal (0.15, not 15.0)

## Common Issues & Solutions

**Issue**: "Could not fetch spot price for NIFTY"
- **Cause**: Using wrong symbol format (NSE:NIFTY instead of NSE:NIFTY 50)
- **Fix**: Use `getIndexSymbol()` helper to map index names

**Issue**: Token expired mid-day
- **Cause**: Manual token refresh needed or auth failure
- **Fix**: Call `KiteAuthService.performFullLogin()` or restart app before 9 AM

**Issue**: All trades hitting stop-loss in trending market
- **Cause**: 0.5% SL too tight for high ADX regime
- **Fix**: Check regime multiplier logic (should be 2.0× for TRENDING, 2.5× for HIGH_VOLATILITY)

**Issue**: RSI signals not executing (volume rejection)
- **Cause**: Using strict volume filter (1.2×) instead of RSI relaxed filter (0.3×)
- **Fix**: Check `marketRegimeFilter.hasRsiVolumeConfirmation()` path in TradingScheduler

**Issue**: BUY signals in downtrend getting stopped out
- **Cause**: EMA50 filter not applied or regime filter disabled
- **Fix**: Ensure price > EMA50 check and counter-trend blocking logic active

**Issue**: FII/DII data shows binary characters (�) instead of JSON
- **Cause**: GZIP decompression not working due to manual Accept-Encoding header
- **Fix**: Remove `Accept-Encoding` header from WebClient request (WebClient auto-decompresses)
- **Verification**: Check logs for `=== RAW NSE FII/DII RESPONSE ===` - should show JSON array

**Issue**: Option chain PCR always shows 1.00 / Kite quotes API returns `{data: {}}`
- **Cause 1**: Symbols sent without `NFO:` exchange prefix (e.g., `NIFTY26FEB25050CE` instead of `NFO:NIFTY26FEB25050CE`)
- **Cause 2**: Manually constructed option symbols don't match Kite's exact tradingsymbols
- **Cause 3**: Wrong expiry date — computed expiry doesn't match actual weekly expiry in Kite instruments
- **Fix**: Use `KiteInstrumentsService.getOptionSymbol()` to look up exact tradingsymbols, `getNearestExpiry()` for correct expiry, and always add `NFO:` prefix before calling quotes API
- **Verification**: Check logs for `Found N tradingsymbols from Kite instruments` and `Data map size: N` (should be > 0)

**Issue**: `DataBufferLimitException` when downloading Kite instruments CSV
- **Cause**: Instruments CSV is ~15-40MB, exceeds WebClient default 256KB buffer
- **Fix**: Set buffer to 50MB: `.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))`

**Issue**: Kite instruments download returns 429 Too Many Requests
- **Cause**: Too many download attempts in short succession (e.g., multiple restarts)
- **Fix**: `KiteInstrumentsService` has 5-minute retry interval protection — wait before retrying

**Issue**: FII/DII values 100x too large or too small
- **Cause**: Unit mismatch - NSE might return Lakhs instead of Crores or vice versa
- **Fix**: Check for `UNIT CONVERSION` log message - automatic conversion triggers for values > 100,000
- **Verification**: Typical daily FII/DII is 10,000-30,000 Crores (not millions)

**Issue**: All signals rejected with "Confidence below threshold" even after weighting
- **Cause**: Pure multiplication weighting crushes confidence (0.82 × 0.60 = 0.49, below any threshold)
- **Fix**: Use blended formula `confidence * (0.5 + 0.5 * weight)` in StrategyWeightCalculator

**Issue**: Benchmark symbol NFO:NIFTY26xxxFUT not resolving
- **Cause**: Monthly futures contract expired
- **Fix**: Update `trading.regime.benchmark-symbol` in application.yml to current month's contract (check Kite for active NFO futures)

**Issue**: Paper trade positions closing after ~60 seconds with small losses
- **Cause**: `PositionSyncService` runs every 2 minutes, checks broker positions, finds paper trades don't exist at broker, marks them CLOSED immediately
- **Fix**: Added paper trading mode skip in `PositionSyncService.syncPositionsFromBroker()` + safety check for `PAPER-` tradeId prefix in `reconcileClosedPositions()`
- **Verification**: Check logs for "Paper trading mode. Skipping broker position sync." instead of "Starting position sync from broker..."

## Package Structure (~240 Java files)

```
com.example.trading/
├── IntradayApplication.java           # Main @SpringBootApplication
├── ai/                 (13 files)     # AI service layer, deep stock research, news, NSE data, peer comparison, discovery, IntrinsicValuationService (reverse DCF), AnalystSignalService (trend-break + brokerage)
├── alerts/             (2 files)      # TargetHitAlertService (daily 15:05 target-hit email) + controller (§26)
├── analyst/            (23 files)     # Analyst target ledger (SPEC §49): who said what, and whether
│                                      # they were right. Four pure classes carry the rules — Brokerages
│                                      # (the one house vocabulary, shared with §24), AnalystTargetParser
│                                      # (headline -> attributed target), HeadlineSubjectResolver
│                                      # (headline -> ticker; it refuses far more readily than it guesses),
│                                      # AnalystTrackRecord (rows -> per-house record, and its refusals).
│                                      # Three services touch the world: capture, outcome measurement, and
│                                      # the DB-only view. Plus config, status enum, coverage holder,
│                                      # scheduler (13:20) and controller. Produces NO verdict on any stock
│                                      # and contributes ZERO points to any score.
├── api/                (6 files)      # REST controllers (TradingController [holdings/research triggers], MultibaggerController, StockResearchController, RecommendationAccuracyController, MarketNewsTriggerController, DashboardController) — BacktestController removed 2026-05-24; SectorScannerController + PerformanceTrackingController 2026-09-03
├── broker/kite/        (5 files)      # Zerodha Kite integration + instruments cache
├── common/             (4 files)      # Exceptions, logging (TradeContext)
├── config/             (3 files)      # Test mode, stock filters, scheduler thread pool (SchedulingConfig)
├── dashboard/          (5 files)      # READ-ONLY UI feed (SPEC §27; column layout §27.9 is client-side only): DashboardController, DashboardService,
│                                    # DashboardDto, ReportPreviewController, package-info (states the contract).
│                                    # Contains NO analysis — composition only; nothing else depends on it.
├── concall/            (3 files)      # Earnings-call transcript extraction + guidance ledger (SPEC §34)
├── fiidii/             (7 files)      # FII/DII activity tracking
├── fundamentals/       (13 files)     # Long-horizon annual history, turnaround detector, forensic screen,
│                                      # CapexCycleService (SPEC §31 prior-year composition) (SPEC §32).
│                                      # Universe backfill (SPEC §32.6): FundamentalsBackfillStatusEntity +
│                                      # Repository, FundamentalsBackfillConfig, FundamentalsBackfillService,
│                                      # FundamentalsBackfillScheduler (11:30 MON-FRI). Two long-horizon
│                                      # lenses, both pure and both worth zero points: CapitalAllocationRecord
│                                      # (§42) and CompoundingPersistence (§43), joined to the DB by
│                                      # LongHorizonRecordService — the only one of the three touching a repo.
├── holdings/           (6 files)      # Portfolio analysis, valuation, exit timing alerts, HoldingsDecayService (thesis-drift detection)
├── insider/            (6 files)      # SEBI PIT / bulk / block disclosure capture + rolling pulse verdict (SPEC §28)
├── integrity/          (1 file)       # DataHealth (SPEC §44) - the pure rule table behind the data-health
│                                    # screen: freshness vs each job's own cron, signal coverage and spread,
│                                    # history depth. No repo, no clock, no I/O; DashboardService composes it.
│                                    # Changes NO score and alerts nothing - it reports.
├── intelligence/       (13 files)     # Market intelligence, news analysis, direction prediction; intelligence/recommendation/ subpackage for RecommendationTracker, RecommendationOutcomeScheduler, RecommendationAccuracyService, RecommendationAccuracyReportService  (PreTradeAnalysisAgent removed 2026-05-24)
├── marketdata/         (2 files)      # Real-time quotes, historical candles
├── multibagger/        (10 files)     # Multibagger screening, scoring, reports, scheduler, UnderDiscoveryService (SPEC §12.10), ScreenerTimingVerdict (§12.11), SuggestedEntry (§12.12), CompoundingQuality (§41 — pure, never enters the composite)
├── universe/           (6 files)      # Dynamic universe expansion funnel + IPO tracker (SPEC §30)
│   universe/ipo/       (9 files)      # IPO pipeline & post-listing tracker (SPEC §45): entity + repository,
│                                      # IpoFeedParser / IpoStructureRead / IpoLockIn / IpoApplicationMath
│                                      # (all pure), IpoTrackingService (the only one touching NSE, Kite or
│                                      # a repo), IpoCaptureScheduler (12:15), IpoController (/api/ipo).
│                                      # Contributes zero points to any score and never says "apply".
├── notification/       (3 files)      # Email service, morning briefing
│   portfolio/core/     (8 files)      # Core Holding Classifier (SPEC §35): gates, durability,
│                                      # asymmetric hysteresis, behavioural overlay, snapshot table
│   portfolio/performance/ (9 files)   # Portfolio truth (SPEC §46): PerformanceMath (pure TWR /
│                                      # drawdown), PortfolioPerformanceService (composes; DB-only),
│                                      # PortfolioSnapshotService (benchmark closes + cash at the
│                                      # 15:00 snapshot, manual backfill), two entities + repos,
│                                      # PerformanceDto, PerformanceController. Zero points to any score.
├── earnings/           (8 files)      # Quarterly result tracking (SPEC §50): what the businesses you
│                                    # own actually reported, kept rather than thrown away. Four pure
│                                    # classes carry the rules - FiscalQuarter (the April-March year),
│                                    # TrendBreak (the shared "did it break its own trend" table, now
│                                    # the single copy - NseDataService delegates), QuarterlyResultRead
│                                    # (the four checks and the three refusals), EarningsCalendar (the
│                                    # next-result WINDOW, never a date). Plus the entity + repository,
│                                    # QuarterlyResultService (the only class touching a repo or NSE),
│                                    # QuarterlyResultCoverage, EarningsConfig and EarningsController.
│                                    # Adds NO scheduler - capture runs inside the 14:00 screening from
│                                    # filings it already fetched - and contributes ZERO points to any
│                                    # score.
├── macro/              (21 files)     # Macro & geopolitical event exposure (SPEC §48). Pure and unit-tested:
│                                    # MacroFactor (21 factors, each a quantity with a direction),
│                                    # MacroExposureMap (the curated rule table, fail-fast at boot),
│                                    # MacroExposureRead (the join), MacroEventDedup, MacroCalendar,
│                                    # MacroKeywordExtractor (the fallback reader), ExtractedEvent/EventBatch.
│                                    # Spring-managed: MacroEventEntity/Repository, MacroConfig,
│                                    # MacroEventExtractor (Spring AI, model-agnostic, ObjectProvider<ChatModel>),
│                                    # MacroIngestService, MacroExposureService, MacroMeasurementService,
│                                    # MacroViewService, MacroReportRenderer, MacroController.
│                                    # The reader NEVER names a company; the map decides who is exposed.
│                                    # Contributes zero points to any score and schedules nothing.
├── learning/           (10 files)     # Learning substrate (SPEC §38). ScoringVersion + registry (provenance stamp),
│                                    # ScreeningCoverage + service (per-signal coverage vector), WeightVariant +
│                                    # WeightVariantRegistry (six pre-registered weightings), ShadowCompositeService,
│                                    # LearningController. Sub-package learning/validation/ (5 files): DailyCandleCache
│                                    # (shared, extracted from RecommendationAccuracyService), Statistics (pure),
│                                    # WalkForwardHarness + WalkForwardReport, PromotionGate (pure), WeightReviewService.
│                                    # Contains NO model, changes NO score and adjusts NO weight — it is what must exist
│                                    # before a weighting question can be answered with evidence rather than with the
│                                    # current quarter's IC panel.
├── persistence/        (33 files)     # JPA entities, repositories (incl. MultibaggerScoreEntity, RecommendationEntity/OutcomeEntity/DimensionEntity, TargetHitEventEntity, PickPerformanceSnapshotEntity; trade/position/signal entities retained but no longer written)
├── risk/               (2 files)      # RiskConfig (capital/risk params, used by SignalTrackingService) + package-info. RiskManagementService removed 2026-05-24
├── scanner/            (4 files)      # Nifty200WatchlistService + quantitative discovery (service, scheduler, report).
│                                    # Breakout scanner + sector/ removed 2026-09-03 (SPEC §39).
├── scheduler/          (4 files)      # MarketHoursService, DataCleanupScheduler, DataCleanupConfig. TradingScheduler removed 2026-05-24
├── strategy/           (2 files)      # StrategyUtils (shared TA helper) + package-info. All strategies/config/signal removed 2026-05-24
│                                    # UI: static/js/long-horizon.js renders §42 and §43 on the stock
│                                    # page (wording and colour only — both rule tables stay in Java).
└── watchlist/          (15 files)     # Watchlist tracking (SPEC §37): entity + snapshot table, WatchlistTrackingService (DB-only read model + add/refresh/remove), BuyTimingVerdict (pure rule table), seed runner, analysis + email + scheduler. DynamicWatchlistService/Scheduler removed 2026-05-24
```
(Removed packages: `backtest/`, `execution/`, `monitoring/`, `position/`, `sync/`, `filters/`; `ml/` and `scanner/sector/ml/` on 2026-08-28; `options/`, `analytics/`, `scanner/sector/` and `regime/` on 2026-09-03.)

## Development Patterns

### Error Handling
- `BrokerException` for broker API errors (retryable via `@Retryable`)
- `@Retryable` with exponential backoff is the standard pattern for broker/HTTP calls (e.g. `KiteBrokerClient`)

### Date/Time Handling
- All market hours in IST: use `MarketHoursService.getMarketZone()` for `ZonedDateTime`
- Historical data formats: `"yyyy-MM-dd HH:mm:ss"` or ISO with timezone
- All `@Scheduled` tasks use `zone = "Asia/Kolkata"` for IST execution

### Number Handling in Market Data
- Kite API returns mixed `Integer`/`Double` types for OHLCV values
- Always use a `toDouble(Object)` helper pattern when reading history map values (never cast directly to `Double`)
- Never cast directly to `Double` - use `((Number) value).doubleValue()` for safety

## Important Constraints

- **Intraday only**: All positions closed by 15:25 IST (no overnight holds)
- **Market hours**: 09:15-15:30 IST weekdays; scheduler auto-stops outside
- **Early signal window**: 9:15-9:30 generates signals but blocks order execution
- **Multiple exchanges**: NSE equity (`NSE:*`), NFO derivatives (`NFO:*`)
- **Zerodha-specific**: Only `KiteBrokerClient` implementation provided (interface is pluggable)
- **PostgreSQL required**: MySQL config commented out in application.yml
- **Java 21**: Record classes, pattern matching used throughout
- **Reactive WebClient**: All HTTP calls use Spring WebFlux (not RestTemplate)
- **Lombok**: `@Slf4j`, `@RequiredArgsConstructor`, `@Data` used throughout - do NOT add manual constructors/getters
- **Scoring-engine tests exist** (added 2026-08-22, now 556 tests): `MultibaggerConfigWeightTest` (weight-sum guard, B-019), `MultibaggerScoreBandsTest` (verdict/grade/market-cap boundaries), `MultibaggerDimensionScoringTest` (per-dimension characterisation + composite renormalisation; its SectorTailwind cases went with that dimension on 2026-09-03 — the defect they documented is quoted in SPEC §39.2), `MultibaggerPercentileRankTest` (rank + scale/shift invariance), `StockValuationServiceTest` (B-018 PE/market-cap arithmetic), `RecommendationAccuracyIcTest` (IC reason codes), `KiteRequestPacingTest` (B-027 rate-limit gate, incl. a 12-thread race), `MultibaggerHighConvictionTest` (tier must never shrink the candidate set), `BuyabilityGuardTest` (SPEC §12.9 — UNKNOWN must never collapse to THIN), `UnderDiscoveryScoreTest` (SPEC §12.10 — quality gate, null-not-zero, renormalisation), `InsiderPulseServiceTest` (SPEC §28 — mode filtering, null-vs-NEUTRAL, future-dated rows), `UniverseExpansionTest` (SPEC §30 — series filtering, base detection, observation-mode defaults), `CapexCycleTest` (SPEC §31 — bank suppression, shrinking-build rejection, the B-034 history fallback, STEADY refusing to quote an unmeasured ratio), `TurnaroundDetectionTest` (SPEC §32.3 — short history refused, missing year unmeasured, all four criteria accounted for), `ForensicScreenTest` (SPEC §32.4 — each flag boundary, auditor escalation, INFO flags scoring zero, "nothing checked" ≠ clean), `FundamentalsImportTest` (SPEC §32.1 — the `Sales Growth %` collision, unparseable cells staying null), `ConcallAnalysisTest` (SPEC §34 — credibility gate, pending never counted, intimation ≠ transcript), `NewSignalShadowModeTest` (Gotcha 30/42 — new bonuses default off, risk controls default on), `CoreHoldingGatesTest` (SPEC §35.2 — the PASS_NO_DATA quorum rule, the 5-vs-4 boundary, critical triggers), `DurabilityScoreTest` (SPEC §35.3 — renormalisation, null below 3 components, and the closed-episode D5 rule that a deeper open drawdown must not move the score), `CoreHysteresisTest` (SPEC §35.6 — two Friday anchors to promote, same-day critical demotion, non-consecutive anchors reset the run), `CoreOverlayTest` (Gotcha 19/69/70 — the mode-specific dedup contract, and that `suppress-technical-exits` ships off), `BuyTimingVerdictTest` (SPEC §37.3 — every rule row, precedence, and that an unmeasured input never fires a rule), `WatchlistReturnMathTest` (a missing leg or a `0.0` price is null, never 0%), `WatchlistSeedTest` (idempotent; a removed row is never resurrected), `WatchlistSymbolsTest` (normalisation incl. the B-013 series suffix), `ForensicScreenTest` (SPEC §32.4 — now also B-066: a bonus recognised without equity data, near-miss ratios that are still raises, a buyback never rewritten), `HoldingsBuyTimingTest` (SPEC §6.5 — SELL is never an add, cross-exchange quality resolution, watchlist deferral, unmeasured-not-zero), `HoldingsDecayRelativeTest` (B-064 — a universe-wide fall is INTACT, an idiosyncratic one still DECAYING, an unmeasured shift is null and falls back to raw with the reason saying so), `ScoringVersionTest` (SPEC §38.1 — stable across runs, sensitive to any weight or shadow-flag change, explicit unknown marker), `ResearchSymbolGuardTest` (B-073 — a retired path under `/api/research` must 404, not fall through to the email-sending catch-all), `ScreeningCoverageTest` (SPEC §38.2/§38.3 — not-applicable leaves the denominator, null coverage is not 0%, a collapsed spread is flagged, an uncomputable one is null not false, and a ratio never gets a 0-100 collapse verdict), `AccumulationModeTest` (SPEC §8.2/§19 — SIGNAL_GATED is refused however well-formed the request, an unknown mode stays a plain bad request rather than collapsing into the policy refusal, and the constant survives for legacy rows; B-077), `CompoundingQualityTest` (SPEC §41 — an unmeasured check is neither a pass nor a fail, not-applicable leaves the denominator, three measured checks is not enough for a non-financial to earn the badge, a HIGH forensic flag outranks every good ratio while MEDIUM and INFO do not, and history length never decides the verdict), `WeightVariantTest` (SPEC §38.8 — renormalisation over measured dimensions, a null score is never a neutral 50, a zero weight and a null score are different things, an unscoreable stock is absent not bottom), `ShadowReconstructionTest` (SPEC §38.8 — a clamp boundary and the 54 hard cap are marked inexact, an honest 54 is not, percentile ties share the best rank, a lone row gets no rank), `StatisticsTest` (SPEC §38.9 — rank correlation bounds the outlier that Pearson lets carry the panel, a constant score is named rather than zeroed, and a spread that is zero only in floating-point residue yields no t-statistic — that last case caught a real defect producing t=1e16 from three identical readings), `PromotionGateTest` (SPEC §38.10 — **refuses on the data shape the system actually has today**, deflation grows with variants tried, the first review fails on stability alone so a run can start, an interrupted run resets, a narrower universe is refused however well it ranks, and automatic adoption stays off), `InsiderPitFilingTest` (B-089 — the PIT V2.0 XBRL mapping, on real BERGEPAINT facts: a promoter's open-market purchase is scoreable and carries its rupee value, a pledge is recorded but never scored, the B-031 future-date guard runs on this path too, an absent transaction type defers to the mode rather than defaulting to BUY, and both eras' date formats parse. It replaces `InsiderCaptureRotationTest`, whose subject — a per-symbol probing budget and its rotation — was deleted when the feed became all-market). `CompoundingPersistenceTest` (SPEC §43 — **a cyclical whose *average* return on capital clears the bar still fails persistence**, which is the whole point of counting years; a missing balance sheet is unmeasured not failed; a lender's leverage gate leaves the denominator; a bare majority of good years is not a track record), `CapitalAllocationRecordTest` (SPEC §42 — a 1:2 bonus is divided out rather than read as dilution, a raise landing 0.18% from 5/4 is still a raise, untagged dividends are unmeasured not a zero payout, a half-reported CWIP refuses to measure per B-048, and flat capital leaves the incremental-return denominator), `FundamentalsBackfillTest` (SPEC §32.6 — a shallow-but-complete symbol is never re-queued, basis skips count toward completion, a failed listing is never completion, an unparseable filing date is null rather than today, and the NSE pacing cannot be configured to zero). `IpoFeedParserTest` (SPEC §45.2 — NSE's `"-"` placeholder and padded price are null, an issue-size sentence naming a leg whose amount cannot be read leaves the whole split unmeasured rather than zero, a fresh-issue span never runs into the offer-for-sale figure — **a real defect this test caught before ship** — an amount with no "Rs." still parses when its unit word is present, and the three "Non Institutional" rows are told apart by serial number), `IpoStructureReadTest` (SPEC §45.3 — a mid-issue subscription never decides anything, an unfilled institutional book outranks every structure, retail 10× beside a cool institutional book is the hype pattern, mostly-an-exit is mixed however strong the book, and the vocabulary contains no instruction to apply or buy), `IpoLockInAndMathTest` (SPEC §45.4/§45.5 — inside six months the stage is HYPE_WINDOW however good the chart looks, no price history is NOT_MEASURED and never WASHOUT, sizing is at the top of the band because sizing at the bottom overshoots the retail ceiling, retail odds are null until the figure is final), `IpoCaptureRulesTest` (SPEC §45.6 — the equity list fills a listing date the past-issues feed never closed out (NSDL) but a date on or before the issue close is a re-used ticker and is rejected; above-listing-high is derived from captured prices when no analysis has run and null when either price is missing). `DataHealthTest` (SPEC §44 — Saturday's screening scores are current on a Sunday and on Monday morning, one session behind is never a PROBLEM because this JVM cannot tell a missed job from a market holiday, a known-cause zero stops being excused once its fix has shipped, an empty denominator is not 0% coverage, and a signal that is measured everywhere with zero spread is flagged even though no other check reports it, and — since B-089 — a feed whose newest row has stopped advancing is a PROBLEM rather than a quiet market, checked on the **PIT-sourced** row specifically because bulk deals kept arriving daily and masked the outage completely). `SectorMappingTest` (B-096 — the screener's title-case and the profile's upper-case spellings meet, a placeholder is unclassified and never a sector called Other, an unknown sector survives upper-cased so a target can still find it, and `resolve` falls back to the screener's table only for a placeholder), `PerformanceMathTest` (SPEC §46.2 — a deposit is not a return, moves chain-link, a sale with a recorded gain is a flow and without one is counted as uncorrected, fewer than two snapshots is no return, a short span is not annualised, a zero opening value is skipped, drawdown is on the flow-free index so a withdrawal is not a fall), `ConvictionStatedTest` (B-097 — the stored flag wins, a legacy row is read from the seeder's fingerprint, an empty thesis is not a statement). `CompoundingSurfaceContractTest` (SPEC §41.5 — the five field names `compoundingCell` dereferences must exist, with wrapper types, on **every** surface that renders the lens. There is no build step for `compounding.js` and nothing type-checks the wire, so a rename or typo does not fail: the cell finds `undefined` and draws "not measured" for ever, on a stock the app measured perfectly well — Gotcha 44 pointed the other way, and quieter, because "not measured" is an ordinary thing to see). `UniverseSectorsTest` (B-098 — the classpath sector table loads and covers >700 symbols, a bank filed under "Financial Services" reads Banking, the hand map overrides and the file fills the rest, and an unknown symbol is null and resolves to unclassified — never "Other"), `ScreenerSurfaceContractTest` (every entity field `fundamentals-cells.js` dereferences exists as a nullable wrapper, and the DTO carries the source of each newly persisted growth/ownership column), `ScreenerScoreChangeTest` (the screener's "vs peers, 30d" figure: a universe-wide fall reads flat relative to peers, fewer than 30 paired symbols is null not zero, and a null score is skipped). `MacroExposureReadTest` (SPEC §48.6 - NOT_MEASURED never collapses into NOT_EXPOSED, conflicting events read MIXED rather than netting to nothing, a dismissed or future-dated event is ignored, one rupee event is a tailwind for an exporter and a headwind for an importer at the same time, the leverage adjustment fires only for non-lenders, and no verdict or reason text anywhere contains an instruction to transact), `MacroExposureMapTest` (every key resolves, SYMBOL outranks INDUSTRY outranks SECTOR, the version is stable across runs and sensitive to content, a bad line fails the load naming its number), `MacroEventDedupTest`, `MacroCalendarTest` (there is no direction field, asserted by reflection so one cannot be added quietly), `MacroKeywordExtractorTest` (direction is part of the rule so "rupee falls" is the currency factor RISING, a conditional headline yields nothing, and - since B-107 - a columnist asking what a rate hike WOULD mean is not a rate hike, while a report that merely contains "why" still counts), `MacroSurfaceContractTest` (the five field names and wrapper types on every surface that renders the reading; every MacroFactor has a label in format.js, or the UI prints "Usdinr"). `AnalystTargetParserTest` (SPEC §49.3 — and the cases that matter are **verbatim from 162 live headlines**, because every invented case passed while the real ones did not: a year read as ₹2,026, a Nifty index target filed against a company named in the same sentence, "up 200% in 1 year" read as ₹1, plus the scale-word rule that must abandon a revenue headline rather than fall through to the year after it), `HeadlineSubjectResolverTest` (SPEC §49.4 — two companies in one headline resolve to neither, one company written two ways resolves to the longer match, an all-caps abbreviation is not a ticker, and the name index is asserted non-empty because an empty one refuses everything while looking like a quiet news week), `AnalystTrackRecordTest` (SPEC §49.9 — a pending call is never a miss, a revised one is excluded from the hit rate *and* published as a revision rate, below five resolved calls there is no hit rate at all, and a correct Sell counts in the house's favour because excess return is signed the way the call was made), `AnalystTargetOutcomeTest` (SPEC §49.5 — reached is the intraday extreme not the close, measurement starts the day after the call, a resolved call is measured at its horizon and not at today, no issue-date close leaves it UNPRICED rather than zero, and a missing index leg leaves excess null rather than equal to the raw return), `BrokerResearchParserTest` (SPEC §49.11 - **every payload verbatim from the live feed**, for the reason Gotcha 126(a) records: a census over 800 real rows found four different key sets, `scid` typed as both string and array, numbers arriving as String/int/double in one field, and a rating of `"-"` that must stay an absence rather than becoming a Hold. Also pins that a call is filed from the day it became **public** and not from the note's own date - measuring from the note date credits the house with what the price did while the note was private, the one direction a track record must never lean - that an unparseable date is null rather than today, that a revision is arithmetic rather than a verb, and that one house reported three ways collapses to one name or its record splits into three sub-floor rows and reports TOO_EARLY for ever), `AnalystSurfaceContractTest` (the wire field names `analyst-cells.js` dereferences, the status vocabulary carrying no instruction to transact, the shadow default with **no bonus field to flip**, and that an assumed horizon is marked as assumed). `AnalystCoverageSurfaceTest` (SPEC 49.14 - the thirteen `analyst*` fields exist on the holdings row as **nullable wrappers**, because a primitive would collapse "did not look" into "looked and found none"; a counted zero carries the sentence saying what a zero does NOT mean; firms are counted, not notes (B-041); a stock with only closed calls is covered-but-quiet rather than uncovered; a spelling with live targets beats one carrying only stale ones (Gotcha 107); upside is null without both legs; and the stock page's `liveSummary` agrees with the portfolio's `Coverage` **by construction** rather than by coincidence). `QuarterlyResultReadTest` (SPEC §50.3 - a consolidated quarter is never compared with a standalone one, an unstated basis is assumed comparable and says so, one quarter on file cannot be judged, a gap in the captured quarters is not fitted as a trend, swinging to a loss is CONCERNING even when sales grew 50%, a narrowing loss is WEAK, a recovery from a loss quotes **no** growth rate, one enormous revenue jump does not carry a quarter whose profit collapsed, quarter-on-quarter never decides a verdict, and no verdict name or sentence anywhere contains an instruction to transact), `EarningsCalendarTest` (SPEC §50.4 - the answer is a window and says why, a habit is a median so one auditor dispute cannot move it, an *estimated* publication date is never counted as evidence of a habit, **a company that reports early gets an early window rather than the population default** - the defect the first live run caught - the March year-end gets 60 days and not 45, and PAST_DUE states in words that it cannot tell a late company from an uncaptured result), `EarningsSurfaceContractTest` (the thirteen field names `earnings-cells.js` dereferences, as nullable wrappers and all `@Transient`; and that `EarningsConfig` carries **no** actionable/bonus/weight field, so "contributes zero points" is checkable rather than asserted in a comment). Run with `mvn test` (850 tests). **Change a scoring rule → a test should fail.** If it doesn't, the behaviour wasn't pinned; add the case. Several tests deliberately document *known defects* and say so — a failure there may be the intended fix, so read the comment before "repairing" it. The rest of the codebase is still untested.
