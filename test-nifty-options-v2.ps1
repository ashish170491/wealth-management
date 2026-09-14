# Test strategy on Nifty call options - small time range
Write-Host "`n================================" -ForegroundColor Cyan
Write-Host "NIFTY OPTIONS STRATEGY TEST" -ForegroundColor Cyan
Write-Host "================================`n" -ForegroundColor Cyan

Write-Host "Configuration:" -ForegroundColor Yellow
Write-Host "- Period: December 15-22, 2025 (1 week)" -ForegroundColor Gray
Write-Host "- Instrument: Nifty Call Options" -ForegroundColor Gray
Write-Host "- Initial Capital: Rs.50,000" -ForegroundColor Gray
Write-Host "- Lookback: 50 periods`n" -ForegroundColor Gray

# Nifty options - using ATM strike (assuming Nifty around 24000 range)
$symbols = @(
    "NFO:NIFTY24000CE",      # Nifty 24000 Call
    "NFO:NIFTY24100CE",      # Nifty 24100 Call
    "NFO:BANKNIFTY49000CE"   # Bank Nifty 49000 Call
)

Write-Host "Symbols to test:" -ForegroundColor Yellow
$symbols | ForEach-Object { Write-Host "  - $_" -ForegroundColor Gray }
Write-Host ""

# Test 1: EMA Crossover Strategy
Write-Host "`n[TEST 1] EMA Crossover Strategy on Options" -ForegroundColor Magenta
$body = @{
    strategyName = "EMA_Crossover_9_21"
    symbols = $symbols
    startDate = "2025-12-15"
    endDate = "2025-12-22"
    initialCapital = 50000
    lookbackPeriod = 50
    maxPositions = 10
    riskPerTrade = 0.5
} | ConvertTo-Json

Write-Host "Sending request to: http://localhost:8080/api/backtest/run" -ForegroundColor Gray
Write-Host "Body: $body" -ForegroundColor Gray

