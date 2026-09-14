# Quick Test Commands - Improved Strategies

## Multi-Symbol Backtest (Recommended First)
```powershell
cd c:\spring-boot-tutorial\intraday-app

$body = @{
    strategyName = "ALL"
    symbols = @("NSE:RELIANCE", "NSE:TCS", "NSE:INFY", "NSE:HDFCBANK", "NSE:ICICIBANK")
    startDate = "2025-10-01"
    endDate = "2025-12-20"
    initialCapital = 100000
    lookbackPeriod = 50
    maxPositions = 5
    riskPerTrade = 1.0
} | ConvertTo-Json

$result = Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/run" -Method Post -Body $body -ContentType "application/json" -TimeoutSec 300
$result | ConvertTo-Json -Depth 10 | Out-File "backtest-multi-symbol-improved.json"
$result | Format-List
```

## Extended Period Backtest (6 months)
```powershell
$body = @{
    strategyName = "ALL"
    symbols = @("NSE:RELIANCE")
    startDate = "2025-07-01"
    endDate = "2025-12-20"
    initialCapital = 100000
    lookbackPeriod = 50
    maxPositions = 5
    riskPerTrade = 1.0
} | ConvertTo-Json

$result = Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/run" -Method Post -Body $body -ContentType "application/json" -TimeoutSec 300
$result | ConvertTo-Json -Depth 10 | Out-File "backtest-6months-improved.json"
Write-Host "Trades: $($result.totalTrades), Win Rate: $($result.winRate)%, Return: $($result.totalReturn)%"
```

## Single Strategy Testing
```powershell
# Test RSI only
$body = @{
    strategyName = "RSI_MR_14_30_70"
    symbols = @("NSE:RELIANCE")
    startDate = "2025-10-01"
    endDate = "2025-12-20"
    initialCapital = 100000
    lookbackPeriod = 40
    maxPositions = 3
    riskPerTrade = 1.0
} | ConvertTo-Json

$result = Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/run" -Method Post -Body $body -ContentType "application/json"
Write-Host "RSI Strategy: $($result.totalTrades) trades"
```

## View Available Strategies
```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/strategies" -Method Get
```

## Check Application Status
```powershell
# Health check
Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" -Method Get

# View recent logs
Get-Content "logs\trading-app-local.log" -Tail 50
```

## Summary of Improvements

### Parameter Changes
| Strategy | Parameter | Before | After |
|----------|-----------|--------|-------|
| RSI MR | Min History | 60 bars | 40 bars |
| RSI MR | Oversold | 35 | 40 |
| RSI MR | Overbought | 65 | 60 |
| VWAP | Direction Bias | 3 bars | 2 bars |
| EMA | Trend Threshold | 0.15% | 0.08% |
| EMA | Volume Multiplier | 1.2x | 1.1x |

### Expected Impact
- **Signal Generation**: 20-50% more opportunities
- **RSI**: Can trigger 20 bars (100 min) earlier
- **VWAP**: Faster reaction to crossovers
- **All**: Bidirectional trading (BUY + SELL)

Run the multi-symbol test to validate!
