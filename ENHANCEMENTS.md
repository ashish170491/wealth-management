# Trading System Enhancements - Implementation Guide

## Overview
This document describes the critical enhancements made to the intraday trading system to make it production-ready and profitable.

---

## 1. Exit Signal Logic ✅

### What Was Added
- **New Actions in TradingSignal**: `EXIT`, `STOP_LOSS`, `TAKE_PROFIT`
- **PositionMonitoringService**: Monitors all open positions every 30 seconds
- **Automatic Exit Triggers**:
  - Stop loss hits (protects capital)
  - Target achievement (locks in profits)
  - End-of-day square-off at 3:25 PM IST

### How It Works
```java
// Position monitoring runs every 30 seconds
@Scheduled(cron = "30 * * * * *")
public void monitorPositions() {
    // For each open position:
    // 1. Check if current price <= stop loss (for BUY) or >= stop loss (for SELL)
    // 2. Check if current price >= target (for BUY) or <= target (for SELL)
    // 3. Execute exit order and close position
}
```

### Key Files
- [TradingSignal.java](src/main/java/com/example/trading/strategy/TradingSignal.java) - Extended with exit actions
- [PositionMonitoringService.java](src/main/java/com/example/trading/monitoring/PositionMonitoringService.java) - Main monitoring logic
- [PositionEntity.java](src/main/java/com/example/trading/persistence/PositionEntity.java) - Updated with stop/target fields

### Configuration
No additional config needed. EOD square-off scheduled at 15:25 IST automatically.

---

## 2. Transaction Cost Model ✅

### What Was Added
Complete Indian stock market cost calculation including:
- **Brokerage**: 0.03% per trade
- **STT** (Securities Transaction Tax): 0.025% on sell side only
- **Exchange Fees**: 0.00325% NSE charges
- **GST**: 18% on brokerage + exchange fees
- **SEBI Charges**: 0.0001% turnover charges
- **Stamp Duty**: 0.003% on buy side
- **Slippage**: 0.05% average market impact

### How It Works
```java
// For every trade closure:
double netPnL = costCalculator.calculateNetPnL(
    entryPrice, exitPrice, quantity, action);
// Returns: grossPnL - totalCosts
```

### Impact on Position Sizing
Transaction costs reduce effective returns by ~0.15% per round trip. The system now accounts for this in P&L calculations.

### Key Files
- [TransactionCostCalculator.java](src/main/java/com/example/trading/execution/TransactionCostCalculator.java)

### Configuration (application.yml)
```yaml
trading:
  costs:
    brokerage-percent: 0.03
    stt-percent: 0.025
    exchange-fees-percent: 0.00325
    gst-percent: 18.0
    sebi-charges-percent: 0.0001
    stamp-duty-percent: 0.003
    slippage-percent: 0.05
```

---

## 3. Market Regime Filters ✅

### What Was Added
Multiple layers of protection to avoid trading in unfavorable conditions:

#### 3.1 Volatility Filter
- Monitors **India VIX** (volatility index)
- Pauses trading when VIX > 25 (high volatility = unpredictable moves)

#### 3.2 Trend Filter (Optional)
- Checks Nifty 50 trend using 20-period EMA
- Can be configured to trade only with market trend

#### 3.3 Volume Filter
- Confirms signals have sufficient volume (1.2x average)
- Rejects low-volume breakouts (likely false signals)

#### 3.4 Time Filter
- Blocks trading in first 15 minutes (9:15-9:30) - too volatile
- Blocks trading in last 15 minutes (15:15-15:30) - square-off rush

### How It Works
```java
// In TradingScheduler, before processing signals:
if (!marketRegimeFilter.shouldAllowTrading()) {
    return; // Skip this trading cycle
}

// For each signal:
if (!marketRegimeFilter.hasVolumeConfirmation(symbol, history)) {
    continue; // Reject this signal
}
```

### Key Files
- [MarketRegimeFilter.java](src/main/java/com/example/trading/filters/MarketRegimeFilter.java)
- [MarketRegimeConfig.java](src/main/java/com/example/trading/filters/MarketRegimeConfig.java)

### Configuration (application.yml)
```yaml
trading:
  filters:
    enable-volatility-filter: true
    max-vix-level: 25.0
    enable-trend-filter: false    # Optional
    enable-volume-filter: true
    min-volume-multiplier: 1.2
    enable-time-filter: true
```

---

## 4. Position Monitoring System ✅

