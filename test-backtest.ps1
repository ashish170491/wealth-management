# Test backtest with all 5 improvements
$uri = "http://localhost:8080/api/backtest/run"
$body = @{
    symbols = @("NSE:RELIANCE")
    startDate = "2025-12-01"
    endDate = "2025-12-10"
    initialCapital = 100000
} | ConvertTo-Json

Write-Host "`nTesting backtest API with improved strategy..." -ForegroundColor Cyan
Write-Host "Request: $body`n" -ForegroundColor Gray

try {
    $response = Invoke-RestMethod -Uri $uri -Method POST -ContentType "application/json" -Body $body -ErrorAction Stop
    
    Write-Host "=== BACKTEST RESULTS ===" -ForegroundColor Green
    Write-Host "Total Trades: $($response.totalTrades)"
    Write-Host "Win Rate: $($response.winRate)%"
    Write-Host "Total Return: $($response.totalReturn)%"
    Write-Host "Max Drawdown: $($response.maxDrawdown)%"
    Write-Host "Final Capital: ₹$($response.finalCapital)`n" -ForegroundColor Yellow
    
    $response | ConvertTo-Json -Depth 10 | Out-File "backtest-results-improved.json"
    Write-Host "Full results saved to backtest-results-improved.json" -ForegroundColor Green
    
    if ($response.totalTrades -eq 0) {
        Write-Host "`nWARNING: No trades generated. Check:" -ForegroundColor Red
        Write-Host "- Strategy is registered correctly"
        Write-Host "- Market data is available for the date range"
        Write-Host "- Trend filter isn't too restrictive"
    }
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Is the application running on port 8080?" -ForegroundColor Yellow
}
