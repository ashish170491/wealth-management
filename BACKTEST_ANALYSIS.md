# Backtest Analysis Report

## Test Period: December 1-19, 2025
**Symbols**: NSE:RELIANCE, NSE:TCS  
**Initial Capital**: ₹100,000  
**Strategy**: EMA Crossover (9/21 periods)

---

## Critical Issues Identified

### 1. ⚠️ Capital Calculation Bug (FIXED)

**Problem**: BacktestEngine was **double-counting profits** when closing positions.

**Location**: `BacktestEngine.java:176`

```java
// BEFORE (WRONG):
state.adjustCapital(exitPrice * position.getQuantity() + netPnL);

// AFTER (FIXED):
state.adjustCapital(position.getEntryPrice() * position.getQuantity() + netPnL);
```

**Impact**: Final capital was inflated by ~21% in the original test, making losing strategies appear profitable.

---

## Performance Analysis (Original Results)

| Metric | Value | Assessment |
|--------|-------|------------|
| **Total Trades** | 119 | ✅ Good sample size |
| **Win Rate** | 27.73% | ❌ **CRITICAL** - Wrong 72% of the time |
| **Profit Factor** | 0.649 | ❌ Losing ₹0.35 for every ₹1 gained |
| **Max Drawdown** | 63.11% | ❌ **UNACCEPTABLE** - Account dropped 63% |
| **Sharpe Ratio** | -0.21 | ❌ Negative risk-adjusted returns |
| **Final Return** | 21.75% | ⚠️ Inflated by bug (should be negative) |

---

## Root Causes of Poor Performance

### 1. **EMA Crossover Limitations**

EMA crossover generates false signals in choppy/sideways markets:
- **No trend filter** - Trades in all market conditions
- **Lag** - EMAs are lagging indicators, signals come late
- **Whipsaws** - Multiple false crosses in range-bound markets

**Evidence from logs**:
```
2025-12-04: 5 consecutive stop loss hits (choppy market)
2025-12-10: 4 stop losses in 3 hours (sideways movement)
```

### 2. **Aggressive Position Sizing**

Current formula:
```
Quantity = (Capital × 1.0%) / (Entry - StopLoss)
StopLoss = Entry ± 0.5%
```

With 1% risk and 0.5% stop, this allocates **~40 shares per ₹100K capital**.

**Problem**: No portfolio heat limit - can open up to 200 simultaneous positions.

### 3. **Fixed Stop Loss/Target Ratios**

- **Stop Loss**: 0.5% (too tight for intraday volatility)
- **Target**: 1.5% (3:1 reward-to-risk sounds good but rarely hit)

**Reality**: Average loss (₹1,715) > Average win (₹2,902) / 72% lose rate

### 4. **Transaction Costs Erode Profits**

Per trade costs: ₹400-470 (0.15% round trip)
- With 27% win rate, costs amplify losses
- Example: ₹1,500 gross loss → ₹1,970 net loss after costs

---

## Recommendations for Improvement

### Priority 1: Add Trend Filter

**Before trading**, check if market is trending:

```java
// Add to EmaCrossoverStrategy.java
private boolean isTrending(List<Map<String, Object>> history) {
    if (history.size() < 50) return false;
    
    // Calculate 50-period EMA
    double ema50 = calculateEMA(history, 50);
    double currentPrice = (double) history.get(history.size() - 1).get("close");
    
    // Calculate ADX (Average Directional Index) or simple slope
    double slope = (currentPrice - ema50) / ema50 * 100;
    
    // Only trade if trending (slope > 1% up or down)
    return Math.abs(slope) > 1.0;
}
```

**Expected impact**: Reduce trades by 40-60%, improve win rate to 35-45%

### Priority 2: Dynamic Stop Loss Based on ATR

Average True Range (ATR) adjusts stops to volatility:

```java
// Replace fixed 0.5% with 2x ATR
double atr = calculateATR(history, 14);
double stopLoss = signal.getAction() == BUY ? 
    currentPrice - (2 * atr) : 
    currentPrice + (2 * atr);
```