### What Was Added
Real-time tracking of all open positions with:
- **Unrealized P&L calculation** every 30 seconds
- **Stop loss monitoring** - auto-exits on breach
- **Target monitoring** - locks in profits
- **Position reconciliation** with broker (future enhancement)

### How It Works
```java
// Every 30 seconds:
1. Fetch current price for all open positions
2. Calculate unrealized P&L
3. Check stop loss conditions
4. Check target achievement
5. Execute exits if needed
6. Update RiskManagementService
```

### Key Metrics Tracked
- Unrealized P&L per position
- Realized P&L on closed positions
- Win rate, average win, average loss
- Number of open positions

### Key Files
- [PositionMonitoringService.java](src/main/java/com/example/trading/monitoring/PositionMonitoringService.java)
- [PositionRepository.java](src/main/java/com/example/trading/persistence/PositionRepository.java)

---

## 5. Backtesting Framework ✅

### What Was Added
**Complete backtesting engine** to validate strategies on historical data before live trading.

#### Metrics Calculated
1. **Total Return**: (Final Capital - Initial Capital) / Initial Capital × 100
2. **Win Rate**: (Winning Trades / Total Trades) × 100
3. **Profit Factor**: Total Profit / Total Loss
4. **Sharpe Ratio**: Risk-adjusted returns
5. **Max Drawdown**: Largest peak-to-trough decline
6. **Average Win/Loss**: Mean profit per winning/losing trade

### How to Use

#### Via REST API
```bash
POST http://localhost:8080/api/backtest/run
Content-Type: application/json

{
  "strategyName": "EmaCrossoverStrategy",
  "symbols": ["NSE:RELIANCE", "NSE:TCS", "NSE:INFY"],
  "startDate": "2024-01-01",
  "endDate": "2024-12-31",
  "initialCapital": 100000,
  "lookbackPeriod": 100,
  "maxPositions": 5,
  "riskPerTrade": 1.0
}
```

#### Via Code
```java
@Autowired
private BacktestEngine backtestEngine;

BacktestResult result = backtestEngine.runBacktest(
    strategy,
    List.of("NSE:RELIANCE", "NSE:TCS"),
    LocalDate.of(2024, 1, 1),
    LocalDate.of(2024, 12, 31),
    100000.0,
    BacktestConfig.builder()
        .lookbackPeriod(100)
        .maxPositions(5)
        .riskPerTrade(1.0)
        .build()
);

System.out.println(result.toFormattedString());
```

#### Sample Output
```
========== BACKTEST RESULTS ==========
Total Trades: 142
Winning Trades: 64 (45.07%)
Losing Trades: 78
Win Rate: 45.07%

Total Profit: ₹18,450.00
Total Loss: ₹12,300.00
Profit Factor: 1.50
Average Win: ₹288.28
Average Loss: ₹157.69

Initial Capital: ₹100,000.00
Final Capital: ₹106,150.00
Total Return: 6.15%
Max Drawdown: 8.23%
Sharpe Ratio: 1.24
======================================
```

### Key Files
- [BacktestEngine.java](src/main/java/com/example/trading/backtest/BacktestEngine.java)
- [BacktestController.java](src/main/java/com/example/trading/api/BacktestController.java)

---

## REST API Endpoints

### Backtesting
- `POST /api/backtest/run` - Run backtest for a strategy
- `GET /api/backtest/strategies` - List available strategies

### Trading Operations
- `GET /api/trading/positions/open` - View all open positions
- `GET /api/trading/positions/closed` - View closed positions
- `GET /api/trading/trades` - View all trades
- `GET /api/trading/pnl/summary` - Get P&L summary
- `POST /api/trading/positions/close-all` - Emergency square-off

### Example: View P&L Summary
```bash
GET http://localhost:8080/api/trading/pnl/summary

Response:
{
  "totalRealizedPnL": 2450.75,
  "totalUnrealizedPnL": -150.50,
  "netPnL": 2300.25,
  "totalTrades": 28,
  "winningTrades": 14,
  "losingTrades": 14,
  "winRate": 50.0,
  "openPositions": 2
}
```

---

## Updated Configuration (application.yml)

