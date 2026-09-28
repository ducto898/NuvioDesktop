<#
.SYNOPSIS
  Play a test clip in the dev build and record refresh-rate / frame-timing measurements (SPEC P2-9).

.DESCRIPTION
  1. Starts observer.exe (read-only, 50 ms) for the Windows rate/HDR/registry mode before, during, after.
  2. Launches the dev build through scripts/run-dev.ps1 (isolated profile) in upstream's smoke-player
     mode (NUVIO_DESKTOP_SMOKE_PLAYER_URL = file:///<clip>) with NUVIO_RR_MEASURE=1, so the native
     sampler writes its log into the run folder.
  3. After file-loaded: optional F11 fullscreen, optional PresentMon (UAC prompt) and nvidia-smi power,
     optional key actions; plays -Seconds; closes the window normally.
  4. Writes measurements/<stamp>-<clip>[-label]/summary.json and prints it.
  Exit 1 if the app did not start/load, or the desktop is not back at ~279.961 Hz afterwards
  (it then runs restore.exe and says so), or the official %APPDATA%\Nuvio was written.

  -Kill ends the run with a hard kill of the app process (java.exe) instead of a normal close.
  -Compare <dir,dir,...> instead compares summary.json files for repeatability (SPEC P2-10).
  -Cadence <dir> instead recomputes the PresentMon cadence section of an existing run folder (SPEC P2b-4).

  Phase 2b spike knobs: -Opts "k=v;k=v" (NUVIO_RR_MEASURE_OPTS, mpv options set before the file loads) and
  -HideOverlay (NUVIO_RR_MEASURE_HIDE_OVERLAY=1, hides the WebView2 controls overlay for the run).
  -PresentMonCsv <csv> slices this run (app pid, file-loaded + 6 s .. close - 1 s) out of one long elevated capture.

  Phase 4 (the feature): -Feature sets NUVIO_RR_ENABLE=1; its log is the run folder's refresh-rate.log and
  summary.json gets a "feature" section. -Fault <kind> sets NUVIO_RR_FAULT (measure runs only; kinds: enumerate,
  display-not-found, switch-api, settle-timeout, slow-settle, verify-mismatch, restore-failed, unexpected; Phase 5:
  timing-set = the interpolation set fails at the hook, drop-mode = the temporary mode is dropped at 20 s and 50 s).
  Phase 5: summary.json mpv.after5s = counters from 5 s of playback to the end (P2b-13 / P5-12 rule), speed
  corrections, and the timing options mpv reports.
  -CloseAfterSwitchMs <ms> closes the window <ms> after the feature's switch call (dispose-mid-switch, P4-12).

  Phase 6 (the setting): NUVIO_RR_ENABLE is the override ("1" on, "0" off, unset = the "Match display refresh rate"
  setting). -Feature passes "1"; -Setting on|off writes the setting into the DEV profile's nuvio_refresh_rate store
  before launch and leaves NUVIO_RR_ENABLE unset (-Setting absent deletes that store: the fresh-profile case); with neither, "0" is passed, so a baseline run stays off whatever
  the dev profile holds. -EnableEnv 0|1 passes that value explicitly (e.g. -EnableEnv 0 -Setting on, P6-7).
  Fault kind enable-upcall: the H2 upcall fails, so the player must play without the feature (P6-10).
  summary.json "enable" = the env passed, the store file after the run, the enable/upcall lines of the log.

.PARAMETER Actions
  Comma list of key@second (seconds after file-loaded): space (pause toggle), right/left (seek),
  mouse (wiggle the cursor over the window so the controls show), f11. Example: 'space@40,space@60'.
#>
param(
    [string]$Clip,
    [int]$Seconds = 120,
    [switch]$Fullscreen,
    [switch]$PresentMon,
    [switch]$Power,
    [switch]$Kill,
    [string]$Sync,
    [int]$SwitchHz = 0,
    [string]$Actions = '',
    [string]$Label = '',
    [string[]]$Compare,
    [string]$Opts = '',
    [switch]$HideOverlay,
    [string]$Cadence = '',
    # A long-running elevated PresentMon capture (--process_name java.exe --date_time) to slice this run out of,
    # instead of one UAC prompt per run (Phase 2b).
    [string]$PresentMonCsv = '',
    # Live rate expected before/after. Only for runs where switcher.exe holds a mode around the run (P2-19);
    # the registry mode must still be 280 before/during/after in every run.
    [double]$ExpectHz = 279.961,
    [switch]$Feature,
    [string]$Fault = '',
    [int]$CloseAfterSwitchMs = -1,
    [ValidateSet('', 'on', 'off', 'absent')][string]$Setting = '',
    [ValidateSet('', '0', '1')][string]$EnableEnv = ''
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$root = Split-Path -Parent $repo
$tools = Join-Path $root 'tools'
$rrTools = Join-Path $tools 'rr-tools'
$measureRoot = Join-Path $repo 'measurements'

function Median([double[]]$v) {
    if (-not $v -or $v.Count -eq 0) { return $null }
    $s = $v | Sort-Object
    if ($s.Count % 2) { return $s[[int][math]::Floor($s.Count / 2)] }
    return ($s[$s.Count / 2 - 1] + $s[$s.Count / 2]) / 2
}
function Pct([double[]]$v, [double]$p) {
    if (-not $v -or $v.Count -eq 0) { return $null }
    $s = $v | Sort-Object
    return $s[[int][math]::Min($s.Count - 1, [math]::Floor($p * $s.Count))]
}

# PresentMon cadence of the player's swapchain (java.exe), SPEC P2b-4. Two present patterns exist:
#  per-frame  (audio sync: one present per video frame) => MsBetweenDisplayChange = how long each frame was held;
#  per-refresh (display-sync modes: one present per refresh, the same frame repeated) => every interval should be
#   exactly 1 refresh; each longer interval is a missed refresh that lengthens one frame (and mpv then shortens
#   another), so frames-at-ideal >= 1 - 2 * missedRefreshEvents / frames (a bound, combined with mpv's counters).
function Get-Cadence([string]$csvPath, [double]$refreshHz, [double]$fps) {
    $rows = @(Import-Csv $csvPath | Where-Object { $_.Application -eq 'java.exe' })
    if ($rows.Count -lt 10 -or $refreshHz -le 0 -or $fps -le 0) { return $null }
    $period = 1000.0 / $refreshHz
    $frameMs = 1000.0 / $fps
    $ideal = [int][math]::Round($frameMs / $period)
    $dc = @($rows | Where-Object { $_.MsBetweenDisplayChange -and $_.MsBetweenDisplayChange -ne 'NA' } |
        ForEach-Object { [double]$_.MsBetweenDisplayChange } | Where-Object { $_ -gt 0 })
    $seconds = if ($rows[0].PSObject.Properties['TimeInMs']) { ([double]$rows[-1].TimeInMs - [double]$rows[0].TimeInMs) / 1000.0 } else {
        $parse = { param($v) $d = $v.LastIndexOf('.'); if ($d -gt 0 -and $v.Length - $d -gt 8) { $v = $v.Substring(0, $d + 8) }; [datetime]::Parse($v) }
        ((& $parse $rows[-1].TimeInDateTime) - (& $parse $rows[0].TimeInDateTime)).TotalSeconds
    }
    $presentRate = if ($seconds -gt 0) { $rows.Count / $seconds } else { 0 }
    $hist = [ordered]@{}
    $dc | ForEach-Object { [int][math]::Round($_ / $period) } | Group-Object | Sort-Object { [int]$_.Name } |
        ForEach-Object { $hist["$($_.Name)"] = $_.Count }
    $inPresent = @($rows | Where-Object { $_.MsInPresentAPI -and $_.MsInPresentAPI -ne 'NA' } | ForEach-Object { [double]$_.MsInPresentAPI })
    $result = [ordered]@{
        refreshHz = $refreshHz; fps = $fps; idealRefreshesPerFrame = $ideal; seconds = [math]::Round($seconds, 1)
        presentsPerSecond = [math]::Round($presentRate, 2); displayChanges = $dc.Count; histogramRefreshes = $hist
        msInPresentApiMedian = Median $inPresent; msInPresentApiP95 = Pct $inPresent 0.95
        presentModes = [ordered]@{}
    }
    $rows | Group-Object PresentMode | ForEach-Object { $result.presentModes[$_.Name] = $_.Count }
    if ($presentRate -gt 2 * $fps) {
        $result.pattern = 'per-refresh'
        $missedEvents = @($dc | Where-Object { [math]::Round($_ / $period) -gt 1 }).Count
        $missedRefreshes = ($dc | ForEach-Object { [math]::Max(0, [math]::Round($_ / $period) - 1) } | Measure-Object -Sum).Sum
        $frames = $seconds * $fps
        $result.missedRefreshEvents = $missedEvents
        $result.missedRefreshes = [int]$missedRefreshes
        $result.pctIntervalsOneRefresh = [math]::Round(100.0 * ($dc.Count - $missedEvents) / [math]::Max(1, $dc.Count), 3)
        $result.pctFramesAtIdealLowerBound = [math]::Round([math]::Max(0, 100.0 * (1 - 2 * $missedEvents / [math]::Max(1, $frames))), 3)
    } else {
        $result.pattern = 'per-frame'
        $atIdeal = @($dc | Where-Object { [math]::Round($_ / $period) -eq $ideal }).Count
        $mean = ($dc | Measure-Object -Average).Average
        $std = [math]::Sqrt((($dc | ForEach-Object { ($_ - $mean) * ($_ - $mean) } | Measure-Object -Sum).Sum) / $dc.Count)
        $result.pctFramesAtIdeal = [math]::Round(100.0 * $atIdeal / $dc.Count, 2)
        $result.holdMsMean = [math]::Round($mean, 3); $result.holdMsStd = [math]::Round($std, 3)
        $result.holdMsMeanAbsDev = [math]::Round(($dc | ForEach-Object { [math]::Abs($_ - $frameMs) } | Measure-Object -Average).Average, 3)
    }
    return $result
}

# ---------------------------------------------------------------- cadence mode (P2b-4)
if ($Cadence) {
    $dir = if (Test-Path $Cadence) { $Cadence } else { Join-Path $measureRoot $Cadence }
    $sum = Get-Content (Join-Path $dir 'summary.json') -Raw | ConvertFrom-Json
    $hz = Median (@(Import-Csv (Join-Path $dir 'observer.csv') | Where-Object { $_.event -eq 'tick' } | ForEach-Object { [double]$_.hz }))
    Get-Cadence (Join-Path $dir 'presentmon.csv') $hz ([double]$sum.mpv.containerFps) | ConvertTo-Json -Depth 4
    exit 0
}

# ---------------------------------------------------------------- compare mode (P2-10)
if ($Compare) {
    $dirs = $Compare | ForEach-Object { $_ -split ',' } | Where-Object { $_ }
    $runs = foreach ($d in $dirs) {
        $p = if (Test-Path (Join-Path $d 'summary.json')) { Join-Path $d 'summary.json' } else { Join-Path $measureRoot "$d\summary.json" }
        Get-Content $p -Raw | ConvertFrom-Json
    }
    $fail = @()
    $dfps = $runs | ForEach-Object { [double]$_.mpv.displayFpsMedian }
    $spread = ($dfps | Measure-Object -Maximum -Minimum)
    if ($spread.Maximum - $spread.Minimum -gt 0.05) { $fail += "display-fps spread $($spread.Maximum - $spread.Minimum)" }
    foreach ($k in 'frameDrops', 'decoderDrops', 'mistimed', 'delayed') {
        $raw = @($runs | ForEach-Object { $_.mpv.counters.$k })
        $nulls = @($raw | Where-Object { $null -eq $_ }).Count
        if ($nulls -eq $raw.Count) { Write-Host "    $k : na in all runs (property unavailable in this sync mode)"; continue }
        if ($nulls -gt 0) { $fail += "$k is na in $nulls of $($raw.Count) runs"; continue }
        $v = $raw | ForEach-Object { [double]$_ }
        $m = $v | Measure-Object -Maximum -Minimum
        $allowed = [math]::Max(2, 0.2 * $m.Maximum)
        if ($m.Maximum - $m.Minimum -gt $allowed) { $fail += "$k differs: $($v -join ' / ') (allowed $allowed)" }
    }
    foreach ($r in $runs) {
        foreach ($phase in 'before', 'after') {
            if ([math]::Abs([double]$r.windows.$phase.hz - $ExpectHz) -gt 0.01) { $fail += "$($r.run) $phase hz $($r.windows.$phase.hz)" }
        }
        if ($r.windows.during.distinctHz.Count -ne 1 -or [math]::Abs([double]$r.windows.during.distinctHz[0] - $ExpectHz) -gt 0.01) {
            $fail += "$($r.run) during hz $($r.windows.during.distinctHz -join ',')"
        }
    }
    $runs | ForEach-Object {
        '{0,-45} dfps={1,9:n4} drops={2,4} dec={3,4} mistimed={4,4} delayed={5,4} hz={6}' -f $_.run, [double]$_.mpv.displayFpsMedian,
            $_.mpv.counters.frameDrops, $_.mpv.counters.decoderDrops, $_.mpv.counters.mistimed, $_.mpv.counters.delayed,
            ($_.windows.during.distinctHz -join ',')
    }
    if ($fail) { Write-Host "COMPARE FAIL:`n  $($fail -join "`n  ")"; exit 1 }
    Write-Host "COMPARE PASS ($($runs.Count) runs)"; exit 0
}

# ---------------------------------------------------------------- measurement run
if (-not $Clip) { throw 'give -Clip <name in testdata> or a path' }
$clipPath = if (Test-Path $Clip) { (Resolve-Path $Clip).Path } else { Join-Path $repo "testdata\$Clip.mkv" }
if (-not (Test-Path $clipPath)) { throw "clip not found: $clipPath" }
foreach ($exe in 'observer.exe', 'restore.exe') {
    if (-not (Test-Path (Join-Path $rrTools $exe))) { throw "$exe missing: run scripts\rr-tools\build.ps1" }
}
$presentMonExe = Get-ChildItem $tools -Filter 'PresentMon-*-x64.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($PresentMon -and -not $presentMonExe) { throw "PresentMon not found in $tools" }

$clipName = [IO.Path]::GetFileNameWithoutExtension($clipPath)
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runName = "$stamp-$clipName" + $(if ($Label) { "-$Label" } else { '' })
$out = Join-Path $measureRoot $runName
New-Item -ItemType Directory -Force $out | Out-Null
$ignore = Join-Path $measureRoot '.gitignore'
if (-not (Test-Path $ignore)) { Set-Content -Encoding ascii $ignore "*`n!.gitignore" }
Write-Host "run: $runName"

# Wake the monitor first: after the Windows display timeout the panel is off, and presenting to it gives
# ~500 drops + audio underruns per 30 s even with the feature off (measured 2026-09-28 16:23 vs 16:25).
# A 1 px relative mouse move there and back is enough; the app keeps the display awake while playing.
if (-not ('RrWake' -as [type])) {
    Add-Type -Namespace '' -Name RrWake -MemberDefinition '[DllImport("user32.dll")] public static extern void mouse_event(uint f, int dx, int dy, uint d, System.UIntPtr e);'
}
[RrWake]::mouse_event(1, 1, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 200; [RrWake]::mouse_event(1, -1, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Seconds 2

$officialProfile = Join-Path $env:APPDATA 'Nuvio'
$officialLocal = Join-Path $env:LOCALAPPDATA 'Nuvio'   # incl. WebView2 (see run-dev.ps1)
$startTime = Get-Date
$observer = Start-Process (Join-Path $rrTools 'observer.exe') -ArgumentList "`"$out\observer.csv`"" -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$out\observer.txt"
Start-Sleep -Milliseconds 800

# Start in the requested window mode: the app restores fullscreen from the DEV profile's window-state
# file, and F11 cannot leave a restored fullscreen. Only the dev profile (run-dev.ps1 default) is edited.
$windowState = Join-Path $root 'devprofile\Roaming\Nuvio\nuvio_window_state.properties'
$wantFs = if ($Fullscreen) { 'true' } else { 'false' }
if (Test-Path $windowState) {
    $lines = @(Get-Content $windowState | Where-Object { $_ -notmatch '^was_fullscreen=' }) + "was_fullscreen=$wantFs"
    Set-Content -Encoding ascii $windowState $lines
} else {
    New-Item -ItemType Directory -Force (Split-Path $windowState) | Out-Null
    Set-Content -Encoding ascii $windowState "was_fullscreen=$wantFs"
}

# Phase 6 (P6-8): the setting goes into the DEV profile's store only (never the official one).
$settingStore = Join-Path $root 'devprofile\Roaming\Nuvio\nuvio_refresh_rate.properties'
if ($Setting -eq 'absent') {
    Remove-Item $settingStore -ErrorAction SilentlyContinue   # fresh-profile case (P6-5)
} elseif ($Setting) {
    New-Item -ItemType Directory -Force (Split-Path $settingStore) | Out-Null
    Set-Content -Encoding ascii $settingStore "match_display_refresh_rate=$(if ($Setting -eq 'on') { 'true' } else { 'false' })"
}
$enableValue = if ($EnableEnv) { $EnableEnv } elseif ($Feature) { '1' } elseif ($Setting) { '' } else { '0' }

$savedEnv = @{}
$envSet = @{
    NUVIO_RR_MEASURE = '1'; NUVIO_RR_MEASURE_DIR = $out
    NUVIO_RR_MEASURE_SYNC = $Sync; NUVIO_RR_MEASURE_SWITCH_HZ = $(if ($SwitchHz) { "$SwitchHz" } else { '' })
    NUVIO_DESKTOP_SMOKE_PLAYER_URL = 'file:///' + ($clipPath -replace '\\', '/')
    NUVIO_RR_MEASURE_IPC = $(if ($Actions) { '1' } else { '' })
    NUVIO_RR_MEASURE_OPTS = $Opts; NUVIO_RR_MEASURE_HIDE_OVERLAY = $(if ($HideOverlay) { '1' } else { '' })
    NUVIO_RR_ENABLE = $enableValue; NUVIO_RR_FAULT = $Fault
}
foreach ($k in $envSet.Keys) { $savedEnv[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $envSet[$k]) }
try {
    $launcher = Start-Process pwsh -ArgumentList '-NoProfile', '-File', "`"$repo\scripts\run-dev.ps1`"" -PassThru -WindowStyle Minimized `
        -RedirectStandardOutput "$out\app-stdout.txt" -RedirectStandardError "$out\app-stderr.txt"
} finally {
    foreach ($k in $savedEnv.Keys) { [Environment]::SetEnvironmentVariable($k, $savedEnv[$k]) }
}

$problems = @()
$log = $null; $appPid = $null; $loadedAt = $null; $closedEarlyAt = $null
$featureLog = Join-Path $out 'refresh-rate.log'
$deadline = (Get-Date).AddSeconds(300)
while ((Get-Date) -lt $deadline -and -not $loadedAt) {
    Start-Sleep -Milliseconds $(if ($CloseAfterSwitchMs -ge 0) { 20 } else { 500 })
    if ($launcher.HasExited) { break }
    $log = Get-ChildItem $out -Filter 'nuvio-rr-*.log' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $log) { continue }
    $text = Get-Content $log.FullName -Raw -ErrorAction SilentlyContinue
    if (-not $appPid -and $text -match 'start: pid=(\d+)') { $appPid = [int]$Matches[1] }
    if ($CloseAfterSwitchMs -ge 0 -and $appPid -and (Test-Path $featureLog) -and
        (Get-Content $featureLog -Raw -ErrorAction SilentlyContinue) -match 'CDS_FULLSCREEN\) = 0') {
        # P4-12: close the window while the switch/settle is (probably) still running.
        Start-Sleep -Milliseconds $CloseAfterSwitchMs
        $closedEarlyAt = Get-Date
        $early = Get-Process -Id $appPid -ErrorAction SilentlyContinue
        if ($early) { [void]$early.CloseMainWindow() }
        Add-Content "$out\actions.txt" "$($closedEarlyAt.ToString('HH:mm:ss.fff')) close $CloseAfterSwitchMs ms after the switch call"
        break
    }
    if ($text -match '\bE file-loaded\b') { $loadedAt = Get-Date }
}
if (-not $loadedAt -and -not $closedEarlyAt) { $problems += 'app did not reach file-loaded within 300 s (see app-stdout.txt)' }
$app = if ($appPid) { Get-Process -Id $appPid -ErrorAction SilentlyContinue } else { $null }

$powerProc = $null; $pmProc = $null
if ($loadedAt -and $app -and -not $closedEarlyAt) {
    Write-Host "file loaded (pid $appPid); playing $Seconds s"
    $shell = New-Object -ComObject WScript.Shell
    if (-not ('RrWin' -as [type])) {
        Add-Type -Namespace '' -Name RrWin -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
[DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
'@
    }
    # Windows refuses SetForegroundWindow from a background process unless it just "had input": a synthetic
    # Alt press satisfies that rule, then the window can be activated and F11 goes to it.
    $focusApp = {
        $app.Refresh()
        [RrWin]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero); [RrWin]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
        [void][RrWin]::SetForegroundWindow($app.MainWindowHandle); Start-Sleep -Milliseconds 300
    }
    # Pause/seek go to mpv's JSON IPC pipe (NUVIO_RR_MEASURE_IPC), not as keys: keys never reached the player.
    $pipeName = "nuvio-rr-$appPid"
    $mpvCommand = {
        param([string]$json)
        try {
            $pipe = New-Object System.IO.Pipes.NamedPipeClientStream('.', $pipeName, [System.IO.Pipes.PipeDirection]::InOut)
            $pipe.Connect(2000)
            $w = New-Object System.IO.StreamWriter($pipe); $w.AutoFlush = $true; $w.WriteLine($json)
            $reply = (New-Object System.IO.StreamReader($pipe)).ReadLine(); $pipe.Dispose(); return $reply
        } catch { return "ipc error: $($_.Exception.Message)" }
    }
    # The app remembers fullscreen from its last exit, so force the requested mode (it changes mpv's timing:
    # display-resample measured ~6 Hz windowed vs ~191 Hz fullscreen). Current mode = latest sample's fs=.
    Start-Sleep -Seconds 2
    $fsNow = (Select-String -Path $log.FullName -Pattern '^\S+ S .* fs=(yes|no)' | Select-Object -Last 1).Matches.Groups[1].Value
    if (($fsNow -eq 'yes') -ne [bool]$Fullscreen) {
        & $focusApp; $shell.SendKeys('{F11}')
        Add-Content "$out\actions.txt" "$(Get-Date -Format 'HH:mm:ss.fff') F11 (window was fs=$fsNow, requested fullscreen=$([bool]$Fullscreen))"
        Start-Sleep -Seconds 2
    }
    if ($Power) {
        # One-shot query per second from a helper shell (nvidia-smi -lms buffers and loses data when stopped).
        $powerLoop = "`$next = Get-Date; while (-not (Test-Path '$out\power.stop')) { nvidia-smi --query-gpu=timestamp,power.draw,clocks.gr,clocks.mem,utilization.gpu,pstate --format=csv,noheader,nounits | Add-Content '$out\power.csv'; `$next = `$next.AddSeconds(1); `$w = (`$next - (Get-Date)).TotalMilliseconds; if (`$w -gt 0) { Start-Sleep -Milliseconds `$w } }"
        $powerProc = Start-Process pwsh -ArgumentList '-NoProfile', '-Command', $powerLoop -PassThru -WindowStyle Hidden
    }
    if ($PresentMon) {
        $pmSeconds = [math]::Max(10, $Seconds - 8)
        try {
            $pmProc = Start-Process $presentMonExe.FullName -Verb RunAs -PassThru -WindowStyle Minimized -ArgumentList `
                '--output_file', "`"$out\presentmon.csv`"", '--timed', $pmSeconds, '--terminate_after_timed', '--no_console_stats',
                '--session_name', 'NuvioRR', '--stop_existing_session'
        } catch {
            $problems += "PresentMon not started (UAC declined?): $($_.Exception.Message)"
        }
    }
    $plan = @()
    foreach ($a in ($Actions -split ',' | Where-Object { $_ })) {
        $k, $t = $a -split '@'; $plan += [pscustomobject]@{ Key = $k.Trim().ToLower(); At = [double]$t; Done = $false }
    }
    Add-Type -AssemblyName System.Windows.Forms
    while (((Get-Date) - $loadedAt).TotalSeconds -lt $Seconds) {
        if ($app.HasExited) { $problems += 'app exited during the run'; break }
        $elapsed = ((Get-Date) - $loadedAt).TotalSeconds
        foreach ($step in $plan | Where-Object { -not $_.Done -and $_.At -le $elapsed }) {
            $step.Done = $true
            $result = ''
            switch ($step.Key) {
                'space' { $result = & $mpvCommand '{"command":["cycle","pause"]}' }
                'right' { $result = & $mpvCommand '{"command":["seek",10,"relative"]}' }
                'left' { $result = & $mpvCommand '{"command":["seek",-10,"relative"]}' }
                'f11' { & $focusApp; $shell.SendKeys('{F11}') }
                'mouse' {
                    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
                    for ($i = 0; $i -lt 10; $i++) {
                        [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point(($b.Width / 2 + 40 * ($i % 2)), ($b.Height / 2))
                        Start-Sleep -Milliseconds 100
                    }
                }
            }
            Add-Content "$out\actions.txt" ("{0} {1}@{2:n1}s {3}" -f (Get-Date -Format 'HH:mm:ss.fff'), $step.Key, $elapsed, $result)
        }
        Start-Sleep -Milliseconds 200
    }
}

# Close normally (onCloseRequest -> exitApplication), then force if needed.
$closeAt = if ($closedEarlyAt) { $closedEarlyAt } else { Get-Date }
$forced = $false
if ($Kill -and $app -and -not $app.HasExited) {
    # Hard kill (TerminateProcess), for crash/kill restore tests: no cleanup code runs.
    Add-Content "$out\actions.txt" "$(Get-Date -Format 'HH:mm:ss.fff') kill java pid $($app.Id)"
    Stop-Process -Id $app.Id -Force
    [void]$app.WaitForExit(10000)
} elseif ($app -and -not $app.HasExited) {
    [void]$app.CloseMainWindow()
    if (-not $app.WaitForExit(20000)) { Stop-Process -Id $app.Id -Force; $forced = $true; $problems += 'app did not close within 20 s (killed)' }
}
if (-not $launcher.HasExited) { [void]$launcher.WaitForExit(30000) }
if (-not $launcher.HasExited) { Stop-Process -Id $launcher.Id -Force }
if ($pmProc -and -not $pmProc.HasExited) { [void]$pmProc.WaitForExit(15000) }
if ($powerProc) {
    New-Item -ItemType File "$out\power.stop" -Force | Out-Null
    if (-not $powerProc.WaitForExit(5000)) { Stop-Process -Id $powerProc.Id -Force }
}
Start-Sleep -Seconds 3
Stop-Process -Id $observer.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 300

# ---------------------------------------------------------------- summarize
$obs = Import-Csv "$out\observer.csv"
$toTime = { param($w) [datetime]::ParseExact($w, 'HH:mm:ss.fff', $null) }
$first = $obs | Select-Object -First 1
$last = $obs | Select-Object -Last 1
$during = if ($loadedAt) {
    $a = $loadedAt.ToString('HH:mm:ss.fff'); $z = $closeAt.ToString('HH:mm:ss.fff')
    $obs | Where-Object { $_.wall -ge $a -and $_.wall -lt $z -and $_.event -eq 'tick' }
} else { @() }
$winState = { param($r) [ordered]@{ hz = [double]$r.hz; rational = "$($r.num)/$($r.den)"; regHz = [int]$r.reg_hz; hdr = [int]$r.hdr; bpc = [int]$r.bpc } }

$samples = @()
$lines = if ($log) { Get-Content $log.FullName } else { @() }
$afterLoad = $false
foreach ($l in $lines) {
    if ($l -match '\bE file-loaded\b') { $afterLoad = $true }
    if (-not $afterLoad -or $l -notmatch '^\S+ S ') { continue }
    # Samples from the close on show the restore, not playback (Phase 5: the restore to 280 mid-sample counted as drops).
    if ((& $toTime ($l.Substring(0, 12))).TimeOfDay -ge $closeAt.TimeOfDay) { continue }
    $o = @{}
    foreach ($m in [regex]::Matches($l, '(\S+?)=(\S+)')) { $o[$m.Groups[1].Value] = $m.Groups[2].Value }
    if ($o['time-pos'] -and $o['time-pos'] -ne 'na') { $samples += [pscustomobject]$o }
}
function Nums($name) { $samples | ForEach-Object { $_.$name } | Where-Object { $_ -and $_ -ne 'na' } | ForEach-Object { [double]$_ } }
function Delta($name) {
    $v = @(Nums $name); if ($v.Count -lt 2) { return $null }; return $v[-1] - $v[0]
}
$minutes = if ($samples.Count -gt 1) { ([double]$samples[-1].'time-pos' - [double]$samples[0].'time-pos') / 60 } else { 0 }
$gaps = @()
$times = $lines | Where-Object { $_ -match '^\S+ S ' } | ForEach-Object { & $toTime ($_.Substring(0, 12)) }
for ($i = 1; $i -lt $times.Count; $i++) { $gaps += ($times[$i] - $times[$i - 1]).TotalSeconds }

$summary = [ordered]@{
    run = $runName; clip = $clipName; seconds = $Seconds; fullscreenRequested = [bool]$Fullscreen; sync = $Sync; switchHz = $SwitchHz
    actions = $Actions; forcedClose = $forced; opts = $Opts; hideOverlay = [bool]$HideOverlay
    windows = [ordered]@{
        before = & $winState $first
        during = [ordered]@{
            distinctHz = @($during | ForEach-Object { [double]$_.hz } | Sort-Object -Unique)
            distinctHdr = @($during | ForEach-Object { [int]$_.hdr } | Sort-Object -Unique)
            distinctRegHz = @($during | ForEach-Object { [int]$_.reg_hz } | Sort-Object -Unique)
        }
        after = & $winState $last
        displayChangeEvents = @($obs | Where-Object { $_.event -eq 'WM_DISPLAYCHANGE' } | ForEach-Object { "$($_.wall) $($_.hz)" })
    }
    mpv = [ordered]@{
        samples = $samples.Count; playedMinutes = [math]::Round($minutes, 2)
        maxSampleGapSeconds = if ($gaps) { [math]::Round(($gaps | Measure-Object -Maximum).Maximum, 2) } else { $null }
        containerFps = Median (Nums 'container-fps'); estimatedVfFps = Median (Nums 'estimated-vf-fps')
        displayFpsMedian = Median (Nums 'display-fps'); displayFpsDistinct = @(Nums 'display-fps' | ForEach-Object { [math]::Round($_, 3) } | Sort-Object -Unique)
        estimatedDisplayFps = Median (Nums 'estimated-display-fps'); vsyncJitterMedian = Median (Nums 'vsync-jitter')
        vsyncRatioMedian = Median (Nums 'vsync-ratio')
        videoSync = @($samples | ForEach-Object { $_.'video-sync' } | Sort-Object -Unique)
        displaySyncActive = @($samples | ForEach-Object { $_.'display-sync-active' } | Sort-Object -Unique)
        hwdec = @($samples | ForEach-Object { $_.'hwdec-current' } | Sort-Object -Unique)
        gamma = @($samples | ForEach-Object { $_.gamma } | Sort-Object -Unique)
        fullscreen = @($samples | ForEach-Object { $_.fs } | Sort-Object -Unique)
        counters = [ordered]@{
            frameDrops = Delta 'frame-drop-count'; decoderDrops = Delta 'decoder-frame-drop-count'
            mistimed = Delta 'mistimed-frame-count'; delayed = Delta 'vo-delayed-frame-count'
        }
        sampleCostMs = [ordered]@{ median = Median (Nums 'cost_ms'); max = (@(Nums 'cost_ms') | Measure-Object -Maximum).Maximum }
        pausedSamples = @($samples | Where-Object { $_.pause -eq 'yes' }).Count
    }
}
$perMin = [ordered]@{}
foreach ($k in $summary.mpv.counters.Keys) {
    $c = $summary.mpv.counters[$k]
    $perMin[$k] = if ($null -eq $c -or $minutes -le 0) { $null } else { [math]::Round($c / $minutes, 2) }
}
$summary.mpv.countersPerMinute = $perMin
# P5-12: counted after the first 5 s of playback (P2b-13 rule).
$late = @($samples | Where-Object { [double]$_.'time-pos' -ge 5 })
if ($late.Count -ge 2) {
    $lateMin = ([double]$late[-1].'time-pos' - [double]$late[0].'time-pos') / 60
    $lateDelta = { param($n) $a = $late[0].$n; $b = $late[-1].$n; if ($a -and $b -and $a -ne 'na' -and $b -ne 'na') { [double]$b - [double]$a } else { $null } }
    $dr = & $lateDelta 'frame-drop-count'; $mt = & $lateDelta 'mistimed-frame-count'
    $summary.mpv.after5s = [ordered]@{
        minutes = [math]::Round($lateMin, 2); frameDrops = $dr; mistimed = $mt; delayed = & $lateDelta 'vo-delayed-frame-count'
        dropsPlusMistimedPerMinute = if ($null -ne $dr -and $null -ne $mt -and $lateMin -gt 0) { [math]::Round(($dr + $mt) / $lateMin, 2) } else { $null }
        estimatedDisplayFpsMedian = Median (@($late | ForEach-Object { $_.'estimated-display-fps' } | Where-Object { $_ -and $_ -ne 'na' } | ForEach-Object { [double]$_ }))
    }
}
$speed = { param($n) @($samples | ForEach-Object { $_.$n } | Where-Object { $_ -and $_ -ne 'na' } | ForEach-Object { [double]$_ }) }
$vsc = & $speed 'video-speed-correction'; $asc = & $speed 'audio-speed-correction'
$summary.mpv.speedCorrection = [ordered]@{
    videoMin = ($vsc | Measure-Object -Minimum).Minimum; videoMax = ($vsc | Measure-Object -Maximum).Maximum
    audioMin = ($asc | Measure-Object -Minimum).Minimum; audioMax = ($asc | Measure-Object -Maximum).Maximum
}
$summary.mpv.interpolation = @($samples | ForEach-Object { $_.interpolation } | Sort-Object -Unique)
$summary.mpv.displayFpsOverride = @($samples | ForEach-Object { $_.'display-fps-override' } | Sort-Object -Unique)
$summary.mpv.dropsPlusMistimedPerMinute = if ($null -ne $perMin.frameDrops -and $null -ne $perMin.mistimed) { $perMin.frameDrops + $perMin.mistimed } else { $null }
# Audio underruns after the first 5 s of playback (P2b-13), occlusion/present messages (P2b-10), knob results (P2b-1/2).
$loadWall = ($lines | Where-Object { $_ -match '\bE file-loaded\b' } | Select-Object -First 1)
$underrunLines = @($lines | Where-Object { $_ -match 'Audio device underrun detected' })
$lateUnderruns = if ($loadWall) {
    $t0 = & $toTime ($loadWall.Substring(0, 12))
    @($underrunLines | Where-Object { ((& $toTime ($_.Substring(0, 12))) - $t0).TotalSeconds -gt 5 }).Count
} else { $null }
$summary.mpv.audioUnderruns = [ordered]@{ total = $underrunLines.Count; afterFirst5s = $lateUnderruns }
$summary.mpv.occlusionOrPresentMessages = @($lines | Where-Object { $_ -match '^\S+ M ' -and $_ -match '(?i)occlu|DXGI_STATUS|present.*(fail|error)|device (lost|removed)' } |
    Select-Object -First 20)
$summary.mpv.knobLines = @($lines | Where-Object { $_ -match '^\S+ (opt |hide-overlay: |knob: )' } | Select-Object -First 40 | ForEach-Object { $_.Substring(13) })
$lastFs = if ($samples.Count) { $samples[-1].fs } else { 'na' }   # first ~2 s may predate the F11 toggle
$summary.mpv.fullscreenAtEnd = $lastFs
if (($lastFs -eq 'yes') -ne [bool]$Fullscreen) { $problems += "window mode at end fs=$lastFs, requested fullscreen=$([bool]$Fullscreen)" }
foreach ($phase in 'before', 'after') {
    if ($summary.windows.$phase.regHz -ne 280) { $problems += "registry mode $phase the run: $($summary.windows.$phase.regHz) (expected 280)" }
}
$regDuring = @($summary.windows.during.distinctRegHz)
# Early close (-CloseAfterSwitchMs): no "during" window; observer.csv still has every reg_hz sample.
if (-not $closedEarlyAt -and ($regDuring.Count -ne 1 -or $regDuring[0] -ne 280)) { $problems += "registry mode during run: $($regDuring -join ',') (expected 280)" }
if (Test-Path "$out\power.csv") {
    $pw = Get-Content "$out\power.csv" | ForEach-Object { $c = $_ -split ',\s*'; [pscustomobject]@{ W = [double]$c[1]; Clock = [double]$c[2]; Util = [double]$c[4] } }
    $summary.power = [ordered]@{ samples = @($pw).Count; wattsMedian = Median ($pw.W); clockMedian = Median ($pw.Clock); utilMedian = Median ($pw.Util) }
}
if ($PresentMonCsv -and $appPid -and $loadedAt -and (Test-Path $PresentMonCsv) -and (Get-Item $PresentMonCsv).LastWriteTime -ge $loadedAt) {  # stale capture => no slice (pids get reused)
    $from = $loadedAt.AddSeconds(6); $to = $closeAt.AddSeconds(-1)
    $fs = [IO.File]::Open($PresentMonCsv, 'Open', 'Read', 'ReadWrite')
    $reader = New-Object IO.StreamReader($fs)
    $header = $reader.ReadLine().TrimStart([char]0xFEFF)
    $cols = $header -split ','
    $pidIdx = [array]::IndexOf($cols, 'ProcessID')
    $timeIdx = [array]::FindIndex($cols, [Predicate[string]] { param($c) $c -like 'CPUStart*' })
    $mine = New-Object System.Collections.Generic.List[object]
    while ($null -ne ($row = $reader.ReadLine())) {
        $c = $row -split ','
        if ($c.Count -le $timeIdx -or $c[$pidIdx] -ne "$appPid") { continue }
        $ts = $c[$timeIdx]
        $dot = $ts.LastIndexOf('.'); if ($dot -gt 0 -and $ts.Length - $dot -gt 8) { $ts = $ts.Substring(0, $dot + 8) }  # ns -> 100 ns
        $t = [datetime]::MinValue
        if ([datetime]::TryParse($ts, [ref]$t)) { $mine.Add([pscustomobject]@{ T = $t; Row = $row }) }
    }
    $reader.Dispose()
    # PresentMon 2.6 --date_time is off by a whole timezone offset on this PC (measured +7 h = UTC+7 applied twice).
    # Calibrate per run: the app's last present is just before its close; round the difference to 15 min.
    $offset = [timespan]::Zero
    if ($mine.Count) {
        $diffMin = ($mine[$mine.Count - 1].T - $closeAt).TotalMinutes
        $offset = [timespan]::FromMinutes(15 * [math]::Round($diffMin / 15))
    }
    $kept = New-Object System.Collections.Generic.List[string]
    $kept.Add($header)
    foreach ($m in $mine) { $t = $m.T - $offset; if ($t -ge $from -and $t -le $to) { $kept.Add($m.Row) } }
    $kept | Set-Content -Encoding utf8 "$out\presentmon.csv"
    $summary.presentMonSlice = [ordered]@{ source = $PresentMonCsv; rows = $kept.Count - 1; from = $from.ToString('HH:mm:ss.fff'); to = $to.ToString('HH:mm:ss.fff'); timeColumn = $cols[$timeIdx]; clockOffsetMinutes = $offset.TotalMinutes }
    if ($kept.Count -lt 100) { $problems += "PresentMon slice has only $($kept.Count - 1) rows (capture not running or not flushed?)" }
}
if (Test-Path "$out\presentmon.csv") {
    $pm = Import-Csv "$out\presentmon.csv"
    $modes = $pm | Group-Object Application, PresentMode | Sort-Object Count -Descending |
        ForEach-Object { [ordered]@{ key = $_.Name; count = $_.Count } }
    $javaDc = @($pm | Where-Object { $_.Application -eq 'java.exe' -and $_.MsBetweenDisplayChange -and $_.MsBetweenDisplayChange -ne 'NA' } |
        ForEach-Object { [double]$_.MsBetweenDisplayChange } | Where-Object { $_ -gt 0 })
    $period = 1000.0 / [double]$first.hz
    $offGrid = @($javaDc | Where-Object { $q = $_ / $period; [math]::Abs($q - [math]::Round($q)) * $period -gt 0.5 }).Count
    $summary.presentMon = [ordered]@{
        presentModes = $modes
        javaMsBetweenDisplayChange = [ordered]@{ count = $javaDc.Count; median = Median $javaDc; p05 = Pct $javaDc 0.05; p95 = Pct $javaDc 0.95
            offVsyncGridOver0_5ms = $offGrid; vsyncPeriodMs = [math]::Round($period, 4) }
    }
    $duringHz = Median (@($during | ForEach-Object { [double]$_.hz }))
    if ($duringHz -and $summary.mpv.containerFps) {
        $summary.cadence = Get-Cadence "$out\presentmon.csv" $duringHz ([double]$summary.mpv.containerFps)
    }
}

# Phase 6 enable evidence (P6-5..P6-10).
$enableLines = if (Test-Path $featureLog) { @(Get-Content $featureLog | Where-Object { $_ -match 'player p\d+ created|enable upcall' }) } else { @() }
$summary.enable = [ordered]@{
    env = $enableValue; setting = $Setting
    storeAfter = if (Test-Path $settingStore) { @(Get-Content $settingStore | Where-Object { $_ -notmatch '^#' }) } else { $null }
    featureLogExists = Test-Path $featureLog
    lines = $enableLines
    upcallMs = @($enableLines | ForEach-Object { if ($_ -match '(?:upcall |\) in )([\d.]+) ms') { [double]$Matches[1] } })
}

# Phase 4 feature log (P4-10, P4-19, P4-20) and crash evidence (P4-12).
if ($Feature -or $Setting -eq 'on' -or $EnableEnv -eq '1') {
    $fl = if (Test-Path $featureLog) { @(Get-Content $featureLog) } else { @() }
    $ms = { param($pattern) @($fl | ForEach-Object { if ($_ -match $pattern) { [double]$Matches[1] } }) }
    $firstSwitch = $fl | Where-Object { $_ -match 'CDS_FULLSCREEN\) = ' } | Select-Object -First 1
    $summary.feature = [ordered]@{
        fault = $Fault; closeAfterSwitchMs = $CloseAfterSwitchMs; lines = $fl.Count
        switchCalls = @($fl | Where-Object { $_ -match 'CDS_FULLSCREEN\) = ' } | ForEach-Object { $_.Substring(0, 12) + ' ' + ($_ -replace '^.*ChangeDisplaySettingsExW', 'CDS') })
        restoreCalls = @($fl | Where-Object { $_ -match 'restore = ' } | ForEach-Object { $_.Substring(0, 12) + ' ' + ($_ -replace '^.*restore = ', 'restore = ') })
        settleMs = & $ms 'settled in (\d+) ms'
        hookDoneMs = & $ms 'hook p\d+ done after (\d+) ms'
        switchBeforeFileLoaded = if ($firstSwitch -and $loadWall) { $firstSwitch.Substring(0, 12) -lt $loadWall.Substring(0, 12) } else { $null }
        reasons = @($fl | Where-Object { $_ -match ' K step ' } | ForEach-Object { if ($_ -match 'reasons=(\S*)') { $Matches[1] } })
        modesLine = $fl | Where-Object { $_ -match ' N modes ' } | Select-Object -First 1
    }
}
$hsErr = @(Get-ChildItem $repo, (Join-Path $repo 'composeApp') -Filter 'hs_err_pid*.log' -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -gt $startTime } | ForEach-Object { $_.FullName })
$werEvents = @(try {
        Get-WinEvent -FilterHashtable @{ LogName = 'Application'; ProviderName = 'Application Error', 'Windows Error Reporting'; StartTime = $startTime } -ErrorAction Stop |
            Where-Object { $_.Message -match 'java' } | ForEach-Object { "$($_.TimeCreated.ToString('HH:mm:ss')) $($_.ProviderName) $($_.Id)" }
    } catch { @() })
$summary.crash = [ordered]@{ hsErr = $hsErr; werEvents = $werEvents }
if ($hsErr.Count -or $werEvents.Count) { $problems += "crash evidence: $($hsErr.Count) hs_err file(s), $($werEvents.Count) WER event(s)" }

# Official profile must be untouched (P2-11).
$touched = @($officialProfile, $officialLocal | Where-Object { Test-Path $_ } | ForEach-Object { Get-ChildItem $_ -Recurse -File -ErrorAction SilentlyContinue } |
    Where-Object { $_.LastWriteTime -gt $startTime })
$summary.officialProfileWrites = @($touched | ForEach-Object { $_.FullName })
if ($touched.Count) { $problems += "official profile written: $($touched.Count) file(s)" }

if ([math]::Abs([double]$last.hz - $ExpectHz) -gt 0.01) {
    $problems += "desktop not back at $ExpectHz Hz after the run (was $($last.hz)); ran restore.exe"
    & (Join-Path $rrTools 'restore.exe') | Out-File "$out\restore.txt"
    $summary.restoreRun = Get-Content "$out\restore.txt"
}
$summary.problems = $problems
$summary | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 "$out\summary.json"
Get-Content "$out\summary.json"
if ($problems) { Write-Host "MEASURE PROBLEMS:`n  $($problems -join "`n  ")"; exit 1 }
Write-Host "MEASURE OK: $out"
exit 0
