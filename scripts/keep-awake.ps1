# Keeps Windows from sleeping while the trading app builds and runs (B-173).
#
# The 09:00 TradingApp-Start task wakes the laptop (WakeToRun), but this machine uses Modern
# Standby, which lets it fall asleep again once the task is under way. On 13 Oct 2026 the build
# that should take ~2 minutes ran from 09:02 to 10:45, and the app came up at 11:43, so every
# morning job was missed. start-app.bat launches this before the build.
#
# It asks Windows for "system required" (SetThreadExecutionState), which blocks IDLE sleep only.
# Closing the lid or pressing the power button still sleeps the machine, and the screen may
# still turn off. The request lasts exactly as long as this process, which exits on its own once
# nothing has listened on port 8080 for $IdleExitMinutes (so after the 15:45 stop task, or a
# failed start). One copy at a time: a second launch sees the first and exits.

param(
    [int]$IdleExitMinutes = 20,
    [int]$PollSeconds = 30,
    [int]$Port = 8080
)

$mutex = $null
try {
    $mutex = New-Object System.Threading.Mutex($false, 'Global\IntradayAppKeepAwake')
    if (-not $mutex.WaitOne(0)) { exit 0 }
} catch {
    # A mutex we cannot create is not a reason to let the machine sleep; carry on without it.
    $mutex = $null
}

Add-Type -Namespace IntradayApp -Name Power -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll")]
public static extern uint SetThreadExecutionState(uint esFlags);
'@

$log = Join-Path $PSScriptRoot '..\logs\keep-awake.log'
function Note([string]$text) {
    try { Add-Content -Path $log -Value ("{0:yyyy-MM-dd HH:mm:ss} [pid {1}] {2}" -f (Get-Date), $PID, $text) } catch {}
}

$ES_CONTINUOUS = [uint32]2147483648      # 0x80000000
$ES_SYSTEM_REQUIRED = [uint32]1
# Returns the previous state, or 0 when Windows refused the request - so a 0 here is the one
# line that explains a laptop that still slept.
$previous = [IntradayApp.Power]::SetThreadExecutionState($ES_CONTINUOUS -bor $ES_SYSTEM_REQUIRED)
if ($previous -eq 0) { Note "Windows REFUSED the keep-awake request; the machine may still sleep." }
else { Note "Holding the machine awake (idle sleep blocked) until nothing has listened on port $Port for $IdleExitMinutes min." }
$started = Get-Date

# Counted from launch, so the build (no listener yet) and the gap while start-app.bat swaps the
# old instance for the new one are both covered.
$lastSeen = Get-Date
try {
    while ($true) {
        if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
            $lastSeen = Get-Date
        } elseif (((Get-Date) - $lastSeen).TotalMinutes -ge $IdleExitMinutes) {
            break
        }
        Start-Sleep -Seconds $PollSeconds
    }
} finally {
    [IntradayApp.Power]::SetThreadExecutionState($ES_CONTINUOUS) | Out-Null
    Note ("Released after {0:N0} min." -f ((Get-Date) - $started).TotalMinutes)
    if ($mutex) { $mutex.ReleaseMutex() }
}
