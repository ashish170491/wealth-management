# Test all strategies - both individually and combined (like live trading)
Write-Host "Testing strategies with Oct 1-31 backtest..." -ForegroundColor Cyan

# Test 1: ALL strategies together (Live Trading Mode)
Write-Host "`n========== Testing ALL Strategies Together (Live Trading Mode) ==========" -ForegroundColor Magenta

$multiStrategyBody = @{
    strategyName = "ALL"  # Use all strategies
    symbols = @("NSE:RELIANCE", "NSE:TCS")
    startDate = "2025-10-01"
    endDate = "2025-10-31"
    initialCapital = 100000
    lookbackPeriod = 100
    maxPositions = 200
    riskPerTrade = 1.0
} | ConvertTo-Json

try {
    $response = Invoke-WebRequest -Uri "http://localhost:8080/api/backtest/run" `
        -Method POST `
        -ContentType "application/json" `
        -Body $multiStrategyBody `
        -UseBasicParsing
    
    Write-Host "Status: $($response.StatusCode)" -ForegroundColor Green
    Start-Sleep -Seconds 2
    Get-Content "logs\trading-app.log" -Tail 50 | Select-String -Pattern "(MULTI-STRATEGY|Total Trades|Win Rate|Total Return|Max Drawdown)" | Select-Object -Last 15
} catch {
    Write-Host "Error: $_" -ForegroundColor Red
}

Start-Sleep -Seconds 2

# Test 2: Individual strategies
Write-Host "`n========== Testing Individual Strategies ==========" -ForegroundColor Yellow

$strategies = @(
    "EMA_Crossover_9_21",
    "VWAP_20_1.5", 
    "ORB_15min",
    "RSI_MR_14_30_70"
)

foreach ($strategy in $strategies) {
    Write-Host "`n--- Testing $strategy ---" -ForegroundColor Cyan
    
    $body = @{
        strategyName = $strategy
        symbols = @("NSE:RELIANCE", "NSE:TCS")
        startDate = "2025-10-01"
        endDate = "2025-10-31"
        initialCapital = 100000
        lookbackPeriod = 100
        maxPositions = 200
        riskPerTrade = 1.0
    } | ConvertTo-Json
    
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:8080/api/backtest/run" `
            -Method POST `
            -ContentType "application/json" `
            -Body $body `
            -UseBasicParsing
        
        Write-Host "Status: $($response.StatusCode)" -ForegroundColor Green
        Start-Sleep -Seconds 1
        Get-Content "logs\trading-app.log" -Tail 30 | Select-String -Pattern "($strategy|Total Trades|Win Rate|Total Return)" | Select-Object -Last 5
    } catch {
        Write-Host "Error: $_" -ForegroundColor Red
    }
    
    Start-Sleep -Seconds 1
}

Write-Host "`n========== All Strategy Tests Complete ==========" -ForegroundColor Green
Write-Host "Review the logs above to compare individual vs combined performance" -ForegroundColor Yellow
