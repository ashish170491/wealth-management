@echo off
setlocal
set "LOG=%~dp0logs\start-app.log"
if not exist "%~dp0logs" mkdir "%~dp0logs"

REM The build and port-kill phases are logged so a failed scheduled run leaves a trace -
REM before this the whole script wrote to a console Task Scheduler discards, and two
REM consecutive 09:00 failures produced no evidence at all (B-114).
REM
REM Each phase closes its own redirect. Do NOT wrap the whole script in one: `start` below
REM hands its inherited handles to a cmd child that lives as long as the app, which keeps
REM the log file open and makes the NEXT run die silently at the redirect - the same
REM invisible failure this logging exists to prevent (found 2026-09-16, B-114).
REM A leaked handle on the log (an older build of this script let the app JVM inherit one)
REM must never stop the app from starting. Probe first and fall back rather than dying at
REM the redirect, which produces exactly the silent failure this logging exists to prevent.
>>"%LOG%" echo. 2>nul || set "LOG=%~dp0logs\start-app-alt-%RANDOM%.log"

call :build >> "%LOG%" 2>&1
if errorlevel 1 exit /b 1
call :freeport >> "%LOG%" 2>&1

REM Step 3 deliberately runs outside any redirect of ours. The JVM's own console goes to nul
REM as before; logback initialises early enough that a boot failure still reaches
REM logs\trading-app.log.
start "Intraday Trading App" /B mvn spring-boot:run > nul 2>&1
>> "%LOG%" echo [Step 3] Launch issued at %TIME%. App log: logs\trading-app.log
exit /b 0

:build
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

echo [Step 1] Cleaning and compiling the project...
call mvn clean compile
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Build failed with exit code %ERRORLEVEL%. Aborting.
    exit /b 1
)
echo [Step 1] Build successful.
exit /b 0

:freeport
echo.
echo [Step 2] Checking for process on port 8080...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080 " ^| findstr "LISTENING"') do (
    echo Found process %%a on port 8080. Stopping it...
    taskkill /PID %%a /F
)
REM The Maven launcher and its cmd wrapper outlive the app they started and hold the
REM project's target\ directory. Leaving them behind is how a later `mvn clean` fails.
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='cmd.exe' OR Name='java.exe'\" | Where-Object { $_.CommandLine -like '*spring-boot:run*' } | ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop; Write-Output ('Stopped leftover launcher PID ' + $_.ProcessId) } catch {} }"
echo [Step 2] Port 8080 is free.
exit /b 0
