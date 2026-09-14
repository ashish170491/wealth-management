# Test Sector Scanner Email
# Triggers sector scan manually and sends email alert

Write-Host "=====================================" -ForegroundColor Cyan
Write-Host "  Sector Reversal Scanner Test" -ForegroundColor Cyan
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host ""

$baseUrl = "http://localhost:8080"
$scanEndpoint = "$baseUrl/api/sector-scanner/scan-now"

Write-Host "[1/2] Triggering sector scan..." -ForegroundColor Yellow

try {
    $response = Invoke-RestMethod -Uri $scanEndpoint -Method Post -ContentType "application/json"
    
    if ($response.success) {
        Write-Host "Success: Scan completed successfully!" -ForegroundColor Green
        Write-Host ""
        Write-Host "Scan Results:" -ForegroundColor Cyan
        Write-Host "  Scan Time: $($response.scanTime)" -ForegroundColor Gray
        Write-Host "  Reversing Sectors: $($response.reversingSectors)" -ForegroundColor Gray
        Write-Host "  Top Stock Picks: $($response.topPicks)" -ForegroundColor Gray
        Write-Host ""
        
        if ($response.reversingSectors -gt 0) {
            Write-Host "Success: Email alert should be sent to: the configured report recipient" -ForegroundColor Green
            Write-Host ""
            Write-Host "Check your email inbox for sector reversal alert!" -ForegroundColor Yellow
        } else {
            Write-Host "Warning: No reversing sectors found - email not sent" -ForegroundColor Yellow
            Write-Host ""
            Write-Host "This might be because:" -ForegroundColor Gray
            Write-Host "  - Market is closed or outside scan hours" -ForegroundColor Gray
            Write-Host "  - No sectors showing reversal signals currently" -ForegroundColor Gray
            Write-Host "  - Stock scores below 45 threshold" -ForegroundColor Gray
        }
    } else {
        Write-Host "Error: Scan failed: $($response.error)" -ForegroundColor Red
    }
    
} catch {
    Write-Host "Error: Error connecting to server: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host ""
    Write-Host "Make sure the application is running:" -ForegroundColor Yellow
    Write-Host "  mvn spring-boot:run" -ForegroundColor Gray
}

Write-Host ""
Write-Host "[2/2] Checking scan status..." -ForegroundColor Yellow

try {
    $statusResponse = Invoke-RestMethod -Uri "$baseUrl/api/sector-scanner/status" -Method Get
    
    Write-Host "Success: Scanner status retrieved" -ForegroundColor Green
    Write-Host ""
    Write-Host "Scanner Status:" -ForegroundColor Cyan
    Write-Host "  Last Scan: $($statusResponse.lastScanTime)" -ForegroundColor Gray
    Write-Host "  Total Sectors: $($statusResponse.totalSectorsTracked)" -ForegroundColor Gray
    Write-Host "  Reversing Sectors: $($statusResponse.reversingSectorsCount)" -ForegroundColor Gray
    Write-Host "  Total Stock Picks: $($statusResponse.topPicksCount)" -ForegroundColor Gray
    
} catch {
    Write-Host "Warning: Could not retrieve status" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "=====================================" -ForegroundColor Cyan
Write-Host "Test Complete!" -ForegroundColor Green
Write-Host "=====================================" -ForegroundColor Cyan