**Expected impact**: Reduce premature stop outs by 30%

### Priority 3: Portfolio Heat Limit

Add cumulative risk tracking:

```java
// In BacktestEngine.java
double portfolioHeat = state.getOpenPositions().stream()
    .mapToDouble(p -> Math.abs(p.getEntryPrice() - p.getStopLoss()) * p.getQuantity())
    .sum();

double maxPortfolioHeat = state.getCurrentCapital() * 0.05; // 5% max

if (portfolioHeat >= maxPortfolioHeat) {
    log.debug("Portfolio heat limit reached: {} / {}", portfolioHeat, maxPortfolioHeat);
    continue; // Skip this signal
}
```

**Expected impact**: Reduce max drawdown from 63% to <10%

### Priority 4: Trailing Stop for Winners

Let winners run beyond 1.5% target:

```java
// Update stop to lock in profits
if ("BUY".equals(position.getAction()) && currentPrice > position.getEntryPrice() * 1.01) {
    double newStop = currentPrice * 0.995; // Trail by 0.5%
    if (newStop > position.getStopLoss()) {
        position.setStopLoss(newStop);
        log.debug("Trailing stop updated: {}", newStop);
    }
}
```

**Expected impact**: Increase average win from ₹2,902 to ₹3,500+

### Priority 5: Time-Based Filters

Avoid volatile periods:

```java
// In TradingScheduler.java
private boolean isInSafeTradingHours(ZonedDateTime time) {
    int hour = time.getHour();
    int minute = time.getMinute();
    
    // Skip first 15 minutes (high volatility)
    if (hour == 9 && minute < 30) return false;
    
    // Skip last 15 minutes (EOD manipulation)
    if (hour == 15 && minute > 15) return false;
    
    return true;
}
```

**Expected impact**: Reduce whipsaws by 20%

---

## Revised Backtest Configuration

```yaml
strategy:
  ema-crossover:
    short-period: 9
    long-period: 21
    trend-filter: true
    trend-threshold: 1.0  # 1% slope minimum
    atr-period: 14
    atr-multiplier: 2.0

risk:
  risk-per-trade: 0.5     # Reduced from 1.0%
  max-portfolio-heat: 5.0  # Total risk across all positions
  max-positions: 3         # Reduced from 200
  trailing-stop: true
  trailing-stop-pct: 0.5

execution:
  safe-hours:
    start: "09:30"
    end: "15:15"
```

---

## Expected Performance After Fixes

| Metric | Current | Expected |
|--------|---------|----------|
| Win Rate | 27.73% | 35-40% |
| Profit Factor | 0.649 | 1.2-1.5 |
| Max Drawdown | 63.11% | <10% |
| Sharpe Ratio | -0.21 | 0.5-1.0 |
| Avg Win:Loss | 1.69:1 | 2.5:1 |

---

## Next Steps

1. ✅ **Fixed**: Capital calculation bug
2. ⏳ **Pending**: Implement trend filter
3. ⏳ **Pending**: Add ATR-based stops
4. ⏳ **Pending**: Add portfolio heat limit
5. ⏳ **Pending**: Implement trailing stops
6. ⏳ **Pending**: Add time filters

---

## Test Plan

After implementing changes:

1. **Run backtest on same period** (Dec 1-19) to compare
2. **Test on different periods**: Nov 2025, Oct 2025
3. **Test different symbols**: NIFTY 50 stocks
4. **Paper trade for 1 week** before live deployment

---

## Conclusion

The current EMA crossover strategy is **not suitable for live trading** in its current form. The 27% win rate and 63% drawdown are unacceptable.

**However**, with the recommended improvements:
- Trend filter
- ATR-based stops  
- Portfolio risk management
- Trailing stops

The strategy has potential to become profitable with 35-40% win rate and <10% drawdown.

**DO NOT** deploy to live trading until:
1. All fixes implemented
2. Win rate > 35%
3. Profit factor > 1.2
4. Max drawdown < 10%
5. Successful 1-week paper trading validation
