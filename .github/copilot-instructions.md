# Copilot Instructions: Intraday Trading System

## System Architecture

This is an **automated intraday stock trading system** built with Spring Boot 3.4, Java 21, integrating with Zerodha Kite Connect API. The system runs on a **scheduled 1-minute trading loop** during Indian market hours (09:15-15:30 IST).

### Core Components & Data Flow

1. **TradingScheduler** (`scheduler/`) - Orchestrates the trading loop every minute:
   - Checks market hours via `MarketHoursService` (09:15-15:30 IST)
   - Iterates through expanded watchlist (80+ symbols: Nifty 50 + high-liquidity midcaps + index futures)
   - Initializes `TradeContext` with correlation ID for each symbol
   - Calls **MarketRegimeDetector** to classify market state (HIGH_VOLATILITY, TRENDING, RANGING, NEUTRAL) using VIX, ADX, and EMA slope
   - Applies **MarketRegimeFilter** (volatility, volume, time filters) before strategy evaluation
   - Fetches market data → evaluates ALL strategies (multi-strategy approach) → uses **StrategyWeightCalculator** to weight signals by regime suitability → executes highest-confidence signals

2. **Strategy Layer** (`strategy/`) - Stateless technical analysis:
   - Implements `TradingStrategy` interface with `evaluate(symbol, currentPrice, history)` method
   - **4 active strategies**: EMA Crossover (20/50), VWAP Crossover, Opening Range Breakout (ORB), RSI Mean Reversion (14-period)
   - Uses **TA4J library** for indicators (EMA, RSI, ATR, ADX); all strategies include trend confirmation
   - Returns `TradingSignal` with action (BUY/SELL/HOLD/EXIT/STOP_LOSS/TAKE_PROFIT), confidence (0-100), and descriptive reasons
   - All strategies registered as `@Bean` in `StrategyConfig` for auto-inclusion in trading loop
   - **Multi-strategy execution**: Each symbol evaluated by ALL strategies every minute; weighted by regime suitability

3. **Risk Management** (`risk/`) - Pre-execution validation & position tracking:
   - Calculates position size: `Quantity = (Capital × Risk%) / (Entry - StopLoss)`
   - Enforces limits: max open positions (configurable, default 8), daily loss cap (2%), per-trade risk (1%), daily profit target
   - **Fund validation** (configurable): Checks actual broker balance with margin multiplier (0.2 = 5x leverage for MIS)
   - **Price-tier quantity caps**: Prevents over-exposure based on stock price (e.g., max 100 qty for < ₹100, 1 qty for > ₹5000)
   - **Stop-loss cooldown**: 15-min lockout after SL hit to prevent revenge trading
   - Maintains stateful tracking: `activePositions` Set, `currentDailyLoss` DoubleAdder, daily P&L
   - Returns `RiskValidationResult` with recommended quantity or rejection reason
   - Integration with **PositionMonitoringService**: Monitors open positions every 30s for stop-loss/take-profit triggers

4. **Order Execution & Cost Management** (`execution/`) - Dual-mode trade placement with transaction costs:
   - Paper trading mode (default): Simulates fills with `PAPER-{UUID}` order IDs; use for testing/backtesting
   - Live mode: Places real orders via `BrokerClient` with `@Retryable` (3 attempts, exponential backoff)
   - **Hybrid instrument logic**: NFO futures (NIFTY/BANKNIFTY FUT) automatically traded as options; other symbols as equity
   - **TransactionCostCalculator**: Deducts realistic Indian market costs (brokerage 0.002%, STT 0.025%, GST 18%, slippage 0.01%)
   - Persists all trades to `TradeEntity` with status: PENDING → SUBMITTED/COMPLETED → FAILED

5. **Broker Integration** (`broker/`) - Zerodha Kite API abstraction:
   - `BrokerClient` interface implemented by `KiteBrokerClient`
   - Uses **WebFlux WebClient** for reactive HTTP calls with retry logic
   - `KiteAuthService` handles **100% automated authentication**:
     - Generates TOTP codes from `totp-secret` using GoogleAuthenticator
     - Auto-login flow: credentials + TOTP → request_token → access_token
     - Tokens refresh daily at 08:30 IST

