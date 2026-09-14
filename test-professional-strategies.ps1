$uri = "http://localhost:8080/api/backtest/run"
$body = @{
    symbols = @("NSE:RELIANCE", "NSE:INFY", "NSE:TCS", "NSE:HDFCBANK")
    startDate = "2025-12-01"
    endDate = "2025-12-20"
    initialCapital = 10000
} | ConvertTo-Json

Write-Host "`nRunning Professional Strategy Backtest (ORB + VWAP Pullback + PDH/PDL)..." -ForegroundColor Cyan
Write-Host "Initial Capital: ₹10,000" -ForegroundColor Yellow
Write-Host "Period: December 1-20, 2025" -ForegroundColor Yellow
Write-Host "Symbols: RELIANCE, INFY, TCS, HDFCBANK`n" -ForegroundColor Yellow

try {
    $response = Invoke-RestMethod -Uri $uri -Method POST -ContentType "application/json" -Body $body -ErrorAction Stop
    $response | ConvertTo-Json -Depth 10 | Out-File "backtest-professional-strategies.json"
    
    Write-Host "=== PROFESSIONAL STRATEGY RESULTS ===" -ForegroundColor Green
    Write-Host "Total Trades: $($response.totalTrades)"
    Write-Host "Winning Trades: $($response.winningTrades) ($($response.winRate)%)" -ForegroundColor Green
    Write-Host "Losing Trades: $($response.losingTrades)"
    Write-Host "Profit Factor: $($response.profitFactor)"
    Write-Host "Total Return: $($response.totalReturn)%" -ForegroundColor $(if ($response.totalReturn -gt 0) { "Green" } else { "Red" })
    Write-Host "Final Capital: ₹$($response.finalCapital)" -ForegroundColor Yellow
    Write-Host "Max Drawdown: $($response.maxDrawdown)%"
    Write-Host "`nResults saved to: backtest-professional-strategies.json" -ForegroundColor Cyan
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
}
