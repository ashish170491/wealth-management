@echo off
REM All output of this script is appended to logs\start-app.log so a failed
REM scheduled run leaves a trace. Previously everything went to the console
REM (discarded by Task Scheduler) and spring-boot:run went to nul, so a boot
REM failure was completely silent - two consecutive mornings failed unseen.
if not exist "%~dp0logs" mkdir "%~dp0logs"
call :run >> "%~dp0logs\start-app.log" 2>&1
set "RC=%ERRORLEVEL%"
exit /b %RC%

:run
echo.
echo ========================================
echo  Intraday Trading App - Start Script
echo  Run at %DATE% %TIME%
echo ========================================
echo.

REM Credentials live OUTSIDE this repo, in %USERPROFILE%\.intraday\application.yml.
REM Spring Boot loads that directory in addition to the packaged config, so the values
REM there fill in the ${VAR:} placeholders in src/main/resources/application.yml.
REM Nothing secret is stored in this repository.
set "SPRING_CONFIG_ADDITIONAL_LOCATION=file:///%USERPROFILE:\=/%/.intraday/"
echo [Config] Credentials location: %SPRING_CONFIG_ADDITIONAL_LOCATION%
echo [Config] JAVA_HOME=%JAVA_HOME%
echo.

REM Step 1: Clean and compile
echo [Step 1] Cleaning and compiling the project...
call mvn clean compile
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Build failed with exit code %ERRORLEVEL%. Aborting.
    exit /b 1
)
echo [Step 1] Build successful.
echo.

REM Step 2: Check and kill process on port 8080
echo [Step 2] Checking for process on port 8080...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080 " ^| findstr "LISTENING"') do (
    echo Found process %%a on port 8080. Stopping it...
    taskkill /PID %%a /F
)
echo [Step 2] Port 8080 is free.
echo.

REM Step 3: Start the application in background.
REM Console output goes to logs\app-console.log (truncated each start) so a boot
REM failure before logback initialises is still visible somewhere.
echo [Step 3] Starting the application in background...
start "Intraday Trading App" /B mvn spring-boot:run > "%~dp0logs\app-console.log" 2>&1
echo [Step 3] Application started in background. Logs: logs\trading-app.log
exit /b 0
