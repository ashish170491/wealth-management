# VWAP NaN Issue - Analysis & Fix

## Problem Analysis

**Issue Found in Logs:**
```
VWAP: NaN, Price: 52189.50, Volume: 0 (Avg: 0), Confirmed: false
```

### Root Causes

1. **Zero Volume in Historical Data**
   - The VWAP indicator requires volume data to calculate: `(Price × Volume) / Total Volume`
   - When volume is 0 across all bars, TA4J's VWAP calculation returns NaN
   - This happens when broker API returns candles with missing/zero volume fields

2. **Silent Failures in Data Parsing**
   - The `num()` helper function in `StrategyUtils` was returning 0.0 without logging
   - Missing or null volume values were silently converted to 0.0
   - No way to debug why volume data was missing

3. **No NaN Validation**
   - VwapStrategy was not checking if VWAP calculation failed
   - The NaN value was logged but no corrective action was taken
   - Strategy continued with invalid calculations

---

## Fixes Implemented

### 1. **VwapStrategy.java** - Enhanced Validation

Added three protective checks:

```java
// Check 1: Validate total volume before calculation
long totalVolume = 0;
for (int i = Math.max(0, endIndex - volumeLookback); i <= endIndex; i++) {
    totalVolume += series.getBar(i).getVolume().longValue();
}

if (totalVolume == 0) {
    log.debug("{} - No volume data available for VWAP calculation, skipping strategy", symbol);
    return TradingSignal.hold(symbol, "No volume data available");
}

// Check 2: Validate VWAP result for NaN
if (Double.isNaN(vwap) || Double.isNaN(prevVwap) || vwap == 0.0) {
    log.debug("{} - VWAP calculation resulted in NaN or invalid value, skipping signal", symbol);
    return TradingSignal.hold(symbol, "VWAP calculation failed (NaN)");
}
```

**Impact:** Prevents trading on invalid VWAP values

### 2. **StrategyUtils.java** - Improved Data Parsing

Enhanced `num()` function with detailed logging:

```java
private static Number num(Object val) {
    if (val == null) {
        log.warn("Null value encountered in OHLCV conversion, using 0.0");
        return 0.0;
    }
    if (val instanceof Number n)
        return n;
    if (val instanceof String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse numeric value: {}, using 0.0", s);
            return 0.0;
        }
    }
    log.warn("Unexpected value type: {}, using 0.0", val.getClass());
    return 0.0;
}
```

**Impact:** Logs warnings when volume data is missing, helping identify data quality issues

---

## Expected Behavior After Fix

### Before Fix
```
VWAP: NaN, Price: 52189.50, Volume: 0 (Avg: 0), Confirmed: false
❌ Strategy generates invalid signals
❌ No clear error message
```

### After Fix
```
No volume data available for VWAP calculation, skipping strategy
✅ Strategy skips with clear reason
✅ No NaN signals generated
✅ Logs show data quality issues
```

---

## Testing Recommendation

To verify the fix works:

1. **Run the backtest** with the updated code
   ```powershell
   mvn package -DskipTests -q
   mvn spring-boot:run
   # In another terminal:
   .\test-nifty-options.ps1
   ```

2. **Check logs for**:
   - ✅ No "VWAP: NaN" entries
   - ✅ Clear "No volume data available" messages (if data is missing)
   - ✅ Valid VWAP values when volume data is present

3. **Verify metrics**:
   - Win Rate > 50% is good
   - Max Drawdown < 10% is acceptable
   - Profit Factor > 1.0 means strategy is profitable

---

## Impact on Other Strategies

This fix approach (checking for NaN and invalid indicator values) should be applied to:
- RSI Strategy (RSI indicator can be NaN if insufficient data)
- EMA Crossover (EMA values should not be NaN)
- ORB Strategy (ATR values should not be NaN)

Consider adding similar validation to all indicator-based strategies.

---

## Data Quality Issues to Investigate

If volume remains 0 after these fixes:
1. Check Kite API's historical data response format
2. Verify mock data generator is being used (fallback when broker data unavailable)
3. Ensure broker authentication is working (fresh access token daily at 08:30 IST)
4. Check if date range is within market hours (09:15 - 15:30 IST)

