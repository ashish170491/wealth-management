# Check available strategies and test backtest
Write-Host "Checking available strategies..." -ForegroundColor Cyan

try {
    $strategies = Invoke-RestMethod -Uri "http://localhost:8080/api/backtest/strategies" -Method GET
    Write-Host "Available strategies:" -ForegroundColor Green
    $strategies | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
    
    Write-Host "`nNow run: .\test-backtest.ps1" -ForegroundColor Cyan
} catch {
    Write-Host "ERROR: Cannot connect to application" -ForegroundColor Red
    Write-Host "Start the application first with: mvn spring-boot:run" -ForegroundColor Yellow
}