6. **Market Data** (`marketdata/`) - Real-time and historical data:
   - `getQuote()` for current price (LTP)
   - `getHistoricalData()` for OHLCV candles with date ranges
   - Data passed to strategies in `List<Map<String, Object>>` format

7. **Persistence** (`persistence/`) - H2 in-memory database:
   - `TradeEntity` (entry/exit price, P&L, status), `PositionEntity` (active positions, SL/target), `StrategyExecutionLogEntity` (signal history with JSON snapshot)
   - Access via H2 console: `http://localhost:8080/h2-console` (JDBC: `jdbc:h2:mem:tradingdb`, user: `sa`, password: `password`)
   - MySQL for production (configured in `application.yml`): `jdbc:mysql://localhost:3306/tradingdb`

8. **Market Regime Filters** (`filters/`) - Optional pre-signal checks:
   - **Volatility Filter**: Pauses trading if VIX > 25 (config: `trading.filters.max-vix-level`)
   - **Volume Filter**: Rejects signals if volume < 1.2x average (config: `trading.filters.min-volume-multiplier`)
   - **Time Filter**: Avoids dangerous hours (first 15 min after 09:15, last 15 min before 15:30)
   - Configurable on/off per filter type in `application.yml`

9. **Backtesting Framework** (`backtest/`) - Historical simulation & validation:
   - `BacktestEngine`: Replays historical OHLCV data through strategies
   - REST endpoint `/api/backtest/run` accepts date range, initial capital, lookback period
   - Generates metrics: win rate, Sharpe ratio, max drawdown, profit factor, total return
   - `MockDataGenerator`: Creates synthetic or fetches historical data for testing

10. **Holdings Analysis** (`holdings/`) - Portfolio health monitoring (runs 9:15-15:20 IST):
   - **HoldingsScheduler**: Syncs holdings at 9:20 AM, analyzes at 3:15 PM, sends daily report at 3:18 PM
   - Scores each holding based on EMA alignment, RSI, ATR, and trend strength (0-100 scale)
   - Identifies weak positions (score < 40) for potential exit consideration
   - Automated email reports with trade suggestions (exit recommendations for weak stocks)

11. **Breakout Scanner** (`scanner/`) - Swing trade identification (1-2 week holding):
   - Scans Nifty 200 stocks for consolidation breakouts (20-day highs with 1.5x volume)
   - Runs 4 times daily: 9:30 AM, 11:30 AM, 1:30 PM, 3:00 PM
   - **Consolidation filter**: Stock must trade in 8% range for 15 days before breakout (reduces false signals)
   - Targets: 3x ATR (book 50%), 5x ATR (book remaining); Stop loss: 2x ATR
   - Persists signals to `BreakoutSignalEntity` for tracking; sends email alerts

12. **Position Synchronization** (`sync/`) - Reconciliation with broker:
   - **PositionSyncService**: Runs every 2 minutes during market hours (9:00-15:00 IST)
   - Fetches live positions from broker API and reconciles with local `PositionEntity` records
   - Creates missing positions, updates P&L, closes positions no longer at broker
   - Critical for recovery after restarts or manual broker-side actions

### Configuration Philosophy

- **Externalized config via `@ConfigurationProperties`**: Each module has a `*Config` class
  - `KiteConfig` (broker.kite.*), `RiskConfig` (trading.risk.*), `ExecutionConfig` (trading.execution.*), `MarketRegimeConfig` (trading.filters.*)
- **Secrets management**: Use environment variables for sensitive data (e.g., `${KITE_ACCESS_TOKEN}`)
- **Paper trading default**: Always test with `paper-trading-mode: true` before live deployment
- **Instrument type**: Set `trading.execution.instrument-type: EQUITY|OPTIONS` (or auto-detect via futures symbols)
- **Options trading**: Configure strike selection (ATM/ITM/OTM), expiry (CURRENT_WEEK/NEXT_WEEK), option buying enabled flag

## Development Patterns

### Correlation ID Logging
Every trade execution flow uses `TradeContext` to inject a correlation ID into SLF4J MDC:
```java
tradeContext.init(symbol);  // Creates: "5a2b1c3d-NSE:RELIANCE"
// All logs in this flow tagged with correlationId
tradeContext.clear();       // Remove from MDC
```
Search logs by correlation ID to trace end-to-end signal lifecycle.