```yaml
spring:
  application:
    name: intraday-app
  datasource:
    url: jdbc:h2:mem:tradingdb
  jpa:
    hibernate:
      ddl-auto: update

broker:
  kite:
    # ... (existing config)

trading:
  execution:
    paper-trading-mode: true  # MUST be true for initial testing
    
  risk:
    max-open-positions: 5
    max-daily-loss-percent: 2.0
    per-trade-risk-percent: 1.0
    initial-capital: 100000.0
    
  costs:
    brokerage-percent: 0.03
    stt-percent: 0.025
    exchange-fees-percent: 0.00325
    gst-percent: 18.0
    sebi-charges-percent: 0.0001
    stamp-duty-percent: 0.003
    slippage-percent: 0.05
    
  filters:
    enable-volatility-filter: true
    max-vix-level: 25.0
    enable-trend-filter: false
    enable-volume-filter: true
    min-volume-multiplier: 1.2
    enable-time-filter: true
```

---

## Pre-Live Trading Checklist

### 1. Backtest Thoroughly
- [ ] Run backtest on at least 6 months of data
- [ ] Verify positive expectancy (total return > 0)
- [ ] Check max drawdown is acceptable (< 10%)
- [ ] Ensure win rate > 45% OR profit factor > 1.5
- [ ] Verify Sharpe ratio > 1.0

### 2. Paper Trade
- [ ] Set `paper-trading-mode: true`
- [ ] Run for 30 days minimum
- [ ] Verify no execution bugs
- [ ] Check stop losses trigger correctly
- [ ] Confirm EOD square-off works

### 3. Risk Management Validation
- [ ] Test with small capital first (₹10,000)
- [ ] Verify position sizing is correct
- [ ] Confirm daily loss limit stops trading
- [ ] Check max open positions limit

### 4. Market Filter Testing
- [ ] Verify VIX filter blocks trades during high volatility
- [ ] Test volume filter rejects low-volume signals
- [ ] Confirm time filter blocks dangerous hours

### 5. Monitoring Setup
- [ ] Check H2 console for trade history
- [ ] Use REST API to monitor positions
- [ ] Review logs for any errors
- [ ] Set up alerts for drawdown breaches

---

## Expected Performance Characteristics

### EMA Crossover Strategy (Typical)
- **Win Rate**: 40-50%
- **Profit Factor**: 1.3-1.8
- **Max Drawdown**: 5-12%
- **Sharpe Ratio**: 0.8-1.5
- **Average Win/Loss Ratio**: 1.5-2.0

### With Transaction Costs
- Reduces net returns by ~0.15% per trade
- 100 trades/month = ~15% annual drag
- Strategies MUST have edge > 0.2% per trade to be profitable

### Realistic Expectations
- **Monthly Return**: 2-5% (good)
- **Annual Return**: 25-60% (excellent)
- **Max Drawdown**: < 10% (manageable)
- **First 3 months**: Expect volatility and learning curve

---

## Troubleshooting

### Issue: Positions not closing at stop loss
**Solution**: Check [PositionMonitoringService.java](src/main/java/com/example/trading/monitoring/PositionMonitoringService.java) logs. Verify scheduled task is running every 30 seconds.

### Issue: Backtest returns zero trades
**Solution**: 
1. Check historical data availability for symbols
2. Verify strategy logic is generating signals
3. Increase lookback period or date range

### Issue: High slippage in live trading
**Solution**: 
1. Trade only high-liquidity stocks (Nifty 50)
2. Avoid market orders during volatile times
3. Increase `slippage-percent` in config

### Issue: VIX filter blocking all trades
**Solution**: Check current VIX level. If consistently > 25, consider:
1. Increasing `max-vix-level` (carefully!)
2. Disabling filter temporarily: `enable-volatility-filter: false`
3. Using different strategies for high-volatility periods

---

## Next Steps for Further Enhancement

1. **Walk-Forward Analysis**: Split backtest into in-sample/out-of-sample
2. **Multiple Strategies**: Combine uncorrelated strategies
3. **Dynamic Position Sizing**: Increase size on winning streaks
4. **Trailing Stops**: Move stop loss as position goes in profit
5. **Partial Exits**: Scale out at multiple profit levels
6. **Correlation Checks**: Avoid multiple positions in same sector
7. **News Sentiment**: Pause trading during major events
8. **Broker Reconciliation**: Sync positions with Kite API

---

## Contact & Support

For issues or questions:
1. Check logs in `logs/trading-app.log`
2. Use H2 console: http://localhost:8080/h2-console
3. Review Copilot instructions in `.github/copilot-instructions.md`

**Remember**: Always backtest → paper trade → small capital → scale up!
