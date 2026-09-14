# Registers the weekday start/stop tasks for the trading app, and stops the machine
# sleeping through market hours while plugged in.
#
# WHY THE EXTRA SETTINGS MATTER (2026-08-22 post-mortem):
# The app failed to run at all on Wed 19 and Fri 21 Aug 2026, and slept through
# 14:54-15:30 on Thu 20 Aug — losing the entire afternoon report batch (holdings emails,
# recommendation outcomes, tax-lot capture) plus every Friday-only weekly report. That
# cost more report coverage than any code bug in the same period.
#
# Root cause was the LIVE task settings, which were the Task Scheduler GUI defaults
# rather than what this script intended:
#   DisallowStartIfOnBatteries = True  -> task REFUSED to start on battery. This is
#                                         result code 0x800710E0 ("the operator or
#                                         administrator has refused the request"), which
#                                         is exactly what Fri 21 Aug recorded.
#   StopIfGoingOnBatteries     = True  -> unplugging mid-session killed the app.
#   WakeToRun                  = False -> machine asleep at 09:00 = no run, silently.
#   StartWhenAvailable         = False -> a missed trigger was never retried.
#
# Re-running this script MUST NOT reintroduce those. The verification block at the bottom
# fails loudly if it does, so a silent regression can't happen twice.

$ErrorActionPreference = 'Stop'
$appDir = "C:\spring-boot-tutorial\intraday-app"

# Remove existing tasks if any
Unregister-ScheduledTask -TaskName "TradingApp-Start" -Confirm:$false -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName "TradingApp-Stop" -Confirm:$false -ErrorAction SilentlyContinue

# Shared settings. -WakeToRun is the critical one: without it a sleeping machine simply
# skips the trigger. -StartWhenAvailable is the backstop if it is skipped anyway.
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -WakeToRun `
    -ExecutionTimeLimit (New-TimeSpan -Hours 8)

# Create Start task.
# Weekdays 9:00 AM for market hours; Saturday 7:50 AM for the weekly multibagger
# screening window (SPEC §3.4 carve-out: screening 08:00 SAT, report email 09:00 SAT).
$startAction = New-ScheduledTaskAction -Execute "$appDir\start-app.bat" -WorkingDirectory $appDir
$startTriggerWeek = New-ScheduledTaskTrigger -Weekly -DaysOfWeek Monday,Tuesday,Wednesday,Thursday,Friday -At 9:00AM
$startTriggerSat = New-ScheduledTaskTrigger -Weekly -DaysOfWeek Saturday -At 7:50AM
Register-ScheduledTask -TaskName "TradingApp-Start" -Action $startAction -Trigger @($startTriggerWeek, $startTriggerSat) `
    -Settings $settings -Description "Start trading app before market hours (and Saturday screening window)" -RunLevel Highest | Out-Null

# Create Stop task. Weekdays 3:45 PM; Saturday 10:35 AM (after the 07:45-10:30 in-app window).
$stopAction = New-ScheduledTaskAction -Execute "$appDir\stop-app.bat" -WorkingDirectory $appDir
$stopTriggerWeek = New-ScheduledTaskTrigger -Weekly -DaysOfWeek Monday,Tuesday,Wednesday,Thursday,Friday -At 3:45PM
$stopTriggerSat = New-ScheduledTaskTrigger -Weekly -DaysOfWeek Saturday -At 10:35AM
Register-ScheduledTask -TaskName "TradingApp-Stop" -Action $stopAction -Trigger @($stopTriggerWeek, $stopTriggerSat) `
    -Settings $settings -Description "Stop trading app after market hours (and Saturday screening window)" -RunLevel Highest | Out-Null

# Never sleep while plugged in. Battery timeout is deliberately left alone, so unplugged
# behaviour is unchanged. Revert with: powercfg /change standby-timeout-ac 15
powercfg /change standby-timeout-ac 0

Write-Host "Tasks created." -ForegroundColor Green

# --- Verify the settings actually stuck (they silently did not, pre-2026-08-22) ---
$failed = $false
foreach ($name in @("TradingApp-Start", "TradingApp-Stop")) {
    $s = (Get-ScheduledTask -TaskName $name).Settings
    foreach ($check in @(
        @{ Label = "WakeToRun";                  Actual = $s.WakeToRun;                  Want = $true  },
        @{ Label = "StartWhenAvailable";         Actual = $s.StartWhenAvailable;         Want = $true  },
        @{ Label = "DisallowStartIfOnBatteries"; Actual = $s.DisallowStartIfOnBatteries; Want = $false },
        @{ Label = "StopIfGoingOnBatteries";     Actual = $s.StopIfGoingOnBatteries;     Want = $false }
    )) {
        if ($check.Actual -ne $check.Want) {
            Write-Host "  FAIL $name : $($check.Label) is $($check.Actual), expected $($check.Want)" -ForegroundColor Red
            $failed = $true
        }
    }
}

if ($failed) {
    Write-Host "`nTask settings did NOT apply. The app will miss trading days. Fix before relying on it." -ForegroundColor Red
    exit 1
}

Write-Host "Verified: wake-to-run on, battery restrictions off, no sleep on AC." -ForegroundColor Green
Get-ScheduledTask -TaskName "TradingApp-*" | Format-Table TaskName, State -AutoSize
