$uri = "http://localhost:8080/api/backtest/run"
$body = @{
    symbols = @("NSE:RELIANCE", "NSE:INFY", "NSE:TCS", "NSE:HDFCBANK")
    startDate = "2025-11-01"
    endDate = "2025-12-20"
    initialCapital = 10000
} | ConvertTo-Json

Write-Host "`n=== EXTENDED BACKTEST: Professional Strategies ===" -ForegroundColor Cyan
Write-Host "Period: November 1 - December 20, 2025 (50 days)" -ForegroundColor Yellow
Write-Host "Initial Capital: ₹10,000" -ForegroundColor Yellow
Write-Host "Strategies: EMA Crossover + VWAP + ORB" -ForegroundColor Yellow
Write-Host "Symbols: RELIANCE, INFY, TCS, HDFCBANK`n" -ForegroundColor Yellow

try {
    Write-Host "Running backtest... (this may take 1-2 minutes)" -ForegroundColor Gray
    $response = Invoke-RestMethod -Uri $uri -Method POST -ContentType "application/json" -Body $body -ErrorAction Stop
    $response | ConvertTo-Json -Depth 10 | Out-File "backtest-extended-nov-dec.json"
    
    Write-Host "`n=== RESULTS ===" -ForegroundColor Green
    Write-Host "Total Trades: $($response.totalTrades)"
    Write-Host "Winning Trades: $($response.winningTrades) (Win Rate: $($response.winRate)%)" -ForegroundColor $(if ($response.winRate -gt 50) { "Green" } else { "Yellow" })
    Write-Host "Losing Trades: $($response.losingTrades)"
    Write-Host "Profit Factor: $($response.profitFactor)" -ForegroundColor $(if ($response.profitFactor -gt 1) { "Green" } else { "Red" })
    Write-Host "Average Win: ₹$([math]::Round($response.averageWin, 2))"
    Write-Host "Average Loss: ₹$([math]::Round($response.averageLoss, 2))"
    Write-Host "`nTotal Return: $($response.totalReturn)%" -ForegroundColor $(if ($response.totalReturn -gt 0) { "Green" } else { "Red" })
    Write-Host "Final Capital: ₹$($response.finalCapital)" -ForegroundColor Yellow
    Write-Host "Max Drawdown: $($response.maxDrawdown)%"
    Write-Host "Sharpe Ratio: $([math]::Round($response.sharpeRatio, 2))"
    Write-Host "`nResults saved to: backtest-extended-nov-dec.json" -ForegroundColor Cyan
} catch {
    Write-Host "`nERROR: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Make sure the application is running on port 8080" -ForegroundColor Yellow
}