### Stateless Strategy Design
Strategies must be **thread-safe and stateless**:
- Accept `symbol`, `currentPrice`, `history` as method parameters (history = `List<Map<String, Object>>` with keys: timestamp, open, high, low, close, volume)
- Use TA4J `BaseBarSeries` for indicator calculations; parse timestamps as `ZonedDateTime` in IST timezone
- Implement trend confirmation via 50-period EMA slope (e.g., only BUY if uptrend exists)
- Never store state between `evaluate()` calls; all state is injected or computed fresh

### Scheduled Tasks Pattern
Application uses `@EnableScheduling` with multiple scheduled components:
- **TradingScheduler**: `@Scheduled(cron = "0 * * * * *")` - Every minute during market hours
- **PositionMonitoring**: `@Scheduled(cron = "30 * * * * *")` - Every 30 seconds for SL/target checks
- **TokenManagement**: `@Scheduled(cron = "0 45 9 * * MON-FRI", zone = "Asia/Kolkata")` - 9:45 AM IST, weekdays. This is a *recovery* path: the token is normally obtained at startup via `ApplicationReadyEvent`. It is in-window, so no `@Scheduled` method fires before 09:15 (SPEC §3.4, §15; corrected 2026-09-05, B-076 - this line previously read "0 35 8", which was never the cron).
- **PositionSync**: `@Scheduled(cron = "0 0/2 9-15 * * MON-FRI", zone = "Asia/Kolkata")` - Every 2 minutes during market
- **BreakoutScanner**: 4 times daily (9:30 AM, 11:30 AM, 1:30 PM, 3:00 PM) - Configurable via properties
- **HoldingsScheduler**: Multiple times (9:20 AM sync, 3:15 PM analysis, 3:18 PM report)
- All timezone-dependent tasks use `zone = "Asia/Kolkata"` to ensure IST execution

### Error Handling Conventions
- `BrokerException` for broker API errors (retryable)
- `@Retryable` on `OrderExecutionService.placeOrderWithRetry()` for transient failures
- Failed trades persist with `status=FAILED` and `failureReason` for audit

### Date/Time Handling
- Market hours in IST: Use `MarketHoursService.getMarketZone()` for ZonedDateTime
- Historical data formats: `"yyyy-MM-dd HH:mm:ss"` or `"yyyy-MM-dd'T'HH:mm:ssXX"` (ISO with timezone)

## Build & Run Workflows

```bash
# Compile
mvn compile

# Run application (uses application.yml, runs trading loop during market hours)
mvn spring-boot:run

# Override config with environment variables
KITE_ACCESS_TOKEN=your_token KITE_USER_ID=RA1234 mvn spring-boot:run

# Build JAR (in target/)
mvn package

# Test specific strategy via PowerShell
.\test-all-strategies.ps1  # Runs backtest for all strategies with Oct-Dec 2024 data
```

**Debugging**:
- **H2 console**: `http://localhost:8080/h2-console` (JDBC: `jdbc:h2:mem:tradingdb`, user: `sa`, pass: `password`)
- **View trades**: `http://localhost:8080/api/trading/positions` (REST API)
- **Logs**: `logs/trading-app.log` (search by correlation ID for signal trace)

## Critical Integration Points

### Zerodha Kite Authentication Flow
1. **Setup**: User provides `api-key`, `api-secret`, `user-id`, `password`, `totp-secret` in config
2. **Auto-login**: `TokenManagementService` calls `KiteAuthService.performFullLogin()`
   - Simulates browser login with credentials + generated TOTP
   - Extracts `request_token` from redirect URL
   - Exchanges for `access_token` via POST to `/session/token` with SHA256 checksum
3. **Token refresh**: Scheduled task regenerates token daily before market open

### TA4J Integration
- Convert candle history to `BaseBarSeries` with proper timestamp parsing
- Use `ClosePriceIndicator` + `EMAIndicator` for technical analysis
- Extract `Num` values for comparison (e.g., `shortEMA.getValue(i).isGreaterThan(longEMA.getValue(i))`)