try {
    $response = Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/run" `
        -Method POST `
        -ContentType "application/json" `
        -Body $body `
        -ErrorAction Stop
    
    Write-Host "[SUCCESS] Response received:" -ForegroundColor Green
    Write-Host "  Total Trades: $($response.totalTrades)" -ForegroundColor White
    Write-Host "  Win Rate: $($response.winRate)%" -ForegroundColor White
    Write-Host "  Total Return: $($response.totalReturn)%" -ForegroundColor White
    Write-Host "  Max Drawdown: $($response.maxDrawdown)%" -ForegroundColor White
    Write-Host "  Final Capital: Rs.$($response.finalCapital)" -ForegroundColor Yellow
} catch {
    Write-Host "[ERROR]: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Status Code: $($_.Exception.Response.StatusCode)" -ForegroundColor Red
    Write-Host "Response: $($_.Exception.Response | Select-Object -Property * | Out-String)" -ForegroundColor Yellow
    Write-Host "  Make sure the Spring Boot application is running on port 8080" -ForegroundColor Yellow
    exit 1
}

# Test 2: VWAP Strategy
Write-Host "`n[TEST 2] VWAP Strategy on Options" -ForegroundColor Magenta
$body = @{
    strategyName = "VWAP_20_1.5"
    symbols = $symbols
    startDate = "2025-12-15"
    endDate = "2025-12-22"
    initialCapital = 50000
    lookbackPeriod = 50
    maxPositions = 10
    riskPerTrade = 0.5
} | ConvertTo-Json

try {
    $response = Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/run" `
        -Method POST `
        -ContentType "application/json" `
        -Body $body `
        -ErrorAction Stop
    
    Write-Host "[SUCCESS] Response received:" -ForegroundColor Green
    Write-Host "  Total Trades: $($response.totalTrades)" -ForegroundColor White
    Write-Host "  Win Rate: $($response.winRate)%" -ForegroundColor White
    Write-Host "  Total Return: $($response.totalReturn)%" -ForegroundColor White
    Write-Host "  Final Capital: Rs.$($response.finalCapital)" -ForegroundColor Yellow
} catch {
    Write-Host "[ERROR]: $($_.Exception.Message)" -ForegroundColor Red
}

# Test 3: ORB Strategy
Write-Host "`n[TEST 3] ORB (Opening Range Breakout) Strategy on Options" -ForegroundColor Magenta
$body = @{
    strategyName = "ORB_15min"
    symbols = $symbols
    startDate = "2025-12-15"
    endDate = "2025-12-22"
    initialCapital = 50000
    lookbackPeriod = 50
    maxPositions = 10
    riskPerTrade = 0.5
} | ConvertTo-Json

try {
    $response = Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/run" `
        -Method POST `
        -ContentType "application/json" `
        -Body $body `
        -ErrorAction Stop
    
    Write-Host "[SUCCESS] Response received:" -ForegroundColor Green
    Write-Host "  Total Trades: $($response.totalTrades)" -ForegroundColor White
    Write-Host "  Win Rate: $($response.winRate)%" -ForegroundColor White
    Write-Host "  Total Return: $($response.totalReturn)%" -ForegroundColor White
    Write-Host "  Final Capital: Rs.$($response.finalCapital)" -ForegroundColor Yellow
} catch {
    Write-Host "[ERROR]: $($_.Exception.Message)" -ForegroundColor Red
}

# Test 4: RSI Mean Reversion
Write-Host "`n[TEST 4] RSI Mean Reversion Strategy on Options" -ForegroundColor Magenta
$body = @{
    strategyName = "RSI_MR_14_30_70"
    symbols = $symbols
    startDate = "2025-12-15"
    endDate = "2025-12-22"
    initialCapital = 50000
    lookbackPeriod = 50
    maxPositions = 10
    riskPerTrade = 0.5
} | ConvertTo-Json

try {
    $response = Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/run" `
        -Method POST `
        -ContentType "application/json" `
        -Body $body `
        -ErrorAction Stop
    
    Write-Host "[SUCCESS] Response received:" -ForegroundColor Green
    Write-Host "  Total Trades: $($response.totalTrades)" -ForegroundColor White
    Write-Host "  Win Rate: $($response.winRate)%" -ForegroundColor White
    Write-Host "  Total Return: $($response.totalReturn)%" -ForegroundColor White
    Write-Host "  Final Capital: Rs.$($response.finalCapital)" -ForegroundColor Yellow
} catch {
    Write-Host "[ERROR]: $($_.Exception.Message)" -ForegroundColor Red
}

# Test 5: ALL strategies combined
Write-Host "`n[TEST 5] ALL Strategies Combined on Options" -ForegroundColor Magenta
$body = @{
    strategyName = "ALL"
    symbols = $symbols
    startDate = "2025-12-15"
    endDate = "2025-12-22"
    initialCapital = 50000
    lookbackPeriod = 50
    maxPositions = 10
    riskPerTrade = 0.5
} | ConvertTo-Json

try {
    $response = Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/run" `
        -Method POST `
        -ContentType "application/json" `
        -Body $body `
        -ErrorAction Stop
    
    Write-Host "[SUCCESS] Response received:" -ForegroundColor Green
    Write-Host "  Total Trades: $($response.totalTrades)" -ForegroundColor White
    Write-Host "  Win Rate: $($response.winRate)%" -ForegroundColor White
    Write-Host "  Total Return: $($response.totalReturn)%" -ForegroundColor White
    Write-Host "  Max Drawdown: $($response.maxDrawdown)%" -ForegroundColor White
    Write-Host "  Final Capital: Rs.$($response.finalCapital)" -ForegroundColor Yellow
    Write-Host "  Sharpe Ratio: $($response.sharpeRatio)" -ForegroundColor White
    Write-Host "  Profit Factor: $($response.profitFactor)" -ForegroundColor White
} catch {
    Write-Host "[ERROR]: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "`n================================" -ForegroundColor Cyan
Write-Host "Test Complete" -ForegroundColor Cyan
Write-Host "================================`n" -ForegroundColor Cyan

Write-Host "[INFO] Interpretation Guide:" -ForegroundColor Yellow
Write-Host "  - Win Rate greater than 50%: Strategy is profitable" -ForegroundColor Gray
Write-Host "  - Max Drawdown less than 10%: Risk is acceptable" -ForegroundColor Gray
Write-Host "  - Sharpe Ratio greater than 1.0: Good risk-adjusted returns" -ForegroundColor Gray
Write-Host "  - Profit Factor greater than 1.0: Gains exceed losses" -ForegroundColor Gray
