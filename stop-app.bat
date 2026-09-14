@echo off
echo ========================================
echo  Intraday Trading App - Stop Script
echo ========================================
echo.

echo Checking for process on port 8080...
set "found=0"
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080 " ^| findstr "LISTENING"') do (
    set "found=1"
    echo Found process %%a on port 8080. Stopping it...
    taskkill /PID %%a /F
    if %ERRORLEVEL% neq 0 (
        echo [ERROR] Failed to stop process %%a.
        exit /b 1
    )
    echo Process %%a stopped successfully.
)
if "%found%"=="0" (
    echo No process found running on port 8080.
)
