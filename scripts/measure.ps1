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
    [double]$ExpectHz = 279.961
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
        $v = $runs | ForEach-Object { [double]$_.mpv.counters.$k }
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

$officialProfile = Join-Path $env:APPDATA 'Nuvio'
$startTime = Get-Date
$observer = Start-Process (Join-Path $rrTools 'observer.exe') -ArgumentList "`"$out\observer.csv`"" -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$out\observer.txt"
Start-Sleep -Milliseconds 800

$savedEnv = @{}
$envSet = @{
    NUVIO_RR_MEASURE = '1'; NUVIO_RR_MEASURE_DIR = $out
    NUVIO_RR_MEASURE_SYNC = $Sync; NUVIO_RR_MEASURE_SWITCH_HZ = $(if ($SwitchHz) { "$SwitchHz" } else { '' })
    NUVIO_DESKTOP_SMOKE_PLAYER_URL = 'file:///' + ($clipPath -replace '\\', '/')
}
foreach ($k in $envSet.Keys) { $savedEnv[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $envSet[$k]) }
try {
    $launcher = Start-Process pwsh -ArgumentList '-NoProfile', '-File', "`"$repo\scripts\run-dev.ps1`"" -PassThru -WindowStyle Minimized `
        -RedirectStandardOutput "$out\app-stdout.txt" -RedirectStandardError "$out\app-stderr.txt"
} finally {
    foreach ($k in $savedEnv.Keys) { [Environment]::SetEnvironmentVariable($k, $savedEnv[$k]) }
}

$problems = @()
$log = $null; $appPid = $null; $loadedAt = $null
$deadline = (Get-Date).AddSeconds(300)
while ((Get-Date) -lt $deadline -and -not $loadedAt) {
    Start-Sleep -Milliseconds 500
    if ($launcher.HasExited) { break }
    $log = Get-ChildItem $out -Filter 'nuvio-rr-*.log' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $log) { continue }
    $text = Get-Content $log.FullName -Raw -ErrorAction SilentlyContinue
    if (-not $appPid -and $text -match 'start: pid=(\d+)') { $appPid = [int]$Matches[1] }
    if ($text -match '\bE file-loaded\b') { $loadedAt = Get-Date }
}
if (-not $loadedAt) { $problems += 'app did not reach file-loaded within 300 s (see app-stdout.txt)' }
$app = if ($appPid) { Get-Process -Id $appPid -ErrorAction SilentlyContinue } else { $null }

$powerProc = $null; $pmProc = $null
if ($loadedAt -and $app) {
    Write-Host "file loaded (pid $appPid); playing $Seconds s"
    $shell = New-Object -ComObject WScript.Shell
    if ($Fullscreen) {
        Start-Sleep -Seconds 2
        [void]$shell.AppActivate($appPid); Start-Sleep -Milliseconds 300; $shell.SendKeys('{F11}')
        Start-Sleep -Seconds 2
    }
    if ($Power) {
        $powerProc = Start-Process nvidia-smi -ArgumentList '--query-gpu=timestamp,power.draw,clocks.gr,clocks.mem,utilization.gpu,pstate',
            '--format=csv,noheader,nounits', '-lms', '1000', '-f', "`"$out\power.csv`"" -PassThru -WindowStyle Hidden
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
            [void]$shell.AppActivate($appPid); Start-Sleep -Milliseconds 200
            switch ($step.Key) {
                'space' { $shell.SendKeys(' ') }
                'right' { $shell.SendKeys('{RIGHT}') }
                'left' { $shell.SendKeys('{LEFT}') }
                'f11' { $shell.SendKeys('{F11}') }
                'mouse' {
                    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
                    for ($i = 0; $i -lt 10; $i++) {
                        [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point(($b.Width / 2 + 40 * ($i % 2)), ($b.Height / 2))
                        Start-Sleep -Milliseconds 100
                    }
                }
            }
            Add-Content "$out\actions.txt" ("{0} {1}@{2:n1}s" -f (Get-Date -Format 'HH:mm:ss.fff'), $step.Key, $elapsed)
        }
        Start-Sleep -Milliseconds 200
    }
}

# Close normally (onCloseRequest -> exitApplication), then force if needed.
$closeAt = Get-Date
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
if ($powerProc -and -not $powerProc.HasExited) { Stop-Process -Id $powerProc.Id -Force }
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
    actions = $Actions; forcedClose = $forced
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
    }
}
if (Test-Path "$out\power.csv") {
    $pw = Get-Content "$out\power.csv" | ForEach-Object { $c = $_ -split ',\s*'; [pscustomobject]@{ W = [double]$c[1]; Clock = [double]$c[2]; Util = [double]$c[4] } }
    $summary.power = [ordered]@{ samples = @($pw).Count; wattsMedian = Median ($pw.W); clockMedian = Median ($pw.Clock); utilMedian = Median ($pw.Util) }
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
}

# Official profile must be untouched (P2-11).
$touched = if (Test-Path $officialProfile) { @(Get-ChildItem $officialProfile -Recurse -File | Where-Object { $_.LastWriteTime -gt $startTime }) } else { @() }
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
