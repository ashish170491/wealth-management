# RELIANCE Backtest Analysis & Improvement Opportunities

## Test Results Summary

**Period**: Oct 1 - Dec 20, 2024 (3 months)  
**Symbol**: NSE:RELIANCE  
**Result**: Only **1 trade** executed  
**Return**: 0.28% (₹276 profit)

## Problem Diagnosis

### Issue 1: OVERLY STRICT FILTERS
All 4 strategies have multiple layers of filters that reject most signals:

#### **EMA Crossover Strategy (20/50)**
- ❌ Time Filter: Rejects 9:15-9:30 AM and 3:00-3:30 PM
- ❌ Trend Filter: Requires 50-EMA slope > 0.15% (very strict)
- ❌ Volume Filter: Requires 1.2x average volume on crossover candle
- ❌ Price Position: Must be above/below 50-EMA for entry
- ❌ Minimum Price: ₹100 (RELIANCE passes this)

**Why it fails**: Crossovers happen rarely, and when they do, ALL 4 filters must align perfectly. Over 3 months, this creates near-zero opportunities.

#### **RSI Mean Reversion Strategy (14/30/70)**
- ❌ Time Filter: Rejects 9:15-9:45 AM and 2:45-3:30 PM
- ❌ Trend Filter: Only buys in uptrend (EMA20 > EMA50), sells in downtrend
- ❌ Extreme RSI: Only triggers at RSI < 25 or > 75 (very rare)
- ❌ ADX Filter: Requires ADX > 18 (directional movement)
- ❌ RSI Bounce: Requires 2+ consecutive bars of RSI movement
- ❌ Price Structure: Must show higher lows or lower highs
- ❌ Minimum Price: ₹50

**Why it fails**: RSI reaching 25 or 75 is rare in stable large-cap stocks. Combining with trend alignment and ADX filter creates almost impossible conditions.

#### **VWAP Strategy (30 min, 1.0%)**
- ❌ Time Filter: Only trades 10:00 AM - 2:30 PM
- ❌ VWAP Distance: Requires price 1% away from VWAP
- ❌ Trend Filter: Uses EMA50 for trend confirmation
- ❌ Volume Filter: Requires 1.5x average volume

**Why it fails**: Price staying 1% away from VWAP + volume spike is rare for liquid stocks like RELIANCE.

#### **Opening Range Breakout (15 min)**
- ❌ Time Filter: Only the first 15 minutes (9:15-9:30)
- ❌ Single Window: Only 1 opportunity per day
- ❌ Consolidation Required: Needs sideways range before breakout
- ❌ Volume Confirmation: 1.5x average volume required

**Why it fails**: Only 1 chance per day, and RELIANCE doesn't always break opening range with volume.

### Issue 2: INTRADAY TIMEFRAME (5-minute candles)
- Current backtest uses **5-minute candles** as per BacktestEngine configuration
- Large-cap stocks like RELIANCE move slowly intraday
- Better suited for **1-minute** (faster signals) or **15-minute** (cleaner trends)

### Issue 3: LOOKBACK PERIOD TOO SHORT
- Current: **50 bars** lookback
- For 5-min candles: Only 4 hours of history
- Strategies need more context for reliable indicator calculations (especially 50-EMA)

### Issue 4: SINGLE SYMBOL BACKTEST
- Only testing RELIANCE in isolation
- Market regime (bull/bear/sideways) during Oct-Dec 2024 affects signal quality
- Need to test across multiple symbols to validate strategy robustness

## Improvement Recommendations

### Priority 1: RELAX FILTERS (Quick Wins)

#### A. **Reduce Trend Filter Strictness**
```java
// EmaCrossoverStrategy.java - Line ~54
// BEFORE: trendThreshold = 0.15%
// AFTER: trendThreshold = 0.08%  // More flexible
```

#### B. **Lower RSI Extremes**
```java
// RsiMeanReversionStrategy.java - Line ~42-43
// BEFORE: EXTREME_OVERSOLD = 25.0, EXTREME_OVERBOUGHT = 75.0
// AFTER: EXTREME_OVERSOLD = 35.0, EXTREME_OVERBOUGHT = 65.0
```

#### C. **Expand Trading Hours**
```java
// All strategies
// BEFORE: SAFE_START = 9:30/9:45, SAFE_END = 14:45/15:00
// AFTER: SAFE_START = 9:20, SAFE_END = 15:15  // Capture more opportunities
```

#### D. **Reduce Volume Confirmation**
```java
// EmaCrossoverStrategy, VwapStrategy
// BEFORE: volumeMultiplier = 1.2x or 1.5x
// AFTER: volumeMultiplier = 1.1x  // Easier to meet
```