### WebClient Configuration
- Base URL: `https://api.kite.trade`
- Headers: `X-Kite-Version: 3`, `Authorization: token API_KEY:ACCESS_TOKEN`
- Form-encoded bodies for POST requests (not JSON)
- Retry with exponential backoff on 5xx errors

## Key Constraints & Assumptions

- **Multiple exchanges**: NSE equity (`NSE:*`), NFO futures/options (`NFO:*`); symbols auto-detect instrument type
- **Intraday only**: All positions closed by 15:30 IST (EOD square-off enforced by `PositionMonitoringService`)
- **Hybrid trading**: Nifty/BankNifty futures automatically route to options market if options flag enabled
- **Signal independence**: Each strategy evaluation independent; multi-strategy backtest runs all strategies per candle
- **Market hours**: Enforced via `MarketHoursService.isMarketOpen()` in scheduler; IST timezone assumed throughout
- **MySQL persistence**: Uses MySQL database (configured in `application.yml`); H2 option available for local testing
- **Zerodha-specific**: `BrokerClient` interface abstracted, but only `KiteBrokerClient` implementation provided; pluggable design allows other brokers

## Adding New Features

### To add a new strategy:
1. Implement `TradingStrategy` interface in `strategy/` package
2. Use TA4J for indicators or custom logic on `List<Map<String, Object>> history`
3. Return descriptive `TradingSignal.hold()` when conditions not met
4. Register as `@Bean` in `StrategyConfig` to auto-include in trading loop

### To add a new broker:
1. Implement `BrokerClient` interface
2. Handle authentication in separate service (like `KiteAuthService`)
3. Map broker-specific response formats to standard `Map<String, Object>`
4. Use `@Profile` or `@ConditionalOnProperty` to switch implementations

### To add filters or monitoring:
1. Extend `MarketRegimeFilter` interface or add method to `MarketRegimeConfig`
2. Call filter in `TradingScheduler.processSymbol()` before strategy evaluation
3. Return `false` from filter to skip signal (logs reason)

### To test strategies:
1. Use `/api/backtest/run` REST endpoint with JSON body (date range, symbols, initial capital)
2. Or run PowerShell scripts: `test-all-strategies.ps1`, `test-backtest.ps1` (uses historical data from backtest JSON files)
3. Results saved to `backtest-*.json`; review metrics: win rate > 50%, Sharpe > 1.0, max drawdown < 10%

## Common Gotchas & Debugging

- **TOTP Secret**: Config requires BASE32 secret from Zerodha (from "Can't scan?" link), NOT the 6-digit code; GoogleAuthenticator auto-generates TOTP every 30s
- **Token Expiry**: Kite access tokens expire daily; `TokenManagementService` auto-refreshes at startup and again at 09:45 IST as a fallback; if manual refresh needed, call `KiteAuthService.performFullLogin()`
- **History Data Format**: Kite APIs return candles as array `[timestamp, open, high, low, close, volume]`; mapped to `Map<String, Object>` in `MarketDataService.getHistoricalData()`
- **Stop Loss Direction**: Entry-based (BUY: SL < entry, SELL: SL > entry); enforced in `RiskManagementService.validatePositionSize()`
- **Paper vs Live**: Paper orders get `PAPER-{UUID}` prefix; real orders get broker's order ID; search logs for "PAPER TRADING:" or "order_id" to distinguish
- **Position Cleanup**: `PositionMonitoringService` closes all open positions at 15:25 IST (5-min buffer before market close); check logs for "EOD Square-Off"
- **Options Symbols**: NFO contracts use format `NFO:NIFTY25DECFUT` or `NFO:NIFTY{expiry}{strike}{type}` (e.g., `NFO:NIFTY25DEC24000CE`); auto-mapped by `OptionContractService`
- **Correlation IDs**: Format: `{8-char-uuid}-{SYMBOL}` (e.g., `5a2b1c3d-NSE:RELIANCE`); search logs for this to trace complete signal lifecycle
- **Performance**: Strategy evaluation is per-symbol/per-minute; ~50 symbols × 5-6 strategies = ~250 evaluations/min; ensure indicator calculations (TA4J) don't exceed 1-sec overhead
