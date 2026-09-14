$uri = "http://localhost:8080/api/backtest/run"
$body = @{
    symbols = @("NSE:NIFTY 50", "NSE:NIFTY BANK", "NSE:RELIANCE", "NSE:INFY", "NSE:TCS", "NSE:HDFCBANK", "NSE:SBIN", "NSE:ICICIBANK")
    startDate = "2024-10-01"
    endDate = "2024-11-30"
    initialCapital = 10000
} | ConvertTo-Json

Write-Host "`n=== HISTORICAL BACKTEST: Oct-Nov 2024 ===" -ForegroundColor Cyan
Write-Host "Period: October 1 - November 30, 2024 (2 months)" -ForegroundColor Yellow
Write-Host "Initial Capital: Rs.10,000" -ForegroundColor Yellow
Write-Host "Strategies: Hybrid (ORB, VWAP, PDH/PDL) on Stocks & Indices" -ForegroundColor Yellow
Write-Host "Symbols: NIFTY 50, BANKNIFTY + Top 6 Stocks`n" -ForegroundColor Yellow

try {
    Write-Host "Running backtest with REAL historical data..." -ForegroundColor Gray
    $response = Invoke-RestMethod -Uri $uri -Method POST -ContentType "application/json" -Body $body -ErrorAction Stop
    $response | ConvertTo-Json -Depth 10 | Out-File "backtest-oct-nov-2024.json"
    
    Write-Host "`n=== BACKTEST RESULTS ===" -ForegroundColor Green
    Write-Host "`nTrade Statistics:"
    Write-Host "  Total Trades: $($response.totalTrades)"
    Write-Host "  Winning: $($response.winningTrades) | Losing: $($response.losingTrades)"
    Write-Host "  Win Rate: $($response.winRate)%" -ForegroundColor $(if ($response.winRate -gt 50) { "Green" } else { "Yellow" })
    Write-Host "`nPerformance Metrics:"
    Write-Host "  Profit Factor: $($response.profitFactor)" -ForegroundColor $(if ($response.profitFactor -gt 1) { "Green" } else { "Red" })
    Write-Host "  Average Win: Rs.$([math]::Round($response.averageWin, 2))"
    Write-Host "  Average Loss: Rs.$([math]::Round($response.averageLoss, 2))"
    Write-Host "  Sharpe Ratio: $([math]::Round($response.sharpeRatio, 2))"
    Write-Host "`nCapital Performance:"
    Write-Host "  Initial: Rs.10,000"
    Write-Host "  Final: Rs.$($response.finalCapital)" -ForegroundColor Yellow
    Write-Host "  Total Return: $($response.totalReturn)%" -ForegroundColor $(if ($response.totalReturn -gt 0) { "Green" } else { "Red" })
    Write-Host "  Max Drawdown: $($response.maxDrawdown)%"
    Write-Host "`nResults saved to: backtest-oct-nov-2024.json" -ForegroundColor Cyan
} catch {
    Write-Host "`nERROR: $($_.Exception.Message)" -ForegroundColor Red
}