### Priority 2: OPTIMIZE BACKTEST PARAMETERS

#### A. **Use 1-Minute Candles**
```java
// BacktestEngine.java - Line ~70
// BEFORE: "5minute"
// AFTER: "minute" or "1minute"
```
**Benefit**: More signal opportunities, faster reaction to intraday moves

#### B. **Increase Lookback Period**
```java
// Backtest request
// BEFORE: lookbackPeriod = 50
// AFTER: lookbackPeriod = 100  // More indicator stability
```

#### C. **Test Multiple Symbols**
```java
// Add more liquid large-caps
symbols: ["NSE:RELIANCE", "NSE:TCS", "NSE:INFY", "NSE:HDFCBANK"]
```

### Priority 3: ADD NEW STRATEGIES

#### A. **Momentum Breakout Strategy**
- Simpler logic: Price breaks 20-bar high with volume
- No complex filters, just volume + ATR-based stops
- Expected: 4-6 trades/symbol/month

#### B. **Bollinger Band Squeeze**
- Volatility contraction followed by expansion
- Works well with stable large-caps like RELIANCE
- Expected: 2-3 trades/symbol/month

#### C. **MACD Divergence**
- Price vs MACD divergence for reversal entries
- Less strict than RSI extremes
- Expected: 3-5 trades/symbol/month

### Priority 4: HYBRID APPROACH

Combine strategies with **weighted voting**:
- If 2+ strategies signal BUY/SELL, increase position size
- If only 1 strategy signals, use smaller size
- This reduces false positives while capturing high-conviction setups

## Expected Improvements

| Change | Before | After | Impact |
|--------|--------|-------|--------|
| Trade Frequency | 1 trade/3 months | 8-12 trades/month | +2400% |
| Win Rate | N/A (1 trade) | 55-60% | Acceptable |
| Profit Factor | N/A | 1.5-2.0 | Profitable |
| Max Drawdown | 0% | 5-8% | Controlled |

## Testing Plan

### Step 1: Relax Filters (Quick Test)
- Modify 3 key parameters per strategy
- Re-run Oct-Dec 2024 backtest
- Target: 10-15 trades for RELIANCE

### Step 2: Optimize Timeframe
- Test with 1-minute candles
- Compare vs 5-minute and 15-minute
- Select best performing timeframe

### Step 3: Multi-Symbol Validation
- Run same period with 5 large-cap symbols
- Verify strategy works across different stocks
- Ensure not overfitted to RELIANCE

### Step 4: Walk-Forward Testing
- Month 1 (Oct): Optimize parameters
- Month 2-3 (Nov-Dec): Validate with optimized params
- Check if improvements hold out-of-sample

## Immediate Action Items

1. **Modify EmaCrossoverStrategy**:
   - Reduce `trendThreshold` from 0.15% → 0.08%
   - Reduce `volumeMultiplier` from 1.2x → 1.1x
   - Expand `SAFE_START` from 9:30 → 9:20

2. **Modify RsiMeanReversionStrategy**:
   - Change `EXTREME_OVERSOLD` from 25 → 35
   - Change `EXTREME_OVERBOUGHT` from 75 → 65
   - Reduce `MIN_ADX_FOR_TREND` from 18 → 15

3. **Modify VwapStrategy**:
   - Reduce `volumeMultiplier` from 1.5x → 1.2x
   - Expand time window from 10:00-14:30 → 9:30-15:00

4. **Run New Backtest**:
   - Same period (Oct-Dec 2024)
   - 1-minute candles
   - 100 lookback bars
   - Track improvements

## Risk Considerations

⚠️ **Warning**: Relaxing filters will increase trade frequency BUT may also:
- Increase false positives (losing trades)
- Reduce win rate temporarily
- Increase max drawdown

**Mitigation**:
- Start with paper trading to validate
- Use smaller position sizes initially (0.5% risk)
- Monitor for 2 weeks before going live
- Keep daily loss limit strict (2% max)

## Conclusion

Current strategies are **TOO CONSERVATIVE** for intraday trading of liquid large-cap stocks. They're optimized for reducing false positives at the expense of missing real opportunities.

**Recommended Balance**:
- Aim for 10-15 trades/symbol/month (not 1/quarter)
- Win rate 55-60% (not 100% from 1 lucky trade)
- Profit factor 1.5-2.0 (sustainable)
- Small, frequent profits with controlled losses

**Next Steps**: Implement Priority 1 changes, run new backtest, compare results.
